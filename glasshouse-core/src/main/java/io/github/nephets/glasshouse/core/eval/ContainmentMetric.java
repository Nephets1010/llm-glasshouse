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

/**
 * 包含关系指标：{@code expected.forbidden} 中的任一字符串出现在输出里即判失败。
 *
 * <p>这是「幻觉率」指标的实现基础。幻觉率 = 包含禁用内容的用例占比， 得分取 {@code 1 - 幻觉率}，从而与其他指标统一为「越高越好」， 门禁表达式可以直接写 {@code
 * hallucination>0.9} 而不必担心方向写反。
 */
public final class ContainmentMetric implements Metric {

    /** 期望键名：必须包含（至少命中一个）。 */
    public static final String EXPECTED_REQUIRED = "containsAny";

    /** 期望键名：禁止包含（命中任一即失败）。 */
    public static final String EXPECTED_FORBIDDEN = "forbidden";

    /** 共享实例。 */
    public static final ContainmentMetric INSTANCE = new ContainmentMetric();

    @Override
    public String name() {
        return NAME_CONTAINMENT;
    }

    @Override
    public String description() {
        return "包含关系合规率：输出未触碰 expected.forbidden 中的任一禁用内容";
    }

    @Override
    public BigDecimal score(EvalResult result) {
        BigDecimal precomputed = result.scoreOf(name());
        return BigDecimal.ZERO.compareTo(precomputed) != 0 ? precomputed : Metric.normalize(0.0d);
    }

    /**
     * 对单条结果直接打分（只看禁用内容）。
     *
     * @param actual 模型输出，可为 null
     * @param forbidden 禁用内容列表，可为 null 或空
     * @return 1.0 表示未触碰任何禁用内容；0.0 表示命中
     */
    public BigDecimal scoreOf(String actual, java.util.List<String> forbidden) {
        if (forbidden == null || forbidden.isEmpty()) {
            return Metric.normalize(1.0d);
        }
        if (actual == null) {
            return Metric.normalize(0.0d);
        }
        String normalizedActual = ExactMatchMetric.normalize(actual);
        for (String keyword : forbidden) {
            if (keyword != null
                    && !keyword.isBlank()
                    && normalizedActual.contains(ExactMatchMetric.normalize(keyword))) {
                return Metric.normalize(0.0d);
            }
        }
        return Metric.normalize(1.0d);
    }

    /**
     * 判断输出是否命中禁用内容。
     *
     * @param actual 模型输出，可为 null
     * @param forbidden 禁用内容列表，可为 null 或空
     * @return 命中返回 {@code true}
     */
    public boolean hitsForbidden(String actual, java.util.List<String> forbidden) {
        return Metric.normalize(0.0d).compareTo(scoreOf(actual, forbidden)) == 0
                && forbidden != null
                && !forbidden.isEmpty();
    }
}
