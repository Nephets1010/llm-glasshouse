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
import java.math.RoundingMode;

/**
 * 评测指标 SPI。
 *
 * <p><b>首版刻意只做规则型指标</b>（精确匹配、关键词召回、包含关系、JSON 结构、成本）， 不引入 LangChain4j 的 Evaluation 模块，也不接
 * LLM-as-a-Judge。原因见架构 §4.1： 那个模块对中文评测与国产模型支持不足，且是重量级依赖。
 *
 * <p><b>确定性是门禁的前提</b>：CI 门禁要求「同一个 commit 跑两次结果完全一致」， 因此首版所有内置指标都必须是纯函数。{@link LlmJudgeMetric} 的 SPI
 * 留在这里， 但默认不注册到引擎，避免引入不确定性。
 *
 * <p>指标取值统一归一化到 <b>0~1</b>，高分一律代表「更好」。需要「越低越好」的指标 （如成本、幻觉率）在实现里做一次 {@code 1 - x} 变换，这样 {@link
 * GateEvaluator} 的比较逻辑 只需要处理「越高越好」一种方向，减少门禁表达式写反的机会。
 */
public interface Metric {

    /** 指标名（门禁表达式里用的名字）。 */
    String NAME_ACCURACY = "accuracy";

    /** 关键词召回率指标名。 */
    String NAME_KEYWORD_RECALL = "keywordRecall";

    /** 包含率指标名。 */
    String NAME_CONTAINMENT = "containment";

    /** JSON 结构合规率指标名。 */
    String NAME_JSON_SCHEMA = "jsonSchema";

    /** 成本归一化得分指标名（原始值越低得分越高）。 */
    String NAME_COST = "cost";

    /** 幻觉率指标名（原始值越低越好，已做 1-x 变换）。 */
    String NAME_HALLUCINATION = "hallucination";

    /**
     * 返回指标名。
     *
     * @return 指标名，不可为空
     */
    String name();

    /**
     * 返回指标的中文描述。
     *
     * @return 描述文本
     */
    default String description() {
        return name();
    }

    /**
     * 返回该指标的优化方向。
     *
     * @return 恒为 {@link Direction#HIGHER_IS_BETTER}（首版所有指标已归一化）
     */
    default Direction direction() {
        return Direction.HIGHER_IS_BETTER;
    }

    /**
     * 对一条评测结果打分。
     *
     * @param result 评测结果，不可为 null
     * @return 0~1 的得分；无法评分时返回 0
     */
    BigDecimal score(EvalResult result);

    /**
     * 归一化到 0~1 并裁剪边界。
     *
     * @param value 原始值
     * @return 裁剪到 [0,1] 且 scale=4 的值
     */
    static BigDecimal normalize(double value) {
        if (Double.isNaN(value)) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        double clamped = Math.max(0.0d, Math.min(1.0d, value));
        return BigDecimal.valueOf(clamped).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 指标优化方向。
     *
     * <p>首版内置指标都归一化为「越高越好」，因此只有 {@link #HIGHER_IS_BETTER} 会被真正使用。 保留 {@link #LOWER_IS_BETTER}
     * 是为了自定义指标（如直接暴露原始幻觉率）能声明自己的方向。
     */
    enum Direction {
        /** 得分越高越好。 */
        HIGHER_IS_BETTER,
        /** 得分越低越好。 */
        LOWER_IS_BETTER
    }
}
