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

package io.github.nephets.glasshouse.eval.gate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import io.github.nephets.glasshouse.core.eval.EvalCase;
import io.github.nephets.glasshouse.core.eval.EvalConfig;
import io.github.nephets.glasshouse.core.eval.EvalReport;
import io.github.nephets.glasshouse.core.eval.EvalResult;
import io.github.nephets.glasshouse.core.eval.GateEvaluator;
import io.github.nephets.glasshouse.core.eval.GateRule;
import io.github.nephets.glasshouse.core.eval.Metric;
import io.github.nephets.glasshouse.core.eval.Operator;
import io.github.nephets.glasshouse.core.usage.Usage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 门禁退出码测试（架构 ③T04 验收标准第 3 条 ★ CI 门禁的核心断言）。
 *
 * <p><b>本类证明的事</b>：门禁不达标时<b>真的</b>返回退出码 1，达标时返回 0， 配置写错时返回 2。这是 GitHub Action 把流水线变红的唯一依据，因此必须逐条锁定。
 *
 * <p>退出码契约：
 *
 * <ul>
 *   <li>{@code 0} 通过
 *   <li>{@code 1} 门禁未达标 → 流水线变红
 *   <li>{@code 2} 配置错误 → 这是命令写错了，不是评测不达标
 * </ul>
 */
class GateFailureExitCodeTest {

    /**
     * 构造一份指定分数的报告。
     *
     * @param scores 指标名 → 分数
     * @param failedCalls 失败用例数
     * @return 评测报告
     */
    private static EvalReport report(Map<String, BigDecimal> scores, long failedCalls) {
        return new EvalReport(
                "customer-service-v1",
                "v1",
                "fp",
                "planner@v3",
                "mock/mock-chat",
                List.of(),
                scores,
                Math.max(failedCalls, 1L),
                failedCalls,
                BigDecimal.ZERO,
                java.time.Instant.now(),
                100L);
    }

    @Test
    @DisplayName("★ 达标 → exit 0")
    void passingGateReturnsZero() {
        EvalReport r =
                report(
                        Map.of(
                                "keywordRecall",
                                new BigDecimal("0.95"),
                                "containment",
                                new BigDecimal("1.0")),
                        0L);
        GateParser.Verdict verdict = GateParser.evaluate(r, "keywordRecall>0.90,containment=1.0");

        assertThat(verdict.passed()).isTrue();
        assertThat(verdict.exitCode())
                .as("★ 达标必须 exit 0，否则流水线会无故变红")
                .isEqualTo(GateParser.EXIT_OK)
                .isZero();
        assertThat(verdict.message()).contains("门禁全部通过");
    }

    @Test
    @DisplayName("★ 不达标 → exit 1（让流水线变红）")
    void failingGateReturnsOne() {
        EvalReport r =
                report(
                        Map.of(
                                "keywordRecall",
                                new BigDecimal("0.72"),
                                "containment",
                                new BigDecimal("1.0")),
                        0L);
        GateParser.Verdict verdict = GateParser.evaluate(r, "keywordRecall>0.90,containment=1.0");

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.exitCode())
                .as("★ 这是 CI 门禁的核心断言：不达标必须 exit 1")
                .isEqualTo(GateParser.EXIT_GATE_FAILED)
                .isEqualTo(1);
        assertThat(verdict.message())
                .contains("门禁未通过")
                .contains("1/2 条规则不达标")
                .contains("keywordRecall=0.72")
                .contains("不满足 > 0.9");
    }

    @Test
    @DisplayName("★ 表达式写错 → exit 2（区分「配置错」与「效果差」）")
    void configErrorReturnsTwo() {
        EvalReport r = report(Map.of("keywordRecall", new BigDecimal("0.95")), 0L);
        assertThatThrownBy(() -> GateParser.evaluate(r, "keywordRecall>>0.9"))
                .isInstanceOf(GateEvaluator.GateParseException.class);

        GateParser.Verdict verdict =
                GateParser.configError(new GateEvaluator.GateParseException("第 1 条规则 \"x\" 缺少比较符"));
        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.exitCode())
                .as("★ 配置错误必须是 2，不能和「门禁不达标（1）」混在一起")
                .isEqualTo(GateParser.EXIT_CONFIG_ERROR)
                .isEqualTo(2);
        assertThat(verdict.message()).contains("配置问题");
    }

    @Test
    @DisplayName("★ 指标缺失也算不达标 → exit 1（防「拼错指标名导致门禁静默通过」）")
    void missingMetricReturnsOne() {
        EvalReport r = report(Map.of("accuracy", new BigDecimal("1.00")), 0L);
        GateParser.Verdict verdict = GateParser.evaluate(r, "keywordRecall>0.9");

        assertThat(verdict.exitCode())
                .as("★ 打错指标名不能变成「通过」，必须 exit 1")
                .isEqualTo(GateParser.EXIT_GATE_FAILED);
        assertThat(verdict.message()).contains("keywordRecall");
    }

    @Test
    @DisplayName("★ 用例执行失败数超预算 → exit 1（即使指标分数很高）")
    void caseFailureBudgetReturnsOne() {
        // 指标分数全部达标，但有 3 条用例直接跑挂了
        EvalReport r = report(Map.of("keywordRecall", new BigDecimal("1.00")), 3L);

        assertThat(GateParser.evaluateAll(r, "keywordRecall>0.9", 0).exitCode())
                .as("★ 「所有用例都超时」不能因为「没有失败数据」而被判为通过")
                .isEqualTo(GateParser.EXIT_GATE_FAILED);

        assertThat(GateParser.evaluateAll(r, "keywordRecall>0.9", 5).exitCode())
                .as("失败数在预算内则放行")
                .isEqualTo(GateParser.EXIT_OK);
    }

    @Test
    @DisplayName("多规则：只要一条不达标就 exit 1")
    void anyFailingRuleFails() {
        EvalReport r =
                report(
                        Map.of(
                                "accuracy", new BigDecimal("0.95"),
                                "keywordRecall", new BigDecimal("0.60"),
                                "containment", new BigDecimal("1.00")),
                        0L);

        GateParser.Verdict verdict =
                GateParser.evaluate(r, "accuracy>0.90,keywordRecall>0.90,containment=1.0");
        assertThat(verdict.exitCode()).isEqualTo(1);
        assertThat(verdict.outcomes()).hasSize(3);
        assertThat(verdict.outcomes())
                .filteredOn(o -> !o.passed())
                .hasSize(1)
                .allMatch(o -> o.rule().metricName().equals("keywordRecall"));
    }

    @Test
    @DisplayName("不配置门禁 → exit 0（没配门禁不该让 CI 变红）")
    void noGateReturnsZero() {
        EvalReport r = report(Map.of("accuracy", new BigDecimal("0.1")), 0L);
        assertThat(GateParser.evaluate(r, null).exitCode()).isZero();
        assertThat(GateParser.evaluate(r, "  ").exitCode()).isZero();
        assertThat(GateParser.evaluateAll(r, null, 0).exitCode()).isZero();
    }

    @Test
    @DisplayName("★ 端到端：故意把阈值调到不可能达到 → 一定 exit 1")
    void endToEndGateAlwaysFailsWithImpossibleThreshold() {
        // 用真实的评测报告（分数已知），阈值设成 0.999
        EvalReport actual = report(Map.of("keywordRecall", new BigDecimal("0.6667")), 0L);

        GateParser.Verdict impossible = GateParser.evaluate(actual, "keywordRecall>0.999");
        assertThat(impossible.exitCode()).as("★ 架构 3T04 第 3 条：故意降低阈值时构建必须失败").isEqualTo(1);

        GateParser.Verdict achievable = GateParser.evaluate(actual, "keywordRecall>0.5");
        assertThat(achievable.exitCode()).isZero();
    }

    @Test
    @DisplayName("逐条判定的 outcome 携带足够定位的信息")
    void outcomesAreActionable() {
        EvalReport r = report(Map.of("accuracy", new BigDecimal("0.70")), 0L);
        GateRule rule = new GateRule("accuracy", Operator.GT, new BigDecimal("0.85"));
        GateRule.GateOutcome outcome = rule.evaluate(r);

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.rule().metricName()).isEqualTo("accuracy");
        assertThat(outcome.actual()).isEqualByComparingTo("0.70");
        assertThat(outcome.reason())
                .contains("accuracy=0.7")
                .contains("不满足 > 0.85")
                .contains("差值 -0.15");
    }

    @Test
    @DisplayName("caseFailuresWithinBudget 边界")
    void caseFailureBudgetBoundaries() {
        assertThat(GateParser.caseFailuresWithinBudget(report(Map.of(), 0L), 0)).isTrue();
        assertThat(GateParser.caseFailuresWithinBudget(report(Map.of(), 1L), 0)).isFalse();
        assertThat(GateParser.caseFailuresWithinBudget(report(Map.of(), 1L), 1)).isTrue();
        assertThat(GateParser.caseFailuresWithinBudget(report(Map.of(), 5L), -1))
                .as("负预算按 0 处理")
                .isFalse();
    }

    @Test
    @DisplayName("★ 从真实用例构造的分数驱动门禁（不写死分数）")
    void gateDrivenByRealEvalRun() {
        // 三条用例，两条命中关键词、一条没有
        List<EvalCase> cases =
                List.of(
                        EvalCase.of(
                                "c-1",
                                Map.of("question", "q1"),
                                Map.of("contains", List.of("退货"), "forbidden", List.of("不能退")),
                                "售后",
                                "easy",
                                true,
                                null),
                        EvalCase.of(
                                "c-2",
                                Map.of("question", "q2"),
                                Map.of("contains", List.of("发票"), "forbidden", List.of("不能开票")),
                                "售后",
                                "easy",
                                true,
                                null),
                        EvalCase.of(
                                "c-3",
                                Map.of("question", "q3"),
                                Map.of("contains", List.of("退款"), "forbidden", List.of("概不退")),
                                "售后",
                                "easy",
                                true,
                                null));

        Map<String, String> answers = Map.of("c-1", "支持七天无理由退货", "c-2", "可以开具发票", "c-3", "对不起");
        io.github.nephets.glasshouse.core.eval.EvalRunner runner =
                new io.github.nephets.glasshouse.core.eval.EvalRunner(
                        c ->
                                EvalResult.success(
                                        c.id(), answers.get(c.id()), Usage.of(10L, 10L), null, 1L));

        EvalConfig config = EvalConfig.of("mock/mock-chat", "planner@v3");
        EvalReport passed =
                runner.run(
                        new io.github.nephets.glasshouse.core.eval.JsonlDataset(
                                "ds", "v1", cases, null),
                        config);

        assertThat(passed.scoreOf(Metric.NAME_KEYWORD_RECALL))
                .as("2/3 命中关键词")
                .isEqualByComparingTo("0.6667");

        assertThat(GateParser.evaluate(passed, "keywordRecall>0.6").exitCode()).isZero();
        assertThat(GateParser.evaluate(passed, "keywordRecall>0.8").exitCode())
                .as("★ 阈值高于实际分数时必须 exit 1")
                .isEqualTo(1);
    }
}
