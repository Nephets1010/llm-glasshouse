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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EvalConfig} 的字段兜底与副本构造测试。
 *
 * <p>配置项大多来自命令行或 YAML，缺省、留空、写 0 都是常态。兜底值决定了报告里的模型名与预算， 兜错会让两份本应可比的报告看起来是不同的实验。
 */
class EvalConfigFallbackTest {

    @Test
    @DisplayName("modelRef 与 promptVersion 为空白时回退 unknown")
    void blankModelRefAndPromptVersionFallBackToUnknown() {
        EvalConfig config =
                new EvalConfig(
                        "   ", "", null, null, BigDecimal.ONE, 0, 1, 30_000L, null, null, true);
        assertThat(config.modelRef())
                .as("模型名留空时报告不能出现空白字段，否则基线对比无法判断是不是同一模型")
                .isEqualTo("unknown/unknown");
        assertThat(config.promptVersion()).isEqualTo("unknown");
    }

    @Test
    @DisplayName("withCostBudget 传入 0 时预算回退默认值 1")
    void withCostBudgetZeroFallsBackToOne() {
        EvalConfig config = EvalConfig.of("mock/mock-chat", "v3").withCostBudget(BigDecimal.ZERO);
        assertThat(config.costBudgetCny())
                .as("预算 0 会让成本曲线除零，必须回退而不是抛异常打断整轮评测")
                .isEqualByComparingTo("1");
        assertThat(EvalConfig.of("mock/mock-chat", "v3").withCostBudget(null).costBudgetCny())
                .isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("withParallelism 传入 0 时回退串行 1")
    void withParallelismZeroFallsBackToOne() {
        EvalConfig config = EvalConfig.of("mock/mock-chat", "v3").withParallelism(0);
        assertThat(config.parallelism()).as("并发度 0 无法执行任何用例；回退到 1 也顺带保住了 CI 的可复现性").isEqualTo(1);
        assertThat(config.parallelism()).isEqualTo(EvalConfig.DEFAULT_PARALLELISM);
    }

    @Test
    @DisplayName("withCostBudget 只改预算，其他字段保持不变")
    void withCostBudgetOnlyChangesBudget() {
        EvalConfig base = EvalConfig.of("deepseek/deepseek-chat", "v3").withParallelism(4);
        EvalConfig updated = base.withCostBudget(new BigDecimal("2.5"));

        assertThat(updated.costBudgetCny()).isEqualByComparingTo("2.5");
        assertThat(updated.modelRef()).isEqualTo("deepseek/deepseek-chat");
        assertThat(updated.promptVersion()).isEqualTo("v3");
        assertThat(updated.parallelism()).isEqualTo(4);
        assertThat(updated.continueOnCaseError()).isTrue();
        assertThat(updated.perCaseTimeoutMillis())
                .isEqualTo(EvalConfig.DEFAULT_PER_CASE_TIMEOUT_MILLIS);
        assertThat(base.costBudgetCny()).as("record 是不可变的，改副本不能反写原配置").isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("maxCaseFailures 为负时归零，perCaseTimeoutMillis 非正时回退 30 秒")
    void negativeFailureBudgetAndNonPositiveTimeoutFallBack() {
        EvalConfig config =
                new EvalConfig(
                        "mock/mock-chat",
                        "v3",
                        null,
                        null,
                        BigDecimal.ONE,
                        -3,
                        1,
                        0L,
                        null,
                        null,
                        true);
        assertThat(config.maxCaseFailures()).as("负的允许失败数等价于「任何失败都超限」，归零后语义才是「一条都不许失败」").isZero();
        assertThat(config.perCaseTimeoutMillis())
                .as("超时 0 会让每条用例立刻中断，必须回退到文档写明的 30 秒")
                .isEqualTo(30_000L);
        assertThat(EvalConfig.DEFAULT_PER_CASE_TIMEOUT_MILLIS)
                .as("常量值本身就是对外承诺，30 秒变了所有超时相关的历史报告都不可比")
                .isEqualTo(30_000L);
        assertThat(config.exceedsFailureBudget(0L)).isFalse();
        assertThat(config.exceedsFailureBudget(1L)).isTrue();

        EvalConfig negativeTimeout =
                new EvalConfig(
                        "mock/mock-chat",
                        "v3",
                        null,
                        null,
                        BigDecimal.ONE,
                        0,
                        1,
                        -5L,
                        null,
                        null,
                        true);
        assertThat(negativeTimeout.perCaseTimeoutMillis())
                .as("负数超时同样回退默认值，不是取绝对值变成 5 毫秒")
                .isEqualTo(30_000L);
    }
}
