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
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 门禁表达式解析与判定测试（PRD 仓库③ P0-6 ★ CI 回归门禁的核心）。
 *
 * <p>架构 ③T02 验收标准第 7 条要求覆盖：6 种比较符 + 边界相等 + 语法错误可读报错 + 多指标全通过才为 true。本测试逐条对应。
 */
class GateEvaluatorTest {

    /**
     * 构造一份指定指标分数的报告。
     *
     * @param scores 指标名 → 分数
     * @return 评测报告
     */
    private static EvalReport report(Map<String, BigDecimal> scores) {
        return new EvalReport(
                "ds",
                "v1",
                "fp",
                "prompt@v3",
                "mock/mock-chat",
                List.of(),
                scores,
                1L,
                0L,
                BigDecimal.ZERO,
                java.time.Instant.now(),
                10L);
    }

    @Nested
    @DisplayName("五种比较符")
    class Operators {

        @ParameterizedTest(name = "{0}{1}{2} → {3}")
        @CsvSource({
            "accuracy, >,  0.85, 0.90, true",
            "accuracy, >,  0.95, 0.90, false",
            "accuracy, >=, 0.90, 0.90, true",
            "accuracy, <,  0.85, 0.90, false",
            "accuracy, <=, 0.90, 0.90, true",
            "accuracy, =,  0.90, 0.90, true",
            "accuracy, =,  0.90, 0.91, false"
        })
        @DisplayName("比较符语义正确（含边界相等）")
        void operatorSemantics(
                String metric, String symbol, String threshold, String actual, boolean expected) {
            GateRule rule = GateEvaluator.parse(metric + symbol + threshold).get(0);
            boolean passed = rule.passed(report(Map.of(metric, new BigDecimal(actual))));
            assertThat(passed)
                    .as("%s %s %s（实际 %s）", metric, symbol, threshold, actual)
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("边界相等：>= 与 <= 在恰好等于阈值时通过")
        void boundaryEquality() {
            assertThat(
                            new GateRule("accuracy", Operator.GTE, new BigDecimal("0.85"))
                                    .passed(report(Map.of("accuracy", new BigDecimal("0.85")))))
                    .isTrue();
            assertThat(
                            new GateRule("accuracy", Operator.LTE, new BigDecimal("0.85"))
                                    .passed(report(Map.of("accuracy", new BigDecimal("0.85")))))
                    .isTrue();
            assertThat(
                            new GateRule("accuracy", Operator.GT, new BigDecimal("0.85"))
                                    .passed(report(Map.of("accuracy", new BigDecimal("0.85")))))
                    .as("严格大于在相等时必须不通过")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("表达式解析")
    class Parsing {

        @Test
        @DisplayName("多条规则：accuracy>0.85,hallucination<0.1")
        void parseMultipleRules() {
            List<GateRule> rules = GateEvaluator.parse("accuracy>0.85,hallucination<0.1");
            assertThat(rules).hasSize(2);
            assertThat(rules.get(0).metricName()).isEqualTo("accuracy");
            assertThat(rules.get(0).op()).isEqualTo(Operator.GT);
            assertThat(rules.get(0).threshold()).isEqualByComparingTo("0.85");
            assertThat(rules.get(1).metricName()).isEqualTo("hallucination");
            assertThat(rules.get(1).op()).isEqualTo(Operator.LT);
            assertThat(rules.get(1).threshold()).isEqualByComparingTo("0.1");
        }

        @Test
        @DisplayName("长符号优先：>= 不会被 > 抢先匹配")
        void longSymbolWins() {
            GateRule rule = GateEvaluator.parse("accuracy>=0.85").get(0);
            assertThat(rule.op()).isEqualTo(Operator.GTE);
            assertThat(rule.threshold()).isEqualByComparingTo("0.85");
        }

        @Test
        @DisplayName("空白容忍：空格与换行都能解析")
        void toleratesWhitespace() {
            List<GateRule> rules = GateEvaluator.parse("  accuracy > 0.85 ,  cost >= 0.5  ");
            assertThat(rules).hasSize(2);
            assertThat(rules.get(0).threshold()).isEqualByComparingTo("0.85");
            assertThat(rules.get(1).threshold()).isEqualByComparingTo("0.5");
        }

        @Test
        @DisplayName("全角符号与中文逗号被归一化（中文用户从文档复制很常见）")
        void normalizesFullWidthSymbols() {
            List<GateRule> rules = GateEvaluator.parse("accuracy＞0.85，hallucination＜0.1");
            assertThat(rules).hasSize(2);
            assertThat(rules.get(0).op()).isEqualTo(Operator.GT);
            assertThat(rules.get(1).op()).isEqualTo(Operator.LT);
        }

        @Test
        @DisplayName("空表达式返回空规则列表（= 不设门禁）")
        void emptyExpressionMeansNoGate() {
            assertThat(GateEvaluator.parse(null)).isEmpty();
            assertThat(GateEvaluator.parse("")).isEmpty();
            assertThat(GateEvaluator.parse("   ")).isEmpty();
        }

        @ParameterizedTest(name = "非法表达式 \"{0}\" 报可读错误")
        @ValueSource(
                strings = {
                    "accuracy", // 无比较符
                    ">0.85", // 无指标名
                    "accuracy>", // 无阈值
                    "accuracy>abc", // 阈值非数字
                    "accuracy>0.85,", // 尾部空规则
                    ",accuracy>0.85" // 头部空规则
                })
        @DisplayName("语法错误必须给出可读的中文报错（含位置与期望）")
        void syntaxErrorsAreReadable(String bad) {
            assertThatThrownBy(() -> GateEvaluator.parse(bad))
                    .as("非法表达式 \"%s\" 必须报可读的中文错误", bad)
                    .isInstanceOf(GateEvaluator.GateParseException.class)
                    .hasMessageContaining("条规则")
                    .hasMessageContaining("表达式语法")
                    .hasMessageContaining("accuracy>0.85");
        }

        @Test
        @DisplayName("报错信息指明第几条规则出错")
        void errorTellsWhichRule() {
            assertThatThrownBy(() -> GateEvaluator.parse("accuracy>0.85,badrule"))
                    .hasMessageContaining("第 2 条规则");
        }
    }

    @Nested
    @DisplayName("判定")
    class Evaluation {

        @Test
        @DisplayName("多指标全部通过才为 true")
        void allMustPass() {
            EvalReport ok =
                    report(
                            Map.of(
                                    "accuracy",
                                    new BigDecimal("0.90"),
                                    "hallucination",
                                    new BigDecimal("0.05")));
            assertThat(
                            GateEvaluator.allPassed(
                                    ok, GateEvaluator.parse("accuracy>0.85,hallucination<0.1")))
                    .isTrue();

            EvalReport oneBad =
                    report(
                            Map.of(
                                    "accuracy",
                                    new BigDecimal("0.90"),
                                    "hallucination",
                                    new BigDecimal("0.30")));
            assertThat(
                            GateEvaluator.allPassed(
                                    oneBad, GateEvaluator.parse("accuracy>0.85,hallucination<0.1")))
                    .as("一条不达标就不能算通过")
                    .isFalse();
        }

        @Test
        @DisplayName("★ 指标不存在时判为不通过（防「拼错指标名 → 门禁静默通过」）")
        void missingMetricFailsClosed() {
            EvalReport r = report(Map.of("accuracy", new BigDecimal("1.00")));
            List<GateRule> rules = GateEvaluator.parse("keywordRecall>0.9");
            assertThat(GateEvaluator.allPassed(r, rules))
                    .as("门禁语义是「这条指标必须达标」，算不出来就意味着没达标")
                    .isFalse();

            GateRule.GateOutcome outcome = rules.get(0).evaluate(r);
            assertThat(outcome.reason()).contains("未在本次评测中计算").contains("keywordRecall");
        }

        @Test
        @DisplayName("空规则列表返回 true（没配门禁就不该让 CI 变红）")
        void emptyRulesPass() {
            assertThat(GateEvaluator.allPassed(report(Map.of()), List.of())).isTrue();
            assertThat(GateEvaluator.allPassed(report(Map.of()), null)).isTrue();
        }

        @Test
        @DisplayName("逐条判定给出可读的中文原因与差值")
        void outcomesAreReadable() {
            EvalReport r = report(Map.of("accuracy", new BigDecimal("0.80")));
            List<GateRule.GateOutcome> outcomes =
                    GateEvaluator.evaluate(r, GateEvaluator.parse("accuracy>0.85"));
            assertThat(outcomes).hasSize(1);
            assertThat(outcomes.get(0).passed()).isFalse();
            assertThat(outcomes.get(0).reason())
                    .contains("accuracy=0.8")
                    .contains("不满足 > 0.85")
                    .contains("差值 -0.05");
        }

        @Test
        @DisplayName("describe 汇总：全过 / 有失败")
        void describeSummarizes() {
            List<GateRule> rules = GateEvaluator.parse("accuracy>0.85,hallucination<0.1");
            assertThat(
                            GateEvaluator.describe(
                                    GateEvaluator.evaluate(
                                            report(
                                                    Map.of(
                                                            "accuracy",
                                                            new BigDecimal("0.9"),
                                                            "hallucination",
                                                            new BigDecimal("0.05"))),
                                            rules)))
                    .contains("门禁全部通过")
                    .contains("2 条规则");

            assertThat(
                            GateEvaluator.describe(
                                    GateEvaluator.evaluate(
                                            report(
                                                    Map.of(
                                                            "accuracy",
                                                            new BigDecimal("0.5"),
                                                            "hallucination",
                                                            new BigDecimal("0.05"))),
                                            rules)))
                    .contains("门禁未通过")
                    .contains("1/2 条规则不达标");

            assertThat(GateEvaluator.describe(List.of())).contains("未配置门禁规则");
        }
    }

    @Nested
    @DisplayName("Operator 枚举")
    class OperatorEnum {

        @Test
        @DisplayName("符号解析与枚举一一对应")
        void symbolParsing() {
            assertThat(Operator.fromSymbol(">")).isEqualTo(Operator.GT);
            assertThat(Operator.fromSymbol(">=")).isEqualTo(Operator.GTE);
            assertThat(Operator.fromSymbol("<")).isEqualTo(Operator.LT);
            assertThat(Operator.fromSymbol("<=")).isEqualTo(Operator.LTE);
            assertThat(Operator.fromSymbol("=")).isEqualTo(Operator.EQ);
            assertThat(Operator.fromSymbol(">=")).as("长符号优先").isEqualTo(Operator.GTE);
            assertThat(Operator.fromSymbol("!=")).isNull();
            assertThat(Operator.fromSymbol(null)).isNull();
        }

        @Test
        @DisplayName("rule() 工厂方法构造规则")
        void ruleFactory() {
            GateRule rule = Operator.GT.rule("accuracy", new BigDecimal("0.85"));
            assertThat(rule.metricName()).isEqualTo("accuracy");
            assertThat(rule.op()).isEqualTo(Operator.GT);
            assertThat(rule.threshold()).isEqualByComparingTo("0.85");
            assertThat(Operator.GT.symbol()).isEqualTo(">");
        }

        @Test
        @DisplayName("GateRule 构造器 fail-fast")
        void gateRuleValidation() {
            assertThatThrownBy(() -> new GateRule(" ", Operator.GT, BigDecimal.ONE))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new GateRule("accuracy", null, BigDecimal.ONE))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new GateRule("accuracy", Operator.GT, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
