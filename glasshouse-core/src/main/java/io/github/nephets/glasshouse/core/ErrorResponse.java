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

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 三仓统一的对外错误响应体（契约 {@code ERR-001}）。
 *
 * <p>序列化后的字段顺序固定为 {@code code / message / retryable / traceId / timestamp}，与契约 JSON 中 {@code
 * errorResponseShape} 一致。
 *
 * @param code 错误码
 * @param message 面向调用方的可读提示，不含堆栈与凭据
 * @param retryable 是否可重试，与 {@link ErrorCode#retryable()} 一致
 * @param traceId 关联的 W3C traceId，便于从错误反查链路
 * @param timestamp 发生时刻，ISO-8601 UTC
 */
@JsonPropertyOrder({"code", "message", "retryable", "traceId", "timestamp"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        @JsonProperty("code") ErrorCode code,
        @JsonProperty("message") String message,
        @JsonProperty("retryable") boolean retryable,
        @JsonProperty("traceId") String traceId,
        @JsonProperty("timestamp") Instant timestamp) {

    /**
     * 基于错误码构造响应，{@code retryable} 自动取自错误码定义。
     *
     * @param code 错误码，不可为 null
     * @param message 可读提示，不可为 null
     * @param traceId 关联 traceId，可为 null
     * @param timestamp 发生时刻，不可为 null
     * @return 构造好的响应体
     */
    public static ErrorResponse of(
            ErrorCode code, String message, String traceId, Instant timestamp) {
        Objects.requireNonNull(code, "code 不能为 null");
        Objects.requireNonNull(message, "message 不能为 null");
        Objects.requireNonNull(timestamp, "timestamp 不能为 null");
        return new ErrorResponse(code, message, code.retryable(), traceId, timestamp);
    }

    /**
     * 基于错误码构造响应并使用当前时刻。
     *
     * @param code 错误码，不可为 null
     * @param message 可读提示，不可为 null
     * @param traceId 关联 traceId，可为 null
     * @return 构造好的响应体
     */
    public static ErrorResponse of(ErrorCode code, String message, String traceId) {
        return of(code, message, traceId, Instant.now());
    }
}
