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

package io.github.nephets.glasshouse.core;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.nephets.glasshouse.core.attribution.AttributionDimension;
import io.github.nephets.glasshouse.core.audit.AuditEvent;
import io.github.nephets.glasshouse.core.pricing.CostCalculator;
import io.github.nephets.glasshouse.core.pricing.PriceEntry;
import io.github.nephets.glasshouse.core.pricing.PriceSource;
import io.github.nephets.glasshouse.core.pricing.PriceUnit;
import io.github.nephets.glasshouse.core.telemetry.GenaiAttributes;
import io.github.nephets.glasshouse.core.telemetry.SpnNames;
import io.github.nephets.glasshouse.core.telemetry.SwyAttributes;
import io.github.nephets.glasshouse.core.trace.TraceContext;
import io.github.nephets.glasshouse.core.trace.W3CTraceCodec;
import io.github.nephets.glasshouse.core.usage.Usage;
import io.github.nephets.glasshouse.core.usage.UsageSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 契约一致性门禁（架构 §2.6）。
 *
 * <p><b>这是三仓「只共享规范、不共享 jar」的执行手段</b>。本类做三件事：
 *
 * <ol>
 *   <li>逐字段断言本仓 record / enum 与 {@code contract/contract-v1.json} 一致 （字段名、类型、枚举值、pattern）
 *   <li>断言测试资源里的契约副本与仓库根目录的契约文件<b>字节一致</b>
 *   <li>断言 span 属性名与 SPN-001 的 {@code otelGenAiAttributes} / {@code customAttributes} 一致，且前缀合规
 * </ol>
 *
 * <p>任何一方改了字段名而没同步契约（或反之），CI 都会红。
 */
class ContractSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 契约固定向量：与三仓逐字符一致。 */
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private static final String SPAN_ID = "00f067aa0ba902b7";

    // ── 契约文件一致性 ────────────────────────────────────────────────

    @Test
    @DisplayName("★ 契约文件与测试资源副本字节一致")
    void contractFileIsByteIdentical() throws Exception {
        byte[] fromRepoRoot = readRepoRootContract();
        byte[] fromClasspath = readClasspathContract();

        assertThat(fromRepoRoot).as("仓库根目录必须有 contract/contract-v1.json").isNotEmpty();
        assertThat(fromClasspath).as("测试资源里必须有契约副本").isNotEmpty();
        assertThat(new String(fromClasspath, StandardCharsets.UTF_8))
                .as(
                        "contract/contract-v1.json 与 src/test/resources 下的副本必须字节一致。"
                                + "不一致说明有人改了契约但没同步副本（架构 §2.6）。")
                .isEqualTo(new String(fromRepoRoot, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("★ 三仓共享元信息正确")
    void contractMetadata() throws Exception {
        JsonNode spec = contract();
        assertThat(spec.path("contractVersion").asText()).isEqualTo("v1");
        assertThat(spec.path("contractRevision").asInt()).isEqualTo(1);
        assertThat(spec.path("family").asText()).isEqualTo("llm-infra");
        assertThat(spec.path("packagePrefix").asText()).isEqualTo("io.github.nephets");
        assertThat(scalars(spec.path("repositories")))
                .as("★ 契约必须声明三个仓库都遵守")
                .containsExactly("llm-switchyard", "agentswitchyard", "llm-glasshouse");
    }

    @Test
    @DisplayName("★ conventions 与实现一致")
    void conventionsMatchImplementation() throws Exception {
        JsonNode c = contract().path("conventions");

        assertThat(c.path("customAttributePrefix").asText()).isEqualTo(SwyAttributes.PREFIX);
        assertThat(c.path("currency").asText()).isEqualTo(PriceEntry.CURRENCY_CNY);
        assertThat(c.path("moneyScaleInternal").asInt())
                .as("★ 金额内部精度必须与 CostCalculator 一致")
                .isEqualTo(CostCalculator.COST_SCALE);
        assertThat(c.path("moneyScaleInternal").asInt()).isEqualTo(8);
        assertThat(c.path("moneyScaleDisplay").asInt()).isEqualTo(4);
        assertThat(c.path("spanNameTemplate").asText())
                .isEqualTo("{gen_ai.operation.name} {target}");
        assertThat(c.path("errorCodePrefix").asText()).isEqualTo("SW_");

        assertThat(scalars(c.path("providerIdValues")))
                .as("★ 八家供应商取值必须与契约一致")
                .containsExactlyInAnyOrder(
                        "openai",
                        "anthropic",
                        "dashscope",
                        "deepseek",
                        "zhipu",
                        "moonshot",
                        "doubao",
                        "ollama");
        assertThat(c.path("identifierStyle").asText())
                .as("★ 标识符风格约定属于契约")
                .contains("lowerCamelCase")
                .contains("UPPER_SNAKE_CASE");
    }

    // ── CTX-001 TraceContext ───────────────────────────────────────────

    @Test
    @DisplayName("★ CTX-001 TraceContext 字段逐项一致")
    void traceContextMatchesContract() throws Exception {
        JsonNode spec = contract().path("contracts").path("CTX-001");
        assertThat(spec.path("title").asText()).isEqualTo("TraceContext");
        assertThat(spec.path("javaType").asText())
                .as("★ javaType 必须指向本仓的包")
                .isEqualTo("io.github.nephets.glasshouse.core.trace.TraceContext");

        assertRecordComponents(
                TraceContext.class,
                spec.path("fields"),
                List.of("traceId", "spanId", "parentSpanId", "traceFlags", "traceState"));

        JsonNode fields = spec.path("fields");
        assertThat(fields.get(0).path("pattern").asText()).isEqualTo("^[0-9a-f]{32}$");
        assertThat(fields.get(1).path("pattern").asText()).isEqualTo("^[0-9a-f]{16}$");
        assertThat(scalars(fields.get(3).path("enum"))).containsExactly("00", "01");

        JsonNode propagation = spec.path("propagation");
        assertThat(scalars(propagation.path("httpHeaders")))
                .as("★ 传播头必须包含 baggage，否则跨进程维度会丢")
                .containsExactly("traceparent", "tracestate", "baggage");
        assertThat(scalars(propagation.path("mqHeaders")))
                .as("★ MQ 至少要带 traceparent + baggage")
                .contains("traceparent", "baggage");
        assertThat(propagation.path("traceparentFormat").asText())
                .isEqualTo("00-{traceId}-{spanId}-{traceFlags}");
        assertThat(propagation.path("inProcess").asText())
                .as("★ 必须约定 InheritableThreadLocal（虚拟线程安全），架构 §8.3")
                .contains("InheritableThreadLocal");
    }

    @Test
    @DisplayName("★ traceparent 编解码与契约格式逐字符一致")
    void traceparentMatchesContractFormat() {
        assertThat(W3CTraceCodec.encodeTraceparent(TraceContext.root(TRACE_ID, SPAN_ID, true)))
                .as("★ 这是跨三仓链路追踪能对上的唯一依据")
                .isEqualTo("00-" + TRACE_ID + "-" + SPAN_ID + "-01");
        assertThat(W3CTraceCodec.encodeTraceparent(TraceContext.root(TRACE_ID, SPAN_ID, false)))
                .isEqualTo("00-" + TRACE_ID + "-" + SPAN_ID + "-00");
    }

    // ── USG-001 Usage ─────────────────────────────────────────────────

    @Test
    @DisplayName("★ USG-001 Usage 字段逐项一致，且 totalTokens 是不变量")
    void usageMatchesContract() throws Exception {
        JsonNode spec = contract().path("contracts").path("USG-001");
        assertThat(spec.path("title").asText()).isEqualTo("Usage");
        assertThat(spec.path("javaType").asText())
                .isEqualTo("io.github.nephets.glasshouse.core.usage.Usage");

        assertRecordComponents(
                Usage.class,
                spec.path("fields"),
                List.of(
                        "inputTokens",
                        "outputTokens",
                        "reasoningTokens",
                        "cachedInputTokens",
                        "totalTokens",
                        "usageSource",
                        "reasoningEstimated",
                        "streamUsageReported"));

        assertThat(spec.path("invariant").asText())
                .as("★ 不变量原文属于契约，改写即破坏跨仓一致性")
                .contains("totalTokens MUST be recomputed");

        for (int i = 0; i < 5; i++) {
            assertThat(spec.path("fields").get(i).path("unit").asText())
                    .as("token 相关字段单位必须是 token")
                    .isEqualTo("token");
        }

        assertThat(scalars(spec.path("enums").path("UsageSource")))
                .as("★ UsageSource 枚举值与顺序都是契约的一部分")
                .isEqualTo(Arrays.stream(UsageSource.values()).map(Enum::name).toList());

        Usage usage =
                new Usage(
                        1000L,
                        200L,
                        50L,
                        100L,
                        999_999L,
                        UsageSource.VENDOR_REPORTED,
                        false,
                        false);
        assertThat(usage.totalTokens())
                .as("★ totalTokens 必须重算，忽略上游给的值（USG-001 不变量）")
                .isEqualTo(1250L);
        assertThat(usage.billableInputTokens()).as("★ 缓存命中的输入不能重复计费").isEqualTo(900L);
    }

    @Test
    @DisplayName("★ USG-001 字段映射与计量误差门槛")
    void usageMappingsAndAccuracyGate() throws Exception {
        JsonNode spec = contract().path("contracts").path("USG-001");
        JsonNode mappings = spec.path("fieldMappings");
        assertThat(mappings.isMissingNode()).as("★ 契约必须给出字段映射，否则各仓各写一套，跨仓口径必然漂移").isFalse();

        // 契约只列「结构与其他家不同」的供应商。zhipu / deepseek / moonshot / doubao 与
        // dashscope 同构（input_tokens / output_tokens），共用 CnUsageNormalizer 一套口径，
        // 因此它们的字段映射与 dashscope 完全相同——这正是「国产五家统一口径」的价值所在。
        assertThat(scalars(mappings.path("inputTokens")))
                .as("★ 契约声明的供应商字段映射必须逐项一致")
                .containsExactly(
                        "openai.prompt_tokens",
                        "anthropic.input_tokens",
                        "dashscope.input_tokens",
                        "ollama.prompt_eval_count");
        assertThat(scalars(mappings.path("outputTokens")))
                .containsExactly(
                        "openai.completion_tokens",
                        "anthropic.output_tokens",
                        "dashscope.output_tokens",
                        "ollama.eval_count");

        JsonNode gate = spec.path("accuracyGate");
        assertThat(gate.path("metric").asText())
                .as("★ 计量误差的定义属于契约：与供应商账单比对")
                .contains("vendor billing");
        assertThat(gate.path("maxErrorPercent").asDouble())
                .as("★ 计量误差门槛是 0.5%（PRD P0-3 写的是「<1%」，契约收紧到 0.5%）")
                .isEqualTo(0.5d);
        assertThat(gate.path("trafficSize").asInt())
                .as("★ 误差验证的样本量属于契约：10 万条真实流量")
                .isEqualTo(100_000);
        assertThat(gate.path("streamingMustBeNonZero").asBoolean())
                .as("★ 流式计量必须非零 —— 这是 PRD 痛点 4 的机器可判定验收点")
                .isTrue();
    }

    // ── PRC-001 PriceEntry + CostCalculator ───────────────────────────

    @Test
    @DisplayName("★ PRC-001 PriceEntry 字段与成本公式一致")
    void priceEntryMatchesContract() throws Exception {
        JsonNode spec = contract().path("contracts").path("PRC-001");
        assertThat(spec.path("title").asText()).isEqualTo("PriceEntry + CostCalculator");
        assertThat(spec.path("javaType").asText())
                .isEqualTo("io.github.nephets.glasshouse.core.pricing.PriceEntry");

        assertRecordComponents(
                PriceEntry.class,
                spec.path("fields"),
                List.of(
                        "providerId",
                        "modelId",
                        "currency",
                        "unit",
                        "inputPricePerUnit",
                        "outputPricePerUnit",
                        "reasoningPricePerUnit",
                        "cachedInputPricePerUnit",
                        "effectiveFrom",
                        "source"));

        assertThat(spec.path("formula").asText())
                .as("★ 成本公式原文属于契约：缓存命中必须走缓存价，且 scale=8 HALF_UP")
                .contains("cachedInputTokens")
                .contains("scale=8 HALF_UP");

        assertThat(spec.path("priceTableHotReload").asBoolean())
                .as("★ 价格表热更新是首版能力，契约必须写明")
                .isTrue();
        assertThat(spec.path("attributionErrorGate").path("maxErrorPercent").asDouble())
                .as("★ 成本误差验收门槛是 1%")
                .isEqualTo(1.0d);

        for (int i = 4; i <= 7; i++) {
            assertThat(spec.path("fields").get(i).path("unit").asText())
                    .as("★ 价格字段单位以契约为准（三仓逐字符一致，不做单位改写）")
                    .isEqualTo("CNY/1000token");
        }
    }

    @Test
    @DisplayName("★ 成本公式实测：缓存不重复计费，精度 scale=8")
    void costFormulaBehaviour() {
        PriceEntry price =
                new PriceEntry(
                        "deepseek",
                        "deepseek-chat",
                        "CNY",
                        PriceUnit.PER_1K_TOKENS,
                        new BigDecimal("0.0020"),
                        new BigDecimal("0.0008"),
                        null,
                        new BigDecimal("0.00005"),
                        LocalDate.parse("2026-01-01"),
                        PriceSource.OFFICIAL);

        // 计费输入 700 + 缓存 300 + 输出 1000
        // = 700/1000×0.0020 + 300/1000×0.00005 + 1000/1000×0.0008
        // = 0.0014 + 0.000015 + 0.0008 = 0.002215
        Usage usage =
                new Usage(1000L, 1000L, 0L, 300L, 0L, UsageSource.VENDOR_REPORTED, false, false);
        assertThat(CostCalculator.getInstance().compute(usage, price))
                .as("★ 缓存命中的 300 token 必须按缓存价计，而不是按输入价")
                .isEqualByComparingTo("0.00221500");
        assertThat(CostCalculator.COST_SCALE).isEqualTo(8);
    }

    @Test
    @DisplayName("PriceSource / PriceUnit 枚举与契约一致")
    void priceEnumsMatchContract() throws Exception {
        JsonNode spec = contract().path("contracts").path("PRC-001");
        assertThat(scalars(spec.path("enums").path("PriceSource")))
                .isEqualTo(Arrays.stream(PriceSource.values()).map(Enum::name).toList());
        assertThat(scalars(spec.path("enums").path("PriceUnit")))
                .isEqualTo(Arrays.stream(PriceUnit.values()).map(Enum::name).toList());
    }

    // ── EVT-001 AuditEvent ────────────────────────────────────────────

    @Test
    @DisplayName("★ EVT-001 AuditEvent 字段逐项一致")
    void auditEventMatchesContract() throws Exception {
        JsonNode spec = contract().path("contracts").path("EVT-001");
        assertThat(spec.path("title").asText()).isEqualTo("AuditEvent");
        assertThat(spec.path("javaType").asText())
                .isEqualTo("io.github.nephets.glasshouse.core.audit.AuditEvent");

        assertRecordComponents(
                AuditEvent.class,
                spec.path("fields"),
                List.of(
                        "eventType",
                        "traceId",
                        "spanId",
                        "occurredAt",
                        "tenantId",
                        "bizLine",
                        "feature",
                        "promptVersion",
                        "agentId",
                        "flowInstanceId",
                        "stepId",
                        "stepSeq",
                        "usage",
                        "costAmount",
                        "costCurrency",
                        "payloadDigest",
                        "payloadFull"));

        assertThat(spec.path("nullPlaceholderForbidden").asText())
                .as("★ 禁止用 unknown 占位，否则污染归因统计")
                .contains("禁止填")
                .contains("unknown");

        assertThat(spec.path("fields").get(15).path("pattern").asText())
                .as("★ payloadDigest 格式属于契约")
                .isEqualTo("^sha256:[0-9a-f]{64}$");
        assertThat(AuditEvent.DIGEST_PREFIX).isEqualTo("sha256:");
    }

    // ── ERR-001 ErrorCode ─────────────────────────────────────────────

    @Test
    @DisplayName("★ ERR-001 ErrorCode 枚举、HTTP 状态码与 retryable 一致")
    void errorCodeMatchesContract() throws Exception {
        JsonNode spec = contract().path("contracts").path("ERR-001");
        assertThat(spec.path("title").asText()).isEqualTo("ErrorCode");
        assertThat(spec.path("kind").asText()).isEqualTo("enum");

        List<String> declared = new ArrayList<>();
        for (JsonNode v : spec.path("values")) {
            declared.add(v.path("name").asText());
            ErrorCode code = ErrorCode.valueOf(v.path("name").asText());
            assertThat(code.httpStatus())
                    .as("%s 的 HTTP 状态码属于契约", code)
                    .isEqualTo(v.path("httpStatus").asInt());
            assertThat(code.retryable())
                    .as("%s 的 retryable 属于契约", code)
                    .isEqualTo(v.path("retryable").asBoolean());
            assertThat(v.path("semantics").asText()).as("%s 必须写明语义", code).isNotBlank();
        }
        assertThat(declared)
                .as("★ 枚举值与顺序都是契约的一部分")
                .isEqualTo(Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
        assertThat(scalars(spec.path("responseBodyShape")))
                .as("★ 错误响应体字段顺序属于契约")
                .containsExactly("code", "message", "retryable", "traceId", "timestamp");
    }

    @Test
    @DisplayName("ErrorResponse 的 @JsonPropertyOrder 与契约响应体形状一致")
    void errorResponseShapeMatchesContract() throws Exception {
        List<String> declared =
                scalars(contract().path("contracts").path("ERR-001").path("responseBodyShape"));
        com.fasterxml.jackson.annotation.JsonPropertyOrder order =
                ErrorResponse.class.getAnnotation(
                        com.fasterxml.jackson.annotation.JsonPropertyOrder.class);
        assertThat(order).isNotNull();
        assertThat(List.of(order.value())).as("★ 错误响应体字段顺序必须与契约一致，否则下游解析会错位").isEqualTo(declared);
    }

    // ── SPN-001 span 命名与属性 ────────────────────────────────────────

    @Test
    @DisplayName("★ SPN-001 span 命名模板与五个 operation 一致")
    void spanNamingMatchesContract() throws Exception {
        JsonNode spec = contract().path("contracts").path("SPN-001");
        assertThat(spec.path("definedBy").asText())
                .as("★ SPN-001 由本仓定义，另外两仓遵循")
                .isEqualTo("llm-glasshouse");
        assertThat(scalars(spec.path("followedBy")))
                .containsExactly("llm-switchyard", "agentswitchyard");
        assertThat(spec.path("spanNameTemplate").asText())
                .isEqualTo("{gen_ai.operation.name} {target}");

        for (JsonNode op : spec.path("operations")) {
            String operationName = op.path("operationName").asText();
            String example = op.path("example").asText();
            // 契约里 target 写的是「该 span 第二个字段的语义名」（modelId / agentId /
            // toolName / retrieverName / approverRole），example 才是真实取值样例。
            // 用 example 的第二段当 target 回填模板，必须逐字符复现 example。
            String exampleTarget = example.substring(example.indexOf(' ') + 1);
            assertThat(SpnNames.name(operationName, exampleTarget))
                    .as("★ 契约样例 %s 必须逐字符复现", example)
                    .isEqualTo(example);
            assertThat(exampleTarget)
                    .as("★ target 的语义名 %s 应说明该 span 第二个字段的来源", op.path("target").asText())
                    .isNotBlank();
        }

        assertThat(SpnNames.operations())
                .containsExactlyInAnyOrderElementsOf(
                        List.of("chat", "invoke_agent", "execute_tool", "retrieval", "approval"));
    }

    @Test
    @DisplayName("★ SPN-001 GenAI 属性名逐个一致（含旧名冗余）")
    void genaiAttributesMatchContract() throws Exception {
        JsonNode spec = contract().path("contracts").path("SPN-001");

        List<String> declared = scalars(spec.path("otelGenAiAttributes"));
        // 契约用 "[]" 后缀标注数组类型，实现里的 AttributeKey 类型本身就是数组，
        // 因此比对时去掉后缀——属性名字符串必须逐字符相同，类型由 AttributeKey 保证。
        List<String> declaredPlain = new ArrayList<>();
        for (String name : declared) {
            declaredPlain.add(name.endsWith("[]") ? name.substring(0, name.length() - 2) : name);
        }
        assertThat(GenaiAttributes.allNames())
                .as("★ GenAI 属性名集合必须与契约逐字符一致（契约用 [] 标注数组类型）")
                .containsExactlyInAnyOrderElementsOf(declaredPlain);

        assertThat(GenaiAttributes.legacyNames())
                .as("★ 旧名冗余一个发布周期，集合必须与契约一致")
                .containsExactlyInAnyOrderElementsOf(
                        scalars(spec.path("deprecatedAliasesRedundantForOneRelease")));
        assertThat(GenaiAttributes.allNamesIncludingLegacy())
                .hasSize(
                        scalars(spec.path("otelGenAiAttributes")).size()
                                + scalars(spec.path("deprecatedAliasesRedundantForOneRelease"))
                                        .size());
    }

    @Test
    @DisplayName("★ SPN-001 swy.* 属性名逐个一致，且都带 swy. 前缀")
    void swyAttributesMatchContract() throws Exception {
        JsonNode spec = contract().path("contracts").path("SPN-001");

        // 契约按属性声明「哪几仓可以写」。三仓共有的（tenant.id / biz.line / feature /
        // prompt.version / payload.*）本仓必须支持；只在 agentswitchyard 里的
        // （flow.instance.id / step.* / approval.state）本仓不写但必须<b>认识</b>，
        // 否则收到上游传来的这些维度时会当成未知属性丢掉。
        List<String> ours = new ArrayList<>();
        List<String> othersOnly = new ArrayList<>();
        for (JsonNode a : spec.path("customAttributes")) {
            String name = a.path("name").asText();
            assertThat(name)
                    .as("★ 自有属性必须带 swy. 前缀：%s", name)
                    .startsWith(SwyAttributes.PREFIX)
                    .contains(".");
            assertThat(SwyAttributes.isSwyAttribute(name))
                    .as("实现必须认识契约里的属性 %s（否则收到会被静默丢弃）", name)
                    .isTrue();
            assertThat(a.path("type").asText()).isNotBlank();
            if (scalars(a.path("in")).contains("glasshouse")) {
                ours.add(name);
            } else {
                othersOnly.add(name);
            }
        }

        assertThat(SwyAttributes.writableNames())
                .as("★ 本仓实际会写的 swy.* 属性必须正好是契约声明的那批")
                .containsExactlyInAnyOrderElementsOf(ours);
        assertThat(SwyAttributes.allNames())
                .as("★ 实现必须认识契约里的全部 swy.* 属性（包括其他仓才写的那些）")
                .containsExactlyInAnyOrderElementsOf(scalarsNamed(spec));
        assertThat(othersOnly).as("这些属性由其他仓写，本仓只负责认识").isNotEmpty();

        assertThat(SwyAttributes.isSwyAttribute("swy")).as("禁止裸 swy").isFalse();
        assertThat(SwyAttributes.isSwyAttribute("tenant_id")).isFalse();
    }

    @Test
    @DisplayName("★ 归因维度枚举与「五维下钻」承诺一致")
    void attributionDimensions() {
        List<String> business = new ArrayList<>();
        for (AttributionDimension d : AttributionDimension.all()) {
            if (d == AttributionDimension.PROVIDER || d == AttributionDimension.MODEL) {
                continue;
            }
            business.add(d.columnName());
        }
        assertThat(business)
                .as("★ PRD P0-3：租户 / 业务线 / 业务功能 / Prompt 版本 / Agent 步骤 五维必须都能下钻")
                .hasSizeGreaterThanOrEqualTo(5);
    }

    // ── 辅助 ──────────────────────────────────────────────────────────

    /**
     * 断言 record 的组件名、顺序与契约字段逐项一致。
     *
     * @param type record 类型
     * @param fields 契约里的 fields 数组
     * @param expected 期望的字段名顺序
     */
    private static void assertRecordComponents(
            Class<?> type, JsonNode fields, List<String> expected) {
        RecordComponent[] components = type.getRecordComponents();
        assertThat(fields.size())
                .as("契约里 %s 的字段数与 record 组件数不一致", type.getSimpleName())
                .isEqualTo(components.length);
        assertThat(expected).hasSize(components.length);

        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            JsonNode field = fields.get(i);
            assertThat(component.getName())
                    .as("%s 第 %d 个字段名必须与契约逐字符一致", type.getSimpleName(), i)
                    .isEqualTo(field.path("name").asText())
                    .isEqualTo(expected.get(i));
            assertThat(field.path("type").asText())
                    .as("%s.%s 的类型必须与契约一致", type.getSimpleName(), component.getName())
                    .isEqualTo(expectedTypeName(component, field.path("type").asText()));
        }
    }

    /**
     * 把 Java 类型名映射到契约里用的类型名。
     *
     * @param component record 组件
     * @param declared 契约声明的类型名
     * @return 应匹配的类型名
     */
    private static String expectedTypeName(RecordComponent component, String declared) {
        String actual = component.getType().getSimpleName();
        return "long".equals(declared) && "Long".equals(actual) ? "long" : declared;
    }

    /**
     * 取出契约里全部 customAttributes 的属性名。
     *
     * @param spec SPN-001 契约节点
     * @return 属性名列表
     */
    private static List<String> scalarsNamed(JsonNode spec) {
        List<String> names = new ArrayList<>();
        spec.path("customAttributes").forEach(n -> names.add(n.path("name").asText()));
        return names;
    }

    /**
     * 把 JSON 数组节点转成字符串列表。
     *
     * @param node JSON 数组节点
     * @return 字符串列表；节点缺失返回空列表
     */
    private static List<String> scalars(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> result.add(n.asText()));
        }
        return result;
    }

    /**
     * 读取仓库根目录的契约文件。
     *
     * @return 文件字节
     * @throws Exception 读取失败
     */
    private static byte[] readRepoRootContract() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && root != null; i++) {
            Path candidate = root.resolve("contract/contract-v1.json");
            if (Files.isRegularFile(candidate)) {
                return Files.readAllBytes(candidate);
            }
            root = root.getParent();
        }
        throw new IllegalStateException("找不到仓库根目录的 contract/contract-v1.json（已向上查找 4 层）");
    }

    /**
     * 读取测试资源里的契约副本。
     *
     * @return 文件字节
     * @throws Exception 读取失败
     */
    private static byte[] readClasspathContract() throws Exception {
        try (java.io.InputStream in =
                ContractSchemaTest.class
                        .getClassLoader()
                        .getResourceAsStream("contract/contract-v1.json")) {
            assertThat(in).as("测试资源里必须有 contract/contract-v1.json 副本").isNotNull();
            return in.readAllBytes();
        }
    }

    /**
     * 读取并解析仓库根目录的契约。
     *
     * @return 契约 JSON
     * @throws Exception 读取失败
     */
    private static JsonNode contract() throws Exception {
        return MAPPER.readTree(readRepoRootContract());
    }
}
