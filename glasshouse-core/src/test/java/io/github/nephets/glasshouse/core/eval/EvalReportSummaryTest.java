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

package io.github.nephets.glasshouse.core.eval;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EvalReport#summaryLine()} 的拼装格式测试。
 *
 * <p>这一行是控制台输出与 Markdown 报告共用的标题行，人工做基线对比时全靠它一眼认出「这份报告属于哪个数据集、哪个模型」。
 * 字段顺序和成本的小数位都是对外格式，改动会让所有历史报告无法按行比对。
 */
class EvalReportSummaryTest {

    /**
     * 构造一份固定内容的报告。
     *
     * @param totalCost 总成本（CNY）
     * @return 报告
     */
    private static EvalReport report(BigDecimal totalCost) {
        return new EvalReport(
                "customer-service-v1",
                "abc123",
                "sha256:0000",
                "v3",
                "mock/mock-chat",
                List.of(EvalResult.success("c-1", "七天无理由退货", null, null, 12L)),
                Map.of(Metric.NAME_ACCURACY, new BigDecimal("0.5")),
                7L,
                2L,
                totalCost,
                Instant.parse("2026-01-01T00:00:00Z"),
                1234L);
    }

    @Test
    @DisplayName("字段顺序固定为 dataset@version, prompt, model, calls, failed, costCny")
    void summaryLineHasFixedFieldOrder() {
        assertThat(report(new BigDecimal("0.123456")).summaryLine())
                .as("基线对比要按这一行对齐，字段顺序与逗号分隔都是对外格式")
                .isEqualTo(
                        "dataset=customer-service-v1@abc123, prompt=v3, model=mock/mock-chat, "
                                + "calls=7, failed=2, costCny=0.1235");
    }

    @Test
    @DisplayName("成本固定保留四位小数，不足补零")
    void costIsPaddedToFourDecimals() {
        assertThat(report(BigDecimal.ONE).summaryLine())
                .as("整数成本也要显示成 1.0000，否则同一份报告的两次输出位数不同")
                .endsWith("costCny=1.0000");
        assertThat(report(new BigDecimal("0.00004")).summaryLine())
                .as("第五位不足 5 时舍去，显示 0.0000 而不是科学计数法")
                .endsWith("costCny=0.0000");
    }
}
