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
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KeywordRecallMetric} 的打分测试。
 *
 * <p>这是中文评测里最常用的规则型指标，正确性完全依赖「子串包含」这一条判断。 本类同时钉住它的分母口径：分母恒为关键词列表长度，其中不合理的关键词条目也会让整条用例失分。
 */
class KeywordRecallMetricTest {

    private static final KeywordRecallMetric METRIC = KeywordRecallMetric.INSTANCE;

    @Test
    @DisplayName("name=keywordRecall，description 说明是关键点占比")
    void nameAndDescription() {
        assertThat(METRIC.name()).isEqualTo("keywordRecall");
        assertThat(METRIC.name()).isEqualTo(Metric.NAME_KEYWORD_RECALL);
        assertThat(METRIC.description()).contains("关键词").contains("比例");
    }

    @Test
    @DisplayName("得分等于命中数除以关键词数")
    void hitRatioIsHitsOverKeywords() {
        assertThat(METRIC.scoreOf("支持七天无理由退货", List.of("七天无理由退货", "发票")))
                .as("两个关键词命中一个 → 1/2")
                .isEqualByComparingTo("0.5000");
        assertThat(METRIC.scoreOf("支持七天无理由退货，可开发票", List.of("七天无理由退货", "发票")))
                .isEqualByComparingTo("1.0000");
        assertThat(METRIC.scoreOf("答非所问", List.of("七天无理由退货", "发票"))).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("中文没有空格，靠子串包含即可命中")
    void chineseSubstringWithoutSpaces() {
        assertThat(METRIC.scoreOf("支持七天无理由退货", List.of("七天无理由退货", "七天无理由")))
                .as("长短语与它的前缀都能在原文里找到，不需要分词器")
                .isEqualByComparingTo("1.0000");
    }

    @Test
    @DisplayName("中英混排时按各自形态匹配")
    void mixedChineseAndEnglish() {
        assertThat(METRIC.scoreOf("支持 7 天无理由退货", List.of("7 天", "七天")))
                .as("原文写的是阿拉伯数字 7，所以中文数字写法的关键词「七天」不命中")
                .isEqualByComparingTo("0.5000");
    }

    @Test
    @DisplayName("英文按子串匹配，不做词边界判断")
    void englishSubstringMatches() {
        assertThat(METRIC.scoreOf("unreturnable", List.of("return")))
                .as("这是已知的宽松之处：只要字符序列出现就算命中，词边界判断不在首版范围内")
                .isEqualByComparingTo("1.0000");
    }

    @Test
    @DisplayName("实际输出先做全角转半角再匹配")
    void fullWidthActualIsNormalized() {
        assertThat(METRIC.scoreOf("ＡＢＣ", List.of("ABC"))).isEqualByComparingTo("1.0000");
        assertThat(METRIC.scoreOf("ABC", List.of("ＡＢＣ")))
                .as("关键词侧同样走归一化，否则两侧写法的差异会单向导致漏命中")
                .isEqualByComparingTo("1.0000");
    }

    @Test
    @DisplayName("中文标点：全角逗号能命中，全角句号命中不到")
    void chinesePunctuationIsPartOfKeyword() {
        assertThat(METRIC.scoreOf("可以退货，请注意时效", List.of("退货，")))
                .as("两侧都先做全角转半角，所以全角逗号写法差异不会导致漏命中；" + "但中文句号 U+3002 不在归一化范围内，写全角句号当关键词就命中不到")
                .isEqualByComparingTo("1.0000");
        assertThat(METRIC.scoreOf("可以退货，请注意时效", List.of("退货。"))).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("actual 为 null 或关键词列表为 null/空都得 0.0000")
    void nullActualOrKeywordsScoreZero() {
        assertThat(METRIC.scoreOf(null, List.of("退货"))).isEqualByComparingTo("0.0000");
        assertThat(METRIC.scoreOf("退货", null)).isEqualByComparingTo("0.0000");
        assertThat(METRIC.scoreOf("退货", List.of()))
                .as("没有关键词就无从判定，给满分会让「漏配 expected.contains」的用例静默通过")
                .isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("空串与纯空白输出得 0.0000")
    void blankActualScoresZero() {
        assertThat(METRIC.scoreOf("", List.of("退货"))).isEqualByComparingTo("0.0000");
        assertThat(METRIC.scoreOf("   ", List.of("退货"))).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("★ null 与空白关键词仍占分母")
    void nullAndBlankKeywordsCountInDenominator() {
        assertThat(METRIC.scoreOf("支持退货", Arrays.asList("退货", null, "   ")))
                .as(
                        "★ 这是当前行为，不是期望行为，改它需要改主源码 KeywordRecallMetric.java:73："
                                + "分母固定取 keywords.size()，null 与空白条目只被跳过命中判断、不从分母剔除。"
                                + "后果是评测集里写错一个空关键词，整条用例的召回率上限就被压到 2/3，"
                                + "这里留断言让这个偏差在报告里可见")
                .isEqualByComparingTo("0.3333");
    }

    @Test
    @DisplayName("全为空白关键词时得 0.0000，且不抛除零异常")
    void allBlankKeywordsYieldZeroWithoutDivisionByZero() {
        assertThat(METRIC.scoreOf("支持退货", Arrays.asList(null, "  ")))
                .as("命中数为 0、分母为 2，结果仍是可比较的 0.0000")
                .isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("同一关键词写两次各自计一次命中")
    void duplicateKeywordCountsTwice() {
        assertThat(METRIC.scoreOf("支持退货", List.of("退货", "退货")))
                .as("重复条目把权重放大了一倍，这是分母口径的直接结果，不是特判")
                .isEqualByComparingTo("1.0000");
        assertThat(METRIC.scoreOf("支持退货", List.of("退货", "退货", "发票")))
                .isEqualByComparingTo("0.6667");
    }

    @Test
    @DisplayName("score 取具名指标上的预置值，缺省时为 0.0000")
    void scoreUsesPrecomputedValue() {
        EvalResult precomputed =
                EvalResult.success("k-1", "退货", null, null, 1L)
                        .withScores(Map.of("keywordRecall", new BigDecimal("0.5")));
        assertThat(METRIC.score(precomputed)).isEqualByComparingTo("0.5000");
        assertThat(METRIC.score(EvalResult.success("k-2", "退货", null, null, 1L)))
                .isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("expected.contains 写成单个字符串时按单元素列表参与计算")
    void singleStringExpectedBecomesSingleElementList() {
        EvalCase evalCase =
                EvalCase.of(
                        "k-3",
                        Map.of("question", "退货政策？"),
                        Map.of("contains", "七天无理由退货"),
                        "售后",
                        "easy",
                        true,
                        null);
        List<String> keywords = evalCase.expectedList(KeywordRecallMetric.EXPECTED_KEY);
        assertThat(keywords).containsExactly("七天无理由退货");
        assertThat(METRIC.scoreOf("支持七天无理由退货", keywords)).isEqualByComparingTo("1.0000");
    }

    @Test
    @DisplayName("中文短语整体作为关键词时靠子串包含命中")
    void chinesePhraseHitsBySubstring() {
        assertThat(METRIC.scoreOf("支持七日无理由退货", List.of("七日无理由退货")))
                .as("关键词本身就是短语，原文里出现这串字符即算命中，不需要分词")
                .isEqualByComparingTo("1.0000");
        assertThat(METRIC.scoreOf("支持七日无理由退货", List.of("退货政策")))
                .as("短语被拆开或中间插字就命中不到，子串口径不支持语义近似")
                .isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("三个关键词命中两个得 0.6667")
    void twoOfThreeHitsScoreTwoThirds() {
        assertThat(METRIC.scoreOf("支持七日无理由退货与开发票", List.of("七日无理由退货", "发票", "退款")))
                .as("命中比例按 HALF_UP 保留四位：2/3 舍入到 0.6667")
                .isEqualByComparingTo("0.6667");
    }

    @Test
    @DisplayName("★ 空串关键词不算命中但照样占分母")
    void emptyStringKeywordCountsInDenominator() {
        assertThat(METRIC.scoreOf("支持退货", List.of("退货", "发票")))
                .as("两个关键词里命中一个")
                .isEqualByComparingTo("0.5000");
        assertThat(METRIC.scoreOf("支持退货", List.of("退货", "发票", "")))
                .as(
                        "★ 这是当前行为，不是期望行为：空串 isBlank 为真，被跳过命中判断，"
                                + "但分母仍是 keywords.size()=3，于是同样命中一个关键词只能拿到 0.3333。"
                                + "评测集里多写一个空串，这条用例的召回率上限就被压掉三分之一")
                .isEqualByComparingTo("0.3333");
    }
}
