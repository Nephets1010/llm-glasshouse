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

package io.github.nephets.glasshouse.core.attribution;

/**
 * 归因聚合键：{@code (providerId, modelId, tenantId, feature, promptVersion, agentStep)}。
 *
 * <p>六个字段构成 PRD P0-3 要求的五个业务维度（模型维度拆成 provider + model 两级， 因为「通义的 qwen-max」和「智谱的
 * GLM-4」都叫「qwen-max」的可能性是真实存在的）。
 *
 * <p><b>缺失值统一归一化为 {@link #MISSING}</b>，而不是 {@code null}： record 用作 {@code HashMap} 的 key 时 {@code
 * null} 是允许的，但会让「用 null 探测维度是否存在」 这类代码变得脆弱。归一化后所有 key 的字段都是非空字符串，下钻统计不会出现 {@code null} 行列。
 *
 * <p><b>但要强调</b>：契约 EVT-001 要求 {@code AuditEvent} 里缺失维度为 {@code null} 而不是 {@code "unknown"}。本类的
 * {@link #MISSING} 只存在于<b>聚合键</b>内部（一个进程内的临时键）， 不会回写到事件或对外输出，{@link AttributionRow} 渲染时会把 {@link
 * #MISSING} 还原为「未标注」。
 */
public record AttributionKey(
        String providerId,
        String modelId,
        String tenantId,
        String bizLine,
        String feature,
        String promptVersion,
        String agentStep) {

    /**
     * 聚合键内部的缺失占位符。
     *
     * <p><b>严格约束</b>：本值只允许出现在<b>进程内的 Map key</b> 里， <b>绝不允许出现在任何对外输出中</b>（契约 EVT-001：「归因维度缺失为 null，
     * 禁止填 {@code "unknown"} 占位，否则归因统计被污染」）。
     *
     * <p>因此所有对外渲染路径都必须经过 {@link #display(AttributionDimension)} 或 {@code
     * AttributionRow.valueOf}，它们会把本占位符还原为 {@link #DISPLAY_MISSING}。 {@code AttributionEngineTest}
     * 对此有断言。
     */
    public static final String MISSING = "\u0000missing";

    /** 对展示时缺失维度的显示文本。 */
    public static final String DISPLAY_MISSING = "未标注";

    /**
     * 空构造：全部维度缺失。
     *
     * @return 全缺失的聚合键
     */
    public static AttributionKey empty() {
        return new AttributionKey(MISSING, MISSING, MISSING, MISSING, MISSING, MISSING, MISSING);
    }

    /**
     * 紧凑构造器：所有字段归一化为非空字符串。
     *
     * @param providerId 供应商标识，可为 null
     * @param modelId 模型标识，可为 null
     * @param tenantId 租户，可为 null
     * @param bizLine 业务线，可为 null
     * @param feature 业务功能，可为 null
     * @param promptVersion Prompt 版本，可为 null
     * @param agentStep Agent 步骤，可为 null
     */
    public AttributionKey {
        providerId = normalize(providerId);
        modelId = normalize(modelId);
        tenantId = normalize(tenantId);
        bizLine = normalize(bizLine);
        feature = normalize(feature);
        promptVersion = normalize(promptVersion);
        agentStep = normalize(agentStep);
    }

    /**
     * 从审计事件 + 模型信息构造聚合键。
     *
     * <p><b>为什么模型信息要单独传</b>：契约 EVT-001 刻意把 {@code AuditEvent} 限定为「事件与归因维度」， 不含 {@code
     * providerId/modelId}——那是 span 属性（{@code gen_ai.request.model}）的职责。 聚合需要两者，因此由调用方（starter 的
     * observation handler）把两者一起传进来， 而不是往契约结构里塞字段破坏三仓一致性。
     *
     * @param event 审计事件，不可为 null
     * @param providerId 供应商标识，可为 null
     * @param modelId 模型标识，可为 null
     * @return 聚合键
     */
    public static AttributionKey from(
            io.github.nephets.glasshouse.core.audit.AuditEvent event,
            String providerId,
            String modelId) {
        return new AttributionKey(
                providerId,
                modelId,
                event.tenantId(),
                event.bizLine(),
                event.feature(),
                event.promptVersion(),
                stepLabel(event.agentId(), event.stepId()));
    }

    /**
     * 把 Agent 标识与步骤 ID 组合成步骤标签。
     *
     * @param agentId Agent 标识，可为 null
     * @param stepId 步骤 ID，可为 null
     * @return 组合标签；两者都缺失返回 {@link #MISSING}
     */
    public static String stepLabel(String agentId, String stepId) {
        boolean hasAgent = agentId != null && !agentId.isBlank();
        boolean hasStep = stepId != null && !stepId.isBlank();
        if (hasAgent && hasStep) {
            return agentId.trim() + "/" + stepId.trim();
        }
        if (hasAgent) {
            return agentId.trim();
        }
        if (hasStep) {
            return stepId.trim();
        }
        return MISSING;
    }

    /**
     * 按维度取值。
     *
     * @param dimension 归因维度，不可为 null
     * @return 该维度的值；字段缺失时返回 {@link #MISSING}
     */
    public String valueOf(AttributionDimension dimension) {
        return switch (dimension) {
            case PROVIDER -> providerId;
            case MODEL -> modelId;
            case TENANT -> tenantId;
            case BIZ_LINE -> bizLine;
            case FEATURE -> feature;
            case PROMPT_VERSION -> promptVersion;
            case AGENT_STEP -> agentStep;
        };
    }

    /**
     * 返回一个替换了指定维度值的副本。
     *
     * @param dimension 目标维度
     * @param value 新值，可为 null（归一化为 {@link #MISSING}）
     * @return 新的聚合键
     */
    public AttributionKey with(AttributionDimension dimension, String value) {
        return switch (dimension) {
            case PROVIDER ->
                    new AttributionKey(
                            value, modelId, tenantId, bizLine, feature, promptVersion, agentStep);
            case MODEL ->
                    new AttributionKey(
                            providerId,
                            value,
                            tenantId,
                            bizLine,
                            feature,
                            promptVersion,
                            agentStep);
            case TENANT ->
                    new AttributionKey(
                            providerId, modelId, value, bizLine, feature, promptVersion, agentStep);
            case BIZ_LINE ->
                    new AttributionKey(
                            providerId,
                            modelId,
                            tenantId,
                            value,
                            feature,
                            promptVersion,
                            agentStep);
            case FEATURE ->
                    new AttributionKey(
                            providerId,
                            modelId,
                            tenantId,
                            bizLine,
                            value,
                            promptVersion,
                            agentStep);
            case PROMPT_VERSION ->
                    new AttributionKey(
                            providerId, modelId, tenantId, bizLine, feature, value, agentStep);
            case AGENT_STEP ->
                    new AttributionKey(
                            providerId, modelId, tenantId, bizLine, feature, promptVersion, value);
        };
    }

    /**
     * 判断某维度是否有真实取值（不是缺失占位）。
     *
     * @param dimension 目标维度
     * @return 有真实取值返回 {@code true}
     */
    public boolean has(AttributionDimension dimension) {
        return !MISSING.equals(valueOf(dimension));
    }

    /**
     * 返回<b>对外可展示</b>的维度值。
     *
     * <p>这是唯一允许用于渲染的取值入口：内部占位符 {@link #MISSING} 在此被还原为 {@link #DISPLAY_MISSING}。任何直接用 {@link
     * #valueOf} 输出到报表、日志或 CLI 的代码都是 bug。
     *
     * @param dimension 目标维度，不可为 null
     * @return 维度值；未标注时返回 {@link #DISPLAY_MISSING}
     */
    public String display(AttributionDimension dimension) {
        String value = valueOf(dimension);
        return MISSING.equals(value) ? DISPLAY_MISSING : value;
    }

    /**
     * 把可空字段归一化为非空字符串。
     *
     * <p><b>已归一化的值必须原样返回</b>（测试捕获的真实 bug）：{@link String#trim()} 会移除 所有 {@code <= U+0020} 的字符，而
     * {@link #MISSING} 以 NUL（{@code U+0000}）开头。 若不先短路，{@code stepLabel(null, null)} 产出的 MISSING
     * 会在构造器里被 {@code trim()} 削成 {@code "missing"}，导致后续 {@code MISSING.equals(value)} 判定失败，
     * 内部占位符就泄漏到了对外输出里。
     *
     * @param value 原始值，可为 null
     * @return 归一化后的非空值
     */
    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return MISSING;
        }
        if (MISSING.equals(value)) {
            return MISSING;
        }
        return value.trim();
    }
}
