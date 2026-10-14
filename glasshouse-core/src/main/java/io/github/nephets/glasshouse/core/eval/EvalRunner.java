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

package io.github.nephets.glasshouse.core.eval;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 极简评测引擎。
 *
 * <p><b>刻意不引入 LangChain4j 的 Evaluation 模块</b>（架构 §4.1）：它对中文评测与国产模型支持不足，
 * 且是重量级依赖。整个引擎只做三件事——跑用例、算指标、判门禁——全部是确定性纯逻辑。
 *
 * <p><b>可复现性是本类的第一约束</b>。架构 ③T04 验收标准第 1 条要求「两次运行分数完全一致」。 为此：
 *
 * <ul>
 *   <li>默认串行执行（{@link EvalConfig#DEFAULT_PARALLELISM} = 1），不引入并发不确定性
 *   <li>指标全部是纯函数，不含随机、不含时间依赖
 *   <li>结果按用例在数据集中的<b>原始顺序</b>排列，不因执行顺序变化
 * </ul>
 *
 * <p><b>调用模型的方式</b>是本类唯一的外部依赖，通过 {@link CaseInvoker} 函数接口注入。 这样 core 层不需要依赖 Spring AI（保持零 Spring
 * 依赖）， 而 starter/eval 模块可以传一个「走 ChatModelDecorator」的 invoker，从而<b>评测也产出 span</b>。
 */
public final class EvalRunner {

    private final CaseInvoker invoker;
    private final List<Metric> metrics;
    private final CostMetric costMetric;

    /**
     * 用默认指标集构造。
     *
     * @param invoker 用例执行器，不可为 null
     */
    public EvalRunner(CaseInvoker invoker) {
        this(invoker, defaultMetrics(BigDecimal.ONE));
    }

    /**
     * 用指定指标集构造。
     *
     * @param invoker 用例执行器，不可为 null
     * @param metrics 指标列表；为 null 时用默认指标集
     */
    public EvalRunner(CaseInvoker invoker, List<Metric> metrics) {
        this(invoker, metrics, metrics == null ? null : findCostMetric(metrics));
    }

    private EvalRunner(CaseInvoker invoker, List<Metric> metrics, CostMetric costMetric) {
        this.invoker = Objects.requireNonNull(invoker, "invoker 不能为 null");
        this.metrics =
                metrics == null || metrics.isEmpty()
                        ? defaultMetrics(BigDecimal.ONE)
                        : List.copyOf(metrics);
        this.costMetric = costMetric;
    }

    /**
     * 返回默认指标集。
     *
     * @param costBudgetCny 成本指标预算
     * @return 不可变指标列表
     */
    public static List<Metric> defaultMetrics(BigDecimal costBudgetCny) {
        List<Metric> list = new ArrayList<>();
        list.add(ExactMatchMetric.INSTANCE);
        list.add(KeywordRecallMetric.INSTANCE);
        list.add(ContainmentMetric.INSTANCE);
        list.add(JsonSchemaMetric.INSTANCE);
        list.add(new CostMetric(costBudgetCny));
        return List.copyOf(list);
    }

    private static CostMetric findCostMetric(List<Metric> metrics) {
        for (Metric m : metrics) {
            if (m instanceof CostMetric cm) {
                return cm;
            }
        }
        return new CostMetric(BigDecimal.ONE);
    }

    /**
     * 跑一次评测。
     *
     * @param dataset 评测集，不可为 null
     * @param config 评测配置，不可为 null
     * @return 评测报告，不可为 null
     */
    public EvalReport run(JsonlDataset dataset, EvalConfig config) {
        Objects.requireNonNull(dataset, "dataset 不能为 null");
        Objects.requireNonNull(config, "config 不能为 null");
        long startedAt = System.nanoTime();

        List<EvalResult> results = new ArrayList<>(dataset.size());
        for (EvalCase evalCase : dataset.cases()) {
            EvalResult result = runCase(evalCase);
            results.add(result);
            if (!result.passed() && !config.continueOnCaseError()) {
                break;
            }
        }
        long durationMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        Map<String, BigDecimal> scores = aggregate(results);
        long failed = results.stream().filter(r -> !r.passed()).count();
        BigDecimal totalCost =
                results.stream()
                        .map(EvalResult::cost)
                        .filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new EvalReport(
                dataset.name(),
                dataset.version(),
                dataset.fingerprint(),
                config.promptVersion(),
                config.modelRef(),
                results,
                scores,
                results.size(),
                failed,
                totalCost,
                Instant.now(),
                durationMillis);
    }

    /**
     * 跑单条用例并计算各指标得分。
     *
     * @param evalCase 用例，不可为 null
     * @return 结果，不可为 null
     */
    public EvalResult runCase(EvalCase evalCase) {
        Objects.requireNonNull(evalCase, "evalCase 不能为 null");
        long startedAt = System.nanoTime();
        EvalResult raw;
        try {
            raw = invoker.invoke(evalCase);
        } catch (RuntimeException e) {
            return EvalResult.failure(evalCase.id(), describe(e));
        }
        long latency = (System.nanoTime() - startedAt) / 1_000_000L;
        EvalResult withLatency =
                new EvalResult(
                        raw.caseId(),
                        raw.actual(),
                        raw.score(),
                        raw.usage(),
                        raw.cost(),
                        raw.error(),
                        raw.latencyMillis() > 0 ? raw.latencyMillis() : latency,
                        raw.completedAt());
        return scoreCase(evalCase, withLatency);
    }

    /**
     * 为一条结果补齐各指标得分。
     *
     * @param evalCase 用例（含 expected）
     * @param result 原始结果
     * @return 带得分的结果
     */
    public EvalResult scoreCase(EvalCase evalCase, EvalResult result) {
        Map<String, BigDecimal> scores = new LinkedHashMap<>(result.score());
        String actual = result.actual();
        // 失败用例不参与指标计算，避免「跑挂了」被算成「效果差」而掩盖真实原因
        if (result.passed()) {
            scores.put(
                    Metric.NAME_ACCURACY,
                    ExactMatchMetric.INSTANCE.scoreOf(
                            actual, evalCase.expectedText(ExactMatchMetric.EXPECTED_KEY)));
            scores.put(
                    Metric.NAME_KEYWORD_RECALL,
                    KeywordRecallMetric.INSTANCE.scoreOf(
                            actual, evalCase.expectedList(KeywordRecallMetric.EXPECTED_KEY)));
            scores.put(
                    Metric.NAME_CONTAINMENT,
                    ContainmentMetric.INSTANCE.scoreOf(
                            actual, evalCase.expectedList(ContainmentMetric.EXPECTED_FORBIDDEN)));
            scores.put(
                    Metric.NAME_JSON_SCHEMA,
                    JsonSchemaMetric.INSTANCE.scoreOf(
                            actual,
                            evalCase.expectedList(JsonSchemaMetric.EXPECTED_REQUIRED_FIELDS),
                            expectedFieldTypes(evalCase)));
            BigDecimal cost = result.cost();
            if (costMetric != null) {
                scores.put(Metric.NAME_COST, costMetric.scoreOf(cost));
            }
        }
        return result.withScores(scores);
    }

    /**
     * 汇总所有结果的指标得分（算术平均）。
     *
     * <p>失败用例不计入平均值，因为它们的得分无意义。如果把失败当作 0 分平均， 「10 条里挂了 8 条」和「10 条都跑了但都只有 0.1
     * 分」会得到相同的总分，无法区分故障与质量问题。
     *
     * @param results 结果列表
     * @return 指标名 → 总分
     */
    public Map<String, BigDecimal> aggregate(List<EvalResult> results) {
        Map<String, List<BigDecimal>> buckets = new LinkedHashMap<>();
        for (EvalResult r : results) {
            if (!r.passed()) {
                continue;
            }
            for (Map.Entry<String, BigDecimal> e : r.score().entrySet()) {
                if (e.getValue() != null) {
                    buckets.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue());
                }
            }
        }
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<BigDecimal>> e : buckets.entrySet()) {
            BigDecimal sum = e.getValue().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            result.put(
                    e.getKey(),
                    sum.divide(BigDecimal.valueOf(e.getValue().size()), 4, RoundingMode.HALF_UP));
        }
        return Map.copyOf(result);
    }

    /**
     * 返回当前指标集。
     *
     * @return 不可变指标列表
     */
    public List<Metric> metrics() {
        return metrics;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> expectedFieldTypes(EvalCase evalCase) {
        Object value = evalCase.expected().get(JsonSchemaMetric.EXPECTED_FIELD_TYPES);
        if (value instanceof Map<?, ?> map) {
            Map<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                result.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
            return result;
        }
        return Map.of();
    }

    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        String type = e.getClass().getSimpleName();
        return message == null || message.isBlank() ? type : type + ": " + message;
    }

    /**
     * 用例执行器 SPI：把「跑一条用例」抽象成函数，让 core 层不依赖 Spring AI。
     *
     * <p>生产实现（eval 模块的 {@code CaseExecutor}）会走 {@code ChatModelDecorator}， 因此<b>评测过程本身也会产出 OTel
     * span</b>，可以像排查线上问题一样排查评测为什么变慢。
     */
    @FunctionalInterface
    public interface CaseInvoker {

        /**
         * 执行一条用例。
         *
         * @param evalCase 用例，不可为 null
         * @return 执行结果；返回 null 视为失败
         */
        EvalResult invoke(EvalCase evalCase);
    }
}
