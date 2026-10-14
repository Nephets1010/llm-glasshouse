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

package io.github.nephets.glasshouse.demo.mockllm;

import java.util.List;

/**
 * 场景引擎：内置一套确定性的中文客服场景。
 *
 * <p><b>刻意让不同场景的耗时差异明显</b>（80ms / 1500ms / 300ms）： 这样 README 首屏那张 span 树截图里，「慢在哪」是一眼可见的， 而不是靠文字解释。
 */
public final class ScenarioEngine {

    private final List<Scenario> scenarios;

    /** 用内置场景构造。 */
    public ScenarioEngine() {
        this(defaultScenarios());
    }

    /**
     * 用自定义场景构造。
     *
     * @param scenarios 场景列表，不可为空
     */
    public ScenarioEngine(List<Scenario> scenarios) {
        if (scenarios == null || scenarios.isEmpty()) {
            throw new IllegalArgumentException("场景列表不能为空");
        }
        this.scenarios = List.copyOf(scenarios);
    }

    /**
     * 返回内置场景列表。
     *
     * <p>覆盖 PRD 架构 §3.8 首屏示例里的三步 Agent： {@code planner}（规划，慢）→ {@code kb_lookup}（检索）→ {@code
     * writer}（成文）。
     *
     * @return 不可变场景列表
     */
    public static List<Scenario> defaultScenarios() {
        return List.of(
                new Scenario(
                        "planner",
                        List.of("规划", "拆解", "步骤", "怎么问"),
                        """
            我把这个问题拆成三步：
            1. 先确认你的订单号或商品信息；
            2. 再核对该商品当前的售后政策；
            3. 最后给出可执行的处理建议。
            请提供订单号，我继续处理。""",
                        186L,
                        142L,
                        1500L),
                new Scenario(
                        "kb-lookup",
                        List.of("政策", "规则", "规定", "多久", "几天"),
                        """
            根据《售后服务政策》：
            · 七天无理由退货：商品签收之日起 7 个自然日内可申请；
            · 质量问题：非人为损坏，7 天内包退，15 天内包换；
            · 退货运费：质量问题由商家承担，无理由退货由买家承担。
            以上为标准政策，特殊活动以页面标注为准。""",
                        542L,
                        268L,
                        80L),
                new Scenario(
                        "writer",
                        List.of("总结", "回复", "话术", "最终"),
                        """
            给您的最终答复：
            您的诉求属于「七天无理由退货」范围。请在订单详情页点击「申请退货」，
            选择退货原因并提交，我们会在 1 个工作日内审核通过，
            退款将原路返回至您的支付账户，通常 1-7 个工作日到账。
            如需帮助，请回复「人工客服」。""",
                        312L,
                        196L,
                        300L),
                new Scenario(
                        "default",
                        List.of(),
                        """
            您好，我是智能客服助手。为了更准确地帮到您，请补充以下信息：
            1. 您的订单号或商品名称；
            2. 具体遇到的问题。
            您也可以直接回复「人工客服」转接人工。""",
                        128L,
                        96L,
                        120L));
    }

    /**
     * 按用户输入选择场景。
     *
     * @param input 用户输入，可为 null
     * @return 命中的场景；无命中返回默认场景（保证永不返回 null）
     */
    public Scenario select(String input) {
        for (Scenario scenario : scenarios) {
            if (scenario.matches(input)) {
                return scenario;
            }
        }
        for (Scenario scenario : scenarios) {
            if ("default".equals(scenario.name())) {
                return scenario;
            }
        }
        return scenarios.get(scenarios.size() - 1);
    }

    /**
     * 返回全部场景。
     *
     * @return 不可变场景列表
     */
    public List<Scenario> all() {
        return scenarios;
    }
}
