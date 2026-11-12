# 查询规划与 ONN adapter：108 部署验收

日期：2026-11-12。两套 Ontomato v4.1.1 已成对替换 engine-core 和 ONN adapter。保留原组件及完整 Compose 启动命令，支持整组回滚。本轮没有修改任何本地 onn-core 文件夹内容，没有升级或重启 ONN Core。

## 部署范围

- 质量追溯：[3011 问数入口](http://192.168.1.108:3011/#/chat)。
- 物流：[3010 问数入口](http://192.168.1.108:3010/#/chat)。
- 只重建两个 Java 引擎容器，镜像 ID、环境变量、其余挂载保持一致；新的 Compose overlay 只变更两个 JAR 的挂载来源。
- engine-core SHA256：`031f7d46622400b79dd151f752f557dbcd0aaf7fe98e35ae6bde5ade30842fe8`。
- adapter SHA256：`f24afb166e8a512fc06056ecee3f71b966e1e3a00345cc35557f3986ceab24a1`。
- ZIP 内容比对确认新旧 engine JAR 只有 DataAdapter、ABCProgrammerSkillTools、UserMessageUtil 三个 class 不同。工作树已有的 LiveInstanceGraph 修改所对应 class 与部署前完全一致。

## 应用层回归

部署前、后分别运行质量追溯 62 项及物流 34 项查询，答案全部匹配既有 oracle/基准。另有 13 项只读验收通过，覆盖 metadata ETag/304、能力声明、匿名 Core 访问拒绝、Manager 概览/搜索/双向邻域、七跳窗口及空窗口、无 limit 的物化路径和真实 Python 执行的来源窗口传播。

新增在线 HTTP → Java → adapter → Core 验证四项通过：

| 项目 | 结果 |
|---|---|
| CUST-001 关系产品明细整行去重排序 | 17 条，与独立基准逐条一致 |
| 去重后 offset=7、count=5 | 与完整列表切片一致 |
| 关系明细 → 去重临时结果 → count | 17，保留 snapshot 一致性与本地流水线证据 |
| 后续临时步骤包含不支持表达式 | 明确拒绝并附带规划修复说明 |

上述三个成功查询通过 Workbench HTTP 的耗时约 27、20、29 毫秒，仅作为本次功能验收观测，不是稳定性能基准。

## 真实网页问数

使用 webapp-testing 流程，通过 Chromium 实际输入此前相同的三条问题；均收到 workflow_complete，无浏览器 page error 或失败请求。页面显示、刷新恢复、历史记录和 CSV 下载均通过核对。

| 问题 | 答案 | 本次提交到流结束 | 前次观测 |
|---|---|---:|---:|
| 质量追溯客户总数 | 42 | 42.83 秒 | 54.22 秒 |
| 物流运单总数 | 800 | 41.14 秒 | 45.15 秒 |
| CUST-001 经订单关联的产品序列号和去重数量 | 17 个，完整有序明细逐条吻合基准；数量 17 | 115.36 秒 | 125.44 秒 |

下载分别包含 1、1、18 行；关系问题为 17 条序列号加 1 条计数。真实 requestSeq=0 的历史记录包含对应原问题、阶段结果及执行链。

这些是各题单次观测，关系题与简单计数测试有部分时间重叠，且存在模型随机性，不构成受控性能对照。约 115 秒仍然偏慢，本轮不能宣称端到端性能问题已经解决。

### 剩余问题及后续重点

1. 关系题模型将 conditions.properties 写成数组，合法协议要求谓词对象；产生两次实际 DSL 执行拒绝，随后改为 properties 对象后成功。同一轮成功使用了原生支持的行级 DISTINCT，未再出现原先的行去重不支持或只能执行单步骤的报错。新增能力已经生效，但当前规则仍未消除全部语法重试。
2. 下一轮应优先在规划规则补充过滤、逻辑组合和关系查询的精确 JSON 示例，并在发送数据库查询前给出字段路径明确的结构诊断，避免模型重复执行同一错误。这里属于模型生成不符合协议，不能静默忽略筛选、放宽权限或把错误条件视为无条件读取。
3. 聊天耗时还包含规划、Python 验证/正式执行、答案生成和附加流程。需要进一步记录各阶段耗时与工具失败次数，再决定减少哪类调用；本轮没有完整模型调用次数统计，不把 115 秒归因于 ONN Core 的查询执行。
4. embedding endpoint 仍未配置，日志中知识/MetricView 检索与校验流程出现 baseUrl cannot be null or blank。结构化答案正确，不代表向量检索或知识校验可用。本轮没有改动模型配置。两引擎启动时还记录一条 LangServiceImpl 资源解析错误；中文页面与查询结果展示通过，其原因未在本轮定位。

本轮未测试 Action 写入、持续压测、故障恢复及 ONN/PG 性能对照；原有中间结果最多 4999 行、2–4 步线性链等支持边界继续适用。

## 运行态与回滚

业务 Core 18081/18082 的进程、二进制及服务状态与部署前一致；18080 验证服务继续停用。全部八个原有容器名称保留，除两个引擎外，其余六个容器的 ID、启动时间与镜像保持一致。两套应用 schema、配置、Action 目录及 Core schema/capabilities 未改变。磁盘剩余约 19GB。

远端私有部署目录：`/opt/ontomato-query-planning-deploy-20261112/`。其中 deploy.py、result.json、after/acceptance-read.json 和 after/new-query-checks.private.json 保存部署及验收证据。完整旧 Compose 命令保存在 quality-rollback-command.json、logistics-rollback-command.json。可分别执行：

```sh
python3 /opt/ontomato-query-planning-deploy-20261112/deploy.py rollback quality
python3 /opt/ontomato-query-planning-deploy-20261112/deploy.py rollback logistics
```

回滚助手会一并恢复旧引擎与 adapter，等待健康状态并核对两个组件 SHA。部署过程中未触发回滚，因此本轮不宣称实际演练了回滚后的业务可用性。

本地私有测试证据：`/Users/weichen/.codex/artifacts/ontomato-query-planning-deploy-20261112/`。原始业务响应、聊天历史、CSV 和截图不进入 Git。本轮未提交或推送 Git，保护文件哈希与任务前一致。
