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

import java.util.ArrayList;
import java.util.List;

/**
 * 门禁表达式解析与判定（PRD 仓库③ P0-6 ★ CI 回归门禁）。
 *
 * <p><b>表达式语法</b>：{@code metricName>0.85,hallucination<0.1}，多个规则用逗号分隔。 支持的比较符：{@code > >= < <=
 * =}。也支持空格分隔与全角符号（{@code ＞} {@code ＜} 等）， 因为中文用户从文档里复制表达式时很容易带上全角字符。
 *
 * <p><b>错误必须可读</b>：解析失败时抛 {@link GateParseException} 并给出「哪个位置、期望什么、实际是什么」， 而不是笼统的 {@code
 * NumberFormatException}。CI 上一个看不懂的报错会让使用者直接放弃这个功能。
 */
public final class GateEvaluator {

    private GateEvaluator() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 解析门禁表达式。
     *
     * @param expression 表达式，如 {@code accuracy>0.85,hallucination<0.1}
     * @return 规则列表；表达式为 null 或空白时返回空列表（表示不设门禁）
     * @throws GateParseException 表达式语法错误
     */
    public static List<GateRule> parse(String expression) {
        if (expression == null || expression.isBlank()) {
            return List.of();
        }
        // ★ 先把全角逗号归一化为半角，再按逗号切分。
        //   顺序反了的话，Chinese comma 会被留在单条规则里，最终报
        //   「阈值 0.85,hallucination<0.1 不是合法数字」——从文档里复制带全角逗号的表达式必踩。
        String normalized = expression.replace('，', ',').replace('；', ',').replace(';', ',');
        // 用 -1 保留首尾空串：默认的 split 会丢掉开头的空串，
        // 于是 ",accuracy>0.85" 会被当成合法表达式通过——用户少打一个逗号却没有任何提示。
        String[] parts = normalized.split(",", -1);
        List<GateRule> rules = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            String raw = parts[i].trim();
            if (raw.isEmpty()) {
                throw new GateParseException(
                        "第 "
                                + (i + 1)
                                + " 条规则为空。表达式语法：指标名 比较符 阈值，多条用逗号分隔，例如 accuracy>0.85,hallucination<0.1");
            }
            rules.add(parseOne(raw, i + 1));
        }
        return List.copyOf(rules);
    }

    /**
     * 解析单条规则。
     *
     * @param raw 规则文本
     * @param position 位置（1 起），用于错误信息
     * @return 门禁规则
     * @throws GateParseException 语法错误
     */
    private static GateRule parseOne(String raw, int position) {
        String text = normalizeSymbols(raw);
        Operator op = null;
        int opIndex = -1;
        int opLength = 0;
        // 从长的符号开始找，保证 ">=" 不会被 ">" 抢先匹配
        for (Operator candidate :
                new Operator[] {
                    Operator.GTE, Operator.LTE, Operator.GT, Operator.LT, Operator.EQ
                }) {
            int idx = text.indexOf(candidate.symbol());
            if (idx > 0) {
                op = candidate;
                opIndex = idx;
                opLength = candidate.symbol().length();
                break;
            }
        }
        if (op == null) {
            throw new GateParseException(
                    "第 "
                            + position
                            + " 条规则 \""
                            + raw
                            + "\" 缺少比较符（支持的比较符：> >= < <= =）。"
                            + "表达式语法：指标名 比较符 阈值，多条用逗号分隔，例如 accuracy>0.85,hallucination<0.1");
        }
        String metricName = text.substring(0, opIndex).trim();
        if (metricName.isEmpty()) {
            throw new GateParseException(
                    "第 "
                            + position
                            + " 条规则 \""
                            + raw
                            + "\" 缺少指标名。"
                            + "表达式语法：指标名 比较符 阈值，例如 accuracy>0.85");
        }
        String thresholdText = text.substring(opIndex + opLength).trim();
        if (thresholdText.isEmpty()) {
            throw new GateParseException(
                    "第 "
                            + position
                            + " 条规则 \""
                            + raw
                            + "\" 缺少阈值。"
                            + "表达式语法：指标名 比较符 阈值，例如 accuracy>0.85");
        }
        java.math.BigDecimal threshold;
        try {
            threshold = new java.math.BigDecimal(thresholdText);
        } catch (NumberFormatException e) {
            throw new GateParseException(
                    "第 "
                            + position
                            + " 条规则 \""
                            + raw
                            + "\" 的阈值 \""
                            + thresholdText
                            + "\" 不是合法数字。"
                            + "表达式语法：指标名 比较符 阈值，例如 accuracy>0.85");
        }
        return new GateRule(metricName, op, threshold);
    }

    /**
     * 把全角比较符归一化为半角。
     *
     * <p>全角逗号的处理在 {@link #parse} 里更早完成（必须先于切分），这里只管比较符。
     *
     * @param raw 单条规则文本
     * @return 归一化后的文本
     */
    private static String normalizeSymbols(String raw) {
        return raw.replace('＞', '>')
                .replace('＜', '<')
                .replace('≥', '>')
                .replace('≤', '<')
                .replace('＝', '=')
                .replace('：', ':');
    }

    /**
     * 判定报告是否通过全部规则。
     *
     * <p><b>空规则列表返回 {@code true}</b>：没配门禁就是没门禁，不应该让 CI 变红。
     *
     * @param report 评测报告，不可为 null
     * @param rules 规则列表，可为 null 或空
     * @return 全部通过返回 {@code true}
     */
    public static boolean allPassed(EvalReport report, List<GateRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        for (GateRule rule : rules) {
            if (!rule.passed(report)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判定并返回逐条结果，供报告渲染。
     *
     * @param report 评测报告
     * @param rules 规则列表，可为 null 或空
     * @return 逐条判定结果，顺序与规则顺序一致
     */
    public static List<GateRule.GateOutcome> evaluate(EvalReport report, List<GateRule> rules) {
        List<GateRule.GateOutcome> outcomes = new ArrayList<>();
        if (rules == null) {
            return outcomes;
        }
        for (GateRule rule : rules) {
            outcomes.add(rule.evaluate(report));
        }
        return outcomes;
    }

    /**
     * 生成门禁失败的中文摘要，供 CLI 与 Markdown 报告使用。
     *
     * @param outcomes 逐条判定结果
     * @return 多行摘要；全部通过返回单行通过说明
     */
    public static String describe(List<GateRule.GateOutcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) {
            return "未配置门禁规则，本次不判定通过与否。";
        }
        List<String> failed =
                outcomes.stream()
                        .filter(o -> !o.passed())
                        .map(GateRule.GateOutcome::reason)
                        .toList();
        if (failed.isEmpty()) {
            return "门禁全部通过（" + outcomes.size() + " 条规则）。";
        }
        return "门禁未通过（"
                + failed.size()
                + "/"
                + outcomes.size()
                + " 条规则不达标）：\n"
                + String.join("\n", failed.stream().map(r -> "  - " + r).toList());
    }

    /**
     * 门禁表达式解析异常。
     *
     * <p>独立类型而不是复用 {@code IllegalArgumentException}：CLI 需要区分 「表达式写错了」（退出码 2，配置问题）与「评测跑挂了」（退出码
     * 1，门禁不通过）。
     */
    public static final class GateParseException extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        /**
         * 构造解析异常。
         *
         * @param message 可读的中文错误说明
         */
        public GateParseException(String message) {
            super(message);
        }
    }
}
