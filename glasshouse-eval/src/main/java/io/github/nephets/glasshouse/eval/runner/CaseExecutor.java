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

package io.github.nephets.glasshouse.eval.runner;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;

import io.github.nephets.glasshouse.core.eval.EvalCase;
import io.github.nephets.glasshouse.core.eval.EvalResult;
import io.github.nephets.glasshouse.core.eval.EvalRunner;
import io.github.nephets.glasshouse.core.model.ModelRef;
import io.github.nephets.glasshouse.core.pricing.CostCalculator;
import io.github.nephets.glasshouse.core.pricing.PriceEntry;
import io.github.nephets.glasshouse.core.pricing.PricingTable;
import io.github.nephets.glasshouse.core.usage.AnthropicUsageNormalizer;
import io.github.nephets.glasshouse.core.usage.CnUsageNormalizer;
import io.github.nephets.glasshouse.core.usage.NormalizeContext;
import io.github.nephets.glasshouse.core.usage.OpenAiUsageNormalizer;
import io.github.nephets.glasshouse.core.usage.RawUsage;
import io.github.nephets.glasshouse.core.usage.Usage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 用例执行器：把一条评测用例变成一次真实的模型调用。
 *
 * <p><b>关键设计</b>：{@code chatModel} 由调用方注入，可以是
 *
 * <ul>
 *   <li><b>裸 {@code ChatModel}</b> —— CI 场景，直接打真实端点或 mock-llm
 *   <li><b>{@code ChatModelDecorator}</b> —— 走 starter 的装饰器， 于是<b>评测过程本身也产出 OTel span 与成本归因</b>
 * </ul>
 *
 * <p>后者是架构 ③T04 明确要求的：「{@code EvalRunner} 复用 ③T03 的 ChatModelDecorator， 保证评测也产
 * span」。这样「评测为什么变慢」「评测花了多少钱」也能像排查线上问题一样排查。
 *
 * <p>本类只依赖 Spring AI 的 {@code ChatModel} 接口，<b>不依赖 starter</b>—— 因此 eval 模块可以被独立引用（CI 里只跑评测，不需要整套
 * starter）。
 */
public final class CaseExecutor implements EvalRunner.CaseInvoker {

    private static final Logger log = LoggerFactory.getLogger(CaseExecutor.class);

    private static final OpenAiUsageNormalizer OPENAI = new OpenAiUsageNormalizer();

    private static final AnthropicUsageNormalizer ANTHROPIC = new AnthropicUsageNormalizer();

    private static final CnUsageNormalizer CN = new CnUsageNormalizer();

    private final ChatModel chatModel;
    private final ModelRef modelRef;
    private final PricingTable pricingTable;
    private final Function<EvalCase, Prompt> promptRenderer;
    private final String promptVersion;

    /**
     * 构造执行器。
     *
     * @param chatModel 模型，不可为 null
     * @param modelRef 模型引用，不可为 null
     * @param pricingTable 价格表；可为 null（成本记 0）
     * @param promptRenderer Prompt 渲染器，不可为 null
     * @param promptVersion Prompt 版本标识，用于报告标注
     */
    public CaseExecutor(
            ChatModel chatModel,
            ModelRef modelRef,
            PricingTable pricingTable,
            Function<EvalCase, Prompt> promptRenderer,
            String promptVersion) {
        if (chatModel == null) {
            throw new NullPointerException("chatModel 不能为 null");
        }
        if (modelRef == null) {
            throw new NullPointerException("modelRef 不能为 null");
        }
        if (promptRenderer == null) {
            throw new NullPointerException("promptRenderer 不能为 null");
        }
        this.chatModel = chatModel;
        this.modelRef = modelRef;
        this.pricingTable = pricingTable;
        this.promptRenderer = promptRenderer;
        this.promptVersion = promptVersion == null ? "unknown" : promptVersion;
    }

    @Override
    public EvalResult invoke(EvalCase evalCase) {
        long startedAt = System.nanoTime();
        Prompt prompt = promptRenderer.apply(evalCase);
        try {
            ChatResponse response = chatModel.call(prompt);
            long latency = (System.nanoTime() - startedAt) / 1_000_000L;
            String text = extractText(response);
            Usage usage = normalizeUsage(response, prompt, text);
            BigDecimal cost = computeCost(usage);
            return EvalResult.success(evalCase.id(), text, usage, cost, latency);
        } catch (RuntimeException e) {
            long latency = (System.nanoTime() - startedAt) / 1_000_000L;
            log.debug("[glasshouse] 用例 {} 执行失败: {}", evalCase.id(), e.toString());
            return new EvalResult(
                    evalCase.id(),
                    null,
                    java.util.Map.of(),
                    null,
                    null,
                    e.getClass().getSimpleName() + ": " + e.getMessage(),
                    latency,
                    Instant.now());
        }
    }

    /**
     * 归一化本次调用的计量。
     *
     * @param response 响应
     * @param prompt 提示
     * @param text 响应文本
     * @return 计量
     */
    private Usage normalizeUsage(ChatResponse response, Prompt prompt, String text) {
        org.springframework.ai.chat.metadata.Usage springUsage = extractUsage(response);
        NormalizeContext ctx =
                NormalizeContext.nonStreaming(
                        renderPrompt(prompt),
                        text,
                        maxTokensOf(prompt),
                        modelRef.providerId(),
                        modelRef.modelId());
        RawUsage raw = RawUsage.empty();
        if (springUsage != null) {
            Long promptTokens =
                    springUsage.getPromptTokens() == null
                            ? null
                            : (long) springUsage.getPromptTokens();
            Long completionTokens =
                    springUsage.getCompletionTokens() == null
                            ? null
                            : (long) springUsage.getCompletionTokens();
            Long vendorTotal =
                    springUsage.getTotalTokens() == null
                            ? null
                            : (long) springUsage.getTotalTokens();
            raw = new RawUsage(promptTokens, completionTokens, 0L, 0L, vendorTotal, false, true);
        }
        // 供应商口径差异由各自的 normalizer 处理（国产五家走 CnUsageNormalizer）
        if ("anthropic".equals(modelRef.providerId())) {
            return ANTHROPIC.normalize(raw, ctx);
        }
        if (CnUsageNormalizer.isCnProvider(modelRef.providerId())) {
            return CN.normalize(raw, ctx);
        }
        return OPENAI.normalize(raw, ctx);
    }

    /**
     * 计算成本，价格缺失时记 0。
     *
     * @param usage 计量
     * @return 成本（CNY）
     */
    private BigDecimal computeCost(Usage usage) {
        if (pricingTable == null || usage == null || usage.totalTokens() == 0L) {
            return BigDecimal.ZERO.setScale(
                    CostCalculator.COST_SCALE, java.math.RoundingMode.HALF_UP);
        }
        Optional<PriceEntry> price =
                pricingTable.lookupFuzzy(modelRef.providerId(), modelRef.modelId());
        if (price.isEmpty()) {
            return BigDecimal.ZERO.setScale(
                    CostCalculator.COST_SCALE, java.math.RoundingMode.HALF_UP);
        }
        return CostCalculator.getInstance().compute(usage, price.get());
    }

    /**
     * 从响应中提取文本。
     *
     * @param response 响应，可为 null
     * @return 文本；无内容返回空串
     */
    static String extractText(ChatResponse response) {
        if (response == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Generation generation : response.getResults()) {
            AssistantMessage message = generation.getOutput();
            if (message != null && message.getText() != null) {
                sb.append(message.getText());
            }
        }
        return sb.toString();
    }

    /**
     * 从响应中提取 Spring AI usage。
     *
     * @param response 响应，可为 null
     * @return usage；无 usage 返回 null
     */
    static org.springframework.ai.chat.metadata.Usage extractUsage(ChatResponse response) {
        if (response == null) {
            return null;
        }
        ChatResponseMetadata metadata = response.getMetadata();
        return metadata == null ? null : metadata.getUsage();
    }

    private static Integer maxTokensOf(Prompt prompt) {
        try {
            return prompt == null || prompt.getOptions() == null
                    ? null
                    : prompt.getOptions().getMaxTokens();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String renderPrompt(Prompt prompt) {
        if (prompt == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Message message : prompt.getInstructions()) {
            sb.append(message.getMessageType().name())
                    .append(": ")
                    .append(message.getText())
                    .append('\n');
        }
        return sb.toString();
    }

    /**
     * 返回 Prompt 版本标识。
     *
     * @return Prompt 版本
     */
    public String promptVersion() {
        return promptVersion;
    }

    /**
     * 返回模型引用。
     *
     * @return 模型引用
     */
    public ModelRef modelRef() {
        return modelRef;
    }
}
