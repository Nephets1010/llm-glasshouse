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

/**
 * 归因结果行。
 *
 * @param key 归因键
 * @param callCount 调用次数
 * @param inputTokens 累计输入 token
 * @param outputTokens 累计输出 token
 * @param reasoningTokens 累计推理 token
 * @param cachedInputTokens 累计缓存命中输入 token
 * @param estimatedCount 非供应商真实返回的计量次数
 * @param totalCost 累计成本（CNY）
 * @param share 占总成本的比例（0~1）
 */
public record AttributionRow(
        AttributionKey key,
        long callCount,
        long inputTokens,
        long outputTokens,
        long reasoningTokens,
        long cachedInputTokens,
        long estimatedCount,
        BigDecimal totalCost,
        double share) {

    /**
     * 紧凑构造器：分母归一化。
     *
     * @throws IllegalArgumentException 计数为负或 share 不在 [0,1]
     */
    public AttributionRow {
        if (callCount < 0 || inputTokens < 0 || outputTokens < 0 || estimatedCount < 0) {
            throw new IllegalArgumentException("计数禁止负值");
        }
        if (totalCost == null) {
            totalCost = BigDecimal.ZERO;
        }
        if (share < 0.0d || share > 1.0d || Double.isNaN(share)) {
            throw new IllegalArgumentException("share 必须落在 [0,1]，实际为: " + share);
        }
    }

    /**
     * 从累加器快照构造结果行。
     *
     * @param key 归因键
     * @param snapshot 累加器快照
     * @param share 占比
     * @return 结果行
     */
    public static AttributionRow of(
            AttributionKey key, SlidingWindowAccumulator.Snapshot snapshot, double share) {
        return new AttributionRow(
                key,
                snapshot.totalCount(),
                snapshot.inputTokens(),
                snapshot.outputTokens(),
                snapshot.reasoningTokens(),
                snapshot.cachedInputTokens(),
                snapshot.estimatedCount(),
                snapshot.totalCost(),
                share);
    }

    /**
     * 返回总 token 数。
     *
     * @return 输入 + 输出 + 推理 + 缓存命中
     */
    public long totalTokens() {
        return inputTokens + outputTokens + reasoningTokens + cachedInputTokens;
    }

    /**
     * 返回平均单次成本。
     *
     * @return 平均成本
     */
    public BigDecimal avgCost() {
        if (callCount == 0L) {
            return BigDecimal.ZERO;
        }
        return totalCost.divide(BigDecimal.valueOf(callCount), 8, java.math.RoundingMode.HALF_UP);
    }

    /**
     * 返回按指定维度取到的值（缺失时返回「未标注」而非内部占位符）。
     *
     * @param dimension 目标维度，不可为 null
     * @return 维度值；未标注时返回 {@code "未标注"}
     */
    public String valueOf(AttributionDimension dimension) {
        if (key == null) {
            return AttributionKey.DISPLAY_MISSING;
        }
        return key.display(dimension);
    }
}
