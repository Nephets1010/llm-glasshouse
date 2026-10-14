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

package io.github.nephets.glasshouse.core.telemetry;

import java.util.List;

import io.opentelemetry.api.common.AttributeKey;

/**
 * OTel GenAI 标准属性别名（语义约定锁定 <b>1.41.0</b>）。
 *
 * <p><b>历史改名陷阱（必须知道，否则线上看板会突然空白）</b>：1.42.0 之前 GenAI 属性名为 {@code gen_ai.usage.prompt_tokens} /
 * {@code gen_ai.usage.completion_tokens}， 1.42.0 之后改为 {@code gen_ai.usage.input_tokens} / {@code
 * gen_ai.usage.output_tokens}。 本项目<b>统一使用新名</b>，同时在 span 上<b>冗余旧名一个发布周期</b>，兼容存量看板 （Langfuse、自研
 * Grafana、各家 APM 的字段映射并不一致）。
 *
 * <p>属性名在此以字面量声明而非引用 {@code GenAiIncubatingAttributes}，原因有二：
 *
 * <ol>
 *   <li>GenAI 属性在 semconv 1.41.0 中仍处于 incubating 包，随时可能移动；
 *       用字面量把「改名」的成本从「编译失败」降级为「契约测试变红」，后者是我们能主动处理的
 *   <li>让 {@link SwyAttributes} 成为所有属性名的唯一出处，符合架构 §9.3 风险 2 的应对策略
 * </ol>
 */
public final class GenaiAttributes {

    /** 本项目锁定的语义约定版本。 */
    public static final String SEMCONV_VERSION = "1.41.0";

    /** 操作名，取值见 {@link SpnNames#operations()}。 */
    public static final AttributeKey<String> OPERATION_NAME =
            AttributeKey.stringKey("gen_ai.operation.name");

    /** 供应商标识，小写。 */
    public static final AttributeKey<String> PROVIDER_NAME =
            AttributeKey.stringKey("gen_ai.provider.name");

    /** 请求模型。 */
    public static final AttributeKey<String> REQUEST_MODEL =
            AttributeKey.stringKey("gen_ai.request.model");

    /** 响应模型（供应商可能返回与请求不同的模型名，如自动路由场景）。 */
    public static final AttributeKey<String> RESPONSE_MODEL =
            AttributeKey.stringKey("gen_ai.response.model");

    /** 结束原因列表。 */
    public static final AttributeKey<List<String>> RESPONSE_FINISH_REASONS =
            AttributeKey.stringArrayKey("gen_ai.response.finish_reasons");

    /** 采样温度。 */
    public static final AttributeKey<Double> REQUEST_TEMPERATURE =
            AttributeKey.doubleKey("gen_ai.request.temperature");

    /** 请求声明的 max_tokens。 */
    public static final AttributeKey<Long> REQUEST_MAX_TOKENS =
            AttributeKey.longKey("gen_ai.request.max_tokens");

    /** 是否为流式请求。 */
    public static final AttributeKey<Boolean> REQUEST_STREAM =
            AttributeKey.booleanKey("gen_ai.request.stream");

    /** ★ 新名（1.42.0 起）：输入 token。 */
    public static final AttributeKey<Long> USAGE_INPUT_TOKENS =
            AttributeKey.longKey("gen_ai.usage.input_tokens");

    /** ★ 新名（1.42.0 起）：输出 token。 */
    public static final AttributeKey<Long> USAGE_OUTPUT_TOKENS =
            AttributeKey.longKey("gen_ai.usage.output_tokens");

    /** 旧名，冗余一个发布周期，等价于 {@link #USAGE_INPUT_TOKENS}。 */
    public static final AttributeKey<Long> USAGE_PROMPT_TOKENS_LEGACY =
            AttributeKey.longKey("gen_ai.usage.prompt_tokens");

    /** 旧名，冗余一个发布周期，等价于 {@link #USAGE_OUTPUT_TOKENS}。 */
    public static final AttributeKey<Long> USAGE_COMPLETION_TOKENS_LEGACY =
            AttributeKey.longKey("gen_ai.usage.completion_tokens");

    private static final List<String> ALL_NAMES =
            List.of(
                    "gen_ai.operation.name",
                    "gen_ai.provider.name",
                    "gen_ai.request.model",
                    "gen_ai.response.model",
                    "gen_ai.response.finish_reasons",
                    "gen_ai.request.temperature",
                    "gen_ai.request.max_tokens",
                    "gen_ai.request.stream",
                    "gen_ai.usage.input_tokens",
                    "gen_ai.usage.output_tokens");

    private static final List<String> LEGACY_NAMES =
            List.of("gen_ai.usage.prompt_tokens", "gen_ai.usage.completion_tokens");

    private GenaiAttributes() {
        throw new AssertionError("常量类不允许实例化");
    }

    /**
     * 返回 GenAI 标准属性名列表（新名，不含旧名）。
     *
     * @return 不可变列表
     */
    public static List<String> allNames() {
        return ALL_NAMES;
    }

    /**
     * 返回冗余的旧属性名列表。
     *
     * @return 不可变列表
     */
    public static List<String> legacyNames() {
        return LEGACY_NAMES;
    }

    /**
     * 返回「本项目实际写入 span 的全部 GenAI 属性名」，含新旧两套。
     *
     * @return 不可变列表
     */
    public static List<String> allNamesIncludingLegacy() {
        List<String> result = new java.util.ArrayList<>(ALL_NAMES);
        result.addAll(LEGACY_NAMES);
        return List.copyOf(result);
    }

    /**
     * 判断属性名是否属于 GenAI 语义约定。
     *
     * @param name 属性名，可为 null
     * @return 以 {@code gen_ai.} 开头返回 {@code true}
     */
    public static boolean isGenaiAttribute(String name) {
        return name != null && name.startsWith("gen_ai.");
    }
}
