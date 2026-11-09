# ONN Core T33：应用接入复核与 adapter 补充修复

日期：2026-11-09。工作目录为 `worktrees/ontomato-v4.1.1-zh`，分支为 `feature/onn-core-native-query`。本次只修改 Ontomato 的窗口读取器、对应测试和本报告；未修改 onn-core 目录，未提交或切换 108 运行服务。

## 结论

值得接入 T33。它改善了元数据并发访问，并新增显式的大明细窗口读取。T33 交付已经在本工作目录包含 adapter、Java/Python 后计算、contracts、Workbench 服务端和界面的对应改动，不需要重复编写这些功能。

复核发现一项应用实现与契约不一致：窗口失败重试时，行数与字节预算在每次尝试内重新计数。现已改为同一查询的全部尝试共用预算，符合 T33 文档的 45 秒、32 MiB、100000 行上限。此修复不需要 Core 改接口。

## 交付证据复核

阅读并交叉核对了 Core T33 验收报告、元数据与窗口接入契约，以及本目录的 [T33 应用交接文档](onn-core-t33-metadata-window-20261108.zh-CN.md)。

补充修复前，交付清单中的 111 个证据文件、246 个 Core 源文件、416 个应用 Java 文件及 738 个应用 Web 文件的哈希全部一致；本地 adapter 与 engine-core 的原 T33 JAR 也与交接哈希一致。上述一致性代表原 T33 交付，不代表后续补丁的构建身份。

从原始样本重新计算：4950 个正式计时样本、330 组、每组 15 次，与汇总中位数没有差异。显式窗口结果文件实际有 168 项且全部通过。性能数字来自团队已完成的隔离验收，本次没有重新运行远程性能测试。

| 项目 | 验收结果 | 解释 |
|---|---|---|
| 默认物化读取 | ONN 166/168，PG 168/168 | 旧路径仍拒绝两道超过预算的高扇出大明细 |
| 显式窗口读取 | 168/168 | 6、7 跳分别读取前 5000 条，逐行比较顺序、值和重数 |
| 6、7 跳源匹配量 | 65536、262144 | 精确 total 不等于已读取所有源行 |
| 并发 16 吞吐 | 81.85 → 106.76 req/s | 同环境 T32 与 T33 的 ONN 应用链路比较，提升 30.4% |
| 并发 16 p95 | 325.49 → 221.32 ms | 降低 32.0%，不是 ONN 与 PG 的并发对比 |
| 7 跳质量明细中位数 | ONN 28.435 ms，PG 34.164 ms | 该数据与查询口径下 ONN 较快，不代表任意负载优势 |

大明细窗口记录的约 350.550/456.105 ms 为单次观察，不能当作重复采样中位数。选择性规划、权威属性索引和任意排序 Top-K 不属于 T33 已完成能力。

## 本次修复及验证

`OnnCoreWindowReader` 每次调用创建一个独立预算，跨 stale/busy/auth-changed/expired 重试保留已消耗的行数与字节计数；每次尝试仍从空结果开始，失败页不会混入最终答案。独立查询之间不共享消耗计数。超预算直接拒绝答案，仍进入 finally 尝试关闭窗口。

新增两项模拟 HTTP 回归测试，分别覆盖跨重试累计行数、累计字节预算，并验证关闭行为及独立查询的预算隔离。两项测试在未修复读取逻辑时均失败，修复后通过。

本次实际执行结果：

| 检查 | 结果 |
|---|---|
| adapter 明确筛选的 13 个测试类 | 127/127，通过 |
| Java/Python 后计算来源范围的 2 个测试类 | 15/15，通过 |
| Workbench `onn-window.test.ts` | 5/5，通过 |
| UI `query-execution-view.component.test.ts` | 34/34，通过 |
| contracts/server/workbench app/UI 类型检查 | 全部通过 |
| Git 已跟踪改动空白检查 | 通过 |

Java 仅运行上述明确筛选的测试，未执行全量外部系统测试。Node 25 测试环境使用 `NODE_OPTIONS=--no-experimental-webstorage`，避免原生 Web Storage 干扰现有 DOM 测试环境。

Java 验证入口：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
./mvnw -B -ntp -pl packages/data-adapters/onn-core -am \
  -Dtest=OnnCoreT33MetadataTest,OnnCoreT33WindowTest,OnnCoreT32Test,OnnCoreT30Test,OnnCoreAdapterTest,OnnCoreDslCompilerTest,OnnCoreGraphCompilerTest,OnnCoreHttpClientTest,OnnCoreSnapshotReaderTest,OnnCoreCapabilitiesTest,OnnCoreSchemaTest,OnnCoreAdapterProviderTest,OnnCoreConditionalWriterTest,ABCAfterCalculateSubQueryExecutorPartialResultTest,PythonExecuteServiceImplQueryStatusTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## 部署接续

1. 一并打包 Core、adapter、engine-core、contracts、Workbench 服务端和 UI。仅更换 adapter 会缺少后计算、历史、模型和下载中的来源范围传递。本次 Maven `test` 没有重新打包 JAR，原交接 JAR 不包含本补丁。
2. 默认先保留 `ONTOMATO_ONN_GRAPH_READ_CONSISTENCY=materialized`，复核既有查询和 Action。
3. 在隔离应用实例显式设置 `ONTOMATO_ONN_GRAPH_READ_CONSISTENCY=version_checked_window`，校验 6、7 跳大明细、offset、排序、关闭、后计算、历史和 JSON 下载。
4. 窗口仅适用于能力协商成功、必选且不同类的关系树、显式正数 limit、offset+count≤100000、变量声明与执行顺序一致，以及没有过滤、分组、function、distinct 或全局排序的查询。其他查询保持旧路径及预算。
5. 界面和模型必须保留 `sourceWindows`：读取 5000 条后算出一行指标，仍只代表这 5000 条来源范围；`version_checked` 不能称为物化快照。

T33 原报告明确记录 108 的生产入口未切换。本次完成本地复核、修复和定向回归，未重新验收运行实例，不能据此声称 108 已上线 T33 或完成自然语言问答验收。
