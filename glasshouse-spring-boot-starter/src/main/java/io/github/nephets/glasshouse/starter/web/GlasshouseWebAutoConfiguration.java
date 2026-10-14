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

package io.github.nephets.glasshouse.starter.web;

import io.github.nephets.glasshouse.starter.GlasshouseAutoConfiguration;
import io.github.nephets.glasshouse.starter.GlasshouseProperties;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.RestController;

/**
 * Web 端点自动配置：只读地把链路与成本暴露成 JSON。
 *
 * <p>抽成独立的自动配置而不是直接把 {@code @RestController} 打在类上， 是为了让<b>非 Web 应用也能用 starter</b>（批处理、定时任务场景）——
 * 这类应用引入 starter 后不会因为找不到 Servlet 组件而起不来。
 */
@AutoConfiguration(after = GlasshouseAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(RestController.class)
@ConditionalOnProperty(
        prefix = GlasshouseProperties.PREFIX,
        name = "enabled",
        matchIfMissing = true,
        havingValue = "true")
@Import(GlasshouseTraceController.class)
public class GlasshouseWebAutoConfiguration {

    /** 默认构造。 */
    public GlasshouseWebAutoConfiguration() {
        // 标记类，Bean 定义全部通过 @Import 引入
    }

    /**
     * 保留一个占位 Bean，使本自动配置在某些严格模式下也产生明确的上下文贡献。
     *
     * @return 固定标记
     */
    @Bean
    @ConditionalOnMissingBean(name = "glasshouseWebAutoConfigurationMarker")
    public String glasshouseWebAutoConfigurationMarker() {
        return "glasshouse-web";
    }
}
