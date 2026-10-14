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

package io.github.nephets.glasshouse.core.trace;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * W3C TraceContext 编解码器（契约 {@code CTX-001} 的载体实现）。
 *
 * <p><b>三仓一致性硬约束</b>：三个仓库都必须提供 {@code TraceContextCodec.encode/decode(headers)}，
 * 且对同一输入产生<b>字节级相同</b>的 {@code traceparent}。本类的实现与 {@code llm-switchyard} / {@code agentswitchyard}
 * 逐行等价，由 {@code W3CTraceCodecContractTest} 中的固定向量锁定。
 *
 * <p>传播载体（HTTP 头与 MQ 消息头键名完全一致）：
 *
 * <ul>
 *   <li>{@code traceparent} —— {@code 00-{traceId}-{spanId}-{traceFlags}}
 *   <li>{@code tracestate} —— 原样透传
 *   <li>{@code baggage} —— 承载五维归因维度
 * </ul>
 */
public final class W3CTraceCodec {

    /** {@code traceparent} 头名。 */
    public static final String TRACEPARENT = "traceparent";

    /** {@code tracestate} 头名。 */
    public static final String TRACESTATE = "tracestate";

    /** {@code baggage} 头名。 */
    public static final String BAGGAGE = "baggage";

    /** {@code traceparent} 中固定的分段符。 */
    public static final String SEPARATOR = "-";

    /** {@code traceparent} 的合法段数：版本-traceId-spanId-flags。 */
    public static final int SEGMENTS = 4;

    private W3CTraceCodec() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 将上下文编码为 {@code traceparent} 头值。
     *
     * @param context 上下文，不可为 null
     * @return 形如 {@code 00-{traceId}-{spanId}-{traceFlags}} 的字符串
     */
    public static String encodeTraceparent(TraceContext context) {
        return TraceContext.VERSION
                + SEPARATOR
                + context.traceId()
                + SEPARATOR
                + context.spanId()
                + SEPARATOR
                + context.traceFlags();
    }

    /**
     * 将上下文编码为一组传播头。
     *
     * @param context 上下文，不可为 null
     * @return 键为小写头名的不可变映射；{@code tracestate} 为 null 时不出现该键
     */
    public static Map<String, String> encode(TraceContext context) {
        Map<String, String> headers = new LinkedHashMap<>(4);
        headers.put(TRACEPARENT, encodeTraceparent(context));
        if (context.traceState() != null && !context.traceState().isBlank()) {
            headers.put(TRACESTATE, context.traceState());
        }
        return Map.copyOf(headers);
    }

    /**
     * 将归因维度写入 {@code baggage} 头。
     *
     * <p>W3C baggage 规范要求 key/value 均按 URL 编码。这里用最小实现：跳过 null 值，其余原样拼接， 由 {@code
     * java.net.URLEncoder} 处理非法字符。
     *
     * @param baggageItems 维度键值对，允许 null 值（会被跳过）
     * @return baggage 头值，无有效条目时返回空字符串
     */
    public static String encodeBaggage(Map<String, String> baggageItems) {
        if (baggageItems == null || baggageItems.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : baggageItems.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }

    /**
     * 从传播头解码上下文。
     *
     * <p>解析失败（头缺失、格式错误、字段非法）一律返回 {@link Optional#empty()}，<b>不抛异常</b>： 上游传来的脏 header
     * 不应导致业务请求失败，只应退化为新链路。
     *
     * @param headers 传播头，键大小写不敏感；可为 null
     * @return 解码成功返回上下文，否则为空
     */
    public static Optional<TraceContext> decode(Map<String, String> headers) {
        if (headers == null) {
            return Optional.empty();
        }
        String traceparent = null;
        String tracestate = null;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String key = entry.getKey().toLowerCase(java.util.Locale.ROOT);
            if (TRACEPARENT.equals(key)) {
                traceparent = entry.getValue();
            } else if (TRACESTATE.equals(key)) {
                tracestate = entry.getValue();
            }
        }
        return decodeTraceparent(traceparent, tracestate);
    }

    /**
     * 从 {@code traceparent} 头值解码上下文。
     *
     * @param traceparent 头值，可为 null
     * @param tracestate {@code tracestate} 头值，可为 null
     * @return 解码成功返回上下文，否则为空
     */
    public static Optional<TraceContext> decodeTraceparent(String traceparent, String tracestate) {
        if (traceparent == null || traceparent.isBlank()) {
            return Optional.empty();
        }
        String[] parts = traceparent.trim().split(SEPARATOR);
        if (parts.length < SEGMENTS) {
            return Optional.empty();
        }
        String version = parts[0].length() >= 2 ? parts[0].substring(0, 2) : parts[0];
        // W3C 规范：版本 00 时必须恰好 4 段；版本 >= 01 时 version 与 traceId 之间允许额外字段，
        // 此时 traceId / spanId / flags 取<b>最后三段</b>（前面的额外字段前向兼容忽略）。
        // 未知版本：traceId / spanId / flags 永远是<b>最后三段</b>
        // （SEGMENTS=4 → indices 1,2,3，即 length-3, length-2, length-1）
        int flagsIndex = parts.length - 1;
        String traceId;
        String spanId;
        String flags;
        if (TraceContext.VERSION.equals(version)) {
            if (parts.length != SEGMENTS) {
                // 版本 00 不允许额外字段
                return Optional.empty();
            }
            traceId = parts[1];
            spanId = parts[2];
            flags = parts[3];
        } else {
            // 未知版本：取最后三段做前向兼容，不直接拒绝
            traceId = parts[flagsIndex - 2];
            spanId = parts[flagsIndex - 1];
            flags = parts[flagsIndex];
        }
        if (flags.length() < 2) {
            return Optional.empty();
        }
        try {
            return Optional.of(
                    new TraceContext(traceId, spanId, null, flags.substring(0, 2), tracestate));
        } catch (IllegalArgumentException | NullPointerException ex) {
            return Optional.empty();
        }
    }

    /**
     * 解析 {@code baggage} 头为键值对。
     *
     * @param baggage 头值，可为 null
     * @return 解析结果；输入为空时返回空映射
     */
    public static Map<String, String> decodeBaggage(String baggage) {
        Map<String, String> result = new LinkedHashMap<>();
        if (baggage == null || baggage.isBlank()) {
            return result;
        }
        for (String pair : baggage.split(",")) {
            int idx = pair.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            result.put(pair.substring(0, idx).trim(), pair.substring(idx + 1).trim());
        }
        return result;
    }
}
