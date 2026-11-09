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

package io.github.nephets.glasshouse.starter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;

import io.github.nephets.glasshouse.core.attribution.AttributionEngine;
import io.github.nephets.glasshouse.core.attribution.InMemoryMetricStore;
import io.github.nephets.glasshouse.core.attribution.MetricStore;
import io.github.nephets.glasshouse.core.audit.PayloadRedactor;
import io.github.nephets.glasshouse.core.pricing.PricingTable;
import io.github.nephets.glasshouse.core.pricing.PricingTableLoader;
import io.github.nephets.glasshouse.core.telemetry.OtelProperties;
import io.github.nephets.glasshouse.starter.annotation.AgentStepAspect;
import io.github.nephets.glasshouse.starter.health.GlasshouseHealthIndicator;
import io.github.nephets.glasshouse.starter.observation.SpanFactory;
import io.github.nephets.glasshouse.starter.store.SpanRingBuffer;
import io.github.nephets.glasshouse.starter.store.SpanTreeQuery;
import io.github.nephets.glasshouse.starter.web.GlasshouseWebAutoConfiguration;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GlasshouseAutoConfiguration} 的装配行为测试。
 *
 * <p>用 {@link ApplicationContextRunner} 起一个精简上下文，只加载本 starter 的两个自动配置类， 逐条核对「什么条件下有哪些 Bean、配置项落到
 * core 配置对象上的值」。每条断言都对应一个可反证的条件：去掉注解、改掉默认值或漏掉属性透传， 对应的用例就会变红。
 *
 * <p>注意：自动配置类上带 {@code @EnableScheduling}，{@code fixedDelay} 任务首次执行是立即的， 而 {@code
 * ApplicationContextRunner} 在断言结束后立刻关闭上下文，后台线程有可能落在关闭之后， 打出一条「context has been closed already」的
 * ERROR 日志。这是快速开关上下文的时序现象，不影响装配结论，本类不对它下断言。
 */
class GlasshouseAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(
                                    GlasshouseAutoConfiguration.class,
                                    GlasshouseWebAutoConfiguration.class));

    /** 零配置时自动配置生效，核心 Bean 全部注册且各只有一份。 */
    @Test
    void defaultConfigurationRegistersAllCoreBeans() {
        runner.run(
                ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(GlasshouseAutoConfiguration.class);
                    assertThat(ctx).hasSingleBean(GlasshouseProperties.class);
                    assertThat(ctx).hasSingleBean(OtelProperties.class);
                    assertThat(ctx).hasSingleBean(SpanRingBuffer.class);
                    assertThat(ctx).hasSingleBean(SpanTreeQuery.class);
                    assertThat(ctx).hasSingleBean(PayloadRedactor.class);
                    assertThat(ctx).hasSingleBean(PricingTable.class);
                    assertThat(ctx).hasSingleBean(PricingTableLoader.class);
                    assertThat(ctx).hasSingleBean(MetricStore.class);
                    assertThat(ctx).hasSingleBean(AttributionEngine.class);
                    assertThat(ctx).hasSingleBean(OpenTelemetrySdk.class);
                    assertThat(ctx).hasSingleBean(Tracer.class);
                    assertThat(ctx).hasSingleBean(SpanFactory.class);
                    assertThat(ctx).hasSingleBean(AgentStepAspect.class);
                    assertThat(ctx).hasSingleBean(ChatModelDecoratorBeanPostProcessor.class);
                    assertThat(ctx).hasSingleBean(GlasshouseHealthIndicator.class);
                    assertThat(ctx.getBean("glasshouseSpanProcessors")).isEqualTo(List.of());
                });
    }

    /** 零配置时各项默认值写死在断言里，改了默认值就会红，不引用源码常量做镜像。 */
    @Test
    void defaultPropertiesMatchDocumentedValues() {
        runner.run(
                ctx -> {
                    assertThat(ctx).hasNotFailed();
                    OtelProperties otel = ctx.getBean(OtelProperties.class);
                    assertThat(otel.enabled()).isTrue();
                    assertThat(otel.applicationName()).isEqualTo("unknown-app");
                    assertThat(otel.sampleRatio()).isEqualTo(1.0d);
                    assertThat(otel.otlpEndpoint()).isNull();
                    assertThat(otel.otlpExportEnabled()).isFalse();
                    assertThat(otel.otlpTimeoutMillis()).isEqualTo(10_000);
                    assertThat(otel.ringBufferCapacity()).isEqualTo(100_000);
                    assertThat(otel.recordPayloadFull()).isFalse();
                    assertThat(otel.payloadMaxLength()).isEqualTo(4_000);
                    assertThat(otel.redactPii()).isTrue();
                    assertThat(otel.evalEnabled()).isFalse();
                    assertThat(otel.contractPath()).isEqualTo("contract/contract-v1.json");
                    assertThat(ctx.getBean(SpanRingBuffer.class).capacity()).isEqualTo(100_000);

                    GlasshouseProperties props = ctx.getBean(GlasshouseProperties.class);
                    assertThat(props.enabled()).isTrue();
                    assertThat(props.redactKeepTail()).isEqualTo(4);
                    assertThat(props.pricingReloadSeconds()).isEqualTo(300L);
                    assertThat(props.attributionMaxKeys()).isEqualTo(20_000);
                    assertThat(props.costBudgetCny()).isEqualByComparingTo("1");
                });
    }

    /** 关闭开关后两个自动配置都不生效，一个业务 Bean 也不注册。 */
    @Test
    void disabledPropertyTurnsOffWholeAutoConfiguration() {
        runner.withPropertyValues("llm.glasshouse.enabled=false")
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            assertThat(ctx).doesNotHaveBean(GlasshouseAutoConfiguration.class);
                            assertThat(ctx).doesNotHaveBean(GlasshouseProperties.class);
                            assertThat(ctx).doesNotHaveBean(OtelProperties.class);
                            assertThat(ctx).doesNotHaveBean(SpanRingBuffer.class);
                            assertThat(ctx).doesNotHaveBean(SpanFactory.class);
                            assertThat(ctx).doesNotHaveBean(OpenTelemetrySdk.class);
                            assertThat(ctx).doesNotHaveBean(GlasshouseWebAutoConfiguration.class);
                        });
    }

    /** classpath 上没有 ChatModel 接口时按条件跳过，避免非 LLM 应用被强加埋点。 */
    @Test
    void missingChatModelClassTurnsOffAutoConfiguration() {
        runner.withClassLoader(new FilteredClassLoader(ChatModel.class))
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            assertThat(ctx).doesNotHaveBean(GlasshouseAutoConfiguration.class);
                            assertThat(ctx).doesNotHaveBean(GlasshouseProperties.class);
                            assertThat(ctx).doesNotHaveBean(SpanRingBuffer.class);
                            assertThat(ctx).doesNotHaveBean(SpanFactory.class);
                            assertThat(ctx).doesNotHaveBean(OpenTelemetrySdk.class);
                        });
    }

    /** 配置逐项绑定，并且原样透传进 core 的 OtelProperties 与环形缓冲容量。 */
    @Test
    void configuredPropertiesAreBoundAndPassedToCore() {
        runner.withPropertyValues(
                        "llm.glasshouse.application-name=order-service",
                        "llm.glasshouse.sample-ratio=0.25",
                        "llm.glasshouse.ring-buffer-capacity=7",
                        "llm.glasshouse.otlp-endpoint=http://127.0.0.1:4318/v1/traces",
                        "llm.glasshouse.otlp-timeout-millis=2222",
                        "llm.glasshouse.record-payload-full=true",
                        "llm.glasshouse.payload-max-length=123",
                        "llm.glasshouse.redact-pii=false",
                        "llm.glasshouse.redact-keep-tail=6",
                        "llm.glasshouse.eval-enabled=true",
                        "llm.glasshouse.pricing-reload-seconds=42",
                        "llm.glasshouse.attribution-max-keys=99")
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            GlasshouseProperties props = ctx.getBean(GlasshouseProperties.class);
                            assertThat(props.applicationName()).isEqualTo("order-service");
                            assertThat(props.sampleRatio()).isEqualTo(0.25d);
                            assertThat(props.ringBufferCapacity()).isEqualTo(7);
                            assertThat(props.otlpEndpoint())
                                    .isEqualTo("http://127.0.0.1:4318/v1/traces");
                            assertThat(props.otlpExportEnabled()).isTrue();
                            assertThat(props.otlpTimeoutMillis()).isEqualTo(2222);
                            assertThat(props.recordPayloadFull()).isTrue();
                            assertThat(props.payloadMaxLength()).isEqualTo(123);
                            assertThat(props.redactPii()).isFalse();
                            assertThat(props.redactKeepTail()).isEqualTo(6);
                            assertThat(props.evalEnabled()).isTrue();
                            assertThat(props.pricingReloadSeconds()).isEqualTo(42L);
                            assertThat(props.attributionMaxKeys()).isEqualTo(99);

                            OtelProperties otel = ctx.getBean(OtelProperties.class);
                            assertThat(otel.enabled()).isTrue();
                            assertThat(otel.applicationName()).isEqualTo("order-service");
                            assertThat(otel.sampleRatio()).isEqualTo(0.25d);
                            assertThat(otel.otlpEndpoint())
                                    .isEqualTo("http://127.0.0.1:4318/v1/traces");
                            assertThat(otel.otlpExportEnabled()).isTrue();
                            assertThat(otel.otlpTimeoutMillis()).isEqualTo(2222);
                            assertThat(otel.ringBufferCapacity()).isEqualTo(7);
                            assertThat(otel.recordPayloadFull()).isTrue();
                            assertThat(otel.payloadMaxLength()).isEqualTo(123);
                            assertThat(otel.redactPii()).isFalse();
                            assertThat(otel.evalEnabled()).isTrue();
                            assertThat(otel.contractPath()).isEqualTo("contract/contract-v1.json");
                            assertThat(ctx.getBean(SpanRingBuffer.class).capacity()).isEqualTo(7);
                        });
    }

    /** 用户自己声明的 Bean 优先，自动配置不再重复创建同类型 Bean。 */
    @Test
    void userDefinedBeansWinOverConditionalOnMissingBean() {
        runner.withUserConfiguration(CustomBeans.class)
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            assertThat(ctx.getBean(SpanRingBuffer.class))
                                    .isSameAs(CustomBeans.BUFFER);
                            assertThat(ctx.getBeansOfType(SpanRingBuffer.class)).hasSize(1);
                            assertThat(ctx.getBean(SpanRingBuffer.class).capacity()).isEqualTo(3);
                            assertThat(ctx.getBean(MetricStore.class)).isSameAs(CustomBeans.STORE);
                            assertThat(ctx.getBeansOfType(MetricStore.class)).hasSize(1);
                            assertThat(ctx).hasSingleBean(GlasshouseAutoConfiguration.class);
                            assertThat(ctx).hasSingleBean(AttributionEngine.class);
                        });
    }

    /** 配了 OTLP 端点就打开导出开关，SDK 仍能创建（不发网络请求）。 */
    @Test
    void otlpEndpointEnablesExportAndKeepsSdkAvailable() {
        runner.withPropertyValues("llm.glasshouse.otlp-endpoint=http://127.0.0.1:4318/v1/traces")
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            assertThat(ctx.getBean(GlasshouseProperties.class).otlpExportEnabled())
                                    .isTrue();
                            assertThat(ctx.getBean(OtelProperties.class).otlpExportEnabled())
                                    .isTrue();
                            assertThat(ctx).hasSingleBean(OpenTelemetrySdk.class);
                        });
    }

    /** 自动配置清单必须同时声明两个类，漏掉任一个真实应用都不会加载它。 */
    @Test
    void autoConfigurationImportsListsBothClasses() throws IOException {
        String resource =
                "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("starter 的自动配置清单必须存在于 classpath").isNotNull();
            List<String> entries =
                    new String(in.readAllBytes(), StandardCharsets.UTF_8)
                            .lines()
                            .map(String::trim)
                            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                            .toList();
            assertThat(entries)
                    .containsExactlyInAnyOrder(
                            "io.github.nephets.glasshouse.starter.GlasshouseAutoConfiguration",
                            "io.github.nephets.glasshouse.starter.web.GlasshouseWebAutoConfiguration");
        }
    }

    /** 用户自定义 Bean 的样例配置，只覆盖两个纯内存协作者。 */
    @Configuration(proxyBeanMethods = false)
    static class CustomBeans {

        /** 容量 3 的自定义缓冲。 */
        static final SpanRingBuffer BUFFER = new SpanRingBuffer(3);

        /** 自定义归因存储。 */
        static final MetricStore STORE = new InMemoryMetricStore();

        /**
         * 注册自定义缓冲。
         *
         * @return 缓冲
         */
        @Bean
        SpanRingBuffer customSpanRingBuffer() {
            return BUFFER;
        }

        /**
         * 注册自定义归因存储。
         *
         * @return 归因存储
         */
        @Bean
        MetricStore customMetricStore() {
            return STORE;
        }
    }
}
