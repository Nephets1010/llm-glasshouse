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

import io.github.nephets.glasshouse.core.usage.Usage;

/**
 * 单条用例的评测结果。
 *
 * <p>{@link #passed} 由「本条是否出错」唯一决定（{@code passed = error == null}）， 这一点很重要：门禁判定的第一道关卡是「有没有用例直接失败」，
 * 如果把指标不达标也算进 {@code passed}，就没法区分「跑挂了」和「跑完了但效果差」。
 *
 * @param caseId 用例标识
 * @param actual 模型实际输出，可为 null（调用失败）
 * @param score 本条在各指标上的原始明细：指标名 → 得分
 * @param usage 本次调用的计量，可为 null
 * @param cost 本次调用的成本（CNY），可为 null
 * @param error 错误描述；无错误为 null
 * @param latencyMillis 本次耗时（毫秒）
 * @param completedAt 完成时刻
 */
public record EvalResult(
        String caseId,
        String actual,
        java.util.Map<String, BigDecimal> score,
        Usage usage,
        BigDecimal cost,
        String error,
        long latencyMillis,
        Instant completedAt) {

    /** 紧凑构造器：字段兜底。 */
    public EvalResult {
        score = score == null ? java.util.Map.of() : java.util.Map.copyOf(score);
        completedAt = completedAt == null ? Instant.now() : completedAt;
        latencyMillis = Math.max(0L, latencyMillis);
    }

    /**
     * 构造一条成功结果。
     *
     * @param caseId 用例标识
     * @param actual 模型输出
     * @param usage 计量
     * @param cost 成本
     * @param latencyMillis 耗时
     * @return 结果实例
     */
    public static EvalResult success(
            String caseId, String actual, Usage usage, BigDecimal cost, long latencyMillis) {
        return new EvalResult(
                caseId,
                actual,
                java.util.Map.of(),
                usage,
                cost,
                null,
                latencyMillis,
                Instant.now());
    }

    /**
     * 构造一条失败结果。
     *
     * @param caseId 用例标识
     * @param error 错误描述
     * @return 结果实例
     */
    public static EvalResult failure(String caseId, String error) {
        return new EvalResult(
                caseId,
                null,
                java.util.Map.of(),
                null,
                null,
                error == null ? "未知错误" : error,
                0L,
                Instant.now());
    }

    /**
     * 返回一个填入了指标得分的副本。
     *
     * @param scores 指标名 → 得分
     * @return 新的结果实例
     */
    public EvalResult withScores(java.util.Map<String, BigDecimal> scores) {
        return new EvalResult(
                caseId, actual, scores, usage, cost, error, latencyMillis, completedAt);
    }

    /**
     * 判断本条是否成功完成（无错误）。
     *
     * @return 无错误返回 {@code true}
     */
    public boolean passed() {
        return error == null;
    }

    /**
     * 判断本条的<b>效果</b>是否达标。
     *
     * <p><b>与 {@link #passed()} 的区别</b>：{@code passed()} 只看「有没有跑挂」，
     * 而回归门禁关心的是「答得好不好」。一条能正常返回但答案全错的用例， {@code passed()} 是 {@code true}，用它判断退化会<b>完全漏掉效果退化</b>——
     * 这正是 CI 门禁最需要抓的情况（架构 ③T04 第 6 条）。
     *
     * <p>判定规则：已计算出的质量指标（{@code accuracy} / {@code keywordRecall} / {@code
     * containment}）全部达标即可。{@code cost} 与 {@code jsonSchema} 不参与判定： 前者是「越低越好」，后者在没有 JSON 期望时恒为
     * 0，把它们算进来会让 「非结构化用例」永远不达标，基线对比随之完全失效。
     *
     * @param threshold 达标阈值；非正时回退默认值 {@code 0.9999}
     * @return 质量指标全部达标返回 {@code true}；一条都没算返回 {@code false}
     */
    public boolean meetsThreshold(BigDecimal threshold) {
        if (error != null) {
            return false;
        }
        BigDecimal bar =
                threshold == null || threshold.signum() <= 0 ? PASS_SCORE_THRESHOLD : threshold;
        boolean anyScored = false;
        for (String metric : QUALITY_METRICS) {
            BigDecimal value = score.get(metric);
            if (value == null) {
                continue;
            }
            anyScored = true;
            if (value.compareTo(bar) < 0) {
                return false;
            }
        }
        return anyScored;
    }

    /**
     * 参与「效果达标」判定的质量指标。
     *
     * <p>刻意<b>不含</b> {@code cost}（越低越好）与 {@code jsonSchema} （无 JSON 期望时恒为 0）。
     */
    public static final List<String> QUALITY_METRICS =
            List.of(Metric.NAME_ACCURACY, Metric.NAME_KEYWORD_RECALL, Metric.NAME_CONTAINMENT);

    /** 默认达标阈值：满分 1.0000，要求四舍五入后等于满分。 */
    public static final BigDecimal PASS_SCORE_THRESHOLD = new BigDecimal("0.9999");

    /**
     * 读取某指标得分。
     *
     * @param metricName 指标名
     * @return 得分；未计算返回 {@link BigDecimal#ZERO}
     */
    public BigDecimal scoreOf(String metricName) {
        BigDecimal value = score.get(metricName);
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * 返回实际输出的长度。
     *
     * @return 字符数；输出为 null 时返回 0
     */
    public int actualLength() {
        return actual == null ? 0 : actual.length();
    }
}
