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

/**
 * {@link JsonlDataset} 的用例 id 唯一性契约测试。
 *
 * <p>报告里的逐用例明细、失败用例列表、基线对比的差异行都以 {@code id} 为键。同一个 id 在数据集里出现两次时， 三处都会互相覆盖或错位，最终现象是「报告里的用例数少于 jsonl
 * 的行数」。 唯一性校验是这类问题唯一的拦截点，所以正反两面都要钉住。
 */
class JsonlDatasetTest {

    /**
     * 构造一条最小合法用例。
     *
     * @param id 用例标识
     * @return 用例
     */
    private static EvalCase evalCase(String id) {
        return new EvalCase(
                id, Map.of("question", "退货政策？"), Map.of("exact", "七天"), Map.of("authored", "自撰"));
    }

    @Test
    @DisplayName("id 互不相同时 hasUniqueIds 为 true")
    void uniqueIdsReturnTrue() {
        JsonlDataset dataset =
                new JsonlDataset(
                        "customer-service-v1",
                        "v1",
                        List.of(evalCase("c-1"), evalCase("c-2")),
                        null);
        assertThat(dataset.hasUniqueIds()).isTrue();
        assertThat(dataset.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("★ 出现重复 id 时 hasUniqueIds 为 false，但构造期不会拒绝该数据集")
    void duplicateIdsReturnFalse() {
        JsonlDataset dataset =
                new JsonlDataset(
                        "customer-service-v1",
                        "v1",
                        List.of(evalCase("dup"), evalCase("dup")),
                        null);
        assertThat(dataset.hasUniqueIds())
                .as(
                        "★ 这是当前行为，不是期望行为：紧凑构造器只校验「有用例」，不校验 id 唯一，"
                                + "重复 id 只在调用 hasUniqueIds 时暴露；同名用例会让报告明细按 id 互相覆盖，"
                                + "而 size() 依旧是 2，两者对不上")
                .isFalse();
        assertThat(dataset.size()).as("重复 id 的两条都被保留，构造期没有去重").isEqualTo(2);
    }

    @Test
    @DisplayName("单条用例的数据集天然唯一")
    void singleCaseIsUnique() {
        JsonlDataset dataset = new JsonlDataset("ds", "v1", List.of(evalCase("only")), "说明");
        assertThat(dataset.hasUniqueIds()).isTrue();
    }
}
