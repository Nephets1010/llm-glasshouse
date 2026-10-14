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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * W3C TraceContext 契约测试（CTX-001）。
 *
 * <p><b>架构 §2.1 的一致性硬约束</b>：三个仓库必须对同一输入产生<b>字节级相同</b>的 {@code
 * traceparent}。本类的固定向量就是这道约束的执行手段——任何一方改了编码规则， 另一方跑这个测试就会红。
 */
class W3CTraceCodecContractTest {

    /** 契约固定向量：与 llm-switchyard / agentswitchyard 逐字节一致。 */
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private static final String SPAN_ID = "00f067aa0ba902b7";

    /** 契约固定向量：expected traceparent 字符串。 */
    private static final String TRACEPARENT =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Test
    @DisplayName("★ 编码：traceparent 字节级固定（跨仓一致性向量）")
    void encodeIsByteStable() {
        TraceContext sampled = TraceContext.root(TRACE_ID, SPAN_ID, true);
        assertThat(W3CTraceCodec.encodeTraceparent(sampled))
                .as("★ 这条断言就是三仓编码规则一致性的执行手段")
                .isEqualTo(TRACEPARENT);

        TraceContext notSampled = TraceContext.root(TRACE_ID, SPAN_ID, false);
        assertThat(W3CTraceCodec.encodeTraceparent(notSampled))
                .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00");

        TraceContext child = TraceContext.child(TRACE_ID, SPAN_ID, SPAN_ID, true);
        assertThat(W3CTraceCodec.encodeTraceparent(child)).isEqualTo(TRACEPARENT);
    }

    @Test
    @DisplayName("解码：能还原出编码前的上下文（往返一致）")
    void decodeRoundTrip() {
        TraceContext original = TraceContext.root(TRACE_ID, SPAN_ID, true);
        Optional<TraceContext> decoded =
                W3CTraceCodec.decode(
                        Map.of(
                                W3CTraceCodec.TRACEPARENT,
                                W3CTraceCodec.encodeTraceparent(original)));
        assertThat(decoded).isPresent();
        assertThat(decoded.get().traceId()).isEqualTo(TRACE_ID);
        assertThat(decoded.get().spanId()).isEqualTo(SPAN_ID);
        assertThat(decoded.get().traceFlags()).isEqualTo("01");
        assertThat(decoded.get().sampled()).isTrue();
        assertThat(decoded.get().parentSpanId()).as("traceparent 里没有父信息，解码后为 null").isNull();
    }

    @Test
    @DisplayName("头名大小写不敏感")
    void headerNameIsCaseInsensitive() {
        Map<String, String> headers = new HashMap<>();
        headers.put("TraceParent", TRACEPARENT);
        headers.put("TRACESTATE", "vendor=abc");
        Optional<TraceContext> decoded = W3CTraceCodec.decode(headers);
        assertThat(decoded).isPresent();
        assertThat(decoded.get().traceState()).isEqualTo("vendor=abc");
    }

    @ParameterizedTest(name = "脏 header \"{0}\" 安全降级为空（不抛异常）")
    @ValueSource(
            strings = {
                "", // 空
                "00", // 段数不足
                "00-abc", // 段数不足
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01-extra", // 版本 00 不允许额外字段
                "00-XYZ-00f067aa0ba902b7-01", // traceId 非 hex
                "00-4bf92f3577b34da6a3ce929d0e0e4736-ZZZZ-01", // spanId 非 hex
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-02", // 非法 flags
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-1", // flags 太短
            })
    @DisplayName("脏 header 不抛异常，退化为新链路")
    void malformedHeadersDegradeGracefully(String traceparent) {
        assertThat(W3CTraceCodec.decodeTraceparent(traceparent, null))
                .as("上游传来的脏 header 不应导致业务请求失败，只应退化为新链路")
                .isEmpty();
    }

    @Test
    @DisplayName("null / 缺 header 的处理")
    void nullSafety() {
        assertThat(W3CTraceCodec.decode(null)).isEmpty();
        assertThat(W3CTraceCodec.decode(Map.of())).isEmpty();
        assertThat(W3CTraceCodec.decodeTraceparent(null, null)).isEmpty();
        assertThat(W3CTraceCodec.decodeTraceparent("   ", null)).isEmpty();
        Map<String, String> withNullKey = new HashMap<>();
        withNullKey.put(null, "x");
        assertThat(W3CTraceCodec.decode(withNullKey)).isEmpty();
    }

    @Test
    @DisplayName("未知版本号按最后三段前向兼容解析（不直接拒绝）")
    void unknownVersionIsForwardCompatible() {
        // W3C 规范：版本 >= 01 时 version 与 traceId 之间可插入未来字段，
        // 此时 traceId / spanId / flags 是最后三段。直接拒绝会让未来的 W3C 版本把我们的链路全断掉。
        assertThat(
                        W3CTraceCodec.decodeTraceparent(
                                "01-extra-" + TRACE_ID + "-" + SPAN_ID + "-01", null))
                .as("未知版本应取最后三段做前向兼容，而不是拒绝")
                .isPresent();
        assertThat(
                        W3CTraceCodec.decodeTraceparent(
                                        "01-extra-" + TRACE_ID + "-" + SPAN_ID + "-01", null)
                                .orElseThrow()
                                .traceId())
                .isEqualTo(TRACE_ID);
        assertThat(W3CTraceCodec.decodeTraceparent("cc-" + TRACE_ID + "-" + SPAN_ID + "-01", null))
                .as("任意未知版本都前向兼容")
                .isPresent();

        // 版本 00 时不允许额外字段（否则 traceId 会被错位解析）
        assertThat(
                        W3CTraceCodec.decodeTraceparent(
                                "00-extra-" + TRACE_ID + "-" + SPAN_ID + "-01", null))
                .as("版本 00 下出现额外字段属于格式错误")
                .isEmpty();
    }

    @Test
    @DisplayName("encode() 返回传播头集合，tracestate 为空时不出现该键")
    void encodeHeaders() {
        TraceContext withState = new TraceContext(TRACE_ID, SPAN_ID, null, "01", "vendor=abc");
        Map<String, String> headers = W3CTraceCodec.encode(withState);
        assertThat(headers).containsEntry(W3CTraceCodec.TRACEPARENT, TRACEPARENT);
        assertThat(headers).containsEntry(W3CTraceCodec.TRACESTATE, "vendor=abc");

        Map<String, String> withoutState =
                W3CTraceCodec.encode(TraceContext.root(TRACE_ID, SPAN_ID, true));
        assertThat(withoutState).containsOnlyKeys(W3CTraceCodec.TRACEPARENT);
    }

    @Test
    @DisplayName("baggage 编解码：承载五维归因维度")
    void baggageRoundTrip() {
        Map<String, String> dims = new LinkedHashMap<>();
        dims.put("swy.tenant.id", "vip");
        dims.put("swy.feature", "customer-service");
        dims.put("swy.prompt.version", "planner@v3");
        dims.put("skipped", null);

        String encoded = W3CTraceCodec.encodeBaggage(dims);
        assertThat(encoded).contains("swy.tenant.id=vip").doesNotContain("skipped");

        Map<String, String> decoded = W3CTraceCodec.decodeBaggage(encoded);
        assertThat(decoded)
                .containsEntry("swy.tenant.id", "vip")
                .containsEntry("swy.feature", "customer-service")
                .containsEntry("swy.prompt.version", "planner@v3");
    }

    @Test
    @DisplayName("baggage 边界：null / 空 / 畸形条目")
    void baggageEdgeCases() {
        assertThat(W3CTraceCodec.encodeBaggage(null)).isEmpty();
        assertThat(W3CTraceCodec.encodeBaggage(Map.of())).isEmpty();
        assertThat(W3CTraceCodec.decodeBaggage(null)).isEmpty();
        assertThat(W3CTraceCodec.decodeBaggage("  ")).isEmpty();
        assertThat(W3CTraceCodec.decodeBaggage("no-equals-sign,=novalue,k=v"))
                .as("畸形条目被跳过而不是整体失败")
                .containsExactly(Map.entry("k", "v"));
    }

    @Test
    @DisplayName("TraceContext 校验：非法字段 fail-fast")
    void traceContextValidation() {
        assertThatThrownBy(() -> new TraceContext("SHORT", SPAN_ID, null, "01", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 位小写 hex");
        assertThatThrownBy(() -> new TraceContext(TRACE_ID, "SHORT", null, "01", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("16 位小写 hex");
        assertThatThrownBy(() -> new TraceContext(TRACE_ID, SPAN_ID, "BAD", "01", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TraceContext(TRACE_ID, SPAN_ID, null, "02", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("00 或 01");
        assertThatThrownBy(() -> new TraceContext(null, SPAN_ID, null, "01", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TraceContext(TRACE_ID, SPAN_ID, null, "01", "x".repeat(600)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("512");
    }

    @Test
    @DisplayName("TraceContext：父子关系与 sameTrace")
    void traceContextRelations() {
        TraceContext root = TraceContext.root(TRACE_ID, SPAN_ID, true);
        TraceContext child = root.withChild("a1b2c3d4e5f6a7b8");
        assertThat(child.traceId()).isEqualTo(TRACE_ID);
        assertThat(child.parentSpanId()).isEqualTo(SPAN_ID);
        assertThat(child.traceFlags()).isEqualTo("01");

        assertThat(root.sameTrace(child)).isTrue();
        assertThat(root.sameTrace(TraceContext.root("0".repeat(32), SPAN_ID, true))).isFalse();
        assertThat(root.sameTrace(null)).isFalse();
        assertThat(TraceContext.VERSION).isEqualTo("00");
        assertThat(TraceContext.FLAGS_SAMPLED).isEqualTo("01");
        assertThat(TraceContext.FLAGS_NOT_SAMPLED).isEqualTo("00");
    }

    @ParameterizedTest(name = "child spanId {0} 非法")
    @ValueSource(strings = {"", "SHORT", "0123456789ABCDEF"})
    @DisplayName("withChild 同样校验 spanId")
    void withChildValidates(String badSpanId) {
        TraceContext root = TraceContext.root(TRACE_ID, SPAN_ID, true);
        assertThatThrownBy(() -> root.withChild(badSpanId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("★ SpanContextCarrier：上下文设置 / 读取 / 嵌套恢复 / 清理")
    void carrierBasics() {
        SpanContextCarrier carrier = SpanContextCarrier.getInstance();
        carrier.clear();
        try {
            assertThat(carrier.current()).isEmpty();
            assertThat(carrier.currentTraceId()).isEmpty();

            TraceContext ctx = TraceContext.root(TRACE_ID, SPAN_ID, true);
            carrier.set(ctx);
            assertThat(carrier.current()).contains(ctx);
            assertThat(carrier.currentTraceId()).contains(TRACE_ID);

            // 嵌套：内层结束后必须恢复外层，避免上下文串味
            String innerResult =
                    carrier.callWith(
                            TraceContext.child(TRACE_ID, "a1b2c3d4e5f6a7b8", SPAN_ID, true),
                            () -> carrier.currentTraceId().orElse("none"));
            assertThat(innerResult).isEqualTo(TRACE_ID);
            assertThat(carrier.current()).contains(ctx);

            carrier.set(null);
            assertThat(carrier.current()).isEmpty();
            carrier.set(ctx);
            carrier.clear();
            assertThat(carrier.current()).isEmpty();
        } finally {
            carrier.clear();
        }
    }

    @Test
    @DisplayName("SpanContextCarrier：runWith 异常时也恢复上下文")
    void carrierRestoresOnException() {
        SpanContextCarrier carrier = SpanContextCarrier.getInstance();
        carrier.clear();
        try {
            TraceContext outer = TraceContext.root(TRACE_ID, SPAN_ID, true);
            carrier.set(outer);
            assertThatThrownBy(
                            () ->
                                    carrier.runWith(
                                            TraceContext.child(
                                                    TRACE_ID, "a1b2c3d4e5f6a7b8", SPAN_ID, false),
                                            () -> {
                                                throw new IllegalStateException("boom");
                                            }))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(carrier.current()).as("★ 异常路径也必须恢复上下文，否则埋点会一直带着错误的 traceId").contains(outer);
        } finally {
            carrier.clear();
        }
    }

    @Test
    @DisplayName("SpanContextCarrier：wrap 让任务携带上下文跨线程执行")
    void carrierWrapAcrossThreads() throws Exception {
        SpanContextCarrier carrier = SpanContextCarrier.getInstance();
        carrier.clear();
        try {
            carrier.set(TraceContext.root(TRACE_ID, SPAN_ID, true));

            // 当前线程有上下文 → 包装后的任务应携带
            Runnable wrapped =
                    carrier.wrap(
                            (Runnable)
                                    () -> assertThat(carrier.currentTraceId()).contains(TRACE_ID));
            wrapped.run();

            Callable<String> wrappedCallable =
                    carrier.wrap((Callable<String>) () -> carrier.currentTraceId().orElse("none"));
            assertThat(wrappedCallable.call()).isEqualTo(TRACE_ID);

            // 在另一个线程执行：InheritableThreadLocal 对已存在的线程池线程不生效，
            // 正是 wrap 存在的意义
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                String onOtherThread =
                        pool.submit(
                                        carrier.wrap(
                                                (Callable<String>)
                                                        () ->
                                                                carrier.currentTraceId()
                                                                        .orElse("none")))
                                .get(5, TimeUnit.SECONDS);
                assertThat(onOtherThread)
                        .as("wrap 必须让上下文跨线程传播（线程池场景下 InheritableThreadLocal 不生效）")
                        .isEqualTo(TRACE_ID);
            } finally {
                pool.shutdownNow();
            }

            // 无上下文时原样返回同一个对象（不制造无意义的包装）
            carrier.clear();
            Runnable plain = () -> {};
            assertThat(carrier.<Runnable>wrap(plain)).isSameAs(plain);
            Callable<String> plainCallable = () -> "x";
            assertThat(carrier.<String>wrap(plainCallable)).isSameAs(plainCallable);
        } finally {
            carrier.clear();
        }
    }

    @ParameterizedTest(name = "版本固定为 00")
    @CsvSource({"00", "00"})
    @DisplayName("W3C 版本号常量在三仓一致")
    void versionConstant(String expected) {
        assertThat(TraceContext.VERSION).isEqualTo(expected);
        assertThat(W3CTraceCodec.SEGMENTS).isEqualTo(4);
        assertThat(W3CTraceCodec.SEPARATOR).isEqualTo("-");
        assertThat(W3CTraceCodec.TRACEPARENT).isEqualTo("traceparent");
        assertThat(W3CTraceCodec.TRACESTATE).isEqualTo("tracestate");
        assertThat(W3CTraceCodec.BAGGAGE).isEqualTo("baggage");
    }
}
