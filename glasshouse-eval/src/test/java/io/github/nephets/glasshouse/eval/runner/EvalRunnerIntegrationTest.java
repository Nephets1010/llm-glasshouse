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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.nephets.glasshouse.core.eval.EvalCase;
import io.github.nephets.glasshouse.core.eval.EvalConfig;
import io.github.nephets.glasshouse.core.eval.EvalReport;
import io.github.nephets.glasshouse.core.eval.EvalResult;
import io.github.nephets.glasshouse.core.eval.EvalRunner;
import io.github.nephets.glasshouse.core.eval.JsonSchemaMetric;
import io.github.nephets.glasshouse.core.eval.JsonlDataset;
import io.github.nephets.glasshouse.core.eval.LlmJudgeMetric;
import io.github.nephets.glasshouse.core.usage.Usage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评测执行与基线对比测试。
 *
 * <p>覆盖架构 ③T04 验收标准的「跑两次分数完全一致」与「不达标时写报告」， 以及 P1 预留的 {@link BaselineComparator}（CI 门禁体验的核心）。
 */
class EvalRunnerIntegrationTest {

    private static final String DIGEST =
            "sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

    private static EvalCase serviceCase(String id, String question, String... keywords) {
        return EvalCase.of(
                id,
                Map.of("question", question),
                Map.of("contains", List.of(keywords), "forbidden", List.of("不能退")),
                "售后",
                "easy",
                true,
                null);
    }

    private static JsonlDataset dataset(List<EvalCase> cases) {
        return new JsonlDataset("customer-service-v1", "a3f9b21c4e5d", cases, "集成测试数据集");
    }

    /**
     * 构造一个「按 caseId 返回预设输出」的执行器。
     *
     * @param answers caseId → 输出
     * @return invoker
     */
    /**
     * 构造一条带 {@code expected.exact} 的用例（精确匹配）。
     *
     * <p>基线对比需要「达标/不达标」有明确判据；只有 {@code contains} 时 {@code accuracy} 恒为 0，任何版本都判不达标，对比就失去意义。
     *
     * @param id 用例标识
     * @param question 问题
     * @param exact 期望的完整回答
     * @return 用例
     */
    private static EvalCase exactCase(String id, String question, String exact) {
        return EvalCase.of(
                id,
                Map.of("question", question),
                Map.of("exact", exact, "contains", List.of(exact), "forbidden", List.of("不能退")),
                "售后",
                "easy",
                true,
                null);
    }

    /**
     * 构造一个「按 caseId 返回预设输出」的执行器。
     *
     * @param answers caseId → 输出
     * @return invoker
     */
    private static EvalRunner.CaseInvoker invoker(Map<String, String> answers) {
        return c -> {
            String actual = answers.get(c.id());
            if (actual == null) {
                throw new IllegalStateException("未配置 " + c.id());
            }
            return EvalResult.success(
                    c.id(), actual, Usage.of(1000L, 1000L), new BigDecimal("0.0028"), 120L);
        };
    }

    @Test
    @DisplayName("★ 跑两次分数完全一致（CI 门禁的结构性保证）")
    void twoRunsAreIdentical() {
        List<EvalCase> cases =
                List.of(
                        serviceCase("cs-1", "退货政策？", "七天无理由退货"),
                        serviceCase("cs-2", "发票怎么开？", "发票", "增值税"),
                        serviceCase("cs-3", "多久到账？", "工作日"));
        Map<String, String> answers =
                Map.of(
                        "cs-1", "根据政策，签收 7 天内可申请七天无理由退货。",
                        "cs-2", "可以开具增值税发票，请提供抬头与税号。",
                        "cs-3", "退款原路返回，1-7 个工作日到账。");
        EvalConfig config = EvalConfig.of("deepseek/deepseek-chat", "planner@v3");

        EvalReport first = new EvalRunner(invoker(answers)).run(dataset(cases), config);
        EvalReport second = new EvalRunner(invoker(answers)).run(dataset(cases), config);

        assertThat(second.metricScores())
                .as("★ 同一 commit 跑两次必须同分，否则门禁会随机变红")
                .isEqualTo(first.metricScores());
        assertThat(second.totalCost()).isEqualByComparingTo(first.totalCost());
        assertThat(second.failedCalls()).isEqualTo(first.failedCalls());
        assertThat(first.metricScores())
                .containsKeys("accuracy", "keywordRecall", "containment", "cost");
    }

    @Test
    @DisplayName("★ 报告带全部溯源信息（数据集名/版本/指纹/模型/Prompt 版本）")
    void reportCarriesProvenance() {
        List<EvalCase> cases = List.of(serviceCase("cs-1", "退货政策？", "退货"));
        EvalReport report =
                new EvalRunner(invoker(Map.of("cs-1", "支持退货")))
                        .run(dataset(cases), EvalConfig.of("deepseek/deepseek-chat", "planner@v3"));

        assertThat(report.datasetName()).isEqualTo("customer-service-v1");
        assertThat(report.datasetVersion()).isEqualTo("a3f9b21c4e5d");
        assertThat(report.datasetFingerprint()).matches("^sha256:[0-9a-f]{64}$");
        assertThat(report.modelRef()).isEqualTo("deepseek/deepseek-chat");
        assertThat(report.promptVersion()).isEqualTo("planner@v3");
        assertThat(report.totalCost()).isEqualByComparingTo("0.0028");
        assertThat(report.summaryLine()).contains("customer-service-v1");
        assertThat(report.allPassed()).isTrue();
    }

    @Test
    @DisplayName("失败用例被记录原因，且不拉低指标平均")
    void failuresAreIsolated() {
        List<EvalCase> cases =
                List.of(
                        serviceCase("ok-1", "q", "退货"),
                        serviceCase("ok-2", "q", "退货"),
                        serviceCase("bad", "q", "退款"));
        EvalReport report =
                new EvalRunner(
                                c -> {
                                    if ("bad".equals(c.id())) {
                                        throw new IllegalStateException("模拟上游超时");
                                    }
                                    return EvalResult.success(
                                            c.id(), "支持退货", Usage.of(10L, 10L), null, 5L);
                                })
                        .run(dataset(cases), EvalConfig.of("mock/mock-chat", "v3"));

        assertThat(report.failedCalls()).isEqualTo(1L);
        assertThat(report.failedCaseIds()).containsExactly("bad");
        assertThat(report.metricScores().get("keywordRecall"))
                .as("失败用例不参与平均，否则「跑挂了」会被算成「效果差」")
                .isEqualByComparingTo("1.0000");
        assertThat(report.allPassed()).isFalse();
    }

    @Test
    void baselineCompareLocatesRegressions() {
        // 用带 exact 的用例，让 meetsThreshold 有明确的判定依据：
        //   没有 exact 时 accuracy 恒为 0，任何版本都「不达标」，对比就失去意义。
        List<EvalCase> cases =
                List.of(
                        exactCase("c-1", "退货政策？", "支持退货，七天无理由"),
                        exactCase("c-2", "发票？", "可以开发票，增值税专用发票"),
                        exactCase("c-3", "到账？", "1-7 个工作日到账"));

        // v3：三条都答对
        EvalReport v3 =
                new EvalRunner(
                                invoker(
                                        Map.of(
                                                "c-1", "支持退货，七天无理由",
                                                "c-2", "可以开发票，增值税专用发票",
                                                "c-3", "1-7 个工作日到账")))
                        .run(dataset(cases), EvalConfig.of("mock/mock-chat", "planner@v3"));

        // v4：c-2 漏了「增值税」、c-3 结论不具体 → 两条退化
        EvalReport v4 =
                new EvalRunner(
                                invoker(
                                        Map.of(
                                                "c-1", "支持退货，七天无理由",
                                                "c-2", "可以开发票",
                                                "c-3", "很快到账")))
                        .run(dataset(cases), EvalConfig.of("mock/mock-chat", "planner@v4"));

        assertThat(v3.metricScores().get("accuracy"))
                .as("基线版本三条全对，accuracy 应为 1.0")
                .isEqualByComparingTo("1.0000");
        assertThat(v4.metricScores().get("accuracy"))
                .as("★ 新版本只对一条，accuracy 降到 1/3")
                .isEqualByComparingTo("0.3333");

        BaselineComparator.Comparison comparison = new BaselineComparator().compare(v3, v4);

        assertThat(comparison.baselinePrompt()).isEqualTo("planner@v3");
        assertThat(comparison.currentPrompt()).isEqualTo("planner@v4");
        assertThat(comparison.deltaOf("accuracy"))
                .as("★ accuracy 从 1.0 降到 0.3333，差值 -0.6667")
                .isNotNull();
        assertThat(comparison.deltaOf("accuracy").improved()).isFalse();
        assertThat(comparison.regressedCaseIds())
                .as("★ 必须能定位到「哪几条变差了」，而不是只给一个总分变化")
                .containsExactlyInAnyOrder("c-2", "c-3");
        assertThat(comparison.regressedCount()).isEqualTo(2L);
        assertThat(comparison.describe())
                .contains("planner@v3")
                .contains("planner@v4")
                .contains("变差");
        assertThat(BaselineComparator.toMarkdown(comparison))
                .contains("| 指标 |")
                .contains("accuracy");
    }

    @Test
    @DisplayName("基线对比：无变化时全部持平")
    void identicalVersionsShowNoChange() {
        List<EvalCase> cases = List.of(serviceCase("c-1", "q", "退货"));
        EvalRunner runner = new EvalRunner(invoker(Map.of("c-1", "支持退货")));
        EvalReport a = runner.run(dataset(cases), EvalConfig.of("mock/mock-chat", "v3"));
        EvalReport b = runner.run(dataset(cases), EvalConfig.of("mock/mock-chat", "v3"));

        BaselineComparator.Comparison comparison = new BaselineComparator().compare(a, b);
        assertThat(comparison.regressedCount()).isZero();
        assertThat(comparison.improvedCount()).isZero();
        assertThat(comparison.deltaOf("keywordRecall").unchanged()).isTrue();
    }

    @Test
    @DisplayName("JSON Schema 用例：结构化输出被判分")
    void jsonSchemaCasesAreScored() {
        EvalCase schemaCase =
                EvalCase.of(
                        "j-1",
                        Map.of("question", "评估这笔贷款"),
                        Map.of(
                                "requiredFields", List.of("conclusion", "riskLevel"),
                                "fieldTypes",
                                        Map.of("conclusion", "string", "riskLevel", "string")),
                        "信贷",
                        "hard",
                        true,
                        null);
        EvalReport report =
                new EvalRunner(
                                c ->
                                        EvalResult.success(
                                                c.id(),
                                                "```json\n{\"conclusion\":\"建议拒绝\",\"riskLevel\":\"高\"}\n```",
                                                Usage.of(200L, 80L),
                                                null,
                                                200L))
                        .run(dataset(List.of(schemaCase)), EvalConfig.of("mock/mock-chat", "v3"));

        assertThat(report.metricScores().get("jsonSchema"))
                .as("★ 代码块围栏必须被剥掉，否则结构化输出全判失败")
                .isEqualByComparingTo("1.0000");
        assertThat(JsonSchemaMetric.INSTANCE.name()).isEqualTo("jsonSchema");
    }

    @Test
    @DisplayName("幻觉检测：命中 forbidden 即判 containment 为 0")
    void containmentDetectsHallucination() {
        List<EvalCase> cases = List.of(serviceCase("h-1", "能退货吗", "退货"));
        EvalReport hallucinated =
                new EvalRunner(invoker(Map.of("h-1", "这个订单不能退")))
                        .run(dataset(cases), EvalConfig.of("mock/mock-chat", "v3"));
        EvalReport honest =
                new EvalRunner(invoker(Map.of("h-1", "支持退货")))
                        .run(dataset(cases), EvalConfig.of("mock/mock-chat", "v3"));

        assertThat(hallucinated.metricScores().get("containment"))
                .as("★ 出现禁答内容 → 幻觉率 1.0")
                .isEqualByComparingTo("0.0000");
        assertThat(honest.metricScores().get("containment")).isEqualByComparingTo("1.0000");
    }

    @Test
    @DisplayName("成本指标：单次成本超预算时得分下降")
    void costMetricDegradesWhenOverBudget() {
        List<EvalCase> cheap = List.of(serviceCase("c-1", "q", "退货"));
        List<EvalCase> expensive = List.of(serviceCase("c-1", "q", "退货"));
        EvalConfig config =
                EvalConfig.of("mock/mock-chat", "v3").withCostBudget(new BigDecimal("0.001"));

        EvalReport cheapReport =
                new EvalRunner(
                                c ->
                                        EvalResult.success(
                                                c.id(),
                                                "支持退货",
                                                Usage.of(1000L, 1000L),
                                                new BigDecimal("0.0001"),
                                                5L))
                        .run(dataset(cheap), config);
        EvalReport expensiveReport =
                new EvalRunner(
                                c ->
                                        EvalResult.success(
                                                c.id(),
                                                "支持退货",
                                                Usage.of(1000L, 1000L),
                                                new BigDecimal("0.010"),
                                                5L))
                        .run(dataset(expensive), config);

        assertThat(expensiveReport.metricScores().get("cost"))
                .as("★ 成本超预算时得分必须下降")
                .isLessThan(cheapReport.metricScores().get("cost"));
    }

    @Test
    @DisplayName("空数据集与超长数据集的边界")
    void datasetSizeBoundaries() {
        assertThatThrownByDataset("空数据集");
        List<EvalCase> many = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            many.add(serviceCase("bulk-" + i, "q", "退货"));
        }
        StringBuilder ids = new StringBuilder();
        many.forEach(c -> ids.append(c.id()).append('=').append("支持退货").append('\n'));
        Map<String, String> answers = new java.util.LinkedHashMap<>();
        many.forEach(c -> answers.put(c.id(), "支持退货"));

        EvalReport report =
                new EvalRunner(invoker(answers))
                        .run(dataset(many), EvalConfig.of("mock/mock-chat", "v3"));
        assertThat(report.totalCalls()).isEqualTo(100L);
        assertThat(report.failedCalls()).isZero();
        assertThat(report.successRate()).isEqualTo(1.0d);
    }

    private static void assertThatThrownByDataset(String label) {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new JsonlDataset("empty", "v1", List.of(), null),
                label + " 必须被拒绝");
    }

    @Test
    @DisplayName("LlmJudgeMetric 可插拔，默认不参与评测（P1 才启用 LLM-as-judge）")
    void llmJudgeIsOptional() {
        LlmJudgeMetric judge =
                new LlmJudgeMetric() {
                    @Override
                    public String name() {
                        return "hallucination";
                    }

                    @Override
                    public String description() {
                        return "测试用的 LLM judge";
                    }

                    @Override
                    public String dimension() {
                        return LlmJudgeMetric.DIMENSION_FAITHFULNESS;
                    }
                };
        assertThat(judge.name()).isEqualTo("hallucination");
        assertThat(judge.dimension()).isEqualTo("faithfulness");
        assertThat(judge.parseScore("结论：合理\nSCORE: 4")).as("★ judge 输出要能解析出 1~5 分").isEqualTo(4);
        assertThat(judge.normalizeScore(5)).isEqualByComparingTo("1.0000");
        assertThat(judge.normalizeScore(1)).isEqualByComparingTo("0.0000");
        assertThat(judge.normalizeScore(null))
                .as("★ 解析不到分数时记 0，不能默认满分")
                .isEqualByComparingTo("0.0000");
        assertThat(
                        judge.score(
                                io.github.nephets.glasshouse.core.eval.EvalResult.success(
                                        "c", "a", Usage.of(1L, 1L), null, 1L)))
                .as("★ judge 未接入时返回 0（不能让门禁「因为没算分而通过」）")
                .isEqualByComparingTo("0.0000");
        // 没有配置 judge 时，未知 judge 的指标不应影响默认指标集
        List<io.github.nephets.glasshouse.core.eval.Metric> defaults =
                EvalRunner.defaultMetrics(BigDecimal.ONE);
        assertThat(defaults)
                .as("★ 默认指标集必须可复现，不能因为 judge 不可用而每次分数不同")
                .hasSize(5)
                .extracting(io.github.nephets.glasshouse.core.eval.Metric::name)
                .containsExactlyInAnyOrder(
                        "accuracy", "keywordRecall", "containment", "jsonSchema", "cost");
    }

    @Test
    @DisplayName("Usage 为 null 的结果不计入 token 与成本汇总")
    void nullUsageIsHandled() {
        List<EvalCase> cases = List.of(serviceCase("c-1", "q", "退货"));
        EvalReport report =
                new EvalRunner(c -> EvalResult.success(c.id(), "支持退货", null, null, 5L))
                        .run(dataset(cases), EvalConfig.of("mock/mock-chat", "v3"));
        assertThat(report.totalCalls()).isEqualTo(1L);
        assertThat(report.failedCalls()).isZero();
    }
}
