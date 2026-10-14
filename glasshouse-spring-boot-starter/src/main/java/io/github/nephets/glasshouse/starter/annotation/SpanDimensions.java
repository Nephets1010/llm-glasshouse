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

package io.github.nephets.glasshouse.starter.annotation;

import java.util.Optional;

/**
 * 归因维度的线程内承载。
 *
 * <p><b>解决的问题</b>：五维归因要求每个模型调用都带上 {@code tenantId / bizLine / feature / promptVersion /
 * agentId}。如果要求业务在每次调用前显式传参，那就不叫「零侵入」了。 本类把这几个维度挂在当前线程上：{@code @AgentStep} 切面进入时设置，
 * 内部所有模型调用自动继承，退出时恢复。
 *
 * <p><b>虚拟线程安全</b>：与 {@code TraceContext} 同理，使用 {@link InheritableThreadLocal}（架构 §8.3）。
 *
 * <p><b>嵌套安全</b>：{@link #runWith} 采用「保存-设置-执行-恢复」模式， 内层步骤退出时会恢复外层的维度，不会因为嵌套 Agent 而串味。
 */
public final class SpanDimensions {

    private static final InheritableThreadLocal<SpanDimensions> CURRENT =
            new InheritableThreadLocal<>();

    private final String tenantId;
    private final String bizLine;
    private final String feature;
    private final String promptVersion;
    private final String agentId;
    private final String stepId;
    private final Integer stepSeq;
    private final String stepKind;

    private SpanDimensions(
            String tenantId,
            String bizLine,
            String feature,
            String promptVersion,
            String agentId,
            String stepId,
            Integer stepSeq,
            String stepKind) {
        this.tenantId = tenantId;
        this.bizLine = bizLine;
        this.feature = feature;
        this.promptVersion = promptVersion;
        this.agentId = agentId;
        this.stepId = stepId;
        this.stepSeq = stepSeq;
        this.stepKind = stepKind;
    }

    /**
     * 返回当前线程的维度。
     *
     * @return 存在返回维度，否则为空
     */
    public static Optional<SpanDimensions> currentOptional() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * 返回当前线程的维度，无维度时返回空实例。
     *
     * @return 维度，永不为 null
     */
    public static SpanDimensions current() {
        SpanDimensions dims = CURRENT.get();
        return dims == null ? EMPTY : dims;
    }

    /** 空维度常量。 */
    public static final SpanDimensions EMPTY =
            new SpanDimensions(null, null, null, null, null, null, null, null);

    /**
     * 设置当前线程的维度。
     *
     * @param dims 维度；传 null 清除
     */
    public static void set(SpanDimensions dims) {
        if (dims == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(dims);
        }
    }

    /** 清除当前线程的维度。 */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 在给定维度下执行逻辑，结束后恢复原维度。
     *
     * @param dims 维度；为 null 时等价于直接执行
     * @param action 待执行逻辑
     */
    public static void runWith(SpanDimensions dims, Runnable action) {
        SpanDimensions previous = CURRENT.get();
        set(dims);
        try {
            action.run();
        } finally {
            set(previous);
        }
    }

    /**
     * 返回一个补全了指定字段的新维度。
     *
     * <p>注解上没写的字段（如 {@code feature}）会继承当前线程上已有的值， 因此外层 Agent 设了 {@code feature=customer-service}，
     * 内层步骤即使不重复声明也会带上。
     *
     * @param builder 补全动作
     * @return 新维度
     */
    public static SpanDimensions merge(java.util.function.UnaryOperator<Builder> builder) {
        SpanDimensions base = current();
        Builder b =
                new Builder(
                        base.tenantId,
                        base.bizLine,
                        base.feature,
                        base.promptVersion,
                        base.agentId,
                        base.stepId,
                        base.stepSeq,
                        base.stepKind);
        return builder.apply(b).build();
    }

    /**
     * 返回租户。
     *
     * @return 租户；未设置返回 null
     */
    public String tenantId() {
        return tenantId;
    }

    /**
     * 返回业务线。
     *
     * @return 业务线；未设置返回 null
     */
    public String bizLine() {
        return bizLine;
    }

    /**
     * 返回业务功能。
     *
     * @return 功能；未设置返回 null
     */
    public String feature() {
        return feature;
    }

    /**
     * 返回 Prompt 版本。
     *
     * @return 版本；未设置返回 null
     */
    public String promptVersion() {
        return promptVersion;
    }

    /**
     * 返回 Agent 标识。
     *
     * @return Agent 标识；未设置返回 null
     */
    public String agentId() {
        return agentId;
    }

    /**
     * 返回步骤 ID。
     *
     * @return 步骤 ID；未设置返回 null
     */
    public String stepId() {
        return stepId;
    }

    /**
     * 返回步骤序号。
     *
     * @return 序号；未设置返回 null
     */
    public Integer stepSeq() {
        return stepSeq;
    }

    /**
     * 返回步骤类型。
     *
     * @return 步骤类型；未设置返回 null
     */
    public String stepKind() {
        return stepKind;
    }

    /**
     * 判断是否所有维度都未设置。
     *
     * @return 无任何维度返回 {@code true}
     */
    public boolean isEmpty() {
        return tenantId == null
                && bizLine == null
                && feature == null
                && promptVersion == null
                && agentId == null
                && stepId == null
                && stepSeq == null
                && stepKind == null;
    }

    /**
     * 维度构造器。
     *
     * <p>用 builder 而不是公开构造器，是为了让「继承外层维度」这件事在类型上自然表达出来。
     */
    public static final class Builder {

        private String tenantId;
        private String bizLine;
        private String feature;
        private String promptVersion;
        private String agentId;
        private String stepId;
        private Integer stepSeq;
        private String stepKind;

        private Builder(
                String tenantId,
                String bizLine,
                String feature,
                String promptVersion,
                String agentId,
                String stepId,
                Integer stepSeq,
                String stepKind) {
            this.tenantId = tenantId;
            this.bizLine = bizLine;
            this.feature = feature;
            this.promptVersion = promptVersion;
            this.agentId = agentId;
            this.stepId = stepId;
            this.stepSeq = stepSeq;
            this.stepKind = stepKind;
        }

        /**
         * 设置租户。
         *
         * @param value 租户
         * @return 自身
         */
        public Builder tenantId(String value) {
            this.tenantId = value;
            return this;
        }

        /**
         * 设置业务线。
         *
         * @param value 业务线
         * @return 自身
         */
        public Builder bizLine(String value) {
            this.bizLine = value;
            return this;
        }

        /**
         * 设置业务功能。
         *
         * @param value 功能
         * @return 自身
         */
        public Builder feature(String value) {
            this.feature = value;
            return this;
        }

        /**
         * 设置 Prompt 版本。
         *
         * @param value 版本
         * @return 自身
         */
        public Builder promptVersion(String value) {
            this.promptVersion = value;
            return this;
        }

        /**
         * 设置 Agent 标识。
         *
         * @param value 标识
         * @return 自身
         */
        public Builder agentId(String value) {
            this.agentId = value;
            return this;
        }

        /**
         * 设置步骤 ID。
         *
         * @param value 步骤 ID
         * @return 自身
         */
        public Builder stepId(String value) {
            this.stepId = value;
            return this;
        }

        /**
         * 设置步骤序号。
         *
         * @param value 序号
         * @return 自身
         */
        public Builder stepSeq(Integer value) {
            this.stepSeq = value;
            return this;
        }

        /**
         * 设置步骤类型。
         *
         * @param value 类型（LLM / TOOL / RULE / HUMAN / COMPENSATE）
         * @return 自身
         */
        public Builder stepKind(String value) {
            this.stepKind = value;
            return this;
        }

        /**
         * 构造维度对象。
         *
         * @return 维度实例
         */
        public SpanDimensions build() {
            return new SpanDimensions(
                    tenantId, bizLine, feature, promptVersion, agentId, stepId, stepSeq, stepKind);
        }
    }
}
