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

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 价格表（不可变快照 + 热更新槽位）。
 *
 * <p><b>线程安全模型</b>：内部持有一个 {@link AtomicReference} 指向不可变的 {@code Map}。 读路径无锁（一次 volatile 读 + 一次
 * get），写路径（{@link PricingTableLoader#reload()}）整体替换引用， 因此「读到一半新一半旧」的情况在设计上就不可能发生。
 *
 * <p><b>热更新语义</b>：同 key 多条目时，{@link #lookup} 取 {@code effectiveFrom} 最新的一条， 但不晚于 {@link
 * #lookup(String, String, LocalDate)} 传入的日期。这让「按生效日对账历史账单」成为可能，
 * 而不是简单地「谁最后加载谁赢」——后者会让历史成本随价格调整而漂移，无法审计。
 */
public final class PricingTable {

    private final AtomicReference<Map<String, List<PriceEntry>>> entriesRef;

    /** 构造空价格表。 */
    public PricingTable() {
        this.entriesRef = new AtomicReference<>(Map.of());
    }

    /**
     * 用给定条目构造价格表。
     *
     * @param entries 初始条目，可为 null（按空表处理）
     */
    public PricingTable(Collection<PriceEntry> entries) {
        this();
        replaceAll(entries);
    }

    /**
     * 整体替换价格表内容。
     *
     * @param entries 新条目集合，可为 null
     * @return 被替换掉的旧条目数
     */
    public int replaceAll(Collection<PriceEntry> entries) {
        Map<String, List<PriceEntry>> snapshot = new ConcurrentHashMap<>();
        if (entries != null) {
            for (PriceEntry entry : entries) {
                if (entry == null) {
                    continue;
                }
                snapshot.computeIfAbsent(
                                entry.lookupKey(),
                                k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                        .add(entry);
            }
        }
        Map<String, List<PriceEntry>> previous = entriesRef.getAndSet(Map.copyOf(snapshot));
        int oldSize = 0;
        for (List<PriceEntry> list : previous.values()) {
            oldSize += list.size();
        }
        return oldSize;
    }

    /**
     * 按供应商标识与模型标识查找当前生效的价格。
     *
     * @param providerId 供应商标识，可为 null
     * @param modelId 模型标识，可为 null
     * @return 命中的价格条目；未命中为空
     */
    public Optional<PriceEntry> lookup(String providerId, String modelId) {
        return lookup(providerId, modelId, LocalDate.now());
    }

    /**
     * 按供应商标识、模型标识与生效日查找价格。
     *
     * @param providerId 供应商标识，可为 null
     * @param modelId 模型标识，可为 null
     * @param onDate 生效日；为 null 时按今天
     * @return {@code effectiveFrom <= onDate} 中最新的一条；未命中为空
     */
    public Optional<PriceEntry> lookup(String providerId, String modelId, LocalDate onDate) {
        if (providerId == null || modelId == null) {
            return Optional.empty();
        }
        List<PriceEntry> candidates =
                entriesRef.get().get(PriceEntry.lookupKey(providerId, modelId));
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        LocalDate date = onDate == null ? LocalDate.now() : onDate;
        return candidates.stream()
                .filter(e -> !e.effectiveFrom().isAfter(date))
                .max((a, b) -> a.effectiveFrom().compareTo(b.effectiveFrom()));
    }

    /**
     * 按供应商标识与模型标识获取价格，未命中时返回 null。
     *
     * <p>供成本计算的快路径使用，避免 {@link Optional} 在热路径上的分配。
     *
     * @param providerId 供应商标识
     * @param modelId 模型标识
     * @return 价格条目；未命中返回 null
     */
    public PriceEntry getOrNull(String providerId, String modelId) {
        return lookup(providerId, modelId).orElse(null);
    }

    /**
     * 模糊匹配模型标识。
     *
     * <p>现实中的模型名带日期后缀（{@code qwen-max-2025-01-25}）或部署名（{@code deepseek-chat
     * @company}）。逐字符精确匹配会让这些模型「查不到价格 → 成本记 0」，这是很难排查的静默失真，
     * 因此提供前缀与包含两种回退。
     *
     * @param providerId 供应商标识
     * @param modelId 模型标识
     * @return 命中的价格条目；未命中为空
     */
    public Optional<PriceEntry> lookupFuzzy(String providerId, String modelId) {
        Optional<PriceEntry> exact = lookup(providerId, modelId);
        if (exact.isPresent()) {
            return exact;
        }
        if (providerId == null || modelId == null) {
            return Optional.empty();
        }
        String prefix = PriceEntry.lookupKey(providerId, modelId);
        String lowerModel = modelId.toLowerCase(Locale.ROOT);
        List<PriceEntry> best = null;
        int bestLength = Integer.MAX_VALUE;
        for (Map.Entry<String, List<PriceEntry>> e : entriesRef.get().entrySet()) {
            String key = e.getKey();
            if (!key.startsWith(prefix.substring(0, prefix.indexOf('/') + 1))) {
                continue;
            }
            String candidateModel = key.substring(key.indexOf('/') + 1).toLowerCase(Locale.ROOT);
            boolean hit =
                    candidateModel.startsWith(lowerModel) || lowerModel.startsWith(candidateModel);
            if (!hit) {
                continue;
            }
            for (PriceEntry entry : e.getValue()) {
                if (entry.effectiveFrom().isAfter(LocalDate.now())) {
                    continue;
                }
                if (candidateModel.length() < bestLength) {
                    bestLength = candidateModel.length();
                    best = List.of(entry);
                }
            }
        }
        return best == null || best.isEmpty() ? Optional.empty() : Optional.of(best.get(0));
    }

    /**
     * 返回当前全部条目。
     *
     * @return 不可变列表快照
     */
    public List<PriceEntry> all() {
        List<PriceEntry> result = new java.util.ArrayList<>();
        entriesRef.get().values().forEach(result::addAll);
        return List.copyOf(result);
    }

    /**
     * 返回条目总数。
     *
     * @return 条目数
     */
    public int size() {
        int total = 0;
        for (List<PriceEntry> list : entriesRef.get().values()) {
            total += list.size();
        }
        return total;
    }

    /**
     * 判断价格表是否为空。
     *
     * @return 无条目返回 {@code true}
     */
    public boolean isEmpty() {
        return size() == 0;
    }

    /**
     * 返回当前加载来源描述，供 CLI {@code prices show} 输出。
     *
     * @return 条目数与覆盖的供应商标识集合描述
     */
    public String describe() {
        List<String> providers =
                entriesRef.get().keySet().stream()
                        .map(k -> k.substring(0, Math.max(0, k.indexOf('/'))))
                        .filter(s -> !s.isEmpty())
                        .distinct()
                        .sorted()
                        .toList();
        return "entries=" + size() + ", providers=" + String.join(",", providers);
    }
}
