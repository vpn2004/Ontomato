# T40：108 运行态核验与 Ontomato adapter 部署

日期：2026-11-12。状态：部署完成，应用只读验收通过。本次没有重新运行 ONN/PG 性能基准。

## 实际部署内容

已阅读 [T34–T40 交付总结](ontomato-t34-t40-delivery-summary-20261112.zh-CN.md)。接手时，ONN 团队已经把 108 的三个 Core 服务升级到 T40；本次独立检查实际运行二进制、服务状态与交付证据，没有再次重启或切换 Core。

两套 Ontomato 生产引擎的 adapter 实际仍为 T33。本次补部署经过 T34–T40 联合验收的固定 T34 计量 adapter。T35–T40 的 Core 内部优化和安全修复不要求新增应用参数，当前无需再开发一套 adapter。

| 组件 | 本次核验或操作 |
|---|---|
| Core 18080 | 已运行 T40；PID 1358684，`onn-core-18080-t40.service`，active/static，保持未 enable |
| 物流 Core 18081 | 已运行 T40；PID 1361262，`ontomato-onn-logistics.service`，active/enabled |
| 质量 Core 18082 | 已运行 T40；PID 1359968，`ontomato-onn-quality.service`，active/enabled |
| 质量 Ontomato | 先更新 `ontomato-quality-quality-engine-1` 的 adapter 挂载并重建该容器 |
| 物流 Ontomato | 质量验收通过后，更新 `ontomato-v411-datarag-1` 的 adapter 挂载并重建该容器 |

三个 Core 实际 `/proc/<PID>/exe` 的 SHA256 一致：

```text
fbd44325014cb96525dbae6d9ca0aec3dcbf41214e8e34962794f864f808e817
```

运行构建为 `v13.0.0`，commit `aed62eda1a501da37a6281da4ba258ed85292434-dirty`，UTC 构建时间 `2026-11-12T01:03:16Z`。应用查询返回的 backendCommit/backendBuild 与此一致。Core 交付目录的 17 个生产文件已按 manifest 逐项检查 size/SHA；这不等于本次重新执行了 Core 团队的全仓测试、备份恢复或性能验收。

应用运行版本仍为原有 Ontomato v4.1.1。本次没有重新构建镜像或发布其他应用源码。

| JAR | SHA256 |
|---|---|
| 更新前 T33 adapter | `38064c03b12a79a945ad58412b000034c988a28e55c50bd7adfe04a0039e6961` |
| 更新后 T34 adapter | `0b247a253f28cdb139350aeed067aaf208ce49b4c7fdc8877c5d483983538884` |
| 保持原样的 engine-core | `a60a58e07d422ad336ad43e4e8533d0d7ac1c1104a148e6f751c90c273179d5e` |

两个引擎容器内部均重新读取 JAR 并校验 SHA。生效的 Compose 配置与部署前逐字段比较，唯一变化是各自 adapter 挂载的宿主文件来源。保留 `ONTOMATO_ONN_GRAPH_READ_CONSISTENCY=version_checked_window`、原镜像 ID、连接与授权配置、其他挂载。

## 本次实际验证

所有查询均由实际部署的 Workbench HTTP → Java → adapter → 对应生产 Core 发起，不经过 LLM，不切换生产数据源。

| 检查 | 结果与范围 |
|---|---|
| 质量追溯查询 | 部署前、最终部署后各 62/62；20 道业务题与 42 道 1–7 跳题，对独立既有 fixture/oracle 校验 |
| 物流查询 | 部署前、最终部署后各 34/34；14 类对象计数、20 项关系计数，与既有验收结果比较 |
| 191 行业务三跳 | 答案及来源证据通过，不与 94 行三跳题混同 |
| 七跳窗口 | 完整结果 128 条；offset/count 为 0/10、7/10、129/5 时，顺序切片、总数和空窗口正确 |
| 窗口语义 | `answerComplete=true`、`totalExact=true`、`snapshotConsistent=false`、`windowReleased=true`；省略 limit 仍走 materialized |
| Python 后计算 | 真实 `/abcProgram/execute` 将 10 条输入变为 1 条输出，仍保留 offset=7、count=10、matchingTotal=128 的来源范围 |
| 元数据 | 两套 Core 的强 ETag 与真实空 body 304 通过；窗口 ordering/close 能力匹配 |
| 授权 | 两套业务 Core 的匿名 schema 访问被拒绝 |
| Manager 后端 | 两套环境的 summary、search、incoming/outgoing neighborhood 均成功；质量 1979 节点、物流 3228 节点 |
| T34 新计量 | 真实业务三跳的 page/schema/close 分项 requestTotal、httpExchange、成功次数及响应字节均已返回 |
| 页面入口 | 3010、3011 根页面均返回 HTTP 200；未将此描述为浏览器全量 UI 回归 |

除前后各 96 道查询外，统一兼容检查共 13 项，全部通过。业务配置、本体、Core schema/capabilities、受控 Action 目录与部署前一致。三个 Core 的 PID/SHA/服务启用状态不变；其他五个容器的 ID、StartedAt 和镜像不变；容器总数仍为七个。

本次没有执行 Action 或修改业务数据，没有迁移数据、扩大预算、放宽权限或增加缓存 TTL。T34 之前已接受的单测成绩属于历史验收，本次没有重新运行这些单测。

### 保留的首次部署失败

质量环境首次更新后，62 道查询已经全部通过。但本次部署验收脚本错误地从 `evidence.timingMs` 读取新增的 `page.requestTotal`，触发 KeyError，并自动回滚到原 T33 adapter。

实际协议将分项计量放在 `evidence.timingPhasesMs`，计数放在 `evidence.workCounters`；`timingMs` 保留 total/remoteRead/residual。修正脚本后重新部署，62 道质量题及新增计量验证均通过，之后才更新物流。没有因此修改产品源码或重新构建 JAR。首次脚本、响应和失败记录保存在私有 artifacts 的 `attempts/quality-first-read-field-error/`，不将这次失败归为 Core 功能问题。

## 性能结论与后续

本次是部署兼容性验证，单次 smoke 时延不用于宣称性能提升。T40 团队的同轮隔离基准仍有以下结果：

| 指标 | T40 报告结果 |
|---|---|
| 191 行业务三跳 | ONN 30.882ms / PG 22.428ms，约 1.377 倍，目标未达 |
| 1–7 跳明细组 | ONN/PG 几何比约 0.941，组目标通过；不表示每道题都更快 |
| 持续 c4/c8 | 两档吞吐和 p95 组合目标均未达到 |

T40 修复 T39 公开绑定别名导致的副本安全问题；T39 没有部署到 108。发布依据包含安全契约恢复，不应把它写为全面性能超越 PostgreSQL。本次接入没有剩余的部署阻断，不需要 ONN 团队再重复升级这三个服务。后续性能迭代继续按团队报告中的三跳、并发和更大规模目标安排。

## 入口、配置与回滚

- [质量追溯问数](http://192.168.1.108:3011/#/chat)
- [物流问数](http://192.168.1.108:3010/#/chat)

新 adapter 文件位于 108 的版本化目录：

```text
/opt/ontomato-t40-application-deploy-20261112/payload/java/data-adapter-onn-core-4.1.1.jar
```

为两套应用分别新增仅覆盖 adapter 挂载的 Compose 文件：

```text
/opt/ontomato-quality-20261104/app/docker-compose.onn-t40.yml
/opt/ontomato-v4.1.1-deploy/docker-compose.onn-t40.yml
```

今后重建应用应沿用本次保存的完整 `quality-compose-command.json` / `logistics-compose-command.json`，包含此前各层 overlay 和新的 T40 overlay，避免丢失其他既有配置。

如需单独回滚应用 adapter，可在 108 执行：

```bash
python3 /opt/ontomato-t40-application-deploy-20261112/deploy.py rollback quality
python3 /opt/ontomato-t40-application-deploy-20261112/deploy.py rollback logistics
```

回滚使用部署前的完整 Compose 命令，仅重建对应 engine，不回退 Core 或业务数据。首次质量验收脚本失败时已实际执行并通过回滚就绪检查；没有为了测试而额外回滚最终成功状态。

私有配置、查询响应、前后 Docker inspect、完整 Compose 命令及证据 SHA 清单保存在 108 的 `/opt/ontomato-t40-application-deploy-20261112/`，目录权限 0700。最终 `result.json` 和 `public-summary.json` 为通过；首次失败记录仍保留，不能用目录里历史 failure 文件代替最终状态。远端证据清单包含 58 个文件，不含清单自身及 Python 字节缓存。

本地仅在受限目录 `/Users/weichen/.codex/artifacts/ontomato-t40-application-deploy-20261112/` 保存部署助手、公开摘要与证据索引；真实配置和业务响应未加入 Git。没有修改任何 `onn-core` 文件夹内容；保留 Ontomato 已有 `LiveInstanceGraph.java` 和 `.task-onn108/` 改动，未提交或推送。
