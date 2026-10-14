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
 * 标注一个检索方法，产生 {@code retrieval <retriever>} span。
 *
 * <p>RAG 场景下「回答慢」经常是检索慢而不是模型慢。把检索单独成一个 span， 才能区分这两种情况——这正是 PRD 痛点 1「无法定位到具体哪一步慢」的直接解法。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Retrieval {

    /**
     * 检索器标识。
     *
     * @return 检索器名；留空时回退到「方法名」
     */
    String retriever() default "";

    /**
     * 检索的知识库/数据源标识。
     *
     * @return 标识；留空表示不记录
     */
    String dataSourceId() default "";
}
