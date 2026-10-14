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

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import io.github.nephets.glasshouse.eval.dataset.DatasetRegistry;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * {@code eval run} 与 {@code eval compare} 共用的参数与 Prompt 渲染。
 *
 * <p>抽成独立类而不是放在 {@link EvalRunCommand} 里，是因为 {@link EvalCompareCommand} 需要完全相同的一套参数——两份拷贝迟早会漂移。
 */
final class EvalCommonOptions {

    /** 评测集名称。 */
    @picocli.CommandLine.Option(
            names = {"-d", "--dataset"},
            paramLabel = "<name>",
            description = "评测集名称（eval-datasets 下的文件名，不带 .jsonl）。")
    String dataset;

    /** 被测 Prompt 版本。 */
    @picocli.CommandLine.Option(
            names = {"-p", "--prompt-version"},
            paramLabel = "<version>",
            defaultValue = "current",
            description = "被测 Prompt 版本标识，会写进报告。默认 current。")
    String promptVersion;

    /** 被测模型。 */
    @picocli.CommandLine.Option(
            names = {"-m", "--model"},
            paramLabel = "<provider/model>",
            defaultValue = "mock/mock-chat",
            description = "被测模型，形如 provider/model。默认 mock/mock-chat（零费用）。")
    String model;

    /** 录制回放文件。 */
    @picocli.CommandLine.Option(
            names = {"-r", "--replay"},
            paramLabel = "<file>",
            description = "录制回放文件（JSONL）。不指定时自动找 eval-datasets/{dataset}.responses.jsonl。")
    Path replayFile;

    /** 真实端点。 */
    @picocli.CommandLine.Option(
            names = {"--base-url"},
            paramLabel = "<url>",
            description = "OpenAI 兼容端点。指定后走真实调用（需要环境变量 GLASSHOUSE_API_KEY），" + "不指定则默认零成本回放模式。")
    String baseUrl;

    /** 报告输出目录。 */
    @picocli.CommandLine.Option(
            names = {"-o", "--out"},
            paramLabel = "<dir>",
            defaultValue = "report",
            description = "报告输出目录。默认 report。")
    Path outDir;

    /** 允许的最大失败用例数。 */
    @picocli.CommandLine.Option(
            names = {"--max-case-failures"},
            defaultValue = "0",
            description = "允许的最大用例失败数，超过则门禁不通过。默认 0（任何一条失败都不放过）。")
    int maxCaseFailures;

    /** 成本指标预算。 */
    @picocli.CommandLine.Option(
            names = {"--cost-budget"},
            defaultValue = "1.0",
            description = "成本指标的单次预算（CNY）。默认 1.0。")
    BigDecimal costBudget;

    /**
     * 构造评测配置。
     *
     * @param gate 门禁表达式，可为 null
     * @return 评测配置
     */
    io.github.nephets.glasshouse.core.eval.EvalConfig toConfig(String gate) {
        return new io.github.nephets.glasshouse.core.eval.EvalConfig(
                model,
                promptVersion,
                0.0d,
                1024,
                costBudget,
                maxCaseFailures,
                1,
                30_000L,
                gate,
                null,
                true);
    }

    /**
     * 构造 Prompt 渲染器。
     *
     * <p>渲染出的 Prompt 第一条是 system 消息，第二条 user 消息里带 {@code [case:<id>]} 标记， 供 {@link ReplayChatModel}
     * 定位录制条目。
     *
     * @return 渲染器
     */
    java.util.function.Function<io.github.nephets.glasshouse.core.eval.EvalCase, Prompt>
            renderer() {
        return evalCase -> {
            String system =
                    """
          你是电商客服的 AI 助手。请用简体中文回答用户问题。

          要求：
          1. 先给结论，再给必要的依据；
          2. 不要输出与问题无关的内容；
          3. 涉及金额、期限、资格条件时必须写明具体数字或条件；
          4. 不确定的部分明确说明，不要编造。
          """;
            String user =
                    "["
                            + ReplayChatModel.CASE_MARKER_PREFIX
                            + evalCase.id()
                            + ReplayChatModel.CASE_MARKER_SUFFIX
                            + "]\n"
                            + evalCase.inputText("question");
            return new Prompt(
                    List.<Message>of(
                            new org.springframework.ai.chat.messages.SystemMessage(system),
                            new org.springframework.ai.chat.messages.UserMessage(user)),
                    ChatOptions.builder().model(model).temperature(0.0d).build());
        };
    }

    /**
     * 解析回放文件路径。
     *
     * @return 回放文件路径；未显式指定且默认路径不存在时返回 null
     */
    Path resolveReplayFile() {
        if (replayFile != null) {
            return replayFile;
        }
        Path candidate = Path.of("eval-datasets", dataset + ".responses.jsonl");
        return java.nio.file.Files.isRegularFile(candidate) ? candidate : null;
    }

    /**
     * 把 classpath 里的回放资源抽成临时文件。
     *
     * <p><b>为什么需要这一步</b>：回放文件与评测集一起打包在 jar 里， 但 {@link ReplayChatModel} 需要一个真实的文件路径。把资源流复制到临时文件后
     * 再交给它，CLI 就能在「只拿到 jar」的场景下直接跑门禁 —— 而这正是 GitHub Action 的用法。
     *
     * @return 临时文件路径；classpath 上没有该资源时返回 null
     * @throws java.io.IOException 临时文件写入失败
     */
    java.nio.file.Path extractReplayFromClasspath() throws java.io.IOException {
        String resource = DatasetRegistry.DEFAULT_CLASSPATH_DIR + dataset + ".responses.jsonl";
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = EvalCommonOptions.class.getClassLoader();
        }
        try (java.io.InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            java.nio.file.Path temp =
                    java.nio.file.Files.createTempFile("glasshouse-replay-", ".jsonl");
            temp.toFile().deleteOnExit();
            java.nio.file.Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return temp;
        }
    }

    /**
     * 构造评测用的模型。
     *
     * @param root 根命令（用于打印错误）
     * @return ChatModel
     * @throws java.io.IOException 回放文件读取失败
     */
    org.springframework.ai.chat.model.ChatModel buildModel(GlasshouseCli root)
            throws java.io.IOException {
        if (baseUrl != null && !baseUrl.isBlank()) {
            System.out.println("[glasshouse] 使用真实端点: " + baseUrl);
            return RealEndpointChatModelFactory.create(baseUrl, model);
        }
        Path replay = resolveReplayFile();
        if (replay != null && !java.nio.file.Files.isReadable(replay)) {
            System.err.println("[glasshouse] 错误: 回放文件不存在或不可读: " + replay.toAbsolutePath());
            throw new IllegalStateException("回放文件不可读: " + replay);
        }
        if (replay == null) {
            // jar 里带了内置回放（评测集与回放一起打进 jar），
            // 没有 --replay 时直接从 classpath 抽出来，CLI 才能「只拿一个 jar 就跑门禁」
            try {
                replay = extractReplayFromClasspath();
            } catch (java.io.IOException e) {
                throw new IllegalStateException("抽取 classpath 回放文件失败", e);
            }
        }
        if (replay == null) {
            System.err.println(
                    "[glasshouse] 错误: 未找到录制文件 eval-datasets/"
                            + dataset
                            + ".responses.jsonl。\n"
                            + "          请二选一：\n"
                            + "            1) 用 --replay 指定录制文件\n"
                            + "            2) 用 --base-url 指定 OpenAI 兼容端点（会调真实模型并产生费用）\n"
                            + "            3) 录制一份：glasshouse eval run --dataset "
                            + dataset
                            + " --base-url <url>");
            throw new IllegalStateException("没有可用的模型来源");
        }
        ReplayChatModel model = ReplayChatModel.fromFile(replay);
        System.out.println(
                "[glasshouse] 录制回放模式: "
                        + replay.toAbsolutePath()
                        + "（"
                        + model.size()
                        + " 条响应，零调用零费用）");
        return model;
    }
}
