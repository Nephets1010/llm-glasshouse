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

/**
 * LLM 玻璃房纯逻辑层。
 *
 * <p>本包及其子包共同实现三仓共享契约（CTX-001 / USG-001 / PRC-001 / EVT-001 / ERR-001 / SPN-001），刻意保持零 Spring
 * 依赖：升级面锁死在 starter 模块，未来迁移 Spring Boot 4.1 + Spring AI 2.x 时本层无需改动。
 *
 * <p>子包职责：
 *
 * <ul>
 *   <li>{@code trace} —— W3C TraceContext 编码/解码与上下文存取
 *   <li>{@code usage} —— Token 计量归一化（含国产模型口径适配）
 *   <li>{@code pricing} —— 价格表与成本计算
 *   <li>{@code model} —— 模型引用与能力描述
 *   <li>{@code telemetry} —— span 命名与属性（SPN-001 唯一出处）
 *   <li>{@code attribution} —— 五维成本归因
 *   <li>{@code audit} —— 审计事件与 PII 脱敏
 *   <li>{@code eval} —— 评测引擎（数据集、指标、门禁）
 * </ul>
 */
package io.github.nephets.glasshouse.core;
