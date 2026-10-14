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

package io.github.nephets.glasshouse.core.pricing;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 价格表热更新测试（PRD 仓库③ P0-7：价格表支持外部配置文件热更新）。
 *
 * <p>验收标准（架构 ③T02 第 4 条）：改 yml 后 {@code reload()} 生效、无需改代码、无需重启。
 */
class PricingTableLoaderTest {

    @TempDir Path tempDir;

    private static final String VALID_YAML =
            """
      currency: CNY
      entries:
        - providerId: deepseek
          modelId: deepseek-chat
          inputPricePerUnit: 0.0020
          outputPricePerUnit: 0.0008
          cachedInputPricePerUnit: 0.00004
          effectiveFrom: 2026-01-01
          source: OFFICIAL
        - providerId: dashscope
          modelId: qwen-max
          inputPricePerUnit: 0.0200
          outputPricePerUnit: 0.0600
          reasoningPricePerUnit: 0.0200
          effectiveFrom: "2026-03-01"
          source: OFFICIAL
      """;

    private Path writeYaml(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    @DisplayName("★ 解析有效价格表：字段完整映射为 PriceEntry")
    void parseValidYaml() throws Exception {
        PricingTableLoader loader = new PricingTableLoader();
        PricingTableLoader.LoadResult result = loader.parse(VALID_YAML, "test");

        assertThat(result.hasSkipped()).as("有效文件不应有跳过条目: %s", result.skipped()).isFalse();
        assertThat(result.entries()).hasSize(2);

        PriceEntry deepseek = result.entries().get(0);
        assertThat(deepseek.providerId()).isEqualTo("deepseek");
        assertThat(deepseek.modelId()).isEqualTo("deepseek-chat");
        assertThat(deepseek.inputPricePerUnit()).isEqualByComparingTo("0.0020");
        assertThat(deepseek.outputPricePerUnit()).isEqualByComparingTo("0.0008");
        assertThat(deepseek.cachedInputPricePerUnit()).isEqualByComparingTo("0.00004");
        assertThat(deepseek.reasoningPricePerUnit()).isNull();
        assertThat(deepseek.effectiveFrom()).isEqualTo(LocalDate.parse("2026-01-01"));
        assertThat(deepseek.source()).isEqualTo(PriceSource.OFFICIAL);
        assertThat(deepseek.unit()).isEqualTo(PriceUnit.PER_1K_TOKENS);

        PriceEntry qwen = result.entries().get(1);
        assertThat(qwen.reasoningPricePerUnit()).isEqualByComparingTo("0.0200");
        assertThat(qwen.effectiveFrom())
                .as("★ 带引号的日期必须与不带引号的解析结果一致（SnakeYAML 行为差异）")
                .isEqualTo(LocalDate.parse("2026-03-01"));
        assertThat(result.location()).isEqualTo("test");
        assertThat(result.summary()).contains("loaded=2").contains("skipped=0");
    }

    @Test
    @DisplayName("★ 未加引号的 effectiveFrom 也能解析（SnakeYAML 会给 java.util.Date）")
    void unquotedDateIsParsed() throws Exception {
        PricingTableLoader loader = new PricingTableLoader();
        PricingTableLoader.LoadResult result =
                loader.parse(
                        """
            currency: CNY
            entries:
              - providerId: openai
                modelId: gpt-4o-mini
                inputPricePerUnit: 0.0011
                outputPricePerUnit: 0.0043
                effectiveFrom: 2026-01-01
                source: OFFICIAL
            """,
                        "unquoted");
        assertThat(result.entries())
                .as("SnakeYAML 把未加引号的 ISO 日期解析成 java.util.Date，必须显式处理")
                .hasSize(1);
        assertThat(result.entries().get(0).effectiveFrom())
                .isEqualTo(LocalDate.parse("2026-01-01"));
    }

    @Test
    @DisplayName("★ 热更新：改文件后 reload() 生效，无需改代码、无需重启")
    void hotReloadPicksUpChanges() throws Exception {
        Path file = writeYaml("prices.yml", VALID_YAML);
        PricingTableLoader loader = new PricingTableLoader();
        PricingTable table = new PricingTable();

        loader.reload(table, file);
        assertThat(table.getOrNull("deepseek", "deepseek-chat")).isNotNull();
        assertThat(table.getOrNull("deepseek", "deepseek-chat").inputPricePerUnit())
                .isEqualByComparingTo("0.0020");
        int sizeBefore = table.size();

        // 运维调价：只改 YAML，不动代码
        Files.writeString(
                file,
                VALID_YAML.replace("inputPricePerUnit: 0.0020", "inputPricePerUnit: 0.0030"),
                StandardCharsets.UTF_8);

        loader.reload(table, file);
        assertThat(table.getOrNull("deepseek", "deepseek-chat").inputPricePerUnit())
                .as("★ 改 YAML 后 reload 必须生效")
                .isEqualByComparingTo("0.0030");
        assertThat(table.size()).isEqualTo(sizeBefore);
    }

    @Test
    @DisplayName("reload 替换是整体的：被删除的条目不再存在")
    void reloadReplacesAll() throws Exception {
        Path file = writeYaml("p.yml", VALID_YAML);
        PricingTableLoader loader = new PricingTableLoader();
        PricingTable table = new PricingTable();
        loader.reload(table, file);
        assertThat(table.size()).isEqualTo(2);

        Files.writeString(
                file,
                """
        currency: CNY
        entries:
          - providerId: zhipu
            modelId: glm-4-air
            inputPricePerUnit: 0.0010
            outputPricePerUnit: 0.0010
            effectiveFrom: 2026-01-01
            source: OFFICIAL
        """,
                StandardCharsets.UTF_8);
        loader.reload(table, file);
        assertThat(table.size()).as("旧条目应被整体替换掉").isEqualTo(1);
        assertThat(table.getOrNull("deepseek", "deepseek-chat")).isNull();
        assertThat(table.getOrNull("zhipu", "glm-4-air")).isNotNull();
    }

    @Test
    @DisplayName("★ 单条坏数据被跳过，其余条目照常生效（不让一条敲错导致全表失效）")
    void badEntryIsSkippedNotFatal() throws Exception {
        PricingTableLoader loader = new PricingTableLoader();
        PricingTableLoader.LoadResult result =
                loader.parse(
                        """
            currency: CNY
            entries:
              - providerId: good-one
                modelId: m1
                inputPricePerUnit: 0.001
                outputPricePerUnit: 0.002
                effectiveFrom: 2026-01-01
                source: OFFICIAL
              - providerId: missing-price
                modelId: m2
                outputPricePerUnit: 0.002
                effectiveFrom: 2026-01-01
                source: OFFICIAL
              - providerId: bad-date
                modelId: m3
                inputPricePerUnit: 0.001
                outputPricePerUnit: 0.002
                effectiveFrom: "not-a-date"
                source: OFFICIAL
              - providerId: good-two
                modelId: m4
                inputPricePerUnit: 0.003
                outputPricePerUnit: 0.004
                effectiveFrom: 2026-01-01
                source: ESTIMATED
            """,
                        "mixed");
        assertThat(result.entries()).hasSize(2);
        assertThat(result.hasSkipped()).isTrue();
        assertThat(result.skipped()).hasSize(2);
        assertThat(String.join(" ", result.skipped()))
                .as("★ 跳过信息必须带 providerId/modelId，运维才知道是哪条模型的价没生效")
                .contains("entries[1]")
                .contains("missing-price/m2")
                .contains("entries[2]")
                .contains("bad-date/m3");
        assertThat(result.summary()).contains("skipped=2");
    }

    @Test
    @DisplayName("各类畸形输入的处理")
    void malformedInputs() {
        PricingTableLoader loader = new PricingTableLoader();

        PricingTableLoader.LoadResult nullContent = loader.parse(null, "x");
        assertThat(nullContent.entries()).isEmpty();
        assertThat(nullContent.hasSkipped()).isFalse();

        assertThat(loader.parse("   ", "x").entries()).isEmpty();

        PricingTableLoader.LoadResult notMap = loader.parse("- a\n- b", "x");
        assertThat(notMap.hasSkipped()).isTrue();

        PricingTableLoader.LoadResult badEntries = loader.parse("entries: not-a-list", "x");
        assertThat(badEntries.hasSkipped()).isTrue();

        // entries 是列表但元素不是映射
        assertThat(loader.parse("entries:\n  - just-a-string", "x").hasSkipped()).isTrue();
    }

    @Test
    @DisplayName("币种非 CNY 的条目被跳过（三仓统一 CNY）")
    void nonCnyEntrySkipped() {
        PricingTableLoader.LoadResult result =
                new PricingTableLoader()
                        .parse(
                                """
                entries:
                  - providerId: x
                    modelId: y
                    currency: USD
                    inputPricePerUnit: 0.001
                    outputPricePerUnit: 0.002
                    effectiveFrom: 2026-01-01
                    source: OFFICIAL
                """,
                                "usd");
        assertThat(result.hasSkipped()).isTrue();
        assertThat(result.entries()).isEmpty();
    }

    @Test
    @DisplayName("unit 与 source 的显式取值")
    void unitAndSourceParsing() {
        PricingTableLoader.LoadResult result =
                new PricingTableLoader()
                        .parse(
                                """
                entries:
                  - providerId: x
                    modelId: y
                    unit: per_1k_tokens
                    inputPricePerUnit: 0.001
                    outputPricePerUnit: 0.002
                    effectiveFrom: 2026-01-01
                    source: estimated
                """,
                                "s");
        assertThat(result.entries()).hasSize(1);
        assertThat(result.entries().get(0).unit()).isEqualTo(PriceUnit.PER_1K_TOKENS);
        assertThat(result.entries().get(0).source())
                .as("source / unit 大小写不敏感（运维手写 YAML 常用小写）")
                .isEqualTo(PriceSource.ESTIMATED);
    }

    @Test
    @DisplayName("缺 effectiveFrom / providerId / modelId 的条目被跳过")
    void missingRequiredFields() {
        PricingTableLoader loader = new PricingTableLoader();
        assertThat(
                        loader.parse(
                                        "entries:\n  - providerId: x\n    modelId: y\n    inputPricePerUnit: 0.1\n    outputPricePerUnit: 0.1\n    source: OFFICIAL",
                                        "no-date")
                                .hasSkipped())
                .isTrue();
        assertThat(
                        loader.parse(
                                        "entries:\n  - modelId: y\n    inputPricePerUnit: 0.1\n    outputPricePerUnit: 0.1\n    effectiveFrom: 2026-01-01\n    source: OFFICIAL",
                                        "no-provider")
                                .hasSkipped())
                .isTrue();
        assertThat(
                        loader.parse(
                                        "entries:\n  - providerId: x\n    inputPricePerUnit: 0.1\n    outputPricePerUnit: 0.1\n    effectiveFrom: 2026-01-01\n    source: OFFICIAL",
                                        "no-model")
                                .hasSkipped())
                .isTrue();
    }

    @Test
    @DisplayName("非法 source 取值被跳过")
    void invalidSourceSkipped() {
        assertThat(
                        new PricingTableLoader()
                                .parse(
                                        "entries:\n  - providerId: x\n    modelId: y\n    inputPricePerUnit: 0.1\n    outputPricePerUnit: 0.1\n    effectiveFrom: 2026-01-01\n    source: GUESSED",
                                        "bad-source")
                                .hasSkipped())
                .isTrue();
    }

    @Test
    @DisplayName("loadFromFile / loadFromClasspath")
    void loadFromSources() throws Exception {
        Path file = writeYaml("disk.yml", VALID_YAML);
        PricingTableLoader loader = new PricingTableLoader();

        assertThat(loader.loadFromFile(file).entries()).hasSize(2);
        assertThat(loader.lastLoadedLocation()).contains("disk.yml");

        assertThat(loader.loadFromClasspath().entries())
                .as("内置价格表必须可从 classpath 加载")
                .hasSizeGreaterThanOrEqualTo(15);

        assertThatThrownBy(() -> loader.loadFromFile(tempDir.resolve("nope.yml")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不存在");
        assertThatThrownBy(() -> loader.loadFromClasspath("prices/nope.yml"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> loader.loadFromFile(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("重复 key 的多条价格：按生效日取最新一条")
    void multipleEntriesPerKeyUseLatestEffectiveDate() {
        PricingTable table = new PricingTable();
        table.replaceAll(
                java.util.List.of(
                        entry("deepseek", "deepseek-chat", "0.0010", "2026-01-01"),
                        entry("deepseek", "deepseek-chat", "0.0020", "2026-06-01")));

        assertThat(table.lookup("deepseek", "deepseek-chat").orElseThrow().inputPricePerUnit())
                .as("今天应取最新生效的那条")
                .isEqualByComparingTo("0.0020");
        assertThat(
                        table.lookup("deepseek", "deepseek-chat", LocalDate.parse("2026-03-01"))
                                .orElseThrow()
                                .inputPricePerUnit())
                .as("按历史日期查询应取当时生效的那条——否则历史成本会随调价漂移，无法审计")
                .isEqualByComparingTo("0.0010");
        assertThat(table.lookup("deepseek", "deepseek-chat", LocalDate.parse("2025-01-01")))
                .as("生效日之前没有条目")
                .isEmpty();
    }

    @Test
    @DisplayName("PricingTable：all / size / isEmpty / describe / replaceAll 计数")
    void pricingTableBasics() {
        PricingTable table = new PricingTable();
        assertThat(table.isEmpty()).isTrue();
        assertThat(table.size()).isZero();
        assertThat(table.describe()).contains("entries=0");

        assertThat(table.replaceAll(java.util.List.of(entry("a", "m", "0.1", "2026-01-01"))))
                .isZero();
        assertThat(table.size()).isEqualTo(1);
        assertThat(table.all()).hasSize(1);
        assertThat(table.replaceAll(java.util.List.of(entry("b", "m", "0.2", "2026-01-01"))))
                .as("replaceAll 返回被替换掉的旧条目数")
                .isEqualTo(1);

        assertThat(table.replaceAll(null)).isEqualTo(1);
        table.replaceAll(java.util.Arrays.asList(entry("a", "m", "0.1", "2026-01-01"), null));
        assertThat(table.size()).as("null 条目被忽略").isEqualTo(1);
    }

    @Test
    @DisplayName("PricingTable：空 key 与大小写")
    void pricingTableKeyEdgeCases() {
        PricingTable table = new PricingTable();
        // providerId 只做 trim + 非空校验（契约规定取值全小写，由调用方保证），
        // modelId 保留供应商原始名（可能带日期后缀）
        assertThatThrownBy(() -> entry("  ", "m", "0.1", "2026-01-01"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entry("p", " ", "0.1", "2026-01-01"))
                .isInstanceOf(IllegalArgumentException.class);

        table.replaceAll(
                java.util.List.of(entry(" deepseek ", "deepseek-chat", "0.002", "2026-01-01")));
        assertThat(table.all().get(0).providerId())
                .as("providerId 前后空白被 trim")
                .isEqualTo("deepseek");
        assertThat(table.lookup(null, "m")).isEmpty();
        assertThat(table.lookup("p", null)).isEmpty();
        assertThat(table.getOrNull("deepseek", "unknown-model")).isNull();
    }

    @Test
    @DisplayName("★ lookupFuzzy：模型名带日期后缀/部署后缀也能查到价格")
    void fuzzyLookup() {
        PricingTable table = new PricingTable();
        table.replaceAll(java.util.List.of(entry("dashscope", "qwen-max", "0.02", "2026-01-01")));

        assertThat(table.lookupFuzzy("dashscope", "qwen-max")).isPresent();
        assertThat(table.lookupFuzzy("dashscope", "qwen-max-2025-01-25"))
                .as("真实模型名常带日期后缀，查不到价格会让成本静默记 0")
                .isPresent();
        assertThat(table.lookupFuzzy("dashscope", "qwen-max@company").isPresent())
                .as("OpenAI 兼容的企业部署名带 @ 后缀")
                .isTrue();
        assertThat(table.lookupFuzzy("openai", "gpt-4o")).as("不同供应商不串味").isEmpty();
        assertThat(table.lookupFuzzy(null, "x")).isEmpty();
        assertThat(table.lookupFuzzy("dashscope", null)).isEmpty();

        // 未生效的条目不应被模糊匹配选中
        PricingTable future = new PricingTable();
        future.replaceAll(java.util.List.of(entry("dashscope", "qwen-max", "0.02", "2099-01-01")));
        assertThat(future.lookupFuzzy("dashscope", "qwen-max")).as("生效日在未来的条目不应命中").isEmpty();
    }

    @Test
    @DisplayName("LoadResult：不可变拷贝与 hasSkipped")
    void loadResultValueSemantics() {
        PricingTableLoader.LoadResult empty = new PricingTableLoader.LoadResult(null, null, null);
        assertThat(empty.entries()).isEmpty();
        assertThat(empty.skipped()).isEmpty();
        assertThat(empty.location()).isEqualTo("(unknown)");
        assertThat(empty.hasSkipped()).isFalse();
    }

    @Test
    @DisplayName("CostCalculator.format 展示精度")
    void costFormatting() {
        CostCalculator calc = CostCalculator.getInstance();
        assertThat(calc.format(new BigDecimal("0.123456789"))).isEqualTo("0.1235");
        assertThat(calc.format(BigDecimal.ZERO)).isEqualTo("0.0000");
        assertThat(calc.format(null)).isEqualTo("0.0000");
        assertThat(calc.format(new BigDecimal("12.5"))).isEqualTo("12.5000");
        assertThat(CostCalculator.getInstance()).isSameAs(calc);
    }

    /**
     * 构造一个价格条目。
     *
     * @param provider 供应商标识
     * @param model 模型标识
     * @param input 输入单价
     * @param effectiveFrom 生效日
     * @return 价格条目
     */
    private static PriceEntry entry(
            String provider, String model, String input, String effectiveFrom) {
        return new PriceEntry(
                provider,
                model,
                "CNY",
                PriceUnit.PER_1K_TOKENS,
                new BigDecimal(input),
                new BigDecimal(input),
                null,
                null,
                LocalDate.parse(effectiveFrom),
                PriceSource.OFFICIAL);
    }
}
