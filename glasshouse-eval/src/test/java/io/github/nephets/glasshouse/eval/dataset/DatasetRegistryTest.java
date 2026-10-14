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

package io.github.nephets.glasshouse.eval.dataset;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.github.nephets.glasshouse.core.eval.EvalCase;
import io.github.nephets.glasshouse.core.eval.JsonlDataset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 评测集加载与版本化测试。
 *
 * <p>重点验证架构 ③T04 验收标准第 5 条：<b>每条 case 必须含 id / input / expected / metadata 四字段</b>， 以及第 6 条：<b>每条
 * case 有 authored 或 source 出处</b>。
 */
class DatasetRegistryTest {

    @TempDir Path tempDir;

    private Path writeJsonl(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    @DisplayName("★ 内置 customer-service-v1 可加载，且每条 case 四字段齐全")
    void builtinCustomerServiceDataset() {
        JsonlDataset dataset = new DatasetRegistry().load("customer-service-v1");

        assertThat(dataset.size()).as("★ 内置中文客服评测集首版 20 条").isEqualTo(20);
        assertThat(dataset.hasUniqueIds()).isTrue();
        assertThat(dataset.version()).isNotBlank();

        for (EvalCase c : dataset.cases()) {
            assertThat(c.id()).as("case %s 的 id", c.id()).isNotBlank();
            assertThat(c.input()).as("case %s 的 input", c.id()).isNotEmpty();
            assertThat(c.expected()).as("case %s 的 expected", c.id()).isNotEmpty();
            assertThat(c.metadata()).as("case %s 的 metadata", c.id()).isNotEmpty();
            assertThat(c.provenance())
                    .as("★ case %s 必须有 authored 或 source 出处（禁止无出处数据）", c.id())
                    .isNotBlank();
            assertThat(c.inputText("question")).as("case %s 的问题应为中文", c.id()).isNotBlank();
        }
    }

    @Test
    @DisplayName("★ 内置 credit-risk-v3 可加载，15 条信贷风控用例")
    void builtinCreditRiskDataset() {
        JsonlDataset dataset = new DatasetRegistry().load("credit-risk-v3");

        assertThat(dataset.size()).as("★ 内置信贷风控评测集首版 15 条").isEqualTo(15);
        assertThat(dataset.hasUniqueIds()).isTrue();
        assertThat(dataset.scenarioDistribution()).as("覆盖准入 / 征信 / 反欺诈 / 成本测算等场景").isNotEmpty();
        dataset.cases()
                .forEach(
                        c -> {
                            assertThat(c.provenance()).as("case %s 出处", c.id()).isNotBlank();
                            assertThat(c.expected().containsKey("forbidden"))
                                    .as("★ 信贷用例必须写明 forbidden（禁答内容），否则幻觉检测失效：%s", c.id())
                                    .isTrue();
                        });
    }

    @Test
    @DisplayName("★ 两套内置评测集合计 35 条自撰用例")
    void builtinDatasetsTotal35() {
        DatasetRegistry registry = new DatasetRegistry();
        assertThat(registry.list())
                .as("★ 架构要求首版 35 条自撰中文用例")
                .containsExactlyInAnyOrder("customer-service-v1", "credit-risk-v3");
        assertThat(
                        registry.load("customer-service-v1").size()
                                + registry.load("credit-risk-v3").size())
                .isEqualTo(35);
    }

    @Test
    @DisplayName("加载时接受带 / 不带 .jsonl 后缀")
    void nameSuffixIsOptional() {
        DatasetRegistry registry = new DatasetRegistry();
        assertThat(registry.load("customer-service-v1").size())
                .isEqualTo(registry.load("customer-service-v1.jsonl").size());
        assertThat(registry.load("  customer-service-v1  ").size()).isEqualTo(20);
    }

    @Test
    @DisplayName("★ 缺 metadata 的用例被拒绝（防止无出处数据混入门禁）")
    void caseWithoutMetadataIsRejected() throws Exception {
        Path file =
                writeJsonl(
                        "bad.jsonl",
                        """
            {"id":"x-1","input":{"question":"q"},"expected":{"contains":["a"]}}
            """);
        JsonlDatasetParser parser = new JsonlDatasetParser();
        assertThatThrownBy(() -> parser.parseFile(file))
                .isInstanceOf(JsonlDatasetParser.DatasetParseException.class)
                .hasMessageContaining("metadata")
                .hasMessageContaining("authored");
    }

    @Test
    @DisplayName("★ 缺 id / input / expected 的用例被拒绝，且错误信息指明行号")
    void malformedCasesAreRejectedWithLineNumber() throws Exception {
        Path file =
                writeJsonl(
                        "bad2.jsonl",
                        """
            {"id":"ok-1","input":{"question":"q"},"expected":{"contains":["a"]},"metadata":{"authored":"t"}}
            {"input":{"question":"q"},"expected":{"contains":["a"]},"metadata":{"authored":"t"}}
            """);
        JsonlDatasetParser parser = new JsonlDatasetParser();
        assertThatThrownBy(() -> parser.parseFile(file))
                .isInstanceOf(JsonlDatasetParser.DatasetParseException.class)
                .hasMessageContaining("第 2 行")
                .hasMessageContaining("id");
    }

    @Test
    @DisplayName("重复 id 被拒绝（否则评测会重复计分）")
    void duplicateIdsAreRejected() throws Exception {
        Path file =
                writeJsonl(
                        "dup.jsonl",
                        """
            {"id":"d-1","input":{"question":"q"},"expected":{"contains":["a"]},"metadata":{"authored":"t"}}
            {"id":"d-1","input":{"question":"q"},"expected":{"contains":["a"]},"metadata":{"authored":"t"}}
            """);
        assertThatThrownBy(() -> new JsonlDatasetParser().parseFile(file))
                .isInstanceOf(JsonlDatasetParser.DatasetParseException.class)
                .hasMessageContaining("重复");
    }

    @Test
    @DisplayName("空内容 / 全注释 / 非 JSON 行")
    void emptyAndCommentLines() {
        JsonlDatasetParser parser = new JsonlDatasetParser();
        assertThatThrownBy(() -> parser.parse("", "x", "v1"))
                .isInstanceOf(JsonlDatasetParser.DatasetParseException.class);
        assertThatThrownBy(() -> parser.parse("// 只有注释\n# 也是注释\n", "x", "v1"))
                .isInstanceOf(JsonlDatasetParser.DatasetParseException.class)
                .hasMessageContaining("没有");

        String valid =
                """
        // 注释行会被跳过
        {"id":"a","input":{"question":"q"},"expected":{"contains":["x"]},"metadata":{"authored":"t"}}
        """;
        assertThat(parser.parse(valid, "x", "v1").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 版本标识：拿不到 git 时回退到内容指纹（仍是确定性的）")
    void versionFallsBackToContentFingerprint() throws Exception {
        Path file =
                writeJsonl(
                        "v.jsonl",
                        """
            {"id":"v-1","input":{"question":"q"},"expected":{"contains":["a"]},"metadata":{"authored":"t"}}
            """);
        String v1 =
                new JsonlDatasetParser(new DatasetVersionResolver(tempDir))
                        .parseFile(file)
                        .version();
        assertThat(v1).isNotBlank();

        Files.writeString(
                file,
                """
        {"id":"v-1","input":{"question":"改过了"},"expected":{"contains":["a"]},"metadata":{"authored":"t"}}
        """,
                StandardCharsets.UTF_8);
        String v2 =
                new JsonlDatasetParser(new DatasetVersionResolver(tempDir))
                        .parseFile(file)
                        .version();

        assertThat(v2).as("★ 内容变了版本标识必须跟着变，否则报告无法追溯到具体数据集内容").isNotEqualTo(v1);
    }

    @Test
    @DisplayName("找不到数据集时报可读错误")
    void missingDatasetGivesReadableError() {
        assertThatThrownBy(() -> new DatasetRegistry().load("no-such-dataset"))
                .isInstanceOf(JsonlDatasetParser.DatasetParseException.class)
                .hasMessageContaining("no-such-dataset");
        assertThatThrownBy(() -> new DatasetRegistry().load(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DatasetRegistry().load("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("从 classpath 加载内置数据集")
    void loadFromClasspath() {
        JsonlDataset dataset =
                new JsonlDatasetParser().parseClasspath("eval-datasets/customer-service-v1.jsonl");
        assertThat(dataset.name()).isEqualTo("customer-service-v1");
        assertThat(dataset.size()).isEqualTo(20);
        assertThatThrownBy(
                        () -> new JsonlDatasetParser().parseClasspath("eval-datasets/nope.jsonl"))
                .isInstanceOf(JsonlDatasetParser.DatasetParseException.class);
    }

    @Test
    @DisplayName("versionsOf 返回版本列表（首版只有当前版本，接口为 P1 预留）")
    void versionsOfReturnsCurrentVersion() {
        DatasetRegistry registry = new DatasetRegistry();
        List<String> versions = registry.versionsOf("customer-service-v1");
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0)).isEqualTo(registry.load("customer-service-v1").version());
    }

    @Test
    @DisplayName("缓存与清理")
    void cachingAndClear() {
        DatasetRegistry registry = new DatasetRegistry();
        JsonlDataset first = registry.load("customer-service-v1");
        assertThat(registry.load("customer-service-v1")).as("重复加载走缓存").isSameAs(first);
        registry.clearCache();
        assertThat(registry.load("customer-service-v1")).as("清缓存后重新加载").isNotSameAs(first);
        assertThat(registry.parser()).isNotNull();
    }

    @Test
    @DisplayName("数据集指纹随内容变化")
    void fingerprintChangesWithContent() throws Exception {
        String line1 =
                "{\"id\":\"a\",\"input\":{\"question\":\"q1\"},\"expected\":{\"contains\":[\"x\"]},\"metadata\":{\"authored\":\"t\"}}";
        String line2 =
                "{\"id\":\"a\",\"input\":{\"question\":\"q2\"},\"expected\":{\"contains\":[\"x\"]},\"metadata\":{\"authored\":\"t\"}}";
        JsonlDatasetParser parser = new JsonlDatasetParser();
        assertThat(parser.parse(line1, "ds", "v1").fingerprint())
                .isNotEqualTo(parser.parse(line2, "ds", "v1").fingerprint());
    }
}
