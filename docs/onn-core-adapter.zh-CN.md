# ONN Core 首期适配器使用说明

本次改造在 Ontomato 中新增独立 `onn-core` HTTP adapter，使用 `sql=false`、`useM3=false`。它已装配到 Java 应用，提供源模型预览、显式导入和受限单类只读查询。没有迁移业务数据，也没有部署改造后的完整应用。

ONN 保存已有业务对象、属性和关系；Ontomato 的本体投影、展示说明、连接配置、用户与权限管理、对话、任务、执行证据和附件索引继续使用现有 PostgreSQL。首期不删除 PostgreSQL，不使用 ONN Edit/Action 替代平台服务。

改动只发生在 Ontomato。`/Users/weichen/Documents/dev/onn-core` 未修改，远端 `192.168.1.108` 只做读取和只读查询。

## 接入步骤

1. 从本分支构建 Data Engine 和浏览器应用。已发布的 `4.0.0-beta.1` 镜像不包含本次改造；仅更新仓库不能让旧镜像自动获得适配器。
2. 在 Workbench 的 Admin Panel → Business Configuration 中选择 **ONN Core**，输入服务 URL 并保存，例如 `http://192.168.1.108:18081`。这是业务数据连接，内部 PostgreSQL 连接仍由原有配置管理。URL 可为 HTTP(S) origin 或以 `/api/v1` 结尾；本期表单只接受 URL，没有新增 token、租户或角色转发配置。
3. 打开 Ontology Manager，点击 **Preview source schema**。服务端读取 ONN schema，转换为 Ontomato 模型，显示整份 JSON 供核对。
4. 核对后点击 **Replace model with this schema**。导入会替换当前 Ontomato 模型及其展示名、描述；需要保留的展示修改应先导出。该操作只写平台 PostgreSQL 中的本体元数据，不修改 ONN schema 或业务实例。若预览之后连接或 schema 改变，服务端拒绝旧预览，需要重新预览。
5. 导入后浏览小类的对象数据，或通过现有 DSL/问答入口执行受支持查询。展示名、描述和已启用属性的业务展示键仍可编辑；禁用属性不能作为业务展示键。新增/删除类、属性、关系以及主键、类型、映射等结构修改会被拒绝。

选择 ONN 不会自动迁移原 PostgreSQL datasource 的业务数据，也不会自动把旧模型改成 ONN 模型。首次使用需完成源模型导入。

## 已支持的范围

| 能力 | 首期行为 |
| --- | --- |
| Schema | 读取 `/schema/all`，展开继承属性，导入类、属性和有明确两端类的关系定义；空端点关系不映射，并在数据集说明列出 |
| 对象身份 | 虚拟字符串主键 `id`，读取实例顶层 ID；ONN 业务属性 `id` 映射为 `onn_attr_id`，`sourceName` 保留真实来源 |
| 精确对象读取 | 虚拟 `id = "…"` 或可安全收窄的 `id in […]`（最多 100 个字符串 ID）使用按类和 ID 的 GET，支持大类中的指定对象 |
| 单类读取 | 全类对象数最多 5,000；请求 5,001 条作为越界哨兵，核对读取前后 sample 总数、query 总数、行数、类路径和唯一 ID |
| 筛选与投影 | 单类标量字段、AND/OR、比较、IN/BETWEEN、IS NULL、LIKE；本地求值，未声明或禁用字段不可查询 |
| 聚合 | 单类 count/sum/avg/min/max、聚合 distinct、GROUP BY/HAVING；count(field) 排除 null/missing，count(id) 计对象数 |
| 排序与分页 | 在已获取的有限集合上排序；用 ID 打破排序平局；`output.limit` 使用 `offset/count` |
| 证据 | 记录源 URL、schema fingerprint、实际 HTTP 查询和读取对象数；明确 `view=BASE`、`snapshotConsistent=false` |
| 平台编排 | 保留原有问答规划、分析与 MetricView 入口，但执行只接受上述 DSL 子集；Action 与沙箱业务写入受能力校验限制 |

字段求值不调用 ONN ABC 翻译器，也不依赖原生跨类遍历的变量绑定。全类计数一致只提供遗漏与变化检查，**不能证明原子快照**：并发更新、同数替换等仍可能发生。需要快照一致性的业务分析应等待 ONN 的版本化分页/快照契约。

分组、HAVING 或聚合排序的答案可以完成，但现有节点证据回查把输出改为身份字段后仍保留聚合结构，ONN 会拒绝这种查询；Workbench 此时显示空节点列表。查询的执行记录和上述证据元信息仍保留，本期未实现按分组计算完整的对象贡献关系，不能宣称完整图溯源。

本期 capabilities 为：`query=true`、`schemaRead=true`；`schemaWrite/objectWrite/edgeWrite/sandbox/graphRead=false`。不支持关系绑定、多步/临时表、业务对象与边写入、邻居探索、ONN 向量/derived 属性。空类型或未识别类型的属性保留来源映射并禁用，不推测其业务含义。关系定义可用于查看本体结构，但不代表可以查询关系实例。

当前 DataBrowser 的关键词搜索（`conditions.text`）和 distinct 属性建议（行级 DISTINCT）也不在支持范围，会明确返回错误；按已导入类进行普通分页读取和上述精确 ID 查询可用。

现有附件索引仍在 PostgreSQL；本期没有接通 ONN 对象的文档向量写入与检索，相关入口明确拒绝。ONN 的数值 vector 与 Ontomato 文档 vector 属性不是同一契约。

超过读取上限、schema 映射变化、错误/降级/不完整响应、无法核对总数或不支持的 DSL 均返回失败，不以空表或零值代替。HTTP 请求包含 3 秒连接超时、12 秒整体响应期限及 8 MiB 响应上限，不跟随重定向。

## 最小 DSL 示例

以下是一条 DSL 对象。通过现有 DSL 入口使用，类名需来自已导入 schema。整个执行只接受一条查询、一步、一个 pattern、一个对象变量。

```json
{
  "answer": {
    "steps": [
      {
        "graph": {
          "patterns": [
            {
              "objects": [{"class": "/demo/user", "variable": "u"}],
              "relationships": []
            }
          ]
        },
        "output": {
          "to_user": true,
          "fields": [{"variable": "u", "field": "id", "as": "id"}],
          "limit": {"offset": 0, "count": 20}
        }
      }
    ]
  }
}
```

可在对象内增加 `"conditions": {"properties": {"field": "id", "operator": "=", "value": "<已存在 ID>"}}` 做精确读取。统计对象数时，输出字段改为 `{"variable":"u","field":"id","function":"count","as":"total"}`。普通字段与聚合字段混用时，普通字段必须属于分组键。

## 验证与复现

离线测试使用 loopback HTTP fixtures，不访问业务服务。Java 核心只运行明确选择的单元测试，不运行未筛选的数据库、模型或外部服务测试。

```sh
# 新适配器；live test 默认跳过
./mvnw -pl packages/data-adapters/onn-core -am \
  -Dtest='OnnCore*Test' -Dsurefire.failIfNoSpecifiedTests=false test

# 核心能力、只读边界、身份、失败传播及问答映射回归；不访问数据库或模型
./mvnw -pl packages/data-engine-core \
  -Dtest=DataAdapterCapabilitiesTest,OntologySchemaValidatorTest,AdminServiceImplPrimaryKeyTest,VectorResourceServiceImplTest,HttpRequestUtilVectorTest,DataServiceImplGraphCapabilitiesTest,ReadOnlyAdapterWriteBoundariesTest,BusinessConfigControllerDataAdapterTest,BusinessConfigControllerOntologyImportTest,AdminControllerPrimaryKeyTest,DataAdapterRegistryTest,DataServiceImplQueryFailureTest,HttpRequestUtilIdentityTest,PromptSchemaMappingTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

# 应用装配，确认 PostgreSQL 和 ONN provider 同时可用
./mvnw -pl apps/data-engine -am \
  -Dtest=DataAdapterAssemblyTest -Dsurefire.failIfNoSpecifiedTests=false test

# Java 应用打包，不启动服务
./mvnw -pl apps/data-engine -am -DskipTests package

# 浏览器契约和应用类型检查
pnpm --filter @ontomato/contracts --filter @ontomato/ontology-manager \
  --filter @ontomato/ontology-manager-app --filter @ontomato/workbench-ui typecheck

# Node 25 的内置 localStorage 与 happy-dom 冲突时关闭内置实现
NODE_OPTIONS=--no-experimental-webstorage pnpm --filter @ontomato/ontology-manager test
pnpm --filter @ontomato/workbench-ui exec vitest run \
  src/features/system/components/__tests__/data-adapter-config.component.test.ts
pnpm --filter @ontomato/ontology-manager-app build
```

Java 命令需使用项目要求的 Java 21+。可选 live test 必须显式设置环境变量，选用非空且不超过 5,000 对象的小类；它读取 schema、样本、指定对象和该类的全量计数，不创建或修改任何对象。

```sh
ONTOMATO_ONN_LIVE_TEST=true \
ONTOMATO_ONN_LIVE_URL=http://192.168.1.108:18081 \
ONTOMATO_ONN_LIVE_CLASS=/demo/user \
./mvnw -pl packages/data-adapters/onn-core -am \
  -Dtest=OnnCoreLiveTest -Dsurefire.failIfNoSpecifiedTests=false test
```

2026-10-09 的最终验证结果：

| 验证 | 结果 |
| --- | --- |
| ONN HTTP、Schema、Adapter、DSL 离线测试 | 63 项通过；live 默认跳过 |
| 核心筛选回归 | 105 项通过，含首次问答、重试和 DSL 说明的真实提示词映射链 |
| Java 应用装配 | 1 项通过，PostgreSQL 与 ONN provider 同时可用 |
| Ontology Manager 测试 | 69 项通过 |
| Workbench 连接配置组件 | 6 项通过 |
| 4 个前端包类型检查 | 通过 |
| Data Engine 打包、Ontology Manager 应用构建 | 通过 |
| 显式远端 live | 1 项通过：138 类 Schema、指定对象 GET、`/demo/user` 的 108 个对象计数 |

离线合计 244 项通过。远端 live 只验证小规模单类 BASE 读取，不代表关系查询、权限治理、Edit 视图或快照一致性已通过。

未执行完整 Workbench + PostgreSQL + 模型的问答端到端验收，未迁移或部署业务数据。测试结果和 ONN 当前缺陷在[远端只读验证与团队需求](onn-core-live-probe-and-team-requirements.zh-CN.md)中记录；前期职责分析见[项目理解](project-understanding.zh-CN.md)和[接入可行性](onn-core-integration-feasibility.zh-CN.md)。

## 下一阶段接口依赖

完整图接入需要 ONN 团队提供严格且带版本的 DTO、可验证的完整性与稳定 cursor/snapshot、跨对象 bindings、完整邻居/边列表及稳定身份，修复多分支和 pipeline 的漏执行，并在全部读取路径统一权限与属性保护。需求及可验收案例已写入远端报告，当前未向团队发送消息。
