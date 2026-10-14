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

package io.github.nephets.glasshouse.starter;

import java.math.BigDecimal;

import io.github.nephets.glasshouse.core.telemetry.OtelProperties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Starter 配置项（{@code @ConfigurationProperties("llm.glasshouse")}）。
 *
 * <p>所有字段都有默认值，因此<b>零配置即可产 span</b>——这是「零侵入」承诺的一部分： 用户引入 starter 后不改一行 {@code application.yml}
 * 也能看到链路。
 *
 * <p>与 core 的 {@link OtelProperties} 分开是为了守住 core 的零 Spring 依赖边界： core 里是纯 record，这里才带
 * {@code @ConfigurationProperties} 注解。
 */
@ConfigurationProperties(prefix = GlasshouseProperties.PREFIX)
public class GlasshouseProperties {

    /** 配置前缀。 */
    public static final String PREFIX = "llm.glasshouse";

    /** 是否启用埋点。 */
    private boolean enabled = true;

    /** 应用名，写入 span 的 resource 属性。 */
    private String applicationName = OtelProperties.DEFAULT_APPLICATION_NAME;

    /** 采样率，0~1。 */
    private double sampleRatio = 1.0d;

    /** OTLP 导出端点，如 {@code http://localhost:4318/v1/traces}。 */
    private String otlpEndpoint;

    /** OTLP 导出超时（毫秒）。 */
    private int otlpTimeoutMillis = OtelProperties.DEFAULT_OTLP_TIMEOUT_MILLIS;

    /** 内存 span 环形缓冲容量。 */
    private int ringBufferCapacity = OtelProperties.DEFAULT_RING_BUFFER_CAPACITY;

    /** 是否记录完整 payload。<b>默认 false</b>，开启前必须确认已配置脱敏。 */
    private boolean recordPayloadFull = false;

    /** payload 留痕的最大字符数。 */
    private int payloadMaxLength = OtelProperties.DEFAULT_PAYLOAD_MAX_LENGTH;

    /** 是否启用 PII 正则脱敏，<b>默认 true</b>。 */
    private boolean redactPii = true;

    /** 脱敏保留尾部字符数。 */
    private int redactKeepTail = 4;

    /** 归因存储的最大键数量，超过后淘汰最旧的窗口。 */
    private int attributionMaxKeys = 20_000;

    /** 价格表热更新轮询间隔（秒）。 */
    private long pricingReloadSeconds = 300L;

    /** 是否启用评测引擎自动装配。 */
    private boolean evalEnabled = false;

    /**
     * 转为 core 的不可变配置对象。
     *
     * @return core 配置
     */
    public OtelProperties toOtelProperties() {
        return new OtelProperties(
                enabled,
                applicationName,
                sampleRatio,
                otlpEndpoint,
                otlpTimeoutMillis,
                ringBufferCapacity,
                recordPayloadFull,
                payloadMaxLength,
                redactPii,
                evalEnabled,
                OtelProperties.DEFAULT_CONTRACT_PATH);
    }

    /**
     * 返回成本指标的默认预算（CNY / 次）。
     *
     * @return 成本预算
     */
    public BigDecimal costBudgetCny() {
        return BigDecimal.ONE;
    }

    /**
     * 是否启用 OTLP 导出。
     *
     * @return 配置了端点返回 {@code true}
     */
    public boolean otlpExportEnabled() {
        return otlpEndpoint != null && !otlpEndpoint.isBlank();
    }

    /**
     * 是否启用全局埋点。
     *
     * @return 启用返回 {@code true}
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * 设置是否启用埋点。
     *
     * @param enabled 开关
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 返回应用名。
     *
     * @return 应用名
     */
    public String applicationName() {
        return applicationName;
    }

    /**
     * 设置应用名。
     *
     * @param applicationName 应用名
     */
    public void setApplicationName(String applicationName) {
        this.applicationName = applicationName;
    }

    /**
     * 返回采样率。
     *
     * @return 采样率
     */
    public double sampleRatio() {
        return sampleRatio;
    }

    /**
     * 设置采样率。
     *
     * @param sampleRatio 采样率，0~1
     */
    public void setSampleRatio(double sampleRatio) {
        this.sampleRatio = sampleRatio;
    }

    /**
     * 返回 OTLP 端点。
     *
     * @return 端点；未配置为 null
     */
    public String otlpEndpoint() {
        return otlpEndpoint;
    }

    /**
     * 设置 OTLP 端点。
     *
     * @param otlpEndpoint 端点
     */
    public void setOtlpEndpoint(String otlpEndpoint) {
        this.otlpEndpoint = otlpEndpoint;
    }

    /**
     * 返回 OTLP 超时。
     *
     * @return 超时（毫秒）
     */
    public int otlpTimeoutMillis() {
        return otlpTimeoutMillis;
    }

    /**
     * 设置 OTLP 超时。
     *
     * @param otlpTimeoutMillis 超时（毫秒）
     */
    public void setOtlpTimeoutMillis(int otlpTimeoutMillis) {
        this.otlpTimeoutMillis = otlpTimeoutMillis;
    }

    /**
     * 返回环形缓冲容量。
     *
     * @return 容量（条）
     */
    public int ringBufferCapacity() {
        return ringBufferCapacity;
    }

    /**
     * 设置环形缓冲容量。
     *
     * @param ringBufferCapacity 容量（条）
     */
    public void setRingBufferCapacity(int ringBufferCapacity) {
        this.ringBufferCapacity = ringBufferCapacity;
    }

    /**
     * 是否记录完整 payload。
     *
     * @return 开启返回 {@code true}
     */
    public boolean recordPayloadFull() {
        return recordPayloadFull;
    }

    /**
     * 设置是否记录完整 payload。
     *
     * @param recordPayloadFull 开关
     */
    public void setRecordPayloadFull(boolean recordPayloadFull) {
        this.recordPayloadFull = recordPayloadFull;
    }

    /**
     * 返回 payload 留痕上限。
     *
     * @return 字符数
     */
    public int payloadMaxLength() {
        return payloadMaxLength;
    }

    /**
     * 设置 payload 留痕上限。
     *
     * @param payloadMaxLength 字符数
     */
    public void setPayloadMaxLength(int payloadMaxLength) {
        this.payloadMaxLength = payloadMaxLength;
    }

    /**
     * 是否启用脱敏。
     *
     * @return 启用返回 {@code true}
     */
    public boolean redactPii() {
        return redactPii;
    }

    /**
     * 设置是否启用脱敏。
     *
     * @param redactPii 开关
     */
    public void setRedactPii(boolean redactPii) {
        this.redactPii = redactPii;
    }

    /**
     * 返回脱敏保留尾部字符数。
     *
     * @return 字符数
     */
    public int redactKeepTail() {
        return redactKeepTail;
    }

    /**
     * 设置脱敏保留尾部字符数。
     *
     * @param redactKeepTail 字符数
     */
    public void setRedactKeepTail(int redactKeepTail) {
        this.redactKeepTail = redactKeepTail;
    }

    /**
     * 返回归因存储最大键数。
     *
     * @return 键数
     */
    public int attributionMaxKeys() {
        return attributionMaxKeys;
    }

    /**
     * 设置归因存储最大键数。
     *
     * @param attributionMaxKeys 键数
     */
    public void setAttributionMaxKeys(int attributionMaxKeys) {
        this.attributionMaxKeys = attributionMaxKeys;
    }

    /**
     * 返回价格表热更新轮询间隔。
     *
     * <p><b>单位是秒，不是毫秒</b>。该值会被 {@code GlasshouseAutoConfiguration#reloadPricingTable} 上的 {@code
     * fixedDelayString = "${llm.glasshouse.pricing-reload-seconds:300}"} 配合 {@code timeUnit =
     * TimeUnit.SECONDS} 直接消费 —— 单位由 {@code timeUnit} 声明， 因此这里配 300 就是 300 秒（5 分钟）。不要再像早前那样补 "000"
     * 去凑毫秒，否则会被再放大 1000 倍。
     *
     * @return 秒数（默认 300）
     */
    public long pricingReloadSeconds() {
        return pricingReloadSeconds;
    }

    /**
     * 设置价格表热更新轮询间隔。
     *
     * @param pricingReloadSeconds 秒数；建议不小于 30，过小的间隔会让每次调度都重复读取 classpath 资源
     */
    public void setPricingReloadSeconds(long pricingReloadSeconds) {
        this.pricingReloadSeconds = pricingReloadSeconds;
    }

    /**
     * 是否启用评测引擎。
     *
     * @return 启用返回 {@code true}
     */
    public boolean evalEnabled() {
        return evalEnabled;
    }

    /**
     * 设置是否启用评测引擎。
     *
     * @param evalEnabled 开关
     */
    public void setEvalEnabled(boolean evalEnabled) {
        this.evalEnabled = evalEnabled;
    }
}
