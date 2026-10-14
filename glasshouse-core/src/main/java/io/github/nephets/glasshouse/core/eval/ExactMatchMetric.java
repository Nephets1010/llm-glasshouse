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
 * 精确匹配指标：输出与 {@code expected.exact} 完全一致（去空白后）得 1 分，否则 0 分。
 *
 * <p>适用于分类、意图识别、槽位抽取这类「答案唯一」的场景。中文场景下先做全角/半角与空白归一化， 否则模型只是多打了一个空格就被判失败，指标会失真。
 */
public final class ExactMatchMetric implements Metric {

    /** 期望键名。 */
    public static final String EXPECTED_KEY = "exact";

    /** 共享实例。 */
    public static final ExactMatchMetric INSTANCE = new ExactMatchMetric();

    @Override
    public String name() {
        return NAME_ACCURACY;
    }

    @Override
    public String description() {
        return "精确匹配率：输出与 expected.exact 完全一致（忽略首尾空白与全角/半角差异）";
    }

    @Override
    public BigDecimal score(EvalResult result) {
        // 单条结果拿不到 expected，需要由 EvalRunner 在计算时传入；
        // 这里按「结果自带明细优先」的约定处理。
        BigDecimal precomputed = result.scoreOf(NAME_ACCURACY + "#" + result.caseId());
        if (BigDecimal.ZERO.compareTo(precomputed) != 0) {
            return precomputed;
        }
        return Metric.normalize(0.0d);
    }

    /**
     * 对单条结果直接打分。
     *
     * @param actual 模型输出，可为 null
     * @param expected 期望输出，可为 null
     * @return 1.0 归一化为 {@code 1.0000}，否则 {@code 0.0000}
     */
    public BigDecimal scoreOf(String actual, String expected) {
        if (actual == null || expected == null) {
            return Metric.normalize(0.0d);
        }
        return Metric.normalize(normalize(actual).equals(normalize(expected)) ? 1.0d : 0.0d);
    }

    /**
     * 归一化文本：全角转半角、去首尾空白、压缩连续空白。
     *
     * <p><b>不要用 {@code StringBuilder.append(intStream.toArray())}</b>： {@code IntStream.toArray()}
     * 返回 {@code int[]}，而 {@code StringBuilder.append} 对数组会调用 {@code Object.toString()}，结果是 {@code
     * "[I@dd0c991"} 这种无意义文本 （测试 {@code EvalRunnerTest.keywordRecall} 抓到过）。必须先映射成字符串再拼接。
     *
     * @param text 原始文本
     * @return 归一化文本
     */
    static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        StringBuilder half = new StringBuilder(text.length());
        text.codePoints()
                .forEach(
                        cp ->
                                half.appendCodePoint(
                                        (cp >= 0xFF01 && cp <= 0xFF5E) ? cp - 0xFEE0 : cp));
        return half.toString().trim().replaceAll("\\s+", " ");
    }
}
