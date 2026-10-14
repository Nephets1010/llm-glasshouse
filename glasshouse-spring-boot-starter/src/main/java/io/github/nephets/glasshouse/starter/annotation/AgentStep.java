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

package io.github.nephets.glasshouse.starter.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注一个 Agent 步骤方法，产生 {@code invoke_agent <agentId>} span。
 *
 * <p><b>用法</b>：在业务方法上加一行注解即可，业务代码<b>不需要</b>调用任何埋点 API：
 *
 * <pre>{@code
 * @AgentStep(agentId = "planner", stepId = "plan", feature = "customer-service")
 * public String plan(String question) {
 *     return chatClient.prompt().user(question).call().getContent();
 * }
 * }</pre>
 *
 * <p>产生的 span 会自动成为该方法内所有模型调用 span 的父节点，从而形成 「agent → chat」的嵌套树——这正是 README 首屏那张 span 树的形态。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AgentStep {

    /**
     * Agent 标识。
     *
     * @return agentId；留空时回退到「方法名」
     */
    String agentId() default "";

    /**
     * 步骤 ID。
     *
     * @return stepId；留空时回退到「方法名」
     */
    String stepId() default "";

    /**
     * 步骤序号，从 0 开始。
     *
     * @return 序号；默认 -1 表示不记录
     */
    int stepSeq() default -1;

    /**
     * Agent 版本。
     *
     * @return 版本号；留空表示不记录
     */
    String agentVersion() default "";

    /**
     * 业务功能（归因维度）。
     *
     * @return 功能名；留空则回退到线程上已有的维度
     */
    String feature() default "";

    /**
     * Prompt 版本（归因维度）。
     *
     * @return 版本标识；留空则回退到线程上已有的维度
     */
    String promptVersion() default "";
}
