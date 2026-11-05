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

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link GateEvaluator} 的边界入参测试。
 *
 * <p>门禁是 CI 的最后一道判定，调用方常常直接把命令行参数透传进来：没配门禁时传 null，从文档里粘贴时带上
 * 全角空格。这两类入参都不该让一次已经跑完的评测以异常收场，或者以错误的异常类型收场。
 */
class GateEvaluatorEdgeCaseTest {

    @Test
    @DisplayName("规则列表为 null 时 evaluate 返回空列表、allPassed 返回 true")
    void nullRulesMeanNoGate() {
        EvalReport report = EvalReport.empty("customer-service-v1", "v1");
        assertThat(GateEvaluator.evaluate(report, null))
                .as("没配门禁时 CLI 会把这个 null 直接透传进来，抛 NPE 会让一次正常的评测以栈崩收场")
                .isEmpty();
        assertThat(GateEvaluator.evaluate(report, List.of())).isEmpty();
        assertThat(GateEvaluator.allPassed(report, null)).as("没配门禁就是没门禁，不该让 CI 变红").isTrue();
    }

    @Test
    @DisplayName("★ 指标名为全角空格时报普通 IllegalArgumentException，不是 GateParseException")
    void fullWidthSpaceMetricNameBypassesGateParseException() {
        assertThatThrownBy(() -> GateEvaluator.parse("\u3000>0.85"))
                .as(
                        "★ 这是当前行为，不是期望行为：U+3000 不被 String.trim 去掉，指标名成了「　」，"
                                + "GateEvaluator 里的 metricName.isEmpty() 判不出来（isEmpty 不认空白），"
                                + "最后是 GateRule 的 isBlank 把它拦下，抛出 IllegalArgumentException 而非 GateParseException。"
                                + "CLI 靠异常类型区分「表达式写错」（退出码 2）与「门禁不通过」（退出码 1），"
                                + "这条路径会被错判成后者")
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(GateEvaluator.GateParseException.class)
                .hasMessageContaining("指标名");
    }
}
