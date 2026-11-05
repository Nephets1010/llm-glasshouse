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
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ContainmentMetric} 的打分测试。
 *
 * <p>这个指标是「幻觉率」的实现基础：命中任一禁用内容即判 0 分。它比关键词召回严格， 因此最容易在边界上出错——空列表要不要给满分、actual 为 null 算不算命中，都必须逐条确认。
 */
class ContainmentMetricTest {

    private static final ContainmentMetric METRIC = ContainmentMetric.INSTANCE;

    @Test
    @DisplayName("name=containment，description 说明看的是禁用内容")
    void nameAndDescription() {
        assertThat(METRIC.name()).isEqualTo("containment");
        assertThat(METRIC.name()).isEqualTo(Metric.NAME_CONTAINMENT);
        assertThat(METRIC.description()).contains("禁用");
    }

    @Test
    @DisplayName("禁用列表为空或 null 时得 1.0000")
    void emptyOrNullForbiddenScoresOne() {
        assertThat(METRIC.scoreOf("随便说点什么", null))
                .as("没有禁用约束就没有可违反的规则，给满分才能让它不拉低整体均分")
                .isEqualByComparingTo("1.0000");
        assertThat(METRIC.scoreOf("随便说点什么", List.of())).isEqualByComparingTo("1.0000");
    }

    @Test
    @DisplayName("actual 为 null 且存在禁用约束时得 0.0000")
    void nullActualScoresZero() {
        assertThat(METRIC.scoreOf(null, List.of("不能退")))
                .as("拿不到输出就无法确认它没触碰禁用内容，按失败处理")
                .isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("命中任一禁用内容得 0.0000")
    void hittingAnyForbiddenScoresZero() {
        assertThat(METRIC.scoreOf("这个订单不能退", List.of("不能退", "概不退换")))
                .isEqualByComparingTo("0.0000");
        assertThat(METRIC.scoreOf("概不退换，抱歉", List.of("不能退", "概不退换")))
                .as("命中列表里第二项同样判 0，不存在「只查第一个」的短路")
                .isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("一条禁用内容都没出现得 1.0000")
    void missingAllForbiddenScoresOne() {
        assertThat(METRIC.scoreOf("根据政策可以退货", List.of("不能退", "概不退换")))
                .isEqualByComparingTo("1.0000");
    }

    @Test
    @DisplayName("null 与空白条目被跳过，不影响判定")
    void blankForbiddenEntriesAreSkipped() {
        assertThat(METRIC.scoreOf("根据政策可以退货", Arrays.asList(null, "   ", "不能退")))
                .as("空白条目如果参与匹配，空串会被任意输出包含，从而把所有用例判成 0 分")
                .isEqualByComparingTo("1.0000");
    }

    @Test
    @DisplayName("输出与禁用词两侧都先做全角转半角")
    void fullWidthOnBothSidesIsNormalized() {
        assertThat(METRIC.scoreOf("ABC", List.of("ＡＢＣ")))
                .as("禁用词用全角写、模型输出半角时必须仍能命中，否则违规内容会漏检")
                .isEqualByComparingTo("0.0000");
        assertThat(METRIC.scoreOf("ＡＢＣ", List.of("ABC"))).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("中文子串命中即可")
    void chineseSubstringMatches() {
        assertThat(METRIC.scoreOf("七日无理由退货", List.of("无理由退货")))
                .as("禁用内容是短语，出现在更长句子里也算触碰")
                .isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("禁用词比输出长时得 1.0000")
    void longerForbiddenTextScoresOne() {
        assertThat(METRIC.scoreOf("退", List.of("七天无理由退货"))).isEqualByComparingTo("1.0000");
    }

    @Test
    @DisplayName("hitsForbidden 只在真命中时返回 true")
    void hitsForbiddenOnlyOnRealHit() {
        assertThat(METRIC.hitsForbidden("这个订单不能退", List.of("不能退"))).isTrue();
        assertThat(METRIC.hitsForbidden("可以退货", List.of("不能退"))).isFalse();
        assertThat(METRIC.hitsForbidden("可以退货", List.of()))
                .as("没用禁用约束时不能报「命中」，否则报告会凭空造出一条幻觉记录")
                .isFalse();
        assertThat(METRIC.hitsForbidden("可以退货", null)).isFalse();
    }

    @Test
    @DisplayName("★ hitsForbidden(null, [..]) 返回 true")
    void hitsForbiddenNullActualReturnsTrue() {
        assertThat(METRIC.hitsForbidden(null, List.of("x")))
                .as(
                        "★ 这是当前行为，不是期望行为，改它需要改主源码 ContainmentMetric.java:87-89："
                                + "hitsForbidden 用「scoreOf 等于 0 且禁用列表非空」判断命中，"
                                + "而 scoreOf(null, 非空) 恰好也返回 0，于是「拿不到输出」被标成「命中禁用内容」。"
                                + "两个语义在这里重合了，调用方若用它统计幻觉条数会偏高，"
                                + "这里留断言让偏差在报告里可见")
                .isTrue();
    }

    @Test
    @DisplayName("score 取具名指标上的预置值，缺省时为 0.0000")
    void scoreUsesPrecomputedValue() {
        EvalResult precomputed =
                EvalResult.success("c-1", "可以退货", null, null, 1L)
                        .withScores(Map.of("containment", new BigDecimal("1.0")));
        assertThat(METRIC.score(precomputed)).isEqualByComparingTo("1.0000");
        assertThat(METRIC.score(EvalResult.success("c-2", "可以退货", null, null, 1L)))
                .as("缺省时给 0 而不是 1，避免「没算分」被门禁当成通过")
                .isEqualByComparingTo("0.0000");
    }
}
