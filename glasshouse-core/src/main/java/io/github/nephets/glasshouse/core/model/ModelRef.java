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

import java.util.Locale;
import java.util.Objects;

/**
 * 模型引用：{@code (providerId, modelId)} 二元组。
 *
 * <p>为什么不直接用字符串：供应商侧模型名（{@code deepseek-chat}）在不同供应商下可能重名， 而价格表与计量口径都必须按 {@code (provider, model)}
 * 定位。用 record 强制成对出现， 从类型上杜绝「拿到 model 忘了 provider」这类 bug。
 *
 * @param providerId 供应商标识，小写，见契约 {@code ProviderId}
 * @param modelId 模型标识，供应商侧原名
 */
public record ModelRef(String providerId, String modelId) {

    /**
     * 紧凑构造器：自动小写归一化 providerId 并校验。
     *
     * @throws IllegalArgumentException 任一字段为空
     */
    public ModelRef {
        Objects.requireNonNull(providerId, "providerId 不能为 null");
        Objects.requireNonNull(modelId, "modelId 不能为 null");
        providerId = providerId.trim().toLowerCase(Locale.ROOT);
        modelId = modelId.trim();
        if (providerId.isEmpty()) {
            throw new IllegalArgumentException("providerId 不能为空");
        }
        if (modelId.isEmpty()) {
            throw new IllegalArgumentException("modelId 不能为空");
        }
    }

    /**
     * 从 {@code provider/model} 形式解析。
     *
     * @param qualified 形如 {@code deepseek/deepseek-chat} 的字符串
     * @return 模型引用
     * @throws IllegalArgumentException 格式不含斜杠
     */
    public static ModelRef parse(String qualified) {
        Objects.requireNonNull(qualified, "qualified 不能为 null");
        int idx = qualified.indexOf('/');
        if (idx <= 0 || idx == qualified.length() - 1) {
            throw new IllegalArgumentException("模型引用格式应为 provider/model，实际为: " + qualified);
        }
        return new ModelRef(qualified.substring(0, idx), qualified.substring(idx + 1));
    }

    /**
     * 返回价格表查找键。
     *
     * @return {@code providerId/modelId}
     */
    public String lookupKey() {
        return providerId + "/" + modelId;
    }

    @Override
    public String toString() {
        return lookupKey();
    }
}
