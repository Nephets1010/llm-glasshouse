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
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import io.github.nephets.glasshouse.core.audit.AuditEvent;
import io.github.nephets.glasshouse.core.pricing.CostCalculator;
import io.github.nephets.glasshouse.core.pricing.PriceEntry;
import io.github.nephets.glasshouse.core.pricing.PricingTable;

/**
 * 五维成本归因引擎（PRD 仓库③ P0-3）。
 *
 * <p>它是「观测」与「成本」之间的桥：接收 {@link AuditEvent}，用 {@link PricingTable} 算出成本， 再按 {@link AttributionKey} 入
 * {@link MetricStore}。业务侧<b>不需要主动调用</b>—— starter 的 observation handler 在 span 结束时会自动调用 {@link
 * #record}。
 *
 * <p><b>永不抛异常</b>：这是观测侧的铁律。价格表里查不到模型、事件字段缺失、存储实现出错—— 任何一种都只记一条 WARN 并降级，绝不能因为「算成本失败」让业务请求失败。 因此
 * {@link #record} 的返回值是 {@code boolean} 而不是抛异常，调用方一眼能看出是否成功。
 */
public final class AttributionEngine {

    private final MetricStore store;
    private final PricingTable pricingTable;
    private final CostCalculator costCalculator;
    private final Consumer<String> warnSink;

    /** 价格解析器：优先精确匹配，未命中时回退模糊匹配（模型名常带日期后缀或部署后缀）。 */
    private final BiFunction<String, String, Optional<PriceEntry>> priceResolver;

    /** 用默认内存存储构造。 */
    public AttributionEngine() {
        this(
                new InMemoryMetricStore(),
                new PricingTable(),
                CostCalculator.getInstance(),
                msg -> {});
    }

    /**
     * 用指定存储与价格表构造。
     *
     * @param store 归因存储，不可为 null
     * @param pricingTable 价格表，不可为 null
     */
    public AttributionEngine(MetricStore store, PricingTable pricingTable) {
        this(store, pricingTable, CostCalculator.getInstance(), msg -> {});
    }

    /**
     * 完整构造。
     *
     * @param store 归因存储，不可为 null
     * @param pricingTable 价格表，不可为 null
     * @param costCalculator 成本计算器，不可为 null
     * @param warnSink 告警回调；接收可读的中文告警文本，不可为 null
     */
    public AttributionEngine(
            MetricStore store,
            PricingTable pricingTable,
            CostCalculator costCalculator,
            Consumer<String> warnSink) {
        if (store == null) {
            throw new NullPointerException("store 不能为 null");
        }
        if (pricingTable == null) {
            throw new NullPointerException("pricingTable 不能为 null");
        }
        if (costCalculator == null) {
            throw new NullPointerException("costCalculator 不能为 null");
        }
        this.store = store;
        this.pricingTable = pricingTable;
        this.costCalculator = costCalculator;
        this.warnSink = warnSink == null ? msg -> {} : warnSink;
        this.priceResolver = pricingTable::lookupFuzzy;
    }

    /**
     * 记录一次调用。
     *
     * @param event 审计事件，不可为 null
     * @param providerId 供应商标识，可为 null（不在价格表里时成本记 0）
     * @param modelId 模型标识，可为 null
     * @return 记录成功返回 {@code true}
     */
    public boolean record(AuditEvent event, String providerId, String modelId) {
        if (event == null) {
            return false;
        }
        try {
            AttributionKey key = AttributionKey.from(event, providerId, modelId);
            BigDecimal cost = computeCost(event, providerId, modelId);
            store.increment(key, event.usage(), cost);
            return true;
        } catch (RuntimeException e) {
            // 观测永不抛异常：算成本失败不能影响业务
            warnSink.accept(
                    "[glasshouse] 归因记录失败 traceId="
                            + event.traceId()
                            + ", spanId="
                            + event.spanId()
                            + ", cause="
                            + e.getClass().getSimpleName()
                            + ": "
                            + e.getMessage());
            return false;
        }
    }

    /**
     * 计算一次调用的成本，价格缺失时返回 0 并告警。
     *
     * @param event 审计事件
     * @param providerId 供应商标识
     * @param modelId 模型标识
     * @return 成本金额（CNY），scale=8
     */
    public BigDecimal computeCost(AuditEvent event, String providerId, String modelId) {
        if (event.usage().totalTokens() == 0L) {
            return BigDecimal.ZERO.setScale(
                    CostCalculator.COST_SCALE, java.math.RoundingMode.HALF_UP);
        }
        Optional<PriceEntry> price = priceResolver.apply(providerId, modelId);
        if (price.isEmpty()) {
            warnSink.accept(
                    "[glasshouse] 价格表中查不到模型 "
                            + providerId
                            + "/"
                            + modelId
                            + "，本次成本记 0。请更新 glasshouse-prices.yml（支持热更新，无需重启）。");
            return BigDecimal.ZERO.setScale(
                    CostCalculator.COST_SCALE, java.math.RoundingMode.HALF_UP);
        }
        return costCalculator.compute(event.usage(), price.get());
    }

    /**
     * 按查询条件下钻。
     *
     * @param query 查询条件，不可为 null
     * @return 结果行列表，按成本倒序
     */
    public List<AttributionRow> query(AttributionQuery query) {
        return store.aggregate(query);
    }

    /**
     * 按指定维度生成报表。
     *
     * @param dimension 归因维度，不可为 null
     * @return 归因报表
     */
    public AttributionReport report(AttributionDimension dimension) {
        return store.report(AttributionQuery.of(dimension));
    }

    /**
     * 按查询条件生成报表。
     *
     * @param query 查询条件；为 null 时返回全量报表
     * @return 归因报表
     */
    public AttributionReport report(AttributionQuery query) {
        return store.report(query == null ? AttributionQuery.all() : query);
    }

    /**
     * 对每个归因维度各生成一份报表。
     *
     * <p>这正是 PRD P0-3 的「任一维度均可下钻」的可执行断言： {@code AttributionEngineTest} 对五个维度逐一断言行数与金额。
     *
     * @param query 基础查询条件；为 null 时用全量
     * @return 维度 → 报表的映射
     */
    public java.util.Map<AttributionDimension, AttributionReport> reportAllDimensions(
            AttributionQuery query) {
        AttributionQuery base = query == null ? AttributionQuery.all() : query;
        java.util.Map<AttributionDimension, AttributionReport> result =
                new java.util.LinkedHashMap<>();
        for (AttributionDimension d : AttributionDimension.all()) {
            result.put(d, store.report(base.withDimension(d)));
        }
        return java.util.Map.copyOf(result);
    }

    /**
     * 返回归因存储，供健康检查与测试断言。
     *
     * @return 归因存储
     */
    public MetricStore store() {
        return store;
    }

    /**
     * 返回价格表。
     *
     * @return 价格表
     */
    public PricingTable pricingTable() {
        return pricingTable;
    }

    /** 清空归因数据。 */
    public void clear() {
        store.clear();
    }

    /**
     * 返回报表生成时刻，供测试断言时钟注入是否生效。
     *
     * @param dimension 归因维度
     * @return 报表的生成时刻
     */
    public Instant reportGeneratedAt(AttributionDimension dimension) {
        return report(dimension).generatedAt();
    }
}
