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

package io.github.nephets.glasshouse.core.audit;

import java.util.List;

/**
 * 脱敏策略（PRD 仓库③ P0-4）。
 *
 * @param enabled 是否启用脱敏
 * @param rules 启用的规则名列表，空列表表示用全部内置规则
 * @param keepTail 保留尾部字符数（手机号保留后 4 位等），0 表示不保留
 * @param replacement 替换文本
 * @param maxPayloadLength payload 留痕的最大字符数，超出截断
 */
public record RedactionPolicy(
        boolean enabled,
        List<String> rules,
        int keepTail,
        String replacement,
        int maxPayloadLength) {

    /** 脱敏后替换文本。 */
    public static final String DEFAULT_REPLACEMENT = "***";

    /** 默认保留尾部字符数。 */
    public static final int DEFAULT_KEEP_TAIL = 4;

    /** 默认 payload 上限。 */
    public static final int DEFAULT_MAX_PAYLOAD_LENGTH = 4_000;

    /** 全部内置规则名。 */
    public static final List<String> ALL_RULE_NAMES =
            List.of("mobile", "idCard", "bankCard", "email", "ipv4", "address");

    /** 默认策略：启用、全部规则、保留后 4 位。 */
    public static final RedactionPolicy DEFAULT =
            new RedactionPolicy(
                    true,
                    ALL_RULE_NAMES,
                    DEFAULT_KEEP_TAIL,
                    DEFAULT_REPLACEMENT,
                    DEFAULT_MAX_PAYLOAD_LENGTH);

    /** 关闭脱敏的策略（仅用于本地调试，生产禁止）。 */
    public static final RedactionPolicy DISABLED =
            new RedactionPolicy(
                    false, List.of(), 0, DEFAULT_REPLACEMENT, DEFAULT_MAX_PAYLOAD_LENGTH);

    /**
     * 紧凑构造器：字段兜底。
     *
     * @throws IllegalArgumentException keepTail 或 maxPayloadLength 为负
     */
    public RedactionPolicy {
        if (rules == null) {
            rules = ALL_RULE_NAMES;
        }
        rules = List.copyOf(rules);
        if (replacement == null || replacement.isEmpty()) {
            replacement = DEFAULT_REPLACEMENT;
        }
        if (keepTail < 0) {
            throw new IllegalArgumentException("keepTail 不得为负");
        }
        if (maxPayloadLength <= 0) {
            maxPayloadLength = DEFAULT_MAX_PAYLOAD_LENGTH;
        }
    }

    /**
     * 构造一个只启用指定规则、保留尾部的策略。
     *
     * @param ruleNames 规则名列表
     * @param keepTail 保留尾部字符数
     * @return 策略实例
     */
    public static RedactionPolicy of(List<String> ruleNames, int keepTail) {
        return new RedactionPolicy(
                true, ruleNames, keepTail, DEFAULT_REPLACEMENT, DEFAULT_MAX_PAYLOAD_LENGTH);
    }

    /**
     * 返回一个只替换了 payload 上限的副本。
     *
     * @param maxLength 新的 payload 上限；非正时回退默认值
     * @return 新策略
     */
    public RedactionPolicy withMaxPayloadLength(int maxLength) {
        return new RedactionPolicy(
                enabled,
                rules,
                keepTail,
                replacement,
                maxLength <= 0 ? DEFAULT_MAX_PAYLOAD_LENGTH : maxLength);
    }

    /**
     * 判断某条规则是否启用。
     *
     * @param ruleName 规则名
     * @return 启用返回 {@code true}
     */
    public boolean enabledRule(String ruleName) {
        return enabled && (rules.isEmpty() || rules.contains(ruleName));
    }
}
