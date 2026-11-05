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

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link EvalCase} 四字段校验的报错测试。
 *
 * <p>{@code input} 与 {@code expected} 缺一个，这条用例就跑不出有意义的分数。校验发生在构造期而不是打分时， 好处是 jsonl 解析阶段就带着行号与 id
 * 报错；因此报错信息必须带用例 id， 否则在几百行的评测集里无法定位是哪一条写漏了。
 *
 * <p>null 与空 map 两种写法都要覆盖：null 会先被兜底成空 map，再走同一个判定，两条路径的报错必须一致。
 */
class EvalCaseValidationTest {

    /** 合法的出处标注，用来把校验失败集中在 input / expected 上。 */
    private static final Map<String, String> PROVENANCE = Map.of("authored", "自撰");

    @Test
    @DisplayName("input 为 null 或空 map 时抛 IllegalArgumentException，报错里带用例 id")
    void emptyInputIsRejected() {
        assertThatThrownBy(() -> new EvalCase("c-9", null, Map.of("exact", "七天"), PROVENANCE))
                .as("input 为 null 走的是兜底成空 map 再判定这条路")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("c-9")
                .hasMessageContaining("input");
        assertThatThrownBy(() -> new EvalCase("c-9", Map.of(), Map.of("exact", "七天"), PROVENANCE))
                .as("空 map 与 null 必须报同一类错误，调用方才能只写一处兜底")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("c-9")
                .hasMessageContaining("input");
    }

    @Test
    @DisplayName("expected 为 null 或空 map 时抛 IllegalArgumentException，报错里带用例 id")
    void emptyExpectedIsRejected() {
        assertThatThrownBy(
                        () -> new EvalCase("c-10", Map.of("question", "退货政策？"), null, PROVENANCE))
                .as("没有期望结果就无法判定对错，不能让它静默跑成 0 分")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("c-10")
                .hasMessageContaining("expected");
        assertThatThrownBy(
                        () ->
                                new EvalCase(
                                        "c-10", Map.of("question", "退货政策？"), Map.of(), PROVENANCE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("c-10")
                .hasMessageContaining("expected");
    }

    @Test
    @DisplayName("缺出处标注时的报错同样带用例 id")
    void missingProvenanceIsRejected() {
        assertThatThrownBy(
                        () ->
                                new EvalCase(
                                        "c-11",
                                        Map.of("question", "退货政策？"),
                                        Map.of("exact", "七天"),
                                        Map.of()))
                .as("id 是唯一能把报错行与 jsonl 行对上的线索，任何一条校验失败都要带上它")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("c-11")
                .hasMessageContaining("authored");
    }

    @Test
    @DisplayName("metadata 为 null 时同样报缺出处，不抛 NPE")
    void nullMetadataIsTreatedAsEmpty() {
        assertThatThrownBy(
                        () ->
                                new EvalCase(
                                        "c-12",
                                        Map.of("question", "退货政策？"),
                                        Map.of("exact", "七天"),
                                        null))
                .as("null 与空 map 走同一条兜底，报的是可读的缺出处错误而不是 NPE")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("c-12")
                .hasMessageContaining("source");
    }
}
