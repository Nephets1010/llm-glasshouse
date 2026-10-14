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

package io.github.nephets.glasshouse.eval.report;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.github.nephets.glasshouse.core.eval.EvalCase;
import io.github.nephets.glasshouse.core.eval.EvalConfig;
import io.github.nephets.glasshouse.core.eval.EvalReport;
import io.github.nephets.glasshouse.core.eval.EvalResult;
import io.github.nephets.glasshouse.core.eval.EvalRunner;
import io.github.nephets.glasshouse.core.eval.JsonlDataset;
import io.github.nephets.glasshouse.core.eval.Metric;
import io.github.nephets.glasshouse.core.usage.Usage;
import io.github.nephets.glasshouse.eval.gate.GateParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评测报告输出测试（PRD 仓库③ P0-6：不达标时写报告）。
 *
 * <p><b>为什么报告测试不可省略</b>：门禁变红时，使用者第一件事就是看报告。 报告缺字段、排版错乱、JSON 解析不了，都会让「门禁红了」变成「不知道该改什么」， 门禁就失去了意义。
 */
class EvalReportWriterTest {

    @TempDir Path tempDir;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EvalReport sampleReport() {
        List<EvalCase> cases =
                List.of(
                        EvalCase.of(
                                "cs-1",
                                Map.of("question", "退货政策是什么？"),
                                Map.of("contains", List.of("七天无理由退货"), "forbidden", List.of("不能退")),
                                "售后",
                                "easy",
                                true,
                                null),
                        EvalCase.of(
                                "cs-2",
                                Map.of("question", "发票怎么开？"),
                                Map.of(
                                        "contains",
                                        List.of("发票", "增值税"),
                                        "forbidden",
                                        List.of("不能开票")),
                                "售后",
                                "medium",
                                true,
                                null));
        Map<String, String> answers =
                Map.of(
                        "cs-1", "根据《售后服务政策》，签收 7 天内可申请七天无理由退货。",
                        "cs-2", "可以开具增值税发票，请提供抬头与税号。");

        return new EvalRunner(
                        c ->
                                EvalResult.success(
                                        c.id(),
                                        answers.get(c.id()),
                                        Usage.of(1000L, 500L),
                                        new BigDecimal("0.0035"),
                                        150L))
                .run(
                        new JsonlDataset("customer-service-v1", "a3f9b21c4e5d", cases, "报告测试数据集"),
                        EvalConfig.of("deepseek/deepseek-chat", "planner@v3"));
    }

    @Test
    @DisplayName("★ Markdown 报告包含结论、元信息、指标、门禁四段")
    void markdownHasAllSections() {
        EvalReport report = sampleReport();
        GateParser.Verdict verdict =
                GateParser.evaluate(report, "keywordRecall>0.9,containment=1.0");

        String md = EvalReportWriter.toMarkdown(report, verdict);

        assertThat(md)
                .as("★ 结论必须在最前面：门禁红时使用者只看第一段")
                .contains("# 评测报告：customer-service-v1")
                .contains("## 结论：✅ 通过")
                .contains("## 元信息")
                .contains("## 指标")
                .contains("## 门禁");

        assertThat(md)
                .as("★ 元信息必须能追溯到具体数据集版本与指纹")
                .contains("| 数据集 | customer-service-v1 |")
                .contains("| 数据集版本 | `a3f9b21c4e5d` |")
                .contains("| Prompt 版本 | planner@v3 |")
                .contains("| 模型 | deepseek/deepseek-chat |")
                .contains("| 数据集指纹 | `sha256:");
    }

    @Test
    @DisplayName("★ 门禁不达标时报告写明「哪条指标不达标、差多少」")
    void markdownExplainsFailure() {
        EvalReport report = sampleReport();
        // keywordRecall 实测 1.0000，阈值必须高于它才能真的触发失败
        // 本样本的 cost 得分是 0.9965（单次成本 0.0035 略高于默认预算 0.001*10），
        // 用它触发失败最贴近真实场景：不是「效果差」，而是「这次改动变贵了」
        GateParser.Verdict verdict = GateParser.evaluate(report, "cost>0.999");

        String md = EvalReportWriter.toMarkdown(report, verdict);
        assertThat(md)
                .contains("## 结论：❌ 未通过")
                .contains("门禁未通过")
                .contains("`cost > 0.999`")
                .contains("❌");
    }

    @Test
    @DisplayName("失败用例清单进报告（这是「怎么修」的入口）")
    void failedCasesAreListed() {
        EvalReport report =
                new EvalRunner(
                                c -> {
                                    if ("cs-2".equals(c.id())) {
                                        throw new IllegalStateException("上游 429 限流");
                                    }
                                    return EvalResult.success(
                                            c.id(), "支持退货", Usage.of(100L, 50L), null, 10L);
                                })
                        .run(
                                new JsonlDataset(
                                        "customer-service-v1",
                                        "v1",
                                        List.of(
                                                EvalCase.of(
                                                        "cs-1",
                                                        Map.of("question", "q1"),
                                                        Map.of("contains", List.of("退货")),
                                                        "售后",
                                                        "easy",
                                                        true,
                                                        null),
                                                EvalCase.of(
                                                        "cs-2",
                                                        Map.of("question", "q2"),
                                                        Map.of("contains", List.of("发票")),
                                                        "售后",
                                                        "easy",
                                                        true,
                                                        null)),
                                        null),
                                EvalConfig.of("mock/mock-chat", "v3"));

        String md = EvalReportWriter.toMarkdown(report, GateParser.evaluate(report, null));
        assertThat(md)
                .as("★ 失败用例必须列出 caseId 与错误原因，否则使用者无从下手")
                .contains("## 失败用例（1 条）")
                .contains("`cs-2`")
                .contains("429");
    }

    @Test
    @DisplayName("★ JSON 报告可被程序解析，且含完整溯源字段")
    void jsonIsMachineReadable() throws Exception {
        EvalReport report = sampleReport();
        GateParser.Verdict verdict = GateParser.evaluate(report, "keywordRecall>0.9");

        String json = EvalReportWriter.toJson(report, verdict);
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = MAPPER.readValue(json, Map.class);

        assertThat(parsed)
                .containsEntry("dataset", "customer-service-v1")
                .containsEntry("datasetVersion", "a3f9b21c4e5d")
                .containsEntry("promptVersion", "planner@v3")
                .containsEntry("model", "deepseek/deepseek-chat");
        assertThat((String) parsed.get("datasetFingerprint")).matches("^sha256:[0-9a-f]{64}$");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cases = (List<Map<String, Object>>) parsed.get("cases");
        assertThat(cases).hasSize(2);
        assertThat(cases.get(0))
                .containsKey("caseId")
                .containsKey("passed")
                .containsKey("scores")
                .containsKey("usage");

        @SuppressWarnings("unchecked")
        Map<String, Object> gate = (Map<String, Object>) parsed.get("gate");
        assertThat(gate).containsEntry("passed", Boolean.TRUE).containsKey("rules");
    }

    @Test
    @DisplayName("写出 Markdown 与 JSON 文件，自动创建父目录")
    void writesFiles() throws Exception {
        EvalReport report = sampleReport();
        GateParser.Verdict verdict = GateParser.evaluate(report, "keywordRecall>0.9");
        Path dir = tempDir.resolve("nested").resolve("report");

        int mdLen = EvalReportWriter.writeMarkdown(report, verdict, dir.resolve("eval-report.md"));
        int jsonLen = EvalReportWriter.writeJson(report, verdict, dir.resolve("eval-report.json"));
        int jsonlRows = EvalReportWriter.writeJsonl(report, dir.resolve("eval-report.jsonl"));

        assertThat(mdLen).isPositive();
        assertThat(jsonLen).isPositive();
        assertThat(jsonlRows).isEqualTo(2);
        assertThat(dir.resolve("eval-report.md")).exists();
        assertThat(Files.readString(dir.resolve("eval-report.md"), StandardCharsets.UTF_8))
                .contains("## 结论");
        for (String line :
                Files.readAllLines(dir.resolve("eval-report.jsonl"), StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                MAPPER.readTree(line);
            }
        }
    }

    @Test
    @DisplayName("无门禁配置时报告说明「未配置门禁」")
    void noGateIsExplained() {
        String md = EvalReportWriter.toMarkdown(sampleReport(), null);
        assertThat(md).as("★ 没有门禁判定时也必须有「结论」段，且说明如何开启门禁").contains("未配置门禁").contains("--gate");
    }

    @Test
    @DisplayName("全部用例失败时指标段落明确说明原因，不静默空白")
    void allFailedStillExplains() {
        EvalReport report =
                new EvalRunner(
                                c -> {
                                    throw new IllegalStateException("全部超时");
                                })
                        .run(
                                new JsonlDataset(
                                        "ds",
                                        "v1",
                                        List.of(
                                                EvalCase.of(
                                                        "f-1",
                                                        Map.of("question", "q"),
                                                        Map.of("contains", List.of("a")),
                                                        "售后",
                                                        "easy",
                                                        true,
                                                        null)),
                                        null),
                                EvalConfig.of("mock/mock-chat", "v3"));

        String md = EvalReportWriter.toMarkdown(report, GateParser.evaluate(report, null));
        assertThat(md).as("★ 全失败时要说明「没产生指标是因为全挂了」，而不是给一个空表").contains("无成功用例，未产生指标分数");
        assertThat(md).contains("## 失败用例（1 条）");
    }

    @Test
    @DisplayName("报告尾注说明可复现条件")
    void footerExplainsReproducibility() {
        String md = EvalReportWriter.toMarkdown(sampleReport(), null);
        assertThat(md).contains("llm-glasshouse").contains("温度为 0 时可完全复现");
    }

    @Test
    @DisplayName("Metric 名称常量与报告用的一致")
    void metricNamesMatchReport() {
        EvalReport report = sampleReport();
        for (String name :
                List.of(
                        Metric.NAME_ACCURACY,
                        Metric.NAME_KEYWORD_RECALL,
                        Metric.NAME_CONTAINMENT)) {
            if (report.scoreOf(name).signum() > 0) {
                assertThat(EvalReportWriter.toMarkdown(report, null)).contains(name);
            }
        }
    }
}
