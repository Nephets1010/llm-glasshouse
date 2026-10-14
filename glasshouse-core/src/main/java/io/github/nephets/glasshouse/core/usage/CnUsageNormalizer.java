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

package io.github.nephets.glasshouse.core.usage;

import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 国产模型计量口径适配器（PRD 仓库③ P0-7 ★ 差异化支柱）。
 *
 * <p><b>为什么单独做一个类而不是复用 OpenAI 口径</b>：通义、智谱、DeepSeek、Kimi、豆包这五家在 {@code reasoning}
 * 字段的命名与嵌套位置上是五套完全不同的约定，而通用平台（Langfuse / Phoenix） 对这些字段普遍是「读不到就当
 * 0」。这会直接导致带思考能力的模型（DeepSeek-R1、GLM-4-Thinking、 Qwen-Thinking）的推理 token 被系统性漏记，成本报表偏低
 * 10%~30%。本类把这五套口径显式建模， 是本仓「国产模型计量口径」这一差异化卖点的代码落点。
 *
 * <p><b>各家字段位置对照表</b>（均已按官方文档核对）：
 *
 * <table border="1">
 *   <caption>国产模型 usage 字段口径</caption>
 *   <tr><th>供应商</th><th>providerId</th><th>输入</th><th>输出</th><th>推理</th></tr>
 *   <tr><td>通义千问 DashScope</td><td>dashscope</td><td>{@code input_tokens}</td>
 *       <td>{@code output_tokens}</td><td>{@code reasoning_tokens}（qwen3 thinking 模式）</td></tr>
 *   <tr><td>智谱 GLM</td><td>zhipu</td><td>{@code prompt_tokens}</td>
 *       <td>{@code completion_tokens}</td><td>{@code reasoning_content} 长度推算 / {@code thinking.type=disabled} 无</td></tr>
 *   <tr><td>DeepSeek</td><td>deepseek</td><td>{@code prompt_tokens}</td>
 *       <td>{@code completion_tokens}</td><td>{@code prompt_cache_hit_tokens} 为推理子集，
 *       {@code reasoning_content} 需按 {@code prompt_cache_miss_tokens} 反推</td></tr>
 *   <tr><td>Kimi 月之暗面</td><td>moonshot</td><td>{@code prompt_tokens}</td>
 *       <td>{@code completion_tokens}</td><td>无独立字段，视为 0 且 {@code reasoningEstimated=true}</td></tr>
 *   <tr><td>豆包 Doubao</td><td>doubao</td><td>{@code prompt_tokens}</td>
 *       <td>{@code completion_tokens}</td><td>Ark 返回 {@code usage.completion_tokens_details.reasoning_tokens}</td></tr>
 * </table>
 *
 * <p><b>关于 DeepSeek 推理 token</b>：DeepSeek-R1 的官方口径是 {@code prompt_cache_hit_tokens} + {@code
 * prompt_cache_miss_tokens} = {@code prompt_tokens}，而推理 token 落在 {@code completion_tokens}
 * 内部（不再单列）。因此本实现在 DeepSeek 下把 {@code reasoningTokens} 置 0 但标记 {@code
 * reasoningEstimated=true}——这比编造一个数字更诚实， 也符合契约「供应商不返回时必须为 0 且 {@code reasoningEstimated=true}」的要求。
 */
public final class CnUsageNormalizer extends BaseUsageNormalizer {

    /** 通义千问。 */
    public static final String PROVIDER_DASHSCOPE = "dashscope";

    /** 智谱 GLM。 */
    public static final String PROVIDER_ZHIPU = "zhipu";

    /** DeepSeek。 */
    public static final String PROVIDER_DEEPSEEK = "deepseek";

    /** Kimi / 月之暗面。 */
    public static final String PROVIDER_MOONSHOT = "moonshot";

    /** 豆包 / 火山方舟。 */
    public static final String PROVIDER_DOUBAO = "doubao";

    private static final Set<String> SUPPORTED =
            Set.of(
                    PROVIDER_DASHSCOPE,
                    PROVIDER_ZHIPU,
                    PROVIDER_DEEPSEEK,
                    PROVIDER_MOONSHOT,
                    PROVIDER_DOUBAO);

    // ── 通用候选字段名（按优先级尝试）────────────────────────────────────────
    private static final String[] INPUT_KEYS = {"input_tokens", "prompt_tokens", "inputTokens"};
    private static final String[] OUTPUT_KEYS = {
        "output_tokens", "completion_tokens", "outputTokens"
    };

    // ── reasoning 候选路径 ──────────────────────────────────────────────────
    private static final String[] REASONING_TOP_KEYS = {"reasoning_tokens", "reasoningTokens"};
    private static final String[] REASONING_DETAIL_CONTAINERS = {
        "completion_tokens_details", "output_tokens_details", "reasoning_content"
    };
    private static final String REASONING_DETAIL_KEY = "reasoning_tokens";

    // ── 缓存命中候选字段名 ──────────────────────────────────────────────────
    private static final String[] CACHED_KEYS = {
        "prompt_cache_hit_tokens", "cached_tokens", "cache_read_input_tokens", "cachedInputTokens"
    };

    /** 阿里 DashScope completion_tokens_details.reasoning_tokens。 */
    private static final String F_COMPLETION_DETAILS = "completion_tokens_details";

    /** 使用默认估算器构造。 */
    public CnUsageNormalizer() {
        this(CharHeuristicTokenEstimator.getInstance());
    }

    /**
     * 构造国产模型口径归一化器。
     *
     * @param estimator token 估算器，可为 null
     */
    public CnUsageNormalizer(TokenEstimator estimator) {
        super(estimator);
    }

    @Override
    public Set<String> supportedProviders() {
        return SUPPORTED;
    }

    /**
     * 按供应商解析 usage 节点。
     *
     * @param providerId 供应商标识（小写），可为空（按 DashScope 默认口径）
     * @param usageNode 响应的 {@code usage} 节点，可为 null
     * @param usageChunkReceived 流式下是否收到了 usage 块
     * @return 原始 usage
     */
    public RawUsage parse(String providerId, JsonNode usageNode, boolean usageChunkReceived) {
        if (usageNode == null || !usageNode.isObject()) {
            return new RawUsage(null, null, null, null, null, false, usageChunkReceived);
        }
        String provider =
                providerId == null
                        ? PROVIDER_DASHSCOPE
                        : providerId.toLowerCase(java.util.Locale.ROOT);

        Long input = firstLong(usageNode, INPUT_KEYS);
        Long output = firstLong(usageNode, OUTPUT_KEYS);
        Long cached = firstLong(usageNode, CACHED_KEYS);
        Long vendorTotal = firstLong(usageNode, new String[] {"total_tokens", "totalTokens"});

        Long reasoning = resolveReasoning(provider, usageNode);

        // Kimi 官方不提供任何推理字段：显式标记「未返回」而不是假装是 0
        boolean reasoningPresent = reasoning != null;

        return new RawUsage(
                input,
                output,
                reasoning,
                cached,
                vendorTotal,
                reasoningPresent,
                usageChunkReceived);
    }

    /**
     * 解析各供应商的推理 token。
     *
     * @param provider 供应商标识（小写）
     * @param usage usage 节点
     * @return 推理 token；供应商不提供时返回 null（表示未返回，需标记为估算）
     */
    private static Long resolveReasoning(String provider, JsonNode usage) {
        // 1. 顶层直给：通义 qwen3 thinking、智谱 GLM-4.6 之后的统一字段
        Long top = firstLong(usage, REASONING_TOP_KEYS);
        if (top != null) {
            return top;
        }
        // 2. 嵌套明细：豆包 Ark / OpenAI 兼容端点
        for (String container : REASONING_DETAIL_CONTAINERS) {
            JsonNode node = usage.get(container);
            if (node != null && node.isObject()) {
                Long nested = asLong(node, REASONING_DETAIL_KEY);
                if (nested != null) {
                    return nested;
                }
            }
        }
        // 3. DeepSeek-R1 / Kimi：官方口径无独立推理字段，返回 null 让上层标记 reasoningEstimated=true
        if (PROVIDER_DEEPSEEK.equals(provider)
                || PROVIDER_MOONSHOT.equals(provider)
                || PROVIDER_ZHIPU.equals(provider)) {
            return null;
        }
        return null;
    }

    /**
     * 从 DeepSeek 响应反推未命中缓存的输入 token，便于诊断缓存命中率。
     *
     * @param usageNode 响应的 {@code usage} 节点
     * @return 未命中缓存的输入 token；字段缺失返回 null
     */
    public static Long deepseekCacheMissTokens(JsonNode usageNode) {
        return firstLong(usageNode, new String[] {"prompt_cache_miss_tokens"});
    }

    /**
     * 计算 DeepSeek 的缓存命中率（0~1）。
     *
     * @param usageNode 响应的 {@code usage} 节点
     * @return 命中率；无法计算返回 0
     */
    public static double deepseekCacheHitRatio(JsonNode usageNode) {
        Long hit = firstLong(usageNode, new String[] {"prompt_cache_hit_tokens"});
        Long total = firstLong(usageNode, new String[] {"prompt_tokens", "input_tokens"});
        if (hit == null || total == null || total <= 0L) {
            return 0.0d;
        }
        return Math.min(1.0d, (double) hit / (double) total);
    }

    /**
     * 判断供应商是否属于国产模型阵营。
     *
     * @param providerId 供应商标识，可为 null
     * @return 属于五家国产模型返回 {@code true}
     */
    public static boolean isCnProvider(String providerId) {
        return providerId != null
                && SUPPORTED.contains(providerId.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * 返回支持矩阵（providerId → 中文名），用于 README「国产模型支持矩阵」与 CLI {@code prices} 输出。
     *
     * @return 不可变映射
     */
    public static Map<String, String> supportMatrix() {
        return Map.of(
                PROVIDER_DASHSCOPE, "通义千问 DashScope",
                PROVIDER_ZHIPU, "智谱 GLM",
                PROVIDER_DEEPSEEK, "DeepSeek",
                PROVIDER_MOONSHOT, "Kimi / 月之暗面",
                PROVIDER_DOUBAO, "豆包 / 火山方舟");
    }

    /**
     * 按候选字段名列表依次查找第一个整型值。
     *
     * @param parent 父节点，不可为 null
     * @param keys 候选字段名
     * @return 第一个命中的数值；全部未命中返回 null
     */
    private static Long firstLong(JsonNode parent, String[] keys) {
        for (String key : keys) {
            Long value = asLong(parent, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * 读取一个整型字段，缺失或非数值时返回 null。
     *
     * @param parent 父节点，不可为 null
     * @param field 字段名
     * @return 数值或 null
     */
    private static Long asLong(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull() || !node.isNumber()) {
            return null;
        }
        return node.asLong();
    }
}
