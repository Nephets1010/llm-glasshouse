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

import java.util.Map;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import io.github.nephets.glasshouse.cli.version.BuildVersion;
import io.github.nephets.glasshouse.core.usage.CnUsageNormalizer;

/**
 * {@code glasshouse prices support} —— 打印国产模型支持矩阵。
 *
 * <p>与 README 的支持矩阵<b>同源</b>（都读 {@link CnUsageNormalizer#supportMatrix()}）， 避免文档与代码漂移——这是架构 2.4
 * 节要求的。
 */
@Command(
        name = "support",
        mixinStandardHelpOptions = true,
        sortOptions = false,
        description = "打印国产模型计量口径与价格表维护状态。")
public final class PricesSupportCommand implements Callable<Integer> {

    /**
     * 当前命令的 spec。picocli 通过反射注入，字段必须包级可见。
     *
     * <p>用 {@code @Spec} 而不是 {@code @ParentCommand}：后者只能拿到直接父命令， {@code eval run} 的父是 {@code
     * EvalCommand} 而非 {@code GlasshouseCli}， 类型不匹配会让 picocli 在初始化阶段直接失败。
     */
    @Spec CommandSpec spec;

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
            System.out.println("国产模型计量口径支持矩阵（llm-glasshouse " + BuildVersion.version() + "）");
            System.out.println();
            System.out.printf(
                    "%-12s %-16s %-10s %-10s %s%n", "providerId", "供应商", "计量口径", "价格条目", "备注");
            System.out.println("-".repeat(88));
            for (Map.Entry<String, String> entry : CnUsageNormalizer.supportMatrix().entrySet()) {
                long priceCount =
                        runtime.pricingTable().all().stream()
                                .filter(p -> p.providerId().equals(entry.getKey()))
                                .count();
                System.out.printf(
                        "%-12s %-16s %-10s %-10s %s%n",
                        entry.getKey(),
                        entry.getValue(),
                        "CnUsage",
                        priceCount,
                        priceCount > 0 ? "已维护价格" : "未配置价格（成本将记 0）");
            }
            System.out.println();
            System.out.println("计量口径说明：");
            System.out.println(
                    "  · 通义/智谱/DeepSeek/Kimi/豆包 走 CnUsageNormalizer，各自的 reasoning 字段位置不同");
            System.out.println(
                    "  · 供应商不返回推理 token 时，reasoningTokens=0 且 reasoningEstimated=true（不编造数字）");
            System.out.println("  · 价格表热更新：改 glasshouse-prices.yml 即可，无需改代码、无需重启");
            System.out.println();
            System.out.println("非国产但已支持：openai / anthropic / ollama（OpenAI 与 Anthropic 各有独立口径）。");
            return GlasshouseCli.EXIT_OK;
        }
    }
}
