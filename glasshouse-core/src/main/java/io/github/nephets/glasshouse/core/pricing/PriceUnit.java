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

package io.github.nephets.glasshouse.core.pricing;

/**
 * 价格计价单位（契约 {@code PRC-001}）。
 *
 * <p>首版只支持一种单位。新增单位（例如 {@code PER_1M_TOKENS}）必须三仓同改。
 */
public enum PriceUnit {
    /** 每 1000 token 计价。 */
    PER_1K_TOKENS
}
