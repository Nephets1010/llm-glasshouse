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

package io.github.nephets.glasshouse.core.attribution;

import java.math.BigDecimal;
import java.util.List;

import io.github.nephets.glasshouse.core.usage.Usage;

/**
 * 归因存储 SPI。
 *
 * <p><b>为什么留接口</b>：首版用内存实现（架构 Q-D 拍板，理由是 README 首屏的 span 树必须开箱即得， 不能要求用户先起一套存储后端），但企业客户上线后一定会问「能不能接
 * ClickHouse」。 接口留在这里，P2 换实现时 starter 与 CLI 一行都不用改。
 *
 * <p><b>实现约定</b>：
 *
 * <ul>
 *   <li>{@link #increment} 必须线程安全，且<b>永不抛异常</b>——观测失败不能影响业务
 *   <li>{@link #aggregate} 必须返回一个与查询条件一致的快照
 * </ul>
 */
public interface MetricStore {

    /**
     * 累加一次用量。
     *
     * @param key 归因键，不可为 null
     * @param usage 本次计量，不可为 null
     * @param cost 本次成本，不可为 null
     */
    void increment(AttributionKey key, Usage usage, BigDecimal cost);

    /**
     * 按查询条件聚合。
     *
     * @param query 查询条件，不可为 null
     * @return 结果行列表，按成本倒序
     */
    List<AttributionRow> aggregate(AttributionQuery query);

    /**
     * 汇总全部数据。
     *
     * @param query 查询条件，不可为 null
     * @return 归因报表
     */
    AttributionReport report(AttributionQuery query);

    /**
     * 清空全部聚合数据。
     *
     * <p>供 CLI {@code prices reload} 后的自检与测试使用。
     */
    void clear();

    /**
     * 返回当前聚合的键数量。
     *
     * @return 键数量
     */
    int size();
}
