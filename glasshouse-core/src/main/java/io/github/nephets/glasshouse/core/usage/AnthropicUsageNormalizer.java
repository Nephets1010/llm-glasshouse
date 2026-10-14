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

import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Anthropic 口径归一化器。
 *
 * <p><b>口径差异（相对 OpenAI）</b>：
 *
 * <ul>
 *   <li>字段名是 {@code input_tokens} / {@code output_tokens}，不是 {@code prompt_tokens} / {@code
 *       completion_tokens}
 *   <li>Anthropic 原生区分「读缓存」{@code cache_read_input_tokens} 与「写缓存」{@code
 *       cache_creation_input_tokens}
 *   <li>{@code input_tokens} <b>不包含</b>缓存命中部分，两者相加才是总输入——与 OpenAI 相反
 * </ul>
 *
 * <p>为了与 OpenAI 口径统一（{@code inputTokens} 是「总输入」、{@code cachedInputTokens} 是「其中命中缓存」）， 本实现在解析阶段就把
 * cache_read 加回 input_tokens。
 */
public final class AnthropicUsageNormalizer extends BaseUsageNormalizer {

    /** JSON 路径：输入 token（不含缓存命中）。 */
    public static final String F_INPUT_TOKENS = "input_tokens";

    /** JSON 路径：输出 token。 */
    public static final String F_OUTPUT_TOKENS = "output_tokens";

    /** JSON 路径：读缓存命中 token。 */
    public static final String F_CACHE_READ_INPUT_TOKENS = "cache_read_input_tokens";

    /** JSON 路径：写缓存 token（本仓按输入计费，不单独定价）。 */
    public static final String F_CACHE_CREATION_INPUT_TOKENS = "cache_creation_input_tokens";

    private static final Set<String> SUPPORTED = Set.of("anthropic");

    /** 使用默认估算器构造。 */
    public AnthropicUsageNormalizer() {
        this(CharHeuristicTokenEstimator.getInstance());
    }

    /**
     * 构造 Anthropic 口径归一化器。
     *
     * @param estimator token 估算器，可为 null
     */
    public AnthropicUsageNormalizer(TokenEstimator estimator) {
        super(estimator);
    }

    @Override
    public Set<String> supportedProviders() {
        return SUPPORTED;
    }

    /**
     * 从 Anthropic 响应的 {@code usage} 节点解析原始计量。
     *
     * @param usageNode 响应的 {@code usage} 节点；可为 null
     * @param usageChunkReceived 流式下是否收到了 usage 块（Anthropic 在 message_start 就带 usage）
     * @return 原始 usage
     */
    public static RawUsage parse(JsonNode usageNode, boolean usageChunkReceived) {
        if (usageNode == null || !usageNode.isObject()) {
            return new RawUsage(null, null, null, null, null, false, usageChunkReceived);
        }
        Long inputTokens = asLong(usageNode, F_INPUT_TOKENS);
        Long outputTokens = asLong(usageNode, F_OUTPUT_TOKENS);
        Long cacheRead = asLong(usageNode, F_CACHE_READ_INPUT_TOKENS);
        Long cacheWrite = asLong(usageNode, F_CACHE_CREATION_INPUT_TOKENS);

        // 统一到 OpenAI 口径：inputTokens 是总输入
        Long totalInput = inputTokens;
        if (inputTokens != null && cacheRead != null) {
            totalInput = inputTokens + cacheRead;
        }
        // 写缓存的 token 也算输入（无独立价格时按 inputPrice 计）
        if (totalInput != null && cacheWrite != null) {
            totalInput = totalInput + cacheWrite;
        }

        return new RawUsage(
                totalInput, outputTokens, 0L, cacheRead, null, false, usageChunkReceived);
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
