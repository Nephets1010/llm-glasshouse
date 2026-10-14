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

package io.github.nephets.glasshouse.starter.store;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.nephets.glasshouse.core.telemetry.SwyAttributes;
import io.github.nephets.glasshouse.starter.store.SpanRingBuffer.SpanData;

/**
 * Span 树查询（{@code glasshouse trace <traceId> --tree --cost} 的实现）。
 *
 * <p><b>它必须能同时回答两个问题</b>（架构 ③T05 验收标准第 2 条）：
 *
 * <ul>
 *   <li><b>慢在哪</b>：输出 {@code slowestSpan=}，指出耗时最长的 span
 *   <li><b>花了多少</b>：输出 {@code totalCostCny=}，给出成本合计
 * </ul>
 *
 * <p>这两个问题必须<b>一次查询同时回答</b>，因为用户排查成本问题的典型路径是： 「这个功能贵 → 那它慢在哪」。分两次查询会打断这个思路。
 */
public final class SpanTreeQuery {

    private final SpanRingBuffer buffer;

    /**
     * 构造查询器。
     *
     * @param buffer span 缓冲，不可为 null
     */
    public SpanTreeQuery(SpanRingBuffer buffer) {
        if (buffer == null) {
            throw new NullPointerException("buffer 不能为 null");
        }
        this.buffer = buffer;
    }

    /**
     * 返回一条链路的全部 span。
     *
     * @param traceId traceId
     * @return 按开始时刻排序的不可变列表
     */
    public List<SpanData> spansOf(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return List.of();
        }
        List<SpanData> result = new ArrayList<>();
        for (SpanData s : buffer.all()) {
            if (traceId.equals(s.traceId())) {
                result.add(s);
            }
        }
        result.sort(SpanData.byStartTime());
        return List.copyOf(result);
    }

    /**
     * 返回一条链路的树形结构。
     *
     * @param traceId traceId
     * @return 根节点列表（通常只有一个）；链路不存在返回空列表
     */
    public List<SpanNode> tree(String traceId) {
        List<SpanData> flat = spansOf(traceId);
        if (flat.isEmpty()) {
            return List.of();
        }
        Map<String, SpanNode> index = new LinkedHashMap<>();
        for (SpanData s : flat) {
            index.put(s.spanId(), new SpanNode(s, new ArrayList<>()));
        }
        List<SpanNode> roots = new ArrayList<>();
        for (SpanNode node : index.values()) {
            SpanData data = node.data();
            String parentId = data.parentSpanId();
            SpanNode parent = parentId == null ? null : index.get(parentId);
            if (parent == null) {
                roots.add(node);
            } else {
                parent.children().add(node);
            }
        }
        // 父 span 可能不在缓冲里（例如已被环形覆盖），这些 span 也要能被看到
        if (roots.isEmpty()) {
            return flat.stream().map(s -> new SpanNode(s, new ArrayList<>())).toList();
        }
        return List.copyOf(roots);
    }

    /**
     * 找到耗时最长的 span。
     *
     * @param traceId traceId
     * @return 最慢的 span；链路为空返回空
     */
    public Optional<SpanData> slowestSpan(String traceId) {
        return spansOf(traceId).stream().max(Comparator.comparingLong(SpanData::durationMillis));
    }

    /**
     * 计算一条链路的成本合计。
     *
     * @param traceId traceId
     * @return 成本合计（CNY），scale=4；无成本记录返回 0.0000
     */
    public BigDecimal totalCost(String traceId) {
        BigDecimal total = BigDecimal.ZERO;
        for (SpanData s : spansOf(traceId)) {
            Double cost = s.costAmount();
            if (cost != null) {
                total = total.add(BigDecimal.valueOf(cost));
            }
        }
        return total.setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 汇总一条链路的 token 用量。
     *
     * @param traceId traceId
     * @return 输入 + 输出 token 合计
     */
    public TokenSummary tokenSummary(String traceId) {
        long input = 0L;
        long output = 0L;
        long calls = 0L;
        for (SpanData s : spansOf(traceId)) {
            Long in = s.inputTokens();
            Long out = s.outputTokens();
            if (in != null || out != null) {
                calls++;
                input += in == null ? 0L : in;
                output += out == null ? 0L : out;
            }
        }
        return new TokenSummary(input, output, calls);
    }

    /**
     * 渲染文本形式的 span 树。
     *
     * <p><b>输出必须同时包含 {@code slowestSpan=} 与 {@code totalCostCny=}</b>， 这两个标记是架构 ③T05 的机器可判定验收点。
     *
     * @param traceId traceId
     * @param withCost 是否附带成本列
     * @return 多行文本
     */
    public String render(String traceId, boolean withCost) {
        List<SpanNode> roots = tree(traceId);
        StringBuilder sb = new StringBuilder();
        if (roots.isEmpty()) {
            sb.append("未找到 traceId=").append(traceId).append(" 的 span。");
            sb.append('\n').append("提示：请确认应用仍在运行，且请求是在开启 llm.glasshouse.enabled 的实例上发起的。");
            return sb.toString();
        }
        sb.append("traceId=").append(traceId).append('\n');
        TokenSummary tokens = tokenSummary(traceId);
        sb.append("spans=")
                .append(spansOf(traceId).size())
                .append(", modelCalls=")
                .append(tokens.modelCalls())
                .append(", tokensIn=")
                .append(tokens.inputTokens())
                .append(", tokensOut=")
                .append(tokens.outputTokens())
                .append('\n');
        for (SpanNode root : roots) {
            renderNode(sb, root, 0, withCost);
        }
        slowestSpan(traceId)
                .ifPresentOrElse(
                        slowest ->
                                sb.append("\nslowestSpan=")
                                        .append(slowest.name())
                                        .append(" durationMs=")
                                        .append(slowest.durationMillis()),
                        () -> sb.append("\nslowestSpan=(none)"));
        sb.append("\ntotalCostCny=").append(totalCost(traceId).toPlainString());
        return sb.toString();
    }

    /**
     * 递归渲染一个节点。
     *
     * @param sb 输出缓冲
     * @param node 当前节点
     * @param depth 深度
     * @param withCost 是否附带成本列
     */
    private void renderNode(StringBuilder sb, SpanNode node, int depth, boolean withCost) {
        SpanData data = node.data();
        sb.append("  ".repeat(depth));
        if (depth > 0) {
            // 根节点不加连接符：它是链路的起点，用 └─ 反而看起来像一个子步骤
            sb.append("└─ ");
        }
        sb.append(data.name()).append(' ').append(data.durationMillis()).append("ms");
        if (withCost) {
            Double cost = data.costAmount();
            sb.append("  ¥")
                    .append(
                            cost == null
                                    ? BigDecimal.ZERO
                                            .setScale(4, RoundingMode.HALF_UP)
                                            .toPlainString()
                                    : BigDecimal.valueOf(cost)
                                            .setScale(4, RoundingMode.HALF_UP)
                                            .toPlainString());
        }
        Long in = data.inputTokens();
        if (in != null) {
            Long out = data.outputTokens();
            sb.append("  (in ")
                    .append(in)
                    .append("/out ")
                    .append(out == null ? 0 : out)
                    .append(')');
        }
        String tenant = stringAttr(data, SwyAttributes.TENANT_ID.getKey());
        if (tenant != null) {
            sb.append("  tenant=").append(tenant);
        }
        if ("ERROR".equals(data.status())) {
            sb.append("  ⚠ ").append(data.errorMessage() == null ? "error" : data.errorMessage());
        }
        sb.append('\n');
        for (SpanNode child : node.children()) {
            renderNode(sb, child, depth + 1, withCost);
        }
    }

    private static String stringAttr(SpanData data, String key) {
        Object v = data.attributes().get(key);
        return v == null ? null : String.valueOf(v);
    }

    /**
     * 一条链路的 token 用量汇总。
     *
     * @param inputTokens 输入 token 合计
     * @param outputTokens 输出 token 合计
     * @param modelCalls 有 token 记录的模型调用数
     */
    public record TokenSummary(long inputTokens, long outputTokens, long modelCalls) {

        /**
         * 返回 token 总量。
         *
         * @return 输入 + 输出
         */
        public long total() {
            return inputTokens + outputTokens;
        }
    }

    /**
     * Span 树节点。
     *
     * @param data span 数据
     * @param children 子节点列表
     */
    public record SpanNode(SpanData data, List<SpanNode> children) {}
}
