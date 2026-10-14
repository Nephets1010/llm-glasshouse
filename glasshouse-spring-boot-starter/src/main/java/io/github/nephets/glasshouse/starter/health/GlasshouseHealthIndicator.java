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

package io.github.nephets.glasshouse.starter.health;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.nephets.glasshouse.core.attribution.MetricStore;
import io.github.nephets.glasshouse.core.pricing.PricingTable;
import io.github.nephets.glasshouse.starter.store.SpanRingBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * LLM 玻璃房健康检查。
 *
 * <p>暴露在 Actuator 的 {@code /actuator/health} 下，让运维在不加任何日志检索的情况下 确认「链路是否在收数据」「价格表是否有内容」「归因是否在工作」。
 *
 * <p><b>降级为 DEGRADED 而非 DOWN 的场景</b>：span 缓冲写满（丢弃数超过总写入数的 10%） 或价格表为空。前者是容量问题（该调大容量），后者是配置问题（该补价格），
 * 两者都不代表「服务不可用」，标成 DOWN 会导致 K8s 把 pod 摘掉，代价远大于收益。
 */
public class GlasshouseHealthIndicator implements HealthIndicator {

    /** 丢弃率超过该比例即标记 DEGRADED。 */
    public static final double DROPPED_RATIO_THRESHOLD = 0.10d;

    /** 健康状态：UP。 */
    private static final String STATUS_UP = "UP";

    /** 健康状态：DEGRADED。 */
    private static final String STATUS_DEGRADED = "DEGRADED";

    private static final Logger log = LoggerFactory.getLogger(GlasshouseHealthIndicator.class);

    private final SpanRingBuffer ringBuffer;
    private final PricingTable pricingTable;
    private final MetricStore metricStore;

    /**
     * 上一次上报的状态，用于只在<b>状态迁移</b>时打日志。
     *
     * <p>健康检查被 Actuator 按固定间隔轮询，若每次都打日志，DEGRADED 期间日志量会等于轮询次数， 真正有用的那一条（第一次降级）反而被淹掉。
     */
    private volatile String lastStatus = STATUS_UP;

    /**
     * 构造健康检查。
     *
     * @param ringBuffer span 缓冲，不可为 null
     * @param pricingTable 价格表，不可为 null
     * @param metricStore 归因存储，不可为 null
     */
    public GlasshouseHealthIndicator(
            SpanRingBuffer ringBuffer, PricingTable pricingTable, MetricStore metricStore) {
        this.ringBuffer = ringBuffer;
        this.pricingTable = pricingTable;
        this.metricStore = metricStore;
    }

    @Override
    public Health health() {
        long total = ringBuffer.totalWritten();
        long dropped = ringBuffer.droppedCount();
        double droppedRatio = total == 0L ? 0.0d : (double) dropped / (double) total;

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("spansBuffered", ringBuffer.size());
        details.put("spansTotalWritten", total);
        details.put("spansDropped", dropped);
        details.put("spanBufferCapacity", ringBuffer.capacity());
        details.put("droppedRatio", String.format(java.util.Locale.ROOT, "%.4f", droppedRatio));
        details.put("priceEntries", pricingTable.size());
        details.put("attributionKeys", metricStore.size());
        details.put(
                "semconvVersion",
                io.github.nephets.glasshouse.core.telemetry.GenaiAttributes.SEMCONV_VERSION);

        Health.Builder builder;
        String status;
        String reason;
        if (pricingTable.isEmpty()) {
            status = STATUS_DEGRADED;
            reason = "价格表为空，成本将全部记为 0";
        } else if (droppedRatio > DROPPED_RATIO_THRESHOLD) {
            status = STATUS_DEGRADED;
            reason = "span 缓冲丢弃率过高，建议调大 llm.glasshouse.ring-buffer-capacity";
        } else {
            status = STATUS_UP;
            reason = null;
        }
        if (STATUS_DEGRADED.equals(status)) {
            builder = Health.status(status).withDetail("reason", reason);
        } else {
            builder = Health.up();
        }
        String previous = lastStatus;
        if (!status.equals(previous)) {
            lastStatus = status;
            if (STATUS_DEGRADED.equals(status)) {
                log.warn("[glasshouse] 健康状态 UP → DEGRADED: {}（详情 {}）", reason, details);
            } else {
                log.info("[glasshouse] 健康状态 DEGRADED → UP 已恢复（详情 {}）", details);
            }
        }
        return builder.withDetails(details).build();
    }
}
