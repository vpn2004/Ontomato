# ONN Core 线上只读验证与团队需求

验证日期：2026-10-09（Asia/Shanghai）。目标：`192.168.1.108:18081`；历史端口 `18080` 仅做基础元数据对照。本文服务于 Ontomato 接入评估，未修改 ONN 源码、配置或远端数据。

**结论：基础 Schema 和实例读取可用，但现有 ABC 接口不能直接替代 Ontomato 的 M3 查询后端。条件丢失、变量绑定丢失、多分支过滤遗漏、静默截断和 Edit 未启用均已在线上复现。** 本分支已实现能力明确、范围受限的只读适配器；完整图查询、原生聚合、编辑合并与安全治理不能依据“HTTP 200”宣称兼容。

## 1. 验证边界与环境

验证只使用 GET 元数据、少量 `/demo/*` 样本和已知 ID 读取，以及三个只读查询 POST：`query/execute`、`query/pipeline`、`query/execute-abc-dsl`。没有运行 schema、实例、边、Action、Edit、向量或 seed 的创建、更新、删除；没有 SSH、启动服务或使用 admin 角色扩大访问。读取身份为无角色请求及少量 `reader` 对照。

指南将 18081 标为 v11，本地 ONN `VERSION` 为 `v11.0.0`；`/ping` 只返回固定状态文案，没有远端构建版本。**无法确认当前部署二进制与本地源码完全一致。** 本文“线上实证”和“源码风险”分别列出。源码参考为本地 `/Users/weichen/Documents/dev/onn-core`，检查时 HEAD 为 `0abecb5d74bb5247b8f652ad7f80fbd237582fd5`。

| 项目 | 18081 | 18080 |
| --- | --- | --- |
| `/ping` | 200，引擎运行状态 | 200，相同状态文案 |
| Schema 类 / 关系数量 | 138 / 112 | 1 / 0 |
| `schema_version_sum` | 271 | 5 |
| 全局实例 / 边数量 | 402,936 / 399,828 | 2 / 0 |

18080 是另一套小规模数据，不能用它的成功结果证明 18081 的查询、权限或 Edit 功能。

原始响应和请求仅保存在权限受限的临时目录，未写入仓库。报告只保留必要计数、字段名和匿名示例；以下示例中的 `R-A`、`P-A`、`P-B` 是示意名称，实际请求使用已存在的 demo 对象。

## 2. 已确认的接口形状

| 接口 | 线上状态 / 关键契约 | 接入含义 |
| --- | --- | --- |
| `GET /api/v1/schema/all` | 200；顶层 `ontologies`、`relations`、`schema_hash`、`schema_version_sum` | 可读取类、关系和属性元数据 |
| `GET /api/v1/schema/meta` | 200；类/关系数量、版本和 hash | 适合元数据检查，hash 不能直接与 `/all` 比较 |
| `GET /api/v1/schema/shape/metrics` | 200；全局实例/边计数 | 是总体统计，不能证明某次查询结果完整 |
| `GET /api/v1/data/sample?class=…&limit=2` | 200；`samples`、`total_count` | 已验证 demo 样本；抽样结果不能用于全量业务计算 |
| `GET /api/v1/data/edge-sample?relation=…&limit=4` | 200；边有 `relationPath`、`fromId`、`toId` | 当前返回未提供稳定边 ID、分页完整性或绑定行 |
| `GET /api/v1/data/instance?class_path=…&id=…` | 200；`id`、`classPath`、`attributes` | 必须同时传类路径和 ID；指南中省略类路径的例子不可直接沿用 |
| `POST /api/v1/query/execute` | 200；`data`、`projected`、`metrics`、`matched_count` | 使用 native GraphQuery DTO，不能把 `matched_count` 当总数 |
| `POST /api/v1/query/pipeline` | 200；`steps_executed`、按 step 名组织的 `data` | 可执行，但 `from_step + traverses` 已复现漏执行 |
| `POST /api/v1/query/execute-abc-dsl` | 支持自己的 ABC DTO；`status`、`data[].answer` | 与 Ontomato 当前 ABC 结构和语义不兼容 |
| `GET /api/v1/edit/merged?classPath=…&instanceId=…` | 503：`合并引擎未启用` | 当前不能读取编辑合并视图 |
| `GET /api/v1/edit/history?classPath=…&instanceId=…` | 503：`编辑层未启用` | 当前不能验证编辑历史 |

Schema 类字段为 `path/name/description/parentPath/attributes/version/schemaHash`；关系字段含 `sourceClass/targetClass/bidirectional/attributes/annotations`。`parentPath` 表示存在继承结构，导入时需明确定义继承属性和实例查询语义，不能仅把父子类当不相关的平面表。

真实 Schema 还含普通中文类/关系名、55 个空类型属性及 `text/json/double` 等 26 个非基础枚举属性；13 条关系没有 sourceClass/targetClass。当前投影保留中文源名，将非基础/空类型属性设为禁用，不猜测类型语义；空端点关系不生成关系规则，并在数据集说明列出。非空但不存在的关系端点、缺失父类、继承环和重复定义仍拒绝导入。

实例 `attributes` 不一定严格等于 Schema 属性集合：demo 用户实例可携带 Schema 未声明的字段。适配器应按显式 Schema 做列映射，同时保留稳定对象身份；不能推断未声明字段天然公开。

Native 的小型查询形状为：

```json
{
  "match_class": "/demo/review",
  "where": [{"attribute": "text", "operator": "eq", "value": "<已知 demo 文本>"}],
  "limit": 2,
  "output": {"fields": [{"attribute": "rating", "as": "rating"}]}
}
```

原生返回 `data[].id`，投影自动添加 `_id`。`output.fields[].attribute = "id"` 只读取 `attributes.id`，不会映射到对象的 `id`，因此 alias 为 null。适配器必须显式处理对象 ID，不能依赖属性投影替代身份字段。

## 3. 线上已复现的问题

### 3.1 跨类变量与源端字段丢失

已有关系 `R-A → P-A`、`R-A → P-B`。原生单条遍历 `/demo/review → /demo/product` 返回两个 product 对象，包含叶端 `_id` 和 product 属性；源端 review 不保留在结果绑定中。把源端 `rating` 投影成 `source_rating` 得到 null。这证明接口返回的是叶端对象集合，不能直接作为 Ontomato 的跨类关系行。

使用 ONN 支持的 ABC DTO，分别指定 `r.rating`、`p.price`、`r.id`、`p.id`，结果仍只有叶端 `_id` 和 `p.price`；`source_rating/source_id/target_id` 为 null。**variable 声明没有保留跨对象字段绑定。**

同一已知目标属性值放在原生 `traverse.next.where` 中，返回 1 个 product；放在 ABC 的 `conditions.properties` 中并标注 `variable: "p"`，返回 0 行。源码将所有 properties 转为根对象 `query.Where`，没有用 variable 分配到目标节点。不能把 ABC 空结果解释为“业务上不存在匹配”。

### 3.2 当前 Ontomato ABC 结构不能直通

| 小型对照 | 实际结果 |
| --- | --- |
| 不可能匹配的条件放在 `graph.patterns[].objects[].conditions.properties` | 200，仍返回 2 行；条件被静默忽略 |
| 相同条件移到 ONN 支持的 `step.conditions.properties` | 200，0 行 |
| `output.limit = {"offset": 0, "limit": 2}` | 400；解析错误明确要求 `limit` 为 int |
| 同时输出普通字段和 count 聚合 | 200，只有普通投影字段，count 消失 |

ONN 当前关系 DTO 使用 `relation_path/fromObject/toObject/direction`，输出 limit 是整数、offset 是相邻字段。Ontomato 还有对象内条件、关系索引和 output 内临时表等结构，不能只改 URL。未知字段被忽略与错误类型被拒绝并存，尤其容易产生“请求成功但含义改变”的结果。

### 3.3 多分支漏掉过滤与嵌套遍历

在一个已知 review 根对象上设置两个 `/demo/reviews` 分支，结果如下：

| 分支目标的条件 | 实际结果 |
| --- | --- |
| `next.where` 中的不可能 category 条件 | 200，0 行 |
| 同一条件改为 `next.where_logic` | 200，仍返回根对象 1 行 |
| `next.traverse` 再回到 review，末端为不可能条件 | 200，仍返回根对象 1 行 |

这不是因业务数据不同而出现的差异：请求根对象、关系和不可能值相同，只改变条件结构。源码的多分支检查只判断一跳邻居的 `Where` 和时间约束，未递归执行 `Next.Traverse(s)` 或评估 `Next.WhereLogic`。

另外，仅一个 `traverses` 元素与两个元素的输出类不同：一个元素按单遍历返回目标类，两个元素按 star existence 返回源类。适配器不能仅用字段名判断输出对象类别。

### 3.4 分支上限改变结果，没有完整性反馈

对 demo review 类设置两个相同关系分支、`limit=2` 和对象 count：

| 请求 | `matched_count` | count 指标 | 完整性标记 |
| --- | --- | --- | --- |
| `branch_limit=1` | 1 | 1 | 无 |
| `branch_limit=200` | 2 | 150 | 无 |

两者均为 HTTP 200。`limit=2` 只是最终分页，`branch_limit=1` 则先截断参与集合和聚合。没有 `complete/truncated/has_more/cursor/snapshot` 表明哪份 count 是完整计算。当前适配器必须把不能证明完整的关系聚合视为不支持或失败。

### 3.5 Pipeline 的 from_step 与 traverses 不兼容

第一步得到一个已知 review；第二步 `from_step` 引用第一步并附带目标不可能匹配的 `traverses`。第二步仍返回 seed review 1 行。相同条件独立执行返回 0 行。源码判断是否遍历只检查 `Traverse == nil`，没有检查 `Traverses`。

此次 pipeline 对未要求属性输出的结果返回空 `attributes`，因此也不能默认“pipeline 的实例 DTO 与普通 GET 的属性完备性相同”。

### 3.6 计数、投影和路径语义必须明确

`/demo/review` 原生 `limit=1 + count` 返回 `matched_count=1`、count=150。`matched_count` 是当前页 `data` 长度，不是总匹配数。

`/demo/user` 的 shape 显示 status 填充率约 94.44%、marker 约 7.41%。原生 `count(status)`、`count(marker)` 与无属性对象 count 均为 108。**当前 count 不区分属性缺失。** 如果 Ontomato 编译的是 SQL 的 `COUNT(column)`，该结果不能直接替代；如果 ONN 将 count 定义为对象数，则需要明确契约并区分两个操作。

Native 投影输出同时仍返回完整 `data[].attributes`；对 product 只请求 price，raw data 仍包含 embedding 等属性。此测试使用公开 demo 属性，证明的是“投影不构成数据裁剪”，**没有证明受保护字段泄漏**。

已知 review 经 product 回到同一 review，原生查询得到 0；独立从对应 product 回到该 review 得到 1。源码将初始 ID 加入共享 visited，禁止再次到达。它不是保留路径 tuple 的 JOIN 语义；visited 键还只有 ID，没有类路径。不能据此计算路径重数或跨类同 ID 身份。

### 3.7 Edit 视图未装配

对已知 demo 实例，merged 和 history 读取均返回 503。普通 GET 与原生 query 可返回实例，但当前不能证明它们叠加了编辑覆盖、软删除或历史快照。不能把基础实例读取描述为“合并后当前对象”。

## 4. 源码风险与尚未在线上证明的项目

以下仅基于本地源码，不等于已确认远端权限配置或二进制行为：

| 项目 | 源码证据 / 风险 | 验证边界 |
| --- | --- | --- |
| 属性安全过滤 | `PropertySecurityFilter` 有定义，但生产 GET、sample、native、pipeline、ABC 输出路径未调用 | 当前 Schema 未找到安全标记/角色 fixture；reader 与无角色读取同一 demo 相同，不能证明保密字段安全或泄漏 |
| 资源权限 | 默认 ResourceGovernance 关闭；角色直接解析 `X-OMS-Roles`；ABC、sample 等部分路径缺资源 authorize，pipeline 只检查第一类 | 没有测试 admin 伪造，也不知道部署代理是否清洗身份头 |
| 安全过滤 fail-open | 过滤器遇 Schema 错误、缺定义、未知安全级别时可按公开字段处理 | 未触发线上异常，也未制造敏感字段 |
| count_distinct | 属性值使用 `fmt.Sprintf("%v")` 作 key；存在的 nil 被计入，数值 1 与文本 `"1"` 可合并 | 无受控异构类型 fixture，未做 live 对照 |
| 聚合推断与脱敏 | 部分数据治理 mask 在返回分页对象阶段执行，而指标使用 mask 前实例 | 未读取或尝试推断敏感属性 |
| 默认上限 | 未显式 limit 时默认 10,000；分支默认 10,000，最多 5 个分支 | 本次没有大范围数据扫描来验证上限边界；显式分支上限导致的静默不完整已实证 |
| 根类全量读取 | 显式 `match_class + limit=5001` 不受 10,000 默认值改写，先全类扫描再限制候选；但扫描错误可吞为空成功，个别 hydrate 错误可跳过对象，可选 late materialization 取消时返回部分数据 | 没有追加线上大扫描；`data.length <= 5000` 不能单独证明总类大小或完整性；分页 offset 还受无稳定顺序影响 |
| 临时表/多步 ABC | `save_table` 只成为 pipeline step 名；不会自动建立 `from_step` 或持久临时类；当前 ABC handler 取数组首个请求 | 未执行需要创建临时类的操作，适配器不可推断兼容 |
| Snapshot、继承、完整分页 | 当前响应没有统一快照版本；继承字段、父类实例查询和跨分页一致性未完成受控验收 | 元数据存在 parentPath；尚未验证全部语义 |

`schema/meta` 与 `schema/all` 的 hash 不同已有线上观测；本地源码使用不同输入计算 hash，并且只有 meta 添加 `sha256:` 前缀。这不应直接认定为 Schema 变化。适配器若缓存元数据，应固定一个端点的同一 hash 规则；ONN 团队应统一或明确区分两个 hash 的含义。

## 5. 给 ONN 团队的可验收需求

### P0：避免成功响应掩盖查询含义变化

1. **发布版本与严格 DTO 契约。** 提供运行版本、构建 ID、支持的 DSL 版本和能力；提供 OpenAPI/JSON Schema。未知条件位置、未知字段、未支持的变量引用/临时表/嵌套条件必须返回明确 4xx，不能忽略后执行。统一 native、pipeline、ABC 校验与错误码。
   - 验收：本报告的 object-local 不可能条件要么得到 0 行，要么拒绝为不支持；不能返回匹配行。Ontomato limit 对象必须明确拒绝或完整翻译。
2. **返回可证明的完整性。** 结果带 `complete/truncated/reason/has_more`，分页提供稳定 cursor；指标说明输入是否完整及计数范围，响应包含 schema/data snapshot 标识。任何 branch cap、超时、降级、扫描限制触发都应显式标注或失败。不能用空结果冒充无匹配。
   - 验收：branch_limit=1 与 200 的结果不能同样宣称完整；聚合若不完整，调用方能拒绝展示“总数”。跨页读取可验证同一快照。
3. **修复递归分支与 pipeline 执行。** 同一语义用 Where/WhereLogic 表达应一致；Next.Traverse(s) 按递归 AST 执行；from_step 与 Traverse/Traverses 组合都执行；输出类别和 optional/AND/OR 规则明确。
   - 验收：本报告三个不可能分支用例及 pipeline 对照均得到 0；至少覆盖两层嵌套、AND/OR、optional 和 from_step。
4. **覆盖所有读取路径的授权和属性保护。** GET、sample、shape/values、native、pipeline、ABC、MCP 的类/关系/字段权限规则一致；聚合、排序、过滤、错误消息不能越权推断受限字段。身份来自受信认证上下文，客户端角色头必须在边界清洗或校验。
   - 验收：ONN 团队在隔离测试环境准备公开/受限字段、不同角色和异常 Schema 的 fixture，验证原始 `data`、投影与指标都符合权限。此需求源于源码风险，未宣称线上存在保密数据泄漏。

### P1：提供 Ontomato 图模型和分析所需的语义

5. **完整邻居/边列表 HTTP API、跨对象绑定行与稳定身份。** 邻居接口明确接收 `class_path + id + relation_path + direction`，保留源/目标类路径与完整 ID、稳定 edge identity 和关系属性，并返回稳定分页 cursor、`complete/has_more` 与 snapshot 标识。`edge-sample` 是抽样接口，不能作为完整邻居接口。分析接口另提供 `bindings` 或等价 tabular rows：`r.id/r.rating/p.id/p.price` 均按变量解析，并明确 bag/set、路径和边重数。对象主键使用 `(class_path, id)` 或全局 ID。
   - 验收：指定一个含冒号 ID、多个邻居和关系属性的 fixture，分页拼接与完整单次读取一致，方向和关系过滤正确，超时/截断不能宣称 complete。`R-A→P-A`、`R-A→P-B` 返回两条绑定行，每行有正确源/目标 ID、属性与边身份；同 ID 不同类不会被 visited 合并。路径回环是否允许必须明确，不能静默改变 JOIN 语义。
6. **定义聚合契约。** 区分对象数、非空属性数、类型保真的 distinct；定义 null/missing、GROUP BY/HAVING、空集合与数值类型；投影与聚合混用时同时返回二者。
   - 验收：公开 fixture 中有缺失、null、数值 1、字符串 `"1"`、多条关系；count/count_distinct 结果按文档可预测。ABC 普通字段与 count 混用不丢 metric。
7. **启用并明确 Base/Edit/Merged 读取。** 标注服务是否配置 Edit Repo/MergeEngine；指定默认视图、软删除、覆盖来源和 snapshot 行为；GET/query/pipeline 对同一视图保持一致。未配置时声明不支持。
   - 验收：隔离测试数据的覆盖/删除结果在 GET 和 query 一致；history/snapshot 可追溯；生产当前 503 状态可被 capabilities 检测。

### P2：降低接入和运维成本

8. **统一 Schema hash 和继承契约。** 文档区分结构 hash/版本 hash，输出算法和范围；明确 parentPath、继承字段、父类查询是否含后代、override 规则；提供变更检测与一致读取。
9. **提供可自动运行的只读契约套件。** ONN 团队维护小型隔离 fixture 与版本化请求/期望结果，覆盖本报告案例、完整分页、字段授权和异常降级。适配器每次升级跑套件，而不是依据功能列表宣布兼容。

## 6. Ontomato 适配器的当前边界

**当前已实现有限只读第一阶段，不是完整替换。** Schema/单类对象读取和有限本地求值由 adapter 显式声明；`graphRead=false`，所有关系 DSL、写入、Schema 写入、sandbox 与文档向量属性均明确拒绝。

- 禁止把 Ontomato ABC 原样发到 ONN ABC endpoint；不依赖当前多分支、from_step、循环遍历、跨类绑定或 native 聚合得到完整 SQL 语义。
- 精确对象查询使用 `(class_path, id)` 的 GET；类/实例 ID 映射必须可逆且防碰撞。保留业务属性与对象身份的边界。
- 虚拟 `id` 固定读取实例顶层身份，`sourceName=@identity`；同名业务属性生成防碰撞别名并保存原始 `sourceName`。执行前读取新 Schema 并核对导入映射的源名、类型、主键和启用状态，防止别名漂移。样本读取先验证已导入的类和虚拟身份，再过滤显式 disabled 属性；缺少映射或字段漂移明确失败，不返回空对象成功。
- 全类拉取用于本地求值时设置有限上限，例如最多接受 5,000 对象、请求 5,001 作为越界哨兵；超过上限明确失败。返回少于上限只是必要条件：当前源码可能静默遗漏，必须对照独立读取的 `total_count`、去重数量与类路径，并检查所有错误/降级字段；缺少快照时仍无法保证并发一致性。不能用 sample 填补全量读取，也不能把该方法称为已证明完整的远端分页协议。
- 当前完整类路径交叉检查前后两次 sample `total_count`、native `metrics.total`、`matched_count`、去重对象数和类路径；计数必须为非负精确整数。证据标记 `BASE`、Schema fingerprint、对象数及 `snapshotConsistent=false`；`mqls` 记录 Schema 与前/后计数 HTTP 请求。相同基数的并发更新仍可能未被发现。
- HTTP 读取默认 12 秒总期限（含响应体）、8 MiB 响应上限、3 秒连接期限，不跟随重定向；非 200、无效 DTO、错误/降级/不完整标记均失败。唯一空集合例外是精确对象 GET 的 404。某些空类 native 请求可能返回 422，此时明确失败，不猜测零计数。
- 路由没有完整邻居接口。`edge-sample` 只有关系级样本，无总数、稳定边身份或分页完整性；源码按未转义冒号拆 key，带冒号 ID 还可能损坏。关系 shape 是另一次带缓存及扫描保护的计数，不共享 sample 的快照，不能证明边样本完整。因此保留 `graphRead=false`，不以 edge-sample 或现有遍历作回退。
- 对继承类、Schema 未声明字段、模糊 ID、Edit 视图和安全治理未确认的情况明确限制。请求出错、DTO 不符、降级或必要计数缺失时失败，不回退成空表、零计数或别的连接器。单类读取采用上述计数核对；需要完整关系或一致快照但接口无法证明时，拒绝相应查询。

这些限制允许开展受控接入开发，但不会把底层未实现的能力包装成可用业务功能。

### 已执行的回归与 opt-in live 验证

离线 HTTP/Schema/Adapter/Compiler 契约测试共 **63 项通过**（20/13/16/14）；默认执行共 64 项，live 1 项跳过。测试使用合成响应和临时 loopback HTTP 服务，不访问业务系统。过滤执行命令：

```sh
env JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
  ./mvnw -o -pl packages/data-adapters/onn-core -am \
  '-Dtest=OnnCore*Test' -Dsurefire.failIfNoSpecifiedTests=false test
```

2026-10-09 15:30（Asia/Shanghai）显式设置 `ONTOMATO_ONN_LIVE_TEST=true`、`ONTOMATO_ONN_LIVE_URL` 和 `ONTOMATO_ONN_LIVE_CLASS=/demo/user` 后，同一过滤命令得到 **64 项全通过，含 1 项真实 live**。live 完整导入 138 类 Schema，以已存在样本 ID 验证 GET，并通过前/后计数交叉检查和本地 `COUNT(id)` 验证 108 个对象的 BASE 读取；没有修改远端数据。默认没有这些环境变量时不会运行 live；URL/实例响应不存入仓库。该结果仅证明这项单类小规模只读路径，不证明关系查询、权限、Edit 或快照一致性。

## 7. 关键源码定位

下列链接指向此次审查的本地源码，不表示已确认远端二进制版本：

| 主题 | 定位 |
| --- | --- |
| 路由只有 edge-sample，没有完整邻居读取 | [router.go](/Users/weichen/Documents/dev/onn-core/internal/api/router.go:85) |
| edge-sample 只返回 relation/samples，无完整性 | [handlers.go](/Users/weichen/Documents/dev/onn-core/internal/api/handlers.go:1989) |
| 边样本按 limit 截断、冒号 key 拆分损坏身份 | [graph.go](/Users/weichen/Documents/dev/onn-core/internal/domain/graph.go:233) |
| 关系 shape 缓存/扫描保护、独立计数 | [shape_engine.go](/Users/weichen/Documents/dev/onn-core/internal/domain/shape_engine.go:182) |
| 原生 DTO 与 QueryResult 无 completeness 字段 | [dsl.go](/Users/weichen/Documents/dev/onn-core/internal/domain/dsl.go:146) |
| ABC variable 条件落根、输出字段翻译 | [abc_dsl_translator.go](/Users/weichen/Documents/dev/onn-core/internal/domain/abc_dsl_translator.go:164) |
| ABC 投影优先导致 metrics 丢失 | [abc_dsl_translator.go](/Users/weichen/Documents/dev/onn-core/internal/domain/abc_dsl_translator.go:245) |
| matched_count 等于返回 data 长度 | [handlers.go](/Users/weichen/Documents/dev/onn-core/internal/api/handlers.go:2251) |
| Pipeline 只检查 Traverse | [executor.go](/Users/weichen/Documents/dev/onn-core/internal/domain/executor.go:261) |
| 多分支仅检查一跳条件并按 branch limit 提前结束 | [executor.go](/Users/weichen/Documents/dev/onn-core/internal/domain/executor.go:2613) |
| count/count_distinct | [executor.go](/Users/weichen/Documents/dev/onn-core/internal/domain/executor.go:1951) |
| projection 读取 attributes 后添加 _id | [executor.go](/Users/weichen/Documents/dev/onn-core/internal/domain/executor.go:2037) |
| visited 以 ID 防止重复到达 | [executor.go](/Users/weichen/Documents/dev/onn-core/internal/domain/executor.go:2222) |
| 根类扫描错误和 hydrate 错误的遗漏 | [executor.go](/Users/weichen/Documents/dev/onn-core/internal/domain/executor.go:2201)、[executor.go](/Users/weichen/Documents/dev/onn-core/internal/domain/executor.go:1054) |
| 可选 late materialization 取消后返回部分 | [executor.go](/Users/weichen/Documents/dev/onn-core/internal/domain/executor.go:1389) |
| Edit merged 未装配返回 503 | [edit_handler.go](/Users/weichen/Documents/dev/onn-core/internal/api/edit_handler.go:176) |
| 属性安全过滤定义 | [property_security.go](/Users/weichen/Documents/dev/onn-core/internal/domain/property_security.go:48) |
| 资源治理默认关闭 | [resource_governance.go](/Users/weichen/Documents/dev/onn-core/internal/domain/resource_governance.go:93) |
| 两个 Schema hash 算法 | [handlers.go](/Users/weichen/Documents/dev/onn-core/internal/api/handlers.go:873)、[handlers.go](/Users/weichen/Documents/dev/onn-core/internal/api/handlers.go:917) |

没有为证明风险而改造真实 ONN 数据。受保护字段、typed distinct、Edit 合并、继承和稳定分页的剩余验收，应由 ONN 团队在可写隔离 fixture 中完成。
