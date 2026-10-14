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

package io.github.nephets.glasshouse.core.audit;

/** 审计 / 轨迹事件类型（契约 {@code EVT-001}）。三仓共用同一份取值。 */
public enum EventType {

    /** 模型调用。 */
    MODEL_CALL,

    /** 工具调用。 */
    TOOL_CALL,

    /** 检索。 */
    RETRIEVAL,

    /** 人工审批。 */
    APPROVAL,

    /** 编排流程步骤。 */
    FLOW_STEP
}
