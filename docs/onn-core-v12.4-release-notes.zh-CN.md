# ONN-Core V12.4 交付说明

> 对应审查文档：`onn-core-v12.3-response-review.zh-CN.md` 中 P0/P1/P2 三级问题的闭环交付。
> 源仓库：`/Users/weichen/Documents/dev/onn-core`，交付基线 commit `066aedb2`。

## 交付范围（TDD：红→绿→全仓回归→race→隔离验收）

### Sprint A1 — 类身份收敛（P0 越权堵漏）
- 新增 `internal/domain/query_classes.go`：`ResolveQueryClasses` 统一解析查询类身份（Seed/Filter/Vector/Output），冲突立即报错
- 授权（属性读取）、执行、水化、投影全部消费同一份类解析结果，杜绝"授权按 A 类、执行按 B 类"
- Pipeline 步骤逐步校验类身份；HTTP 层在属性授权**之前**拦截冲突（422）
- **效果**：`from_step`(employee) + `vector_match`(department) 的跨类组合不再可能绕过属性授权读取保密工资——任何角色（含 hr_admin）均 422

### Sprint A2 — 向量重排能力（决策 1：同类重排真能力）
- `from_step` + 同类 `vector_match` = 对前一步种子做余弦相似度重排（V12.4 计划 §9.1 六项语义全部实现）
- `VectorMatchDef` 新增 `score_threshold` 契约字段：先阈值过滤再 top_k
- 诚实总数：`total_matched` = 阈值后、top_k 前的候选数
- 平局按 ID 升序保证分页稳定；显式 `order_by` 优先于相似度排序；维度不符/缺向量的种子**排除而非记 0 分**

### Sprint B — 时间窗口计数诚实（P1）
- `resolveStep` 在时间窗口过滤后回写 `PrePagingTotal` 与 `SourceCandidateCount`
- **效果**：`total_matched`/`source_candidate_count` 均为窗口过滤后的权威值，不再消费窗口截断前的陈旧总数

### Sprint C — 空集指标（P2）
- 空集结果产出完整语义指标：count/count_distinct=0、sum=avg=min=max=0（统一 float64 类型）
- Native 与 Pipeline 两条路径逐字段一致；`metrics` 不再为 null

### Sprint D — 联合验收
- 定向 race 检测（V12.1~V12.4 全用例）通过
- 108 主机隔离实例 `/root/onn-v124-acceptance`（backend 47267 / gateway 60959）：**57/57 请求通过**
  （V12.3 全量 47 回归 + A1 类冲突 422 + A2 重排/阈值 + B 窗口计数 + C 空集指标）
- 证据：`onn-core` 仓库 `docs/acceptance/v12.4/evidence/`

## 行为变更清单（对外契约）

| 场景 | 旧行为 | 新行为 |
|---|---|---|
| Pipeline `from_step` + 跨类 `vector_match` | 200，越权读取目标类属性 | **422**（类冲突） |
| Native `match_class` + 跨类 `vector_match` | 200，越权 | **422**（类冲突） |
| Pipeline `from_step` + 同类 `vector_match` | 403/依赖执行器可用性 | **200**，向量重排 |
| `vector_match.score_threshold` | 不支持（严格解码拒绝） | 支持：先过滤再 top_k |
| 时间窗口查询 `total_matched` | 窗口截断前陈旧值 | **窗口过滤后权威值** |
| 时间窗口查询 `source_candidate_count` | 同上 | 同上 |
| 空集 + metrics | `metrics: null` | **完整指标对象，全 0** |

## 已知边界（非本轮范围，已在审查文档登记）
- V12.3 隔离实例向量索引随进程重启丢失（内存 HNSW）——V12.4 实例通过"部署即写数据"规避，持久化索引待后续版本
- Ontomato adapter 六项改造、R04/R05、生产 Idp/SSO/TLS：另行排期
