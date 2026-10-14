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

import picocli.CommandLine;
import picocli.CommandLine.Command;

import io.github.nephets.glasshouse.cli.version.BuildVersion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM 玻璃房命令行入口。
 *
 * <p>四个子命令各自回答一个运维问题：
 *
 * <table border="1">
 *   <caption>CLI 命令一览</caption>
 *   <tr><th>命令</th><th>回答的问题</th><th>典型场景</th></tr>
 *   <tr><td>{@code trace}</td><td>慢在哪、花了多少</td><td>线上排障</td></tr>
 *   <tr><td>{@code cost}</td><td>钱花在哪</td><td>成本归因下钻</td></tr>
 *   <tr><td>{@code eval}</td><td>这次改动是好是坏</td><td><b>CI 回归门禁</b></td></tr>
 *   <tr><td>{@code prices}</td><td>价格表对不对</td><td>调价后核对</td></tr>
 * </table>
 *
 * <p><b>退出码契约</b>（GitHub Action 依赖这个）：
 *
 * <ul>
 *   <li>{@code 0} —— 成功 / 门禁通过
 *   <li>{@code 1} —— 门禁未达标（流水线变红）
 *   <li>{@code 2} —— 参数或配置错误（这是使用者的命令写错了，不是评测结果）
 * </ul>
 */
@Command(
        name = "glasshouse",
        mixinStandardHelpOptions = true,
        versionProvider = GlasshouseCli.VersionProvider.class,
        sortOptions = false,
        description = "Java AI 可观测与评测：看得见（span 树）、说得清（五维成本归因）、能回归（CI 门禁）。",
        subcommands = {
            TraceCommand.class,
            CostCommand.class,
            EvalCommand.class,
            PricesCommand.class
        })
public final class GlasshouseCli implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(GlasshouseCli.class);

    /** 退出码：成功。 */
    public static final int EXIT_OK = 0;

    /** 退出码：门禁未达标。 */
    public static final int EXIT_GATE_FAILED = 1;

    /** 退出码：参数 / 配置错误。 */
    public static final int EXIT_USAGE_ERROR = 2;

    /** 全局选项：采样率（保留给后续的本地采样开关）。 */
    @CommandLine.Option(
            names = {"--sample-ratio"},
            defaultValue = "1.0",
            description = "采样率 0~1，1 表示全采。默认 1.0（CI 里必须全采，否则评测样本会被漏掉）。")
    double sampleRatio = 1.0d;

    /** 全局选项：内存缓冲容量。 */
    @CommandLine.Option(
            names = {"--buffer-capacity"},
            defaultValue = "100000",
            description = "内存 span 缓冲容量。默认 100000（10 万条）。")
    int bufferCapacity = 100_000;

    /** 全局选项：OTLP 端点。 */
    @CommandLine.Option(
            names = {"--otlp-endpoint"},
            description = "OTLP 导出端点，如 http://localhost:4318/v1/traces。默认不导出。")
    String otlpEndpoint;

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return EXIT_OK;
    }

    /**
     * 构造并执行 CLI。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        GlasshouseCli root = new GlasshouseCli();
        CommandLine commandLine =
                new CommandLine(root)
                        .setCaseInsensitiveEnumValuesAllowed(true)
                        .setExecutionExceptionHandler(
                                (ex, cmd, parseResult) -> {
                                    // 参数/配置错误统一映射为退出码 2，与「门禁未达标（1）」区分开，
                                    // 这样 CI 日志能直接告诉使用者该改命令还是该改阈值
                                    log.debug("[glasshouse] 命令执行异常: {}", ex.toString());
                                    cmd.getErr()
                                            .println(
                                                    "[glasshouse] 错误: "
                                                            + root.describeError(ex)
                                                            + System.lineSeparator());
                                    cmd.getErr().println("提示：退出码 2 表示命令或配置有误；退出码 1 表示评测跑完了但门禁不达标。");
                                    return EXIT_USAGE_ERROR;
                                });
        commandLine.setParameterExceptionHandler(
                (ex, args2) -> {
                    CommandLine cmd = ex.getCommandLine();
                    cmd.getErr().println("[glasshouse] 参数错误: " + ex.getMessage());
                    cmd.usage(cmd.getErr());
                    return EXIT_USAGE_ERROR;
                });
        System.exit(commandLine.execute(args));
    }

    /**
     * 生成用户可读的错误描述。
     *
     * @param ex 异常
     * @return 描述文本
     */
    String describeError(Throwable ex) {
        String message = ex.getMessage();
        String type = ex.getClass().getSimpleName();
        return message == null || message.isBlank() ? type : message;
    }

    /**
     * 构造运行时装配。
     *
     * @return 运行时装配；调用方负责关闭
     */
    CliRuntime newRuntime() {
        return new CliRuntime(sampleRatio, bufferCapacity, otlpEndpoint, BuildVersion.name());
    }

    /** picocli 版本提供者。 */
    static final class VersionProvider implements CommandLine.IVersionProvider {

        @Override
        public String[] getVersion() {
            return new String[] {BuildVersion.describe()};
        }
    }
}
