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

import java.nio.file.Path;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import io.github.nephets.glasshouse.core.pricing.PriceEntry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code glasshouse prices reload} —— 重新加载并对比变化。
 *
 * <p>这是「价格表热更新」在运维层面的验证手段：改完文件跑一次，就看到 「哪些模型的价变了、从多少到多少、生效日是哪天」，不必等成本报表跑一周才发现。
 */
@Command(
        name = "reload",
        mixinStandardHelpOptions = true,
        sortOptions = false,
        description = "从指定文件重新加载价格表，并对比与当前内置表的差异。")
public final class PricesReloadCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(PricesReloadCommand.class);

    /**
     * 当前命令的 spec。picocli 通过反射注入，字段必须包级可见。
     *
     * <p>用 {@code @Spec} 而不是 {@code @ParentCommand}：后者只能拿到直接父命令， {@code eval run} 的父是 {@code
     * EvalCommand} 而非 {@code GlasshouseCli}， 类型不匹配会让 picocli 在初始化阶段直接失败。
     */
    @Spec CommandSpec spec;

    @Option(
            names = {"-f", "--file"},
            paramLabel = "<path>",
            description = "价格表文件路径。")
    Path file;

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
    public Integer call() {
        if (file == null) {
            log.warn("[glasshouse] 错误: 必须用 --file 指定价格表文件。");
            return GlasshouseCli.EXIT_USAGE_ERROR;
        }
        try (CliRuntime runtime = findRoot().newRuntime()) {
            var before = new java.util.LinkedHashMap<String, PriceEntry>();
            runtime.pricingTable().all().forEach(e -> before.put(e.lookupKey(), e));

            var result = runtime.pricingLoader().loadFromFile(file);
            runtime.pricingTable().replaceAll(result.entries());

            System.out.println("重载完成: " + result.summary());
            int changed = 0;
            int added = 0;
            for (PriceEntry entry : runtime.pricingTable().all()) {
                PriceEntry old = before.get(entry.lookupKey());
                if (old == null) {
                    added++;
                    System.out.println(
                            "  + 新增 "
                                    + entry.lookupKey()
                                    + " in="
                                    + entry.inputPricePerUnit()
                                    + " out="
                                    + entry.outputPricePerUnit());
                } else if (!old.inputPricePerUnit().equals(entry.inputPricePerUnit())
                        || !old.outputPricePerUnit().equals(entry.outputPricePerUnit())) {
                    changed++;
                    System.out.println(
                            "  ~ 变更 "
                                    + entry.lookupKey()
                                    + " 输入 "
                                    + old.inputPricePerUnit()
                                    + " → "
                                    + entry.inputPricePerUnit()
                                    + "，输出 "
                                    + old.outputPricePerUnit()
                                    + " → "
                                    + entry.outputPricePerUnit()
                                    + "（生效日 "
                                    + entry.effectiveFrom()
                                    + "）");
                }
            }
            System.out.println("新增 " + added + " 条，价格变更 " + changed + " 条。");
            if (result.hasSkipped()) {
                // ★ 价格表加载跳过条目会让「某模型成本恒为 0」，是最典型的静默失败场景，必须进日志
                log.warn(
                        "[glasshouse] 价格表 {} 有 {} 条被跳过: {}",
                        file,
                        result.skipped().size(),
                        result.skipped());
                System.out.println("⚠ 跳过条目 " + result.skipped().size() + " 条：");
                result.skipped().forEach(s -> System.out.println("   - " + s));
            }
            log.info(
                    "[glasshouse] 价格表重载完成: file={}, 新增={}, 变更={}, 跳过={}",
                    file,
                    added,
                    changed,
                    result.skipped().size());
            System.out.println("提示：生产环境请把该文件挂载到固定路径，由 PricingTableLoader 定时热更新，无需重启。");
            return result.hasSkipped() ? GlasshouseCli.EXIT_GATE_FAILED : GlasshouseCli.EXIT_OK;
        }
    }
}
