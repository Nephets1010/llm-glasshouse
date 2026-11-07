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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttributionQueryTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static AttributionQuery withFilter(AttributionDimension dimension, String value) {
        String tenant = null;
        String bizLine = null;
        String feature = null;
        String promptVersion = null;
        String agentStep = null;
        String providerId = null;
        String modelId = null;
        switch (dimension) {
            case TENANT -> tenant = value;
            case BIZ_LINE -> bizLine = value;
            case FEATURE -> feature = value;
            case PROMPT_VERSION -> promptVersion = value;
            case AGENT_STEP -> agentStep = value;
            case PROVIDER -> providerId = value;
            case MODEL -> modelId = value;
        }
        return new AttributionQuery(
                AttributionDimension.MODEL,
                tenant,
                bizLine,
                feature,
                promptVersion,
                agentStep,
                providerId,
                modelId,
                null,
                null,
                null,
                100);
    }

    @Test
    @DisplayName("静态工厂填入正确的字段，默认行数上限为 500")
    void factories() {
        AttributionQuery of = AttributionQuery.of(AttributionDimension.TENANT);
        assertThat(of.dimension()).isEqualTo(AttributionDimension.TENANT);
        assertThat(of.limit()).isEqualTo(500);
        assertThat(of.tenantId()).isNull();
        assertThat(of.from()).isNull();
        assertThat(of.to()).isNull();
        assertThat(of.onlyUnlabeled()).isNull();

        AttributionQuery all = AttributionQuery.all();
        assertThat(all.dimension()).isEqualTo(AttributionDimension.MODEL);

        AttributionQuery unlabeled = AttributionQuery.onlyUnlabeled(AttributionDimension.BIZ_LINE);
        assertThat(unlabeled.dimension()).isEqualTo(AttributionDimension.BIZ_LINE);
        assertThat(unlabeled.onlyUnlabeled()).isEqualTo(AttributionDimension.BIZ_LINE);

        AttributionQuery between =
                AttributionQuery.between(AttributionDimension.FEATURE, T0, T0.plusSeconds(60));
        assertThat(between.dimension()).isEqualTo(AttributionDimension.FEATURE);
        assertThat(between.from()).isEqualTo(T0);
        assertThat(between.to()).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    @DisplayName("维度为 null 时兜底为 MODEL")
    void nullDimensionFallsBackToModel() {
        AttributionQuery query =
                new AttributionQuery(
                        null, null, null, null, null, null, null, null, null, null, null, 100);
        assertThat(query.dimension()).isEqualTo(AttributionDimension.MODEL);
    }

    @Test
    @DisplayName("withDimension 只改聚合维度，保留过滤条件与行数上限")
    void withDimensionKeepsFilters() {
        AttributionQuery base = withFilter(AttributionDimension.TENANT, "vip");
        AttributionQuery changed = base.withDimension(AttributionDimension.FEATURE);
        assertThat(changed.dimension()).isEqualTo(AttributionDimension.FEATURE);
        assertThat(changed.tenantId()).isEqualTo("vip");
        assertThat(changed.limit()).isEqualTo(100);
    }

    @Test
    @DisplayName("withLimit 只改行数上限，保留过滤条件")
    void withLimitKeepsFilters() {
        AttributionQuery base = withFilter(AttributionDimension.TENANT, "vip");
        AttributionQuery changed = base.withLimit(3);
        assertThat(changed.limit()).isEqualTo(3);
        assertThat(changed.tenantId()).isEqualTo("vip");
        assertThat(changed.dimension()).isEqualTo(AttributionDimension.MODEL);
    }

    @ParameterizedTest(name = "维度 {0} 的等于过滤")
    @EnumSource(AttributionDimension.class)
    @DisplayName("七个维度逐项等于过滤：同值通过、异值拒绝")
    void eachDimensionEqFilter(AttributionDimension dimension) {
        AttributionKey key = AttributionKey.empty().with(dimension, "vip");
        assertThat(withFilter(dimension, "vip").matches(key)).isTrue();
        assertThat(withFilter(dimension, "other").matches(key)).isFalse();
    }

    @Test
    @DisplayName("过滤值为 null、空串、纯空白时该维度不过滤")
    void blankFilterDoesNotFilter() {
        AttributionKey key = AttributionKey.empty().with(AttributionDimension.TENANT, "vip");
        assertThat(withFilter(AttributionDimension.TENANT, null).matches(key)).isTrue();
        assertThat(withFilter(AttributionDimension.TENANT, "").matches(key)).isTrue();
        assertThat(withFilter(AttributionDimension.TENANT, "   ").matches(key)).isTrue();
    }

    @Test
    @DisplayName("过滤值比较前会去掉首尾空白")
    void filterIsTrimmed() {
        AttributionKey key = AttributionKey.empty().with(AttributionDimension.TENANT, "vip");
        assertThat(withFilter(AttributionDimension.TENANT, " vip ").matches(key)).isTrue();
        assertThat(withFilter(AttributionDimension.TENANT, "vip ").matches(key)).isTrue();
    }

    @Test
    @DisplayName("多条件同时命中才通过，任一不符即拒绝")
    void combinedFilters() {
        AttributionKey key =
                AttributionKey.empty()
                        .with(AttributionDimension.TENANT, "vip")
                        .with(AttributionDimension.FEATURE, "客服");
        AttributionQuery both =
                new AttributionQuery(
                        AttributionDimension.TENANT,
                        "vip",
                        null,
                        "客服",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        100);
        AttributionQuery mismatch =
                new AttributionQuery(
                        AttributionDimension.TENANT,
                        "vip",
                        null,
                        "问答",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        100);
        assertThat(both.matches(key)).isTrue();
        assertThat(mismatch.matches(key)).isFalse();
    }

    @Test
    @DisplayName("onlyUnlabeled 只放行该维度缺失的行")
    void onlyUnlabeled() {
        AttributionQuery query = AttributionQuery.onlyUnlabeled(AttributionDimension.TENANT);
        assertThat(query.onlyUnlabeled()).isEqualTo(AttributionDimension.TENANT);
        assertThat(query.matches(AttributionKey.empty().with(AttributionDimension.TENANT, "vip")))
                .isFalse();
        assertThat(query.matches(AttributionKey.empty())).isTrue();
        assertThat(query.matches(AttributionKey.empty().with(AttributionDimension.FEATURE, "客服")))
                .isTrue();
    }

    @Test
    @DisplayName("matches 遇到 null 键抛异常")
    void matchesRejectsNullKey() {
        assertThatThrownBy(() -> AttributionQuery.of(AttributionDimension.TENANT).matches(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("无时间范围时任意时刻都在范围内，含 null")
    void withinRangeOpenEnded() {
        AttributionQuery query = AttributionQuery.of(AttributionDimension.TENANT);
        assertThat(query.withinRange(T0)).isTrue();
        assertThat(query.withinRange(null)).isTrue();
        assertThat(query.withinRange(Instant.parse("1999-01-01T00:00:00Z"))).isTrue();
        assertThat(query.withinRange(Instant.parse("2099-01-01T00:00:00Z"))).isTrue();
    }

    @Test
    @DisplayName("from 含、to 不含")
    void withinRangeBoundaries() {
        AttributionQuery query =
                AttributionQuery.between(AttributionDimension.TENANT, T0, T0.plusSeconds(3600));
        assertThat(query.withinRange(T0)).isTrue();
        assertThat(query.withinRange(T0.minusSeconds(1))).isFalse();
        assertThat(query.withinRange(T0.plusSeconds(3599))).isTrue();
        assertThat(query.withinRange(T0.plusSeconds(3600))).isFalse();
        assertThat(query.withinRange(T0.plusSeconds(3601))).isFalse();
    }

    @Test
    @DisplayName("带时间范围时 occurredAt 为 null 不在范围内")
    void withinRangeNullOccurred() {
        AttributionQuery query =
                AttributionQuery.between(AttributionDimension.TENANT, T0, T0.plusSeconds(3600));
        assertThat(query.withinRange(null)).isFalse();
        assertThat(AttributionQuery.of(AttributionDimension.TENANT).withinRange(null)).isTrue();
    }

    @Test
    @DisplayName("只有 from 或只有 to 时按单边判断")
    void withinRangeOneSided() {
        AttributionQuery fromOnly = AttributionQuery.between(AttributionDimension.TENANT, T0, null);
        assertThat(fromOnly.withinRange(T0.minusSeconds(1))).isFalse();
        assertThat(fromOnly.withinRange(T0)).isTrue();
        assertThat(fromOnly.withinRange(T0.plusSeconds(100000))).isTrue();

        AttributionQuery toOnly = AttributionQuery.between(AttributionDimension.TENANT, null, T0);
        assertThat(toOnly.withinRange(T0.minusSeconds(1))).isTrue();
        assertThat(toOnly.withinRange(T0)).isFalse();
    }

    @Test
    @DisplayName("from 晚于 to 直接拒绝")
    void fromAfterToRejected() {
        assertThatThrownBy(
                        () ->
                                AttributionQuery.between(
                                        AttributionDimension.TENANT, T0.plusSeconds(10), T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from");
    }
}
