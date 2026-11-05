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
import java.util.Map;

import io.github.nephets.glasshouse.core.eval.EvalCase;
import io.github.nephets.glasshouse.core.eval.JsonlDataset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JsonlDatasetParser} 错误路径与类型映射测试。
 *
 * <p>评测集是门禁的依据，解析器的错误必须「指到行」而不是「整份文件失败」。本类只钉住解析器自己 引入的语义：坏行如何报行号、各 JSON 节点类型如何映射成 {@link EvalCase}
 * 的字段类型、classpath 路径如何归一化、以及 UTF-8 中文与 emoji 往返后是否仍是同一串字符。
 *
 * <p>刻意<b>不测</b> {@code forEachLine} 与 {@code openClasspath}：生产代码零调用，属于死代码。 内置数据集的装载与「缺 metadata
 * 被拒」已在 {@code DatasetRegistryTest} 覆盖，这里不重复。
 */
class JsonlDatasetParserTest {

    private static final String ONE_CASE =
            "{\"id\":\"ok-1\",\"input\":{\"question\":\"q\"},"
                    + "\"expected\":{\"contains\":[\"x\"]},\"metadata\":{\"authored\":\"t\"}}";

    @TempDir Path tempDir;

    private Path write(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    @DisplayName("版本解析器为 null 时构造立即失败")
    void constructorRejectsNullResolver() {
        assertThatThrownBy(() -> new JsonlDatasetParser(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("versionResolver");
    }

    @Test
    @DisplayName("parseFile(null) 立即失败，不拖到 IO 阶段")
    void parseFileRejectsNullPath() {
        assertThatThrownBy(() -> new JsonlDatasetParser().parseFile(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("file");
    }

    @Test
    @DisplayName("★ 文件不存在时报绝对路径，使用者能直接定位")
    void missingFileIsReportedWithAbsolutePath() {
        Path missing = tempDir.resolve("no-such.jsonl");
        assertThatThrownBy(() -> new JsonlDatasetParser().parseFile(missing))
                .isInstanceOf(JsonlDatasetParser.DatasetParseException.class)
                .hasMessageContaining("不存在")
                .hasMessageContaining(missing.toAbsolutePath().toString());
    }

    @Test
    @DisplayName("★ 坏 JSON 的行号是 1-based 的原始行号，不是「有效行序号」")
    void badJsonReportsOriginalLineNumber() throws IOException {
        Path file = write("bad-json.jsonl", ONE_CASE + "\n{\"id\":");
        JsonlDatasetParser parser = new JsonlDatasetParser();

        // 第 1 行合法、第 2 行是残缺 JSON：错误必须指向第 2 行，否则使用者会去改第一条用例
        assertThatThrownBy(() -> parser.parseFile(file))
                .isInstanceOf(JsonlDatasetParser.DatasetParseException.class)
                .hasMessageContaining("第 2 行")
                .hasMessageContaining("不是合法 JSON");
    }

    @Test
    @DisplayName("★ 缺 id 字段的行按原始行号报错，且点名缺的是 id")
    void missingIdReportsLineNumber() {
        String content =
                ONE_CASE
                        + "\n"
                        + "{\"input\":{\"question\":\"q\"},\"expected\":{\"contains\":[\"x\"]},"
                        + "\"metadata\":{\"authored\":\"t\"}}";
        assertThatThrownBy(() -> new JsonlDatasetParser().parse(content, "ds", "v1"))
                .as("没有 id 的用例无法在报告里对齐，必须在解析期就指出是第几行")
                .isInstanceOf(JsonlDatasetParser.DatasetParseException.class)
                .hasMessageContaining("第 2 行")
                .hasMessageContaining("缺少 id");
    }

    @Test
    @DisplayName("★ 多行都坏时一次报全部行号，而不是只报第一条")
    void multipleBadLinesAreAllReported() {
        String content = "{\"id\":\n" + "// 注释行不算错误\n" + "[1,2,3]\n";
        assertThatThrownBy(() -> new JsonlDatasetParser().parse(content, "ds", "v1"))
                .isInstanceOf(JsonlDatasetParser.DatasetParseException.class)
                .hasMessageContaining("2 行无法解析")
                .hasMessageContaining("第 1 行")
                .hasMessageContaining("第 3 行");
    }

    @Test
    @DisplayName("顶层不是 JSON 对象（数组 / 标量）被拒绝")
    void nonObjectNodeIsRejected() {
        JsonlDatasetParser parser = new JsonlDatasetParser();
        assertThatThrownBy(() -> parser.parse("[1,2,3]", "ds", "v1"))
                .isInstanceOf(JsonlDatasetParser.DatasetParseException.class)
                .hasMessageContaining("不是 JSON 对象");
        assertThatThrownBy(() -> parser.parse("42", "ds", "v1"))
                .isInstanceOf(JsonlDatasetParser.DatasetParseException.class)
                .hasMessageContaining("不是 JSON 对象");
    }

    @Test
    @DisplayName("★ 空 input 对象被拒绝，错误串带上用例 id 便于定位")
    void emptyInputIsRejected() {
        String line =
                "{\"id\":\"e-1\",\"input\":{},\"expected\":{\"contains\":[\"x\"]},"
                        + "\"metadata\":{\"authored\":\"t\"}}";
        assertThatThrownBy(() -> new JsonlDatasetParser().parse(line, "ds", "v1"))
                .isInstanceOf(JsonlDatasetParser.DatasetParseException.class)
                .hasMessageContaining("e-1")
                .hasMessageContaining("input");
    }

    @Test
    @DisplayName("空 expected 对象被拒绝（没有期望就无法判分）")
    void emptyExpectedIsRejected() {
        String line =
                "{\"id\":\"e-2\",\"input\":{\"question\":\"q\"},\"expected\":{},"
                        + "\"metadata\":{\"authored\":\"t\"}}";
        assertThatThrownBy(() -> new JsonlDatasetParser().parse(line, "ds", "v1"))
                .isInstanceOf(JsonlDatasetParser.DatasetParseException.class)
                .hasMessageContaining("e-2")
                .hasMessageContaining("expected");
    }

    @Test
    @DisplayName("★ parseClasspath 接受前导斜杠，且数据集名取自文件名而不是整条路径")
    void classpathAcceptsLeadingSlash() {
        JsonlDataset withSlash =
                new JsonlDatasetParser().parseClasspath("/eval-datasets/customer-service-v1.jsonl");
        JsonlDataset withoutSlash =
                new JsonlDatasetParser().parseClasspath("eval-datasets/customer-service-v1.jsonl");

        assertThat(withSlash.name())
                .as("★ 前导斜杠不参与命名：name 必须是 customer-service-v1 而不是 /eval-datasets/...")
                .isEqualTo("customer-service-v1");
        assertThat(withSlash.size()).isEqualTo(withoutSlash.size());
    }

    @Test
    @DisplayName("classpath 上找不到资源时报可读错误（含原始入参）")
    void classpathMissingResourceIsReported() {
        assertThatThrownBy(
                        () ->
                                new JsonlDatasetParser()
                                        .parseClasspath("eval-datasets/no-such.jsonl"))
                .isInstanceOf(JsonlDatasetParser.DatasetParseException.class)
                .hasMessageContaining("classpath 上找不到")
                .hasMessageContaining("eval-datasets/no-such.jsonl");
    }

    @Test
    @DisplayName("★ 线程上下文类加载器为空时回退到本类加载器，仍能读到内置数据集")
    void classpathWorksWithoutContextClassLoader() {
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        thread.setContextClassLoader(null);
        try {
            JsonlDataset dataset =
                    new JsonlDatasetParser()
                            .parseClasspath("eval-datasets/customer-service-v1.jsonl");
            assertThat(dataset.size()).as("回退路径读到的必须还是完整数据集").isEqualTo(20);
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    @Test
    @DisplayName("★ unicode 转义与 emoji 往返：转义解成中文，代理对不被拆散")
    void unicodeEscapeAndEmojiSurviveParsing() {
        // 用字符串拼接构造转义序列，避免源码里出现反斜杠紧接 u 的写法（javac 会在词法前处理它）
        String backslash = "\\";
        String escaped =
                "{\"id\":\"u-1\",\"input\":{\"question\":\""
                        + backslash
                        + "u9000"
                        + backslash
                        + "u8d27 "
                        + backslash
                        + "uD83D"
                        + backslash
                        + "uDE00\"},"
                        + "\"expected\":{\"contains\":[\"退货\"]},"
                        + "\"metadata\":{\"authored\":\"t\",\"language\":\"zh-CN\"}}";

        EvalCase evalCase = new JsonlDatasetParser().parse(escaped, "ds", "v1").cases().get(0);
        String question = evalCase.inputText("question");

        assertThat(question).as("★ 转义必须被解码成中文，而不是原样保留六个字符").isEqualTo("退货 \uD83D\uDE00");
        assertThat(question.codePointCount(0, question.length()))
                .as("★ emoji 是代理对：按码点数应是 4（退、货、空格、emoji），按 char 数是 5")
                .isEqualTo(4);
        assertThat(evalCase.metadata()).containsEntry("language", "zh-CN");
    }

    @Test
    @DisplayName("★ input 的 JSON 标量类型映射：整数→Long、小数→Double、布尔、null、数组、嵌套对象")
    @SuppressWarnings("unchecked")
    void inputValuesKeepTheirJsonTypes() {
        String line =
                "{\"id\":\"t-1\",\"input\":{\"question\":\"q\",\"count\":42,\"ratio\":0.5,"
                        + "\"flag\":true,\"none\":null,\"tags\":[\"a\",\"b\"],"
                        + "\"nested\":{\"k\":\"v\"}},"
                        + "\"expected\":{\"contains\":[\"a\"]},\"metadata\":{\"authored\":\"t\"}}";

        Map<String, Object> input =
                new JsonlDatasetParser().parse(line, "ds", "v1").cases().get(0).input();

        assertThat(input.get("count"))
                .as("JSON 整数统一映射成 Long，避免下游按 int 取值时 ClassCastException")
                .isInstanceOf(Long.class)
                .isEqualTo(42L);
        assertThat(input.get("ratio")).isInstanceOf(Double.class).isEqualTo(0.5d);
        assertThat(input.get("flag")).isEqualTo(Boolean.TRUE);
        assertThat(input).containsEntry("none", null);
        assertThat((List<String>) input.get("tags")).containsExactly("a", "b");
        assertThat((Map<String, Object>) input.get("nested")).containsEntry("k", "v");
    }

    @Test
    @DisplayName("★ 超长坏行在错误串里被截断到 80 字符，不会把整行灌进日志")
    void longBadLineIsAbbreviated() {
        // 尾部标记落在第 80 个字符之后：正确截断后它不应出现在错误串里
        String line = "{" + "x".repeat(148) + "TAIL_MARKER";
        assertThatThrownBy(() -> new JsonlDatasetParser().parse(line, "ds", "v1"))
                .isInstanceOf(JsonlDatasetParser.DatasetParseException.class)
                .hasMessageContaining("...")
                .hasMessageNotContaining("TAIL_MARKER");
    }
}
