# 108 环境 T33 部署与实测记录

日期：2026-11-09。基于 Ontomato v4.1.1 中文环境，部署现有 T32/T33 应用改动和窗口读取重试预算补丁。本次没有修改本地 `onn-core` 目录，没有切换分支或提交工作区中其他任务的改动。

## 部署结果

两套应用已重新部署，可以继续问数。数据、模型配置和既有访问端口保留。

| 环境 | 问数入口 | Manager | Data Engine | 专用 ONN Core |
|---|---|---|---|---|
| 物流 | http://192.168.1.108:3010/#/chat | 3006 | 18088 | 18081 |
| 质量追溯 | http://192.168.1.108:3011/#/chat | 3007 | 18090 | 18082 |

物流保持 14 个对象类型、20 个关系类型；质量追溯保持 29 个对象类型、54 个关系类型。PostgreSQL 和两个 Manager 容器没有重建；本次更新的是两个专用 Core 服务、Data Engine 和 Workbench。108 的通用 Core 18080 未切换版本。

两个 Data Engine 均显式设置：

```text
ONTOMATO_ONN_GRAPH_READ_CONSISTENCY=version_checked_window
```

符合协商能力和查询形状要求的显式窗口查询使用窗口路径；不满足要求的查询继续走已有物化路径。`version_checked` 表示读取期间校验版本，不能称为物化快照。窗口来源范围继续传入后计算、历史、分析重放和模型数据视图。

## 运行产物身份

108 已有与 T33 交付匹配的 Linux 包，来自 `/opt/onn-core-t33-benchmark-20261108/release-final/oms-linux-amd64`，因此不需要 ONN Core 团队另外构建或部署。

| 产物 | 身份 |
|---|---|
| 部署标识 | `4.1.1-onn-t33-20261109` |
| 应用 Git 基点 | `1e8af436da573da99bec683fd15aec9db681a22b`，加工作区现有 T32/T33 改动及 adapter 补丁 |
| Core 版本 | `v13.0.0` |
| Core commit | `9b97cfb131ffefb012bcce1a665abbd6455c6000-dirty` |
| Core 构建时间 | `2026-11-08T13:52:33Z` |
| Linux OMS SHA256 | `d9e6d990a89f6abdd3c552b4199b810f4662faf3b3f1ba20696c1aaca03f7845` |
| 新 adapter JAR SHA256 | `38064c03b12a79a945ad58412b000034c988a28e55c50bd7adfe04a0039e6961` |
| 新 engine-core JAR SHA256 | `a60a58e07d422ad336ad43e4e8533d0d7ac1c1104a148e6f751c90c273179d5e` |

adapter 包含[本地复核报告](onn-core-t33-adapter-review-20261109.zh-CN.md)记录的修复：同一窗口读取的全部重试共用行数、字节预算，避免失败重试重置预算。这里的 adapter 哈希取代原团队交接 JAR 的哈希。

本次重新构建 Java 应用包和 Web 静态资源：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
./mvnw -B -ntp -pl apps/data-engine -am -DskipTests package

pnpm --filter @ontomato/workbench build:web
```

均构建成功；Web 构建只有现有 Vite chunk 大小提示。部署前的本地定向验证已有 181 项测试及相关类型检查通过，本次未运行未筛选的 Java 全量测试。

108 根分区部署时约剩 4.3 GB，因此沿用现有运行时镜像，通过带版本目录的只读挂载加载新产物，没有另建完整镜像。部署目录约 60 MB，部署后根分区仍约剩 4.3 GB。Docker image 标签不代表本次实际应用文件版本，应以挂载清单和运行时哈希为准。

实际核对了两个运行中 Core 的 `/proc/PID/exe` 哈希，以及两套容器内共 304 个挂载文件：每套 2 个 Java JAR、19 个 Node/contracts 源文件、131 个 Web 文件，全部匹配部署清单。

## 部署后验收

| 项目 | 结果与范围 |
|---|---|
| 业务查询回归 | 76/76：质量追溯 42 道 1–7 跳查询，物流 14 项对象计数和 20 项关系计数；物化配置、窗口配置下分别全部通过 |
| 窗口路径实际选用 | 上述窗口配置回归中，7 道质量明细查询实际走显式窗口；其他查询保留各自适用路径 |
| 读取、元数据、Manager、窗口与后计算 | 13/13 检查组通过 |
| Workbench 真实 PostgreSQL 历史、分析重放及模型来源范围 | 12/12 检查通过，测试临时 query_run 已清理 |
| 受控 Action | 3/3 检查通过：物流同值写入及幂等、拒绝访客注入角色、质量环境保持禁写 |
| 浏览器实际自然语言问数 | 两套环境均成功，无页面脚本错误 |
| 浏览器历史回读和 CSV 下载 | 两套环境均成功，下载数据与独立校验数据一致 |

13 项读取检查包含两套 Core 的强 ETag/空响应 304、窗口能力协商、匿名访问拒绝，以及 Manager 摘要、搜索、入边和出边探索。Manager 验证使用实际后端接口，没有据此声称重新测试了全部可视化交互。

质量七跳窗口分别测试 `offset=0,count=10`、`offset=7,count=10`、`offset=129,count=5`。精确匹配总数为 128，返回分别为 10、10、0 行，顺序和值与完整查询对应切片一致。返回保留 `answerComplete`、`totalExact`、`consistency=version_checked` 和 `snapshotConsistent=false`，关闭结果 `windowReleased=true`；没有混入旧的 `complete=true` 或预算截断标记。省略 limit 时正确保留物化路径。

实际 Python `/abcProgram/execute` 将 offset 7 的 10 条输入汇总为一行 `{"rows":10}`，仍保留来源 offset 7、count 10、total 128。该样本总共只有 128 条匹配路径，小于读取页大小 500，不能据此声称只从 Core 读取了 10 个 binding，也不能用来证明大数据性能。

物流 Action 使用既有 `carrier-contract-level`，对 `CR001` 写回当前相同值；执行成功、`attempts=1`，查询状态及重复执行返回一致，业务字段值未改变。该验证实际新增了一条 Action 审计/决策记录并更新数据版本，不能描述为全程无写入。

浏览器通过中文问数入口实测：

1. 物流：“当前物流数据中共有多少张运单？请查询实际数据。”返回 **800**，历史标题为“运单数量查询”，CSV 含一行计数 800。
2. 质量：“请沿供应商、物料批次、装配事件、产品的关系，列出前10条关联明细，只返回各对象的业务编号。”返回 **10 条**关联明细，历史标题为“供应批次装配产品关联”；下载后的四个业务编号按字段名与独立三跳校验结果匹配。

这两个自然语言案例走了现有规划与后计算路径，本身没有展示来源窗口提示；窗口语义通过上面的真实引擎、Python 和 Workbench 历史测试单独验证。本次浏览器验证了普通结果 CSV 下载，未重新验证窗口结果的界面 JSON 下载按钮。

本次目标是部署与正确性回归，没有重跑团队原有 4950 个计时样本，也没有产生新的 ONN/PG 性能结论。

## 配置、备份与回退

服务器部署目录：`/opt/ontomato-t33-deploy-20261109`，权限 0700。真实配置、数据库备份和业务样本均保存在受限目录，没有写入 Git。

部署前保存了原 Docker inspect、有效 Compose 配置、Core 环境配置、本体、Action 目录及基线查询；两个 Core 数据目录停服冷备，PostgreSQL 备份 gzip 完整性已验证：

- `before/logistics-core-data.tar.gz`
- `before/quality-core-data.tar.gz`
- `before/postgres.sql.gz`

新增 Compose 叠加配置：

- `/opt/ontomato-v4.1.1-deploy/docker-compose.onn-t33.yml`
- `/opt/ontomato-quality-20261104/app/docker-compose.onn-t33.yml`

后续启停应包含对应叠加配置。完整调用参数已保存在部署目录的 `logistics-compose-command.json`、`quality-compose-command.json`，避免只使用基础 Compose 而重新加载旧产物。两个 Core 服务各自通过 `90-ontomato-t33.conf` systemd drop-in 指向新二进制，既有数据、环境和权限配置保留。

回退脚本已准备并检查 Python 语法，本次没有执行回退：

```sh
ssh root@192.168.1.108 \
  'python3 /opt/ontomato-t33-deploy-20261109/rollback.py'
```

该脚本恢复原应用镜像 ID/有效配置与 Core 可执行文件，保留当前持久化数据，不会自动覆盖为旧数据库备份。冷备恢复属于另一步操作。

验收证据保存在服务器部署目录 `after/`；本地受限证据目录为 `/Users/weichen/.codex/artifacts/ontomato-t33-deploy-20261109`，其中保存挂载清单、运行身份、查询回归、窗口与历史检查，以及浏览器截图和下载校验。受控 Action 验证新增审计记录后，不能再用包含旧完整 Action 历史的基线进行逐字相等检查。

## ONN Core 团队后续事项

本次部署和已执行的验收没有发现阻止这两套应用使用的新问题，不需要 ONN Core 团队再更新 18081/18082。既有性能后续计划仍按原范围推进；本报告不把未验收的任意排序、选择性规划或更大负载性能视为已完成能力。
