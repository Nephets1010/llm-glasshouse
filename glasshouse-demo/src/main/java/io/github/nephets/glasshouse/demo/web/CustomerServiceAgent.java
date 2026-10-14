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

package io.github.nephets.glasshouse.demo.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.nephets.glasshouse.starter.annotation.AgentStep;
import io.github.nephets.glasshouse.starter.annotation.Retrieval;
import io.github.nephets.glasshouse.starter.annotation.SpanDimensions;
import io.github.nephets.glasshouse.starter.annotation.ToolCall;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 演示用的三步客服 Agent：{@code planner → kb_lookup → writer}。
 *
 * <p><b>这就是 README 首屏那张 span 树的来源</b>。整个类里<b>没有任何一行埋点代码</b>—— 只有三个注解（{@code @AgentStep} /
 * {@code @Retrieval} / {@code @ToolCall}）， span 由 starter 的切面自动产生。
 *
 * <p>三个步骤刻意设计了不同的耗时与 token 数，让 span 树能同时展示 「慢在哪」（planner 1500ms）与「花了多少」（writer token 最多）。
 */
@Service
public class CustomerServiceAgent {

    private static final Logger log = LoggerFactory.getLogger(CustomerServiceAgent.class);

    private final ChatClient chatClient;

    /**
     * 构造 Agent。
     *
     * @param chatClient Spring AI 的 ChatClient，<b>已被 starter 装饰过</b>
     */
    public CustomerServiceAgent(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 第 1 步：规划。用 {@code @AgentStep} 标注。
     *
     * @param question 用户问题
     * @param promptVersion Prompt 版本（归因维度）
     * @return 规划结果
     */
    @AgentStep(agentId = "planner", stepId = "plan", stepSeq = 0, feature = "customer-service")
    public String plan(String question, String promptVersion) {
        log.info("[demo] planner 收到问题: {}", abbreviate(question));
        return call(
                question,
                """
        你是客服问题的规划助手。请把用户问题拆成不超过三步的处理计划，每步一行。
        """,
                promptVersion);
    }

    /**
     * 第 2 步：知识库检索。用 {@code @Retrieval} 标注。
     *
     * @param question 用户问题
     * @return 检索到的政策文本
     */
    @Retrieval(retriever = "kb_lookup", dataSourceId = "faq-zh-v1")
    public String lookupKnowledgeBase(String question) {
        log.info("[demo] kb_lookup 检索: {}", abbreviate(question));
        // 演示里「检索」是一个本地方法；真实项目里这里是向量库 / ES 的调用。
        // 耗时刻意设为 80ms，让它在 span 树上明显快于 planner。
        sleep(80L);
        return """
        售后服务政策摘要：
        · 七天无理由退货（签收起 7 个自然日）
        · 质量问题 7 天包退、15 天包换
        · 退款原路返回，1-7 个工作日到账
        """;
    }

    /**
     * 第 3 步：成文。用 {@code @ToolCall} 标注（模拟写回业务系统的工具调用）。
     *
     * @param question 用户问题
     * @param knowledge 检索到的知识
     * @param promptVersion Prompt 版本
     * @return 最终答复
     */
    @ToolCall(toolName = "ticket_create", stepId = "draft")
    public String write(String question, String knowledge, String promptVersion) {
        log.info("[demo] writer 成文, promptVersion={}", promptVersion);
        return call(
                question,
                """
        你是客服答复撰写助手。基于给定的政策摘要，用简体中文给出最终答复。
        要求：先给结论，再给依据，最后给下一步动作。不要编造政策。
        政策摘要：
        %s
        """
                        .formatted(knowledge),
                promptVersion);
    }

    /**
     * 跑完三步，返回完整轨迹与最终答复。
     *
     * @param question 用户问题
     * @param promptVersion Prompt 版本（归因维度）
     * @param tenantId 租户（归因维度）
     * @return 执行轨迹
     */
    public Map<String, Object> answer(String question, String promptVersion, String tenantId) {
        // 归因维度只需设置一次，内部三步与其中的模型调用会自动继承
        SpanDimensions.set(
                SpanDimensions.merge(
                        b ->
                                b.tenantId(tenantId)
                                        .feature("customer-service")
                                        .promptVersion(promptVersion)));
        try {
            long startedAt = System.nanoTime();
            String plan = plan(question, promptVersion);
            String knowledge = lookupKnowledgeBase(question);
            String answer = write(question, knowledge, promptVersion);
            long elapsed = (System.nanoTime() - startedAt) / 1_000_000L;

            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("plan", plan);
            trace.put("knowledge", knowledge);
            trace.put("answer", answer);
            trace.put("elapsedMs", elapsed);
            trace.put("tenantId", tenantId);
            trace.put("promptVersion", promptVersion);
            return trace;
        } finally {
            SpanDimensions.clear();
        }
    }

    /**
     * 发起一次模型调用。
     *
     * <p>这里能看到<b>业务代码的本来样子</b>：只用 {@code ChatClient}， 没有任何 span API 调用。
     *
     * @param question 用户问题
     * @param systemPrompt 系统提示
     * @param promptVersion Prompt 版本
     * @return 模型回答
     */
    private String call(String question, String systemPrompt, String promptVersion) {
        try {
            return chatClient.prompt().system(systemPrompt).user(question).call().content();
        } catch (RuntimeException e) {
            log.warn("[demo] 模型调用失败: {}", e.toString());
            return "【模型调用失败】" + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /**
     * 列出本 Agent 的步骤说明，供 README 与文档引用。
     *
     * @return 步骤说明
     */
    public List<Map<String, String>> steps() {
        List<Map<String, String>> steps = new ArrayList<>();
        steps.add(
                Map.of(
                        "step",
                        "1",
                        "annotation",
                        "@AgentStep(agentId=\"planner\")",
                        "spanName",
                        "invoke_agent planner"));
        steps.add(
                Map.of(
                        "step",
                        "2",
                        "annotation",
                        "@Retrieval(retriever=\"kb_lookup\")",
                        "spanName",
                        "retrieval kb_lookup"));
        steps.add(
                Map.of(
                        "step",
                        "3",
                        "annotation",
                        "@ToolCall(toolName=\"ticket_create\")",
                        "spanName",
                        "execute_tool ticket_create"));
        return steps;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 40 ? text : text.substring(0, 40) + "...";
    }
}
