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
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExactMatchMetric} 的文本归一化与打分测试。
 *
 * <p>中文场景下模型多打一个空格、或者输入法把标点打成全角，都会让「精确匹配」误判失败。 这个指标的价值全压在 {@code normalize} 上，所以本类逐条钉住它做与不做哪些归一化。
 */
class ExactMatchMetricTest {

    private static final ExactMatchMetric METRIC = ExactMatchMetric.INSTANCE;

    @Test
    @DisplayName("name=accuracy，description 说明忽略全角/半角差异")
    void nameAndDescription() {
        assertThat(METRIC.name()).isEqualTo("accuracy");
        assertThat(METRIC.name()).isEqualTo(Metric.NAME_ACCURACY);
        assertThat(METRIC.description())
                .as("报告里要让人一眼看出这条指标做了归一化，否则会误以为它是字节级相等")
                .contains("全角")
                .contains("空白");
    }

    @Test
    @DisplayName("完全相同的文本得 1.0000")
    void identicalTextScoresOne() {
        assertThat(METRIC.scoreOf("七天无理由退货", "七天无理由退货")).isEqualByComparingTo("1.0000");
    }

    @Test
    @DisplayName("首尾空白被去掉，连续空白折叠成一个空格")
    void trimsAndCollapsesWhitespace() {
        assertThat(METRIC.scoreOf("  退货  ", "退货"))
                .as("模型在答案前后加空格很常见，不该因此判失败")
                .isEqualByComparingTo("1.0000");
        assertThat(METRIC.scoreOf("退货\t\t政策", "退货 政策"))
                .as("制表符与多个空格都折叠成单个空格后相等")
                .isEqualByComparingTo("1.0000");
        assertThat(ExactMatchMetric.normalize("  退货\t\t政策  ")).isEqualTo("退货 政策");
    }

    @Test
    @DisplayName("全角 ASCII 字符转成半角")
    void convertsFullWidthAscii() {
        assertThat(METRIC.scoreOf("ＡＢＣ１２３", "ABC123"))
                .as("中文输入法下全角字母数字很常见，字节不同但语义相同")
                .isEqualByComparingTo("1.0000");
        assertThat(ExactMatchMetric.normalize("ＡＢＣ１２３")).isEqualTo("ABC123");
    }

    @Test
    @DisplayName("全角标点转成半角标点")
    void convertsFullWidthPunctuation() {
        assertThat(METRIC.scoreOf("退货！", "退货!")).isEqualByComparingTo("1.0000");
        assertThat(ExactMatchMetric.normalize("退货！")).isEqualTo("退货!");
    }

    @Test
    @DisplayName("★ 全角空格 U+3000 不参与归一化，两侧带它的文本判为不等")
    void fullWidthSpaceIsNotTrimmed() {
        assertThat(ExactMatchMetric.normalize("　退货　"))
                .as("★ 现状：U+3000 不在 0xFF01~0xFF5E 区间，String.trim 也不认它，于是原样保留")
                .isEqualTo("　退货　");
        assertThat(METRIC.scoreOf("　退货　", "退货"))
                .as(
                        "★ 这是当前行为，不是期望行为，改它需要改主源码 ExactMatchMetric.normalize。"
                                + "全角空格属于空白，理想情况下应被折叠掉；现在判 0 分，"
                                + "意味着用户从中文文档里复制答案会莫名失分，这里留断言让偏差在报告里可见")
                .isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("任一侧为 null 都得 0.0000")
    void nullOnEitherSideScoresZero() {
        assertThat(METRIC.scoreOf(null, "退货"))
                .as("调用失败的用例 actual 是 null，不能算命中")
                .isEqualByComparingTo("0.0000");
        assertThat(METRIC.scoreOf("退货", null)).isEqualByComparingTo("0.0000");
        assertThat(METRIC.scoreOf(null, null)).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("大小写敏感")
    void caseSensitive() {
        assertThat(METRIC.scoreOf("ABC", "abc"))
                .as("全角转半角之后不再做大小写折叠，分类标签类场景要求严格一致")
                .isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("两侧都是空串得 1.0000")
    void emptyStringsMatch() {
        assertThat(METRIC.scoreOf("", "")).isEqualByComparingTo("1.0000");
    }

    @Test
    @DisplayName("normalize(null) 返回空串")
    void normalizeNullReturnsEmpty() {
        assertThat(ExactMatchMetric.normalize(null)).isEmpty();
        assertThat(ExactMatchMetric.normalize("")).isEmpty();
    }

    @Test
    @DisplayName("结果里没有预置得分时 score 返回 0.0000")
    void scoreWithoutPrecomputedValueIsZero() {
        EvalResult result = EvalResult.success("c-1", "退货", null, null, 1L);
        assertThat(METRIC.score(result)).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("结果里带 accuracy#用例id 的预置得分时取预置值")
    void scoreUsesPrecomputedValue() {
        EvalResult result =
                EvalResult.success("c-1", "退货", null, null, 1L)
                        .withScores(Map.of("accuracy#c-1", new BigDecimal("0.7")));
        assertThat(METRIC.score(result))
                .as("键里带 caseId 是为了让同一次评测的不同用例各自保留明细，不会互相覆盖")
                .isEqualByComparingTo("0.7");
    }

    @Test
    @DisplayName("预置 0 与完全没有预置不可区分")
    void zeroPrecomputedIsIndistinguishableFromMissing() {
        EvalResult withZero =
                EvalResult.success("c-2", "退货", null, null, 1L)
                        .withScores(Map.of("accuracy#c-2", new BigDecimal("0.0000")));
        EvalResult withoutScore = EvalResult.success("c-2", "退货", null, null, 1L);
        assertThat(METRIC.score(withZero))
                .as("两者都走 0.0000 分支，说明预置 0 分不会被当成「已算过」而跳过重算")
                .isEqualByComparingTo(METRIC.score(withoutScore));
        assertThat(METRIC.score(withZero)).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("全角逗号转半角，中文句号 U+3002 不转")
    void fullWidthCommaConvertsButIdeographicFullStopDoesNot() {
        assertThat(ExactMatchMetric.normalize("退货，请尽快"))
                .as("U+FF0C 落在 0xFF01~0xFF5E 区间内，减去 0xFEE0 得到半角逗号")
                .isEqualTo("退货,请尽快");
        assertThat(METRIC.scoreOf("退货，请尽快", "退货,请尽快"))
                .as("中文输入法下逗号默认出全角，不归一化的话同一句话永远判不上")
                .isEqualByComparingTo("1.0000");

        assertThat(ExactMatchMetric.normalize("退货。"))
                .as("U+3002 不在 0xFF01~0xFF5E 区间内，代码不做任何替换")
                .isEqualTo("退货。");
        assertThat(METRIC.scoreOf("退货。", "退货."))
                .as("★ 现状：中文句号与英文句号被视为不同字符，期望写半角句号时模型答全角句号会失分")
                .isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("★ 全角空格夹在文本中间时既不折叠也不去空白，两侧判为不等")
    void fullWidthSpaceInMiddleIsNotCollapsed() {
        assertThat(ExactMatchMetric.normalize("中文\u3000答案"))
                .as("Java 正则的 \\s 默认只认 ASCII 空白，U+3000 原样保留")
                .isEqualTo("中文\u3000答案");
        assertThat(METRIC.scoreOf("中文\u3000答案", "中文 答案"))
                .as(
                        "★ 这是当前行为，不是期望行为：类注释承诺「中文场景下先做全角/半角与空白归一化」，"
                                + "但 U+3000 既不在 0xFF01~0xFF5E 区间，也不被 trim 与 \\s 认作空白。"
                                + "修 normalize 让它把 U+3000 也折叠成半角空格时，必须同步改这条用例")
                .isEqualByComparingTo("0.0000");
    }
}
