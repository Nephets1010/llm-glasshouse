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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LlmJudgeMetric} 的中文评审提示词与得分解析测试。
 *
 * <p>这个 SPI 首版不注册到引擎（Judge 依赖另一次模型调用，无法保证跨时间可复现）， 但提示词模板与解析规则是给用户自建 CI 门禁直接复用的，所以两者都要钉住：
 * 提示词里少了评分锚点，Judge 的输出就会漂移；解析规则放松一位，5 分制就会被误读成 15 分。
 */
class LlmJudgeMetricTest {

    /** 只实现两个必需方法的最小 Judge 实现，用于验证默认方法。 */
    private static final LlmJudgeMetric JUDGE =
            new LlmJudgeMetric() {

                @Override
                public String name() {
                    return "judgeHelpfulness";
                }

                @Override
                public String dimension() {
                    return LlmJudgeMetric.DIMENSION_HELPFULNESS;
                }
            };

    @Test
    @DisplayName("三个维度常量与文档一致")
    void dimensionConstants() {
        assertThat(LlmJudgeMetric.DIMENSION_HELPFULNESS).isEqualTo("helpfulness");
        assertThat(LlmJudgeMetric.DIMENSION_FAITHFULNESS).isEqualTo("faithfulness");
        assertThat(LlmJudgeMetric.DIMENSION_HARMFULNESS).isEqualTo("harmfulness");
        assertThat(JUDGE.name()).isEqualTo("judgeHelpfulness");
    }

    @Test
    @DisplayName("提示词写明评审员角色与当前维度")
    void buildPromptStatesRoleAndDimension() {
        String prompt = JUDGE.buildPrompt("退货政策？", "七天无理由退货。", null);
        assertThat(prompt).as("角色设定决定了 Judge 会不会随手给高分").contains("你是一位严格的中文问答质量评审员");
        assertThat(prompt).contains("【评分维度】").contains("helpfulness");
    }

    @Test
    @DisplayName("提示词包含五档评分锚点原文")
    void buildPromptContainsAllFiveAnchors() {
        String prompt = JUDGE.buildPrompt("退货政策？", "七天无理由退货。", null);
        assertThat(prompt)
                .as("没有锚点定义时 Judge 只能凭感觉给分，同一份回答两次评审结果会不一致")
                .contains("【评分锚点】")
                .contains("5分：完全满足该维度要求，无需任何修改。")
                .contains("4分：基本满足，仅有轻微瑕疵，不影响可用性。")
                .contains("3分：部分满足，存在明显问题但核心信息可用。")
                .contains("2分：大部分不满足，需要重新生成。")
                .contains("1分：完全不满足，或内容有害/失实。");
    }

    @Test
    @DisplayName("参考答案为 null 时提示词写「（无）」")
    void buildPromptUsesPlaceholderWhenReferenceNull() {
        assertThat(JUDGE.buildPrompt("退货政策？", "七天无理由退货。", null))
                .as("占位符让 Judge 明确知道「没有参考答案」，而不是误以为参考为空字符串")
                .contains("（无）");
    }

    @Test
    @DisplayName("参考答案非 null 时原文出现在提示词里")
    void buildPromptEmbedsReferenceText() {
        String prompt = JUDGE.buildPrompt("退货政策？", "七天无理由退货。", "自签收次日起七日内可无理由退货");
        assertThat(prompt).contains("自签收次日起七日内可无理由退货").doesNotContain("（无）");
    }

    @Test
    @DisplayName("问题与待评回答的原文都出现在提示词里")
    void buildPromptEmbedsQuestionAndAnswer() {
        String prompt = JUDGE.buildPrompt("发票怎么开？", "可以在订单页申请电子发票。", null);
        assertThat(prompt)
                .as("四个占位符按顺序填充，少填一个就会串位")
                .contains("【用户问题】")
                .contains("发票怎么开？")
                .contains("【待评回答】")
                .contains("可以在订单页申请电子发票。");
    }

    @Test
    @DisplayName("提示词要求第一行先引用证据原文")
    void buildPromptRequiresEvidenceQuoteFirst() {
        String prompt = JUDGE.buildPrompt("退货政策？", "七天无理由退货。", null);
        assertThat(prompt)
                .as("先引用证据再打分，能压住「看起来合理就给高分」的倾向")
                .contains("第一行必须先引用回答中的关键证据原文")
                .contains("EVIDENCE:")
                .contains("SCORE:")
                .contains("REASON:");
    }

    @Test
    @DisplayName("解析器读取普通冒号的 SCORE 行")
    void parseScoreReadsStrictLine() {
        assertThat(JUDGE.parseScore("SCORE: 4")).isEqualTo(4);
        assertThat(JUDGE.parseScore("EVIDENCE: 七天无理由\nSCORE: 5\nREASON: 准确")).isEqualTo(5);
    }

    @Test
    @DisplayName("解析器接受全角冒号")
    void parseScoreAcceptsFullWidthColon() {
        assertThat(JUDGE.parseScore("SCORE：3")).as("中文 Judge 输出全角标点是常态，不认就等于解析失败").isEqualTo(3);
    }

    @Test
    @DisplayName("解析器接受冒号后换行的写法")
    void parseScoreAcceptsNewlineAfterColon() {
        assertThat(JUDGE.parseScore("SCORE:\n4")).isEqualTo(4);
    }

    @Test
    @DisplayName("严谨的 SCORE 行优先于正文里散落的数字")
    void parseScorePrefersStrictLineOverLooseDigit() {
        assertThat(JUDGE.parseScore("EVIDENCE: 4 分\nSCORE: 2"))
                .as("证据里出现的数字不能抢先被当成得分")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("多条 SCORE 行时取第一条")
    void parseScoreTakesFirstStrictMatch() {
        assertThat(JUDGE.parseScore("SCORE: 3\nSCORE: 5"))
                .as("模型重复输出格式时，先出现的那个才是它的第一判断")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("没有 SCORE 行时回退取第一个独立的 1~5 数字")
    void parseScoreFallsBackToLooseDigit() {
        assertThat(JUDGE.parseScore("我给这个回答 3 分")).isEqualTo(3);
    }

    @Test
    @DisplayName("★ 只取一位数字：SCORE: 15 读成 1，SCORE: 5.5 读成 5")
    void parseScoreTakesOnlyOneDigit() {
        assertThat(JUDGE.parseScore("SCORE: 15"))
                .as(
                        "★ 这是当前行为，不是期望行为，改它需要改主源码 LlmJudgeMetric.java:100-104："
                                + "正则只捕获一位数字，越界的 15 被读成 1，于是「模型给了满分」变成「给了最低分」。"
                                + "这里留断言让偏差在报告里可见")
                .isEqualTo(1);
        assertThat(JUDGE.parseScore("SCORE: 5.5")).as("★ 同上：小数被截成整数部分，5.5 读成 5").isEqualTo(5);
    }

    @Test
    @DisplayName("0 分与 7 分都解析不出来")
    void parseScoreRejectsOutOfRange() {
        assertThat(JUDGE.parseScore("SCORE: 0")).isNull();
        assertThat(JUDGE.parseScore("SCORE: 7")).isNull();
        assertThat(JUDGE.parseScore("这个回答没有明显问题")).isNull();
    }

    @Test
    @DisplayName("★ 全角数字解析不出来")
    void parseScoreRejectsFullWidthDigit() {
        assertThat(JUDGE.parseScore("SCORE：５"))
                .as(
                        "★ 这是当前行为，不是期望行为，改它需要改主源码 LlmJudgeMetric.java:101："
                                + "正则只认 ASCII 数字，而中文 Judge 很容易输出全角数字，"
                                + "结果是整条用例的 Judge 得分为 0，这里留断言让偏差可见")
                .isNull();
    }

    @Test
    @DisplayName("null 与空串都返回 null")
    void parseScoreNullAndEmptyReturnsNull() {
        assertThat(JUDGE.parseScore(null)).isNull();
        assertThat(JUDGE.parseScore("")).isNull();
    }

    @Test
    @DisplayName("normalizeScore 把 1~5 分映射到 0~1")
    void normalizeScoreMapsOneToFive() {
        assertThat(JUDGE.normalizeScore(1)).isEqualByComparingTo("0.0000");
        assertThat(JUDGE.normalizeScore(3)).isEqualByComparingTo("0.5000");
        assertThat(JUDGE.normalizeScore(5)).isEqualByComparingTo("1.0000");
        assertThat(JUDGE.normalizeScore(null))
                .as("解析失败时给 0 而不是 0.5，避免「没读懂」被当成中性评价")
                .isEqualByComparingTo("0.0000");
        assertThat(JUDGE.normalizeScore(0)).isEqualByComparingTo("0.0000");
        assertThat(JUDGE.normalizeScore(9)).isEqualByComparingTo("1.0000");
    }

    @Test
    @DisplayName("Judge 未接入时打分恒为 0.0000")
    void scoreNotWiredReturnsZero() {
        assertThat(JUDGE.score(EvalResult.success("j-1", "答", null, null, 1L)))
                .as("占位实现必须返回 0：给满分会让门禁在 Judge 没算分的情况下直接通过")
                .isEqualByComparingTo("0.0000");
        assertThat(JUDGE.score(EvalResult.failure("j-2", "boom"))).isEqualByComparingTo("0.0000");
    }
}
