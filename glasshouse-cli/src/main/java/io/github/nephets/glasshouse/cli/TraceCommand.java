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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import io.github.nephets.glasshouse.starter.store.SpanRingBuffer;
import io.github.nephets.glasshouse.starter.store.SpanTreeQuery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * {@code glasshouse trace} —— 把一条链路渲染成树，同时回答「慢在哪」与「花了多少」。
 *
 * <p><b>数据来源有两种</b>，按优先级：
 *
 * <ol>
 *   <li>{@code --input <file>}：读 JSONL span 文件（由应用侧 {@code JsonlSpanStore} 或 CI 归档产出）
 *   <li>内存缓冲：CLI 进程内的 span 缓冲（用于本地调试）
 * </ol>
 *
 * <p><b>输出必须同时包含 {@code slowestSpan=} 与 {@code totalCostCny=}</b>—— 这两个标记是架构 ③T05 的机器可判定验收点，{@code
 * SpanTreeQueryIT} 会对它们做断言。
 */
@Command(
        name = "trace",
        mixinStandardHelpOptions = true,
        sortOptions = false,
        description = "查看一条 trace 的 span 树。输出同时给出「慢在哪」（slowestSpan=）与「花了多少」（totalCostCny=）。")
public final class TraceCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(TraceCommand.class);

    // 直接子命令：@ParentCommand 拿到的就是 GlasshouseCli。
    // 字段必须包级可见 —— private 会让 picocli 反射赋值失败，整棵子命令都跑不起来。
    @ParentCommand GlasshouseCli root;

    /** traceId；与 {@code --last} 二选一。 */
    @Option(
            names = {"-t", "--trace-id"},
            paramLabel = "<traceId>",
            description = "要查看的 traceId（32 位 hex）。")
    String traceId;

    /** 取最近一条 trace。 */
    @Option(
            names = {"-l", "--last"},
            description = "查看最近一条 trace（缓冲中最后一个 traceId）。")
    boolean last;

    /** 附带成本列。 */
    @Option(names = "--cost", description = "在每行显示成本。")
    boolean cost;

    /** 输出 JSON。 */
    @Option(names = "--json", description = "以 JSON 输出（供程序消费）。")
    boolean json;

    /** 从 JSONL 文件读取 span。 */
    @Option(
            names = {"-i", "--input"},
            paramLabel = "<file>",
            description = "从 JSONL span 文件读取（每行一个 span 的 JSON）。")
    Path input;

    @Override
    public Integer call() throws Exception {
        String effectiveTraceId = resolveTraceId();
        // ★ runtime 必须显式关闭：它持有 SdkTracerProvider（内含 BatchSpanProcessor 的导出线程）。
        //   原实现在 resolveQuery 里 new 出来后从不关闭，CLI 是短命进程看不出问题，
        //   但同一套代码被长跑进程复用时就会每次调用泄漏一批线程。
        try (CliRuntime runtime = root.newRuntime()) {
            SpanTreeQuery query = resolveQuery(runtime);
            if (query == null) {
                log.warn("[glasshouse] trace 命令没有可查询的 span 数据: input={}", input);
                System.err.println(
                        "[glasshouse] 错误: 没有可查询的 span 数据。"
                                + (input == null
                                        ? "请用 --input 指定 span 文件，或在运行中的应用进程内查询 /_glasshouse/trace/{traceId}。"
                                        : "请检查文件是否可读: " + input));
                return GlasshouseCli.EXIT_USAGE_ERROR;
            }
            List<SpanRingBuffer.SpanData> spans = query.spansOf(effectiveTraceId);
            if (spans.isEmpty()) {
                log.warn("[glasshouse] 未找到 traceId={} 的 span", effectiveTraceId);
                System.err.println("[glasshouse] 错误: 未找到 traceId=" + effectiveTraceId + " 的 span。");
                if (input == null) {
                    // ★ 没给 --input 时必须直说「去哪儿拿 span」——
                    //   只说「没找到」会让使用者以为埋点没生效，实际只是查错了地方。
                    System.err.println();
                    System.err.println("当前 CLI 进程内没有 span 数据。三种取数方式：");
                    System.err.println("  1) 从 JSONL 文件查询：--input spans.jsonl");
                    System.err.println("  2) 在运行中的应用里查：GET /_glasshouse/trace/" + effectiveTraceId);
                    System.err.println(
                            "  3) 直接 curl：curl localhost:8080/_glasshouse/trace/"
                                    + effectiveTraceId
                                    + "?cost=true");
                    return GlasshouseCli.EXIT_USAGE_ERROR;
                }
                System.err.println("可用的 traceId：");
                for (String id : availableTraceIds().stream().limit(10).toList()) {
                    System.err.println("  - " + id);
                }
                return GlasshouseCli.EXIT_USAGE_ERROR;
            }
            log.debug(
                    "[glasshouse] trace 查询命中 {} 个 span: traceId={}",
                    spans.size(),
                    effectiveTraceId);
            if (json) {
                System.out.println(renderJson(effectiveTraceId, query, spans));
            } else {
                System.out.println(query.render(effectiveTraceId, cost));
            }
            return GlasshouseCli.EXIT_OK;
        }
    }

    /**
     * 解析要查询的 traceId。
     *
     * @return traceId；无法确定返回 null
     */
    private String resolveTraceId() {
        if (traceId != null && !traceId.isBlank()) {
            return traceId.trim();
        }
        if (last) {
            List<String> ids = new ArrayList<>(availableTraceIds());
            if (!ids.isEmpty()) {
                return ids.get(ids.size() - 1);
            }
        }
        return null;
    }

    /**
     * 列出可用的 traceId。
     *
     * @return traceId 列表
     */
    private List<String> availableTraceIds() {
        try (CliRuntime runtime = root.newRuntime()) {
            if (input != null) {
                loadFromFile(runtime.ringBuffer());
            }
            return runtime.ringBuffer().byTrace().keySet().stream().sorted().toList();
        }
    }

    /**
     * 构造查询器并加载数据。
     *
     * <p>运行时由 {@link #call()} 持有并以 try-with-resources 关闭，本方法不再自己创建它 —— 否则 {@code CliRuntime} 里的
     * {@code SdkTracerProvider} 会随每次调用泄漏。
     *
     * @param runtime 运行时装配，不可为 null
     * @return 查询器
     */
    private SpanTreeQuery resolveQuery(CliRuntime runtime) throws java.io.IOException {
        if (input != null) {
            loadInto(runtime.ringBuffer());
        }
        return runtime.spanTreeQuery();
    }

    /**
     * 从 JSONL 文件加载 span 到缓冲。
     *
     * @param buffer 目标缓冲
     * @return 加载的条数
     * @throws java.io.IOException 文件读取失败
     */
    private int loadInto(SpanRingBuffer buffer) throws java.io.IOException {
        if (input == null) {
            return 0;
        }
        if (!Files.isRegularFile(input)) {
            throw new java.io.FileNotFoundException("span 文件不存在: " + input.toAbsolutePath());
        }
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        int count = 0;
        int skipped = 0;
        for (String line : Files.readAllLines(input, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                Map<String, Object> map = mapper.readValue(trimmed, Map.class);
                buffer.append(SpanJsonCodec.toSpanData(map));
                count++;
            } catch (JsonProcessingException | IllegalArgumentException e) {
                // 与 cost 命令同规则：坏行跳过但必须留痕，否则「树里少几个 span」无人察觉
                skipped++;
                log.warn("[glasshouse] 跳过无法解析的行: {}", e.getMessage());
            }
        }
        if (skipped > 0) {
            log.warn("[glasshouse] {} 文件共跳过 {} 行坏数据，成功加载 {} 行", input, skipped, count);
        }
        return count;
    }

    private void loadFromFile(SpanRingBuffer buffer) {
        try {
            loadInto(buffer);
        } catch (java.io.IOException e) {
            log.warn("[glasshouse] 读取 span 文件失败: {}", e.getMessage());
            System.err.println("[glasshouse] WARN " + e.getMessage());
        }
    }

    /**
     * 渲染 JSON 输出。
     *
     * @param effectiveTraceId traceId
     * @param query 查询器
     * @param spans span 列表
     * @return JSON 文本
     */
    private String renderJson(
            String effectiveTraceId, SpanTreeQuery query, List<SpanRingBuffer.SpanData> spans) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"traceId\": \"").append(effectiveTraceId).append("\",\n");
        sb.append("  \"spanCount\": ").append(spans.size()).append(",\n");
        sb.append("  \"totalCostCny\": ")
                .append(query.totalCost(effectiveTraceId).toPlainString())
                .append(",\n");
        query.slowestSpan(effectiveTraceId)
                .ifPresentOrElse(
                        slowest ->
                                sb.append("  \"slowestSpan\": {\"name\": \"")
                                        .append(slowest.name())
                                        .append("\", \"durationMs\": ")
                                        .append(slowest.durationMillis())
                                        .append("},\n"),
                        () -> sb.append("  \"slowestSpan\": null,\n"));
        var tokens = query.tokenSummary(effectiveTraceId);
        sb.append("  \"tokens\": {\"input\": ")
                .append(tokens.inputTokens())
                .append(", \"output\": ")
                .append(tokens.outputTokens())
                .append(", \"modelCalls\": ")
                .append(tokens.modelCalls())
                .append("},\n");
        sb.append("  \"tree\": \"")
                .append(escape(query.render(effectiveTraceId, cost)))
                .append("\",\n");
        sb.append("  \"spans\": [\n");
        for (int i = 0; i < spans.size(); i++) {
            sb.append("    ").append(SpanJsonCodec.toJson(spans.get(i)));
            if (i < spans.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append("  ]\n}");
        return sb.toString();
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
