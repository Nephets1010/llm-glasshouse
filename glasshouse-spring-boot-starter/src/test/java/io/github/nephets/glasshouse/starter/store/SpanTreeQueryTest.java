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

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Span 树与环形缓冲测试（架构 ③T05 验收标准第 2、6 条）。
 *
 * <p>核心断言：{@code render()} 的输出<b>必须同时包含</b> {@code slowestSpan=} 与 {@code
 * totalCostCny=}——因为「慢在哪」与「花了多少」必须一次查询同时回答， 分两次查询会打断「这个功能贵 → 那它慢在哪」的排查思路。
 */
class SpanTreeQueryTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private SpanRingBuffer buffer;
    private SpanTreeQuery query;

    @BeforeEach
    void setUp() {
        buffer = new SpanRingBuffer(100);
        query = new SpanTreeQuery(buffer);
    }

    /**
     * 写入一条 span 快照。
     *
     * @param spanId spanId
     * @param parentId 父 spanId
     * @param name span 名
     * @param durationMs 耗时
     * @param cost 成本；null 表示不写成本
     * @param inTokens 输入 token
     * @param outTokens 输出 token
     * @param extra 额外属性
     * @return 写入的快照
     */
    private SpanRingBuffer.SpanData append(
            String spanId,
            String parentId,
            String name,
            long durationMs,
            Double cost,
            Long inTokens,
            Long outTokens,
            Map<String, Object> extra) {
        Instant start = Instant.parse("2026-10-09T12:00:00Z");
        SpanRingBuffer.SpanData data =
                new SpanRingBuffer.SpanData(
                        TRACE_ID,
                        spanId,
                        parentId,
                        name,
                        name.split(" ")[0],
                        name.length() > 1 ? name.substring(name.indexOf(' ') + 1) : "",
                        start,
                        start.plusMillis(durationMs),
                        extra,
                        "OK",
                        null);
        if (cost != null) {
            Map<String, Object> merged = new java.util.LinkedHashMap<>(extra);
            merged.put("swy.cost.amount", cost);
            merged.put("gen_ai.usage.input_tokens", inTokens);
            merged.put("gen_ai.usage.output_tokens", outTokens);
            data =
                    new SpanRingBuffer.SpanData(
                            TRACE_ID,
                            spanId,
                            parentId,
                            name,
                            name.split(" ")[0],
                            name.length() > 1 ? name.substring(name.indexOf(' ') + 1) : "",
                            start,
                            start.plusMillis(durationMs),
                            merged,
                            "OK",
                            null);
        }
        buffer.append(data);
        return data;
    }

    private void appendAgentChain() {
        // 模拟一次「planner → kb_lookup → writer」的三步 Agent
        append(
                "aaaaaaaaaaaaaaaaaaaaaaaa",
                null,
                "invoke_agent planner",
                1900L,
                null,
                null,
                null,
                Map.of("swy.agent.id", "planner", "swy.feature", "customer-service"));
        append(
                "bbbbbbbbbbbbbbbbbbbbbbbb",
                "aaaaaaaaaaaaaaaaaaaaaaaa",
                "chat deepseek-chat",
                1500L,
                0.008d,
                1200L,
                340L,
                Map.of());
        append(
                "cccccccccccccccccccccccc",
                "aaaaaaaaaaaaaaaaaaaaaaaa",
                "retrieval kb_lookup",
                80L,
                null,
                null,
                null,
                Map.of("swy.step.kind", "RULE"));
        append(
                "dddddddddddddddddddddddd",
                "aaaaaaaaaaaaaaaaaaaaaaaa",
                "execute_tool ticket_create",
                320L,
                null,
                null,
                null,
                Map.of("swy.step.kind", "TOOL"));
        append(
                "eeeeeeeeeeeeeeeeeeeeeeee",
                "aaaaaaaaaaaaaaaaaaaaaaaa",
                "chat qwen-max",
                900L,
                0.021d,
                1200L,
                340L,
                Map.of());
    }

    @Test
    @DisplayName("★ render 输出必须同时含 slowestSpan= 与 totalCostCny=")
    void renderAnswersBothQuestions() {
        appendAgentChain();
        String rendered = query.render(TRACE_ID, true);

        assertThat(rendered)
                .as("★ 架构 3T05：输出必须能同时回答「慢在哪」与「花了多少」")
                .contains("slowestSpan=")
                .contains("totalCostCny=");

        assertThat(rendered)
                .as("最慢的应是 planner（1900ms），而不是它的子 span chat（1500ms）")
                .contains("slowestSpan=invoke_agent planner durationMs=1900");
        assertThat(rendered).as("根节点不带连接符，它是链路起点").contains("\ninvoke_agent planner");

        assertThat(rendered).as("成本合计 0.008 + 0.021 = 0.0290 CNY").contains("totalCostCny=0.0290");
    }

    @Test
    @DisplayName("★ span 树父子关系正确：1 个根 + 4 个子")
    void treeStructure() {
        appendAgentChain();
        List<SpanTreeQuery.SpanNode> roots = query.tree(TRACE_ID);
        assertThat(roots).hasSize(1);
        assertThat(roots.get(0).data().name()).isEqualTo("invoke_agent planner");
        assertThat(roots.get(0).children()).hasSize(4);
        assertThat(roots.get(0).children())
                .extracting(n -> n.data().name())
                .containsExactlyInAnyOrder(
                        "chat deepseek-chat",
                        "retrieval kb_lookup",
                        "execute_tool ticket_create",
                        "chat qwen-max");
        assertThat(query.spansOf(TRACE_ID)).hasSize(5);
    }

    @Test
    @DisplayName("树形渲染带缩进，可直接看出嵌套层级")
    void renderIndentsChildren() {
        appendAgentChain();
        String rendered = query.render(TRACE_ID, true);
        assertThat(rendered).contains("invoke_agent planner");
        assertThat(rendered)
                .as("子 span 必须缩进，否则用户看不出层级")
                .contains("└─ chat deepseek-chat")
                .contains("  └─ retrieval kb_lookup");
    }

    @Test
    @DisplayName("render 带成本列时输出 ¥ 与 token 统计")
    void renderWithCostColumn() {
        appendAgentChain();
        String withCost = query.render(TRACE_ID, true);
        assertThat(withCost).contains("¥").contains("in 1200/out 340");
        assertThat(withCost)
                .contains("modelCalls=2")
                .contains("tokensIn=2400")
                .contains("tokensOut=680");

        String withoutCost = query.render(TRACE_ID, false);
        assertThat(withoutCost).as("--cost 关闭时不显示 ¥ 列").doesNotContain("¥");
    }

    @Test
    @DisplayName("租户维度出现在渲染结果里")
    void renderShowsTenant() {
        append(
                "aaaaaaaaaaaaaaaaaaaaaaaa",
                null,
                "chat deepseek-chat",
                100L,
                0.01d,
                100L,
                50L,
                Map.of("swy.tenant.id", "vip"));
        assertThat(query.render(TRACE_ID, true)).contains("tenant=vip");
    }

    @Test
    @DisplayName("ERROR span 在树上标注 ⚠")
    void renderMarksErrorSpans() {
        Instant start = Instant.parse("2026-10-09T12:00:00Z");
        buffer.append(
                new SpanRingBuffer.SpanData(
                        TRACE_ID,
                        "aaaaaaaaaaaaaaaaaaaaaaaa",
                        null,
                        "chat deepseek-chat",
                        "chat",
                        "deepseek-chat",
                        start,
                        start.plusMillis(50L),
                        Map.of(),
                        "ERROR",
                        "上游 429"));
        String rendered = query.render(TRACE_ID, false);
        assertThat(rendered).contains("⚠").contains("上游 429");
    }

    @Test
    @DisplayName("空链路给出可操作的提示而不是空白")
    void emptyTraceGivesActionableHint() {
        String rendered = query.render(TRACE_ID, true);
        assertThat(rendered).contains("未找到").contains("llm.glasshouse.enabled");
        assertThat(query.tree(TRACE_ID)).isEmpty();
        assertThat(query.spansOf(TRACE_ID)).isEmpty();
        assertThat(query.slowestSpan(TRACE_ID)).isEmpty();
        assertThat(query.totalCost(TRACE_ID)).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("只返回指定 traceId 的 span")
    void isolatesTraceIds() {
        appendAgentChain();
        Instant start = Instant.parse("2026-10-09T12:00:00Z");
        buffer.append(
                new SpanRingBuffer.SpanData(
                        "00000000000000000000000000000001",
                        "ffffffffffffffffffffffff",
                        null,
                        "chat gpt-4o",
                        "chat",
                        "gpt-4o",
                        start,
                        start.plusMillis(10L),
                        Map.of(),
                        "OK",
                        null));

        assertThat(query.spansOf(TRACE_ID)).hasSize(5);
        assertThat(query.spansOf("00000000000000000000000000000001")).hasSize(1);
        assertThat(query.spansOf(null)).isEmpty();
        assertThat(query.spansOf("  ")).isEmpty();
        assertThat(buffer.byTrace()).hasSize(2);
    }

    @Test
    @DisplayName("父 span 不在缓冲里时，该 span 仍作为根返回（环形覆盖场景）")
    void orphanSpanBecomesRoot() {
        Instant start = Instant.parse("2026-10-09T12:00:00Z");
        buffer.append(
                new SpanRingBuffer.SpanData(
                        TRACE_ID,
                        "bbbbbbbbbbbbbbbbbbbbbbbb",
                        "aaaaaaaaaaaaaaaaaaaaaaaa",
                        "chat deepseek-chat",
                        "chat",
                        "deepseek-chat",
                        start,
                        start.plusMillis(10L),
                        Map.of(),
                        "OK",
                        null));

        List<SpanTreeQuery.SpanNode> roots = query.tree(TRACE_ID);
        assertThat(roots).as("父 span 被环形缓冲覆盖掉时，子 span 必须仍然可见").hasSize(1);
        assertThat(roots.get(0).data().name()).isEqualTo("chat deepseek-chat");
    }

    @Test
    @DisplayName("多根并存时都返回")
    void multipleRoots() {
        Instant start = Instant.parse("2026-10-09T12:00:00Z");
        for (String spanId : List.of("aaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbbbbbbbbbb")) {
            buffer.append(
                    new SpanRingBuffer.SpanData(
                            TRACE_ID,
                            spanId,
                            null,
                            "chat m",
                            "chat",
                            "m",
                            start,
                            start.plusMillis(5L),
                            Map.of(),
                            "OK",
                            null));
        }
        assertThat(query.tree(TRACE_ID)).hasSize(2);
    }

    @Test
    @DisplayName("★ 环形缓冲写满后丢弃最旧的，并记录丢弃数")
    void ringBufferEvictsOldest() {
        SpanRingBuffer small = new SpanRingBuffer(3);
        Instant start = Instant.parse("2026-10-09T12:00:00Z");
        for (int i = 0; i < 5; i++) {
            small.append(
                    new SpanRingBuffer.SpanData(
                            TRACE_ID,
                            String.format("%016x", i),
                            null,
                            "chat m" + i,
                            "chat",
                            "m" + i,
                            start,
                            start.plusMillis(i),
                            Map.of(),
                            "OK",
                            null));
        }
        assertThat(small.size()).as("容量上限").isEqualTo(3);
        assertThat(small.totalWritten()).isEqualTo(5L);
        assertThat(small.droppedCount()).as("丢弃数必须可见，否则「数据不全」无人知晓").isEqualTo(2L);
        assertThat(small.all())
                .as("保留的是最新的 3 条")
                .extracting(SpanRingBuffer.SpanData::name)
                .containsExactly("chat m2", "chat m3", "chat m4");
        assertThat(small.capacity()).isEqualTo(3);

        small.clear();
        assertThat(small.size()).isZero();
        assertThat(small.droppedCount()).isZero();
        assertThat(small.all()).isEmpty();
    }

    @Test
    @DisplayName("SpanData 访问器与防御性语义")
    void spanDataAccessors() {
        Instant start = Instant.parse("2026-10-09T12:00:00Z");
        SpanRingBuffer.SpanData child =
                new SpanRingBuffer.SpanData(
                        TRACE_ID,
                        "bbbbbbbbbbbbbbbbbbbbbbbb",
                        "aaaaaaaaaaaaaaaaaaaaaaaa",
                        "chat m",
                        "chat",
                        "m",
                        start,
                        start.plusMillis(120L),
                        Map.of(
                                "swy.cost.amount",
                                0.01d,
                                "gen_ai.usage.input_tokens",
                                100L,
                                "gen_ai.usage.output_tokens",
                                50L),
                        "OK",
                        null);
        assertThat(child.durationMillis()).isEqualTo(120L);
        assertThat(child.costAmount()).isEqualTo(0.01d);
        assertThat(child.inputTokens()).isEqualTo(100L);
        assertThat(child.outputTokens()).isEqualTo(50L);
        assertThat(child.root()).isFalse();
        assertThat(child.status()).isEqualTo("OK");

        SpanRingBuffer.SpanData root =
                new SpanRingBuffer.SpanData(
                        TRACE_ID,
                        "aaaaaaaaaaaaaaaaaaaaaaaa",
                        null,
                        "invoke_agent p",
                        "invoke_agent",
                        "p",
                        start,
                        start,
                        Map.of(),
                        null,
                        null);
        assertThat(root.root()).isTrue();
        assertThat(root.durationMillis()).isZero();
        assertThat(root.costAmount()).isNull();
        assertThat(root.inputTokens()).isNull();
        assertThat(root.status()).as("status 为 null 时归一化为 UNSET").isEqualTo("UNSET");
        assertThat(root.attributes()).isEmpty();
    }

    @Test
    @DisplayName("tokenSummary 汇总")
    void tokenSummary() {
        appendAgentChain();
        SpanTreeQuery.TokenSummary summary = query.tokenSummary(TRACE_ID);
        assertThat(summary.inputTokens()).isEqualTo(2400L);
        assertThat(summary.outputTokens()).isEqualTo(680L);
        assertThat(summary.modelCalls()).as("只有带 token 的 span 才算模型调用").isEqualTo(2L);
        assertThat(summary.total()).isEqualTo(3080L);
        assertThat(query.tokenSummary("nope").total()).isZero();
    }

    @Test
    @DisplayName("构造参数校验：buffer 不可为 null")
    void constructorValidation() {
        org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class, () -> new SpanTreeQuery(null));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> new SpanRingBuffer(0));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> new SpanRingBuffer(-1));
        SpanRingBuffer b = new SpanRingBuffer(1);
        b.append(null);
        assertThat(b.size()).isZero();
        b.appendAll(null);
        b.appendAll(List.of());
        assertThat(b.size()).isZero();
    }

    @Test
    @DisplayName("byStartTime 排序：null 时间排最后")
    void byStartTimeOrdering() {
        Instant start = Instant.parse("2026-10-09T12:00:00Z");
        SpanRingBuffer.SpanData later =
                new SpanRingBuffer.SpanData(
                        TRACE_ID,
                        "bbbbbbbbbbbbbbbbbbbbbbbb",
                        null,
                        "chat b",
                        "chat",
                        "b",
                        start.plusSeconds(10),
                        start.plusSeconds(10),
                        Map.of(),
                        "OK",
                        null);
        SpanRingBuffer.SpanData earlier =
                new SpanRingBuffer.SpanData(
                        TRACE_ID,
                        "aaaaaaaaaaaaaaaaaaaaaaaa",
                        null,
                        "chat a",
                        "chat",
                        "a",
                        start,
                        start,
                        Map.of(),
                        "OK",
                        null);
        SpanRingBuffer.SpanData noTime =
                new SpanRingBuffer.SpanData(
                        TRACE_ID,
                        "cccccccccccccccccccccccc",
                        null,
                        "chat c",
                        "chat",
                        "c",
                        null,
                        null,
                        Map.of(),
                        "OK",
                        null);

        List<SpanRingBuffer.SpanData> sorted =
                new java.util.ArrayList<>(List.of(later, noTime, earlier));
        sorted.sort(SpanRingBuffer.SpanData.byStartTime());
        assertThat(sorted)
                .extracting(SpanRingBuffer.SpanData::name)
                .containsExactly("chat a", "chat b", "chat c");
    }
}
