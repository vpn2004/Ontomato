# Ontomato v4.1.1 中文改造与部署验收

日期：2026-10-26。

## 交付范围

工作台、管理后台、本体管理器默认使用简体中文，并保留英文切换。语言选择保存在浏览器中；独立本体管理器和工作台分别保存各自的选择，嵌入式本体管理器跟随工作台。支持通过启动 URL 的 `locale` 参数指定语言。

工作台左下角“管理后台”的弹出菜单可以切换语言；管理后台右上角有语言菜单；独立本体管理器右上角有语言选择框。

已增加工作台界面、服务端进度与错误消息、本体管理器和数据引擎语言包。服务端已接入中英文输出指令，要求保留代码、DSL/API 标识符、对象 ID 和原始业务值。固定协议和匹配器文案继续使用原有取值，避免破坏解析与已有数据语义。

本次保留 ONN Core adapter、已有连接配置、PostgreSQL 卷与业务模型。没有修改 `/Users/weichen/Documents/dev/onn-core`，没有修改 108 上的 ONN 服务或向 ONN 写入业务数据。

## 访问地址

- 工作台：<http://192.168.1.108:3010/>
- 模型配置：<http://192.168.1.108:3010/#/admin/model-config>
- 系统配置：<http://192.168.1.108:3010/#/admin/system-config>
- 嵌入式本体管理器：<http://192.168.1.108:3010/#/admin/ontology-manager>
- 独立本体管理器：<http://192.168.1.108:3006/>

前端采用 hash 路由，后台深链接应保留 `/#/`。

## 源码与部署

本地持久工作目录：`/Users/weichen/Documents/dev/worktrees/ontomato-v4.1.1-zh`。

分支：`feature/onn-core-v4.1.1-combined`。

- `b1f81349`：中文语言包、默认语言、切换与测试。
- `bdfafa27`：ONN HTTP 连接探测和符合前端契约的报告。

原主工作目录没有切换分支或覆盖改动。组合工作目录中先前已有的 `LiveInstanceGraph.java` 主键排序修改仍保留，未混入上述提交。部署镜像也包含这一已有修改。

108 源码目录：`/opt/ontomato-v4.1.1-src`。部署目录：`/opt/ontomato-v4.1.1-deploy`，Compose 项目为 `ontomato-v411`。

| 组件 | 实际部署镜像 |
| --- | --- |
| 工作台 | `ontomato:4.1.1-20261026-182857-gab97ccda3c01-dirty-amd64` |
| 本体管理器 | `ontomato-ontology-manager:4.1.1-20261026-183003-gab97ccda3c01-dirty-amd64` |
| 数据引擎 | `datarag-opensource:4.1.1-20261026-183901-gab97ccda3c01-dirty-amd64` |

镜像标签中的 SHA 属于远端构建目录的原始提交；`dirty` 表示镜像包含同步后的改造源码。上述本地提交记录了本次代码变更。

三个镜像均构建成功并部署。PostgreSQL 与数据引擎健康检查通过，工作台与本体管理器正常运行。构建时保留 `PROMPT_LANG=en`：它选择内部提示词模板，不决定最终答案的语言。

## 验收结果

| 检查 | 结果 |
| --- | --- |
| 工作台 UI 测试 | 55 文件、347 测试通过 |
| 本体管理器组件测试 | 6 文件、93 测试通过 |
| 本体管理器应用测试 | 5 文件、38 测试通过 |
| 工作台应用测试 | 16 文件、68 测试通过 |
| 新增浏览器偏好与响应式文案测试 | 1 文件、2 测试通过 |
| 工作台服务端测试 | 128 通过，1 个已有 PPT 测试失败，见下文 |
| Java `LangServiceImplTest` | 2 测试通过 |
| Java `OnnCoreAdapterTest` | 17 测试通过 |
| 相关 Node/Vue 类型检查、改动文件 ESLint、diff 空白检查 | 通过 |
| 语言包字段与占位符完整性、前端消息编译 | 通过 |

Java 测试在 108 的 JDK 21 Docker 构建环境中按类名过滤运行，没有运行依赖真实数据库或模型的整套 Java 测试。本机 Node 25 的测试使用 `NODE_OPTIONS=--no-experimental-webstorage` 避免其原生 localStorage 与 happy-dom 冲突。

部署后的 Chromium 验收覆盖：

1. 新浏览器工作台默认中文。
2. 工作台切换英文，刷新后保留，再切回中文。
3. 模型配置页与系统配置页显示中文。
4. 已保存的 ONN adapter HTTP 连接探测成功，前端显示成功报告。
5. 嵌入式本体管理器初始语言跟随工作台。
6. 本体管理器能接收父页面实时语言消息。
7. 管理后台实际语言菜单同时切换父页面与嵌入式本体管理器。
8. 独立本体管理器中英文切换与刷新保存。
9. 从 ONN 读取已有业务对象并显示中文分页；对象 ID、字段名和数据值保持原样。
10. 上述页面无浏览器脚本错误或 console error。

验收截图、浏览器结果和临时测试脚本保存在本机产物目录 `/Users/weichen/.codex/artifacts/ontomato-v4.1.1-zh-20261026`，不将运行数据与配置提交进仓库。

## 验收中修正的问题

ONN HTTP 连接探测成功时，原报告将对象类标记成 `CLASS`；当前前端连接报告契约只接受 `TABLE` 或 `VIEW`，因此错误显示“连接测试返回的报告格式无法识别”。adapter 现在用现有目录报告的 `TABLE` 类型表示类，并保留原始类名。已增加 Java 断言，108 浏览器实际连接测试也已通过。此修正发生在 Ontomato adapter 内，无需 ONN 团队改动。

## 已知限制

108 的模型配置页没有聊天模型，角色模型显示未配置，嵌入模型未配置密钥。因此本次未完成真实 LLM 问答、深度分析和嵌入检索的端到端验证。语言指令、语言包与传递逻辑已通过代码测试；实际模型输出仍需配置可用模型后验收。当前交付可用于界面与 ONN 读取测试，不能视作完整 AI 业务验收通过。

服务端 `src/core/skills/__tests__/ppt-runtime.test.ts` 有一个已有失败：测试期望 `generate-ppt` 默认可用，而仓库原有技能清单为 `defaultEnabled: false`。相关文件未因中文改造修改；本次没有调整 PPT 启用策略。不能据此宣称全仓测试全部通过。

## 回滚

部署前为原镜像保留了 `pre-zh-20261026` 标签。需要回滚时在 108 上执行：

```sh
docker tag ontomato:pre-zh-20261026 ontomato:deploy-amd64
docker tag ontomato-ontology-manager:pre-zh-20261026 ontomato-ontology-manager:deploy-amd64
docker tag datarag-opensource:pre-zh-20261026 datarag-opensource:deploy-amd64
cd /opt/ontomato-v4.1.1-deploy
docker compose up -d datarag ontomato ontology-manager
```

回滚复用现有配置和数据库卷，不执行卷删除或数据库重建。
