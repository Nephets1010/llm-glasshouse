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

import java.io.IOException;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ErrorResponse 两个工厂方法与真实序列化形状的测试。
 *
 * <p>retryable 必须取自错误码，不能由调用方或默认值决定；traceId 允许为 null 且序列化时应当省掉。 下面 10 个错误码的 retryable 字面量来自契约
 * {@code contract/contract-v1.json} 的 ERR-001 段， 不从源码常量取值做镜像。
 */
class ErrorResponseTest {

    /** 固定时刻，便于把序列化结果写成逐字符的字面量。 */
    private static final Instant FIXED = Instant.parse("2026-01-02T03:04:05Z");

    /** 契约固定向量：W3C traceId。 */
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(instantModule());

    /**
     * core 的测试依赖里没有 jackson-datatype-jsr310，裸 ObjectMapper 无法序列化 Instant。 这里只给测试用的 mapper 注册一个「写
     * Instant.toString()」的序列化器，让断言聚焦在字段顺序、 字段名与 NON_NULL 省略上，而不是去校验日期格式。
     *
     * @return 仅供本测试类使用的 Jackson 模块
     */
    private static SimpleModule instantModule() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(
                Instant.class,
                new JsonSerializer<Instant>() {
                    @Override
                    public void serialize(
                            Instant value, JsonGenerator gen, SerializerProvider serializers)
                            throws IOException {
                        gen.writeString(value.toString());
                    }
                });
        return module;
    }

    @ParameterizedTest(name = "{0} 的 retryable 应为 {1}（契约 ERR-001）")
    @CsvSource({
        "SW_QUOTA_EXCEEDED, false",
        "SW_PROVIDER_UNAVAILABLE, true",
        "SW_PROVIDER_TIMEOUT, true",
        "SW_MODEL_UNAVAILABLE, true",
        "SW_CONTENT_FILTERED, false",
        "SW_BAD_REQUEST, false",
        "SW_TOOL_DENIED, false",
        "SW_APPROVAL_REQUIRED, false",
        "SW_APPROVAL_DENIED, false",
        "SW_INTERNAL, false"
    })
    @DisplayName("四参 of 的 retryable 取自错误码，10 个契约字面量逐项核对")
    void fourArgOfDerivesRetryableFromCode(String codeName, boolean expected) {
        ErrorResponse response =
                ErrorResponse.of(ErrorCode.valueOf(codeName), "m", TRACE_ID, FIXED);

        // of() 里把 retryable 写死 true 或 false，对应行就会变红。
        assertThat(response.retryable()).isEqualTo(expected);
    }

    @Test
    @DisplayName("四参 of 原样保存 code / message / traceId / timestamp")
    void fourArgOfKeepsPassedFields() {
        ErrorResponse response =
                ErrorResponse.of(
                        ErrorCode.SW_PROVIDER_UNAVAILABLE, "upstream unavailable", TRACE_ID, FIXED);

        assertThat(response.message()).isEqualTo("upstream unavailable");
        assertThat(response.traceId()).isEqualTo(TRACE_ID);
        assertThat(response.timestamp()).isEqualTo(FIXED);
        assertThat(response.retryable()).isTrue();
    }

    @Test
    @DisplayName("四参 of 允许 traceId 为 null，且不重试的错误码保持不可重试")
    void fourArgOfAllowsNullTraceId() {
        ErrorResponse response = ErrorResponse.of(ErrorCode.SW_INTERNAL, "boom", null, FIXED);

        // 若工厂对 traceId 也做 requireNonNull，这里会直接抛异常变红。
        assertThat(response.traceId()).isNull();
        assertThat(response.retryable()).isFalse();
        assertThat(response.message()).isEqualTo("boom");
        assertThat(response.timestamp()).isEqualTo(FIXED);
    }

    @Test
    @DisplayName("四参 of 的 code 为 null 抛 NPE")
    void fourArgOfRejectsNullCode() {
        assertThatThrownBy(() -> ErrorResponse.of(null, "m", TRACE_ID, FIXED))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("code 不能为 null");
    }

    @Test
    @DisplayName("四参 of 的 message 为 null 抛 NPE")
    void fourArgOfRejectsNullMessage() {
        assertThatThrownBy(() -> ErrorResponse.of(ErrorCode.SW_INTERNAL, null, TRACE_ID, FIXED))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("message 不能为 null");
    }

    @Test
    @DisplayName("四参 of 的 timestamp 为 null 抛 NPE")
    void fourArgOfRejectsNullTimestamp() {
        assertThatThrownBy(() -> ErrorResponse.of(ErrorCode.SW_INTERNAL, "m", TRACE_ID, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("timestamp 不能为 null");
    }

    @Test
    @DisplayName("三参 of 的 code 为 null 抛 NPE，说明它确实委托给四参校验")
    void threeArgOfRejectsNullCode() {
        assertThatThrownBy(() -> ErrorResponse.of(null, "m", TRACE_ID))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("code 不能为 null");
    }

    @Test
    @DisplayName("三参 of 的 message 为 null 抛 NPE")
    void threeArgOfRejectsNullMessage() {
        assertThatThrownBy(() -> ErrorResponse.of(ErrorCode.SW_INTERNAL, null, TRACE_ID))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("message 不能为 null");
    }

    @Test
    @DisplayName("三参 of 用当前时刻，retryable 仍取自错误码")
    void threeArgOfUsesCurrentTimestamp() {
        Instant before = Instant.now();
        ErrorResponse response = ErrorResponse.of(ErrorCode.SW_PROVIDER_TIMEOUT, "timeout", null);
        Instant after = Instant.now();

        // 若三参重载改用固定时刻（例如 Instant.EPOCH），isBetween 会变红。
        assertThat(response.timestamp()).isNotNull().isBetween(before, after);
        assertThat(response.retryable()).isTrue();
        assertThat(response.message()).isEqualTo("timeout");
        assertThat(response.traceId()).isNull();
    }

    @Test
    @DisplayName("真实序列化：null traceId 被省掉，其余字段保持契约顺序")
    void serializationOmitsNullTraceId() throws Exception {
        ErrorResponse response =
                ErrorResponse.of(
                        ErrorCode.SW_PROVIDER_UNAVAILABLE, "upstream unavailable", null, FIXED);

        // 删掉 @JsonInclude(NON_NULL) 会多出 "traceId":null；改 @JsonPropertyOrder 会打乱顺序。
        assertThat(MAPPER.writeValueAsString(response))
                .isEqualTo(
                        "{\"code\":\"SW_PROVIDER_UNAVAILABLE\","
                                + "\"message\":\"upstream unavailable\","
                                + "\"retryable\":true,"
                                + "\"timestamp\":\"2026-01-02T03:04:05Z\"}");
    }

    @Test
    @DisplayName("真实序列化：traceId 非空时五个字段按契约顺序齐全")
    void serializationIncludesAllFiveFieldsInOrder() throws Exception {
        ErrorResponse response =
                ErrorResponse.of(ErrorCode.SW_QUOTA_EXCEEDED, "quota", TRACE_ID, FIXED);

        assertThat(MAPPER.writeValueAsString(response))
                .isEqualTo(
                        "{\"code\":\"SW_QUOTA_EXCEEDED\","
                                + "\"message\":\"quota\","
                                + "\"retryable\":false,"
                                + "\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\","
                                + "\"timestamp\":\"2026-01-02T03:04:05Z\"}");
    }
}
