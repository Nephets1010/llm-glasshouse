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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 临时定位用：逐层拆开评测链路的每一步。 */
class ScratchProbeTest {

    @Test
    @DisplayName("逐层验证关键词召回")
    void probe() {
        EvalCase c =
                EvalCase.of(
                        "k",
                        Map.of("question", "q"),
                        Map.of("contains", List.of("七天无理由退货", "发票", "退款"), "forbidden", List.of()),
                        "售后",
                        "medium",
                        true,
                        null);

        List<String> keywords = c.expectedList("contains");
        assertThat(keywords).as("关键词").hasSize(3);

        String actual = "支持七天无理由退货与退款";
        String normalizedActual = ExactMatchMetric.normalize(actual);
        assertThat(normalizedActual).as("归一化后仍含关键词").contains("七天无理由退货");

        java.math.BigDecimal direct = KeywordRecallMetric.INSTANCE.scoreOf(actual, keywords);
        assertThat(direct).as("直接调用 scoreOf").isEqualByComparingTo("0.6667");

        EvalRunner runner = new EvalRunner(x -> EvalResult.success(x.id(), actual, null, null, 1L));
        EvalResult scored = runner.scoreCase(c, EvalResult.success(c.id(), actual, null, null, 1L));
        assertThat(scored.scoreOf(Metric.NAME_KEYWORD_RECALL))
                .as("经 scoreCase 后的 keywordRecall")
                .isEqualByComparingTo("0.6667");
    }
}
