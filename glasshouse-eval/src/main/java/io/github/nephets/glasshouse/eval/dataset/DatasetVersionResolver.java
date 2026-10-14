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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import io.github.nephets.glasshouse.core.eval.JsonlDataset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 数据集版本解析器：用 git commit sha 作为版本号。
 *
 * <p><b>为什么用 git sha 而不是自增版本号</b>：评测集文件进 git，sha 就是它的不可变身份。 报告里写着 {@code
 * dataset=customer-service-v1@a3f9b21c4e5d}，任何人都能 checkout 到那个提交 复现出完全一致的评测——这才是「CI 可复现」的含义。
 *
 * <p><b>为什么还要 fallback</b>：CI 里通常只 checkout 单个 commit（无 .git 目录）， 从 jar 运行更是完全没有 git 信息。这时退化为「内容
 * SHA-256 前 12 位」， 它仍然是确定性的内容指纹——内容变了指纹就变，可追溯性不丢，只是粒度从「提交」降到「内容」。
 *
 * <p><b>为什么每次调用都执行 git</b>：这是「首版够用」的选择。评测是低频操作（CI 里一次几分钟）， 每次多几十毫秒的 git 调用完全可以接受；缓存 git sha
 * 反而会引入「缓存过期导致版本号错误」的风险。
 */
public final class DatasetVersionResolver {

    private static final Logger log = LoggerFactory.getLogger(DatasetVersionResolver.class);

    /** 执行 git 命令的超时时间。 */
    public static final long GIT_TIMEOUT_SECONDS = 5L;

    /** 短 sha 的长度。 */
    public static final int SHORT_SHA_LENGTH = 12;

    private final Path repositoryRoot;

    /** 以当前工作目录作为仓库根。 */
    public DatasetVersionResolver() {
        this(Path.of(".").toAbsolutePath().normalize());
    }

    /**
     * 以指定目录作为仓库根。
     *
     * @param repositoryRoot 仓库根目录，不可为 null
     */
    public DatasetVersionResolver(Path repositoryRoot) {
        if (repositoryRoot == null) {
            throw new NullPointerException("repositoryRoot 不能为 null");
        }
        this.repositoryRoot = repositoryRoot;
    }

    /**
     * 解析某个评测集文件的版本。
     *
     * @param file 评测集文件路径，可为 null
     * @return git 短 sha；不可用时返回内容指纹
     */
    public String resolveFor(Path file) {
        String sha = gitSha();
        if (sha != null) {
            return sha;
        }
        return contentFingerprint(file);
    }

    /**
     * 解析某个 classpath 资源的版本。
     *
     * @param resourcePath 资源路径，可为 null
     * @return git 短 sha；不可用时返回 {@code unknown}
     */
    public String resolveForResource(String resourcePath) {
        String sha = gitSha();
        if (sha != null) {
            return sha;
        }
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = DatasetVersionResolver.class.getClassLoader();
        }
        String normalized = resourcePath == null ? "" : resourcePath.replaceFirst("^/", "");
        InputStream in = loader.getResourceAsStream(normalized);
        if (in == null) {
            return JsonlDataset.UNKNOWN_VERSION;
        }
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return DatasetRegistry.fingerprint(DatasetRegistry.readAll(reader));
        } catch (IOException e) {
            return JsonlDataset.UNKNOWN_VERSION;
        }
    }

    /**
     * 执行 {@code git rev-parse --short HEAD}。
     *
     * @return 短 sha；不在 git 仓库或 git 不可用时返回 null
     */
    public String gitSha() {
        Process process = null;
        try {
            ProcessBuilder builder =
                    new ProcessBuilder("git", "rev-parse", "--short=" + SHORT_SHA_LENGTH, "HEAD")
                            .directory(repositoryRoot.toFile())
                            .redirectErrorStream(true);
            process = builder.start();
            String output = readAllQuietly(process);
            if (output == null) {
                return null;
            }
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            // 输出可能带 git 的告警行，只取第一行形似 sha 的
            for (String line : output.split("\\R")) {
                String candidate = line.trim();
                if (!candidate.isEmpty() && candidate.matches("[0-9a-f]{7,40}")) {
                    return candidate;
                }
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException | java.io.IOException e) {
            log.debug("[glasshouse] 获取 git sha 失败（将回退内容指纹）: {}", e.toString());
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * 读取子进程输出，失败返回 null。
     *
     * @param process 子进程
     * @return 输出文本；读取失败返回 null
     */
    private static String readAllQuietly(Process process) {
        try (InputStream in = process.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /**
     * 计算文件内容指纹。
     *
     * @param file 文件路径，可为 null
     * @return 12 位指纹；文件不可读时返回 {@code unknown}
     */
    public String contentFingerprint(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return JsonlDataset.UNKNOWN_VERSION;
        }
        try {
            return DatasetRegistry.fingerprint(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return JsonlDataset.UNKNOWN_VERSION;
        }
    }

    /**
     * 返回仓库根目录。
     *
     * @return 仓库根路径
     */
    public Path repositoryRoot() {
        return repositoryRoot;
    }
}
