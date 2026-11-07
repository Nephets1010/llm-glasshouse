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
import java.util.ArrayList;
import java.util.List;

import io.github.nephets.glasshouse.core.audit.AuditEvent;
import io.github.nephets.glasshouse.core.pricing.CostCalculator;
import io.github.nephets.glasshouse.core.pricing.PricingTable;
import io.github.nephets.glasshouse.core.usage.Usage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.github.nephets.glasshouse.core.attribution.AttributionTestFixtures.entry;
import static io.github.nephets.glasshouse.core.attribution.AttributionTestFixtures.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttributionEngineBranchesTest {

    private static final Instant FIXED = Instant.parse("2026-01-01T00:00:00Z");

    private PricingTable pricingTable;
    private List<String> warnings;
    private InMemoryMetricStore store;
    private AttributionEngine engine;

    @BeforeEach
    void setUp() {
        pricingTable =
                new PricingTable(List.of(entry("deepseek", "deepseek-chat", "0.002", "0.0008")));
        warnings = new ArrayList<>();
        store =
                new InMemoryMetricStore(
                        Clock.fixed(FIXED, ZoneOffset.UTC), Duration.ofHours(1), 2, () -> 0L, 1000);
        engine =
                new AttributionEngine(
                        store, pricingTable, CostCalculator.getInstance(), warnings::add);
    }

    private static AuditEvent sampleEvent() {
        return event("vip", "电商", "客服", "v3", 1000L, 1000L);
    }

    @Test
    @DisplayName("构造器拒绝 null 存储、价格表、成本计算器")
    void constructorNullChecks() {
        assertThatThrownBy(() -> new AttributionEngine(null, pricingTable))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("store");
        assertThatThrownBy(() -> new AttributionEngine(store, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("pricingTable");
        assertThatThrownBy(() -> new AttributionEngine(store, pricingTable, null, warnings::add))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("costCalculator");
    }

    @Test
    @DisplayName("默认、两参、四参构造器都能建立，且返回注入的存储与价格表")
    void constructorsAndAccessors() {
        AttributionEngine defaults = new AttributionEngine();
        assertThat(defaults.store()).isInstanceOf(InMemoryMetricStore.class);
        assertThat(defaults.pricingTable()).isNotNull();

        AttributionEngine twoArgs = new AttributionEngine(store, pricingTable);
        assertThat(twoArgs.store()).isSameAs(store);
        assertThat(twoArgs.pricingTable()).isSameAs(pricingTable);

        AttributionEngine fourArgs =
                new AttributionEngine(
                        store, pricingTable, CostCalculator.getInstance(), warnings::add);
        assertThat(fourArgs.store()).isSameAs(store);
        assertThat(fourArgs.pricingTable()).isSameAs(pricingTable);
    }

    @Test
    @DisplayName("告警回调为 null 时缺价格也不抛异常，成本记零")
    void nullWarnSinkIsSafe() {
        AttributionEngine noSink =
                new AttributionEngine(store, pricingTable, CostCalculator.getInstance(), null);
        AuditEvent source = sampleEvent();
        assertThat(noSink.record(source, "openai", "gpt-4o")).isTrue();
        assertThat(noSink.computeCost(source, "openai", "gpt-4o"))
                .isEqualByComparingTo("0.00000000");
    }

    @Test
    @DisplayName("clear 清空存储后 size 归零")
    void clearEmptiesStore() {
        engine.record(sampleEvent(), "deepseek", "deepseek-chat");
        assertThat(engine.store().size()).isEqualTo(1);
        engine.clear();
        assertThat(engine.store().size()).isZero();
        assertThat(engine.report(AttributionDimension.MODEL).rows()).isEmpty();
    }

    @Test
    @DisplayName("注入固定时钟后报表生成时刻等于该时刻")
    void reportGeneratedAtIsFixed() {
        assertThat(engine.reportGeneratedAt(AttributionDimension.TENANT)).isEqualTo(FIXED);
        assertThat(engine.report(AttributionDimension.FEATURE).generatedAt()).isEqualTo(FIXED);
    }

    @Test
    @DisplayName("零 token 事件成本为零且带 8 位精度")
    void computeCostZeroTokens() {
        AuditEvent zero = event("vip", "电商", "客服", "v3", 0L, 0L);
        assertThat(engine.computeCost(zero, "deepseek", "deepseek-chat").toPlainString())
                .isEqualTo("0.00000000");
    }

    @Test
    @DisplayName("价格缺失时成本记零并发出含模型名的告警")
    void computeCostMissingPriceWarns() {
        assertThat(engine.computeCost(sampleEvent(), "openai", "gpt-4o"))
                .isEqualByComparingTo("0.00000000");
        assertThat(warnings).anyMatch(w -> w.contains("价格表中查不到模型") && w.contains("openai/gpt-4o"));
    }

    @Test
    @DisplayName("命中价格时按用量算出成本")
    void computeCostPriced() {
        assertThat(engine.computeCost(sampleEvent(), "deepseek", "deepseek-chat"))
                .isEqualByComparingTo("0.00280000");
    }

    @Test
    @DisplayName("record 成功后 query 能查到对应的分组行")
    void recordThenQuery() {
        assertThat(engine.record(sampleEvent(), "deepseek", "deepseek-chat")).isTrue();
        assertThat(engine.query(AttributionQuery.of(AttributionDimension.TENANT)))
                .extracting(row -> row.valueOf(AttributionDimension.TENANT))
                .containsExactly("vip");
    }

    @Test
    @DisplayName("null 事件返回 false，不产生告警")
    void recordNullReturnsFalse() {
        assertThat(engine.record(null, "deepseek", "deepseek-chat")).isFalse();
        assertThat(warnings).isEmpty();
    }

    @Test
    @DisplayName("存储抛异常时 record 返回 false 并发出可定位的告警")
    void recordFallsBackOnStoreFailure() {
        AttributionEngine broken =
                new AttributionEngine(
                        new FailingMetricStore(),
                        pricingTable,
                        CostCalculator.getInstance(),
                        warnings::add);
        assertThat(broken.record(sampleEvent(), "deepseek", "deepseek-chat")).isFalse();
        assertThat(warnings).anyMatch(w -> w.contains("归因记录失败") && w.contains("存储故障"));
    }

    private static final class FailingMetricStore implements MetricStore {

        @Override
        public void increment(AttributionKey key, Usage usage, BigDecimal cost) {
            throw new IllegalStateException("模拟存储故障");
        }

        @Override
        public List<AttributionRow> aggregate(AttributionQuery query) {
            return List.of();
        }

        @Override
        public AttributionReport report(AttributionQuery query) {
            return AttributionReport.empty(AttributionDimension.MODEL);
        }

        @Override
        public void clear() {}

        @Override
        public int size() {
            return 0;
        }
    }
}
