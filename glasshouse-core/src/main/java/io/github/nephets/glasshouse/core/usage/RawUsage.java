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

/**
 * 供应商返回的<b>原始</b> usage，字段全部可空。
 *
 * <p>这是「供应商异构结构」与「统一计量模型」之间的隔离层。归一化算法（架构 §2.2）作用于本类型， 而不是各家不同的 JSON 形状——这样新增一家供应商只需要写一个 {@link
 * UsageNormalizer}， 不需要动 {@link Usage} 与成本计算。
 *
 * @param promptTokens 输入 token，映射 OpenAI {@code prompt_tokens} / DashScope {@code input_tokens}
 * @param completionTokens 输出 token，映射 OpenAI {@code completion_tokens} / Anthropic {@code
 *     output_tokens}
 * @param reasoningTokens 推理 token，映射 DeepSeek {@code reasoning_content} / GLM {@code thinking}
 * @param cachedInputTokens 缓存命中的输入 token，映射 OpenAI {@code prompt_tokens_details.cached_tokens}
 * @param vendorTotalTokens 供应商返回的 total。<b>本仓不使用</b>，仅保留用于诊断日志比对
 * @param reasoningContentPresent 供应商是否返回了推理内容字段；false 时 {@code reasoningEstimated=true}
 * @param usageChunkReceived 流式请求下是否收到了末尾 usage 块
 */
public record RawUsage(
        Long promptTokens,
        Long completionTokens,
        Long reasoningTokens,
        Long cachedInputTokens,
        Long vendorTotalTokens,
        boolean reasoningContentPresent,
        boolean usageChunkReceived) {

    /** 全空的原始 usage，表示供应商未返回任何计量信息。 */
    public static RawUsage empty() {
        return new RawUsage(null, null, null, null, null, false, false);
    }

    /**
     * 构造「非流式且供应商完整返回」的原始 usage。
     *
     * @param promptTokens 输入 token
     * @param completionTokens 输出 token
     * @param cachedInputTokens 缓存命中输入 token
     * @param vendorTotalTokens 供应商 total，仅供诊断
     * @return 原始 usage
     */
    public static RawUsage reported(
            long promptTokens,
            long completionTokens,
            long cachedInputTokens,
            long vendorTotalTokens) {
        return new RawUsage(
                promptTokens,
                completionTokens,
                0L,
                cachedInputTokens,
                vendorTotalTokens,
                false,
                true);
    }

    /**
     * 判断供应商是否返回了任何计量字段。
     *
     * <p>归一化算法用它决定 {@code usageSource}：全部缺失则 {@code ESTIMATED}，否则 {@code VENDOR_REPORTED}。
     *
     * @return 至少有一个 token 字段非空返回 {@code true}
     */
    public boolean anyPresent() {
        return promptTokens != null
                || completionTokens != null
                || reasoningTokens != null
                || cachedInputTokens != null;
    }
}
