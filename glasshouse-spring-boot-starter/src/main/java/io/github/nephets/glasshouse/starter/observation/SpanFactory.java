/*
 * Copyright 2026 the llm-glasshouse authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.nephets.glasshouse.starter.observation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;

import io.github.nephets.glasshouse.core.audit.PayloadRedactor;
import io.github.nephets.glasshouse.core.pricing.CostCalculator;
import io.github.nephets.glasshouse.core.pricing.PriceEntry;
import io.github.nephets.glasshouse.core.pricing.PricingTable;
import io.github.nephets.glasshouse.core.telemetry.GenaiAttributes;
import io.github.nephets.glasshouse.core.telemetry.GenaiSpanFactory;
import io.github.nephets.glasshouse.core.telemetry.SpnNames;
import io.github.nephets.glasshouse.core.telemetry.SwyAttributes;
import io.github.nephets.glasshouse.core.usage.Usage;
import io.github.nephets.glasshouse.starter.store.SpanRingBuffer;
import io.github.nephets.glasshouse.starter.store.SpanRingBuffer.SpanData;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Span 工厂（starter 层）：把 core 的属性口径与 OTel SDK 的 span 生命周期缝在一起。
 *
 * <p><b>核心价值</b>：所有 span 都会同时
 *
 * <ol>
 *   <li>写入真实的 OTel SDK span（可导出到任意 OTLP 后端 → 零锁定）
 *   <li>把轻量快照写入 {@link SpanRingBuffer}（让 README 的 span 树开箱即得）
 *   <li>计算成本并交给归因引擎（埋点与归因不需要业务再调一次 API）
 * </ol>
 *
 * <p>第3 点是架构 ③T03 验收标准第 7 条：「{@code AttributionEngine} 断言 onStop 时自动 record （埋点与归因不需要业务再调一次 API）」。
 */
public final class SpanFactory {

    private static final Logger log = LoggerFactory.getLogger(SpanFactory.class);

    /** instrumentation scope 名称。 */
    public static final String SCOPE_NAME = "io.github.nephets.glasshouse";

    /** instrumentation scope 版本。 */
    public static final String SCOPE_VERSION = "0.1.0";

    private final Tracer tracer;
    private final SpanRingBuffer ringBuffer;
    private final PayloadRedactor redactor;
    private final PricingTable pricingTable;
    private final CostCalculator costCalculator;
    private final boolean recordPayloadFull;
    private final SpanListener listener;

    /**
     * 构造工厂。
     *
     * @param tracer OTel tracer，不可为 null
     * @param ringBuffer span 缓冲，可为 null（只导 OTLP）
     * @param redactor 脱敏器，不可为 null
     * @param pricingTable 价格表，不可为 null
     * @param recordPayloadFull 是否记录完整 payload
     * @param listener span 结束回调；可为 null
     */
    public SpanFactory(
            Tracer tracer,
            SpanRingBuffer ringBuffer,
            PayloadRedactor redactor,
            PricingTable pricingTable,
            boolean recordPayloadFull,
            SpanListener listener) {
        if (tracer == null) {
            throw new NullPointerException("tracer 不能为 null");
        }
        if (redactor == null) {
            throw new NullPointerException("redactor 不能为 null");
        }
        if (pricingTable == null) {
            throw new NullPointerException("pricingTable 不能为 null");
        }
        this.tracer = tracer;
        this.ringBuffer = ringBuffer;
        this.redactor = redactor;
        this.pricingTable = pricingTable;
        this.costCalculator = CostCalculator.getInstance();
        this.recordPayloadFull = recordPayloadFull;
        this.listener = listener;
    }

    /**
     * 按 SPN-001 模板启动一个 span。
     *
     * @param operation 操作名，见 {@link SpnNames#operations()}
     * @param target 目标标识
     * @return span 句柄
     */
    public Handle start(String operation, String target) {
        String spanName = SpnNames.name(operation, target);
        Span span = tracer.spanBuilder(spanName).startSpan();
        span.setAttribute(GenaiAttributes.OPERATION_NAME, operation);
        return new Handle(span, operation, target, spanName);
    }

    /**
     * 取得底层 tracer。
     *
     * @return tracer
     */
    public Tracer tracer() {
        return tracer;
    }

    /**
     * 取得 span 缓冲。
     *
     * @return 缓冲；未配置时为 null
     */
    public SpanRingBuffer ringBuffer() {
        return ringBuffer;
    }

    /**
     * Span 生命周期句柄：记录开始信息，结束时统一收口。
     *
     * <p>把「结束」做成一个对象而不是让调用方自己拼参数，是为了保证 「写 usage 属性 → 写成本属性 → 落缓冲 → 通知归因 → end
     * span」这五步<b>永远一起发生</b>， 不会因为某处 early return 而漏掉成本归因。
     */
    public final class Handle {

        private final Span span;
        private final String operation;
        private final String target;
        private final String spanName;
        private final Instant startTime;
        private final Map<String, Object> bufferedAttributes = new LinkedHashMap<>();
        private String parentSpanId;
        private String status = "UNSET";
        private String errorMessage;

        private Handle(Span span, String operation, String target, String spanName) {
            this.span = span;
            this.operation = operation;
            this.target = target;
            this.spanName = spanName;
            this.startTime = Instant.now();
        }

        /**
         * 设置一个 {@code swy.*} 属性，同时记入缓冲快照。
         *
         * <p>值缺失时<b>不写</b>（契约 EVT-001：禁止 {@code unknown} 占位污染归因统计）。
         *
         * @param key 属性键
         * @param value 属性值，可为 null
         * @return 自身，便于链式调用
         */
        public Handle swy(io.opentelemetry.api.common.AttributeKey<String> key, String value) {
            if (value != null && !value.isBlank()) {
                span.setAttribute(key, value);
                bufferedAttributes.put(key.getKey(), value);
            }
            return this;
        }

        /**
         * 设置一个数值型 {@code swy.*} 属性，同时记入缓冲快照。
         *
         * @param key 属性键
         * @param value 属性值，可为 null
         * @return 自身
         */
        public Handle swyLong(io.opentelemetry.api.common.AttributeKey<Long> key, Long value) {
            if (value != null) {
                span.setAttribute(key, value);
                bufferedAttributes.put(key.getKey(), value);
            }
            return this;
        }

        /**
         * 设置一个布尔型 {@code swy.*} 属性，同时记入缓冲快照。
         *
         * @param key 属性键
         * @param value 属性值，可为 null
         * @return 自身
         */
        public Handle swyBool(
                io.opentelemetry.api.common.AttributeKey<Boolean> key, Boolean value) {
            if (value != null) {
                span.setAttribute(key, value);
                bufferedAttributes.put(key.getKey(), value);
            }
            return this;
        }

        /**
         * 设置一个 {@code gen_ai.*} 属性。
         *
         * @param key 属性键
         * @param value 属性值，可为 null
         * @return 自身
         */
        public Handle genai(io.opentelemetry.api.common.AttributeKey<String> key, String value) {
            if (value != null && !value.isBlank()) {
                span.setAttribute(key, value);
                bufferedAttributes.put(key.getKey(), value);
            }
            return this;
        }

        /**
         * 设置布尔型 {@code gen_ai.*} 属性（如 {@code gen_ai.request.stream}）。
         *
         * @param key 属性键
         * @param value 属性值，可为 null
         * @return 自身
         */
        public Handle genaiBool(
                io.opentelemetry.api.common.AttributeKey<Boolean> key, Boolean value) {
            if (value != null) {
                span.setAttribute(key, value);
                bufferedAttributes.put(key.getKey(), value);
            }
            return this;
        }

        /**
         * 设置数值型 {@code gen_ai.*} 属性。
         *
         * @param key 属性键
         * @param value 属性值，可为 null
         * @return 自身
         */
        public Handle genaiLong(io.opentelemetry.api.common.AttributeKey<Long> key, Long value) {
            if (value != null) {
                span.setAttribute(key, value);
                bufferedAttributes.put(key.getKey(), value);
            }
            return this;
        }

        /**
         * 设置双精度 {@code gen_ai.*} 属性（如温度）。
         *
         * @param key 属性键
         * @param value 属性值，可为 null
         * @return 自身
         */
        public Handle genaiDouble(
                io.opentelemetry.api.common.AttributeKey<Double> key, Double value) {
            if (value != null) {
                span.setAttribute(key, value);
                bufferedAttributes.put(key.getKey(), value);
            }
            return this;
        }

        /**
         * 设置字符串数组型 {@code gen_ai.*} 属性（如结束原因列表）。
         *
         * @param key 属性键
         * @param values 属性值；为 null 或空列表时不写
         * @return 自身
         */
        public Handle genaiStrings(
                io.opentelemetry.api.common.AttributeKey<java.util.List<String>> key,
                java.util.List<String> values) {
            if (values != null && !values.isEmpty()) {
                span.setAttribute(key, List.copyOf(values));
                bufferedAttributes.put(key.getKey(), List.copyOf(values));
            }
            return this;
        }

        /**
         * 记录 Prompt 留痕：写 digest 属性，并按开关写完整内容。
         *
         * <p><b>留痕策略</b>（PRD P0-4）：digest <b>总是</b>写（它不含明文，恒定可追溯）； 完整内容只在显式开启时写，且<b>先脱敏再写</b>。
         *
         * @param rawPayload 渲染后的 prompt 原文，可为 null
         * @return 自身
         */
        public Handle payload(String rawPayload) {
            if (rawPayload == null) {
                return this;
            }
            String digest = redactor.digest(rawPayload);
            span.setAttribute(SwyAttributes.PAYLOAD_DIGEST, digest);
            bufferedAttributes.put(SwyAttributes.PAYLOAD_DIGEST.getKey(), digest);
            if (recordPayloadFull) {
                String safe = redactor.redact(rawPayload);
                span.setAttribute(SwyAttributes.PAYLOAD_FULL, safe);
                bufferedAttributes.put(SwyAttributes.PAYLOAD_FULL.getKey(), safe);
            }
            return this;
        }

        /**
         * 记录响应留痕。
         *
         * @param rawPayload 响应原文，可为 null
         * @return 自身
         */
        public Handle responsePayload(String rawPayload) {
            return payload(rawPayload);
        }

        /**
         * 返回本 span 的 traceId。
         *
         * @return 32 位 hex traceId；span 无效时返回 null
         */
        public String traceId() {
            return span.getSpanContext().getTraceId();
        }

        /**
         * 返回本 span 的 spanId。
         *
         * @return 16 位 hex spanId；span 无效时返回 null
         */
        public String spanId() {
            return span.getSpanContext().getSpanId();
        }

        /**
         * 返回底层 span，供需要直接操作（如设置 status）的场景使用。
         *
         * @return OTel span
         */
        public Span span() {
            return span;
        }

        /**
         * 设置父 spanId（用于缓冲快照里重建树结构）。
         *
         * @param parentSpanId 父 spanId，可为 null
         * @return 自身
         */
        public Handle parent(String parentSpanId) {
            this.parentSpanId = parentSpanId;
            return this;
        }

        /**
         * 结束 span（无 usage）。
         *
         * @return 自身
         */
        public Handle end() {
            finish(null, null, null, null);
            return this;
        }

        /**
         * 结束 span 并记录计量与成本。
         *
         * @param usage 计量；为 null 时只 end 不写 usage 属性
         * @param providerId 供应商标识；为 null 时成本记 0
         * @param modelId 模型标识；为 null 时成本记 0
         * @param errorMessage 错误描述；无错误传 null
         * @return 自身
         */
        public Handle end(Usage usage, String providerId, String modelId, String errorMessage) {
            finish(usage, providerId, modelId, errorMessage);
            return this;
        }

        /**
         * 结束 span 并抛出异常。
         *
         * @param error 异常，不可为 null
         * @return 自身
         */
        public Handle endWithError(Throwable error) {
            span.recordException(error);
            finish(null, null, null, error.getClass().getSimpleName() + ": " + error.getMessage());
            span.setStatus(StatusCode.ERROR);
            return this;
        }

        /**
         * 统一的结束流程。
         *
         * @param usage 计量
         * @param providerId 供应商标识
         * @param modelId 模型标识
         * @param errorMessage 错误描述
         */
        private void finish(Usage usage, String providerId, String modelId, String errorMessage) {
            Instant endTime = Instant.now();
            SpanContext context = span.getSpanContext();
            this.errorMessage = errorMessage;
            // OTel 的 span 状态默认是 UNSET而不是 OK。不显式设 OK 的话，
            // Jaeger / Tempo 上正常 span 会显示为「未设置」，看起来像「没埋上」，
            // 与「埋点缺失」无法区分。因此成功时也显式设 OK。
            this.status = errorMessage == null ? "OK" : "ERROR";
            if (errorMessage == null) {
                span.setStatus(StatusCode.OK);
            } else {
                span.setStatus(StatusCode.ERROR, errorMessage);
            }
            if (usage != null) {
                // 只写属性，不结束 span：span.end() 之后再 setAttribute 会被 OTel 静默忽略
                GenaiSpanFactory.writeUsage(span, usage.inputTokens(), usage.outputTokens());
                bufferedAttributes.put(
                        GenaiAttributes.USAGE_INPUT_TOKENS.getKey(), usage.inputTokens());
                bufferedAttributes.put(
                        GenaiAttributes.USAGE_OUTPUT_TOKENS.getKey(), usage.outputTokens());
                span.setAttribute(SwyAttributes.USAGE_SOURCE, usage.usageSource().name());
                span.setAttribute(SwyAttributes.USAGE_STREAM_REPORTED, usage.streamUsageReported());
                span.setAttribute(SwyAttributes.USAGE_REASONING_TOKENS, usage.reasoningTokens());
                span.setAttribute(
                        SwyAttributes.USAGE_CACHED_INPUT_TOKENS, usage.cachedInputTokens());
                bufferedAttributes.put(
                        SwyAttributes.USAGE_SOURCE.getKey(), usage.usageSource().name());
                bufferedAttributes.put(
                        SwyAttributes.USAGE_STREAM_REPORTED.getKey(), usage.streamUsageReported());
                bufferedAttributes.put(
                        SwyAttributes.USAGE_REASONING_TOKENS.getKey(), usage.reasoningTokens());
                bufferedAttributes.put(
                        SwyAttributes.USAGE_CACHED_INPUT_TOKENS.getKey(),
                        usage.cachedInputTokens());

                Optional<PriceEntry> price = pricingTable.lookupFuzzy(providerId, modelId);
                if (price.isPresent()) {
                    BigDecimal cost = costCalculator.compute(usage, price.get());
                    span.setAttribute(SwyAttributes.COST_AMOUNT, cost.doubleValue());
                    span.setAttribute(SwyAttributes.COST_CURRENCY, PriceEntry.CURRENCY_CNY);
                    span.setAttribute(SwyAttributes.PRICE_SOURCE, price.get().source().name());
                    bufferedAttributes.put(SwyAttributes.COST_AMOUNT.getKey(), cost.doubleValue());
                    bufferedAttributes.put(
                            SwyAttributes.COST_CURRENCY.getKey(), PriceEntry.CURRENCY_CNY);
                    bufferedAttributes.put(
                            SwyAttributes.PRICE_SOURCE.getKey(), price.get().source().name());
                }
            }
            span.end();

            SpanData snapshot =
                    new SpanData(
                            context.getTraceId(),
                            context.getSpanId(),
                            parentSpanId,
                            spanName,
                            operation,
                            target,
                            startTime,
                            endTime,
                            Map.copyOf(bufferedAttributes),
                            status,
                            errorMessage);
            if (ringBuffer != null) {
                ringBuffer.append(snapshot);
            }
            if (listener != null) {
                try {
                    listener.onSpanFinished(snapshot, usage, providerId, modelId);
                } catch (RuntimeException e) {
                    // 观测链路的回调不允许影响业务：此时 span 已 end、ring buffer 已写入，
                    // 重抛只会让业务请求失败，却追不回归因。但「静默」同样不可接受——
                    // 归因丢了而日志里一点痕迹都没有，成本报表少一截却没人知道。
                    // 因此这里只记录 warn 并继续，写法与 ChatModelDecorator 的埋点收口保持一致。
                    log.warn("[glasshouse] span 回调失败（已忽略）: {}", e.toString());
                }
            }
        }
    }

    /**
     * span 结束回调，用于自动接入归因引擎。
     *
     * <p>抽成接口是为了让 core 与 starter 解耦，也便于测试里替换成断言用的实现。
     */
    @FunctionalInterface
    public interface SpanListener {

        /**
         * 一条 span 结束时的回调。
         *
         * @param snapshot span 快照
         * @param usage 计量；无 token 的 span 为 null
         * @param providerId 供应商标识，可为 null
         * @param modelId 模型标识，可为 null
         */
        void onSpanFinished(SpanData snapshot, Usage usage, String providerId, String modelId);
    }
}
