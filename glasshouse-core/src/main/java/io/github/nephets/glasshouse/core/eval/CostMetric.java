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

package io.github.nephets.glasshouse.core.eval;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 成本指标：把「本次评测花的钱」映射成 0~1 的得分（越便宜得分越高）。
 *
 * <p><b>归一化方式</b>：{@code score = 1 / (1 + avgCostCny / budgetCny)}。
 * 用这个函数而不是线性扣分，是因为线性映射在成本超预算时会变成负数（clamp 到 0 后完全丧失区分度）， 而超支恰恰是最需要区分「超了一点」还是「超很多」的区间。
 *
 * <p>默认预算 1 元/次。这个数字可以被 {@code EvalConfig.costBudgetCny} 覆盖， 应该在 README 里说明「按你的实际单价调整」，而不是让用户猜。
 */
public final class CostMetric implements Metric {

    /** 默认成本预算（CNY / 次）。 */
    public static final BigDecimal DEFAULT_BUDGET_CNY = BigDecimal.ONE;

    /** 共享实例（使用默认预算）。 */
    public static final CostMetric INSTANCE = new CostMetric(DEFAULT_BUDGET_CNY);

    private final BigDecimal budgetCny;

    /** 使用默认预算构造。 */
    public CostMetric() {
        this(DEFAULT_BUDGET_CNY);
    }

    /**
     * 使用指定预算构造。
     *
     * @param budgetCny 成本预算（CNY）；必须为正
     */
    public CostMetric(BigDecimal budgetCny) {
        if (budgetCny == null || budgetCny.signum() <= 0) {
            throw new IllegalArgumentException("成本预算必须为正数");
        }
        this.budgetCny = budgetCny;
    }

    @Override
    public String name() {
        return NAME_COST;
    }

    @Override
    public String description() {
        return "成本得分：avg=预算时约 0.5，超预算时平滑下降（越大越省）";
    }

    @Override
    public BigDecimal score(EvalResult result) {
        BigDecimal precomputed = result.scoreOf(name());
        return BigDecimal.ZERO.compareTo(precomputed) != 0 ? precomputed : Metric.normalize(1.0d);
    }

    /**
     * 对单条结果直接打分。
     *
     * @param cost 本次成本（CNY），可为 null（按 0 处理）
     * @return 0~1 的成本得分
     */
    public BigDecimal scoreOf(BigDecimal cost) {
        BigDecimal actual = cost == null ? BigDecimal.ZERO : cost.max(BigDecimal.ZERO);
        double ratio = actual.divide(budgetCny, 10, RoundingMode.HALF_UP).doubleValue();
        return Metric.normalize(1.0d / (1.0d + ratio));
    }

    /**
     * 返回当前预算。
     *
     * @return 成本预算（CNY）
     */
    public BigDecimal budgetCny() {
        return budgetCny;
    }
}
