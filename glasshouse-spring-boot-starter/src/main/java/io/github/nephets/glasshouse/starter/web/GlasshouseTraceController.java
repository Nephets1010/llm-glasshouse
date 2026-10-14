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

package io.github.nephets.glasshouse.starter.web;

import java.util.List;
import java.util.Map;

import io.github.nephets.glasshouse.core.attribution.AttributionDimension;
import io.github.nephets.glasshouse.core.attribution.AttributionReport;
import io.github.nephets.glasshouse.starter.GlasshouseProperties;
import io.github.nephets.glasshouse.starter.store.SpanRingBuffer;
import io.github.nephets.glasshouse.starter.store.SpanTreeQuery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 链路与成本的只读 JSON 端点（架构 Q-C：首版只出 JSON 端点，SPA 放 P1）。
 *
 * <p>存在的意义是让 README 的 span 树截图<b>不依赖任何外部可观测后端</b>： 应用起起来，浏览器打开 {@code
 * /_glasshouse/trace/{traceId}?tree=true&cost=true} 就有内容。
 *
 * <p>端点是<b>只读</b>的，且不返回 Prompt 原文——它读的是 span 缓冲里的摘要属性， 不存在「通过端点泄漏 Prompt」的路径。
 */
@RestController
@ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(
        prefix = GlasshouseProperties.PREFIX,
        name = "enabled",
        matchIfMissing = true,
        havingValue = "true")
public class GlasshouseTraceController {

    private static final Logger log = LoggerFactory.getLogger(GlasshouseTraceController.class);

    private final SpanTreeQuery spanTreeQuery;
    private final SpanRingBuffer ringBuffer;
    private final io.github.nephets.glasshouse.core.attribution.AttributionEngine attributionEngine;

    /**
     * 构造控制器。
     *
     * @param spanTreeQuery span 树查询器
     * @param ringBuffer span 缓冲
     * @param attributionEngine 归因引擎
     */
    public GlasshouseTraceController(
            SpanTreeQuery spanTreeQuery,
            SpanRingBuffer ringBuffer,
            io.github.nephets.glasshouse.core.attribution.AttributionEngine attributionEngine) {
        this.spanTreeQuery = spanTreeQuery;
        this.ringBuffer = ringBuffer;
        this.attributionEngine = attributionEngine;
    }

    /**
     * 返回一条链路的 span 树。
     *
     * @param traceId traceId
     * @return 渲染后的文本树（含 slowestSpan 与 totalCostCny 两个结论行）
     */
    @GetMapping("/_glasshouse/trace/{traceId}")
    public Map<String, Object> trace(
            @PathVariable String traceId, @RequestParam(defaultValue = "true") boolean cost) {
        if (log.isDebugEnabled()) {
            log.debug("[glasshouse] GET /_glasshouse/trace/{}?cost={}", traceId, cost);
        }
        List<io.github.nephets.glasshouse.starter.store.SpanRingBuffer.SpanData> spans =
                spanTreeQuery.spansOf(traceId);
        if (spans.isEmpty()) {
            // 未命中是常态（缓冲是环形的，老 trace 会被覆盖），因此只记 debug，
            // 但必须记：否则「为什么这个 trace 查不到」只能靠猜。
            log.debug("[glasshouse] traceId={} 在缓冲中没有命中任何 span", traceId);
        }
        return Map.of(
                "traceId", traceId,
                "tree", spanTreeQuery.render(traceId, cost),
                "spans", spans,
                "totalCostCny", spanTreeQuery.totalCost(traceId).toPlainString(),
                "tokens", spanTreeQuery.tokenSummary(traceId));
    }

    /**
     * 列出缓冲中最近的 traceId。
     *
     * @param limit 返回条数上限
     * @return traceId 列表（按最近出现顺序）
     */
    @GetMapping("/_glasshouse/traces")
    public Map<String, Object> traces(@RequestParam(defaultValue = "20") int limit) {
        List<String> traceIds =
                ringBuffer.byTrace().keySet().stream().sorted().limit(Math.max(1, limit)).toList();
        return Map.of("traceIds", traceIds, "spanCount", ringBuffer.size());
    }

    /**
     * 按维度返回成本归因报表。
     *
     * @param dimension 归因维度名，如 {@code tenant} / {@code feature} / {@code promptVersion}
     * @return 归因报表
     */
    @GetMapping("/_glasshouse/cost")
    public AttributionReport cost(@RequestParam(defaultValue = "tenant") String dimension) {
        AttributionDimension dim = AttributionDimension.from(dimension);
        if (dim == null) {
            // ★ 这里返回空报表是「接口不该 500」的取舍，但绝不能静默：
            //   前端拿到空报表会以为「成本就是 0」，必须留下 warn 让人能查到维度名写错了。
            log.warn("[glasshouse] 未知的归因维度 \"{}\"，返回空报表；可用维度：{}", dimension, dimensions());
            return AttributionReport.empty(AttributionDimension.MODEL);
        }
        return attributionEngine.report(dim);
    }

    /**
     * 返回组件自检信息。
     *
     * @return 版本、semconv 版本、缓冲与价格表状态
     */
    @GetMapping("/_glasshouse/info")
    public Map<String, Object> info() {
        return Map.of(
                "name", "llm-glasshouse",
                "version", "0.1.0",
                "semconvVersion",
                        io.github.nephets.glasshouse.core.telemetry.GenaiAttributes.SEMCONV_VERSION,
                "spanBufferCapacity", ringBuffer.capacity(),
                "spansBuffered", ringBuffer.size(),
                "spansDropped", ringBuffer.droppedCount(),
                "priceEntries", attributionEngine.pricingTable().size());
    }

    /**
     * 返回可用的归因维度列表，供 CLI 与前端做下拉框。
     *
     * @return 维度名列表
     */
    @GetMapping("/_glasshouse/dimensions")
    public List<String> dimensions() {
        return AttributionDimension.all().stream().map(AttributionDimension::columnName).toList();
    }
}
