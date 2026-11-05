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

package io.github.nephets.glasshouse.core.eval;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CostMetric} 的打分与预算校验测试。
 *
 * <p>成本指标用的是 {@code 1 / (1 + cost / budget)} 这条平滑曲线而不是线性扣分，
 * 目的是让「超了一点」和「超了很多」在得分上仍然可区分。这条曲线的几个关键点必须逐个钉住。
 */
class CostMetricTest {

    @Test
    @DisplayName("默认预算为 1 元")
    void defaultBudgetIsOne() {
        assertThat(new CostMetric().budgetCny()).isEqualByComparingTo("1");
        assertThat(CostMetric.INSTANCE.budgetCny()).isEqualByComparingTo("1");
        assertThat(CostMetric.DEFAULT_BUDGET_CNY).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("预算为 null、0 或负数时构造即拒绝")
    void rejectsNullOrNonPositiveBudget() {
        assertThatThrownBy(() -> new CostMetric(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("成本预算必须为正数");
        assertThatThrownBy(() -> new CostMetric(BigDecimal.ZERO))
                .as("预算为 0 会让 1/(1+x) 在 x 为任意正数时都退化，必须 fail-fast")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("成本预算必须为正数");
        assertThatThrownBy(() -> new CostMetric(new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("成本预算必须为正数");
    }

    @Test
    @DisplayName("name=cost，description 说明预算是参照点")
    void nameAndDescription() {
        assertThat(CostMetric.INSTANCE.name()).isEqualTo("cost");
        assertThat(CostMetric.INSTANCE.name()).isEqualTo(Metric.NAME_COST);
        assertThat(CostMetric.INSTANCE.description()).contains("预算");
    }

    @Test
    @DisplayName("成本为 null 时得 1.0000")
    void nullCostScoresOne() {
        assertThat(CostMetric.INSTANCE.scoreOf(null))
                .as("没有成本数据说明这次调用没花钱或没接计量，不扣分")
                .isEqualByComparingTo("1.0000");
    }

    @Test
    @DisplayName("负成本被 max 到 0，得 1.0000")
    void negativeCostClampedToZero() {
        assertThat(CostMetric.INSTANCE.scoreOf(new BigDecimal("-5")))
                .as("负成本是计量错误，向下取 0 比算出大于 1 的得分安全")
                .isEqualByComparingTo("1.0000");
    }

    @Test
    @DisplayName("成本为 0 时得 1.0000")
    void zeroCostScoresOne() {
        assertThat(CostMetric.INSTANCE.scoreOf(BigDecimal.ZERO)).isEqualByComparingTo("1.0000");
    }

    @Test
    @DisplayName("成本等于预算时得 0.5000")
    void costEqualToBudgetScoresHalf() {
        assertThat(CostMetric.INSTANCE.scoreOf(BigDecimal.ONE))
                .as("1/(1+1) = 0.5，这是曲线的中点，报告里最常被引用")
                .isEqualByComparingTo("0.5000");
    }

    @Test
    @DisplayName("三倍预算得 0.2500")
    void tripleBudgetScoresQuarter() {
        assertThat(CostMetric.INSTANCE.scoreOf(new BigDecimal("3")))
                .as("1/(1+3) = 0.25")
                .isEqualByComparingTo("0.2500");
    }

    @Test
    @DisplayName("十倍预算得 0.0909")
    void tenTimesBudgetScoresAboutNinePercent() {
        assertThat(CostMetric.INSTANCE.scoreOf(new BigDecimal("10")))
                .as("1/11 保留四位小数是 0.0909")
                .isEqualByComparingTo("0.0909");
    }

    @Test
    @DisplayName("★ 极大成本四舍五入后得 0.0000")
    void hugeCostRoundsDownToZero() {
        CostMetric metric = new CostMetric(new BigDecimal("2"));
        BigDecimal score = metric.scoreOf(new BigDecimal("1000000"));
        assertThat(score)
                .as(
                        "★ 这是当前行为，不是期望行为，改它需要改主源码 Metric.java:98："
                                + "四位小数下 1/500001 会四舍五入成 0.0000。"
                                + "注意不要再断言它 isPositive ——超过五万倍预算时曲线本来就落到四位小数的 0，"
                                + "这不是 bug，但意味着极端超支的用例彼此之间已经不可区分")
                .isEqualByComparingTo("0.0000");
        assertThat(score.signum()).isZero();
    }

    @Test
    @DisplayName("一毛钱对 1 元预算得 0.9091")
    void tenCentsAgainstOneYuanBudget() {
        assertThat(CostMetric.INSTANCE.scoreOf(new BigDecimal("0.10")))
                .as("1/1.1 保留四位小数是 0.9091，常见单价区间里的代表值")
                .isEqualByComparingTo("0.9091");
    }

    @Test
    @DisplayName("结果里没有预置成本得分时返回 1.0000")
    void scoreWithoutPrecomputedValueIsOne() {
        assertThat(CostMetric.INSTANCE.score(EvalResult.success("m-1", "答", null, null, 1L)))
                .as("与其他四个指标相反：成本缺省给满分，避免「没接计量」把门禁卡死")
                .isEqualByComparingTo("1.0000");
    }

    @Test
    @DisplayName("结果里带预置成本得分时取预置值")
    void scoreUsesPrecomputedValue() {
        EvalResult precomputed =
                EvalResult.success("m-2", "答", null, null, 1L)
                        .withScores(Map.of("cost", new BigDecimal("0.3")));
        assertThat(CostMetric.INSTANCE.score(precomputed)).isEqualByComparingTo("0.3");
    }
}
