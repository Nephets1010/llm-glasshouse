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

import java.util.Locale;

/**
 * OTel / 归因 / 埋点配置项（纯 POJO，不依赖 Spring 的绑定注解）。
 *
 * <p>刻意做成普通 record 而不是 {@code @ConfigurationProperties} 类：core 层零 Spring 依赖是架构 §9.1
 * 划定的升级隔离边界。starter 模块负责把 {@code llm.glasshouse.*} 绑定到本 record 上。
 *
 * @param enabled 是否启用埋点；false 时所有埋点直接短路返回
 * @param applicationName 应用名，写入 span 的 resource 属性
 * @param sampleRatio 采样率，取值 0~1；1 表示全采
 * @param otlpEndpoint OTLP 导出端点，如 {@code http://localhost:4318/v1/traces}；为 null 时不导出
 * @param otlpTimeoutMillis OTLP 导出超时（毫秒）
 * @param ringBufferCapacity 内存 span 环形缓冲容量（条）
 * @param recordPayloadFull 是否记录完整 payload。<b>默认 false</b>——开启前必须确认已配置脱敏
 * @param payloadMaxLength payload 留痕的最大字符数，超出截断
 * @param redactPii 是否启用 PII 正则脱敏，<b>默认 true</b>
 * @param evalEnabled 是否启用评测引擎自动装配
 * @param contractPath 契约文件路径，用于校验 SPN-001 一致性
 */
public record OtelProperties(
        boolean enabled,
        String applicationName,
        double sampleRatio,
        String otlpEndpoint,
        int otlpTimeoutMillis,
        int ringBufferCapacity,
        boolean recordPayloadFull,
        int payloadMaxLength,
        boolean redactPii,
        boolean evalEnabled,
        String contractPath) {

    /** 默认应用名。 */
    public static final String DEFAULT_APPLICATION_NAME = "unknown-app";

    /** 默认内存缓冲容量：10 万条 span（架构 Q-D 拍板值）。 */
    public static final int DEFAULT_RING_BUFFER_CAPACITY = 100_000;

    /** 默认 OTLP 超时（毫秒）。 */
    public static final int DEFAULT_OTLP_TIMEOUT_MILLIS = 10_000;

    /** 默认 payload 留痕上限。 */
    public static final int DEFAULT_PAYLOAD_MAX_LENGTH = 4_000;

    /** 默认契约文件路径。 */
    public static final String DEFAULT_CONTRACT_PATH = "contract/contract-v1.json";

    /** 全默认配置。 */
    public static final OtelProperties DEFAULTS =
            new OtelProperties(
                    true,
                    DEFAULT_APPLICATION_NAME,
                    1.0d,
                    null,
                    DEFAULT_OTLP_TIMEOUT_MILLIS,
                    DEFAULT_RING_BUFFER_CAPACITY,
                    false,
                    DEFAULT_PAYLOAD_MAX_LENGTH,
                    true,
                    false,
                    DEFAULT_CONTRACT_PATH);

    /**
     * 紧凑构造器：字段兜底与合法性校验。
     *
     * @throws IllegalArgumentException 采样率不在 0~1、容量或长度非正
     */
    public OtelProperties {
        if (applicationName == null || applicationName.isBlank()) {
            applicationName = DEFAULT_APPLICATION_NAME;
        } else {
            applicationName = applicationName.trim();
        }
        if (sampleRatio < 0.0d || sampleRatio > 1.0d || Double.isNaN(sampleRatio)) {
            throw new IllegalArgumentException("sampleRatio 必须落在 [0,1]，实际为: " + sampleRatio);
        }
        if (otlpTimeoutMillis <= 0) {
            otlpTimeoutMillis = DEFAULT_OTLP_TIMEOUT_MILLIS;
        }
        if (ringBufferCapacity <= 0) {
            ringBufferCapacity = DEFAULT_RING_BUFFER_CAPACITY;
        }
        if (payloadMaxLength <= 0) {
            payloadMaxLength = DEFAULT_PAYLOAD_MAX_LENGTH;
        }
        if (contractPath == null || contractPath.isBlank()) {
            contractPath = DEFAULT_CONTRACT_PATH;
        }
        if (otlpEndpoint != null) {
            otlpEndpoint = otlpEndpoint.trim();
            if (otlpEndpoint.isEmpty()) {
                otlpEndpoint = null;
            }
        }
    }

    /**
     * 判断是否配置了 OTLP 导出。
     *
     * @return 有端点返回 {@code true}
     */
    public boolean otlpExportEnabled() {
        return otlpEndpoint != null;
    }

    /**
     * 返回采样率的百分数字符串，供日志与 Actuator 端点展示。
     *
     * @return 形如 {@code 100.00%} 的字符串
     */
    public String sampleRatioPercent() {
        return String.format(Locale.ROOT, "%.2f%%", sampleRatio * 100.0d);
    }

    /**
     * 创建一个只覆盖少量字段的副本，用于配置绑定时的默认值合并。
     *
     * @return 默认值配置
     */
    public static OtelProperties defaults() {
        return DEFAULTS;
    }
}
