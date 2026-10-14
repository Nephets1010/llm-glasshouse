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

package io.github.nephets.glasshouse.cli.version;

/**
 * 构建版本信息。
 *
 * <p><b>为什么不用 {@code BuildVersion.class} 的包信息</b>：从 jar 运行与从 IDE 运行时 包信息可能为 null，导致 {@code
 * glasshouse --version} 在某些环境下打印 {@code null}。 这里读 Maven 资源过滤生成的 properties 文件，行为确定。
 *
 * <p>该 properties 文件由父 pom 的 {@code maven-resources-plugin} 在构建时生成。
 */
public final class BuildVersion {

    /** 默认版本号，资源缺失时的兜底。 */
    public static final String FALLBACK_VERSION = "0.1.0-SNAPSHOT";

    /** 版本资源文件路径。 */
    public static final String RESOURCE =
            "/io/github/nephets/glasshouse/cli/version/build.properties";

    private static final String VERSION = read("version", FALLBACK_VERSION);
    private static final String NAME = read("name", "llm-glasshouse");

    private BuildVersion() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 返回构建版本。
     *
     * @return 语义化版本号
     */
    public static String version() {
        return VERSION;
    }

    /**
     * 返回构件名。
     *
     * @return 构件名
     */
    public static String name() {
        return NAME;
    }

    /**
     * 返回一行版本信息。
     *
     * @return 形如 {@code llm-glasshouse 0.1.0-SNAPSHOT}
     */
    public static String describe() {
        return NAME + " " + VERSION;
    }

    /**
     * 读取一个资源属性。
     *
     * @param key 属性名
     * @param fallback 缺失时的默认值
     * @return 属性值
     */
    private static String read(String key, String fallback) {
        try (java.io.InputStream in = BuildVersion.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return fallback;
            }
            java.util.Properties props = new java.util.Properties();
            props.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            String value = props.getProperty(key);
            return value == null || value.isBlank() || value.startsWith("${")
                    ? fallback
                    : value.trim();
        } catch (java.io.IOException | RuntimeException e) {
            return fallback;
        }
    }
}
