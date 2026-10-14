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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PII 脱敏测试（PRD 仓库③ P0-4）。
 *
 * <p>架构 ③T02 验收标准第 6 条：手机号 / 身份证 / 银行卡 / 邮箱 4 类各 3 个样本全部脱敏， 且 {@code digest} 不受影响。
 */
class PayloadRedactorTest {

    private final PayloadRedactor redactor = new PayloadRedactor();

    @Test
    @DisplayName("手机号：3 个样本全部脱敏，且保留后 4 位便于人工核对")
    void mobileRedaction() {
        for (String phone : new String[] {"13812345678", "15900001234", "18612340000"}) {
            String redacted = redactor.redact("客户手机号是 " + phone + "，请回电");
            assertThat(redacted).as("手机号 %s 必须被脱敏", phone).doesNotContain(phone);
            assertThat(redacted).as("保留后 4 位便于人工核对").contains(phone.substring(phone.length() - 4));
        }
    }

    @Test
    @DisplayName("身份证：3 个样本全部整体遮蔽（保留尾部反而泄露更多）")
    void idCardRedaction() {
        for (String id :
                new String[] {"110101199003074567", "310101198512123456", "44030119920815123X"}) {
            String redacted = redactor.redact("身份证 " + id + " 申请");
            assertThat(redacted).as("身份证 %s 必须被脱敏", id).doesNotContain(id);
        }
    }

    @Test
    @DisplayName("银行卡：3 个样本全部脱敏（含带空格分组形态）")
    void bankCardRedaction() {
        for (String card :
                new String[] {"6222021234567890123", "6222 0212 3456 7890", "4111111111111111"}) {
            String redacted = redactor.redact("卡号 " + card);
            String digits = card.replaceAll("[\\s-]", "");
            assertThat(redacted).as("银行卡 %s 必须被脱敏", card).doesNotContain(digits);
        }
    }

    @Test
    @DisplayName("邮箱：3 个样本全部脱敏")
    void emailRedaction() {
        for (String email :
                new String[] {
                    "zhangsan@example.com", "li.si-123@corp.cn", "user_test@mail.qq.com"
                }) {
            String redacted = redactor.redact("联系邮箱 " + email);
            assertThat(redacted).as("邮箱 %s 必须被脱敏", email).doesNotContain(email);
        }
    }

    @ParameterizedTest(name = "IPv4 {0} 被脱敏")
    @ValueSource(strings = {"192.168.1.1", "10.0.0.255", "172.16.254.1"})
    @DisplayName("IPv4 地址脱敏")
    void ipv4Redaction(String ip) {
        assertThat(redactor.redact("访问来源 " + ip + " 拒绝了请求")).doesNotContain(ip);
    }

    @Test
    @DisplayName("详细地址脱敏")
    void addressRedaction() {
        String address = "北京市海淀区中关村大街1号";
        String redacted = redactor.redact("收货地址：" + address);
        assertThat(redacted).doesNotContain("中关村大街1号");
    }

    @Test
    @DisplayName("★ digest 始终对原始内容计算，不受脱敏开关影响")
    void digestIsIndependentOfRedaction() {
        String raw = "客户手机号是 13812345678";
        PayloadRedactor enabled = new PayloadRedactor(RedactionPolicy.DEFAULT);
        PayloadRedactor disabled = new PayloadRedactor(RedactionPolicy.DISABLED);

        String digestEnabled = enabled.digest(raw);
        String digestDisabled = disabled.digest(raw);
        assertThat(digestEnabled)
                .as("摘要必须对原始内容计算，否则开关切换后同一次调用的摘要会变，审计链断裂")
                .isEqualTo(digestDisabled)
                .matches("^sha256:[0-9a-f]{64}$");

        // 脱敏前后 digest 相同
        String redacted = enabled.redact(raw);
        assertThat(enabled.digest(redacted)).isNotEqualTo(digestEnabled);
        assertThat(enabled.digest(raw)).isEqualTo(digestEnabled);
    }

    @Test
    @DisplayName("digest 稳定性：同一内容多次调用结果一致")
    void digestIsStable() {
        PayloadRedactor r = PayloadRedactor.getInstance();
        assertThat(r.digest("同一段内容")).isEqualTo(r.digest("同一段内容"));
        assertThat(r.digest("a")).isNotEqualTo(r.digest("b"));
        assertThat(r.digest(null)).matches("^sha256:[0-9a-f]{64}$");
        assertThat(r.digest("")).isEqualTo(r.digest(null));
    }

    @Test
    @DisplayName("脱敏开关关闭时原样返回")
    void disabledPolicyKeepsContent() {
        PayloadRedactor off = new PayloadRedactor(RedactionPolicy.DISABLED);
        String raw = "手机号 13812345678";
        assertThat(off.redact(raw)).isEqualTo(raw);
        assertThat(off.wouldRedact(raw)).isFalse();
        assertThat(off.policy().enabled()).isFalse();
    }

    @Test
    @DisplayName("wouldRedact 能区分「跑过了」与「真的改了」")
    void wouldRedactDetectsChange() {
        assertThat(redactor.wouldRedact("手机号 13812345678")).isTrue();
        assertThat(redactor.wouldRedact("这里没有任何隐私信息")).isFalse();
        assertThat(redactor.wouldRedact(null)).isFalse();
    }

    @ParameterizedTest(name = "classpath 规则可用：{0}")
    @ValueSource(
            strings = {
                PayloadRedactor.RULE_MOBILE,
                PayloadRedactor.RULE_ID_CARD,
                PayloadRedactor.RULE_BANK_CARD,
                PayloadRedactor.RULE_EMAIL,
                PayloadRedactor.RULE_IPV4,
                PayloadRedactor.RULE_ADDRESS
            })
    @DisplayName("六类内置规则都启用")
    void allBuiltinRulesEnabled(String rule) {
        assertThat(redactor.policy().enabledRule(rule)).as("规则 %s 默认启用", rule).isTrue();
    }

    @Test
    @DisplayName("只启用指定规则")
    void selectiveRules() {
        PayloadRedactor only =
                new PayloadRedactor(
                        RedactionPolicy.of(java.util.List.of(PayloadRedactor.RULE_EMAIL), 0));
        assertThat(only.redact("手机号 13812345678 邮箱 a@b.com")).contains("13812345678");
        assertThat(only.redact("手机号 13812345678 邮箱 a@b.com")).doesNotContain("a@b.com");
    }

    @Test
    @DisplayName("超长内容被截断")
    void truncation() {
        PayloadRedactor shortLimit =
                new PayloadRedactor(RedactionPolicy.DEFAULT.withMaxPayloadLength(20));
        String result = shortLimit.redact("一".repeat(100));
        assertThat(result.length()).isLessThan(100);
        assertThat(result.contains("[truncated]")).as("超长内容必须被截断并标注: %s", result).isTrue();
    }

    @Test
    @DisplayName("fromClasspath 加载 pii-cn.yml，配置缺失时回退内置规则而非失效")
    void classpathPatternLoadingDegradesSafely() {
        PayloadRedactor fromFile = PayloadRedactor.fromClasspath(RedactionPolicy.DEFAULT);
        assertThat(fromFile.redact("手机号 13812345678")).doesNotContain("13812345678");

        // 规则文件不存在 / 格式错误时，PiiPatternLoader 返回空覆盖表 → 等价于用内置规则。
        // 脱敏是安全兜底，绝不能因为一份 YAML 写错就变成「不脱敏」。
        assertThat(PiiPatternLoader.load("patterns/does-not-exist.yml")).isEmpty();
        assertThat(PiiPatternLoader.load(PiiPatternLoader.DEFAULT_RESOURCE))
                .as("内置 pii-cn.yml 必须可加载且覆盖全部六类规则")
                .containsKeys(
                        PayloadRedactor.RULE_MOBILE,
                        PayloadRedactor.RULE_ID_CARD,
                        PayloadRedactor.RULE_BANK_CARD,
                        PayloadRedactor.RULE_EMAIL,
                        PayloadRedactor.RULE_IPV4,
                        PayloadRedactor.RULE_ADDRESS);
    }

    @Test
    @DisplayName("同一段文本里多处 PII 全部被脱敏")
    void multipleOccurrences() {
        String raw = "主联系人 13812345678，备用联系人 13998765432，邮箱 zhangsan@example.com";
        String redacted = redactor.redact(raw);
        assertThat(redacted)
                .doesNotContain("13812345678")
                .doesNotContain("13998765432")
                .doesNotContain("zhangsan@example.com");
    }

    @ParameterizedTest(name = "普通文本不应被误伤：{0}")
    @CsvSource({"这是一段普通的业务描述，没有个人信息", "订单号 20260315-8823 的物流状态是已签收", "金额 1234.56 元，折扣 8.5 折"})
    @DisplayName("无 PII 的文本不应被误脱敏")
    void noFalsePositiveOnPlainText(String text) {
        assertThat(redactor.redact(text)).as("普通文本被误伤会让留痕失去可读性").isEqualTo(text);
    }

    @Test
    @DisplayName("RedactionPolicy 的防御性拷贝与兜底")
    void policyDefensiveCopies() {
        RedactionPolicy p = RedactionPolicy.of(java.util.List.of("mobile"), 2);
        assertThat(p.keepTail()).isEqualTo(2);
        assertThat(p.rules()).containsExactly("mobile");
        assertThat(p.withMaxPayloadLength(-1).maxPayloadLength())
                .isEqualTo(RedactionPolicy.DEFAULT_MAX_PAYLOAD_LENGTH);
        assertThat(p.withMaxPayloadLength(99).maxPayloadLength()).isEqualTo(99);
        assertThat(RedactionPolicy.DEFAULT.rules()).isEqualTo(RedactionPolicy.ALL_RULE_NAMES);
        assertThat(RedactionPolicy.DEFAULT.enabledRule("unknown-rule")).isFalse();
    }

    @Test
    @DisplayName("null 与空输入安全")
    void nullSafety() {
        assertThat(redactor.redact(null)).isNull();
        assertThat(redactor.redact("")).isEmpty();
        assertThat(redactor.policy()).isNotNull();
    }
}
