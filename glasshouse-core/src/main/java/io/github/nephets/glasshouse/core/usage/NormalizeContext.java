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
 * 归一化上下文：当供应商未返回 usage 时，用于回退估算的输入。
 *
 * @param promptText 渲染后的完整 prompt（system + user 拼接），可为 null
 * @param completionText 模型响应文本，可为 null
 * @param maxTokens 请求声明的 {@code max_tokens}，可为 null
 * @param streaming 本次请求是否为流式
 * @param providerId 供应商标识，小写（见契约 {@code ProviderId} 枚举）
 * @param modelId 模型标识
 */
public record NormalizeContext(
        String promptText,
        String completionText,
        Integer maxTokens,
        boolean streaming,
        String providerId,
        String modelId) {

    /**
     * 构造非流式上下文。
     *
     * @param promptText prompt 全文
     * @param completionText 响应全文
     * @param maxTokens max_tokens，可为 null
     * @param providerId 供应商标识
     * @param modelId 模型标识
     * @return 上下文
     */
    public static NormalizeContext nonStreaming(
            String promptText,
            String completionText,
            Integer maxTokens,
            String providerId,
            String modelId) {
        return new NormalizeContext(
                promptText, completionText, maxTokens, false, providerId, modelId);
    }

    /**
     * 构造流式上下文。
     *
     * @param promptText prompt 全文
     * @param maxTokens max_tokens，可为 null
     * @param providerId 供应商标识
     * @param modelId 模型标识
     * @return 上下文
     */
    public static NormalizeContext streaming(
            String promptText, Integer maxTokens, String providerId, String modelId) {
        return new NormalizeContext(promptText, null, maxTokens, true, providerId, modelId);
    }
}
