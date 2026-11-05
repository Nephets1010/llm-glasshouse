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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JsonSchemaMetric} 的打分、围栏剥离、类型判定与缺失字段测试。
 *
 * <p>结构化输出是工具调用与信息抽取场景的成败关键，而这个指标刻意不引入完整 JSON Schema 校验器，
 * 只判定「必需字段在不在、类型对不对」。因此每一条约束怎么算分母、怎么算满足，都要单独钉住。
 */
class JsonSchemaMetricTest {

    private static final JsonSchemaMetric METRIC = JsonSchemaMetric.INSTANCE;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 把 JSON 文本解析成节点，仅用于构造 typeMatches 的输入。
     *
     * @param json JSON 文本
     * @return 节点
     */
    private static JsonNode node(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("测试夹具里的 JSON 写错了：" + json, e);
        }
    }

    @Test
    @DisplayName("name=jsonSchema，description 说明只查必需字段与类型")
    void nameAndDescription() {
        assertThat(METRIC.name()).isEqualTo("jsonSchema");
        assertThat(METRIC.name()).isEqualTo(Metric.NAME_JSON_SCHEMA);
        assertThat(METRIC.description()).contains("必需字段").contains("类型");
    }

    @Test
    @DisplayName("输出为 null 或空白时得 0.0000")
    void nullOrBlankActualScoresZero() {
        assertThat(METRIC.scoreOf(null, List.of("a"), null)).isEqualByComparingTo("0.0000");
        assertThat(METRIC.scoreOf("", List.of("a"), null)).isEqualByComparingTo("0.0000");
        assertThat(METRIC.scoreOf("   ", List.of("a"), null)).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("不可解析的文本得 0.0000")
    void unparseableTextScoresZero() {
        assertThat(METRIC.scoreOf("这不是 JSON", List.of("a"), null))
                .as("解析失败返回 0 是这个指标的语义本身：输出不是合法 JSON 就是失分")
                .isEqualByComparingTo("0.0000");
        assertThat(METRIC.scoreOf("{\"a\":1", List.of("a"), null)).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("顶层不是对象时得 0.0000")
    void nonObjectRootScoresZero() {
        assertThat(METRIC.scoreOf("[1,2,3]", null, null))
                .as("下游要按字段名取值，数组与标量都不满足这个前提")
                .isEqualByComparingTo("0.0000");
        assertThat(METRIC.scoreOf("42", null, null)).isEqualByComparingTo("0.0000");
        assertThat(METRIC.scoreOf("null", null, null)).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("带语言标记的代码块围栏被剥离")
    void fencedWithLanguageIsUnwrapped() {
        assertThat(METRIC.scoreOf("```json\n{\"a\":1}\n```", List.of("a"), null))
                .as("模型习惯把 JSON 包在 ```json 里，剥不掉会让所有结构化用例判 0")
                .isEqualByComparingTo("1.0000");
    }

    @Test
    @DisplayName("不带语言标记的代码块围栏也被剥离")
    void fencedWithoutLanguageIsUnwrapped() {
        assertThat(METRIC.scoreOf("```\n{\"a\":1}\n```", List.of("a"), null))
                .isEqualByComparingTo("1.0000");
        assertThat(JsonSchemaMetric.stripCodeFence("```\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
    }

    @Test
    @DisplayName("★ 未闭合的围栏不被剥离，整段文本解析失败")
    void unclosedFenceScoresZero() {
        assertThat(JsonSchemaMetric.stripCodeFence("```json\n{\"a\":1}"))
                .as("★ 现状：找不到收尾围栏时原样返回，围栏与 JSON 一起交给解析器")
                .isEqualTo("```json\n{\"a\":1}");
        assertThat(METRIC.scoreOf("```json\n{\"a\":1}", List.of("a"), null))
                .as(
                        "★ 这是当前行为，不是期望行为，改它需要改主源码 JsonSchemaMetric.java:129-143："
                                + "stripCodeFence 要求首尾都有围栏，模型被截断输出（只有开头围栏）时"
                                + "本可以剥掉第一行抢救出 JSON，现在直接判 0 分，这里留断言让偏差可见")
                .isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("★ 单行围栏不被剥离")
    void singleLineFenceScoresZero() {
        assertThat(JsonSchemaMetric.stripCodeFence("```{\"a\":1}```"))
                .as("★ 现状：没有换行就定位不到正文起点，原样返回")
                .isEqualTo("```{\"a\":1}```");
        assertThat(METRIC.scoreOf("```{\"a\":1}```", List.of("a"), null))
                .as(
                        "★ 这是当前行为，不是期望行为，改它需要改主源码 JsonSchemaMetric.java:134-137："
                                + "剥离逻辑按『第一行是围栏、最后一行是围栏』设计，"
                                + "单行 ```{...}``` 这种紧凑写法虽然合法但解析不了，这里留断言让偏差可见")
                .isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("必需字段按满足比例给分")
    void requiredFieldsRatioIsSatisfiedOverTotal() {
        assertThat(METRIC.scoreOf("{\"a\":1}", List.of("a", "b"), null))
                .as("两个必需字段只满足一个 → 1/2")
                .isEqualByComparingTo("0.5000");
    }

    @Test
    @DisplayName("显式 null 的字段不算满足")
    void explicitNullFieldIsNotSatisfied() {
        assertThat(METRIC.scoreOf("{\"a\":null}", List.of("a"), null))
                .as("字段在但值是 null 时下游照样取不到内容，不能算满足")
                .isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("必需字段列表里的 null 与空白条目被跳过，不计入分母")
    void blankAndNullRequiredFieldNamesAreSkipped() {
        assertThat(METRIC.scoreOf("{\"a\":1}", Arrays.asList("a", null, "  "), null))
                .as("占位用的空条目如果计入分母，会让评测集配置失误直接压低所有用例得分")
                .isEqualByComparingTo("1.0000");
    }

    @Test
    @DisplayName("没有任何结构约束时只要能解析成对象就得 1.0000")
    void noConstraintsMeansOne() {
        assertThat(METRIC.scoreOf("{\"a\":1}", null, null)).isEqualByComparingTo("1.0000");
        assertThat(METRIC.scoreOf("{\"a\":1}", List.of(), Map.of())).isEqualByComparingTo("1.0000");
    }

    @Test
    @DisplayName("typeMatches 支持七种类型名")
    void typeMatchesSupportsSevenTypes() {
        assertThat(JsonSchemaMetric.typeMatches(node("\"文本\""), "string")).isTrue();
        assertThat(JsonSchemaMetric.typeMatches(node("1.5"), "number")).isTrue();
        assertThat(JsonSchemaMetric.typeMatches(node("42"), "integer")).isTrue();
        assertThat(JsonSchemaMetric.typeMatches(node("true"), "boolean")).isTrue();
        assertThat(JsonSchemaMetric.typeMatches(node("{\"a\":1}"), "object")).isTrue();
        assertThat(JsonSchemaMetric.typeMatches(node("[1,2]"), "array")).isTrue();
        assertThat(JsonSchemaMetric.typeMatches(node("null"), "null")).isTrue();
        assertThat(JsonSchemaMetric.typeMatches(node("\"文本\""), "integer"))
                .as("类型错配必须返回 false，否则类型约束形同虚设")
                .isFalse();
    }

    @Test
    @DisplayName("integer 拒绝小数，number 拒绝数字字符串")
    void integerRejectsDecimalAndNumberRejectsNumericString() {
        assertThat(JsonSchemaMetric.typeMatches(node("1.5"), "integer"))
                .as("小数不是整数，下游按 int 解析会抛异常")
                .isFalse();
        assertThat(JsonSchemaMetric.typeMatches(node("\"25\""), "number"))
                .as("字符串形式的数字不是 JSON number，这条规则专门挡「该给数字却给了引号」的输出")
                .isFalse();
        assertThat(METRIC.scoreOf("{\"age\":\"25\"}", List.of("age"), Map.of("age", "number")))
                .as("约束共 2 条（存在 + 类型），只满足存在那一条 → 1/2")
                .isEqualByComparingTo("0.5000");
    }

    @Test
    @DisplayName("未知类型名按匹配处理")
    void unknownTypeNameFailsOpen() {
        assertThat(JsonSchemaMetric.typeMatches(node("\"文本\""), "decimal"))
                .as(
                        "★ 这是当前行为：类型名拼错时宁可放过也不误杀，"
                                + "因为类型约束是用户手写的，拼错一个词不该让整条评测集全判失败。"
                                + "改它需要改主源码 JsonSchemaMetric.java:164")
                .isTrue();
    }

    @Test
    @DisplayName("类型名前后空白与大小写都不敏感")
    void typeNameIsTrimmedAndCaseInsensitive() {
        assertThat(JsonSchemaMetric.typeMatches(node("\"文本\""), "  STRING ")).isTrue();
        assertThat(JsonSchemaMetric.typeMatches(node("\"文本\""), "String")).isTrue();
    }

    @Test
    @DisplayName("类型约束为 null 时恒匹配")
    void nullTypeConstraintAlwaysMatches() {
        assertThat(JsonSchemaMetric.typeMatches(node("1"), null))
                .as("只写字段名不写类型是允许的，此时只校验字段存在")
                .isTrue();
    }

    @Test
    @DisplayName("fieldTypes 的条目也计入分母")
    void fieldTypesCountInDenominator() {
        Map<String, String> types = new LinkedHashMap<>();
        types.put("b", "string");
        assertThat(METRIC.scoreOf("{\"a\":1}", List.of("a", "b"), types))
                .as("约束共 3 条：a 存在（满足）、b 存在（缺失）、b 为 string（缺失）→ 1/3")
                .isEqualByComparingTo("0.3333");
    }

    @Test
    @DisplayName("中文字段名与中文值都能正常判定")
    void chineseFieldNamesAndValuesWork() {
        assertThat(METRIC.scoreOf("{\"结论\":\"建议通过\"}", List.of("结论"), Map.of("结论", "string")))
                .as("中文场景里字段名直接用中文很常见，Jackson 与 has/get 都不受影响")
                .isEqualByComparingTo("1.0000");
    }

    @Test
    @DisplayName("missingFields 只返回缺失的字段")
    void missingFieldsReturnsOnlyMissingOnes() {
        assertThat(METRIC.missingFields("{\"a\":1}", List.of("a", "b")))
                .as("报告靠这个列表定位失败原因，多列一个字段会误导排查方向")
                .containsExactly("b");
    }

    @Test
    @DisplayName("★ missingFields 把 null 值与空白字段名也当成缺失返回")
    void missingFieldsIncludesNullValueAndBlankName() {
        assertThat(METRIC.missingFields("{\"a\":null}", Arrays.asList("a", null, "  ")))
                .as(
                        "★ 这是当前行为，不是期望行为，改它需要改主源码 JsonSchemaMetric.java:191-193："
                                + "判定条件把「值为 null」「字段名为空」与「字段不存在」合并成一条，"
                                + "于是报告里会出现 null 与空白条目。功能上无害（都算缺失），"
                                + "但报告的可读性会变差，这里留断言让偏差在报告里可见")
                .containsExactly("a", null, "  ");
    }

    @Test
    @DisplayName("missingFields 在 null 输入、非对象与非法 JSON 时返回全部必需字段")
    void missingFieldsReturnsAllWhenUnusable() {
        assertThat(METRIC.missingFields(null, List.of("a")))
                .as("输入为 null 时降级成「全部缺失」，报告据此能给出可读结论")
                .containsExactly("a");
        assertThat(METRIC.missingFields("这不是 JSON", List.of("a", "b"))).containsExactly("a", "b");
        assertThat(METRIC.missingFields("[1,2,3]", List.of("a"))).containsExactly("a");
    }

    @Test
    @DisplayName("必需字段列表为 null 或空时 missingFields 返回空列表")
    void missingFieldsWithEmptyRequiredListReturnsEmpty() {
        assertThat(METRIC.missingFields("{\"a\":1}", null)).isEmpty();
        assertThat(METRIC.missingFields("{\"a\":1}", List.of())).isEmpty();
        assertThat(METRIC.missingFields("这不是 JSON", List.of()))
                .as("没有约束就谈不上缺失，非法 JSON 也不该造出缺失条目")
                .isEmpty();
    }

    @Test
    @DisplayName("score 取具名指标上的预置值，缺省时为 0.0000")
    void scoreUsesPrecomputedValue() {
        EvalResult precomputed =
                EvalResult.success("j-1", "{\"a\":1}", null, null, 1L)
                        .withScores(Map.of("jsonSchema", new BigDecimal("0.5")));
        assertThat(METRIC.score(precomputed)).isEqualByComparingTo("0.5000");
        assertThat(METRIC.score(EvalResult.success("j-2", "{\"a\":1}", null, null, 1L)))
                .isEqualByComparingTo("0.0000");
    }
}
