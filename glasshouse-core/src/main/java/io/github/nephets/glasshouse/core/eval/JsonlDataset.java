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

package io.github.nephets.glasshouse.core.eval;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSONL 评测集。
 *
 * <p><b>为什么用文件而不是数据库</b>：评测集进 git 才能做到「PR diff 友好 + CI 可复现 + 版本化免费」。 数据库版本化放 P1。这里的 {@code version}
 * 就是 git commit sha， 配合 {@code DatasetVersionResolver} 让「这份报告对应哪一次提交」永远可追溯。
 *
 * @param name 数据集名称，如 {@code customer-service-v1}
 * @param version 版本标识（git commit sha 或手工版本号）
 * @param cases 用例列表，不可为空
 * @param description 数据集说明，可为 null
 */
public record JsonlDataset(String name, String version, List<EvalCase> cases, String description) {

    /** 默认版本标识，用于无法解析 git sha 的场景（如从 jar 运行）。 */
    public static final String UNKNOWN_VERSION = "unknown";

    /**
     * 紧凑构造器：校验并做防御性拷贝。
     *
     * @throws IllegalArgumentException 数据集名为空或用例列表为空
     */
    public JsonlDataset {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("数据集名称不能为空");
        }
        name = name.trim();
        version = version == null || version.isBlank() ? UNKNOWN_VERSION : version.trim();
        cases = cases == null ? List.of() : List.copyOf(cases);
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("数据集 " + name + " 不能没有用例");
        }
    }

    /**
     * 按 id 查找用例。
     *
     * @param caseId 用例标识
     * @return 命中的用例；未命中返回 null
     */
    public EvalCase caseById(String caseId) {
        for (EvalCase c : cases) {
            if (c.id().equals(caseId)) {
                return c;
            }
        }
        return null;
    }

    /**
     * 返回用例数量。
     *
     * @return 用例数
     */
    public int size() {
        return cases.size();
    }

    /**
     * 按场景分组统计用例数。
     *
     * @return 场景 → 用例数的映射
     */
    public Map<String, Integer> scenarioDistribution() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (EvalCase c : cases) {
            counts.merge(c.scenario(), 1, Integer::sum);
        }
        return Collections.unmodifiableMap(counts);
    }

    /**
     * 校验全部用例的 id 唯一。
     *
     * @return 全部唯一返回 {@code true}
     */
    public boolean hasUniqueIds() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (EvalCase c : cases) {
            if (!seen.add(c.id())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 返回数据集指纹：<b>内容</b>的确定性摘要。
     *
     * <p>用于评测报告中标注「这份结果对应哪个数据集的哪个版本」，防止拿着 A 数据集的报告去论证 B。
     *
     * <p><b>指纹必须覆盖内容而不只是 id</b>（测试捕获的真实缺陷）：早前版本只把 {@code id} 列表拼进去，于是改了一条用例的 {@code expected}
     * 之后指纹不变—— 两份内容不同却指纹相同的报告会互相混淆，而这正是回归门禁最不能出现的错误。
     *
     * @return {@code sha256:<64 位小写 hex>}
     */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder(name).append('@').append(version);
        for (EvalCase c : cases) {
            sb.append('\n')
                    .append(c.id())
                    .append('\t')
                    .append(c.input())
                    .append('\t')
                    .append(c.expected());
        }
        return io.github.nephets.glasshouse.core.audit.PayloadRedactor.getInstance()
                .digest(sb.toString());
    }
}
