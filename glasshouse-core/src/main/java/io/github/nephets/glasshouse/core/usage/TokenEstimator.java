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

package io.github.nephets.glasshouse.core.usage;

/**
 * Token 估算器 SPI。
 *
 * <p>存在的理由：流式请求下供应商常常不返回 usage（PRD 痛点 4），此时必须有一个可插拔的估算手段， 否则整条链路的成本会静默记为 0。默认实现是 {@link
 * CharHeuristicTokenEstimator}。
 */
public interface TokenEstimator {

    /**
     * 估算一段文本的 token 数。
     *
     * @param text 文本，可为 null
     * @return 估算的 token 数，非负
     */
    long estimateTokens(String text);

    /**
     * 返回估算器名称，用于在 UI 与日志中标注「本次是估算值」。
     *
     * @return 估算器名称
     */
    default String name() {
        return getClass().getSimpleName();
    }
}
