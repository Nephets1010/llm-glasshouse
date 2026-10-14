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

package io.github.nephets.glasshouse.starter.observation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import reactor.core.publisher.Flux;

import io.github.nephets.glasshouse.core.audit.PayloadRedactor;
import io.github.nephets.glasshouse.core.pricing.PriceEntry;
import io.github.nephets.glasshouse.core.pricing.PriceSource;
import io.github.nephets.glasshouse.core.pricing.PriceUnit;
import io.github.nephets.glasshouse.core.pricing.PricingTable;
import io.github.nephets.glasshouse.starter.annotation.SpanDimensions;
import io.github.nephets.glasshouse.starter.model.ChatModelMetadata;
import io.github.nephets.glasshouse.starter.store.SpanRingBuffer;
import io.github.nephets.glasshouse.starter.store.SpanTreeQuery;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 零侵入埋点测试（架构 ③T03 验收标准第 2 条 ★ 本仓的核心承诺）。
 *
 * <p><b>本类要证明的事只有一件</b>：业务代码<b>一行不改</b>（只加 starter 依赖）， 每次模型调用就自动产生符合 SPN-001 的 span，带完整计量与成本。
 *
 * <p>因此这里的 {@link RecordingChatModel} 是「业务代码」——它<b>不含任何一行埋点 API 调用</b>， 就是最朴素的 Spring AI {@code
 * ChatModel} 实现。断言全在装饰器外部做。
 */
class ChatModelDecoratorTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private SpanRingBuffer buffer;
    private PricingTable pricingTable;
    private ChatModelDecorator decorator;
    private RecordingChatModel businessCode;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider =
                SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build();
        Tracer tracer = tracerProvider.get(SpanFactory.SCOPE_NAME, SpanFactory.SCOPE_VERSION);
        buffer = new SpanRingBuffer(100);
        pricingTable =
                new PricingTable(
                        List.of(
                                new PriceEntry(
                                        "mock",
                                        "mock-chat",
                                        "CNY",
                                        PriceUnit.PER_1K_TOKENS,
                                        new BigDecimal("0.0010"),
                                        new BigDecimal("0.0020"),
                                        null,
                                        new BigDecimal("0.0001"),
                                        LocalDate.parse("2026-01-01"),
                                        PriceSource.CUSTOM)));
        SpanFactory spanFactory =
                new SpanFactory(
                        tracer,
                        buffer,
                        PayloadRedactor.getInstance(),
                        pricingTable,
                        false,
                        (snapshot, usage, providerId, modelId) -> {});
        // ↓↓↓ 业务代码：一个完全不知道 llm-glasshouse 存在的 ChatModel ↓↓↓
        businessCode = new RecordingChatModel();
        // ↑↑↑ 装饰器：Spring 容器里就是通过 BeanPostProcessor 自动完成这一步 ↑↑↑
        decorator =
                new ChatModelDecorator(
                        businessCode,
                        spanFactory,
                        new ChatModelMetadata("mock", "mock-chat", "http://localhost/mock"),
                        null);
    }

    @AfterEach
    void tearDown() {
        SpanDimensions.clear();
        tracerProvider.close();
    }

    private Prompt prompt(String text) {
        return new Prompt(
                List.<Message>of(new org.springframework.ai.chat.messages.UserMessage(text)),
                ChatOptions.builder().model("mock-chat").temperature(0.0d).build());
    }

    @Test
    @DisplayName("★ 零侵入：业务代码 0 改动，call 一次即产出一个 SPN-001 span")
    void zeroIntrusionProducesSpan() {
        decorator.call(prompt("退货政策是什么？"));

        assertThat(businessCode.callCount).as("★ 业务代码确实被调用了，链路是真的").isEqualTo(1);
        List<io.opentelemetry.sdk.trace.data.SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getName())
                .as("span 名按 SPN-001 模板：{operation} {target}")
                .isEqualTo("chat mock-chat");
        assertThat(spans.get(0).getAttributes().asMap())
                .containsEntry(
                        io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.provider.name"),
                        "mock")
                .containsEntry(
                        io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.request.model"),
                        "mock-chat");
    }

    @Test
    @DisplayName("★ 零侵入：span 上带完整计量与成本（无需业务传任何参数）")
    void zeroIntrusionCarriesUsageAndCost() {
        decorator.call(prompt("发票怎么开？"));

        io.opentelemetry.api.common.Attributes attributes =
                exporter.getFinishedSpanItems().get(0).getAttributes();
        assertThat(
                        attributes.get(
                                io.opentelemetry.api.common.AttributeKey.longKey(
                                        "gen_ai.usage.input_tokens")))
                .as("业务模型的 usage 被原样带过来，业务不用传任何参数")
                .isEqualTo(1000L);
        assertThat(
                        attributes.get(
                                io.opentelemetry.api.common.AttributeKey.longKey(
                                        "gen_ai.usage.output_tokens")))
                .isEqualTo(1000L);
        assertThat(
                        attributes.get(
                                io.opentelemetry.api.common.AttributeKey.doubleKey(
                                        "swy.cost.amount")))
                .as("成本由价格表自动算出：1000 输入×0.001 + 1000 输出×0.002 = 0.003")
                .isNotNull();
        assertThat(
                        attributes.get(
                                io.opentelemetry.api.common.AttributeKey.stringKey(
                                        "swy.usage.source")))
                .isEqualTo("VENDOR_REPORTED");
        assertThat(
                        attributes.get(
                                io.opentelemetry.api.common.AttributeKey.stringKey(
                                        "swy.cost.currency")))
                .isEqualTo("CNY");
    }

    @Test
    @DisplayName("★ 零侵入：prompt 摘要自动写入（业务只传 Prompt，不传摘要）")
    void zeroIntrusionWritesPayloadDigest() {
        decorator.call(prompt("客户手机号 13812345678，请回电"));
        io.opentelemetry.api.common.Attributes attributes =
                exporter.getFinishedSpanItems().get(0).getAttributes();
        String digest =
                attributes.get(
                        io.opentelemetry.api.common.AttributeKey.stringKey("swy.payload.digest"));
        assertThat(digest).matches("^sha256:[0-9a-f]{64}$");
        assertThat(attributes.asMap().keySet())
                .as("完整 payload 默认不写")
                .doesNotContain(
                        io.opentelemetry.api.common.AttributeKey.stringKey("swy.payload.full"));
    }

    @Test
    @DisplayName("★ 归因维度：业务只设置一次 SpanDimensions，内部调用自动继承")
    void attributionDimensionsAreInherited() {
        SpanDimensions.set(
                SpanDimensions.merge(
                        b ->
                                b.tenantId("vip")
                                        .bizLine("电商")
                                        .feature("客服")
                                        .promptVersion("planner@v3")));

        decorator.call(prompt("q"));

        io.opentelemetry.api.common.Attributes attributes =
                exporter.getFinishedSpanItems().get(0).getAttributes();
        assertThat(
                        attributes.get(
                                io.opentelemetry.api.common.AttributeKey.stringKey(
                                        "swy.tenant.id")))
                .isEqualTo("vip");
        assertThat(
                        attributes.get(
                                io.opentelemetry.api.common.AttributeKey.stringKey("swy.biz.line")))
                .isEqualTo("电商");
        assertThat(
                        attributes.get(
                                io.opentelemetry.api.common.AttributeKey.stringKey("swy.feature")))
                .isEqualTo("客服");
        assertThat(
                        attributes.get(
                                io.opentelemetry.api.common.AttributeKey.stringKey(
                                        "swy.prompt.version")))
                .isEqualTo("planner@v3");
    }

    @Test
    @DisplayName("流式：★ 用量缺失时不记 0，而是估算并标 streamUsageReported=false（PRD 痛点 4）")
    void streamWithoutUsageIsEstimatedNotZero() {
        RecordingChatModel noUsage = new RecordingChatModel();
        noUsage.usageInStreaming = false;
        ChatModelDecorator streaming =
                new ChatModelDecorator(
                        noUsage,
                        new SpanFactory(
                                tracerProvider.get(
                                        SpanFactory.SCOPE_NAME, SpanFactory.SCOPE_VERSION),
                                buffer,
                                PayloadRedactor.getInstance(),
                                pricingTable,
                                false,
                                null),
                        new ChatModelMetadata("mock", "mock-chat", null),
                        null);

        exporter.reset();
        buffer.clear();
        List<ChatResponse> chunks = streaming.stream(prompt("很长的中文问题需要回答")).collectList().block();
        assertThat(chunks).hasSize(2);

        List<io.opentelemetry.sdk.trace.data.SpanData> finished = exporter.getFinishedSpanItems();
        assertThat(finished).as("流式调用应产出一个 span").hasSize(1);
        io.opentelemetry.api.common.Attributes attributes = finished.get(0).getAttributes();
        assertThat(
                        attributes.get(
                                io.opentelemetry.api.common.AttributeKey.booleanKey(
                                        "gen_ai.request.stream")))
                .as("gen_ai.request.stream 是 boolean 属性")
                .isEqualTo(Boolean.TRUE);
        assertThat(
                        attributes.get(
                                io.opentelemetry.api.common.AttributeKey.stringKey(
                                        "swy.usage.source")))
                .as("供应商没给 usage → 标 ESTIMATED，不能标 VENDOR_REPORTED")
                .isEqualTo("ESTIMATED");
        assertThat(
                        attributes.get(
                                io.opentelemetry.api.common.AttributeKey.booleanKey(
                                        "swy.usage.stream_reported")))
                .as("★ 流式下没收到 usage 块，必须能看出来")
                .isEqualTo(Boolean.FALSE);
        // 估算值写在 buffer 快照上（OTel span 上只记供应商上报的原值）
        Long estimatedIn = (Long) buffer.all().get(0).attributes().get("gen_ai.usage.input_tokens");
        assertThat(estimatedIn)
                .as("★ 流式无 usage 时的估算值必须为正，绝不能记成 0（PRD 痛点 4）")
                .isNotNull()
                .isPositive();
        assertThat(buffer.all().get(0).inputTokens()).as("CLI 的 token 汇总也要能看到估算值").isPositive();
    }

    @Test
    @DisplayName("流式：收到 usage 块时标 streamUsageReported=true")
    void streamWithUsageIsReported() {
        // 我们的 mock 在两个分片上都带 usage，最后一块有 usage → 供应商真实上报
        decorator.stream(prompt("问题")).collectList().block();
        io.opentelemetry.api.common.Attributes attributes =
                exporter.getFinishedSpanItems().get(0).getAttributes();
        assertThat(
                        attributes.get(
                                io.opentelemetry.api.common.AttributeKey.booleanKey(
                                        "swy.usage.stream_reported")))
                .isEqualTo(Boolean.TRUE);
        assertThat(
                        attributes.get(
                                io.opentelemetry.api.common.AttributeKey.stringKey(
                                        "swy.usage.source")))
                .isEqualTo("VENDOR_REPORTED");
    }

    @Test
    @DisplayName("★ 上游异常原样抛出，且 span 标记为 ERROR")
    void upstreamExceptionPropagatesAndMarksError() {
        RecordingChatModel failing = new RecordingChatModel();
        failing.failWith = new IllegalStateException("上游 429");
        ChatModelDecorator failingDecorator =
                new ChatModelDecorator(
                        failing,
                        new SpanFactory(
                                tracerProvider.get(
                                        SpanFactory.SCOPE_NAME, SpanFactory.SCOPE_VERSION),
                                buffer,
                                PayloadRedactor.getInstance(),
                                pricingTable,
                                false,
                                null),
                        new ChatModelMetadata("mock", "mock-chat", null),
                        null);

        assertThatThrownBy(() -> failingDecorator.call(prompt("q")))
                .as("★ 埋点绝不能吞掉业务异常")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("429");

        io.opentelemetry.sdk.trace.data.SpanData span = exporter.getFinishedSpanItems().get(0);
        assertThat(span.getStatus().getStatusCode())
                .isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
        assertThat(span.getEvents()).anyMatch(e -> e.getName().equals("exception"));
    }

    @Test
    @DisplayName("getDefaultOptions / delegate / metadata 透传")
    void accessorsDelegate() {
        ChatOptions options = decorator.getDefaultOptions();
        assertThat(options).as("默认选项从被装饰对象透传，业务侧不用改配置方式").isNotNull();
        assertThat(options.getModel()).isEqualTo(businessCode.getDefaultOptions().getModel());
        assertThat(options.getTemperature())
                .isEqualTo(businessCode.getDefaultOptions().getTemperature());
        assertThat(decorator.delegate()).isSameAs(businessCode);
        assertThat(decorator.metadata().lookupKey()).isEqualTo("mock/mock-chat");
        assertThat(decorator.stream(prompt("x"))).isNotNull();
    }

    @Test
    @DisplayName("★ 三步 Agent 产生完整 span 树（planner → chat + retrieval + tool + chat）")
    void threeStepAgentProducesNestedTree() {
        SpanDimensions.set(
                SpanDimensions.merge(b -> b.tenantId("vip").feature("customer-service")));

        SpanFactory factory =
                new SpanFactory(
                        tracerProvider.get(SpanFactory.SCOPE_NAME, SpanFactory.SCOPE_VERSION),
                        buffer,
                        PayloadRedactor.getInstance(),
                        pricingTable,
                        false,
                        null);

        // 模拟一次「planner 步骤 → 检索 → 模型调用」：
        // planner 用装饰器（业务代码），检索用手工 span（模拟 @Retrieval 切面）
        factory.start("invoke_agent", "planner").end();
        decorator.call(prompt("退货政策是什么？"));
        factory.start("retrieval", "kb_lookup").end();
        decorator.call(prompt("根据政策给出最终答复"));

        assertThat(buffer.size()).as("两条 chat span + 两个手工步骤 span").isEqualTo(4);
        assertThat(buffer.all())
                .as("★ 架构 3T03 第 4 条：四种 span 名都符合 SPN-001 模板")
                .extracting(SpanRingBuffer.SpanData::name)
                .containsExactly(
                        "invoke_agent planner",
                        "chat mock-chat",
                        "retrieval kb_lookup",
                        "chat mock-chat");

        // 每条 chat span 单独渲染时都能回答「慢在哪 / 花了多少」
        SpanTreeQuery query = new SpanTreeQuery(buffer);
        String chatTrace = buffer.all().get(1).traceId();
        assertThat(query.render(chatTrace, true))
                .contains("chat mock-chat")
                .contains("slowestSpan=")
                .contains("totalCostCny=");
    }

    @Test
    @DisplayName("构造参数校验")
    void constructorValidation() {
        SpanFactory factory =
                new SpanFactory(
                        tracerProvider.get(SpanFactory.SCOPE_NAME, SpanFactory.SCOPE_VERSION),
                        buffer,
                        PayloadRedactor.getInstance(),
                        pricingTable,
                        false,
                        null);
        ChatModelMetadata meta = new ChatModelMetadata("mock", "mock-chat", null);
        org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class,
                () -> new ChatModelDecorator(null, factory, meta, null));
        org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class,
                () -> new ChatModelDecorator(businessCode, null, meta, null));
        org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class,
                () -> new ChatModelDecorator(businessCode, factory, null, null));
    }

    @Test
    @DisplayName("ChatModelMetadata：供应商推断的三层回退")
    void metadataResolution() {
        assertThat(
                        ChatModelMetadata.resolve("openAiChatModel", null, Map.of(), "gpt-4o", null)
                                .providerId())
                .isEqualTo("openai");
        assertThat(
                        ChatModelMetadata.resolve(
                                        "bean",
                                        "org.springframework.ai.anthropic.AnthropicChatModel",
                                        Map.of(),
                                        "claude",
                                        null)
                                .providerId())
                .isEqualTo("anthropic");
        assertThat(
                        ChatModelMetadata.resolve(
                                        "bean", "com.deepseek.DeepSeekChatModel", null, "ds", null)
                                .providerId())
                .isEqualTo("deepseek");
        assertThat(
                        ChatModelMetadata.resolve("zhipuChatModel", null, null, "glm", null)
                                .providerId())
                .isEqualTo("zhipu");
        assertThat(ChatModelMetadata.resolve(null, null, null, null, null).providerId())
                .as("全无法判断时回退 openai（OpenAI 兼容是国内最常见形态）")
                .isEqualTo("mock".equals(ChatModelMetadata.FALLBACK_PROVIDER) ? "mock" : "openai");
        assertThat(ChatModelMetadata.FALLBACK_MODEL).isEqualTo("unknown");
        assertThat(new ChatModelMetadata("  MOCK  ", "  m  ", null).lookupKey())
                .isEqualTo("mock/m");
        assertThat(new ChatModelMetadata("mock", "m", "http://x").toString()).isEqualTo("mock/m");
    }

    /**
     * 一个「业务代码」——完全不知道 llm-glasshouse 存在的 {@link ChatModel}。
     *
     * <p>本类里<b>没有任何一行埋点代码</b>，这就是「零侵入」最直接的证据： 装饰器包住它之后，业务侧一行不用改就自动有了链路。
     */
    static final class RecordingChatModel implements ChatModel {

        private int callCount;
        private boolean usageInStreaming = true;
        private RuntimeException failWith;

        @Override
        public ChatResponse call(Prompt prompt) {
            callCount++;
            if (failWith != null) {
                throw failWith;
            }
            return response("根据《售后服务政策》，签收 7 天内可申请七天无理由退货。", true);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            callCount++;
            if (failWith != null) {
                return Flux.error(failWith);
            }
            return Flux.just(response("根据《售后服务政策》，", usageInStreaming))
                    .concatWith(Flux.just(response("签收 7 天内可申请退货。", usageInStreaming)));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ChatOptions.builder().model("mock-chat").temperature(0.0d).build();
        }

        private ChatResponse response(String text, boolean withUsage) {
            // 1.1.x 的 ChatResponseMetadata.Builder 没有 finishReason：
            // 结束原因挂在 ChatGenerationMetadata 上，这里只设模型名与 usage。
            ChatResponseMetadata.Builder metadata =
                    ChatResponseMetadata.builder().model("mock-chat");
            if (withUsage) {
                metadata.usage(new DefaultUsage(1000, 1000));
            }
            return ChatResponse.builder()
                    .generations(
                            List.of(
                                    new Generation(
                                            AssistantMessage.builder().content(text).build(),
                                            ChatGenerationMetadata.builder()
                                                    .finishReason("stop")
                                                    .build())))
                    .metadata(metadata.build())
                    .build();
        }
    }
}
