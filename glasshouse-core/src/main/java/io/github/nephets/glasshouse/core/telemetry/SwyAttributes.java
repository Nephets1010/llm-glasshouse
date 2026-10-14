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

import java.util.List;

import io.opentelemetry.api.common.AttributeKey;

/**
 * 三仓自有 span 属性定义（契约 {@code SPN-001}，★ 本仓是唯一出处，①②必须遵循）。
 *
 * <p><b>前缀为什么是 {@code swy.} 而不是 {@code glasshouse.}</b>：三个仓库同族 （switchyard / agentswitchyard /
 * glasshouse），但本仓不叫 switchyard。如果各仓用自己的前缀， 「一个 trace 里的 span
 * 属性口径完全一致」这个卖点就不成立。统一用家族前缀，跨仓看板才能用同一套查询。
 *
 * <p><b>为什么禁止裸自定义属性名</b>：OTel 语义约定仍在演进，裸名字（如 {@code tenant_id}）迟早与官方约定撞名，
 * 到时后端解析行为不可预期。所有自有属性一律带点分前缀，见 {@link #PREFIX}。
 *
 * <p><b>为什么是静态常量而不是动态构造</b>：属性名散落在多个模块里各写一遍字符串， 是「属性口径漂移」最常见的成因。全部集中在这里，改名只改一处，契约测试会红 CI 提醒三仓同步。
 */
public final class SwyAttributes {

    /** 三仓自有属性的统一前缀，末尾必须带点。 */
    public static final String PREFIX = "swy.";

    // ── 五维归因维度（①②③）───────────────────────────────────────────────
    /** 租户标识。 */
    public static final AttributeKey<String> TENANT_ID = string("swy.tenant.id");

    /** 业务线。 */
    public static final AttributeKey<String> BIZ_LINE = string("swy.biz.line");

    /** 业务功能。 */
    public static final AttributeKey<String> FEATURE = string("swy.feature");

    /** Prompt 模板版本。 */
    public static final AttributeKey<String> PROMPT_VERSION = string("swy.prompt.version");

    // ── 编排维度（②③）─────────────────────────────────────────────────────
    /** Agent 标识。 */
    public static final AttributeKey<String> AGENT_ID = string("swy.agent.id");

    /** Agent 版本。 */
    public static final AttributeKey<String> AGENT_VERSION = string("swy.agent.version");

    /** 流程实例 ID。 */
    public static final AttributeKey<String> FLOW_INSTANCE_ID = string("swy.flow.instance.id");

    /** 步骤 ID。 */
    public static final AttributeKey<String> STEP_ID = string("swy.step.id");

    /** 步骤序号，从 0 开始。 */
    public static final AttributeKey<Long> STEP_SEQ = AttributeKey.longKey("swy.step.seq");

    /** 步骤类型：{@code LLM / TOOL / RULE / HUMAN / COMPENSATE}。 */
    public static final AttributeKey<String> STEP_KIND = string("swy.step.kind");

    // ── 计量与成本（①③）───────────────────────────────────────────────────
    /** 计量来源：{@code VENDOR_REPORTED / ESTIMATED / FALLBACK}。 */
    public static final AttributeKey<String> USAGE_SOURCE = string("swy.usage.source");

    /** 流式下是否真收到 usage 块。 */
    public static final AttributeKey<Boolean> USAGE_STREAM_REPORTED =
            AttributeKey.booleanKey("swy.usage.stream_reported");

    /** 推理 token 数。 */
    public static final AttributeKey<Long> USAGE_REASONING_TOKENS =
            AttributeKey.longKey("swy.usage.reasoning_tokens");

    /** 缓存命中的输入 token 数。 */
    public static final AttributeKey<Long> USAGE_CACHED_INPUT_TOKENS =
            AttributeKey.longKey("swy.usage.cached_input_tokens");

    /** 成本金额，单位 CNY。 */
    public static final AttributeKey<Double> COST_AMOUNT =
            AttributeKey.doubleKey("swy.cost.amount");

    /** 成本币种，默认 CNY。 */
    public static final AttributeKey<String> COST_CURRENCY = string("swy.cost.currency");

    /** 价格来源：{@code OFFICIAL / ESTIMATED / CUSTOM}。 */
    public static final AttributeKey<String> PRICE_SOURCE = string("swy.price.source");

    // ── 审批（②）─────────────────────────────────────────────────────────
    /** 审批状态：{@code PENDING / APPROVED / REJECTED / TIMEOUT}。 */
    public static final AttributeKey<String> APPROVAL_STATE = string("swy.approval.state");

    // ── 留痕（①②③）───────────────────────────────────────────────────────
    /** payload 摘要，形如 {@code sha256:<64 位 hex>}。 */
    public static final AttributeKey<String> PAYLOAD_DIGEST = string("swy.payload.digest");

    /** 完整 payload，默认不落，需显式开启且已脱敏。 */
    public static final AttributeKey<String> PAYLOAD_FULL = string("swy.payload.full");

    private static final List<AttributeKey<?>> ALL =
            List.of(
                    TENANT_ID,
                    BIZ_LINE,
                    FEATURE,
                    PROMPT_VERSION,
                    AGENT_ID,
                    AGENT_VERSION,
                    FLOW_INSTANCE_ID,
                    STEP_ID,
                    STEP_SEQ,
                    STEP_KIND,
                    USAGE_SOURCE,
                    USAGE_STREAM_REPORTED,
                    USAGE_REASONING_TOKENS,
                    USAGE_CACHED_INPUT_TOKENS,
                    COST_AMOUNT,
                    COST_CURRENCY,
                    PRICE_SOURCE,
                    APPROVAL_STATE,
                    PAYLOAD_DIGEST,
                    PAYLOAD_FULL);

    private SwyAttributes() {
        throw new AssertionError("常量类不允许实例化");
    }

    /**
     * 构造字符串属性键并校验前缀。
     *
     * @param name 完整属性名，必须以 {@code swy.} 开头
     * @return 属性键
     * @throws IllegalArgumentException 属性名不以 {@code swy.} 开头
     */
    public static AttributeKey<String> string(String name) {
        requirePrefixed(name);
        return AttributeKey.stringKey(name);
    }

    /**
     * 返回全部自有属性的名称列表。
     *
     * <p>{@code ContractSchemaTest} 用它与 {@code contract/contract-v1.json} 的 {@code swyAttributes}
     * 段做双向比对：多一个少一个都会让 CI 失败。
     *
     * @return 不可变属性名列表
     */
    public static List<String> allNames() {
        return ALL.stream().map(AttributeKey::getKey).sorted().toList();
    }

    /**
     * 返回全部自有属性键。
     *
     * @return 不可变列表
     */
    public static List<AttributeKey<?>> all() {
        return ALL;
    }

    /**
     * 返回<b>本仓会写</b>的 {@code swy.*} 属性名集合。
     *
     * <p><b>为什么必须区分「认识」与「会写」</b>：契约 SPN-001 的 {@code customAttributes} 逐条声明了「哪几仓可以写」。{@code
     * swy.flow.instance.id} / {@code swy.step.*} / {@code swy.approval.state} 只由 agentswitchyard
     * 写，本仓<b>不写</b>， 但必须<b>认识</b>——否则收到上游传来的这些维度时会被当成未知属性丢掉， 归因报表就少一维。
     *
     * <p>{@link #allNames()} 返回「认识的全部」；本方法返回「本仓会写的」。 {@code ContractSchemaTest}
     * 分别对这两个集合断言，任何一方改了归属而没改实现都会红。
     *
     * @return 本仓会写的属性名（升序）
     */
    public static List<String> writableNames() {
        return List.of(
                AGENT_ID.getKey(),
                AGENT_VERSION.getKey(),
                BIZ_LINE.getKey(),
                COST_AMOUNT.getKey(),
                COST_CURRENCY.getKey(),
                FEATURE.getKey(),
                PAYLOAD_DIGEST.getKey(),
                PAYLOAD_FULL.getKey(),
                PRICE_SOURCE.getKey(),
                PROMPT_VERSION.getKey(),
                TENANT_ID.getKey(),
                USAGE_CACHED_INPUT_TOKENS.getKey(),
                USAGE_REASONING_TOKENS.getKey(),
                USAGE_SOURCE.getKey(),
                USAGE_STREAM_REPORTED.getKey());
    }

    /**
     * 判断属性名是否属于本项目的自有属性。
     *
     * @param name 属性名，可为 null
     * @return 以 {@code swy.} 开头返回 {@code true}
     */
    public static boolean isSwyAttribute(String name) {
        return name != null && name.startsWith(PREFIX);
    }

    /**
     * 校验属性名带家族前缀。
     *
     * @param name 属性名
     * @throws IllegalArgumentException 缺少前缀时抛出
     */
    private static void requirePrefixed(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("属性名不能为空");
        }
        if (!name.startsWith(PREFIX)) {
            throw new IllegalArgumentException("自有属性必须以 " + PREFIX + " 开头（禁止裸属性名），实际为: " + name);
        }
        if (name.length() == PREFIX.length()) {
            throw new IllegalArgumentException("属性名不允许恰好等于前缀 " + PREFIX + "（禁止裸 swy）");
        }
    }
}
