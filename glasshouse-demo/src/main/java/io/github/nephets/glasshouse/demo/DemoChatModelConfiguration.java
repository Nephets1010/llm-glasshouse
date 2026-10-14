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

package io.github.nephets.glasshouse.demo;

import java.util.Map;

import io.github.nephets.glasshouse.demo.mockllm.ScenarioEngine;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Demo 的模型装配：把 Spring AI 的 {@code ChatModel} 指向本进程内的 mock 服务。
 *
 * <p><b>关键点</b>：这里声明的是一个普通的 {@code ChatModel} Bean， <b>没有任何一行埋点代码</b>。埋点由 starter 的 {@code
 * ChatModelDecoratorBeanPostProcessor} 自动完成—— 这就是「零侵入」最直观的证明：demo 里唯一的「接入代码」就是这个正常的 {@code
 * ChatModel} 配置。
 */
@Configuration
public class DemoChatModelConfiguration {

    /**
     * 构造 mock 模型 Bean。
     *
     * @param baseUrl mock 服务地址，来自 {@code llm.glasshouse.demo.base-url}
     * @param modelName 模型名，来自 {@code llm.glasshouse.demo.model}
     * @param scenarioEngine 场景引擎（保证 mock 服务已装配）
     * @return ChatModel
     */
    @Bean
    public ChatModel demoChatModel(
            @Value("${llm.glasshouse.demo.base-url:http://127.0.0.1:${server.port:8080}/mock}")
                    String baseUrl,
            @Value("${llm.glasshouse.demo.model:mock-chat}") String modelName,
            ScenarioEngine scenarioEngine) {
        // scenarioEngine 只用于确保 mock 服务的 Bean 已被创建（依赖注入顺序保证）
        OpenAiApi api =
                OpenAiApi.builder()
                        .baseUrl(baseUrl)
                        // mock 服务不校验 key，这里放一个明显的占位值，避免使用者误配成真实端点
                        .apiKey("mock-not-a-real-key")
                        .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(
                        org.springframework.ai.openai.OpenAiChatOptions.builder()
                                .model(modelName)
                                .build())
                .build();
    }

    /**
     * 构造 ChatClient，业务侧只用它，完全不感知埋点。
     *
     * @param chatModel 模型（已被 starter 装饰）
     * @return ChatClient
     */
    @Bean
    public ChatClient demoChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    /**
     * 返回场景引擎 Bean。
     *
     * @return 场景引擎
     */
    @Bean
    public ScenarioEngine scenarioEngine() {
        return new ScenarioEngine();
    }

    /**
     * 返回 demo 元信息，供 {@code /demo/info} 端点使用。
     *
     * @return 元信息
     */
    @Bean
    public Map<String, String> demoInfo() {
        return Map.of(
                "mockEndpoint", "/mock/v1/chat/completions",
                "chatEndpoint", "/demo/chat",
                "traceEndpoint", "/_glasshouse/trace/{traceId}",
                "costEndpoint", "/_glasshouse/cost?dimension=tenant",
                "note", "全部零 API Key：模型由本进程内的 mock 服务提供");
    }
}
