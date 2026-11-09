# ONN Core T33：元数据并发与显式窗口应用接续

日期：2026-11-08。Core、公开adapter、Java后计算、Workbench来源范围链均已实现并完成隔离联调。未提交/推送，生产未切换。

## 应用需要如何配置

默认保持：

```text
ONTOMATO_ONN_GRAPH_READ_CONSISTENCY=materialized
```

需要大明细窗口时，由应用部署显式选择：

```text
ONTOMATO_ONN_GRAPH_READ_CONSISTENCY=version_checked_window
```

未知值拒绝。新reader还要求Core能力精确声明 `adapter_identity_json_utf16_v1`、`binding_window_close_v1`、`version_checked`及bindings。旧Core能力不匹配时走原路径，不猜测支持。

资格限于必选、不同类的安全树、显式limit.count>0、offset≥0且offset+count≤100000、变量声明/执行顺序一致。过滤、分组、function、distinct、全局排序或不支持形状保持旧路径。省略limit不隐式选择100行窗口，不提高预算。

## 三方责任与范围语义

| 层 | 本轮已实现 | 接续注意 |
|---|---|---|
| Core | 有界schema表示/304、显式UTF16身份排序、归属关闭、精确total/版本检查 | version_checked不等于物化快照；旧排序不变 |
| adapter | 元数据HTTP锁外、安全发布、严格能力协商、前缀读取、有界重试及finally关闭 | 当前500行/页，前5000条10页；403不重试 |
| Java后计算 | 本engine DSL响应来源范围旁路记录，依赖范围传播 | 派生一行仍只能描述已读取来源范围 |
| Workbench | sourceWindows随数据集、历史、分析重放、模型、范围说明及JSON下载保留 | 窗口不冒用预算truncated标记，不把source total当作已返回行数 |

直接查询顶层 `resultWindow` 表示本次答案范围；Workbench验证returnedRows等于answer.length，再归一成 `sourceWindows[]`。后计算继承的是输入来源，因此允许输出一行而来源returnedRows为5000。

`answerComplete=true`表示请求范围完整；`sourceExhausted=false`表示匹配源还有更多。`matchingTotal=262144`不代表读取了262144行。真正预算截断继续用原truncated/rowLimit/truncationHint，两个概念独立。

关闭接口为 `POST /api/v1/query/windows/close`，JSON包含close_token。它与cursor用途隔离，成功204空体；重复关闭不续租。adapter记录windowReleased真实结果，取消时不能保证HTTP清理一定发出，TTL兜底。

## 最终隔离验收

- 默认materialized：原ONN166/168、PG168/168，4950正式样本，0计时失败。旧两道大明细预算拒绝保留。
- 最终显式窗口：168/168；原6/7跳大明细各5000条与独立完整有序枚举逐行一致，精确源total65536/262144，10页并主动关闭。
- 完整应用并发16：81.85→106.76 req/s，p95 325.49→221.32ms；基线与最终各12000请求、0错答。并发1/4/8吞吐分别提升11.6%/15.8%/26.0%。
- 默认质量7跳28.435ms，同轮PG34.164ms，比值0.832；3–7跳均≤1.3。
- Java adapter125、Python/后计算15、真实Java质量23、Workbench Node134、UI348通过；contracts/server/app/UI类型检查及web构建通过。
- 真实ABC Python将5000行计算成一行仍有sourceWindows；PostgreSQL历史保存/读回、分析和模型范围12项通过。
- 本机Chrome真实生产结果动作组件读取数据库历史结果，键盘下载5000行JSON并逐行对拍；移动390px无横向溢出、控制台无错。浏览器子代理服务地区限制被单独记录，未声称生成录像或完整LLM问答UI验收。

## 构建身份与发布建议

最终adapter SHA256：

```text
6e73e138530e227bc8e2d42414066ec90eae3c8c443f392f0cac98640ace2fd1
```

包含Python来源范围传播的engine-core SHA256：

```text
a60a58e07d422ad336ad43e4e8533d0d7ac1c1104a148e6f751c90c273179d5e
```

最终Linux Core SHA256：

```text
d9e6d990a89f6abdd3c552b4199b810f4662faf3b3f1ba20696c1aaca03f7845
```

应用需一并部署contracts、Node及UI范围传播变更，不能只更新adapter后忽略下游语义。建议生产上线仍分默认模式回归、隔离窗口验收、负责人显式开关三步；本次已完成开发与隔离验收，不自动生产启用。

完整Core证据位于同工作区 `onn-core/docs/acceptance/t33/report.zh-CN.md`，接入契约为 `onn-core/docs/t33-metadata-window.zh-CN.md`。108临时资源已清，原7容器/监听/systemd不变。用户LiveInstanceGraph既有修改SHA保持开发前状态。选择性规划、权威索引和任意排序Top-K不在本轮。
