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

package io.github.nephets.glasshouse.eval.runner;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.nephets.glasshouse.core.eval.EvalReport;
import io.github.nephets.glasshouse.core.eval.EvalResult;
import io.github.nephets.glasshouse.core.eval.Metric;

/**
 * 基线对比器：对两个 Prompt 版本的评测结果做逐项 diff。
 *
 * <p><b>回答的是 PRD 画像 C（效果验收方）最想知道的问题</b>：「我改了 Prompt， 到底是变好还是变坏？」没有这个对比，改 Prompt 就只能靠几个人的主观感受，
 * 于是要么不敢改，要么盲改（PRD 痛点 3）。
 *
 * <p><b>diff 粒度到 case 级</b>：只给总分变化是不够的——总分从 0.82 变成 0.80 到底该改还是不改？
 * 必须能看到「哪几条变差了、差在哪个指标」，才能指导下一步。因此本类输出 {@link CaseDiff} 列表，每条能定位到 caseId 与具体指标。
 */
public final class BaselineComparator {

    /**
     * 比较两个报告。
     *
     * @param baseline 基线报告（如 prompt@v3），不可为 null
     * @param current 当前报告（如 prompt@v4），不可为 null
     * @return 对比结果
     */
    public Comparison compare(EvalReport baseline, EvalReport current) {
        Objects.requireNonNull(baseline, "baseline 不能为 null");
        Objects.requireNonNull(current, "current 不能为 null");

        List<CaseDiff> caseDiffs = new ArrayList<>();
        Map<String, EvalResult> baselineByCase = indexByCase(baseline);
        Map<String, EvalResult> currentByCase = indexByCase(current);

        java.util.Set<String> allCaseIds = new java.util.LinkedHashSet<>();
        allCaseIds.addAll(baselineByCase.keySet());
        allCaseIds.addAll(currentByCase.keySet());

        for (String caseId : allCaseIds) {
            EvalResult before = baselineByCase.get(caseId);
            EvalResult after = currentByCase.get(caseId);
            Map<String, BigDecimal> beforeScores = before == null ? Map.of() : before.score();
            Map<String, BigDecimal> afterScores = after == null ? Map.of() : after.score();
            Map<String, BigDecimal> deltas = new LinkedHashMap<>();
            java.util.Set<String> metricNames = new java.util.LinkedHashSet<>();
            metricNames.addAll(beforeScores.keySet());
            metricNames.addAll(afterScores.keySet());
            for (String metric : metricNames) {
                BigDecimal b = beforeScores.get(metric);
                BigDecimal c = afterScores.get(metric);
                BigDecimal delta =
                        (b == null || c == null)
                                ? null
                                : c.subtract(b).setScale(4, java.math.RoundingMode.HALF_UP);
                if (delta != null && delta.signum() != 0) {
                    deltas.put(metric, delta);
                }
            }
            // ★ 用「效果达标」而不是「有没有跑挂」判断退化：
            //   一条能正常返回但答案全错的用例 passed() 是 true，
            //   用它判断会让「Prompt 改坏了」这种最典型的退化完全不被发现
            //   ——那正是回归门禁唯一存在的理由。
            boolean regressed =
                    before != null
                            && before.meetsThreshold(null)
                            && (after == null || !after.meetsThreshold(null));
            boolean improved =
                    (before == null || !before.meetsThreshold(null))
                            && after != null
                            && after.meetsThreshold(null);
            if (!deltas.isEmpty() || regressed || improved) {
                caseDiffs.add(
                        new CaseDiff(
                                caseId,
                                before == null ? null : abbreviate(before.actual()),
                                after == null ? null : abbreviate(after.actual()),
                                deltas,
                                regressed,
                                improved,
                                after == null || !after.passed()));
            }
        }

        Map<String, MetricDelta> metricDeltas = new LinkedHashMap<>();
        java.util.Set<String> allMetrics = new java.util.LinkedHashSet<>();
        allMetrics.addAll(baseline.metricScores().keySet());
        allMetrics.addAll(current.metricScores().keySet());
        for (String metric : allMetrics) {
            BigDecimal b = baseline.scoreOf(metric);
            BigDecimal c = current.scoreOf(metric);
            if (b == null || c == null) {
                continue;
            }
            BigDecimal delta = c.subtract(b).setScale(4, java.math.RoundingMode.HALF_UP);
            metricDeltas.put(metric, new MetricDelta(metric, b, c, delta));
        }

        return new Comparison(
                baseline.promptVersion(),
                current.promptVersion(),
                baseline.datasetName(),
                baseline.datasetVersion(),
                metricDeltas,
                List.copyOf(caseDiffs),
                baseline.totalCost(),
                current.totalCost());
    }

    /**
     * 按 caseId 建立索引。
     *
     * @param report 报告
     * @return caseId → 结果
     */
    private static Map<String, EvalResult> indexByCase(EvalReport report) {
        Map<String, EvalResult> index = new LinkedHashMap<>();
        for (EvalResult r : report.results()) {
            index.put(r.caseId(), r);
        }
        return index;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return null;
        }
        String single = text.replaceAll("\\s+", " ").trim();
        return single.length() <= 120 ? single : single.substring(0, 120) + "...";
    }

    /**
     * 单个指标的分数变化。
     *
     * @param metricName 指标名
     * @param baselineScore 基线分
     * @param currentScore 当前分
     * @param delta 差值（当前 - 基线）
     */
    public record MetricDelta(
            String metricName,
            BigDecimal baselineScore,
            BigDecimal currentScore,
            BigDecimal delta) {

        /**
         * 判断该指标是变好还是变差。
         *
         * <p>约定：分数一律「越高越好」（{@code Metric} 接口的归一化保证）， 因此 delta &gt; 0 就是变好。
         *
         * @return 变好返回 {@code true}
         */
        public boolean improved() {
            return delta.signum() > 0;
        }

        /**
         * 判断该指标是否持平。
         *
         * @return 无变化返回 {@code true}
         */
        public boolean unchanged() {
            return delta.signum() == 0;
        }
    }

    /**
     * 单条用例的差异。
     *
     * @param caseId 用例标识
     * @param baselineOutput 基线输出（截断到 120 字符）
     * @param currentOutput 当前输出（截断到 120 字符）
     * @param metricDeltas 各指标的差值
     * @param regressed 是否从「通过」变成「失败」
     * @param improved 是否从「失败」变成「通过」
     * @param failed 当前是否失败
     */
    public record CaseDiff(
            String caseId,
            String baselineOutput,
            String currentOutput,
            Map<String, BigDecimal> metricDeltas,
            boolean regressed,
            boolean improved,
            boolean failed) {

        /**
         * 紧凑构造器：差值表不可变。
         *
         * @param caseId 用例标识
         * @param baselineOutput 基线输出
         * @param currentOutput 当前输出
         * @param metricDeltas 指标差值
         * @param regressed 是否退化
         * @param improved 是否改善
         * @param failed 当前是否失败
         */
        public CaseDiff {
            metricDeltas = metricDeltas == null ? Map.of() : Map.copyOf(metricDeltas);
        }
    }

    /**
     * 两个报告的完整对比。
     *
     * @param baselinePrompt 基线 Prompt 版本
     * @param currentPrompt 当前 Prompt 版本
     * @param datasetName 数据集名称
     * @param datasetVersion 数据集版本
     * @param metricDeltas 指标级差异
     * @param caseDiffs 用例级差异
     * @param baselineCost 基线总成本
     * @param currentCost 当前总成本
     */
    public record Comparison(
            String baselinePrompt,
            String currentPrompt,
            String datasetName,
            String datasetVersion,
            Map<String, MetricDelta> metricDeltas,
            List<CaseDiff> caseDiffs,
            BigDecimal baselineCost,
            BigDecimal currentCost) {

        /**
         * 紧凑构造器：字段兜底。
         *
         * @param baselinePrompt 基线版本
         * @param currentPrompt 当前版本
         * @param datasetName 数据集名
         * @param datasetVersion 数据集版本
         * @param metricDeltas 指标差异
         * @param caseDiffs 用例差异
         * @param baselineCost 基线成本
         * @param currentCost 当前成本
         */
        public Comparison {
            metricDeltas = metricDeltas == null ? Map.of() : Map.copyOf(metricDeltas);
            caseDiffs = caseDiffs == null ? List.of() : List.copyOf(caseDiffs);
            baselineCost = baselineCost == null ? BigDecimal.ZERO : baselineCost;
            currentCost = currentCost == null ? BigDecimal.ZERO : currentCost;
        }

        /**
         * 统计「变差」的用例数。
         *
         * <p>这是对比结论里最重要个数：门禁该卡的不是总分，而是「有没有把原本对的用例做错」。
         *
         * @return 退化用例数
         */
        public long regressedCount() {
            return caseDiffs.stream().filter(CaseDiff::regressed).count();
        }

        /**
         * 统计「变好」的用例数。
         *
         * @return 改善用例数
         */
        public long improvedCount() {
            return caseDiffs.stream().filter(CaseDiff::improved).count();
        }

        /**
         * 读取某指标的差异。
         *
         * @param metricName 指标名
         * @return 差异；不存在返回 null
         */
        public MetricDelta deltaOf(String metricName) {
            return metricDeltas.get(metricName);
        }

        /**
         * 生成人类可读的中文摘要。
         *
         * @return 多行摘要
         */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("对比 ")
                    .append(baselinePrompt)
                    .append(" → ")
                    .append(currentPrompt)
                    .append("（数据集 ")
                    .append(datasetName)
                    .append('@')
                    .append(datasetVersion)
                    .append("）\n");
            if (metricDeltas.isEmpty()) {
                sb.append("  （两个版本的所有指标完全一致）\n");
            } else {
                for (MetricDelta d : metricDeltas.values()) {
                    sb.append("  ")
                            .append(d.metricName())
                            .append(": ")
                            .append(d.baselineScore())
                            .append(" → ")
                            .append(d.currentScore())
                            .append("  (")
                            .append(d.delta().signum() > 0 ? "+" : "")
                            .append(d.delta())
                            .append(") ")
                            .append(d.improved() ? "↑变好" : d.unchanged() ? "持平" : "↓变差")
                            .append('\n');
                }
            }
            sb.append("  用例级：变好 ")
                    .append(improvedCount())
                    .append(" 条，变差 ")
                    .append(regressedCount())
                    .append(" 条，共 ")
                    .append(caseDiffs.size())
                    .append(" 条有差异\n");
            sb.append("  成本：")
                    .append(baselineCost.setScale(4, java.math.RoundingMode.HALF_UP))
                    .append(" → ")
                    .append(currentCost.setScale(4, java.math.RoundingMode.HALF_UP))
                    .append(" CNY");
            return sb.toString();
        }

        /**
         * 列出退化的用例 id。
         *
         * @return 退化用例 id 列表
         */
        public List<String> regressedCaseIds() {
            return caseDiffs.stream().filter(CaseDiff::regressed).map(CaseDiff::caseId).toList();
        }
    }

    /**
     * 渲染对比结果的 Markdown 表格。
     *
     * @param comparison 对比结果，不可为 null
     * @return Markdown 文本
     */
    public static String toMarkdown(Comparison comparison) {
        StringBuilder sb = new StringBuilder();
        sb.append("| 指标 | ")
                .append(comparison.baselinePrompt())
                .append(" | ")
                .append(comparison.currentPrompt())
                .append(" | 变化 |\n");
        sb.append("|---|---:|---:|---:|\n");
        for (MetricDelta d : comparison.metricDeltas().values()) {
            sb.append("| ")
                    .append(d.metricName())
                    .append(" | ")
                    .append(d.baselineScore())
                    .append(" | ")
                    .append(d.currentScore())
                    .append(" | ")
                    .append(d.delta().signum() > 0 ? "+" : "")
                    .append(d.delta())
                    .append(" |\n");
        }
        List<CaseDiff> regressed =
                comparison.caseDiffs().stream().filter(CaseDiff::regressed).toList();
        if (!regressed.isEmpty()) {
            sb.append("\n**变差的用例**：\n\n");
            for (CaseDiff d : regressed) {
                sb.append("- `").append(d.caseId()).append('`');
                d.metricDeltas()
                        .forEach((k, v) -> sb.append(" · ").append(k).append(' ').append(v));
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 返回全部内置指标名，供对比报告表头使用。
     *
     * @return 指标名列表
     */
    public static List<String> metricNames() {
        return List.of(
                Metric.NAME_ACCURACY,
                Metric.NAME_KEYWORD_RECALL,
                Metric.NAME_CONTAINMENT,
                Metric.NAME_JSON_SCHEMA,
                Metric.NAME_COST);
    }
}
