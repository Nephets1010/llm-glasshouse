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

package io.github.nephets.glasshouse.cli;

/**
 * 真实端点 {@code ChatModel} 的工厂。
 *
 * <p>抽成独立类是为了让 {@link EvalCommand} 不必 import 具体实现类—— CLI 层只依赖「能造出一个
 * ChatModel」这个抽象，具体是回放还是真实端点由参数决定。
 */
final class RealEndpointChatModelFactory {

    private RealEndpointChatModelFactory() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 构造指向真实 OpenAI 兼容端点的模型。
     *
     * @param baseUrl 端点地址
     * @param modelRef 模型引用
     * @return ChatModel
     * @throws IllegalStateException 缺少 API Key
     */
    static org.springframework.ai.chat.model.ChatModel create(String baseUrl, String modelRef) {
        return RealEndpointChatModel.create(baseUrl, modelRef);
    }
}
