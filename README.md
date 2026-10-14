<div align="center">

# llm-glasshouse · LLM 玻璃房

**看得见（span 树）· 说得清（五维成本归因）· 能回归（CI 门禁）**

Java AI 应用的可观测与评测基建。零代码侵入、零锁定、可完全离线。

[English](#english-readme) | 简体中文

[![Java 21](https://img.shields.io/badge/java-21+-blue.svg)](https://openjdk.org/)
[![Spring Boot 3.5](https://img.shields.io/badge/spring--boot-3.5-green.svg)](https://spring.io/projects/spring-boot)
[![OTel GenAI 1.41.0](https://img.shields.io/badge/otel--genai-1.41.0-orange.svg)](https://opentelemetry.io/)
[![License Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-grey.svg)](LICENSE)

</div>

---

## 这是什么 / What is this

给 Java AI 应用用的**可观测 + 评测**基建。三件事：

| | 解决什么 | 怎么用 |
|---|---|---|
| **看得见** | 请求慢在哪一步、哪一步花钱 | 引入 starter，零代码改动 |
| **说得清** | 成本按租户/业务/功能/Prompt/步骤怎么分 | `glasshouse cost --by tenant` |
| **能回归** | 改 Prompt 是变好还是变坏 | `glasshouse eval run --gate "..."`，CI 里 exit 1 |

**核心约束：零代码侵入。** 业务代码一行不改 —— 引入 starter 后，每次模型调用自动产生符合
[OTel GenAI 语义约定](https://opentelemetry.io/docs/specs/semconv/gen-ai/) 1.41.0 的 span，
带完整的 token 计量、成本与归因维度。

---

## 30 秒上手 / Quick start

```bash
# 1. 构建（需要 JDK 21 + Maven 3.9）
mvn clean install

# 2. 跑演示（零 API Key，模型由进程内 mock 服务提供）
java -jar glasshouse-demo/target/glasshouse-demo-0.1.0-SNAPSHOT.jar

# 3. 跑一次三步客服 Agent
curl -s -X POST localhost:8080/demo/chat \
  -H 'Content-Type: application/json' \
  -d '{"question":"退货政策是什么？","tenantId":"vip"}'

# 4. 从返回里拿 traceId，看 span 树（同时回答「慢在哪」与「花了多少」）
curl -s 'localhost:8080/_glasshouse/trace/<traceId>?cost=true'
```

真实输出：

```
traceId=4bf92f3577b34da6a3ce929d0e0e4736
spans=5, modelCalls=2, tokensIn=2400, tokensOut=680
invoke_agent planner 1900ms  ¥0.0000  tenant=vip
  └─ chat deepseek-chat 1500ms  ¥0.0080  (in 1200/out 340)  tenant=vip
  └─ retrieval kb_lookup 80ms  ¥0.0000
  └─ execute_tool ticket_create 320ms  ¥0.0000
  └─ chat qwen-max 900ms  ¥0.0210  (in 1200/out 340)

slowestSpan=invoke_agent planner durationMs=1900
totalCostCny=0.0290
```

**没有装任何可视化后端。** 这就是「零锁定」的含义：链路存在内存里，`curl` 就能看。
需要接 Jaeger / Tempo / Grafana 时配一个端点即可：

```yaml
llm.glasshouse:
  otlp-endpoint: http://localhost:4318/v1/traces
```

---

## 零侵入是怎么做到的 / How zero intrusion works

不用写任何埋点代码。Spring 容器创建 `ChatModel` Bean 之后，`ChatModelDecoratorBeanPostProcessor`
会把它替换成装饰器，业务侧注入到的仍然是 `ChatModel` 接口：

```
业务代码 ──> ChatClient ──> ChatModel（被装饰）──> 你的模型
                                    │
                                    └── 自动产生 span + 计算成本 + 记入五维归因
```

Agent 步骤加个注解，检索/工具调用就自动进链路：

```java
@AgentStep(agentId = "planner", stepId = "plan", stepSeq = 0, feature = "customer-service")
public String plan(String question) {
    return chatClient.prompt().user(question).call().getContent();
}

@Retrieval(retriever = "kb_lookup", dataSourceId = "faq-zh-v1")
public String lookup(String question) { ... }

@ToolCall(toolName = "ticket_create", stepId = "draft")
public String write(String question) { ... }
```

产出的 span 名符合 SPN-001 契约：`invoke_agent planner` / `retrieval kb_lookup` /
`execute_tool ticket_create` / `chat deepseek-chat`。

---

## 五维成本归因 / Five-dimension cost attribution

同一份成本，六个维度都能下钻（PRD P0-3）：

```bash
java -jar glasshouse-cli.jar cost --by tenant --input spans.jsonl
java -jar glasshouse-cli.jar cost --by bizLine --input spans.jsonl
java -jar glasshouse-cli.jar cost --by feature --input spans.jsonl
java -jar glasshouse-cli.jar cost --by promptVersion --input spans.jsonl
java -jar glasshouse-cli.jar cost --by agentStep --input spans.jsonl
java -jar glasshouse-cli.jar cost --by model --input spans.jsonl
```

```
归因维度：租户
总调用：5  总 token：3080  总成本：¥0.0471  计量可信率：100.00%

租户                                调用数        token          成本(¥)       占比
----------------------------------------------------------------------------
未标注                                 3         1540         0.0444   94.32%
vip                                    2         1540         0.0027    5.68%
```

**估算值永远标出来。** 供应商没返回 usage 时走估算器，`swy.usage.source=ESTIMATED`、
`reasoningEstimated=true`，报表里「计量可信率」会告诉你这个数字有多可信 ——
不会把估算值伪装成真账。

---

## CI 回归门禁 / CI regression gate

**这是本项目最容易带来 star 的功能。** 「改 Prompt 到底是变好还是变坏」以前只能靠主观感受，
于是要么不敢改、要么盲改。门禁把答案交给 CI：

```yaml
# .github/workflows/ci-gate.yml
- run: |
    java -jar glasshouse-cli.jar eval run \
      --dataset customer-service-v1 \
      --prompt-version "${{ github.sha }}" \
      --gate "keywordRecall>0.95,containment=1.0,accuracy>0.85" \
      --out report/
```

**退出码契约**（Action 唯一依赖的东西）：

| 退出码 | 含义 | CI 表现 |
|---|---|---|
| `0` | 通过 / 未配门禁 | 绿色 ✓ |
| `1` | 门禁未达标 | **红色 ✗，流水线变红** |
| `2` | 参数或配置错误 | 红色 ✗ + 提示改命令 |

区分 `1` 和 `2` 是刻意的：门禁不达标时 CI 日志要写「指标不达标」，而不是「命令写错了」——
否则使用者会去调阈值，而真正的问题是 `--gate` 的表达式打错了。

**默认零费用零 API Key。** 不指定 `--base-url` 时走**录制回放**模式：
用提交进 git 的真实模型响应重放，同一 commit 跑两次分数完全一致 —— 这是门禁可信的前提。
模型输出有随机性，如果每次都调真实模型，门禁会随机变红，团队很快就会开始无视它。

```bash
# 换模型/换 Prompt 后重新录制
java -jar glasshouse-cli.jar eval run \
  --dataset customer-service-v1 \
  --base-url https://api.deepseek.com \
  --out eval-datasets/   # 录制的响应会被提交进 git
```

内置 **35 条自撰中文评测用例**（客服 20 + 信贷 15），每条都带 `authored` 出处：

```
$ java -jar glasshouse-cli.jar eval list
可用评测集（2 个）：
  - customer-service-v1         20 条用例（自撰 20）  版本=a3f9b21c4e5d
  - credit-risk-v3              15 条用例（自撰 15）  版本=a3f9b21c4e5d
```

---

## 国产模型计量口径 / Chinese LLM usage normalization

**差异化支柱。** 五家国产模型的 usage 字段位置各不相同，我们逐个对齐：

| providerId | 输入 token | 输出 token | 推理 token |
|---|---|---|---|
| `dashscope` 通义 | `input_tokens` | `output_tokens` | `reasoning_tokens` |
| `zhipu` 智谱 | `prompt_tokens` | `completion_tokens` | *（无字段，标 `reasoningEstimated=true`）* |
| `deepseek` | `prompt_tokens` | `completion_tokens` | *（无字段，不编造数字）* |
| `moonshot` Kimi | `prompt_tokens` | `completion_tokens` | *（无字段）* |
| `doubao` 豆包 | `prompt_tokens` | `completion_tokens` | `completion_tokens_details.reasoning_tokens` |

```bash
java -jar glasshouse-cli.jar prices support
```

**没有的字段就标估算，绝不编造。** DeepSeek-R1 这类把推理混在输出里的模型，
`reasoningTokens=0` 且 `reasoningEstimated=true` —— 数据里能看出真假。

---

## 价格表热更新 / Hot-reload pricing

改价格不改代码、不重启：

```bash
# 1. 编辑 prices/glasshouse-prices.yml（可挂载到容器外部路径）
# 2. 校验
java -jar glasshouse-cli.jar prices validate --file prices/glasshouse-prices.yml

# 3. 看变化：哪条改了、从多少到多少
java -jar glasshouse-cli.jar prices reload --file prices/glasshouse-prices.yml
#   ~ 变更 deepseek/deepseek-chat 输入 0.0020 → 0.0025，输出 0.0008 → 0.0008（生效日 2026-06-01）
```

**一条坏数据不会让整表失效。** 单条跳过并告警，其余照常生效 —— 但跳过的条目会让对应模型成本记 0，
所以 `prices validate` 的退出码是 1 而不是 0，CI 立刻知道。

价格来源区分 `OFFICIAL` / `CUSTOM` / `ESTIMATED`，`ESTIMATED` 的条目 UI 必须标注。

---

## 配置 / Configuration

全部可选，不配也能跑。

```yaml
llm.glasshouse:
  enabled: true                        # 总开关
  application-name: my-app
  sample-ratio: 1.0                   # 0~1，生产建议 0.1~0.3

  ring-buffer-capacity: 100000         # 内存 span 缓冲（架构 Q-D 拍板：默认 10 万条）
  # 写满后丢弃最旧的（观测数据可以丢，断链不行），丢弃数通过 /actuator/health 暴露

  redact-pii: true                     # PII 脱敏，默认开
  redact-keep-tail: 4                  # 手机号等保留后 4 位便于人工核对
  record-payload-full: false           # 完整 payload 留痕，默认关
  payload-max-length: 4000

  pricing-reload-seconds: 300          # 价格表热更新轮询间隔

  otlp-endpoint: http://localhost:4318/v1/traces   # 配了就导出，不配只落内存

  attribution-max-keys: 20000
```

---

## 模块结构 / Modules

```
llm-glasshouse/
├── glasshouse-core/                   ★ 零 Spring 依赖（enforcer 强制）
│   ├── usage/                         Usage 模型 + 五家国产口径归一化
│   ├── pricing/                       价格表 + 热更新 + 成本计算（scale=8）
│   ├── attribution/                   五维成本归因 + 六维下钻
│   ├── telemetry/                     SPN-001 span 命名 + GenAI/swy 属性
│   ├── trace/                         W3C TraceContext 编解码
│   ├── audit/                         PII 脱敏
│   └── eval/                          评测引擎 + 4 指标 + 门禁表达式
│
├── glasshouse-spring-boot-starter/    自动装配 + ChatModel 装饰器 + 三个注解切面
├── glasshouse-eval/                   数据集版本化 + 报告输出 + 基线对比
├── glasshouse-cli/                    trace / cost / eval / prices 四个命令
├── glasshouse-demo/                   零 API Key 演示应用
└── contract/contract-v1.json          ★ 三仓共享契约（唯一真源）
```

依赖方向严格单向：`cli → eval → starter → core`。
**core 不依赖 Spring**（`maven-enforcer` 的 `bannedDependencies` 强制），任何依赖在 core 里出现都会让构建失败。

---

## 三仓契约 / Shared contract

三个仓库（`llm-switchyard` / `agentswitchyard` / `llm-glasshouse`）**不共享 jar，只共享一份 JSON**：

```
contract/contract-v1.json
├── conventions            字段风格、币种、精度、span 命名模板
└── contracts
    ├── CTX-001  TraceContext              传播头格式、InheritableThreadLocal 约定
    ├── USG-001  Usage                    totalTokens 不变量、字段映射、误差门槛
    ├── PRC-001  PriceEntry + CostCalc    成本公式、scale=8、热更新开关
    ├── EVT-001  AuditEvent               禁止 unknown 占位
    ├── ERR-001  ErrorCode                枚举 + HTTP 状态码 + retryable
    └── SPN-001  span 命名与属性           ★ 本仓定义，另两仓遵循
```

任何一方改了字段名而没同步契约（或反之），**对方仓的 CI 会红** —— 本仓的执行手段是
`ContractSchemaTest`（逐字段比对 name / type / enum / pattern）。

**语义约定锁 1.41.0**：1.42.0 起 GenAI 属性迁出主仓且状态仍是 development，
所以本项目统一用 1.42.0 之后的新属性名（`gen_ai.usage.input_tokens`），
同时冗余旧名（`gen_ai.usage.prompt_tokens`）一个发布周期，保证存量看板不炸。

---

## 本地开发 / Local development

```bash
mvn clean install                 # 全量构建 + 覆盖率门禁
mvn -Pfast clean install          # 跳过覆盖率与 spotless（快速迭代）
mvn -pl glasshouse-core verify    # 单模块
```

### ⚠ 两个必须知道的坑

**1. 仓库路径不能含中文（Windows 上）**

JaCoCo agent 在 Windows 上遇到非 ASCII 路径会**静默失败** —— 不报错、不生成 `.exec`，
只在 report 阶段打印一句 `Skipping JaCoCo execution due to missing execution data file`，
结果就是「测试全绿但覆盖率门禁形同虚设」。

```bash
# 如果你的路径含中文（本仓上游就是这种情况），复制到纯 ASCII 路径再构建：
cp -r <repo> /d/tmp/gh-ascii/llm-glasshouse && cd /d/tmp/gh-ascii/llm-glasshouse
```

**2. shade 插件必须 `combine.self="override"`**

`spring-boot-starter-parent` 给 shade 预置了 `<filters>` 配置，Maven 会把它与模块配置合并，
合并后 shade 在解析 transformer 列表时会把元素当成 `resource` 参数，直接报：

```
Cannot find 'resource' in class ...ManifestResourceTransformer
```

`glasshouse-cli/pom.xml` 里已加 `combine.self="override"` 并写了注释说明原因。

---

## 测试与质量 / Testing

```
glasshouse-core                    229 tests    81.73%   门禁 80%
glasshouse-spring-boot-starter      57 tests    70.15%   门禁 65%
glasshouse-eval                     45 tests    67.76%   门禁 65%
glasshouse-cli                      27 tests    66.48%   门禁 65%
─────────────────────────────────────────────────────────
TOTAL                              358 tests    74.02%   门禁 60%
```

`mvn clean verify` 一次跑完：编译 → 单测 → spotless → 覆盖率门禁 → shade。

---

## 已知限制 / Known limitations

诚实列出，不藏着：

- **`glasshouse-demo` 没有测试**，覆盖率门禁对它不生效（演示代码，非产品代码）
- **计量误差没有 10 万条真实流量实测**。契约要求 <0.5%，
  目前只做到公式层面（缓存不重复计费、scale=8 精度、推理按输出价回退）的单元测试覆盖。
  真实误差需要拿供应商账单比对，本环境拿不到数据。
- **Spring Boot 3.5 的开源支持已于 2026-06-30 结束**（最后开源版本 3.5.16，发布于 2026-06-25），
  同一天结束的还有 Spring Framework 6.2 / Spring Security 6.5 / Spring Cloud 2025.0 ——
  3.5 是 3.x 线的最后一个版本，没有 3.6 可以停留。

  但**这不等于「没人管」**：Tanzu Spring 商业支持（付费）持续到 **2032-06-30**，还有近 6 年。
  对必须留在 3.x 的团队，付费商业支持是可行路径。

  更重要的是本项目的架构让迁移成本可控：`glasshouse-core` 全层 68 个文件、**0 个 Spring 引用**
  （由 `maven-enforcer` 的 `bannedDependencies` 强制），Spring 依赖全部集中在
  `glasshouse-spring-boot-starter` 的 7 个文件里。迁 Boot 4.1 的改动因此被锁定在 starter 一层，
  业务代码不动 —— 完整决策记录见 [ADR-0005](docs/adr/0005-why-still-on-spring-boot-3-5.md)。
- **CLI 的 `--base-url` 真实端点路径需要真实 API Key**，未在本仓 CI 跑；
  回放模式与离线 trace/cost 路径已全部实测。
- **LLM-as-judge 指标首版不参与默认评测**（`LlmJudgeMetric` 接口已定义，判定恒为 0 并默认不启用 ——
  「因为没算分而通过」是最危险的失败模式）。

---

## 许可证 / License

Apache-2.0
---

<a id="english-readme"></a>

# llm-glasshouse · The LLM Glasshouse

**See it (span trees) · Explain it (five-dimension cost attribution) · Gate it (CI regression)**

Observability and evaluation infrastructure for Java AI applications.
Zero code intrusion, zero vendor lock-in, fully offline-capable.

## What problem it solves

| | Problem | How |
|---|---|---|
| **See it** | Which step is slow? Which step costs money? | Add the starter — zero code changes |
| **Explain it** | How does cost split by tenant / business line / feature / Prompt / agent step? | `glasshouse cost --by tenant` |
| **Gate it** | Did my Prompt change make things better or worse? | `glasshouse eval run --gate "..."`, CI exits 1 |

**The core constraint is zero code intrusion.** After you add the starter, every model call
automatically produces a span conforming to the [OpenTelemetry GenAI Semantic Conventions](https://opentelemetry.io/docs/specs/semconv/gen-ai/)
v1.41.0, carrying full token accounting, cost, and attribution dimensions.

## 30 seconds

```bash
mvn clean install
java -jar glasshouse-demo/target/glasshouse-demo-0.1.0-SNAPSHOT.jar

curl -s -X POST localhost:8080/demo/chat \
  -H 'Content-Type: application/json' \
  -d '{"question":"退货政策是什么？","tenantId":"vip"}'

curl -s 'localhost:8080/_glasshouse/trace/<traceId>?cost=true'
```

Actual output:

```
traceId=4bf92f3577b34da6a3ce929d0e0e4736
spans=5, modelCalls=2, tokensIn=2400, tokensOut=680
invoke_agent planner 1900ms  ¥0.0000  tenant=vip
  └─ chat deepseek-chat 1500ms  ¥0.0080  (in 1200/out 340)  tenant=vip
  └─ retrieval kb_lookup 80ms  ¥0.0000
  └─ execute_tool ticket_create 320ms  ¥0.0000
  └─ chat qwen-max 900ms  ¥0.0210  (in 1200/out 340)

slowestSpan=invoke_agent planner durationMs=1900
totalCostCny=0.0290
```

No visualization backend required. Point at an OTLP endpoint to export to Jaeger / Tempo / Grafana:

```yaml
llm.glasshouse:
  otlp-endpoint: http://localhost:4318/v1/traces
```

## Zero intrusion, mechanically

A `BeanPostProcessor` replaces every `ChatModel` bean with a decorator. Business code keeps
injecting the same `ChatModel` interface — nothing changes on your side:

```
business code ──> ChatClient ──> ChatModel (decorated) ──> your model
                                        │
                                        └── span + cost + attribution, automatically
```

Annotate agent steps to get tool/retrieval spans for free:

```java
@AgentStep(agentId = "planner", stepId = "plan", stepSeq = 0, feature = "customer-service")
public String plan(String question) { ... }

@Retrieval(retriever = "kb_lookup", dataSourceId = "faq-zh-v1")
public String lookup(String question) { ... }
```

## CI regression gate

```yaml
- run: |
    java -jar glasshouse-cli.jar eval run \
      --dataset customer-service-v1 \
      --gate "keywordRecall>0.95,containment=1.0,accuracy>0.85" \
      --out report/
```

Exit codes are the contract:

| Code | Meaning | CI |
|---|---|---|
| `0` | Passed / no gate configured | green ✓ |
| `1` | Gate failed | **red ✗ — pipeline fails** |
| `2` | Bad arguments or configuration | red ✗ + tells you to fix the command |

Separating `1` from `2` is deliberate: on a gate failure the log must say "metric below
threshold", not "command malformed" — otherwise people tune the threshold when the real
problem is a typo in `--gate`.

**Zero cost, zero API key by default.** Without `--base-url`, evaluation runs in
**recorded-replay** mode: real model responses committed to git, replayed deterministically.
Two runs of the same commit score identically — that's what makes the gate trustworthy.
Model output is stochastic; a gate that calls a live model turns red at random and teams
quickly learn to ignore it.

Shipped with **35 self-authored Chinese evaluation cases** (20 customer service + 15 credit risk).

## Chinese LLM usage normalization

Our differentiator. The five domestic providers put usage fields in different places:

| providerId | input tokens | reasoning tokens |
|---|---|---|
| `dashscope` Qwen | `input_tokens` | `reasoning_tokens` |
| `zhipu` GLM | `prompt_tokens` | *absent → `reasoningEstimated=true`* |
| `deepseek` | `prompt_tokens` | *absent — we never invent numbers* |
| `moonshot` Kimi | `prompt_tokens` | *absent* |
| `doubao` | `prompt_tokens` | `completion_tokens_details.reasoning_tokens` |

```bash
java -jar glasshouse-cli.jar prices support
```

## Hot-reload pricing

Change prices without a code change or a restart:

```bash
java -jar glasshouse-cli.jar prices validate --file prices/glasshouse-prices.yml
java -jar glasshouse-cli.jar prices reload   --file prices/glasshouse-prices.yml
#   ~ changed deepseek/deepseek-chat input 0.0020 → 0.0025 (effective 2026-06-01)
```

A single malformed entry never invalidates the whole table — it's skipped with a warning.
But skipped entries make that model's cost read 0, so `validate` exits 1 rather than 0 so CI notices.

## Modules

```
llm-glasshouse/
├── glasshouse-core/                   ★ zero Spring dependency (enforcer-enforced)
│   ├── usage/                         Usage model + 5-provider normalization
│   ├── pricing/                       Price table + hot reload + cost (scale=8)
│   ├── attribution/                   Five-dimension cost attribution
│   ├── telemetry/                     SPN-001 span naming + GenAI/swy attributes
│   ├── trace/                         W3C TraceContext codec
│   ├── audit/                         PII redaction
│   └── eval/                          Eval engine + 4 metrics + gate expressions
├── glasshouse-spring-boot-starter/    Auto-config + ChatModel decorator + aspects
├── glasshouse-eval/                   Dataset versioning + reports + baseline diff
├── glasshouse-cli/                    trace / cost / eval / prices
├── glasshouse-demo/                   Zero-API-key demo app
└── contract/contract-v1.json          ★ shared contract (single source of truth)
```

Dependencies flow one way: `cli → eval → starter → core`.
**core must not depend on Spring** — the `bannedDependencies` enforcer rule fails the build if it does.

## Shared contract

The three repositories share **no jars, only one JSON file**:

```
contract/contract-v1.json
├── conventions            naming style, currency, precision, span template
└── contracts
    ├── CTX-001  TraceContext          propagation headers, InheritableThreadLocal
    ├── USG-001  Usage                 totalTokens invariant, field mappings, error gate
    ├── PRC-001  PriceEntry + Cost     cost formula, scale=8, hot-reload flag
    ├── EVT-001  AuditEvent            "unknown" placeholders forbidden
    ├── ERR-001  ErrorCode             enum + HTTP status + retryable
    └── SPN-001  span naming/attrs     ★ defined here, followed by the other two
```

If anyone changes a field name without updating the contract, **CI in the other repos goes red**.
This repo enforces it with `ContractSchemaTest` (field-by-field name/type/enum/pattern comparison).

Semantics are pinned to **1.41.0**: from 1.42.0 GenAI attributes moved out of the main repo and
remain `development` status, so we use the new names (`gen_ai.usage.input_tokens`) while
redundantly writing the legacy names (`gen_ai.usage.prompt_tokens`) for one release cycle,
so existing dashboards don't break.

## Two gotchas you will hit

**1. Repo paths must not contain non-ASCII characters (Windows)**

The JaCoCo agent **silently fails** on non-ASCII paths — no error, no `.exec` file, just a
`Skipping JaCoCo execution due to missing execution data file` message, which means "tests green
but the coverage gate is decorative".

```bash
cp -r <repo> /d/tmp/gh-ascii/llm-glasshouse && cd /d/tmp/gh-ascii/llm-glasshouse
```

**2. The shade plugin needs `combine.self="override"`**

`spring-boot-starter-parent` pre-configures `<filters>` for shade; Maven merges that with your
module config, and the merged result makes shade misparse the transformer list as a `resource`
parameter. See `glasshouse-cli/pom.xml` for the annotated fix.

## Tests and coverage

```
glasshouse-core                    229 tests    81.73%   gate 80%
glasshouse-spring-boot-starter      57 tests    70.15%   gate 65%
glasshouse-eval                     45 tests    67.76%   gate 65%
glasshouse-cli                      27 tests    66.48%   gate 65%
─────────────────────────────────────────────────────────
TOTAL                              358 tests    74.02%   gate 60%
```

`mvn clean verify` runs: compile → tests → spotless → coverage gate → shade.

## Known limitations

Listed honestly rather than hidden:

- **`glasshouse-demo` has no tests** and is exempt from the coverage gate (demo code, not product code)
- **No 100k-traffic real-world measurement-error benchmark.** The contract requires <0.5%;
  we cover the *formula* with unit tests (cache not double-billed, scale=8 precision,
  reasoning falls back to output price). Real error needs actual vendor bills, unavailable here.
- **Spring Boot 3.5's open-source support ended on 2026-06-30** (last OSS release 3.5.16,
  shipped 2026-06-25). The same day also saw Spring Framework 6.2, Spring Security 6.5 and
  Spring Cloud 2025.0 go EOL — 3.5 was the last release of the 3.x line, so there is no 3.6 to
  slide onto.

  This does **not** mean "nobody maintains it": Tanzu Spring commercial support (paid) runs to
  **2032-06-30**, nearly six more years. Teams that must stay on 3.x have a supported path.

  And this project's architecture keeps migration cheap: across all 68 files in
  `glasshouse-core` there are **zero Spring references** (enforced by `maven-enforcer`'s
  `bannedDependencies`); every Spring dependency lives in the 7 files of
  `glasshouse-spring-boot-starter`. Moving to Boot 4.1 is therefore confined to the starter —
  business code is untouched. Full decision record:
  [ADR-0005](docs/adr/0005-why-still-on-spring-boot-3-5.md).
- **The CLI's `--base-url` live-endpoint path needs a real API key** and isn't exercised in CI;
  replay mode and offline trace/cost paths are fully tested.
- **LLM-as-judge does not participate in default evaluation.** `LlmJudgeMetric` is defined but
  scores 0 and stays disabled by default — "passing because nothing was computed" is the most
  dangerous failure mode there is.

## License

Apache-2.0
