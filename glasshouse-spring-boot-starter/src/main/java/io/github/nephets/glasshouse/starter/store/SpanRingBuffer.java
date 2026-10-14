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

package io.github.nephets.glasshouse.starter.store;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存 Span 环形缓冲（首版默认存储，架构 Q-D 拍板）。
 *
 * <p><b>为什么首版不上 ClickHouse / Jaeger</b>：README 首屏那张「12 秒请求的 span 树」截图必须
 * <b>开箱即得</b>。如果需要用户先起一套存储后端才能看到 span，绝大多数人在第一步就流失了。 同时首版<b>完整支持 OTLP 导出</b>，生产环境一律推给
 * Jaeger/Tempo/Grafana，数据不锁定。
 *
 * <p><b>容量策略</b>：默认 10 万条。超出后<b>丢弃最旧的 span</b>而不是拒绝新 span—— 观测系统的数据永远是「可以丢的」，而拒绝新数据会让链路断掉，那是更严重的问题。
 * 被丢弃的数量记在 {@link #droppedCount()}，通过 Actuator 端点暴露，让「数据不全」这件事可见。
 *
 * <p><b>线程安全</b>：写入走一个环形数组 + 原子游标；{@link #tree(String)} 读取时对快照加锁。
 */
public final class SpanRingBuffer {

    private final int capacity;
    private final SpanData[] ring;
    private final AtomicLong cursor = new AtomicLong(0);
    private final AtomicLong dropped = new AtomicLong(0);
    private final AtomicLong totalWritten = new AtomicLong(0);
    private final Object snapshotLock = new Object();

    /** 构造指定容量的缓冲。 */
    public SpanRingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity 必须为正数，实际为: " + capacity);
        }
        this.capacity = capacity;
        this.ring = new SpanData[capacity];
    }

    /**
     * 返回容量。
     *
     * @return 最大存放条数
     */
    public int capacity() {
        return capacity;
    }

    /**
     * 追加一条 span。
     *
     * @param span span 数据；为 null 时忽略
     */
    public void append(SpanData span) {
        if (span == null) {
            return;
        }
        long index = cursor.getAndIncrement();
        totalWritten.incrementAndGet();
        synchronized (snapshotLock) {
            if (index >= capacity) {
                // 已环绕，写入位置复用最旧的那一格，并计数丢弃
                dropped.incrementAndGet();
            }
            ring[(int) Math.floorMod(index, capacity)] = span;
        }
    }

    /**
     * 追加多条 span。
     *
     * @param spans span 列表；为 null 时忽略
     */
    public void appendAll(List<SpanData> spans) {
        if (spans == null) {
            return;
        }
        for (SpanData s : spans) {
            append(s);
        }
    }

    /**
     * 返回全部 span 的快照。
     *
     * @return 按写入顺序的不可变列表
     */
    public List<SpanData> all() {
        synchronized (snapshotLock) {
            long total = totalWritten.get();
            if (total == 0L) {
                return List.of();
            }
            int size = (int) Math.min(total, capacity);
            long end = cursor.get();
            // 按写入顺序（最旧 → 最新）返回：CLI 的 --tree 与 JSONL 导出都依赖这个顺序，
            // 倒序输出会让 span 树看起来是「从叶子往根长」，与真实链路相反。
            List<SpanData> result = new ArrayList<>(size);
            for (int i = size - 1; i >= 0; i--) {
                SpanData s = ring[(int) Math.floorMod(end - 1 - i, capacity)];
                if (s != null) {
                    result.add(s);
                }
            }
            return List.copyOf(result);
        }
    }

    /**
     * 返回全部 span 树（按 traceId 分组）。
     *
     * @return traceId → span 列表
     */
    public Map<String, List<SpanData>> byTrace() {
        Map<String, List<SpanData>> result = new ConcurrentHashMap<>();
        for (SpanData s : all()) {
            result.computeIfAbsent(s.traceId(), k -> new ArrayList<>()).add(s);
        }
        result.replaceAll((k, v) -> List.copyOf(v));
        return Map.copyOf(result);
    }

    /**
     * 返回当前的 span 数量。
     *
     * @return 数量
     */
    public int size() {
        return all().size();
    }

    /**
     * 返回累计因环形覆盖而丢弃的 span 数。
     *
     * @return 丢弃数
     */
    public long droppedCount() {
        return dropped.get();
    }

    /**
     * 返回累计写入的 span 总数（含已被覆盖的）。
     *
     * @return 总数
     */
    public long totalWritten() {
        return totalWritten.get();
    }

    /** 清空缓冲。 */
    public void clear() {
        synchronized (snapshotLock) {
            java.util.Arrays.fill(ring, null);
            cursor.set(0L);
            dropped.set(0L);
            totalWritten.set(0L);
        }
    }

    /**
     * 一条 span 的轻量快照。
     *
     * <p>只保留「回答『慢在哪、花了多少』」所必需的字段，不做全量 OTel SDK 对象序列化—— 后者既占内存又难以在 CLI 里渲染。
     *
     * @param traceId 所属 traceId
     * @param spanId 本 span 的 spanId
     * @param parentSpanId 父 spanId；根节点为 null
     * @param name span 名（SPN-001 模板产出）
     * @param operation 操作名
     * @param target 操作目标（模型名 / Agent 名 / 工具名）
     * @param startTime 开始时刻
     * @param endTime 结束时刻
     * @param attributes 属性表
     * @param status 状态描述（OK / ERROR / UNSET）
     * @param errorMessage 错误描述；无错误为 null
     */
    public record SpanData(
            String traceId,
            String spanId,
            String parentSpanId,
            String name,
            String operation,
            String target,
            Instant startTime,
            Instant endTime,
            Map<String, Object> attributes,
            String status,
            String errorMessage) {

        /**
         * 紧凑构造器：属性表不可变，缺省为空表。
         *
         * @param traceId traceId
         * @param spanId spanId
         * @param parentSpanId 父 spanId
         * @param name span 名
         * @param operation 操作名
         * @param target 目标
         * @param startTime 开始时刻
         * @param endTime 结束时刻
         * @param attributes 属性表
         * @param status 状态
         * @param errorMessage 错误描述
         */
        public SpanData {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            status = status == null ? "UNSET" : status;
        }

        /**
         * 返回耗时。
         *
         * @return 毫秒数；结束时刻缺失时返回 0
         */
        public long durationMillis() {
            if (startTime == null || endTime == null) {
                return 0L;
            }
            return endTime.toEpochMilli() - startTime.toEpochMilli();
        }

        /**
         * 读取成本属性。
         *
         * @return 成本金额；未记录返回 null
         */
        public Double costAmount() {
            Object v = attributes.get("swy.cost.amount");
            return v instanceof Number n ? n.doubleValue() : null;
        }

        /**
         * 读取输入 token 属性。
         *
         * @return 输入 token；未记录返回 null
         */
        public Long inputTokens() {
            Object v = attributes.get("gen_ai.usage.input_tokens");
            return v instanceof Number n ? n.longValue() : null;
        }

        /**
         * 读取输出 token 属性。
         *
         * @return 输出 token；未记录返回 null
         */
        public Long outputTokens() {
            Object v = attributes.get("gen_ai.usage.output_tokens");
            return v instanceof Number n ? n.longValue() : null;
        }

        /**
         * 判断是否为根 span。
         *
         * @return 无父节点返回 {@code true}
         */
        public boolean root() {
            return parentSpanId == null || parentSpanId.isBlank();
        }

        /**
         * 按开始时刻排序的比较器。
         *
         * @return 比较器
         */
        public static Comparator<SpanData> byStartTime() {
            return Comparator.comparing(
                    SpanData::startTime, Comparator.nullsLast(Comparator.naturalOrder()));
        }
    }
}
