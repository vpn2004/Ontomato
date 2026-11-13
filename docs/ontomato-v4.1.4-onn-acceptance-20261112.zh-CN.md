# Ontomato v4.1.4 + ONN adapter 验收记录

日期：2026-11-12

本轮将上游 Ontomato v4.1.4 与现有 ONN adapter、中文界面、关系查询、图探索、窗口证据和受控 Action 改造整合，并在 192.168.1.108 完成两套应用环境的替换验证。没有修改 `/Users/weichen/Documents/dev/onn-core` 的任何文件，也没有升级 ONN Core 三个服务。

## 版本与部署

- 上游基线：`ontomato-ai/Ontomato` v4.1.4，提交 `f6669a861362e0774365fc3f4b328ed3acd880a2`。
- 整合分支：`feature/onn-core-v4.1.4`。
- 整合提交：`48a8a42c`。
- 质量问数：<http://192.168.1.108:3011/#/chat>；Manager 使用 3007。
- 物流问数：<http://192.168.1.108:3010/#/chat>；Manager 使用 3006。
- 当前三类应用镜像均为 `4.1.4-onn-20261112`。
- 质量和物流业务数据仍由 ONN Core 提供；用户、权限、配置、对话、任务和执行证据仍由 PostgreSQL 管理。

部署前已保存管理库备份、Compose 配置、镜像和 JAR 校验信息以及两套回滚命令：
`/opt/ontomato-v414-upgrade-20261112/`。

## 通过的检查

- Java 构建、前后端类型检查、Manager/Workbench/脚本定向测试通过。
- data-engine-core 定向测试 112 项通过，ONN adapter 定向测试 151 项通过，应用引导测试 1 项通过。
- 质量和物流既有业务查询全部匹配既有 oracle/基准。
- ONN metadata ETag、能力声明、匿名访问拒绝、对象身份、来源窗口、7 跳查询、空窗口和图探索通过。
- 行级整行去重、去重后分页、关系明细到临时结果再 count、多步骤线性查询和不支持表达式的明确拒绝通过。
- 受控 Action 的同值幂等和越权拒绝通过。
- 旧 v4.1.1 引擎读取迁移后的管理库的回滚兼容验证通过。
- 真实 Manager 对象视图、关系编辑和实例图谱页面通过。

## 线上问数观测

本次真实网页验证均收到 `workflow_complete`，没有浏览器 page error 或失败请求：

| 问题 | 结果 | 单次流结束时间 |
| --- | --- | ---: |
| 质量追溯客户总数 | 42 | 42.83 秒 |
| 物流运单总数 | 800 | 41.14 秒 |
| CUST-001 经订单关联的产品序列号和去重数量 | 17 个，明细逐条匹配基准 | 115.36 秒 |

关系问题仍有约 115 秒端到端耗时。一次执行中模型把 `conditions.properties` 生成数组，协议要求谓词对象，因此发生两次 DSL 拒绝后才成功。新增行级 DISTINCT 和多步骤能力已经生效，但这组观测不能证明端到端性能已优于 PostgreSQL，也不能把耗时直接归因于 ONN Core 查询执行。

当前日志还显示 embedding endpoint 未配置，知识/MetricView 检索与校验会出现 `baseUrl cannot be null or blank`；结构化问数结果正确，但向量检索能力尚未完成配置验收。

## 后续优化顺序

1. 给规划提示补充关系过滤、逻辑组合和 `conditions.properties` 的精确 JSON 示例，并在发起查询前做结构诊断，减少模型生成错误后的重试。
2. 分阶段记录规划、工具调用、ONN 查询、Python 校验、答案生成和质检等待的耗时及失败次数，先确定 115 秒主要落在哪个阶段。
3. 在有完整证据后再做安全的临时步骤折叠、更多聚合下推和事件唤醒优化；每项先在隔离环境小规模验证，再部署 108。
4. 配置并验证 embedding endpoint 后，再单独验收知识检索和 MetricView 校验链路。

本轮没有进行持续压测、故障恢复演练或新的 ONN/PG 受控性能基准；也没有改变业务数据、模型配置或 ONN Core 服务。

## 2026-11-13 DSL 重试优化代码验收

针对上一轮 CUST-001 关系问数中 `conditions.properties` 数组形状错误、关系方向错误引发的 DSL 重试，本轮在 Ontomato 功能分支增加以下保护：

- ONN DSL 在适配器执行前统一校验属性谓词形状；数组结构会返回具体 DSL 路径和“查询未执行”说明。合法的 `operator: "logic"` 与 and/or/not 组合继续接受。
- ONN 关系类型未知、未导入或方向不匹配时，错误中列出 ontology 定义的方向和可用关系，便于 Agent 按已知 schema 修正。
- ONN 单次 Agent 调用最多允许连续 4 次失败 DSL 尝试；成功后清零。预算通过同步预留统计在并发工具调用下生效，其他 adapter 不受此限制。
- DSL 语法门通过后才发出 `m3.query.started`，因此前置拒绝不会伪装成已提交到 ONN 的查询。

定向验证：`DslGrammarValidatorTest` 9 项、`AgentCallContextTest` 1 项、`OnnCoreGraphCompilerTest` 6 项通过；`git diff --check` 通过。未运行会连接数据库或外部系统的全量 Java 测试。本节为本地代码验收记录，**尚未部署到 108，也未复测相同网页问数的端到端耗时**；部署后需复测 CUST-001，确认错误 DSL 在调用次数上受控，并按阶段日志判断剩余耗时归属。本轮没有修改任何 onn-core 目录内容。

后续部署状态：2026-11-13 已完成两套 108 引擎更新与网页复测，详见 [DSL 重试保护：108 部署与 Node 环境核查](ontomato-dsl-guard-108-deployment-20261113.zh-CN.md)。该记录同时说明关系题的质检超时限制。
