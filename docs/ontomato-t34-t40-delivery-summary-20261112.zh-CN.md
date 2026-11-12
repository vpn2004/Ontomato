# Ontomato：T34–T40 优化、兼容性与 108 交付总结

日期：2026-11-12。状态：T40安全修复、profile定位、全量自动化/真实联合验收、108三端口部署与只读生产smoke已完成；三跳和c4/c8性能目标未达，短反序明细退化如实保留。本文由 ONN-Core 侧整理，应用仓保留同文副本；“交付给 Ontomato”指本地报告，不代表已发送邮件、IM 或外部通知。

> [!IMPORTANT]
> T39 的公开绑定独占标记存在别名契约缺陷，T40 优先撤回该不安全优化并恢复通用清理副本边界。T39 没有部署到 108 生产。历史测试绿灯不能替代新的别名回归；本轮已通过独立安全审查、普通包回归和Linux联合验收并逐服务发布；选择依据是恢复公开副本安全契约，不是性能达标。

## 1. 为什么继续优化

T33 原隔离验收已经交付有界 schema/304 缓存、adapter 元数据锁外 HTTP 与显式窗口来源链。其原质量 94 行三跳为 18.457/PG20.984ms，七跳 28.435/34.164ms；这些数字不适用于另一道 191 行业务三跳。

2026-11-09 使用质量追溯实际部署与普通 quality-reader 角色复测后，ONN 62/62、同机/远程 PG 各61/62；PG 唯一失败是应用 PostgreAdapter 的布尔等值拒绝，原生 SQL 正常。共同 61 题整体几何比0.827，但明细成为短板：

| 原始问题 | ONN | 同机 PG | 含义 |
|---|---:|---:|---|
| 191 行业务三跳明细 | 35.650ms | 20.629ms | 比1.728；不是94行三跳 |
| 1–7 跳明细 | 几何比1.166 | 1 | 整体较慢，5/6跳各约1.315 |
| 短混合 c4 | 85.2 QPS / p95 78.9ms | 98.4 / 65.3ms | 吞吐与尾延迟落后 |
| 短混合 c8 | 99.7 QPS / p95 143.4ms | 114.0 / 106.0ms | 吞吐与尾延迟落后 |
| 物化模式诊断，191行三跳 | 36.665ms | 22.339ms | 仅关闭窗口不能消除差距 |

原报告 [T33 部署后 PG 比较](/Users/weichen/Documents/dev/worktrees/ontomato-v4.1.1-zh/docs/onn-core-t33-quality-pg-comparison-20261109.zh-CN.md) 的9710次正式请求均独立校验，环境是实际部署，非纯引擎基准、不含LLM；没有给后端增加相同隔离限额。T34 起改用固定四容器隔离环境，每轮与自己的同轮/相邻 PG 对照，不把跨轮毫秒差或比例差直接归因某个提交。

## 2. T34–T40 每轮交付了什么

| 轮次 | 产品变化 | 实现提交 | 本轮支持的结论与边界 |
|---|---|---|---|
| T34 | 读取/HTTP分操作计量；字段权限投影；因子树请求内邻接只读借用 | 8a184932；应用计量62d03bad | 清理分配下降；七跳短反序小幅改善，191行三跳短反序无稳定提速；terminal/权威目录/物理起点未实现。 |
| T35 | V2对象前缀身份跳扫，保留legacy/marker完整并集 | 60c7c1dc | 191行三跳逻辑读键4448→1837、根3357→746；短C/A/C两侧约3.68%/4.28%改善；单属性合成场景可变慢，持续c4/c8未一致改善。 |
| T36 | 稳定请求内已有单worker；从正式存储key-only iterator直接复制邻接后缀 | 3f3d4df0 | 同轮短HTTP反序三跳两侧7.68%/6.65%；成功完整key计费不减、Atomic和故障包装回退；持续c4略好/c8略差。 |
| T37 | 多source批次复用两侧懒建scoped iterator | c698d29e | 三跳逻辑scans1215/suffix1212、实际iterator构建6次；domain分配与短反序改善，不等于逻辑读/物理I/O减少。 |
| T38 | 三个内部纯读点复用私有只读权限策略 | 1d467cbc | 公共策略/有效定义/类读取仍深副本；domain三题B/op降低，同轮HTTP短反序改善；191行三跳严格1.3015845未达，持续c4未达、c8达标。 |
| T39（历史，未发布） | 公开绑定结构带私有bool，首次清理原地删除 | 0105f658、9de6e1ef | 域分配下降；公开别名独占证明不成立，不能作为可发布安全结果；T40撤回。HTTP短反序未见一致显著收益。 |
| T40 | 最小安全回退 + 三跳CPU/阶段定位 | 产品39c3adf6；冻结HEAD aed62eda | 真实别名红灯后作者/独立专项绿；全仓test/vet/race通过，最终增强新请求测试另跑专项race；profile/普通包/108部署均已完成；本轮未提速，明细短反序较不安全T39慢约4%–6%。 |

T34–T38 自动化覆盖包含各轮专项、全仓单测、vet、完整串行race、真实Core E2E与隔离完整应用。T38/T39 初始“红灯”有新增计量缺失，不能等同实际旧路径行为反证；实现后补反证应标明时间。T39 nil测试也不能追认为真正实现前红灯。详细更正在第4节，T40用真实别名回归建立新的红→绿证据。

## 3. 可比较的性能与正确性证据

每行是当轮候选与当轮PG，环境/顺序/JIT/共享负载会变化。表格用于观察各轮门槛，不计算“七轮累计提速”或将不同题混为起点。

| 轮次 | 191行三跳 ONN/PG ms | ONN/PG | 1–7明细几何比 | 正式请求数与正确性 |
|---|---:|---:|---:|---|
| T34 | 31.061 / 19.261 | 约1.613，未达 | 按当轮报告，不套用后轮 | 17860，含成功最终adapter/驱动/反序；正式答案全正确，失败与预热另存 |
| T35 | 29.627 / 20.160 | 约1.470，未达 | 1.038，达标 | 63832，全独立校验、0正式失败 |
| T36 | 27.917 / 19.346 | 约1.443，未达 | 1.013590，达标 | 66079，全独立校验、0正式失败 |
| T37 | 27.137 / 20.131 | 约1.348，未达 | 0.97514，达标 | 67975，全独立校验、0正式失败 |
| T38 | 25.560733 / 19.638166 | 1.3015845，严格未达 | 0.925589，达标 | 69145，全独立校验、0正式失败 |
| T39（历史） | 27.997120 / 20.393423 | 1.3728505，未达 | 0.969688，达标 | 69820，总数正确但旧报告公式漏分项；新安全门槛未覆盖 |
| T40 | 30.882080 / 22.428045 | 1.376940，未达；差1.725622ms | 0.940974647，达标 | 108141=7590短轮/paired+100551持续，全部正确；白名单/统计已独立复核 |

T34 汇总旧表曾混用基线ONN33.349和推算PG20.655。这里以T34原验收报告的候选31.061/PG19.261更正；原始证据不回写。T38距三跳目标只有0.031117ms，也不能因展示舍入到1.30判为通过。T39三跳较T38的独立样本不同，不作为版本回退或收益证据。

持续c4/c8门槛为吞吐≥相邻PG95%、p95≤PG1.1；固定四题历史8段×60秒、本轮16段×60秒都不是长期稳定性或最大QPS保证：

| 轮次 | c4 ONN/PG QPS；p95 ms | c8 ONN/PG QPS；p95 ms | 判定 |
|---|---|---|---|
| T35 | 83.708/105.855；75.246/51.141 | 103.347/123.322；121.589/94.984 | 两档均未达 |
| T36 | 87.255/102.479；72.796/57.198 | 97.607/117.163；135.504/104.147 | 两档均未达 |
| T37 | 88.305/102.477；72.789/56.663 | 102.666/118.041；127.282/101.789 | 两档均未达 |
| T38 | 90.790411/102.219853；70.404751/55.982932 | 112.435194/116.011031；113.045881/105.716359 | c4未达，c8两项达标 |
| T39（历史） | 97.973903/103.808383；61.676251/53.753359 | 113.959430/120.876291；110.120980/97.284250 | 两档均未达，不能归因跨轮改善 |
| T40 | 85.649490/97.218722；76.526396/59.664071 | 110.734868/119.326581；114.138808/98.281199 | 两档均未达；16段加权QPS/pooled p95 |

各轮基准均为 Workbench HTTP→Java→固定adapter→后端→完整JSON，不含LLM。ONN 62/62、PG61/62中的PG布尔失败单列，不被“0正式失败”隐藏；只有共同通过题进入双方计时集合。预热build变化拒绝、工具失败与失败轮保留，不能把正式成功样本零失败写成全过程无失败。

## 4. T39 显式更正与真实构建映射

本节取代旧报告冲突叙述，旧raw与旧T39报告保持不回写。

1. **独占证明不成立。** 私有bool随整结构赋值/跨包浅拷贝保留；公开返回后外部可保留或替换Attributes。首次原地delete可修改外部引用。旧“公共深副本契约零变化”不成立，T40恢复通用重建；不能把内部深副本与不安全公开清理混称兼容。
2. **逐侧C/A/C。** 单SHA域三跳2.935823/3.064111/3.031888ms，时间改善4.19%/1.05%；七跳明细3.386856/3.524712/3.475312，3.91%/1.40%；count1.413428/1.440968/1.449823，1.91%/−0.61%。原单个“双侧值”取了较小候选，count后侧实际退化。字节两侧分别5.24079%/5.24901%、8.07743%/8.12521%，count近零。
3. **同轮c4。** T39同轮基线97.206509QPS/62.051777ms p95，候选97.973903/61.676251；原始变化约+0.789%/−0.605%，不是跨轮T38→T39的+7.18QPS/−12.4%归因。c4吞吐门槛实际差0.644061QPS，旧0.74有误。HTTP反序三题也没有双侧一致显著收益，不能由分配下降推出“时延主体不在分配”。
4. **请求总数。** 69820正确：`(3660+3200+1800)×2+270+52230`；旧公式漏了两阶段各3200混合与1800CPU定向，共10000，不能把总数改为59820。
5. **TDD。** 首次红灯主要是缺计量，不能证明旧分配行为；nil测试实现后补写且未保证实际nil前置，不能称“先测后修”。T40真实别名红灯为独立新证据；独立overlay对0105f658旧sanitizer的实际nil测试已真实exit1，是T40事后历史反证，不能追认T39当时TDD。
6. **身份。** 原T39 build-manifest是首次未注入ldflags的无效发布候选，不是验收普通包；应用摘要pending/旧T38 buildIdentity为继承元数据。实际产品实现full commit为0105f658227eb0ccfeea6957667c9d9a5f1d0d2c和9de6e1ef3e3471dc8ffa98bcc6dc09c8abcabe59。四包实际Go VCS为5154e6e29a361b06841629f6716d66c6c15e571a、modified=true；显式build_commit却是短5154e6e2（未注入-dirty），UTC2026-11-11T10:26:24Z，保留差异不伪造后缀。四包由构建代理只读核验；Linux两包另经独立审查核验，mac原文件未由独立审查者重复读取，Linux包未执行。

| T39真实验收产物 | SHA256 |
|---|---|
| Linux amd64 OMS | 05515fe48f3043afaf393740baf51d77f543cde7e2642d697067af0509af03a9 |
| Linux amd64 CLI | 2dd57a4929dc9d56a8d1fe1df7ebb8749cae1f568b73f1fc9ae07b0f7ba883a9 |
| macOS arm64 OMS | c1e67bf05c9fde248954b0e9dd83631bde321108465aca12bf85eb08c96ce8ec |
| macOS arm64 CLI | 3814b1838875d6b4302f9fe6ced99b84132c15cb89811e6a1b17905b93d1579a |

这份映射只说明“哪一个包接受了当时的验收”，不再称其满足T40新的安全门槛；108未运行T39生产包。

## 5. T40 profile：方法、结果与未证明项

已完成：普通HTTP公开phase、cgroup CPU、隔离Java JFR与真实domain CPU profile组合定位。不给生产开放debug端口，不修改主机采样权限或perf限制，不进行生产压测。完整角色/授权/预算/稳定票据/分页/JSON/close保留，domain不含HTTP或Java。

| 证据 | 最终样本/结果 | 能回答的问题 |
|---|---|---|
| Core CPU flat/cumulative | 候选30.01s/31.05s进程CPU；mallocgc14.62%、JSON18.62% cum | 哪些栈实际耗CPU，累计栈不可相加 |
| 同请求core/encode/sanitize phases | 普通HTTP每后端30样本与分操作median齐全 | 直接阶段观测与嵌套关系 |
| Java HTTP/转换/close阶段及可用JFR | 两侧60秒、1610/1649正确请求、栈截断边界保留 | Java转换、网络等待、关闭的直接证据 |
| 固定同轮baseline/candidate/PG | 明细paired退化、三跳/c4/c8未达、1–7组达标 | 安全回退候选是否防回退、门槛是否达到 |
| 未覆盖/受限/不可识别部分 | 无普通OMS完整HTTP CPU采样、旧testbin身份受限、不能推出唯一根因 | 明确证据不能说明什么 |

最终样本显示：候选domain 30.01s、全部进程CPU样本31.05s，ExecutePipeline cum58.45%、factor窗口24.67%、邻接19.13%、json.Marshal18.62%、mallocgc14.62%、sanitizer3.83%、BindingPaginationKey5.48%，domain close仅0.16%。这些累计栈嵌套、可含后台CPU，不能相加/当wall share；close低比例不含真实HTTP往返。分配仍是可见热点，明确撤回旧“时延主体已不在分配路径”。

普通HTTP三跳候选30.882080/PG22.428045ms，比约1.376940未达。候选core直接阶段median：evaluate9.564011、page_encode1.353286、sanitize0.509294ms；adapter total21.584455、remoteRead18.101644、residual1.320645ms。基线Java JFR的60秒1610请求均正确，643 ExecutionSample中499截断，fastjson readObject/readArray/list写分别cum19.28%/15.40%/11.20%；这些只是转换/JSON线索，native采样含阻塞，不是CPU share。普通HTTP正式30样本直接adapter median：page.requestTotal15.971123ms、close.requestTotal1.058326（HTTP0.996943）、schema.requestTotal0.816921、flatten1.148469、responseDecode0.392220ms；不与JFR重启/采样段混合。候选Java JFR60秒1649请求0失败，689 ExecutionSample中554栈截断；readObject/readArray/list写cum22.50%/16.26%/10.60%、flatten5.22%，仍是线索而非完整因果归因。16段已完成：100551正式持续请求0失败；加两阶段3660×2+paired270，正式性能合计108141。候选c4两段77.722176/93.585499QPS、p9585.753063/66.359952ms，漂移显著原样保留不重跑择优。短C/A/C三跳候选两侧慢6.0953%/3.9795%、h7rows慢4.7041%/4.2005%，count快0.7007%/2.7655%；必须明确本轮明细观察到性能回退，不能称无回退或有提速，已知不安全基线只供参考不再发布。最终白名单已归档，独立审查已从两轮各3660请求重建共244组median/nearest-rank p95、16段全部100551数值/加权QPS/pooled统计，均匹配；不称独立重新计算全部答案oracle。

历史基线domain测试包67ad4fc2...无VCS、完整冻结输入未证，历史编译exit受tail掩盖不可信；本轮运行exit0与字节SHA成立，非873860d7 bench分派包、环境未设OMS_T39_BENCH_MODE。profile/h.query源码与当前相同但不能据此称整个旧输入已冻结，因此不以两testbin的CPU百分点差作为安全回退因果收益。候选自身热点定位仍成立，普通HTTP基准另按05515fe4→fbd44325正式包映射。

禁止用不同分布median相减得“纯网络/纯Java”耗时，也不由减少map推导分配不再是热点。JSON/fastjson/BindingPaginationKey只是下一轮候选，尚未证明是HTTP根因；若证据不足，结论写“仍待定位”。

## 6. 回归范围、失败和阻塞

T34–T39每轮真实普通Core本机非权限7800项：质量60查询/183检查、快照25、身份45、条件写209、治理14、普通角色23、高扇出19、窗口32、一致性差分7250；权限mac249PASS/3平台BLOCKED，由同轮Linux普通包T27-O03/O04/O05逐case补齐为252/252，不称mac原生252或Linux全仓。T39新公共别名缺陷说明这些既有绿灯覆盖仍不足。

| T40 gate | 状态 |
|---|---|
| 外部公开引用/结构浅拷贝/替换属性身份/Return+Bindings+Data回归 | 实现前真实4顶层FAIL；作者绿灯exit0，独立34顶层专项全PASS/0SKIP，安全源码阻断解除 |
| nested map/slice、真实nil/空JSON/类型 | 两个不变量实现前PASS；最终增强全新ReadTicket/ReadSnapshot并追加T40+类型专项race exit0；nil旧缺陷由事后overlay exit1反证 |
| 全仓test/vet/完整串行race | 真实exit0/0/0；domain11.733s/race66.244s、storage race5.252s；最终新请求测试增强另经专项race通过，产品源不变 |
| 本机最终普通包7800与mac权限 | 非权限7800/7800、9套真实exit0；差分7250耗时405.948s；权限249PASS/0FAIL/3平台BLOCKED、实际expected exit2，Linux同包3项已补，逐case合并252/252，mac原阻塞保留 |
| Linux双UID/同源专项/真实oracle | O03/O04/O05同包PASS，合并权限252/252；49domain顶层PASS/0SKIP、三题×三角色×500/37完整18格；不是Linux全仓/race |
| 真实应用/PG/历史/offset窗口/恢复 | ONN62/62、PG61/62布尔拒绝保留；offset7取10/total128、close/Python1行来源范围/缺limit物化回退通过；未冒领全部T33历史UI回归 |
| 独立安全与数值审查、发布gate | 发布前安全gate PASS；62公共+12额外逐SHA匹配、普通四包/全部统计重建一致；实际生产已完成，事后独立gate PASS；17生产白名单/tar/实际运行SHA与配置差异均复核，无安全/部署剩余阻断 |

完整test/vet/race与最终增强测试不是相同测试输入：完整检查之后仅增强新请求隔离用例，产品字节不变；最终专项race补齐，不称所有最终测试字节都曾运行全仓。原生全仓包含既有未跟踪审计probe，普通包不编入测试，Linux测试包overlay排除另记身份。逐字日志与JSON的SHA索引位于Core [自动化归档](/Users/weichen/Documents/dev/onn-core/docs/acceptance/t40/evidence/automated/archive-index.json)。

本机本轮普通包十套已真实完成，独立审查核对31项raw的size/SHA及真实exit/计数/cleanup，不冒称重新逐条计算所有业务答案。31项非产物driver/fixture/执行器输入与10原保护文件SHA一致；全部任务进程/监听finally清理，forced kill=0。mac权限的3平台BLOCKED是保留状态，不在Linux实际补齐前称252。31项raw留受限brain、Git仅存路径/size/SHA。

Linux恢复fbd44325候选后29类/1979对象/5740边、三目标oracle与同boot只读dataVersion稳定。本轮总664payload逐SHA匹配（不沿用旧轮663计数，普通源码manifest另记）；4任务容器+网已清，原7容器、service、完整监听保持，任务端口关闭；首预检沿用旧1GiB守卫但实际7.13GiB高于2GiB，后23观测均2GiB；最低6.623GiB。远端224raw索引复核为223原SHA一致、唯一archive-public.log事后stdout由0B追加133B，当前SHA另记不回写旧manifest；不能称224原SHA全一致。

历史失败保留：T34反序能力刷新拒绝；T35切换预热build变化拒绝；T36隔离PG临时socket误就绪；T38目录保护性拒绝、摘要器旧SHA、scp截断；T39管理socket长目录和本轮发现的安全/证据缺口。T26故障tag并非每轮重跑，不把前序成绩当本轮新覆盖。既有fixture类测试未设置私有env时的SKIP与显式oracle独立执行分开。

## 7. 最终包、108运行态和回滚

T40普通包已构建、独立核验并完成本机/Linux联合及生产发布。需要同时记录full source commit、full build commit、实际dirty、Go版本、UTC、tags=[]、四产物SHA与产品/测试/probe各自清单；不以文档HEAD代替冻结源码。用户既有源码空行与审计probe、应用LiveInstanceGraph未提交改动保留，不入本轮提交。

| 最终T40构建字段 | 实际值 |
|---|---|
| 产品实现full commit/冻结manifest | 39c3adf6ae92caeacae5bc2c1c331dd55165ad5f；冻结HEAD aed62eda1a501da37a6281da4ba258ed85292434，255普通源/362tracked tests+t22；构建cmd/internal清单范围独立标注 |
| build VCS/显式commit/dirty/UTC/Go/tags | full VCS aed62eda1a501da37a6281da4ba258ed85292434，modified=true；显式同full-dirty；UTC2026-11-12T01:03:16Z；tags=[]、CGO0；Go1.26.0 |
| Linux OMS/CLI SHA256 | fbd44325014cb96525dbae6d9ca0aec3dcbf41214e8e34962794f864f808e817 / 718b610ba8c19664f95e5b9d4dae8c97def8006b6d44438c6fd62b7216689ac3 |
| macOS OMS/CLI SHA256 | 1106df4e026ced9d36e264e7b9307afc1cb3fb7fa9032dede6e2c5b81a961d3c / 74209ffffc63d94773f07547cf73ac33843be11c27da72946b5a4673d3162dd3 |

108三目标最终实际运行SHA均为 **fbd44325014cb96525dbae6d9ca0aec3dcbf41214e8e34962794f864f808e817**，内嵌版本均v13.0.0/full `aed62eda1a501da37a6281da4ba258ed85292434-dirty`、UTC构建2026-11-12T01:03:16Z、Go1.26.0。按18080→18082→18081逐个切换，前一个完整通过才继续：

| 108端口 | 最终实际PID/服务 | 状态/只读smoke | 冷备与独立恢复 |
|---|---|---|---|
| 18080 | 1358684 / onn-core-18080-t40.service | active/static未enable；1类1对象、无关系三跳N/A；schema/ETag304/匿名/内容摘要通过 | 6文件1049429B；manifest SHA 9b04295f94ee9a91c3775707867f1452f8d480970bd1d752ec8892df9014543c |
| 18082 quality | 1359968 / ontomato-onn-quality.service | active/enabled；29类54关系、42行实际schema三跳+close；3011固定191行亦通过 | 8文件2404560B；SHA bc48d9682b200ca85555ad7a16cae4678b8dbf477233628820e9136f7a5ea684 |
| 18081 logistics | 1361262 / ontomato-onn-logistics.service | active/enabled；14类20关系、80行实际schema三跳+close；应用count通过 | 11文件4262680B；SHA 10719f9c4294c854395a18de67a6cdb695417994cae66b3374bfb6444974cc69 |

部署UTC为01:37:28.682–01:38:18.019，最终运行态01:39:12采集。三旧PID正常停止后LOCK文件不存在，明确记录为退出/文件不存在边界；冷备后实际恢复到独立目录，内容SHA、相对路径、uid/gid/mode全部匹配。原uid/gid/补充组/umask/cwd/argv/rlimits与唯一监听均匹配，env除systemd invocation变量外保持。旧二进制、release、backup留在 `/opt/onn-core-t40-deploy-20261112/` 供回滚；本轮没有触发真实回滚或覆盖生产数据。

18080从非受管user-session进程变为专属systemd scope，是明确管理方式变化；未enable、TasksMax=infinity，原日志位置保留。18081/2原unit及drop-in的SHA/权限未变，仅新增95 ExecStart；停机前临时94禁止SIGKILL，成功后均移除/reload恢复原政策，最终无94。原7容器完整身份不变，额外8080旧PID667432不动。控制器3个合成故障测试覆盖stop已退出恢复、仍存活不启第二OMS、应用smoke失败切旧路径并重抛；合成测试不冒充生产回滚演练。发生失败仅自动恢复旧二进制并停后续，数据恢复须停机保留现场复核，不盲覆潜在外部写入。

应用实际指向 **3010→http://192.168.1.108:18081，3011→http://192.168.1.108:18082**，前后schema/count/quality191行答案SHA一致，未触发history写或生产性能负载。42/80行Core smoke不是191行隔离性能题；旧v12 metrics分页422 CONSISTENCY_UNSUPPORTED保留并显式fallback不分页完整只读核对。最后free7124373504B，约6.635GiB。

data/schema/auth revision是启动初始化0的进程内计数，重启前后不声称持久revision相等。持久完整性比较schema内容SHA、授权全对象多重集SHA+计数、策略文件SHA，保留boot/dataVersion变化；同boot才能以revision检测外部写入。旧cursor/snapshot/close_token重启失效，客户端从首页重查，不盲重放旧续页。

部署运行SHA对应aed62eda冻结产品/构建身份，后续Git文档提交不改变该产品字节，不要求为追随文档HEAD再编译部署。生产17文件+manifest逐size/SHA归档：[最终运行态](/Users/weichen/Documents/dev/onn-core/docs/acceptance/t40/evidence/deployment/final-deployment-public.json)、[实际应用](/Users/weichen/Documents/dev/onn-core/docs/acceptance/t40/evidence/deployment/application-after-public.json)、[部署证据索引](/Users/weichen/Documents/dev/onn-core/docs/acceptance/t40/evidence/deployment/manifest.json)。生产smoke证明部署兼容与内容保全，不替代隔离性能基准。

## 8. Ontomato接入与兼容性

### 无需新增应用配置的内部优化

T34–T38的字段投影、身份跳扫、邻接后缀与scoped iterator、内部只读策略均由Core自动选择；固定T34 adapter SHA `0b247a253f28cdb139350aeed067aaf208ce49b4c7fdc8877c5d483983538884`、engine-core `a60a58e07d422ad336ad43e4e8533d0d7ac1c1104a148e6f751c90c273179d5e`、engine-app `4eb8954062e9260f1052dd4166d240fa23fb39bbced5f5f1afc5815e47ba9d32`。T40安全回退不要求应用新参数；本轮已部署并确认生效，无需新增应用参数。旧服务未升级时仍使用各自已有能力，应用先按capabilities决定新模式，不因版本字符串相同假设功能相同。

### 可以缓存什么、不能缓存什么

- schema/capabilities表示可按完整身份scope、真实后端、版本/ETag与有界容量缓存；每请求读取后的schema/授权校验仍保留。304不等于跳过票据终验，不混用不同用户/角色/库/版本的表示。
- 字段投影只在同一次清理固定角色/完整ReadTicket内部复用，不缓存业务值；权限策略内部借用只读，公开策略/类/有效定义仍独立副本。
- 不跨请求缓存“某用户可以读取某字段/对象”的结论，不将请求内permission memo或iterator保存到应用全局，更不能把未授权字段作为共享缓存源。

### 属性、身份、iterator与窗口生命周期

- 属性缺失、显式null与存在值三态要保留；Data的AttributeStates不能由Bindings中字段是否存在反推。窗口水化从缓存取得的嵌套map/slice保持逐出现递归副本，int64/json.Number等原类型保持。通用sanitizer恢复的契约是逐出现顶层map重建，不能承诺对调用者任意注入的嵌套容器额外深复制；新请求隔离已用最终增强回归的全新ReadTicket/ReadSnapshot及专项race验证。
- 身份枚举合并V2历史无marker对象、legacy和marker-only空对象，不仅扫描marker。逻辑读取计费与物理I/O/CPU不同，不能据profile counter减少收费或扩大预算。
- scoped iterator仅用于正式Client、有效同库票据、owned readSnapshot的单批请求；正ScanLimit、Atomic/foreign/no snapshot/故障包装回旧路径。scope结束关闭，不跨请求借用；callback不递归KV/关闭快照。
- `version_checked_window`是有界版本校验会话，不是历史物化快照。只有显式选择、能力/安全树/显式limit匹配才启用；省略limit按旧materialized路径。answerComplete与sourceExhausted分别表达，返回请求5000行不等于源全量耗尽。
- cursor/close按owner、query、模式、epoch和硬期限隔离；close用途不同于续页token，重复关闭不续租，数据变化后仍应可清理。取消时close可能无法发出，TTL兜底；应用在finally/best-effort关闭。
- Core重启/切包会改变启动epoch并使活动cursor失效，即使持久keyring保留也不能继续旧会话；应用收到过期/版本变化后重新开始完整查询，不跨新旧包接续。部署窗口需允许在途查询重试，不能把部分来源伪装完整结果。

### 已知应用与协议边界

- PostgreAdapter布尔等值的`UNSUPPORTED_OPERATOR_FOR_TYPE`是应用待修，不是PG缺少布尔能力；修复需保留boolean/null类型并加单元和联合对拍。
- 30条旧路径如primary_key_lookup沿既有协议没有版本元数据；不能宣称所有响应都有dataVersion。
- 不提高读取/物化/快照预算，默认高扇出旧协议两题预算拒绝的历史不因新窗口成功被抹除；百万全量count、任意排序Top-K、选择性规划/权威属性索引仍未关闭。
- 数据目录仅单OMS进程独占；包回退不代表可以回退已被新存储格式写入的数据，回滚按本轮核验的兼容版本与备份流程执行。本轮产品仅移除不安全只读清理标记/分支，未新增存储格式或写路径；数据恢复仍按已验证备份与兼容流程执行。

## 9. 剩余缺口与下一步

基于已完成的T40真实安全回归、独立gate与CPU/阶段证据，选择下一轮最小可验证优化。若CPU仍集中在复制/反射/JSON，继续计量并对该路径写行为/性能反证；若Java转换、HTTP往返或close有直接占比证据，再由相应模块做同轮A/B，不先认定“非分配主导”。每次变更保留普通角色、三态、路径重数、排序、预算、票据终验、故障顺序与关闭生命周期。

T40三跳/c4/c8门槛均未达，1–7明细组达标；历史T38一项接近或c8达标不自动关闭本轮目标。大对象数/高扇出/长时持续与生产持久盘需另立验证规模，固定1979对象、5740边与本轮16×60秒无法推广到这些情景。本轮已于UTC2026-11-12T00:53:18Z实际查询GitHub golang/go的JSON/map alias参考，官方搜索返回400 FAILED_PRECONDITION（location不支持）；已停止、不绕过，未完成在线优秀项目核查，原始摘录归档于Core自动化证据，不把T38阻塞当本轮核查。

本报告最终以 [T40验收报告](/Users/weichen/Documents/dev/onn-core/docs/acceptance/t40/report.zh-CN.md)、[独立审查](/Users/weichen/Documents/dev/onn-core/docs/acceptance/t40/independent-review.zh-CN.md) 与各轮原始证据为依据。生产事后独立gate已PASS；审查是对证据的独立复核，审查者未直连远端重复操作，也未重新计算全部答案oracle。本地交付报告在Core与应用仓各留同文副本；最终生产结论以已归档实际exe SHA、逐实例备份/恢复与只读smoke为依据，性能未达和采样限制继续保留。未推送、未发送外部消息。
