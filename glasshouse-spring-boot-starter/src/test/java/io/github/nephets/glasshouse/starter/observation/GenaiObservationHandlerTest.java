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
import java.time.LocalDate;
import java.util.List;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import io.github.nephets.glasshouse.core.audit.PayloadRedactor;
import io.github.nephets.glasshouse.core.audit.RedactionPolicy;
import io.github.nephets.glasshouse.core.pricing.PriceEntry;
import io.github.nephets.glasshouse.core.pricing.PriceSource;
import io.github.nephets.glasshouse.core.pricing.PriceUnit;
import io.github.nephets.glasshouse.core.pricing.PricingTable;
import io.github.nephets.glasshouse.core.telemetry.GenaiAttributes;
import io.github.nephets.glasshouse.core.telemetry.SwyAttributes;
import io.github.nephets.glasshouse.core.usage.Usage;
import io.github.nephets.glasshouse.starter.store.SpanRingBuffer;
import io.github.nephets.glasshouse.starter.store.SpanRingBuffer.SpanData;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Span 属性逐个精确断言（架构 ③T03 验收标准第 1 条）。
 *
 * <p><b>本类是「零锁定 + 口径一致」的证明</b>：它用 OTel SDK 的 {@link InMemorySpanExporter} 把 span
 * 抓出来，<b>逐个属性名与值</b>比对 SPN-001 契约， 而不是只断言「有几个 span」。架构明确要求「不允许只断言 span 数量」。
 *
 * <p>特别验证：GenAI usage 属性<b>同时写新名与旧名</b>（{@code gen_ai.usage.input_tokens} 与 {@code
 * gen_ai.usage.prompt_tokens}），这是兼容存量看板的关键。
 */
class GenaiObservationHandlerTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private Tracer tracer;
    private SpanRingBuffer buffer;
    private PricingTable pricingTable;
    private SpanFactory spanFactory;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider =
                SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build();
        tracer = tracerProvider.get(SpanFactory.SCOPE_NAME, SpanFactory.SCOPE_VERSION);
        buffer = new SpanRingBuffer(100);
        pricingTable = new PricingTable(List.of(price()));
        spanFactory =
                new SpanFactory(
                        tracer,
                        buffer,
                        PayloadRedactor.getInstance(),
                        pricingTable,
                        false,
                        (snapshot, usage, providerId, modelId) -> {});
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
    }

    private static PriceEntry price() {
        return new PriceEntry(
                "deepseek",
                "deepseek-chat",
                "CNY",
                PriceUnit.PER_1K_TOKENS,
                new BigDecimal("0.0020"),
                new BigDecimal("0.0008"),
                null,
                new BigDecimal("0.00004"),
                LocalDate.parse("2026-01-01"),
                PriceSource.OFFICIAL);
    }

    private static io.opentelemetry.api.common.Attributes attrs2(
            io.opentelemetry.api.common.Attributes attributes) {
        return attributes;
    }

    /**
     * 读取 span 上某个属性名对应的字符串值。
     *
     * @param attributes OTel 属性
     * @param name 属性名
     * @return 属性值；不存在返回 null
     */
    private static String str(io.opentelemetry.api.common.Attributes attributes, String name) {
        return attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey(name));
    }

    /**
     * 判断 span 上是否存在某个属性名。
     *
     * @param attributes OTel 属性
     * @param name 属性名
     * @return 存在返回 {@code true}
     */
    private static boolean has(io.opentelemetry.api.common.Attributes attributes, String name) {
        return attributes.asMap().keySet().stream().anyMatch(k -> name.equals(k.getKey()));
    }

    /**
     * 读取整型属性。
     *
     * @param attributes OTel 属性
     * @param name 属性名
     * @return 属性值；不存在返回 null
     */
    private static Long longValue(io.opentelemetry.api.common.Attributes attributes, String name) {
        return attributes.get(io.opentelemetry.api.common.AttributeKey.longKey(name));
    }

    /**
     * 取第一条 span。
     *
     * @return 唯一的 span
     */
    private io.opentelemetry.sdk.trace.data.SpanData single() {
        List<io.opentelemetry.sdk.trace.data.SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).as("应恰好产出一个 span").hasSize(1);
        return spans.get(0);
    }

    @Test
    @DisplayName("★ span 名按 SPN-001 模板生成：{operation} {target}")
    void spanNameFollowsContract() {
        spanFactory
                .start("chat", "deepseek-chat")
                .end(Usage.of(100L, 50L), "deepseek", "deepseek-chat", null);
        assertThat(single().getName()).isEqualTo("chat deepseek-chat");

        exporter.reset();
        spanFactory.start("invoke_agent", "planner").end();
        assertThat(single().getName()).isEqualTo("invoke_agent planner");

        exporter.reset();
        spanFactory.start("execute_tool", "kb_lookup").end();
        assertThat(single().getName()).isEqualTo("execute_tool kb_lookup");

        exporter.reset();
        spanFactory.start("retrieval", "pgvector_kb").end();
        assertThat(single().getName()).isEqualTo("retrieval pgvector_kb");
    }

    @Test
    @DisplayName("★ GenAI 属性名逐个精确匹配（含 semconv 1.41.0 新名）")
    void genaiAttributeNamesAreExact() {
        SpanFactory.Handle handle = spanFactory.start("chat", "deepseek-chat");
        handle.genai(GenaiAttributes.PROVIDER_NAME, "deepseek");
        handle.genai(GenaiAttributes.REQUEST_MODEL, "deepseek-chat");
        handle.genaiBool(GenaiAttributes.REQUEST_STREAM, Boolean.FALSE);
        handle.genaiLong(GenaiAttributes.REQUEST_MAX_TOKENS, 1024L);
        handle.genaiDouble(GenaiAttributes.REQUEST_TEMPERATURE, 0.0d);
        handle.end(Usage.of(1200L, 340L, 50L, 300L), "deepseek", "deepseek-chat", null);

        io.opentelemetry.sdk.trace.data.SpanData span = single();
        io.opentelemetry.api.common.Attributes attributes = attrs2(span.getAttributes());

        // 每个属性名逐个精确断言（架构 3T03：不允许只断言 span 数量）
        assertThat(str(attributes, "gen_ai.operation.name")).isEqualTo("chat");
        assertThat(str(attributes, "gen_ai.provider.name")).isEqualTo("deepseek");
        assertThat(str(attributes, "gen_ai.request.model")).isEqualTo("deepseek-chat");
        assertThat(attributes.get(GenaiAttributes.REQUEST_STREAM)).isEqualTo(Boolean.FALSE);
        assertThat(longValue(attributes, "gen_ai.request.max_tokens")).isEqualTo(1024L);
        assertThat(attributes.get(GenaiAttributes.REQUEST_TEMPERATURE)).isEqualTo(0.0d);

        // 新名（1.42.0 之后）
        assertThat(longValue(attributes, GenaiAttributes.USAGE_INPUT_TOKENS.getKey()))
                .as("新属性名 gen_ai.usage.input_tokens")
                .isEqualTo(1200L);
        assertThat(longValue(attributes, GenaiAttributes.USAGE_OUTPUT_TOKENS.getKey()))
                .isEqualTo(340L);
        // 旧名冗余一个发布周期，兼容存量看板
        assertThat(longValue(attributes, GenaiAttributes.USAGE_PROMPT_TOKENS_LEGACY.getKey()))
                .as("旧属性名 gen_ai.usage.prompt_tokens 必须同时写入")
                .isEqualTo(1200L);
        assertThat(longValue(attributes, GenaiAttributes.USAGE_COMPLETION_TOKENS_LEGACY.getKey()))
                .isEqualTo(340L);
    }

    @Test
    @DisplayName("★ swy.* 属性名逐个精确匹配 SPN-001")
    void swyAttributeNamesAreExact() {
        SpanFactory.Handle handle = spanFactory.start("chat", "deepseek-chat");
        handle.swy(SwyAttributes.TENANT_ID, "vip");
        handle.swy(SwyAttributes.BIZ_LINE, "电商");
        handle.swy(SwyAttributes.FEATURE, "customer-service");
        handle.swy(SwyAttributes.PROMPT_VERSION, "planner@v3");
        handle.swy(SwyAttributes.AGENT_ID, "planner");
        handle.swy(SwyAttributes.AGENT_VERSION, "v3");
        handle.swy(SwyAttributes.STEP_ID, "plan");
        handle.swyLong(SwyAttributes.STEP_SEQ, 0L);
        handle.swy(SwyAttributes.STEP_KIND, "LLM");
        handle.end(Usage.of(100L, 50L), "deepseek", "deepseek-chat", null);

        io.opentelemetry.api.common.Attributes attributes = single().getAttributes();
        assertThat(str(attributes, "swy.tenant.id")).isEqualTo("vip");
        assertThat(str(attributes, "swy.biz.line")).isEqualTo("电商");
        assertThat(str(attributes, "swy.feature")).isEqualTo("customer-service");
        assertThat(str(attributes, "swy.prompt.version")).isEqualTo("planner@v3");
        assertThat(str(attributes, "swy.agent.id")).isEqualTo("planner");
        assertThat(str(attributes, "swy.agent.version")).isEqualTo("v3");
        assertThat(str(attributes, "swy.step.id")).isEqualTo("plan");
        assertThat(longValue(attributes, "swy.step.seq")).isEqualTo(0L);
        assertThat(str(attributes, "swy.step.kind")).isEqualTo("LLM");
    }

    @Test
    @DisplayName("★ 计量与成本属性写全，且成本由价格表算出")
    void usageAndCostAttributes() {
        // 1200 输入（300 缓存命中）+ 340 输出 + 50 推理
        // 计费输入 = 900 × 0.002/1K = 0.0018
        // 缓存输入 = 300 × 0.00004/1K = 0.000012
        // 输出 = 340 × 0.0008/1K = 0.000272
        // 推理价缺失 → 按输出价 = 50 × 0.0008/1K = 0.00004
        // 合计 = 0.002124
        Usage usage =
                new Usage(
                        1200L,
                        340L,
                        50L,
                        300L,
                        0L,
                        io.github.nephets.glasshouse.core.usage.UsageSource.VENDOR_REPORTED,
                        false,
                        false);
        spanFactory.start("chat", "deepseek-chat").end(usage, "deepseek", "deepseek-chat", null);

        io.opentelemetry.api.common.Attributes attributes = single().getAttributes();
        // 直接用契约里的 AttributeKey 读，避免「用属性名字符串反查」在类型不匹配时静默返回 null
        assertThat(attributes.get(SwyAttributes.USAGE_SOURCE)).isEqualTo("VENDOR_REPORTED");
        assertThat(attributes.get(SwyAttributes.USAGE_STREAM_REPORTED)).isEqualTo(Boolean.FALSE);
        assertThat(attributes.get(SwyAttributes.USAGE_REASONING_TOKENS)).isEqualTo(50L);
        assertThat(attributes.get(SwyAttributes.USAGE_CACHED_INPUT_TOKENS)).isEqualTo(300L);
        assertThat(attributes.get(SwyAttributes.COST_CURRENCY)).isEqualTo("CNY");
        assertThat(attributes.get(SwyAttributes.PRICE_SOURCE)).isEqualTo("OFFICIAL");

        Double cost = attributes.get(SwyAttributes.COST_AMOUNT);
        assertThat(cost).isNotNull();
        assertThat(cost)
                .as("成本应等于 0.002124 CNY（缓存不重复计费 + 推理按输出价）")
                .isCloseTo(0.002124d, org.assertj.core.data.Offset.offset(1e-9));

        // 同一批属性也会落到内存缓冲快照上（CLI --tree / --cost 的数据源）
        SpanData snapshot = buffer.all().get(0);
        assertThat(snapshot.attributes())
                .containsEntry("swy.usage.source", "VENDOR_REPORTED")
                .containsEntry("swy.usage.reasoning_tokens", 50L)
                .containsEntry("swy.usage.cached_input_tokens", 300L)
                .containsEntry("swy.cost.currency", "CNY")
                .containsEntry("swy.price.source", "OFFICIAL");
    }

    @Test
    @DisplayName("空维度不写入（禁止 unknown 占位污染归因）")
    void blankDimensionsAreNotWritten() {
        SpanFactory.Handle handle = spanFactory.start("chat", "deepseek-chat");
        handle.swy(SwyAttributes.TENANT_ID, null);
        handle.swy(SwyAttributes.FEATURE, "   ");
        handle.swyLong(SwyAttributes.STEP_SEQ, null);
        handle.swyBool(SwyAttributes.USAGE_STREAM_REPORTED, null);
        handle.end();

        io.opentelemetry.api.common.Attributes attributes = attrs2(single().getAttributes());
        assertThat(has(attributes, "swy.tenant.id"))
                .as("★ 缺失维度不写，而不是写 unknown / 空串（契约 EVT-001）")
                .isFalse();
        assertThat(has(attributes, "swy.feature")).isFalse();
        assertThat(has(attributes, "swy.step.seq")).isFalse();
        assertThat(has(attributes, "swy.usage.stream_reported")).isFalse();
    }

    @Test
    @DisplayName("★ payload 摘要恒定写；完整内容默认不写")
    void payloadDigestAlwaysWrittenFullTextOptIn() {
        String prompt = "客户手机号是 13812345678，请回电";
        spanFactory.start("chat", "deepseek-chat").payload(prompt).end();

        io.opentelemetry.api.common.Attributes attributes = attrs2(single().getAttributes());
        assertThat(has(attributes, "swy.payload.digest")).isTrue();
        assertThat(str(attributes, "swy.payload.digest")).matches("^sha256:[0-9a-f]{64}$");
        assertThat(has(attributes, "swy.payload.full")).as("★ 完整 payload 默认不写，避免 PII 泄漏").isFalse();

        // 开启后：先脱敏再写
        exporter.reset();
        SpanFactory optIn =
                new SpanFactory(
                        tracer,
                        new SpanRingBuffer(10),
                        PayloadRedactor.fromClasspath(RedactionPolicy.DEFAULT),
                        pricingTable,
                        true,
                        (s, u, p, m) -> {});
        optIn.start("chat", "deepseek-chat").payload(prompt).end();

        io.opentelemetry.api.common.Attributes optInAttributes =
                exporter.getFinishedSpanItems().get(0).getAttributes();
        assertThat(has(optInAttributes, "swy.payload.full")).isTrue();
        assertThat(str(optInAttributes, "swy.payload.full"))
                .as("★ 开启留痕后仍必须脱敏")
                .doesNotContain("13812345678");
    }

    @Test
    @DisplayName("span 状态：正常 OK，异常 ERROR 并记录异常")
    void spanStatus() {
        spanFactory.start("chat", "m").end();
        assertThat(single().getStatus().getStatusCode()).isEqualTo(StatusCode.OK);

        exporter.reset();
        spanFactory.start("chat", "m").endWithError(new IllegalStateException("上游 429"));
        io.opentelemetry.sdk.trace.data.SpanData errored = single();
        assertThat(errored.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(errored.getStatus().getDescription()).contains("429");
        assertThat(errored.getEvents())
                .as("★ 异常必须作为 event 记录，便于在 Jaeger 上直接看到")
                .anyMatch(e -> e.getName().equals("exception"));
    }

    @Test
    @DisplayName("★ span 缓冲同时落快照，供 CLI 的 --tree 使用（不依赖外部后端）")
    void spanBufferCapturesSnapshot() {
        SpanFactory.Handle handle = spanFactory.start("chat", "deepseek-chat");
        handle.swy(SwyAttributes.TENANT_ID, "vip");
        handle.end(Usage.of(100L, 50L), "deepseek", "deepseek-chat", null);

        assertThat(buffer.size()).isEqualTo(1);
        SpanData snapshot = buffer.all().get(0);
        assertThat(snapshot.name()).isEqualTo("chat deepseek-chat");
        assertThat(snapshot.operation()).isEqualTo("chat");
        assertThat(snapshot.target()).isEqualTo("deepseek-chat");
        assertThat(snapshot.attributes()).containsEntry("swy.tenant.id", "vip");
        assertThat(snapshot.status()).isEqualTo("OK");
        assertThat(snapshot.traceId()).isNotBlank();
        assertThat(snapshot.spanId()).isNotBlank();
        assertThat(snapshot.inputTokens()).isEqualTo(100L);
        assertThat(snapshot.outputTokens()).isEqualTo(50L);
        assertThat(snapshot.costAmount()).isNotNull();
        assertThat(snapshot.durationMillis()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("SpanListener 在 span 结束时被调用（归因自动接入的挂载点）")
    void spanListenerInvoked() {
        java.util.concurrent.atomic.AtomicReference<Usage> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        SpanFactory withListener =
                new SpanFactory(
                        tracer,
                        buffer,
                        PayloadRedactor.getInstance(),
                        pricingTable,
                        false,
                        (snapshot, usage, providerId, modelId) -> captured.set(usage));
        withListener.start("chat", "m").end(Usage.of(10L, 20L), "deepseek", "deepseek-chat", null);
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().totalTokens()).isEqualTo(30L);
    }

    @Test
    @DisplayName("SpanListener 抛异常不影响 span（观测不得让业务失败）")
    void spanListenerFailureIsIsolated() {
        SpanFactory brokenListener =
                new SpanFactory(
                        tracer,
                        buffer,
                        PayloadRedactor.getInstance(),
                        pricingTable,
                        false,
                        (snapshot, usage, providerId, modelId) -> {
                            throw new IllegalStateException("归因存储故障");
                        });
        brokenListener.start("chat", "m").end(Usage.of(10L, 20L), "deepseek", "m", null);
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        assertThat(buffer.size()).as("缓冲仍应写入").isEqualTo(1);
    }

    @Test
    @DisplayName("价格缺失时不写成本属性，且不抛异常")
    void missingPriceOmitsCost() {
        spanFactory
                .start("chat", "unknown-model")
                .end(Usage.of(100L, 50L), "unknown", "unknown-model", null);
        io.opentelemetry.api.common.Attributes attributes = single().getAttributes();
        assertThat(has(attributes, "swy.cost.amount"))
                .as("查不到价格就不写 swy.cost.amount（而不是写 0 冒充有成本）")
                .isFalse();
    }

    @Test
    @DisplayName("构造参数校验：tracer / redactor / pricingTable 不可为 null")
    void constructorValidation() {
        PayloadRedactor r = PayloadRedactor.getInstance();
        PricingTable t = new PricingTable();
        org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class, () -> new SpanFactory(null, buffer, r, t, false, null));
        org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class,
                () -> new SpanFactory(tracer, buffer, null, t, false, null));
        org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class,
                () -> new SpanFactory(tracer, buffer, r, null, false, null));
    }

    @Test
    @DisplayName("Handle：traceId / spanId / span 访问器")
    void handleAccessors() {
        SpanFactory.Handle handle = spanFactory.start("chat", "m");
        assertThat(handle.traceId()).hasSize(32);
        assertThat(handle.spanId()).hasSize(16);
        assertThat(handle.span()).isNotNull();
        handle.end();
    }

    @Test
    @DisplayName("parent() 记录父 spanId，供缓冲重建树结构")
    void parentIsRecorded() {
        SpanFactory.Handle child = spanFactory.start("chat", "m");
        child.parent("00f067aa0ba902b7");
        child.end();
        assertThat(buffer.all().get(0).parentSpanId()).isEqualTo("00f067aa0ba902b7");
    }

    @Test
    @DisplayName("genaiStrings 写字符串数组属性；空列表不写")
    void genaiStrings() {
        SpanFactory.Handle handle = spanFactory.start("chat", "m");
        handle.genaiStrings(GenaiAttributes.RESPONSE_FINISH_REASONS, List.of("stop"));
        handle.genaiStrings(GenaiAttributes.RESPONSE_FINISH_REASONS, List.of());
        handle.end();
        io.opentelemetry.api.common.Attributes attributes = attrs2(single().getAttributes());
        assertThat(attributes.get(GenaiAttributes.RESPONSE_FINISH_REASONS))
                .isEqualTo(List.of("stop"));
    }

    @Test
    @DisplayName("buffer 为 null 时不落快照但不报错（只导 OTLP 的场景）")
    void nullBufferIsSafe() {
        SpanFactory noBuffer =
                new SpanFactory(
                        tracer,
                        null,
                        PayloadRedactor.getInstance(),
                        pricingTable,
                        false,
                        (s, u, p, m) -> {});
        noBuffer.start("chat", "m").end(Usage.of(1L, 1L), "deepseek", "deepseek-chat", null);
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
    }

    @Test
    @DisplayName("作用域名与版本固定（三仓一致的 instrumentation scope）")
    void scopeIsFixed() {
        assertThat(SpanFactory.SCOPE_NAME).isEqualTo("io.github.nephets.glasshouse");
        assertThat(SpanFactory.SCOPE_VERSION).isEqualTo("0.1.0");
    }

    @Test
    @DisplayName("属性键类型与契约一致")
    void attributeKeyTypes() {
        assertThat(SwyAttributes.TENANT_ID.getType())
                .isEqualTo(io.opentelemetry.api.common.AttributeType.STRING);
        assertThat(SwyAttributes.STEP_SEQ.getType())
                .isEqualTo(io.opentelemetry.api.common.AttributeType.LONG);
        assertThat(SwyAttributes.COST_AMOUNT.getType())
                .isEqualTo(io.opentelemetry.api.common.AttributeType.DOUBLE);
        assertThat(SwyAttributes.USAGE_STREAM_REPORTED.getType())
                .isEqualTo(io.opentelemetry.api.common.AttributeType.BOOLEAN);
        assertThat(AttributeKey.stringKey("x")).isNotNull();
    }
}
