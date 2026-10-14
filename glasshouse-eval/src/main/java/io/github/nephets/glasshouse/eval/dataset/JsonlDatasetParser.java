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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.nephets.glasshouse.core.eval.EvalCase;
import io.github.nephets.glasshouse.core.eval.JsonlDataset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSONL 评测集解析器。
 *
 * <p><b>为什么是 JSONL 而不是 JSON 数组</b>：
 *
 * <ul>
 *   <li><b>diff 友好</b>：改一条用例只产生一行 diff，PR review 能看清改了哪条
 *   <li><b>可流式读取</b>：10 万条用例不需要一次性载入内存
 *   <li><b>行级错误定位</b>：坏数据能精确定位到第几行，而不是「整个文件解析失败」
 * </ul>
 *
 * <p><b>错误策略</b>：单行解析失败时抛 {@link DatasetParseException} 并指明行号， 不静默跳过——评测集是门禁的依据，静默跳过会让「实际跑了 14
 * 条」被当成「15 条都通过」， 这是最危险的失败模式。
 */
public final class JsonlDatasetParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DatasetVersionResolver versionResolver;

    /** 用默认版本解析器构造。 */
    public JsonlDatasetParser() {
        this(new DatasetVersionResolver());
    }

    /**
     * 用指定版本解析器构造。
     *
     * @param versionResolver 版本解析器，不可为 null
     */
    public JsonlDatasetParser(DatasetVersionResolver versionResolver) {
        if (versionResolver == null) {
            throw new NullPointerException("versionResolver 不能为 null");
        }
        this.versionResolver = versionResolver;
    }

    /**
     * 从 JSONL 文本解析评测集。
     *
     * @param content JSONL 内容，可为 null
     * @param datasetName 数据集名称
     * @param version 版本标识；为空时用 {@code unknown}
     * @return 评测集
     * @throws DatasetParseException 内容为空、无有效用例或某行格式错误
     */
    public JsonlDataset parse(String content, String datasetName, String version) {
        if (content == null || content.isBlank()) {
            throw new DatasetParseException("评测集内容为空: " + datasetName);
        }
        List<EvalCase> cases = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String[] lines = content.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) {
                continue;
            }
            try {
                cases.add(parseCase(line));
            } catch (RuntimeException e) {
                errors.add("第 " + (i + 1) + " 行: " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw new DatasetParseException(
                    "评测集 "
                            + datasetName
                            + " 有 "
                            + errors.size()
                            + " 行无法解析：\n  "
                            + String.join("\n  ", errors));
        }
        if (cases.isEmpty()) {
            throw new DatasetParseException("评测集 " + datasetName + " 没有任何有效用例");
        }
        JsonlDataset dataset = new JsonlDataset(datasetName, version, cases, null);
        if (!dataset.hasUniqueIds()) {
            throw new DatasetParseException("评测集 " + datasetName + " 存在重复的用例 id");
        }
        return dataset;
    }

    /**
     * 从文件系统读取并解析。
     *
     * @param file JSONL 文件路径，不可为 null
     * @return 评测集；版本取自 git commit sha
     * @throws DatasetParseException 文件不存在或解析失败
     */
    public JsonlDataset parseFile(Path file) {
        if (file == null) {
            throw new NullPointerException("file 不能为 null");
        }
        if (!Files.isRegularFile(file)) {
            throw new DatasetParseException("评测集文件不存在: " + file.toAbsolutePath());
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String name = stripExtension(file.getFileName().toString());
            return parse(content, name, versionResolver.resolveFor(file));
        } catch (IOException e) {
            throw new UncheckedIOException("读取评测集失败: " + file.toAbsolutePath(), e);
        }
    }

    /**
     * 从 classpath 资源读取并解析。
     *
     * @param resourcePath 资源路径，如 {@code eval-datasets/customer-service-v1.jsonl}
     * @return 评测集
     * @throws DatasetParseException 资源不存在或解析失败
     */
    public JsonlDataset parseClasspath(String resourcePath) {
        if (resourcePath == null) {
            throw new NullPointerException("resourcePath 不能为 null");
        }
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = JsonlDatasetParser.class.getClassLoader();
        }
        String normalized = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        try (InputStream in = loader.getResourceAsStream(normalized)) {
            if (in == null) {
                throw new DatasetParseException("classpath 上找不到评测集: " + resourcePath);
            }
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String name = stripExtension(normalized.substring(normalized.lastIndexOf('/') + 1));
            return parse(content, name, versionResolver.resolveForResource(normalized));
        } catch (IOException e) {
            throw new UncheckedIOException("读取评测集失败: " + resourcePath, e);
        }
    }

    /**
     * 逐行流式读取评测集（10 万条场景用）。
     *
     * @param reader 行读取器，不可为 null
     * @param datasetName 数据集名称
     * @param caseConsumer 单条用例消费者
     */
    public void forEachLine(BufferedReader reader, String datasetName, CaseConsumer caseConsumer) {
        try {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("//")) {
                    continue;
                }
                try {
                    caseConsumer.accept(parseCase(trimmed));
                } catch (RuntimeException e) {
                    throw new DatasetParseException(
                            "评测集 " + datasetName + " 第 " + lineNo + " 行解析失败: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取评测集失败: " + datasetName, e);
        }
    }

    /**
     * 从 classpath 资源打开行读取器。
     *
     * @param resourcePath 资源路径
     * @return 行读取器；调用方负责关闭
     * @throws DatasetParseException 资源不存在
     */
    public BufferedReader openClasspath(String resourcePath) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = JsonlDatasetParser.class.getClassLoader();
        }
        String normalized = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        InputStream in = loader.getResourceAsStream(normalized);
        if (in == null) {
            throw new DatasetParseException("classpath 上找不到评测集: " + resourcePath);
        }
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    /**
     * 解析单行 JSON。
     *
     * @param line JSON 文本
     * @return 用例
     */
    private static EvalCase parseCase(String line) {
        JsonNode node;
        try {
            node = MAPPER.readTree(line);
        } catch (JsonProcessingException e) {
            // 只收窄到 JSON 解析异常：其余异常说明是代码 bug，让它照原样冒出去，
            // 而不是被笼统地改写成「不是合法 JSON」误导使用者去改数据集。
            throw new IllegalArgumentException("不是合法 JSON: " + abbreviate(line), e);
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("不是 JSON 对象: " + abbreviate(line));
        }
        String id = text(node, "id");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("缺少 id 字段: " + abbreviate(line));
        }
        JsonNode inputNode = node.get("input");
        JsonNode expectedNode = node.get("expected");
        JsonNode metadataNode = node.get("metadata");
        if (inputNode == null || !inputNode.isObject() || inputNode.isEmpty()) {
            throw new IllegalArgumentException("用例 " + id + " 缺少非空的 input 对象");
        }
        if (expectedNode == null || !expectedNode.isObject() || expectedNode.isEmpty()) {
            throw new IllegalArgumentException("用例 " + id + " 缺少非空的 expected 对象");
        }
        if (metadataNode == null || !metadataNode.isObject()) {
            throw new IllegalArgumentException(
                    "用例 " + id + " 缺少 metadata 对象（必须含 authored 或 source 标注）");
        }
        return new EvalCase(id, toMap(inputNode), toMap(expectedNode), toStringMap(metadataNode));
    }

    private static Map<String, Object> toMap(JsonNode node) {
        Map<String, Object> result = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> e = fields.next();
            result.put(e.getKey(), toPlain(e.getValue()));
        }
        return result;
    }

    private static Map<String, String> toStringMap(JsonNode node) {
        Map<String, String> result = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> e = fields.next();
            result.put(e.getKey(), e.getValue().asText());
        }
        return result;
    }

    private static Object toPlain(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isInt() || node.isLong()) {
            return node.asLong();
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>(node.size());
            node.forEach(child -> list.add(toPlain(child)));
            return list;
        }
        return toMap(node);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String stripExtension(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx > 0 ? fileName.substring(0, idx) : fileName;
    }

    private static String abbreviate(String text) {
        return text.length() <= 80 ? text : text.substring(0, 80) + "...";
    }

    /**
     * 单条用例消费者。
     *
     * @param caseId 用例标识
     * @param evalCase 用例
     */
    @FunctionalInterface
    public interface CaseConsumer {

        /**
         * 消费一条用例。
         *
         * @param evalCase 用例
         */
        void accept(EvalCase evalCase);
    }

    /**
     * 评测集解析异常。
     *
     * <p>独立类型让 CLI 能把「数据集有问题」（退出码 2）与「门禁不通过」（退出码 1）区分开。
     */
    public static final class DatasetParseException extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        /**
         * 构造解析异常。
         *
         * @param message 可读的中文错误说明
         */
        public DatasetParseException(String message) {
            super(message);
        }
    }
}
