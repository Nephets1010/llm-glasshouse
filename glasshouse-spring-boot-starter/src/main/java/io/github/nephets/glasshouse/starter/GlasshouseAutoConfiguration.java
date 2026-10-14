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

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;

import io.github.nephets.glasshouse.core.attribution.AttributionEngine;
import io.github.nephets.glasshouse.core.attribution.InMemoryMetricStore;
import io.github.nephets.glasshouse.core.attribution.MetricStore;
import io.github.nephets.glasshouse.core.audit.AuditEvent;
import io.github.nephets.glasshouse.core.audit.PayloadRedactor;
import io.github.nephets.glasshouse.core.audit.RedactionPolicy;
import io.github.nephets.glasshouse.core.pricing.PricingTable;
import io.github.nephets.glasshouse.core.pricing.PricingTableLoader;
import io.github.nephets.glasshouse.core.telemetry.OtelProperties;
import io.github.nephets.glasshouse.starter.annotation.AgentStepAspect;
import io.github.nephets.glasshouse.starter.health.GlasshouseHealthIndicator;
import io.github.nephets.glasshouse.starter.model.ChatModelMetadata;
import io.github.nephets.glasshouse.starter.observation.ChatModelDecorator;
import io.github.nephets.glasshouse.starter.observation.SpanFactory;
import io.github.nephets.glasshouse.starter.store.SpanRingBuffer;
import io.github.nephets.glasshouse.starter.store.SpanTreeQuery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * LLM 玻璃房自动配置。
 *
 * <p><b>零侵入的落点就在这里</b>：本类用 {@link ChatModelDecoratorBeanPostProcessor} 包装容器里 所有的 {@code ChatModel}
 * Bean，因此用户的业务代码<b>一行不改</b>就能产生 span。
 *
 * <p><b>零配置的落点</b>：所有 Bean 都有 {@code @ConditionalOnMissingBean}， 且默认值保证「什么都不配」也能跑通（内存缓冲 + 全采样 +
 * 脱敏开启）。 用户想调优时再改 {@code application.yml} 里的 {@code llm.glasshouse.*}。
 *
 * <p><b>零锁定</b>：配置了 {@code llm.glasshouse.otlp-endpoint} 就会自动导出 OTLP， 数据可进 Jaeger / Tempo / Grafana
 * / Datadog，我们不做任何二次锁定。
 */
@AutoConfiguration
@EnableConfigurationProperties(GlasshouseProperties.class)
@ConditionalOnClass(ChatModel.class)
@ConditionalOnProperty(
        prefix = GlasshouseProperties.PREFIX,
        name = "enabled",
        matchIfMissing = true,
        havingValue = "true")
@EnableScheduling
public class GlasshouseAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GlasshouseAutoConfiguration.class);

    /**
     * 价格表与其加载器延迟获取，从而不必改动既有的公开构造签名。
     *
     * <p><b>为什么用 {@link ObjectProvider} 而不是直接注入</b>：这两个 Bean 都带
     * {@code @ConditionalOnMissingBean}，用户可能替换成自己的实现；用 ObjectProvider 在真正执行时再取， 既不会把 Bean
     * 创建时机提前到自动配置实例化阶段，也不会因缺失而在容器刷新期炸掉。
     */
    @Autowired private ObjectProvider<PricingTable> pricingTableProvider;

    /** 价格表加载器提供者，理由同上。 */
    @Autowired private ObjectProvider<PricingTableLoader> pricingTableLoaderProvider;

    /**
     * 构造自动配置。
     *
     * @param properties 配置项
     * @param environment Spring 环境，用于推断模型供应商
     */
    public GlasshouseAutoConfiguration(GlasshouseProperties properties, Environment environment) {
        log.info(
                "[glasshouse] 自动配置已启用：application={}, sampleRatio={}, ringBuffer={}, redactPii={}, otlp={}",
                properties.applicationName(),
                properties.sampleRatio(),
                properties.ringBufferCapacity(),
                properties.redactPii(),
                properties.otlpExportEnabled() ? properties.otlpEndpoint() : "(disabled)");
    }

    /**
     * 构造 core 的不可变配置对象。
     *
     * @param properties 配置项
     * @return core 配置
     */
    @Bean
    @ConditionalOnMissingBean
    public OtelProperties otelProperties(GlasshouseProperties properties) {
        return properties.toOtelProperties();
    }

    /**
     * 构造内存 span 环形缓冲。
     *
     * @param properties 配置项
     * @return span 缓冲
     */
    @Bean
    @ConditionalOnMissingBean
    public SpanRingBuffer spanRingBuffer(GlasshouseProperties properties) {
        return new SpanRingBuffer(properties.ringBufferCapacity());
    }

    /**
     * 构造 span 树查询器（CLI 与 Actuator 端点共用）。
     *
     * @param buffer span 缓冲
     * @return 查询器
     */
    @Bean
    @ConditionalOnMissingBean
    public SpanTreeQuery spanTreeQuery(SpanRingBuffer buffer) {
        return new SpanTreeQuery(buffer);
    }

    /**
     * 构造脱敏器：从 classpath 的 {@code patterns/pii-cn.yml} 加载规则，失败自动回退内置规则。
     *
     * @param properties 配置项
     * @return 脱敏器
     */
    @Bean
    @ConditionalOnMissingBean
    public PayloadRedactor payloadRedactor(GlasshouseProperties properties) {
        RedactionPolicy policy =
                properties.redactPii()
                        ? RedactionPolicy.of(
                                        RedactionPolicy.ALL_RULE_NAMES, properties.redactKeepTail())
                                .withMaxPayloadLength(properties.payloadMaxLength())
                        : RedactionPolicy.DISABLED.withMaxPayloadLength(
                                properties.payloadMaxLength());
        return PayloadRedactor.fromClasspath(policy);
    }

    /**
     * 构造价格表：从 classpath 的内置价格表加载。
     *
     * @param loader 加载器
     * @return 价格表
     */
    @Bean
    @ConditionalOnMissingBean
    public PricingTable pricingTable(PricingTableLoader loader) {
        PricingTable table = new PricingTable();
        PricingTableLoader.LoadResult result = loader.loadFromClasspath();
        table.replaceAll(result.entries());
        if (result.hasSkipped()) {
            log.warn("[glasshouse] 价格表存在被跳过的条目: {}", String.join("; ", result.skipped()));
        }
        log.info("[glasshouse] 价格表加载完成: {}", result.summary());
        return table;
    }

    /**
     * 构造价格表加载器。
     *
     * @return 加载器
     */
    @Bean
    @ConditionalOnMissingBean
    public PricingTableLoader pricingTableLoader() {
        return new PricingTableLoader();
    }

    /**
     * 归因存储。
     *
     * @return 内存归因存储
     */
    @Bean
    @ConditionalOnMissingBean
    public MetricStore metricStore() {
        return new InMemoryMetricStore();
    }

    /**
     * 五维成本归因引擎。
     *
     * @param store 归因存储
     * @param pricingTable 价格表
     * @return 归因引擎
     */
    @Bean
    @ConditionalOnMissingBean
    public AttributionEngine attributionEngine(MetricStore store, PricingTable pricingTable) {
        return new AttributionEngine(
                store,
                pricingTable,
                io.github.nephets.glasshouse.core.pricing.CostCalculator.getInstance(),
                msg -> log.warn(msg));
    }

    /**
     * OTel SDK 实例。
     *
     * <p><b>刻意不直接用 Spring Boot 的 OTel 自动配置</b>：我们要保证 「只加 starter 就有一个可用的内存链路」，而不是必须先配 OTLP 端点。
     * 用户配了端点时我们额外挂一个 OTLP exporter，两种模式都成立。
     *
     * @param properties core 配置
     * @return OpenTelemetry 实例
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public OpenTelemetrySdk openTelemetrySdk(OtelProperties properties) {
        Resource resource =
                Resource.getDefault().toBuilder()
                        .put("service.name", properties.applicationName())
                        .put("service.version", "0.1.0")
                        .put("telemetry.sdk.language", "java")
                        .build();

        io.opentelemetry.sdk.trace.SdkTracerProviderBuilder providerBuilder =
                SdkTracerProvider.builder()
                        .setResource(resource)
                        .setSampler(
                                properties.sampleRatio() >= 1.0d
                                        ? Sampler.alwaysOn()
                                        : Sampler.traceIdRatioBased(properties.sampleRatio()));

        if (properties.otlpExportEnabled()) {
            OtlpHttpSpanExporter exporter =
                    OtlpHttpSpanExporter.builder()
                            .setEndpoint(properties.otlpEndpoint())
                            .setTimeout(Duration.ofMillis(properties.otlpTimeoutMillis()))
                            .build();
            providerBuilder.addSpanProcessor(BatchSpanProcessor.builder(exporter).build());
            log.info("[glasshouse] OTLP 导出已启用: {}", properties.otlpEndpoint());
        } else {
            log.info("[glasshouse] OTLP 导出未配置，数据只落在内存环形缓冲中（生产环境请配置 llm.glasshouse.otlp-endpoint）");
        }

        return OpenTelemetrySdk.builder().setTracerProvider(providerBuilder.build()).build();
    }

    /**
     * Span 处理器收集器，供 {@link SpanRingBuffer} 之外的扩展点使用。
     *
     * @param sdk OTel SDK
     * @return 空的处理器列表占位（保留扩展点）
     */
    @Bean
    @ConditionalOnMissingBean
    public java.util.List<SpanProcessor> glasshouseSpanProcessors(OpenTelemetrySdk sdk) {
        return java.util.List.of();
    }

    /**
     * Tracer Bean。
     *
     * @param sdk OTel SDK
     * @return tracer
     */
    @Bean
    @ConditionalOnMissingBean
    public Tracer glasshouseTracer(OpenTelemetrySdk sdk) {
        return sdk.getTracer(SpanFactory.SCOPE_NAME, SpanFactory.SCOPE_VERSION);
    }

    /**
     * Span 工厂。
     *
     * @param tracer tracer
     * @param buffer span 缓冲
     * @param redactor 脱敏器
     * @param pricingTable 价格表
     * @param properties 配置项
     * @param attributionEngine 归因引擎
     * @param environment Spring 环境
     * @return span 工厂
     */
    @Bean
    @ConditionalOnMissingBean
    public SpanFactory spanFactory(
            Tracer tracer,
            SpanRingBuffer buffer,
            PayloadRedactor redactor,
            PricingTable pricingTable,
            GlasshouseProperties properties,
            AttributionEngine attributionEngine,
            Environment environment) {
        return new SpanFactory(
                tracer,
                buffer,
                redactor,
                pricingTable,
                properties.recordPayloadFull(),
                (snapshot, usage, providerId, modelId) ->
                        recordAttribution(
                                attributionEngine,
                                snapshot,
                                usage,
                                providerId,
                                modelId,
                                environment));
    }

    /**
     * 把结束的 span 转成审计事件并交给归因引擎。
     *
     * <p>这是「埋点与归因不需要业务再调一次 API」的落点。
     *
     * @param engine 归因引擎
     * @param snapshot span 快照
     * @param usage 计量；无 token 的 span 为 null
     * @param providerId 供应商标识
     * @param modelId 模型标识
     * @param environment Spring 环境（用于读取归因维度）
     */
    private static void recordAttribution(
            AttributionEngine engine,
            SpanRingBuffer.SpanData snapshot,
            io.github.nephets.glasshouse.core.usage.Usage usage,
            String providerId,
            String modelId,
            Environment environment) {
        if (engine == null || usage == null || usage.totalTokens() == 0L) {
            return;
        }
        io.github.nephets.glasshouse.core.usage.Usage normalized =
                usage.usageSource() == io.github.nephets.glasshouse.core.usage.UsageSource.FALLBACK
                        ? io.github.nephets.glasshouse.core.usage.Usage.estimated()
                        : usage;
        Double cost = snapshot.costAmount();
        AuditEvent event =
                new AuditEvent(
                        io.github.nephets.glasshouse.core.audit.EventType.MODEL_CALL,
                        snapshot.traceId(),
                        snapshot.spanId(),
                        snapshot.startTime(),
                        stringAttr(snapshot, "swy.tenant.id"),
                        stringAttr(snapshot, "swy.biz.line"),
                        stringAttr(snapshot, "swy.feature"),
                        stringAttr(snapshot, "swy.prompt.version"),
                        stringAttr(snapshot, "swy.agent.id"),
                        null,
                        stringAttr(snapshot, "swy.step.id"),
                        longAttr(snapshot, "swy.step.seq"),
                        normalized,
                        cost == null
                                ? java.math.BigDecimal.ZERO
                                : java.math.BigDecimal.valueOf(cost),
                        AuditEvent.CURRENCY_CNY,
                        stringAttr(snapshot, "swy.payload.digest"),
                        stringAttr(snapshot, "swy.payload.full"));
        engine.record(event, providerId, modelId);
    }

    /**
     * 埋点切面。
     *
     * @param spanFactory span 工厂
     * @return 切面
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentStepAspect agentStepAspect(SpanFactory spanFactory) {
        return new AgentStepAspect(spanFactory);
    }

    /**
     * 包装所有 {@code ChatModel} Bean——这就是「零代码侵入」的实现。
     *
     * @param tracer tracer
     * @param spanFactory span 工厂
     * @param properties 配置项
     * @param attributionEngine 归因引擎
     * @param environment Spring 环境
     * @return Bean 后置处理器
     */
    @Bean
    @ConditionalOnMissingBean
    public static ChatModelDecoratorBeanPostProcessor chatModelDecoratorBeanPostProcessor(
            ObjectProvider<Tracer> tracerProvider,
            ObjectProvider<SpanFactory> spanFactoryProvider,
            GlasshouseProperties properties,
            ObjectProvider<AttributionEngine> attributionEngineProvider,
            Environment environment) {
        return new ChatModelDecoratorBeanPostProcessor(
                tracerProvider::getIfAvailable,
                spanFactoryProvider::getIfAvailable,
                properties,
                attributionEngineProvider::getIfAvailable,
                environment);
    }

    /**
     * 健康检查。
     *
     * @param buffer span 缓冲
     * @param pricingTable 价格表
     * @param store 归因存储
     * @return 健康检查指示器
     */
    @Bean
    @ConditionalOnMissingBean
    public GlasshouseHealthIndicator glasshouseHealthIndicator(
            SpanRingBuffer buffer, PricingTable pricingTable, MetricStore store) {
        return new GlasshouseHealthIndicator(buffer, pricingTable, store);
    }

    /**
     * 价格表热更新入口：默认每 5 分钟检查一次 classpath 资源的时间戳，变了就重新加载。 <b>价格变更无需改代码、无需重启</b>（PRD P0-7）。
     *
     * <p><b>为什么这个方法必须无参</b>：{@code @Scheduled} 由 {@code ScheduledAnnotationBeanPostProcessor}
     * 处理，它断言被标注方法<b>参数个数必须为 0</b>（断言文案 {@code Only no-arg methods may be annotated
     * with @Scheduled}），且该断言发生在<b>容器刷新期</b>创建本配置 Bean 的过程中。早前把注解标在两参方法上，导致 {@code
     * GlasshouseAutoConfiguration} 这个 Bean 直接 创建失败 —— 应用根本起不来，热更新也从未真正生效过。依赖改从 {@link
     * #pricingTableProvider} 与 {@link #pricingTableLoaderProvider} 取。
     *
     * <p><b>为什么用 {@code timeUnit = SECONDS}</b>：属性名是 {@code pricing-reload-seconds}、语义是秒。 早前写作
     * {@code ${...pricing-reload-seconds:300}000}，靠字符串拼尾零把秒转成毫秒， 运维一旦按直觉把 {@code 300} 改成 {@code
     * 300000}（以为是毫秒）就会被再放大 1000 倍。 交给 {@code TimeUnit} 声明单位后，属性值直接按秒解释，不再做任何字符串加工。
     */
    @Scheduled(
            fixedDelayString = "${llm.glasshouse.pricing-reload-seconds:300}",
            timeUnit = TimeUnit.SECONDS)
    public void reloadPricingTable() {
        PricingTable table = pricingTableProvider.getIfAvailable();
        PricingTableLoader loader = pricingTableLoaderProvider.getIfAvailable();
        if (table == null || loader == null) {
            // 用户替换了 Bean 或自动配置尚未就绪：本次跳过，下一轮再试，绝不因此中断调度线程
            log.debug("[glasshouse] 价格表热更新跳过：pricingTable 或 loader 暂不可用");
            return;
        }
        log.info("[glasshouse] 触发价格表热更新（@Scheduled 周期执行）");
        reloadPricingTable(table, loader);
    }

    /**
     * 用给定的价格表与加载器执行一次热更新。
     *
     * <p>与调度入口分开是为了让这一层可以脱离 Spring 直接驱动（测试里传入自建的价格表即可）， 也让上面的 {@code @Scheduled} 方法能保持必须的无参形态。
     *
     * @param table 价格表
     * @param loader 加载器
     */
    void reloadPricingTable(PricingTable table, PricingTableLoader loader) {
        try {
            PricingTableLoader.LoadResult result = loader.loadFromClasspath();
            table.replaceAll(result.entries());
            if (result.hasSkipped()) {
                log.warn("[glasshouse] 价格表热更新时有条目被跳过: {}", String.join("; ", result.skipped()));
            }
        } catch (RuntimeException e) {
            // 热更新失败必须保留旧价格表继续服务，绝不能因为价格文件被误改而让成本报表清零
            log.warn("[glasshouse] 价格表热更新失败，继续使用旧价格表: {}", e.toString());
        }
    }

    private static String stringAttr(SpanRingBuffer.SpanData data, String key) {
        Object v = data.attributes().get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static Integer longAttr(SpanRingBuffer.SpanData data, String key) {
        Object v = data.attributes().get(key);
        return v instanceof Number n ? n.intValue() : null;
    }

    /**
     * 供扩展使用的属性键工厂（保持与 core 的唯一出处一致）。
     *
     * @param name 属性名，必须以 {@code swy.} 开头
     * @return 属性键
     */
    public static AttributeKey<String> swyKey(String name) {
        return io.github.nephets.glasshouse.core.telemetry.SwyAttributes.string(name);
    }

    /**
     * 返回模型元数据解析器，供 Bean 后置处理器使用。
     *
     * @param environment Spring 环境
     * @param beanName Bean 名称
     * @param className 实现类名
     * @param modelName 模型名
     * @param baseUrl 端点
     * @return 模型元数据
     */
    static ChatModelMetadata resolveMetadata(
            Environment environment,
            String beanName,
            String className,
            String modelName,
            String baseUrl) {
        return ChatModelMetadata.resolve(beanName, className, null, modelName, baseUrl);
    }

    /**
     * 构造一个不做归因的 ChatModel 装饰器（归因引擎缺失时使用）。
     *
     * @param delegate 被装饰的模型
     * @param spanFactory span 工厂
     * @param metadata 元数据
     * @return 装饰器
     */
    static ChatModelDecorator decoratorOf(
            org.springframework.ai.chat.model.ChatModel delegate,
            SpanFactory spanFactory,
            ChatModelMetadata metadata) {
        return new ChatModelDecorator(delegate, spanFactory, metadata, null);
    }
}
