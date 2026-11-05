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

package io.github.nephets.glasshouse.eval.dataset;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

import io.github.nephets.glasshouse.core.eval.JsonlDataset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数据集装载路径与版本回退测试。
 *
 * <p>版本号是报告可追溯性的根：报告里写着 {@code dataset=x@<sha>}，第三方才能 checkout 到同一份数据。 因此本类钉两件事——在没有 git
 * 的环境里回退到「内容指纹」是否确定，以及装载时相对路径 / 绝对路径 的命中顺序是否符合预期。
 *
 * <p><b>不断言具体 git sha</b>：sha 每次提交都变，断言它会让测试与提交历史耦合，每次提交全红。 需要断言版本时只断言「12 位十六进制」或「与独立计算的 SHA-256
 * 前缀一致」。
 */
class DatasetSourceTest {

    private static final String ONE_CASE =
            "{\"id\":\"ok-1\",\"input\":{\"question\":\"q\"},"
                    + "\"expected\":{\"contains\":[\"x\"]},\"metadata\":{\"authored\":\"t\"}}";

    @TempDir Path tempDir;

    /**
     * 独立计算内容的 SHA-256 前 12 位十六进制。
     *
     * <p>与生产实现分开算，才能证明「指纹确实是内容的 SHA-256 前缀」，而不是把实现里的常量抄回来。
     *
     * @param content 文本内容
     * @return 12 位小写十六进制
     */
    private static String sha256Prefix(String content) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(String.format(Locale.ROOT, "%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 必须支持 SHA-256", e);
        }
    }

    @Test
    @DisplayName("仓库根为 null 时构造立即失败")
    void resolverRejectsNullRoot() {
        assertThatThrownBy(() -> new DatasetVersionResolver(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("repositoryRoot");
    }

    @Test
    @DisplayName("★ 没有 .git 的目录：版本回退成内容的 SHA-256 前 12 位")
    void nonGitDirectoryFallsBackToContentFingerprint() throws IOException {
        Path file = tempDir.resolve("ds.jsonl");
        Files.writeString(file, ONE_CASE + "\n", StandardCharsets.UTF_8);

        String version = new DatasetVersionResolver(tempDir).resolveFor(file);

        assertThat(version)
                .as("★ 独立算出的 SHA-256 前缀必须与实现一致，否则「内容指纹」只是自说自话")
                .isEqualTo(sha256Prefix(ONE_CASE + "\n"))
                .matches("[0-9a-f]{12}")
                .isNotEqualTo(JsonlDataset.UNKNOWN_VERSION);
    }

    @Test
    @DisplayName("★ 内容变了指纹必须变（否则报告无法区分两份不同的数据集）")
    void fingerprintChangesWhenContentChanges() throws IOException {
        Path file = tempDir.resolve("ds.jsonl");
        DatasetVersionResolver resolver = new DatasetVersionResolver(tempDir);
        Files.writeString(file, ONE_CASE + "\n", StandardCharsets.UTF_8);
        String before = resolver.resolveFor(file);
        Files.writeString(
                file, ONE_CASE.replace("\"q\"", "\"改过的问题\"") + "\n", StandardCharsets.UTF_8);
        String after = resolver.resolveFor(file);

        assertThat(after).isNotEqualTo(before).matches("[0-9a-f]{12}");
    }

    @Test
    @DisplayName("内容指纹：null / 目录 / 不存在的文件都返回 unknown，不抛异常")
    void contentFingerprintHandlesUnreadableTargets() {
        DatasetVersionResolver resolver = new DatasetVersionResolver(tempDir);

        assertThat(resolver.contentFingerprint(null)).isEqualTo(JsonlDataset.UNKNOWN_VERSION);
        assertThat(resolver.contentFingerprint(tempDir))
                .as("目录不是普通文件，指纹无从谈起")
                .isEqualTo(JsonlDataset.UNKNOWN_VERSION);
        assertThat(resolver.contentFingerprint(tempDir.resolve("no-such-file")))
                .as("不存在的文件等价于不可读：返回 unknown 而不是抛异常")
                .isEqualTo(JsonlDataset.UNKNOWN_VERSION);
    }

    @Test
    @DisplayName("★ classpath 资源不存在时版本返回 unknown（而不是 null 或抛异常）")
    void resolveForResourceWithoutResourceIsUnknown() {
        DatasetVersionResolver resolver = new DatasetVersionResolver(tempDir);

        assertThat(resolver.resolveForResource("eval-datasets/no-such.jsonl"))
                .isEqualTo(JsonlDataset.UNKNOWN_VERSION);
    }

    @Test
    @DisplayName("★ classpath 资源存在时用内容指纹：不同资源指纹不同、前导斜杠不影响结果")
    void resolveForResourceUsesContentFingerprint() {
        DatasetVersionResolver resolver = new DatasetVersionResolver(tempDir);

        String cs = resolver.resolveForResource("eval-datasets/customer-service-v1.jsonl");
        String credit = resolver.resolveForResource("eval-datasets/credit-risk-v3.jsonl");
        String leadingSlash =
                resolver.resolveForResource("/eval-datasets/customer-service-v1.jsonl");

        assertThat(cs)
                .as("★ 资源指纹必须是 12 位十六进制，且不能退化成 unknown")
                .matches("[0-9a-f]{12}")
                .isNotEqualTo(JsonlDataset.UNKNOWN_VERSION);
        assertThat(credit).as("★ 两份内容不同的数据集不能算出同一个指纹").isNotEqualTo(cs);
        assertThat(leadingSlash).as("前导斜杠必须被裁掉").isEqualTo(cs);
    }

    @Test
    @DisplayName("DatasetRegistry 解析器为 null 时构造立即失败")
    void registryRejectsNullParser() {
        assertThatThrownBy(() -> new DatasetRegistry(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("parser");
    }

    @Test
    @DisplayName("★ 相对路径命中：带斜杠且文件存在时直接按文件装载，数据集名取自文件名（不含后缀）")
    void registryLoadsRelativePathWithoutExtension() throws IOException {
        Path dir = Path.of("target");
        Files.createDirectories(dir);
        Path file = dir.resolve("gh16-relative-dataset");
        Files.writeString(file, ONE_CASE + "\n", StandardCharsets.UTF_8);
        try {
            JsonlDataset dataset = new DatasetRegistry().load("target/gh16-relative-dataset");

            assertThat(dataset.size()).isEqualTo(1);
            assertThat(dataset.name())
                    .as("★ 名字来自文件名而不是整条路径，报告里才不会出现 target/ 前缀")
                    .isEqualTo("gh16-relative-dataset");
            assertThat(dataset.version()).isNotBlank();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    @DisplayName("★ 绝对路径命中：文件名不带 .jsonl 后缀也能装载，名字取文件名本身")
    void registryLoadsAbsolutePathWithoutExtension() throws IOException {
        Path file = tempDir.resolve("gh16-absolute-dataset");
        Files.writeString(file, ONE_CASE + "\n", StandardCharsets.UTF_8);

        JsonlDataset dataset = new DatasetRegistry().load(file.toString());

        assertThat(dataset.size()).isEqualTo(1);
        assertThat(dataset.name()).isEqualTo("gh16-absolute-dataset");
    }
}
