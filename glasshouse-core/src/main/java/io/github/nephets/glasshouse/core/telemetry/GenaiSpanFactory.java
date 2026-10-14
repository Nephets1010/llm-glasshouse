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

import java.util.function.Consumer;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;

/**
 * Span 工厂：把「命名（SPN-001）+ 属性口径」收敛到一处。
 *
 * <p>存在的理由：属性写入如果散落在各处的 {@code span.setAttribute(...)} 调用里，迟早会出现 「同一个含义在两个地方用了不同的 key」。工厂强制所有 span
 * 都经由 {@link #setSwy} / {@link #setGenai} / {@link #writeUsage} 三个出口，口径由代码结构保证，而不是靠约定与 review。
 *
 * <p>本类只依赖 OTel API（不含 SDK），因此可以在 core 层直接单测。
 */
public final class GenaiSpanFactory {

    private GenaiSpanFactory() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 按 SPN-001 模板启动一个 span。
     *
     * @param span OTel span，不可为 null
     * @param operation 操作名，见 {@link SpnNames#operations()}
     * @param target 目标标识（模型名 / Agent 名 / 工具名）
     * @return 传入的 span，便于链式调用
     */
    public static Span start(Span span, String operation, String target) {
        span.setAttribute(GenaiAttributes.OPERATION_NAME, operation);
        span.updateName(SpnNames.name(operation, target));
        return span;
    }

    /**
     * 写入一组 {@code swy.*} 属性。
     *
     * <p>用 {@link Consumer} 回调而不是可变 Map，是为了避免每次调用都分配一个 HashMap； 调用方直接写 {@code setSwy(span, s ->
     * s.setAttribute(SwyAttributes.TENANT_ID, tenantId))}。
     *
     * @param span 目标 span，不可为 null
     * @param writer 属性写入回调，不可为 null
     * @return 传入的 span
     */
    public static Span setSwy(Span span, Consumer<Span> writer) {
        writer.accept(span);
        return span;
    }

    /**
     * 写入一组 {@code gen_ai.*} 属性。
     *
     * @param span 目标 span，不可为 null
     * @param writer 属性写入回调，不可为 null
     * @return 传入的 span
     */
    public static Span setGenai(Span span, Consumer<Span> writer) {
        writer.accept(span);
        return span;
    }

    /**
     * 写入 usage 属性（<b>不结束 span</b>）。
     *
     * <p><b>为什么改名</b>：原名 {@code endWithUsage} 会让人以为它只写 usage， 于是调用方在它之后继续写 {@code swy.*} 属性。但它内部调用了
     * {@code span.end()}， 而 OTel 的 {@code Span} 在 {@code end()} 之后<b>静默忽略</b>所有 {@code
     * setAttribute} —— 结果是 {@code swy.usage.*} / {@code swy.cost.*} 这些五维归因与成本属性全部丢失，而且没有任何报错。 这个
     * bug 由 {@code GenaiObservationHandlerTest.usageAndCostAttributes} 抓到。
     *
     * <p>现在语义明确：<b>只写属性，不碰生命周期</b>。结束 span 由调用方自己控制， 这样「写属性 → end」的顺序永远不会搞错。
     *
     * @param span 目标 span，不可为 null
     * @param inputTokens 输入 token
     * @param outputTokens 输出 token
     */
    public static void writeUsage(Span span, long inputTokens, long outputTokens) {
        span.setAttribute(GenaiAttributes.USAGE_INPUT_TOKENS, inputTokens);
        span.setAttribute(GenaiAttributes.USAGE_OUTPUT_TOKENS, outputTokens);
        // 旧名冗余一个发布周期，兼容存量看板（见 GenaiAttributes 的 javadoc）
        span.setAttribute(GenaiAttributes.USAGE_PROMPT_TOKENS_LEGACY, inputTokens);
        span.setAttribute(GenaiAttributes.USAGE_COMPLETION_TOKENS_LEGACY, outputTokens);
    }

    /**
     * 结束一个 span（无 usage）。
     *
     * @param span 目标 span，不可为 null
     */
    public static void end(Span span) {
        span.end();
    }

    /**
     * 在 span 上记录异常并标记为错误状态，然后结束 span。
     *
     * @param span 目标 span，不可为 null
     * @param error 异常，不可为 null
     */
    public static void endWithError(Span span, Throwable error) {
        span.recordException(error);
        span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, error.getMessage());
        span.end();
    }

    /**
     * 构造一组 {@code swy.*} 属性集合，供不依赖 OTel Span 的路径（如内存归因）复用同一套 key。
     *
     * @param builder 属性构造器，不可为 null
     * @return 属性集合
     */
    public static Attributes swyAttributes(
            java.util.function.Consumer<io.opentelemetry.api.common.AttributesBuilder> builder) {
        io.opentelemetry.api.common.AttributesBuilder b = Attributes.builder();
        builder.accept(b);
        return b.build();
    }

    /**
     * 把可空字符串属性安全写入 span：null 与空白都不写。
     *
     * <p>归因维度缺失时<b>不写</b>而不是写 {@code "unknown"}——这是契约 EVT-001 的硬要求， 因为占位值会污染下钻统计（架构 §2.4）。
     *
     * @param span 目标 span，不可为 null
     * @param key 属性键，不可为 null
     * @param value 属性值，可为 null
     */
    public static void setIfPresent(Span span, AttributeKey<String> key, String value) {
        if (value != null && !value.isBlank()) {
            span.setAttribute(key, value);
        }
    }

    /**
     * 把当前上下文作为父上下文返回（占位实现，供需要显式建父子关系的调用方使用）。
     *
     * @return 当前 OTel 上下文
     */
    public static Context currentContext() {
        return Context.current();
    }
}
