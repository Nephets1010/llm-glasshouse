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

package io.github.nephets.glasshouse.eval.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.nephets.glasshouse.core.eval.EvalReport;
import io.github.nephets.glasshouse.core.eval.EvalResult;
import io.github.nephets.glasshouse.core.eval.GateRule;
import io.github.nephets.glasshouse.eval.gate.GateParser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * 评测报告输出器：Markdown（给人看）+ JSON（给机器读）。
 *
 * <p><b>为什么两种都要</b>：Markdown 报告会被贴进 PR 评论和 Slack，是「效果验收方」 （算法/业务/QA）真正会读的东西；JSON 报告供 CI 后续步骤做趋势分析。
 *
 * <p><b>报告里必须包含的两件事</b>：
 *
 * <ol>
 *   <li><b>结论</b>：门禁通过 / 未通过 + 未通过的具体原因（架构 ③T04 要求「不达标时写报告」）
 *   <li><b>数据出处</b>：数据集名 + 版本 + 指纹。没有这三样，报告无法被第三方复核
 * </ol>
 */
public final class EvalReportWriter {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * 专供 JSONL 使用的紧凑 mapper。
     *
     * <p><b>不能复用 {@link #MAPPER}</b>：它开了 {@code INDENT_OUTPUT}（给人读的报告需要缩进）， 但 JSONL
     * 要求<b>每条记录必须是一行</b>，缩进会把一条记录摊成多行， 下游按行解析时直接报「Unexpected end-of-input」。
     */
    private static final ObjectMapper COMPACT_MAPPER = new ObjectMapper();

    private EvalReportWriter() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 渲染 Markdown 报告。
     *
     * @param report 评测报告，不可为 null
     * @param verdict 门禁判定，可为 null（不设门禁）
     * @return Markdown 文本
     */
    public static String toMarkdown(EvalReport report, GateParser.Verdict verdict) {
        Objects.requireNonNull(report, "report 不能为 null");
        StringBuilder sb = new StringBuilder();
        sb.append("# 评测报告：").append(report.datasetName()).append('\n').append('\n');

        // ① 结论放最前面：验收方只关心这一行
        if (verdict != null) {
            sb.append("## 结论：").append(verdict.passed() ? "✅ 通过" : "❌ 未通过").append("\n\n");
            sb.append(verdict.message()).append("\n\n");
        } else {
            // 没有门禁判定时也要有「结论」段：报告的第一屏必须是可执行的信息，
            // 空白会让读者以为报告生成失败
            sb.append("## 结论：ℹ 未配置门禁（仅出报告，不判定通过与否）\n\n");
            sb.append("使用 `--gate \"accuracy>0.85,keywordRecall>0.9\"` 可开启门禁判定。\n\n");
        }

        // ② 可追溯性
        sb.append("## 元信息\n\n");
        sb.append("| 项 | 值 |\n|---|---|\n");
        sb.append("| 数据集 | ").append(report.datasetName()).append(" |\n");
        sb.append("| 数据集版本 | `").append(report.datasetVersion()).append("` |\n");
        sb.append("| 数据集指纹 | `").append(report.datasetFingerprint()).append("` |\n");
        sb.append("| Prompt 版本 | ").append(report.promptVersion()).append(" |\n");
        sb.append("| 模型 | ").append(report.modelRef()).append(" |\n");
        sb.append("| 用例数 | ").append(report.totalCalls()).append(" |\n");
        sb.append("| 失败数 | ").append(report.failedCalls()).append(" |\n");
        sb.append("| 成功率 | ")
                .append(String.format(java.util.Locale.ROOT, "%.2f%%", report.successRate() * 100))
                .append(" |\n");
        sb.append("| 总成本 | ¥")
                .append(report.totalCost().setScale(4, RoundingMode.HALF_UP).toPlainString())
                .append(" |\n");
        sb.append("| 耗时 | ").append(report.durationMillis()).append(" ms |\n\n");

        // ③ 指标总分
        sb.append("## 指标\n\n");
        if (report.metricScores().isEmpty()) {
            sb.append("（无成功用例，未产生指标分数——请检查是否有用例执行失败）\n\n");
        } else {
            sb.append("| 指标 | 得分 |\n|---|---:|\n");
            report.metricScores()
                    .forEach(
                            (k, v) ->
                                    sb.append("| ")
                                            .append(k)
                                            .append(" | ")
                                            .append(v)
                                            .append(" |\n"));
            sb.append('\n');
        }

        // ④ 门禁逐条判定
        if (verdict != null && !verdict.outcomes().isEmpty()) {
            sb.append("## 门禁\n\n");
            sb.append("| 规则 | 实际值 | 结果 |\n|---|---:|---|\n");
            for (GateRule.GateOutcome o : verdict.outcomes()) {
                sb.append("| `")
                        .append(o.rule().metricName())
                        .append(' ')
                        .append(o.rule().op().symbol())
                        .append(' ')
                        .append(o.rule().threshold().toPlainString())
                        .append("` | ")
                        .append(o.actual() == null ? "—" : o.actual().toPlainString())
                        .append(" | ")
                        .append(o.passed() ? "✅" : "❌")
                        .append(" |\n");
            }
            sb.append('\n');
        }

        // ⑤ 失败用例明细：这是「怎么修」的入口
        List<String> failed = report.failedCaseIds();
        if (!failed.isEmpty()) {
            sb.append("## 失败用例（").append(failed.size()).append(" 条）\n\n");
            sb.append("| 用例 | 错误 | 输出长度 |\n|---|---|---:|\n");
            for (EvalResult r : report.results()) {
                if (r.passed()) {
                    continue;
                }
                sb.append("| `")
                        .append(r.caseId())
                        .append("` | ")
                        .append(r.error() == null ? "" : r.error().replace('|', '/'))
                        .append(" | ")
                        .append(r.actualLength())
                        .append(" |\n");
            }
            sb.append('\n');
        }

        sb.append("---\n");
        sb.append("由 [llm-glasshouse](https://github.com/nephets/llm-glasshouse) 生成。")
                .append("报告可用 `glasshouse eval run` 复现：数据集与 Prompt 版本决定结果，模型温度为 0 时可完全复现。\n");
        return sb.toString();
    }

    /**
     * 渲染 JSON 报告。
     *
     * @param report 评测报告，不可为 null
     * @param verdict 门禁判定，可为 null
     * @return JSON 文本
     */
    public static String toJson(EvalReport report, GateParser.Verdict verdict) {
        Objects.requireNonNull(report, "report 不能为 null");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("dataset", report.datasetName());
        root.put("datasetVersion", report.datasetVersion());
        root.put("datasetFingerprint", report.datasetFingerprint());
        root.put("promptVersion", report.promptVersion());
        root.put("model", report.modelRef());
        root.put("totalCalls", report.totalCalls());
        root.put("failedCalls", report.failedCalls());
        root.put("successRate", round(report.successRate()));
        root.put("totalCostCny", report.totalCost());
        root.put("durationMillis", report.durationMillis());
        root.put("generatedAt", report.generatedAt().toString());
        root.put("metricScores", report.metricScores());
        root.put("failedCaseIds", report.failedCaseIds());

        List<Map<String, Object>> cases = new ArrayList<>();
        for (EvalResult r : report.results()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("caseId", r.caseId());
            c.put("passed", r.passed());
            c.put("scores", r.score());
            c.put("latencyMillis", r.latencyMillis());
            c.put("actualLength", r.actualLength());
            c.put("costCny", r.cost());
            if (r.usage() != null) {
                Map<String, Object> u = new LinkedHashMap<>();
                u.put("inputTokens", r.usage().inputTokens());
                u.put("outputTokens", r.usage().outputTokens());
                u.put("reasoningTokens", r.usage().reasoningTokens());
                u.put("totalTokens", r.usage().totalTokens());
                u.put("source", r.usage().usageSource().name());
                c.put("usage", u);
            }
            if (!r.passed()) {
                c.put("error", r.error());
            }
            cases.add(c);
        }
        root.put("cases", cases);

        if (verdict != null) {
            Map<String, Object> gate = new LinkedHashMap<>();
            gate.put("passed", verdict.passed());
            gate.put("exitCode", verdict.exitCode());
            gate.put("message", verdict.message());
            List<Map<String, Object>> rules = new ArrayList<>();
            for (GateRule.GateOutcome o : verdict.outcomes()) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("metric", o.rule().metricName());
                r.put("operator", o.rule().op().symbol());
                r.put("threshold", o.rule().threshold());
                r.put("actual", o.actual());
                r.put("passed", o.passed());
                r.put("reason", o.reason());
                rules.add(r);
            }
            gate.put("rules", rules);
            root.put("gate", gate);
        }

        try {
            return MAPPER.writeValueAsString(root);
        } catch (IOException e) {
            throw new UncheckedIOException("序列化评测报告失败", e);
        }
    }

    /**
     * 把 Markdown 报告写到文件，自动创建父目录。
     *
     * @param report 评测报告
     * @param verdict 门禁判定
     * @param target 目标文件路径，不可为 null
     * @return 写入的字符数
     */
    public static int writeMarkdown(EvalReport report, GateParser.Verdict verdict, Path target) {
        return write(toMarkdown(report, verdict), target);
    }

    /**
     * 把 JSON 报告写到文件，自动创建父目录。
     *
     * @param report 评测报告
     * @param verdict 门禁判定
     * @param target 目标文件路径，不可为 null
     * @return 写入的字符数
     */
    public static int writeJson(EvalReport report, GateParser.Verdict verdict, Path target) {
        return write(toJson(report, verdict), target);
    }

    /**
     * 把 JSONL 格式的 span / 用例结果写到文件（供 CLI 与 Action 归档）。
     *
     * @param report 评测报告
     * @param target 目标文件路径
     * @return 写入的行数
     */
    public static int writeJsonl(EvalReport report, Path target) {
        StringBuilder sb = new StringBuilder();
        for (EvalResult r : report.results()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("caseId", r.caseId());
            c.put("passed", r.passed());
            c.put("scores", r.score());
            c.put("costCny", r.cost());
            try {
                sb.append(COMPACT_MAPPER.writeValueAsString(c)).append('\n');
            } catch (IOException e) {
                throw new UncheckedIOException("序列化用例结果失败: " + r.caseId(), e);
            }
        }
        write(sb.toString(), target);
        return report.results().size();
    }

    private static int write(String content, Path target) {
        try {
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(target, content, StandardCharsets.UTF_8);
            return content.length();
        } catch (IOException e) {
            throw new UncheckedIOException("写入报告失败: " + target.toAbsolutePath(), e);
        }
    }

    private static BigDecimal round(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }
}
