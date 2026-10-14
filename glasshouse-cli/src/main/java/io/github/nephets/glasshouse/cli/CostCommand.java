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
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import io.github.nephets.glasshouse.core.attribution.AttributionDimension;
import io.github.nephets.glasshouse.core.attribution.AttributionReport;
import io.github.nephets.glasshouse.core.attribution.AttributionRow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * {@code glasshouse cost} —— 按维度下钻成本，回答「钱花在哪」。
 *
 * <p><b>支持六种下钻维度</b>（架构 PRD 仓库③ P0-3）：供应商、模型、租户、业务功能、 Prompt 版本、Agent
 * 步骤。每一维都可单独查询，这就是「任一维度均可下钻」的可执行形态。
 *
 * <p>数据来自 JSONL span 文件（{@code --input}）或运行中应用暴露的 {@code /_glasshouse/cost?dimension=} 端点。这里实现前者，让
 * CI 与本地脚本都能用。
 */
@Command(
        name = "cost",
        mixinStandardHelpOptions = true,
        sortOptions = false,
        description = "按维度下钻成本。维度：provider / model / tenant / feature / promptVersion / agentStep。")
public final class CostCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(CostCommand.class);

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    // 直接子命令：@ParentCommand 拿到的就是 GlasshouseCli。
    // 字段必须包级可见 —— private 会让 picocli 反射赋值失败，整棵子命令都跑不起来。
    @ParentCommand GlasshouseCli root;

    /** 归因维度。 */
    @Option(
            names = {"-b", "--by"},
            paramLabel = "<dimension>",
            defaultValue = "tenant",
            description =
                    "归因维度：provider / model / tenant / feature / promptVersion / agentStep。默认 tenant。")
    String by;

    /** 输入文件。 */
    @Option(
            names = {"-i", "--input"},
            paramLabel = "<file>",
            description = "从 JSONL span 文件聚合（每行一个 span 的 JSON）。")
    Path input;

    /** 输出格式。 */
    @Option(
            names = {"-f", "--format"},
            paramLabel = "<format>",
            defaultValue = "table",
            description = "输出格式：table / json / csv。默认 table。")
    String format;

    /** 限制行数。 */
    @Option(
            names = {"-n", "--limit"},
            defaultValue = "50",
            description = "最多返回多少行。默认 50。")
    int limit;

    @Override
    public Integer call() throws Exception {
        AttributionDimension dimension = AttributionDimension.from(by);
        if (dimension == null) {
            log.warn("[glasshouse] cost 命令收到未知维度: {}", by);
            System.err.println(
                    "[glasshouse] 错误: 未知维度 \""
                            + by
                            + "\"。可用维度："
                            + AttributionDimension.all().stream()
                                    .map(AttributionDimension::columnName)
                                    .reduce((a, b) -> a + " / " + b)
                                    .orElse(""));
            return GlasshouseCli.EXIT_USAGE_ERROR;
        }
        if (input == null) {
            log.warn("[glasshouse] cost 命令缺少 --input");
            System.err.println(
                    "[glasshouse] 错误: 请用 --input 指定 span 文件（JSONL，每行一个 span）。"
                            + "或直接访问运行中应用的 /_glasshouse/cost?dimension="
                            + dimension.columnName());
            return GlasshouseCli.EXIT_USAGE_ERROR;
        }
        if (!Files.isRegularFile(input)) {
            log.warn("[glasshouse] cost 输入文件不存在: {}", input.toAbsolutePath());
            System.err.println("[glasshouse] 错误: 文件不存在: " + input.toAbsolutePath());
            return GlasshouseCli.EXIT_USAGE_ERROR;
        }

        try (CliRuntime runtime = root.newRuntime()) {
            int loaded = load(runtime);
            if (loaded == 0) {
                log.warn("[glasshouse] cost 输入文件中没有可解析的 span: {}", input.toAbsolutePath());
                System.err.println("[glasshouse] 错误: 文件中没有可解析的 span: " + input.toAbsolutePath());
                return GlasshouseCli.EXIT_USAGE_ERROR;
            }
            AttributionReport report =
                    runtime.attributionEngine()
                            .report(
                                    io.github.nephets.glasshouse.core.attribution.AttributionQuery
                                            .of(dimension)
                                            .withLimit(limit));
            switch (format.toLowerCase(java.util.Locale.ROOT)) {
                case "json" -> System.out.println(renderJson(report));
                case "csv" -> System.out.print(renderCsv(report));
                case "table" -> System.out.print(renderTable(report));
                default -> {
                    log.warn("[glasshouse] cost 命令收到未知输出格式: {}", format);
                    System.err.println(
                            "[glasshouse] 错误: 未知格式 \"" + format + "\"，可用：table / json / csv");
                    return GlasshouseCli.EXIT_USAGE_ERROR;
                }
            }
            log.debug(
                    "[glasshouse] cost 查询完成: dimension={}, rows={}, totalCostCny={}",
                    dimension.columnName(),
                    report.rows().size(),
                    report.totalCost());
            return GlasshouseCli.EXIT_OK;
        }
    }

    /**
     * 把 JSONL span 文件读入缓冲并归因。
     *
     * @param runtime 运行时装配
     * @return 加载条数
     * @throws java.io.IOException 读取失败
     */
    private int load(CliRuntime runtime) throws java.io.IOException {
        int count = 0;
        int skipped = 0;
        for (String line : Files.readAllLines(input, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> map = MAPPER.readValue(trimmed, java.util.Map.class);
                io.github.nephets.glasshouse.starter.store.SpanRingBuffer.SpanData span =
                        SpanJsonCodec.toSpanData(map);
                runtime.ringBuffer().append(span);
                recordForAttribution(runtime, span);
                count++;
            } catch (JsonProcessingException | IllegalArgumentException e) {
                // ★ 单行坏数据跳过但要留痕：整份文件静默少算几行，
                //   使用者会以为「成本就是这么低」，这是最危险的一类静默失败。
                //   收窄到「解析失败 / span 字段不合法」两类，其余异常照常冒出去。
                skipped++;
                log.warn("[glasshouse] 跳过无法解析的行: {}", e.getMessage());
            }
        }
        if (skipped > 0) {
            log.warn("[glasshouse] {} 文件共跳过 {} 行坏数据，成功加载 {} 行", input, skipped, count);
        }
        return count;
    }

    /**
     * 把一条 span 还原成 {@link io.github.nephets.glasshouse.core.audit.AuditEvent} 并交给归因引擎。
     *
     * <p><b>为什么必须走归因引擎而不是直接读 span 属性</b>：归因报表要按维度聚合、 要算占比、要看数据质量（估算占比）。这些都住在 {@code
     * AttributionEngine} 里， 在 CLI 侧重新实现一遍必然与在线链路算出不同的数。
     *
     * <p>供应商与模型从 span 的 {@code gen_ai.*} 属性还原；缺失时归到 {@code unknown}，
     * 而不是丢弃这条记录——离线对账时「模型名缺失」本身就是要暴露的问题。
     *
     * @param runtime 运行时装配
     * @param span span 快照
     */
    private void recordForAttribution(
            CliRuntime runtime,
            io.github.nephets.glasshouse.starter.store.SpanRingBuffer.SpanData span) {
        io.github.nephets.glasshouse.core.attribution.AttributionEngine engine =
                runtime.attributionEngine();
        if (engine == null) {
            return;
        }
        Map<String, Object> attributes = span.attributes();
        String providerId = stringAttr(attributes, "gen_ai.provider.name");
        String modelId = stringAttr(attributes, "gen_ai.request.model");
        long inputTokens = longAttr(attributes, "gen_ai.usage.input_tokens");
        long outputTokens = longAttr(attributes, "gen_ai.usage.output_tokens");
        long reasoningTokens = longAttr(attributes, "swy.usage.reasoning_tokens");
        long cachedInputTokens = longAttr(attributes, "swy.usage.cached_input_tokens");
        boolean estimated = "ESTIMATED".equals(stringAttr(attributes, "swy.usage.source"));

        io.github.nephets.glasshouse.core.usage.Usage usage =
                estimated
                        ? io.github.nephets.glasshouse.core.usage.Usage.estimated(
                                inputTokens, outputTokens, reasoningTokens, cachedInputTokens)
                        : io.github.nephets.glasshouse.core.usage.Usage.of(
                                inputTokens, outputTokens, reasoningTokens, cachedInputTokens);

        java.math.BigDecimal cost = decimalAttr(attributes, "swy.cost.amount");
        io.github.nephets.glasshouse.core.audit.AuditEvent event =
                new io.github.nephets.glasshouse.core.audit.AuditEvent(
                        io.github.nephets.glasshouse.core.audit.EventType.MODEL_CALL,
                        span.traceId(),
                        span.spanId(),
                        span.startTime() == null ? Instant.now() : span.startTime(),
                        stringAttr(attributes, "swy.tenant.id"),
                        stringAttr(attributes, "swy.biz.line"),
                        stringAttr(attributes, "swy.feature"),
                        stringAttr(attributes, "swy.prompt.version"),
                        stringAttr(attributes, "swy.agent.id"),
                        null,
                        stringAttr(attributes, "swy.step.id"),
                        (int) longAttr(attributes, "swy.step.seq"),
                        usage,
                        cost == null ? java.math.BigDecimal.ZERO : cost,
                        io.github.nephets.glasshouse.core.audit.AuditEvent.CURRENCY_CNY,
                        // span 上没有 payload 摘要时用 span 名兜底：
                        // 契约 EVT-001 要求 payloadDigest 非空且以 sha256: 开头，
                        // 而离线 JSONL 未必带摘要字段。传 null 会让 AuditEvent 抛异常，
                        // 结果整条 span 被「跳过」——离线成本对账时静默丢数据。
                        digestOrFallback(attributes, span),
                        null);
        engine.record(event, providerId, modelId);
    }

    /**
     * 取 span 的 payload 摘要，缺失时用 span 名算一个占位摘要。
     *
     * @param attributes span 属性
     * @param span span 快照
     * @return 合法的 {@code sha256:...} 摘要
     */
    private static String digestOrFallback(
            Map<String, Object> attributes,
            io.github.nephets.glasshouse.starter.store.SpanRingBuffer.SpanData span) {
        String digest = stringAttr(attributes, "swy.payload.digest");
        if (digest != null && digest.startsWith("sha256:")) {
            return digest;
        }
        return io.github.nephets.glasshouse.core.audit.PayloadRedactor.getInstance()
                .digest(span.name() == null ? "" : span.name());
    }

    private static String stringAttr(Map<String, Object> attributes, String key) {
        Object v = attributes.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v);
        return s.isBlank() ? null : s;
    }

    private static long longAttr(Map<String, Object> attributes, String key) {
        Object v = attributes.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static java.math.BigDecimal decimalAttr(Map<String, Object> attributes, String key) {
        Object v = attributes.get(key);
        if (v instanceof java.math.BigDecimal bd) {
            return bd;
        }
        if (v == null) {
            return null;
        }
        try {
            return new java.math.BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 渲染表格。
     *
     * @param report 报表
     * @return 表格文本
     */
    private String renderTable(AttributionReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("归因维度：").append(report.dimension().displayName()).append('\n');
        sb.append("总调用：")
                .append(report.totalCalls())
                .append("  总 token：")
                .append(report.totalTokens())
                .append("  总成本：¥")
                .append(scale(report.totalCost()))
                .append("  计量可信率：")
                .append(
                        String.format(
                                java.util.Locale.ROOT, "%.2f%%", report.trustworthyRatio() * 100))
                .append("\n\n");
        sb.append(
                String.format(
                        "%-28s %8s %12s %14s %8s%n",
                        report.dimension().displayName(), "调用数", "token", "成本(¥)", "占比"));
        sb.append("-".repeat(76)).append('\n');
        for (AttributionRow row : report.rows()) {
            sb.append(
                    String.format(
                            "%-28s %8d %12d %14s %7.2f%%%n",
                            truncate(row.valueOf(report.dimension()), 28),
                            row.callCount(),
                            row.totalTokens(),
                            scale(row.totalCost()),
                            row.share() * 100.0d));
        }
        if (report.rows().isEmpty()) {
            sb.append("（无数据）\n");
        }
        if (!report.untrustworthySources().isEmpty()) {
            sb.append("\n⚠ 注意：本结果包含估算计量（")
                    .append(report.untrustworthySources())
                    .append("），不应作为财务对账依据。\n");
        }
        return sb.toString();
    }

    /**
     * 渲染 JSON。
     *
     * @param report 报表
     * @return JSON 文本
     */
    private String renderJson(AttributionReport report) {
        // 不能直接把 AttributionReport 交给 Jackson：它是带派生方法的 record，
        // 序列化会连带触发 computed getter。这里显式投影成纯数据视图，
        // 保证 json 输出既可被下游解析、也不会随 record 演进而意外变形。
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("dimension", report.dimension().columnName());
        view.put("dimensionDisplayName", report.dimension().displayName());
        view.put("totalCalls", report.totalCalls());
        view.put("totalTokens", report.totalTokens());
        view.put("totalCostCny", report.totalCost());
        view.put("trustworthyRatio", report.trustworthyRatio());
        view.put("untrustworthySources", report.untrustworthySources());
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (AttributionRow row : report.rows()) {
            Map<String, Object> r = new java.util.LinkedHashMap<>();
            r.put("value", row.valueOf(report.dimension()));
            r.put("callCount", row.callCount());
            r.put("totalTokens", row.totalTokens());
            r.put("inputTokens", row.inputTokens());
            r.put("outputTokens", row.outputTokens());
            r.put("reasoningTokens", row.reasoningTokens());
            r.put("cachedInputTokens", row.cachedInputTokens());
            r.put("estimatedCount", row.estimatedCount());
            r.put("totalCostCny", row.totalCost());
            r.put("share", row.share());
            rows.add(r);
        }
        view.put("rows", rows);
        try {
            return MAPPER.writeValueAsString(view);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化归因报表失败", e);
        }
    }

    /**
     * 渲染 CSV。
     *
     * @param report 报表
     * @return CSV 文本
     */
    private String renderCsv(AttributionReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append(report.dimension().columnName())
                .append(",callCount,inputTokens,outputTokens,reasoningTokens,cachedInputTokens,")
                .append("estimatedCount,totalCostCny,share\n");
        for (AttributionRow row : report.rows()) {
            sb.append(csvEscape(row.valueOf(report.dimension())))
                    .append(',')
                    .append(row.callCount())
                    .append(',')
                    .append(row.inputTokens())
                    .append(',')
                    .append(row.outputTokens())
                    .append(',')
                    .append(row.reasoningTokens())
                    .append(',')
                    .append(row.cachedInputTokens())
                    .append(',')
                    .append(row.estimatedCount())
                    .append(',')
                    .append(scale(row.totalCost()))
                    .append(',')
                    .append(String.format(java.util.Locale.ROOT, "%.6f", row.share()))
                    .append('\n');
        }
        return sb.toString();
    }

    private static String scale(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value)
                .setScale(4, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ");
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max - 1) + "…";
    }

    private static String csvEscape(String text) {
        if (text == null) {
            return "";
        }
        String v = text.replace("\"", "\"\"");
        return v.contains(",") || v.contains("\n") || v.contains("\"") ? "\"" + v + "\"" : v;
    }

    /**
     * 返回支持的维度名，供帮助信息使用。
     *
     * @return 维度名列表
     */
    static List<String> supportedDimensions() {
        return AttributionDimension.all().stream().map(AttributionDimension::columnName).toList();
    }
}
