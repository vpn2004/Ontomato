# ONN Core V12.3 回复复核与 Ontomato 接入影响

复核日期：2026-10-22。本文只复核 ONN Core V12.3 的交付说明、固定构建、当前源码、定向测试和 192.168.1.108 上的隔离实例，不修改 `onn-core`，也不切换 Ontomato 业务数据。

## 结论

V12.3 已经修复了 V12.2 复核中确认的两项问题：`time_bucket.attribute` 的属性授权绕过，以及 Pipeline 根步骤/`from_step` 空集合没有完成完整性收尾。两项都有本地源码与定向测试支持，并在 108 的 V12.3 隔离实例上通过只读复验。

但 V12.3 目前不能作为安全验收基线。有一个新的 P0 权限绕过在本地和 108 都能复现：Pipeline 的 `from_step` 同时携带指向另一对象类的 `vector_match.class_path` 时，授权按向量类判断，执行却继续使用前一步的对象类，reader 可以通过投影别名读出 employee 的保密 `salary`。此外还发现一个时间窗口总数错误和一个 Pipeline 空集计数指标缺口。

因此可以继续进行 Ontomato adapter 的隔离开发和契约测试，但不应把 V12.3 接入生产业务数据，也不应在 P0 修复前进行完整业务 E2E 验收。

## 已确认的 V12.3 修复

固定构建与 108 运行实例一致：

- 引擎 SHA：`944449463153febd16a3ceb01d4f1d839970e096`。
- 版本：`v12.3.0`，二进制 SHA-256 为 `38b08e4a…b4d8663f`。
- 108 隔离实例网关为 loopback `127.0.0.1:43723`，后端为 `127.0.0.1:37375`。
- 当前 `onn-core` 工作区只有原有未跟踪的 V11 指南文档，没有本次复核产生的修改。

本地将 domain 和 api 测试分别编译到 `/tmp` 后运行，`^TestV123|^TestReviewV121|^TestV122` 共通过 28 个 domain 测试和 12 个 api 测试。108 上完成 30 次只读 HTTP 检查，未运行会写入 fixture 的验收脚本。

已通过的范围包括：

1. 显式时间分桶、时间窗口、隐式分桶、向量属性和 HAVING 的受限字段，reader 返回 403，`hr_admin` 返回 200。
2. 已知类的 Native、Pipeline root 和 `from_step` 空集返回 `count_kind=exact`、`total_exact=true`、`total_matched=0`、`page_count=0`、`has_more=false`、`data=[]`。
3. 分组行不再猜测 `group_` 前缀，也不生成实例级 `attribute_states`；缓存保留来源元数据。

V12.3 的 108 验收说明称通过 47 个请求，但其中包含 fixture 创建和多条子场景，不能当作 47 个独立能力。验收脚本也没有覆盖下面的混合 `from_step`/`vector_match`、无匹配时间窗口和空集聚合指标。

## P0：Pipeline 类解析与执行类不一致导致越权

在 108 的既有合成 fixture 上，使用如下纯查询请求：

```json
{
  "steps": [
    {"name": "source", "graph": {"match_class": "/accept/employee", "limit": 1}},
    {"name": "leak", "graph": {
      "from_step": "source",
      "vector_match": {
        "class_path": "/accept/department",
        "attribute": "name",
        "query_vector": [1, 0],
        "top_k": 1
      },
      "output": {"fields": [{"attribute": "salary", "as": "compensation"}]}
    }}
  ]
}
```

reader 收到 HTTP 200，`projected` 中出现 `compensation: 50000`；同一 `salary` 的普通查询和没有 `vector_match` 的 `from_step` 查询均为 403。`hr_admin` 正常得到 200。脱敏证据保存在 [/tmp/onn-v123-from-step-vector-leak-evidence.json](/tmp/onn-v123-from-step-vector-leak-evidence.json)。

根因是 `queryInputClass` 遇到 `vector_match` 时优先使用 `vector_match.class_path`，而 `from_step` 执行器实际沿用前一步的 employee ID 和类路径。授权与实际水化/投影对象因此分离。

ONN Core 需要：

- 明确禁止 `from_step` 与不一致的 `vector_match.class_path`，返回 422；或让授权、执行、投影和水化统一使用同一个最终类解析结果。
- 增加 reader 403、`hr_admin` 200 的 Native/Pipeline 回归测试，并断言投影字段不会从前一步对象类泄露。
- 在存在 `from_step` 时，对任何派生表达式都以实际输出对象类重新解析源属性，不能只依赖请求中优先级更高的表达式类。

在该问题关闭前，不能把 V12.3 标记为安全验收通过。

## P1：时间窗口过滤后的总数仍然错误

`/accept/person` 中唯一记录的日期是 `2026-08-17`。查询 `2027-01-01` 到 `2027-12-31` 的时间窗口时，Native 和 Pipeline root 都返回空 `data`，但元数据为：

```json
{"count_kind":"exact","total_exact":true,"total_matched":1,"page_count":0,"has_more":true}
```

时间窗口已经过滤掉唯一记录，结果应为精确 0、`has_more=false`；如果执行器无法证明完整集合，则应返回 `unknown` 和可空总数，而不能使用过滤前的 `PrePagingTotal`。源码路径显示时间窗口过滤后没有同步更新 `PrePagingTotal`，后续完整性收尾又采用了过滤前的值。

这会直接影响 Ontomato 的分页和答案证据，建议 ONN Core 补充 Native/Pipeline 的无匹配时间窗口回归，并在修复后重新部署隔离实例。

## P2：Pipeline 空集没有返回 count 指标

对不可能匹配的查询附加 `metrics: [{"name":"n","type":"count"}]` 时：

- Native 返回 `metrics.n=0`；
- Pipeline root 和 `from_step` 的完整性字段虽然正确，但 `metrics` 缺失或为 `null`。

原因是 Pipeline 的零 ID 分支提前调用完整性收尾，跳过了 `applyMetrics`。这不是 V12.3 新引入的安全问题，但会让同一 DSL 在 Native 和 Pipeline 下产生不同结果。若 Ontomato 使用空集 count，应统一返回 `n=0`；在修复前 adapter 不能把缺失指标默认为 0。

## 对 Ontomato adapter 的影响

当前首期 adapter 仍应保持 `sql=false、useM3=false`、单类只读和受限查询能力。V12.3 的表达式授权修复不等于可以放宽 adapter 的边界，也没有完成 Ontomato 前后端完整 E2E。

adapter 侧继续按既定六项工作推进：可信身份传递、`attribute_states` 和本地授权、可空完整性、Schema 生命周期与类型映射、按身份隔离缓存、明确传播 403/422/unknown/truncated。特别是不能把受限字段当成普通 `null`，也不能把 `total_matched=null`、`has_more=null` 或缺失 metrics 转换成 0/false。

本轮不需要再次部署 108：现有隔离实例已经确认运行交付 SHA，并且已经复现 P0/P1/P2。待 ONN Core 修复 P0，随后应在新的独立目录和端口部署修订构建，再重复权限负例、时间窗口、空集指标和 adapter 只读 E2E；不要升级现有 18081 实例。

本次复核未修改 `/Users/weichen/Documents/dev/onn-core`，未写入远端 fixture，也未提交凭证或真实配置。
