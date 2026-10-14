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

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

import io.github.nephets.glasshouse.core.eval.EvalConfig;
import io.github.nephets.glasshouse.core.eval.EvalReport;
import io.github.nephets.glasshouse.core.eval.EvalRunner;
import io.github.nephets.glasshouse.core.eval.JsonlDataset;
import io.github.nephets.glasshouse.core.model.ModelRef;
import io.github.nephets.glasshouse.core.pricing.PricingTable;
import io.github.nephets.glasshouse.eval.dataset.DatasetRegistry;
import io.github.nephets.glasshouse.eval.gate.GateParser;
import io.github.nephets.glasshouse.eval.report.EvalReportWriter;
import io.github.nephets.glasshouse.eval.runner.BaselineComparator;
import io.github.nephets.glasshouse.eval.runner.CaseExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 评测引擎门面：一次调用完成「加载数据集 → 跑评测 → 判门禁 → 出报告」。
 *
 * <p><b>为什么要门面</b>：CLI 与 GitHub Action 都需要同一套编排逻辑。 如果这段编排散落在 CLI 里，Action 就只能 shell out 到
 * CLI，用户在自己的项目里 想用 Java API 时又得重写一遍。门面把这套编排固定下来，三种入口共用同一份行为。
 *
 * <p><b>本类无 Spring 依赖</b>，只用普通构造器注入，因此在 CLI（纯 picocli）与 Spring 应用里都能用。
 */
public final class EvalEngine {

    private static final Logger log = LoggerFactory.getLogger(EvalEngine.class);

    private final DatasetRegistry registry;
    private final PricingTable pricingTable;

    /** 用默认注册表与空价格表构造（成本记 0）。 */
    public EvalEngine() {
        this(new DatasetRegistry(), new PricingTable());
    }

    /**
     * 构造评测引擎。
     *
     * @param registry 数据集注册表，不可为 null
     * @param pricingTable 价格表，不可为 null
     */
    public EvalEngine(DatasetRegistry registry, PricingTable pricingTable) {
        if (registry == null) {
            throw new NullPointerException("registry 不能为 null");
        }
        if (pricingTable == null) {
            throw new NullPointerException("pricingTable 不能为 null");
        }
        this.registry = registry;
        this.pricingTable = pricingTable;
    }

    /**
     * 跑一次评测并判定门禁。
     *
     * @param datasetName 数据集名称
     * @param config 评测配置，不可为 null
     * @param chatModel 模型，不可为 null
     * @param promptRenderer Prompt 渲染器，不可为 null
     * @return 评测结果（报告 + 门禁判定）
     */
    public Result run(
            String datasetName,
            EvalConfig config,
            ChatModel chatModel,
            Function<io.github.nephets.glasshouse.core.eval.EvalCase, Prompt> promptRenderer) {
        JsonlDataset dataset = registry.load(datasetName);
        ModelRef modelRef = ModelRef.parse(config.modelRef());
        CaseExecutor executor =
                new CaseExecutor(
                        chatModel, modelRef, pricingTable, promptRenderer, config.promptVersion());
        EvalRunner runner = new EvalRunner(executor);
        EvalReport report = runner.run(dataset, config);
        GateParser.Verdict verdict =
                GateParser.evaluateAll(report, config.gateExpression(), config.maxCaseFailures());
        log.info("[glasshouse] 评测完成: {} → exitCode={}", report.summaryLine(), verdict.exitCode());
        return new Result(report, verdict, dataset);
    }

    /**
     * 对比两个 Prompt 版本在同一数据集上的表现。
     *
     * @param datasetName 数据集名称
     * @param baselineConfig 基线配置
     * @param currentConfig 当前配置
     * @param chatModel 模型
     * @param promptRenderer Prompt 渲染器
     * @return 对比结果（含两份报告）
     */
    public ComparisonResult compare(
            String datasetName,
            EvalConfig baselineConfig,
            EvalConfig currentConfig,
            ChatModel chatModel,
            Function<io.github.nephets.glasshouse.core.eval.EvalCase, Prompt> promptRenderer) {
        Result baseline = run(datasetName, baselineConfig, chatModel, promptRenderer);
        Result current = run(datasetName, currentConfig, chatModel, promptRenderer);
        BaselineComparator comparator = new BaselineComparator();
        BaselineComparator.Comparison comparison =
                comparator.compare(baseline.report(), current.report());
        return new ComparisonResult(comparison, baseline, current);
    }

    /**
     * 写报告到目录。
     *
     * @param result 评测结果
     * @param outputDir 输出目录；传 null 表示不写文件
     * @return 写入的文件路径列表
     */
    public List<Path> writeReports(Result result, Path outputDir) {
        if (outputDir == null) {
            return java.util.List.of();
        }
        List<Path> written = new java.util.ArrayList<>();
        Path md = outputDir.resolve("eval-report.md");
        Path json = outputDir.resolve("eval-report.json");
        EvalReportWriter.writeMarkdown(result.report(), result.verdict(), md);
        EvalReportWriter.writeJson(result.report(), result.verdict(), json);
        written.add(md);
        written.add(json);
        return List.copyOf(written);
    }

    /**
     * 返回数据集注册表。
     *
     * @return 注册表
     */
    public DatasetRegistry registry() {
        return registry;
    }

    /**
     * 单次评测结果。
     *
     * @param report 评测报告
     * @param verdict 门禁判定
     * @param dataset 数据集
     */
    public record Result(EvalReport report, GateParser.Verdict verdict, JsonlDataset dataset) {

        /**
         * 判断门禁是否通过。
         *
         * @return 通过返回 {@code true}
         */
        public boolean passed() {
            return verdict != null && verdict.passed();
        }

        /**
         * 返回进程退出码。
         *
         * <p>这是 CLI 与 GitHub Action 之间的唯一契约：Action 只需读这个整数。
         *
         * @return 0 通过 / 1 门禁未达标 / 2 配置错误
         */
        public int exitCode() {
            return verdict == null ? GateParser.EXIT_OK : verdict.exitCode();
        }
    }

    /**
     * 对比结果。
     *
     * @param comparison 对比详情
     * @param baseline 基线结果
     * @param current 当前结果
     */
    public record ComparisonResult(
            BaselineComparator.Comparison comparison, Result baseline, Result current) {

        /**
         * 判断当前版本是否比基线更好。
         *
         * <p>判定标准：<b>没有用例从「通过」变成「失败」</b>。 为什么不看总分？因为「总分持平但一条原本正确的用例被做错」是真实的退化，
         * 而「总分略升但有一条用例崩了」不能算改进。用例级的「不引入退化」是更稳的门禁标准。
         *
         * @return 无退化返回 {@code true}
         */
        public boolean notRegressed() {
            return comparison.regressedCount() == 0L;
        }
    }
}
