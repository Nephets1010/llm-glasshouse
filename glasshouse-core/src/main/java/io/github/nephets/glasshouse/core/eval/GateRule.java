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
 * 单条门禁规则：{@code metricName operator threshold}。
 *
 * <p>例如 {@code accuracy > 0.85}、{@code hallucination < 0.1}。
 *
 * @param metricName 指标名
 * @param op 比较符
 * @param threshold 阈值
 */
public record GateRule(String metricName, Operator op, BigDecimal threshold) {

    /**
     * 紧凑构造器：字段校验。
     *
     * @throws IllegalArgumentException 指标名为空、比较符为 null、阈值为 null
     */
    public GateRule {
        if (metricName == null || metricName.isBlank()) {
            throw new IllegalArgumentException("门禁规则的指标名不能为空");
        }
        metricName = metricName.trim();
        if (op == null) {
            throw new IllegalArgumentException("门禁规则的比较符不能为空");
        }
        if (threshold == null) {
            throw new IllegalArgumentException("门禁规则的阈值不能为空");
        }
    }

    /**
     * 判定这条规则是否通过。
     *
     * <p><b>指标不存在时判为不通过</b>，而不是「跳过」或「通过」——门禁的语义是 「这条指标必须达标」，而指标算不出来就意味着它没达标。这条选择避免了 「拼错指标名 →
     * 门禁静默通过」这个最危险的失败模式。
     *
     * @param report 评测报告，不可为 null
     * @return 通过返回 {@code true}
     */
    public boolean passed(EvalReport report) {
        BigDecimal actual = report.scoreOf(metricName);
        return actual != null && op.test(actual, threshold);
    }

    /**
     * 判定这条规则是否通过，并给出可读的失败原因。
     *
     * @param report 评测报告
     * @return 判定结果
     */
    public GateOutcome evaluate(EvalReport report) {
        BigDecimal actual = report.scoreOf(metricName);
        if (actual == null) {
            return new GateOutcome(
                    false, this, null, "指标 " + metricName + " 未在本次评测中计算（请检查指标名拼写或该指标是否已注册）");
        }
        boolean ok = op.test(actual, threshold);
        return new GateOutcome(
                ok,
                this,
                actual,
                ok
                        ? metricName
                                + "="
                                + plain(actual)
                                + " 满足 "
                                + op.symbol()
                                + " "
                                + plain(threshold)
                        : metricName
                                + "="
                                + plain(actual)
                                + " 不满足 "
                                + op.symbol()
                                + " "
                                + plain(threshold)
                                + "（差值 "
                                + plain(actual.subtract(threshold))
                                + "）");
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /**
     * 单条规则的判定结果。
     *
     * @param passed 是否通过
     * @param rule 对应规则
     * @param actual 实际值；指标缺失时为 null
     * @param reason 可读的中文原因
     */
    public record GateOutcome(boolean passed, GateRule rule, BigDecimal actual, String reason) {}
}
