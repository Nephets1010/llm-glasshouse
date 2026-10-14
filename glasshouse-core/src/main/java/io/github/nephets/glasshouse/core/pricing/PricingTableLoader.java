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

package io.github.nephets.glasshouse.core.pricing;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 价格表加载器：支持 YAML / JSON，价格变更<b>无需改代码、无需重启</b>（PRD 仓库③ P0-7）。
 *
 * <p><b>为什么必须热更新</b>：模型厂商调价是常态（2024~2026 年国产模型调价超过十次）。如果价格写死在代码里， 每次调价都要发一个版本，成本报表在发版前就是错的。热更新让「改一个
 * YAML 文件」成为唯一动作。
 *
 * <p><b>YAML 文件格式</b>（{@code prices/glasshouse-prices.yml}）：
 *
 * <pre>{@code
 * currency: CNY
 * entries:
 *   - providerId: deepseek
 *     modelId: deepseek-chat
 *     inputPricePerUnit: 0.0012      # CNY / 1K tokens
 *     outputPricePerUnit: 0.0012
 *     cachedInputPricePerUnit: 0.00003
 *     effectiveFrom: 2026-01-01
 *     source: OFFICIAL
 * }</pre>
 *
 * <p><b>失败策略</b>：单条条目缺字段或格式错误时<b>跳过该条并记录</b>，不让一条坏数据导致整张表加载失败。
 * 这条策略很重要——运维改价格时敲错一个字段，如果导致整个价格表清空，成本报表会从「略偏」变成「全零」， 故障面积反而扩大。{@link LoadResult} 收集被跳过的条目供 {@code
 * glasshouse prices validate} 展示。
 */
public final class PricingTableLoader {

    /** classpath 默认价格表位置。 */
    public static final String DEFAULT_CLASSPATH_RESOURCE = "prices/glasshouse-prices.yml";

    private static final String KEY_CURRENCY = "currency";
    private static final String KEY_ENTRIES = "entries";
    private static final String KEY_PROVIDER = "providerId";
    private static final String KEY_MODEL = "modelId";
    private static final String KEY_UNIT = "unit";
    private static final String KEY_INPUT_PRICE = "inputPricePerUnit";
    private static final String KEY_OUTPUT_PRICE = "outputPricePerUnit";
    private static final String KEY_REASONING_PRICE = "reasoningPricePerUnit";
    private static final String KEY_CACHED_PRICE = "cachedInputPricePerUnit";
    private static final String KEY_EFFECTIVE_FROM = "effectiveFrom";
    private static final String KEY_SOURCE = "source";

    private final List<String> skipped = new ArrayList<>();
    private String lastLoadedLocation = "(none)";

    /**
     * 从 classpath 加载默认价格表。
     *
     * @return 加载结果
     * @throws IllegalStateException classpath 上找不到价格表
     */
    public LoadResult loadFromClasspath() {
        return loadFromClasspath(DEFAULT_CLASSPATH_RESOURCE);
    }

    /**
     * 从 classpath 加载指定价格表。
     *
     * @param resourcePath classpath 资源路径，不可为 null
     * @return 加载结果
     * @throws IllegalStateException 资源不存在
     */
    public LoadResult loadFromClasspath(String resourcePath) {
        Objects.requireNonNull(resourcePath, "resourcePath 不能为 null");
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = PricingTableLoader.class.getClassLoader();
        }
        try (InputStream in = loader.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("classpath 上找不到价格表: " + resourcePath);
            }
            return parse(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    "classpath:" + resourcePath);
        } catch (IOException e) {
            throw new UncheckedIOException("读取价格表失败: " + resourcePath, e);
        }
    }

    /**
     * 从文件系统加载价格表。
     *
     * @param file 文件路径，不可为 null
     * @return 加载结果
     * @throws IllegalStateException 文件不存在
     */
    public LoadResult loadFromFile(Path file) {
        Objects.requireNonNull(file, "file 不能为 null");
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("价格表文件不存在: " + file.toAbsolutePath());
        }
        try {
            return parse(
                    Files.readString(file, StandardCharsets.UTF_8),
                    file.toAbsolutePath().toString());
        } catch (IOException e) {
            throw new UncheckedIOException("读取价格表失败: " + file.toAbsolutePath(), e);
        }
    }

    /**
     * 从字符串解析价格表。
     *
     * @param yamlContent YAML 或 JSON 内容（YAML 是 JSON 的超集），可为 null
     * @param location 来源描述，用于日志与 {@link LoadResult}
     * @return 加载结果，永不为 null
     */
    @SuppressWarnings("unchecked")
    public LoadResult parse(String yamlContent, String location) {
        skipped.clear();
        lastLoadedLocation = location == null ? "(inline)" : location;
        if (yamlContent == null || yamlContent.isBlank()) {
            return new LoadResult(List.of(), List.copyOf(skipped), lastLoadedLocation);
        }
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object parsed = new Yaml(new SafeConstructor(options)).load(yamlContent);
        if (!(parsed instanceof Map)) {
            skipped.add("根节点不是映射结构，已跳过整个文件: " + lastLoadedLocation);
            return new LoadResult(List.of(), List.copyOf(skipped), lastLoadedLocation);
        }
        Map<String, Object> root = (Map<String, Object>) parsed;
        String currency = str(root.get(KEY_CURRENCY));
        if (currency == null) {
            currency = PriceEntry.CURRENCY_CNY;
        }
        List<PriceEntry> parsedEntries = new ArrayList<>();
        Object rawEntries = root.get(KEY_ENTRIES);
        if (rawEntries instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                if (!(item instanceof Map)) {
                    skipped.add("entries[" + i + "] 不是映射结构，已跳过");
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> entry = (Map<String, Object>) item;
                try {
                    parsedEntries.add(toEntry(entry, currency));
                } catch (RuntimeException e) {
                    // 错误信息必须带上条目自身的标识：只说「第 2 条」运维根本不知道是哪条模型
                    skipped.add(
                            "entries["
                                    + i
                                    + "]（"
                                    + identify(entry)
                                    + "）解析失败已跳过: "
                                    + e.getMessage());
                }
            }
        } else if (rawEntries != null) {
            skipped.add("entries 字段不是数组，已跳过: " + lastLoadedLocation);
        }
        return new LoadResult(List.copyOf(parsedEntries), List.copyOf(skipped), lastLoadedLocation);
    }

    /**
     * 加载并整体替换给定价格表。
     *
     * @param table 目标价格表，不可为 null
     * @param content YAML 内容
     * @return 加载结果
     */
    public LoadResult reload(PricingTable table, String content) {
        LoadResult result = parse(content, "reload");
        table.replaceAll(result.entries());
        return result;
    }

    /**
     * 从文件系统加载并整体替换给定价格表。
     *
     * @param table 目标价格表，不可为 null
     * @param file 文件路径
     * @return 加载结果
     */
    public LoadResult reload(PricingTable table, Path file) {
        LoadResult result = loadFromFile(file);
        table.replaceAll(result.entries());
        return result;
    }

    /**
     * 从 YAML 映射里取出可读标识，用于错误信息。
     *
     * <p>运维改价格时最需要知道的是「哪条模型的价没生效」，因此即使条目解析失败， 也要尽量把 providerId / modelId 报出来。
     *
     * @param item 条目映射
     * @return 形如 {@code deepseek/deepseek-chat} 的标识；字段缺失时返回 {@code ?/?}
     */
    private static String identify(Map<String, Object> item) {
        String provider = str(item.get(KEY_PROVIDER));
        String model = str(item.get(KEY_MODEL));
        return (provider == null ? "?" : provider) + "/" + (model == null ? "?" : model);
    }

    /**
     * 把一条 YAML 映射转为价格条目。
     *
     * @param item 映射，不可为 null
     * @param currency 默认币种，可为 null（回退 CNY）
     * @return 价格条目
     */
    @SuppressWarnings("unchecked")
    private static PriceEntry toEntry(Map<String, Object> item, String currency) {
        String providerId = requireNonBlank(str(item.get(KEY_PROVIDER)), KEY_PROVIDER);
        String modelId = requireNonBlank(str(item.get(KEY_MODEL)), KEY_MODEL);
        BigDecimal inputPrice = decimal(item.get(KEY_INPUT_PRICE));
        if (inputPrice == null) {
            throw new IllegalArgumentException(KEY_INPUT_PRICE + " 缺失或不是数值");
        }
        BigDecimal outputPrice = decimal(item.get(KEY_OUTPUT_PRICE));
        if (outputPrice == null) {
            throw new IllegalArgumentException(KEY_OUTPUT_PRICE + " 缺失或不是数值");
        }
        PriceUnit unit = PriceUnit.PER_1K_TOKENS;
        String unitText = str(item.get(KEY_UNIT));
        if (unitText != null && !unitText.isBlank()) {
            unit = PriceUnit.valueOf(unitText.trim().toUpperCase(java.util.Locale.ROOT));
        }
        PriceSource source = PriceSource.OFFICIAL;
        String sourceText = str(item.get(KEY_SOURCE));
        if (sourceText != null && !sourceText.isBlank()) {
            source = PriceSource.valueOf(sourceText.trim().toUpperCase(java.util.Locale.ROOT));
        }
        LocalDate effectiveFrom = toLocalDate(item.get(KEY_EFFECTIVE_FROM));
        String entryCurrency = str(item.get(KEY_CURRENCY));
        return new PriceEntry(
                providerId,
                modelId,
                entryCurrency == null ? currency : entryCurrency,
                unit,
                inputPrice,
                outputPrice,
                decimal(item.get(KEY_REASONING_PRICE)),
                decimal(item.get(KEY_CACHED_PRICE)),
                effectiveFrom,
                source);
    }

    /**
     * 返回最近一次加载中被跳过的条目描述。
     *
     * @return 不可变列表；无跳过时为空
     */
    public List<String> skippedEntries() {
        return List.copyOf(skipped);
    }

    /**
     * 返回最近一次加载的来源位置。
     *
     * @return 来源描述
     */
    public String lastLoadedLocation() {
        return lastLoadedLocation;
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * 把 YAML 节点转为 {@link LocalDate}。
     *
     * <p><b>关键坑（测试捕获的真实 bug）</b>：SnakeYAML 会把<b>不加引号</b>的 {@code effectiveFrom: 2026-01-01} 解析成
     * {@link java.util.Date}，不是 {@link String}。 若只用 {@code
     * LocalDate.parse(str(value))}，内置价格表的<b>每一条</b>都会解析失败， 整个价格表加载为空 → 成本全记 0 → 整个「热更新价格表」卖点失效且静默。
     *
     * <p>这里显式处理 {@link java.util.Date}，并按系统默认时区转换—— 与 SnakeYAML 自身解析行为一致，因此同一个文件在任何机器上都得到同一天。
     *
     * @param value YAML 节点值
     * @return 日期；无法解析抛 {@link IllegalArgumentException}
     */
    private static LocalDate toLocalDate(Object value) {
        if (value == null) {
            throw new IllegalArgumentException(KEY_EFFECTIVE_FROM + " 缺失");
        }
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof java.util.Date date) {
            return date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        }
        String text = str(value);
        if (text == null) {
            throw new IllegalArgumentException(KEY_EFFECTIVE_FROM + " 缺失");
        }
        try {
            return LocalDate.parse(text);
        } catch (java.time.format.DateTimeParseException e) {
            // SnakeYAML 也可能给出默认 toString 形态
            try {
                return LocalDate.parse(
                        text,
                        java.time.format.DateTimeFormatter.ofPattern(
                                "EEE MMM dd HH:mm:ss zzz yyyy", java.util.Locale.ENGLISH));
            } catch (RuntimeException ignored) {
                throw new IllegalArgumentException(KEY_EFFECTIVE_FROM + " 不是合法日期: " + text);
            }
        }
    }

    /**
     * 把 YAML 节点转为 {@link BigDecimal}。
     *
     * <p>注意 {@link java.util.Date} 走到这里的 {@code str(value)} 分支会得到 {@code "Thu Jan 01 08:00:00 CST
     * 2026"}，{@code new BigDecimal} 会抛 {@code NumberFormatException} 并被 catch 成 null——这正是「坏数据只跳过单条」
     * 策略要保护的情况。
     *
     * @param value YAML 节点值
     * @return 金额；无法转换返回 null
     */
    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        String s = str(value);
        if (s == null) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " 缺失");
        }
        return value;
    }

    /**
     * 一次加载的结果。
     *
     * @param entries 成功解析的条目
     * @param skipped 被跳过的条目描述（用于运维排错，不影响已解析条目生效）
     * @param location 来源位置
     */
    public record LoadResult(List<PriceEntry> entries, List<String> skipped, String location) {

        /**
         * 构造加载结果并校验非空。
         *
         * @param entries 成功解析的条目
         * @param skipped 跳过描述
         * @param location 来源位置
         */
        public LoadResult {
            entries = entries == null ? List.of() : List.copyOf(entries);
            skipped = skipped == null ? List.of() : List.copyOf(skipped);
            location = location == null ? "(unknown)" : location;
        }

        /**
         * 本次加载是否有被跳过的条目。
         *
         * @return 有跳过返回 {@code true}
         */
        public boolean hasSkipped() {
            return !skipped.isEmpty();
        }

        /**
         * 生成人类可读摘要。
         *
         * @return 单行摘要
         */
        public String summary() {
            return "loaded="
                    + entries.size()
                    + ", skipped="
                    + skipped.size()
                    + ", from="
                    + location
                    + (hasSkipped() ? " (" + String.join("; ", skipped) + ")" : "");
        }
    }
}
