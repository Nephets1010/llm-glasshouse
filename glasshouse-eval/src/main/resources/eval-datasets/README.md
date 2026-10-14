# 内置评测集贡献指南

LLM 玻璃房内置两套中文评测集。它们的首要目标是**可复现且无争议**，不是规模。

---

## 为什么是 JSONL 文件而不是数据库

评测集进 git 才能做到三件事，缺一不可：

1. **PR diff 友好** —— 改一条用例只产生一行 diff，review 能看清改了哪条、为什么改
2. **CI 可复现** —— `git checkout <sha>` 之后评测结果完全一致，因为数据集版本就是 commit sha
3. **版本化免费** —— 不需要额外做版本表与冲突合并

数据库版本化与界面化 CRUD 放 P1。

---

## 硬性要求

每条用例**必须**包含以下四个字段，缺一不可（由 `JsonlDatasetParser` 与 `ContractSchemaTest` 双重校验）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 数据集内唯一，建议 `{数据集前缀}-{三位序号}`，如 `cs-001` |
| `input` | object | 非空。至少含 `question`（渲染 Prompt 用） |
| `expected` | object | 非空。至少含下列之一 |
| `metadata` | object | **必须含 `authored` 或 `source`**，否则解析直接失败 |

### `expected` 支持的键

| 键 | 类型 | 语义 | 对应指标 |
|---|---|---|---|
| `contains` | string[] | 输出中必须出现的关键词（全部命中才算满分） | `keywordRecall` |
| `exact` | string | 与输出完全一致（忽略空白与全角/半角差异） | `accuracy` |
| `forbidden` | string[] | 输出中<b>不得</b>出现的内容，命中任一即 0 分 | `containment` |
| `requiredFields` | string[] | JSON 输出必须包含的字段名 | `jsonSchema` |
| `fieldTypes` | object | 字段名 → 类型名（`string`/`number`/`integer`/`boolean`/`object`/`array`） | `jsonSchema` |

### `metadata` 支持的键

| 键 | 必填 | 说明 |
|---|---|---|
| `authored` | 二选一 | 自撰标记，如 `"llm-glasshouse 自撰"` |
| `source` | 二选一 | 公开来源，如 `"《XX管理办法》第 X 条（改写）"` |
| `scenario` | 建议 | 场景分类，用于报告分组 |
| `difficulty` | 建议 | `easy` / `medium` / `hard` |
| `language` | 建议 | 语言标记 |

---

## 数据出处与授权（重要）

**禁止无出处数据。** 这是 CI 门禁能成立的前提：一份来源不明的评测集，评审无法复核，
它的分数也就不能用来论证「这次改动没有让效果变差」。

- 自撰用例：`metadata.authored` 写明 `"llm-glasshouse 自撰"`
- 改写自公开资料：`metadata.source` 写明原始出处 + 改写说明
- **不要**直接复制他人评测集的用例内容

若你的用例涉及具体企业的真实业务数据，请先脱敏，并在 `metadata.source` 说明脱敏方式。

---

## 一条写得好的用例长什么样

```jsonl
{"id":"cs-021","input":{"question":"预售商品什么时候发货？"},"expected":{"contains":["预售","发货"],"forbidden":["已发货"]},"metadata":{"authored":"llm-glasshouse 自撰","scenario":"售前-预售","difficulty":"medium","language":"zh-CN"}}
```

它满足三个条件：

1. **问题具体** —— 有具体业务场景，不是「介绍一下你们的产品」
2. **期望可判定** —— `contains` 是确定性子串匹配，不依赖模型判断，因此 CI 结果可复现
3. **有反例** —— `forbidden` 写出「不该出现什么」。这一步能挡住「说得客套但没回答问题」这类
   真实的失败模式，而不只是检查有没有命中关键词

---

## 内置数据集

| 文件 | 用例数 | 场景 |
|---|---|---|
| `customer-service-v1.jsonl` | 20 | 电商客服：售后、物流、发票、账户、营销 |
| `credit-risk-v3.jsonl` | 15 | 信贷风控：准入、征信、反欺诈、成本测算 |

合计 **35 条自撰用例**。首版不追求规模，只求每条可复现、无争议、能真正挡住退化。

---

## 贡献流程

```bash
# 1. 编辑或新增 eval-datasets/*.jsonl

# 2. 本地跑一遍，确认可复现
./mvnw -pl glasshouse-eval test
java -jar glasshouse-cli/target/glasshouse-cli.jar eval run \
  --dataset customer-service-v1 \
  --gate "keywordRecall>0.95,containment=1.0"

# 3. 提 PR。CI 会用 ci-gate.yml 跑同一份评测集
```

新增数据集只需在 `eval-datasets/` 下放一个 `.jsonl` 文件，无需改任何代码。
