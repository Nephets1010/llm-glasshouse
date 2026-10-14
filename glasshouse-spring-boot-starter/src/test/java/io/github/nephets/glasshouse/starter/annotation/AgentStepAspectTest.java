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

import java.util.Map;

import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import io.github.nephets.glasshouse.core.audit.PayloadRedactor;
import io.github.nephets.glasshouse.core.pricing.PricingTable;
import io.github.nephets.glasshouse.starter.observation.SpanFactory;
import io.github.nephets.glasshouse.starter.store.SpanRingBuffer;
import io.github.nephets.glasshouse.starter.store.SpanRingBuffer.SpanData;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 埋点切面测试（架构 ③T03 验收标准第 3 条）。
 *
 * <p>三个注解共用一个切面，因此本类一次验证三件事：
 *
 * <ul>
 *   <li>{@code @AgentStep} 产出 {@code invoke_agent <agentId>} span
 *   <li>{@code @ToolCall} 产出 {@code execute_tool <toolName>} span
 *   <li>{@code @Retrieval} 产出 {@code retrieval <retriever>} span
 * </ul>
 *
 * <p>同时验证切面最关键的两条性质：<b>异常原样抛出</b>、<b>嵌套时维度正确恢复</b>。
 */
class AgentStepAspectTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private SpanRingBuffer buffer;
    private AgentStepAspect aspect;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider =
                SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build();
        buffer = new SpanRingBuffer(100);
        aspect =
                new AgentStepAspect(
                        new SpanFactory(
                                tracerProvider.get(
                                        SpanFactory.SCOPE_NAME, SpanFactory.SCOPE_VERSION),
                                buffer,
                                PayloadRedactor.getInstance(),
                                new PricingTable(),
                                false,
                                null));
    }

    @AfterEach
    void tearDown() {
        SpanDimensions.clear();
        tracerProvider.close();
    }

    /**
     * 构造一个「返回固定值」的连接点桩。
     *
     * @param method 目标方法（用于读取注解）
     * @param result 返回值
     * @return 连接点桩
     */
    private ProceedingJoinPoint joinPointFor(java.lang.reflect.Method method, Object result) {
        return (ProceedingJoinPoint)
                java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {ProceedingJoinPoint.class},
                        (proxy, m, args) -> {
                            switch (m.getName()) {
                                case "proceed":
                                    return result;
                                case "getSignature":
                                    return new MinimalMethodSignature(method);
                                case "toShortString":
                                    return new MinimalMethodSignature(method).toShortString();
                                default:
                                    return null;
                            }
                        });
    }

    /**
     * 构造一个「抛异常」的连接点桩。
     *
     * @param method 目标方法
     * @param error 要抛出的异常
     * @return 连接点桩
     */
    private ProceedingJoinPoint failingJoinPoint(
            java.lang.reflect.Method method, RuntimeException error) {
        return (ProceedingJoinPoint)
                java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {ProceedingJoinPoint.class},
                        (proxy, m, args) -> {
                            if ("proceed".equals(m.getName())) {
                                throw error;
                            }
                            return "getSignature".equals(m.getName())
                                    ? new MinimalMethodSignature(method)
                                    : null;
                        });
    }

    /** 被测的「业务方法」。 */
    static final class BusinessService {

        @AgentStep(agentId = "planner", stepId = "plan", stepSeq = 0, feature = "customer-service")
        public String plan(String q) {
            return "plan:" + q;
        }

        @AgentStep(agentId = "writer")
        public String write(String q) {
            return "write:" + q;
        }

        @AgentStep
        public String noAgentId(String q) {
            return "no-id:" + q;
        }

        @ToolCall(toolName = "ticket_create", stepId = "draft")
        public String createTicket(String q) {
            return "ticket:" + q;
        }

        @ToolCall
        public String noToolName(String q) {
            return "tool:" + q;
        }

        @Retrieval(retriever = "kb_lookup", dataSourceId = "faq-zh-v1")
        public String lookup(String q) {
            return "kb:" + q;
        }

        @Retrieval
        public String noRetriever(String q) {
            return "retrieval:" + q;
        }

        @AgentStep(agentId = "outer", stepId = "outer")
        public String outer(String q) {
            return SpanDimensions.current().stepId();
        }
    }

    private final BusinessService service = new BusinessService();

    @Test
    @DisplayName("★ @AgentStep 产出 span 名 invoke_agent planner")
    void agentStepProducesSpan() throws Throwable {
        Object result =
                aspect.aroundAgentStep(
                        joinPointFor(
                                BusinessService.class.getMethod("plan", String.class), "ignored"));
        assertThat(buffer.size()).isEqualTo(1);

        SpanData span = buffer.all().get(0);
        assertThat(span.name()).isEqualTo("invoke_agent planner");
        assertThat(span.operation()).isEqualTo("invoke_agent");
        assertThat(span.target()).isEqualTo("planner");
        assertThat(span.status()).isEqualTo("OK");
        assertThat(result).as("方法返回值原样返回").isEqualTo("ignored");

        SpanTreeAssertions.assertAttributes(
                span,
                Map.of(
                        "swy.agent.id", "planner",
                        "swy.step.id", "plan",
                        "swy.feature", "customer-service",
                        "swy.step.kind", "LLM"));
    }

    @Test
    @DisplayName("@AgentStep 的 stepSeq 写成数值属性")
    void agentStepWritesStepSeq() throws Throwable {
        aspect.aroundAgentStep(
                joinPointFor(BusinessService.class.getMethod("plan", String.class), "x"));
        assertThat(buffer.all().get(0).attributes()).containsEntry("swy.step.seq", 0L);
    }

    @Test
    @DisplayName("@AgentStep 不填 agentId 时回退到方法名")
    void agentStepFallsBackToMethodName() throws Throwable {
        aspect.aroundAgentStep(
                joinPointFor(BusinessService.class.getMethod("noAgentId", String.class), "x"));
        assertThat(buffer.all().get(0).name()).isEqualTo("invoke_agent noAgentId");
    }

    @Test
    @DisplayName("★ @ToolCall 产出 span 名 execute_tool ticket_create")
    void toolCallProducesSpan() throws Throwable {
        aspect.aroundToolCall(
                joinPointFor(BusinessService.class.getMethod("createTicket", String.class), "x"));
        assertThat(buffer.all().get(0).name()).isEqualTo("execute_tool ticket_create");
        assertThat(buffer.all().get(0).attributes())
                .containsEntry("swy.step.id", "draft")
                .containsEntry("swy.step.kind", "TOOL");
    }

    @Test
    @DisplayName("@ToolCall 不填 toolName 时回退到方法名")
    void toolCallFallsBackToMethodName() throws Throwable {
        aspect.aroundToolCall(
                joinPointFor(BusinessService.class.getMethod("noToolName", String.class), "x"));
        assertThat(buffer.all().get(0).name()).isEqualTo("execute_tool noToolName");
    }

    @Test
    @DisplayName("★ @Retrieval 产出 span 名 retrieval kb_lookup 并带 data_source.id")
    void retrievalProducesSpan() throws Throwable {
        aspect.aroundRetrieval(
                joinPointFor(BusinessService.class.getMethod("lookup", String.class), "x"));
        SpanData span = buffer.all().get(0);
        assertThat(span.name()).isEqualTo("retrieval kb_lookup");
        assertThat(span.attributes())
                .containsEntry("gen_ai.data_source.id", "faq-zh-v1")
                .containsEntry("swy.step.kind", "RULE");
    }

    @Test
    @DisplayName("@Retrieval 不填 retriever 时回退到方法名")
    void retrievalFallsBackToMethodName() throws Throwable {
        aspect.aroundRetrieval(
                joinPointFor(BusinessService.class.getMethod("noRetriever", String.class), "x"));
        assertThat(buffer.all().get(0).name()).isEqualTo("retrieval noRetriever");
    }

    @Test
    @DisplayName("★ 异常原样抛出，span 标记为 ERROR 并记录异常")
    void exceptionPropagatesAndMarksSpan() throws Throwable {
        IllegalStateException boom = new IllegalStateException("业务异常");
        assertThatThrownBy(
                        () ->
                                aspect.aroundAgentStep(
                                        failingJoinPoint(
                                                BusinessService.class.getMethod(
                                                        "plan", String.class),
                                                boom)))
                .as("★ 切面绝不能吞掉业务异常")
                .isSameAs(boom);

        assertThat(buffer.all().get(0).status()).isEqualTo("ERROR");
        assertThat(exporter.getFinishedSpanItems().get(0).getStatus().getStatusCode())
                .isEqualTo(StatusCode.ERROR);
        assertThat(exporter.getFinishedSpanItems().get(0).getEvents())
                .anyMatch(e -> e.getName().equals("exception"));
    }

    @Test
    @DisplayName("★ 嵌套步骤：内层退出后恢复外层维度（不串味）")
    void nestedStepsRestoreDimensions() throws Throwable {
        aspect.aroundAgentStep(
                joinPointFor(BusinessService.class.getMethod("outer", String.class), "outer"));
        // 切面内部跑了一个内层步骤
        aspect.aroundAgentStep(
                joinPointFor(BusinessService.class.getMethod("plan", String.class), "inner"));

        assertThat(buffer.size()).isEqualTo(2);
        SpanData inner = buffer.all().get(1);
        assertThat(inner.name()).isEqualTo("invoke_agent planner");
        assertThat(inner.attributes()).containsEntry("swy.agent.id", "planner");

        assertThat(SpanDimensions.current().isEmpty()).as("两层切面都退出后，线程上的维度必须回到干净状态").isTrue();
    }

    @Test
    @DisplayName("切面读取的归因维度来自线程上下文")
    void dimensionsComeFromThreadContext() throws Throwable {
        SpanDimensions.set(
                SpanDimensions.merge(
                        b -> b.tenantId("vip").bizLine("电商").promptVersion("planner@v3")));
        aspect.aroundAgentStep(
                joinPointFor(BusinessService.class.getMethod("plan", String.class), "x"));

        assertThat(buffer.all().get(0).attributes())
                .containsEntry("swy.tenant.id", "vip")
                .containsEntry("swy.biz.line", "电商")
                .containsEntry("swy.prompt.version", "planner@v3");
    }

    @Test
    @DisplayName("连接点无法解析方法时原样 proceed（不吞异常）")
    void unresolvableSignatureStillProceeds() throws Throwable {
        ProceedingJoinPoint broken =
                (ProceedingJoinPoint)
                        java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {ProceedingJoinPoint.class},
                                (proxy, m, args) -> {
                                    if ("proceed".equals(m.getName())) {
                                        return "passthrough";
                                    }
                                    if ("getSignature".equals(m.getName())) {
                                        throw new IllegalStateException("无法解析签名");
                                    }
                                    return null;
                                });
        assertThat(aspect.aroundAgentStep(broken)).isEqualTo("passthrough");
    }

    @Test
    @DisplayName("切面构造器校验：spanFactory 不可为 null")
    void constructorValidation() {
        org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class, () -> new AgentStepAspect(null));
    }

    @Test
    @DisplayName("三个注解的 span 名都符合 SPN-001 模板")
    void allThreeOperationsMatchContract() throws Throwable {
        aspect.aroundAgentStep(
                joinPointFor(BusinessService.class.getMethod("plan", String.class), "x"));
        aspect.aroundToolCall(
                joinPointFor(BusinessService.class.getMethod("createTicket", String.class), "x"));
        aspect.aroundRetrieval(
                joinPointFor(BusinessService.class.getMethod("lookup", String.class), "x"));

        assertThat(buffer.all())
                .extracting(SpanData::name)
                .containsExactly(
                        "invoke_agent planner",
                        "execute_tool ticket_create",
                        "retrieval kb_lookup");
        for (SpanData span : buffer.all()) {
            assertThat(span.name().split(" ")).as("span 名必须是 {operation} {target} 两段").hasSize(2);
            assertThat(
                            io.github.nephets.glasshouse.core.telemetry.SpnNames.isKnownOperation(
                                    span.operation()))
                    .isTrue();
        }
    }

    /**
     * 最小 {@link MethodSignature} 实现。
     *
     * <p>切面只用到 {@link #getMethod()}，其余方法按接口签名返回默认值即可。 内联实现（而不是引入 aspectjweaver 的 {@code
     * MethodSignatureImpl}）是为了让测试的 依赖面保持最小——测试不需要 weaving 运行时。
     */
    static final class MinimalMethodSignature implements MethodSignature {

        private final java.lang.reflect.Method method;

        MinimalMethodSignature(java.lang.reflect.Method method) {
            this.method = method;
        }

        @Override
        public java.lang.reflect.Method getMethod() {
            return method;
        }

        @Override
        public Class<?> getReturnType() {
            return method.getReturnType();
        }

        @Override
        public Class<?> getDeclaringType() {
            return method.getDeclaringClass();
        }

        @Override
        public String getDeclaringTypeName() {
            return method.getDeclaringClass().getName();
        }

        @Override
        public String getName() {
            return method.getName();
        }

        @Override
        public int getModifiers() {
            return method.getModifiers();
        }

        @Override
        public Class<?>[] getParameterTypes() {
            return method.getParameterTypes();
        }

        /** {@code CodeSignature} 要求的异常类型（切面不用，返回真实值便于排查）。 */
        @Override
        public Class<?>[] getExceptionTypes() {
            return method.getExceptionTypes();
        }

        /** {@code CodeSignature} 要求的参数名；字节码里通常没有调试信息，返回 null。 */
        @Override
        public String[] getParameterNames() {
            return null;
        }

        @Override
        public String toShortString() {
            // java.lang.reflect.Method#toShortString() 不是 public，手工拼一个够用的
            StringBuilder sb = new StringBuilder(method.getDeclaringClass().getSimpleName());
            sb.append('.').append(method.getName()).append('(');
            for (Class<?> p : method.getParameterTypes()) {
                sb.append(p.getSimpleName()).append(',');
            }
            if (method.getParameterCount() > 0) {
                sb.setLength(sb.length() - 1);
            }
            return sb.append(')').toString();
        }

        @Override
        public String toLongString() {
            return method.toString();
        }

        @Override
        public String toString() {
            return toLongString();
        }
    }

    /** span 属性断言的小工具，避免测试里重复写 key。 */
    static final class SpanTreeAssertions {

        private SpanTreeAssertions() {}

        /**
         * 断言 span 快照包含给定的属性。
         *
         * @param span span 快照
         * @param expected 属性名 → 属性值
         */
        static void assertAttributes(SpanData span, Map<String, Object> expected) {
            org.assertj.core.api.Assertions.assertThat(span.attributes())
                    .as("span %s 的属性", span.name())
                    .containsAllEntriesOf(expected);
        }
    }
}
