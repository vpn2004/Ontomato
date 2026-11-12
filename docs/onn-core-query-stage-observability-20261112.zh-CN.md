# v4.1.4 问数耗时观测与 DSL 响应诊断

## 本轮范围

在已推送 vpn2004/Ontomato 的 `feature/onn-core-v4.1.4` 基线上进行。本轮补齐定位问题所需的日志，不宣称已消除模型等待或后台质检等待。没有修改 ONN Core 代码、业务权限、过滤或完整性判定。

- 修复节点执行完成后才计时的问题。每次工作流持有独立计时记录，query/analysis/visualization 的执行事件保存真实起止时间和单调时钟耗时；节点返回 errors 不再标成 completed。计时信息不进入 LangGraph checkpoint。
- `query-timing` 日志覆盖 `workflow.*`、`planner.schema/knowledge/model.prepare/model.initial/model.repair`、`query.branch.*`、`harness.stream/result.prepare/answer.generate`。取消、异常、空结果和降级分别记录；日志关联已有 requestId/turnKey/threadId/requestSeq。
- Harness 记录首事件、首数据、答案首 token。`harness.stream` 包含后端规划/执行以及客户端逐帧处理，不能等同数据库耗时；嵌套阶段也不能直接相加。模型 SDK 内部重试仍包含在单次 model 阶段内。
- DSL 诊断读取响应的显式 `dsl` 字段，检查已知 envelope、graph.patterns、过滤谓词对象、关系端点和聚合容器。仅记录 JSON 路径及期待类型，不记录过滤值，不把 Python code 当作 DSL。未知协议、未返回 DSL、遍历截断分别可见。
- 诊断在响应到达后发生，不是执行前拦截，也不更改原响应或证明查询语义正确。引擎的既有验证仍负责拒绝非法查询；若 Harness 不返回 DSL，客户端无法诊断其内部重试。执行前有限修复、Java 通知唤醒和持久化后台质检仍是后续工作。

## 本地验证

- `pnpm typecheck:workbench` 通过。
- `pnpm lint:workbench` 无错误；既有 skill-manager.ts 未使用 registry 警告保留。
- 七个定向测试文件共 41 项通过：新增计时/DSL 诊断 15 项，结果完整性、来源窗口、流内容、数据集定位、答案/质检计时回归 26 项。
- 计时测试包含真实 LangGraph 流节点、并发 collector、错误/取消、墙钟回退和观测回调失败。测试未调用真实模型和业务数据库。

## 部署验证安排

先在 108 质量追溯 Workbench 旁路端口验证，复制管理数据库并停用副本的任务调度；沿用既有质量引擎及 ONN 数据，只运行读取问题。通过真实问数、结果和历史记录核对后，再更新 3011 的 Workbench。保留原镜像和 Compose 回滚命令。本轮不变更引擎、Manager、物流入口或模型配置。

部署、真实耗时和验收结论在完成后补录。此处本地通过不代表线上提速验收通过。
