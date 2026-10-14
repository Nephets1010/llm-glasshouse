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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import io.github.nephets.glasshouse.eval.EvalEngine;
import io.github.nephets.glasshouse.eval.dataset.DatasetRegistry;
import io.github.nephets.glasshouse.eval.runner.BaselineComparator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code glasshouse eval compare} —— 两个 Prompt 版本逐项对比。
 *
 * <p><b>判据是「有没有用例从对变错」</b>，而不是总分涨跌：总分持平但一条原本正确的用例 被做错是真实的退化；总分略升但有一条用例崩了也不能算改进。
 */
@Command(
        name = "compare",
        mixinStandardHelpOptions = true,
        sortOptions = false,
        description = "对两个 Prompt 版本在同一评测集上跑分，输出逐项 diff 与退化用例清单。")
public final class EvalCompareCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(EvalCompareCommand.class);

    /**
     * 当前命令的 spec。picocli 通过反射注入，字段必须包级可见。
     *
     * <p>用 {@code @Spec} 而不是 {@code @ParentCommand}：后者只能拿到直接父命令， {@code eval run} 的父是 {@code
     * EvalCommand} 而非 {@code GlasshouseCli}， 类型不匹配会让 picocli 在初始化阶段直接失败。
     */
    @Spec CommandSpec spec;

    @Mixin EvalCommonOptions options = new EvalCommonOptions();

    @Option(
            names = {"-b", "--baseline"},
            paramLabel = "<version>",
            description = "基线 Prompt 版本标识。")
    String baseline;

    @Option(
            names = {"--baseline-replay"},
            paramLabel = "<file>",
            description = "基线版本的录制回放文件。")
    Path baselineReplay;

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
        if (options.dataset == null || options.dataset.isBlank() || baseline == null) {
            log.warn("[glasshouse] 必须同时指定 --dataset 与 --baseline。");
            return GlasshouseCli.EXIT_USAGE_ERROR;
        }
        Path replay = options.resolveReplayFile();
        if (replay == null) {
            log.warn("[glasshouse] 未找到录制文件。");
            return GlasshouseCli.EXIT_USAGE_ERROR;
        }
        Path baseReplay =
                baselineReplay != null
                        ? baselineReplay
                        : Path.of(
                                "eval-datasets",
                                options.dataset + "." + baseline + ".responses.jsonl");
        if (!Files.isRegularFile(baseReplay)) {
            log.warn("[glasshouse] 未找到基线录制文件: {}", baseReplay.toAbsolutePath());
            return GlasshouseCli.EXIT_USAGE_ERROR;
        }

        try (CliRuntime runtime = findRoot().newRuntime()) {
            EvalEngine engine = new EvalEngine(new DatasetRegistry(), runtime.pricingTable());
            EvalEngine.Result baseResult =
                    engine.run(
                            options.dataset,
                            options.toConfig(null),
                            ReplayChatModel.fromFile(baseReplay),
                            options.renderer());
            EvalEngine.Result currentResult =
                    engine.run(
                            options.dataset,
                            options.toConfig(null),
                            ReplayChatModel.fromFile(replay),
                            options.renderer());

            BaselineComparator.Comparison comparison =
                    new BaselineComparator().compare(baseResult.report(), currentResult.report());
            System.out.println(comparison.describe());

            long regressed = comparison.regressedCount();
            if (regressed > 0) {
                System.out.println();
                System.out.println("❌ 存在退化用例：" + comparison.regressedCaseIds());
                return GlasshouseCli.EXIT_GATE_FAILED;
            }
            System.out.println();
            System.out.println("✅ 未引入退化用例。");
            return GlasshouseCli.EXIT_OK;
        } catch (RuntimeException e) {
            log.error("[glasshouse] 对比执行失败: {}", findRoot().describeError(e), e);
            return GlasshouseCli.EXIT_USAGE_ERROR;
        }
    }
}
