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
import java.util.Map;

import io.github.nephets.glasshouse.core.pricing.CostCalculator;
import io.github.nephets.glasshouse.core.pricing.PricingTable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.github.nephets.glasshouse.core.attribution.AttributionTestFixtures.cost;
import static io.github.nephets.glasshouse.core.attribution.AttributionTestFixtures.key;
import static io.github.nephets.glasshouse.core.attribution.AttributionTestFixtures.tenantKey;
import static io.github.nephets.glasshouse.core.attribution.AttributionTestFixtures.usage;
import static org.assertj.core.api.Assertions.assertThat;

class AttributionDrillDownRegressionTest {

    private static final Instant FIXED = Instant.parse("2026-01-01T00:00:00Z");

    private static final String[][] KEYS = {
        {"pA", "mA", "tA", "bA", "fA", "vA", "sA"},
        {"pA", "mA", "tB", "bA", "fA", "vA", "sA"},
        {"pA", "mB", "tB", "bB", "fB", "vB", "sB"},
        {"pA", "mB", "tC", "bB", "fB", "vB", "sB"}
    };
    private static final String[] COSTS = {"0.04", "0.03", "0.02", "0.01"};

    private Map<AttributionDimension, AttributionReport> all;

    @BeforeEach
    void setUp() {
        InMemoryMetricStore store =
                new InMemoryMetricStore(
                        Clock.fixed(FIXED, ZoneOffset.UTC), Duration.ofHours(1), 2, () -> 0L, 1000);
        for (int i = 0; i < KEYS.length; i++) {
            String[] k = KEYS[i];
            store.increment(
                    key(k[0], k[1], k[2], k[3], k[4], k[5], k[6]),
                    usage(100L, 50L),
                    cost(COSTS[i]));
        }
        AttributionEngine engine =
                new AttributionEngine(
                        store, new PricingTable(), CostCalculator.getInstance(), msg -> {});
        all = engine.reportAllDimensions(AttributionQuery.all());
    }

    private static void assertRow(
            AttributionRow row,
            AttributionDimension dimension,
            String value,
            long calls,
            String totalCost) {
        assertThat(row.valueOf(dimension)).isEqualTo(value);
        assertThat(row.callCount()).isEqualTo(calls);
        assertThat(row.totalCost()).isEqualByComparingTo(totalCost);
    }

    @Test
    @DisplayName("按供应商下钻：4 个键合并成 1 行，4 次调用 0.10 元")
    void providerRows() {
        AttributionReport report = all.get(AttributionDimension.PROVIDER);
        assertThat(report.dimension()).isEqualTo(AttributionDimension.PROVIDER);
        assertThat(report.rows()).hasSize(1);
        assertThat(report.rows())
                .extracting(row -> row.valueOf(AttributionDimension.PROVIDER))
                .containsExactly("pA");
        assertRow(report.rows().get(0), AttributionDimension.PROVIDER, "pA", 4L, "0.10");
        assertThat(report.totalCalls()).isEqualTo(4L);
    }

    @Test
    @DisplayName("按模型下钻：2 行，mA 两次 0.07 元、mB 两次 0.03 元")
    void modelRows() {
        AttributionReport report = all.get(AttributionDimension.MODEL);
        assertThat(report.dimension()).isEqualTo(AttributionDimension.MODEL);
        assertThat(report.rows()).hasSize(2);
        assertThat(report.rows())
                .extracting(row -> row.valueOf(AttributionDimension.MODEL))
                .containsExactly("mA", "mB");
        assertRow(report.rows().get(0), AttributionDimension.MODEL, "mA", 2L, "0.07");
        assertRow(report.rows().get(1), AttributionDimension.MODEL, "mB", 2L, "0.03");
    }

    @Test
    @DisplayName("按租户下钻：3 行，成本降序 tB/tA/tC，逐行次数与金额固定")
    void tenantRows() {
        AttributionReport report = all.get(AttributionDimension.TENANT);
        assertThat(report.dimension()).isEqualTo(AttributionDimension.TENANT);
        assertThat(report.rows()).hasSize(3);
        assertThat(report.rows())
                .extracting(row -> row.valueOf(AttributionDimension.TENANT))
                .containsExactly("tB", "tA", "tC");
        assertRow(report.rows().get(0), AttributionDimension.TENANT, "tB", 2L, "0.05");
        assertRow(report.rows().get(1), AttributionDimension.TENANT, "tA", 1L, "0.04");
        assertRow(report.rows().get(2), AttributionDimension.TENANT, "tC", 1L, "0.01");
    }

    @Test
    @DisplayName("按业务线下钻：2 行，bA 两次 0.07 元、bB 两次 0.03 元")
    void bizLineRows() {
        AttributionReport report = all.get(AttributionDimension.BIZ_LINE);
        assertThat(report.dimension()).isEqualTo(AttributionDimension.BIZ_LINE);
        assertThat(report.rows()).hasSize(2);
        assertThat(report.rows())
                .extracting(row -> row.valueOf(AttributionDimension.BIZ_LINE))
                .containsExactly("bA", "bB");
        assertRow(report.rows().get(0), AttributionDimension.BIZ_LINE, "bA", 2L, "0.07");
        assertRow(report.rows().get(1), AttributionDimension.BIZ_LINE, "bB", 2L, "0.03");
    }

    @Test
    @DisplayName("按业务功能下钻：2 行，fA 两次 0.07 元、fB 两次 0.03 元")
    void featureRows() {
        AttributionReport report = all.get(AttributionDimension.FEATURE);
        assertThat(report.dimension()).isEqualTo(AttributionDimension.FEATURE);
        assertThat(report.rows()).hasSize(2);
        assertThat(report.rows())
                .extracting(row -> row.valueOf(AttributionDimension.FEATURE))
                .containsExactly("fA", "fB");
        assertRow(report.rows().get(0), AttributionDimension.FEATURE, "fA", 2L, "0.07");
        assertRow(report.rows().get(1), AttributionDimension.FEATURE, "fB", 2L, "0.03");
    }

    @Test
    @DisplayName("按 Prompt 版本下钻：2 行，vA 两次 0.07 元、vB 两次 0.03 元")
    void promptVersionRows() {
        AttributionReport report = all.get(AttributionDimension.PROMPT_VERSION);
        assertThat(report.dimension()).isEqualTo(AttributionDimension.PROMPT_VERSION);
        assertThat(report.rows()).hasSize(2);
        assertThat(report.rows())
                .extracting(row -> row.valueOf(AttributionDimension.PROMPT_VERSION))
                .containsExactly("vA", "vB");
        assertRow(report.rows().get(0), AttributionDimension.PROMPT_VERSION, "vA", 2L, "0.07");
        assertRow(report.rows().get(1), AttributionDimension.PROMPT_VERSION, "vB", 2L, "0.03");
    }

    @Test
    @DisplayName("按 Agent 步骤下钻：2 行，sA 两次 0.07 元、sB 两次 0.03 元")
    void agentStepRows() {
        AttributionReport report = all.get(AttributionDimension.AGENT_STEP);
        assertThat(report.dimension()).isEqualTo(AttributionDimension.AGENT_STEP);
        assertThat(report.rows()).hasSize(2);
        assertThat(report.rows())
                .extracting(row -> row.valueOf(AttributionDimension.AGENT_STEP))
                .containsExactly("sA", "sB");
        assertRow(report.rows().get(0), AttributionDimension.AGENT_STEP, "sA", 2L, "0.07");
        assertRow(report.rows().get(1), AttributionDimension.AGENT_STEP, "sB", 2L, "0.03");
    }

    @Test
    @DisplayName("withLimit(3) 保留成本最高的三行，而非任意三行")
    void limitKeepsThreeMostExpensiveTenants() {
        InMemoryMetricStore store =
                new InMemoryMetricStore(
                        Clock.fixed(FIXED, ZoneOffset.UTC), Duration.ofHours(1), 2, () -> 0L, 1000);
        for (int i = 0; i < 10; i++) {
            store.increment(
                    tenantKey("tenant-" + i),
                    usage(100L, 50L),
                    new BigDecimal("0.01").multiply(BigDecimal.valueOf(i + 1)));
        }
        AttributionEngine engine =
                new AttributionEngine(
                        store, new PricingTable(), CostCalculator.getInstance(), msg -> {});
        List<AttributionRow> rows =
                engine.query(AttributionQuery.of(AttributionDimension.TENANT).withLimit(3));
        assertThat(rows).hasSize(3);
        assertThat(rows)
                .extracting(row -> row.valueOf(AttributionDimension.TENANT))
                .containsExactly("tenant-9", "tenant-8", "tenant-7");
        assertRow(rows.get(0), AttributionDimension.TENANT, "tenant-9", 1L, "0.10");
        assertRow(rows.get(1), AttributionDimension.TENANT, "tenant-8", 1L, "0.09");
        assertRow(rows.get(2), AttributionDimension.TENANT, "tenant-7", 1L, "0.08");
    }
}
