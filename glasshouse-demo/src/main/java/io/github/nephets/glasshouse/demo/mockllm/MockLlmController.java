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

package io.github.nephets.glasshouse.demo.mockllm;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Mock LLM：提供 OpenAI 兼容的 {@code /v1/chat/completions}。
 *
 * <p><b>它是 demo 能「零 API Key 跑通」的关键</b>，也是 CI 能在不花钱的前提下 跑通全链路的原因。
 *
 * <p><b>返回的 usage 是完整的 OpenAI 结构</b>（含 {@code prompt_tokens_details.cached_tokens}）， 这样 {@code
 * OpenAiUsageNormalizer} 走的是真实的解析路径而不是估算路径—— demo 里看到的成本是真算法算出来的，不是编的数字。
 */
@RestController
@RequestMapping("/mock")
public class MockLlmController {

    private static final Logger log = LoggerFactory.getLogger(MockLlmController.class);

    private final ScenarioEngine scenarioEngine;
    private final ObjectMapper mapper;

    /**
     * 构造控制器。
     *
     * @param scenarioEngine 场景引擎
     */
    public MockLlmController(ScenarioEngine scenarioEngine) {
        this.scenarioEngine = scenarioEngine;
        this.mapper = new ObjectMapper();
    }

    /**
     * OpenAI 兼容的对话补全接口。
     *
     * @param body 请求体
     * @return OpenAI 兼容响应
     * @throws Exception JSON 处理失败
     */
    @PostMapping(
            value = "/v1/chat/completions",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> chatCompletions(@RequestBody String body) throws Exception {
        JsonNode request = mapper.readTree(body);
        String model = request.path("model").asText("mock-chat");
        String userInput = lastUserMessage(request);
        Scenario scenario = scenarioEngine.select(userInput);

        // 模拟真实模型的耗时，让 span 树上的「慢在哪」一眼可见
        sleepQuietly(scenario.latencyMillis());

        log.info(
                "[mock-llm] model={} scenario={} inputTokens={} outputTokens={}",
                model,
                scenario.name(),
                scenario.inputTokens(),
                scenario.outputTokens());

        ObjectNode usage = mapper.createObjectNode();
        usage.put("prompt_tokens", scenario.inputTokens());
        usage.put("completion_tokens", scenario.outputTokens());
        usage.put("total_tokens", scenario.inputTokens() + scenario.outputTokens());
        ObjectNode details = usage.putObject("prompt_tokens_details");
        details.put("cached_tokens", 0L);

        ObjectNode response = mapper.createObjectNode();
        response.put("id", "chatcmpl-mock-" + Math.abs(scenario.name().hashCode()));
        response.put("object", "chat.completion");
        response.put("created", System.currentTimeMillis() / 1000L);
        response.put("model", model);
        ObjectNode choice = response.putArray("choices").addObject();
        choice.put("index", 0);
        choice.put("finish_reason", "stop");
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        message.put("content", scenario.answer());
        response.set("usage", usage);
        return mapper.convertValue(response, Map.class);
    }

    /**
     * 取出最后一条 user 消息的文本。
     *
     * @param request 请求节点
     * @return 用户输入；无 user 消息返回空串
     */
    private static String lastUserMessage(JsonNode request) {
        JsonNode messages = request.path("messages");
        if (!messages.isArray()) {
            return "";
        }
        String last = "";
        for (JsonNode message : messages) {
            if ("user".equals(message.path("role").asText())) {
                last = message.path("content").asText("");
            }
        }
        return last;
    }

    /**
     * 模拟耗时。
     *
     * <p>demo 里需要真实的延迟差异才能体现 span 树的耗时信息， 但要设上限（默认最多 3 秒），避免 CI 因 mock 而变慢。
     *
     * @param millis 毫秒数
     */
    private static void sleepQuietly(long millis) {
        long capped = Math.min(millis, 3_000L);
        if (capped <= 0L) {
            return;
        }
        try {
            Thread.sleep(capped);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 返回可用场景列表，供 demo 前端与文档展示。
     *
     * @return 场景摘要
     */
    @org.springframework.web.bind.annotation.GetMapping("/scenarios")
    public List<Map<String, Object>> scenarios() {
        return scenarioEngine.all().stream()
                .map(
                        s ->
                                Map.<String, Object>of(
                                        "name", s.name(),
                                        "keywords", s.keywords(),
                                        "inputTokens", s.inputTokens(),
                                        "outputTokens", s.outputTokens(),
                                        "latencyMillis", s.latencyMillis()))
                .toList();
    }
}
