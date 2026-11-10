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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import picocli.CommandLine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * CLI 入口装配测试：参数解析、两个异常处理器、退出码契约。
 *
 * <p>现有 {@link GlasshouseCliTest} 用裸 {@code new CommandLine(...)} 执行，走不到 {@code main()}
 * 里装配的两个异常处理器。本类每次都经 {@link GlasshouseCli#newCommandLine(GlasshouseCli)} 执行，让用户真正敲命令的那条路径变成可断言的行为。
 *
 * <p>退出码与默认值一律写字面量，不引用 {@code EXIT_*} 常量或 {@code BuildVersion.describe()} 镜像；对帮助、报错这类长文本用
 * contains，行分隔跟随 {@link System#lineSeparator()}。
 */
class GlasshouseCliEntryTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String NL = System.lineSeparator();

    @TempDir Path tempDir;

    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void setUp() {
        originalOut = System.out;
        originalErr = System.err;
        installCapture();
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    /**
     * 换上全新的捕获流。
     *
     * <p>picocli 的 getOut()/getErr() 在首次访问时惰性取当时的 System.out/err 并缓存，所以每条命令执行前都要先装好流、再建
     * CommandLine；同一用例里跑第二条命令时也要重装一次。
     */
    private void installCapture() {
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    /**
     * 经入口工厂执行一条命令。
     *
     * @param args 命令行参数
     * @return 退出码
     */
    private int run(String... args) {
        return GlasshouseCli.newCommandLine(new GlasshouseCli()).execute(args);
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    // ---------------- A 组：工厂接线 ----------------

    @Test
    @DisplayName("工厂装好两个异常处理器与枚举大小写开关，与裸 CommandLine 不同")
    void factoryInstallsBothHandlers() {
        CommandLine bare = new CommandLine(new GlasshouseCli());
        CommandLine wired = GlasshouseCli.newCommandLine(new GlasshouseCli());

        // 比 Class 而不是实例：picocli 给每个 CommandLine 各建一个默认 handler 实例
        assertThat(wired.getParameterExceptionHandler().getClass())
                .as("工厂必须替换默认参数异常处理器，否则测试就是假绿")
                .isNotEqualTo(bare.getParameterExceptionHandler().getClass());
        assertThat(wired.getExecutionExceptionHandler().getClass())
                .as("工厂必须替换默认执行异常处理器，否则测试就是假绿")
                .isNotEqualTo(bare.getExecutionExceptionHandler().getClass());
        assertThat(wired.isCaseInsensitiveEnumValuesAllowed()).as("工厂必须打开枚举值大小写不敏感").isTrue();
    }

    // ---------------- B 组：参数解析 ----------------

    @Test
    @DisplayName("三个全局选项默认值是字面量 1.0 / 100000 / null")
    void globalOptionDefaultsAreLiteral() {
        GlasshouseCli root = new GlasshouseCli();
        new CommandLine(root).parseArgs();

        assertThat(root.sampleRatio).isEqualTo(1.0d);
        assertThat(root.bufferCapacity).isEqualTo(100000);
        assertThat(root.otlpEndpoint).isNull();
    }

    @Test
    @DisplayName("根级合法值解析进三个全局选项字段")
    void globalOptionsParseIntoFields() {
        GlasshouseCli root = new GlasshouseCli();
        new CommandLine(root)
                .parseArgs(
                        "--sample-ratio", "0.25",
                        "--buffer-capacity", "13",
                        "--otlp-endpoint", "http://x:4318");

        assertThat(root.sampleRatio).isEqualTo(0.25d);
        assertThat(root.bufferCapacity).isEqualTo(13);
        assertThat(root.otlpEndpoint).isEqualTo("http://x:4318");
    }

    @Test
    @DisplayName("newRuntime 把解析后的 bufferCapacity 传给 SpanRingBuffer")
    void newRuntimePassesParsedCapacity() {
        GlasshouseCli root = new GlasshouseCli();
        new CommandLine(root).parseArgs("--buffer-capacity", "7");

        try (CliRuntime runtime = root.newRuntime()) {
            assertThat(runtime.ringBuffer().capacity()).isEqualTo(7);
        }
    }

    @Test
    @DisplayName("非法数值与 int 溢出在解析期被拒绝")
    void invalidNumericOptionsAreRejected() {
        CommandLine.ParameterException capacity =
                assertThrows(
                        CommandLine.ParameterException.class,
                        () ->
                                new CommandLine(new GlasshouseCli())
                                        .parseArgs("--buffer-capacity", "abc"));
        assertThat(capacity.getMessage()).contains("abc").contains("not an int");

        CommandLine.ParameterException ratio =
                assertThrows(
                        CommandLine.ParameterException.class,
                        () ->
                                new CommandLine(new GlasshouseCli())
                                        .parseArgs("--sample-ratio", "abc"));
        assertThat(ratio.getMessage()).contains("abc").contains("not a double");

        CommandLine.ParameterException overflow =
                assertThrows(
                        CommandLine.ParameterException.class,
                        () ->
                                new CommandLine(new GlasshouseCli())
                                        .parseArgs("--buffer-capacity", "2147483648"));
        assertThat(overflow.getMessage()).contains("is not an int");
    }

    @Test
    @DisplayName("全局选项缺值在解析期被拒绝")
    void missingOptionValuesAreRejected() {
        CommandLine.ParameterException ratio =
                assertThrows(
                        CommandLine.ParameterException.class,
                        () -> new CommandLine(new GlasshouseCli()).parseArgs("--sample-ratio"));
        assertThat(ratio.getMessage()).contains("Missing required parameter");

        CommandLine.ParameterException endpoint =
                assertThrows(
                        CommandLine.ParameterException.class,
                        () -> new CommandLine(new GlasshouseCli()).parseArgs("--otlp-endpoint"));
        assertThat(endpoint.getMessage()).contains("Missing required parameter");
    }

    @Test
    @DisplayName("根级全局选项能跑通整个子命令")
    void rootLevelGlobalOptionsReachCommand() {
        int code = run("--buffer-capacity", "7", "prices", "show");

        assertThat(code).isEqualTo(0);
        assertThat(stdout()).contains("条目总数");
        assertThat(stderr()).doesNotContain("[glasshouse] 参数错误");
    }

    // ---------------- C 组：退出码与两个 handler ----------------

    @Test
    @DisplayName("参数错误：exit 2 + 前缀 + 根 usage，stdout 为空")
    void parameterErrorReturnsTwoWithPrefixAndUsage() {
        int code = run("--buffer-capacity", "abc");

        assertThat(code).isEqualTo(2);
        assertThat(stderr())
                .contains("[glasshouse] 参数错误: ")
                .contains("Invalid value for option '--buffer-capacity'")
                .contains("Usage: glasshouse");
        assertThat(stdout()).isEmpty();
    }

    @Test
    @DisplayName("未知子命令：exit 2 + 参数错误前缀 + 根 usage")
    void unknownSubcommandReturnsTwoWithUsage() {
        int code = run("not-a-command");

        assertThat(code).isEqualTo(2);
        assertThat(stderr())
                .contains("[glasshouse] 参数错误:")
                .contains("Unmatched argument")
                .contains("Usage: glasshouse");
    }

    @Test
    @DisplayName("子命令的未知选项用子命令自身的 usage")
    void unknownOptionAtSubcommandUsesSubcommandUsage() {
        int code = run("trace", "--bogus");

        assertThat(code).isEqualTo(2);
        assertThat(stderr())
                .contains("[glasshouse] 参数错误: Unknown option: '--bogus'")
                .contains("Usage: glasshouse trace");
    }

    @Test
    @DisplayName("子命令缺选项值：parse 错误 + 参数错误前缀")
    void missingValueAtSubcommandIsParseError() {
        int code = run("eval", "run", "--dataset");

        assertThat(code).isEqualTo(2);
        assertThat(stderr())
                .contains("Missing required parameter for option '--dataset'")
                .contains("[glasshouse] 参数错误: ");
    }

    @Test
    @DisplayName("eval run 完全没给 --dataset 是运行时错误，不是 parse 错误")
    void missingDatasetIsRuntimeErrorNotParseError() {
        int code = run("eval", "run");

        assertThat(code).isEqualTo(2);
        assertThat(stderr())
                .contains("必须用 --dataset 指定评测集")
                .doesNotContain("[glasshouse] 参数错误: ")
                .doesNotContain("Usage:");
    }

    @Test
    @DisplayName("★ 执行异常经入口 handler 映射为 exit 2（不是门禁的 1），无 Java 堆栈")
    void executionExceptionMapsToTwoNotOne() {
        Path missing = tempDir.resolve("no-such-spans.jsonl");
        int code = run("trace", "--trace-id", TRACE_ID, "--input", missing.toString());

        assertThat(code).isEqualTo(2);
        assertThat(stderr())
                .contains("[glasshouse] 错误: ")
                .contains("span 文件不存在")
                .contains(missing.toString())
                .contains("提示：退出码 2 表示命令或配置有误；退出码 1 表示评测跑完了但门禁不达标。")
                .doesNotContain("at io.github.nephets");
        assertThat(stdout()).isEmpty();
    }

    @Test
    @DisplayName("成功路径不触发两个异常处理器")
    void successDoesNotInvokeHandlers() {
        int code = run("--buffer-capacity", "7", "prices", "show");

        assertThat(code).isEqualTo(0);
        assertThat(stderr())
                .doesNotContain("[glasshouse] 错误:")
                .doesNotContain("[glasshouse] 参数错误: ");
        assertThat(stdout()).contains("条目总数");
    }

    @Test
    @DisplayName("门禁未达标的 exit 1 不被执行异常处理器吞掉")
    void gateFailureExitOneSurvivesEntryWiring() throws IOException {
        Path partial = tempDir.resolve("partial.yml");
        String yml =
                String.join(
                        NL,
                        "currency: CNY",
                        "entries:",
                        "  - providerId: good",
                        "    modelId: m1",
                        "    inputPricePerUnit: 0.001",
                        "    outputPricePerUnit: 0.002",
                        "    effectiveFrom: 2026-01-01",
                        "    source: OFFICIAL",
                        "  - providerId: no-price",
                        "    modelId: m2",
                        "    outputPricePerUnit: 0.002",
                        "    effectiveFrom: 2026-01-01",
                        "    source: OFFICIAL");
        Files.writeString(partial, yml + NL, StandardCharsets.UTF_8);

        int code = run("prices", "validate", "--file", partial.toString());

        assertThat(code).isEqualTo(1);
        assertThat(stdout()).contains("跳过条目");
        assertThat(stderr()).doesNotContain("[glasshouse] 错误:");
    }

    // ---------------- D 组：help / version ----------------

    @Test
    @DisplayName("根 --help / -h：exit 0，帮助落 stdout，stderr 为空")
    void rootHelpOptionsExitZeroOnStdout() {
        assertThat(run("--help")).isEqualTo(0);
        assertThat(stdout())
                .contains("Usage: glasshouse")
                .contains("trace")
                .contains("cost")
                .contains("eval")
                .contains("prices");
        assertThat(stderr()).isEmpty();

        installCapture();
        assertThat(run("-h")).isEqualTo(0);
        assertThat(stdout())
                .contains("Usage: glasshouse")
                .contains("trace")
                .contains("cost")
                .contains("eval")
                .contains("prices");
        assertThat(stderr()).isEmpty();
    }

    @Test
    @DisplayName("-V 与 --version 输出一致，且与 VersionProvider 返回一致")
    void shortAndLongVersionAgree() {
        assertThat(run("--version")).isEqualTo(0);
        String longVersion = stdout();
        assertThat(longVersion).contains("llm-glasshouse").doesNotContain("null").isNotBlank();
        assertThat(stderr()).isEmpty();

        installCapture();
        assertThat(run("-V")).isEqualTo(0);
        assertThat(stdout()).isEqualTo(longVersion);
        assertThat(stderr()).isEmpty();

        String[] version = new GlasshouseCli.VersionProvider().getVersion();
        assertThat(version).hasSize(1);
        assertThat(version[0])
                .isEqualTo(longVersion.replace(NL, "").strip())
                .contains("llm-glasshouse");
    }

    @Test
    @DisplayName("子命令 --help：exit 0，各子命令 usage 落 stdout，stderr 为空")
    void subcommandHelpExitZeroOnStdout() {
        assertThat(run("trace", "--help")).isEqualTo(0);
        assertThat(stdout()).contains("Usage: glasshouse trace");
        assertThat(stderr()).isEmpty();

        installCapture();
        assertThat(run("eval", "run", "--help")).isEqualTo(0);
        assertThat(stdout()).contains("Usage: glasshouse eval run");
        assertThat(stderr()).isEmpty();

        installCapture();
        assertThat(run("prices", "--help")).isEqualTo(0);
        assertThat(stdout()).contains("Usage: glasshouse prices");
        assertThat(stderr()).isEmpty();
    }

    // ---------------- E 组：describeError / handler 分支 ----------------

    @Test
    @DisplayName("describeError 有消息时返回消息本身")
    void describeErrorUsesMessageWhenPresent() {
        GlasshouseCli root = new GlasshouseCli();
        assertThat(root.describeError(new IllegalStateException("有消息"))).isEqualTo("有消息");
    }

    @Test
    @DisplayName("describeError 对 null / 全空白消息回退到异常类型名")
    void describeErrorFallsBackToTypeForNullAndBlank() {
        GlasshouseCli root = new GlasshouseCli();
        assertThat(root.describeError(new RuntimeException((String) null)))
                .isEqualTo("RuntimeException");
        assertThat(root.describeError(new IllegalStateException("  ")))
                .isEqualTo("IllegalStateException");
    }

    @Test
    @DisplayName("执行异常处理器对 null 消息走 describeError 回退，返回 2")
    void executionHandlerUsesDescribeErrorFallback() throws Exception {
        CommandLine cl = GlasshouseCli.newCommandLine(new GlasshouseCli());
        int code =
                cl.getExecutionExceptionHandler()
                        .handleExecutionException(new RuntimeException((String) null), cl, null);

        assertThat(code).isEqualTo(2);
        assertThat(stderr()).contains("[glasshouse] 错误: RuntimeException").contains("提示：退出码 2");
    }
}
