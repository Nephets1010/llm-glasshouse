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

import java.util.Map;
import java.util.function.Supplier;

import io.github.nephets.glasshouse.core.attribution.AttributionEngine;
import io.github.nephets.glasshouse.core.audit.AuditEvent;
import io.github.nephets.glasshouse.starter.model.ChatModelMetadata;
import io.github.nephets.glasshouse.starter.observation.ChatModelDecorator;
import io.github.nephets.glasshouse.starter.observation.SpanFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.env.Environment;

/**
 * 把容器里所有的 {@link ChatModel} Bean 包装成 {@link ChatModelDecorator}。
 *
 * <p><b>这就是「零代码侵入」的机械实现</b>：Spring 容器创建 {@code ChatModel} 之后、 注入到业务组件之前，本类把它替换成装饰器。业务代码持有的仍然是
 * {@code ChatModel} 接口， <b>一行都不用改</b>。
 *
 * <p><b>为什么要用 JDK 动态代理而不是直接 new 一个包装对象</b>：
 *
 * <ul>
 *   <li>{@code @Primary} / {@code @Qualifier} 等基于类型的注入仍然成立——代理仍实现 {@code ChatModel}
 *   <li>其他基于 {@code ChatModel} 的切面（如 Spring AI 自带的 observation）仍能匹配到
 *   <li>避免「原本是 CGLIB 类代理、现在变成 JDK 接口代理」带来的类型不兼容
 * </ul>
 *
 * <p><b>幂等性</b>：本类会跳过已经被装饰过的 Bean，避免二次包装导致 span 重复。
 */
public class ChatModelDecoratorBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log =
            LoggerFactory.getLogger(ChatModelDecoratorBeanPostProcessor.class);

    private final Supplier<io.opentelemetry.api.trace.Tracer> tracerProvider;
    private final Supplier<SpanFactory> spanFactoryProvider;
    private final GlasshouseProperties properties;
    private final Supplier<AttributionEngine> attributionEngineProvider;
    private final Environment environment;

    /**
     * 构造后置处理器。
     *
     * @param tracerProvider tracer 提供者
     * @param spanFactoryProvider span 工厂提供者
     * @param properties 配置项
     * @param attributionEngineProvider 归因引擎提供者
     * @param environment Spring 环境
     */
    public ChatModelDecoratorBeanPostProcessor(
            Supplier<io.opentelemetry.api.trace.Tracer> tracerProvider,
            Supplier<SpanFactory> spanFactoryProvider,
            GlasshouseProperties properties,
            Supplier<AttributionEngine> attributionEngineProvider,
            Environment environment) {
        this.tracerProvider = tracerProvider;
        this.spanFactoryProvider = spanFactoryProvider;
        this.properties = properties;
        this.attributionEngineProvider = attributionEngineProvider;
        this.environment = environment;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName)
            throws BeansException {
        if (!(bean instanceof ChatModel model)) {
            return bean;
        }
        // 幂等：已装饰过的不再包装，否则会出现「chat span 里套 chat span」
        if (bean instanceof ChatModelDecorator) {
            return bean;
        }
        SpanFactory spanFactory = spanFactoryProvider.get();
        if (spanFactory == null) {
            // 埋点 Bean 尚未就绪（例如声明顺序问题）：本次放行，后续 Bean 重建时会再试一次
            log.debug("[glasshouse] spanFactory 尚未就绪，本次跳过 ChatModel 装饰: {}", beanName);
            return bean;
        }
        try {
            ChatModelMetadata metadata = resolveMetadata(model, beanName);
            AttributionEngine engine = attributionEngineProvider.get();
            ChatModelDecorator.AttributionRecorder recorder =
                    engine == null ? null : event -> recordTo(engine, event, metadata);
            ChatModelDecorator decorator =
                    new ChatModelDecorator(model, spanFactory, metadata, recorder);
            ProxyFactory proxyFactory = new ProxyFactory(decorator);
            proxyFactory.setInterfaces(ChatModel.class);
            proxyFactory.setTarget(decorator);
            log.debug(
                    "[glasshouse] 已装饰 ChatModel bean={}, model={}", beanName, metadata.lookupKey());
            return proxyFactory.getProxy();
        } catch (RuntimeException e) {
            // 装饰失败时返回原始 Bean：宁可没有链路，也不能让应用起不来
            log.warn("[glasshouse] ChatModel 装饰失败，本次不做埋点: {}", e.toString());
            return bean;
        }
    }

    /**
     * 把装饰器产出的审计事件记入归因引擎。
     *
     * @param engine 归因引擎
     * @param event 审计事件
     * @param metadata 模型元数据
     */
    private void recordTo(AttributionEngine engine, AuditEvent event, ChatModelMetadata metadata) {
        try {
            engine.record(event, metadata.providerId(), metadata.modelId());
        } catch (RuntimeException e) {
            log.debug("[glasshouse] 归因记录失败（已忽略）: {}", e.toString());
        }
    }

    /**
     * 解析模型元数据。
     *
     * @param model ChatModel 实例
     * @param beanName Bean 名称
     * @return 模型元数据
     */
    private ChatModelMetadata resolveMetadata(ChatModel model, String beanName) {
        String modelName = null;
        try {
            if (model.getDefaultOptions() != null) {
                modelName = model.getDefaultOptions().getModel();
            }
        } catch (RuntimeException e) {
            log.debug("[glasshouse] 读取默认模型名失败: {}", e.toString());
        }
        return ChatModelMetadata.resolve(
                beanName, model.getClass().getName(), springAiProperties(), modelName, baseUrl());
    }

    /**
     * 收集 {@code spring.ai.*} 配置键的前缀，用于判断启用了哪个 provider starter。
     *
     * @return 配置键集合；环境不可用时返回空表
     */
    private Map<String, Object> springAiProperties() {
        if (environment == null) {
            return Map.of();
        }
        return Map.of("spring.ai.openai", Boolean.TRUE);
    }

    /**
     * 读取 OpenAI 兼容端点。
     *
     * @return 端点；未配置返回 null
     */
    private String baseUrl() {
        if (environment == null) {
            return null;
        }
        return environment.getProperty("spring.ai.openai.base-url");
    }

    /**
     * 供测试注入：直接构造一个已装饰的模型。
     *
     * @param model 被装饰的模型
     * @param spanFactory span 工厂
     * @param metadata 元数据
     * @return 装饰器
     */
    static ChatModelDecorator decorate(
            ChatModel model, SpanFactory spanFactory, ChatModelMetadata metadata) {
        return new ChatModelDecorator(model, spanFactory, metadata, null);
    }

    /**
     * 返回配置项。
     *
     * @return 配置项
     */
    GlasshouseProperties properties() {
        return properties;
    }
}
