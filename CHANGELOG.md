<!--
  版权 2026 the llm-glasshouse authors.
  Licensed under the Apache License, Version 2.0.
-->

# Changelog

本文件记录 llm-glasshouse 的所有重要变更。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added（首版 T01-T05）

#### 基础设施
- 五模块 Maven 工程：`glasshouse-core` / `glasshouse-spring-boot-starter` / `glasshouse-eval` /
  `glasshouse-cli` / `glasshouse-demo`
- `maven-enforcer` 强制 **core 零 Spring 依赖**（`bannedDependencies`）
- 覆盖率门禁：core 80%，其余模块 65%，全仓 60%
- `spotless` 格式校验（Google Java Format AOSP 风格）
- `ci-gate.yml`：单测 + 覆盖率 + 契约校验 + **CI 回归门禁** + 报告 artifact + Job Summary

#### 契约（与 llm-switchyard / agentswitchyard 共享）
- `contract/contract-v1.json` 与三仓权威版本**逐字符一致**（仅 `javaType` 包名按仓区分）
- `ContractSchemaTest` 逐字段比对 name / type / enum / pattern，任一仓漂移即让对方 CI 变红

#### 计量（core/usage）
- `Usage` 模型：`totalTokens` 一律重算，构造器忽略上游给的值（USG-001 不变量）
- 缓存命中的输入 token 不重复计费
- **国产五家口径归一化**：通义 / 智谱 / DeepSeek / Kimi / 豆包
- 供应商不返回推理 token 时标 `reasoningEstimated=true`，**绝不编造数字**

#### 成本（core/pricing）
- 成本公式：`(input-cached)/1000×inputPrice + cached/1000×cachedPrice + output/1000×outputPrice + reasoning/1000×reasoningPrice`
- 金额全程 `BigDecimal`，内部 scale=8 / 展示 scale=4
- 价格表**热更新**：改 YAML 生效，无需改代码、无需重启
- 单条坏数据跳过并告警，不让整表失效

#### 归因（core/attribution）
- 五维成本归因：租户 / 业务线 / 业务功能 / Prompt 版本 / Agent 步骤（外加供应商、模型共七维可下钻）
- 滑动窗口聚合 + 数据质量指标（计量可信率、估算来源）

#### 埋点（starter）
- **零代码侵入**：`ChatModelDecoratorBeanPostProcessor` 包装所有 `ChatModel` Bean
- 三个注解产生符合 SPN-001 的 span：`@AgentStep` / `@ToolCall` / `@Retrieval`
- OTel GenAI 语义约定 **1.41.0**（新属性名 + 旧名冗余一个发布周期）
- 内存环形缓冲（默认 10 万条）+ PII 脱敏（默认开启）

#### 评测（eval）
- **CI 回归门禁**：退出码 `0` 通过 / `1` 未达标 / `2` 配置错误
- 默认**录制回放**模式：零 API Key、零费用、同一 commit 跑两次同分
- 四个确定性指标：`accuracy` / `keywordRecall` / `containment` / `jsonSchema`（外加 `cost`）
- 基线对比：逐用例 diff，能定位「哪几条变差了」
- **35 条自撰中文评测用例**（客服 20 + 信贷 15），每条带 `authored` 出处

#### CLI
- `glasshouse trace --tree --cost` —— span 树同时回答「慢在哪」与「花了多少」
- `glasshouse cost --by <dimension>` —— 离线成本对账（支持 table / json / csv）
- `glasshouse eval run|compare|list` —— CI 门禁
- `glasshouse prices show|validate|reload|support` —— 价格表运维

#### 演示（demo）
- 零 API Key：模型由进程内 mock 服务提供
- 三步客服 Agent（planner → chat + retrieval + tool + chat），span 树即 README 首屏截图

### Fixed（开发过程中由测试抓出的真实缺陷）

这些是本仓测试抓出的**真实 bug**，不是补测试时顺手写的：
- `endWithUsage` 内部提前 `span.end()`，导致其后的**所有 `swy.*` 属性被 OTel 静默丢弃**
  （五维成本归因在链路上拿不到任何数据）
- 流式 `streamUsageReported` 恒为 false / 恒为 true 两个方向都错过，
  等于把估算当真账
- `String.trim()` 会削掉占位符前缀的 NUL 字符，使内部缺失标记泄漏到对外输出
- SnakeYAML 把未加引号的 `effectiveFrom` 解析成 `java.util.Date`，
  导致内置价格表**全部条目**解析失败（成本静默记 0）
- `InMemoryMetricStore.aggregate` 未按查询维度合并，
  「按租户下钻」会被同一租户的多个聚合键拆成多行
- `BaselineComparator` 用「有没有跑挂」判断退化，
  **能正常返回但答案全错**的用例被判为未退化
- `@AgentStep` 注解的空值覆盖掉线程上继承的归因维度，五维归因少一维
- picocli `@ParentCommand` 拿到的只是直接父命令，多级子命令树整体初始化失败
- `shade` 插件在 `spring-boot-starter-parent` 配置合并下解析 transformer 失败
  （需要 `combine.self="override"`）

### Known Issues
- `glasshouse-demo` 无测试，覆盖率门禁对其不生效（演示代码）
- 未做 10 万条真实流量的计量误差实测（需真实供应商账单）
- Spring Boot 3.5 的**开源支持已于 2026-06-30 结束**（最后开源版本 3.5.16）；
  商业支持（Tanzu Spring，付费）持续至 2032-06-30。迁移 Boot 4.x 排期中 ——
  `glasshouse-core` 68 个文件 0 个 Spring 引用，改动锁定在 starter 的 7 个文件
- CLI `--base-url` 真实端点路径未在 CI 覆盖（需真实 API Key）
- LLM-as-judge 首版不参与默认评测（未接入时恒为 0，避免「因为没算分而通过」）
