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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * PII 正则库加载器：从 classpath 的 {@code patterns/pii-cn.yml} 读取规则覆盖。
 *
 * <p><b>失败策略：任何异常都回退到内置规则，绝不让脱敏能力因配置问题而失效。</b> 脱敏是安全兜底，如果一份写错的 YAML 导致「不脱敏」，那这份配置就成了越权开关。
 * 因此这里刻意<b>不抛异常</b>，只返回空覆盖表（等价于用内置规则）。
 */
final class PiiPatternLoader {

    /** classpath 默认规则库位置。 */
    static final String DEFAULT_RESOURCE = "patterns/pii-cn.yml";

    private PiiPatternLoader() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 加载规则覆盖表，任何失败都返回空表。
     *
     * @return 规则名 → 正则表达式；不可为空
     */
    static Map<String, String> loadOrEmpty() {
        try {
            return load(DEFAULT_RESOURCE);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    /**
     * 从 classpath 加载指定规则库。
     *
     * @param resourcePath classpath 资源路径
     * @return 规则名 → 正则表达式
     */
    @SuppressWarnings("unchecked")
    static Map<String, String> load(String resourcePath) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = PiiPatternLoader.class.getClassLoader();
        }
        try (InputStream in = loader.getResourceAsStream(resourcePath)) {
            if (in == null) {
                return Map.of();
            }
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(content);
            if (!(parsed instanceof Map)) {
                return Map.of();
            }
            Map<String, Object> root = (Map<String, Object>) parsed;
            Object patternsNode = root.get("patterns");
            if (!(patternsNode instanceof Map)) {
                return Map.of();
            }
            Map<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) patternsNode).entrySet()) {
                Object regex = e.getValue();
                if (regex != null) {
                    result.put(String.valueOf(e.getKey()), String.valueOf(regex));
                }
            }
            return Map.copyOf(result);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 PII 规则库失败: " + resourcePath, e);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }
}
