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

import java.util.List;

/**
 * 归因维度枚举（PRD 仓库③ P0-3：按五个维度归因成本）。
 *
 * <p>五个维度分别是<b>模型 / 租户 / 业务功能 / Prompt 版本 / Agent 步骤</b>。 在聚合键的排列表述上，前四个来自契约 EVT-001 的归因字段，第五个由
 * {@link #stepLabel(AuditKey)} 从 {@code agentId + stepId} 组合而来（编排仓才有这两个字段，观测仓不能凭空造）。
 */
public enum AttributionDimension {

    /** 供应商标识。 */
    PROVIDER("供应商", "provider"),

    /** 模型标识。 */
    MODEL("模型", "model"),

    /** 租户。 */
    TENANT("租户", "tenant"),

    /** 业务线。 */
    BIZ_LINE("业务线", "bizLine"),

    /** 业务功能。 */
    FEATURE("业务功能", "feature"),

    /** Prompt 模板版本。 */
    PROMPT_VERSION("Prompt 版本", "promptVersion"),

    /** Agent 步骤。 */
    AGENT_STEP("Agent 步骤", "agentStep");

    private final String displayName;
    private final String columnName;

    AttributionDimension(String displayName, String columnName) {
        this.displayName = displayName;
        this.columnName = columnName;
    }

    /**
     * 返回中文显示名，供 CLI 与 Markdown 报告表头使用。
     *
     * @return 显示名
     */
    public String displayName() {
        return displayName;
    }

    /**
     * 返回列名，供 CSV 报告表头使用。
     *
     * @return 列名
     */
    public String columnName() {
        return columnName;
    }

    /**
     * 解析维度名，忽略大小写与驼峰差异。
     *
     * @param value 维度名，可为 null
     * @return 对应维度；未识别返回 null
     */
    public static AttributionDimension from(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().replace("_", "").replace("-", "");
        for (AttributionDimension d : values()) {
            if (d.name().equalsIgnoreCase(normalized)
                    || d.columnName.equalsIgnoreCase(normalized)) {
                return d;
            }
        }
        return null;
    }

    /**
     * 返回全部维度。
     *
     * @return 不可变列表
     */
    public static List<AttributionDimension> all() {
        return List.of(values());
    }
}
