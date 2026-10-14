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
import java.math.RoundingMode;

import io.github.nephets.glasshouse.core.usage.Usage;

/**
 * 成本计算器（契约编号 {@code PRC-001}）。
 *
 * <p><b>公式三仓逐字一致</b>（架构 §2.3）：
 *
 * <pre>{@code
 * BigDecimal billableInput  = usage.inputTokens() - usage.cachedInputTokens();
 * BigDecimal billableCached = usage.cachedInputTokens();
 * BigDecimal cost =
 *       billableInput .divide(THOUSAND).multiply(effectiveInputPrice)
 *     .add(billableCached.divide(THOUSAND).multiply(effectiveCachedPrice))
 *     .add(usage.outputTokens().divide(THOUSAND).multiply(outputPrice))
 *     .add(usage.reasoningTokens().divide(THOUSAND).multiply(effectiveReasoningPrice));
 * return cost.setScale(8, RoundingMode.HALF_UP);
 * }</pre>
 *
 * <p>三个容易被写错的地方，本实现显式处理：
 *
 * <ol>
 *   <li><b>缓存命中不能重复计费</b>：{@code inputTokens} 已含 {@code cachedInputTokens}，
 *       所以先扣掉再按输入价算，缓存部分单独按缓存价算。
 *   <li><b>精度</b>：除法必须在乘法<b>之前</b>完成，且用 {@link BigDecimal#divide(java.math.BigDecimal, int,
 *       RoundingMode)} 指定精度而不是无参 {@code divide()}（后者遇到无限小数会抛 {@code ArithmeticException}）。
 *   <li><b>scale 固定 8</b>，{@link RoundingMode#HALF_UP}；展示层才 scale=4。
 * </ol>
 */
public final class CostCalculator {

    /** 每 1000 token 的除数。 */
    public static final BigDecimal THOUSAND = BigDecimal.valueOf(1000L);

    /** 成本内部精度（小数位）。 */
    public static final int COST_SCALE = 8;

    /** 展示层精度（小数位）。 */
    public static final int DISPLAY_SCALE = 4;

    private static final CostCalculator INSTANCE = new CostCalculator();

    /**
     * 返回共享实例。本类无状态。
     *
     * @return 共享实例
     */
    public static CostCalculator getInstance() {
        return INSTANCE;
    }

    /**
     * 按价格条目计算成本。
     *
     * @param usage 计量，不可为 null
     * @param price 价格条目，不可为 null
     * @return 成本金额，scale=8 的 CNY 金额
     */
    public BigDecimal compute(Usage usage, PriceEntry price) {
        if (usage == null) {
            throw new NullPointerException("usage 不能为 null");
        }
        if (price == null) {
            throw new NullPointerException("price 不能为 null");
        }
        BigDecimal billableInput = BigDecimal.valueOf(usage.billableInputTokens());
        BigDecimal billableCached = BigDecimal.valueOf(usage.cachedInputTokens());
        BigDecimal outputTokens = BigDecimal.valueOf(usage.outputTokens());
        BigDecimal reasoningTokens = BigDecimal.valueOf(usage.reasoningTokens());

        BigDecimal cost =
                billableInput
                        .divide(THOUSAND, COST_SCALE, RoundingMode.HALF_UP)
                        .multiply(price.inputPricePerUnit())
                        .add(
                                billableCached
                                        .divide(THOUSAND, COST_SCALE, RoundingMode.HALF_UP)
                                        .multiply(price.effectiveCachedInputPrice()))
                        .add(
                                outputTokens
                                        .divide(THOUSAND, COST_SCALE, RoundingMode.HALF_UP)
                                        .multiply(price.outputPricePerUnit()))
                        .add(
                                reasoningTokens
                                        .divide(THOUSAND, COST_SCALE, RoundingMode.HALF_UP)
                                        .multiply(price.effectiveReasoningPrice()));

        return cost.setScale(COST_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 按价格条目计算成本，价格缺失时返回 0（而不是抛异常）。
     *
     * <p>观测侧的原则是<b>永不因埋点让业务失败</b>：模型不在价格表里就记 0 元并打一条 WARN， 绝不能因为缺价格而让观测链路抛出异常影响主流程。
     *
     * @param usage 计量，可为 null（按 0 处理）
     * @param price 价格条目，可为 null（按 0 处理）
     * @return 成本金额，scale=8
     */
    public BigDecimal computeOrZero(Usage usage, PriceEntry price) {
        if (usage == null || price == null) {
            return BigDecimal.ZERO.setScale(COST_SCALE, RoundingMode.HALF_UP);
        }
        return compute(usage, price);
    }

    /**
     * 按展示精度格式化成本。
     *
     * @param cost 成本金额，可为 null
     * @return scale=4 的字符串；入参为 null 时返回 {@code "0.0000"}
     */
    public String format(BigDecimal cost) {
        if (cost == null) {
            return BigDecimal.ZERO.setScale(DISPLAY_SCALE, RoundingMode.HALF_UP).toPlainString();
        }
        return cost.setScale(DISPLAY_SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * 计算相对误差。
     *
     * <p>用于「与供应商账单误差 &lt; 1%」这一验收标准的自动化测试。
     *
     * @param actual 实际值
     * @param expected 期望值（供应商账单）
     * @return 相对误差绝对值；{@code expected} 为 0 时，{@code actual} 也为 0 则返回 0，否则返回 1
     */
    public double relativeError(BigDecimal actual, BigDecimal expected) {
        if (expected == null || expected.signum() == 0) {
            return actual == null || actual.signum() == 0 ? 0.0d : 1.0d;
        }
        BigDecimal diff = actual.subtract(expected).abs();
        return diff.divide(expected.abs(), 10, RoundingMode.HALF_UP).doubleValue();
    }
}
