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
 * OpenAI 兼容口径归一化器（覆盖 {@code openai} 及所有 OpenAI 兼容端点）。
 *
 * <p><b>口径说明</b>：OpenAI 的 {@code prompt_tokens} <b>已经包含</b> {@code
 * prompt_tokens_details.cached_tokens}。 这一点极易搞错——如果把 cached 再加一次，缓存命中的部分会被重复计费。本实现在解析阶段就把 cached
 * 识别出来，交给 {@link Usage} 去保证 {@code cachedInputTokens <= inputTokens}， 成本计算阶段再把 cached 从 input 里扣掉（见
 * {@code CostCalculator}）。
 */
public final class OpenAiUsageNormalizer extends BaseUsageNormalizer {

    /** JSON 路径：输入 token。 */
    public static final String F_PROMPT_TOKENS = "prompt_tokens";

    /** JSON 路径：输出 token。 */
    public static final String F_COMPLETION_TOKENS = "completion_tokens";

    /** JSON 路径：供应商 total（本仓不使用，仅诊断）。 */
    public static final String F_TOTAL_TOKENS = "total_tokens";

    /** JSON 路径：输入明细容器。 */
    public static final String F_PROMPT_DETAILS = "prompt_tokens_details";

    /** JSON 路径：缓存命中 token。 */
    public static final String F_CACHED_TOKENS = "cached_tokens";

    /** JSON 路径：推理 token（o1 系列 / 部分推理模型）。 */
    public static final String F_COMPLETION_DETAILS = "completion_tokens_details";

    /** JSON 路径：推理 token 明细字段。 */
    public static final String F_REASONING_TOKENS = "reasoning_tokens";

    private static final Set<String> SUPPORTED = Set.of("openai");

    /** 使用默认估算器构造。 */
    public OpenAiUsageNormalizer() {
        this(CharHeuristicTokenEstimator.getInstance());
    }

    /**
     * 构造 OpenAI 口径归一化器。
     *
     * @param estimator token 估算器，可为 null
     */
    public OpenAiUsageNormalizer(TokenEstimator estimator) {
        super(estimator);
    }

    @Override
    public Set<String> supportedProviders() {
        return SUPPORTED;
    }

    /**
     * 从 OpenAI 响应的 {@code usage} 节点解析原始计量。
     *
     * @param usageNode 响应的 {@code usage} 节点；可为 null
     * @param usageChunkReceived 流式下是否收到了 usage 块
     * @return 原始 usage；节点为空时返回 {@link RawUsage#empty()}
     */
    public static RawUsage parse(JsonNode usageNode, boolean usageChunkReceived) {
        if (usageNode == null || !usageNode.isObject()) {
            return new RawUsage(null, null, null, null, null, false, usageChunkReceived);
        }
        Long promptTokens = asLong(usageNode, F_PROMPT_TOKENS);
        Long completionTokens = asLong(usageNode, F_COMPLETION_TOKENS);
        Long vendorTotal = asLong(usageNode, F_TOTAL_TOKENS);

        Long cachedTokens = null;
        JsonNode details = usageNode.get(F_PROMPT_DETAILS);
        if (details != null && details.isObject()) {
            cachedTokens = asLong(details, F_CACHED_TOKENS);
        }

        Long reasoningTokens = null;
        boolean reasoningPresent = false;
        JsonNode completionDetails = usageNode.get(F_COMPLETION_DETAILS);
        if (completionDetails != null && completionDetails.isObject()) {
            reasoningTokens = asLong(completionDetails, F_REASONING_TOKENS);
            reasoningPresent = reasoningTokens != null;
        }

        return new RawUsage(
                promptTokens,
                completionTokens,
                reasoningTokens,
                cachedTokens,
                vendorTotal,
                reasoningPresent,
                usageChunkReceived);
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
