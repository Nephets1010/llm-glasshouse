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

package io.github.nephets.glasshouse.core.usage;

/**
 * 基于字符启发式的 Token 估算器（中文场景）。
 *
 * <p><b>为什么是 1.6 而不是常见的 1.4</b>：中文模型（通义/智谱/DeepSeek/Kimi/豆包）的分词器 对汉字通常按 1 字 ≈ 0.6~1 token 处理，对
 * ASCII 英文则接近 1 token / 4 字符。 取 {@code CJK 字符 ÷ 1.6 + ASCII 字符 ÷ 4} 是在实测样本上误差最小的折中。
 *
 * <p><b>它不是精确计量</b>。所有由它产出的 {@link Usage} 都会被标记为 {@code usageSource=ESTIMATED}，
 * 这样成本报表能区分「真账」与「估账」——这正是契约 USG-001 把 {@code usageSource} 设计成必填字段的原因。
 */
public final class CharHeuristicTokenEstimator implements TokenEstimator {

    /** 中文/全角字符的除数：1 个汉字约等于 0.6 token。 */
    public static final double CJK_CHARS_PER_TOKEN = 1.6;

    /** ASCII 字符的除数：约 4 个 ASCII 字符 1 token。 */
    public static final double ASCII_CHARS_PER_TOKEN = 4.0;

    private static final TokenEstimator INSTANCE = new CharHeuristicTokenEstimator();

    /**
     * 返回共享实例。本类无状态，构造新的没有收益。
     *
     * @return 共享实例
     */
    public static TokenEstimator getInstance() {
        return INSTANCE;
    }

    @Override
    public long estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0L;
        }
        long cjkChars = 0L;
        long asciiChars = 0L;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (isCjk(codePoint)) {
                cjkChars++;
            } else {
                asciiChars++;
            }
        }
        double estimate = cjkChars / CJK_CHARS_PER_TOKEN + asciiChars / ASCII_CHARS_PER_TOKEN;
        return Math.max(0L, Math.round(estimate));
    }

    /**
     * 判断码点是否属于 CJK 统一表意文字区（含扩展 A/B 与兼容区），这是中文占比统计的核心。
     *
     * <p>用码点区间而不是 {@link Character.UnicodeBlock} 枚举：Unicode 版本推进时枚举常量会增删， 而区间是稳定的语义描述。
     *
     * @param codePoint Unicode 码点
     * @return 属于 CJK 表意文字返回 {@code true}
     */
    private static boolean isCjk(int codePoint) {
        // CJK 统一表意文字（含扩展 A）
        if (codePoint >= 0x4E00 && codePoint <= 0x9FFF) {
            return true;
        }
        // CJK 兼容表意文字
        if (codePoint >= 0xF900 && codePoint <= 0xFAFF) {
            return true;
        }
        // CJK 扩展 A
        if (codePoint >= 0x3400 && codePoint <= 0x4DBF) {
            return true;
        }
        // CJK 扩展 B ~ F（兼容汉字、生僻字）
        if (codePoint >= 0x20000 && codePoint <= 0x2FA1F) {
            return true;
        }
        // CJK 扩展 G 与补充区
        if (codePoint >= 0x30000 && codePoint <= 0x323AF) {
            return true;
        }
        // 中日韩符号与标点、全角字符：按 1 汉字处理，避免中文标点被算成 ASCII 而低估 token
        if (codePoint >= 0x3000 && codePoint <= 0x303F) {
            return true;
        }
        if (codePoint >= 0xFF00 && codePoint <= 0xFFEF) {
            return true;
        }
        return false;
    }

    @Override
    public String name() {
        return "CharHeuristicTokenEstimator";
    }
}
