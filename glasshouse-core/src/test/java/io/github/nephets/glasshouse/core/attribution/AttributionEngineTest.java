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
import java.util.Map;

import io.github.nephets.glasshouse.core.audit.AuditEvent;
import io.github.nephets.glasshouse.core.audit.EventType;
import io.github.nephets.glasshouse.core.audit.PayloadRedactor;
import io.github.nephets.glasshouse.core.pricing.CostCalculator;
import io.github.nephets.glasshouse.core.pricing.PriceEntry;
import io.github.nephets.glasshouse.core.pricing.PriceSource;
import io.github.nephets.glasshouse.core.pricing.PricingTable;
import io.github.nephets.glasshouse.core.pricing.PricingTableLoader;
import io.github.nephets.glasshouse.core.usage.Usage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 五维成本归因测试（PRD 仓库③ P0-3：任一维度均可下钻）。
 *
 * <p>用两个租户 × 两个功能 × 两个 Prompt 版本的构造流量，验证<b>每一个维度</b>都能独立下钻， 且行数与金额都正确。这是「说得清」这个卖点的可执行证明。
 */
class AttributionEngineTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";
    private static final Instant WHEN = Instant.parse("2026-10-09T12:00:00Z");

    private AttributionEngine engine;
    private PricingTable pricingTable;
    private final List<String> warnings = new ArrayList<>();

    @BeforeEach
    void setUp() {
        pricingTable =
                new PricingTable(List.of(entry("deepseek", "deepseek-chat", "0.002", "0.0008")));
        engine =
                new AttributionEngine(
                        new InMemoryMetricStore(),
                        pricingTable,
                        CostCalculator.getInstance(),
                        warnings::add);
        warnings.clear();
    }

    private static PriceEntry entry(String provider, String model, String in, String out) {
        return new PriceEntry(
                provider,
                model,
                "CNY",
                io.github.nephets.glasshouse.core.pricing.PriceUnit.PER_1K_TOKENS,
                new BigDecimal(in),
                new BigDecimal(out),
                null,
                null,
                java.time.LocalDate.parse("2026-01-01"),
                PriceSource.OFFICIAL);
    }

    /**
     * 构造一条审计事件。
     *
     * @param tenant 租户
     * @param bizLine 业务线
     * @param feature 功能
     * @param promptVersion Prompt 版本
     * @param inTokens 输入 token
     * @param outTokens 输出 token
     * @return 审计事件
     */
    private static AuditEvent event(
            String tenant,
            String bizLine,
            String feature,
            String promptVersion,
            long inTokens,
            long outTokens) {
        return new AuditEvent(
                EventType.MODEL_CALL,
                TRACE_ID,
                SPAN_ID,
                WHEN,
                tenant,
                bizLine,
                feature,
                promptVersion,
                null,
                null,
                null,
                null,
                Usage.of(inTokens, outTokens),
                BigDecimal.ZERO,
                "CNY",
                PayloadRedactor.getInstance().digest("payload"),
                null);
    }

    /**
     * 记录一条事件。
     *
     * @param tenant 租户
     * @param bizLine 业务线
     * @param feature 功能
     * @param promptVersion Prompt 版本
     * @param inTokens 输入 token
     * @param outTokens 输出 token
     * @return 是否记录成功
     */
    private boolean record(
            String tenant,
            String bizLine,
            String feature,
            String promptVersion,
            long inTokens,
            long outTokens) {
        return engine.record(
                event(tenant, bizLine, feature, promptVersion, inTokens, outTokens),
                "deepseek",
                "deepseek-chat");
    }

    @Test
    @DisplayName("构造流量：2 租户 × 2 功能 × 2 Prompt 版本")
    void recordConstructionTraffic() {
        // vip × 客服 × v3：3 次 × 1000/1000 token
        for (int i = 0; i < 3; i++) {
            record("vip", "电商", "客服助手", "planner@v3", 1000L, 1000L);
        }
        // vip × 智能问答 × v3：1 次
        record("vip", "电商", "智能问答", "planner@v3", 1000L, 1000L);
        // standard × 客服 × v2：2 次
        for (int i = 0; i < 2; i++) {
            record("standard", "电商", "客服助手", "planner@v2", 1000L, 1000L);
        }
        // trial × 智能问答 × v2：1 次
        record("trial", "教育", "智能问答", "planner@v2", 500L, 500L);

        assertThat(engine.store().size()).as("4 个不同维度组合 → 4 个聚合键").isEqualTo(4);
    }

    @ParameterizedTest(name = "维度 {0} 可独立下钻")
    @EnumSource(AttributionDimension.class)
    @DisplayName("PRD P0-3 验收：五个业务维度逐一可下钻，行数与金额正确")
    void everyDimensionCanDrillDown(AttributionDimension dimension) {
        for (int i = 0; i < 3; i++) {
            record("vip", "电商", "客服助手", "planner@v3", 1000L, 1000L);
        }
        record("vip", "电商", "智能问答", "planner@v3", 1000L, 1000L);
        record("standard", "电商", "客服助手", "planner@v2", 1000L, 1000L);
        record("trial", "教育", "智能问答", "planner@v2", 500L, 500L);

        AttributionReport report = engine.report(dimension);
        assertThat(report.dimension()).isEqualTo(dimension);
        assertThat(report.totalCalls()).as("总调用次数").isEqualTo(6L);

        // 每个维度的期望行数
        int expectedRows =
                switch (dimension) {
                    case PROVIDER, MODEL -> 1; // 全部 deepseek-chat
                    case TENANT -> 3; // vip / standard / trial
                    case BIZ_LINE -> 2; // 电商 / 教育
                    case FEATURE -> 2; // 客服助手 / 智能问答
                    case PROMPT_VERSION -> 2; // v3 / v2
                    case AGENT_STEP -> 2; // 未标注 / 未标注 但 feature 不同… 见下
                };
        if (dimension == AttributionDimension.AGENT_STEP) {
            // agentId 与 stepId 都为 null → 全部落到同一个「未标注」分组
            assertThat(report.rows()).hasSize(1);
            assertThat(report.rows().get(0).valueOf(dimension))
                    .as("AGENT_STEP 维度下 agentId/stepId 均为 null，必须显示为「未标注」而非内部占位符")
                    .isEqualTo(AttributionKey.DISPLAY_MISSING);
        } else {
            assertThat(report.rows())
                    .as("维度 %s 应有 %d 行", dimension.displayName(), expectedRows)
                    .hasSize(expectedRows);
        }

        // 金额必须与用量一致：6 次调用中 5 次是 1000/1000，1 次是 500/500
        // 单价：输入 0.002 / 1K，输出 0.0008 / 1K
        // 1000/1000 一次 = 0.002 + 0.0008 = 0.0028
        //   500/500  一次 = 0.001 + 0.0004 = 0.0014
        // 合计 = 5 × 0.0028 + 1 × 0.0014 = 0.014 + 0.0014 = 0.0154
        assertThat(report.totalCost())
                .as("维度 %s 的总成本应为 0.0154 CNY（与用量严格一致）", dimension.displayName())
                .isEqualByComparingTo("0.01540000");
    }

    @Test
    @DisplayName("按租户下钻：vip 占比最高")
    void drillDownByTenant() {
        for (int i = 0; i < 3; i++) {
            record("vip", "电商", "客服助手", "v3", 1000L, 1000L);
        }
        record("standard", "电商", "客服助手", "v3", 1000L, 1000L);

        List<AttributionRow> rows = engine.query(AttributionQuery.of(AttributionDimension.TENANT));
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).valueOf(AttributionDimension.TENANT)).isEqualTo("vip");
        assertThat(rows.get(0).callCount()).isEqualTo(3L);
        assertThat(rows.get(0).share()).isCloseTo(0.75d, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(rows.get(1).valueOf(AttributionDimension.TENANT)).isEqualTo("standard");
        assertThat(rows.get(0).totalCost()).isGreaterThan(rows.get(1).totalCost());
    }

    @Test
    @DisplayName("按 Prompt 版本下钻：能看出 v2 与 v3 的成本差")
    void drillDownByPromptVersion() {
        record("vip", "电商", "客服助手", "planner@v2", 2000L, 2000L);
        record("vip", "电商", "客服助手", "planner@v3", 1000L, 1000L);

        List<AttributionRow> rows =
                engine.query(AttributionQuery.of(AttributionDimension.PROMPT_VERSION));
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).valueOf(AttributionDimension.PROMPT_VERSION))
                .isEqualTo("planner@v2");
        assertThat(rows.get(0).inputTokens()).isEqualTo(2000L);
        assertThat(rows.get(1).inputTokens()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("过滤条件：只查某租户")
    void filterByTenant() {
        record("vip", "电商", "客服助手", "v3", 1000L, 1000L);
        record("standard", "电商", "客服助手", "v3", 1000L, 1000L);

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
        List<AttributionRow> rows = engine.query(query);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).valueOf(AttributionDimension.TENANT)).isEqualTo("vip");
    }

    @Test
    @DisplayName("过滤条件：只查未标注某维度的行")
    void filterOnlyUnlabeled() {
        record("vip", "电商", "客服助手", "v3", 1000L, 1000L);
        record(null, "电商", "客服助手", "v3", 1000L, 1000L);

        List<AttributionRow> rows =
                engine.query(AttributionQuery.onlyUnlabeled(AttributionDimension.TENANT));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).valueOf(AttributionDimension.TENANT)).isEqualTo("未标注");
    }

    @Test
    @DisplayName("空字符串维度归一化为 null，不产生 \"\" 脏行（契约 EVT-001）")
    void blankDimensionsBecomeNull() {
        AuditEvent blank = event("  ", "", "  ", null, 100L, 100L);
        assertThat(blank.tenantId()).isNull();
        assertThat(blank.bizLine()).isNull();
        assertThat(blank.feature()).isNull();
        assertThat(blank.promptVersion()).isNull();

        engine.record(blank, "deepseek", "deepseek-chat");
        List<AttributionRow> rows =
                engine.query(AttributionQuery.onlyUnlabeled(AttributionDimension.TENANT));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).valueOf(AttributionDimension.TENANT)).isEqualTo("未标注");
    }

    @Test
    @DisplayName("价格缺失时成本记 0 并告警，不抛异常（观测不得让业务失败）")
    void missingPriceWarnsInsteadOfThrowing() {
        boolean ok =
                engine.record(event("vip", "电商", "客服", "v3", 1000L, 1000L), "openai", "gpt-4o");
        assertThat(ok).isTrue();
        assertThat(warnings)
                .as("缺价格必须告警，否则成本静默变 0 无人察觉")
                .anyMatch(w -> w.contains("价格表中查不到模型") && w.contains("openai/gpt-4o"));
        assertThat(engine.report(AttributionDimension.MODEL).totalCost())
                .isEqualByComparingTo("0.00000000");
    }

    @Test
    @DisplayName("null 事件返回 false，不抛异常")
    void nullEventIsSafe() {
        assertThat(engine.record(null, "deepseek", "deepseek-chat")).isFalse();
    }

    @Test
    @DisplayName("报告带数据质量指标：估算计量必须被标注")
    void reportExposesDataQuality() {
        // 全部是供应商真实返回 → 可信率 1.0
        for (int i = 0; i < 3; i++) {
            record("vip", "电商", "客服", "v3", 1000L, 1000L);
        }
        AttributionReport trustworthy = engine.report(AttributionDimension.TENANT);
        assertThat(trustworthy.trustworthyRatio()).isEqualTo(1.0d);
        assertThat(trustworthy.untrustworthySources()).isEmpty();

        // 混入一条估算计量 → 可信率下降
        AuditEvent estimated =
                new AuditEvent(
                        EventType.MODEL_CALL,
                        TRACE_ID,
                        SPAN_ID,
                        WHEN,
                        "vip",
                        "电商",
                        "客服",
                        "v3",
                        null,
                        null,
                        null,
                        null,
                        Usage.estimated(UsageSourceHolder.ESTIMATED, false),
                        BigDecimal.ZERO,
                        "CNY",
                        PayloadRedactor.getInstance().digest("x"),
                        null);
        engine.record(estimated, "deepseek", "deepseek-chat");

        AttributionReport mixed = engine.report(AttributionDimension.TENANT);
        assertThat(mixed.trustworthyRatio())
                .as("3 真 + 1 估 → 可信率 0.75")
                .isCloseTo(0.75d, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(mixed.untrustworthySources()).isNotEmpty();
    }

    @Test
    @DisplayName("reportAllDimensions 一次返回所有维度的报表")
    void reportAllDimensions() {
        record("vip", "电商", "客服助手", "v3", 1000L, 1000L);
        record("standard", "电商", "智能问答", "v2", 500L, 500L);

        Map<AttributionDimension, AttributionReport> all =
                engine.reportAllDimensions(AttributionQuery.all());
        assertThat(all).hasSize(AttributionDimension.values().length);
        for (Map.Entry<AttributionDimension, AttributionReport> e : all.entrySet()) {
            assertThat(e.getValue().totalCalls())
                    .as("维度 %s 的总调用数应一致", e.getKey().displayName())
                    .isEqualTo(2L);
        }
    }

    @Test
    @DisplayName("limit 生效，防止 CLI 一次吐出十万行")
    void limitIsApplied() {
        for (int i = 0; i < 10; i++) {
            record("tenant-" + i, "电商", "客服", "v3", 1000L, 1000L);
        }
        List<AttributionRow> rows =
                engine.query(AttributionQuery.of(AttributionDimension.TENANT).withLimit(3));
        assertThat(rows).hasSize(3);
    }

    @Test
    @DisplayName("AttributionEngine 永不抛异常：存储异常也只告警")
    void neverThrowsOnStoreFailure() {
        AttributionEngine broken =
                new AttributionEngine(
                        new MetricStore() {
                            @Override
                            public void increment(
                                    AttributionKey key, Usage usage, BigDecimal cost) {
                                throw new IllegalStateException("模拟存储故障");
                            }

                            @Override
                            public List<AttributionRow> aggregate(AttributionQuery query) {
                                return List.of();
                            }

                            @Override
                            public AttributionReport report(AttributionQuery query) {
                                return AttributionReport.empty(
                                        query == null
                                                ? AttributionDimension.MODEL
                                                : query.dimension());
                            }

                            @Override
                            public void clear() {}

                            @Override
                            public int size() {
                                return 0;
                            }
                        },
                        pricingTable,
                        CostCalculator.getInstance(),
                        warnings::add);

        boolean ok =
                broken.record(
                        event("vip", "电商", "客服", "v3", 1000L, 1000L), "deepseek", "deepseek-chat");
        assertThat(ok).isFalse();
        assertThat(warnings).anyMatch(w -> w.contains("归因记录失败") && w.contains("模拟存储故障"));
    }

    @Test
    @DisplayName("价格表模糊匹配：模型名带日期后缀也能查到价格")
    void fuzzyLookupHandlesModelSuffix() {
        pricingTable.replaceAll(List.of(entry("dashscope", "qwen-max", "0.02", "0.06")));
        engine =
                new AttributionEngine(
                        new InMemoryMetricStore(),
                        pricingTable,
                        CostCalculator.getInstance(),
                        warnings::add);

        // 实际模型名是 qwen-max-2025-01-25，价格表里只有 qwen-max
        assertThat(pricingTable.lookup("dashscope", "qwen-max-2025-01-25")).isEmpty();
        assertThat(pricingTable.lookupFuzzy("dashscope", "qwen-max-2025-01-25"))
                .as("模型名带日期后缀是真实场景，模糊匹配要能兜住，否则成本静默记 0")
                .isPresent();

        engine.record(
                event("vip", "电商", "客服", "v3", 1000L, 1000L), "dashscope", "qwen-max-2025-01-25");
        assertThat(engine.report(AttributionDimension.MODEL).totalCost())
                .isEqualByComparingTo("0.08000000");
    }

    @Test
    @DisplayName("内置价格表可从 classpath 加载，且覆盖全部八家供应商")
    void builtinPricingTableLoads() {
        PricingTableLoader loader = new PricingTableLoader();
        PricingTableLoader.LoadResult result = loader.loadFromClasspath();
        assertThat(result.hasSkipped()).as("内置价格表不应有解析失败的条目: %s", result.skipped()).isFalse();
        assertThat(result.entries()).hasSizeGreaterThanOrEqualTo(15);

        for (String provider :
                List.of(
                        "openai",
                        "anthropic",
                        "dashscope",
                        "deepseek",
                        "zhipu",
                        "moonshot",
                        "doubao",
                        "ollama")) {
            assertThat(result.entries())
                    .as("内置价格表必须覆盖 %s（README 支持矩阵的承诺）", provider)
                    .anyMatch(e -> e.providerId().equals(provider));
        }
        assertThat(result.entries())
                .anyMatch(e -> e.source() == PriceSource.ESTIMATED)
                .as("必须有一个 ESTIMATED 条目作为「UI 要标注估算值」的示例");
    }

    /** 避免测试类直接 import 过多 UsageSource 常量的辅助引用。 */
    private static final class UsageSourceHolder {
        static final io.github.nephets.glasshouse.core.usage.UsageSource ESTIMATED =
                io.github.nephets.glasshouse.core.usage.UsageSource.ESTIMATED;

        private UsageSourceHolder() {}
    }
}
