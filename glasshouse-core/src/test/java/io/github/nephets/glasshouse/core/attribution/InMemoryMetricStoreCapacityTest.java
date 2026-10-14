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
import java.time.Clock;
import java.time.Duration;

import io.github.nephets.glasshouse.core.usage.Usage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link InMemoryMetricStore} 容量上限回归测试。
 *
 * <p>滑动窗口淘汰只能回收「不再被写入」的键，挡不住高基数维度值在窗口内的持续膨胀 （例如把 traceId 写进 tenant 维度）。容量上限是这条路径上唯一的兜底，
 * 因此必须有测试锁定「超限一定被淘汰」而不是「大概会被淘汰」。
 */
class InMemoryMetricStoreCapacityTest {

    private static final Usage USAGE = Usage.of(10L, 5L);
    private static final BigDecimal COST = new BigDecimal("0.01");

    private static AttributionKey tenantKey(String tenant) {
        return AttributionKey.empty().with(AttributionDimension.TENANT, tenant);
    }

    private static InMemoryMetricStore storeWithCapacity(int capacity) {
        return new InMemoryMetricStore(
                Clock.systemUTC(), Duration.ofHours(1), 2, System::nanoTime, capacity);
    }

    @Test
    @DisplayName("默认构造必须带容量上限，而不是无界")
    void defaultCapacityIsBounded() {
        assertThat(new InMemoryMetricStore().maxEntries())
                .isEqualTo(InMemoryMetricStore.DEFAULT_MAX_ENTRIES);
        assertThat(InMemoryMetricStore.DEFAULT_MAX_ENTRIES).isPositive();
    }

    @Test
    @DisplayName("未超限时不得淘汰任何键")
    void noEvictionBelowCapacity() {
        InMemoryMetricStore store = storeWithCapacity(100);
        for (int i = 0; i < 50; i++) {
            store.increment(tenantKey("t" + i), USAGE, COST);
        }
        assertThat(store.size()).isEqualTo(50);
        assertThat(store.evictedCount()).isZero();
    }

    @Test
    @DisplayName("超过容量上限时必须淘汰到上限以内，且被淘汰数量可观测")
    void overflowIsEvictedAndObservable() {
        InMemoryMetricStore store = storeWithCapacity(8);
        for (int i = 0; i < 20; i++) {
            store.increment(tenantKey("t" + i), USAGE, COST);
        }
        assertThat(store.size()).isEqualTo(8);
        assertThat(store.evictedCount()).isEqualTo(12L);
    }

    @Test
    @DisplayName("淘汰保留最近写入的键，最久未更新的键先被淘汰")
    void evictionKeepsMostRecentlyUpdated() {
        InMemoryMetricStore store = storeWithCapacity(4);
        for (int i = 0; i < 4; i++) {
            store.increment(tenantKey("t" + i), USAGE, COST);
        }
        assertThat(store.size()).isEqualTo(4);

        // 再写入 4 个新键：容量只有 4，先写入的 t0..t3 必须被淘汰
        for (int i = 4; i < 8; i++) {
            store.increment(tenantKey("t" + i), USAGE, COST);
        }

        assertThat(store.size()).isEqualTo(4);
        assertThat(store.evictedCount()).isEqualTo(4L);
        assertThat(store.aggregate(AttributionQuery.of(AttributionDimension.TENANT)))
                .extracting(row -> row.valueOf(AttributionDimension.TENANT))
                .containsExactlyInAnyOrder("t4", "t5", "t6", "t7");
    }

    @Test
    @DisplayName("容量上限必须为正，0 或负数直接拒绝")
    void maxEntriesMustBePositive() {
        assertThatThrownBy(() -> storeWithCapacity(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxEntries");
        assertThatThrownBy(() -> storeWithCapacity(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
