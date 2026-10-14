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
import java.util.List;
import java.util.Map;

import io.github.nephets.glasshouse.core.usage.Usage;
import io.github.nephets.glasshouse.core.usage.UsageSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 评测引擎测试：可复现性与指标正确性。
 *
 * <p><b>可复现性是第一约束</b>（架构 ③T04 验收标准第 1 条：跑两次分数完全一致），
 * 因此本类专门断言「同一输入两次跑分完全一致」，以及「失败用例不参与指标平均」这两个容易被破坏的性质。
 */
class EvalRunnerTest {

    private static final String DIGEST =
            "sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

    /**
     * 构造一条客服用例。
     *
     * @param id 用例标识
     * @param question 问题
     * @param contains 必须命中的关键词
     * @param forbidden 禁止出现的内容
     * @return 用例
     */
    private static EvalCase serviceCase(
            String id, String question, List<String> contains, List<String> forbidden) {
        return EvalCase.of(
                id,
                Map.of("question", question),
                Map.of("contains", contains, "forbidden", forbidden),
                "售后",
                "medium",
                true,
                null);
    }

    /**
     * 构造一个确定性的 invoker：按 caseId 返回预设输出。
     *
     * @param answers caseId → 模型输出
     * @return invoker
     */
    private static EvalRunner.CaseInvoker invoker(Map<String, String> answers) {
        return evalCase -> {
            String actual = answers.get(evalCase.id());
            if (actual == null) {
                throw new IllegalStateException("没有为 " + evalCase.id() + " 配置响应");
            }
            return EvalResult.success(
                    evalCase.id(), actual, Usage.of(100L, 50L), new BigDecimal("0.0003"), 12L);
        };
    }

    private static JsonlDataset dataset(List<EvalCase> cases) {
        return new JsonlDataset("test-ds", "v1", cases, "测试数据集");
    }

    @Nested
    @DisplayName("可复现性")
    class Reproducibility {

        @Test
        @DisplayName("★ 同一输入两次跑分完全一致（CI 门禁的结构性保证）")
        void twoRunsProduceIdenticalScores() {
            List<EvalCase> cases =
                    List.of(
                            serviceCase("t-1", "退货政策？", List.of("七天无理由退货", "7 天"), List.of("不能退")),
                            serviceCase("t-2", "发票怎么开？", List.of("发票", "增值税"), List.of("不能开票")),
                            serviceCase("t-3", "多久到账？", List.of("1-7 个工作日"), List.of("24 小时必然到账")));
            Map<String, String> answers =
                    Map.of(
                            "t-1", "根据政策，签收 7 天内可申请七天无理由退货。",
                            "t-2", "可以开具发票，请提供抬头与税号，可开增值税专用发票。",
                            "t-3", "退款原路返回，通常 1-7 个工作日到账。");
            JsonlDataset ds = dataset(cases);
            EvalRunner runner = new EvalRunner(invoker(answers));

            EvalReport first = runner.run(ds, EvalConfig.of("deepseek/deepseek-chat", "v3"));
            EvalReport second = runner.run(ds, EvalConfig.of("deepseek/deepseek-chat", "v3"));

            assertThat(second.metricScores())
                    .as("★ 两次运行分数必须完全一致，否则门禁会随机变红")
                    .isEqualTo(first.metricScores());
            assertThat(second.totalCost()).isEqualByComparingTo(first.totalCost());
            assertThat(second.failedCalls()).isEqualTo(first.failedCalls());
            // 关键词总数 = 2+2+1 = 5；命中 = 2（退货政策）+ 2（发票）+ 1（到账）= 5/5
            // t-3 的关键词是「1-7 个工作日」，回答里写的是「1-7 个工作日」→ 命中
            assertThat(first.metricScores().get(Metric.NAME_KEYWORD_RECALL))
                    .as("★ 三条用例的关键词全部命中，召回率应为 1.0000")
                    .isEqualByComparingTo("1.0000");
        }

        @Test
        @DisplayName("结果按用例在数据集中的原始顺序排列，不因执行顺序变化")
        void resultsKeepDatasetOrder() {
            List<EvalCase> cases =
                    List.of(
                            serviceCase("t-1", "q1", List.of("a"), List.of()),
                            serviceCase("t-2", "q2", List.of("a"), List.of()),
                            serviceCase("t-3", "q3", List.of("a"), List.of()));
            EvalReport report =
                    new EvalRunner(invoker(Map.of("t-1", "a", "t-2", "a", "t-3", "a")))
                            .run(dataset(cases), EvalConfig.of("mock/mock-chat", "v3"));
            assertThat(report.results())
                    .extracting(EvalResult::caseId)
                    .containsExactly("t-1", "t-2", "t-3");
        }
    }

    @Nested
    @DisplayName("指标")
    class Metrics {

        private BigDecimal score(EvalCase evalCase, String actual, String metric) {
            EvalRunner runner =
                    new EvalRunner(
                            c -> EvalResult.success(c.id(), actual, null, null, 1L),
                            EvalRunner.defaultMetrics(java.math.BigDecimal.ONE));
            EvalResult scored =
                    runner.scoreCase(
                            evalCase, EvalResult.success(evalCase.id(), actual, null, null, 1L));
            BigDecimal value = scored.scoreOf(metric);
            assertThat(scored.score())
                    .as("指标 %s 的得分明细（case=%s, actual=%s）", metric, evalCase.id(), actual)
                    .isNotEmpty();
            return value;
        }

        @Test
        @DisplayName("keywordRecall：命中的关键词比例")
        void keywordRecall() {
            EvalCase c = serviceCase("k", "q", List.of("七天无理由退货", "发票", "退款"), List.of());
            assertThat(score(c, "支持七天无理由退货与退款", Metric.NAME_KEYWORD_RECALL))
                    .isEqualByComparingTo("0.6667");
            assertThat(score(c, "七天无理由退货、发票、退款都支持", Metric.NAME_KEYWORD_RECALL))
                    .isEqualByComparingTo("1.0000");
            assertThat(score(c, "完全无关的回答", Metric.NAME_KEYWORD_RECALL))
                    .isEqualByComparingTo("0.0000");
        }

        @Test
        @DisplayName("accuracy：与 expected.exact 精确匹配（忽略空白与全角/半角）")
        void accuracy() {
            EvalCase c =
                    EvalCase.of(
                            "a",
                            Map.of("question", "q"),
                            Map.of("exact", "可以七天无理由退货"),
                            "售后",
                            "easy",
                            true,
                            null);
            assertThat(score(c, "可以七天无理由退货", Metric.NAME_ACCURACY)).isEqualByComparingTo("1.0000");
            assertThat(score(c, " 可以七天无理由退货 ", Metric.NAME_ACCURACY))
                    .as("首尾空白与连续空白必须被忽略，否则模型只是多打一个空格就被判失败")
                    .isEqualByComparingTo("1.0000");
            assertThat(score(c, "可以7天无理由退货", Metric.NAME_ACCURACY)).isEqualByComparingTo("0.0000");
        }

        @Test
        @DisplayName("containment：命中 forbidden 即 0 分")
        void containment() {
            EvalCase c = serviceCase("c", "q", List.of(), List.of("不能退", "概不退换"));
            assertThat(score(c, "根据政策可以退货", Metric.NAME_CONTAINMENT))
                    .isEqualByComparingTo("1.0000");
            assertThat(score(c, "这个订单不能退", Metric.NAME_CONTAINMENT)).isEqualByComparingTo("0.0000");
            assertThat(score(c, "概不退换，抱歉", Metric.NAME_CONTAINMENT)).isEqualByComparingTo("0.0000");
        }

        @Test
        @DisplayName("jsonSchema：必需字段齐全、类型正确、无代码块围栏也能解析")
        void jsonSchema() {
            EvalCase c =
                    EvalCase.of(
                            "j",
                            Map.of("question", "q"),
                            Map.of(
                                    "requiredFields",
                                    List.of("conclusion", "riskLevel"),
                                    "fieldTypes",
                                    Map.of("riskLevel", "string")),
                            "信贷",
                            "medium",
                            true,
                            null);
            assertThat(
                            score(
                                    c,
                                    "{\"conclusion\":\"建议拒绝\",\"riskLevel\":\"高\"}",
                                    Metric.NAME_JSON_SCHEMA))
                    .isEqualByComparingTo("1.0000");
            assertThat(
                            score(
                                    c,
                                    "```json\n{\"conclusion\":\"建议拒绝\",\"riskLevel\":\"高\"}\n```",
                                    Metric.NAME_JSON_SCHEMA))
                    .as("模型常在 JSON 外面包代码块围栏，必须能剥掉")
                    .isEqualByComparingTo("1.0000");
            assertThat(score(c, "{\"conclusion\":\"建议拒绝\"}", Metric.NAME_JSON_SCHEMA))
                    .as(
                            "约束共 3 条：requiredFields(conclusion, riskLevel) + fieldTypes(riskLevel)。"
                                    + "缺 riskLevel 时它同时违反「存在」与「类型为 string」两条约束，只剩 1/3")
                    .isEqualByComparingTo("0.3333");
            assertThat(score(c, "这不是 JSON", Metric.NAME_JSON_SCHEMA))
                    .isEqualByComparingTo("0.0000");
            assertThat(score(c, "[1,2,3]", Metric.NAME_JSON_SCHEMA))
                    .as("顶层必须是对象")
                    .isEqualByComparingTo("0.0000");
        }

        @Test
        @DisplayName("jsonSchema：类型不匹配被判失败")
        void jsonSchemaTypeMismatch() {
            EvalCase c =
                    EvalCase.of(
                            "j",
                            Map.of("question", "q"),
                            Map.of(
                                    "requiredFields",
                                    List.of("age"),
                                    "fieldTypes",
                                    Map.of("age", "number")),
                            "信贷",
                            "easy",
                            true,
                            null);
            assertThat(score(c, "{\"age\":\"25\"}", Metric.NAME_JSON_SCHEMA))
                    .as("age 存在但类型是 string 而非 number → 2 条约束只满足 1 条")
                    .isEqualByComparingTo("0.5000");
            assertThat(score(c, "{\"age\":25}", Metric.NAME_JSON_SCHEMA))
                    .isEqualByComparingTo("1.0000");
            assertThat(score(c, "{}", Metric.NAME_JSON_SCHEMA)).isEqualByComparingTo("0.0000");
        }

        @Test
        @DisplayName("cost：成本越低得分越高，且单调")
        void cost() {
            EvalCase c = serviceCase("m", "q", List.of(), List.of());
            CostMetric metric = new CostMetric(new BigDecimal("1.0"));
            assertThat(metric.scoreOf(BigDecimal.ZERO))
                    .isGreaterThan(metric.scoreOf(new BigDecimal("1.0")));
            assertThat(metric.scoreOf(new BigDecimal("1.0")).doubleValue())
                    .as("avg = 预算时约 0.5")
                    .isCloseTo(0.5d, org.assertj.core.data.Offset.offset(0.001d));
            assertThat(metric.scoreOf(new BigDecimal("100.0")))
                    .as("超预算 100 倍仍为正，避免 clamp 到 0 后丧失区分度")
                    .isPositive();
            assertThat(metric.scoreOf(null)).isEqualByComparingTo("1.0000");
            assertThat(metric.budgetCny()).isEqualByComparingTo("1.0");
        }

        @Test
        @DisplayName("cost：预算非法时 fail-fast")
        void costBudgetValidation() {
            assertThatThrownBy(() -> new CostMetric(BigDecimal.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new CostMetric(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("normalize 把越界值裁剪到 [0,1]，NaN 归零")
        void normalizeClamps() {
            assertThat(Metric.normalize(-1.0d)).isEqualByComparingTo("0.0000");
            assertThat(Metric.normalize(2.0d)).isEqualByComparingTo("1.0000");
            assertThat(Metric.normalize(Double.NaN)).isEqualByComparingTo("0.0000");
            assertThat(Metric.normalize(0.4567d)).isEqualByComparingTo("0.4567");
        }
    }

    @Nested
    @DisplayName("失败处理")
    class Failures {

        @Test
        @DisplayName("★ 失败用例不参与指标平均（区分「跑挂了」与「效果差」）")
        void failedCasesExcludedFromScores() {
            List<EvalCase> cases =
                    List.of(
                            serviceCase("ok-1", "q", List.of("退货"), List.of()),
                            serviceCase("ok-2", "q", List.of("退货"), List.of()),
                            serviceCase("bad", "q", List.of("退款"), List.of()));
            EvalRunner runner =
                    new EvalRunner(
                            c -> {
                                if ("bad".equals(c.id())) {
                                    throw new IllegalStateException("模拟模型超时");
                                }
                                return EvalResult.success(
                                        c.id(), "支持退货", Usage.of(10L, 10L), null, 1L);
                            });
            EvalReport report = runner.run(dataset(cases), EvalConfig.of("mock/mock-chat", "v3"));

            assertThat(report.failedCalls()).isEqualTo(1L);
            assertThat(report.failedCaseIds()).containsExactly("bad");
            assertThat(report.metricScores().get(Metric.NAME_KEYWORD_RECALL))
                    .as("2 条成功用例全命中 → 1.0；失败用例不拉低平均")
                    .isEqualByComparingTo("1.0000");
            assertThat(report.allPassed()).isFalse();
            assertThat(report.successRate())
                    .isCloseTo(0.6667d, org.assertj.core.data.Offset.offset(0.001d));
        }

        @Test
        @DisplayName("continueOnCaseError=false 时遇到失败即停止")
        void stopOnFirstFailure() {
            List<EvalCase> cases =
                    List.of(
                            serviceCase("t-1", "q", List.of("a"), List.of()),
                            serviceCase("t-2", "q", List.of("a"), List.of()),
                            serviceCase("t-3", "q", List.of("a"), List.of()));
            EvalConfig stopEarly =
                    new EvalConfig(
                            "mock/mock-chat",
                            "v3",
                            null,
                            null,
                            BigDecimal.ONE,
                            0,
                            1,
                            30_000L,
                            null,
                            null,
                            false);
            EvalReport report =
                    new EvalRunner(
                                    c -> {
                                        if ("t-1".equals(c.id())) {
                                            throw new IllegalStateException("boom");
                                        }
                                        return EvalResult.success(c.id(), "a", null, null, 1L);
                                    })
                            .run(dataset(cases), stopEarly);
            assertThat(report.results()).hasSize(1);
            assertThat(report.failedCalls()).isEqualTo(1L);
        }

        @Test
        @DisplayName("失败原因被记为可读文本")
        void failureMessageIsReadable() {
            EvalRunner runner =
                    new EvalRunner(
                            c -> {
                                throw new IllegalArgumentException("模型返回 429");
                            });
            EvalReport report =
                    runner.run(
                            dataset(List.of(serviceCase("f", "q", List.of("a"), List.of()))),
                            EvalConfig.of("mock/mock-chat", "v3"));
            assertThat(report.results().get(0).error())
                    .contains("IllegalArgumentException")
                    .contains("429");
            assertThat(report.results().get(0).passed()).isFalse();
        }
    }

    @Nested
    @DisplayName("EvalCase 与数据集")
    class Cases {

        @Test
        @DisplayName("★ metadata 必须有 authored 或 source（禁止无出处数据）")
        void provenanceIsMandatory() {
            assertThatThrownBy(
                            () ->
                                    new EvalCase(
                                            "x",
                                            Map.of("q", "1"),
                                            Map.of("contains", List.of("a")),
                                            Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("authored");
            assertThatThrownBy(
                            () ->
                                    new EvalCase(
                                            "x",
                                            Map.of("q", "1"),
                                            Map.of("contains", List.of("a")),
                                            Map.of("scenario", "售后")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("source");
        }

        @Test
        @DisplayName("四字段任一为空即拒绝")
        void fourFieldsRequired() {
            assertThatThrownBy(
                            () ->
                                    new EvalCase(
                                            " ",
                                            Map.of("q", "1"),
                                            Map.of("c", "x"),
                                            Map.of("authored", "a")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(
                            () ->
                                    new EvalCase(
                                            "x",
                                            Map.of(),
                                            Map.of("c", "x"),
                                            Map.of("authored", "a")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(
                            () ->
                                    new EvalCase(
                                            "x",
                                            Map.of("q", "1"),
                                            Map.of(),
                                            Map.of("authored", "a")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("非自撰用例必须给出来源")
        void nonAuthoredRequiresSource() {
            assertThatThrownBy(
                            () ->
                                    EvalCase.of(
                                            "x",
                                            Map.of("q", "1"),
                                            Map.of("c", "x"),
                                            "售后",
                                            "easy",
                                            false,
                                            null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("source");
            EvalCase c =
                    EvalCase.of(
                            "x",
                            Map.of("q", "1"),
                            Map.of("c", "x"),
                            "售后",
                            "easy",
                            false,
                            "《管理办法》第 3 条");
            assertThat(c.authored()).isFalse();
            assertThat(c.provenance()).isEqualTo("《管理办法》第 3 条");
        }

        @Test
        @DisplayName("防御性拷贝：外部修改不影响用例")
        void defensiveCopies() {
            java.util.Map<String, Object> input = new java.util.LinkedHashMap<>();
            input.put("question", "q");
            EvalCase c =
                    new EvalCase(
                            "x", input, Map.of("contains", List.of("a")), Map.of("authored", "t"));
            input.put("question", "改了");
            assertThat(c.inputText("question")).isEqualTo("q");
            assertThatThrownBy(() -> c.input().put("k", "v"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("expectedList / expectedText 的取值与兜底")
        void expectedAccessors() {
            EvalCase c = serviceCase("e", "q", List.of("a", "b"), List.of("c"));
            assertThat(c.expectedList("contains")).containsExactly("a", "b");
            assertThat(c.expectedList("forbidden")).containsExactly("c");
            assertThat(c.expectedList("missing")).isEmpty();
            assertThat(c.expectedText("missing")).isNull();
            assertThat(c.inputText("question")).isEqualTo("q");
            assertThat(c.scenario()).isEqualTo("售后");
        }

        @Test
        @DisplayName("JsonlDataset：id 唯一性、场景分布、指纹随内容变化")
        void datasetInvariants() {
            EvalCase c1 = serviceCase("d-1", "q", List.of("a"), List.of());
            EvalCase c2 = serviceCase("d-2", "q", List.of("a"), List.of());
            JsonlDataset ds = dataset(List.of(c1, c2));

            assertThat(ds.size()).isEqualTo(2);
            assertThat(ds.caseById("d-1")).isSameAs(c1);
            assertThat(ds.caseById("nope")).isNull();
            assertThat(ds.hasUniqueIds()).isTrue();
            assertThat(ds.scenarioDistribution()).containsEntry("售后", 2);
            assertThat(ds.fingerprint()).isEqualTo(dataset(List.of(c1, c2)).fingerprint());
            assertThat(dataset(List.of(c1)).fingerprint())
                    .as("内容不同的数据集指纹必须不同，否则报告无法追溯")
                    .isNotEqualTo(ds.fingerprint());

            assertThatThrownBy(() -> new JsonlDataset("empty", "v1", List.of(), null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new JsonlDataset(" ", "v1", List.of(c1), null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("EvalResult / EvalReport / EvalConfig")
    class ValueObjects {

        @Test
        @DisplayName("EvalResult：passed 由 error 唯一决定")
        void resultPassedIsErrorDriven() {
            assertThat(EvalResult.success("x", "a", null, null, 1L).passed()).isTrue();
            assertThat(EvalResult.failure("x", "boom").passed()).isFalse();
            assertThat(EvalResult.success("x", null, null, null, 1L).actualLength()).isZero();
            assertThat(EvalResult.failure("x", null).error()).isEqualTo("未知错误");
        }

        @Test
        @DisplayName("EvalReport：totalCalls 不小于结果数，failedCalls 越界被拒")
        void reportInvariants() {
            assertThat(EvalReport.empty("ds", "v1").totalCalls()).isZero();
            assertThat(EvalReport.empty("ds", "v1").successRate()).isZero();
            assertThatThrownBy(
                            () ->
                                    new EvalReport(
                                            "ds",
                                            "v1",
                                            "fp",
                                            "p",
                                            "m",
                                            List.of(),
                                            Map.of(),
                                            1L,
                                            5L,
                                            BigDecimal.ZERO,
                                            java.time.Instant.now(),
                                            0L))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("EvalConfig：字段兜底与副本构造")
        void configDefaults() {
            EvalConfig base = EvalConfig.of("mock/mock-chat", "v3");
            assertThat(base.parallelism()).isEqualTo(1);
            assertThat(base.maxCaseFailures()).isZero();
            assertThat(base.costBudgetCny()).isEqualByComparingTo("1.0");
            assertThat(base.continueOnCaseError()).isTrue();
            assertThat(base.exceedsFailureBudget(1L)).isTrue();
            assertThat(base.exceedsFailureBudget(0L)).isFalse();
            assertThat(base.withGate("accuracy>0.9").gateExpression()).isEqualTo("accuracy>0.9");
            assertThat(base.withBaseline("v2").baselinePromptVersion()).isEqualTo("v2");
            assertThat(base.withParallelism(4).parallelism()).isEqualTo(4);
        }

        @Test
        @DisplayName("Usage 在评测结果里被正确携带")
        void usageCarriedThrough() {
            EvalResult r =
                    EvalResult.success("x", "a", Usage.of(100L, 50L), new BigDecimal("0.001"), 5L);
            assertThat(r.usage().totalTokens()).isEqualTo(150L);
            assertThat(r.cost()).isEqualByComparingTo("0.001");
            assertThat(r.usage().usageSource()).isEqualTo(UsageSource.VENDOR_REPORTED);
            assertThat(r.withScores(Map.of("m", BigDecimal.ONE)).scoreOf("m"))
                    .isEqualByComparingTo("1");
        }

        @Test
        @DisplayName("Digest 格式常量被契约测试共用")
        void digestConstant() {
            assertThat(io.github.nephets.glasshouse.core.audit.AuditEvent.DIGEST_PREFIX)
                    .isEqualTo("sha256:");
            assertThat(DIGEST).matches("^sha256:[0-9a-f]{64}$");
        }
    }
}
