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

package io.github.nephets.glasshouse.eval.runner;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.nephets.glasshouse.core.eval.EvalReport;
import io.github.nephets.glasshouse.core.eval.EvalResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BaselineComparator} 的 diff 边界测试。
 *
 * <p>对比器回答的是「改 Prompt 之后是变好还是变坏」。它的边界条件比正常路径更重要：一边只有 部分指标、用例只在一侧存在、输出需要截断——这些情况下如果 diff
 * 被静默丢掉，使用者会误以为 「没有差异」，而那正是回归门禁最不能出的错。
 *
 * <p>本类只断言<b>派生结果</b>（差值、退化/改善标记、截断后的形状），不断言输入本身。
 */
class BaselineComparatorDiffTest {

    private static EvalResult scored(String id, String actual, Map<String, BigDecimal> scores) {
        return EvalResult.success(id, actual, null, null, 1L).withScores(scores);
    }

    private static EvalResult failed(String id) {
        return EvalResult.failure(id, "IllegalStateException: 上游 429");
    }

    private static EvalReport report(
            String promptVersion, List<EvalResult> results, Map<String, BigDecimal> metricScores) {
        long failedCount = results.stream().filter(r -> !r.passed()).count();
        return new EvalReport(
                "ds",
                "v1",
                "sha256:fp",
                promptVersion,
                "mock/mock-chat",
                results,
                metricScores,
                results.size(),
                failedCount,
                BigDecimal.ZERO,
                Instant.now(),
                1L);
    }

    private static BaselineComparator.CaseDiff diffOf(
            BaselineComparator.Comparison comparison, String caseId) {
        return comparison.caseDiffs().stream()
                .filter(d -> d.caseId().equals(caseId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有找到用例 " + caseId + " 的差异条目"));
    }

    @Test
    @DisplayName("★ 两侧得分完全相同的用例不进 caseDiffs（否则每次对比都刷满噪音）")
    void identicalScoresProduceNoCaseDiff() {
        EvalResult same = scored("c-1", "支持退货", Map.of("accuracy", BigDecimal.ONE));
        EvalReport before = report("v3", List.of(same), Map.of("accuracy", BigDecimal.ONE));
        EvalReport after = report("v4", List.of(same), Map.of("accuracy", BigDecimal.ONE));

        BaselineComparator.Comparison comparison = new BaselineComparator().compare(before, after);

        assertThat(comparison.caseDiffs()).isEmpty();
        assertThat(comparison.regressedCount()).isZero();
        assertThat(comparison.improvedCount()).isZero();
        assertThat(comparison.deltaOf("accuracy").unchanged()).isTrue();
    }

    @Test
    @DisplayName("★ 指标只在一侧报告出现：case 级算不出差值就不记，指标级直接跳过")
    void metricPresentOnOneSideOnlyIsSkipped() {
        // case 级：before 只有 accuracy，after 多了 cost → cost 没有基数，delta 应为 null 而不是被当成「涨了 0.8」
        EvalResult before = scored("c-1", "支持退货", Map.of("accuracy", BigDecimal.ONE));
        EvalResult after =
                scored(
                        "c-1",
                        "支持退货",
                        Map.of("accuracy", BigDecimal.ONE, "cost", new BigDecimal("0.8")));

        BaselineComparator.Comparison comparison =
                new BaselineComparator()
                        .compare(
                                report(
                                        "v3",
                                        List.of(before),
                                        Map.of("accuracy", new BigDecimal("0.5"))),
                                report(
                                        "v4",
                                        List.of(after),
                                        Map.of(
                                                "accuracy",
                                                new BigDecimal("0.5"),
                                                "keywordRecall",
                                                new BigDecimal("0.9"))));

        assertThat(comparison.caseDiffs()).as("★ 只有单侧出现的指标不能凭空造出差值").isEmpty();
        assertThat(comparison.deltaOf("keywordRecall")).as("★ 指标级：一侧没有分数就不参与对比，而不是当成 0").isNull();
        assertThat(comparison.deltaOf("accuracy").unchanged()).isTrue();
    }

    @Test
    @DisplayName("★ 用例只在一侧存在：缺的一侧输出为 null，并分别记改善 / 退化")
    void casesMissingFromOneSideGetNullOutput() {
        EvalResult c1 = scored("c-1", "支持退货", Map.of("accuracy", BigDecimal.ONE));
        EvalResult c2 = scored("c-2", "可以开发票", Map.of("accuracy", BigDecimal.ONE));
        EvalResult c3 = scored("c-3", "1-7 个工作日到账", Map.of("accuracy", BigDecimal.ONE));

        // v3 有 c-1 / c-3；v4 有 c-1 / c-2 —— c-2 是新增且达标（改善），c-3 消失了（退化）
        BaselineComparator.Comparison comparison =
                new BaselineComparator()
                        .compare(
                                report("v3", List.of(c1, c3), Map.of()),
                                report("v4", List.of(c1, c2), Map.of()));

        assertThat(comparison.caseDiffs()).hasSize(2);
        assertThat(comparison.improvedCount()).isEqualTo(1L);
        assertThat(comparison.regressedCaseIds()).containsExactly("c-3");

        BaselineComparator.CaseDiff added = diffOf(comparison, "c-2");
        assertThat(added.baselineOutput()).as("基线里没有这条用例，输出只能是 null").isNull();
        assertThat(added.improved()).isTrue();
        assertThat(added.failed()).isFalse();

        BaselineComparator.CaseDiff removed = diffOf(comparison, "c-3");
        assertThat(removed.currentOutput()).as("当前版本没有这条用例，输出只能是 null").isNull();
        assertThat(removed.regressed()).isTrue();
        assertThat(removed.failed()).as("★ 用例在新版本里根本不存在，必须算「当前失败」，否则删用例可以绕过门禁").isTrue();
    }

    @Test
    @DisplayName("★ 截断边界：恰好 120 字符原样保留，121 字符截成 120 + 省略号")
    void abbreviateKeepsExactly120AndTruncates121() {
        String exactly120 = "b".repeat(120);
        String longer121 = "c".repeat(121);
        BaselineComparator.Comparison comparison =
                new BaselineComparator()
                        .compare(
                                report(
                                        "v3",
                                        List.of(
                                                scored(
                                                        "c-1",
                                                        exactly120,
                                                        Map.of("accuracy", BigDecimal.ONE))),
                                        Map.of()),
                                report(
                                        "v4",
                                        List.of(
                                                scored(
                                                        "c-1",
                                                        longer121,
                                                        Map.of("accuracy", new BigDecimal("0.5")))),
                                        Map.of()));

        BaselineComparator.CaseDiff diff = diffOf(comparison, "c-1");
        assertThat(diff.regressed()).isTrue();
        assertThat(diff.baselineOutput()).as("120 字符是边界内，不应被截断").isEqualTo(exactly120).hasSize(120);
        assertThat(diff.currentOutput())
                .as("121 字符超界，保留前 120 字符并加省略号")
                .isEqualTo("c".repeat(120) + "...")
                .hasSize(123);
    }

    @Test
    @DisplayName("截断前先把空白折叠成单空格（多行输出不让报告表格炸行）")
    void abbreviateCollapsesWhitespaceBeforeTruncating() {
        String messy = "支持\n\n退货 \t 政策";
        BaselineComparator.Comparison comparison =
                new BaselineComparator()
                        .compare(
                                report(
                                        "v3",
                                        List.of(
                                                scored(
                                                        "c-1",
                                                        "支持退货",
                                                        Map.of("accuracy", BigDecimal.ONE))),
                                        Map.of()),
                                report(
                                        "v4",
                                        List.of(
                                                scored(
                                                        "c-1",
                                                        messy,
                                                        Map.of("accuracy", new BigDecimal("0.5")))),
                                        Map.of()));

        assertThat(diffOf(comparison, "c-1").currentOutput()).isEqualTo("支持 退货 政策");
    }

    @Test
    @DisplayName("★ emoji 被 substring(0,120) 切开：留下孤立高位代理（钉住现状，注明后果）")
    void emojiSplitAt120LeavesLoneHighSurrogate() {
        // 「a」*119 + emoji（两个 char）共 121 个 char。截断按 char 而非码点切，
        // 于是第 120 个 char 是 emoji 的高位代理，低位代理被丢掉。
        // 这是当前实现的真实行为：JSON 序列化会把它转义成 \ud83d，渲染端显示为乱码方块。
        // 要修需要改成按码点截断，属于行为变更，不在补单测范围内——这里只把边界钉住。
        String baselineActual = "a".repeat(119) + "\uD83D\uDE00";
        BaselineComparator.Comparison comparison =
                new BaselineComparator()
                        .compare(
                                report(
                                        "v3",
                                        List.of(
                                                scored(
                                                        "c-1",
                                                        baselineActual,
                                                        Map.of("accuracy", BigDecimal.ONE))),
                                        Map.of()),
                                report(
                                        "v4",
                                        List.of(
                                                scored(
                                                        "c-1",
                                                        "支持退货",
                                                        Map.of("accuracy", new BigDecimal("0.5")))),
                                        Map.of()));

        String truncated = diffOf(comparison, "c-1").baselineOutput();
        assertThat(truncated).hasSize(123).endsWith("...");
        assertThat(Character.isHighSurrogate(truncated.charAt(119)))
                .as("★ 截断点落在代理对中间，末位是孤立高位代理")
                .isTrue();
        assertThat(Character.isLowSurrogate(truncated.charAt(120)))
                .as("低位代理已被丢掉，紧跟的是省略号而不是配对的低位代理")
                .isFalse();
    }

    @Test
    @DisplayName("★ 两个版本都没有指标分时，describe 走「完全一致」分支而不是空表")
    void describeExplainsNoMetricDeltas() {
        BaselineComparator.Comparison comparison =
                new BaselineComparator()
                        .compare(
                                report("v3", List.of(failed("c-1")), Map.of()),
                                report("v4", List.of(failed("c-1")), Map.of()));

        String text = comparison.describe();
        assertThat(text).contains("两个版本的所有指标完全一致");
        assertThat(text).as("成本按 4 位小数呈现，空报告也不该出现 null").contains("成本：0.0000 → 0.0000 CNY");
    }

    @Test
    @DisplayName("★ 指标变好时 describe / toMarkdown 都带正号，并标「↑变好」")
    void improvedMetricCarriesPlusSign() {
        BaselineComparator.Comparison comparison =
                new BaselineComparator()
                        .compare(
                                report(
                                        "v3",
                                        List.of(
                                                scored(
                                                        "c-1",
                                                        "答得一般",
                                                        Map.of("accuracy", new BigDecimal("0.5")))),
                                        Map.of("accuracy", new BigDecimal("0.5"))),
                                report(
                                        "v4",
                                        List.of(
                                                scored(
                                                        "c-1",
                                                        "答对了",
                                                        Map.of("accuracy", BigDecimal.ONE))),
                                        Map.of("accuracy", BigDecimal.ONE)));

        assertThat(comparison.improvedCount()).isEqualTo(1L);
        assertThat(comparison.deltaOf("accuracy").improved()).isTrue();
        assertThat(comparison.deltaOf("accuracy").delta()).isEqualByComparingTo("0.5000");
        assertThat(comparison.describe()).contains("+0.5000").contains("↑变好");
        assertThat(BaselineComparator.toMarkdown(comparison))
                .as("Markdown 表格里的正变化同样需要正号，否则「变好」看起来像没变")
                .contains("| +0.5000 |");
    }

    @Test
    @DisplayName("紧凑构造器对 null 兜底：集合为空、成本为 0，报告侧无需再判空")
    void compactConstructorsDefaultNulls() {
        BaselineComparator.Comparison comparison =
                new BaselineComparator.Comparison("v3", "v4", "ds", "v1", null, null, null, null);

        assertThat(comparison.metricDeltas()).isEmpty();
        assertThat(comparison.caseDiffs()).isEmpty();
        assertThat(comparison.baselineCost()).isEqualByComparingTo("0");
        assertThat(comparison.currentCost()).isEqualByComparingTo("0");
        assertThat(comparison.regressedCount()).isZero();

        BaselineComparator.CaseDiff diff =
                new BaselineComparator.CaseDiff("c-1", null, null, null, false, false, true);
        assertThat(diff.metricDeltas()).isEmpty();
        assertThat(diff.failed()).isTrue();
    }
}
