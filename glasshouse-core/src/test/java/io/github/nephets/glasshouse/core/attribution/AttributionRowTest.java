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

import io.github.nephets.glasshouse.core.attribution.SlidingWindowAccumulator.Snapshot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttributionRowTest {

    private static AttributionKey key() {
        return AttributionKey.empty();
    }

    @Test
    @DisplayName("负计数直接拒绝")
    void negativeCountsRejected() {
        assertThatThrownBy(
                        () ->
                                new AttributionRow(
                                        key(), -1L, 0L, 0L, 0L, 0L, 0L, BigDecimal.ZERO, 0.0d))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("计数");
        assertThatThrownBy(
                        () ->
                                new AttributionRow(
                                        key(), 1L, -1L, 0L, 0L, 0L, 0L, BigDecimal.ZERO, 0.0d))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new AttributionRow(
                                        key(), 1L, 0L, -1L, 0L, 0L, 0L, BigDecimal.ZERO, 0.0d))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new AttributionRow(
                                        key(), 1L, 0L, 0L, 0L, 0L, -1L, BigDecimal.ZERO, 0.0d))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("share 越界或 NaN 直接拒绝")
    void invalidShareRejected() {
        assertThatThrownBy(
                        () ->
                                new AttributionRow(
                                        key(), 1L, 0L, 0L, 0L, 0L, 0L, BigDecimal.ZERO, 1.5d))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("share");
        assertThatThrownBy(
                        () ->
                                new AttributionRow(
                                        key(), 1L, 0L, 0L, 0L, 0L, 0L, BigDecimal.ZERO, -0.1d))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new AttributionRow(
                                        key(), 1L, 0L, 0L, 0L, 0L, 0L, BigDecimal.ZERO, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("totalCost 为 null 时兜底为零，share 的 0 与 1 边界合法")
    void nullCostAndShareBoundaries() {
        AttributionRow zeroCost = new AttributionRow(key(), 0L, 0L, 0L, 0L, 0L, 0L, null, 1.0d);
        assertThat(zeroCost.totalCost()).isEqualByComparingTo("0");
        assertThat(zeroCost.share()).isEqualTo(1.0d);
        assertThat(zeroCost.avgCost()).isEqualByComparingTo("0");

        AttributionRow lowerBound =
                new AttributionRow(key(), 1L, 0L, 0L, 0L, 0L, 0L, BigDecimal.ONE, 0.0d);
        assertThat(lowerBound.share()).isEqualTo(0.0d);
    }

    @Test
    @DisplayName("totalTokens 把输入、输出、推理、缓存命中四项都算进去")
    void totalTokensIncludesCached() {
        AttributionRow row =
                new AttributionRow(key(), 1L, 10L, 20L, 30L, 40L, 0L, new BigDecimal("1"), 0.5d);
        assertThat(row.totalTokens()).isEqualTo(100L);
    }

    @Test
    @DisplayName("avgCost 用 8 位小数 HALF_UP，零调用返回零")
    void avgCostPrecision() {
        AttributionRow zeroCalls =
                new AttributionRow(key(), 0L, 0L, 0L, 0L, 0L, 0L, new BigDecimal("5"), 0.0d);
        assertThat(zeroCalls.avgCost()).isEqualByComparingTo("0");

        AttributionRow oneThird =
                new AttributionRow(key(), 3L, 0L, 0L, 0L, 0L, 0L, new BigDecimal("1"), 0.0d);
        assertThat(oneThird.avgCost()).isEqualByComparingTo("0.33333333");

        AttributionRow twoThirds =
                new AttributionRow(key(), 3L, 0L, 0L, 0L, 0L, 0L, new BigDecimal("2"), 0.0d);
        assertThat(twoThirds.avgCost()).isEqualByComparingTo("0.66666667");
    }

    @Test
    @DisplayName("of 从快照逐字段拷贝")
    void ofCopiesSnapshot() {
        Snapshot snapshot =
                new Snapshot(
                        4L,
                        100L,
                        200L,
                        30L,
                        40L,
                        1L,
                        new BigDecimal("1.25"),
                        Instant.parse("2026-01-01T00:00:00Z"));
        AttributionKey tenantKey = AttributionKey.empty().with(AttributionDimension.TENANT, "vip");
        AttributionRow row = AttributionRow.of(tenantKey, snapshot, 0.25d);
        assertThat(row.key()).isSameAs(tenantKey);
        assertThat(row.callCount()).isEqualTo(4L);
        assertThat(row.inputTokens()).isEqualTo(100L);
        assertThat(row.outputTokens()).isEqualTo(200L);
        assertThat(row.reasoningTokens()).isEqualTo(30L);
        assertThat(row.cachedInputTokens()).isEqualTo(40L);
        assertThat(row.estimatedCount()).isEqualTo(1L);
        assertThat(row.totalCost()).isEqualByComparingTo("1.25");
        assertThat(row.share()).isEqualTo(0.25d);
        assertThat(row.totalTokens()).isEqualTo(370L);
        assertThat(row.avgCost()).isEqualByComparingTo("0.31250000");
    }

    @Test
    @DisplayName("valueOf 在键为 null 时返回未标注，有值键按维度展示")
    void valueOfDisplay() {
        AttributionRow nullKey =
                new AttributionRow(null, 0L, 0L, 0L, 0L, 0L, 0L, BigDecimal.ZERO, 0.0d);
        assertThat(nullKey.valueOf(AttributionDimension.TENANT)).isEqualTo("未标注");

        AttributionKey featureKey = AttributionKey.empty().with(AttributionDimension.FEATURE, "客服");
        AttributionRow row =
                new AttributionRow(featureKey, 1L, 0L, 0L, 0L, 0L, 0L, BigDecimal.ZERO, 0.0d);
        assertThat(row.valueOf(AttributionDimension.FEATURE)).isEqualTo("客服");
        assertThat(row.valueOf(AttributionDimension.TENANT)).isEqualTo("未标注");
    }
}
