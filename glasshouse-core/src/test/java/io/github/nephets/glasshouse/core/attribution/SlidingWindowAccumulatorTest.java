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
import java.time.Duration;
import java.time.Instant;

import io.github.nephets.glasshouse.core.attribution.SlidingWindowAccumulator.Snapshot;
import io.github.nephets.glasshouse.core.usage.Usage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlidingWindowAccumulatorTest {

    private static final Instant EARLIER = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant LATER = Instant.parse("2026-02-01T00:00:00Z");
    private static final Instant FAR_FUTURE = Instant.parse("2099-01-01T00:00:00Z");
    private static final Instant FAR_PAST = Instant.parse("1971-01-01T00:00:00Z");

    @Test
    @DisplayName("累加器拒绝 null 键")
    void accumulatorRejectsNullKey() {
        assertThatThrownBy(() -> new SlidingWindowAccumulator(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("累加器 add 后快照字段正确，key 不变，最后更新时刻为正")
    void accumulatorAdd() {
        AttributionKey key = AttributionKey.empty().with(AttributionDimension.TENANT, "vip");
        SlidingWindowAccumulator accumulator = new SlidingWindowAccumulator(key);
        assertThat(accumulator.key()).isSameAs(key);
        assertThat(accumulator.isEmpty()).isTrue();
        assertThat(accumulator.lastUpdatedAtMillis()).isZero();

        accumulator.add(Usage.of(100L, 50L), new BigDecimal("0.01"));
        assertThat(accumulator.isEmpty()).isFalse();
        assertThat(accumulator.snapshot().totalCount()).isEqualTo(1L);
        assertThat(accumulator.snapshot().inputTokens()).isEqualTo(100L);
        assertThat(accumulator.snapshot().outputTokens()).isEqualTo(50L);
        assertThat(accumulator.snapshot().totalCost()).isEqualByComparingTo("0.01");
        assertThat(accumulator.lastUpdatedAtMillis()).isPositive();
        assertThat(accumulator.toString()).contains("tenant=vip", "calls=1", "cost=0.01");
    }

    @Test
    @DisplayName("累加器忽略 null 用量与 null 成本，不写入最后更新时刻")
    void addIgnoresNulls() {
        SlidingWindowAccumulator accumulator = new SlidingWindowAccumulator(AttributionKey.empty());
        accumulator.add(null, BigDecimal.ONE);
        accumulator.add(Usage.of(1L, 1L), null);
        assertThat(accumulator.isEmpty()).isTrue();
        assertThat(accumulator.snapshot().totalCount()).isZero();
        assertThat(accumulator.lastUpdatedAtMillis()).isZero();
    }

    @Test
    @DisplayName("从未写入的累加器不判过期，写入后按截止时刻判断")
    void staleThan() {
        SlidingWindowAccumulator accumulator = new SlidingWindowAccumulator(AttributionKey.empty());
        assertThat(accumulator.staleThan(FAR_FUTURE)).isFalse();
        accumulator.add(Usage.of(1L, 1L), BigDecimal.ONE);
        assertThat(accumulator.staleThan(FAR_FUTURE)).isTrue();
        assertThat(accumulator.staleThan(FAR_PAST)).isFalse();
    }

    @Test
    @DisplayName("plus(Usage,cost) 累加一次用量，估算计量计入 estimatedCount")
    void plusUsageAccumulates() {
        Snapshot snapshot =
                Snapshot.EMPTY
                        .plus(Usage.of(100L, 50L), new BigDecimal("0.01"))
                        .plus(Usage.estimated(10L, 5L, 2L, 1L), new BigDecimal("0.02"));
        assertThat(snapshot.totalCount()).isEqualTo(2L);
        assertThat(snapshot.inputTokens()).isEqualTo(110L);
        assertThat(snapshot.outputTokens()).isEqualTo(55L);
        assertThat(snapshot.reasoningTokens()).isEqualTo(2L);
        assertThat(snapshot.cachedInputTokens()).isEqualTo(1L);
        assertThat(snapshot.estimatedCount()).isEqualTo(1L);
        assertThat(snapshot.totalCost()).isEqualByComparingTo("0.03");
        assertThat(snapshot.trustworthyRatio()).isEqualTo(0.5d);
        assertThat(snapshot.lastUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("成本为 null 时按零累加")
    void plusUsageNullCost() {
        Snapshot snapshot = Snapshot.EMPTY.plus(Usage.of(1L, 1L), null);
        assertThat(snapshot.totalCost()).isEqualByComparingTo("0");
        assertThat(snapshot.totalCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("plus(Snapshot) 八字段相加，更新时间取较晚者")
    void plusSnapshotAddsAllFields() {
        Snapshot first = new Snapshot(2L, 100L, 200L, 30L, 40L, 1L, new BigDecimal("1.00"), LATER);
        Snapshot second = new Snapshot(3L, 10L, 20L, 3L, 4L, 2L, new BigDecimal("2.00"), EARLIER);
        Snapshot merged = first.plus(second);
        assertThat(merged.totalCount()).isEqualTo(5L);
        assertThat(merged.inputTokens()).isEqualTo(110L);
        assertThat(merged.outputTokens()).isEqualTo(220L);
        assertThat(merged.reasoningTokens()).isEqualTo(33L);
        assertThat(merged.cachedInputTokens()).isEqualTo(44L);
        assertThat(merged.estimatedCount()).isEqualTo(3L);
        assertThat(merged.totalCost()).isEqualByComparingTo("3.00");
        assertThat(merged.lastUpdatedAt()).isEqualTo(LATER);
    }

    @Test
    @DisplayName("plus(null) 返回自身，不改动")
    void plusNullSnapshot() {
        Snapshot snapshot = new Snapshot(1L, 2L, 3L, 4L, 5L, 6L, new BigDecimal("7"), EARLIER);
        assertThat(snapshot.plus(null)).isSameAs(snapshot);
    }

    @Test
    @DisplayName("合并时任一侧更新时间为 null 就取另一侧")
    void plusSnapshotNullTimestamps() {
        Snapshot withTime = new Snapshot(1L, 0L, 0L, 0L, 0L, 0L, BigDecimal.ZERO, EARLIER);
        Snapshot noTime = new Snapshot(1L, 0L, 0L, 0L, 0L, 0L, BigDecimal.ZERO, null);
        assertThat(withTime.plus(noTime).lastUpdatedAt()).isEqualTo(EARLIER);
        assertThat(noTime.plus(withTime).lastUpdatedAt()).isEqualTo(EARLIER);
        assertThat(withTime.plus(withTime).lastUpdatedAt()).isEqualTo(EARLIER);
        assertThat(Snapshot.EMPTY.plus(Snapshot.EMPTY).lastUpdatedAt()).isNull();
    }

    @Test
    @DisplayName("快照的六个计数都不允许负值")
    void snapshotRejectsNegativeCounts() {
        assertThatThrownBy(() -> new Snapshot(-1L, 0L, 0L, 0L, 0L, 0L, BigDecimal.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Snapshot(0L, -1L, 0L, 0L, 0L, 0L, BigDecimal.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Snapshot(0L, 0L, -1L, 0L, 0L, 0L, BigDecimal.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Snapshot(0L, 0L, 0L, -1L, 0L, 0L, BigDecimal.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Snapshot(0L, 0L, 0L, 0L, -1L, 0L, BigDecimal.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Snapshot(0L, 0L, 0L, 0L, 0L, -1L, BigDecimal.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("快照 totalCost 为 null 时兜底为零")
    void snapshotNullCost() {
        Snapshot snapshot = new Snapshot(0L, 0L, 0L, 0L, 0L, 0L, null, null);
        assertThat(snapshot.totalCost()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("快照派生指标：除零、精度、可信率、总 token、窗口、耗时")
    void snapshotDerived() {
        assertThat(Snapshot.EMPTY.avgCost()).isEqualByComparingTo("0");
        assertThat(Snapshot.EMPTY.trustworthyRatio()).isEqualTo(0.0d);
        assertThat(Snapshot.EMPTY.totalTokens()).isZero();
        assertThat(Snapshot.EMPTY.window()).isEqualTo(Duration.ZERO);
        assertThat(Snapshot.EMPTY.avgLatencyMillis()).isZero();

        Snapshot snapshot = new Snapshot(3L, 10L, 20L, 30L, 40L, 1L, new BigDecimal("1"), null);
        assertThat(snapshot.avgCost()).isEqualByComparingTo("0.33333333");
        assertThat(snapshot.trustworthyRatio()).isEqualTo(0.6666666666666666d);
        assertThat(snapshot.totalTokens()).isEqualTo(100L);
    }

    @Test
    @DisplayName("快照 avgCost 用 8 位小数 HALF_UP")
    void snapshotAvgCostHalfUp() {
        Snapshot snapshot = new Snapshot(3L, 0L, 0L, 0L, 0L, 0L, new BigDecimal("2"), null);
        assertThat(snapshot.avgCost()).isEqualByComparingTo("0.66666667");
    }
}
