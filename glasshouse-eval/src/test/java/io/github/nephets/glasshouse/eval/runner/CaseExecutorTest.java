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

package io.github.nephets.glasshouse.eval.runner;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import io.github.nephets.glasshouse.core.eval.EvalCase;
import io.github.nephets.glasshouse.core.eval.EvalResult;
import io.github.nephets.glasshouse.core.model.ModelRef;
import io.github.nephets.glasshouse.core.pricing.PriceEntry;
import io.github.nephets.glasshouse.core.pricing.PriceSource;
import io.github.nephets.glasshouse.core.pricing.PricingTable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用例执行器测试。
 *
 * <p>本类补的是 eval 模块里最大的一块空白：{@link CaseExecutor} 此前被评测器单测完全绕过（测试直接实现 {@code
 * EvalRunner.CaseInvoker}），所以「把一次模型调用变成一条 {@link EvalResult}」这段映射逻辑 一行都没被测过。
 *
 * <p><b>断言对象是「映射规则」而不是「模型说了什么」</b>：如果只断言「我给模型塞了 A，结果就是 A」， 那测试永远为真、也永远抓不到问题。因此这里断言的是执行器自己引入的语义——多
 * generation 合并、 用量归一化的来源标记、价格表缺失时的 0 成本、异常被转成错误串的形状、以及渲染器异常不被吞。
 */
class CaseExecutorTest {

    private static final ModelRef DEEPSEEK = ModelRef.parse("deepseek/deepseek-chat");

    private static EvalCase serviceCase(String id) {
        return EvalCase.of(
                id,
                Map.of("question", "退货政策？"),
                Map.of("contains", List.of("退货")),
                "售后",
                "easy",
                true,
                null);
    }

    private static ChatResponse response(
            String text, Integer promptTokens, Integer completionTokens) {
        return ChatResponse.builder()
                .generations(
                        List.of(new Generation(AssistantMessage.builder().content(text).build())))
                .metadata(
                        ChatResponseMetadata.builder()
                                .usage(new DefaultUsage(promptTokens, completionTokens))
                                .build())
                .build();
    }

    private static CaseExecutor executor(ChatModel model, PricingTable table) {
        return new CaseExecutor(
                model,
                DEEPSEEK,
                table,
                c -> new Prompt("问：" + c.input().get("question")),
                "planner@v3");
    }

    @Test
    @DisplayName("★ 供应商上报用量时：原样归一化，来源标 VENDOR_REPORTED")
    void vendorReportedUsageIsKept() {
        EvalResult result =
                executor(p -> response("支持退货", 1000, 500), new PricingTable())
                        .invoke(serviceCase("cs-1"));

        assertThat(result.passed()).as("没抛异常就算成功完成").isTrue();
        assertThat(result.error()).isNull();
        assertThat(result.usage().inputTokens()).isEqualTo(1000L);
        assertThat(result.usage().outputTokens()).isEqualTo(500L);
        assertThat(result.usage().totalTokens()).isEqualTo(1500L);
        assertThat(result.usage().usageSource())
                .as("★ 供应商给了用量就必须标 VENDOR_REPORTED，标成 ESTIMATED 会让成本归因失去可信度")
                .isEqualTo(io.github.nephets.glasshouse.core.usage.UsageSource.VENDOR_REPORTED);
    }

    @Test
    @DisplayName("★ 已知限制：metadata 在但 usage 为空时被记成 VENDOR_REPORTED（Spring AI 返回 EmptyUsage 而非 null）")
    void emptyUsageIsMisclassifiedAsVendorReported() {
        ChatResponse noUsage =
                ChatResponse.builder()
                        .generations(
                                List.of(
                                        new Generation(
                                                AssistantMessage.builder()
                                                        .content("支持退货")
                                                        .build())))
                        .metadata(ChatResponseMetadata.builder().build())
                        .build();

        EvalResult result = executor(p -> noUsage, new PricingTable()).invoke(serviceCase("cs-1"));

        // 这条断言钉的是「当前实际行为」，不是「期望行为」：
        // CaseExecutor.extractUsage 只判 springUsage != null，而 Spring AI 在无用量时返回
        // EmptyUsage 这个非 null 占位对象，于是「供应商没报用量」被误标成 VENDOR_REPORTED、
        // 用量记 0、成本记 0。要修就得改成判 EmptyUsage / totalTokens==0，属于产品行为变更，
        // 不在补单测的范围内。这里留断言是为了让这个偏差在报告里可见，而不是被静默接受。
        assertThat(result.usage().usageSource())
                .as("★ 现状：无用量被标 VENDOR_REPORTED —— 这是已知偏差，修它需要改主源码")
                .isEqualTo(io.github.nephets.glasshouse.core.usage.UsageSource.VENDOR_REPORTED);
        assertThat(result.usage().totalTokens()).isZero();
        assertThat(result.cost()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("★ 整个响应为 null 时走估算路径（ESTIMATED，不记 0）")
    void nullResponseIsEstimatedNotZero() {
        EvalResult result = executor(p -> null, new PricingTable()).invoke(serviceCase("cs-1"));

        assertThat(result.usage().usageSource())
                .as("★ 没有响应对象时用量必须标成估算：记 0 会让「成本为 0」被当成事实")
                .isEqualTo(io.github.nephets.glasshouse.core.usage.UsageSource.ESTIMATED);
        assertThat(result.usage().totalTokens()).isPositive();
    }

    @Test
    @DisplayName("★ 价格表命中时按价格算成本（不是 0）")
    void costComesFromPricingTable() {
        PriceEntry entry =
                PriceEntry.of(
                        "deepseek",
                        "deepseek-chat",
                        new BigDecimal("0.001"),
                        new BigDecimal("0.002"),
                        LocalDate.of(2020, 1, 1),
                        PriceSource.OFFICIAL);
        PricingTable table = new PricingTable(List.of(entry));

        EvalResult result =
                executor(p -> response("支持退货", 1000, 1000), table).invoke(serviceCase("cs-1"));

        assertThat(result.cost())
                .as("1000 输入 + 1000 输出，按 0.001 / 0.002 每千 token 计价")
                .isEqualByComparingTo("0.003");
    }

    @Test
    @DisplayName("价格表为空 / 查不到该模型时成本记 0，且不抛异常")
    void missingPriceMeansZeroCost() {
        EvalResult emptyTable =
                executor(p -> response("x", 1000, 1000), new PricingTable())
                        .invoke(serviceCase("cs-1"));
        assertThat(emptyTable.cost()).isEqualByComparingTo("0");
        assertThat(emptyTable.passed()).isTrue();

        EvalResult nullTable =
                new CaseExecutor(
                                p -> response("x", 1000, 1000),
                                DEEPSEEK,
                                null,
                                c -> new Prompt("q"),
                                "v")
                        .invoke(serviceCase("cs-1"));
        assertThat(nullTable.cost()).isEqualByComparingTo("0");
        assertThat(nullTable.passed()).as("价格表为 null 是合法入参，不是失败").isTrue();
    }

    @Test
    @DisplayName("★ 多个 generation 被拼接（不是只取第一个）")
    void multipleGenerationsAreConcatenated() {
        ChatResponse multi =
                ChatResponse.builder()
                        .generations(
                                List.of(
                                        new Generation(
                                                AssistantMessage.builder().content("第一段").build()),
                                        new Generation(
                                                AssistantMessage.builder().content("第二段").build())))
                        .metadata(ChatResponseMetadata.builder().build())
                        .build();

        EvalResult result = executor(p -> multi, new PricingTable()).invoke(serviceCase("cs-1"));

        assertThat(result.actual())
                .as("★ 只取 results.get(0) 会静默丢掉后续内容，指标会偏低且无从发现")
                .isEqualTo("第一段第二段");
    }

    @Test
    @DisplayName("模型返回 null 响应时按空输出处理，不算失败")
    void nullResponseBecomesEmptyOutput() {
        EvalResult result = executor(p -> null, new PricingTable()).invoke(serviceCase("cs-1"));

        assertThat(result.passed()).as("null 响应不是异常路径，仍记为成功完成").isTrue();
        assertThat(result.actual()).isEmpty();
        assertThat(result.actualLength()).isZero();
    }

    @Test
    @DisplayName("★ 模型抛异常时：转成「异常类名: 消息」的错误串，而不是把异常抛出去")
    void modelFailureBecomesErrorString() {
        EvalResult result =
                executor(
                                p -> {
                                    throw new IllegalStateException("端点 502");
                                },
                                new PricingTable())
                        .invoke(serviceCase("cs-1"));

        assertThat(result.passed()).isFalse();
        assertThat(result.actual()).isNull();
        assertThat(result.error())
                .as("★ 错误串必须带异常类名，否则「超时」与「鉴权失败」在报告里长得一模一样")
                .isEqualTo("IllegalStateException: 端点 502");
        assertThat(result.score()).isEmpty();
        assertThat(result.usage()).isNull();
        assertThat(result.cost()).isNull();
    }

    @Test
    @DisplayName("★ Prompt 渲染器抛异常不被吞掉（渲染失败不是「用例失败」）")
    void rendererFailurePropagates() {
        CaseExecutor failing =
                new CaseExecutor(
                        p -> response("x", 1, 1),
                        DEEPSEEK,
                        new PricingTable(),
                        c -> {
                            throw new IllegalArgumentException("模板缺变量");
                        },
                        "v");

        assertThatThrownBy(() -> failing.invoke(serviceCase("cs-1")))
                .as("★ 把渲染异常吞成「用例失败」会让数据集的模板问题被误读成模型效果差")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("模板缺变量");
    }

    @Test
    @DisplayName("promptVersion 为 null 时兜底 unknown，报告里不出现 null 字样")
    void nullPromptVersionFallsBack() {
        CaseExecutor ex =
                new CaseExecutor(
                        p -> response("x", 1, 1),
                        DEEPSEEK,
                        new PricingTable(),
                        c -> new Prompt("q"),
                        null);

        assertThat(ex.promptVersion()).isEqualTo("unknown");
        assertThat(ex.modelRef()).isEqualTo(DEEPSEEK);
    }

    @Test
    @DisplayName("构造器入参为 null 时立即失败，不留到运行期")
    void constructorRejectsNulls() {
        assertThatThrownBy(() -> new CaseExecutor(null, DEEPSEEK, null, c -> new Prompt("q"), "v"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("chatModel");
        assertThatThrownBy(() -> new CaseExecutor(p -> null, null, null, c -> new Prompt("q"), "v"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("modelRef");
        assertThatThrownBy(() -> new CaseExecutor(p -> null, DEEPSEEK, null, null, "v"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("promptRenderer");
    }

    @Test
    @DisplayName("extractText：null 响应、null message、多结果都不炸")
    void extractTextHandlesDegenerateResponses() {
        assertThat(CaseExecutor.extractText(null)).isEmpty();
        assertThat(CaseExecutor.extractText(ChatResponse.builder().generations(List.of()).build()))
                .isEmpty();
        assertThat(CaseExecutor.extractUsage(null)).isNull();
        assertThat(CaseExecutor.extractUsage(ChatResponse.builder().generations(List.of()).build()))
                .as("★ Spring AI 不会返回 null usage，而是返回 EmptyUsage；判 null 判不出来")
                .isInstanceOf(org.springframework.ai.chat.metadata.EmptyUsage.class);
    }

    @Test
    @DisplayName("★ 同一份原始用量在 openai / deepseek / anthropic 三个口径下归一化结果一致")
    void providerRoutingKeepsNormalizedUsageConsistent() {
        // 三家的差异只在 parse()（JSON 字段位置），normalize() 共用 BaseUsageNormalizer 模板。
        // 这里钉住的是「provider 不同不该改变同一份 RawUsage 的归一化结果」：若有人给某一家
        // 单独覆写 normalize，成本口径就会按供应商漂移，这条会变红。
        ModelRef[] providers = {
            ModelRef.parse("openai/gpt-4o-mini"),
            ModelRef.parse("deepseek/deepseek-chat"),
            ModelRef.parse("anthropic/claude-3-5-sonnet")
        };
        long expectedInput = -1L;
        for (ModelRef ref : providers) {
            EvalResult result =
                    new CaseExecutor(
                                    p -> response("支持退货", 1000, 500),
                                    ref,
                                    new PricingTable(),
                                    c -> new Prompt("问：" + c.input().get("question")),
                                    "v")
                            .invoke(serviceCase("cs-1"));
            if (expectedInput < 0L) {
                expectedInput = result.usage().inputTokens();
            }
            assertThat(result.usage().inputTokens())
                    .as("%s 的输入 token 不应被口径差异改写", ref.providerId())
                    .isEqualTo(expectedInput);
            assertThat(result.usage().outputTokens()).isEqualTo(500L);
            assertThat(result.usage().totalTokens()).isEqualTo(1500L);
            assertThat(result.usage().usageSource())
                    .isEqualTo(io.github.nephets.glasshouse.core.usage.UsageSource.VENDOR_REPORTED);
        }
    }

    @Test
    @DisplayName("★ Usage 对象在但各字段为 null：token 走估算，来源仍标 VENDOR_REPORTED（已知偏差）")
    void usageWithNullFieldsEstimatesTokensButKeepsVendorReported() {
        ChatResponse nullFieldUsage =
                ChatResponse.builder()
                        .generations(
                                List.of(
                                        new Generation(
                                                AssistantMessage.builder()
                                                        .content("支持退货")
                                                        .build())))
                        .metadata(
                                ChatResponseMetadata.builder().usage(new NullFieldUsage()).build())
                        .build();

        EvalResult result =
                executor(p -> nullFieldUsage, new PricingTable()).invoke(serviceCase("cs-1"));

        // 与 EmptyUsage 同一个根因：CaseExecutor 把「usage 对象非 null」当成「供应商报了用量」，
        // 而 RawUsage 里 reasoning/cached 又被填成 0（非 null），anyPresent() 因此为真。
        // 只钉现状，不改主源码（改它会变更成本口径与 CI 门禁）。
        assertThat(result.usage().usageSource())
                .as("★ 现状：字段全空仍标 VENDOR_REPORTED —— 已知偏差，修它需要改主源码")
                .isEqualTo(io.github.nephets.glasshouse.core.usage.UsageSource.VENDOR_REPORTED);
        assertThat(result.usage().inputTokens()).as("输入 token 由 prompt 文本估算器给出，不是 0").isPositive();
        assertThat(result.usage().outputTokens()).isPositive();
    }

    @Test
    @DisplayName("★ prompt 里声明的 max_tokens 在估算不出 token 时作为输入上界")
    void maxTokensFromPromptOptionsIsUsedAsInputFallback() {
        Prompt withoutInstructions =
                new Prompt("占位", ChatOptions.builder().maxTokens(64).build()) {
                    @Override
                    public List<Message> getInstructions() {
                        return List.of();
                    }
                };
        ChatResponse nullFieldUsage =
                ChatResponse.builder()
                        .generations(
                                List.of(
                                        new Generation(
                                                AssistantMessage.builder()
                                                        .content("支持退货")
                                                        .build())))
                        .metadata(
                                ChatResponseMetadata.builder().usage(new NullFieldUsage()).build())
                        .build();

        EvalResult result =
                new CaseExecutor(
                                p -> nullFieldUsage,
                                DEEPSEEK,
                                new PricingTable(),
                                c -> withoutInstructions,
                                "v")
                        .invoke(serviceCase("cs-1"));

        assertThat(result.usage().inputTokens())
                .as("★ prompt 文本为空 → 估算器给 0 → 才轮到 max_tokens=64 兜底，证明上界真的透传进来了")
                .isEqualTo(64L);
        assertThat(result.usage().outputTokens()).isPositive();
    }

    @Test
    @DisplayName("渲染器返回 null Prompt 时按空 prompt 处理，不抛异常")
    void nullPromptFromRendererIsTolerated() {
        EvalResult result =
                new CaseExecutor(
                                p -> response("支持退货", 800, 200),
                                DEEPSEEK,
                                new PricingTable(),
                                c -> null,
                                "v")
                        .invoke(serviceCase("cs-1"));

        assertThat(result.passed()).isTrue();
        assertThat(result.actual()).isEqualTo("支持退货");
        assertThat(result.usage().totalTokens())
                .as("★ 仍然走了正常的用量归一化路径（800 + 200），不是被 null prompt 短路成空结果")
                .isEqualTo(1000L);
        assertThat(result.usage().usageSource())
                .isEqualTo(io.github.nephets.glasshouse.core.usage.UsageSource.VENDOR_REPORTED);
    }

    /**
     * 四个字段全为 null 的 Usage 实现。
     *
     * <p>用来覆盖 {@code CaseExecutor.extractUsage} 之后的空值分支：真实供应商的某些实现会返回 缺字段的 usage，而不是 Spring AI 的
     * {@code EmptyUsage} 占位对象。
     */
    private static final class NullFieldUsage
            implements org.springframework.ai.chat.metadata.Usage {

        @Override
        public Integer getPromptTokens() {
            return null;
        }

        @Override
        public Integer getCompletionTokens() {
            return null;
        }

        @Override
        public Integer getTotalTokens() {
            return null;
        }

        @Override
        public Object getNativeUsage() {
            return null;
        }
    }
}
