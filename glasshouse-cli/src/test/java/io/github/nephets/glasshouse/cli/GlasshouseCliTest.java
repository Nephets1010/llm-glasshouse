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

package io.github.nephets.glasshouse.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import picocli.CommandLine;

import io.github.nephets.glasshouse.cli.version.BuildVersion;
import io.github.nephets.glasshouse.starter.store.SpanRingBuffer;
import io.github.nephets.glasshouse.starter.store.SpanRingBuffer.SpanData;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLI 端到端测试（架构 ③T05 验收标准第 2 条 ★ 退出码契约）。
 *
 * <p>本类把「用户敲一条命令 → 进程退出码是多少」这件事变成可断言的行为， 因为<b>退出码就是 CI 门禁的全部契约</b>：数字错了，GitHub Action 就不会变红。
 */
class GlasshouseCliTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @TempDir Path tempDir;

    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void setUp() {
        originalOut = System.out;
        originalErr = System.err;
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    /**
     * 执行一条 CLI 命令。
     *
     * @param args 命令行参数
     * @return 退出码
     */
    private int run(String... args) {
        return new CommandLine(new GlasshouseCli()).execute(args);
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    /**
     * 造一份 JSONL span 文件：一次「planner → chat + 检索 + chat」的三步 Agent。
     *
     * @return 文件路径
     * @throws IOException 写文件失败
     */
    private Path writeAgentSpanFile() throws IOException {
        Instant start = Instant.parse("2026-10-09T12:00:00Z");
        SpanRingBuffer buffer = new SpanRingBuffer(100);

        buffer.append(
                span(
                        TRACE_ID,
                        "aaaaaaaaaaaaaaaaaaaaaaaa",
                        null,
                        "invoke_agent planner",
                        1900L,
                        null,
                        Map.of("swy.agent.id", "planner", "swy.tenant.id", "vip")));
        buffer.append(
                span(
                        TRACE_ID,
                        "bbbbbbbbbbbbbbbbbbbbbbbb",
                        "aaaaaaaaaaaaaaaaaaaaaaaa",
                        "chat deepseek-chat",
                        1500L,
                        0.008d,
                        Map.of(
                                "gen_ai.usage.input_tokens",
                                1200L,
                                "gen_ai.usage.output_tokens",
                                340L,
                                "gen_ai.provider.name",
                                "deepseek",
                                "gen_ai.request.model",
                                "deepseek-chat",
                                "swy.tenant.id",
                                "vip",
                                "swy.usage.source",
                                "VENDOR_REPORTED")));
        buffer.append(
                span(
                        TRACE_ID,
                        "cccccccccccccccccccccccc",
                        "aaaaaaaaaaaaaaaaaaaaaaaa",
                        "retrieval kb_lookup",
                        80L,
                        null,
                        Map.of()));
        buffer.append(
                span(
                        TRACE_ID,
                        "dddddddddddddddddddddddd",
                        "aaaaaaaaaaaaaaaaaaaaaaaa",
                        "execute_tool ticket_create",
                        320L,
                        null,
                        Map.of()));
        buffer.append(
                span(
                        TRACE_ID,
                        "eeeeeeeeeeeeeeeeeeeeeeee",
                        "aaaaaaaaaaaaaaaaaaaaaaaa",
                        "chat qwen-max",
                        900L,
                        0.021d,
                        Map.of(
                                "gen_ai.usage.input_tokens",
                                1200L,
                                "gen_ai.usage.output_tokens",
                                340L,
                                "gen_ai.provider.name",
                                "dashscope",
                                "gen_ai.request.model",
                                "qwen-max")));

        StringBuilder sb = new StringBuilder();
        for (SpanData s : buffer.all()) {
            sb.append(
                    MAPPER.writeValueAsString(
                            Map.of(
                                    "traceId", s.traceId(),
                                    "spanId", s.spanId(),
                                    "parentSpanId",
                                            s.parentSpanId() == null ? "" : s.parentSpanId(),
                                    "name", s.name(),
                                    "startTime", s.startTime().toString(),
                                    "endTime", s.endTime().toString(),
                                    "status", s.status(),
                                    "attributes", s.attributes())));
            sb.append('\n');
        }
        Path file = tempDir.resolve("spans.jsonl");
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        assertThat(start).isNotNull();
        return file;
    }

    private static SpanData span(
            String traceId,
            String spanId,
            String parentId,
            String name,
            long durationMs,
            Double cost,
            Map<String, Object> extra) {
        Instant start = Instant.parse("2026-10-09T12:00:00Z");
        Map<String, Object> attributes = new java.util.LinkedHashMap<>(extra);
        if (cost != null) {
            attributes.put("swy.cost.amount", cost);
        }
        return new SpanData(
                traceId,
                spanId,
                parentId,
                name,
                name.split(" ")[0],
                name.contains(" ") ? name.substring(name.indexOf(' ') + 1) : "",
                start,
                start.plusMillis(durationMs),
                attributes,
                "OK",
                null);
    }

    @Test
    @DisplayName("★ trace 输出同时含 slowestSpan= 与 totalCostCny=")
    void tracePrintsBothAnswers() throws IOException {
        Path file = writeAgentSpanFile();
        int code = run("trace", "--trace-id", TRACE_ID, "--cost", "--input", file.toString());

        String stdout = stdout();
        assertThat(code).as("trace 查询成功应返回 0").isZero();
        assertThat(stdout)
                .as("★ 架构 3T05 第 2 条：必须同时回答「慢在哪」与「花了多少」")
                .contains("slowestSpan=invoke_agent planner durationMs=1900")
                .contains("totalCostCny=0.0290")
                .contains("invoke_agent planner")
                .contains("chat deepseek-chat");
    }

    @Test
    @DisplayName("trace JSON 输出可被程序解析，含 slowestSpan 与 totalCostCny")
    void traceJsonIsMachineReadable() throws IOException {
        Path file = writeAgentSpanFile();
        int code = run("trace", "--trace-id", TRACE_ID, "--json", "--input", file.toString());

        JsonNode json = MAPPER.readTree(stdout());
        assertThat(code).isZero();
        assertThat(json.get("traceId").asText()).isEqualTo(TRACE_ID);
        assertThat(json.get("spanCount").asInt()).isEqualTo(5);
        assertThat(json.get("totalCostCny").asText()).as("JSON 里的成本要与树渲染一致").startsWith("0.029");
        assertThat(json.get("slowestSpan").get("name").asText()).isEqualTo("invoke_agent planner");
        assertThat(json.get("slowestSpan").get("durationMs").asLong()).isEqualTo(1900L);
        assertThat(json.get("tokens").get("input").asLong()).isEqualTo(2400L);
        assertThat(json.get("tree").asText()).contains("slowestSpan=").contains("totalCostCny=");
    }

    @Test
    @DisplayName("traceId 不存在 → exit 2，并列出可用 traceId")
    void unknownTraceReturnsTwo() throws IOException {
        Path file = writeAgentSpanFile();
        int code =
                run(
                        "trace",
                        "--trace-id",
                        "00000000000000000000000000000000",
                        "--input",
                        file.toString());

        assertThat(code)
                .as("★ 参数错误必须 exit 2，与「门禁不达标（1）」区分开")
                .isEqualTo(GlasshouseCli.EXIT_USAGE_ERROR)
                .isEqualTo(2);
        assertThat(stderr()).contains("未找到").contains(TRACE_ID);
    }

    @Test
    @DisplayName("trace 缺 --input 且无缓冲时给出可操作提示")
    void traceWithoutInputExplains() {
        int code = run("trace", "--trace-id", TRACE_ID);
        assertThat(code).isEqualTo(2);
        assertThat(stderr())
                .as("★ 错误信息要告诉用户下一步怎么做")
                .contains("--input")
                .contains("/_glasshouse/trace/");
    }

    @Test
    @DisplayName("trace 缺 --input 时三种取数方式逐行完整（锁住多行提示合并成一次日志调用）")
    void traceWithoutInputListsThreeWaysLineByLine() {
        int code = run("trace", "--trace-id", TRACE_ID);

        assertThat(code).isEqualTo(2);
        assertThat(stderr())
                .as("★ 导航提示合并成一次日志调用后，每一行仍要逐行原样出现")
                .contains("当前 CLI 进程内没有 span 数据。三种取数方式：")
                .contains("  1) 从 JSONL 文件查询：--input spans.jsonl")
                .contains("  2) 在运行中的应用里查：GET /_glasshouse/trace/" + TRACE_ID)
                .contains(
                        "  3) 直接 curl：curl localhost:8080/_glasshouse/trace/"
                                + TRACE_ID
                                + "?cost=true");
    }

    @Test
    @DisplayName("trace --last 取最近一条链路")
    void traceLastPicksLatest() throws IOException {
        Path file = writeAgentSpanFile();
        int code = run("trace", "--last", "--input", file.toString());
        assertThat(code).isZero();
        assertThat(stdout()).contains("traceId=" + TRACE_ID);
    }

    @Test
    @DisplayName("★ cost 按维度下钻：租户占比可读")
    void costDrillsDownByTenant() throws IOException {
        Path file = writeAgentSpanFile();
        int code = run("cost", "--by", "tenant", "--input", file.toString());

        String stdout = stdout();
        assertThat(code).as("cost 查询成功应返回 0；stderr=%s", stderr()).isZero();
        assertThat(stdout)
                .as("★ 五维归因：按租户下钻要能看到 vip")
                .contains("归因维度：租户")
                .as("★ 五维归因：按租户下钻必须能看到租户名与占比")
                .contains("vip")
                .contains("未标注")
                .contains("总成本");
    }

    @Test
    @DisplayName("cost 支持 json / csv 输出")
    void costSupportsMachineFormats() throws IOException {
        Path file = writeAgentSpanFile();

        assertThat(run("cost", "--by", "tenant", "--format", "json", "--input", file.toString()))
                .as("json 模式应成功；stderr=%s", stderr())
                .isZero();
        JsonNode json = MAPPER.readTree(stdout());
        assertThat(json.get("dimension").asText()).isEqualTo("tenant");
        assertThat(json.get("rows").isArray()).isTrue();
        assertThat(json.get("rows").size()).isPositive();
        assertThat(json.get("rows").get(0).get("value").asText())
                .as("★ JSON 下钻结果也要带租户名，下游才能直接画图")
                .isNotBlank();
        assertThat(json.get("totalCostCny").asDouble()).isPositive();

        assertThat(run("cost", "--by", "model", "--format", "csv", "--input", file.toString()))
                .isZero();
        assertThat(stdout()).as("CSV 必须有表头，下游才知道列的含义").contains("model");
        assertThat(stdout()).contains("deepseek-chat").contains("qwen-max");
    }

    @Test
    @DisplayName("cost 未知维度 → exit 2 并列出可用维度")
    void costUnknownDimensionReturnsTwo() throws IOException {
        Path file = writeAgentSpanFile();
        int code = run("cost", "--by", "not-a-dimension", "--input", file.toString());
        assertThat(code).isEqualTo(2);
        assertThat(stderr()).contains("未知维度").contains("tenant");
    }

    @Test
    @DisplayName("cost 缺 --input → exit 2 并指向 Web 端点")
    void costWithoutInputExplains() {
        int code = run("cost", "--by", "tenant");
        assertThat(code).isEqualTo(2);
        assertThat(stderr()).contains("--input").contains("/_glasshouse/cost");
    }

    @Test
    @DisplayName("★ prices show 列出内置价格表")
    void pricesShowListsEntries() {
        int code = run("prices", "show");
        String stdout = stdout();
        assertThat(code).isZero();
        assertThat(stdout)
                .as("★ 价格表必须覆盖八家供应商（README 支持矩阵的承诺）")
                .contains("openai")
                .contains("anthropic")
                .contains("dashscope")
                .contains("deepseek")
                .contains("zhipu")
                .contains("moonshot")
                .contains("doubao")
                .contains("ollama");
    }

    @Test
    @DisplayName("prices support 打印国产计量口径矩阵")
    void pricesSupportPrintsMatrix() {
        int code = run("prices", "support");
        String stdout = stdout();
        assertThat(code).isZero();
        assertThat(stdout)
                .contains("CnUsageNormalizer")
                .contains("dashscope")
                .contains("deepseek")
                .contains("zhipu")
                .contains("moonshot")
                .contains("doubao");
    }

    @Test
    @DisplayName("prices validate：合法文件通过、缺字段文件跳过并 exit 1")
    void pricesValidate() throws IOException {
        Path ok = tempDir.resolve("ok.yml");
        Files.writeString(
                ok,
                """
        currency: CNY
        entries:
          - providerId: deepseek
            modelId: deepseek-chat
            inputPricePerUnit: 0.0020
            outputPricePerUnit: 0.0008
            effectiveFrom: 2026-01-01
            source: OFFICIAL
        """,
                StandardCharsets.UTF_8);
        assertThat(run("prices", "validate", "--file", ok.toString())).as("合法价格表应通过").isZero();
        assertThat(stdout()).contains("校验通过");

        Path partial = tempDir.resolve("partial.yml");
        Files.writeString(
                partial,
                """
        currency: CNY
        entries:
          - providerId: good
            modelId: m1
            inputPricePerUnit: 0.001
            outputPricePerUnit: 0.002
            effectiveFrom: 2026-01-01
            source: OFFICIAL
          - providerId: no-price
            modelId: m2
            outputPricePerUnit: 0.002
            effectiveFrom: 2026-01-01
            source: OFFICIAL
        """,
                StandardCharsets.UTF_8);
        assertThat(run("prices", "validate", "--file", partial.toString()))
                .as("★ 单条坏数据不该让整表失效，但要 exit 1 让 CI 知道")
                .isEqualTo(1);
        assertThat(stdout()).contains("跳过条目").contains("no-price/m2");
    }

    @Test
    @DisplayName("prices reload 对比价格变更")
    void pricesReloadShowsDiff() throws IOException {
        Path file = tempDir.resolve("prices.yml");
        String base =
                """
        currency: CNY
        entries:
          - providerId: deepseek
            modelId: deepseek-chat
            inputPricePerUnit: 0.0020
            outputPricePerUnit: 0.0008
            effectiveFrom: 2026-01-01
            source: OFFICIAL
        """;
        Files.writeString(file, base, StandardCharsets.UTF_8);
        assertThat(run("prices", "reload", "--file", file.toString())).isZero();

        Files.writeString(
                file,
                base.replace("inputPricePerUnit: 0.0020", "inputPricePerUnit: 0.0025"),
                StandardCharsets.UTF_8);
        assertThat(run("prices", "reload", "--file", file.toString())).isZero();
        assertThat(stdout())
                .as("★ 调价后必须能看到「哪条变了、从多少到多少」")
                .contains("变更")
                .contains("deepseek/deepseek-chat");
    }

    @Test
    @DisplayName("prices validate 缺 --file → exit 2")
    void pricesValidateWithoutFile() {
        assertThat(run("prices", "validate")).isEqualTo(2);
        assertThat(stderr()).contains("--file");
    }

    @Test
    @DisplayName("根命令打印帮助，退出码 0")
    void rootPrintsHelp() {
        assertThat(run()).isZero();
        assertThat(stdout()).contains("trace").contains("cost").contains("eval").contains("prices");
    }

    @Test
    @DisplayName("未知子命令 → exit 2")
    void unknownSubcommandReturnsTwo() {
        assertThat(run("not-a-command")).isEqualTo(2);
    }

    @Test
    @DisplayName("版本信息可读，且不以 null 结尾")
    void versionIsAvailable() {
        assertThat(run("--version")).isZero();
        assertThat(stdout()).contains("llm-glasshouse").doesNotContain("null");
        assertThat(BuildVersion.version()).isNotBlank();
        assertThat(BuildVersion.describe()).startsWith("llm-glasshouse");
        assertThat(BuildVersion.FALLBACK_VERSION).isNotBlank();
    }

    @Test
    @DisplayName("★ eval list 列出两套内置评测集")
    void evalListShowsBuiltins() {
        int code = run("eval", "list");
        assertThat(code).isZero();
        assertThat(stdout())
                .as("★ 架构要求首版 35 条自撰用例（20 + 15）")
                .contains("customer-service-v1")
                .contains("credit-risk-v3");
    }

    @Test
    @DisplayName("★ eval run 无门禁时 exit 0 并写报告")
    void evalRunWithoutGatePasses() throws IOException {
        Path replay = writeReplayFile();
        Path outDir = tempDir.resolve("report");
        int code =
                run(
                        "eval",
                        "run",
                        "--dataset",
                        "customer-service-v1",
                        "--replay",
                        replay.toString(),
                        "--out",
                        outDir.toString());

        assertThat(code).as("未配置门禁 → exit 0（不该让 CI 无故变红）").isEqualTo(0);
        assertThat(stdout()).contains("未配置门禁");
        assertThat(outDir.resolve("eval-report.md")).exists();
        assertThat(outDir.resolve("eval-report.json")).exists();
        assertThat(Files.readString(outDir.resolve("eval-report.md"), StandardCharsets.UTF_8))
                .contains("## 结论");
    }

    @Test
    @DisplayName("★ eval run 门禁不达标 → exit 1（CI 变红的核心证明）")
    void evalRunFailingGateReturnsOne() throws IOException {
        Path replay = writeReplayFile();
        Path outDir = tempDir.resolve("report-fail");
        int code =
                run(
                        "eval", "run",
                        "--dataset", "customer-service-v1",
                        "--replay", replay.toString(),
                        "--gate", "accuracy>0.0001",
                        "--out", outDir.toString());

        assertThat(code).as("★ 这是 CI 门禁唯一依赖的信号：阈值不达标必须 exit 1").isEqualTo(1);
        assertThat(stdout()).as("★ 不达标的原因必须出现在标准输出里，CI 日志才能直接看到").contains("门禁未通过");
        assertThat(outDir.resolve("eval-report.md")).exists();
        String md = Files.readString(outDir.resolve("eval-report.md"), StandardCharsets.UTF_8);
        assertThat(md).contains("❌ 未通过");
    }

    @Test
    @DisplayName("★ eval run 门禁达标 → exit 0")
    void evalRunPassingGateReturnsZero() throws IOException {
        Path replay = writeReplayFile();
        int code =
                run(
                        "eval", "run",
                        "--dataset", "customer-service-v1",
                        "--replay", replay.toString(),
                        "--gate", "containment=1.0",
                        "--out", tempDir.resolve("r2").toString());
        assertThat(code).as("containment=1.0 是硬约束，达标即通过").isZero();
    }

    @Test
    @DisplayName("★ eval run 门禁表达式写错 → exit 2（与不达标区分）")
    void evalRunBadExpressionReturnsTwo() throws IOException {
        Path replay = writeReplayFile();
        int code =
                run(
                        "eval", "run",
                        "--dataset", "customer-service-v1",
                        "--replay", replay.toString(),
                        "--gate", "keywordRecall>>0.9",
                        "--out", tempDir.resolve("r3").toString());
        assertThat(code).as("★ 配置错误必须是 2，不能和「效果不达标（1）」混在一起").isEqualTo(2);
    }

    @Test
    @DisplayName("eval run 缺 --dataset → exit 2")
    void evalRunWithoutDatasetReturnsTwo() {
        assertThat(run("eval", "run")).isEqualTo(2);
        assertThat(stderr()).contains("--dataset");
    }

    @Test
    @DisplayName("eval run 无回放文件且未指定端点 → exit 2 并给出三种可选方案")
    void evalRunWithoutSourceReturnsTwo() {
        int code =
                run(
                        "eval",
                        "run",
                        "--dataset",
                        "customer-service-v1",
                        "--replay",
                        "no-such-file.jsonl");
        assertThat(code).isEqualTo(2);
        assertThat(stderr())
                .as("★ 找不到回放文件时要明确告诉用户路径，并列出可选方案")
                .contains("no-such-file.jsonl")
                .contains("回放");
    }

    @Test
    @DisplayName("★ eval run 回放文件不存在时报错可操作（先于数据集加载）")
    void evalRunErrorIsActionable() {
        int code = run("eval", "run", "--dataset", "no-such-dataset", "--replay", "x.jsonl");
        assertThat(code)
                .as("★ 回放文件读不了是配置错误，必须 exit 2 而不是 exit 1")
                .isEqualTo(GlasshouseCli.EXIT_USAGE_ERROR);
        assertThat(stderr()).as("★ 错误信息必须点明是哪个文件读不了").contains("x.jsonl").contains("回放");
    }

    @Test
    @DisplayName("ReplayChatModel：命中回放时零上游调用，未命中时明确报错")
    void replayChatModelBehaviour() throws Exception {
        Path replay = writeReplayFile();
        ReplayChatModel model = ReplayChatModel.fromFile(replay);
        assertThat(model.size()).isEqualTo(20);

        org.springframework.ai.chat.prompt.Prompt hit =
                new org.springframework.ai.chat.prompt.Prompt(
                        List.<org.springframework.ai.chat.messages.Message>of(
                                new org.springframework.ai.chat.messages.UserMessage(
                                        "[case:cs-001]退货政策是什么？")));
        assertThat(model.call(hit).getResult().getOutput().getText()).contains("退货");

        org.springframework.ai.chat.prompt.Prompt miss =
                new org.springframework.ai.chat.prompt.Prompt(
                        List.<org.springframework.ai.chat.messages.Message>of(
                                new org.springframework.ai.chat.messages.UserMessage(
                                        "[case:no-such]")));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> model.call(miss));
    }

    @Test
    @DisplayName("SpanJsonCodec：写与读字段名严格一致")
    void spanJsonCodecRoundTrip() throws Exception {
        Instant start = Instant.parse("2026-10-09T12:00:00Z");
        SpanData original =
                new SpanData(
                        TRACE_ID,
                        "bbbbbbbbbbbbbbbbbbbbbbbb",
                        "aaaaaaaaaaaaaaaaaaaaaaaa",
                        "chat deepseek-chat",
                        "chat",
                        "deepseek-chat",
                        start,
                        start.plusMillis(1500L),
                        Map.of("swy.cost.amount", 0.008d, "gen_ai.usage.input_tokens", 1200L),
                        "OK",
                        null);

        String json = SpanJsonCodec.toJson(original);
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = MAPPER.readValue(json, Map.class);
        SpanData restored = SpanJsonCodec.toSpanData(parsed);

        assertThat(restored.traceId()).isEqualTo(original.traceId());
        assertThat(restored.spanId()).isEqualTo(original.spanId());
        assertThat(restored.parentSpanId()).isEqualTo(original.parentSpanId());
        assertThat(restored.name()).isEqualTo(original.name());
        assertThat(restored.durationMillis()).isEqualTo(1500L);
        assertThat(restored.attributes()).containsEntry("swy.cost.amount", 0.008d);
    }

    /**
     * 造一份「全部命中」的回放文件（20 条），用于门禁测试。
     *
     * <p>回放文件里每条响应都<b>命中该用例的关键词且不含 forbidden 内容</b>， 因此 {@code containment} 必然是 1.0，门禁 {@code
     * containment=1.0} 必然通过， {@code keywordRecall>0.9999} 必然不通过——两者都能被稳定断言。
     *
     * @return 回放文件路径
     * @throws IOException 写文件失败
     */
    private Path writeReplayFile() throws IOException {
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        List<String> lines = new java.util.ArrayList<>();
        io.github.nephets.glasshouse.core.eval.JsonlDataset dataset =
                new io.github.nephets.glasshouse.eval.dataset.DatasetRegistry()
                        .load("customer-service-v1");
        for (io.github.nephets.glasshouse.core.eval.EvalCase c : dataset.cases()) {
            List<String> keywords = c.expectedList("contains");
            // 把该用例的全部关键词串成一句话：保证 keywordRecall = 1.0，且不含 forbidden 内容
            String answer = "根据相关规定：" + String.join("，", keywords) + "。具体请以订单页面标注为准。";
            java.util.Map<String, Object> record = new java.util.LinkedHashMap<>();
            record.put("caseId", c.id());
            record.put("content", answer);
            record.put("inputTokens", 1000);
            record.put("outputTokens", 500);
            lines.add(mapper.writeValueAsString(record));
        }
        Path file = tempDir.resolve("customer-service-v1.responses.jsonl");
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return file;
    }
}
