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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PII 脱敏器（PRD 仓库③ P0-4 ★ 中国大陆场景）。
 *
 * <p><b>为什么必须做，而且必须默认开</b>：Prompt 与输出留痕是成本归因的前提（要按 {@code promptVersion}
 * 维度归因，就必须知道当时用了哪个版本、渲染成了什么），但 Prompt 里天然会混入用户手机号、身份证、 银行卡。默认不落全量原文是安全的默认值（架构 §2.4「payloadFull
 * 仅当显式开启留痕且通过脱敏后填充」）。
 *
 * <p><b>摘要与脱敏的关系</b>：{@link #digest} 总是对<b>原始</b>内容计算，不受脱敏开关影响。 这一点很关键——如果对脱敏后的内容算摘要，那么同一个 Prompt
 * 在开关切换后摘要会变， 「按 digest 关联两次调用」就失效了，审计链会断。
 *
 * <p><b>正则规则来自 {@code patterns/pii-cn.yml}</b>，用户可覆盖扩展而不改代码。
 */
public final class PayloadRedactor {

    /** 内置规则名：大陆手机号。 */
    public static final String RULE_MOBILE = "mobile";

    /** 内置规则名：18 位身份证。 */
    public static final String RULE_ID_CARD = "idCard";

    /** 内置规则名：16~19 位银行卡号。 */
    public static final String RULE_BANK_CARD = "bankCard";

    /** 内置规则名：邮箱地址。 */
    public static final String RULE_EMAIL = "email";

    /** 内置规则名：IPv4 地址。 */
    public static final String RULE_IPV4 = "ipv4";

    /** 内置规则名：详细地址。 */
    public static final String RULE_ADDRESS = "address";

    private static final Map<String, String> BUILTIN_PATTERNS = builtinPatterns();

    private final RedactionPolicy policy;
    private final List<Rule> compiled;

    private static final PayloadRedactor INSTANCE = new PayloadRedactor(RedactionPolicy.DEFAULT);

    /** 用默认策略构造。 */
    public PayloadRedactor() {
        this(RedactionPolicy.DEFAULT);
    }

    /**
     * 用给定策略构造并预编译正则。
     *
     * @param policy 脱敏策略；为 null 时使用默认策略
     */
    public PayloadRedactor(RedactionPolicy policy) {
        this.policy = policy == null ? RedactionPolicy.DEFAULT : policy;
        this.compiled = compile(this.policy);
    }

    /**
     * 从 classpath 的 {@code patterns/pii-cn.yml} 加载自定义规则并构造脱敏器。
     *
     * <p>加载失败时回退到内置规则，保证脱敏能力不因配置问题而失效——脱敏是安全兜底， 不能因为一个 YAML 写错就变成「不脱敏」。
     *
     * @param policy 脱敏策略，不可为 null
     * @return 脱敏器实例
     */
    public static PayloadRedactor fromClasspath(RedactionPolicy policy) {
        Map<String, String> overrides = PiiPatternLoader.loadOrEmpty();
        return new PayloadRedactor(policy, overrides);
    }

    private PayloadRedactor(RedactionPolicy policy, Map<String, String> patternOverrides) {
        this.policy = policy == null ? RedactionPolicy.DEFAULT : policy;
        this.compiled = compile(this.policy, patternOverrides);
    }

    /**
     * 对内容做 PII 脱敏。
     *
     * @param raw 原始内容，可为 null
     * @return 脱敏并截断后的内容；入参为 null 时返回 null
     */
    public String redact(String raw) {
        if (raw == null || raw.isEmpty() || !policy.enabled()) {
            return truncate(raw);
        }
        String result = raw;
        for (Rule rule : compiled) {
            if (!policy.enabledRule(rule.name())) {
                continue;
            }
            result = applyRule(result, rule);
        }
        return truncate(result);
    }

    /**
     * 返回共享实例。本类无状态（编译后的正则不可变），构造新的没有收益。
     *
     * @return 共享实例
     */
    public static PayloadRedactor getInstance() {
        return INSTANCE;
    }

    /**
     * 计算内容摘要，形如 {@code sha256:<64 位小写 hex>}。
     *
     * <p><b>始终对原始内容计算</b>，与脱敏开关无关。
     *
     * @param raw 原始内容，可为 null
     * @return 摘要字符串；入参为 null 时返回 {@code sha256:} + 空摘要的合法形态
     */
    public String digest(String raw) {
        String content = raw == null ? "" : raw;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(AuditEvent.DIGEST_PREFIX);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 必须支持 SHA-256", e);
        }
    }

    /**
     * 返回当前策略。
     *
     * @return 脱敏策略
     */
    public RedactionPolicy policy() {
        return policy;
    }

    /**
     * 判断内容在当前策略下是否会被改动。
     *
     * <p>用于测试断言「脱敏真的生效」而不是「脱敏跑过了但什么都没改」。
     *
     * @param raw 原始内容，可为 null
     * @return 内容会被改动返回 {@code true}
     */
    public boolean wouldRedact(String raw) {
        return raw != null && !redact(raw).equals(raw);
    }

    /**
     * 截断超长内容。
     *
     * @param raw 原始内容，可为 null
     * @return 截断后的内容
     */
    private String truncate(String raw) {
        if (raw == null) {
            return null;
        }
        int max = policy.maxPayloadLength();
        if (raw.length() <= max) {
            return raw;
        }
        return raw.substring(0, max) + "...[truncated]";
    }

    /**
     * 应用单条规则。
     *
     * @param input 输入文本
     * @param rule 规则
     * @return 脱敏后的文本
     */
    private String applyRule(String input, Rule rule) {
        Matcher matcher = rule.pattern().matcher(input);
        if (!matcher.find()) {
            return input;
        }
        matcher.reset();
        StringBuilder sb = new StringBuilder(input.length());
        int last = 0;
        while (matcher.find()) {
            sb.append(input, last, matcher.start());
            sb.append(mask(matcher.group(), rule));
            last = matcher.end();
        }
        sb.append(input, last, input.length());
        return sb.toString();
    }

    /**
     * 生成脱敏后的替换文本。
     *
     * @param matched 命中的原文
     * @param rule 规则
     * @return 替换文本
     */
    private String mask(String matched, Rule rule) {
        int keep = rule.keepTail() ? policy.keepTail() : 0;
        if (keep <= 0 || matched.length() <= keep) {
            return rule.replacement();
        }
        String tail = matched.substring(matched.length() - keep);
        return rule.prefix() + rule.replacement() + tail;
    }

    /**
     * 预编译规则。
     *
     * @param policy 策略
     * @return 编译后的规则列表
     */
    private static List<Rule> compile(RedactionPolicy policy) {
        return compile(policy, Map.of());
    }

    /**
     * 预编译规则，支持自定义正则覆盖内置值。
     *
     * @param policy 策略
     * @param overrides 规则名 → 正则表达式的覆盖表
     * @return 编译后的规则列表
     */
    private static List<Rule> compile(RedactionPolicy policy, Map<String, String> overrides) {
        List<Rule> rules = new ArrayList<>(BUILTIN_PATTERNS.size());
        for (Map.Entry<String, String> entry : BUILTIN_PATTERNS.entrySet()) {
            String name = entry.getKey();
            String regex = overrides.getOrDefault(name, entry.getValue());
            String replacement = policy.replacement();
            try {
                rules.add(
                        new Rule(
                                name,
                                Pattern.compile(regex),
                                prefixOf(name),
                                replacement,
                                keepTailOf(name)));
            } catch (RuntimeException e) {
                // 自定义正则非法时回退内置规则，保证脱敏不失效
                rules.add(
                        new Rule(
                                name,
                                Pattern.compile(entry.getValue()),
                                prefixOf(name),
                                replacement,
                                keepTailOf(name)));
            }
        }
        return List.copyOf(rules);
    }

    /**
     * 返回内置正则表。
     *
     * @return 规则名 → 正则表达式
     */
    public static Map<String, String> builtinPatterns() {
        Map<String, String> map = new LinkedHashMap<>();
        // 大陆手机号：1[3-9] 开头的 11 位号码
        map.put(RULE_MOBILE, "(?<![0-9])(?:\\+?86[- ]?)?1[3-9][0-9]{9}(?![0-9])");
        // 18 位身份证（含 X 结尾）
        map.put(
                RULE_ID_CARD,
                "(?<![0-9Xx])[1-9][0-9]{5}(?:19|20)[0-9]{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01])[0-9]{3}[0-9Xx](?![0-9Xx])");
        // 16~19 位银行卡（允许空格与短横线分隔）
        map.put(
                RULE_BANK_CARD,
                "(?<![0-9])(?:[0-9]{4}[- ]){3,4}[0-9]{4}|(?<![0-9])[0-9]{16,19}(?![0-9])");
        map.put(RULE_EMAIL, "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
        map.put(
                RULE_IPV4,
                "(?<![0-9])(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])"
                        + "(?:\\.(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}(?![0-9])");
        // 详细地址：省市区 + 详细门牌
        map.put(
                RULE_ADDRESS,
                "(?:北京|上海|天津|重庆|河北|山西|辽宁|吉林|黑龙江|江苏|浙江|安徽|福建|江西|山东|河南|湖北|湖南|"
                        + "广东|广西|海南|四川|贵州|云南|陕西|甘肃|青海|台湾|内蒙古|广西|西藏|宁夏|新疆)"
                        + "(?:省|市|自治区)?[^，。；\\s]{0,20}?(?:路|街|道|巷|弄|村|镇)\\s*[^，。；\\s]{0,30}号");
        return Map.copyOf(map);
    }

    /**
     * 返回规则的替换前缀。
     *
     * @param ruleName 规则名
     * @return 前缀文本
     */
    private static String prefixOf(String ruleName) {
        return switch (ruleName) {
            case RULE_MOBILE -> "1***";
            case RULE_ID_CARD -> "";
            case RULE_BANK_CARD -> "";
            case RULE_EMAIL -> "";
            default -> "";
        };
    }

    /**
     * 判断该规则是否保留尾部。
     *
     * @param ruleName 规则名
     * @return 保留尾部返回 {@code true}
     */
    private static boolean keepTailOf(String ruleName) {
        // 手机号保留后 4 位便于人工核对；身份证与银行卡保留尾部反而泄露更多，一律整体遮蔽
        return RULE_MOBILE.equals(ruleName);
    }

    /**
     * 一条编译后的脱敏规则。
     *
     * @param name 规则名
     * @param pattern 编译后的正则
     * @param prefix 替换前缀（手机号规则用 {@code 1***} 保留首位，便于人工核对区号）
     * @param replacement 替换占位文本
     * @param keepTail 是否保留尾部
     */
    private record Rule(
            String name, Pattern pattern, String prefix, String replacement, boolean keepTail) {}
}
