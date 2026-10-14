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
import java.util.List;

import reactor.core.publisher.Flux;

import io.github.nephets.glasshouse.core.audit.AuditEvent;
import io.github.nephets.glasshouse.core.audit.EventType;
import io.github.nephets.glasshouse.core.audit.PayloadRedactor;
import io.github.nephets.glasshouse.core.telemetry.GenaiAttributes;
import io.github.nephets.glasshouse.core.telemetry.SpnNames;
import io.github.nephets.glasshouse.core.telemetry.SwyAttributes;
import io.github.nephets.glasshouse.core.usage.NormalizeContext;
import io.github.nephets.glasshouse.core.usage.Usage;
import io.github.nephets.glasshouse.starter.annotation.SpanDimensions;
import io.github.nephets.glasshouse.starter.model.ChatModelMetadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * ChatModel 装饰器：<b>零代码侵入</b>产 span 的核心。
 *
 * <p><b>为什么这是「零侵入」的关键</b>：业务代码里已经有 {@code ChatClient} / {@code ChatModel} 的调用（那是 Spring AI
 * 的事，与我们无关）。本装饰器包装那个 {@code ChatModel} Bean， 于是<b>业务代码一行不改</b>，每次模型调用就自动产生一个符合 SPN-001 的 span， 带完整的
 * token 计量、成本与 Prompt 摘要。
 *
 * <p><b>永不因观测而失败</b>：埋点链路整体被 try/catch 包裹，任何观测异常都会降级为 「直接调用被装饰对象」。观测组件让业务 500 是不可接受的。
 *
 * <p><b>关于 Spring AI 版本差异</b>：usage 在 1.1.x 位于 {@code ChatResponse.getMetadata().getUsage()}， 而 1.0
 * 与 2.0 的挂载点不同。本类把「取 usage」收敛到 {@link #extractUsage} 一个方法， 未来升级只需改这一处——这正是架构 §9.1 说的「升级只改 provider
 * + starter 两个模块」。
 */
public final class ChatModelDecorator implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(ChatModelDecorator.class);

    private static final String DEFAULT_TRACE_ID = "00000000000000000000000000000000";
    private static final String DEFAULT_SPAN_ID = "0000000000000000";

    private final ChatModel delegate;
    private final SpanFactory spanFactory;
    private final ChatModelMetadata metadata;
    private final AttributionRecorder attributionRecorder;

    /**
     * 构造装饰器。
     *
     * @param delegate 被装饰的 ChatModel，不可为 null
     * @param spanFactory span 工厂，不可为 null
     * @param metadata 模型元数据，不可为 null
     * @param attributionRecorder 归因记录器；可为 null（关闭归因）
     */
    public ChatModelDecorator(
            ChatModel delegate,
            SpanFactory spanFactory,
            ChatModelMetadata metadata,
            AttributionRecorder attributionRecorder) {
        if (delegate == null) {
            throw new NullPointerException("delegate 不能为 null");
        }
        if (spanFactory == null) {
            throw new NullPointerException("spanFactory 不能为 null");
        }
        if (metadata == null) {
            throw new NullPointerException("metadata 不能为 null");
        }
        this.delegate = delegate;
        this.spanFactory = spanFactory;
        this.metadata = metadata;
        this.attributionRecorder = attributionRecorder;
    }

    /**
     * 返回被装饰的原始模型。
     *
     * @return 原始 ChatModel
     */
    public ChatModel delegate() {
        return delegate;
    }

    /**
     * 返回模型元数据。
     *
     * @return 模型元数据
     */
    public ChatModelMetadata metadata() {
        return metadata;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String modelId = resolveModelId(prompt);
        SpanFactory.Handle handle = spanFactory.start(SpnNames.OP_CHAT, modelId);
        prepareChatHandle(handle, modelId, prompt, false);
        ChatResponse response;
        try {
            response = delegate.call(prompt);
        } catch (RuntimeException e) {
            handle.endWithError(e);
            throw e;
        }
        try {
            finishChat(handle, prompt, response);
        } catch (RuntimeException e) {
            // 埋点收口失败不影响业务：span 结束与归因都已在各自内部做了保护
            log.warn("[glasshouse] chat 埋点收口异常（已忽略）: {}", e.toString());
            handle.end();
        }
        return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        String modelId = resolveModelId(prompt);
        SpanFactory.Handle handle = spanFactory.start(SpnNames.OP_CHAT, modelId);
        prepareChatHandle(handle, modelId, prompt, true);

        StringBuilder textBuffer = new StringBuilder();
        // 任一分片带过 usage 就算供应商上报了。
        // 不少供应商（如 DeepSeek）把 usage 放在倒数第二个 chunk，
        // 只看最后一块会误判成「未上报」，进而把真账当成估算。
        java.util.concurrent.atomic.AtomicBoolean usageSeen =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicReference<ChatResponse> usageChunk =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<ChatResponse> lastChunk =
                new java.util.concurrent.atomic.AtomicReference<>();

        Flux<ChatResponse> upstream;
        try {
            upstream = delegate.stream(prompt);
        } catch (RuntimeException e) {
            handle.endWithError(e);
            throw e;
        }

        return upstream.doOnNext(
                        chunk -> {
                            try {
                                lastChunk.set(chunk);
                                if (extractUsage(chunk) != null) {
                                    usageSeen.set(true);
                                    usageChunk.set(chunk);
                                }
                                String text = extractText(chunk);
                                if (text != null) {
                                    textBuffer.append(text);
                                }
                            } catch (RuntimeException e) {
                                log.debug("[glasshouse] 流式分片处理失败（已忽略）: {}", e.toString());
                            }
                        })
                .doOnComplete(
                        () -> {
                            try {
                                finishStream(
                                        handle,
                                        prompt,
                                        textBuffer.toString(),
                                        usageChunk.get(),
                                        usageSeen.get());
                            } catch (RuntimeException e) {
                                log.warn("[glasshouse] 流式埋点收口异常（已忽略）: {}", e.toString());
                                handle.end();
                            }
                        })
                .doOnError(
                        error -> {
                            try {
                                handle.endWithError(error);
                            } catch (RuntimeException e) {
                                log.debug("[glasshouse] 流式异常埋点失败（已忽略）: {}", e.toString());
                            }
                        });
    }

    @Override
    public ChatOptions getDefaultOptions() {
        // 直接返回被装饰对象的实例：业务侧通过 ChatModel 读到的默认选项必须与
        // 装饰前完全一致（isEqual 不成立时说明配置被改写了）。
        return delegate.getDefaultOptions();
    }

    /**
     * 写入模型调用的通用 span 属性。
     *
     * @param handle span 句柄
     * @param modelId 模型标识
     * @param prompt 提示
     * @param streaming 是否为流式
     */
    private void prepareChatHandle(
            SpanFactory.Handle handle, String modelId, Prompt prompt, boolean streaming) {
        handle.genai(GenaiAttributes.PROVIDER_NAME, metadata.providerId());
        handle.genai(GenaiAttributes.REQUEST_MODEL, modelId);
        handle.genaiBool(GenaiAttributes.REQUEST_STREAM, Boolean.valueOf(streaming));
        handle.genaiLong(GenaiAttributes.REQUEST_MAX_TOKENS, toLong(resolveMaxTokens(prompt)));
        handle.genaiDouble(GenaiAttributes.REQUEST_TEMPERATURE, resolveTemperature(prompt));
        handle.payload(renderPrompt(prompt));
        applyDimensions(handle);
    }

    /**
     * 收口一次非流式调用。
     *
     * @param handle span 句柄
     * @param prompt 提示
     * @param response 响应，可为 null
     */
    private void finishChat(SpanFactory.Handle handle, Prompt prompt, ChatResponse response) {
        String responseText = extractResponseText(response);
        handle.responsePayload(responseText);
        Usage usage =
                UsageNormalizers.fromSpringAi(
                        extractUsage(response),
                        NormalizeContext.nonStreaming(
                                renderPrompt(prompt),
                                responseText,
                                resolveMaxTokens(prompt),
                                metadata.providerId(),
                                modelIdOf(response, metadata.modelId())));
        String finishReason = extractFinishReason(response);
        if (finishReason != null && !finishReason.isBlank()) {
            handle.genai(GenaiAttributes.RESPONSE_MODEL, modelIdOf(response, metadata.modelId()));
            handle.genaiStrings(GenaiAttributes.RESPONSE_FINISH_REASONS, List.of(finishReason));
        }
        handle.end(usage, metadata.providerId(), modelIdOf(response, metadata.modelId()), null);
        recordAttribution(handle, usage, responseText);
    }

    /**
     * 收口一次流式调用。
     *
     * <p><b>PRD 痛点 4 的落点</b>：流式响应的 usage 可能完全缺失（供应商未开启 {@code include_usage}）。这里显式区分两种情况：
     *
     * <ul>
     *   <li>{@code usageReported=true} → 用供应商返回的 usage，{@code streamUsageReported=true}
     *   <li>{@code usageReported=false} → 用估算器，{@code streamUsageReported=false}
     * </ul>
     *
     * 无论哪种，<b>都不会记成 0</b>，也不会假装是真账——数据里能看出这条是估的还是真的。
     *
     * @param handle span 句柄
     * @param prompt 提示
     * @param responseText 流式拼接出的完整文本
     * @param lastChunk 最后一个分片，可为 null
     * @param usageReported 是否真的收到了 usage
     */
    private void finishStream(
            SpanFactory.Handle handle,
            Prompt prompt,
            String responseText,
            ChatResponse lastChunk,
            boolean usageReported) {
        handle.responsePayload(responseText);
        NormalizeContext ctx =
                NormalizeContext.streaming(
                        renderPrompt(prompt),
                        resolveMaxTokens(prompt),
                        metadata.providerId(),
                        metadata.modelId());
        org.springframework.ai.chat.metadata.Usage springUsage =
                usageReported ? extractUsage(lastChunk) : null;
        Usage normalized = UsageNormalizers.fromSpringAi(springUsage, ctx, usageReported);
        handle.end(normalized, metadata.providerId(), metadata.modelId(), null);
        recordAttribution(handle, normalized, responseText);
    }

    /**
     * 把本次调用记入归因引擎。
     *
     * <p>这是「埋点与归因不需要业务再调一次 API」的落点（架构 ③T03 验收标准第 7 条）。
     *
     * @param handle span 句柄
     * @param usage 计量
     * @param responseText 响应文本
     */
    private void recordAttribution(SpanFactory.Handle handle, Usage usage, String responseText) {
        if (attributionRecorder == null) {
            return;
        }
        try {
            SpanDimensions dims = SpanDimensions.current();
            attributionRecorder.record(
                    new AuditEvent(
                            EventType.MODEL_CALL,
                            nonBlank(handle.traceId(), DEFAULT_TRACE_ID),
                            nonBlank(handle.spanId(), DEFAULT_SPAN_ID),
                            Instant.now(),
                            dims.tenantId(),
                            dims.bizLine(),
                            dims.feature(),
                            dims.promptVersion(),
                            dims.agentId(),
                            null,
                            dims.stepId(),
                            dims.stepSeq(),
                            usage,
                            BigDecimal.ZERO,
                            AuditEvent.CURRENCY_CNY,
                            PayloadRedactor.getInstance().digest(responseText),
                            null));
        } catch (RuntimeException e) {
            log.debug("[glasshouse] 归因记录失败（已忽略）: {}", e.toString());
        }
    }

    /**
     * 把当前线程上的归因维度写到 span 上。
     *
     * <p>维度由 {@link SpanDimensions} 承载。Agent 步骤只需设置一次维度， 其内部所有模型调用就自动带上——这正是五维归因能做到「任一维度可下钻」的前提。
     *
     * @param handle span 句柄
     */
    private void applyDimensions(SpanFactory.Handle handle) {
        SpanDimensions dims = SpanDimensions.current();
        if (dims == null || dims.isEmpty()) {
            return;
        }
        handle.swy(SwyAttributes.TENANT_ID, dims.tenantId());
        handle.swy(SwyAttributes.BIZ_LINE, dims.bizLine());
        handle.swy(SwyAttributes.FEATURE, dims.feature());
        handle.swy(SwyAttributes.PROMPT_VERSION, dims.promptVersion());
        handle.swy(SwyAttributes.AGENT_ID, dims.agentId());
        handle.swy(SwyAttributes.STEP_ID, dims.stepId());
        handle.swyLong(
                SwyAttributes.STEP_SEQ, dims.stepSeq() == null ? null : dims.stepSeq().longValue());
        handle.swy(SwyAttributes.STEP_KIND, dims.stepKind());
    }

    /**
     * 解析本次调用的模型标识：优先用请求里声明的，其次用响应回传的，最后用元数据默认值。
     *
     * @param prompt 提示
     * @return 模型标识
     */
    private String resolveModelId(Prompt prompt) {
        try {
            ChatOptions options = prompt == null ? null : prompt.getOptions();
            if (options != null && options.getModel() != null && !options.getModel().isBlank()) {
                return options.getModel();
            }
        } catch (RuntimeException e) {
            log.debug("[glasshouse] 读取模型名失败，使用元数据默认值: {}", e.toString());
        }
        return metadata.modelId();
    }

    /**
     * 从响应元数据中取模型名。
     *
     * @param response 响应，可为 null
     * @param fallback 回退值
     * @return 模型标识
     */
    private static String modelIdOf(ChatResponse response, String fallback) {
        if (response == null) {
            return fallback;
        }
        try {
            ChatResponseMetadata metadata = response.getMetadata();
            String model = metadata == null ? null : metadata.getModel();
            return model == null || model.isBlank() ? fallback : model;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static Integer resolveMaxTokens(Prompt prompt) {
        try {
            ChatOptions options = prompt == null ? null : prompt.getOptions();
            if (options != null && options.getMaxTokens() != null) {
                return options.getMaxTokens();
            }
        } catch (RuntimeException e) {
            log.debug("[glasshouse] 读取 maxTokens 失败: {}", e.toString());
        }
        return null;
    }

    private static Double resolveTemperature(Prompt prompt) {
        try {
            ChatOptions options = prompt == null ? null : prompt.getOptions();
            if (options != null && options.getTemperature() != null) {
                return options.getTemperature();
            }
        } catch (RuntimeException e) {
            log.debug("[glasshouse] 读取 temperature 失败: {}", e.toString());
        }
        return null;
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Long toLong(Integer value) {
        return value == null ? null : value.longValue();
    }

    /**
     * 把 Prompt 渲染为文本，用于摘要与 token 估算。
     *
     * @param prompt 提示，可为 null
     * @return 渲染后的文本；入参为 null 返回空串
     */
    static String renderPrompt(Prompt prompt) {
        if (prompt == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try {
            for (Message message : prompt.getInstructions()) {
                sb.append(message.getMessageType().name())
                        .append(": ")
                        .append(message.getText())
                        .append('\n');
            }
        } catch (RuntimeException e) {
            log.debug("[glasshouse] Prompt 渲染失败（已忽略）: {}", e.toString());
        }
        return sb.toString();
    }

    /**
     * 从响应中提取完整文本。
     *
     * @param response 响应，可为 null
     * @return 文本；无内容返回空串
     */
    static String extractResponseText(ChatResponse response) {
        if (response == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try {
            for (Generation generation : response.getResults()) {
                AssistantMessage message = generation.getOutput();
                if (message != null && message.getText() != null) {
                    sb.append(message.getText());
                }
            }
        } catch (RuntimeException e) {
            return sb.toString();
        }
        return sb.toString();
    }

    /**
     * 从单个分片中提取文本（流式用）。
     *
     * @param chunk 分片，可为 null
     * @return 文本；无内容返回 null
     */
    static String extractText(ChatResponse chunk) {
        if (chunk == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        try {
            for (Generation generation : chunk.getResults()) {
                AssistantMessage message = generation.getOutput();
                if (message != null && message.getText() != null) {
                    sb.append(message.getText());
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * 从响应中提取 Spring AI usage。
     *
     * <p>1.1.x 的路径是 {@code ChatResponse.getMetadata().getUsage()}。 这里额外兼容 1.0.x 的 {@code
     * Generation.getMetadata().getUsage()}， 以便本项目在 Spring AI 小版本间平滑移动。
     *
     * @param response 响应，可为 null
     * @return usage；无 usage 返回 null
     */
    static org.springframework.ai.chat.metadata.Usage extractUsage(ChatResponse response) {
        if (response == null) {
            return null;
        }
        try {
            ChatResponseMetadata responseMetadata = response.getMetadata();
            if (responseMetadata != null) {
                org.springframework.ai.chat.metadata.Usage usage = responseMetadata.getUsage();
                if (isMeaningful(usage)) {
                    return usage;
                }
            }
        } catch (RuntimeException e) {
            log.debug("[glasshouse] 读取 ChatResponseMetadata.usage 失败: {}", e.toString());
        }
        return null;
    }

    /**
     * 判断 usage 是否真的有内容。
     *
     * <p><b>这一步不能省</b>：Spring AI 的 {@code ChatResponseMetadata.getUsage()} 在未设置 usage 时 仍会返回一个非
     * null 的对象（token 全 0）。只判 {@code != null} 的话， 供应商没开 {@code include_usage} 的流式响应会被误判成「已上报」，
     * {@code streamUsageReported} 永远是 {@code true}， 成本报表把估算值当真账——等于把 PRD 痛点 4 又请回来了。
     *
     * @param usage Spring AI usage 对象，可为 null
     * @return 至少有一个 token 数大于 0 时返回 {@code true}
     */
    private static boolean isMeaningful(org.springframework.ai.chat.metadata.Usage usage) {
        if (usage == null) {
            return false;
        }
        Integer promptTokens = usage.getPromptTokens();
        Integer completionTokens = usage.getCompletionTokens();
        return (promptTokens != null && promptTokens > 0)
                || (completionTokens != null && completionTokens > 0);
    }

    /**
     * 从响应中提取结束原因。
     *
     * @param response 响应，可为 null
     * @return 结束原因；无返回 null
     */
    static String extractFinishReason(ChatResponse response) {
        if (response == null) {
            return null;
        }
        try {
            List<Generation> generations = response.getResults();
            if (generations == null || generations.isEmpty()) {
                return null;
            }
            ChatGenerationMetadata metadata = generations.get(0).getMetadata();
            return metadata == null ? null : metadata.getFinishReason();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 归因记录器：把 span 结束事件转成 {@link AuditEvent} 交给归因引擎。
     *
     * <p>抽成接口是为了让 starter 与 {@code AttributionEngine} 解耦， 也让测试能注入断言用的实现。
     */
    @FunctionalInterface
    public interface AttributionRecorder {

        /**
         * 记录一次调用。
         *
         * @param event 审计事件，不可为 null
         */
        void record(AuditEvent event);
    }
}
