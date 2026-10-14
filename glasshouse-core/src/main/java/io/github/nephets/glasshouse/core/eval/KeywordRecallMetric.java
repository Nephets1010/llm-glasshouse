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
import java.util.List;

/**
 * 关键词召回指标：{@code expected.contains} 中的关键词有多少出现在输出里。
 *
 * <p>适用于客服、信贷风控这类「答案不唯一但必须提到关键点」的场景。这是中文评测里最常用也最稳的 规则型指标——不依赖模型判断，因此完全确定性。
 *
 * <p><b>中文没有空格分词</b>，所以这里用「子串包含」而不是分词后比对： 关键词本身就是短语（如「七日无理由退货」），子串匹配语义正确且无需引入分词器依赖。
 */
public final class KeywordRecallMetric implements Metric {

    /** 期望键名。 */
    public static final String EXPECTED_KEY = "contains";

    /** 共享实例。 */
    public static final KeywordRecallMetric INSTANCE = new KeywordRecallMetric();

    @Override
    public String name() {
        return NAME_KEYWORD_RECALL;
    }

    @Override
    public String description() {
        return "关键词召回率：expected.contains 中的关键点在输出中出现的比例";
    }

    @Override
    public BigDecimal score(EvalResult result) {
        BigDecimal precomputed = result.scoreOf(name());
        return BigDecimal.ZERO.compareTo(precomputed) != 0 ? precomputed : Metric.normalize(0.0d);
    }

    /**
     * 对单条结果直接打分。
     *
     * @param actual 模型输出，可为 null
     * @param keywords 期望关键词列表，可为 null 或空
     * @return 召回率 0~1；关键词为空时返回 0（无法判定，不给「满分」）
     */
    public BigDecimal scoreOf(String actual, List<String> keywords) {
        if (actual == null || keywords == null || keywords.isEmpty()) {
            return Metric.normalize(0.0d);
        }
        String normalizedActual = ExactMatchMetric.normalize(actual);
        int hit = 0;
        for (String keyword : keywords) {
            if (keyword != null
                    && !keyword.isBlank()
                    && normalizedActual.contains(ExactMatchMetric.normalize(keyword))) {
                hit++;
            }
        }
        return Metric.normalize((double) hit / keywords.size());
    }
}
