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

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.github.nephets.glasshouse.core.telemetry.GenaiAttributes;
import io.github.nephets.glasshouse.core.telemetry.SpnNames;
import io.github.nephets.glasshouse.core.usage.AnthropicUsageNormalizer;
import io.github.nephets.glasshouse.core.usage.CnUsageNormalizer;
import io.github.nephets.glasshouse.core.usage.NormalizeContext;
import io.github.nephets.glasshouse.core.usage.OpenAiUsageNormalizer;
import io.github.nephets.glasshouse.core.usage.RawUsage;
import io.github.nephets.glasshouse.core.usage.Usage;
import io.github.nephets.glasshouse.core.usage.UsageNormalizer;
import io.github.nephets.glasshouse.starter.model.ChatModelMetadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 计量归一化门面：按供应商选择正确的口径实现。
 *
 * <p>把「路由到哪个 {@link UsageNormalizer}」这件事集中在这里， 调用方（{@link ChatModelDecorator}）不需要知道供应商差异。
 */
public final class UsageNormalizers {

    private static final Logger log = LoggerFactory.getLogger(UsageNormalizers.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final OpenAiUsageNormalizer OPENAI = new OpenAiUsageNormalizer();
    private static final AnthropicUsageNormalizer ANTHROPIC = new AnthropicUsageNormalizer();
    private static final CnUsageNormalizer CN = new CnUsageNormalizer();

    private UsageNormalizers() {
        throw new AssertionError("常量类不允许实例化");
    }

    /**
     * 归一化 OpenAI 兼容响应的 usage。
     *
     * @param providerId 供应商标识
     * @param modelId 模型标识
     * @param ctx 归一化上下文
     * @param usageJson usage 节点的 JSON 文本；可为 null
     * @param usageChunkReceived 流式下是否收到 usage 块
     * @return 归一化计量
     */
    public static Usage normalize(
            String providerId,
            String modelId,
            NormalizeContext ctx,
            String usageJson,
            boolean usageChunkReceived) {
        JsonNode node = parse(usageJson);
        RawUsage raw;
        String provider =
                providerId == null ? "openai" : providerId.toLowerCase(java.util.Locale.ROOT);
        if ("anthropic".equals(provider)) {
            raw = AnthropicUsageNormalizer.parse(node, usageChunkReceived);
            return ANTHROPIC.normalize(raw, ctx);
        }
        if (CnUsageNormalizer.isCnProvider(provider)) {
            raw = CN.parse(provider, node, usageChunkReceived);
            return CN.normalize(raw, ctx);
        }
        // OpenAI 兼容口径：通义/DeepSeek/Kimi/豆包的 OpenAI 兼容端点与 OpenAI 完全一致
        raw = OpenAiUsageNormalizer.parse(node, usageChunkReceived);
        return OPENAI.normalize(raw, ctx);
    }

    /**
     * 归一化 Spring AI 的 {@code Usage} 对象。
     *
     * <p>Spring AI 已经把各家的 usage 结构归一化到了 {@code getPromptTokens()} / {@code
     * getCompletionTokens()}，因此这里只需要做「是否有原生 usage」的判断： {@code getNativeUsage()} 为 null 就说明供应商没返回
     * usage，必须走估算路径。
     *
     * @param springUsage Spring AI usage 对象，可为 null
     * @param ctx 归一化上下文
     * @return 归一化计量
     */
    public static Usage fromSpringAi(Object springUsage, NormalizeContext ctx) {
        return fromSpringAi(springUsage, ctx, !ctx.streaming());
    }

    /**
     * 归一化 Spring AI 的 usage 对象，并显式告知「供应商是否真的上报了 usage 块」。
     *
     * <p><b>为什么必须显式传这个标志</b>（而不是从 {@code ctx.streaming()} 推）： 非流式响应一定有 usage，但<b>流式响应不一定</b>——供应商没开
     * {@code include_usage} 时 一个分片都不带。早前版本用 {@code !ctx.streaming()} 推导，结果<b>流式永远被标成 {@code
     * streamUsageReported=false}</b>，哪怕供应商确实上报了真账。 这会让成本报表把真账和估算混在一起，正是本项目要解决的问题本身。
     *
     * @param springUsage Spring AI usage 对象，可为 null
     * @param ctx 归一化上下文
     * @param usageReported 供应商是否真的上报了 usage 块
     * @return 归一化计量
     */
    public static Usage fromSpringAi(
            Object springUsage, NormalizeContext ctx, boolean usageReported) {
        if (springUsage == null) {
            return OPENAI.normalize(RawUsage.empty(), ctx);
        }
        try {
            Long promptTokens = invokeLong(springUsage, "getPromptTokens");
            Long completionTokens = invokeLong(springUsage, "getCompletionTokens");
            Long total = invokeLong(springUsage, "getTotalTokens");
            if (promptTokens == null && completionTokens == null) {
                return OPENAI.normalize(RawUsage.empty(), ctx);
            }
            RawUsage raw =
                    new RawUsage(
                            promptTokens, completionTokens, 0L, 0L, total, false, usageReported);
            return OPENAI.normalize(raw, ctx);
        } catch (ReflectiveOperationException e) {
            log.debug("[glasshouse] 读取 Spring AI usage 失败，回退估算: {}", e.getMessage());
            return OPENAI.normalize(RawUsage.empty(), ctx);
        }
    }

    /**
     * 返回模型元数据里的供应商推断结果。
     *
     * <p>Spring AI 的不同 provider starter 会返回不同的实现类，无法可靠地通过类型判断供应商， 因此改为读取 Spring Boot 的 {@code
     * spring.ai.*} 配置键前缀。
     *
     * @param springProperties 已加载的 Spring AI 配置键值表，可为 null
     * @return 推断出的供应商标识；无法判断返回 {@code "openai"}
     */
    public static String inferProvider(Map<String, Object> springProperties) {
        if (springProperties == null) {
            return "openai";
        }
        if (springProperties.containsKey("spring.ai.anthropic")) {
            return "anthropic";
        }
        if (springProperties.containsKey("spring.ai.deepseek")) {
            return "deepseek";
        }
        if (springProperties.containsKey("spring.ai.zhipuai")) {
            return "zhipu";
        }
        if (springProperties.containsKey("spring.ai.moonshot")) {
            return "moonshot";
        }
        if (springProperties.containsKey("spring.ai.ollama")) {
            return "ollama";
        }
        if (springProperties.containsKey("spring.ai.dashscope")) {
            return "dashscope";
        }
        if (springProperties.containsKey("spring.ai.openai")) {
            return "openai";
        }
        return "openai";
    }

    /**
     * 反射方法缓存上限：256。
     *
     * <p>缓存键是「类名#方法名」。正常情况下键空间等于「Spring AI usage 实现类 × 3 个 getter」， 远小于上限；但如果 usage 对象来自运行期生成的代理类，
     * 类名会随代理实例变化而无限膨胀。这里给一个硬上限兜住这条路径。
     */
    private static final int MAX_CACHED_METHODS = 256;

    private static final ConcurrentMap<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();

    /**
     * 反射读取一个无参 long 返回值的方法。
     *
     * <p><b>这里是热路径</b>：{@link #fromSpringAi(Object, NormalizeContext, boolean)} 每次 LLM 调用里 都要连读
     * {@code getPromptTokens} / {@code getCompletionTokens} / {@code getTotalTokens} 三个方法，早前每次都做一次
     * {@code getMethod} 查表再 {@code invoke}， 也就是每条 span 至少 3 次无谓的反射查表。这里按「类名#方法名」缓存 {@link Method}，
     * 查不到时仍抛出 {@link NoSuchMethodException}， 返回值与原先完全一致。
     *
     * <p>缓存键带上类名是为了避免不同实现类间的同名方法互相污染；Spring AI 的 usage 实现类数量有限， 且与 ClassLoader 生命周期一致，不会无限增长。
     *
     * @param target 目标对象
     * @param methodName 方法名
     * @return 数值；方法不存在或返回 null 时返回 null
     * @throws ReflectiveOperationException 反射调用失败
     */
    private static Long invokeLong(Object target, String methodName)
            throws ReflectiveOperationException {
        String cacheKey = target.getClass().getName() + "#" + methodName;
        Method method = METHOD_CACHE.get(cacheKey);
        if (method == null) {
            Method looked = lookupMethod(target, cacheKey);
            if (looked != null) {
                if (METHOD_CACHE.size() >= MAX_CACHED_METHODS) {
                    // 只有键空间异常膨胀时才走到这里：整表清空的代价，
                    // 远小于让它无限增长直到 OOM。warn 级别是因为这本身就是要排查的异常信号。
                    log.warn(
                            "[glasshouse] 反射方法缓存达到上限 {}，整表清空；" + "usage 实现类数量异常（可能来自运行期生成的代理类）",
                            MAX_CACHED_METHODS);
                    METHOD_CACHE.clear();
                }
                METHOD_CACHE.put(cacheKey, looked);
                method = looked;
            }
        }
        if (method == null) {
            throw new NoSuchMethodException(cacheKey);
        }
        Object value = method.invoke(target);
        if (value instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    /**
     * 按缓存键反射查找无参方法，查不到返回 {@code null}（让调用方抛出与原语义一致的 {@link NoSuchMethodException}）。
     *
     * @param target 目标对象
     * @param cacheKey 「类名#方法名」形式的缓存键
     * @return 方法；不存在返回 null
     */
    private static Method lookupMethod(Object target, String cacheKey) {
        int separator = cacheKey.lastIndexOf('#');
        String methodName = cacheKey.substring(separator + 1);
        try {
            return target.getClass().getMethod(methodName);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /**
     * 把 usage JSON 文本解析为节点。
     *
     * @param usageJson JSON 文本，可为 null
     * @return 节点；解析失败或为 null 返回 null
     */
    private static JsonNode parse(String usageJson) {
        if (usageJson == null || usageJson.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(usageJson);
        } catch (JsonProcessingException e) {
            // 走估算路径是有意为之的降级（vendor 没给 usage 就按字符数估），日志已在 debug 级留痕
            log.debug("[glasshouse] usage JSON 解析失败，将走估算路径: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 列出全部可用的归一化器名称，供 {@code glasshouse prices validate} 与健康检查输出。
     *
     * @return 归一化器名称列表
     */
    public static List<String> availableNormalizers() {
        return List.of(OPENAI.name(), ANTHROPIC.name(), CN.name());
    }

    /**
     * 返回内置归一化器的元数据，用于 README 支持矩阵与 CLI 输出。
     *
     * @param metadata 模型元数据
     * @return 归一化器名称
     */
    public static String describe(ChatModelMetadata metadata) {
        if (metadata == null) {
            return OPENAI.name();
        }
        String provider = metadata.providerId();
        if ("anthropic".equals(provider)) {
            return ANTHROPIC.name();
        }
        return CnUsageNormalizer.isCnProvider(provider) ? CN.name() : OPENAI.name();
    }

    /**
     * 返回 span 名，供调用方统一生成。
     *
     * @param modelId 模型标识
     * @return span 名
     */
    public static String chatSpanName(String modelId) {
        return SpnNames.chat(modelId);
    }

    /**
     * 返回输入 token 属性键，供调用方统一写入。
     *
     * @return 属性键
     */
    public static io.opentelemetry.api.common.AttributeKey<Long> inputTokensKey() {
        return GenaiAttributes.USAGE_INPUT_TOKENS;
    }
}
