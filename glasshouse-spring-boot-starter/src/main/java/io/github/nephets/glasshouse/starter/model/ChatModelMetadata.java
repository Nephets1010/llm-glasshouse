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

package io.github.nephets.glasshouse.starter.model;

import java.util.Locale;
import java.util.Map;

/**
 * 从 Spring AI {@code ChatModel} 解析出的模型元数据。
 *
 * <p><b>为什么要单独解析</b>：成本归因与价格查询都需要 {@code (providerId, modelId)} 二元组， 而 Spring AI 的 {@code
 * ChatModel} 接口<b>不暴露</b>这两个信息——不同 provider starter 的实现类各不相同，没有统一 getter。
 *
 * <p>本类的解析策略是「三层回退」：
 *
 * <ol>
 *   <li>从 Spring Boot 的 {@code spring.ai.*} 配置键推断供应商
 *   <li>从实现类包名推断（{@code ollama} / {@code anthropic} 等）
 *   <li>都失败则回退 {@code openai}（OpenAI 兼容是最常见的形态）
 * </ol>
 *
 * <p>第三层的回退是刻意保守的：绝大多数国内接入走的都是 OpenAI 兼容端点， 回退到 {@code openai} 的归一化口径在实践中正确率最高。
 *
 * @param providerId 供应商标识（小写）
 * @param modelId 模型标识
 * @param baseUrl 端点地址；未知为 null
 */
public record ChatModelMetadata(String providerId, String modelId, String baseUrl) {

    /** 回退供应商标识。 */
    public static final String FALLBACK_PROVIDER = "openai";

    /** 回退模型标识。 */
    public static final String FALLBACK_MODEL = "unknown";

    /** 紧凑构造器：字段兜底与归一化。 */
    public ChatModelMetadata {
        providerId =
                providerId == null || providerId.isBlank()
                        ? FALLBACK_PROVIDER
                        : providerId.trim().toLowerCase(Locale.ROOT);
        modelId = modelId == null || modelId.isBlank() ? FALLBACK_MODEL : modelId.trim();
    }

    /**
     * 从 Spring AI 配置键推断模型元数据。
     *
     * @param beanName Spring Bean 名称，可为 null
     * @param implementationClassName 实现类全限定名，可为 null
     * @param springProperties 已加载的 {@code spring.ai.*} 配置键值表，可为 null
     * @param modelName 模型名，可为 null
     * @param baseUrl 端点地址，可为 null
     * @return 模型元数据
     */
    public static ChatModelMetadata resolve(
            String beanName,
            String implementationClassName,
            Map<String, Object> springProperties,
            String modelName,
            String baseUrl) {
        String provider = fromProperties(springProperties);
        if (provider == null) {
            provider = fromClassName(implementationClassName);
        }
        if (provider == null) {
            provider = fromBeanName(beanName);
        }
        return new ChatModelMetadata(provider, modelName, baseUrl);
    }

    /**
     * 从 {@code spring.ai.*} 配置键推断供应商。
     *
     * @param properties 配置键值表，可为 null
     * @return 供应商标识；无法判断返回 null
     */
    private static String fromProperties(Map<String, Object> properties) {
        if (properties == null) {
            return null;
        }
        // 顺序有意义：更具体的 provider 优先于通用的 openai（通义/DeepSeek 等也用 openai 兼容端点）
        for (Map.Entry<String, String> e :
                Map.of(
                                "spring.ai.anthropic", "anthropic",
                                "spring.ai.deepseek", "deepseek",
                                "spring.ai.zhipuai", "zhipu",
                                "spring.ai.moonshot", "moonshot",
                                "spring.ai.dashscope", "dashscope",
                                "spring.ai.ollama", "ollama",
                                "spring.ai.openai", "openai")
                        .entrySet()) {
            if (properties.containsKey(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * 从实现类包名推断供应商。
     *
     * @param className 实现类全限定名，可为 null
     * @return 供应商标识；无法判断返回 null
     */
    private static String fromClassName(String className) {
        if (className == null) {
            return null;
        }
        String lower = className.toLowerCase(Locale.ROOT);
        if (lower.contains("anthropic")) {
            return "anthropic";
        }
        if (lower.contains("deepseek")) {
            return "deepseek";
        }
        if (lower.contains("zhipu") || lower.contains("glm")) {
            return "zhipu";
        }
        if (lower.contains("moonshot") || lower.contains("kimi")) {
            return "moonshot";
        }
        if (lower.contains("dashscope") || lower.contains("qwen")) {
            return "dashscope";
        }
        if (lower.contains("doubao") || lower.contains("ark")) {
            return "doubao";
        }
        if (lower.contains("ollama")) {
            return "ollama";
        }
        return null;
    }

    /**
     * 从 Bean 名称推断供应商。
     *
     * @param beanName Bean 名称，可为 null
     * @return 供应商标识；无法判断返回 null
     */
    private static String fromBeanName(String beanName) {
        if (beanName == null) {
            return null;
        }
        return fromClassName(beanName);
    }

    /**
     * 返回价格表查找键。
     *
     * @return {@code providerId/modelId}
     */
    public String lookupKey() {
        return providerId + "/" + modelId;
    }

    @Override
    public String toString() {
        return lookupKey();
    }
}
