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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.nephets.glasshouse.core.usage.UsageSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttributionReportTest {

    private static final Instant FIXED = Instant.parse("2026-01-01T00:00:00Z");

    private static AttributionRow row(long calls, long estimated) {
        return new AttributionRow(
                AttributionKey.empty(), calls, 0L, 0L, 0L, 0L, estimated, BigDecimal.ZERO, 0.0d);
    }

    @Test
    @DisplayName("空报表的可信率为 1.0，没有不可信来源，也没有首行")
    void emptyReportDefaults() {
        AttributionReport report = AttributionReport.empty(AttributionDimension.TENANT);
        assertThat(report.rows()).isEmpty();
        assertThat(report.totalCalls()).isZero();
        assertThat(report.dimension()).isEqualTo(AttributionDimension.TENANT);
        assertThat(report.totalCost()).isEqualByComparingTo("0");
        assertThat(report.totalTokens()).isZero();
        assertThat(report.trustworthyRatio()).isEqualTo(1.0d);
        assertThat(report.untrustworthySources()).isEmpty();
        assertThat(report.topRow()).isEmpty();
    }

    @Test
    @DisplayName("构造时 rows、totalCost、dimension、generatedAt 为 null 都有兜底")
    void nullFieldsDefaulted() {
        AttributionReport report = new AttributionReport(null, null, 0L, 0L, 0L, 0L, null, null);
        assertThat(report.dimension()).isEqualTo(AttributionDimension.MODEL);
        assertThat(report.rows()).isEmpty();
        assertThat(report.totalCost()).isEqualByComparingTo("0");
        assertThat(report.generatedAt()).isNotNull();
        assertThat(report.trustworthyRatio()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("构造后 rows 是拷贝，改动原列表不影响报表且不可追加")
    void rowsAreCopied() {
        List<AttributionRow> rows = new ArrayList<>();
        rows.add(row(1L, 0L));
        AttributionReport report =
                new AttributionReport(
                        AttributionDimension.TENANT, rows, 1L, 0L, 0L, 0L, BigDecimal.ZERO, FIXED);
        rows.clear();
        assertThat(report.rows()).hasSize(1);
        assertThatThrownBy(() -> report.rows().add(row(1L, 0L)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("总成本为负直接拒绝")
    void negativeTotalCostRejected() {
        assertThatThrownBy(
                        () ->
                                new AttributionReport(
                                        AttributionDimension.TENANT,
                                        List.of(),
                                        0L,
                                        0L,
                                        0L,
                                        0L,
                                        new BigDecimal("-1"),
                                        FIXED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("totalCost");
    }

    @Test
    @DisplayName("报表总 token 只算输入、输出、推理三项")
    void reportTotalTokensExcludesCached() {
        AttributionReport report =
                new AttributionReport(
                        AttributionDimension.TENANT,
                        List.of(),
                        1L,
                        10L,
                        20L,
                        30L,
                        BigDecimal.ZERO,
                        FIXED);
        assertThat(report.totalTokens()).isEqualTo(60L);
    }

    @Test
    @DisplayName("可信率按逐行调用数作分母，不按报表 totalCalls")
    void trustworthyRatioUsesRowCalls() {
        AttributionReport report =
                new AttributionReport(
                        AttributionDimension.TENANT,
                        List.of(row(4L, 1L)),
                        100L,
                        0L,
                        0L,
                        0L,
                        new BigDecimal("0.0154"),
                        FIXED);
        assertThat(report.trustworthyRatio()).isEqualTo(0.75d);
        assertThat(report.untrustworthySources())
                .containsExactly(UsageSource.ESTIMATED, UsageSource.FALLBACK);
        assertThat(report.topRow()).isPresent();
        assertThat(report.topRow().orElseThrow()).isSameAs(report.rows().get(0));
    }

    @Test
    @DisplayName("逐行调用数全为 0 时可信率回到 1.0，不可信来源为空")
    void trustworthyRatioWhenNoRowCalls() {
        AttributionReport report =
                new AttributionReport(
                        AttributionDimension.TENANT,
                        List.of(row(0L, 0L)),
                        5L,
                        0L,
                        0L,
                        0L,
                        BigDecimal.ZERO,
                        FIXED);
        assertThat(report.trustworthyRatio()).isEqualTo(1.0d);
        assertThat(report.untrustworthySources()).isEmpty();
    }

    @Test
    @DisplayName("全部可信时不可信来源为空，topRow 取首行")
    void allTrustworthy() {
        AttributionRow first = row(3L, 0L);
        AttributionRow second = row(2L, 0L);
        AttributionReport report =
                new AttributionReport(
                        AttributionDimension.MODEL,
                        List.of(first, second),
                        5L,
                        0L,
                        0L,
                        0L,
                        BigDecimal.ZERO,
                        FIXED);
        assertThat(report.trustworthyRatio()).isEqualTo(1.0d);
        assertThat(report.untrustworthySources()).isEmpty();
        assertThat(report.topRow().orElseThrow()).isSameAs(first);
    }

    @Test
    @DisplayName("摘要整串固定：中文维度名、行数、调用数、四位成本、四位可信率")
    void summaryIsFixedFormat() {
        AttributionReport report =
                new AttributionReport(
                        AttributionDimension.TENANT,
                        List.of(row(4L, 1L)),
                        4L,
                        0L,
                        0L,
                        0L,
                        new BigDecimal("0.0154"),
                        FIXED);
        assertThat(report.summary())
                .isEqualTo(
                        "dimension=租户, rows=1, calls=4, totalCostCny=0.0154,"
                                + " trustworthy=0.7500");
    }

    @Test
    @DisplayName("展示成本按四位 HALF_UP 舍入")
    void summaryRoundsHalfUp() {
        AttributionReport report =
                new AttributionReport(
                        AttributionDimension.MODEL,
                        List.of(),
                        0L,
                        0L,
                        0L,
                        0L,
                        new BigDecimal("0.01545"),
                        FIXED);
        assertThat(report.summary()).contains("totalCostCny=0.0155");
    }

    @Test
    @DisplayName("切换默认语言到法语后摘要仍用小数点点号")
    void summaryIgnoresDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.FRANCE);
            AttributionReport report =
                    new AttributionReport(
                            AttributionDimension.TENANT,
                            List.of(row(4L, 1L)),
                            4L,
                            0L,
                            0L,
                            0L,
                            new BigDecimal("0.0154"),
                            FIXED);
            assertThat(report.summary()).contains("trustworthy=0.7500");
            assertThat(report.summary()).doesNotContain("0,7500");
        } finally {
            Locale.setDefault(previous);
        }
    }
}
