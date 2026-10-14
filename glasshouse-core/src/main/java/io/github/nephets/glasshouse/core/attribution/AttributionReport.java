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

package io.github.nephets.glasshouse.core.attribution;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import io.github.nephets.glasshouse.core.usage.UsageSource;

/**
 * 归因报表。
 *
 * <p>除了逐行数据，还带上「计量可信率」这类<b>数据质量指标</b>。原因很实际： 观测侧最常见的翻车方式是把一批 {@code ESTIMATED} 数据当成真实账汇报给财务，
 * 所以可信率必须和金额一起出现在报表首页，而不是藏在某个 detail 页里。
 *
 * @param dimension 聚合维度
 * @param rows 逐行明细，按成本倒序
 * @param totalCalls 总调用次数
 * @param totalInputTokens 总输入 token
 * @param totalOutputTokens 总输出 token
 * @param totalReasoningTokens 总推理 token
 * @param totalCost 总成本（CNY）
 * @param generatedAt 报表生成时刻
 */
public record AttributionReport(
        AttributionDimension dimension,
        List<AttributionRow> rows,
        long totalCalls,
        long totalInputTokens,
        long totalOutputTokens,
        long totalReasoningTokens,
        BigDecimal totalCost,
        Instant generatedAt) {

    /**
     * 紧凑构造器：字段兜底与不变量校验。
     *
     * @throws IllegalArgumentException totalCost 为负
     */
    public AttributionReport {
        rows = rows == null ? List.of() : List.copyOf(rows);
        if (totalCost == null) {
            totalCost = BigDecimal.ZERO;
        }
        if (dimension == null) {
            dimension = AttributionDimension.MODEL;
        }
        if (generatedAt == null) {
            generatedAt = Instant.now();
        }
        if (totalCost.signum() < 0) {
            throw new IllegalArgumentException("totalCost 不得为负");
        }
    }

    /**
     * 构造空报表。
     *
     * @param dimension 聚合维度
     * @return 空报表
     */
    public static AttributionReport empty(AttributionDimension dimension) {
        return new AttributionReport(
                dimension, List.of(), 0L, 0L, 0L, 0L, BigDecimal.ZERO, Instant.now());
    }

    /**
     * 返回总 token 数。
     *
     * @return 输入 + 输出 + 推理
     */
    public long totalTokens() {
        return totalInputTokens + totalOutputTokens + totalReasoningTokens;
    }

    /**
     * 返回本次查询命中的数据中，供应商真实返回计量的比例。
     *
     * <p>低于 1.0 说明存在估算值。报表应显式提示「本结果包含估算计量」。
     *
     * @return 0~1 之间的可信率；无数据返回 1.0
     */
    public double trustworthyRatio() {
        if (totalCalls == 0L) {
            return 1.0d;
        }
        long estimated = rows.stream().mapToLong(AttributionRow::estimatedCount).sum();
        long rowCalls = rows.stream().mapToLong(AttributionRow::callCount).sum();
        if (rowCalls == 0L) {
            return 1.0d;
        }
        return (double) (rowCalls - estimated) / (double) rowCalls;
    }

    /**
     * 返回本次查询涉及的非可信计量来源。
     *
     * @return 不可变列表；全部可信时为空
     */
    public List<UsageSource> untrustworthySources() {
        if (totalCalls == 0L || trustworthyRatio() >= 1.0d) {
            return List.of();
        }
        return List.of(UsageSource.ESTIMATED, UsageSource.FALLBACK);
    }

    /**
     * 返回成本占比最高的一行。
     *
     * @return 占比最高的行；无数据返回空
     */
    public java.util.Optional<AttributionRow> topRow() {
        return rows.stream().findFirst();
    }

    /**
     * 生成人类可读摘要。
     *
     * @return 单行摘要
     */
    public String summary() {
        return "dimension="
                + dimension.displayName()
                + ", rows="
                + rows.size()
                + ", calls="
                + totalCalls
                + ", totalCostCny="
                + totalCost.setScale(4, java.math.RoundingMode.HALF_UP).toPlainString()
                + ", trustworthy="
                + String.format(java.util.Locale.ROOT, "%.4f", trustworthyRatio());
    }
}
