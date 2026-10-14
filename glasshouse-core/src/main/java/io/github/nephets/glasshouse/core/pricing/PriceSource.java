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
 * 价格数据来源（契约 {@code PRC-001}）。
 *
 * <p>存在的意义是<b>诚实</b>：官方价与社区估算混在一起时，成本报表如果不做区分，就会把估算值 当成官方价汇报给财务。UI 必须对 {@link #ESTIMATED} 的条目做显式标注。
 */
public enum PriceSource {

    /** 官方价目表。 */
    OFFICIAL,

    /** 社区估算值，UI 必须标注。 */
    ESTIMATED,

    /** 用户自配价格。 */
    CUSTOM
}
