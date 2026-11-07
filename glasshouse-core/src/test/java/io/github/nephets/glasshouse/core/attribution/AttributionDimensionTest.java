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

import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class AttributionDimensionTest {

    @Test
    @DisplayName("枚举实有 7 个维度，顺序与 all() 一致")
    void valuesAreSeven() {
        assertThat(AttributionDimension.values()).hasSize(7);
        assertThat(AttributionDimension.all())
                .containsExactly(
                        AttributionDimension.PROVIDER,
                        AttributionDimension.MODEL,
                        AttributionDimension.TENANT,
                        AttributionDimension.BIZ_LINE,
                        AttributionDimension.FEATURE,
                        AttributionDimension.PROMPT_VERSION,
                        AttributionDimension.AGENT_STEP);
    }

    @ParameterizedTest
    @CsvSource({
        "PROVIDER, 供应商, provider",
        "MODEL, 模型, model",
        "TENANT, 租户, tenant",
        "BIZ_LINE, 业务线, bizLine",
        "FEATURE, 业务功能, feature",
        "PROMPT_VERSION, Prompt 版本, promptVersion",
        "AGENT_STEP, Agent 步骤, agentStep"
    })
    @DisplayName("七个维度的显示名与列名固定")
    void displayNames(String name, String displayName, String columnName) {
        AttributionDimension dimension = AttributionDimension.valueOf(name);
        assertThat(dimension.displayName()).isEqualTo(displayName);
        assertThat(dimension.columnName()).isEqualTo(columnName);
    }

    @ParameterizedTest(name = "维度 {0} 的名字与列名都能解析回来")
    @EnumSource(AttributionDimension.class)
    @DisplayName("每个维度按枚举名、列名、小写枚举名都能解析")
    void fromRoundTrip(AttributionDimension dimension) {
        assertThat(AttributionDimension.from(dimension.name())).isEqualTo(dimension);
        assertThat(AttributionDimension.from(dimension.columnName())).isEqualTo(dimension);
        assertThat(AttributionDimension.from(dimension.name().toLowerCase(Locale.ROOT)))
                .isEqualTo(dimension);
    }

    @Test
    @DisplayName("from 归一化大小写、下划线、连字符、首尾空白后匹配")
    void fromNormalizes() {
        assertThat(AttributionDimension.from("provider")).isEqualTo(AttributionDimension.PROVIDER);
        assertThat(AttributionDimension.from("BIZ_LINE")).isEqualTo(AttributionDimension.BIZ_LINE);
        assertThat(AttributionDimension.from("biz-line")).isEqualTo(AttributionDimension.BIZ_LINE);
        assertThat(AttributionDimension.from("Prompt-Version"))
                .isEqualTo(AttributionDimension.PROMPT_VERSION);
        assertThat(AttributionDimension.from("  feature  "))
                .isEqualTo(AttributionDimension.FEATURE);
        assertThat(AttributionDimension.from("AGENT_STEP"))
                .isEqualTo(AttributionDimension.AGENT_STEP);
        assertThat(AttributionDimension.from("agentStep"))
                .isEqualTo(AttributionDimension.AGENT_STEP);
        assertThat(AttributionDimension.from("PROMPT_VERSION"))
                .isEqualTo(AttributionDimension.PROMPT_VERSION);
    }

    @Test
    @DisplayName("from 对未知名、空串、纯空白、null 都返回 null")
    void fromUnknown() {
        assertThat(AttributionDimension.from("nope")).isNull();
        assertThat(AttributionDimension.from("")).isNull();
        assertThat(AttributionDimension.from("   ")).isNull();
        assertThat(AttributionDimension.from(null)).isNull();
    }
}
