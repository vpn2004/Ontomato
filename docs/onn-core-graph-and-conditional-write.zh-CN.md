# ONN v13 关系查询、Manager 图探索与条件写验收

日期：2026-11-01。Ontomato v4.1.1 中文版，分支 `feature/onn-core-native-query`。

## 交付状态

108 的物流 ONN 已升级为正式 **v13.0.0**，新 adapter 已部署，Data Engine 为 healthy。关系 DSL 和 Manager 实例图谱通过真实物流数据验收。

**条件写的 adapter API 已完成，且在独立授权测试库验证；现有 Python Action 执行链尚未接通，线上写入仍关闭。** 不能将底层 PATCH 验收解释为完整 Action 已交付。

保持独立 `onn-core` adapter、`sql=false`、`useM3=false`。ONN 承载业务对象和关系；平台用户、权限、配置、对话、任务、执行资产和执行证据继续使用 PostgreSQL，附件索引保持原方案。

没有修改 `/Users/weichen/Documents/dev/onn-core` 的任何内容，也没有在该目录构建或运行测试。使用团队发布的二进制，构建、运行和测试目录独立。本工作树已有的 `LiveInstanceGraph.java` 两处主键排序修正保留原状，部署使用该修正；它属于此前未提交改动，本轮不将其纳入任务提交。`.task-onn108/` 的真实配置不纳入 Git。

## 108 部署

| 项目 | 结果 |
|---|---|
| Workbench | `http://192.168.1.108:3010/#/chat` |
| Manager | Workbench → 管理后台 → 数据浏览 → 实例图谱；独立端口 3006 |
| Data Engine | 18088；`datarag-opensource:4.1.1-onn-graph-20261101-r2`，healthy |
| 物流 ONN | 18081；`ontomato-onn-logistics.service` |
| ONN 版本 / commit | `v13.0.0` / `9b97cfb131ffefb012bcce1a665abbd6455c6000` |
| OMS SHA-256 | `df1faa345170ecaa186c32f6208fac77d3ee1f95c79fd46923dbf196a6ef6f93` |
| onnctl SHA-256 | `0377d1211a5f7d14ac8f5885b494bfdb0ba9c4ef176a6180a2c0055540e8e869` |
| Adapter jar SHA-256 | `85a8c1e8777e3d58da0075727f4894e1eaed6811715e3325619ede7f95bdfb98` |
| 部署 core jar SHA-256 | `ef0c2a71d6885c4e1c2fe5a496db20b502807692effd46781cf3b6fa7cf0babf` |
| 数据集 | 14 类、20 类关系、3,228 对象、9,352 条原始边 |

OMS/onnctl 摘要与团队 `release-v13.0.0/manifest.json` 一致。服务使用 `/opt/ontomato-onn-v13-logistics-20261101/oms`，数据软链指向本轮先前克隆的 `/opt/ontomato-onn-t29-logistics-20261101/data`。应用仍使用原有网关认证，不输出或提交令牌。

部署以原线上镜像为基础，仅替换 ONN adapter jar，以及 core jar 中 `LiveInstanceGraph` 和新增 `ConditionalObjectWrites` 的类族；逐条校验其余 core jar 内容不变。工作台、Manager 镜像、PostgreSQL 卷及模型配置没有替换。15 个任务源码/测试文件通过前后 SHA 校验后同步到 `/opt/ontomato-v4.1.1-src`。

## 关系查询的支持边界

新增 `OnnCoreGraphCompiler`，将关系编译为具名 Pipeline 和 materialized bindings。接受 `relationship` 或 `relationships`，支持明确关系类型的正向、反向、分支和多跳连接。

当前限定单个 DSL、一个 step、一个 pattern，2–8 个对象、各对象类型不重复、连通关系树、每条关系一跳。关系端点、方向、导入映射的 `fromField/toField` 和启用状态都必须一致。

暂不支持 optional、关系类型 union/wildcard、变长路径、重复类、自环、环路、多 pattern 或多 step。ONN SIMPLE 路径会排除重复的 class+id，普通 DSL 连接没有相同约束，因此遇到这些结构明确拒绝，避免漏行后返回成功。

bindings 按完整变量、类和 ID 元组识别路径，支持跨类相同 ID 和包含冒号的 ID。保留连接结果的路径乘数；`COUNT(id)` 与 `COUNT(DISTINCT id)` 各按其 DSL 语义计算，不按单个节点 ID 提前去重。

快照读取逐页验证 result kind、完整性、精确总数、页计数、快照身份、data version 和绑定身份；失败丢弃全部中间结果。仍有 100,000 行、32 MiB、45 秒总预算，单次 HTTP 最多 12 秒、8 MiB，最终用户输出窗口最多 5,000 行。超预算会明确失败。

关系查询证据包括 `relationshipPushdown=true`、`snapshotConsistent=true`、`complete=true`、`bindingsRead`、`pagesRead`、`readAttempts` 和释放结果；`aggregatePushdown=false`、`residualEvaluation=true`，不把本地聚合标记成原生下推。

## 日期、空值和计算语义

本轮没有扩大日期、空值谓词的下推范围。只复用此前确认安全的字符串/布尔等值与 IN 条件；日期/数字比较、IS NULL、含 NULL 的 IN/NOT IN、三值逻辑继续由 Java DSL 求值器处理。关系 bindings 完整读取之后，再执行最终过滤、投影、分组、聚合、HAVING、排序和窗口。

独立真实图数据验证：`2026-01-15T18:30:00+08:00` 与 `2026-01-15T10:30:00Z` 比较等价；显式 null 与缺失日期都按空值处理。对 131 条关系，日期等值命中 44 条，IS NULL 命中 87 条。原有 DSL 测试继续覆盖 SQL 三值逻辑、空集聚合、去重、排序及精度。

**这些正确性测试不证明 ONN 性能优于 PostgreSQL。** 本轮没有重跑同负载的 PG 性能对比。关系聚合仍在应用侧执行，Manager 请求也可能发生快照准入等待；后续性能实验需独立统一数据、问题、模型、缓存、并发和资源条件。

## Manager 图探索

复用 Manager 及 `LiveInstanceGraph` 的通用 DSL 链路，没有复制第二套前端图实现。API 验收涵盖概览、节点总数、边身份、搜索和 incoming/outgoing/both 邻居方向。

浏览器实际验证：概览显示 500/3,228 个采样实例、241 条采样连接；搜索 `/logistics/carrier:CR001` 后先加载 100 条邻居连接，再加载剩余 16 条，累计 117 个节点和 116 条连接，与原始导入边集一致。概览采样边数不是整个数据集的边总数。

`DataAdapterCapabilities.graphRead` 仍为 false，因为该标志对应未实现的专用 `queryNeighbors` 接口；Manager 使用通用 `executeDsl`，不依赖该标志。不能仅为点亮标志而宣称未实现接口可用。

### 快照准入竞争与重试

第一次部署时，关系查询通过，但 Manager 概览遇到 `429 SNAPSHOT_CAPACITY_EXCEEDED`，脚本已自动回滚。现场 ONN 默认同时构建上限为 2，Manager 有并行查询；检查时活动会话、构建数和存储字节均为 0，没有残留活动快照。

第二次部署加入仅针对该明确 code 的指数退避：最多 8 次准入重试，每次最长 1 秒，并共享原快照读取 45 秒总期限。旧的 409/410 失效重建保留独立限次；任一重建丢弃旧页。403、422、未知 429 和其他错误不自动降级。持久容量不足仍明确失败，不能返回部分答案。

未调大服务器 builder 上限。第二次部署全部 70 项检查通过；服务统计仍可看到准入拒绝，说明应用处理了容量竞争，不能据此宣称容量或吞吐问题已消失。本次属于 adapter 的重试处理缺口，不据此新增一个 ONN 正确性缺陷。

## 条件写 API 与 Action 待办

新增公共接口 `ConditionalObjectWrites`，不打开 legacy `objectWrite`：

- `readVersionedObject(class, id, principal)`：返回对象、版本和不透明强 ETag。
- `patchObject(class, id, values, expectedEtag, idempotencyKey, principal)`：只修改指定对象的 scalar 属性，ID 不可修改；null 明确保留。

principal 必须来自可信 host 的认证状态，不能从普通客户端请求头直接采信。要求配置网关凭证、scoped authorization，以及明确启用的 `conditional_write/v2`、`retry_idempotency/v2`、audit、action/resource governance。网关令牌与业务调用者身份分开。

使用真实协议：PATCH body 为 `class_path`，原始强 ETag 放在 `If-Match`，幂等键为 `X-OMS-Idempotency-Key`。不接受 `*`、弱 ETag 或多值 ETag；冲突后不自动读取新版本覆盖。断连、超时或写后响应异常时结果可能待确认，调用方需保留原 key 与原 ETag 进行协调，不能直接创建新决策重试。

独立授权 OMS 测试验证：更新成功、原 key+ETag 重放不重复写、旧 ETag 返回 412、两个并发决策仅一个成功、只读角色返回 403、null 更新。只写专用测试库，没有写物流业务数据。

108 物流实例当前 `authorization_mode=disabled`，audit、retry idempotency 和 action/resource governance 未启用。现有 `ActionServiceImpl.execute()` 仍由 `requireObjectWrite()` 拒绝；尚未把 Python Action 的写回调改造为受控条件写，不能通过移除该检查来开放。

Action 接入前需要确定调用身份方案：真实用户与角色映射，或明确授权的受限应用服务账号。当前公开版无登录，直接配置服务账号会让能访问该实例的人通过该账号执行写入，属于需要明确的权限边界。已经向用户提出身份选择，尚未收到答复。

身份明确后的具体实现顺序：

1. host 解析身份、映射受限 ONN role/path 并启用审计与持久幂等；验证客户端不能伪造主体。
2. 为 Action 执行上下文传递服务端签发且绑定 run/domain 的写权限，Python 写回调不能自报 roles。
3. 首批仅接明确对象 PATCH，携带用户决策时的原 ETag 与稳定决策键；禁止任意条件批量更新、自动换版本重试及未验收的边/schema/sandbox 写入。
4. PostgreSQL 持久化执行证据、决策键和结果状态；验证成功、冲突、重复提交、超时待确认、权限拒绝与跨 domain 隔离。多对象 Action 不自动等同于原子事务。
5. 完成上述端到端验收后再启用线上入口；自然语言自动生成与 sandbox 单独验收。

## 测试与证据

| 验证 | 结果 |
|---|---|
| ONN adapter 定向测试 | 101 项：96 通过，5 项需显式环境的 live 测试默认跳过；0 失败/错误 |
| Core 定向回归 | 23/23，包括 LiveInstanceGraph、查询失败传播、映射和只读写边界 |
| Data Engine 装配 | 1/1 |
| 独立 OMS 图与 Manager Java live | 2/2；134 对象、262 边，覆盖 130 邻居的 100+30 分页 |
| 独立 scoped-policy 条件写 live | 1/1，包含上述成功/重放/冲突/权限场景 |
| 108 经 Ontomato API 发起 | 70/70：21 既有 DSL、20 关系正反向 40 项、多跳 1 项，以及配置/Manager 等检查 |
| 浏览器 | 实例概览、搜索、记录详情、邻居继续分页；CR001 的 116 条边与原始导入集一致 |
| 平台配置校验 | 前后完整配置 SHA-256 均为 `cc383bfb9d1551e64d63ac0c5727839c7291c5ddb5b1425abb9e583b6a677747` |

未无过滤运行 Java 外部系统测试；未重跑全部 ONN 上游验收或自然语言模型问答，也未据此宣称其他 ONN 能力全部验收。

本轮实际构建命令（108 独立源码目录、JDK 21 容器内）：

```sh
./mvnw -B -ntp -pl packages/data-adapters/onn-core,apps/data-engine -am \
  -Dtest=OnnCore*Test,LiveInstanceGraphTest,DataServiceImplQueryFailureTest,PromptSchemaMappingTest,ReadOnlyAdapterWriteBoundariesTest,DataAdapterAssemblyTest \
  -Dsurefire.failIfNoSpecifiedTests=false package
```

本机可审查证据：`/Users/weichen/.codex/artifacts/ontomato-graph-actions-20261101/final`，包含 `results.json`、`runtime-final.json`、`production-checks.json`、构建和 live 日志，以及 `manager-graph.jpg` 截图；均不含运行凭证。

108 证据：`/opt/ontomato-graph-actions-deploy-20261101-r2`、`/opt/ontomato-graph-actions-acceptance-20261101-r2`、`/opt/ontomato-conditional-acceptance-20261101`。第一次失败的部署证据保留在不带 `-r2` 的部署目录。临时测试 OMS 在 fixture finally 中停止。

## 回滚与后续集成

Adapter 回滚：只将部署 `.env` 的 `DATA_ENGINE_IMAGE` 和 `docker-compose.onn-t28.yml` 对应镜像改回 `datarag-opensource:pre-graph-20261101-r2`，再在 `/opt/ontomato-v4.1.1-deploy` 执行：

```sh
docker compose -f docker-compose.yml -f docker-compose.onn-t28.yml up -d --no-deps datarag
```

不要恢复整份旧配置覆盖后来变更，不删除卷。ONN 的 v13 systemd 覆盖文件为 `60-onn-v13.conf`；若需回退到前一二进制，应先核对数据格式兼容或使用相应备份，不自动用旧库替代后来数据。本轮没有执行 ONN 降级。

本轮仅保存任务提交，不推送、不合并开发主线；工作树保留此前未提交改动。后续从提交重建时，须同时带上经过确认的 `LiveInstanceGraph.java` 主键排序修正，否则不能声称复现本次 Manager 部署组合。
