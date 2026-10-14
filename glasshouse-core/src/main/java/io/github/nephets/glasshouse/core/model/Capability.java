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

/**
 * 模型能力标记。
 *
 * <p>用途：README 的「国产模型支持矩阵」与 CLI {@code prices show} 需要逐项标注每个模型支持什么； 同时评测时按能力选择指标（例如不支持结构化输出的模型不跑
 * {@code JsonSchemaMetric}）。
 *
 * <p>注意这里只描述「本项目已验证」的能力，未列出的能力不代表模型不支持，只代表首版未核验。
 */
public enum Capability {

    /** 流式响应。 */
    STREAM(true),

    /** 工具 / 函数调用。 */
    TOOL_CALL(true),

    /** 结构化输出（JSON Schema 约束）。 */
    STRUCTURED_OUTPUT(true),

    /** 显式缓存命中计费。 */
    PROMPT_CACHE(true),

    /** 暴露独立推理 token 计量。 */
    REASONING_TOKENS(true),

    /** 视觉 / 多模态输入。 */
    VISION(false),

    /** 本地部署（Ollama）。 */
    LOCAL(false);

    private final boolean verified;

    Capability(boolean verified) {
        this.verified = verified;
    }

    /**
     * 判断该能力是否已在首版核验。
     *
     * <p>README 的支持矩阵必须如实区分「已验证」与「未核验」，不能把「模型理论支持」写成「我们验证过」。
     *
     * @return 已核验返回 {@code true}
     */
    public boolean verified() {
        return verified;
    }
}
