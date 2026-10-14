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
import java.util.Locale;

/**
 * Mock 场景：把「用户问什么」映射到「模型答什么」。
 *
 * <p><b>为什么要场景化而不是随机生成</b>：README 的 span 树截图与门禁报告都必须是 <b>可复现</b>的。随机生成会让每次跑出来的 token 数与成本都不一样，
 * 截图无法作为证据，评测也无法断言。
 *
 * @param name 场景标识
 * @param keywords 命中关键词（任一命中即选中本场景）
 * @param answer 模型回答模板
 * @param inputTokens 模拟的输入 token
 * @param outputTokens 模拟的输出 token
 * @param latencyMillis 模拟耗时（毫秒），用于演示 span 树上的耗时差异
 */
public record Scenario(
        String name,
        List<String> keywords,
        String answer,
        long inputTokens,
        long outputTokens,
        long latencyMillis) {

    /**
     * 构造场景。
     *
     * @param name 场景名
     * @param keywords 命中关键词
     * @param answer 回答
     * @param inputTokens 输入 token
     * @param outputTokens 输出 token
     * @param latencyMillis 耗时
     */
    public Scenario {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("场景名不能为空");
        }
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        if (answer == null || answer.isBlank()) {
            throw new IllegalArgumentException("场景 " + name + " 的回答不能为空");
        }
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("场景 " + name + " 的 token 数不能为负");
        }
        if (latencyMillis < 0) {
            throw new IllegalArgumentException("场景 " + name + " 的耗时时不能为负");
        }
    }

    /**
     * 判断输入是否命中本场景。
     *
     * @param input 用户输入，可为 null
     * @return 命中任一关键词返回 {@code true}
     */
    public boolean matches(String input) {
        if (input == null || input.isBlank() || keywords.isEmpty()) {
            return false;
        }
        String lower = input.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (keyword != null && lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 返回带演示标记的 usage JSON（OpenAI 兼容格式）。
     *
     * @return usage JSON
     */
    public String usageJson() {
        return """
        {"prompt_tokens":%d,"completion_tokens":%d,"total_tokens":%d,"prompt_tokens_details":{"cached_tokens":0}}"""
                .formatted(inputTokens, outputTokens, inputTokens + outputTokens);
    }
}
