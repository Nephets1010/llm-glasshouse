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

package io.github.nephets.glasshouse.cli;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.nephets.glasshouse.starter.store.SpanRingBuffer.SpanData;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * span 的 JSON 编解码：CLI 读 JSONL 文件、demo 导出 JSONL 时共用。
 *
 * <p>抽成独立类而不是散落在两个命令里，是为了保证<b>写与读的字段名严格一致</b>—— 这是那种「改了写侧忘了改读侧」的经典 bug 来源。
 */
final class SpanJsonCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SpanJsonCodec() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 把 span 快照序列化为单行 JSON。
     *
     * @param span span 快照，不可为 null
     * @return 单行 JSON（不含换行）
     */
    static String toJson(SpanData span) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("traceId", span.traceId());
        map.put("spanId", span.spanId());
        map.put("parentSpanId", span.parentSpanId());
        map.put("name", span.name());
        map.put("operation", span.operation());
        map.put("target", span.target());
        map.put("startTime", span.startTime() == null ? null : span.startTime().toString());
        map.put("endTime", span.endTime() == null ? null : span.endTime().toString());
        map.put("durationMillis", span.durationMillis());
        map.put("status", span.status());
        map.put("errorMessage", span.errorMessage());
        map.put("attributes", span.attributes());
        try {
            return MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化 span 失败: " + span.spanId(), e);
        }
    }

    /**
     * 从 JSON 映射还原 span 快照。
     *
     * @param map JSON 解析出的映射，不可为 null
     * @return span 快照
     */
    @SuppressWarnings("unchecked")
    static SpanData toSpanData(Map<String, Object> map) {
        String traceId = str(map, "traceId");
        String spanId = str(map, "spanId");
        if (traceId == null || spanId == null) {
            throw new IllegalArgumentException("span JSON 缺少 traceId 或 spanId");
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        Object attrs = map.get("attributes");
        if (attrs instanceof Map<?, ?> m) {
            attributes.putAll((Map<String, Object>) m);
        }
        return new SpanData(
                traceId,
                spanId,
                str(map, "parentSpanId"),
                str(map, "name"),
                str(map, "operation"),
                str(map, "target"),
                instant(map, "startTime"),
                instant(map, "endTime"),
                attributes,
                str(map, "status"),
                str(map, "errorMessage"));
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static Instant instant(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(v));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
