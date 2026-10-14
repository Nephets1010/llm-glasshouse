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

package io.github.nephets.glasshouse.core.usage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAI 与 Anthropic 口径归一化测试。
 *
 * <p>这两个实现的价值在于<b>把各家异构结构收敛到同一模型</b>，因此测试重点是 「字段映射对不对」与「口径差异有没有被正确处理」。
 */
class UsageNormalizerImplTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OpenAiUsageNormalizer openai = new OpenAiUsageNormalizer();
    private final AnthropicUsageNormalizer anthropic = new AnthropicUsageNormalizer();

    private static NormalizeContext ctx(String provider) {
        return NormalizeContext.nonStreaming("中文问题", "中文回答", 1024, provider, "model-x");
    }

    @Test
    @DisplayName("OpenAI：prompt_tokens / completion_tokens 映射正确")
    void openAiStandardMapping() throws Exception {
        String json =
                """
        {"prompt_tokens":1200,"completion_tokens":340,"total_tokens":1540}""";
        RawUsage raw = OpenAiUsageNormalizer.parse(MAPPER.readTree(json), false);
        Usage usage = openai.normalize(raw, ctx("openai"));

        assertThat(usage.inputTokens()).isEqualTo(1200L);
        assertThat(usage.outputTokens()).isEqualTo(340L);
        assertThat(usage.totalTokens()).isEqualTo(1540L);
        assertThat(usage.usageSource()).isEqualTo(UsageSource.VENDOR_REPORTED);
        assertThat(usage.trustworthy()).isTrue();
        assertThat(openai.supportedProviders()).containsExactly("openai");
        assertThat(openai.supports("openai")).isTrue();
        assertThat(openai.supports("anthropic")).isFalse();
        assertThat(openai.name()).isEqualTo("OpenAiUsageNormalizer");
    }

    @Test
    @DisplayName("★ OpenAI：cached_tokens 已包含在 prompt_tokens 内，不重复计费")
    void openAiCachedTokensNotDoubleCounted() throws Exception {
        String json =
                """
        {"prompt_tokens":4000,"completion_tokens":100,\
        "prompt_tokens_details":{"cached_tokens":3000},"total_tokens":4100}""";
        Usage usage =
                openai.normalize(
                        OpenAiUsageNormalizer.parse(MAPPER.readTree(json), false), ctx("openai"));

        assertThat(usage.inputTokens())
                .as("OpenAI 的 prompt_tokens 已含缓存命中部分，不能再加一次")
                .isEqualTo(4000L);
        assertThat(usage.cachedInputTokens()).isEqualTo(3000L);
        assertThat(usage.billableInputTokens()).isEqualTo(1000L);
        assertThat(usage.totalTokens()).isEqualTo(4100L);
    }

    @Test
    @DisplayName("OpenAI：completion_tokens_details.reasoning_tokens 被识别")
    void openAiReasoningTokens() throws Exception {
        String json =
                """
        {"prompt_tokens":100,"completion_tokens":200,\
        "completion_tokens_details":{"reasoning_tokens":150}}""";
        Usage usage =
                openai.normalize(
                        OpenAiUsageNormalizer.parse(MAPPER.readTree(json), false), ctx("openai"));
        assertThat(usage.reasoningTokens()).isEqualTo(150L);
        assertThat(usage.reasoningEstimated()).isFalse();
        assertThat(usage.totalTokens()).isEqualTo(450L);
    }

    @Test
    @DisplayName("OpenAI：usage 节点缺失或非对象时安全降级")
    void openAiMissingUsage() {
        Usage fromNull = openai.normalize(OpenAiUsageNormalizer.parse(null, false), ctx("openai"));
        assertThat(fromNull.usageSource()).isEqualTo(UsageSource.ESTIMATED);
        assertThat(fromNull.inputTokens()).as("应回退到估算器而不是记 0").isPositive();
        assertThat(fromNull.totalTokens()).isPositive();

        Usage fromNullRaw = openai.normalize(null, ctx("openai"));
        assertThat(fromNullRaw.usageSource()).isEqualTo(UsageSource.ESTIMATED);
        assertThat(
                        openai.normalize(
                                        OpenAiUsageNormalizer.parse(
                                                MAPPER.createObjectNode(), false),
                                        ctx("openai"))
                                .usageSource())
                .isEqualTo(UsageSource.ESTIMATED);
    }

    @Test
    @DisplayName("OpenAI：maxTokens 兜底（连 prompt 都为空时）")
    void openAiMaxTokensFallback() {
        NormalizeContext emptyCtx =
                new NormalizeContext(null, null, 512, false, "openai", "model-x");
        Usage usage = openai.normalize(RawUsage.empty(), emptyCtx);
        assertThat(usage.inputTokens())
                .as("无 prompt 可估算时按 max_tokens 上界计（UsageSource.FALLBACK 语义）")
                .isEqualTo(512L);
        assertThat(usage.outputTokens()).isZero();
    }

    @Test
    @DisplayName("Anthropic：input_tokens / output_tokens 直映射")
    void anthropicStandardMapping() throws Exception {
        String json =
                """
        {"input_tokens":2000,"output_tokens":500}""";
        Usage usage =
                anthropic.normalize(
                        AnthropicUsageNormalizer.parse(MAPPER.readTree(json), false),
                        ctx("anthropic"));
        assertThat(usage.inputTokens()).isEqualTo(2000L);
        assertThat(usage.outputTokens()).isEqualTo(500L);
        assertThat(anthropic.supportedProviders()).containsExactly("anthropic");
        assertThat(anthropic.supports("anthropic")).isTrue();
        assertThat(anthropic.name()).isEqualTo("AnthropicUsageNormalizer");
    }

    @Test
    @DisplayName("★ Anthropic：input_tokens 不含缓存命中，必须加回以统一口径")
    void anthropicCacheAddedBack() throws Exception {
        String json =
                """
        {"input_tokens":1000,"output_tokens":200,\
        "cache_read_input_tokens":3000,"cache_creation_input_tokens":500}""";
        Usage usage =
                anthropic.normalize(
                        AnthropicUsageNormalizer.parse(MAPPER.readTree(json), false),
                        ctx("anthropic"));

        assertThat(usage.inputTokens())
                .as("Anthropic 的 input_tokens 不含 cache_read/creation，需加回才能与 OpenAI 口径一致")
                .isEqualTo(4500L);
        assertThat(usage.cachedInputTokens()).isEqualTo(3000L);
        assertThat(usage.billableInputTokens()).isEqualTo(1500L);
    }

    @Test
    @DisplayName("Anthropic：无缓存字段时保持原值")
    void anthropicNoCache() throws Exception {
        String json =
                """
        {"input_tokens":800,"output_tokens":120}""";
        Usage usage =
                anthropic.normalize(
                        AnthropicUsageNormalizer.parse(MAPPER.readTree(json), true),
                        ctx("anthropic"));
        assertThat(usage.inputTokens()).isEqualTo(800L);
        assertThat(usage.cachedInputTokens()).isZero();
    }

    @Test
    @DisplayName("Anthropic：usage 缺失时降级为估算")
    void anthropicMissingUsage() {
        Usage usage =
                anthropic.normalize(AnthropicUsageNormalizer.parse(null, false), ctx("anthropic"));
        assertThat(usage.usageSource()).isEqualTo(UsageSource.ESTIMATED);
        assertThat(usage.inputTokens()).isPositive();
    }

    @Test
    @DisplayName("供应商不返回 usage 时，估算值不为 0（PRD 痛点 4 的核心）")
    void estimationNeverZero() {
        NormalizeContext ctx = ctx("openai");
        for (String text : new String[] {"短", "这是一个比较长的中文问题，用来验证估算器", "a".repeat(500)}) {
            Usage usage =
                    openai.normalize(
                            RawUsage.empty(),
                            new NormalizeContext(text, text, null, false, "openai", "m"));
            assertThat(usage.totalTokens())
                    .as("文本 \"%s\" 的估算 token 必须 > 0，否则流式计量会静默失真", text)
                    .isPositive();
            assertThat(usage.usageSource()).isEqualTo(UsageSource.ESTIMATED);
            assertThat(usage.trustworthy()).isFalse();
        }
    }

    @Test
    @DisplayName("RawUsage.anyPresent 判定来源")
    void rawUsagePresence() {
        assertThat(RawUsage.empty().anyPresent()).isFalse();
        assertThat(RawUsage.reported(100L, 50L, 0L, 150L).anyPresent()).isTrue();
        assertThat(RawUsage.reported(100L, 50L, 0L, 150L).usageChunkReceived()).isTrue();
        assertThat(new RawUsage(null, null, null, null, null, true, false).anyPresent())
                .as("只有 reasoningContentPresent 不算「有 usage 字段」")
                .isFalse();
    }

    @Test
    @DisplayName("Usage 静态工厂")
    void usageFactories() {
        Usage reported = Usage.of(10L, 20L, 30L, 5L);
        assertThat(reported.totalTokens()).isEqualTo(60L);
        assertThat(reported.usageSource()).isEqualTo(UsageSource.VENDOR_REPORTED);
        assertThat(reported.reasoningEstimated()).isFalse();

        Usage estimated = Usage.estimated();
        assertThat(estimated.totalTokens()).isZero();
        assertThat(estimated.usageSource()).isEqualTo(UsageSource.ESTIMATED);
        assertThat(estimated.reasoningEstimated()).isTrue();
        assertThat(estimated.streamUsageReported()).isFalse();

        Usage fallback = Usage.fallback(2048L);
        assertThat(fallback.inputTokens()).isEqualTo(2048L);
        assertThat(fallback.usageSource()).isEqualTo(UsageSource.FALLBACK);
        assertThat(Usage.fallback(-5L).inputTokens()).as("负值被夹到 0").isZero();

        assertThat(Usage.estimated(UsageSource.FALLBACK, true).streamUsageReported()).isTrue();
        assertThat(Usage.of(10L, 20L).billableInputTokens()).isEqualTo(10L);
        assertThat(Usage.of(10L, 20L).vendorReported()).isTrue();
    }

    @Test
    @DisplayName("NormalizeContext 静态工厂")
    void normalizeContextFactories() {
        NormalizeContext nonStream = NormalizeContext.nonStreaming("p", "c", 100, "openai", "m");
        assertThat(nonStream.streaming()).isFalse();
        assertThat(nonStream.maxTokens()).isEqualTo(100);

        NormalizeContext stream = NormalizeContext.streaming("p", 100, "openai", "m");
        assertThat(stream.streaming()).isTrue();
        assertThat(stream.completionText()).isNull();
    }
}
