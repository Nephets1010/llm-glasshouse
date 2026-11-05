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
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link EvalRunner} 的汇总、指标集装配与逐用例打分测试。
 *
 * <p>汇总口径决定了门禁阈值怎么设：「失败用例算 0 分」和「失败用例不进平均」会得出完全不同的总分， 前者让 8 条跑挂和 10 条都答错看起来一样，正是回归门禁最怕的混淆。
 */
class EvalRunnerAggregationTest {

    /**
     * 构造一个不依赖外部模型的执行器。
     *
     * @return 执行器
     */
    private static EvalRunner.CaseInvoker noopInvoker() {
        return evalCase -> EvalResult.success(evalCase.id(), "答", null, null, 1L);
    }

    /**
     * 构造一条成功完成、带指定得分的结果。
     *
     * @param caseId 用例标识
     * @param scores 指标得分
     * @return 结果
     */
    private static EvalResult passed(String caseId, Map<String, BigDecimal> scores) {
        return new EvalResult(caseId, "答", scores, null, BigDecimal.ZERO, null, 1L, Instant.EPOCH);
    }

    /**
     * 构造一条失败结果。
     *
     * @param caseId 用例标识
     * @return 结果
     */
    private static EvalResult failed(String caseId) {
        return EvalResult.failure(caseId, "模拟模型超时");
    }

    /**
     * 构造一条客服用例。
     *
     * @param id 用例标识
     * @return 用例
     */
    private static EvalCase serviceCase(String id) {
        return EvalCase.of(
                id,
                Map.of("question", "退货政策？"),
                Map.of("exact", "答", "contains", List.of("退货"), "forbidden", List.of()),
                "售后",
                "easy",
                true,
                null);
    }

    /**
     * 构造一条三个质量指标都能拿满分的用例。
     *
     * <p>输出固定为「答」，所以 exact、contains 与 forbidden 都围绕这两个字配置。
     *
     * @param id 用例标识
     * @return 用例
     */
    private static EvalCase scoringCase(String id) {
        return EvalCase.of(
                id,
                Map.of("question", "退货政策？"),
                Map.of("exact", "答", "contains", List.of("答"), "forbidden", List.of()),
                "售后",
                "easy",
                true,
                null);
    }

    @Test
    @DisplayName("★ 聚合只平均成功完成的用例")
    void aggregateAveragesOnlySuccessfulCases() {
        EvalRunner runner = new EvalRunner(noopInvoker());
        Map<String, BigDecimal> scores =
                runner.aggregate(
                        List.of(
                                passed("a", Map.of("accuracy", new BigDecimal("1.0000"))),
                                passed("b", Map.of("accuracy", new BigDecimal("0.0000"))),
                                failed("c")));

        assertThat(scores)
                .as("★ 把失败当 0 分平均会得到 0.3333，那样「跑挂了」与「答错了」在报告里就无法区分")
                .containsEntry("accuracy", new BigDecimal("0.5000"));
        assertThat(scores.get("accuracy")).isEqualByComparingTo("0.5000");
    }

    @Test
    @DisplayName("空列表与全失败时聚合结果为空 Map")
    void aggregateEmptyOrAllFailedReturnsEmptyMap() {
        EvalRunner runner = new EvalRunner(noopInvoker());
        assertThat(runner.aggregate(List.of())).isEmpty();
        assertThat(runner.aggregate(List.of(failed("a"), failed("b"))))
                .as("一条成功用例都没有时不该造出任何指标条目")
                .isEmpty();
    }

    @Test
    @DisplayName("某指标只在部分用例上出现时按其自身样本数平均")
    void aggregatePerMetricUsesOwnSampleCount() {
        EvalRunner runner = new EvalRunner(noopInvoker());
        Map<String, BigDecimal> scores =
                runner.aggregate(
                        List.of(
                                passed("a", Map.of("accuracy", new BigDecimal("1.0000"))),
                                passed("b", Map.of("accuracy", new BigDecimal("1.0000"))),
                                passed("c", Map.of("containment", new BigDecimal("0.0000")))));

        assertThat(scores.get("accuracy"))
                .as("accuracy 只出现两次，分母取 2 得 1.0000；若按用例总数 3 平均会错成 0.6667")
                .isEqualByComparingTo("1.0000");
        assertThat(scores.get("containment")).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("默认指标集固定五项且顺序固定")
    void defaultMetricsAreFixedAndOrdered() {
        List<String> expected =
                List.of("accuracy", "keywordRecall", "containment", "jsonSchema", "cost");
        assertThat(EvalRunner.defaultMetrics(BigDecimal.ONE))
                .as("顺序被报告与文档引用，改动会让基线报告出现无意义的差异")
                .extracting(Metric::name)
                .containsExactlyElementsOf(expected);
        assertThat(new EvalRunner(noopInvoker()).metrics())
                .extracting(Metric::name)
                .containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("指标集为 null 或空时回退默认集")
    void nullOrEmptyMetricsFallBackToDefault() {
        List<Metric> nullMetrics = null;
        assertThat(new EvalRunner(noopInvoker(), nullMetrics).metrics())
                .hasSize(5)
                .extracting(Metric::name)
                .contains("accuracy");
        assertThat(new EvalRunner(noopInvoker(), List.of()).metrics())
                .as("传空列表通常是配置读失败的结果，回退默认集比静默地什么都不算安全")
                .hasSize(5);
    }

    @Test
    @DisplayName("★ 自定义指标集里没有成本指标时仍会注入成本分")
    void customMetricsStillGetCostScore() {
        EvalRunner runner = new EvalRunner(noopInvoker(), List.of(ExactMatchMetric.INSTANCE));
        EvalCase evalCase = serviceCase("s-1");
        EvalResult raw = EvalResult.success("s-1", "答", null, new BigDecimal("0.5"), 1L);

        EvalResult scored = runner.scoreCase(evalCase, raw);

        assertThat(runner.metrics()).extracting(Metric::name).containsExactly("accuracy");
        assertThat(scored.score())
                .as(
                        "★ 这是设计选择：成本是每次调用都有的客观数据，用户只挑了准确率指标"
                                + "也不该因此丢掉成本维度；改它需要改主源码 EvalRunner.java:95-102")
                .containsKey("cost");
        assertThat(scored.scoreOf("cost"))
                .as("成本 0.5 对默认预算 1 → 1/(1+0.5) = 0.6667")
                .isEqualByComparingTo("0.6667");
    }

    @Test
    @DisplayName("metrics() 返回不可变列表")
    void metricsListIsImmutable() {
        EvalRunner runner = new EvalRunner(noopInvoker());
        List<Metric> metrics = runner.metrics();
        assertThatThrownBy(() -> metrics.add(ExactMatchMetric.INSTANCE))
                .as("指标集被外部改掉会让同一份报告里的得分口径前后不一致")
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> metrics.clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("invoker 抛出无 message 的异常时错误文本只有类名")
    void invokerThrowsWithoutMessageYieldsTypeNameOnly() {
        EvalRunner runner =
                new EvalRunner(
                        evalCase -> {
                            throw new IllegalStateException();
                        });
        assertThat(runner.runCase(serviceCase("s-2")).error())
                .as("没有消息时不能拼出「IllegalStateException: 」这种带空尾巴的文本")
                .isEqualTo("IllegalStateException");

        EvalRunner blankMessage =
                new EvalRunner(
                        evalCase -> {
                            throw new IllegalArgumentException("   ");
                        });
        assertThat(blankMessage.runCase(serviceCase("s-3")).error())
                .as("纯空白消息与没有消息同样处理")
                .isEqualTo("IllegalArgumentException");
    }

    @Test
    @DisplayName("runCase 保留 invoker 给出的正耗时")
    void runCaseKeepsInvokerProvidedLatency() {
        EvalRunner runner =
                new EvalRunner(
                        evalCase ->
                                EvalResult.success(
                                        evalCase.id(), "答", null, BigDecimal.ZERO, 1234L));
        assertThat(runner.runCase(serviceCase("s-4")).latencyMillis())
                .as("invoker 报的耗时才是真实调用耗时；用外层计时会把指标计算的时间也算进去")
                .isEqualTo(1234L);
    }

    @Test
    @DisplayName("scoreCase 给通过用例写入五项得分，不给失败用例写")
    void scoreCaseWritesFiveMetricsOnlyForPassingCases() {
        EvalRunner runner = new EvalRunner(noopInvoker());
        EvalCase evalCase = scoringCase("s-5");

        EvalResult passing =
                runner.scoreCase(
                        evalCase, EvalResult.success("s-5", "答", null, BigDecimal.ZERO, 1L));
        assertThat(passing.score())
                .as("五项满分口径必须齐，缺一项会让报告出现空白单元格")
                .containsOnlyKeys("accuracy", "keywordRecall", "containment", "jsonSchema", "cost");
        assertThat(passing.scoreOf("accuracy")).isEqualByComparingTo("1.0000");
        assertThat(passing.scoreOf("keywordRecall")).isEqualByComparingTo("1.0000");

        EvalResult failing = runner.scoreCase(evalCase, EvalResult.failure("s-5", "boom"));
        assertThat(failing.score()).as("跑挂的用例不参与指标计算，避免「跑挂了」被算成「效果差」而掩盖真实原因").isEmpty();
    }

    @Test
    @DisplayName("aggregate 的结果按四位小数四舍五入")
    void aggregateRoundsToFourDecimals() {
        EvalRunner runner = new EvalRunner(noopInvoker());
        Map<String, BigDecimal> scores =
                runner.aggregate(
                        List.of(
                                passed("a", Map.of("accuracy", new BigDecimal("1.0000"))),
                                passed("b", Map.of("accuracy", new BigDecimal("0.0000"))),
                                passed("c", Map.of("accuracy", new BigDecimal("0.0000")))));

        assertThat(scores.get("accuracy"))
                .as("1/3 除不尽，必须落到四位小数，否则同一份数据两次聚合会因精度不同而不可比")
                .isEqualByComparingTo("0.3333");
        assertThat(scores.get("accuracy").scale()).isEqualTo(4);
    }
}
