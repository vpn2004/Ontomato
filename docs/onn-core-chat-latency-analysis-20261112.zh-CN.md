# 跨关系问数时延分析

日期：2026-11-12。基于 108 已部署版本的同一次浏览器测试、Java 日志、Workbench 日志和源码核对。本轮仅分析，没有修改源码、服务配置或 onn-core 目录。

## 结论与计时口径

115.36 秒是浏览器提交到 SSE 连接结束，包含答案完成后的质检等待。服务端主答案在约 54.92 秒已完成；随后质检订阅等待约 60.00 秒，达到上限后结束为 unsettled。不能将 115 秒都解释为查询生成或数据库执行。

本题可归属的七次成功读取，其 adapter timingMs.total 合计约 103.05 毫秒。该计时包括 adapter 内的远端读取和残余求值，不是纯数据库 CPU 时间；不覆盖全部模型、Python、前后端网络及管理 PG 操作。这个数量级足以说明本题的主要时延不在 ONN 业务数据读取，但不构成大数据量或其他图查询的性能保证。

## 同一次请求的时间线

服务端时区换算为 UTC+8，浏览器耗时多出约 0.45 秒属于测量边界差异。

| 阶段 | 边界 | 约耗时 | 说明 |
|---|---|---:|---|
| 初始规划与进入查询 Agent | 17:38:35.810 → 17:38:38.495 | 2.69 秒 | 包含业务上下文读取、任务规划、路由 |
| Agent 规划、探查、修正、Python 验证及提交 | 17:38:38.495 → 17:39:18.149 | 39.65 秒 | 包含七次已记录成功查询、两次语法拒绝；不是纯模型推理计时 |
| Agent 结束到队列结果发送 | 17:39:18.149 → 17:39:22.587 | 4.44 秒 | 源码存在 1 秒 Future 等待加 10 秒休眠轮询，与本次等待相符 |
| 两个数据集的字段展示转换 | 17:39:22.588 → 17:39:26.638 | 4.05 秒 | 分别约 1.66 和 2.39 秒，当前每个非空表调用一次 fieldAdapter 模型 |
| 答案整理与主工作流完成 | 17:39:26.639 → 17:39:30.726 | 4.09 秒 | 包含 polishHarnessAnswer 模型调用及结果保存 |
| 等待质检、达到等待上限 | 17:39:30.714 → 17:40:30.719 | 60.01 秒 | 与上一段交叠约 12 毫秒；最终状态 unsettled |

七次成功读取按发生顺序：

| 用途 | adapter 总耗时 |
|---|---:|
| 确认客户编号 | 10.94 ms |
| 修正后的客户条件探查 | 34.58 ms |
| 无条件小样本探查 | 14.27 ms |
| 去重产品明细 | 10.68 ms |
| 去重产品计数 | 12.94 ms |
| Python 明细程序验证中的读取 | 10.11 ms |
| Python 计数程序验证中的读取 | 9.54 ms |

两次拒绝分别发生在 17:38:48.189 和 17:38:51.127，都是 conditions.properties 被写成数组；17:38:56.976 已改成合法谓词对象。拒绝本身是毫秒级，后续模型思考、探查和重写才放大耗时。无法仅凭这些日志将整个修复间隔全部归因于错误重试。

## 质检为什么又等一分钟

Java 在 17:39:22 左右启动后台质检，先串行解释两个 Python 程序，再调用 question-checker：

- 程序 0 说明返回：17:39:46.244。
- 程序 1 说明返回：17:40:06.053。
- 质检评审返回：17:40:33.851，已晚于本次前端订阅的 60 秒上限。

Workbench 在主答案完成后注册该质检等待；chat 路由虽然已经发送 workflow_complete，仍 await pendingLateFacts.settleAll()，之后才发送 DONE。浏览器在 DONE 才释放主要 loading 状态。因此答案可以先显示，但用户仍感到本轮未结束。

embedding 配置缺失也产生知识检索、MetricView 和质检后续流程错误；本次质检至少存在实质的多次模型调用，不能把整整 60 秒简单归因于 embedding 配置。

另一个独立问题：CheckController 的 queryBySessionIdV2 在 while(true) 内连续查询 checkServiceV2 → CheckDaoV2 → PostgreSQL，没有 sleep、退避或明确的服务端期限。每个请求还创建新的单线程 executor，没有在该方法中显式关闭。客户端超时不应被假定等价于后台任务被取消。这可能放大管理 PG 与线程负担，但本轮没有测量该轮询 QPS 或资源占比，不能据此宣称 PG 已成为主瓶颈。

## 建议的优化顺序

### P0：答案完成与后台质检分离

答案通过必要的确定性检查后，完成本轮回答并释放输入；质检作为独立、可跟踪的后台任务继续执行，结果持久化后单独更新界面。保留运行、失败、超时、取消状态和任务归属，避免把“未质检”显示为“质检通过”。权限、筛选正确性、截断/完整性等确定性校验仍应在发布答案前完成。

这能去掉本次约 60 秒的回答收尾等待，但不会让质检本身变快，也不能通过简单删掉 await 留下无归属的异步任务。

### P0：修正两处结果等待机制

- ABCHarness 的消息队列改为可唤醒的阻塞等待/事件通知，结果或 Agent 完成时立即发送，保留取消和错误清理。
- 质检状态改成通知机制；过渡期采用有上限、可取消、有退避的轮询，并使用受控 executor。避免连续 SELECT 和连接断开后继续无边界等待。

### P1：减少 Agent 的试错与工具往返

- 提供与 adapter 一致的结构化 DSL schema、过滤/逻辑组合/关系查询的精确示例；发送数据请求前编译验证，错误带 JSON 字段路径和期望类型。
- 对相同无效计划做指纹识别并限制重复修复，不能静默删除筛选条件。
- 简单计数、筛选、排序、关系明细走结构化查询执行路径；只有确实需要复杂后处理时生成 Python。统一复用权限、来源、完整性与执行证据入口。
- 同一完整去重结果可复用来计算数量，但必须确认无截断、非空计数口径以及数据版本一致；不能把当前页行数当成总数。
- 已有 outputCode 在权限一致时复用测试执行结果，不宜再把“缓存测试结果”当成尚未实现的全新优化。跨权限或跨请求缓存需要独立设计。

### P1：减少展示环节的模型调用

优先从本体字段和 outKeyRefs 获取中文名称、单位及类型；确定的字段使用规则生成展示计划，缓存按 schema 版本和语言隔离。只对含义不明的派生字段调用模型。本题两个单列表为展示耗费约 4 秒。

简单事实答案可采用确定性摘要；复杂解释保留润色模型。主规划模型、字段转换、答案整理、质检不必统一采用相同速度和推理强度，是否切换需用正确率与时延一起验收。

### P2：优化后台质检和可复用查询

独立程序描述可以限流并行或合并处理；权限/空值/完整性先做确定性检查，再让模型评审业务口径。补齐 embedding 配置并验证维度，让知识/MetricView 检索的复用路径真正可用。采用参数化、已验证的 MetricView/计划复用时，仍重新执行当前权限下的数据查询，不直接复用陈旧答案。

## 下一轮验收指标

至少记录 firstDataMs、answerReadyMs、qcFinishedMs/状态、streamClosedMs，以及模型每次调用/首 token/输出 token、工具次数/失败次数、DSL 编译与执行、Python、展示转换、PG 保存和排队时延。沿统一 traceId 关联 Java、Node 和 Core；分别报告用户等待和后台成本。

相同问题在同一模型配置下逐条串行、多轮执行，报告中位数和高分位，保持正确性、权限和完整性基准。本次质量简单计数与关系题有部分时间重叠，不能把两个单次时间差当成可稳定复现的提速比例。

当前应优先迭代 Ontomato 的 Agent/调度/质检路径；没有证据表明需要先升级 ONN Core 才能消除这 115 秒等待。

## 关键代码位置与证据

- apps/workbench 的实际实现位于 packages/workbench-server；packages/workbench-server/src/api/routes/chat.ts 第 280 行等待 late facts。
- packages/workbench-server/src/services/data-query/late-fact.ts 第 23 行定义 60 秒上限。
- packages/data-engine-core/src/main/java/io/ontomato/dataengine/service/impl/ABCHarnessServiceImpl.java 第 206 行休眠 10 秒。
- packages/data-engine-core/src/main/java/io/ontomato/dataengine/controller/CheckController.java 第 43 行起的 V2 质检接口连续轮询 PG。
- packages/workbench-server/src/services/data-query/adapter.ts 的 createFieldDisplayPlan 为表字段调用模型；branches/abc-harness-answer.ts 执行答案润色。
- packages/data-engine-core/src/main/java/io/ontomato/dataengine/service/impl/CheckServiceV2Impl.java 第 98 行起执行串行质检。

原始私有日志与分解结果：/Users/weichen/.codex/artifacts/ontomato-query-planning-deploy-20261112/workbench-timeline.private.log、quality-runtime.private.log、latency-breakdown.private.json；不将原始业务内容加入 Git。
