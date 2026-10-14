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

package io.github.nephets.glasshouse.core.eval;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 评测报告。
 *
 * @param datasetName 数据集名称
 * @param datasetVersion 数据集版本
 * @param datasetFingerprint 数据集指纹
 * @param promptVersion 被测 Prompt 版本
 * @param modelRef 被测模型，形如 {@code provider/model}
 * @param results 逐用例结果
 * @param metricScores 各指标的总分
 * @param totalCalls 总调用次数（失败也计入）
 * @param failedCalls 失败次数
 * @param totalCost 总成本（CNY）
 * @param generatedAt 报告生成时刻
 * @param durationMillis 评测总耗时（毫秒）
 */
public record EvalReport(
        String datasetName,
        String datasetVersion,
        String datasetFingerprint,
        String promptVersion,
        String modelRef,
        List<EvalResult> results,
        Map<String, BigDecimal> metricScores,
        long totalCalls,
        long failedCalls,
        BigDecimal totalCost,
        Instant generatedAt,
        long durationMillis) {

    /**
     * 紧凑构造器：字段兜底与不变量校验。
     *
     * @throws IllegalArgumentException failedCalls 超过 totalCalls
     */
    public EvalReport {
        results = results == null ? List.of() : List.copyOf(results);
        metricScores = metricScores == null ? Map.of() : Map.copyOf(metricScores);
        totalCost = totalCost == null ? BigDecimal.ZERO : totalCost;
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
        datasetName = datasetName == null ? "unknown" : datasetName;
        datasetVersion = datasetVersion == null ? JsonlDataset.UNKNOWN_VERSION : datasetVersion;
        datasetFingerprint = datasetFingerprint == null ? "" : datasetFingerprint;
        promptVersion = promptVersion == null ? "unknown" : promptVersion;
        modelRef = modelRef == null ? "unknown" : modelRef;
        totalCalls = Math.max(totalCalls, results.size());
        if (failedCalls < 0 || failedCalls > totalCalls) {
            throw new IllegalArgumentException("failedCalls 必须落在 [0, totalCalls]");
        }
        durationMillis = Math.max(0L, durationMillis);
    }

    /**
     * 构造空报告。
     *
     * @param datasetName 数据集名称
     * @param datasetVersion 数据集版本
     * @return 空报告
     */
    public static EvalReport empty(String datasetName, String datasetVersion) {
        return new EvalReport(
                datasetName,
                datasetVersion,
                "",
                "unknown",
                "unknown",
                List.of(),
                Map.of(),
                0L,
                0L,
                BigDecimal.ZERO,
                Instant.now(),
                0L);
    }

    /**
     * 返回成功率。
     *
     * @return 0~1 之间的成功率；无数据返回 0
     */
    public double successRate() {
        if (totalCalls == 0L) {
            return 0.0d;
        }
        return (double) (totalCalls - failedCalls) / (double) totalCalls;
    }

    /**
     * 读取某指标总分。
     *
     * @param metricName 指标名
     * @return 得分；不存在返回 null
     */
    public BigDecimal scoreOf(String metricName) {
        return metricScores.get(metricName);
    }

    /**
     * 判断全部用例是否都成功执行。
     *
     * @return 无失败返回 {@code true}
     */
    public boolean allPassed() {
        return failedCalls == 0L && !results.isEmpty();
    }

    /**
     * 返回失败用例的标识列表。
     *
     * @return 不可变列表；无失败时为空
     */
    public List<String> failedCaseIds() {
        return results.stream().filter(r -> !r.passed()).map(EvalResult::caseId).toList();
    }

    /**
     * 返回报告标题行，供 Markdown / 控制台输出复用。
     *
     * @return 单行摘要
     */
    public String summaryLine() {
        return "dataset="
                + datasetName
                + "@"
                + datasetVersion
                + ", prompt="
                + promptVersion
                + ", model="
                + modelRef
                + ", calls="
                + totalCalls
                + ", failed="
                + failedCalls
                + ", costCny="
                + totalCost.setScale(4, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
