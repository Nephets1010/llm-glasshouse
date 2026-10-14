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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code glasshouse prices validate} —— 校验外部价格表文件。
 *
 * <p><b>退出码</b>：0 全部有效；1 有条目被跳过（可用，但 CI 应知道）；2 文件读不了或结构坏。
 */
@Command(
        name = "validate",
        mixinStandardHelpOptions = true,
        sortOptions = false,
        description = "校验一个价格表文件（外部挂载的热更新源）。")
public final class PricesValidateCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(PricesValidateCommand.class);

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
            description = "价格表 YAML/JSON 文件路径。")
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
            System.err.println("[glasshouse] 错误: 必须用 --file 指定价格表文件。");
            return GlasshouseCli.EXIT_USAGE_ERROR;
        }
        var loader = new io.github.nephets.glasshouse.core.pricing.PricingTableLoader();
        try {
            var result = loader.loadFromFile(file);
            System.out.println("✅ 价格表校验通过: " + result.summary());
            System.out.println("   有效条目: " + result.entries().size());
            if (result.hasSkipped()) {
                System.out.println("   ⚠ 跳过条目 " + result.skipped().size() + " 条（这些条目不会生效）：");
                result.skipped().forEach(s -> System.out.println("     - " + s));
                System.out.println("   注意：单条坏数据不会导致整表失效（刻意设计），但这些条目对应的模型成本会记 0。");
                return GlasshouseCli.EXIT_GATE_FAILED;
            }
            return GlasshouseCli.EXIT_OK;
        } catch (RuntimeException e) {
            log.warn("[glasshouse] 价格表校验失败: {}", e.getMessage());
            return GlasshouseCli.EXIT_USAGE_ERROR;
        }
    }
}
