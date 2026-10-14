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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 录制回放模型：命中录制值时<b>零上游调用</b>。
 *
 * <p><b>为什么必须有它</b>：CI 回归门禁要在每次 PR 上跑，如果每次都调真实模型， 一是产生费用，二是<b>结果不可复现</b>——模型输出本身有随机性，同一份代码两次跑分不同，
 * 门禁就会随机变红，团队很快就会开始无视它。回放模式让「同一 commit 两次跑分完全一致」 成为结构性保证，而不是靠运气。
 *
 * <p><b>这不是「伪造结果」</b>：录制文件是<b>由真实模型产出并提交进 git 的</b>， 就像架构 ② 的 {@code
 * ReplayChatModel}。它把「模型的不确定性」从门禁里摘出去， 让门禁真正测的是「你的 Prompt 改动有没有让输出变差」。
 *
 * <p><b>什么时候必须用真模型</b>：想验证「换了新模型会怎样」时， 用 {@code --base-url} 指向真实端点跑一次，并把结果重新录制。首版不自动录制
 * （自动录制意味着有人得付钱，得由使用者显式决定）。
 */
final class ReplayChatModel implements ChatModel {

    private final Map<String, ReplayEntry> entries;

    /**
     * 构造回放模型。
     *
     * @param entries caseId → 录制响应
     */
    ReplayChatModel(Map<String, ReplayEntry> entries) {
        this.entries = entries;
    }

    /**
     * 从 JSONL 录制文件加载。
     *
     * <p>文件格式（每行一条）：
     *
     * <pre>{@code
     * {"caseId":"cs-001","content":"根据《消费者权益保护法》…","inputTokens":120,"outputTokens":86}
     * }</pre>
     *
     * @param file 文件路径，可为 null
     * @return 回放模型；文件为 null 时返回空回放（所有用例都会失败，便于测试门禁真的会变红）
     * @throws IOException 文件读取失败
     */
    static ReplayChatModel fromFile(Path file) throws IOException {
        if (file == null) {
            return new ReplayChatModel(new HashMap<>());
        }
        Map<String, ReplayEntry> entries = new LinkedHashMap<>();
        // ★ ObjectMapper 构造成本不低（要扫描并缓存全部序列化器），不能在循环里逐行 new。
        ObjectMapper mapper = new ObjectMapper();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("#")) {
                continue;
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = mapper.readValue(trimmed, Map.class);
                String caseId = String.valueOf(map.get("caseId"));
                String content = String.valueOf(map.getOrDefault("content", ""));
                long in = toLong(map.get("inputTokens"), 100L);
                long out = toLong(map.get("outputTokens"), 50L);
                entries.put(caseId, new ReplayEntry(content, in, out));
            } catch (JsonProcessingException e) {
                throw new IOException(
                        "录制文件第 " + (entries.size() + 1) + " 行解析失败: " + e.getMessage(), e);
            }
        }
        return new ReplayChatModel(entries);
    }

    /**
     * 返回录制条目数。
     *
     * @return 条目数
     */
    int size() {
        return entries.size();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String key = keyOf(prompt);
        ReplayEntry entry = entries.get(key);
        if (entry == null) {
            // 录制里没有这条用例：返回明确的失败而不是空响应。
            // 空响应会让所有关键词指标得 0 分，从而「门禁红」但原因难定位；
            // 抛异常则错误信息直指「录制文件缺这条用例」。
            throw new IllegalStateException(
                    "录制文件中没有 caseId=" + key + " 的响应。请重新录制或改用 --base-url 指向真实端点。");
        }
        Generation generation =
                new Generation(AssistantMessage.builder().content(entry.content()).build(), null);
        return ChatResponse.builder()
                .generations(List.of(generation))
                .metadata(
                        ChatResponseMetadata.builder()
                                .model("replay")
                                .usage(
                                        new DefaultUsage(
                                                (int) entry.inputTokens(),
                                                (int) entry.outputTokens()))
                                .build())
                .build();
    }

    @Override
    public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
        return org.springframework.ai.chat.prompt.ChatOptions.builder().model("replay").build();
    }

    /**
     * 从 Prompt 中提取 caseId。
     *
     * <p>渲染器会把 caseId 作为一条 {@code system} 消息写进 Prompt， 这里按 {@code [case:<id>]} 标记提取。
     *
     * @param prompt 提示
     * @return caseId；解析不到返回空串
     */
    private static String keyOf(Prompt prompt) {
        if (prompt == null) {
            return "";
        }
        for (Message message : prompt.getInstructions()) {
            String text = message.getText();
            if (text == null) {
                continue;
            }
            int start = text.indexOf(CASE_MARKER_PREFIX);
            if (start < 0) {
                continue;
            }
            int end = text.indexOf(CASE_MARKER_SUFFIX, start);
            if (end > start) {
                return text.substring(start + CASE_MARKER_PREFIX.length(), end).trim();
            }
        }
        return "";
    }

    private static long toLong(Object value, long fallback) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * 从 classpath 资源加载回放模型。
     *
     * @param resourcePath 资源路径；为 null 时返回空回放
     * @return 回放模型
     */
    static ReplayChatModel fromClasspath(String resourcePath) {
        if (resourcePath == null) {
            return new ReplayChatModel(new HashMap<>());
        }
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = ReplayChatModel.class.getClassLoader();
        }
        try (InputStream in = loader.getResourceAsStream(resourcePath)) {
            if (in == null) {
                return new ReplayChatModel(new HashMap<>());
            }
            Map<String, ReplayEntry> entries = new LinkedHashMap<>();
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("//")) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = new ObjectMapper().readValue(trimmed, Map.class);
                    entries.put(
                            String.valueOf(map.get("caseId")),
                            new ReplayEntry(
                                    String.valueOf(map.getOrDefault("content", "")),
                                    toLong(map.get("inputTokens"), 100L),
                                    toLong(map.get("outputTokens"), 50L)));
                }
            }
            return new ReplayChatModel(entries);
        } catch (IOException e) {
            throw new UncheckedIOException("读取录制文件失败: " + resourcePath, e);
        }
    }

    /** caseId 在 Prompt 中的前缀标记。 */
    static final String CASE_MARKER_PREFIX = "[case:";

    /** caseId 在 Prompt 中的后缀标记。 */
    static final String CASE_MARKER_SUFFIX = "]";

    /**
     * 一条录制响应。
     *
     * @param content 模型输出全文
     * @param inputTokens 输入 token（用于成本计算）
     * @param outputTokens 输出 token（用于成本计算）
     */
    record ReplayEntry(String content, long inputTokens, long outputTokens) {}
}
