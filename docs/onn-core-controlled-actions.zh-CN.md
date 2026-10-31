# ONN Core 受控 Action

ONN 适配器支持独立的条件写接口。它不开放旧的 Python Action、任意条件批量更新、建模写入、沙箱、附件写入或关系写入。管理数据、变更决策和执行结果继续保存在 PostgreSQL。

## 开启方式

默认关闭。主机管理员需要显式授权共享应用账号，并通过服务器私有文件提供动作白名单。第一版仅支持修改一个精确对象的一个字符串枚举字段。有限行权限或字段权限的主机身份暂不支持，服务端会拒绝调用。

服务器环境变量：

```dotenv
ONTOMATO_CONTROLLED_ACTION_POLICY=/app/conf/controlled-action-policy.json
ONTOMATO_ONN_TRUSTED_URL=http://onn.example:18081
ONTOMATO_ONN_GATEWAY_TOKEN=<server-only gateway secret>
ONTOMATO_ONN_READ_SUBJECT=application-query
ONTOMATO_ONN_READ_ROLES=business-reader
```

`ONTOMATO_ONN_TRUSTED_URL` 必须与保存的 ONN 连接地址完全一致。网关密钥从服务器环境读取，不写入浏览器可见的业务连接配置。变更目标地址时，适配器拒绝转发此密钥。部署共享访问环境时，应轮换先前出现在连接配置中的网关密钥，使旧值失效。配置文件只读挂载，私有环境文件不可提交。

白名单文件示例：

```json
{
  "version": 1,
  "allowSharedVisitors": true,
  "domainId": "1",
  "backendUrl": "http://onn.example:18081",
  "subject": "application-actions",
  "roles": ["carrier-writer"],
  "actions": [{
    "id": "carrier-contract-level",
    "name": "调整承运商合同等级",
    "className": "/logistics/carrier",
    "field": "contract_level",
    "fieldLabel": "合同等级",
    "values": ["核心", "普通", "观察"]
  }]
}
```

ONN 服务必须启用 `scoped_policy` 授权，以及 `conditional_write/v2`、`retry_idempotency/v2`、`resource_governance/v1`、`action_governance/v1`、`audit_trail/v1`。应用查询账号仅配置业务类的 `data.read/query.execute` 和关系的 `edge.read/query.execute`；动作账号仅配置白名单对象类的 `data.read/data.write`。应用账号不使用 `admin` 角色。字段和枚举限制由本服务白名单执行，ONN 的资源规则限制对象类。

## 调用与恢复

Manager 的“动作”目录展示受控动作。填写对象 ID 和新值后，先预览当前值和版本，再确认变更。界面明确显示共享应用账号；记录不表示某个访问者具有独立登录身份。

以下 JSON POST 接口位于数据引擎 `/action/controlled/` 下。Workbench 代理路径增加 `/api/data-query` 前缀。

| 接口 | 请求 | 返回 |
| --- | --- | --- |
| `catalog` | `{}` | 白名单、共享账号、最近 20 条决策 |
| `prepare` | `actionId, objectId, values` | 预览和服务器生成的决策编号 |
| `execute` | `decisionId` | 执行或核对此决策的结果 |
| `status` | `decisionId` | 持久化状态和结果 |

客户端不能指定 ONN 角色、主体、域、目标类、后端地址、ETag 或幂等键；额外参数被拒绝。主机负责解析身份和域。

`prepare` 读取对象版本，将原始 ETag、补丁、账号、策略和映射指纹、固定幂等键保存到 PostgreSQL 的 `controlled_action_decision` 表。`execute` 通过数据库原子抢占获得执行权，使用原始 `If-Match` 发起精确对象 PATCH。重复执行成功决策直接返回已保存结果。ONN 审计使用服务器主体，`X-Request-ID` 和审计原因关联固定决策键。

| 状态 | 处理 |
| --- | --- |
| `PREPARED` | 等待确认 |
| `EXECUTING` | 正在执行，先核对此决策；120 秒后可恢复过期抢占 |
| `SUCCEEDED` | 成功，重复调用返回原结果 |
| `CONFLICT` | ONN 返回 412，重新查看数据并作新决策 |
| `REJECTED` | 明确的参数、权限或对象拒绝，保留错误码 |
| `UNKNOWN` | 超时、断线、异常响应或其他不确定结果；核对或重试原决策 |

不确定结果和过期抢占的恢复只重发原始补丁、ETag 与幂等键，不重新读取版本覆盖并发变更。同一对象存在 `UNKNOWN/EXECUTING` 决策时，不允许另建决策。策略、连接或本体映射变化会使未完成决策停止执行。

决策执行窗口为 30 分钟，小于 ONN 当前 24 小时幂等保留期。超过窗口的不确定结果需要管理员对照 PostgreSQL 决策、ONN 审计和对象版本核实；系统不会自动清除状态或重新写入。PostgreSQL、ONN 对象、幂等记录和审计记录应按各自持久化目录保留。

日期、空值及关系查询仍遵循适配器现有支持范围。条件写和查询正确性通过不代表性能优于 PostgreSQL。
