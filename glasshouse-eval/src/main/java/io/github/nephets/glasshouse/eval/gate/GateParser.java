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

package io.github.nephets.glasshouse.eval.gate;

import java.util.List;

import io.github.nephets.glasshouse.core.eval.EvalReport;
import io.github.nephets.glasshouse.core.eval.GateEvaluator;
import io.github.nephets.glasshouse.core.eval.GateRule;

/**
 * 门禁表达式解析与判定（eval 模块的薄封装）。
 *
 * <p><b>为什么 core 里已经有 {@link GateEvaluator}，这里还要一层</b>： core 的 {@code GateEvaluator} 是纯函数；本类负责「把
 * CLI 参数变成规则、把报告变成退出码」， 这两件事都需要知道「CI 门禁」的语义，因此属于 eval 模块的职责。
 *
 * <p><b>退出码契约（这是 GitHub Action 依赖的核心约定）</b>：
 *
 * <table border="1">
 *   <caption>glasshouse eval 的退出码</caption>
 *   <tr><th>退出码</th><th>含义</th><th>Action 行为</th></tr>
 *   <tr><td>0</td><td>全部通过</td><td>绿色 ✓</td></tr>
 *   <tr><td>1</td><td>门禁未达标</td><td><b>红色 ✗ —— 让流水线变红</b></td></tr>
 *   <tr><td>2</td><td>表达式 / 数据集配置错误</td><td>红色 ✗ + 提示修配置</td></tr>
 * </table>
 *
 * <p>把「门禁不达标」（1）与「配置写错」（2）分开，是为了让 CI 日志能直接告诉使用者 「该改阈值」还是「该改表达式」。
 */
public final class GateParser {

    /** 退出码：全部通过。 */
    public static final int EXIT_OK = 0;

    /** 退出码：门禁未达标。 */
    public static final int EXIT_GATE_FAILED = 1;

    /** 退出码：配置错误。 */
    public static final int EXIT_CONFIG_ERROR = 2;

    private GateParser() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 解析门禁表达式。
     *
     * @param expression 表达式，如 {@code accuracy>0.85,hallucination<0.1}
     * @return 规则列表；表达式为空时返回空列表
     * @throws GateEvaluator.GateParseException 表达式语法错误
     */
    public static List<GateRule> parse(String expression) {
        return GateEvaluator.parse(expression);
    }

    /**
     * 判定报告是否通过门禁。
     *
     * @param report 评测报告，不可为 null
     * @param expression 门禁表达式，可为 null（不设门禁）
     * @return 门禁判定结果
     * @throws GateEvaluator.GateParseException 表达式语法错误
     */
    public static Verdict evaluate(EvalReport report, String expression) {
        List<GateRule> rules = parse(expression);
        if (rules.isEmpty()) {
            return new Verdict(true, EXIT_OK, List.of(), "未配置门禁规则，本次不判定通过与否。");
        }
        List<GateRule.GateOutcome> outcomes = GateEvaluator.evaluate(report, rules);
        boolean passed = GateEvaluator.allPassed(report, rules);
        return new Verdict(
                passed,
                passed ? EXIT_OK : EXIT_GATE_FAILED,
                outcomes,
                GateEvaluator.describe(outcomes));
    }

    /**
     * 把配置错误转换为退出码 2 的判定结果。
     *
     * @param error 异常
     * @return 判定结果
     */
    public static Verdict configError(RuntimeException error) {
        return new Verdict(
                false,
                EXIT_CONFIG_ERROR,
                List.of(),
                "门禁配置错误（这是配置问题，不是评测不达标）：\n  " + error.getMessage());
    }

    /**
     * 判定用例失败数是否超出配置允许的上限。
     *
     * <p>这是「门禁」的隐形部分：即使所有指标都达标，如果有用例直接跑挂了， 也必须让流水线变红——否则一个「所有用例都超时」的改动会被判为「指标全 0 但达标」。
     *
     * @param report 评测报告
     * @param maxCaseFailures 允许的最大失败数
     * @return 通过返回 {@code true}
     */
    public static boolean caseFailuresWithinBudget(EvalReport report, int maxCaseFailures) {
        return report.failedCalls() <= Math.max(0, maxCaseFailures);
    }

    /**
     * 综合门禁判定：表达式门禁 + 用例失败预算。
     *
     * @param report 评测报告
     * @param expression 门禁表达式，可为 null
     * @param maxCaseFailures 允许的最大失败数
     * @return 判定结果
     */
    public static Verdict evaluateAll(EvalReport report, String expression, int maxCaseFailures) {
        Verdict verdict = evaluate(report, expression);
        if (!verdict.passed()) {
            return verdict;
        }
        if (!caseFailuresWithinBudget(report, maxCaseFailures)) {
            return new Verdict(
                    false,
                    EXIT_GATE_FAILED,
                    verdict.outcomes(),
                    "门禁未通过：有 "
                            + report.failedCalls()
                            + " 条用例执行失败，超过允许的 "
                            + Math.max(0, maxCaseFailures)
                            + " 条。失败用例："
                            + String.join(", ", report.failedCaseIds()));
        }
        return verdict;
    }

    /**
     * 门禁判定结果。
     *
     * @param passed 是否通过
     * @param exitCode 进程退出码
     * @param outcomes 逐条规则判定
     * @param message 可读的中文结论
     */
    public record Verdict(
            boolean passed, int exitCode, List<GateRule.GateOutcome> outcomes, String message) {

        /**
         * 紧凑构造器：列表不可变。
         *
         * @param passed 是否通过
         * @param exitCode 退出码
         * @param outcomes 逐条判定
         * @param message 结论文本
         */
        public Verdict {
            outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
            message = message == null ? "" : message;
        }
    }
}
