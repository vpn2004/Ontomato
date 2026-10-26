# ONN Core v12 回复核查与剩余验收项

核查日期：2026-10-22。源码基线：`0c9b035d00f50af6310af88bd40f2f63ceb7d091`。

**结论：v12 有实质修复，但不能按回复中的“7 项已修复”关闭原需求。权限、完整性、递归与 Pipeline 仍有可复现问题；完整图读取所需的绑定、邻居和快照能力也尚未交付。**

原始需求以 [ONN Core HTTP 数据读取与图查询功能需求](/Users/weichen/Documents/dev/Ontomato/docs/onn-core-team-functional-requirements.zh-CN.md) 的全部验收条件为准。团队修复了某项中的一个问题，不等于该项全部完成。此次只核查和记录，未修改 adapter 实现。

## 1. 核查范围与证据边界

核查材料：

- [团队 v12 对接变更说明](/Users/weichen/.gemini/antigravity-ide/brain/20cd23b0-337d-4025-ad7e-ed160892daed/V12_INTEGRATION_CHANGES.zh-CN.md)。
- ONN Core 本地提交 `daa69d37` 和后续修正 `0c9b035d`，以及现有 adapter 源码。
- 团队 `v12_e2e.sh` 的测试内容；未执行该脚本，因为它会写入 Schema、实例和边。
- 本地内存 fixture 的查询回归，以及本地 `httptest` 权限回归。通过 Go overlay 引入临时测试；测试文件位于 `/tmp`，未写入 ONN Core 目录。
- 对现有服务只执行 GET：版本探测和 Schema 元数据读取。没有写入远端数据，也没有对远端真实数据执行越权测试。

### 实际服务尚不能验收 v12

| 地址与请求 | 本次结果 | 说明 |
| --- | --- | --- |
| `192.168.1.108:18081/ping` | 200，仅返回 `status` | 无法识别构建版本 |
| `192.168.1.108:18081/api/v1/system/version` | 404 | 未暴露回复所述的 v12 版本契约 |
| `127.0.0.1:18082/ping`、`/api/v1/system/version` | 连接被拒绝 | 团队验收脚本默认地址当前不可用 |

这些结果不能确定服务实际运行的是哪份源码。因此下文的“本地复现”证明本地上述提交存在问题，不能描述成已在该远端证实泄漏或复现全部 v12 行为。需要团队交付可识别版本的隔离验收地址。

## 2. 按原需求逐项核查

| 编号 | 确认已有的进展 | 尚未满足的原验收条件 | 本次结论 |
| --- | --- | --- | --- |
| R01 版本与严格契约 | 新增版本/组件能力接口；native、Pipeline 使用严格 JSON 解码；history 参数增加兼容 | ABC 仍忽略未知字段；缺少完整 DSL/错误契约和细分能力；参数兼容未覆盖全部相关接口；版本示例字段与实现不同 | 部分实现 |
| R02 完整性、分页、快照 | 新增页数、总数、后续页与截断字段；类扫描排序 | 过滤查询总数可能错误；截断标记漏报；Pipeline 元数据未填充；未交付 cursor、快照和查询 Schema 版本 | P0 未通过 |
| R03 递归与 Pipeline | 多分支目标检查传入 `where_logic`；`from_step` 开始识别 `traverses` | 多分支下一层遍历仍被忽略；`from_step` 当前层条件未执行；ABC 变量条件仍未按变量路由 | P0 未通过 |
| R04 跨对象绑定与身份 | 回复承认设计缺口 | 绑定行、变量投影、边/路径重数和完整身份契约未交付 | 团队延期 v13 |
| R05 完整邻居与边列表 | 回复承认缺口 | 完整邻居分页、稳定边身份、关系属性读取未交付 | 团队延期 v13 |
| R06 聚合与投影 | native 一条执行路径增加原始属性裁剪 | Pipeline 未裁剪；ABC 混合输出仍丢指标；对象数/非空数、类型保真 distinct 等契约未补齐 | 部分实现，回归未通过 |
| R07 属性权限 | 普通 GET/native 的 `data` 增加过滤；无角色不再默认 admin | native 的投影和指标可绕过；其他入口未统一；Schema 异常放行；认证与可信角色边界未完成验收 | P0 未通过，已有本地 HTTP 复现 |
| R08 Schema 类型与版本 | 团队表示可按类路径审计 | 类型、继承、草稿/抽象状态、fingerprint 用途及查询版本仍缺正式契约 | 未完成，已补充元数据问题清单 |
| R09 Base/Merged/历史 | 启动时装配 EditRepository、MergeEngine | 视图选择与回传、点查/查询一致性、覆盖/删除/历史与权限验收未完成 | 部分实现 |
| R10 验收交付包 | 提供 33 项 E2E 检查脚本 | OpenAPI/JSON Schema 延期；脚本断言范围不足；没有覆盖原需求的隔离验收包 | 未完成 |

R01 的字段差异：`/api/v1/system/version` 实际返回 `build_commit`，回复示例写成 `commit`；`/ping` 才使用 `commit`。`property_security=true` 当前仅代表过滤器已装配，不代表所有接口已经通过权限验收。见 [version.go](/Users/weichen/Documents/dev/onn-core/internal/api/version.go:39)。

history 的 snake_case/camelCase 兼容不能概括成所有编辑接口的参数已经统一；merged、snapshot、effective 仍读取 camelCase。见 [edit_handler.go](/Users/weichen/Documents/dev/onn-core/internal/api/edit_handler.go:158)。

## 3. 查询回归：7 个测试均未通过，覆盖 8 类表现

使用 ONN Core 自带的内存 fixture：3 位员工、2 个部门、2 个项目及两种关系。以下期望来自原需求，没有访问远端业务对象。

| 回归场景 | 预期 | 实际结果 | 对应需求 |
| --- | --- | --- | --- |
| 两个必选分支，其中一个分支的下一跳含不可能条件 | 0 条 | 返回 3 条员工 | R03 |
| Pipeline 第二步 `from_step` 后设置不可能的 `where` | 0 条 | 返回 3 条员工 | R03 |
| Pipeline 只投影部门 `name` | 原始业务属性也只含所选字段 | `data[].attributes` 仍带 `budget` | R06 |
| 上述 Pipeline 返回完整性元数据 | `page_count=2,total_matched=2` | 数据有 2 条，两个元数据字段均为 0 | R02 |
| 3 位员工均符合 `age>0`，`where_logic` 配合 `limit=1` | `total_matched=3,has_more=true` | `total_matched=1,has_more=false` | R02 |
| 3 位合格员工，`branch_limit=2,limit=1` | 内部截断被标识 | `total_matched=2,truncated=false`，漏掉第三位合格员工 | R02 |
| ABC 格式化结果同时有普通投影和 count 指标 | 同时保留，或明确拒绝不支持的组合 | 指标被静默丢弃 | R06 |
| ABC 解析含未知字段的 JSON | 明确拒绝未知字段 | 解析阶段接受未知字段 | R01 |

Pipeline 的投影和元数据检查属于同一个测试，因此这里是 7 个测试、8 类表现。ABC 两项分别验证格式化函数和解析函数；不冒充完整 HTTP 验收。

相关代码与原因：

- [多分支分支检查](/Users/weichen/Documents/dev/onn-core/internal/domain/executor.go:2558) 只判断直接邻居的条件，没有递归执行 `branch.Next.Traverse(s)`。
- [Pipeline from_step 路径](/Users/weichen/Documents/dev/onn-core/internal/domain/executor.go:251) 取前一步 ID 后直接遍历或输出，跳过当前层 `where/where_logic`；[Pipeline 输出路径](/Users/weichen/Documents/dev/onn-core/internal/domain/executor.go:347) 未统一调用新增的裁剪及完整性填充。
- [过滤查询总数](/Users/weichen/Documents/dev/onn-core/internal/domain/executor.go:2146) 先按结果窗口限制候选，再将其长度当作总匹配数。总数不能准确计算时应返回“未知”，不能返回当前页规模并宣称没有后续页。
- [截断标记](/Users/weichen/Documents/dev/onn-core/internal/domain/result_completeness.go:38) 仅考虑显式正数 `branch_limit`，调用方还使用最终分页后的行数判断，无法可靠反映执行阶段实际丢弃的候选。
- [ABC 输出格式化](/Users/weichen/Documents/dev/onn-core/internal/domain/abc_dsl_translator.go:245) 对 `Projected` 和 `Metrics` 使用互斥分支；[ABC 解码](/Users/weichen/Documents/dev/onn-core/internal/domain/abc_dsl_translator.go:118) 仍用普通 `json.Unmarshal`。

源码还保留扫描/邻居读取失败后转为空集合或继续执行的路径，需要纳入错误注入验收。类扫描排序也不能证明所有图查询分页稳定，例如多分支 OR 的集合合并仍有 map 迭代路径。即使排序全部稳定，仍不等于并发读取的一致快照。

## 4. 权限回归：普通查询已过滤，投影和指标仍可绕过

在本地 `httptest` 中，使用团队自己的安全 fixture：`salary` 为 confidential，仅 `hr_admin/finance` 可读；调用角色为 `reader`。测试薪资为合成数据 `50000`。

| reader 请求 | 实际响应 | 判定 |
| --- | --- | --- |
| 普通 native 对象读取 | `data` 正确移除薪资 | 对照子用例通过 |
| 显式投影 `salary as salary_alias` | `data` 移除薪资，但 `projected.salary_alias=50000` | 权限子用例失败 |
| `sum(salary)` | `metrics.salary_total=50000` | 权限子用例失败 |

直接原因是 [ExecuteQuery](/Users/weichen/Documents/dev/onn-core/internal/api/handlers.go:2278) 仅过滤 `results.Data`，之后原样返回已经从原始属性计算的 `results.Projected` 和 `results.Metrics`。仅对响应字段做删除也不能解决受限字段条件、排序和指标产生的推断，需要在执行前检查访问权限。

其余为源码确认、尚未逐一做 HTTP 复现的风险：

- [Pipeline](/Users/weichen/Documents/dev/onn-core/internal/api/handlers.go:2363) 直接返回各步骤结果，[ABC](/Users/weichen/Documents/dev/onn-core/internal/api/handlers.go:3189) 直接格式化结果；sample、shape/values、编辑 merged/history/snapshot 等未统一接入同一保护。
- [Schema 读取失败或没有属性定义](/Users/weichen/Documents/dev/onn-core/internal/domain/property_security.go:63) 时返回全部属性；[未知安全标记](/Users/weichen/Documents/dev/onn-core/internal/domain/property_security.go:130) 被映射为最低级别。这不满足原需求的异常时禁止放行。
- `X-OMS-Roles` 本身没有认证。网关认证后清洗外部角色头、注入可信角色可以成为方案，但仍须交付部署配置、禁止绕过网关直接访问服务，并验证伪造角色失败。不能以客户端自行发送 `admin` 作为修复。

因此，R07 应继续标为“部分实现、验收未通过”，不能根据普通 `data` 已过滤或能力开关为 true 就关闭。

## 5. 尚未交付的契约与 Schema 审计

### 完整读取和跨对象结果

R02 原本要求 cursor、数据快照、Schema 版本以及快照失效错误；本轮未交付。`offset + 稳定排序` 不能防止翻页期间插入、删除或同数替换对象造成遗漏。

R04/R05 可按团队计划放入 v13，但必须保留为未完成需求。多次请求后手工拼接，尚不能替代具有边身份、路径重数、复合对象身份与一致快照的绑定结果；关系抽样也不能替代完整邻居列表。

R06 原需求不只涉及裁剪：对象数与非空属性数、保留类型的 distinct、混合输出、分组及不完整输入的统计语义仍需补齐。现有 count 仍直接使用实例数量，distinct 仍有值字符串化的实现。未完成前不应把服务端统计视为完整关系分析的可靠基础。

### R08 已补充可定位清单

本次 GET 同一服务的 Schema，仍观察到 138 类、112 关系，其中 55 个空类型属性、26 个非六种基础枚举类型属性、13 条缺少两端类的关系。六种基础类型的统计口径包括 `vector`；其他类型不自动等于无效类型。

已将**全部相关类路径、属性名/类型、关系路径与缺失端点**整理在 [本地元数据问题清单](/tmp/onn_v12_review_readonly/schema-issues-2026-10-22.json)，不含业务对象属性值。团队可以据此开展 Schema 审计，不必先提供业务实例 ID。

数据清理之外，R08 仍需定义类型表、继承/覆盖规则、草稿/抽象/可查询状态、hash 输入与用途、Schema 查询版本。不同用途 hash 不同不直接等于数据错误；应由契约明确解释。

### R09 装配完成不等于视图一致性通过

[main.go](/Users/weichen/Documents/dev/onn-core/cmd/oms/main.go:119) 已增加 EditRepository 和 MergeEngine，确实回应了原部署遗漏。但普通 GET 和查询仍使用原实例仓库；没有统一的视图选择和结果视图标识。还需用覆盖、软删除、历史和受限字段 fixture 验证点查、列表、查询、Pipeline 在同一视图下的一致性。

空历史请求得到 200，只能证明端点可调用，不能证明编辑历史链路、合并值和权限已经正确。

## 6. “33 项 E2E 通过”能够证明的范围

团队脚本是积极进展，但没有覆盖原需求的全部验收条件：

- 脚本会创建 `/e2e/*` Schema、实例和边，必须运行在团队提供的隔离环境；本次没有对现有服务执行它。
- 权限只检查无投影的 native 结果，没有检查别名投影、指标、Pipeline、ABC、样本与编辑历史。
- E2E 的“不可能条件”仅有一个分支；两个分支用例只测正例，没有下一跳不可能条件。本地另外已有多分支 `where_logic` 单元测试，不能把 E2E 的缺口说成团队没有任何该项测试。
- Pipeline 只检查响应包含 `s2` 且不包含 `failed`，没有断言对象 ID、类、数量和条件执行结果。
- 分页只检查少量页面和简单类扫描，没有完整集合基准、执行上限、过滤总数与并发快照验收。
- history 检查 HTTP 200，没有实际覆盖/删除/历史值断言。

因此“33 项脚本检查通过”不能替换“R01—R10 原验收条件通过”。需将上面已经失败的回归纳入团队验收包，并断言真实值和完整集合。

## 7. 建议发给 ONN Core 团队的本轮补交项

以下是 ONN Core 自身的交付要求：

1. **P0：补齐属性权限。** 所有输出容器和查询运算执行统一授权；覆盖所有读取入口；Schema/策略异常不能放行；交付可信认证/角色边界及隔离凭据。以 reader 无法取得或推断受限字段、授权角色可正常使用为验收条件。
2. **P0：修正完整性元数据。** 让执行器在实际发生候选截断时记录并向上传播；总数准确或明确未知；Pipeline 同样填充。将本次 `age>0,limit=1` 与 `branch_limit=2,limit=1` 纳入固定回归。cursor/快照若本期不做，明确延期，R02 不标完成。
3. **P0：完整执行已接收的条件。** 多分支递归下一跳、`from_step` 当前层条件必须生效；ABC 变量条件按变量路由；不支持的结构明确拒绝。所有“不可能条件”用例应返回 0 或可识别错误。
4. **补齐投影与 ABC 校验。** Pipeline 与 native 采用同等字段裁剪；ABC 混合输出不静默丢失；未知字段/位置统一严格校验；同步版本字段、参数与示例。
5. **交付可识别的隔离验收环境。** 提供版本、commit、启用配置、fixture、预期值及实际报告。按 R01—R10 全部子项重新反馈“完成、部分、延期”，给出 R04/R05/R08/R10 和 R02 剩余能力的交付安排。

## 8. 对本地 adapter 的影响与后续顺序

本节是接入方自己的兼容工作，不作为向 ONN Core 团队转嫁的功能要求。

最直接的升级风险是：当前 [HTTP 客户端](/Users/weichen/Documents/dev/Ontomato/packages/data-adapters/onn-core/src/main/java/io/ontomato/dataengine/dataAdapter/OnnCoreHttpClient.java:64) 没有发送可信角色；[属性扁平化](/Users/weichen/Documents/dev/Ontomato/packages/data-adapters/onn-core/src/main/java/io/ontomato/dataengine/dataAdapter/OnnCoreSchema.java:135) 将缺失字段转换为 null。V12 隐藏字段后，`salary IS NULL`、`count(salary)` 或分组可能把“无权读取”当成真实空值，得到错误结果。行数核对无法发现这个问题。

升级兼容需要先明确服务身份/用户角色来源；引用不可读字段时明确拒绝；区分缺失、真实 null 和权限不可用。若支持多种身份，Schema、样本缓存及策略版本也要按有效身份隔离，当前 fingerprint 不包含安全标记。不能简单固定发送 admin。

现有 `matched_count` 校验本来就是页行数校验，没有错误地将其当作总数；完整性另外依赖请求的 `metrics.total` 和前后计数。现有请求只使用 native 的 `match_class/limit/metrics`，严格 JSON 变更预计兼容，但仍需在真实 v12 上重跑指定只读测试并核对新元数据，不能仅按文档判断。

当前仍保持 `sql=false、useM3=false`、单类只读、最多 5,000 个对象、本地求值，`graphRead=false`、`snapshotConsistent=false`。组件能力 `graph_query=true` 不能直接升级为完整关系读取；也不能仅凭稳定 offset 放宽规模或快照限制。

后续顺序为：团队补齐 P0 并交付可识别部署 → 本地权限与完整性兼容 → 隔离环境重验 → 在绑定/邻居等能力通过后扩展图接入。既定的业务数据放 ONN、管理数据留 PostgreSQL 的边界仍然可行，本轮尚不足以扩大接入范围。

## 9. 本地复现材料与改动说明

查询测试材料：[源码](/tmp/onn_v12_review_readonly/review_overlay_test.go)、[overlay](/tmp/onn_v12_review_readonly/overlay.json)。从 `/Users/weichen/Documents/dev/onn-core` 执行过：

```bash
go test -mod=readonly \
  -overlay=/tmp/onn_v12_review_readonly/overlay.json \
  ./internal/domain -run '^TestReviewV12' -count=1 -v
```

结果：7 个测试失败，表现见第 3 节。

权限测试材料：[临时测试源](/tmp/onn_v12_security_review/v12_sprint2_with_security_review_test.go)、[overlay](/tmp/onn_v12_security_review/overlay.json)、[关键日志](/tmp/onn_v12_security_review/run-output.txt)。执行过：

```bash
go test -overlay /tmp/onn_v12_security_review/overlay.json \
  ./internal/api -run '^TestV12SecurityReviewNativeReadProtection$' -count=1 -v
```

结果：普通查询对照子用例通过，别名投影和薪资聚合两个权限子用例失败。以上 overlay 和日志是临时本地证据，不冒充团队已交付的长期验收包。

本次在 Ontomato 仓库仅新增本核查文档，没有修改既有源码或其他任务改动。ONN Core 核查前后 Git 状态一致，仅有原先未跟踪的 `docs/ONN_CORE_V11_USER_AND_TESTING_GUIDE.md`；未修改其目录下任何文件。
