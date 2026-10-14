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

package io.github.nephets.glasshouse.core.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 价格表条目（契约编号 {@code PRC-001}）。
 *
 * <p>字段顺序、单位、语义与 {@code llm-switchyard} 逐字符一致。
 *
 * <p><b>精度铁律</b>：金额一律 {@link BigDecimal}，禁止 {@code double}（架构 §8.4）。 内部 scale=8，展示层 scale=4。
 *
 * <p><b>缺省价格语义</b>：{@code reasoningPricePerUnit} 为 null 表示「按 {@code outputPricePerUnit} 计」， {@code
 * cachedInputPricePerUnit} 为 null 表示「按 {@code inputPricePerUnit} 计」。见 {@link CostCalculator}。
 *
 * @param providerId 供应商标识，小写（见契约 {@code ProviderId}）
 * @param modelId 供应商侧模型名
 * @param currency ISO 4217，三仓统一 {@code CNY}
 * @param unit 计价单位
 * @param inputPricePerUnit 每 1000 输入 token 价格
 * @param outputPricePerUnit 每 1000 输出 token 价格
 * @param reasoningPricePerUnit 每 1000 推理 token 价格；null 表示按输出价计
 * @param cachedInputPricePerUnit 每 1000 缓存命中输入 token 价格；null 表示按输入价计
 * @param effectiveFrom 生效日
 * @param source 价格来源
 */
public record PriceEntry(
        String providerId,
        String modelId,
        String currency,
        PriceUnit unit,
        BigDecimal inputPricePerUnit,
        BigDecimal outputPricePerUnit,
        BigDecimal reasoningPricePerUnit,
        BigDecimal cachedInputPricePerUnit,
        LocalDate effectiveFrom,
        PriceSource source) {

    /** 三仓统一的内部核算币种。 */
    public static final String CURRENCY_CNY = "CNY";

    /**
     * 紧凑构造器：字段校验与归一化。
     *
     * @throws IllegalArgumentException 标识为空、价格为负、币种不是 CNY、日期为 null
     * @throws NullPointerException unit / source / effectiveFrom / currency 为 null
     */
    public PriceEntry {
        // providerId / modelId 都要 trim：YAML 里手写价格表时前后空格极常见，
        // 不 trim 会让「明明配了价格却查不到」→ 成本静默记 0。
        // providerId 另外小写化，因为契约规定 providerId 全小写（openai/deepseek/...）。
        providerId = trimTo(providerId);
        modelId = trimTo(modelId);
        requireText(providerId, "providerId");
        requireText(modelId, "modelId");
        providerId = providerId.toLowerCase(java.util.Locale.ROOT);
        if (currency == null) {
            throw new NullPointerException("currency 不能为 null");
        }
        if (!CURRENCY_CNY.equals(currency)) {
            throw new IllegalArgumentException("三仓内部核算基准币种统一为 CNY，实际为: " + currency);
        }
        if (unit == null) {
            throw new NullPointerException("unit 不能为 null");
        }
        if (effectiveFrom == null) {
            throw new NullPointerException("effectiveFrom 不能为 null");
        }
        if (source == null) {
            throw new NullPointerException("source 不能为 null");
        }
        inputPricePerUnit = nonNegative(inputPricePerUnit, "inputPricePerUnit");
        outputPricePerUnit = nonNegative(outputPricePerUnit, "outputPricePerUnit");
        reasoningPricePerUnit = nonNegative(reasoningPricePerUnit, "reasoningPricePerUnit");
        cachedInputPricePerUnit = nonNegative(cachedInputPricePerUnit, "cachedInputPricePerUnit");
    }

    /**
     * 构造「无独立缓存价与推理价」的条目，两者分别按输入价与输出价计。
     *
     * @param providerId 供应商标识
     * @param modelId 模型标识
     * @param inputPrice 每 1000 输入 token 价格
     * @param outputPrice 每 1000 输出 token 价格
     * @param effectiveFrom 生效日
     * @param source 价格来源
     * @return 价格条目
     */
    public static PriceEntry of(
            String providerId,
            String modelId,
            BigDecimal inputPrice,
            BigDecimal outputPrice,
            LocalDate effectiveFrom,
            PriceSource source) {
        return new PriceEntry(
                providerId,
                modelId,
                CURRENCY_CNY,
                PriceUnit.PER_1K_TOKENS,
                inputPrice,
                outputPrice,
                null,
                null,
                effectiveFrom,
                source);
    }

    /**
     * 返回价格表查找键。
     *
     * @return {@code providerId/modelId} 形式的键
     */
    public String lookupKey() {
        return lookupKey(providerId, modelId);
    }

    /**
     * 构造价格表查找键。
     *
     * @param providerId 供应商标识
     * @param modelId 模型标识
     * @return {@code providerId/modelId} 形式的键
     */
    public static String lookupKey(String providerId, String modelId) {
        return (providerId == null ? "" : providerId) + "/" + (modelId == null ? "" : modelId);
    }

    /**
     * 取得推理 token 单价，未配置时回退到输出价。
     *
     * @return 非负单价
     */
    public BigDecimal effectiveReasoningPrice() {
        return reasoningPricePerUnit == null ? outputPricePerUnit : reasoningPricePerUnit;
    }

    /**
     * 取得缓存命中输入 token 单价，未配置时回退到输入价。
     *
     * @return 非负单价
     */
    public BigDecimal effectiveCachedInputPrice() {
        return cachedInputPricePerUnit == null ? inputPricePerUnit : cachedInputPricePerUnit;
    }

    /**
     * 去掉首尾空白，null 保持 null。
     *
     * @param value 原始值
     * @return trim 后的值
     */
    private static String trimTo(String value) {
        return value == null ? null : value.trim();
    }

    /**
     * 构造时校验非空文本字段。
     *
     * @param value 待校验值
     * @param field 字段名，用于错误信息
     */
    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }

    /**
     * 构造时校验金额非负，null 允许（表示缺省）。
     *
     * @param value 待校验值
     * @param field 字段名，用于错误信息
     * @return 原值
     */
    private static BigDecimal nonNegative(BigDecimal value, String field) {
        if (value == null) {
            return null;
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException(field + " 不得为负，实际为: " + value);
        }
        return value;
    }
}
