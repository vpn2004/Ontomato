# T34 adapter分操作计量与联合验收

日期：2026-11-09。本轮不改UI、contracts、窗口资格、数据语义或部署开关。基线`d5b7bc14`保存已有T32/T33接入及跨重试共享预算修复；本轮提交只新增HTTP操作计量和对应测试。

## 变更

- page/schema/close/capabilities/instance/other固定枚举下记录requestEncode、httpExchange、responseDecode、requestTotal及success/failure/responseBytes。
- schema304及window close204也纳入实际操作计时；没有JSON的响应不伪造解析耗时。
- WindowReader增加pageValidation和其内部budgetEncode；仍按原JSON规范化重编码统计预算，不用传输字节代替32MiB限制。
- static HttpClient连接复用、完整body超时、8MiB单响应限制、中断取消、响应拒绝、跨重试45秒/32MiB/100000行累计预算及finally关闭保持原行为。

requestTotal为父阶段；旧聚合与operation字段不可叠加，pageValidation包含budgetEncode。旧聚合字段新增了此前未覆盖的schema304/close/失败和请求编码边界，因此前后应按明确operation对照。responseBytes是subscriber已观察body字节，不含HTTP header/TLS，也不是解码后堆内存。

## TDD与验证

先新增mock HTTP测试，实际红灯8/8后实现；后补404/no-op兼容用例，最终新增9项。

- Java adapter明确筛选的14个既有/新增测试类共136/136通过；Python/后计算15/15通过。
- Node窗口5/5及contracts/server/app类型检查通过。
- 独立只读审查未发现阻塞缺陷；确认304/204/404、超时与限额、重试累计和计量隔离语义。
- 真实Workbench→Java→普通角色Core、独立PG、Python和历史链在隔离环境验收；最终结果以Core[T34报告](../../../onn-core/docs/acceptance/t34/report.zh-CN.md)为准。该相对路径适用于当前两仓布局。

最终adapter JAR SHA256为`0b247a253f28cdb139350aeed067aaf208ce49b4c7fdc8877c5d483983538884`。engine-core保持`a60a58e07d422ad336ad43e4e8533d0d7ac1c1104a148e6f751c90c273179d5e`；本轮仅替换隔离adapter以归因，未切换生产。

## 性能与后续

Core-only局部优化在分时样本中使191行三跳约33.349→31.061ms，七跳37.001→35.139ms；PG也有波动，不能把差值全部归因代码。三跳/PG仍约1.613，并发4/8差距未关闭。

当前不应扩大元数据TTL、删除读取后schema校验、跳过权限或提高预算来降低数字。下一轮优先分析根身份扫描；terminal单页能力要有新旧端协商及来源范围回归，不能只设置旧elide开关。

LiveInstanceGraph既有用户改动、私有运行配置均未纳入本轮提交。未推送、未改变线上运行入口。
