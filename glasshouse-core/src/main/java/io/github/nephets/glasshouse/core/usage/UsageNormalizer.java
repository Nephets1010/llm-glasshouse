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
 * Token 计量归一化 SPI（契约 {@code USG-001} 的算法实现）。
 *
 * <p>归一化算法（三仓同一套逻辑，各写一份）：
 *
 * <pre>
 * normalize(raw, ctx):
 *   1. inputTokens       = raw.promptTokens  ?: estimator.estimate(ctx.prompt)   ?: ctx.maxTokens
 *   2. cachedInputTokens = raw.cachedInputTokens ?: 0
 *   3. outputTokens      = raw.completionTokens ?: estimator.estimate(ctx.completion) ?: 0
 *   4. reasoningTokens   = raw.reasoningTokens ?: 0; reasoningEstimated = !raw.reasoningContentPresent
 *   5. totalTokens       = 1 + 3 + 4// ← 重算，不信任上游（由 Usage 构造器保证）
 *   6. usageSource       = raw.anyPresent() ? VENDOR_REPORTED : ESTIMATED
 *   7. streamUsageReported = ctx.streaming ? raw.usageChunkReceived : false
 * </pre>
 *
 * <p>各供应商的实现只负责「怎么从异构 JSON 里把字段抠出来」，算法主体由 {@link BaseUsageNormalizer} 统一提供，避免「同一套算法写六遍然后各自漂移」。
 */
public interface UsageNormalizer {

    /**
     * 该实现支持的供应商标识集合（小写，见契约 {@code ProviderId}）。
     *
     * @return 支持的 providerId 集合，不可为空
     */
    java.util.Set<String> supportedProviders();

    /**
     * 执行归一化。
     *
     * @param raw 供应商原始 usage，可为 null（等价于 {@link RawUsage#empty()}）
     * @param ctx 归一化上下文，不可为 null
     * @return 归一化后的计量，不可为 null
     */
    Usage normalize(RawUsage raw, NormalizeContext ctx);

    /**
     * 返回实现名称，用于日志与诊断。
     *
     * @return 实现名称
     */
    default String name() {
        return getClass().getSimpleName();
    }

    /**
     * 判断该实现是否支持指定供应商。
     *
     * @param providerId 供应商标识，可为 null
     * @return 支持返回 {@code true}
     */
    default boolean supports(String providerId) {
        return providerId != null && supportedProviders().contains(providerId);
    }
}
