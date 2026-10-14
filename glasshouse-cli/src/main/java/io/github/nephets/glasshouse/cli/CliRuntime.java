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

package io.github.nephets.glasshouse.cli;

import java.time.Duration;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;

import io.github.nephets.glasshouse.cli.version.BuildVersion;
import io.github.nephets.glasshouse.core.attribution.AttributionEngine;
import io.github.nephets.glasshouse.core.pricing.CostCalculator;
import io.github.nephets.glasshouse.core.pricing.PricingTable;
import io.github.nephets.glasshouse.core.pricing.PricingTableLoader;
import io.github.nephets.glasshouse.starter.store.SpanRingBuffer;
import io.github.nephets.glasshouse.starter.store.SpanTreeQuery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CLI 运行时装配。
 *
 * <p><b>刻意不启动 Spring 容器</b>：CLI 是 CI 的主力（每次流水线都要起一个 JVM）， 启动 Spring Boot 上下文要多花几百毫秒到几秒，对「跑一次评测然后
 * exit」的场景是纯浪费。 这里手工装配所需的 8 个对象，启动时间在百毫秒级。
 *
 * <p>这同时也验证了一件事：<b>core 层与 starter 的装配逻辑是解耦的</b>。 如果 starter 的能力必须靠 Spring 才能用，CI 门禁就会被迫背上 Spring
 * 的启动开销。
 */
final class CliRuntime implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CliRuntime.class);

    private final OpenTelemetrySdk openTelemetry;
    private final SdkTracerProvider tracerProvider;
    private final SpanRingBuffer ringBuffer;
    private final SpanTreeQuery spanTreeQuery;
    private final PricingTable pricingTable;
    private final PricingTableLoader pricingLoader;
    private final AttributionEngine attributionEngine;

    /**
     * 构造运行时装配。
     *
     * @param sampleRatio 采样率
     * @param bufferCapacity 环形缓冲容量
     * @param otlpEndpoint OTLP 端点；为 null 时不导出
     * @param applicationName 应用名
     */
    CliRuntime(
            double sampleRatio, int bufferCapacity, String otlpEndpoint, String applicationName) {
        this.ringBuffer = new SpanRingBuffer(bufferCapacity);
        this.spanTreeQuery = new SpanTreeQuery(ringBuffer);
        this.pricingLoader = new PricingTableLoader();
        this.pricingTable = new PricingTable();
        this.attributionEngine =
                new AttributionEngine(
                        new io.github.nephets.glasshouse.core.attribution.InMemoryMetricStore(),
                        pricingTable,
                        CostCalculator.getInstance(),
                        msg -> {
                            // 错误原因同时进日志和 stderr，为了在 CI 日志里能直接看到
                            log.warn("[glasshouse] {}", msg);
                            System.err.println("[glasshouse] WARN " + msg);
                        });

        try {
            this.pricingTable.replaceAll(pricingLoader.loadFromClasspath().entries());
        } catch (RuntimeException e) {
            // ★ 价格表加载失败会让「所有成本都算成 0」——这是最容易被误读成
            //   「这个模型真便宜」的静默失败，必须进日志；异常对象一并带上，
            //   否则只看到一句 message 无法定位是哪个文件、哪一行为什么坏了。
            //   日志的 appender 本身就打 stderr，CI 里能直接看到，不必再写一份 System.err。
            log.warn("[glasshouse] 价格表加载失败，成本将记 0: {}", e.getMessage(), e);
        }

        Resource resource =
                Resource.getDefault().toBuilder()
                        .put("service.name", applicationName)
                        .put("service.version", BuildVersion.version())
                        .build();
        io.opentelemetry.sdk.trace.SdkTracerProviderBuilder providerBuilder =
                SdkTracerProvider.builder().setResource(resource).setSampler(Sampler.alwaysOn());
        if (otlpEndpoint != null && !otlpEndpoint.isBlank()) {
            providerBuilder.addSpanProcessor(
                    BatchSpanProcessor.builder(
                                    OtlpHttpSpanExporter.builder()
                                            .setEndpoint(otlpEndpoint)
                                            .setTimeout(Duration.ofSeconds(10))
                                            .build())
                            .build());
        }
        this.tracerProvider = providerBuilder.build();
        this.openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
    }

    /**
     * 返回 OpenTelemetry 实例。
     *
     * @return OTel SDK
     */
    OpenTelemetry openTelemetry() {
        return openTelemetry;
    }

    /**
     * 返回 span 缓冲。
     *
     * @return 缓冲
     */
    SpanRingBuffer ringBuffer() {
        return ringBuffer;
    }

    /**
     * 返回 span 树查询器。
     *
     * @return 查询器
     */
    SpanTreeQuery spanTreeQuery() {
        return spanTreeQuery;
    }

    /**
     * 返回价格表。
     *
     * @return 价格表
     */
    PricingTable pricingTable() {
        return pricingTable;
    }

    /**
     * 返回价格表加载器。
     *
     * @return 加载器
     */
    PricingTableLoader pricingLoader() {
        return pricingLoader;
    }

    /**
     * 返回归因引擎。
     *
     * @return 归因引擎
     */
    AttributionEngine attributionEngine() {
        return attributionEngine;
    }

    @Override
    public void close() {
        tracerProvider.close();
    }
}
