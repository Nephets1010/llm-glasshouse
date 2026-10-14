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

package io.github.nephets.glasshouse.core.attribution;

import java.time.Instant;
import java.util.Objects;

/**
 * 归因查询条件。
 *
 * <p><b>过滤语义</b>：所有字段都是「等于」过滤，{@code null} 表示<b>不过滤该维度</b>（而不是「只看该维度为空的行」）。 想查「未标注租户的行」用 {@link
 * #onlyUnlabeled(AttributionDimension)}。
 *
 * <p>这个语义选择很关键：如果 {@code null} 表示「只看空值」，那 {@code new AttributionQuery()} 就只能查未标注数据，
 * 而绝大多数查询是「不按某维度过滤」。用「null = 不过滤」让默认构造即为全量查询，符合直觉。
 *
 * <p>六个归因维度（供应商 / 模型 / 租户 / 业务线 / 业务功能 / Prompt 版本 / Agent 步骤） 都可以作为过滤条件，因此下钻时任意组合都成立。
 *
 * @param dimension 聚合维度，决定按哪个维度分组
 * @param tenantId 租户过滤，可为 null
 * @param bizLine 业务线过滤（事件级），可为 null
 * @param feature 业务功能过滤，可为 null
 * @param promptVersion Prompt 版本过滤，可为 null
 * @param agentStep Agent 步骤过滤，可为 null
 * @param providerId 供应商过滤，可为 null
 * @param modelId 模型过滤，可为 null
 * @param from 起始时刻（含），可为 null
 * @param to 结束时刻（不含），可为 null
 * @param onlyUnlabeled 仅返回该维度未标注的行；为 null 时不过滤
 * @param limit 返回行数上限；{@code <=0} 表示不限制
 */
public record AttributionQuery(
        AttributionDimension dimension,
        String tenantId,
        String bizLine,
        String feature,
        String promptVersion,
        String agentStep,
        String providerId,
        String modelId,
        Instant from,
        Instant to,
        AttributionDimension onlyUnlabeled,
        int limit) {

    /** 默认返回行数上限，防止 CLI 一次吐出十万行。 */
    public static final int DEFAULT_LIMIT = 500;

    /**
     * 构造全量查询（按指定维度分组，其余不过滤）。
     *
     * @param dimension 聚合维度
     * @return 查询条件
     */
    public static AttributionQuery of(AttributionDimension dimension) {
        return new AttributionQuery(
                dimension,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                DEFAULT_LIMIT);
    }

    /**
     * 构造全量查询（不分组，只返回明细汇总行）。
     *
     * @return 查询条件
     */
    public static AttributionQuery all() {
        return of(AttributionDimension.MODEL);
    }

    /**
     * 构造「仅查某维度未标注的行」的查询。
     *
     * @param dimension 目标维度
     * @return 查询条件
     */
    public static AttributionQuery onlyUnlabeled(AttributionDimension dimension) {
        AttributionQuery base = of(dimension);
        return new AttributionQuery(
                base.dimension(),
                base.tenantId(),
                base.bizLine(),
                base.feature(),
                base.promptVersion(),
                base.agentStep(),
                base.providerId(),
                base.modelId(),
                base.from(),
                base.to(),
                dimension,
                base.limit());
    }

    /**
     * 构造带时间范围的查询。
     *
     * @param dimension 聚合维度
     * @param from 起始时刻（含）
     * @param to 结束时刻（不含）
     * @return 查询条件
     */
    public static AttributionQuery between(
            AttributionDimension dimension, Instant from, Instant to) {
        AttributionQuery base = of(dimension);
        return new AttributionQuery(
                base.dimension(),
                base.tenantId(),
                base.bizLine(),
                base.feature(),
                base.promptVersion(),
                base.agentStep(),
                base.providerId(),
                base.modelId(),
                from,
                to,
                base.onlyUnlabeled(),
                base.limit());
    }

    /** 紧凑构造器：维度兜底与 limit 归一化。 */
    public AttributionQuery {
        if (dimension == null) {
            dimension = AttributionDimension.MODEL;
        }
        if (limit <= 0) {
            limit = DEFAULT_LIMIT;
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from 不得晚于 to");
        }
    }

    /**
     * 返回一个覆盖了指定过滤条件的副本。
     *
     * @param dimension 聚合维度
     * @return 新查询
     */
    public AttributionQuery withDimension(AttributionDimension dimension) {
        return new AttributionQuery(
                dimension,
                tenantId,
                bizLine,
                feature,
                promptVersion,
                agentStep,
                providerId,
                modelId,
                from,
                to,
                onlyUnlabeled,
                limit);
    }

    /**
     * 返回一个设置了 limit 的副本。
     *
     * @param newLimit 行数上限；{@code <=0} 表示不限制
     * @return 新查询
     */
    public AttributionQuery withLimit(int newLimit) {
        return new AttributionQuery(
                dimension,
                tenantId,
                bizLine,
                feature,
                promptVersion,
                agentStep,
                providerId,
                modelId,
                from,
                to,
                onlyUnlabeled,
                newLimit);
    }

    /**
     * 判断聚合键是否通过本查询的过滤条件。
     *
     * @param key 聚合键，不可为 null
     * @return 通过返回 {@code true}
     */
    public boolean matches(AttributionKey key) {
        Objects.requireNonNull(key, "key 不能为 null");
        if (!eq(tenantId, key.tenantId())
                || !eq(bizLine, key.bizLine())
                || !eq(feature, key.feature())
                || !eq(promptVersion, key.promptVersion())
                || !eq(agentStep, key.agentStep())
                || !eq(providerId, key.providerId())
                || !eq(modelId, key.modelId())) {
            return false;
        }
        if (onlyUnlabeled != null && key.has(onlyUnlabeled)) {
            return false;
        }
        return true;
    }

    /**
     * 判断时刻是否落在查询的时间范围内。
     *
     * @param occurredAt 发生时刻
     * @return 在范围内返回 {@code true}
     */
    public boolean withinRange(Instant occurredAt) {
        if (occurredAt == null) {
            return from == null && to == null;
        }
        if (from != null && occurredAt.isBefore(from)) {
            return false;
        }
        return to == null || occurredAt.isBefore(to);
    }

    /**
     * 等值过滤辅助：{@code filter} 为 null 表示不过滤。
     *
     * @param filter 过滤值，可为 null
     * @param actual 实际值
     * @return 通过返回 {@code true}
     */
    private static boolean eq(String filter, String actual) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        return filter.trim().equals(actual);
    }
}
