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

package io.github.nephets.glasshouse.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.nephets.glasshouse.core.eval.EvalCase;
import io.github.nephets.glasshouse.core.eval.EvalConfig;
import io.github.nephets.glasshouse.core.eval.EvalReport;
import io.github.nephets.glasshouse.core.eval.EvalResult;
import io.github.nephets.glasshouse.core.eval.EvalRunner;
import io.github.nephets.glasshouse.core.eval.JsonlDataset;
import io.github.nephets.glasshouse.core.pricing.PricingTable;
import io.github.nephets.glasshouse.eval.dataset.DatasetRegistry;
import io.github.nephets.glasshouse.eval.dataset.JsonlDatasetParser;
import io.github.nephets.glasshouse.eval.gate.GateParser;
import io.github.nephets.glasshouse.eval.runner.BaselineComparator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 评测引擎门面测试。
 *
 * <p>{@link EvalEngine} 此前同样零覆盖。它是 CLI 与 GitHub Action 共用的唯一编排入口， 「加载数据集 → 跑评测 → 判门禁 →
 * 写报告」四步的顺序和错误归类都只在这里定义，因此值得逐条钉住。
 *
 * <p><b>断言的是编排语义</b>：用例数、失败数、退出码、报告文件个数、异常类型。不断言具体分值与 数据集内容的对应关系——那种断言会在评测集扩充时无缘无故变红，且不能反映引擎是否出错。
 *
 * <p><b>回归检测为什么要用合成数据集</b>：内置的 {@code customer-service-v1} 与 {@code credit-risk-v3} 的 {@code
 * expected} 里都没有 {@code exact} 键，{@code ExactMatchMetric.scoreOf} 拿到 null 期望就恒返回 0， 于是 accuracy 恒为
 * 0、{@code EvalResult.meetsThreshold} 每条恒 false。用它们做对比， 「failed → failed」不构成退化，{@code
 * regressedCount} 恒为 0 —— 那种断言对任何输入都成立，证明不了退化能被抓到。 因此 {@link #compareDetectsRealRegression()}
 * 用内存里造的合成集，让 accuracy 真的有 1.0 与 0.0 之分。
 */
class EvalEngineTest {

    private static final String DATASET = "customer-service-v1";

    private static final EvalConfig CONFIG = EvalConfig.of("deepseek/deepseek-chat", "planner@v3");

    /** 固定回答「支持七天无理由退货」，对客服评测集通常是达标答案。 */
    private static ChatModel steady() {
        return prompt ->
                ChatResponse.builder()
                        .generations(
                                List.of(
                                        new Generation(
                                                AssistantMessage.builder()
                                                        .content("支持七天无理由退货，签收后 7 天内可申请。")
                                                        .build())))
                        .metadata(
                                ChatResponseMetadata.builder()
                                        .usage(new DefaultUsage(800, 200))
                                        .build())
                        .build();
    }

    private static EvalEngine engine() {
        return new EvalEngine(new DatasetRegistry(), new PricingTable());
    }

    /**
     * 造一个 3 条用例的合成数据集。
     *
     * <p>每条用例的 {@code expected} 同时带 {@code exact} 与 {@code contains}：{@code exact} 让 accuracy 有真实的
     * 1.0 / 0.0 之分，{@code contains} 让 keywordRecall 不为 0——{@code EvalResult.meetsThreshold} 要求
     * accuracy、keywordRecall、containment 三者都达标，缺一个就恒不达标，退化同样观测不到。
     *
     * @return 评测集
     */
    private static JsonlDataset syntheticDataset() {
        return new JsonlDataset(
                "regression-fixture",
                "v1",
                List.of(
                        exactCase("rg-1", "七天无理由退货"),
                        exactCase("rg-2", "运费由卖家承担"),
                        exactCase("rg-3", "退款三个工作日内到账")),
                "基线对比用合成集");
    }

    /**
     * 造一条带 exact / contains 期望的用例。
     *
     * @param id 用例标识
     * @param answer 唯一正确答案
     * @return 用例
     */
    private static EvalCase exactCase(String id, String answer) {
        return EvalCase.of(
                id,
                Map.of("question", "问：" + id),
                Map.of("exact", answer, "contains", List.of(answer)),
                "售后",
                "easy",
                true,
                null);
    }

    /**
     * 基线答案表：三条全对。
     *
     * @return 用例 id → 答案
     */
    private static Map<String, String> correctAnswers() {
        Map<String, String> answers = new LinkedHashMap<>();
        answers.put("rg-1", "七天无理由退货");
        answers.put("rg-2", "运费由卖家承担");
        answers.put("rg-3", "退款三个工作日内到账");
        return answers;
    }

    /**
     * 用固定答案表跑一遍合成集。
     *
     * @param answers 用例 id → 模型输出
     * @param config 评测配置
     * @return 评测报告
     */
    private static EvalReport runSynthetic(Map<String, String> answers, EvalConfig config) {
        return new EvalRunner(c -> EvalResult.success(c.id(), answers.get(c.id()), null, null, 1L))
                .run(syntheticDataset(), config);
    }

    @Test
    @DisplayName("★ run 跑通内置评测集：用例数、失败数、退出码都对得上")
    void runEndToEnd() {
        EvalEngine.Result result =
                engine().run(
                                DATASET,
                                CONFIG,
                                steady(),
                                c -> new Prompt("问：" + c.input().get("question")));

        assertThat(result.report().totalCalls())
                .as("内置 customer-service-v1 是 20 条，这是数据集契约")
                .isEqualTo(20L);
        assertThat(result.report().failedCalls()).isZero();
        assertThat(result.report().datasetName()).isEqualTo(DATASET);
        assertThat(result.report().promptVersion()).isEqualTo("planner@v3");
        assertThat(result.report().modelRef()).isEqualTo("deepseek/deepseek-chat");
        assertThat(result.report().metricScores())
                .as("指标必须真的算出来，空表说明编排链断在中间")
                .containsKeys("accuracy", "keywordRecall", "containment", "cost");
        assertThat(result.exitCode()).isZero();
        assertThat(result.passed()).isTrue();
        assertThat(result.dataset()).isNotNull();
    }

    @Test
    @DisplayName("★ 模型按比例失败时：失败数准确、退出码为 1、结论里点名失败用例")
    void failuresAreCountedAndNamed() {
        AtomicInteger counter = new AtomicInteger();
        ChatModel flaky =
                prompt -> {
                    if (counter.incrementAndGet() % 5 == 0) {
                        throw new IllegalStateException("第 " + counter.get() + " 次超时");
                    }
                    return steady().call(prompt);
                };

        EvalEngine.Result result =
                engine().run(
                                DATASET,
                                CONFIG,
                                flaky,
                                c -> new Prompt("问：" + c.input().get("question")));

        assertThat(result.report().totalCalls()).isEqualTo(20L);
        assertThat(result.report().failedCalls()).as("20 条里每 5 条炸一次 → 4 条失败").isEqualTo(4L);
        assertThat(result.report().failedCaseIds()).hasSize(4);
        assertThat(result.exitCode())
                .as("★ 有用例跑挂就必须让流水线变红，否则「全部超时」会被判成「指标全 0 但达标」")
                .isEqualTo(GateParser.EXIT_GATE_FAILED);
        assertThat(result.verdict().message()).contains("执行失败");
    }

    @Test
    @DisplayName("★ 门禁表达式不达标 → exit 1；表达式写错 → exit 2")
    void gateOutcomesMapToExitCodes() {
        EvalEngine.Result tooStrict =
                engine().run(
                                DATASET,
                                CONFIG.withGate("accuracy>0.999999"),
                                steady(),
                                c -> new Prompt("q"));
        assertThat(tooStrict.exitCode()).isEqualTo(GateParser.EXIT_GATE_FAILED);

        assertThatThrownBy(
                        () ->
                                engine().run(
                                                DATASET,
                                                CONFIG.withGate("accuracy>>>1"),
                                                steady(),
                                                c -> new Prompt("q")))
                .as("★ 表达式语法错误必须抛 GateParseException，由 CLI 映射成 exit 2，不能混成「不达标」")
                .isInstanceOf(
                        io.github.nephets.glasshouse.core.eval.GateEvaluator.GateParseException
                                .class);
    }

    @Test
    @DisplayName("数据集不存在 → DatasetParseException（配置问题，不是评测不达标）")
    void missingDatasetIsParseException() {
        assertThatThrownBy(() -> engine().run("根本不存在的评测集", CONFIG, steady(), c -> new Prompt("q")))
                .isInstanceOf(JsonlDatasetParser.DatasetParseException.class);
    }

    @Test
    @DisplayName("模型引用写错 → IllegalArgumentException，且不吞成评测失败")
    void badModelRefIsRejected() {
        assertThatThrownBy(
                        () ->
                                engine().run(
                                                DATASET,
                                                EvalConfig.of("没有斜杠的模型引用", "v"),
                                                steady(),
                                                c -> new Prompt("q")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider/model");
    }

    @Test
    @DisplayName("★ writeReports：传 null 不写文件，传目录写两份报告")
    void writeReportsHonoursNullAndDirectory(@TempDir Path dir) throws IOException {
        EvalEngine engine = engine();
        EvalEngine.Result result =
                engine.run(
                        DATASET,
                        CONFIG,
                        steady(),
                        c -> new Prompt("问：" + c.input().get("question")));

        assertThat(engine.writeReports(result, null)).as("null 表示「不落盘」，返回空列表而不是抛异常").isEmpty();

        Path out = dir.resolve("nested");
        List<Path> written = engine.writeReports(result, out);
        assertThat(written).hasSize(2);
        assertThat(out.resolve("eval-report.md")).exists();
        assertThat(out.resolve("eval-report.json")).exists();
        assertThat(Files.readString(out.resolve("eval-report.md"), StandardCharsets.UTF_8))
                .as("Markdown 报告必须带结论段")
                .contains("## 结论")
                .contains(DATASET);
    }

    @Test
    @DisplayName("★ 构造出真实的效果退化，断言回归检测能抓到：基线全对、当前答错一条 → regressedCount=1")
    void compareDetectsRealRegression() {
        EvalReport baseline =
                runSynthetic(correctAnswers(), EvalConfig.of("mock/mock-chat", "planner@v3"));

        Map<String, String> oneWrong = new LinkedHashMap<>(correctAnswers());
        // 「到账」写成「到帐」：只差一个汉字，模型照样正常返回，passed() 仍是 true
        oneWrong.put("rg-3", "退款三个工作日内到帐");
        EvalReport current = runSynthetic(oneWrong, EvalConfig.of("mock/mock-chat", "planner@v4"));

        assertThat(baseline.scoreOf("accuracy"))
                .as("合成集带 exact 期望、基线三条全对，accuracy 必须真的算出 1.0000，否则下面的退化断不出来")
                .isEqualByComparingTo("1.0000");
        assertThat(current.scoreOf("accuracy"))
                .as("三条对两条 → 2/3 舍入到 0.6667")
                .isEqualByComparingTo("0.6667");

        BaselineComparator.Comparison cmp = new BaselineComparator().compare(baseline, current);

        assertThat(cmp.regressedCount()).as("★ 这条断言是回归检测的价值所在：答错一条必须被点出来，而不是只在总分上略降").isEqualTo(1L);
        assertThat(cmp.regressedCaseIds()).containsExactly("rg-3");
        assertThat(cmp.improvedCount()).isZero();
        assertThat(cmp.deltaOf("accuracy").delta())
                .as("0.6667 - 1.0000，下降幅度按实际值断言")
                .isEqualByComparingTo("-0.3333");
        assertThat(cmp.deltaOf("accuracy").improved()).isFalse();
        assertThat(cmp.deltaOf("accuracy").unchanged()).isFalse();

        BaselineComparator.CaseDiff regressed =
                cmp.caseDiffs().stream()
                        .filter(BaselineComparator.CaseDiff::regressed)
                        .findFirst()
                        .orElseThrow();
        assertThat(regressed.caseId()).isEqualTo("rg-3");
        assertThat(regressed.failed()).as("模型正常返回了错误答案，这条用例并没有「跑挂」，failed 反映的是执行失败").isFalse();
        assertThat(regressed.metricDeltas()).containsKey("accuracy");

        EvalEngine.Result baselineRun = new EvalEngine.Result(baseline, null, syntheticDataset());
        EvalEngine.Result currentRun = new EvalEngine.Result(current, null, syntheticDataset());
        assertThat(new EvalEngine.ComparisonResult(cmp, baselineRun, currentRun).notRegressed())
                .as("门禁读的就是这个方法，有真实退化时必须为 false")
                .isFalse();
    }

    @Test
    @DisplayName("同一份报告自比：没有退化也没有改善，accuracy 差值为零")
    void compareIdenticalReportsIsClean() {
        EvalReport report =
                runSynthetic(correctAnswers(), EvalConfig.of("mock/mock-chat", "planner@v3"));

        BaselineComparator.Comparison cmp = new BaselineComparator().compare(report, report);

        // notRegressed() 的定义就是 regressedCount() == 0，所以既断数量也断方法本身
        assertThat(cmp.regressedCount()).isZero();
        assertThat(cmp.improvedCount()).isZero();
        assertThat(cmp.caseDiffs()).isEmpty();
        assertThat(cmp.deltaOf("accuracy").delta()).isEqualByComparingTo("0.0000");
        assertThat(cmp.deltaOf("accuracy").unchanged()).isTrue();

        EvalEngine.Result run = new EvalEngine.Result(report, null, syntheticDataset());
        assertThat(new EvalEngine.ComparisonResult(cmp, run, run).notRegressed())
                .as("没有任何用例从达标变成不达标，门禁才该放行")
                .isTrue();
    }

    @Test
    @DisplayName("compare 把两个 Prompt 版本与数据集名透传进对比结论")
    void comparePassesPromptVersionsThrough() {
        EvalEngine.ComparisonResult cmp =
                engine().compare(
                                DATASET,
                                CONFIG,
                                EvalConfig.of("deepseek/deepseek-chat", "planner@v4"),
                                steady(),
                                c -> new Prompt("问：" + c.input().get("question")));

        assertThat(cmp.baseline().report().promptVersion()).isEqualTo("planner@v3");
        assertThat(cmp.current().report().promptVersion()).isEqualTo("planner@v4");
        assertThat(cmp.comparison().baselinePrompt()).isEqualTo("planner@v3");
        assertThat(cmp.comparison().currentPrompt()).isEqualTo("planner@v4");
        assertThat(cmp.comparison().datasetName()).isEqualTo(DATASET);
    }

    @Test
    @DisplayName("★ Result 在没有门禁判定时：passed=false 但 exitCode=0（不无故变红）")
    void resultWithoutVerdict() {
        EvalReport report = EvalReport.empty(DATASET, "v1");
        EvalCase oneCase =
                EvalCase.of(
                        "cs-1",
                        java.util.Map.of("question", "q"),
                        java.util.Map.of("contains", List.of("退货")),
                        "售后",
                        "easy",
                        true,
                        null);
        JsonlDataset dataset = new JsonlDataset(DATASET, "v1", List.of(oneCase), null);
        EvalEngine.Result result = new EvalEngine.Result(report, null, dataset);

        assertThat(result.passed()).as("没有判定就谈不上通过").isFalse();
        assertThat(result.exitCode()).as("★ 但退出码必须是 0：没配门禁不该让 CI 变红").isZero();
    }

    @Test
    @DisplayName("构造器拒绝 null；默认构造器可用")
    void constructorGuards() {
        assertThatThrownBy(() -> new EvalEngine(null, new PricingTable()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("registry");
        assertThatThrownBy(() -> new EvalEngine(new DatasetRegistry(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("pricingTable");

        EvalEngine def = new EvalEngine();
        assertThat(def.registry()).isNotNull();
    }
}
