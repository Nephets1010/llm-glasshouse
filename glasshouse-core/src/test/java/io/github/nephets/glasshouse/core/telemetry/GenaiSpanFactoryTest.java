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

package io.github.nephets.glasshouse.core.telemetry;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GenaiSpanFactory} 的生命周期与属性口径测试。
 *
 * <p>用真实 SDK 记录断言，而不是 {@code Span.wrap(...)}：包装出来的 {@code PropagatedSpan} 的 {@code isRecording()} 为
 * false、{@code setAttribute} 静默丢弃，用它写出来的用例会「全绿但什么都没验到」。
 *
 * <p>本类只使用自己 new 出来的 {@link SdkTracerProvider}，不碰 {@code GlobalOpenTelemetry}， 避免与别的用例互相污染。
 */
class GenaiSpanFactoryTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider provider;
    private Tracer tracer;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        provider =
                SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build();
        tracer = provider.get("glasshouse-core-test");
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    /** 开一个尚未结束、处于记录中的 span。 */
    private Span newSpan() {
        return tracer.spanBuilder("seed").startSpan();
    }

    /** 断言缓冲里恰好结束了一条 span，并把它取出来。 */
    private SpanData finished() {
        List<SpanData> items = exporter.getFinishedSpanItems();
        assertThat(items).as("应恰好结束一条 span").hasSize(1);
        return items.get(0);
    }

    @Nested
    @DisplayName("start")
    class Start {

        @Test
        @DisplayName("★ 按 SPN-001 模板改名，并写入 gen_ai.operation.name")
        void renamesAndWritesOperationName() {
            Span span = newSpan();
            assertThat(GenaiSpanFactory.start(span, SpnNames.OP_CHAT, "deepseek-chat"))
                    .as("返回传入的 span，便于链式调用")
                    .isSameAs(span);
            span.end();

            SpanData data = finished();
            assertThat(data.getName()).isEqualTo("chat deepseek-chat");
            assertThat(data.getAttributes().get(GenaiAttributes.OPERATION_NAME)).isEqualTo("chat");
        }

        @ParameterizedTest(name = "target=\"{0}\" 时退化为只有 operation")
        @ValueSource(strings = {"", "   "})
        @DisplayName("★ target 为空白时不产生尾随空格")
        void blankTargetHasNoTrailingSpace(String target) {
            Span span = newSpan();
            GenaiSpanFactory.start(span, SpnNames.OP_APPROVAL, target);
            span.end();

            assertThat(finished().getName())
                    .as("空白 target 应退化为「只有 operation」，不能留下尾随空格")
                    .isEqualTo("approval");
        }

        @Test
        @DisplayName("target 带前后空白时被 trim，中间只有一个空格")
        void targetIsTrimmed() {
            Span span = newSpan();
            GenaiSpanFactory.start(span, SpnNames.OP_EXECUTE_TOOL, "  kb_lookup  ");
            span.end();

            assertThat(finished().getName()).isEqualTo("execute_tool kb_lookup");
        }
    }

    @Nested
    @DisplayName("writeUsage")
    class WriteUsage {

        @Test
        @DisplayName("★ 新名与旧名双写四个 usage 属性（兼容存量看板）")
        void writesBothGenerations() {
            Span span = newSpan();
            GenaiSpanFactory.writeUsage(span, 1200L, 340L);
            span.end();

            SpanData data = finished();
            assertThat(data.getAttributes().get(GenaiAttributes.USAGE_INPUT_TOKENS))
                    .isEqualTo(1200L);
            assertThat(data.getAttributes().get(GenaiAttributes.USAGE_OUTPUT_TOKENS))
                    .isEqualTo(340L);
            assertThat(data.getAttributes().get(GenaiAttributes.USAGE_PROMPT_TOKENS_LEGACY))
                    .as("旧名冗余一个发布周期，存量看板还在读它")
                    .isEqualTo(1200L);
            assertThat(data.getAttributes().get(GenaiAttributes.USAGE_COMPLETION_TOKENS_LEGACY))
                    .isEqualTo(340L);
        }

        @Test
        @DisplayName("★ writeUsage 不结束 span（这就是它从 endWithUsage 改名的原因）")
        void doesNotEndSpan() {
            Span span = newSpan();
            GenaiSpanFactory.writeUsage(span, 10L, 20L);

            assertThat(span.isRecording()).as("写 usage 之后 span 必须还在记录中").isTrue();
            assertThat(exporter.getFinishedSpanItems()).as("还不该有 span 被导出").isEmpty();

            span.end();
            assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        }

        @Test
        @DisplayName("★ writeUsage 之后继续写 swy.* / gen_ai.* 仍然有效（endWithUsage 丢属性 bug 的回归锁）")
        void attributesAfterUsageAreNotSilentlyDropped() {
            Span span = newSpan();
            GenaiSpanFactory.writeUsage(span, 1200L, 340L);
            GenaiSpanFactory.setSwy(span, s -> s.setAttribute(SwyAttributes.TENANT_ID, "vip"));
            GenaiSpanFactory.setGenai(
                    span, s -> s.setAttribute(GenaiAttributes.REQUEST_MODEL, "deepseek-chat"));
            span.end();

            SpanData data = finished();
            assertThat(data.getAttributes().get(SwyAttributes.TENANT_ID))
                    .as("★ 写 usage 之后写的 swy.* 必须还在，否则五维归因全部丢失且不报错")
                    .isEqualTo("vip");
            assertThat(data.getAttributes().get(GenaiAttributes.REQUEST_MODEL))
                    .isEqualTo("deepseek-chat");
            assertThat(data.getAttributes().get(GenaiAttributes.USAGE_INPUT_TOKENS))
                    .isEqualTo(1200L);
        }
    }

    @Nested
    @DisplayName("end / endWithError")
    class Ending {

        @Test
        @DisplayName("end 结束 span，状态保持 UNSET")
        void endClosesSpan() {
            Span span = newSpan();
            assertThat(span.isRecording()).isTrue();

            GenaiSpanFactory.end(span);

            assertThat(span.isRecording()).as("结束后不再记录，后续 setAttribute 会被静默丢弃").isFalse();
            assertThat(finished().getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
        }

        @Test
        @DisplayName("★ endWithError 标记 ERROR 状态、写入异常描述并记一条 exception 事件")
        void endWithErrorMarksError() {
            Span span = newSpan();
            GenaiSpanFactory.endWithError(span, new IllegalStateException("上游 429"));

            SpanData data = finished();
            assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            assertThat(data.getStatus().getDescription()).isEqualTo("上游 429");
            assertThat(data.getEvents()).hasSize(1);
            assertThat(data.getEvents().get(0).getName()).isEqualTo("exception");
            assertThat(
                            data.getEvents()
                                    .get(0)
                                    .getAttributes()
                                    .get(AttributeKey.stringKey("exception.message")))
                    .isEqualTo("上游 429");
        }
    }

    @Nested
    @DisplayName("setIfPresent")
    class SetIfPresent {

        @ParameterizedTest(name = "value=\"{0}\" 时不写属性")
        @ValueSource(strings = {"", "   "})
        @DisplayName("★ 空白值不写属性（EVT-001：不写占位值）")
        void skipsBlank(String value) {
            Span span = newSpan();
            GenaiSpanFactory.setIfPresent(span, SwyAttributes.TENANT_ID, value);
            span.end();

            assertThat(finished().getAttributes().get(SwyAttributes.TENANT_ID))
                    .as("归因维度缺失时必须「不写」，写 unknown 之类的占位值会污染下钻统计")
                    .isNull();
        }

        @Test
        @DisplayName("null 值不写属性")
        void skipsNull() {
            Span span = newSpan();
            GenaiSpanFactory.setIfPresent(span, SwyAttributes.TENANT_ID, null);
            span.end();

            assertThat(finished().getAttributes().get(SwyAttributes.TENANT_ID)).isNull();
        }

        @Test
        @DisplayName("非空值原样写入，不做 trim")
        void writesVerbatimWhenPresent() {
            Span span = newSpan();
            GenaiSpanFactory.setIfPresent(span, SwyAttributes.FEATURE, " customer-service ");
            GenaiSpanFactory.setIfPresent(span, SwyAttributes.TENANT_ID, "vip");
            span.end();

            assertThat(finished().getAttributes().get(SwyAttributes.FEATURE))
                    .as("setIfPresent 只判断「有没有」，不负责清洗内容")
                    .isEqualTo(" customer-service ");
            assertThat(finished().getAttributes().get(SwyAttributes.TENANT_ID)).isEqualTo("vip");
        }
    }

    @Nested
    @DisplayName("setSwy / setGenai")
    class Callbacks {

        @Test
        @DisplayName("setSwy 真的执行回调，并返回同一个 span")
        void setSwyRunsWriter() {
            Span span = newSpan();
            AtomicBoolean ran = new AtomicBoolean(false);

            Span returned =
                    GenaiSpanFactory.setSwy(
                            span,
                            s -> {
                                ran.set(true);
                                s.setAttribute(SwyAttributes.TENANT_ID, "vip");
                            });

            assertThat(ran).as("回调必须被执行，否则属性写入被静默吞掉").isTrue();
            assertThat(returned).isSameAs(span);

            span.end();
            assertThat(finished().getAttributes().get(SwyAttributes.TENANT_ID)).isEqualTo("vip");
        }

        @Test
        @DisplayName("setGenai 真的执行回调，并返回同一个 span")
        void setGenaiRunsWriter() {
            Span span = newSpan();
            AtomicBoolean ran = new AtomicBoolean(false);

            Span returned =
                    GenaiSpanFactory.setGenai(
                            span,
                            s -> {
                                ran.set(true);
                                s.setAttribute(GenaiAttributes.PROVIDER_NAME, "deepseek");
                            });

            assertThat(ran).as("回调必须被执行，否则属性写入被静默吞掉").isTrue();
            assertThat(returned).isSameAs(span);

            span.end();
            assertThat(finished().getAttributes().get(GenaiAttributes.PROVIDER_NAME))
                    .isEqualTo("deepseek");
        }
    }
}
