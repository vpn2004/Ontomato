# ONN adapter 第一轮交付与 108 部署

日期：2026-10-31。基于 Ontomato v4.1.1 中文版，工作目录为 `/Users/weichen/Documents/dev/worktrees/ontomato-v4.1.1-zh`，分支为 `feature/onn-core-v4.1.1-combined`。

## 本轮结果

完成能力协商、结构化错误、安全续页、完整性校验及查询编译基础，并部署到 108。物流 ONN 与 Ontomato adapter 都已更新，不再是新代码只运行在独立验收端口的状态。

保持独立 `onn-core` adapter、`sql=false`、`useM3=false`。本轮仍是只读接入：业务对象、边、schema、sandbox 与附件向量写入未开放；平台管理数据、模型配置、对话和任务继续使用 PostgreSQL。

**本轮没有修改 `/Users/weichen/Documents/dev/onn-core` 的任何内容，也没有在其目录运行构建或测试。** 使用 ONN 团队已发布二进制，测试和运行目录独立。原主工作目录的旧版 adapter 不覆盖；v4.1.1 工作目录中已有的 `LiveInstanceGraph.java` 改动未修改、未纳入本轮提交。

## Adapter 行为

1. 读取 `/system/capabilities`，只接受明确开启的 `query_contract/v2` 与 `snapshot_pagination/v2`，并核对 API 版本、分页类型及单进程一致性边界。不能从不准确的旧 `conditional_write` 标记推导写权限。
2. 旧服务能力端点返回 404，或未声明支持的快照版本时，保留原先最多 5,000 对象、计数校验的读取方式；认证失败、畸形能力清单、已开启快照后的错误不会降级到旧读取来绕过。
3. 新服务使用显式 `snapshot`、每页最多 500 行，逐页检查精确总数、页行数、快照身份、对象 `classPath+id` 唯一性和不透明字符串 `data_version`。完整读取后再次核对 schema 指纹。
4. 快照读取默认预算为 100,000 对象、累计响应 32 MiB、45 秒，最多三次完整尝试；每个 HTTP 请求仍有 12 秒期限和 8 MiB 响应上限。预算是读取保护，不是承诺十万行已做性能验收，也不是 JVM 内存硬限制。
5. `409 SNAPSHOT_BUSY / SNAPSHOT_STALE / SNAPSHOT_AUTH_CHANGED` 与 `410 CURSOR_EXPIRED` 按 code 限次重试；重试丢弃此前全部页，重新从首页建立结果。403、429、422 及其他错误明确失败，不把部分结果作为答案。
6. 完成或失败时尝试关闭快照；关闭请求接受无 JSON 的 204。清理使用独立 1 秒期限，证据记录是否确认释放。不会将 cursor、快照 ID、网关凭证或远端错误正文写进查询证据。
7. HTTP 层增加 Pipeline 分页入口，作为下一轮图编译的基础；本轮尚不开放图查询能力。
8. 接受 Go 将空 schema 集合序列化成显式 null 的情况；schema 键缺失仍拒绝。真实大类验收发现无关系实例的 `relations:null`，已增加回归。

### 查询计算边界

当前 `nativeSnapshotRead()` 编译单类完整快照读取，**过滤、投影、聚合、分组、HAVING、排序及用户窗口仍由现有 Java 求值器完成**。尚未完成原生业务计算下推，不能将这轮作为 ONN 与 PostgreSQL 性能对比结果。

这样保留现有 DSL 的空值、日期、排序与金额计算语义，避免将已确认的 R1/R2 问题带入应用。查询证据现在包含：

```json
{
  "evaluation": "snapshot-single-class-local-evaluation",
  "snapshotConsistent": true,
  "consistency": "materialized",
  "backendCommit": "fb81695c",
  "dataVersion": "<opaque string>",
  "totalExact": true,
  "complete": true,
  "queryPushdown": false,
  "snapshotReleased": true
}
```

精确 ID GET 继续使用原有专门路径，不宣称多个 GET 共享快照。单次 DSL 的物化结果也不等于整段多工具会话共享事务。

完整快照读取可超过原先的 5,000 类规模限制；用户输出 `count` 仍保持原有 0–5,000 范围，没有扩大模型输出或浏览器单页范围。

## 108 运行状态

| 项目 | 当前部署 |
|---|---|
| Workbench | `http://192.168.1.108:3010/#/chat` |
| 数据引擎 | 18088，镜像 `datarag-opensource:4.1.1-onn-t28-20261031`，healthy |
| 物流 ONN | 18081，`ontomato-onn-logistics.service`，commit `fb81695c` |
| 新物流二进制及数据 | `/opt/ontomato-onn-t28-logistics-20261031` |
| OMS SHA-256 | `c15639657f2699c78418a7968eeea2bc64d75c70a11130daa3d79f5cc96bdc96` |
| Adapter jar SHA-256 | `2c488aaa0606c9bab1e0cd8a653b46b9bf1e04490a7dcd2d3627dbeff6b6d034` |
| 独立验收服务 | 原 18080 保留；本轮临时 18082 已停，无额外测试 OMS 进程 |
| 业务数据 | 14 类、20 类关系、3,228 对象、9,352 条边 |
| 保留的旧物流库 | `/opt/ontomato-logistics-20261027`，原二进制、数据及配置未覆盖 |

先短暂停止旧物流服务取得冷克隆，约 5.2 秒后恢复旧服务；在独立 18082 验证新版后，再将原 systemd 服务切到新目录和原 18081 地址。全部业务 schema 字段、对象属性、边端点和边属性对拍一致；schema 数组返回顺序差异在按路径归一后消除。没有重新导入或修改三处历史 boolean/string 声明，也没有替换平台本体。

已启用快照、关系属性和本机私有在线备份 socket。为本次开发测试配置 idle TTL 30 秒、hard TTL 120 秒、每主体会话上限 128；这是有界的开发环境设置，不是生产容量建议。ONN 关闭墓碑仍计费，429 仍可能出现，需由后续底层迭代治理。

持久幂等与 scoped 路径策略没有启用，adapter 也没有开放写入。现有可信网关认证保留；没有将用户角色伪装成固定 admin 身份。

### 镜像与源码维护

本轮以原线上 Data Engine 镜像为基础，**仅替换 `/app/lib/data-adapter-onn-core-4.1.1.jar`**，保留其他运行库与既有中文版本，不发布这轮构建产生的 core jar。原镜像保留为 `datarag-opensource:pre-adapter-t28-20261031`。

部署目录 `.env` 的 `DATA_ENGINE_IMAGE` 已指向新镜像，普通 `docker compose up` 也会保留它；原 `.env` 私有备份在 `/opt/ontomato-onn-t28-logistics-20261031/deploy.env.before-adapter`。辅助覆盖文件为 `/opt/ontomato-v4.1.1-deploy/docker-compose.onn-t28.yml`。

构建在 `/opt/ontomato-adapter-t28-src-20261031` 的 JDK 21 容器内运行。核对旧源码摘要与本地基线一致后，已将本轮 13 个源码/测试文件同步到原部署源码 `/opt/ontomato-v4.1.1-src`，避免后续正常构建丢失 adapter 更新；旧文件独立归档，没有覆盖其他远端源码。

## 验证结果

| 验证 | 结果与范围 |
|---|---|
| Adapter 离线测试 | 79 项通过，1 项显式 live 默认跳过；包含分页重建、错误分类、重复 ID、版本/总数变化、预算和凭证保护 |
| 应用装配 | 1/1，通过；补正既有列表遗漏 `onn-core` 的断言 |
| 新物流实例 Java live | 1/1，通过认证、schema、精确 ID、800 对象的完整快照计数 |
| 6,001 对象真实 OMS + Java adapter | 1/1，通过，13 页；底层统计仅求值 1 次、后页不重算，完整计数 6,001 |
| ONN 升级后、旧 adapter | 经 Ontomato API 发起相同固定 DSL：21/21 |
| 新 adapter 部署后 | 同一 Ontomato API、同一固定 DSL：21/21 |
| Workbench 接入冒烟 | provider 可见、本体预览 14/20、执行证据确认 materialized、完整和已释放 |
| 原生业务备份 | 14 条黄金查询的冷备、verify 和独立 restore 完成；上线后再执行在线备份、verify、独立进程恢复并核对全部对象和边 |
| 平台配置 | 测试前后完整配置 SHA-256 均为 `cc383bfb9d1551e64d63ac0c5727839c7291c5ddb5b1425abb9e583b6a677747` |

没有重新运行自然语言问答与 PG 性能对比，也未重新跑 T21–T28 全部上游套件；本轮不能据此关闭上次四项 ONN 缺陷。

### 扩展核心测试的已有失败

额外选择六个 core 测试类，39 项中 29 项通过、10 项失败。该模块不依赖 ONN adapter，涉及的核心实现本轮未修改，不纳入 adapter 通过数：

- `DataServiceImplQueryFailureTest`：7 项，涉及计数/详情失败原因和搜索、distinct 查询的错误传播。
- `PromptSchemaMappingTest`：2 项，断言 adapter 构建调用次数与当前 v4.1.1 不一致。
- `ReadOnlyAdapterWriteBoundariesTest`：1 项，sandbox 夹具未注入当前实现所用的 `JSONRuleDao`，先产生 NPE。

这些结果应作为下一轮兼容性回归事项：先区分过时夹具与实际服务错误处理，再修复实现或测试，不能删除断言来宣称全绿。线上镜像仅更新 adapter，未用本次新编译 core 替换原运行库。

## 后续迭代

1. **原生单类计算下推**：整理 DSL/ONN 语义矩阵，完成 projection、逻辑条件、group/having、sort、聚合映射及错误传播回归；R1/R2 修复验收前继续保留 Java 计算。金额精度、空集 sum、字符串排序等差异需显式决定，不能静默改语义。
2. **原生图查询和 Manager**：使用已预留 Pipeline 传输接口，按对象身份编译关系路径、optional、对象/路径去重；再开放图探索能力。
3. **真实身份与可靠 Action**：R3/R4、权限映射、幂等及版本冲突通过联合验收后开放受控写入；schema、sandbox 与向量写仍各自验收。

## 回滚

回滚独立处理镜像与业务后端，不删除卷或覆盖数据目录。

Adapter 回滚：将部署 `.env` 的 `DATA_ENGINE_IMAGE` 和辅助覆盖文件中的镜像都改为 `datarag-opensource:pre-adapter-t28-20261031`，然后在 `/opt/ontomato-v4.1.1-deploy` 执行 `docker compose -f docker-compose.yml -f docker-compose.onn-t28.yml up -d --no-deps datarag`。不应直接恢复整份旧 `.env`，以免覆盖后来其他设置。

ONN 回滚：停止物流服务，移走本轮新增的 `/etc/systemd/system/ontomato-onn-logistics.service.d/50-onn-t28.conf`，执行 `systemctl daemon-reload` 并启动 `ontomato-onn-logistics.service`，即可使用保留的旧二进制及旧库。本轮应用保持只读；如果后来发生业务写入，应先处理增量数据，不能直接切回旧快照。

## 证据

- 本机证据目录：`/Users/weichen/.codex/artifacts/ontomato-adapter-t28-20261031`，包含 `evidence.json`、测试日志、源码摘要和执行脚本。
- 108 运行及备份证据：`/opt/ontomato-onn-t28-logistics-20261031`，包含 `staging-results.json`、`cutover-results.json`、`adapter-deployment-results.json`、`business-restore-results.json`。
- 大类独立验收：`/tmp/ontomato-adapter-large-t28-20261031-r2`；其测试 OMS 已关闭。
- 本轮没有提交真实配置、令牌、原生数据库或运行数据到 Git。
