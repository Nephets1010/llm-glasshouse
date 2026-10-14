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

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * W3C TraceContext（契约编号 {@code CTX-001}）。
 *
 * <p>三仓共享的主键载体。字段顺序、单位、语义与 {@code llm-switchyard} / {@code agentswitchyard} 逐字符一致。
 *
 * <p>构造时做 fail-fast 校验（架构 §8.4）：traceId 必须是 32 位小写 hex，spanId 必须是 16 位小写 hex， {@code traceFlags}
 * 只允许 {@code "00"} / {@code "01"}。违规立即抛 {@link IllegalArgumentException}， 避免脏上下文流到下游导致链路断裂。
 *
 * @param traceId 32 位小写 hex
 * @param spanId 16 位小写 hex
 * @param parentSpanId 16 位小写 hex，根节点为 null
 * @param traceFlags {@code "01"} 表示 sampled，{@code "00"} 表示不采样
 * @param traceState 透传字段，不解析，可为 null
 */
public record TraceContext(
        String traceId, String spanId, String parentSpanId, String traceFlags, String traceState) {

    /** W3C 版本号，三仓固定为 {@code 00}。 */
    public static final String VERSION = "00";

    /** 已采样标记位。 */
    public static final String FLAGS_SAMPLED = "01";

    /** 未采样标记位。 */
    public static final String FLAGS_NOT_SAMPLED = "00";

    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern SPAN_ID_PATTERN = Pattern.compile("^[0-9a-f]{16}$");

    /**
     * 紧凑构造器，附带契约校验。
     *
     * @throws IllegalArgumentException 任一字段违反契约约束
     * @throws NullPointerException traceId / spanId / traceFlags 为 null
     */
    public TraceContext {
        Objects.requireNonNull(traceId, "traceId 不能为 null");
        Objects.requireNonNull(spanId, "spanId 不能为 null");
        Objects.requireNonNull(traceFlags, "traceFlags 不能为 null");
        if (!TRACE_ID_PATTERN.matcher(traceId).matches()) {
            throw new IllegalArgumentException("traceId 必须是 32 位小写 hex，实际为: " + traceId);
        }
        if (!SPAN_ID_PATTERN.matcher(spanId).matches()) {
            throw new IllegalArgumentException("spanId 必须是 16 位小写 hex，实际为: " + spanId);
        }
        if (parentSpanId != null && !SPAN_ID_PATTERN.matcher(parentSpanId).matches()) {
            throw new IllegalArgumentException(
                    "parentSpanId 必须是 16 位小写 hex 或 null，实际为: " + parentSpanId);
        }
        if (!FLAGS_SAMPLED.equals(traceFlags) && !FLAGS_NOT_SAMPLED.equals(traceFlags)) {
            throw new IllegalArgumentException("traceFlags 只允许 00 或 01，实际为: " + traceFlags);
        }
        if (traceState != null && traceState.length() > 512) {
            throw new IllegalArgumentException("traceState 长度不得超过 512 字符");
        }
    }

    /**
     * 构造根 span 的上下文（无父）。
     *
     * @param traceId 32 位小写 hex
     * @param spanId 16 位小写 hex
     * @param sampled 是否采样
     * @return 上下文实例
     */
    public static TraceContext root(String traceId, String spanId, boolean sampled) {
        return new TraceContext(
                traceId, spanId, null, sampled ? FLAGS_SAMPLED : FLAGS_NOT_SAMPLED, null);
    }

    /**
     * 构造子 span 的上下文。
     *
     * @param traceId 32 位小写 hex
     * @param spanId 16 位小写 hex
     * @param parentSpanId 父 spanId
     * @param sampled 是否采样
     * @return 上下文实例
     */
    public static TraceContext child(
            String traceId, String spanId, String parentSpanId, boolean sampled) {
        return new TraceContext(
                traceId, spanId, parentSpanId, sampled ? FLAGS_SAMPLED : FLAGS_NOT_SAMPLED, null);
    }

    /**
     * 判断该上下文是否被采样。
     *
     * @return sampled 返回 {@code true}
     */
    public boolean sampled() {
        return FLAGS_SAMPLED.equals(traceFlags);
    }

    /**
     * 以当前 span 为父生成子上下文，沿用 traceId 与采样标记。
     *
     * @param childSpanId 子 spanId
     * @return 子上下文
     */
    public TraceContext withChild(String childSpanId) {
        return new TraceContext(traceId, childSpanId, spanId, traceFlags, traceState);
    }

    /**
     * 判定两个上下文是否属于同一条链路。
     *
     * @param other 另一个上下文，可为 null
     * @return traceId 相同返回 {@code true}
     */
    public boolean sameTrace(TraceContext other) {
        return other != null && traceId.equals(other.traceId);
    }
}
