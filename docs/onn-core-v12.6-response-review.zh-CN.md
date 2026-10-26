# ONN Core V12.6 最终交付独立复验与集中反馈

日期：2026-10-24。源码与 108 部署均为 `7bbcb1c9`。审阅材料：`V12_6_FINAL_ACCEPTANCE.zh-CN.md`；依据为已审阅的 V12.6 S0–S9 计划及关闭门槛。

**结论：原消费者反例已全部修复，部署身份也一致，但“全阶段通过、迭代正式关闭”的结论不成立。计划中 S3/S5/S6/S8 及精确计数冲突仍有可复现失败，部分已通过 108 的真实认证网关确认。暂不接受完整接入验收通过。**

本次补测是按已承诺的角色约束交集、双向关系并集/去重、错误传播、向量阶段计数和 exact 冲突要求展开，属于原问题修复未覆盖完整路径，不是新增一轮功能范围。报告与测试只保存在 Ontomato；没有修改 ONN Core 目录中的任何文件。

## 1. 实际复验结果

| 验证 | 本次独立结果 | 边界 |
|---|---|---|
| 原消费者 HTTP 矩阵 | **159/159 通过** | 在最终 `7bbcb1c9` 运行，保留原断言；不是仅引用早期 `972be013` 的结果 |
| 原 14 + 补充 16 边界 | **30/30 通过** | 原包原样复跑 |
| 原共享 AST 竞态 + V12.6 执行态用例 | **通过，零 DATA RACE 报告** | `-race` 定向运行；不代表独立复跑了全仓 race |
| 团队 TestV126* 用例 | api/domain/storage **三个包通过** | 定向运行；本次没有独立运行 `go test ./...` |
| 团队 108 verify.py | **35/35 通过** | 本次在保留的隔离部署重新执行一次；此前两次是团队报告 |
| 按 V12.6 关闭条款补测 | **62 条：13 通过、49 失败** | 覆盖六组契约；条数是断言数，不是 49 个独立缺陷 |
| 108 独立补测 | **13 条：3 通过、10 失败** | 真实 TCP → Bearer 网关 → ONN；继承角色限制和向量契约 |

补测 62 条构成：向量 18、继承安全/类型冲突 17、关系兼容及故障 8、谓词故障 6、歧义旧边删除 1、不完整结果 opt-in/exact 12。谓词故障的 6 条通过本机真实 TCP server 注入 KV Get 故障，其余本地 HTTP 检查使用 handler 集成；不会把它们写成 108 故障注入。

108 已核对运行接口与 manifest：version=`v12.6.0`，commit=`7bbcb1c9`，build_time=`2026-10-24T06:18:30Z`，磁盘二进制 SHA-256=`6dc72b8d06de37cd4dc86fbb36e9f674466503a7c109d9005fe0d04b9b510c8b`。目录 `/root/onn-v126-acceptance`，后端 `127.0.0.1:48391`，网关 `127.0.0.1:61723`。这两点已经满足上一轮提出的部署身份和隔离部署要求。

## 2. 必须集中关闭的六组问题

### R1 / S5：继承角色约束取并集，仍然放宽权限（P0）

原计划要求各层约束同时满足。当前 `EffectiveAttributeDefs` 明确将 ReadRoles 合并为并集，导致：

- 父类 salary restricted，仅 hr_admin 可读；子类同名字段 restricted，仅 reader 可读。只有 reader 身份的请求仍能 GET 到 `salary=50000`，并在 Native、Pipeline root、from_step 聚合得到 `s=50000`。
- 只有父类角色 hr_admin、没有子类要求的 reader，也仍可访问。
- 父类 restricted 且 ReadRoles 为空（现有语义为 admin-only），子类添加 reader 后，reader 仍可访问。
- 父 float / 子 string 的同名属性冲突，保存与有效 Schema 解析均不报错。

前三类读泄漏的本地拒绝用例全失败；双角色同时满足各层的正向对照通过。第一类泄漏已在 108 真实网关复现，排除了伪造角色头或只在测试装置存在的解释。

要求：保留各层独立授权约束，判定调用者角色集合是否逐层满足；不要简单合并允许角色。单层多个角色之间可为 OR，父子层之间应为 AND。简单对角色名称取交集也不充分，因为同时持有两个不同角色的用户仍可满足两层。admin-only 不得被子类允许列表放宽；同名类型冲突明确拒绝。GET、引用授权、过滤、聚合和响应使用同一有效结果。

实现位置：`internal/domain/v125_s2_security.go:30`。

### R2 / S3：混合关系仍漏读、重复，歧义删除破坏其他旧边（P0/P1）

- `GetInboundNeighborsBatch` 仍只扫描旧格式：旧 `old→target` + 新 `new→target`，返回只有 old，漏掉 new。
- `SampleEdges` 在有 V2 边时跳过旧边，样本不是混合并集。
- 同逻辑边同时有 V2/旧格式副本，单条出邻居返回 `[target,target]`，入邻居和批量出邻居也重复；当前是数组拼接，不是按逻辑身份去重。
- `RemoveEdge(a:b→c)` 会无条件删除拼出的旧 key。旧格式 `a→b:c` 与它同为 `edge_o:<rel>:a:b:c`；即使旧边身份有歧义也直接删掉。本次合成内存 fixture 中，存有 `owner=OTHER` 的旧边被删除，函数仍返回成功。

最后一项仅在独立内存 KV 复现，没有在持久化数据库或 108 执行破坏性歧义删除；它证明的是计划要求的“歧义旧 key 隔离、不猜身份”尚未实现。

要求：入/出、单条/批量、样本、推理等所有入口共用结构化双格式合并与去重；更新、删除不能盲删可能属于其他身份的旧 key。旧身份无法唯一还原时应明确报冲突或隔离，经过迁移映射确认后再清理。补齐双向、多副本及歧义删除测试。

实现位置：`internal/domain/graph.go:66/97/139/273/308`。

### R3 / S6：错误传播仍未覆盖谓词和关系路径（P1）

- 注入实例属性 V2 Get 故障，执行 name 条件或 where_logic：Native、Pipeline root、from_step 全部返回 **200、exact、空结果**，没有返回服务错误。6 条检查使用真实本机 TCP 请求和实际 handler/executor/KV 栈。
- V2 `GetEdge` 真故障且存在旧副本时，仍回退旧边并返回成功。代码中的 `rawValue==nil` 覆盖了真实错误分类，与上一轮实例属性双读问题同类。
- 单条出/入邻居扫描旧格式失败时，仍忽略该故障并返回部分 V2 结果。

要求：筛选、逻辑条件、时间窗等辅助函数提供 error 返回通道；GetAttributesForIDs 的故障不得被退化为条件不匹配或未过滤候选。边属性双读仅在明确 not-found 时回退；任何必要旧扫描失败都返回服务错误。对 query、metric、pipeline、邻居、向量水化等路径按调用链完成审计，保留故障身份，统一状态码及稳定错误字段。

实现位置：`internal/domain/executor.go:3101/3191`；`internal/domain/graph.go:97/139/180`。

### R4 / S8：向量三入口计数和省略阈值语义仍不一致（P1）

合成四向量与 query=[1,0] 的余弦为 1、0.707、0、-1：

| 请求 | 应有结果 | Native / Pipeline root 实际 | from_step |
|---|---|---|---|
| threshold=0.9、top_k=4 | source=4，total=1，pageable=1 | source=0，其余 1 | 正确 |
| threshold=-1、top_k=2 | source=4，total=4，pageable=2 | source=0、total=2、pageable=2，仍标 exact | 正确 |
| 省略 threshold、top_k=4 | 无额外阈值过滤，四个成员 | 只返回三个，负相似度成员被隐式阈值 0 排除 | 返回四个 |

这些差异均已在 108 真实网关复现。上一轮要求的合法负阈值 `[-1,1]` 已被测试接受，这点已修正；问题在执行阶段和阶段计数，而非请求是否返回 200。

要求：三个入口共享候选→评分/有效向量→阈值→top_k→分页的契约。必须保留 top_k 前匹配总数，不以取到的 K 行冒充精确总数。省略阈值不能被 float64 零值静默转为显式 0；成员与计数都应测试，并保留负相似度 fixture。

实现位置：`internal/domain/executor.go:2227/2323`；`VectorSearchRequest` 目前仍只有 float64 阈值。

### R5 / 不完整结果契约：exact 仍可被 opt-in 绕过（P1）

真实多分支执行造成 branch_limit 截断时：

- 缺省和显式 false 确实失败，显式 true 返回 unknown，说明默认失败机制基本有效。
- **Pipeline root 和 from_step 同时指定 `allow_incomplete_results=true,count_mode=exact`，仍返回 200+unknown。** Native 会失败。计划明确规定 opt-in 不得覆盖 exact 保证，两个 Pipeline 路径不符合。
- 缺省/false 及 Native exact 冲突目前映射 500，而非约定的请求可修正语义错误 422，响应只有 `error/details`，缺稳定 code/path。

要求：所有执行分支先统一检查 exact 与不完整结果冲突，再决定是否返回 unknown；避免在 `evaluateFromStepQuery` 截断分支提前 return。统一预算/不完整结果语义错误响应，不依赖英文错误字符串猜测。

实现位置：`internal/domain/executor.go:399`，该分支在后续 exact 检查前返回。

### R6 / S3/S9：原必需交付项不能改称可选

最终说明写“迁移 dry-run/checkpoint 演练、V12.6 契约文档更新未被要求”。这与已审阅计划直接冲突：

- S3/S9 和关闭门槛 12 要求迁移 dry-run、歧义冲突报告、中断后幂等恢复、数量/哈希校验和回滚材料。
- S9 要求更新最终契约与兼容矩阵。当前落库的 requirement-matrix 仍停留 S0“红灯”，不能作为最终关闭证据。

要求：按原计划补齐隔离 fixture 上的迁移/恢复证据和最终契约；如仅交付全新空库试用版本，应明确缩小交付范围，而不是宣称全阶段完成。这里不要求生产迁移、完整图、生产 IdP 或持久化 HNSW。

## 3. 验收意见与下一步

认可本轮原 159+30 反例、AST 竞态修复、阈值合法范围修正和 108 隔离部署的实质进展。不能因为原固定反例通过就跳过计划规定的泛化和组合门槛。六组剩余项应一次性按本报告集中关闭，需求范围保持不变。

当前可继续使用合成数据做受限 adapter 开发联调；不能据此宣布完整接入验收通过，或导入依赖继承权限和旧数据兼容的真实业务数据。修复后在最终部署 commit 同时运行原包、补测包、团队回归及 108 验收，提供统一证据后再关闭。

## 4. 可复核附件

- [测试包与运行方法](acceptance/onn-core-v12.6-review/README.md)
- [本地 62 条逐项证据](acceptance/onn-core-v12.6-review/supplement-evidence.jsonl)
- [108 13 条独立证据](acceptance/onn-core-v12.6-review/remote-supplement.json)
- [团队 108 35 条脚本本次复跑](acceptance/onn-core-v12.6-review/remote-team-verify.log)
- [源码与证据 SHA 清单](acceptance/onn-core-v12.6-review/review-manifest.json)

108 补测仅在 V12.6 隔离环境新建 `/v126_independent_<唯一编号>` 合成 fixture，未修改原业务库和其他服务，未公开任何 token。ONN Core 本地工作树检查前后一致：两个原有未跟踪文档，无已跟踪文件修改。
