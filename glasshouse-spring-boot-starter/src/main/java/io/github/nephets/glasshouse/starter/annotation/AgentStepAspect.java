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

import java.lang.reflect.Method;

import io.github.nephets.glasshouse.core.telemetry.SpnNames;
import io.github.nephets.glasshouse.core.telemetry.SwyAttributes;
import io.github.nephets.glasshouse.starter.observation.SpanFactory;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code @AgentStep} / {@code @ToolCall} / {@code @Retrieval} 三个注解的统一切面。
 *
 * <p>合并成一个切面而不是三个，理由是它们的行为完全一致：进入时开 span、退出时 end、 中间把归因维度挂到线程上。三个几乎相同的类只会带来三份需要同步修改的代码。
 *
 * <p><b>异常语义</b>：被标注方法抛异常时，span 记为 ERROR 并记录异常，然后<b>原样抛出</b>—— 切面绝不能吞掉业务异常。
 */
@Aspect
public class AgentStepAspect {

    private static final Logger log = LoggerFactory.getLogger(AgentStepAspect.class);

    /** 步骤类型常量，取值与契约 EVT-001 的 stepKind 一致（全大写）。 */
    private static final String STEP_KIND_LLM = "LLM";

    private static final String STEP_KIND_TOOL = "TOOL";

    private static final String STEP_KIND_RULE = "RULE";

    /** OTel GenAI 数据源属性名（semconv 1.41.0）。 */
    private static final io.opentelemetry.api.common.AttributeKey<String> GENAI_DATA_SOURCE_ID =
            io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.data_source.id");

    private final SpanFactory spanFactory;

    /**
     * 构造切面。
     *
     * @param spanFactory span 工厂，不可为 null
     */
    public AgentStepAspect(SpanFactory spanFactory) {
        if (spanFactory == null) {
            throw new NullPointerException("spanFactory 不能为 null");
        }
        this.spanFactory = spanFactory;
    }

    /**
     * 拦截 {@code @AgentStep} 方法。
     *
     * @param pjp 连接点
     * @return 方法返回值
     * @throws Throwable 方法本身抛出的异常，原样传播
     */
    @Around("@annotation(io.github.nephets.glasshouse.starter.annotation.AgentStep)")
    public Object aroundAgentStep(ProceedingJoinPoint pjp) throws Throwable {
        Method method = methodOf(pjp);
        if (method == null) {
            return pjp.proceed();
        }
        AgentStep annotation = method.getAnnotation(AgentStep.class);
        String agentId = pick(annotation.agentId(), method.getName());
        String stepId = pick(annotation.stepId(), method.getName());
        Integer stepSeq = annotation.stepSeq() >= 0 ? annotation.stepSeq() : null;

        // 注解上留空的维度必须<b>继承</b>线程上已有的值，而不是把它清空。
        // 之前直接用 blankToNull(annotation.promptVersion()) 覆盖，导致外层设置的
        // promptVersion 被内层步骤的默认空值抹掉——五维归因因此丢一维。
        String annotatedFeature = blankToNull(annotation.feature());
        String annotatedPromptVersion = blankToNull(annotation.promptVersion());
        SpanDimensions currentDims = SpanDimensions.current();
        SpanDimensions dims =
                SpanDimensions.merge(
                        b ->
                                b.agentId(agentId)
                                        .stepId(stepId)
                                        .stepSeq(stepSeq)
                                        .stepKind(STEP_KIND_LLM)
                                        .feature(
                                                annotatedFeature == null
                                                        ? currentDims.feature()
                                                        : annotatedFeature)
                                        .promptVersion(
                                                annotatedPromptVersion == null
                                                        ? currentDims.promptVersion()
                                                        : annotatedPromptVersion));
        SpanFactory.Handle handle = spanFactory.start(SpnNames.OP_INVOKE_AGENT, agentId);
        applyStepDimensions(handle, dims);
        handle.swy(SwyAttributes.AGENT_VERSION, blankToNull(annotation.agentVersion()));

        return proceedWithSpan(pjp, handle, dims);
    }

    /**
     * 拦截 {@code @ToolCall} 方法。
     *
     * @param pjp 连接点
     * @return 方法返回值
     * @throws Throwable 方法本身抛出的异常，原样传播
     */
    @Around("@annotation(io.github.nephets.glasshouse.starter.annotation.ToolCall)")
    public Object aroundToolCall(ProceedingJoinPoint pjp) throws Throwable {
        Method method = methodOf(pjp);
        if (method == null) {
            return pjp.proceed();
        }
        ToolCall annotation = method.getAnnotation(ToolCall.class);
        String toolName = pick(annotation.toolName(), method.getName());
        String stepId = blankToNull(annotation.stepId());

        SpanDimensions dims =
                SpanDimensions.merge(
                        b -> b.stepId(stepId == null ? toolName : stepId).stepKind(STEP_KIND_TOOL));
        SpanFactory.Handle handle = spanFactory.start(SpnNames.OP_EXECUTE_TOOL, toolName);
        applyStepDimensions(handle, dims);

        return proceedWithSpan(pjp, handle, dims);
    }

    /**
     * 拦截 {@code @Retrieval} 方法。
     *
     * @param pjp 连接点
     * @return 方法返回值
     * @throws Throwable 方法本身抛出的异常，原样传播
     */
    @Around("@annotation(io.github.nephets.glasshouse.starter.annotation.Retrieval)")
    public Object aroundRetrieval(ProceedingJoinPoint pjp) throws Throwable {
        Method method = methodOf(pjp);
        if (method == null) {
            return pjp.proceed();
        }
        Retrieval annotation = method.getAnnotation(Retrieval.class);
        String retriever = pick(annotation.retriever(), method.getName());

        SpanDimensions dims =
                SpanDimensions.merge(b -> b.stepId(retriever).stepKind(STEP_KIND_RULE));
        SpanFactory.Handle handle = spanFactory.start(SpnNames.OP_RETRIEVE, retriever);
        applyStepDimensions(handle, dims);
        handle.genai(GENAI_DATA_SOURCE_ID, blankToNull(annotation.dataSourceId()));

        return proceedWithSpan(pjp, handle, dims);
    }

    /**
     * 执行被拦截的方法，并在前后管理 span 与维度。
     *
     * @param pjp 连接点
     * @param handle span 句柄
     * @param stepDimensions 该步骤生效期间线程上生效的维度
     * @return 方法返回值
     * @throws Throwable 方法本身抛出的异常，原样传播
     */
    private Object proceedWithSpan(
            ProceedingJoinPoint pjp, SpanFactory.Handle handle, SpanDimensions stepDimensions)
            throws Throwable {
        SpanDimensions previous = SpanDimensions.currentOptional().orElse(null);
        SpanDimensions.set(stepDimensions);
        try {
            Object result = pjp.proceed();
            handle.end();
            return result;
        } catch (Throwable error) {
            // ★ 这里必须捕获 Throwable（含 Error）：span 要把任何失败都记成 ERROR 状态，
            //   否则断路器/告警会漏掉 OOM、StackOverflow 这类 Error。
            //   捕获后立即原样重抛，不吞任何东西，业务语义完全不变。
            handle.endWithError(error);
            throw error;
        } finally {
            SpanDimensions.set(previous);
        }
    }

    /**
     * 把当前维度写到 span 上。
     *
     * @param handle span 句柄
     * @param dims 维度
     */
    private void applyStepDimensions(SpanFactory.Handle handle, SpanDimensions dims) {
        handle.swy(SwyAttributes.TENANT_ID, dims.tenantId());
        handle.swy(SwyAttributes.BIZ_LINE, dims.bizLine());
        handle.swy(SwyAttributes.FEATURE, dims.feature());
        handle.swy(SwyAttributes.PROMPT_VERSION, dims.promptVersion());
        handle.swy(SwyAttributes.AGENT_ID, dims.agentId());
        handle.swy(SwyAttributes.STEP_ID, dims.stepId());
        handle.swyLong(
                SwyAttributes.STEP_SEQ, dims.stepSeq() == null ? null : dims.stepSeq().longValue());
        handle.swy(SwyAttributes.STEP_KIND, dims.stepKind());
    }

    /**
     * 从连接点取出目标方法。
     *
     * @param pjp 连接点
     * @return 方法；解析失败返回 null
     */
    private static Method methodOf(ProceedingJoinPoint pjp) {
        try {
            MethodSignature signature = (MethodSignature) pjp.getSignature();
            return signature == null ? null : signature.getMethod();
        } catch (RuntimeException e) {
            log.debug("[glasshouse] 解析目标方法失败: {}", e.toString());
            return null;
        }
    }

    private static String pick(String configured, String fallback) {
        String value = blankToNull(configured);
        return value == null ? fallback : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
