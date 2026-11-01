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

package io.github.nephets.glasshouse.starter.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import io.github.nephets.glasshouse.core.attribution.AttributionDimension;
import io.github.nephets.glasshouse.core.attribution.AttributionEngine;
import io.github.nephets.glasshouse.core.attribution.AttributionReport;
import io.github.nephets.glasshouse.core.attribution.InMemoryMetricStore;
import io.github.nephets.glasshouse.core.pricing.PriceEntry;
import io.github.nephets.glasshouse.core.pricing.PriceSource;
import io.github.nephets.glasshouse.core.pricing.PricingTable;
import io.github.nephets.glasshouse.starter.store.SpanRingBuffer;
import io.github.nephets.glasshouse.starter.store.SpanTreeQuery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GlasshouseTraceController} 的纯 POJO 单测。
 *
 * <p>不起 Spring 容器：控制器本身只是「三个协作者 + 几个 {@code Map.of}」，它的行为与 MVC 绑定无关；起容器反而会被 {@code
 * ConditionalOnWebApplication} 挡掉，也让用例慢一个数量级。
 *
 * <p>{@code Map.of} 的迭代序不确定，因此所有断言都按键逐个取，不做有序断言。
 */
class GlasshouseTraceControllerTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    /** 三个 traceId 按「写入顺序」是 ffff → 0001 → aaaa，用来验证输出与其不同。 */
    private static final String TRACE_ID_F = "ffffffffffffffffffffffffffffffff";

    private static final String TRACE_ID_Z = "00000000000000000000000000000001";
    private static final String TRACE_ID_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final Instant START = Instant.parse("2026-10-09T12:00:00Z");

    private SpanRingBuffer buffer;
    private SpanTreeQuery query;
    private GlasshouseTraceController controller;

    @BeforeEach
    void setUp() {
        buffer = new SpanRingBuffer(10);
        query = new SpanTreeQuery(buffer);
        controller = new GlasshouseTraceController(query, buffer, new AttributionEngine());
    }

    /**
     * 往缓冲里写一条 span。
     *
     * @param traceId traceId
     * @param spanId spanId
     * @param parentId 父 spanId
     * @param cost 成本；为 null 时不写成本与 token 属性
     */
    private void append(String traceId, String spanId, String parentId, Double cost) {
        Map<String, Object> attributes =
                cost == null
                        ? Map.of()
                        : Map.of(
                                "swy.cost.amount",
                                cost,
                                "gen_ai.usage.input_tokens",
                                1200L,
                                "gen_ai.usage.output_tokens",
                                340L);
        buffer.append(
                new SpanRingBuffer.SpanData(
                        traceId,
                        spanId,
                        parentId,
                        "chat deepseek-chat",
                        "chat",
                        "deepseek-chat",
                        START,
                        START.plusMillis(90L),
                        attributes,
                        "OK",
                        null));
    }

    @Nested
    @DisplayName("/_glasshouse/trace/{traceId}")
    class TraceEndpoint {

        @Test
        @DisplayName("★ 命中时五个键齐全：traceId / tree / spans / totalCostCny / tokens")
        void returnsAllFiveKeys() {
            append(TRACE_ID, "aaaaaaaaaaaaaaaa", null, 0.008d);

            Map<String, Object> result = controller.trace(TRACE_ID, true);

            assertThat(result)
                    .as("端点必须一次返回「链路 + 树 + 成本 + token」，否则排查要来回切接口")
                    .containsOnlyKeys("traceId", "tree", "spans", "totalCostCny", "tokens");
            assertThat(result.get("traceId")).isEqualTo(TRACE_ID);
            assertThat(result.get("totalCostCny")).isEqualTo("0.0080");
            assertThat((List<?>) result.get("spans")).hasSize(1);

            assertThat(result.get("tokens")).isInstanceOf(SpanTreeQuery.TokenSummary.class);
            SpanTreeQuery.TokenSummary tokens = (SpanTreeQuery.TokenSummary) result.get("tokens");
            assertThat(tokens.inputTokens()).isEqualTo(1200L);
            assertThat(tokens.outputTokens()).isEqualTo(340L);
            assertThat(tokens.modelCalls()).isEqualTo(1L);

            assertThat(result.get("tree").toString())
                    .as("★ 树文本必须同时回答「慢在哪」与「花了多少」")
                    .contains("slowestSpan=")
                    .contains("totalCostCny=");
        }

        @Test
        @DisplayName("cost=false 时不显示 ¥ 列，键不变")
        void worksWithoutCost() {
            append(TRACE_ID, "aaaaaaaaaaaaaaaa", null, 0.008d);

            Map<String, Object> result = controller.trace(TRACE_ID, false);

            assertThat(result)
                    .containsOnlyKeys("traceId", "tree", "spans", "totalCostCny", "tokens");
            assertThat(result.get("tree").toString()).doesNotContain("¥");
        }

        @Test
        @DisplayName("未命中时 spans 为空、成本为 0，并给出可操作提示")
        void missIsEmpty() {
            Map<String, Object> result = controller.trace("0".repeat(32), true);

            assertThat((List<?>) result.get("spans")).isEmpty();
            assertThat(result.get("totalCostCny")).isEqualTo("0.0000");
            assertThat(result.get("tree").toString())
                    .as("缓冲是环形的，老 trace 查不到是常态，必须给出提示而不是空白")
                    .contains("未找到");
        }
    }

    @Nested
    @DisplayName("/_glasshouse/traces")
    class TracesEndpoint {

        @Test
        @DisplayName("★ 返回 traceId 升序（实现是 .sorted()，不是「最近出现顺序」）")
        void sortedAscending() {
            append(TRACE_ID_F, "aaaaaaaaaaaaaaaa", null, null);
            append(TRACE_ID_Z, "bbbbbbbbbbbbbbbb", null, null);
            append(TRACE_ID_A, "cccccccccccccccc", null, null);

            Map<String, Object> result = controller.traces(20);

            assertThat((List<String>) result.get("traceIds"))
                    .as("★ 114 行紧跟 .sorted()，输出是字典升序；" + "Javadoc 写的「最近出现顺序」与实现不符，以实现为准")
                    .containsExactly(TRACE_ID_Z, TRACE_ID_A, TRACE_ID_F);
            assertThat(result.get("spanCount")).isEqualTo(3);
        }

        @ParameterizedTest(name = "limit={0} 被夹成 1 条")
        @ValueSource(ints = {0, -5})
        @DisplayName("非正 limit 被 Math.max 夹成 1 条")
        void nonPositiveLimitClampedToOne(int limit) {
            append(TRACE_ID_F, "aaaaaaaaaaaaaaaa", null, null);
            append(TRACE_ID_Z, "bbbbbbbbbbbbbbbb", null, null);

            Map<String, Object> result = controller.traces(limit);

            assertThat((List<String>) result.get("traceIds"))
                    .as("limit 为 0 或负数时退化成 1 条，不能返回空列表")
                    .hasSize(1);
            assertThat(result.get("spanCount")).isEqualTo(2);
        }

        @Test
        @DisplayName("限制条数时只返回升序后的前 N 条")
        void limitTruncates() {
            append(TRACE_ID_F, "aaaaaaaaaaaaaaaa", null, null);
            append(TRACE_ID_Z, "bbbbbbbbbbbbbbbb", null, null);
            append(TRACE_ID_A, "cccccccccccccccc", null, null);

            assertThat((List<String>) controller.traces(2).get("traceIds"))
                    .containsExactly(TRACE_ID_Z, TRACE_ID_A);
        }
    }

    @Nested
    @DisplayName("/_glasshouse/cost")
    class CostEndpoint {

        @Test
        @DisplayName("合法维度透传给归因引擎，报表带上该维度")
        void delegatesToEngine() {
            AttributionReport report = controller.cost("tenant");

            assertThat(report).isNotNull();
            assertThat(report.dimension()).isEqualTo(AttributionDimension.TENANT);
            assertThat(report.rows()).as("未记录任何事件时是空报表").isEmpty();
            assertThat(report.totalCost())
                    .usingComparator(BigDecimal::compareTo)
                    .isEqualTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("★ 未知维度返回 MODEL 空报表，不抛异常也不返回 null")
        void unknownDimensionReturnsEmptyModelReport() {
            assertThat(AttributionDimension.from("bogus")).isNull();

            AttributionReport report = controller.cost("bogus");

            assertThat(report).as("★ 接口不该 500，也不该返回 null；返回空报表并在日志里 warn 维度名写错了").isNotNull();
            assertThat(report.dimension()).isEqualTo(AttributionDimension.MODEL);
            assertThat(report.rows()).isEmpty();
            assertThat(report.totalCost())
                    .usingComparator(BigDecimal::compareTo)
                    .isEqualTo(BigDecimal.ZERO);
            assertThat(report.totalCalls()).isZero();
        }
    }

    @Nested
    @DisplayName("/_glasshouse/info 与 /_glasshouse/dimensions")
    class MetaEndpoints {

        @Test
        @DisplayName("info 逐键暴露版本、semconv、缓冲与价格表状态")
        void infoExposesSelfCheck() {
            append(TRACE_ID, "aaaaaaaaaaaaaaaa", null, null);

            Map<String, Object> info = controller.info();

            assertThat(info)
                    .containsOnlyKeys(
                            "name",
                            "version",
                            "semconvVersion",
                            "spanBufferCapacity",
                            "spansBuffered",
                            "spansDropped",
                            "priceEntries");
            assertThat(info.get("name")).isEqualTo("llm-glasshouse");
            assertThat(info.get("version")).isEqualTo("0.1.0");
            assertThat(info.get("semconvVersion")).isEqualTo("1.41.0");
            assertThat(info.get("spanBufferCapacity")).isEqualTo(10);
            assertThat(info.get("spansBuffered")).isEqualTo(1);
            assertThat(info.get("spansDropped")).isEqualTo(0L);
            assertThat(info.get("priceEntries"))
                    .as("默认价格表是空的，端点必须如实暴露，否则「成本全是 0」无从排查")
                    .isEqualTo(0);
        }

        @Test
        @DisplayName("info 的 priceEntries 随价格表条目数变化")
        void infoPriceEntriesFollowsPricingTable() {
            PricingTable prices =
                    new PricingTable(
                            List.of(
                                    PriceEntry.of(
                                            "deepseek",
                                            "deepseek-chat",
                                            new BigDecimal("0.0010"),
                                            new BigDecimal("0.0020"),
                                            LocalDate.of(2026, 1, 1),
                                            PriceSource.OFFICIAL)));
            GlasshouseTraceController withPrices =
                    new GlasshouseTraceController(
                            query,
                            buffer,
                            new AttributionEngine(new InMemoryMetricStore(), prices));

            assertThat(withPrices.info().get("priceEntries"))
                    .as("装了 1 条价格就必须是 1，端点要能反映价格表到底加载了几条")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("dimensions 返回全部维度的列名，供 CLI 与前端做下拉框")
        void dimensionsReturnsColumnNames() {
            List<String> dims = controller.dimensions();

            assertThat(dims)
                    .containsExactlyElementsOf(
                            AttributionDimension.all().stream()
                                    .map(AttributionDimension::columnName)
                                    .toList());
            assertThat(dims).contains("tenant", "feature", "model");
        }
    }
}
