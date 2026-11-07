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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.github.nephets.glasshouse.core.attribution.AttributionTestFixtures.tenantKey;
import static io.github.nephets.glasshouse.core.attribution.AttributionTestFixtures.usage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryMetricStoreEvictionTest {

    private static final Instant FAR_FUTURE = Instant.parse("2099-01-01T00:00:00Z");
    private static final Instant FAR_PAST = Instant.parse("1971-01-01T00:00:00Z");
    private static final long THROTTLE_NANOS = 60L * 1_000_000_000L;

    private static InMemoryMetricStore store(Clock clock, AtomicLong nanos) {
        return new InMemoryMetricStore(clock, Duration.ofHours(1), 2, () -> nanos.get(), 1000);
    }

    private static AttributionQuery byTenant() {
        return AttributionQuery.of(AttributionDimension.TENANT);
    }

    @Test
    @DisplayName("远未来时钟下越过 60 秒节流后过期键被淘汰")
    void futureClockEvictsStaleKeys() {
        AtomicLong nanos = new AtomicLong(0L);
        InMemoryMetricStore store = store(Clock.fixed(FAR_FUTURE, ZoneOffset.UTC), nanos);
        store.increment(tenantKey("tA"), usage(10L, 5L), new BigDecimal("0.01"));
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.aggregate(byTenant())).hasSize(1);
        assertThat(store.size()).isEqualTo(1);

        nanos.set(THROTTLE_NANOS);
        assertThat(store.aggregate(byTenant())).isEmpty();
        assertThat(store.size()).isZero();
    }

    @Test
    @DisplayName("远过去时钟下越过节流也不淘汰任何键")
    void pastClockKeepsKeys() {
        AtomicLong nanos = new AtomicLong(THROTTLE_NANOS);
        InMemoryMetricStore store = store(Clock.fixed(FAR_PAST, ZoneOffset.UTC), nanos);
        store.increment(tenantKey("tA"), usage(10L, 5L), new BigDecimal("0.01"));
        assertThat(store.aggregate(byTenant())).hasSize(1);
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("时钟只领先写入 90 分钟时，2 倍窗口滞后仍保留键")
    void staleFactorTwoKeepsRecentKeys() {
        Instant writeApprox = Instant.now();
        AtomicLong nanos = new AtomicLong(THROTTLE_NANOS);
        Instant fixed = writeApprox.plus(Duration.ofMinutes(90));
        InMemoryMetricStore store = store(Clock.fixed(fixed, ZoneOffset.UTC), nanos);
        store.increment(tenantKey("tA"), usage(10L, 5L), new BigDecimal("0.01"));
        assertThat(store.aggregate(byTenant())).hasSize(1);
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("60 秒节流内多次聚合只扫描一次，重复推进不重复淘汰")
    void throttleAllowsOnlyOneScan() {
        AtomicLong nanos = new AtomicLong(THROTTLE_NANOS);
        InMemoryMetricStore store = store(Clock.fixed(FAR_FUTURE, ZoneOffset.UTC), nanos);
        store.increment(tenantKey("tA"), usage(1L, 1L), BigDecimal.ONE);
        assertThat(store.aggregate(byTenant())).isEmpty();

        store.increment(tenantKey("tB"), usage(1L, 1L), BigDecimal.ONE);
        assertThat(store.aggregate(byTenant())).hasSize(1);
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.aggregate(byTenant()))
                .extracting(row -> row.valueOf(AttributionDimension.TENANT))
                .containsExactly("tB");
    }

    @Test
    @DisplayName("构造器拒绝 null 时钟、非正窗口、小于 1 的滞后倍数与容量")
    void constructorValidation() {
        Clock utc = Clock.systemUTC();
        Duration hour = Duration.ofHours(1);
        assertThatThrownBy(() -> new InMemoryMetricStore(null, hour, 2, () -> 0L, 10))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("clock");
        assertThatThrownBy(() -> new InMemoryMetricStore(utc, null, 2, () -> 0L, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
        assertThatThrownBy(() -> new InMemoryMetricStore(utc, Duration.ZERO, 2, () -> 0L, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
        assertThatThrownBy(
                        () -> new InMemoryMetricStore(utc, Duration.ofSeconds(-1), 2, () -> 0L, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
        assertThatThrownBy(() -> new InMemoryMetricStore(utc, hour, 0, () -> 0L, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("staleFactor");
        assertThatThrownBy(() -> new InMemoryMetricStore(utc, hour, 2, () -> 0L, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxEntries");
        assertThatThrownBy(() -> new InMemoryMetricStore(utc, hour, 2, () -> 0L, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxEntries");
    }

    @Test
    @DisplayName("nanoClock 为 null 时回退到系统纳秒时钟")
    void nullNanoClockFallback() {
        InMemoryMetricStore store =
                new InMemoryMetricStore(
                        Clock.fixed(FAR_PAST, ZoneOffset.UTC), Duration.ofHours(1), 2, null, 10);
        store.increment(tenantKey("tA"), usage(1L, 1L), BigDecimal.ONE);
        assertThat(store.aggregate(byTenant())).hasSize(1);
    }

    @Test
    @DisplayName("多次超过容量上限时 size 保持上限，淘汰数量可观测并累加")
    void capacityEvictionCounters() {
        InMemoryMetricStore store =
                new InMemoryMetricStore(
                        Clock.fixed(FAR_PAST, ZoneOffset.UTC), Duration.ofHours(1), 2, () -> 0L, 3);
        for (int i = 0; i < 5; i++) {
            store.increment(tenantKey("a" + i), usage(1L, 1L), BigDecimal.ONE);
        }
        assertThat(store.size()).isEqualTo(3);
        assertThat(store.evictedCount()).isEqualTo(2L);
        for (int i = 5; i < 9; i++) {
            store.increment(tenantKey("a" + i), usage(1L, 1L), BigDecimal.ONE);
        }
        assertThat(store.size()).isEqualTo(3);
        assertThat(store.evictedCount()).isEqualTo(6L);
    }

    @Test
    @DisplayName("未超过容量时不淘汰，淘汰计数保持零")
    void noCapacityEvictionBelowLimit() {
        InMemoryMetricStore store =
                new InMemoryMetricStore(
                        Clock.fixed(FAR_PAST, ZoneOffset.UTC),
                        Duration.ofHours(1),
                        2,
                        () -> 0L,
                        10);
        for (int i = 0; i < 10; i++) {
            store.increment(tenantKey("k" + i), usage(1L, 1L), BigDecimal.ONE);
        }
        assertThat(store.size()).isEqualTo(10);
        assertThat(store.evictedCount()).isZero();
    }
}
