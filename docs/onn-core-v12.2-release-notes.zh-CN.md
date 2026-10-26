# ONN Core V12.2 版本迭代说明（交付 Ontomato）

交付日期：2026-10-22。对应 Ontomato《ONN Core V12.1 回复复核与下一轮验收要求》。本文是 ONN Core 团队的交付说明，供接入方独立复核，不替代 Ontomato 验收或生产切换批准。

## 1. Git 与实际部署版本

本轮代码、测试和部署证据已提交至 ONN Core 本地 main。仓库未配置 remote，**尚未推送远端**。

| 提交 | 变更 |
|---|---|
| `5e527470` | 源条件归属、有限重复路径执行、深度校验 |
| `3ba2fac0` | 跨入口属性授权、命名步骤作用域、字段读取状态 |
| `1fbe9bf4` | 分页总数、Pipeline 统一收尾、嵌套截断和未知计数契约 |
| `6d5b7db9` | Schema 四态、扩展类型、缓存隔离、隔离验收身份边界 |
| `89830038` | 部署清单、验收证据及增强 HTTP 验收脚本 |
| `ce954299` | 外部无法直连隔离实例的验证证据 |

**108 实际二进制对应完整 SHA：`6d5b7db97aae8f7f2dd5c5c445657b983416641c`。** 后续文档、测试脚本和证据提交不代表二进制重新构建；本文提交也不改变部署版本。

## 2. 对业务复核问题的回应

| 问题 | 本轮交付及验证 |
|---|---|
| S01–S03 Pipeline count/order、ABC 默认输出旁路 | Data 按实例真实类裁剪，ABC 单步/多步响应保护；原始复现通过 |
| S04–S08 sample、values、value-search、shape、merged | 接入属性读取规则；显式受限画像拒绝，跨字段搜索先过滤；原始复现通过 |
| S09 where_logic.children | 递归授权完整条件树；执行前拒绝无权属性；原始复现通过 |
| S10 非相邻 from_step | 按步骤名解析输入及输出类，授权与执行共用解析器；原始复现通过 |
| Q01/Q02 普通 where 与 Pipeline 根分页 | 分开完整总数与返回窗口；原始复现通过 |
| Q03/Q04 排序/指标加投影 | Pipeline 分支统一收尾，不带未选业务属性，元数据正确；原始复现通过 |
| Q05/Q06 源条件加遍历 | 源条件只在源层执行，目标条件在目标层执行；原始复现及 HTTP 正例通过 |
| Q07 有限重复路径 | 守卫区分 AST 层级，深层条件不再跳过；原始复现通过；超深明确拒绝 |
| Q08 嵌套截断 | 真实截断向根结果传播；Native/Pipeline 远端 HTTP 验证通过 |
| R08 Schema 治理 | 显式四态、发布校验、扩展类型表、只读质量报告；旧定义保留兼容模式 |
| R10 版本化交付 | 构建清单、fixture/请求/断言脚本、原始测试日志、接口规范子集 |

S01–S10、Q01–Q08 是已知复现的回归通过结论，不等于所有查询组合、所有读取接口、安全审计、生产认证或完整图接入已经验收关闭。请针对固定构建独立复验。

## 3. Ontomato 需要适配的接口契约

### 3.1 总数、分页与截断

| 字段 | 含义 | 调用方要求 |
|---|---|---|
| `page_count` | 当前页实际条数 | 不作为总数 |
| `total_matched` | 已知时为输出集合总数；未知时 null | 使用可空类型，禁止 null 转 0 |
| `count_kind` | exact / unknown | 统计展示依据此字段判断 |
| `total_exact` | 是否精确 | false 时不宣称精确统计 |
| `source_candidate_count` | 源候选数，与遍历后集合不同 | 不替代 total_matched |
| `has_more` | 已知完整集合的正常分页状态；未知时 null | 使用可空类型，禁止 null 转 false |
| `truncated` | 执行阶段确实丢弃合格数据 | true 不能解释为正常末页 |

普通单类分页示例（省略 data）：

```json
{"page_count":1,"total_matched":3,"count_kind":"exact","total_exact":true,"source_candidate_count":3,"has_more":true,"truncated":false}
```

执行截断示例（仅列关键字段）：

```json
{"total_matched":null,"count_kind":"unknown","total_exact":false,"has_more":null,"truncated":true}
```

本轮证明的是 branch_limit 截断和所列回归路径，不应推广为所有内部限额、治理、超时和优化组合都已验收。`count_mode: "exact"` 或统计/分组遇到已识别遍历截断时返回错误，不提供部分集合的伪精确统计；调用方必须传播错误，不能转换为空集合成功。错误状态码分类仍需进一步规范。

精确总数需要继续读取候选，可能增加成本；保留了原有避免全类属性扫描的性能断言，但尚无生产规模基准。

### 3.2 属性权限与字段状态

- 显式引用无权属性用于投影、过滤、排序或指标时，执行前 403；不提供静默降级开关。
- 普通实例响应新增 `attribute_states`：unreadable / missing / null。真实 null 仍保留在 attributes 中；非空值直接在 attributes 中。
- 不可读属性值不返回。字段省略不能统一解释为 null。
- ABC 扁平结果尚无独立三态协议，不能宣称与普通实例完全等价。
- 未声明 Schema 属性、旧定义的兼容行为仍存在；本轮不是全量强类型迁移。
- 查询缓存结果复制隔离，避免 reader 的响应裁剪污染授权角色后续读取；Shape 缓存同样隔离。

### 3.3 Pipeline 与 Schema

- from_step 按名称引用，不依赖立即前一步；未知引用、重复名称、显式类与引用输出类冲突会拒绝。
- `lifecycle`：published / draft / abstract / invalid。显式非 published 定义拒绝查询执行。
- 显式 published 拒绝空类型、未注册类型；published 关系须有存在且可执行的端点。
- `GET /api/v1/schema/quality` 输出逐项状态、问题、扩展类型表和旧定义兼容策略。
- text 映射 string；double 映射 float；json 是不透明结构化值，不承诺 JSON 路径、数值聚合或任意 JSON 运算。
- 未声明 lifecycle 的旧定义保留兼容行为。quality 的 executable 表示通过新发布校验，不保证所有旧入口已经强制阻断。
- 历史 55 空类型、26 扩展类型、13 缺端点关系已逐项报告，没有自动改写 18081，也不是当前线上数量的重新采样结论。

## 4. 108 固定构建和接入方式

| 项目 | 值 |
|---|---|
| 版本 | v12.2.0 |
| 完整引擎 SHA | `6d5b7db97aae8f7f2dd5c5c445657b983416641c` |
| 构建时间 | 2026-10-22T09:41:43Z（北京时间17:41:43） |
| 平台 | Linux/amd64 |
| 二进制 SHA-256 | `ccaabd17eb83fa43ba2564b4995a748caa528785e9fb181a52a24e858ed24e74` |
| 独立目录 | `/root/onn-v122-acceptance.Qinz2q` |
| 后端 | 127.0.0.1:18086，仅供可信网关 |
| 认证验收网关 | 127.0.0.1:18087 |

这些是 **108 主机 loopback 地址**，不是可直接配置到远程 adapter 的 LAN 服务地址。有权限的运维可建立 SSH 隧道：

```bash
ssh -N -L 18087:127.0.0.1:18087 root@192.168.1.108
```

再通过本机 `http://127.0.0.1:18087` 和 `Authorization: Bearer <安全渠道分配的验收凭证>` 访问。凭证机器随机生成，远端 identities.json 权限0600，不入仓库、不粘贴聊天、不写普通日志。

网关根据独立凭证映射 reader/hr_admin/admin，重建身份头；客户端伪造 X-OMS-Roles 无法提权。后端额外校验网关专用凭证。后端与网关均仅loopback监听，外部直连拒绝。原18081未停止、未写fixture，前后健康响应一致；这不等于校验所有旧业务数据完全一致。

这是隔离验收身份方案，不是生产 IdP/SSO、TLS、凭证轮换的完成证明。生产流量未切换。

复验命令：

```bash
ssh root@192.168.1.108 \
  'python3 /root/onn-v122-acceptance.Qinz2q/verify.py /root/onn-v122-acceptance.Qinz2q'
```

该脚本会写独立实例的合成 fixture，重复运行会推进 fixture Schema 版本；禁止改向18081。

## 5. 验收材料和独立复核建议

以下路径均相对 ONN Core 仓库：

| 文件 | 内容 |
|---|---|
| `docs/acceptance/v12.2/README.zh-CN.md` | 构建、部署、访问、停止及范围说明 |
| `docs/acceptance/v12.2/evidence/manifest.json` | 实际完整 SHA、构建时间及校验值 |
| `docs/acceptance/v12.2/evidence/acceptance-results.json` | 远端27请求状态与身份结果 |
| `docs/acceptance/v12.2/evidence/sprint3-all.log` | Sprint3无排除全仓结果 |
| `docs/acceptance/v12.2/evidence/sprint4-release.log` | R08与网关改动后的无排除全仓结果 |
| `docs/acceptance/v12.2/evidence/sprint4-race.log` | 定向race结果 |
| `docs/acceptance/v12.2/evidence/external-boundary.json` | 外部直连拒绝 |
| `docs/acceptance/v12.2/schema-historical-status.json` | 历史问题逐项状态报告 |
| `docs/acceptance/v12.2/openapi.json` | 本轮接口子集，非全量 API 规范 |
| `docs/acceptance/v12.2/query-result.schema.json` | 完整性响应 JSON Schema |
| `scripts/acceptance/v12_2/verify.py` | 合成fixture、请求、具体值断言 |
| `internal/domain/v12_2_review_test.go`、`internal/api/v12_2_review_test.go` | 业务团队原始复现按原语义纳入，仅格式化 |

本轮执行了无排除全仓 Go 测试、定向 race、网关 socket 单测和108的27请求联调。具体值断言覆盖 Alice、count=3、精确分页、授权薪资、投影裁剪和 Native/Pipeline 嵌套截断；不同测试覆盖重叠，不累加为独立功能数。尚未替 Ontomato 执行其 adapter/前后端完整 E2E。

建议 Ontomato 按以下顺序独立验收：

1. 校验 system/version 的完整 SHA、构建时间与 manifest 一致。
2. 经网关复验 reader、授权角色、无身份和伪造角色四类身份。
3. 使用可空类型接收 total_matched / has_more，验证 null 不转0/false，truncated不等于末页。
4. 独立复验 S01–S10、Q01–Q08，保留授权正例、具体值断言和原始响应。
5. 验证 Schema 生命周期导入和 quality 报告，区分历史清单与当前业务定义。
6. 在 Ontomato 自有环境运行 adapter、错误传播、三态展示和配置 E2E 后，再决定流量切换。

## 6. 能力分层和未关闭范围

| 层次 | 当前事实 |
|---|---|
| 引擎支持 | 本轮查询、安全、完整性和显式 Schema 治理已落码，不改变既有多跳能力 |
| 部署启用 | 108独立验收实例运行固定构建；18081没有升级 |
| 调用方启用 | 仅本轮验收脚本；Ontomato adapter和生产流量未切换 |

仍未关闭：R04多实体绑定、R05稳定全邻居分页、ABC扁平三态、全系统严格生命周期迁移、生产身份体系和规模性能验收。历史 Schema 报告不是线上清洗完成证明，OpenAPI为本轮子集。

架构方向保持：业务数据放 ONN，管理数据留 PostgreSQL。不因本轮复现通过扩大 adapter 能力声明，也不把 adapter 当前限制反向描述为引擎限制。
