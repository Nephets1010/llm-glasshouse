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
import java.util.List;

import io.github.nephets.glasshouse.core.usage.Usage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.github.nephets.glasshouse.core.attribution.AttributionTestFixtures.cost;
import static io.github.nephets.glasshouse.core.attribution.AttributionTestFixtures.tenantKey;
import static io.github.nephets.glasshouse.core.attribution.AttributionTestFixtures.usage;
import static org.assertj.core.api.Assertions.assertThat;

class InMemoryMetricStoreAggregationTest {

    private static final Instant FIXED = Instant.parse("2026-01-01T00:00:00Z");

    private static InMemoryMetricStore store() {
        return new InMemoryMetricStore(
                Clock.fixed(FIXED, ZoneOffset.UTC), Duration.ofHours(1), 2, () -> 0L, 1000);
    }

    private static AttributionQuery byTenant() {
        return AttributionQuery.of(AttributionDimension.TENANT);
    }

    @Test
    @DisplayName("aggregate(null) 直接返回空，空存储聚合也为空")
    void aggregateNullAndEmptyStore() {
        InMemoryMetricStore store = store();
        assertThat(store.aggregate(null)).isEmpty();
        assertThat(store.aggregate(byTenant())).isEmpty();
    }

    @Test
    @DisplayName("按租户下钻时把同租户的多个原始键逐字段合并成一行")
    void mergesKeysByDimension() {
        InMemoryMetricStore store = store();
        AttributionKey first = tenantKey("vip").with(AttributionDimension.FEATURE, "f1");
        AttributionKey second = tenantKey("vip").with(AttributionDimension.FEATURE, "f2");
        AttributionKey third = tenantKey("vip").with(AttributionDimension.FEATURE, "f3");
        store.increment(first, usage(1000L, 1000L), cost("0.0028"));
        store.increment(second, usage(1000L, 1000L), cost("0.0028"));
        store.increment(third, Usage.estimated(1000L, 1000L, 0L, 0L), cost("0.0028"));
        assertThat(store.size()).isEqualTo(3);

        List<AttributionRow> rows = store.aggregate(byTenant());
        assertThat(rows).hasSize(1);
        AttributionRow row = rows.get(0);
        assertThat(row.valueOf(AttributionDimension.TENANT)).isEqualTo("vip");
        assertThat(row.callCount()).isEqualTo(3L);
        assertThat(row.inputTokens()).isEqualTo(3000L);
        assertThat(row.outputTokens()).isEqualTo(3000L);
        assertThat(row.estimatedCount()).isEqualTo(1L);
        assertThat(row.totalCost()).isEqualByComparingTo("0.0084");
        assertThat(row.share()).isEqualTo(1.0d);
        assertThat(row.totalTokens()).isEqualTo(6000L);
    }

    @Test
    @DisplayName("行按总成本降序排列")
    void rowsSortedByCostDescending() {
        InMemoryMetricStore store = store();
        store.increment(tenantKey("low"), usage(10L, 5L), cost("0.01"));
        store.increment(tenantKey("high"), usage(10L, 5L), cost("0.09"));
        store.increment(tenantKey("mid"), usage(10L, 5L), cost("0.05"));
        assertThat(store.aggregate(byTenant()))
                .extracting(row -> row.valueOf(AttributionDimension.TENANT))
                .containsExactly("high", "mid", "low");
    }

    @Test
    @DisplayName("同成本的行按分组值字典序升序，与写入顺序无关")
    void tiesBreakByDimensionValue() {
        InMemoryMetricStore store = store();
        store.increment(tenantKey("tB"), usage(1000L, 1000L), cost("0.0028"));
        store.increment(tenantKey("tA"), usage(1000L, 1000L), cost("0.0028"));
        store.increment(tenantKey("tC"), usage(1000L, 1000L), cost("0.0028"));
        assertThat(store.aggregate(byTenant()))
                .extracting(row -> row.valueOf(AttributionDimension.TENANT))
                .containsExactly("tA", "tB", "tC");
    }

    @Test
    @DisplayName("总成本为 0 时每行占比都是 0，不产生 NaN")
    void zeroTotalCostGivesZeroShare() {
        InMemoryMetricStore store = store();
        store.increment(tenantKey("tA"), usage(10L, 5L), BigDecimal.ZERO);
        store.increment(tenantKey("tB"), usage(10L, 5L), BigDecimal.ZERO);
        List<AttributionRow> rows = store.aggregate(byTenant());
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> assertThat(row.share()).isEqualTo(0.0d));
    }

    @Test
    @DisplayName("占比被夹在 0 到 1 之间，负成本行不产生负占比")
    void shareIsClamped() {
        InMemoryMetricStore store = store();
        store.increment(tenantKey("neg"), usage(10L, 5L), cost("-1"));
        store.increment(tenantKey("pos"), usage(10L, 5L), cost("3"));
        List<AttributionRow> rows = store.aggregate(byTenant());
        assertThat(rows)
                .extracting(row -> row.valueOf(AttributionDimension.TENANT))
                .containsExactly("pos", "neg");
        assertThat(rows.get(0).share()).isEqualTo(1.0d);
        assertThat(rows.get(1).share()).isEqualTo(0.0d);
    }

    @Test
    @DisplayName("limit 截断保留成本最高的三行")
    void limitKeepsTopRows() {
        InMemoryMetricStore store = store();
        for (int i = 0; i < 6; i++) {
            store.increment(
                    tenantKey("t" + i),
                    usage(10L, 5L),
                    new BigDecimal("0.01").multiply(BigDecimal.valueOf(i + 1)));
        }
        assertThat(store.aggregate(byTenant())).hasSize(6);
        assertThat(store.aggregate(byTenant().withLimit(3)))
                .extracting(row -> row.valueOf(AttributionDimension.TENANT))
                .containsExactly("t5", "t4", "t3");
    }

    @Test
    @DisplayName("aggregate 按查询过滤条件只合并命中的键")
    void aggregateAppliesQueryFilter() {
        InMemoryMetricStore store = store();
        store.increment(tenantKey("vip"), usage(10L, 5L), cost("0.05"));
        store.increment(tenantKey("standard"), usage(10L, 5L), cost("0.01"));
        AttributionQuery query =
                new AttributionQuery(
                        AttributionDimension.TENANT,
                        "vip",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        100);
        List<AttributionRow> rows = store.aggregate(query);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).valueOf(AttributionDimension.TENANT)).isEqualTo("vip");
        assertThat(rows.get(0).totalCost()).isEqualByComparingTo("0.05");
    }

    @Test
    @DisplayName("报表五个总量字段分别逐行累加，生成时刻来自注入时钟")
    void reportAccumulatesAllTotals() {
        InMemoryMetricStore store = store();
        for (int i = 0; i < 3; i++) {
            store.increment(tenantKey("heavy"), Usage.of(100L, 50L, 10L, 0L), cost("0.01"));
        }
        store.increment(tenantKey("light"), Usage.of(7L, 3L, 1L, 0L), cost("0.01"));
        AttributionReport report = store.report(byTenant());
        assertThat(report.rows()).hasSize(2);
        assertThat(report.totalCalls()).isEqualTo(4L);
        assertThat(report.totalInputTokens()).isEqualTo(307L);
        assertThat(report.totalOutputTokens()).isEqualTo(153L);
        assertThat(report.totalReasoningTokens()).isEqualTo(31L);
        assertThat(report.totalCost()).isEqualByComparingTo("0.04");
        assertThat(report.dimension()).isEqualTo(AttributionDimension.TENANT);
        assertThat(report.generatedAt()).isEqualTo(FIXED);
    }

    @Test
    @DisplayName("空存储的报表总量全为零，生成时刻仍来自注入时钟")
    void reportOnEmptyStore() {
        InMemoryMetricStore store = store();
        AttributionReport report = store.report(byTenant());
        assertThat(report.rows()).isEmpty();
        assertThat(report.totalCalls()).isZero();
        assertThat(report.totalCost()).isEqualByComparingTo("0");
        assertThat(report.generatedAt()).isEqualTo(FIXED);
        assertThat(report.trustworthyRatio()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("increment 忽略 null 键、null 用量、null 成本")
    void incrementIgnoresNulls() {
        InMemoryMetricStore store = store();
        store.increment(null, usage(1L, 1L), BigDecimal.ONE);
        store.increment(tenantKey("t"), null, BigDecimal.ONE);
        store.increment(tenantKey("t"), usage(1L, 1L), null);
        assertThat(store.size()).isZero();
        assertThat(store.operationCount()).isZero();
        store.increment(tenantKey("t"), usage(1L, 1L), BigDecimal.ONE);
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.operationCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("clear 清空键，size 与 operationCount 分开统计")
    void clearAndCounters() {
        InMemoryMetricStore store = store();
        store.increment(tenantKey("a"), usage(1L, 1L), BigDecimal.ONE);
        store.increment(tenantKey("a"), usage(1L, 1L), BigDecimal.ONE);
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.operationCount()).isEqualTo(2L);
        store.clear();
        assertThat(store.size()).isZero();
        assertThat(store.operationCount()).isEqualTo(2L);
        assertThat(store.aggregate(byTenant())).isEmpty();
    }

    @Test
    @DisplayName("默认构造给出默认窗口与容量上限")
    void defaults() {
        InMemoryMetricStore store = new InMemoryMetricStore();
        assertThat(store.window()).isEqualTo(Duration.ofHours(1));
        assertThat(store.maxEntries()).isEqualTo(100_000);
        assertThat(store.evictedCount()).isZero();
    }
}
