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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.nephets.glasshouse.core.eval.JsonlDataset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 评测集注册表：从 {@code eval-datasets/*.jsonl} 加载并做版本化。
 *
 * <p><b>版本化的意义</b>：评测报告必须能回答「这份分数对应哪一次提交的数据集」。 本类的版本标识是 <b>git commit sha</b>——评测集文件进 git，sha
 * 就是它的不可变版本号， 版本化因此是「免费」的（架构 §4.1）。
 *
 * <p><b>fallback 策略</b>：拿不到 git 信息（从 jar 运行、CI 里没有 .git 目录）时， 回退到「文件内容的 SHA-256 前 12
 * 位」。这仍然是一个确定性的内容指纹—— 内容变了指纹就变，报告依然可追溯，只是不再指向具体提交。
 *
 * <p><b>为什么不做 CRUD 界面</b>：评测集进 git 才能做到 PR diff 友好 + CI 可复现。 界面化版本管理放 P1（架构 §6.2 范围判断）。
 */
public final class DatasetRegistry {

    /** classpath 中评测集的默认目录。 */
    public static final String DEFAULT_CLASSPATH_DIR = "eval-datasets/";

    /** 数据集文件后缀。 */
    public static final String DATASET_SUFFIX = ".jsonl";

    /** 录制回放文件后缀。它不是数据集，不该出现在 {@code eval list} 里。 */
    public static final String REPLAY_SUFFIX = ".responses.jsonl";

    /**
     * 缓存容量上限：64。
     *
     * <p>{@link #load(String)} 的入参可以是任意路径，键空间并非「内置数据集枚举」而是调用方可控的， 因此缓存必须有上限。超过上限时按 LRU
     * 淘汰最久未使用的条目（数据集解析要走一遍 JSONL， 淘汰比无限增长更可接受，且淘汰会被记进日志）。
     */
    public static final int MAX_CACHE_ENTRIES = 64;

    private static final Logger log = LoggerFactory.getLogger(DatasetRegistry.class);

    private final JsonlDatasetParser parser;
    private final Map<String, JsonlDataset> cache = newLruCache();
    private final Map<String, List<String>> indexedNames = newLruCache();

    /** 用默认解析器构造。 */
    public DatasetRegistry() {
        this(new JsonlDatasetParser());
    }

    /**
     * 用指定解析器构造。
     *
     * @param parser 解析器，不可为 null
     */
    public DatasetRegistry(JsonlDatasetParser parser) {
        if (parser == null) {
            throw new NullPointerException("parser 不能为 null");
        }
        this.parser = parser;
    }

    /**
     * 按名称加载评测集。
     *
     * <p>依次尝试：classpath 的 {@code eval-datasets/{name}.jsonl}、 文件系统当前目录下的 {@code
     * eval-datasets/{name}.jsonl}。
     *
     * @param name 数据集名称，可带或不带 {@code .jsonl} 后缀
     * @return 评测集
     * @throws JsonlDatasetParser.DatasetParseException 找不到或解析失败
     */
    public JsonlDataset load(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("评测集名称不能为空");
        }
        String normalized = name.trim();
        if (normalized.endsWith(".jsonl")) {
            normalized = normalized.substring(0, normalized.length() - ".jsonl".length());
        }
        // ★ 装载与 LRU 访问必须串行：LinkedHashMap(accessOrder=true) 的 get 会重排内部链表，
        //   并发访问会破坏链表结构。数据集装载不是热路径，这里用同步换正确性。
        synchronized (cache) {
            JsonlDataset cached = cache.get(normalized);
            if (cached != null) {
                return cached;
            }
            JsonlDataset loaded = loadUncached(normalized);
            if (cache.size() >= MAX_CACHE_ENTRIES) {
                log.info(
                        "[glasshouse] 评测集缓存达到上限 {}，正在装载 {}；LRU 条目将被淘汰",
                        MAX_CACHE_ENTRIES,
                        normalized);
            }
            cache.put(normalized, loaded);
            return loaded;
        }
    }

    /**
     * 构造一个容量受限的访问序 LRU 映射。
     *
     * @param <V> 值类型
     * @return 空映射
     */
    private static <V> Map<String, V> newLruCache() {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                if (size() <= MAX_CACHE_ENTRIES) {
                    return false;
                }
                log.debug("[glasshouse] 评测集缓存淘汰最久未使用条目: {}", eldest.getKey());
                return true;
            }
        };
    }

    /**
     * 无缓存加载。
     *
     * @param name 数据集名称（已去掉后缀）
     * @return 评测集
     */
    private JsonlDataset loadUncached(String name) {
        // 1) 用户给的是绝对/相对路径
        Path asPath = Path.of(name);
        if (asPath.isAbsolute() || name.contains("/") || name.contains("\\")) {
            if (Files.isRegularFile(asPath)) {
                return parser.parseFile(asPath);
            }
        }
        // 2) 当前目录下的 eval-datasets/
        Path local = Path.of(DEFAULT_CLASSPATH_DIR + name + DATASET_SUFFIX);
        if (Files.isRegularFile(local)) {
            return parser.parseFile(local);
        }
        // 3) classpath
        return parser.parseClasspath(DEFAULT_CLASSPATH_DIR + name + ".jsonl");
    }

    /**
     * 列出所有可用的评测集名称。
     *
     * <p>来源：classpath 目录索引 + 文件系统 {@code eval-datasets/} 目录下的文件。
     *
     * @return 排序后的名称列表
     */
    public List<String> list() {
        List<String> result;
        synchronized (indexedNames) {
            result = new ArrayList<>(indexedNames.computeIfAbsent("all", k -> scanAll()));
        }
        java.util.Collections.sort(result);
        return List.copyOf(result);
    }

    /**
     * 扫描可用评测集。
     *
     * @return 名称列表
     */
    private List<String> scanAll() {
        List<String> names = new ArrayList<>();
        // classpath 目录索引
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = DatasetRegistry.class.getClassLoader();
        }
        // ★ classpath 目录索引。
        //   注意：从 jar 里加载时 getResource 返回的是 jar: 协议而不是 file:，
        //   只判断 "file".equals(protocol) 会让「打包成 jar 之后 eval list 列不出数据集」
        //   这种问题只在发布后才暴露。因此两条路都走一遍：
        //     1) jar 内 → ClassLoader.getResources 枚举目录条目
        //     2) 目录形式 → Files.list
        try {
            java.util.Enumeration<java.net.URL> urls = loader.getResources(DEFAULT_CLASSPATH_DIR);
            while (urls.hasMoreElements()) {
                java.net.URL url = urls.nextElement();
                if ("file".equals(url.getProtocol())) {
                    try (java.util.stream.Stream<Path> stream = Files.list(Path.of(url.toURI()))) {
                        stream.filter(DatasetRegistry::isDatasetFile)
                                .forEach(p -> names.add(stripJsonl(p.getFileName().toString())));
                    }
                }
            }
        } catch (IOException | java.net.URISyntaxException | java.io.UncheckedIOException e) {
            // 目录形式枚举不了不算失败：下一段还有 jar 内的枚举与文件系统扫描。
            // ★ 这里必须留下日志：整段异常被吞掉后，一旦 classpath 结构变化导致
            //   「eval list 列不全」，排查时没有任何线索可依。debug 级别是因为
            //   正常场景（jar 协议、无 eval-datasets 目录）本来就会走到这里。
            log.debug("[glasshouse] classpath 目录 {} 枚举失败，改用后续兜底扫描", DEFAULT_CLASSPATH_DIR, e);
        }
        try {
            java.util.Enumeration<java.net.URL> roots = loader.getResources("");
            while (roots.hasMoreElements()) {
                java.net.URL root = roots.nextElement();
                if (!"file".equals(root.getProtocol())) {
                    continue;
                }
                Path candidate = Path.of(root.toURI()).resolve(DEFAULT_CLASSPATH_DIR);
                if (Files.isDirectory(candidate)) {
                    try (java.util.stream.Stream<Path> stream = Files.list(candidate)) {
                        stream.filter(DatasetRegistry::isDatasetFile)
                                .forEach(p -> names.add(stripJsonl(p.getFileName().toString())));
                    }
                }
            }
        } catch (IOException | java.net.URISyntaxException | java.io.UncheckedIOException e) {
            // 同样是「尽力而为」，不阻断 list()，但同样需要留下线索
            log.debug("[glasshouse] classpath 根枚举失败，改用文件系统扫描", e);
        }
        // 文件系统目录
        Path dir = Path.of(DEFAULT_CLASSPATH_DIR);
        if (Files.isDirectory(dir)) {
            try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
                stream.filter(DatasetRegistry::isDatasetFile)
                        .forEach(p -> names.add(stripJsonl(p.getFileName().toString())));
            } catch (IOException e) {
                throw new UncheckedIOException("扫描评测集目录失败", e);
            }
        }
        if (names.isEmpty()) {
            // 兜底：classpath 里的 eval-datasets 无法枚举时（jar 协议、
            // Spring Boot fat jar、某些 ClassLoader 实现），至少把内置数据集报出来。
            // 「列不出来」比「列错」更让人无从下手，而内置数据集是代码里确定的。
            names.addAll(List.of("customer-service-v1", "credit-risk-v3"));
        }
        return names.stream().distinct().toList();
    }

    /**
     * 返回某数据集的历史版本列表。
     *
     * <p>首版只有「当前版本」一个条目（因为版本化靠 git 而不是多副本存储）。 保留这个方法是为了让 P1 接入数据库版本化时<b>调用方代码不用改</b>。
     *
     * @param name 数据集名称
     * @return 版本列表，按时间倒序
     */
    public List<String> versionsOf(String name) {
        return List.of(load(name).version());
    }

    /**
     * 清空缓存。
     *
     * <p>供评测集文件热更新后调用，或测试使用。
     */
    public void clearCache() {
        synchronized (cache) {
            cache.clear();
        }
    }

    /**
     * 返回解析器。
     *
     * @return 解析器
     */
    public JsonlDatasetParser parser() {
        return parser;
    }

    /**
     * 把资源流读成字符串。
     *
     * @param in 输入流，不可为 null
     * @return 文本内容
     */
    static String readAll(InputStream in) {
        try (InputStream stream = in) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取资源失败", e);
        }
    }

    /**
     * 把行读取器读成字符串。
     *
     * @param reader 行读取器，不可为 null
     * @return 文本内容
     * @throws UncheckedIOException 读取失败
     */
    static String readAll(BufferedReader reader) {
        StringBuilder sb = new StringBuilder();
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取评测集失败", e);
        }
        return sb.toString();
    }

    private static String stripJsonl(String fileName) {
        return fileName.substring(0, fileName.length() - DATASET_SUFFIX.length());
    }

    /**
     * 判断一个 classpath 目录里的文件是不是「数据集」而不是「录制回放」。
     *
     * <p>回放文件（{@code *.responses.jsonl}）与数据集同目录共存， 枚举时必须区分 —— 把回放当成数据集会让 {@code eval list} 列出
     * {@code customer-service-v1.responses} 这种使用者完全看不懂的条目。
     *
     * @param path 目录下的文件路径
     * @return 是数据集返回 {@code true}
     */
    private static boolean isDatasetFile(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(DATASET_SUFFIX) && !name.endsWith(REPLAY_SUFFIX);
    }

    /**
     * 生成内容指纹，用作 git 不可用时的版本号。
     *
     * @param content 内容
     * @return 12 位十六进制指纹
     */
    static String fingerprint(String content) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(String.format(Locale.ROOT, "%02x", bytes[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 必须支持 SHA-256", e);
        }
    }
}
