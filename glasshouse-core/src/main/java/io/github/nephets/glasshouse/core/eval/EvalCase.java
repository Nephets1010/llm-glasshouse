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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 单条评测用例。
 *
 * <p><b>四字段是硬要求</b>（架构 ③T04 验收标准第 5 条）：{@code id / input / expected / metadata} 四者缺一不可，由 {@code
 * ContractSchemaTest} 与 {@code JsonlDatasetParser} 双重校验。
 *
 * <p><b>为什么强制 metadata</b>：评测集的数据版权与可复现性是真实风险（架构 Q-F）。 每条用例必须标注 {@code authored}（自撰）或 {@code
 * source}（公开来源改写）， 以及场景、语言、难度。没有出处的评测集不可能被第三方复核，也就无法作为 CI 门禁的依据。
 *
 * @param id 用例标识，数据集内唯一
 * @param input 输入（渲染 Prompt 用），键值对
 * @param expected 期望结果，键值对；至少应含 {@code contains} 或 {@code exact} 之一
 * @param metadata 元数据，必须含 {@code authored} 或 {@code source}
 */
public record EvalCase(
        String id,
        Map<String, Object> input,
        Map<String, Object> expected,
        Map<String, String> metadata) {

    /** 常用元数据键：用例自撰标记。 */
    public static final String META_AUTHORED = "authored";

    /** 常用元数据键：公开来源。 */
    public static final String META_SOURCE = "source";

    /** 常用元数据键：场景分类。 */
    public static final String META_SCENARIO = "scenario";

    /** 常用元数据键：难度。 */
    public static final String META_DIFFICULTY = "difficulty";

    /**
     * 紧凑构造器：校验四字段并做防御性拷贝。
     *
     * @throws IllegalArgumentException id 为空、input/expected 为空、或 metadata 无出处标注
     */
    public EvalCase {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("用例 id 不能为空");
        }
        id = id.trim();
        input = input == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(input));
        expected =
                expected == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(expected));
        metadata =
                metadata == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        if (input.isEmpty()) {
            throw new IllegalArgumentException("用例 " + id + " 的 input 不能为空");
        }
        if (expected.isEmpty()) {
            throw new IllegalArgumentException("用例 " + id + " 的 expected 不能为空");
        }
        boolean hasProvenance =
                hasText(metadata.get(META_AUTHORED)) || hasText(metadata.get(META_SOURCE));
        if (!hasProvenance) {
            throw new IllegalArgumentException(
                    "用例 " + id + " 的 metadata 必须包含 authored（自撰）或 source（公开来源）标注，禁止无出处数据");
        }
    }

    /**
     * 构造一个带默认元数据的用例。
     *
     * @param id 用例标识
     * @param input 输入
     * @param expected 期望
     * @param scenario 场景分类
     * @param difficulty 难度
     * @param authored 是否自撰（true 时写入 {@code authored}，false 时需通过 source 参数给出来源）
     * @param source 来源描述；{@code authored=true} 时可传 null
     * @return 用例实例
     */
    public static EvalCase of(
            String id,
            Map<String, Object> input,
            Map<String, Object> expected,
            String scenario,
            String difficulty,
            boolean authored,
            String source) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put(META_SCENARIO, scenario == null ? "未分类" : scenario);
        meta.put(META_DIFFICULTY, difficulty == null ? "medium" : difficulty);
        if (authored) {
            meta.put(META_AUTHORED, "llm-glasshouse 自撰");
        } else {
            if (source == null || source.isBlank()) {
                throw new IllegalArgumentException("非自撰用例必须提供 source");
            }
            meta.put(META_SOURCE, source);
        }
        return new EvalCase(id, input, expected, meta);
    }

    /**
     * 取出期望结果中的字符串列表。
     *
     * <p>支持 {@code contains}（必须包含的关键词）与 {@code exact}（精确匹配的完整答案）两种键。
     *
     * @param key 期望键
     * @return 字符串列表；缺失或类型不符返回空列表
     */
    @SuppressWarnings("unchecked")
    public java.util.List<String> expectedList(String key) {
        Object value = expected.get(key);
        if (value instanceof java.util.List<?> list) {
            java.util.List<String> result = new java.util.ArrayList<>(list.size());
            for (Object o : list) {
                if (o != null) {
                    result.add(String.valueOf(o));
                }
            }
            return result;
        }
        if (value instanceof String s) {
            return java.util.List.of(s);
        }
        return java.util.List.of();
    }

    /**
     * 取出期望结果中的单个字符串。
     *
     * @param key 期望键
     * @return 字符串；缺失返回 null
     */
    public String expectedText(String key) {
        Object value = expected.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 取出输入中的单个字符串。
     *
     * @param key 输入键
     * @return 字符串；缺失返回 null
     */
    public String inputText(String key) {
        Object value = input.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 返回场景分类。
     *
     * @return 场景；未标注时返回 {@code "未分类"}
     */
    public String scenario() {
        return metadata.getOrDefault(META_SCENARIO, "未分类");
    }

    /**
     * 判断该用例是否为自撰。
     *
     * @return 自撰返回 {@code true}
     */
    public boolean authored() {
        return hasText(metadata.get(META_AUTHORED));
    }

    /**
     * 返回数据出处描述。
     *
     * @return 出处描述；无标注返回 {@code ""}
     */
    public String provenance() {
        return metadata.getOrDefault(META_AUTHORED, metadata.getOrDefault(META_SOURCE, ""));
    }

    /**
     * 判断对象是否为非空白字符串。
     *
     * @param value 待判定值，可为 null
     * @return 非空白返回 {@code true}
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 校验对象非空，返回原值。供构造链式调用使用。
     *
     * @param value 待校验值
     * @param <T> 值类型
     * @return 原值
     */
    static <T> T requireNonNull(T value) {
        return Objects.requireNonNull(value);
    }
}
