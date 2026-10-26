# V12.3 修复与验收交接记录

日期：2026-10-22。对应 Ontomato V12.2 复核的时间桶权限泄漏、Pipeline空集收尾两项，以及用户确认的同源入口和分组行三态修复。

## 固定构建

- 引擎 SHA：`944449463153febd16a3ceb01d4f1d839970e096`。
- 版本：v12.3.0；Linux/amd64；BuildTime：2026-10-22T12:20:37Z。
- 二进制 SHA-256：`38b08e4ac782254e5fce3ab4bb33a3e115c85938e5e444c9bec2ae55b4d8663f`。
- 108目录：`/root/onn-v123-acceptance.MlU3at`。
- 后端：127.0.0.1:37375；认证网关：127.0.0.1:43723。
- 启用 `OMS_TEMPORAL_QUERY_ENABLED=true`；时间窗口在开关关闭时仍按既有执行器规则不生效。
- 原计划18088/18089已占用，启动前检查失败，未杀占用进程；改由OS分配空闲端口。
- 18081未写fixture、未升级、未停止；健康响应前后一致。旧V12.2验收实例也未替换。

## 修复内容

1. 统一属性引用解析：明确输入类、输出类、vector_match.class_path；授权与两条水化属性清单消费同一解析逻辑。
2. 覆盖显式time_bucket、时间窗口过滤和隐式分桶、向量属性、HAVING分组输出来源。递归扫描遍历与条件树。
3. HAVING允许分组键、桶别名和指标名；来源解析失败时拒绝。指标别名不是原始动态属性；合法公开count仍可用。
4. GraphQuery字段登记守护测试：新增字段未登记则测试失败。此测试督促声明策略，不能代替字段语义审查。
5. 已知类空集合统一finalize，输出exact/true/0/false及data=[]。无法解析类的内部空结果为unknown，total_matched/has_more=null；显式exact请求在这种状态下返回错误。
6. 空页保留已有PrePagingTotal，截断的空结果仍unknown；不把所有空页总数重置为零。
7. 分组行使用内部标记和来源信息，不根据group_前缀猜测，不生成实例attribute_states。缓存显式深拷贝该信息。
8. 移除Native响应重复实例过滤，避免统一安全清理之后再次给分组行生成错误三态。

## 验证及证据

- 原业务团队时间桶和空集复现先红后绿；原测试语义保留，移除固定/tmp写证据副作用。
- Native/Pipeline真实HTTP socket矩阵：time_bucket、temporal_window、隐式时间桶、vector_match、having，reader403、hr_admin200，并断言日期桶/计数/姓名。
- Native/Pipeline root/from_step空集HTTP JSON完整性断言。
- 域测试覆盖不同源/目标类、未知HAVING、未知类null、截断空结果、空页总数、跨角色缓存与来源隔离。
- 无排除全仓：`go test ./... -count=1 -timeout 180s`通过。
- 定向race：`go test -race ./internal/domain ./internal/api -run '^TestV123|^TestReviewV121|^TestV122' -count=1 -timeout 180s`通过。
- 108真实进程联合验收47请求通过：旧27请求 + 新增fixture/表达式授权/空集请求。请求数不等于独立功能数量。
- evidence目录保留原始红灯、最终全仓/race日志、版本清单、HTTP请求状态、监听/外部直连拒绝及18081健康前后记录。具体值断言见 `scripts/acceptance/v12_3/verify.py`。

## Ontomato接入与兼容变化

使用获授权运维建立隧道：

```bash
ssh -N -L 43723:127.0.0.1:43723 root@192.168.1.108
```

客户端base URL为本机 `http://127.0.0.1:43723`，使用Bearer凭证。新凭证与旧V12.2凭证独立，由运维经安全渠道分配；远端identities.json权限0600，未复制入仓库或对话。

收紧后的受限表达式会403，这是预期安全修复。调用方不得转换为空答案。count_kind不再出现空字符串；total_matched/has_more仍必须可空。普通实例三态继续保留，聚合行不再输出实例三态，不表示ABC已有独立三态协议。

复验仅针对新独立目录（会写合成fixture、推进fixture Schema版本）：

```bash
ssh root@192.168.1.108 'python3 /root/onn-v123-acceptance.MlU3at/verify.py /root/onn-v123-acceptance.MlU3at'
```

若停止实例，运维应核对本目录backend.pid/gateway.pid对应命令行后，只终止这两个进程。不要按端口批量kill，不要删除其它数据目录。

## 范围与后续

本轮验证只覆盖列出的场景，未代替Ontomato adapter/前后端完整E2E。适配器六项改造（可信身份、三态、本地授权、可空完整性、Schema导入、身份缓存）仍由Ontomato侧推进；其能力声明不自动扩大。

引擎修复已落码；108独立验收部署已启用；Ontomato adapter与生产流量未切换。尚未关闭R04/R05、ABC扁平三态、完整生命周期迁移、生产IdP/SSO/TLS/轮换及规模性能基准。旧Schema仍遵守V12.2兼容策略。

本轮参考GitHub ent隐私策略的统一入口组织方式（https://github.com/ent/ent/blob/master/doc/md/privacy.mdx）；本实现未引入ent依赖。
