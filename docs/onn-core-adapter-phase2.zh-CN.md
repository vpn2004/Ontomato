# ONN adapter 第二轮交付：安全下推、计数与错误传播

日期：2026-10-31。基于 Ontomato v4.1.1 中文版及第一轮 T28 adapter，已经部署至 108。

## 本地 Git

继续使用已有 Git 仓库，没有创建嵌套仓库，也没有覆盖旧主目录的开发内容。

- 当前开发目录：`/Users/weichen/Documents/dev/worktrees/ontomato-v4.1.1-zh`。
- 本轮分支：`feature/onn-core-native-query`。
- 新建本地 `develop`，指向已验证的上一轮基线 `e4c91b88`；本轮成果保存在 feature 分支供审阅，尚未合入 develop。
- 核心修复提交：`c670befe`，`fix: propagate adapter query failures and enforce sandbox capabilities`。
- Adapter 提交：`9e6dc8ff`，`feat: push safe ONN filters and certified object counts`。
- 没有推送远端，没有更改远端配置或公共 main。
- 迭代前 Git bundle：`/Users/weichen/.codex/artifacts/ontomato-native-query-20261031/pre-iteration.bundle`，已验证可读。

已有的 `LiveInstanceGraph.java` 未提交改动原样保留，未修改、未纳入本轮提交或隔离构建。本轮也没有修改 `/Users/weichen/Documents/dev/onn-core` 的任何文件，或在该目录运行构建、测试。

## 查询行为

仍使用独立 `onn-core` adapter，`sql=false`、`useM3=false`。开启已知版本的物化快照契约时执行以下优化；旧版服务继续原有计数校验读取，不自动启用下推。

| 查询内容 | 本轮行为 |
|---|---|
| 文本、布尔字段 `=`、不含 null 的同类型 `IN` | 编译为 ONN `where_logic`，减少传回的候选对象；Java 再检查原始条件 |
| AND | 可下推其中安全的限制，其余条件保留在最终计算 |
| OR | 每个分支都必须有安全限制；任一分支无法限制时，整个 OR 保留在最终计算，防止漏行 |
| 字段选择 | ONN `output.fields` 裁剪属性；保留输出、条件、分组、HAVING、排序的全部依赖 |
| 无条件、无分组的 `COUNT(id)` / `COUNT(DISTINCT id)` | 使用物化快照认证的精确 total，下载最多 1 个对象并关闭快照；保留输出 count/offset 语义 |
| 数值条件、日期条件、IS NULL、含 null 的 IN、否定、LIKE | 保留 Java 计算，不能把 ONN 现有语义差异直接带入应用 |
| SUM/AVG/MIN/MAX、其他 count、分组、HAVING、排序、输出窗口 | 保留 Java 计算；可叠加安全筛选及字段裁剪 |
| 原生业务属性也叫 `id` | 保留虚拟身份与业务字段映射；不对这个业务字段下推筛选或属性投影，避免与系统 id 混淆 |
| 精确虚拟 ID | 保留专门 GET 路径；多个 GET 不宣称共享快照 |

下推条件只负责缩小到包含所有正确答案的候选集，原始条件不会被删除。数字外观或日期外观的文本仍由 Java 按原文本语义复核。没有提前把用户的 limit 放入 ONN，从而不会在分组、排序或最终条件检查前截掉答案。

**本轮不是完整原生聚合或图编译交付。** 日期、空值问题未因此关闭；SUM 空集、浮点精度、字符串排序等差异也没有被改成 ONN 语义。

### 精确计数的边界

COUNT 优化使用 ONN 快照的 `total_exact=true` 与 `materialized` 元数据，不是新增一个 native metric 表达式。首页仍验证总数、页行数、对象身份、数据版本、续页结构及预算，完成后关闭快照，再检查 schema 指纹。

它减少网络传输和 Java 处理，但 ONN 仍会完整扫描、物化候选结果。因此仍受 ONN 快照容量和原有 100,000 对象、32 MiB、45 秒等预算约束，不能解释为任意规模 COUNT 已变成常量时间。ONN 对象遗漏等已知缺陷也不能由这个优化自动修复。

证据新增细分项：`filterPushdown`、`projectionPushdown`、`aggregatePushdown`、`residualEvaluation`、`matchedObjects`。`objectsRead` 表示实际下载对象数；`matchedObjects` 表示 ONN 精确候选总数，存在最终条件时不等于最终答案行数。`queryPushdown` 表示至少一种优化实际启用，不能单独据此判断所有计算均在 ONN 执行。

## 核心兼容修复

1. 数据浏览、类计数、对象详情、全文搜索和 distinct 查询先检查 `DslExecutionResult.failed()`，保留底层失败原因，不能将超时、不支持或读取失败包装成零计数、对象不存在或成功的空列表。
2. Sandbox 服务在调用任何 sandbox 操作前检查 adapter 的 sandbox 能力，避免 truncate/drop 路径继续触及业务后端或 PostgreSQL 附件索引。
3. Prompt 测试区分时间格式 adapter 与样例 adapter；sandbox 测试注入当前实现实际依赖的 JSONRuleDao。没有删除错误传播或只读边界断言。

上一轮记录的 10 项失败已处理：7 项错误传播、2 项旧调用次数断言、1 项 sandbox 夹具缺失；补齐后还发现并修复了 sandbox 生命周期检查遗漏。所选核心 39 项现全部通过。

## 验证结果

| 验证 | 结果 |
|---|---|
| Adapter 离线测试 | 84 项通过；2 项需要显式环境的 live 测试默认跳过 |
| 核心选择性测试 | 39/39，通过；未运行不加过滤的数据库/模型测试套件 |
| 应用装配 | 1/1，通过 |
| 独立真实 OMS + Java | 2 项 live 测试通过，其中 17 组结果与完整读取后的 Java 计算逐项一致；数据量 6,001 |
| 经 Ontomato 发起的物流固定 DSL | 原有 21/21，通过 |
| 经 Ontomato 发起的新增状态筛选 | 1/1，通过，并验证真实下推证据 |
| 部署保护 | 数据引擎 healthy；平台完整配置 SHA-256 前后相同；其他服务和 ONN 物流实例不变 |

真实矩阵覆盖等值、IN、布尔、全安全 OR、部分安全 AND、不能下推的 OR、缺失/显式 null、等价时区日期、数值范围、含 null 的 IN、否定、数字/日期外观文本、分组求和、计数和计数 offset。

实际传输结果：

- 6,001 对象独立数据集：COUNT 下载 1 个对象；精确名称条件也只下载 1 个对象。
- 物流运单：800 对象的 COUNT 下载 1 个对象；所测状态筛选下载 89 个候选，最终计数 89。
- 没有筛选的运单 Top 5 仍读取 800 对象，在 Java 排序并选前 5；仅下推了字段裁剪。

这是正确性与传输量验证，没有重做自然语言问答全流程、PG 对照性能压测或 ONN 全部上游测试，不能据此宣布 ONN 整体性能优于 PostgreSQL。

## 108 部署与源码

- Workbench：`http://192.168.1.108:3010/#/chat`；数据引擎 18088。
- 当前镜像：`datarag-opensource:4.1.1-onn-native-query-20261031`。
- 上一轮镜像留存：`datarag-opensource:pre-native-query-20261031`。
- 物流 ONN 仍为 18081、`fb81695c`，本轮没有替换 ONN 二进制或写入物流数据。
- 部署目录 `.env` 的 `DATA_ENGINE_IMAGE` 与 `docker-compose.onn-t28.yml` 均已更新。
- 管理数据、模型配置、权限、对话、任务、执行证据、附件索引继续留在 PostgreSQL。

Adapter jar SHA-256：`0f8603e7de36ef37de1204962bd8ef9096c349cdf0c183f3b21d6bc21e6403d7`。

本轮 core 部署从原运行 jar 保留其他所有条目，只替换 `DataServiceImpl`、`SandboxServiceImpl` 两个编译类族，逐条比较确认其余条目字节不变，包括原有 `LiveInstanceGraph`。避免用本轮完整 core 构建覆盖其他已有运行改动。

隔离构建基于 Git `e4c91b88` 加本轮 12 个源代码/测试文件，路径为 `/opt/ontomato-native-query-src-20261031`；没有夹带本地未提交图改动。经逐文件旧摘要校验后，12 个文件也已同步至 `/opt/ontomato-v4.1.1-src`，旧文件独立备份，其他文件不覆盖。

### 回滚

将部署 `.env` 中 `DATA_ENGINE_IMAGE` 和 `docker-compose.onn-t28.yml` 中的 image 都改为 `datarag-opensource:pre-native-query-20261031`，然后在 `/opt/ontomato-v4.1.1-deploy` 执行：

```sh
docker compose -f docker-compose.yml -f docker-compose.onn-t28.yml up -d --no-deps datarag
```

不要恢复整份旧 `.env`，以免覆盖以后修改的模型或连接配置。回滚不删除 PostgreSQL 卷或 ONN 数据目录。

## 后续范围

本轮仍是只读单类接入。下一轮优先处理原生关系路径与 Manager 图探索，按 Pipeline 编译并验证对象身份、optional 与对象/路径计数，再决定是否开放 `graphRead`。

ONN 上一轮 R1–R4（空值、日期、空属性对象持久化、条件写入版本）仍需团队修复及联合验收；本轮没有添加新的底层阻塞项，也没有把未修复问题当作已解决。写入、Action 幂等、schema、sandbox 和向量能力各自保持关闭，待相应门禁通过后再开放。

## 证据位置

- 本地：`/Users/weichen/.codex/artifacts/ontomato-native-query-20261031`，包含源码摘要、测试日志、真实验收结果、部署脚本、Git bundle。
- 108：`/opt/ontomato-native-query-deploy-20261031/results.json`、`source-sync.json`、旧 jar 和源码备份。
- 独立真实验收：`/opt/ontomato-native-query-acceptance-20261031-r3`，包含 `results.json` 和 `live.log`；测试进程已停止。
