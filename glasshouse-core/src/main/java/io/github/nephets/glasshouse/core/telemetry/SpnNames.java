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

package io.github.nephets.glasshouse.core.telemetry;

/**
 * Span 命名规范（契约编号 {@code SPN-001}，★ 本仓是唯一出处，①②必须遵循）。
 *
 * <p>命名模板固定为 <b>{@code {operation} {target}}</b>，单个空格分隔，无引号。
 *
 * <p>为什么值得为它单独立一个契约：链路追踪的痛点不是「看不到 span」，而是「同一个动作在不同仓里 叫不同的名字」，导致跨仓检索与看板都写不出来。把模板锁死，三仓的 span
 * 树就能用同一套查询语句读。
 *
 * <p>{@code operation} 取值与 OTel GenAI 语义约定 1.41.0 的 {@code gen_ai.operation.name} 对齐 （{@code chat}
 * / {@code invoke_agent} / {@code execute_tool} / {@code retrieval}）， 审批是本项目自有语义，取 {@code
 * approval}。
 */
public final class SpnNames {

    /** 模型调用。 */
    public static final String OP_CHAT = "chat";

    /** Agent 步骤。 */
    public static final String OP_INVOKE_AGENT = "invoke_agent";

    /** 工具调用。 */
    public static final String OP_EXECUTE_TOOL = "execute_tool";

    /** 检索。 */
    public static final String OP_RETRIEVE = "retrieval";

    /** 人工审批（等待期间）。 */
    public static final String OP_APPROVAL = "approval";

    /** 命名模板：operation 与 target 之间用一个空格。 */
    public static final String TEMPLATE = "%s %s";

    private SpnNames() {
        throw new AssertionError("常量类不允许实例化");
    }

    /**
     * 模型调用 span 名。
     *
     * @param model 模型标识，如 {@code deepseek-chat}
     * @return {@code chat deepseek-chat}
     */
    public static String chat(String model) {
        return name(OP_CHAT, model);
    }

    /**
     * Agent 步骤 span 名。
     *
     * @param agent Agent 标识，如 {@code planner}
     * @return {@code invoke_agent planner}
     */
    public static String invokeAgent(String agent) {
        return name(OP_INVOKE_AGENT, agent);
    }

    /**
     * 工具调用 span 名。
     *
     * @param tool 工具名，如 {@code kb_lookup}
     * @return {@code execute_tool kb_lookup}
     */
    public static String executeTool(String tool) {
        return name(OP_EXECUTE_TOOL, tool);
    }

    /**
     * 检索 span 名。
     *
     * @param retriever 检索器标识，如 {@code pgvector_kb}
     * @return {@code retrieval pgvector_kb}
     */
    public static String retrieve(String retriever) {
        return name(OP_RETRIEVE, retriever);
    }

    /**
     * 人工审批 span 名。
     *
     * @param approver 审批角色或人，如 {@code credit_officer}
     * @return {@code approval credit_officer}
     */
    public static String approval(String approver) {
        return name(OP_APPROVAL, approver);
    }

    /**
     * 通用命名。
     *
     * @param operation 操作名，不可为 null
     * @param target 目标标识；null 或空白时退化为只返回 operation
     * @return 拼好的 span 名
     */
    public static String name(String operation, String target) {
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("operation 不能为空");
        }
        if (target == null || target.isBlank()) {
            return operation;
        }
        return String.format(TEMPLATE, operation, target.trim());
    }

    /**
     * 判断给定的 operation 是否属于本契约定义的取值。
     *
     * @param operation 操作名，可为 null
     * @return 是受支持的 operation 返回 {@code true}
     */
    public static boolean isKnownOperation(String operation) {
        if (operation == null) {
            return false;
        }
        return switch (operation) {
            case OP_CHAT, OP_INVOKE_AGENT, OP_EXECUTE_TOOL, OP_RETRIEVE, OP_APPROVAL -> true;
            default -> false;
        };
    }

    /**
     * 返回本契约定义的全部 operation。
     *
     * @return 不可变列表
     */
    public static java.util.List<String> operations() {
        return java.util.List.of(
                OP_CHAT, OP_INVOKE_AGENT, OP_EXECUTE_TOOL, OP_RETRIEVE, OP_APPROVAL);
    }
}
