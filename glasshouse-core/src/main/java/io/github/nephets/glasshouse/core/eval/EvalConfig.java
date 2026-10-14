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

/**
 * 评测配置。
 *
 * @param modelRef 被测模型，形如 {@code provider/model}
 * @param promptVersion 被测 Prompt 版本标识
 * @param temperature 采样温度，可为 null（用模型默认）
 * @param maxTokens 单次最大输出 token，可为 null
 * @param costBudgetCny 成本指标的单次预算（CNY）
 * @param maxCaseFailures 允许的最大用例失败数；超过则整体判失败
 * @param parallelism 并发度；1 表示串行（CI 中推荐，可复现性最好）
 * @param perCaseTimeoutMillis 单用例超时（毫秒）
 * @param gateExpression 门禁表达式，可为 null（不设门禁）
 * @param baselinePromptVersion 基线 Prompt 版本；非空时生成对比报告
 * @param continueOnCaseError 单用例失败时是否继续跑剩余用例
 */
public record EvalConfig(
        String modelRef,
        String promptVersion,
        Double temperature,
        Integer maxTokens,
        BigDecimal costBudgetCny,
        int maxCaseFailures,
        int parallelism,
        long perCaseTimeoutMillis,
        String gateExpression,
        String baselinePromptVersion,
        boolean continueOnCaseError) {

    /** 默认成本预算（CNY / 次）。 */
    public static final BigDecimal DEFAULT_COST_BUDGET_CNY = BigDecimal.ONE;

    /** 默认允许失败数：0（任何一条失败都不放过）。 */
    public static final int DEFAULT_MAX_CASE_FAILURES = 0;

    /** 默认并发度：1（串行，保证 CI 可复现）。 */
    public static final int DEFAULT_PARALLELISM = 1;

    /** 默认单用例超时：30 秒。 */
    public static final long DEFAULT_PER_CASE_TIMEOUT_MILLIS = 30_000L;

    /**
     * 构造默认配置。
     *
     * @param modelRef 被测模型
     * @param promptVersion Prompt 版本
     * @return 配置
     */
    public static EvalConfig of(String modelRef, String promptVersion) {
        return new EvalConfig(
                modelRef,
                promptVersion,
                null,
                null,
                DEFAULT_COST_BUDGET_CNY,
                DEFAULT_MAX_CASE_FAILURES,
                DEFAULT_PARALLELISM,
                DEFAULT_PER_CASE_TIMEOUT_MILLIS,
                null,
                null,
                true);
    }

    /**
     * 紧凑构造器：字段兜底。
     *
     * @throws IllegalArgumentException 成本预算非正、并发度非正
     */
    public EvalConfig {
        if (costBudgetCny == null || costBudgetCny.signum() <= 0) {
            costBudgetCny = DEFAULT_COST_BUDGET_CNY;
        }
        if (parallelism <= 0) {
            parallelism = DEFAULT_PARALLELISM;
        }
        if (maxCaseFailures < 0) {
            maxCaseFailures = 0;
        }
        if (perCaseTimeoutMillis <= 0) {
            perCaseTimeoutMillis = DEFAULT_PER_CASE_TIMEOUT_MILLIS;
        }
        modelRef = modelRef == null || modelRef.isBlank() ? "unknown/unknown" : modelRef.trim();
        promptVersion =
                promptVersion == null || promptVersion.isBlank() ? "unknown" : promptVersion.trim();
    }

    /**
     * 返回一个设置了门禁表达式的副本。
     *
     * @param gateExpression 门禁表达式
     * @return 新配置
     */
    public EvalConfig withGate(String gateExpression) {
        return new EvalConfig(
                modelRef,
                promptVersion,
                temperature,
                maxTokens,
                costBudgetCny,
                maxCaseFailures,
                parallelism,
                perCaseTimeoutMillis,
                gateExpression,
                baselinePromptVersion,
                continueOnCaseError);
    }

    /**
     * 返回一个设置了成本预算（CNY）的副本。
     *
     * <p>没有这个方法的话，想在评测里换成本预算就得重建整个 config； CI 里「只想调一个阈值」这种最常见的场景会因此写出一堆构造器调用。
     *
     * @param costBudget 成本指标的单次预算（CNY）；非正时回退默认值
     * @return 新配置
     */
    public EvalConfig withCostBudget(BigDecimal costBudget) {
        return new EvalConfig(
                modelRef,
                promptVersion,
                temperature,
                maxTokens,
                costBudget,
                maxCaseFailures,
                parallelism,
                perCaseTimeoutMillis,
                gateExpression,
                baselinePromptVersion,
                continueOnCaseError);
    }

    /**
     * 返回一个设置了基线版本的副本。
     *
     * @param baseline 基线 Prompt 版本
     * @return 新配置
     */
    public EvalConfig withBaseline(String baseline) {
        return new EvalConfig(
                modelRef,
                promptVersion,
                temperature,
                maxTokens,
                costBudgetCny,
                maxCaseFailures,
                parallelism,
                perCaseTimeoutMillis,
                gateExpression,
                baseline,
                continueOnCaseError);
    }

    /**
     * 返回一个设置了并发度的副本。
     *
     * @param newParallelism 并发度
     * @return 新配置
     */
    public EvalConfig withParallelism(int newParallelism) {
        return new EvalConfig(
                modelRef,
                promptVersion,
                temperature,
                maxTokens,
                costBudgetCny,
                maxCaseFailures,
                newParallelism,
                perCaseTimeoutMillis,
                gateExpression,
                baselinePromptVersion,
                continueOnCaseError);
    }

    /**
     * 判断失败数是否超过允许上限。
     *
     * @param failed 实际失败数
     * @return 超限返回 {@code true}
     */
    public boolean exceedsFailureBudget(long failed) {
        return failed > maxCaseFailures;
    }
}
