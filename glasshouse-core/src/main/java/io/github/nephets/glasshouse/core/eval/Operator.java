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

/** 门禁比较符（契约 {@code Operator}）。 */
public enum Operator {

    /** 大于。 */
    GT(">"),

    /** 大于等于。 */
    GTE(">="),

    /** 小于。 */
    LT("<"),

    /** 小于等于。 */
    LTE("<="),

    /** 等于。 */
    EQ("=");

    private final String symbol;

    Operator(String symbol) {
        this.symbol = symbol;
    }

    /**
     * 返回表达式中的符号。
     *
     * @return 符号文本
     */
    public String symbol() {
        return symbol;
    }

    /**
     * 按符号解析比较符。
     *
     * @param symbol 符号文本（{@code > >= < <= =}）
     * @return 对应比较符；未识别返回 null
     */
    public static Operator fromSymbol(String symbol) {
        if (symbol == null) {
            return null;
        }
        String s = symbol.trim();
        // 先匹配长符号，避免 ">" 抢先匹配 "=>"（虽然本项目不产生 "=>"，但保持解析顺序正确）
        for (Operator op : new Operator[] {GTE, LTE, GT, LT, EQ}) {
            if (op.symbol.equals(s)) {
                return op;
            }
        }
        return null;
    }

    /**
     * 执行比较。
     *
     * @param actual 实际值
     * @param threshold 阈值
     * @return 比较成立返回 {@code true}
     */
    public boolean test(BigDecimal actual, BigDecimal threshold) {
        int cmp = actual.compareTo(threshold);
        return switch (this) {
            case GT -> cmp > 0;
            case GTE -> cmp >= 0;
            case LT -> cmp < 0;
            case LTE -> cmp <= 0;
            case EQ -> cmp == 0;
        };
    }

    /**
     * 用该比较符构造一条门禁规则。
     *
     * @param metricName 指标名
     * @param threshold 阈值
     * @return 门禁规则
     */
    public GateRule rule(String metricName, BigDecimal threshold) {
        return new GateRule(metricName, this, threshold);
    }
}
