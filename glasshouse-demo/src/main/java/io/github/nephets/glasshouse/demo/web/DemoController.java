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

package io.github.nephets.glasshouse.demo.web;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.nephets.glasshouse.starter.store.SpanRingBuffer;
import io.github.nephets.glasshouse.starter.store.SpanTreeQuery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo 控制器：README「30 秒上手」里那几条 curl 对应的端点。
 *
 * <p><b>零 API Key 的完整链路</b>：
 *
 * <pre>{@code
 * # 1. 跑一次三步客服 Agent
 * curl -s -X POST localhost:8080/demo/chat \
 *   -H 'Content-Type: application/json' \
 *   -d '{"question":"退货政策是什么？","tenantId":"vip"}'
 *
 * # 2. 从返回里拿 traceId，看 span 树
 * curl -s 'localhost:8080/_glasshouse/trace/<traceId>?cost=true'
 *
 * # 3. 跑多租户归因演示，然后查成本
 * curl -s -X POST localhost:8080/demo/multi-tenant
 * curl -s 'localhost:8080/_glasshouse/cost?dimension=tenant'
 * }</pre>
 */
@RestController
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final CustomerServiceAgent agent;
    private final MultiTenantDemo multiTenantDemo;
    private final SpanTreeQuery spanTreeQuery;
    private final SpanRingBuffer ringBuffer;

    /**
     * 构造控制器。
     *
     * @param agent 客服 Agent
     * @param multiTenantDemo 多租户演示
     * @param spanTreeQuery span 树查询器
     * @param ringBuffer span 缓冲
     */
    public DemoController(
            CustomerServiceAgent agent,
            MultiTenantDemo multiTenantDemo,
            SpanTreeQuery spanTreeQuery,
            SpanRingBuffer ringBuffer) {
        this.agent = agent;
        this.multiTenantDemo = multiTenantDemo;
        this.spanTreeQuery = spanTreeQuery;
        this.ringBuffer = ringBuffer;
    }

    /**
     * 跑一次三步客服 Agent。
     *
     * @param body 请求体（含 question / tenantId / promptVersion）
     * @return 执行轨迹 + 本次 traceId
     */
    @PostMapping("/demo/chat")
    public Map<String, Object> chat(@RequestBody(required = false) Map<String, String> body) {
        Map<String, String> safe = body == null ? Map.of() : body;
        String question = safe.getOrDefault("question", "退货政策是什么？");
        String tenantId = safe.getOrDefault("tenantId", "vip");
        String promptVersion = safe.getOrDefault("promptVersion", "planner@v3");
        String bizLine = safe.getOrDefault("bizLine", "电商");
        log.info(
                "[glasshouse] DemoController.chat 收到请求 tenantId={} bizLine={} promptVersion={} questionLen={}",
                tenantId,
                bizLine,
                promptVersion,
                question.length());

        io.github.nephets.glasshouse.starter.annotation.SpanDimensions.set(
                io.github.nephets.glasshouse.starter.annotation.SpanDimensions.merge(
                        b -> b.tenantId(tenantId).bizLine(bizLine)));
        Map<String, Object> result;
        try {
            result = agent.answer(question, promptVersion, tenantId);
        } catch (RuntimeException e) {
            log.error(
                    "[glasshouse] DemoController.chat 执行失败 tenantId={} bizLine={} promptVersion={}",
                    tenantId,
                    bizLine,
                    promptVersion,
                    e);
            throw e;
        } finally {
            io.github.nephets.glasshouse.starter.annotation.SpanDimensions.clear();
        }

        String traceId = latestTraceId();
        Map<String, Object> response = new LinkedHashMap<>(result);
        response.put("traceId", traceId);
        response.put(
                "treeHint", "curl -s 'localhost:8080/_glasshouse/trace/" + traceId + "?cost=true'");
        response.put("costHint", "curl -s 'localhost:8080/_glasshouse/cost?dimension=tenant'");
        return response;
    }

    /**
     * 跑多租户成本归因演示。
     *
     * @return 演示摘要 + 各维度归因
     */
    @PostMapping("/demo/multi-tenant")
    public Map<String, Object> multiTenant() {
        log.info("[glasshouse] DemoController.multiTenant 触发多租户成本归因演示");
        try {
            Map<String, Object> summary = new LinkedHashMap<>(multiTenantDemo.runAll());
            summary.put("attribution", multiTenantDemo.attributionSummary());
            return summary;
        } catch (RuntimeException e) {
            log.error("[glasshouse] DemoController.multiTenant 执行失败", e);
            throw e;
        }
    }

    /**
     * 返回最近的 span 树，便于在浏览器里直接看。
     *
     * @param traceId traceId；留空取最近一条
     * @param cost 是否附带成本
     * @return 渲染后的 span 树
     */
    @GetMapping("/demo/trace")
    public Map<String, Object> trace(
            @RequestParam(required = false) String traceId,
            @RequestParam(defaultValue = "true") boolean cost) {
        log.info("[glasshouse] DemoController.trace 查询 span 树 traceId={} cost={}", traceId, cost);
        String effective = traceId == null || traceId.isBlank() ? latestTraceId() : traceId;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", effective);
        result.put("tree", spanTreeQuery.render(effective, cost));
        result.put("totalCostCny", spanTreeQuery.totalCost(effective).toPlainString());
        result.put("bufferedSpans", ringBuffer.size());
        result.put("allTraces", ringBuffer.byTrace().keySet());
        return result;
    }

    /**
     * 返回 Agent 的步骤说明，展示「埋点只需一个注解」。
     *
     * @return 步骤说明
     */
    @GetMapping("/demo/steps")
    public Map<String, Object> steps() {
        log.info("[glasshouse] DemoController.steps 返回 Agent 步骤说明");
        return Map.of(
                "note", "业务代码里没有任何埋点 API 调用，只有三个注解",
                "steps", agent.steps(),
                "traceEndpoint", "/_glasshouse/trace/{traceId}",
                "costEndpoint", "/_glasshouse/cost?dimension=tenant");
    }

    /**
     * 返回最近的 traceId。
     *
     * @return traceId；无数据返回 {@code unknown}
     */
    private String latestTraceId() {
        return ringBuffer.byTrace().keySet().stream()
                .sorted()
                .reduce((a, b) -> b)
                .orElse("unknown");
    }
}
