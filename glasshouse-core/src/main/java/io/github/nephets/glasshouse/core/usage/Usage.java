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
 * 归一化后的 Token 计量（契约编号 {@code USG-001}，★ 最关键契约）。
 *
 * <p>字段顺序、单位、语义与 {@code llm-switchyard} / {@code agentswitchyard} 完全一致。任何一方修改， 必须三仓同 PR（由 {@code
 * ContractSchemaTest} 强制）。
 *
 * <p><b>核心不变量</b>：{@code totalTokens} 一律由本仓重算（{@code input + output + reasoning}）， <b>禁止拷贝供应商返回的
 * total</b>。原因见架构 §9.3 风险 3：不同供应商对 total 的口径不一致 （部分含 reasoning、部分不含、部分把 cached
 * 重复计入），信任上游会让成本对账在对方改算法时静默失败。
 *
 * <p><b>fail-fast</b>：构造时断言所有 token 字段 {@code >= 0} 且 {@code cachedInputTokens <= inputTokens}， 违规抛
 * {@link IllegalArgumentException}（架构 §8.4：token 计数禁止负值）。
 *
 * @param inputTokens 输入 token
 * @param outputTokens 输出 token
 * @param reasoningTokens 推理/思考 token；供应商不返回时为 0 且 {@code reasoningEstimated=true}
 * @param cachedInputTokens 输入中命中缓存的部分，<b>已从 {@code inputTokens} 中扣除，不重复计费</b>
 * @param totalTokens 由本仓重算：{@code inputTokens + outputTokens + reasoningTokens}
 * @param usageSource 计量来源
 * @param reasoningEstimated reasoning 是否为推算值
 * @param streamUsageReported 流式请求下是否真的收到了 usage 块
 */
public record Usage(
        long inputTokens,
        long outputTokens,
        long reasoningTokens,
        long cachedInputTokens,
        long totalTokens,
        UsageSource usageSource,
        boolean reasoningEstimated,
        boolean streamUsageReported) {

    /**
     * 紧凑构造器：重算 {@code totalTokens} 并做契约校验。
     *
     * <p>入参中的 {@code totalTokens} <b>被忽略</b>，本仓一律重算。这样调用方即使错误地把上游 total 传进来，
     * 也不会污染结果——这是「禁止拷贝上游值」在类型层面的强制。
     *
     * @throws IllegalArgumentException 任一 token 字段为负，或 {@code cachedInputTokens > inputTokens}
     * @throws NullPointerException usageSource 为 null
     */
    public Usage {
        if (inputTokens < 0 || outputTokens < 0 || reasoningTokens < 0 || cachedInputTokens < 0) {
            throw new IllegalArgumentException(
                    "token 计数禁止负值: input="
                            + inputTokens
                            + ", output="
                            + outputTokens
                            + ", reasoning="
                            + reasoningTokens
                            + ", cached="
                            + cachedInputTokens);
        }
        if (cachedInputTokens > inputTokens) {
            throw new IllegalArgumentException(
                    "cachedInputTokens("
                            + cachedInputTokens
                            + ") 不得超过 inputTokens("
                            + inputTokens
                            + ")");
        }
        if (usageSource == null) {
            throw new NullPointerException("usageSource 不能为 null");
        }
        totalTokens = Math.addExact(Math.addExact(inputTokens, outputTokens), reasoningTokens);
    }

    /**
     * 常规构造：供应商明确返回了 usage，reasoning 由供应商给出。
     *
     * @param inputTokens 输入 token
     * @param outputTokens 输出 token
     * @param reasoningTokens 推理 token
     * @param cachedInputTokens 缓存命中的输入 token
     * @return usage 实例
     */
    public static Usage of(
            long inputTokens, long outputTokens, long reasoningTokens, long cachedInputTokens) {
        return new Usage(
                inputTokens,
                outputTokens,
                reasoningTokens,
                cachedInputTokens,
                0L,
                UsageSource.VENDOR_REPORTED,
                false,
                false);
    }

    /**
     * 常规构造：无 reasoning、无缓存命中。
     *
     * @param inputTokens 输入 token
     * @param outputTokens 输出 token
     * @return usage 实例
     */
    public static Usage of(long inputTokens, long outputTokens) {
        return of(inputTokens, outputTokens, 0L, 0L);
    }

    /**
     * 估算构造：带具体 token 数，来源标记为 {@code ESTIMATED}。
     *
     * <p>离线成本对账（{@code glasshouse cost --input spans.jsonl}）会从 span 属性里 还原 usage；当 span 上 {@code
     * swy.usage.source=ESTIMATED} 时用它重建， 这样报表里的「估算占比」才能正确算出来。
     *
     * @param inputTokens 输入 token（估算值）
     * @param outputTokens 输出 token（估算值）
     * @param reasoningTokens 推理 token（估算值）
     * @param cachedInputTokens 缓存命中的输入 token
     * @return usage 实例
     */
    public static Usage estimated(
            long inputTokens, long outputTokens, long reasoningTokens, long cachedInputTokens) {
        return new Usage(
                inputTokens,
                outputTokens,
                reasoningTokens,
                cachedInputTokens,
                0L,
                UsageSource.ESTIMATED,
                true,
                false);
    }

    /**
     * 估算构造：全部字段为 0，来源标记为 {@code ESTIMATED}。
     *
     * <p>用于「无 token 的事件」（如纯检索、纯规则步骤）在 {@code AuditEvent} 中占位， 保证 {@code usage} 永不为 null（契约要求）。
     *
     * @param src 计量来源
     * @param streamReported 流式下是否真收到 usage 块
     * @return usage 实例
     */
    public static Usage estimated(UsageSource src, boolean streamReported) {
        return new Usage(0L, 0L, 0L, 0L, 0L, src, true, streamReported);
    }

    /**
     * 估算构造：来源 {@code ESTIMATED}，非流式。
     *
     * @return usage 实例
     */
    public static Usage estimated() {
        return estimated(UsageSource.ESTIMATED, false);
    }

    /**
     * 兜底构造：按上界计的保守值。
     *
     * @param maxTokens 上界 token 数
     * @return usage 实例
     */
    public static Usage fallback(long maxTokens) {
        long bounded = Math.max(maxTokens, 0L);
        return new Usage(bounded, 0L, 0L, 0L, 0L, UsageSource.FALLBACK, true, false);
    }

    /**
     * 计算实际需要计费的输入 token（扣除缓存命中部分）。
     *
     * @return 非负的计费输入 token
     */
    public long billableInputTokens() {
        return inputTokens - cachedInputTokens;
    }

    /**
     * 判断本次计量是否由供应商真实返回。
     *
     * @return 是 {@code VENDOR_REPORTED} 返回 {@code true}
     */
    public boolean vendorReported() {
        return usageSource == UsageSource.VENDOR_REPORTED;
    }

    /**
     * 判断计量是否可信（供应商真实返回）。
     *
     * <p>成本报表在呈现前应过滤掉非可信数据，或在 UI 上明确标注为估算值。
     *
     * @return 计量可信返回 {@code true}
     */
    public boolean trustworthy() {
        return usageSource == UsageSource.VENDOR_REPORTED;
    }
}
