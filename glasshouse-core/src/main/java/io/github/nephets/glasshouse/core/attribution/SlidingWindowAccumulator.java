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
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.github.nephets.glasshouse.core.usage.Usage;

/**
 * 单个归因键的累加器。
 *
 * <p><b>并发设计</b>：用 {@link java.util.concurrent.atomic.AtomicReference} 持有不可变的 {@link Snapshot}，写入时
 * CAS 重试。这样读路径（{@link #snapshot()}）无锁且<b>永不看到半更新状态</b>—— 成本报表不会在读的瞬间出现「token 累加了但 cost 没累加」的中间态。
 *
 * <p>这是长尾累加而非简单自增的原因：{@code long} 自增会撕裂（读到的值可能跨越一次溢出）， 而在 1 万条/分钟的量级下，写放大可控。
 */
public final class SlidingWindowAccumulator {

    private final AttributionKey key;
    private final AtomicReference<Snapshot> ref = new AtomicReference<>(Snapshot.EMPTY);
    private final AtomicLong lastUpdatedAt = new AtomicLong(0L);

    /**
     * 构造累加器。
     *
     * @param key 归因键，不可为 null
     */
    public SlidingWindowAccumulator(AttributionKey key) {
        if (key == null) {
            throw new NullPointerException("key 不能为 null");
        }
        this.key = key;
    }

    /**
     * 返回所属归因键。
     *
     * @return 归因键
     */
    public AttributionKey key() {
        return key;
    }

    /**
     * 累加一次用量。
     *
     * @param usage 本次计量，不可为 null
     * @param cost 本次成本，不可为 null
     */
    public void add(Usage usage, BigDecimal cost) {
        if (usage == null || cost == null) {
            return;
        }
        Snapshot current;
        Snapshot next;
        do {
            current = ref.get();
            next = current.plus(usage, cost);
        } while (!ref.compareAndSet(current, next));
        // next.lastUpdatedAt() 是本次写入时刻，直接覆盖即可：
        // 并发写入时「后写的 lastUpdatedAt 更大」天然成立（纳秒精度），
        // 因此不需要额外的 max 计算。
        lastUpdatedAt.set(next.lastUpdatedAt().toEpochMilli());
    }

    /**
     * 返回当前快照。
     *
     * @return 不可变快照
     */
    public Snapshot snapshot() {
        return ref.get();
    }

    /**
     * 判断本累加器是否为空（没有任何累加）。
     *
     * @return 无累加返回 {@code true}
     */
    public boolean isEmpty() {
        return ref.get().totalCount() == 0L;
    }

    /**
     * 判断本累加器的数据是否早于给定的截止时刻（用于滑动窗口淘汰）。
     *
     * @param cutoff 截止时刻
     * @return 最后更新时刻早于截止返回 {@code true}
     */
    public boolean staleThan(Instant cutoff) {
        long last = lastUpdatedAt.get();
        return last > 0L && Instant.ofEpochMilli(last).isBefore(cutoff);
    }

    /**
     * 最后更新时刻。
     *
     * @return epoch 毫秒；从未更新时为 0
     */
    public long lastUpdatedAtMillis() {
        return lastUpdatedAt.get();
    }

    @Override
    public String toString() {
        Snapshot s = ref.get();
        return "AttributionRow{"
                + key.display(AttributionDimension.PROVIDER)
                + "/"
                + key.display(AttributionDimension.MODEL)
                + ", tenant="
                + key.display(AttributionDimension.TENANT)
                + ", calls="
                + s.totalCount()
                + ", tokens="
                + s.totalTokens()
                + ", cost="
                + s.totalCost()
                + '}';
    }

    /**
     * 累加器的不可变快照。
     *
     * @param totalCount 调用次数
     * @param inputTokens 累计输入 token
     * @param outputTokens 累计输出 token
     * @param reasoningTokens 累计推理 token
     * @param cachedInputTokens 累计缓存命中输入 token
     * @param estimatedCount 计量来源非 {@code VENDOR_REPORTED} 的次数
     * @param totalCost 累计成本（CNY）
     * @param lastUpdatedAt 最后更新时刻
     */
    public record Snapshot(
            long totalCount,
            long inputTokens,
            long outputTokens,
            long reasoningTokens,
            long cachedInputTokens,
            long estimatedCount,
            BigDecimal totalCost,
            Instant lastUpdatedAt) {

        /** 空快照常量。 */
        public static final Snapshot EMPTY =
                new Snapshot(0L, 0L, 0L, 0L, 0L, 0L, BigDecimal.ZERO, null);

        /**
         * 构造快照并校验非负。
         *
         * @throws IllegalArgumentException 任一计数为负
         */
        public Snapshot {
            if (totalCount < 0
                    || inputTokens < 0
                    || outputTokens < 0
                    || reasoningTokens < 0
                    || cachedInputTokens < 0
                    || estimatedCount < 0) {
                throw new IllegalArgumentException("计数禁止负值");
            }
            if (totalCost == null) {
                totalCost = BigDecimal.ZERO;
            }
        }

        /**
         * 累加一次用量，返回新快照。
         *
         * @param usage 本次计量
         * @param cost 本次成本
         * @return 新快照
         */
        public Snapshot plus(Usage usage, BigDecimal cost) {
            boolean estimated = !usage.trustworthy();
            return new Snapshot(
                    totalCount + 1L,
                    inputTokens + usage.inputTokens(),
                    outputTokens + usage.outputTokens(),
                    reasoningTokens + usage.reasoningTokens(),
                    cachedInputTokens + usage.cachedInputTokens(),
                    estimatedCount + (estimated ? 1L : 0L),
                    totalCost.add(cost == null ? BigDecimal.ZERO : cost),
                    Instant.now());
        }

        /**
         * 把另一个快照累加进来（用于按查询维度合并多个原始聚合键）。
         *
         * @param other 另一个快照，不可为 null
         * @return 合并后的新快照
         */
        public Snapshot plus(Snapshot other) {
            if (other == null) {
                return this;
            }
            return new Snapshot(
                    totalCount + other.totalCount,
                    inputTokens + other.inputTokens,
                    outputTokens + other.outputTokens,
                    reasoningTokens + other.reasoningTokens,
                    cachedInputTokens + other.cachedInputTokens,
                    estimatedCount + other.estimatedCount,
                    totalCost.add(other.totalCost),
                    lastUpdatedAt == null
                            ? other.lastUpdatedAt
                            : (other.lastUpdatedAt == null
                                            || lastUpdatedAt.isAfter(other.lastUpdatedAt)
                                    ? lastUpdatedAt
                                    : other.lastUpdatedAt));
        }

        /**
         * 返回总 token 数（输入 + 输出 + 推理 + 缓存命中）。
         *
         * @return 总 token
         */
        public long totalTokens() {
            return inputTokens + outputTokens + reasoningTokens + cachedInputTokens;
        }

        /**
         * 返回平均单次成本。
         *
         * @return 平均成本；无调用时返回 {@link BigDecimal#ZERO}
         */
        public BigDecimal avgCost() {
            if (totalCount == 0L) {
                return BigDecimal.ZERO;
            }
            return totalCost.divide(
                    BigDecimal.valueOf(totalCount), 8, java.math.RoundingMode.HALF_UP);
        }

        /**
         * 返回平均单次耗时估算（毫秒）。首版无耗时统计，返回 0。
         *
         * @return 毫秒数
         */
        public long avgLatencyMillis() {
            return 0L;
        }

        /**
         * 返回计量可信率（{@code VENDOR_REPORTED} 占比）。
         *
         * @return 0~1 之间的可信率；无调用时返回 0
         */
        public double trustworthyRatio() {
            if (totalCount == 0L) {
                return 0.0d;
            }
            return (double) (totalCount - estimatedCount) / (double) totalCount;
        }

        /**
         * 返回累计耗时。
         *
         * @return 恒为 {@link Duration#ZERO}（首版无耗时累计）
         */
        public Duration window() {
            return Duration.ZERO;
        }
    }
}
