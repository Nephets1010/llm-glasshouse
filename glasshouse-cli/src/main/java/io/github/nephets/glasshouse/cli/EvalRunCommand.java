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

package io.github.nephets.glasshouse.cli;

import java.util.List;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import io.github.nephets.glasshouse.eval.EvalEngine;
import io.github.nephets.glasshouse.eval.report.EvalReportWriter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;

/**
 * {@code glasshouse eval run} —— 跑一次评测并判门禁。
 *
 * <p><b>退出码契约</b>（GitHub Action 唯一依赖的东西）：
 *
 * <ul>
 *   <li>{@code 0} 通过 / 未配门禁
 *   <li>{@code 1} 门禁未达标 → <b>让流水线变红</b>
 *   <li>{@code 2} 参数或配置错误 → 这是命令写错了，不是评测不达标
 * </ul>
 */
@Command(
        name = "run",
        mixinStandardHelpOptions = true,
        sortOptions = false,
        description = "跑一次评测并判门禁。达标 exit 0，不达标 exit 1（让流水线变红）。")
public final class EvalRunCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(EvalRunCommand.class);

    /**
     * 当前命令的 spec。picocli 通过反射注入，字段必须包级可见。
     *
     * <p>用 {@code @Spec} 而不是 {@code @ParentCommand}：后者只能拿到直接父命令， {@code eval run} 的父是 {@code
     * EvalCommand} 而非 {@code GlasshouseCli}， 类型不匹配会让 picocli 在初始化阶段直接失败。
     */
    @Spec CommandSpec spec;

    @Mixin EvalCommonOptions options = new EvalCommonOptions();

    @Option(
            names = {"-g", "--gate"},
            paramLabel = "<expr>",
            description = "门禁表达式，如 accuracy>0.85,keywordRecall>0.9,containment>0.95。不配置则只出报告不判定。")
    String gate;

    @Option(
            names = {"-v", "--verbose"},
            description = "在控制台打印完整 Markdown 报告。")
    boolean verbose;

    /**
     * 沿命令树向上找根命令。
     *
     * <p>{@code @ParentCommand} 只能拿到<b>直接父命令</b>：{@code eval run} 的父是 {@code EvalCommand} 而非 {@code
     * GlasshouseCli}，类型不匹配会让 picocli 在初始化阶段 直接失败（所有子命令一起挂掉）。改用 {@code @Spec} 自己往上走。
     *
     * @return 根命令；理论上不会为 null
     */
    private GlasshouseCli findRoot() {
        picocli.CommandLine.Model.CommandSpec current = spec;
        while (current != null) {
            if (current.userObject() instanceof GlasshouseCli root) {
                return root;
            }
            current = current.parent();
        }
        throw new IllegalStateException(
                "命令树中找不到根命令 GlasshouseCli —— 这是 picocli 组装异常，请检查 @Command 注册");
    }

    @Override
    public Integer call() throws Exception {
        if (options.dataset == null || options.dataset.isBlank()) {
            log.warn("[glasshouse] eval run 缺少 --dataset");
            System.err.println("[glasshouse] 错误: 必须用 --dataset 指定评测集。");
            return GlasshouseCli.EXIT_USAGE_ERROR;
        }
        log.info("[glasshouse] 评测开始: dataset={}, gate={}", options.dataset, gate);
        ChatModel model;
        try {
            model = options.buildModel(findRoot());
        } catch (java.io.IOException | IllegalStateException e) {
            // ★ 回放文件读不了是「配置错误」，必须 exit 2。
            //   混进 exit 1 会让 CI 显示「门禁不达标」，使用者会去调阈值，
            //   而真正的问题是文件路径写错了。
            log.warn("[glasshouse] 模型装配失败: {}", e.toString());
            System.err.println("[glasshouse] 错误: " + findRoot().describeError(e));
            return GlasshouseCli.EXIT_USAGE_ERROR;
        }

        try (CliRuntime runtime = findRoot().newRuntime()) {
            EvalEngine engine =
                    new EvalEngine(
                            new io.github.nephets.glasshouse.eval.dataset.DatasetRegistry(),
                            runtime.pricingTable());
            EvalEngine.Result result =
                    engine.run(options.dataset, options.toConfig(gate), model, options.renderer());
            List<java.nio.file.Path> written = engine.writeReports(result, options.outDir);

            System.out.println("=== llm-glasshouse 评测报告 ===");
            System.out.println(result.report().summaryLine());
            System.out.println();
            System.out.println(result.verdict().message());
            System.out.println();
            System.out.println("指标：");
            result.report()
                    .metricScores()
                    .forEach((k, v) -> System.out.println("  " + k + " = " + v));
            if (!written.isEmpty()) {
                System.out.println();
                written.forEach(p -> System.out.println("报告已写入: " + p.toAbsolutePath()));
            }
            if (verbose) {
                System.out.println();
                System.out.println(EvalReportWriter.toMarkdown(result.report(), result.verdict()));
            }
            // ★ 门禁结论是 CI 唯一关心的状态迁移点，必须进日志：
            //   流水线日志里「这次为什么红」要比报告文件更容易被检索到。
            log.info(
                    "[glasshouse] 评测结束: dataset={}, exitCode={}, 结论={}",
                    options.dataset,
                    result.verdict().exitCode(),
                    result.verdict().message());
            return result.verdict().exitCode();
        } catch (
                io.github.nephets.glasshouse.eval.dataset.JsonlDatasetParser.DatasetParseException
                        e) {
            log.warn("[glasshouse] 评测集解析失败: {}", e.getMessage());
            System.err.println("[glasshouse] 评测集错误: " + e.getMessage());
            return GlasshouseCli.EXIT_USAGE_ERROR;
        } catch (io.github.nephets.glasshouse.core.eval.GateEvaluator.GateParseException e) {
            log.warn("[glasshouse] 门禁表达式解析失败: {}", e.getMessage());
            System.err.println("[glasshouse] 门禁配置错误: " + e.getMessage());
            return GlasshouseCli.EXIT_USAGE_ERROR;
        } catch (RuntimeException e) {
            log.error("[glasshouse] 评测执行失败", e);
            System.err.println("[glasshouse] 错误: " + findRoot().describeError(e));
            return GlasshouseCli.EXIT_USAGE_ERROR;
        }
    }
}
