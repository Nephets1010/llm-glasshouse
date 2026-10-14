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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 国产模型计量口径测试（PRD 仓库③ P0-7 ★ 差异化支柱）。
 *
 * <p>覆盖通义 / 智谱 / DeepSeek / Kimi / 豆包五家的 usage 字段口径， 断言与官方文档字段一致。
 */
class CnUsageNormalizerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CnUsageNormalizer normalizer = new CnUsageNormalizer();

    private static NormalizeContext ctx(String prompt) {
        return NormalizeContext.nonStreaming(prompt, "响应内容", 1024, "deepseek", "deepseek-chat");
    }

    @Nested
    @DisplayName("通义千问 DashScope")
    class DashScope {

        @Test
        @DisplayName("input_tokens / output_tokens 顶层字段")
        void standardFields() throws Exception {
            String json =
                    """
          {"input_tokens":1500,"output_tokens":300,"total_tokens":1800}""";
            Usage result =
                    normalizer.normalize(
                            normalizer.parse(
                                    CnUsageNormalizer.PROVIDER_DASHSCOPE,
                                    MAPPER.readTree(json),
                                    false),
                            ctx("问题"));
            assertThat(result.inputTokens()).isEqualTo(1500L);
            assertThat(result.outputTokens()).isEqualTo(300L);
            assertThat(result.usageSource()).isEqualTo(UsageSource.VENDOR_REPORTED);
            assertThat(result.totalTokens()).isEqualTo(1800L);
        }

        @Test
        @DisplayName("qwen3 thinking 的 reasoning_tokens 顶层字段")
        void reasoningTokens() throws Exception {
            String json =
                    """
          {"input_tokens":800,"output_tokens":200,"reasoning_tokens":512}""";
            RawUsage raw =
                    normalizer.parse(
                            CnUsageNormalizer.PROVIDER_DASHSCOPE, MAPPER.readTree(json), false);
            Usage usage = normalizer.normalize(raw, ctx("问题"));
            assertThat(usage.reasoningTokens()).isEqualTo(512L);
            assertThat(usage.reasoningEstimated())
                    .as("供应商返回了 reasoning 字段，reasoningEstimated 必须为 false")
                    .isFalse();
            assertThat(usage.totalTokens()).isEqualTo(1512L);
        }
    }

    @Nested
    @DisplayName("智谱 GLM")
    class Zhipu {

        @Test
        @DisplayName("prompt_tokens / completion_tokens 口径")
        void standardFields() throws Exception {
            String json =
                    """
          {"prompt_tokens":2000,"completion_tokens":500,"total_tokens":2500}""";
            RawUsage raw =
                    normalizer.parse(
                            CnUsageNormalizer.PROVIDER_ZHIPU, MAPPER.readTree(json), false);
            Usage usage = normalizer.normalize(raw, ctx("问题"));
            assertThat(usage.inputTokens()).isEqualTo(2000L);
            assertThat(usage.outputTokens()).isEqualTo(500L);
        }

        @Test
        @DisplayName("GLM 不返回独立 reasoning 字段时诚实标记为估算，而不是编造")
        void noReasoningFieldIsEstimated() throws Exception {
            String json =
                    """
          {"prompt_tokens":1000,"completion_tokens":200}""";
            RawUsage raw =
                    normalizer.parse(
                            CnUsageNormalizer.PROVIDER_ZHIPU, MAPPER.readTree(json), false);
            Usage usage = normalizer.normalize(raw, ctx("问题"));
            assertThat(usage.reasoningTokens()).as("供应商没给推理 token 必须是 0（契约 USG-001 要求）").isZero();
            assertThat(usage.reasoningEstimated())
                    .as("供应商没给推理 token 时 reasoningEstimated 必须为 true，让数据能区分真假")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("DeepSeek")
    class DeepSeek {

        @Test
        @DisplayName("prompt_cache_hit_tokens 识别为缓存命中输入")
        void cacheHitTokens() throws Exception {
            String json =
                    """
          {"prompt_tokens":4000,"completion_tokens":1000,\
          "prompt_cache_hit_tokens":3000,"prompt_cache_miss_tokens":1000}""";
            com.fasterxml.jackson.databind.JsonNode node = MAPPER.readTree(json);
            RawUsage raw = normalizer.parse(CnUsageNormalizer.PROVIDER_DEEPSEEK, node, false);
            Usage usage = normalizer.normalize(raw, ctx("很长的中文问题".repeat(100)));
            assertThat(usage.inputTokens()).isEqualTo(4000L);
            assertThat(usage.cachedInputTokens()).isEqualTo(3000L);
            assertThat(usage.billableInputTokens()).isEqualTo(1000L);
            assertThat(CnUsageNormalizer.deepseekCacheMissTokens(node)).isEqualTo(1000L);
            assertThat(CnUsageNormalizer.deepseekCacheHitRatio(node))
                    .isCloseTo(0.75d, org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test
        @DisplayName("DeepSeek-R1 无独立推理字段时不编造数字")
        void noReasoningField() throws Exception {
            String json =
                    """
          {"prompt_tokens":1000,"completion_tokens":2000}""";
            RawUsage raw =
                    normalizer.parse(
                            CnUsageNormalizer.PROVIDER_DEEPSEEK, MAPPER.readTree(json), false);
            Usage usage = normalizer.normalize(raw, ctx("问题"));
            assertThat(usage.reasoningTokens()).isZero();
            assertThat(usage.reasoningEstimated()).isTrue();
            assertThat(usage.totalTokens()).isEqualTo(3000L);
        }
    }

    @Nested
    @DisplayName("Kimi / 月之暗面")
    class Moonshot {

        @Test
        @DisplayName("标准字段 + cached_tokens")
        void standardFields() throws Exception {
            String json =
                    """
          {"prompt_tokens":3000,"completion_tokens":500,"cached_tokens":2000}""";
            RawUsage raw =
                    normalizer.parse(
                            CnUsageNormalizer.PROVIDER_MOONSHOT, MAPPER.readTree(json), false);
            Usage usage = normalizer.normalize(raw, ctx("问题"));
            assertThat(usage.inputTokens()).isEqualTo(3000L);
            assertThat(usage.cachedInputTokens()).isEqualTo(2000L);
            assertThat(usage.reasoningEstimated()).isTrue();
        }
    }

    @Nested
    @DisplayName("豆包 / 火山方舟")
    class Doubao {

        @Test
        @DisplayName("reasoning 在 completion_tokens_details 嵌套里")
        void nestedReasoning() throws Exception {
            String json =
                    """
          {"prompt_tokens":1200,"completion_tokens":400,\
          "completion_tokens_details":{"reasoning_tokens":256}}""";
            RawUsage raw =
                    normalizer.parse(
                            CnUsageNormalizer.PROVIDER_DOUBAO, MAPPER.readTree(json), false);
            Usage usage = normalizer.normalize(raw, ctx("问题"));
            assertThat(usage.reasoningTokens())
                    .as("豆包的推理 token 在 completion_tokens_details.reasoning_tokens")
                    .isEqualTo(256L);
            assertThat(usage.reasoningEstimated()).isFalse();
            assertThat(usage.totalTokens()).isEqualTo(1856L);
        }
    }

    @Test
    @DisplayName("支持矩阵与契约 ProviderId 枚举一致")
    void supportMatrixMatchesContract() {
        assertThat(normalizer.supportedProviders())
                .containsExactlyInAnyOrder("dashscope", "zhipu", "deepseek", "moonshot", "doubao");
        assertThat(CnUsageNormalizer.isCnProvider("deepseek")).isTrue();
        assertThat(CnUsageNormalizer.isCnProvider("openai")).isFalse();
        assertThat(CnUsageNormalizer.isCnProvider(null)).isFalse();
        assertThat(CnUsageNormalizer.supportMatrix()).containsEntry("dashscope", "通义千问 DashScope");
        assertThat(normalizer.supports("zhipu")).isTrue();
        assertThat(normalizer.supports("deepseek")).isTrue();
        assertThat(normalizer.supports(null)).isFalse();
        assertThat(normalizer.name()).isEqualTo("CnUsageNormalizer");
    }

    @ParameterizedTest(name = "{0} 的 usage=null 节点不抛异常，退回估算")
    @CsvSource({"dashscope", "zhipu", "deepseek", "moonshot", "doubao"})
    @DisplayName("usage 节点缺失时优雅降级为估算")
    void nullUsageNodeDegrades(String provider) throws Exception {
        RawUsage raw = normalizer.parse(provider, null, false);
        Usage usage = normalizer.normalize(raw, ctx("这是一个比较长的中文问题，用来触发估算器"));
        assertThat(usage.usageSource()).isEqualTo(UsageSource.ESTIMATED);
        assertThat(usage.inputTokens()).as("没有供应商 usage 时应回退到估算器，而不是记 0（PRD 痛点 4）").isPositive();
        assertThat(usage.streamUsageReported()).isFalse();
    }

    @Test
    @DisplayName("流式未收到 usage 块时 streamUsageReported=false 但仍有估算值")
    void streamWithoutUsageChunk() throws Exception {
        String json =
                """
        {"prompt_tokens":500,"completion_tokens":100}""";
        RawUsage raw =
                normalizer.parse(CnUsageNormalizer.PROVIDER_DEEPSEEK, MAPPER.readTree(json), false);
        Usage usage =
                normalizer.normalize(
                        raw, NormalizeContext.streaming("中文问题", 512, "deepseek", "deepseek-chat"));
        assertThat(usage.streamUsageReported())
                .as("流式下未收到 usage 块，streamUsageReported 必须为 false，让成本报表能区分真假")
                .isFalse();
        assertThat(usage.inputTokens()).isEqualTo(500L);
    }

    @Test
    @DisplayName("流式收到 usage 块时 streamUsageReported=true")
    void streamWithUsageChunk() throws Exception {
        String json =
                """
        {"prompt_tokens":500,"completion_tokens":100}""";
        RawUsage raw =
                normalizer.parse(CnUsageNormalizer.PROVIDER_DEEPSEEK, MAPPER.readTree(json), true);
        Usage usage =
                normalizer.normalize(
                        raw, NormalizeContext.streaming("中文问题", 512, "deepseek", "deepseek-chat"));
        assertThat(usage.streamUsageReported()).isTrue();
    }

    @Test
    @DisplayName("cachedInputTokens 超过 inputTokens 时被夹紧，不让 Usage 构造器抛异常")
    void cachedIsClamped() throws Exception {
        // 脏数据：上游把 cached 报得比 input 还大
        String json =
                """
        {"prompt_tokens":100,"completion_tokens":10,"cached_tokens":9999}""";
        RawUsage raw =
                normalizer.parse(CnUsageNormalizer.PROVIDER_DEEPSEEK, MAPPER.readTree(json), false);
        Usage usage = normalizer.normalize(raw, ctx("问题"));
        assertThat(usage.cachedInputTokens()).isEqualTo(100L);
        assertThat(usage.billableInputTokens()).isZero();
    }

    @Test
    @DisplayName("Usage 构造器拒绝负值 token")
    void usageRejectsNegative() {
        assertThatThrownBy(
                        () ->
                                new Usage(
                                        -1L,
                                        0L,
                                        0L,
                                        0L,
                                        0L,
                                        UsageSource.VENDOR_REPORTED,
                                        false,
                                        false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("禁止负值");
        assertThatThrownBy(
                        () ->
                                new Usage(
                                        10L,
                                        0L,
                                        0L,
                                        20L,
                                        0L,
                                        UsageSource.VENDOR_REPORTED,
                                        false,
                                        false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不得超过");
    }

    @Test
    @DisplayName("估算器对中文的估算结果显著小于按 ASCII 计算")
    void estimatorHandlesChinese() {
        TokenEstimator estimator = CharHeuristicTokenEstimator.getInstance();
        String chinese = "退货政策是什么我想问一下七天无理由退货的具体条件";
        String ascii = "abcdefghijklmnopqrstuvwxyz0123456789abcdefghij";
        long chineseTokens = estimator.estimateTokens(chinese);
        long asciiTokens = estimator.estimateTokens(ascii);
        assertThat(chineseTokens).as("27 个汉字按 1.6 字符/token 估算约 17 token").isBetween(10L, 25L);
        assertThat(asciiTokens).as("40 个 ASCII 字符按 4 字符/token 估算约 10 token").isBetween(5L, 15L);
        assertThat(estimator.estimateTokens(null)).isZero();
        assertThat(estimator.estimateTokens("")).isZero();
        assertThat(estimator.name()).isEqualTo("CharHeuristicTokenEstimator");
    }
}
