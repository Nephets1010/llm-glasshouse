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

package io.github.nephets.glasshouse.core.model;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ModelRef 的归一化与解析边界测试。
 *
 * <p>价格表按 (provider, model) 定位记录。若大小写与首尾空白不在构造阶段收敛，同一个模型就会查到两条不同的键， 归因与计费都会分叉。这里逐条固定紧凑构造器与 parse()
 * 的边界行为。
 */
class ModelRefTest {

    @Test
    @DisplayName("紧凑构造器把 providerId 小写归一化，两个字段都去掉首尾空白")
    void compactConstructorNormalizesProviderAndTrimsBothFields() {
        ModelRef ref = new ModelRef("  DeepSeek  ", "  DeepSeek-Chat  ");

        // 去掉 providerId 的 toLowerCase 或 trim，第一行变红；去掉 modelId 的 trim，第二行变红。
        assertThat(ref.providerId()).isEqualTo("deepseek");
        // modelId 是供应商侧原名，只 trim，不改大小写。
        assertThat(ref.modelId()).isEqualTo("DeepSeek-Chat");
    }

    @Test
    @DisplayName("小写归一化用 Locale.ROOT，不受土耳其语默认区域影响")
    void providerLowercasingIsLocaleRootIndependent() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            // 默认区域下 "OPENAI" 的 I 会变成无点 ı；只有 Locale.ROOT 才稳定得到 openai。
            assertThat(new ModelRef("OPENAI", "gpt-4o").providerId()).isEqualTo("openai");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    @DisplayName("providerId 为 null 抛 NPE，且异常信息点名字段")
    void nullProviderThrowsNullPointerException() {
        assertThatThrownBy(() -> new ModelRef(null, "deepseek-chat"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("providerId 不能为 null");
    }

    @Test
    @DisplayName("modelId 为 null 抛 NPE，且异常信息点名字段")
    void nullModelThrowsNullPointerException() {
        assertThatThrownBy(() -> new ModelRef("deepseek", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("modelId 不能为 null");
    }

    @ParameterizedTest(name = "providerId = \"{0}\" 应被拒绝")
    @ValueSource(strings = {"", " ", "\t "})
    @DisplayName("空串与各类空白 providerId 一律抛 IllegalArgumentException")
    void blankProviderRejected(String blank) {
        assertThatThrownBy(() -> new ModelRef(blank, "deepseek-chat"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providerId 不能为空");
    }

    @ParameterizedTest(name = "modelId = \"{0}\" 应被拒绝")
    @ValueSource(strings = {"", " ", "\t "})
    @DisplayName("空串与各类空白 modelId 一律抛 IllegalArgumentException")
    void blankModelRejected(String blank) {
        assertThatThrownBy(() -> new ModelRef("deepseek", blank))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelId 不能为空");
    }

    @Test
    @DisplayName("parse 对合法引用归一化两侧，lookupKey 回到紧凑形式")
    void parseNormalizesBothSides() {
        ModelRef ref = ModelRef.parse("  DeepSeek  /  DeepSeek-Chat  ");

        assertThat(ref.providerId()).isEqualTo("deepseek");
        assertThat(ref.modelId()).isEqualTo("DeepSeek-Chat");
        assertThat(ref.lookupKey()).isEqualTo("deepseek/DeepSeek-Chat");
    }

    @Test
    @DisplayName("parse 只按第一个斜杠切分，多余斜杠留在 modelId 里")
    void parseKeepsExtraSlashInModelId() {
        // 模型名本身可能带斜杠（如 meta/llama-3），所以实现只认第一个斜杠。
        // 若改成 lastIndexOf 或加严格格式校验，下面三行都会变红。
        ModelRef ref = ModelRef.parse("DeepSeek/DeepSeek-Chat/V1");

        assertThat(ref.providerId()).isEqualTo("deepseek");
        assertThat(ref.modelId()).isEqualTo("DeepSeek-Chat/V1");
        assertThat(ref.lookupKey()).isEqualTo("deepseek/DeepSeek-Chat/V1");
    }

    @Test
    @DisplayName("parse 不含斜杠时抛 IllegalArgumentException")
    void parseRejectsMissingSlash() {
        assertThatThrownBy(() -> ModelRef.parse("deepseek-chat"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider/model");
    }

    @Test
    @DisplayName("parse 斜杠在首位时抛 IllegalArgumentException")
    void parseRejectsLeadingSlash() {
        assertThatThrownBy(() -> ModelRef.parse("/deepseek-chat"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider/model");
    }

    @Test
    @DisplayName("parse 斜杠在末位时抛 IllegalArgumentException")
    void parseRejectsTrailingSlash() {
        assertThatThrownBy(() -> ModelRef.parse("deepseek/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider/model");
    }

    @Test
    @DisplayName("parse 入参为 null 抛 NPE")
    void parseRejectsNull() {
        assertThatThrownBy(() -> ModelRef.parse(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("qualified 不能为 null");
    }

    @Test
    @DisplayName("parse 空白 provider 段落到紧凑构造器的空值校验")
    void parseRejectsBlankProviderSegment() {
        assertThatThrownBy(() -> ModelRef.parse("  /deepseek-chat"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providerId 不能为空");
    }

    @Test
    @DisplayName("parse 空白 model 段落到紧凑构造器的空值校验")
    void parseRejectsBlankModelSegment() {
        assertThatThrownBy(() -> ModelRef.parse("deepseek/   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelId 不能为空");
    }

    @Test
    @DisplayName("lookupKey 与 toString 都返回 provider/model")
    void lookupKeyAndToStringAgreeOnLiteralShape() {
        ModelRef ref = new ModelRef("DeepSeek", " DeepSeek-Chat ");

        // 改 lookupKey 的分隔符或删掉 toString 覆写，下面两行都会变红。
        assertThat(ref.lookupKey()).isEqualTo("deepseek/DeepSeek-Chat");
        assertThat(ref.toString()).isEqualTo("deepseek/DeepSeek-Chat");
    }

    @Test
    @DisplayName("归一化后不同输入相等，provider 或 model 不同则不相等")
    void recordEqualityUsesNormalizedFields() {
        ModelRef upper = new ModelRef("DEEPSEEK", " deepseek-chat ");
        ModelRef lower = new ModelRef("deepseek", "deepseek-chat");

        // 去掉小写归一化会让这条相等断言变红。
        assertThat(upper).isEqualTo(lower);
        assertThat(upper.hashCode()).isEqualTo(lower.hashCode());
        assertThat(new ModelRef("anthropic", "deepseek-chat")).isNotEqualTo(lower);
        assertThat(new ModelRef("deepseek", "other-model")).isNotEqualTo(lower);
    }

    @Test
    @DisplayName("归一化后的 ModelRef 可以直接当价格表 HashMap 的键")
    void normalizedRefWorksAsPriceTableKey() {
        Map<ModelRef, String> prices = new HashMap<>();
        prices.put(new ModelRef("  DeepSeek ", " deepseek-chat "), "0.0020");

        // 查表用的是另一种大小写与空白写法；去掉小写归一化会查不到，返回 null。
        assertThat(prices.get(new ModelRef("DEEPSEEK", "deepseek-chat"))).isEqualTo("0.0020");
    }
}
