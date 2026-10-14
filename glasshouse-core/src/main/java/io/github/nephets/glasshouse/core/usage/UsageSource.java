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
 * Token 计量来源（契约 {@code USG-001} 的嵌套枚举，三仓同名同义）。
 *
 * <p>这个枚举存在的根本原因：PRD 痛点 4 指出「流式请求计量恒为 0」是一个静默失真的坑。 只有把「这次计量是供应商真返回的，还是我们估的」显式记在数据里，观测侧才能发现失真，
 * 而不是让一个假的 0 混进成本报表。
 */
public enum UsageSource {

    /** 供应商明确返回了 usage。非流式正常响应，或流式且开启了 include_usage 并收到末尾 usage chunk。 */
    VENDOR_REPORTED,

    /** 用估算器算出来的。典型场景：流式且供应商未返回 usage 块（此时 {@code streamUsageReported=false}）。 */
    ESTIMATED,

    /** 兜底：按 maxTokens 上界计。连估算器也未配置（如 embedding 调用）时使用，用于配额预扣的保守值。 */
    FALLBACK
}
