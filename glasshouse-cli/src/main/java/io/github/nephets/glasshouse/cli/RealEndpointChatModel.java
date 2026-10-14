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

import java.time.Duration;
import java.util.List;

import reactor.core.publisher.Flux;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 真实 OpenAI 兼容端点的 {@link ChatModel} 实现。
 *
 * <p><b>为什么自己写而不用 Spring AI 的 {@code OpenAiChatModel}</b>：
 *
 * <ol>
 *   <li>CLI 必须<b>零 Spring 容器</b>启动，用 starter 要拖进一整套自动配置
 *   <li>CI 场景只需要 {@code /v1/chat/completions} 这一个端点，为它引入整个 {@code spring-ai-starter-model-openai}
 *       不划算
 *   <li>本仓面向中国大陆企业，绝大多数国内模型都提供 OpenAI 兼容端点， 一个 HTTP 客户端就够
 * </ol>
 *
 * <p><b>它不参与默认路径</b>：不传 {@code --base-url} 时 CI 走回放模式，零费用。 本类只在用户显式指定端点时启用。
 *
 * <p><b>API Key 从环境变量读，不接受命令行参数</b>：命令行参数会出现在 {@code ps} 输出与 CI 日志里，这是凭据泄漏的常见途径。
 */
final class RealEndpointChatModel implements ChatModel {

    private static final String API_KEY_ENV = "GLASSHOUSE_API_KEY";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final String baseUrl;
    private final String modelId;
    private final java.net.http.HttpClient httpClient;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    private RealEndpointChatModel(String baseUrl, String modelId) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.modelId = modelId == null || modelId.isBlank() ? "gpt-4o-mini" : modelId;
        this.httpClient = java.net.http.HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        this.mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    }

    /**
     * 构造指向真实端点的模型。
     *
     * @param baseUrl 端点地址，如 {@code http://localhost:8080}
     * @param modelRef 模型引用（{@code provider/model}）
     * @return ChatModel
     * @throws IllegalStateException 缺少 API Key
     */
    static ChatModel create(String baseUrl, String modelRef) {
        String apiKey = System.getenv(API_KEY_ENV);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "指定 --base-url 需要环境变量 "
                            + API_KEY_ENV
                            + "（本仓不接受命令行传 key，避免凭据出现在 ps 输出与 CI 日志里）。"
                            + "若只想让门禁跑起来而不产生费用，请改用录制回放模式（不指定 --base-url 即可）。");
        }
        String actualModel = modelRef;
        int idx = modelRef == null ? -1 : modelRef.indexOf('/');
        if (idx > 0) {
            actualModel = modelRef.substring(idx + 1);
        }
        RealEndpointChatModel model = new RealEndpointChatModel(baseUrl, actualModel);
        model.apiKey = apiKey;
        return model;
    }

    private String apiKey;

    @Override
    public ChatResponse call(Prompt prompt) {
        try {
            String body = renderRequestBody(prompt);
            java.net.http.HttpRequest request =
                    java.net.http.HttpRequest.newBuilder(
                                    java.net.URI.create(baseUrl + "/v1/chat/completions"))
                            .timeout(TIMEOUT)
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + apiKey)
                            .POST(
                                    java.net.http.HttpRequest.BodyPublishers.ofString(
                                            body, java.nio.charset.StandardCharsets.UTF_8))
                            .build();
            java.net.http.HttpResponse<String> response =
                    httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException(
                        "端点返回 HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
            }
            return parseResponse(response.body());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("调用端点失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用端点被中断", e);
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        // 首版 CLI 的评测只走非流式：流式会让「usage 何时到达」变成不确定的，
        // 而门禁必须可复现。生产应用里的流式由 starter 的 ChatModelDecorator 处理。
        return Flux.error(
                new UnsupportedOperationException("CLI 评测暂不支持流式调用，请改用 --base-url 的非流式模式"));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return ChatOptions.builder().model(modelId).build();
    }

    /**
     * 渲染 OpenAI 兼容请求体。
     *
     * @param prompt 提示
     * @return JSON 文本
     * @throws Exception 序列化失败
     */
    private String renderRequestBody(Prompt prompt) throws java.io.IOException {
        com.fasterxml.jackson.databind.node.ObjectNode root = mapper.createObjectNode();
        root.put("model", modelId);
        root.put("temperature", 0.0d);
        root.put("stream", false);
        com.fasterxml.jackson.databind.node.ArrayNode messages = root.putArray("messages");
        for (Message message : prompt.getInstructions()) {
            com.fasterxml.jackson.databind.node.ObjectNode node = messages.addObject();
            node.put("role", roleOf(message));
            node.put("content", message.getText() == null ? "" : message.getText());
        }
        return mapper.writeValueAsString(root);
    }

    /**
     * 解析 OpenAI 兼容响应。
     *
     * @param json 响应 JSON
     * @return ChatResponse
     * @throws Exception 解析失败
     */
    private ChatResponse parseResponse(String json) throws java.io.IOException {
        com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);
        com.fasterxml.jackson.databind.JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IllegalStateException("响应中没有 choices: " + abbreviate(json));
        }
        String content = choices.get(0).path("message").path("content").asText("");
        // OpenAI 的 finish_reason 在 1.1.x 的 ChatResponseMetadata 上没有对应 setter，
        // 因此只读取不使用；需要该字段时应升级到支持它的 Spring AI 版本。
        com.fasterxml.jackson.databind.JsonNode usageNode = root.path("usage");
        org.springframework.ai.chat.metadata.Usage usage = null;
        if (usageNode.isObject()) {
            usage =
                    new org.springframework.ai.chat.metadata.DefaultUsage(
                            usageNode.path("prompt_tokens").asInt(),
                            usageNode.path("completion_tokens").asInt());
        }
        String responseModel = root.path("model").asText(modelId);
        return ChatResponse.builder()
                .generations(
                        List.of(
                                new Generation(
                                        AssistantMessage.builder().content(content).build(), null)))
                .metadata(ChatResponseMetadata.builder().model(responseModel).usage(usage).build())
                .build();
    }

    private static String roleOf(Message message) {
        return switch (message.getMessageType()) {
            case SYSTEM -> "system";
            case ASSISTANT -> "assistant";
            case TOOL -> "tool";
            default -> "user";
        };
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }
}
