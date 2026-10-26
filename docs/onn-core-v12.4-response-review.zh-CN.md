# ONN Core V12.4 回复复核与 Ontomato 接入影响

复核日期：2026-10-23。本文复核 Ontomato 侧 V12.4 交付说明、ONN Core 当前源码与定向测试，并对 192.168.1.108 上的隔离实例执行只读 HTTP 检查。没有修改 `/Users/weichen/Documents/dev/onn-core`，没有运行会写入 fixture 的验收脚本。

## 结论

V12.4 已关闭 V12.3 复核中确认的类身份越权、时间窗口空集陈旧总数和 Pipeline 空集指标缺失问题。交付代码、定向测试、57 条已归档验收记录和 108 上的当前实例相互吻合；跨类查询拒绝、同类向量重排、空集指标和无匹配时间窗口均通过独立只读复验。

但 V12.4 的时间窗口计数修复在与 `limit` 组合时仍会返回错误的精确总数。本次在 108 上对现有 fixture 查询得到可重复反例：窗口内实际有两条记录，Native 与 Pipeline 在 `limit=1` 时均返回 `total_matched=1`、`has_more=false`，应为 `total_matched=2`、`has_more=true`。同类向量重排还把 `source_candidate_count` 写成 top_k 截断后的数量。因此，V12.4 可以支持受限、非分页的 adapter 契约联调，但不能作为分页/完整性已验收的基线，也不能据此放开业务数据迁移。

## 版本与证据范围

- V12.4 实现基线：`04afc710`；验收证据归档提交：`066aedb2`。引擎版本为 `v12.4.0`，构建时间 `2026-10-22T22:04:26Z`。
- 108 上的隔离实例位于 `/root/onn-v124-acceptance`，网关 `127.0.0.1:60959`，后端 `127.0.0.1:47267`；二者只监听 loopback。只读检查确认 `/ping` 无认证返回 401、后端直连返回 403、版本端点返回 `v12.4.0` / `04afc710`。
- 归档的 `acceptance-results.json` 实际有 57 条 checks，`passed=true`，所以“57/57”这个总数正确。但 README 将 V12.4 场景标成 48-64，验收脚本的章节注释又标成 48-64，均与 JSON 实际只有 57 条记录不一致。按实际请求顺序，A1 为 48-50；A2 包含两次写入和两次查询，为 51-54；B 为 55；C 的 Native/Pipeline 两次空指标查询为 56-57。建议交付文档按真实 checks 索引更正，避免读者误以为有 64 条独立验收请求。
- 本地已有隔离编译产物 `/tmp/onn_v124_review.gDvak5`。从该目录运行 `^TestV124`，domain 与 api 定向测试均通过；未在 ONN Core 仓库内创建构建产物。Ontomato `git diff --check` 通过；工作区其余既有修改未触碰。

## 已独立确认关闭的问题

1. **类身份越权（P0）**：在 108 的既有合成 fixture 上，Pipeline `from_step(employee) + vector_match(department)` 对 reader 和 `hr_admin` 均返回 422；Native 跨类组合也返回 422。响应没有工资字段或工资值。
2. **同类向量重排**：`from_step(person) + vector_match(person)` 返回按相似度排序的 Alice、Bob；top_k=2 时总候选数为 3，`score_threshold=0.99` 时为 2。HTTP 请求成功，证明新字段穿过严格 JSON 解码。
3. **时间窗口空集**：Native 对不存在时间范围返回精确 0、`has_more=false`，Pipeline 空集指标在 Native 和 Pipeline 路径均返回完整非 null 指标对象，计数和数值指标为 0。
4. **历史权限与基础结果完整性回归**：V12.4 已归档的 1-47 请求通过；本轮额外确认实例保护仍有效。

## 新发现：时间窗口与 limit 组合仍会错误报总数

V12.4 的 `resolveStep` 在时间窗口过滤后，把 `PrePagingTotal` 设置为 `len(currentIDs)`。当 `candidateWindowCap` 已因 `limit=1` 把窗口输入先裁成一条时，这个长度是当前页窗口，不是整个时间窗口的匹配总数。当前代码路径见 ONN Core `internal/domain/executor.go` 的 `resolveStep` 和 `candidateWindowCap`。

在 108 上使用已有 `/accept/person` fixture、时间窗口 `2026-08-17` 至 `2026-08-18`（含 p1、p2），分别请求 Native 和 Pipeline：

| 查询 | 实际 | 预期 |
|---|---|---|
| `limit=1` | `total_matched=1`，`page_count=1`，`has_more=false` | `total_matched=2`，`page_count=1`，`has_more=true` |
| `limit=1, offset=1` | `total_matched=2`，`page_count=1`，`has_more=false` | 总数仍为 2，当前页为第二条，`has_more=false` |
| `limit=2` | 总数 2，`has_more=false` | 正确 |

还用只包含 p3（`2026-08-18`）的窗口、`limit=1` 查询。Native 和 Pipeline 都返回空集、`total_matched=0`；这是因为执行器先按 limit 截取候选，首个候选 p1 不在窗口内，后续匹配的 p3 没有进入过滤阶段。预期应返回 p3。由此可见该组合会漏掉真实匹配记录，而不只是分页元数据错误。

空窗口和完整窗口（不设小于匹配数的 limit）均通过。错误发生在**窗口过滤 + 候选分页截断**的组合，因此 V12.4 当前“时间窗口过滤后权威计数”的结论不适用于分页。它会让 Ontomato 把仍有下一页的集合报告为完整结果。

建议 ONN Core 先对完整候选集合应用时间窗口，再计算完整匹配总数并分页；若为成本不能取得完整总数，则应返回 `count_kind=unknown` / nullable total，而不是精确但错误的值。补充 Native 与 Pipeline 的 `limit=1`、`offset`、分页边界之前有不匹配记录、无匹配窗口和完整窗口组合回归，并在隔离新目录部署修订构建后复验。

## 新发现：向量重排的 source_candidate_count 被 top_k 覆盖

108 上的同类向量重排 fixture 有 3 个种子，`top_k=2` 返回 `total_matched=3`、`page_count=2`，这是正确的。但响应的 `source_candidate_count=2`；无 `top_k` 截断时该值为 3。执行器在重排后再次用 `len(initialIDs)` 覆盖了源候选数量，因而该字段描述的是重排输出页候选数，不是进入向量重排的源集合数。

建议保留过滤后、重排前的源候选数，或为“向量可用且过阈值候选数”定义独立字段。修正前 Ontomato adapter 不应将重排场景的 `source_candidate_count` 作为源集合规模使用；本次未发现 `total_matched` 或返回排序错误。

## 对 Ontomato adapter 的判断

可继续进行受控只读联调，范围限于 `sql=false、useM3=false` 的单类查询、类冲突 422、字段权限 403、空集指标与同类重排。调用方仍须遵守既定边界：可信身份、`attribute_states` 和本地授权、可空完整性、Schema 生命周期/类型映射、身份隔离缓存，以及明确传播 403/422/unknown/truncated。

在时间窗口分页问题修复并验证之前：

- 不将涉及时间窗口的分页结果用于完整性判断、分析结论或翻页终止判断。
- 不切换 Ontomato 业务数据，也不把 V12.4 视为可生产使用的分页契约。
- 暂不需要重新部署当前 108 实例；现有实例仍可做只读复现。ONN Core 团队交付修复后，应新建隔离目录和端口部署，不升级 V12.2/V12.3 或当前 V12.4 实例。

V12.4 不需要为了已关闭的 V12.3 P0/P2 问题再次部署；需要为上述新分页缺口修订并部署。此判断只覆盖本次复核的查询语义，不能替代 adapter 完整 E2E、生产身份/TLS/凭证轮换或性能验收。
