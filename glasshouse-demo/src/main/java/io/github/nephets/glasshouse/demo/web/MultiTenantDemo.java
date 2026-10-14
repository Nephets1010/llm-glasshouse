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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.nephets.glasshouse.core.attribution.AttributionDimension;
import io.github.nephets.glasshouse.core.attribution.AttributionEngine;
import io.github.nephets.glasshouse.core.attribution.AttributionReport;
import io.github.nephets.glasshouse.core.attribution.AttributionRow;
import io.github.nephets.glasshouse.starter.annotation.AgentStep;
import io.github.nephets.glasshouse.starter.annotation.SpanDimensions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 多租户 × 多功能 × 多 Prompt 版本的归因演示。
 *
 * <p>对应 README 首屏「成本归因」那一段：跑几次不同租户、不同功能、不同 Prompt 版本的请求， 就能看到成本按维度分开了。这是「说得清」的直观证据。
 *
 * <p><b>刻意让不同租户的成本差 10 倍</b>——如果所有租户成本一样， 归因报表就只是「能跑通」而不是「有用」。差 10 倍才能证明下钻是真的在算。
 */
@Service
public class MultiTenantDemo {

    private static final Logger log = LoggerFactory.getLogger(MultiTenantDemo.class);

    /** 演示用的租户配置：调用次数与 Prompt 版本，刻意拉开差异。 */
    private static final Map<String, TenantProfile> TENANTS =
            Map.of(
                    "vip", new TenantProfile("VIP 租户", 6, "planner@v3", "客服助手"),
                    "standard", new TenantProfile("标准租户", 2, "planner@v3", "客服助手"),
                    "trial", new TenantProfile("试用租户", 1, "planner@v2", "智能问答"));

    private final CustomerServiceAgent agent;
    private final AttributionEngine attributionEngine;

    /**
     * 构造演示服务。
     *
     * @param agent 客服 Agent
     * @param attributionEngine starter 装配好的归因引擎
     */
    public MultiTenantDemo(CustomerServiceAgent agent, AttributionEngine attributionEngine) {
        this.agent = agent;
        this.attributionEngine = attributionEngine;
    }

    /**
     * 为一个租户跑一批请求。
     *
     * @param tenantId 租户标识
     * @param bizLine 业务线（归因维度）
     * @param rounds 调用轮数
     * @return 执行摘要
     */
    @AgentStep(agentId = "multi_tenant_demo", stepId = "batch")
    public Map<String, Object> runForTenant(String tenantId, String bizLine, int rounds) {
        TenantProfile profile = TENANTS.getOrDefault(tenantId, TENANTS.get("standard"));
        SpanDimensions.set(
                SpanDimensions.merge(
                        b ->
                                b.tenantId(tenantId)
                                        .bizLine(bizLine)
                                        .feature(profile.feature())
                                        .promptVersion(profile.promptVersion())));
        try {
            int actual = Math.max(1, rounds);
            for (int i = 0; i < actual; i++) {
                agent.plan("第 " + (i + 1) + " 轮：退货政策是什么？", profile.promptVersion());
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("tenantId", tenantId);
            summary.put("tenantName", profile.displayName());
            summary.put("bizLine", bizLine);
            summary.put("feature", profile.feature());
            summary.put("promptVersion", profile.promptVersion());
            summary.put("calls", actual);
            return summary;
        } finally {
            SpanDimensions.clear();
        }
    }

    /**
     * 跑完整的演示：三个租户 × 各自配置。
     *
     * @return 演示摘要
     */
    public Map<String, Object> runAll() {
        List<Map<String, Object>> summaries = new ArrayList<>();
        summaries.add(runForTenant("vip", "电商", 6));
        summaries.add(runForTenant("standard", "电商", 2));
        summaries.add(runForTenant("trial", "教育", 1));
        log.info("[demo] 多租户演示完成: {} 个租户", summaries.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenants", summaries);
        result.put(
                "hint",
                "现在访问 /_glasshouse/cost?dimension=tenant 或 dimension=promptVersion，"
                        + "可以看到成本已按维度分开。");
        return result;
    }

    /**
     * 返回各维度归因报表，供 demo 页面直接展示。
     *
     * @return 维度 → 报表摘要
     */
    public Map<String, Object> attributionSummary() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (AttributionDimension dimension :
                List.of(
                        AttributionDimension.TENANT,
                        AttributionDimension.PROMPT_VERSION,
                        AttributionDimension.FEATURE,
                        AttributionDimension.BIZ_LINE)) {
            AttributionReport report = attributionEngine.report(dimension);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (AttributionRow row : report.rows()) {
                rows.add(
                        Map.of(
                                "value", row.valueOf(dimension),
                                "calls", row.callCount(),
                                "tokens", row.totalTokens(),
                                "costCny", row.totalCost(),
                                "share",
                                        String.format(
                                                java.util.Locale.ROOT,
                                                "%.2f%%",
                                                row.share() * 100)));
            }
            result.put(
                    dimension.columnName(),
                    Map.of("totalCostCny", report.totalCost(), "rows", rows));
        }
        return result;
    }

    /**
     * 租户画像。
     *
     * @param displayName 显示名
     * @param rounds 默认轮数
     * @param promptVersion Prompt 版本
     * @param feature 业务功能
     */
    record TenantProfile(String displayName, int rounds, String promptVersion, String feature) {}
}
