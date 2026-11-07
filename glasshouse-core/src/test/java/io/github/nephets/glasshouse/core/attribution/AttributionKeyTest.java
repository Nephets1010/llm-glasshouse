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

import io.github.nephets.glasshouse.core.audit.AuditEvent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static io.github.nephets.glasshouse.core.attribution.AttributionTestFixtures.event;
import static org.assertj.core.api.Assertions.assertThat;

class AttributionKeyTest {

    private static final String PLACEHOLDER = "\u0000missing";

    @Test
    @DisplayName("空键的 7 个字段保持 NUL 开头的占位符，展示时还原为未标注")
    void emptyKeepsNulPlaceholder() {
        AttributionKey key = AttributionKey.empty();
        assertThat(key.providerId()).isEqualTo(PLACEHOLDER);
        assertThat(key.modelId()).isEqualTo(PLACEHOLDER);
        assertThat(key.tenantId()).isEqualTo(PLACEHOLDER);
        assertThat(key.bizLine()).isEqualTo(PLACEHOLDER);
        assertThat(key.feature()).isEqualTo(PLACEHOLDER);
        assertThat(key.promptVersion()).isEqualTo(PLACEHOLDER);
        assertThat(key.agentStep()).isEqualTo(PLACEHOLDER);
        assertThat(key.has(AttributionDimension.TENANT)).isFalse();
        assertThat(key.display(AttributionDimension.TENANT)).isEqualTo("未标注");
    }

    @Test
    @DisplayName("null、空串、空白串归一化为占位符，普通值去掉首尾空白")
    void normalizeVariants() {
        AttributionKey blank = new AttributionKey(null, "", "  ", null, null, null, null);
        assertThat(blank.providerId()).isEqualTo(PLACEHOLDER);
        assertThat(blank.modelId()).isEqualTo(PLACEHOLDER);
        assertThat(blank.tenantId()).isEqualTo(PLACEHOLDER);
        AttributionKey trimmed = new AttributionKey("  p  ", " m ", "t", "b", "f", "v", "s");
        assertThat(trimmed.providerId()).isEqualTo("p");
        assertThat(trimmed.modelId()).isEqualTo("m");
        assertThat(trimmed.tenantId()).isEqualTo("t");
    }

    @Test
    @DisplayName("stepLabel 覆盖四种组合并去掉首尾空白")
    void stepLabelBranches() {
        assertThat(AttributionKey.stepLabel("a", "s")).isEqualTo("a/s");
        assertThat(AttributionKey.stepLabel("  a  ", "  s  ")).isEqualTo("a/s");
        assertThat(AttributionKey.stepLabel("a", null)).isEqualTo("a");
        assertThat(AttributionKey.stepLabel("a", "  ")).isEqualTo("a");
        assertThat(AttributionKey.stepLabel(null, "s")).isEqualTo("s");
        assertThat(AttributionKey.stepLabel("", "  s ")).isEqualTo("s");
        assertThat(AttributionKey.stepLabel(null, null)).isEqualTo(PLACEHOLDER);
        assertThat(AttributionKey.stepLabel("  ", "")).isEqualTo(PLACEHOLDER);
    }

    @ParameterizedTest(name = "维度 {0} 的替换与读取")
    @EnumSource(AttributionDimension.class)
    @DisplayName("with 替换指定维度，其余维度不变，valueOf/has/display 一致")
    void withAndValueOf(AttributionDimension dimension) {
        AttributionKey base = AttributionKey.empty();
        AttributionKey changed = base.with(dimension, "X");
        assertThat(changed.valueOf(dimension)).isEqualTo("X");
        assertThat(changed.has(dimension)).isTrue();
        assertThat(changed.display(dimension)).isEqualTo("X");
        assertThat(base.valueOf(dimension)).isEqualTo(PLACEHOLDER);
        assertThat(base.has(dimension)).isFalse();
        for (AttributionDimension other : AttributionDimension.values()) {
            if (other != dimension) {
                assertThat(changed.valueOf(other)).isEqualTo(PLACEHOLDER);
                assertThat(changed.display(other)).isEqualTo("未标注");
            }
        }
    }

    @Test
    @DisplayName("from 事件时把编排标识合成步骤标签")
    void fromEvent() {
        AuditEvent source =
                event("vip", "电商", "客服", "v3", 100L, 50L).withStep("agentA", null, "step1", null);
        AttributionKey key = AttributionKey.from(source, "deepseek", "deepseek-chat");
        assertThat(key.providerId()).isEqualTo("deepseek");
        assertThat(key.modelId()).isEqualTo("deepseek-chat");
        assertThat(key.tenantId()).isEqualTo("vip");
        assertThat(key.bizLine()).isEqualTo("电商");
        assertThat(key.feature()).isEqualTo("客服");
        assertThat(key.promptVersion()).isEqualTo("v3");
        assertThat(key.agentStep()).isEqualTo("agentA/step1");
    }

    @Test
    @DisplayName("from 事件缺失维度时落到占位符而不是字符串 null")
    void fromEventMissing() {
        AuditEvent source = event(null, null, null, null, 0L, 0L);
        AttributionKey key = AttributionKey.from(source, null, null);
        assertThat(key.tenantId()).isEqualTo(PLACEHOLDER);
        assertThat(key.providerId()).isEqualTo(PLACEHOLDER);
        assertThat(key.agentStep()).isEqualTo(PLACEHOLDER);
        assertThat(key.display(AttributionDimension.TENANT)).isEqualTo("未标注");
    }

    @Test
    @DisplayName("display 对有值维度返回原值，不改写")
    void displayRealValue() {
        AttributionKey key = AttributionKey.empty().with(AttributionDimension.FEATURE, "客服");
        assertThat(key.display(AttributionDimension.FEATURE)).isEqualTo("客服");
        assertThat(key.valueOf(AttributionDimension.FEATURE)).isEqualTo("客服");
    }
}
