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

package io.github.nephets.glasshouse.core.audit;

import java.math.BigDecimal;
import java.time.Instant;

import io.github.nephets.glasshouse.core.usage.Usage;

/**
 * 审计 / 轨迹事件（契约编号 {@code EVT-001}）。
 *
 * <p>①写审计、②写执行轨迹、③写 Prompt 留痕，<b>三仓共用同一份 schema</b>。这样「一次业务请求在三个仓里 发生了什么」可以用同一段查询串起来——这是本仓「三仓同源
 * traceId 贯通」卖点的数据基础。
 *
 * <p><b>两条硬约束</b>：
 *
 * <ol>
 *   <li>归因维度缺失为 {@code null}，<b>禁止填 {@code "unknown"} 占位</b>，否则归因统计被污染
 *   <li>{@code usage} 不可为 null；无 token 的事件填 {@link Usage#estimated()}
 * </ol>
 *
 * @param eventType 事件类型
 * @param traceId W3C traceId
 * @param spanId W3C spanId
 * @param occurredAt 发生时刻
 * @param tenantId 租户，可为 null
 * @param bizLine 业务线，可为 null
 * @param feature 业务功能，可为 null
 * @param promptVersion Prompt 模板版本，可为 null
 * @param agentId Agent 标识，可为 null
 * @param flowInstanceId 流程实例 ID，可为 null
 * @param stepId 步骤 ID，可为 null
 * @param stepSeq 步骤序号，从 0 开始，可为 null
 * @param usage 计量，不可为 null
 * @param costAmount 成本金额（CNY）
 * @param costCurrency 币种，固定 CNY
 * @param payloadDigest payload 摘要，形如 {@code sha256:<hex>}，默认必填
 * @param payloadFull 完整 payload，默认不落，仅在显式开启且已脱敏时填充，可为 null
 */
public record AuditEvent(
        EventType eventType,
        String traceId,
        String spanId,
        Instant occurredAt,
        String tenantId,
        String bizLine,
        String feature,
        String promptVersion,
        String agentId,
        String flowInstanceId,
        String stepId,
        Integer stepSeq,
        Usage usage,
        BigDecimal costAmount,
        String costCurrency,
        String payloadDigest,
        String payloadFull) {

    /** 契约要求的摘要前缀。 */
    public static final String DIGEST_PREFIX = "sha256:";

    /** 三仓统一的成本币种。 */
    public static final String CURRENCY_CNY = "CNY";

    /**
     * 紧凑构造器：必填字段校验与字段清理。
     *
     * <p>归因维度的空字符串统一归一化为 {@code null}——空串和 null 在语义上都是「没有这个维度」， 但如果让空串混进去，下钻统计会多出一行 {@code ""}
     * 的脏数据。
     *
     * @throws NullPointerException eventType / traceId / spanId / occurredAt / usage / costAmount 为
     *     null
     * @throws IllegalArgumentException payloadDigest 不以 {@code sha256:} 开头
     */
    public AuditEvent {
        if (eventType == null) {
            throw new NullPointerException("eventType 不能为 null");
        }
        if (traceId == null || traceId.isBlank()) {
            throw new NullPointerException("traceId 不能为空");
        }
        if (spanId == null || spanId.isBlank()) {
            throw new NullPointerException("spanId 不能为空");
        }
        if (occurredAt == null) {
            throw new NullPointerException("occurredAt 不能为 null");
        }
        if (usage == null) {
            throw new NullPointerException("usage 不能为 null（无 token 的事件请用 Usage.estimated()）");
        }
        if (costAmount == null) {
            throw new NullPointerException("costAmount 不能为 null");
        }
        if (payloadDigest == null || !payloadDigest.startsWith(DIGEST_PREFIX)) {
            throw new IllegalArgumentException(
                    "payloadDigest 必须以 " + DIGEST_PREFIX + " 开头，实际为: " + payloadDigest);
        }
        tenantId = blankToNull(tenantId);
        bizLine = blankToNull(bizLine);
        feature = blankToNull(feature);
        promptVersion = blankToNull(promptVersion);
        agentId = blankToNull(agentId);
        flowInstanceId = blankToNull(flowInstanceId);
        stepId = blankToNull(stepId);
        payloadFull = blankToNull(payloadFull);
        costCurrency = costCurrency == null || costCurrency.isBlank() ? CURRENCY_CNY : costCurrency;
    }

    /**
     * 构造一个不携带完整 payload 的事件（默认路径）。
     *
     * @param eventType 事件类型
     * @param traceId traceId
     * @param spanId spanId
     * @param occurredAt 发生时刻
     * @param usage 计量
     * @param costAmount 成本
     * @param payloadDigest payload 摘要
     * @return 事件实例
     */
    public static AuditEvent ofDigestOnly(
            EventType eventType,
            String traceId,
            String spanId,
            Instant occurredAt,
            Usage usage,
            BigDecimal costAmount,
            String payloadDigest) {
        return new AuditEvent(
                eventType,
                traceId,
                spanId,
                occurredAt,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                usage,
                costAmount,
                CURRENCY_CNY,
                payloadDigest,
                null);
    }

    /**
     * 返回一个填充了归因维度与成本的副本。
     *
     * <p>用 {@code with*} 而不是 builder：事件是不可变的值对象，复制语义比可变 builder 更不容易出错。
     *
     * @param tenantId 租户
     * @param bizLine 业务线
     * @param feature 业务功能
     * @param promptVersion Prompt 版本
     * @param costAmount 成本
     * @return 新的事件实例
     */
    public AuditEvent withAttribution(
            String tenantId,
            String bizLine,
            String feature,
            String promptVersion,
            BigDecimal costAmount) {
        return new AuditEvent(
                eventType,
                traceId,
                spanId,
                occurredAt,
                tenantId,
                bizLine,
                feature,
                promptVersion,
                agentId,
                flowInstanceId,
                stepId,
                stepSeq,
                usage,
                costAmount,
                costCurrency,
                payloadDigest,
                payloadFull);
    }

    /**
     * 返回一个填入了编排维度的副本。
     *
     * @param agentId Agent 标识
     * @param flowInstanceId 流程实例 ID
     * @param stepId 步骤 ID
     * @param stepSeq 步骤序号
     * @return 新的事件实例
     */
    public AuditEvent withStep(
            String agentId, String flowInstanceId, String stepId, Integer stepSeq) {
        return new AuditEvent(
                eventType,
                traceId,
                spanId,
                occurredAt,
                tenantId,
                bizLine,
                feature,
                promptVersion,
                agentId,
                flowInstanceId,
                stepId,
                stepSeq,
                usage,
                costAmount,
                costCurrency,
                payloadDigest,
                payloadFull);
    }

    /**
     * 判断本次事件是否携带完整 payload。
     *
     * @return 有完整 payload 返回 {@code true}
     */
    public boolean hasPayloadFull() {
        return payloadFull != null;
    }

    /**
     * 判断本次计量是否可信（供应商真实返回）。
     *
     * @return 可信返回 {@code true}
     */
    public boolean usageTrustworthy() {
        return usage.trustworthy();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
