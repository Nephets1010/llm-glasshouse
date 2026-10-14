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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import io.github.nephets.glasshouse.core.usage.Usage;

/**
 * 内存归因存储实现（首版默认，架构 Q-D 拍板）。
 *
 * <p><b>数据结构</b>：{@code ConcurrentHashMap<AttributionKey, SlidingWindowAccumulator>}。 1
 * 万条/分钟级流量下，键数量等于「不同维度组合数」——真实业务里通常在几百到几千， 内存占用完全可接受。
 *
 * <p><b>滑动窗口淘汰</b>：每 {@code window}（默认 1 小时）最多扫描一次全表， 淘汰 {@code lastUpdatedAt} 早于 {@code now -
 * window * 2} 的键。这个「2 倍窗口」的滞后是刻意的： 如果窗口一到就删，用户查「最近一小时的报表」会在边界上看到数据凭空消失。
 *
 * <p><b>时钟可注入</b>：架构 §8.6 要求「所有超时/TTL 逻辑必须支持注入 Clock，测试禁用 Thread.sleep」。 本类的淘汰逻辑全部基于 {@link
 * Clock}，测试用 {@link Clock#fixed} 即可确定性地验证过期行为。
 */
public final class InMemoryMetricStore implements MetricStore {

    /** 默认滑动窗口长度：1 小时。 */
    public static final Duration DEFAULT_WINDOW = Duration.ofHours(1);

    /** 默认淘汰滞后倍数。 */
    public static final int DEFAULT_STALE_FACTOR = 2;

    /**
     * 默认最大归因键数量：10 万。
     *
     * <p><b>为什么必须有硬上限</b>：滑动窗口淘汰只能回收「不再更新」的键。 若某次故障让维度值变成高基数（例如把 traceId 誤当 tenant 写进属性），键会在 60
     * 秒扫描间隔内持续增长， 窗口淘汰追不上写入速度，最终 OOM。容量上限是这条路径上唯一的兜底。
     */
    public static final int DEFAULT_MAX_ENTRIES = 100_000;

    /** 淘汰扫描的最小间隔：60 秒。 */
    private static final long EVICTION_INTERVAL_NANOS = 60L * 1_000_000_000L;

    private final Map<AttributionKey, SlidingWindowAccumulator> map = new ConcurrentHashMap<>();
    private final AtomicLong operationCount = new AtomicLong();
    private final AtomicLong evictedCount = new AtomicLong();

    /** 容量上限，必须 ≥ 1。 */
    private final int maxEntries;

    /** 上次淘汰的单调时刻，初始为 0 使首次写入立即淘汰一次。 */
    private final AtomicLong lastEvictionNanos = new AtomicLong(0L);

    private final Clock clock;
    private final Duration window;
    private final int staleFactor;
    private final Supplier<Long> nanoClock;

    /** 使用默认时钟与默认窗口构造。 */
    public InMemoryMetricStore() {
        this(Clock.systemUTC(), DEFAULT_WINDOW, DEFAULT_STALE_FACTOR, System::nanoTime);
    }

    /**
     * 使用指定时钟与窗口构造。
     *
     * @param clock 时钟，不可为 null
     * @param window 滑动窗口长度，不可为 null
     */
    public InMemoryMetricStore(Clock clock, Duration window) {
        this(clock, window, DEFAULT_STALE_FACTOR, System::nanoTime);
    }

    /**
     * 完整构造。
     *
     * @param clock 时钟，不可为 null
     * @param window 滑动窗口长度，不可为 null
     * @param staleFactor 淘汰滞后倍数，必须 ≥ 1
     * @param nanoClock 单调时钟来源，用于控制扫描频率；为 null 时用 {@link System#nanoTime()}
     */
    public InMemoryMetricStore(
            Clock clock, Duration window, int staleFactor, Supplier<Long> nanoClock) {
        this(clock, window, staleFactor, nanoClock, DEFAULT_MAX_ENTRIES);
    }

    /**
     * 完整构造（含容量上限）。
     *
     * @param clock 时钟，不可为 null
     * @param window 滑动窗口长度，不可为 null
     * @param staleFactor 淘汰滞后倍数，必须 ≥ 1
     * @param nanoClock 单调时钟来源，为 null 时用 {@link System#nanoTime()}
     * @param maxEntries 容量上限，必须 ≥ 1
     */
    public InMemoryMetricStore(
            Clock clock,
            Duration window,
            int staleFactor,
            Supplier<Long> nanoClock,
            int maxEntries) {
        if (clock == null) {
            throw new NullPointerException("clock 不能为 null");
        }
        if (window == null || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("window 必须为正");
        }
        if (staleFactor < 1) {
            throw new IllegalArgumentException("staleFactor 必须 ≥ 1");
        }
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries 必须 ≥ 1");
        }
        this.clock = clock;
        this.window = window;
        this.staleFactor = staleFactor;
        this.nanoClock = nanoClock == null ? System::nanoTime : nanoClock;
        this.maxEntries = maxEntries;
    }

    @Override
    public void increment(AttributionKey key, Usage usage, BigDecimal cost) {
        if (key == null || usage == null || cost == null) {
            return;
        }
        operationCount.incrementAndGet();
        map.computeIfAbsent(key, SlidingWindowAccumulator::new).add(usage, cost);
        evictOverflow();
    }

    @Override
    public List<AttributionRow> aggregate(AttributionQuery query) {
        if (query == null) {
            return List.of();
        }
        evictIfDue();
        // ★ 按查询维度真正聚合：多个聚合键（key 含 6 个维度）在同一维度取值上相同时必须合并。
        //   不合并的话「按租户下钻」会因为同一租户有多个 key 而被拆成多行，
        //   PRD P0-3 的「任一维度均可下钻」就成了假象。这条由 AttributionEngineTest 锁定。
        Map<String, SlidingWindowAccumulator.Snapshot> merged = new LinkedHashMap<>();
        // 每个维度取值对应的「代表性原始 key」：一行可能由多个原始 key 合并而来
        // （它们只在查询维度之外的字段上不同），返回第一个原始 key，
        // 这样 AttributionRow.valueOf 在非查询维度上仍有有意义的值。
        Map<String, AttributionKey> firstKeyByDimension = new LinkedHashMap<>();
        for (Map.Entry<AttributionKey, SlidingWindowAccumulator> entry : map.entrySet()) {
            if (!query.matches(entry.getKey())) {
                continue;
            }
            String dimensionValue = entry.getKey().valueOf(query.dimension());
            merged.merge(
                    dimensionValue,
                    entry.getValue().snapshot(),
                    SlidingWindowAccumulator.Snapshot::plus);
            firstKeyByDimension.putIfAbsent(dimensionValue, entry.getKey());
        }
        if (merged.isEmpty()) {
            return List.of();
        }
        BigDecimal totalCost = BigDecimal.ZERO;
        for (SlidingWindowAccumulator.Snapshot snapshot : merged.values()) {
            totalCost = totalCost.add(snapshot.totalCost());
        }
        List<AttributionRow> rows = new ArrayList<>(merged.size());
        for (Map.Entry<String, SlidingWindowAccumulator.Snapshot> entry : merged.entrySet()) {
            SlidingWindowAccumulator.Snapshot snapshot = entry.getValue();
            if (snapshot.totalCount() == 0L) {
                continue;
            }
            rows.add(
                    AttributionRow.of(
                            firstKeyByDimension.get(entry.getKey()),
                            snapshot,
                            shareOf(snapshot.totalCost(), totalCost)));
        }
        rows.sort(
                Comparator.comparing(AttributionRow::totalCost)
                        .reversed()
                        .thenComparing(r -> r.valueOf(query.dimension())));
        int limit = query.limit();
        return limit > 0 && rows.size() > limit
                ? List.copyOf(rows.subList(0, limit))
                : List.copyOf(rows);
    }

    @Override
    public AttributionReport report(AttributionQuery query) {
        AttributionQuery effective = query == null ? AttributionQuery.all() : query;
        List<AttributionRow> rows = aggregate(effective);
        long calls = 0L;
        long input = 0L;
        long output = 0L;
        long reasoning = 0L;
        BigDecimal cost = BigDecimal.ZERO;
        for (AttributionRow row : rows) {
            calls += row.callCount();
            input += row.inputTokens();
            output += row.outputTokens();
            reasoning += row.reasoningTokens();
            cost = cost.add(row.totalCost());
        }
        return new AttributionReport(
                effective.dimension(),
                rows,
                calls,
                input,
                output,
                reasoning,
                cost,
                clock.instant());
    }

    @Override
    public void clear() {
        map.clear();
    }

    @Override
    public int size() {
        return map.size();
    }

    /**
     * 返回累计的 {@code increment} 调用次数，供健康检查与自测使用。
     *
     * @return 调用次数
     */
    public long operationCount() {
        return operationCount.get();
    }

    /**
     * 返回滑动窗口长度。
     *
     * @return 窗口长度
     */
    public Duration window() {
        return window;
    }

    /**
     * 返回容量上限。
     *
     * @return 最大归因键数量
     */
    public int maxEntries() {
        return maxEntries;
    }

    /**
     * 返回因容量上限被淘汰的键数量。
     *
     * <p>这是容量降级的<b>可观测标记</b>：非 0 说明维度基数已经超过设计容量， 报表里的「总量」是截断后的值，需要排查维度取值是否异常。
     *
     * @return 被淘汰的键数量
     */
    public long evictedCount() {
        return evictedCount.get();
    }

    /**
     * 超出容量上限时按「最久未更新」淘汰多余键（近似 LRU）。
     *
     * <p>只在超限的瞬间做这一次全表排序，未超限时开销是 {@code ConcurrentHashMap.size()} 一次 O(1) 读，
     * 因此不会给正常写入路径增加开销。同毫秒更新的键按归因键的字符串序做稳定兜底， 保证淘汰结果可复现（回归测试依赖这一点）。
     */
    private void evictOverflow() {
        int excess = map.size() - maxEntries;
        if (excess <= 0) {
            return;
        }
        List<Map.Entry<AttributionKey, SlidingWindowAccumulator>> oldest =
                new ArrayList<>(map.entrySet());
        oldest.sort(
                Comparator.comparingLong(
                                (Map.Entry<AttributionKey, SlidingWindowAccumulator> e) ->
                                        e.getValue().lastUpdatedAtMillis())
                        .thenComparing(e -> String.valueOf(e.getKey())));
        int removed = 0;
        for (Map.Entry<AttributionKey, SlidingWindowAccumulator> entry : oldest) {
            if (removed >= excess) {
                break;
            }
            if (map.remove(entry.getKey(), entry.getValue())) {
                removed++;
            }
        }
        if (removed > 0) {
            evictedCount.addAndGet(removed);
        }
    }

    /**
     * 计算占比，分母为 0 时返回 0。
     *
     * @param cost 当前行成本
     * @param total 总成本
     * @return 0~1 之间的占比
     */
    private static double shareOf(BigDecimal cost, BigDecimal total) {
        if (total == null || total.signum() == 0) {
            return 0.0d;
        }
        double ratio = cost.doubleValue() / total.doubleValue();
        return Math.max(0.0d, Math.min(1.0d, ratio));
    }

    /**
     * 按窗口频率触发过期键淘汰。
     *
     * <p>用 {@link System#nanoTime()} 做节流：每 60 秒最多扫描一次全表。 在 1 万条/分钟的量级下，「每次写入都检查过期」会带来不必要的 CPU 消耗。
     */
    private void evictIfDue() {
        long now = nanoClock.get();
        long last = lastEvictionNanos.get();
        if (now - last < EVICTION_INTERVAL_NANOS) {
            return;
        }
        if (!lastEvictionNanos.compareAndSet(last, now)) {
            return;
        }
        Instant cutoff = clock.instant().minus(window.multipliedBy(staleFactor));
        map.entrySet().removeIf(e -> e.getValue().staleThan(cutoff));
    }
}
