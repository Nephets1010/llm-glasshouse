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
import io.github.nephets.glasshouse.starter.store.SpanRingBuffer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GlasshouseWebAutoConfiguration} 的条件装配测试。
 *
 * <p>分别用普通上下文与 servlet Web 上下文验证同一组自动配置：非 Web 应用只拿到核心埋点， Web 应用才额外拿到只读端点。两类条件缺一条，对应的用例就会变红。
 */
class GlasshouseWebAutoConfigurationTest {

    private static final AutoConfigurations AUTOS =
            AutoConfigurations.of(
                    GlasshouseAutoConfiguration.class, GlasshouseWebAutoConfiguration.class);

    private final ApplicationContextRunner plainRunner =
            new ApplicationContextRunner().withConfiguration(AUTOS);

    private final WebApplicationContextRunner webRunner =
            new WebApplicationContextRunner().withConfiguration(AUTOS);

    /** 非 Web 上下文不注册端点，但核心埋点 Bean 照常装配。 */
    @Test
    void nonWebContextKeepsCoreBeansAndSkipsWebEndpoints() {
        plainRunner.run(
                ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(GlasshouseAutoConfiguration.class);
                    assertThat(ctx).hasSingleBean(SpanRingBuffer.class);
                    assertThat(ctx).doesNotHaveBean(GlasshouseWebAutoConfiguration.class);
                    assertThat(ctx).doesNotHaveBean(GlasshouseTraceController.class);
                    assertThat(ctx.containsBean("glasshouseWebAutoConfigurationMarker")).isFalse();
                });
    }

    /** servlet Web 上下文注册控制器与标记 Bean，且核心装配不受影响。 */
    @Test
    void servletWebContextRegistersTraceControllerAndMarker() {
        webRunner.run(
                ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(GlasshouseAutoConfiguration.class);
                    assertThat(ctx).hasSingleBean(GlasshouseWebAutoConfiguration.class);
                    assertThat(ctx).hasSingleBean(GlasshouseTraceController.class);
                    assertThat(ctx.getBean("glasshouseWebAutoConfigurationMarker"))
                            .isEqualTo("glasshouse-web");
                });
    }

    /** Web 应用里关掉开关，两个自动配置和端点一起退出。 */
    @Test
    void disabledPropertySkipsWebEndpointsInWebContext() {
        webRunner
                .withPropertyValues("llm.glasshouse.enabled=false")
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            assertThat(ctx).doesNotHaveBean(GlasshouseAutoConfiguration.class);
                            assertThat(ctx).doesNotHaveBean(GlasshouseWebAutoConfiguration.class);
                            assertThat(ctx).doesNotHaveBean(GlasshouseTraceController.class);
                        });
    }

    /** 缺 RestController 注解类时只跳过 Web 自动配置，核心埋点仍能装配。 */
    @Test
    void missingRestControllerClassSkipsOnlyWebAutoConfiguration() {
        webRunner
                .withClassLoader(new FilteredClassLoader(RestController.class))
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            assertThat(ctx).hasSingleBean(GlasshouseAutoConfiguration.class);
                            assertThat(ctx).hasSingleBean(SpanRingBuffer.class);
                            assertThat(ctx).doesNotHaveBean(GlasshouseWebAutoConfiguration.class);
                            assertThat(ctx).doesNotHaveBean(GlasshouseTraceController.class);
                        });
    }
}
