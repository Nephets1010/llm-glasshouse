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

package io.github.nephets.glasshouse.core.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;

import io.github.nephets.glasshouse.core.usage.Usage;
import io.github.nephets.glasshouse.core.usage.UsageSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 成本计算测试（PRD 仓库③ P0-3 验收标准：与供应商账单误差 &lt; 1%）。
 *
 * <p><b>这 8 个用例与 {@code llm-switchyard} 的同名测试数值完全一致</b>——这是跨仓一致性的回归锁。 任何一方改了成本公式，另一方会红。
 */
class CostCalculatorTest {

    private final CostCalculator calculator = CostCalculator.getInstance();

    private static PriceEntry price(String in, String out, String cached) {
        return new PriceEntry(
                "deepseek",
                "deepseek-chat",
                "CNY",
                PriceUnit.PER_1K_TOKENS,
                new BigDecimal(in),
                new BigDecimal(out),
                null,
                cached == null ? null : new BigDecimal(cached),
                LocalDate.parse("2026-01-01"),
                PriceSource.OFFICIAL);
    }

    @Nested
    @DisplayName("公式（契约 PRC-001，三仓逐字一致）")
    class Formula {

        @Test
        @DisplayName("用例 1：纯输入 + 纯输出")
        void case1InputAndOutputOnly() {
            // 1000 输入 × 0.002 + 1000 输出 × 0.0008 = 0.0028
            BigDecimal cost =
                    calculator.compute(Usage.of(1000L, 1000L), price("0.002", "0.0008", null));
            assertThat(cost).isEqualByComparingTo("0.00280000");
            assertThat(cost.scale()).isEqualTo(CostCalculator.COST_SCALE);
        }

        @Test
        @DisplayName("用例 2：缓存命中不重复计费")
        void case2CachedInputNotDoubleCounted() {
            // 4000 输入（其中 3000 命中缓存）× 0.002 + 1000 输出 × 0.0008
            // 计费输入 = 4000 - 3000 = 1000 → 1000/1000 × 0.002
            // 缓存输入 = 3000        → 3000/1000 × 0.00005
            // 合计 = 0.002 + 0.00015 + 0.0008 = 0.00295
            Usage usage =
                    new Usage(
                            4000L, 1000L, 0L, 3000L, 0L, UsageSource.VENDOR_REPORTED, false, false);
            BigDecimal cost = calculator.compute(usage, price("0.002", "0.0008", "0.00005"));
            assertThat(cost)
                    .as("缓存命中的 3000 token 必须按缓存价计，而不是按输入价重复计")
                    .isEqualByComparingTo("0.00295000");
        }

        @Test
        @DisplayName("用例 3：缓存价缺失时按输入价计（不优惠）")
        void case3CachedFallsBackToInputPrice() {
            // 2000 输入（其中 500 缓存）+ 500 输出，缓存价未配置
            // 计费输入 1500 × 0.002 + 缓存 500 × 0.002（回退）+ 500 × 0.0008
            // = 0.003 + 0.001 + 0.0004 = 0.0044
            Usage usage =
                    new Usage(2000L, 500L, 0L, 500L, 0L, UsageSource.VENDOR_REPORTED, false, false);
            BigDecimal cost = calculator.compute(usage, price("0.002", "0.0008", null));
            assertThat(cost).isEqualByComparingTo("0.00440000");
        }

        @Test
        @DisplayName("用例 4：推理 token 按独立价计")
        void case4ReasoningUsesOwnPrice() {
            PriceEntry withReasoning =
                    new PriceEntry(
                            "dashscope",
                            "qwen-max",
                            "CNY",
                            PriceUnit.PER_1K_TOKENS,
                            new BigDecimal("0.02"),
                            new BigDecimal("0.06"),
                            new BigDecimal("0.02"),
                            null,
                            LocalDate.parse("2026-01-01"),
                            PriceSource.OFFICIAL);
            // 1000 输入 × 0.02 + 1000 输出 × 0.06 + 4000 推理 × 0.02 = 0.02 + 0.06 + 0.08 = 0.16
            Usage usage =
                    new Usage(
                            1000L, 1000L, 4000L, 0L, 0L, UsageSource.VENDOR_REPORTED, false, false);
            assertThat(calculator.compute(usage, withReasoning)).isEqualByComparingTo("0.16000000");
        }

        @Test
        @DisplayName("用例 5：推理价缺失时按输出价计")
        void case5ReasoningFallsBackToOutputPrice() {
            // 1000 输入 × 0.02 + 1000 输出 × 0.06 + 4000 推理 × 0.06 = 0.02 + 0.06 + 0.24 = 0.32
            Usage usage =
                    new Usage(
                            1000L, 1000L, 4000L, 0L, 0L, UsageSource.VENDOR_REPORTED, false, false);
            assertThat(calculator.compute(usage, price("0.02", "0.06", null)))
                    .as("reasoningPricePerUnit 为 null 时按 outputPricePerUnit 计（契约约定）")
                    .isEqualByComparingTo("0.32000000");
        }

        @Test
        @DisplayName("用例 6：全部输入命中缓存")
        void case6AllInputCached() {
            // 5000 输入全缓存 × 0.00005 + 0 输出 = 0.00025
            Usage usage =
                    new Usage(5000L, 0L, 0L, 5000L, 0L, UsageSource.VENDOR_REPORTED, false, false);
            assertThat(calculator.compute(usage, price("0.002", "0.0008", "0.00005")))
                    .isEqualByComparingTo("0.00025000");
        }

        @Test
        @DisplayName("用例 7：token 为 0 时成本为 0")
        void case7ZeroTokens() {
            assertThat(calculator.compute(Usage.of(0L, 0L), price("0.002", "0.0008", null)))
                    .isEqualByComparingTo("0.00000000");
        }

        @Test
        @DisplayName("用例 8：不足 1000 token 的按比例计算，不四舍五入到 0")
        void case8FractionalTokens() {
            // 999 输入 × 0.002 / 1000 = 0.001998；1 输出 × 0.0008 / 1000 = 0.0000008
            assertThat(calculator.compute(Usage.of(999L, 1L), price("0.002", "0.0008", null)))
                    .isEqualByComparingTo("0.00199880");
        }
    }

    @Test
    @DisplayName("精度铁律：金额禁用 double，输出 scale 恒为 8")
    void scaleIsAlwaysEight() {
        BigDecimal cost =
                calculator.compute(Usage.of(1L, 1L), price("0.123456789", "0.987654321", null));
        assertThat(cost.scale()).isEqualTo(8);
        // 不用 doesNotContain(String)：AssertJ 会解析到 char... 重载，
        // 对含非 char 参数报「需要 int,float」。这里用正则一次性断言。
        assertThat(cost.toString())
                .as("必须是 BigDecimal 的精确十进制（scale=8），不能出现 double 的二进制误差或科学计数法")
                .matches("-?[0-9]+\\.[0-9]{8}");
        assertThat(new java.math.BigDecimal(calculator.format(cost)).scale())
                .as("展示层 scale 恒为 4")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("computeOrZero：价格缺失时返回 0 而不是抛异常（观测不得让业务失败）")
    void computeOrZeroNeverThrows() {
        assertThat(calculator.computeOrZero(Usage.of(100L, 100L), null))
                .isEqualByComparingTo("0.00000000");
        assertThat(calculator.computeOrZero(null, price("0.1", "0.1", null)))
                .isEqualByComparingTo("0.00000000");
    }

    @Test
    @DisplayName("compute 对 null 入参 fail-fast")
    void computeRejectsNulls() {
        assertThatThrownBy(() -> calculator.compute(null, price("0.1", "0.1", null)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> calculator.compute(Usage.of(1L, 1L), null))
                .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest(name = "误差 {0}% 时判定为 < 1%")
    @ValueSource(doubles = {0.0d, 0.5d, 0.99d})
    @DisplayName("相对误差计算正确，且 < 1% 的验收口径可机器判定")
    void relativeErrorWithinOnePercent(double errorPercent) {
        // 「供应商账单」基准为 1000
        BigDecimal expected = BigDecimal.valueOf(1000.0d);
        BigDecimal actual = expected.multiply(BigDecimal.valueOf(1.0d + errorPercent / 100.0d));
        double error = calculator.relativeError(actual, expected);
        assertThat(error).isLessThan(0.01d);
    }

    @Test
    @DisplayName("相对误差超过 1% 时能被检出")
    void relativeErrorDetectsRegression() {
        double error =
                calculator.relativeError(BigDecimal.valueOf(1010.0d), BigDecimal.valueOf(1000.0d));
        assertThat(error).isGreaterThanOrEqualTo(0.01d);
        assertThat(calculator.relativeError(BigDecimal.ZERO, BigDecimal.ZERO)).isEqualTo(0.0d);
        assertThat(calculator.relativeError(BigDecimal.ONE, BigDecimal.ZERO)).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("PriceEntry 校验：币种必须 CNY，价格不得为负")
    void priceEntryValidation() {
        assertThatThrownBy(
                        () ->
                                new PriceEntry(
                                        "openai",
                                        "gpt-4o",
                                        "USD",
                                        PriceUnit.PER_1K_TOKENS,
                                        BigDecimal.ONE,
                                        BigDecimal.ONE,
                                        null,
                                        null,
                                        LocalDate.parse("2026-01-01"),
                                        PriceSource.OFFICIAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CNY");

        assertThatThrownBy(
                        () ->
                                new PriceEntry(
                                        "openai",
                                        "gpt-4o",
                                        "CNY",
                                        PriceUnit.PER_1K_TOKENS,
                                        new BigDecimal("-0.1"),
                                        BigDecimal.ONE,
                                        null,
                                        null,
                                        LocalDate.parse("2026-01-01"),
                                        PriceSource.OFFICIAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不得为负");

        assertThatThrownBy(
                        () ->
                                new PriceEntry(
                                        "openai",
                                        "",
                                        "CNY",
                                        PriceUnit.PER_1K_TOKENS,
                                        BigDecimal.ONE,
                                        BigDecimal.ONE,
                                        null,
                                        null,
                                        LocalDate.parse("2026-01-01"),
                                        PriceSource.OFFICIAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        "deepseek, deepseek-chat, deepseek/deepseek-chat",
        "dashscope, qwen-max, dashscope/qwen-max",
        "openai, gpt-4o-mini, openai/gpt-4o-mini"
    })
    @DisplayName("价格表查找键格式为 provider/model")
    void lookupKeyFormat(String provider, String model, String expected) {
        assertThat(PriceEntry.lookupKey(provider, model)).isEqualTo(expected);
        assertThat(price("0.1", "0.1", null).lookupKey()).isEqualTo("deepseek/deepseek-chat");
    }
}
