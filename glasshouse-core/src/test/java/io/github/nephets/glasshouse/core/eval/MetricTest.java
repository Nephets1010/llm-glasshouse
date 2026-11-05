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

/**
 * {@link Metric} 的接口默认实现与静态归一化工具测试。
 *
 * <p>归一化是所有指标共用的最后一环：五个内置指标算出的原始值都要经过 {@code Metric.normalize} 落到 [0,1]。
 * 这一层的行为一旦变化，五个指标的得分会同时漂移，门禁阈值全部要重新校准， 所以边界值（越界、无穷、NaN）必须逐个钉住。
 */
class MetricTest {

    /** 只实现两个必需方法的最小指标，用来验证接口默认实现。 */
    private static final Metric MINIMAL =
            new Metric() {

                @Override
                public String name() {
                    return "minimal";
                }

                @Override
                public BigDecimal score(EvalResult result) {
                    return Metric.normalize(0.0d);
                }
            };

    @Test
    @DisplayName("normalize 按 HALF_UP 保留四位小数")
    void normalizeRoundsHalfUpToFourDecimals() {
        assertThat(Metric.normalize(0.5d)).isEqualByComparingTo("0.5000");
        assertThat(Metric.normalize(0.5d).scale()).as("报告与控制台输出统一显示四位小数，人工比对时不必再对齐位数").isEqualTo(4);
        assertThat(Metric.normalize(0.00005d))
                .as("0.00005 的第五位是 5，HALF_UP 必须进位到 0.0001；换成 HALF_EVEN 会得到 0.0000")
                .isEqualByComparingTo("0.0001");
        assertThat(Metric.normalize(0.12345d)).isEqualByComparingTo("0.1235");
    }

    @Test
    @DisplayName("normalize 把越界值与无穷裁剪到 [0,1]")
    void normalizeClampsOutOfRangeValues() {
        assertThat(Metric.normalize(-1.0d)).isEqualByComparingTo("0.0000");
        assertThat(Metric.normalize(2.0d)).isEqualByComparingTo("1.0000");
        assertThat(Metric.normalize(Double.POSITIVE_INFINITY))
                .as("除零算出的正无穷必须落在 1.0000，而不是抛 NumberFormatException")
                .isEqualByComparingTo("1.0000");
        assertThat(Metric.normalize(Double.NEGATIVE_INFINITY)).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("normalize 把 NaN 归零（不抛异常）")
    void normalizeTurnsNaNIntoZero() {
        assertThat(Metric.normalize(Double.NaN))
                .as("没有这条守卫时 Math.min(1.0, NaN) 仍是 NaN，BigDecimal.valueOf 会直接抛异常把整轮评测打断")
                .isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("description 默认等于 name")
    void descriptionDefaultsToName() {
        assertThat(MINIMAL.description())
                .as("自定义指标只写 name 也能拿到可读描述，报告不会出现空白列")
                .isEqualTo("minimal");
    }

    @Test
    @DisplayName("direction 默认 HIGHER_IS_BETTER，枚举两个方向都可取")
    void directionDefaultsToHigherIsBetter() {
        assertThat(MINIMAL.direction()).isEqualTo(Metric.Direction.HIGHER_IS_BETTER);
        assertThat(Metric.Direction.values())
                .as("LOWER_IS_BETTER 保留给直接暴露原始值的自定义指标，首版内置指标都不会用到")
                .containsExactly(
                        Metric.Direction.HIGHER_IS_BETTER, Metric.Direction.LOWER_IS_BETTER);
        assertThat(Metric.Direction.valueOf("LOWER_IS_BETTER"))
                .isEqualTo(Metric.Direction.LOWER_IS_BETTER);
    }

    @Test
    @DisplayName("★ 五个指标的 score(EvalResult) 都是「预置分优先」，但缺省值与键名口径不一致")
    void precomputedScoreConventionAcrossMetrics() {
        EvalResult bare = EvalResult.success("c-1", "答", null, null, 1L);
        assertThat(CostMetric.INSTANCE.score(bare))
                .as("★ 成本指标缺省给 1.0000：没接计量就不扣分，否则没配价格的评测会全线判失败")
                .isEqualByComparingTo("1.0000");
        assertThat(ContainmentMetric.INSTANCE.score(bare)).isEqualByComparingTo("0.0000");
        assertThat(JsonSchemaMetric.INSTANCE.score(bare)).isEqualByComparingTo("0.0000");
        assertThat(KeywordRecallMetric.INSTANCE.score(bare)).isEqualByComparingTo("0.0000");
        assertThat(ExactMatchMetric.INSTANCE.score(bare)).isEqualByComparingTo("0.0000");

        EvalResult byMetricName =
                bare.withScores(Map.of(Metric.NAME_KEYWORD_RECALL, new BigDecimal("0.25")));
        assertThat(KeywordRecallMetric.INSTANCE.score(byMetricName))
                .as("四个指标按 name() 取预置分，EvalRunner 写回时用的也是 name()")
                .isEqualByComparingTo("0.25");

        EvalResult plainAccuracyKey =
                bare.withScores(Map.of(Metric.NAME_ACCURACY, new BigDecimal("0.25")));
        assertThat(ExactMatchMetric.INSTANCE.score(plainAccuracyKey))
                .as(
                        "★ 现状：精确匹配读的是 accuracy#用例id 这个带 id 的键，"
                                + "所以直接写 accuracy 的预置分读不到，被当成「没算过」而返回 0.0000。"
                                + "其余四个指标的键名口径与它不一致")
                .isEqualByComparingTo("0.0000");

        EvalResult perCaseKey =
                bare.withScores(Map.of(Metric.NAME_ACCURACY + "#c-1", new BigDecimal("0.25")));
        assertThat(ExactMatchMetric.INSTANCE.score(perCaseKey))
                .as("带 id 的键能读到，这正是 EvalRunner 回写精确匹配分时用的形式")
                .isEqualByComparingTo("0.25");
        assertThat(KeywordRecallMetric.INSTANCE.score(perCaseKey))
                .as("反过来，带 id 的键对按 name() 取分的指标无效")
                .isEqualByComparingTo("0.0000");
    }
}
