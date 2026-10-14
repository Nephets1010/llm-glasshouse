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

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import io.github.nephets.glasshouse.core.pricing.PriceEntry;
import io.github.nephets.glasshouse.core.pricing.PriceSource;

/**
 * {@code glasshouse prices show} —— 列出价格表内容。
 *
 * <p>输出里「来源」列区分 {@code OFFICIAL} / {@code CUSTOM} / {@code ESTIMATED}： {@code ESTIMATED}
 * 是我们自己估的价，UI 必须标注，避免被当成官方价用于成本核算。
 */
@Command(
        name = "show",
        mixinStandardHelpOptions = true,
        sortOptions = false,
        description = "列出价格表内容。")
public final class PricesShowCommand implements Callable<Integer> {

    /**
     * 当前命令的 spec。picocli 通过反射注入，字段必须包级可见。
     *
     * <p>用 {@code @Spec} 而不是 {@code @ParentCommand}：后者只能拿到直接父命令， {@code eval run} 的父是 {@code
     * EvalCommand} 而非 {@code GlasshouseCli}， 类型不匹配会让 picocli 在初始化阶段直接失败。
     */
    @Spec CommandSpec spec;

    @Option(
            names = {"--provider"},
            paramLabel = "<providerId>",
            description = "只看指定供应商的条目。")
    String provider;

    @Option(names = "--estimated-only", description = "只看 source=ESTIMATED 的条目（这些值 UI 必须标注）。")
    boolean estimatedOnly;

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
        try (CliRuntime runtime = findRoot().newRuntime()) {
            System.out.println("价格表来源: classpath:prices/glasshouse-prices.yml");
            System.out.println("条目总数  : " + runtime.pricingTable().size());
            System.out.println("覆盖范围  : " + runtime.pricingTable().describe());
            System.out.println();
            System.out.printf(
                    "%-12s %-28s %12s %12s %12s %-10s %s%n",
                    "供应商", "模型", "输入/1K", "输出/1K", "缓存/1K", "来源", "生效日");
            System.out.println("-".repeat(110));
            for (PriceEntry entry : runtime.pricingTable().all()) {
                if (provider != null && !provider.equalsIgnoreCase(entry.providerId())) {
                    continue;
                }
                if (estimatedOnly && entry.source() != PriceSource.ESTIMATED) {
                    continue;
                }
                System.out.printf(
                        "%-12s %-28s %12s %12s %12s %-10s %s%n",
                        entry.providerId(),
                        entry.modelId(),
                        entry.inputPricePerUnit().toPlainString(),
                        entry.outputPricePerUnit().toPlainString(),
                        entry.effectiveCachedInputPrice().toPlainString(),
                        entry.source(),
                        entry.effectiveFrom());
            }
            return GlasshouseCli.EXIT_OK;
        }
    }
}
