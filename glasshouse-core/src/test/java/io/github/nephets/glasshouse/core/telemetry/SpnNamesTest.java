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

import io.opentelemetry.api.common.Attributes;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SPN-001 测试：span 命名模板与 GenAI 属性口径。
 *
 * <p>本类是「三仓 span 口径一致」的执行手段。①②必须与本仓产出同名 span， 否则跨仓看板与查询语句全部失效。
 */
class SpnNamesTest {

    @ParameterizedTest(name = "{1}({2}) → \"{3}\"")
    @CsvSource({
        "chat,           chat,           deepseek-chat,   'chat deepseek-chat'",
        "invoke_agent,   invokeAgent,    planner,         'invoke_agent planner'",
        "execute_tool,   executeTool,    kb_lookup,       'execute_tool kb_lookup'",
        "retrieval,      retrieve,       pgvector_kb,     'retrieval pgvector_kb'",
        "approval,       approval,       credit_officer,  'approval credit_officer'"
    })
    @DisplayName("★ 五个 operation 的 span 名与契约样例逐字一致")
    void spanNamesMatchContractSamples(
            String operation, String method, String target, String expected) {
        String actual =
                switch (method) {
                    case "chat" -> SpnNames.chat(target);
                    case "invokeAgent" -> SpnNames.invokeAgent(target);
                    case "executeTool" -> SpnNames.executeTool(target);
                    case "retrieve" -> SpnNames.retrieve(target);
                    case "approval" -> SpnNames.approval(target);
                    default -> throw new IllegalArgumentException("未知方法: " + method);
                };
        assertThat(actual).as("%s(%s)", method, target).isEqualTo(expected);
        assertThat(operation).isEqualTo(actual.split(" ")[0]);
    }

    @Test
    @DisplayName("命名模板固定为 {operation} {target}，单个空格分隔")
    void templateShape() {
        assertThat(SpnNames.TEMPLATE).isEqualTo("%s %s");
        assertThat(java.util.List.of(SpnNames.chat("m").split(" ")))
                .as("span 名由 operation 与 target 两段组成，中间单个空格")
                .containsExactly("chat", "m");
        assertThat(SpnNames.chat("  m  ")).as("target 前后空白被 trim").isEqualTo("chat m");
        assertThat(SpnNames.chat("  ")).as("空白 target 退化为只有 operation").isEqualTo("chat");
        assertThat(SpnNames.chat(null)).isEqualTo("chat");
    }

    @Test
    @DisplayName("operation 为空时 fail-fast")
    void emptyOperationFailsFast() {
        assertThatThrownBy(() -> SpnNames.name(null, "m"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operation");
        assertThatThrownBy(() -> SpnNames.name("  ", "m"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("operation 取值集合与契约一致")
    void operationValues() {
        assertThat(SpnNames.operations())
                .containsExactlyInAnyOrder(
                        SpnNames.OP_CHAT,
                        SpnNames.OP_INVOKE_AGENT,
                        SpnNames.OP_EXECUTE_TOOL,
                        SpnNames.OP_RETRIEVE,
                        SpnNames.OP_APPROVAL);
        for (String op : SpnNames.operations()) {
            assertThat(SpnNames.isKnownOperation(op)).isTrue();
        }
        assertThat(SpnNames.isKnownOperation("unknown_op")).isFalse();
        assertThat(SpnNames.isKnownOperation(null)).isFalse();
        assertThat(SpnNames.OP_CHAT).isEqualTo("chat");
        assertThat(SpnNames.OP_INVOKE_AGENT).isEqualTo("invoke_agent");
        assertThat(SpnNames.OP_EXECUTE_TOOL).isEqualTo("execute_tool");
        assertThat(SpnNames.OP_RETRIEVE).isEqualTo("retrieval");
        assertThat(SpnNames.OP_APPROVAL).isEqualTo("approval");
    }

    @Test
    @DisplayName("★ GenAI 属性：语义约定锁 1.41.0，新名 + 旧名双写")
    void genaiAttributeNames() {
        assertThat(GenaiAttributes.SEMCONV_VERSION).isEqualTo("1.41.0");
        assertThat(GenaiAttributes.USAGE_INPUT_TOKENS.getKey())
                .isEqualTo("gen_ai.usage.input_tokens");
        assertThat(GenaiAttributes.USAGE_OUTPUT_TOKENS.getKey())
                .isEqualTo("gen_ai.usage.output_tokens");
        assertThat(GenaiAttributes.USAGE_PROMPT_TOKENS_LEGACY.getKey())
                .isEqualTo("gen_ai.usage.prompt_tokens");
        assertThat(GenaiAttributes.USAGE_COMPLETION_TOKENS_LEGACY.getKey())
                .isEqualTo("gen_ai.usage.completion_tokens");

        assertThat(GenaiAttributes.OPERATION_NAME.getKey()).isEqualTo("gen_ai.operation.name");
        assertThat(GenaiAttributes.PROVIDER_NAME.getKey()).isEqualTo("gen_ai.provider.name");
        assertThat(GenaiAttributes.REQUEST_MODEL.getKey()).isEqualTo("gen_ai.request.model");
        assertThat(GenaiAttributes.RESPONSE_MODEL.getKey()).isEqualTo("gen_ai.response.model");
        assertThat(GenaiAttributes.RESPONSE_FINISH_REASONS.getKey())
                .isEqualTo("gen_ai.response.finish_reasons");
        assertThat(GenaiAttributes.REQUEST_TEMPERATURE.getKey())
                .isEqualTo("gen_ai.request.temperature");
        assertThat(GenaiAttributes.REQUEST_MAX_TOKENS.getKey())
                .isEqualTo("gen_ai.request.max_tokens");
        assertThat(GenaiAttributes.REQUEST_STREAM.getKey()).isEqualTo("gen_ai.request.stream");

        assertThat(GenaiAttributes.allNames()).hasSize(10);
        assertThat(GenaiAttributes.legacyNames()).hasSize(2);
        assertThat(GenaiAttributes.allNamesIncludingLegacy()).hasSize(12);
        assertThat(GenaiAttributes.isGenaiAttribute("gen_ai.request.model")).isTrue();
        assertThat(GenaiAttributes.isGenaiAttribute("swy.tenant.id")).isFalse();
        assertThat(GenaiAttributes.isGenaiAttribute(null)).isFalse();
    }

    @ParameterizedTest(name = "{0} 不是 GenAI 属性")
    @ValueSource(strings = {"swy.tenant.id", "user_id", "cost"})
    @DisplayName("自有属性不被误判为 GenAI 属性")
    void notGenai(String name) {
        assertThat(GenaiAttributes.isGenaiAttribute(name)).isFalse();
    }

    @Test
    @DisplayName("SwyAttributes：常量类不可实例化")
    void swyAttributesIsFinalUtility() {
        assertThat(java.lang.reflect.Modifier.isFinal(SwyAttributes.class.getModifiers())).isTrue();
        assertThat(SwyAttributes.PREFIX).isEqualTo("swy.");
        assertThat(SwyAttributes.all()).hasSameSizeAs(SwyAttributes.allNames());
        assertThatThrownBy(() -> SwyAttributes.string("no-dot"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("swy.");
        assertThatThrownBy(() -> SwyAttributes.string("swy"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SwyAttributes.string(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(SwyAttributes.string("swy.custom.key").getKey()).isEqualTo("swy.custom.key");
    }

    @Test
    @DisplayName("OtelProperties：字段兜底与校验")
    void otelPropertiesDefaults() {
        OtelProperties defaults = OtelProperties.DEFAULTS;
        assertThat(defaults.enabled()).isTrue();
        assertThat(defaults.applicationName()).isEqualTo(OtelProperties.DEFAULT_APPLICATION_NAME);
        assertThat(defaults.sampleRatio()).isEqualTo(1.0d);
        assertThat(defaults.ringBufferCapacity()).as("架构 Q-D 拍板：默认 10 万条 span").isEqualTo(100_000);
        assertThat(defaults.ringBufferCapacity())
                .isEqualTo(OtelProperties.DEFAULT_RING_BUFFER_CAPACITY);
        assertThat(defaults.redactPii()).as("PII 脱敏默认开启").isTrue();
        assertThat(defaults.recordPayloadFull()).as("★ 完整 payload 留痕默认关闭").isFalse();
        assertThat(defaults.otlpExportEnabled()).isFalse();
        assertThat(defaults.sampleRatioPercent()).isEqualTo("100.00%");
        assertThat(OtelProperties.defaults()).isSameAs(defaults);
        assertThat(defaults.contractPath()).isEqualTo(OtelProperties.DEFAULT_CONTRACT_PATH);
        assertThat(defaults.evalEnabled()).isFalse();

        OtelProperties blank =
                new OtelProperties(true, "   ", 0.5d, "   ", 0, 0, false, 0, true, false, "  ");
        assertThat(blank.applicationName()).isEqualTo(OtelProperties.DEFAULT_APPLICATION_NAME);
        assertThat(blank.otlpEndpoint()).as("空白端点归一化为 null").isNull();
        assertThat(blank.otlpTimeoutMillis()).isEqualTo(OtelProperties.DEFAULT_OTLP_TIMEOUT_MILLIS);
        assertThat(blank.ringBufferCapacity())
                .isEqualTo(OtelProperties.DEFAULT_RING_BUFFER_CAPACITY);
        assertThat(blank.payloadMaxLength()).isEqualTo(OtelProperties.DEFAULT_PAYLOAD_MAX_LENGTH);
        assertThat(blank.contractPath()).isEqualTo(OtelProperties.DEFAULT_CONTRACT_PATH);
        assertThat(blank.sampleRatioPercent()).isEqualTo("50.00%");
    }

    @ParameterizedTest(name = "采样率 {0} 非法")
    @ValueSource(doubles = {-0.1d, 1.1d})
    @DisplayName("OtelProperties：采样率越界 fail-fast")
    void invalidSampleRatio(double ratio) {
        assertThatThrownBy(
                        () ->
                                new OtelProperties(
                                        true, "app", ratio, null, 1000, 100, false, 100, true,
                                        false, "c.json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampleRatio");
    }

    @Test
    @DisplayName("OtelProperties：NaN 采样率也被拒绝")
    void nanSampleRatioRejected() {
        assertThatThrownBy(
                        () ->
                                new OtelProperties(
                                        true,
                                        "app",
                                        Double.NaN,
                                        null,
                                        1000,
                                        100,
                                        false,
                                        100,
                                        true,
                                        false,
                                        "c.json"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("GenaiSpanFactory：swyAttributes 构造与 span 名设置")
    void spanFactoryHelpers() {
        Attributes attributes =
                GenaiSpanFactory.swyAttributes(
                        b -> b.put(SwyAttributes.TENANT_ID, "vip").put(SwyAttributes.STEP_SEQ, 2L));
        assertThat(attributes.get(SwyAttributes.TENANT_ID)).isEqualTo("vip");
        assertThat(attributes.get(SwyAttributes.STEP_SEQ)).isEqualTo(2L);

        assertThat(GenaiSpanFactory.currentContext()).as("返回当前 OTel 上下文，不为 null").isNotNull();
        assertThat(GenaiSpanFactory.setSwy(null, s -> {})).as("null span 时原样返回，不抛异常").isNull();
        assertThat(GenaiSpanFactory.setGenai(null, s -> {})).isNull();
    }
}
