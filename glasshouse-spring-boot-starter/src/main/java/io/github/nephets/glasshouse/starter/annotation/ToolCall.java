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
 * 标注一个工具调用方法，产生 {@code execute_tool <toolName>} span。
 *
 * <p>工具调用是 Agent 场景里最常见的耗时来源之一（「慢在哪」的答案常常在这里）。 加上本注解后，工具耗时会自动出现在 span 树上，CLI 还会标出 {@code ⚠ 慢} 的条目。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolCall {

    /**
     * 工具名。
     *
     * @return 工具名；留空时回退到「方法名」
     */
    String toolName() default "";

    /**
     * 步骤 ID（写入 {@code swy.step.id}）。
     *
     * @return 步骤 ID；留空表示不记录
     */
    String stepId() default "";
}
