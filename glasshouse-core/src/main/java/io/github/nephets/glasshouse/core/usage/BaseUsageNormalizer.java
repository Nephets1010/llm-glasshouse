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
 * 归一化算法模板实现：把「算法」与「供应商字段映射」分离。
 *
 * <p>子类只需回答一个问题：「这家供应商的 JSON 里那四个数分别叫什么、在哪」，算法（回退顺序、 {@code totalTokens} 重算、{@code usageSource}
 * 判定）全部由本类固定，保证三仓逻辑一致且不漂移。
 */
public abstract class BaseUsageNormalizer implements UsageNormalizer {

    private final TokenEstimator estimator;

    /**
     * 构造归一化器。
     *
     * @param estimator token 估算器；为 null 时退化为 {@link CharHeuristicTokenEstimator#getInstance()}
     */
    protected BaseUsageNormalizer(TokenEstimator estimator) {
        this.estimator = estimator == null ? CharHeuristicTokenEstimator.getInstance() : estimator;
    }

    @Override
    public final Usage normalize(RawUsage raw, NormalizeContext ctx) {
        RawUsage effective = raw == null ? RawUsage.empty() : raw;
        NormalizeContext context =
                ctx == null ? new NormalizeContext(null, null, null, false, null, null) : ctx;

        // 1. 输入 token：优先供应商，其次估算器，最后 max_tokens 上界
        long inputTokens = resolveInputTokens(effective, context);
        // 2. 缓存命中输入：供应商不给就是 0
        long cachedInputTokens = orZero(effective.cachedInputTokens());
        // 缓存命中不可能超过输入本身，防御性夹紧，避免上游脏数据把 Usage 构造器打挂
        cachedInputTokens = Math.min(cachedInputTokens, inputTokens);
        // 3. 输出 token：优先供应商，其次估算器，最后 0
        long outputTokens = resolveOutputTokens(effective, context);
        // 4. reasoning token：供应商不给就是 0，并标记为估算
        long reasoningTokens = orZero(effective.reasoningTokens());
        boolean reasoningEstimated = !effective.reasoningContentPresent();
        // 6. 计量来源：供应商一个字段都没给就是估算
        UsageSource source =
                effective.anyPresent() ? UsageSource.VENDOR_REPORTED : UsageSource.ESTIMATED;
        // 7. 流式下是否真收到 usage 块
        boolean streamReported = context.streaming() && effective.usageChunkReceived();
        // 5. totalTokens 由 Usage 构造器重算，此处不传

        return new Usage(
                inputTokens,
                outputTokens,
                reasoningTokens,
                cachedInputTokens,
                0L,
                source,
                reasoningEstimated,
                streamReported);
    }

    /**
     * 解析输入 token。
     *
     * @param raw 原始 usage
     * @param ctx 归一化上下文
     * @return 非负的输入 token
     */
    private long resolveInputTokens(RawUsage raw, NormalizeContext ctx) {
        if (raw.promptTokens() != null) {
            return Math.max(0L, raw.promptTokens());
        }
        long estimated = estimator.estimateTokens(ctx.promptText());
        if (estimated > 0L) {
            return estimated;
        }
        return ctx.maxTokens() == null ? 0L : Math.max(0L, ctx.maxTokens());
    }

    /**
     * 解析输出 token。
     *
     * @param raw 原始 usage
     * @param ctx 归一化上下文
     * @return 非负的输出 token
     */
    private long resolveOutputTokens(RawUsage raw, NormalizeContext ctx) {
        if (raw.completionTokens() != null) {
            return Math.max(0L, raw.completionTokens());
        }
        return Math.max(0L, estimator.estimateTokens(ctx.completionText()));
    }

    /**
     * 把可空 Long 转为非负 long。
     *
     * @param value 可空值
     * @return null 时返回 0，否则返回非负值
     */
    private static long orZero(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    /**
     * 取出本实现使用的 token 估算器。
     *
     * @return 估算器
     */
    protected final TokenEstimator estimator() {
        return estimator;
    }
}
