# ONN Core V12.2 回复复核与 Ontomato 接入影响

复核日期：2026-10-22。本文只记录对 ONN Core V12.2 交付说明、当前源码、108 隔离验收实例和 Ontomato 现有 adapter 的复核结果，不修改 `onn-core`。

## 结论

V12.2 已经实质修复上一轮报告中的主要已知回归：S01–S10 权限/入口场景和 Q01–Q08 查询场景的原始复现，在当前代码和定向回归中通过。108 上的隔离验收实例也确实运行固定构建，网关认证、角色映射、受限字段投影、分页和嵌套截断返回与发布说明一致。

这还不能作为 Ontomato 直接切换到 ONN 业务数据或生产流量的批准。本次复核又确认了两个 V12.2 缺口：

1. `time_bucket.Attribute` 没有进入执行前属性授权。reader 普通读取会隐藏受限日期，但仍可用时间分桶别名得到日期桶，实际 HTTP 200，应该是 403。
2. Pipeline 根步骤和 `from_step` 得到空集合时绕过统一完整性收尾，`total_matched=0`、`page_count=0`、`has_more=false` 虽然正确，但 `count_kind` 为空、`total_exact=false`，不能宣称这是精确空集。

因此 V12.2 的判断是“已修复主要回归，仍需修订后再作完整接入验收”。当前可以继续做 adapter 的隔离开发和只读契约测试；不应把本版本当作已经完成所有权限表达式和完整性组合的生产版本。

## 版本和证据边界

- ONN Core 本地工作区当前 HEAD 为 `958c068fd95c2e971424e00288c25228d3ed3531`；V12.2 实际 Go 引擎构建和 108 部署对应 `6d5b7db97aae8f7f2dd5c5c445657b983416641c`。从该引擎提交到当前 HEAD 的差异是文档、验收证据和脚本，没有新的 Go 引擎实现变更。
- 108 版本接口只读核验返回：`v12.2.0`、commit `6d5b7db97aae8f7f2dd5c5c445657b983416641c`、构建时间 `2026-10-22T09:41:43Z`。
- 本地定向测试运行了 `internal/domain` 的 22 个顶层测试和 `internal/api` 的 9 个顶层测试，全部通过。测试覆盖上一轮 8 个查询失败场景和 12 个安全场景，但顶层测试、子场景和远端请求数不能相加当作独立功能数。
- 108 只读检查结果：无认证网关请求 401；无网关密钥直连后端 403；伪造 admin 头仍按 reader 处理；hr_admin 可读 salary；reader 投影 salary 返回 403；offset 分页返回 `total_matched=3`、`has_more=false`；嵌套截断返回 `truncated=true`、`total_matched=null`、`has_more=null`；draft schema 查询返回 422。
- 108 使用的是独立 loopback 验收实例，原有 18081 未升级、未写入。当前不需要为了本轮复核再次部署。

## 已确认的修复

V12.2 的以下变化已经有源码、定向回归或 108 只读结果支持：

- Pipeline 的数据裁剪、命名 `from_step` 解析、嵌套 `where_logic` 授权和嵌套截断传播已覆盖上一轮已知复现。
- 查询结果新增 `page_count`、可空 `total_matched`、`count_kind`、`total_exact`、可空 `has_more`、`source_candidate_count` 和 `truncated`。识别到遍历截断时，不再把部分结果伪装成精确统计。
- 普通实例响应提供 `attribute_states`，区分 `unreadable`、`missing` 和 `null`；查询缓存和 Shape 缓存进行了角色读取隔离。
- Schema 增加 `published`、`draft`、`abstract`、`invalid` 生命周期和质量报告；显式非 published 定义不能执行。
- 108 的验收网关会验证 Bearer 凭证、重建角色头并阻止后端直连。这是隔离验收边界，不是生产 IdP、TLS、轮换和 SSO 的完成证明。

这些结果不能推广为所有查询组合、所有旁路端点、完整邻居分页、跨实体绑定、快照/cursor 或生产规模性能都已完成。发布说明也明确把 R04、R05、ABC 扁平三态、全量生命周期迁移和生产身份体系列为未关闭范围。

## 新确认的 V12.2 问题

### 1. 时间分桶绕过属性授权

使用内存合成对象 `/review/person`：`private_date` 是仅 `hr_admin` 可读的 datetime 属性。三条 HTTP 用例中，普通读取的 reader 正确得到 `attribute_states.private_date=unreadable` 且不返回日期；reader 请求下面的时间分桶却返回 200 和日期桶，hr_admin 同样请求返回 200：

```json
{
  "match_class": "/review/person",
  "time_bucket": {
    "attribute": "private_date",
    "unit": "day",
    "as": "public_bucket"
  },
  "metrics": [{"name": "n", "type": "count"}]
}
```

reader 实际得到：

```json
{
  "data": [{
    "attributes": {"n": 1, "public_bucket": "2026-08-17"},
    "attribute_states": {"private_date": "unreadable"}
  }]
}
```

源码路径显示，授权枚举没有检查 `TimeBucket`（`internal/domain/property_authorization.go:96-147`），执行器随后读取源字段并生成别名（`internal/domain/executor.go:1369-1370`、`1741-1751`、`1773-1782`）。这是已复现的权限问题，不是仅凭代码推测。

独立复现材料保存在 `/tmp/onn_v122_timebucket_review_54mat5o9/`，包括测试源、overlay、reader 对照、reader 失败用例、hr_admin 正例和原始输出。测试二进制从 `/tmp` 启动，未修改 ONN Core 文件。

修复要求：所有会读取、变换或生成别名的表达式（至少包含 `time_bucket`）必须在执行前枚举源属性并使用与执行器相同的类解析结果；reader 引用受限日期应 403，hr_admin 正例应保持可用。

### 2. Pipeline 空结果没有完成精确性收尾

独立空结果测试比较了 Native 空查询、Pipeline 根步骤空查询和 `from_step` 空查询。Native 对照返回 `count_kind=exact`、`total_exact=true`、`total_matched=0`、`page_count=0`、`has_more=false`。两个 Pipeline 空结果实际返回：

```json
{
  "count_kind": "",
  "total_exact": false,
  "total_matched": 0,
  "page_count": 0,
  "has_more": false,
  "truncated": false,
  "source_candidate_count": 0,
  "data": []
}
```

原因是 `internal/domain/executor.go:366-375` 在 `classPath==""` 或 `len(ids)==0` 时提前返回，绕过后面的 `finalizeFromStepResult` 和完整性收尾。数值为零本身没有问题，但缺少 `count_kind=exact` 会让调用方无法区分“精确空集”和“未知/未收尾结果”。

复现材料保存在 `/tmp/onn_v122_empty_pipeline_review_20261022_1/`，二进制从 `/tmp` 工作目录运行，退出码为 1；ONN Core Git 状态和 ignored 文件均未发生变化。

修复要求：所有 Pipeline root/from_step 空结果统一经过 finalize；如果该路径确实能证明集合完整，至少返回 `count_kind=exact`、`total_exact=true`、`total_matched=0`、`page_count=0`、`has_more=false`，并加入空集合回归测试。

## 对当前 Ontomato adapter 的直接影响

V12.2 不支持“只把 URL 换成 108 地址”。当前 adapter 的边界仍然是 `sql=false`、`useM3=false`、单类只读、本地求值、非精确 ID 查询最多读取 5,000 个对象，`graphRead=false`、`snapshotConsistent=false`。本轮不应因为 ONN 引擎修复而放宽这些边界。

接入 V12.2 前需要补齐以下契约：

1. **可信身份传递。** `OnnCoreHttpClient` 当前只发送 `Accept` 和 `Content-Type`（`packages/data-adapters/onn-core/src/main/java/io/ontomato/dataengine/dataAdapter/OnnCoreHttpClient.java:64-69`），Provider 只声明 `url`（`OnnCoreAdapterProvider.java:14`）。需要设计 Bearer/token 的配置、加密保存、轮换和角色来源；不能把 URL 改成网关地址后假定认证已经存在。通用连接模型目前只有 url/user/password，新增 token 会影响配置契约和 UI 保存流程。
2. **字段状态和本地授权。** `OnnCoreSchema.flatten` 只读取 `attributes`，忽略 `attribute_states`（`OnnCoreSchema.java:135-145`）；当前 DSL 编译和本地求值也不会把实际引用字段交给 ONN 做授权。不可读字段不能被静默当成 null 继续过滤、排序或聚合，adapter 必须在本地计算前识别 `unreadable` 并传播拒绝。
3. **可空完整性。** 当前 `OnnCoreAdapter` 仍依赖旧的 `matched_count` 和 `metrics.total`，并要求它们等于返回数据长度（`OnnCoreAdapter.java:55-63`）。它还没有读取 `total_matched`、`count_kind`、`total_exact`；HTTP client 只会拒绝 `truncated=true` 或 `has_more=true`（`OnnCoreHttpClient.java:87-94`）。升级时必须显式处理 `null` 和 `unknown`，不能把未知总数或未知 `has_more` 转成 0/false，也不能把截断当正常空结果。
4. **Schema 生命周期和类型。** 当前 schema 导入不检查 `lifecycle`，类型投影只识别原始 string/int/float/boolean/datetime（`OnnCoreSchema.java:22-69`）。需要按 V12.2 规则拒绝不可执行的 draft/abstract/invalid，明确 text→string、double→float，json 保持不透明不可用，并把安全/生命周期变化纳入 schema 指纹。
5. **身份隔离缓存。** adapter 只有一个 `sampleSchema`，样本文件 key 由 URL、schema fingerprint、mapping 和 class 组成，没有身份信息（`OnnCoreAdapter.java:110-117`）。启用角色访问后，reader 的裁剪结果不能复用给 hr_admin，反向也不能发生；缓存必须按可信身份或权限上下文隔离，或者只缓存未裁剪的安全中间结果。
6. **错误传播和能力声明。** 403、422、未知计数、截断和 schema 不可执行都应成为明确失败，不能继续生成 Ontomato 答案。当前 adapter 不走 ABC，ABC 扁平三态缺口不是它的直接阻断项；但也不能因此宣称 ONN 全部读取入口的权限契约已经闭环。当前 DSL 也不支持 `time_bucket` 等复杂原生表达式，首期应继续保持能力声明收窄。

## 是否需要部署到 108

本轮不需要再次部署。108 的隔离验收实例已经运行与发布说明一致的固定构建，且已完成只读身份、权限、分页、截断和 Schema draft 核验。可以通过 SSH 隧道把 Ontomato adapter 指向 18087 做后续只读联调，凭证只从安全渠道注入，不写入配置仓库或日志。

如果 ONN 团队修复时间分桶授权和 Pipeline 空结果收尾，应在 108 新建独立目录和端口重新部署该修订构建，再重复上述负例、正例和 adapter E2E；不要升级或写入现有 18081。生产切换仍需另行完成身份、TLS、凭证轮换、规模性能和完整 Ontomato E2E 验收。

## 给 ONN Core 团队的两个独立修复项

1. **表达式源属性统一授权。** 对 `time_bucket` 及其他派生/聚合表达式，执行前必须递归枚举源属性，使用同一份输入类解析和权限判断；受限源属性不得通过公开别名、分组键或指标间接输出。交付 reader 403、hr_admin 200 的 HTTP 回归。
2. **Pipeline 空结果统一完整性收尾。** root、`from_step` 和普通步骤的空集合必须走同一 finalize；交付 `count_kind`、`total_exact`、可空总数/后续页字段的断言，并保留 Native 空集对照。

本报告只新增 Ontomato 侧复核文档；复核过程中未修改 `/Users/weichen/Documents/dev/onn-core`。
