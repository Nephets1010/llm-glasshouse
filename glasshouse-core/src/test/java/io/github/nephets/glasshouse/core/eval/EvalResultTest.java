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
import java.time.Instant;
import java.util.Map;

import io.github.nephets.glasshouse.core.usage.Usage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link EvalResult} 的值语义与「效果是否达标」判定测试。
 *
 * <p>{@code passed()} 只看有没有跑挂，{@code meetsThreshold()} 才看答得好不好，两者混用会让回归门禁完全漏掉效果退化。
 * 本类的重点就是把这两个判定的边界分清楚。
 */
class EvalResultTest {

    /**
     * 构造一条带得分的结果。
     *
     * @param scores 指标得分
     * @return 结果
     */
    private static EvalResult scored(Map<String, BigDecimal> scores) {
        return new EvalResult("c-1", "答", scores, null, BigDecimal.ZERO, null, 1L, Instant.EPOCH);
    }

    @Test
    @DisplayName("scoreOf 未命中时返回 ZERO（scale 为 0）")
    void scoreOfMissingReturnsZero() {
        assertThat(scored(Map.of()).scoreOf("accuracy"))
                .as("返回的是 BigDecimal.ZERO 常量而非 0.0000，比较时只能用数值相等")
                .isEqualByComparingTo("0");
        assertThat(scored(Map.of()).scoreOf("accuracy").scale()).isZero();
    }

    @Test
    @DisplayName("一条指标都没算时 meetsThreshold 返回 false")
    void meetsThresholdFalseWhenNothingScored() {
        assertThat(scored(Map.of()).meetsThreshold(EvalResult.PASS_SCORE_THRESHOLD))
                .as("没有得分不等于达标，否则「什么都没测」会被当成通过")
                .isFalse();
    }

    @Test
    @DisplayName("★ 只有 quality 三项参与达标判定，cost 与 jsonSchema 不参与")
    void onlyQualityMetricsCount() {
        assertThat(scored(Map.of("cost", new BigDecimal("1.0000"))).meetsThreshold(null))
                .as("★ 这是设计选择：cost 是「越低越好」，放进来会让省钱但答错的用例判达标；" + "改它需要改主源码 EvalResult.java:158-159")
                .isFalse();
        assertThat(scored(Map.of("jsonSchema", new BigDecimal("1.0000"))).meetsThreshold(null))
                .as("非结构化用例的 jsonSchema 恒为 0，参与判定会让它们永远不达标")
                .isFalse();
        assertThat(scored(Map.of("accuracy", new BigDecimal("1.0000"))).meetsThreshold(null))
                .as("只有质量指标在场时才会给出 true")
                .isTrue();
    }

    @Test
    @DisplayName("默认阈值是 0.9999")
    void defaultThresholdIs09999() {
        assertThat(EvalResult.PASS_SCORE_THRESHOLD).isEqualByComparingTo("0.9999");
        assertThat(scored(Map.of("accuracy", new BigDecimal("0.9999"))).meetsThreshold(null))
                .isTrue();
        assertThat(scored(Map.of("accuracy", new BigDecimal("0.9998"))).meetsThreshold(null))
                .as("0.9998 落在阈值线下，说明比较不是「约等于满分」的宽松判断")
                .isFalse();
    }

    @Test
    @DisplayName("阈值为 null、0 或负数时回退默认值")
    void invalidThresholdFallsBackToDefault() {
        Map<String, BigDecimal> nearlyPerfect = Map.of("accuracy", new BigDecimal("0.9998"));
        assertThat(scored(nearlyPerfect).meetsThreshold(null)).isFalse();
        assertThat(scored(nearlyPerfect).meetsThreshold(BigDecimal.ZERO))
                .as("阈值 0 若被当真，任何得分都会达标，必须回退")
                .isFalse();
        assertThat(scored(nearlyPerfect).meetsThreshold(new BigDecimal("-1"))).isFalse();
    }

    @Test
    @DisplayName("多个质量指标按「与」语义判定")
    void multipleQualityMetricsUseAnd() {
        Map<String, BigDecimal> mixed =
                Map.of(
                        "accuracy", new BigDecimal("1.0000"),
                        "keywordRecall", new BigDecimal("0.5000"));
        assertThat(scored(mixed).meetsThreshold(new BigDecimal("0.9")))
                .as("任一项不达标就整体不达标")
                .isFalse();
        Map<String, BigDecimal> allGood =
                Map.of(
                        "accuracy", new BigDecimal("1.0000"),
                        "keywordRecall", new BigDecimal("0.9500"),
                        "containment", new BigDecimal("1.0000"));
        assertThat(scored(allGood).meetsThreshold(new BigDecimal("0.9"))).isTrue();
    }

    @Test
    @DisplayName("error 非空时一律不达标")
    void errorMeansNotMeetingThreshold() {
        EvalResult failed =
                EvalResult.failure("c-9", "模拟模型超时")
                        .withScores(Map.of("accuracy", new BigDecimal("1.0000")));
        assertThat(failed.meetsThreshold(null)).as("跑挂的用例即便残留满分明细也不能算达标").isFalse();
        assertThat(failed.passed()).isFalse();
    }

    @Test
    @DisplayName("紧凑构造器兜底 score、completedAt 与负耗时")
    void compactConstructorDefaults() {
        EvalResult result = new EvalResult("c-2", null, null, null, null, null, -5L, null);
        assertThat(result.score()).isNotNull().isEmpty();
        assertThat(result.completedAt()).isNotNull();
        assertThat(result.latencyMillis()).as("负耗时是计时误差，记 0 比让报告出现负数安全").isZero();
    }

    @Test
    @DisplayName("withScores 保留其他字段，且新 Map 不可变")
    void withScoresKeepsOtherFieldsAndIsImmutable() {
        EvalResult base =
                new EvalResult(
                        "c-3",
                        "答",
                        Map.of(),
                        Usage.of(10L, 5L),
                        new BigDecimal("0.0003"),
                        null,
                        7L,
                        Instant.EPOCH);
        EvalResult updated = base.withScores(Map.of("accuracy", new BigDecimal("1.0000")));
        assertThat(updated.caseId()).isEqualTo("c-3");
        assertThat(updated.actual()).isEqualTo("答");
        assertThat(updated.cost()).isEqualByComparingTo("0.0003");
        assertThat(updated.usage().totalTokens()).isEqualTo(15L);
        assertThat(updated.latencyMillis()).isEqualTo(7L);
        assertThat(updated.completedAt()).isEqualTo(Instant.EPOCH);
        assertThatThrownBy(() -> updated.score().put("x", BigDecimal.ONE))
                .as("结果对象要能被安全地放进报告，调用方改不动它的得分表")
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("actualLength 对 null 输出返回 0")
    void actualLengthNullIsZero() {
        assertThat(EvalResult.failure("c-4", "boom").actualLength()).isZero();
        assertThat(EvalResult.success("c-5", "支持退货", null, null, 1L).actualLength()).isEqualTo(4);
    }

    @Test
    @DisplayName("QUALITY_METRICS 恰好是三项")
    void qualityMetricsAreExactlyThree() {
        assertThat(EvalResult.QUALITY_METRICS)
                .as("成本与 JSON 结构刻意不在其中，改这个列表等于改门禁语义")
                .containsExactly("accuracy", "keywordRecall", "containment");
    }

    @Test
    @DisplayName("failure(null) 用「未知错误」占位")
    void failureWithNullErrorUsesPlaceholder() {
        assertThat(EvalResult.failure("c-6", null).error())
                .as("错误文本为空的话报告里会出现一条没有任何原因的红灯")
                .isEqualTo("未知错误");
        assertThat(EvalResult.failure("c-6", null).passed()).isFalse();
    }

    @Test
    @DisplayName("scoreOf 命中时原样返回，不做归一化")
    void scoreOfReturnsRawValue() {
        BigDecimal raw = new BigDecimal("0.123");
        assertThat(scored(Map.of("accuracy", raw)).scoreOf("accuracy"))
                .as("归一化是指标实现的职责，取值侧再动一次会把明细改掉")
                .isEqualByComparingTo("0.123");
        assertThat(scored(Map.of("accuracy", raw)).scoreOf("accuracy").scale()).isEqualTo(3);
    }
}
