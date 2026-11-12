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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RedactionPolicy 紧凑构造器的字段兜底与异常分支。
 *
 * <p>策略对象由配置直接构造，配置缺字段时必须回退到内置默认值，不能把 null 或非法值传到脱敏链路。 下面所有期望值都写字面量，不引用 {@code DEFAULT_*} / {@code
 * ALL_RULE_NAMES} 常量做镜像。
 */
class RedactionPolicyBoundaryTest {

    @Test
    @DisplayName("rules 为 null 时回退到全部六个内置规则名")
    void nullRulesFallBackToAllSixBuiltInRuleNames() {
        RedactionPolicy policy = new RedactionPolicy(true, null, 0, "***", 4000);

        // 删掉构造器 rules==null 兜底 → List.copyOf(null) 抛 NPE，此断言变红。
        assertThat(policy.rules())
                .containsExactly("mobile", "idCard", "bankCard", "email", "ipv4", "address");
    }

    @Test
    @DisplayName("replacement 为 null 时回退到三颗星")
    void nullReplacementFallsBackToThreeStars() {
        RedactionPolicy policy = new RedactionPolicy(true, List.of("mobile"), 0, null, 4000);

        // 删掉 replacement 兜底 → replacement() 为 null，此断言变红。
        assertThat(policy.replacement()).isEqualTo("***");
    }

    @Test
    @DisplayName("replacement 为空串时回退到三颗星")
    void emptyReplacementFallsBackToThreeStars() {
        RedactionPolicy policy = new RedactionPolicy(true, List.of("mobile"), 0, "", 4000);

        // 只判 null 不判空串（去掉 || isEmpty 分支）→ 仍是空串，此断言变红。
        assertThat(policy.replacement()).isEqualTo("***");
    }

    @Test
    @DisplayName("keepTail 为负抛 IllegalArgumentException 并点名字段")
    void negativeKeepTailThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new RedactionPolicy(true, List.of("mobile"), -1, "***", 4000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keepTail 不得为负");
    }

    @ParameterizedTest(name = "maxPayloadLength = {0} 回退到 4000")
    @ValueSource(ints = {0, -5})
    @DisplayName("maxPayloadLength 非正时回退到 4000")
    void nonPositiveMaxPayloadLengthFallsBackTo4000(int maxLength) {
        RedactionPolicy policy = new RedactionPolicy(true, List.of("mobile"), 0, "***", maxLength);

        // 删掉构造器 maxPayloadLength<=0 兜底 → 保留 0 / -5，此断言变红。
        assertThat(policy.maxPayloadLength()).isEqualTo(4000);
    }

    @Test
    @DisplayName("withMaxPayloadLength(0) 返回一个新策略，把自定义上限换成 4000")
    void withMaxPayloadLengthZeroReplacesCustomLimitWithDefault() {
        RedactionPolicy base = new RedactionPolicy(true, List.of("mobile"), 4, "MASK", 8000);

        // 特意让原策略的上限不是默认值：若该方法直接返回 this，结果会是 8000 而不是 4000。
        assertThat(base.withMaxPayloadLength(0).maxPayloadLength()).isEqualTo(4000);
    }

    @Test
    @DisplayName("withMaxPayloadLength 传正数时保留调用方指定的上限")
    void withMaxPayloadLengthPositiveKeepsRequestedValue() {
        RedactionPolicy base = new RedactionPolicy(true, List.of("mobile"), 4, "MASK", 8000);

        // 把三元表达式改成恒取默认值 → 4000，此断言变红。
        assertThat(base.withMaxPayloadLength(5000).maxPayloadLength()).isEqualTo(5000);
    }
}
