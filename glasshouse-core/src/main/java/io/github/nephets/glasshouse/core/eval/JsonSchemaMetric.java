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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON 结构合规指标：输出必须是可解析的 JSON，且必需字段齐全、字段类型正确。
 *
 * <p>适用于工具调用、信息抽取、结构化报表场景。
 *
 * <p><b>为什么不用完整的 JSON Schema 校验器</b>：完整的 draft 2020-12 校验器是重量级依赖，
 * 而本项目要判定的只是「这个结构化输出能不能被下游消费」——即必需字段在不在、类型对不对。 这个子集覆盖了真实场景里 95% 的结构化输出失败原因，且零依赖、可完全确定性复现。 需要完整
 * Schema 校验时用 {@code --schema <file>} 走 P1 的 {@code JsonSchemaValidator}。
 */
public final class JsonSchemaMetric implements Metric {

    /** 期望键名：必需字段列表。 */
    public static final String EXPECTED_REQUIRED_FIELDS = "requiredFields";

    /** 期望键名：字段类型约束，形如 {@code {"age":"number","name":"string"}}。 */
    public static final String EXPECTED_FIELD_TYPES = "fieldTypes";

    /** 共享实例。 */
    public static final JsonSchemaMetric INSTANCE = new JsonSchemaMetric();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return NAME_JSON_SCHEMA;
    }

    @Override
    public String description() {
        return "JSON 结构合规率：输出可解析且必需字段齐全、类型正确";
    }

    @Override
    public BigDecimal score(EvalResult result) {
        BigDecimal precomputed = result.scoreOf(name());
        return BigDecimal.ZERO.compareTo(precomputed) != 0 ? precomputed : Metric.normalize(0.0d);
    }

    /**
     * 对单条结果直接打分。
     *
     * @param actual 模型输出，可为 null
     * @param requiredFields 必需字段列表，可为 null 或空（此时只校验可解析性）
     * @param fieldTypes 字段类型约束，可为 null
     * @return 1.0 表示完全合规；否则按「满足的约束比例」给分
     */
    public BigDecimal scoreOf(
            String actual, List<String> requiredFields, Map<String, String> fieldTypes) {
        if (actual == null || actual.isBlank()) {
            return Metric.normalize(0.0d);
        }
        JsonNode root;
        try {
            // 模型常在 JSON 外面包 ```json 代码块，先剥掉围栏
            root = MAPPER.readTree(stripCodeFence(actual));
        } catch (JsonProcessingException e) {
            // ★ 这里返回 0 是<b>指标语义本身</b>而不是「吞异常」：
            //   「输出不是合法 JSON」就是 JSON 结构合规率为 0，属于被评测模型的失分。
            //   刻意不打日志：本方法在评测热路径上按用例逐条调用，
            //   一个不合规的模型会让日志量等于用例数，把真正的故障日志淹掉。
            //   需要定位是哪条用例不合规时走 missingFields()，报告里会列出缺失字段。
            return Metric.normalize(0.0d);
        }
        if (root == null || !root.isObject()) {
            return Metric.normalize(0.0d);
        }
        int total = 0;
        int satisfied = 0;
        if (requiredFields != null) {
            for (String field : requiredFields) {
                if (field == null || field.isBlank()) {
                    continue;
                }
                total++;
                if (root.has(field) && !root.get(field).isNull()) {
                    satisfied++;
                }
            }
        }
        if (fieldTypes != null) {
            for (Map.Entry<String, String> e : fieldTypes.entrySet()) {
                total++;
                JsonNode node = root.get(e.getKey());
                if (node != null && typeMatches(node, e.getValue())) {
                    satisfied++;
                }
            }
        }
        if (total == 0) {
            // 无结构约束时，「能解析成 JSON 对象」本身就满分
            return Metric.normalize(1.0d);
        }
        return Metric.normalize((double) satisfied / total);
    }

    /**
     * 剥掉 Markdown 代码块围栏。
     *
     * @param text 原始文本
     * @return 剥离后的文本；无围栏时原样返回
     */
    static String stripCodeFence(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        if (firstNewline < 0) {
            return trimmed;
        }
        int lastFence = trimmed.lastIndexOf("```");
        if (lastFence <= firstNewline) {
            return trimmed;
        }
        return trimmed.substring(firstNewline + 1, lastFence).trim();
    }

    /**
     * 判断节点类型是否符合约束。
     *
     * @param node 节点
     * @param expectedType 期望类型名：{@code string / number / integer / boolean / object / array}
     * @return 符合返回 {@code true}
     */
    static boolean typeMatches(JsonNode node, String expectedType) {
        if (expectedType == null) {
            return true;
        }
        return switch (expectedType.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "string" -> node.isTextual();
            case "number" -> node.isNumber();
            case "integer" -> node.isIntegralNumber();
            case "boolean" -> node.isBoolean();
            case "object" -> node.isObject();
            case "array" -> node.isArray();
            case "null" -> node.isNull();
            default -> true;
        };
    }

    /**
     * 返回「缺失的必需字段」列表，供报告定位失败原因。
     *
     * @param actual 模型输出
     * @param requiredFields 必需字段列表
     * @return 缺失字段列表；全部齐全返回空列表
     */
    public List<String> missingFields(String actual, List<String> requiredFields) {
        List<String> missing = new ArrayList<>();
        if (requiredFields == null || requiredFields.isEmpty()) {
            return missing;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(stripCodeFence(actual == null ? "" : actual));
        } catch (JsonProcessingException e) {
            // 与 scoreOf 同规则：解析不了即视为「全部必需字段都缺失」，
            // 返回值本身就是给报告看的降级标记，因此不额外打日志（评测热路径）。
            return new ArrayList<>(requiredFields);
        }
        if (root == null || !root.isObject()) {
            return new ArrayList<>(requiredFields);
        }
        for (String field : requiredFields) {
            if (field == null || field.isBlank() || !root.has(field) || root.get(field).isNull()) {
                missing.add(field);
            }
        }
        return missing;
    }
}
