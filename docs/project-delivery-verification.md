# project-delivery 验证记录（内部验收完成）

2026-10-27，按[已确认规格](../SPEC-project-delivery.md)完成 PD01–PD14 / C1–C5。此文档按时间保留过程和失败记录，段落中的“尚未完成”指当时状态；最终结果见末尾汇总，不把内部单测或 HTTP 夹具视为真实 Vue/下载工程验收。总体进度见[任务清单](../tasks/project-delivery/todo.md)。当前没有 push、部署、真实模型或邮件调用；正式工作台尚未接入，新模式默认关闭。

## 独立构建配置

| 检查 | 结果 / 证据（仓库本机 `.local/`） |
|---|---|
| 构建参数、私有文件拒绝、npm 不获得测试配置 | `delivery-pd01-green.log`，14 个脚本测试通过；先有两项失败证明缺少入口 |
| 模板源码政策和实际 Boot JAR 资源 | `ex22-delivery-pd01-template-fixed.log`，5 个单测、2 个打包检查通过；新增测试值不在既有白名单导致的前次失败保留，修正占位值，未放宽源码政策 |
| 模板 Java 私有配置边界与原 MVC 行为 | `delivery-pd02-green-host.log`，8 项通过；沙箱 JVM 附加失败日志保留，使用批准的项目隔离 JDK 在主机测试 |
| 完整模板目录、POM、依赖策略一致 | `ex22-delivery-pd02-template.log` 通过；当前目录 37 个文件，新增测试辅助类和其测试；旧快照未修改 |
| worker 显式传递测试配置路径 | `ex22-delivery-pd03-worker.log`，10 项通过 |
| 新目录独立 Java 测试 | `delivery-pd03-native.log`，16 项通过，包括 A/B 测试库只读身份检查、伪造业务用途拒绝和 B_test 待办 CRUD/冲突等六项业务测试 |
| 测试完成后的空 schema | `delivery-pd03-rollback.log`，六项待办测试及 `@AfterAll` 空 schema 再检查通过 |

独立 Java 目录：`.local/delivery-pd03-native-3cilnunr/project/backend`。由模板清单和受控待办资源复制，使用目录自己的 `mvnw`，未使用平台 worker 或平台 classpath 构建。复验命令（仅已批准的私有配置路径，不包含凭据）：

```sh
# 在上述 backend 目录，JAVA_HOME 为已批准的项目隔离 Java 17.0.14
./mvnw -B -o \
  -Dfull-project.test-config=/Users/wubingheng/atoms-demo/.local/project-execution-acceptance.x5cvui/atoms_exec_b_test_20261020.properties \
  -Ddelivery.test-config-directory=/Users/wubingheng/atoms-demo/.local/project-execution-acceptance.x5cvui \
  test
```

额外数据库契约测试资源为 `backend/src/test/resources/projectdelivery/PrivateTestDatabaseContractTest.java`，只复制进验收目录，不进入用户模板。测试没有创建数据库/角色、启动业务应用或执行业务库迁移。这里只验证独立 Java/数据库测试配置，**不是 ZIP 自身完整 `scripts/build.mjs`、Vue 浏览器或重启持久化验收**。

## 预览传输基础

- `ex22-delivery-pd04-green.log`：交付设置及原执行设置共 12 项通过。默认关闭、独立别名、origin 限制和无副作用配置检查。
- `ex22-delivery-pd05a-green.log`：5 项请求策略测试，多组路径穿越/编码/查询/凭据头/JSON 编码/体积及不可变性检查。
- `ex22-delivery-pd05b-green.log`：请求及真实 loopback HTTP 夹具共 11 项通过；`ex22-delivery-pd05b-bounds.log` 加入大静态文件和派发前过期后，7 项传输检查通过。覆盖 409 和 JS 原字节、跳转拒绝、上游策略头隔离、MIME、正文上限、读取期限、写结果不确定、HEAD/204。
- `ex22-delivery-pd06a-green.log`：健康 HTTP 和原进程生命周期共 13 项通过，已验证目标不可重绑、停止即失效。此前 RED 编译因未等上一次退出而发生输出冲突，日志保留且不计作有效 RED；后续 PD06b 全新编译串行确认。
- `ex22-delivery-pd06-second.log`：全新编译后 30 项相关单测、3 项实际 owner/PG 集成通过。先前清理编译后的字段名称错误已修正，失败输出保留。预览请求响应中的 run/execution 与原句柄一致，过期预算、串用身份和停止后的访问被拒绝。
- `ex22-delivery-pd06-postcheck-fixed.log`：新增响应后期限耗尽后，7 项传输单测、3 项实际 owner/PG 集成通过。前次错误分类失败的日志保留；修正为沿用执行模块的预算错误协议，读取超时不返回缓存字节，已派发写入返回 `DELIVERY_WRITE_UNCERTAIN`。这里的 `/api/echo` 是故障注入夹具，不作为业务 CRUD 的证明。

这些 HTTP 夹具的临时端口、合成响应和进程停止记录，只证明传输及生命周期约束。真实 Vue/Java 应用、完整 ZIP/恢复、A 下载到 B 启动及兼容性回归仍待后续任务，不能据此宣布模块完成。

## 会话授权

- `ex22-delivery-pd07a-green.log`：9 项注册表/设置测试通过，验证授权期限和容量、撤销后仍在执行的请求不提前释放名额。
- `ex22-delivery-pd07b-project-green.log`：4 项注册表单测、3 项实际 owner/PG 集成通过；管理操作可核验项目权限，无需重新取得活跃进程。
- `ex22-delivery-pd07b-session-green.log`：14 项会话服务/注册表/设置单测通过，原会话失效或配置变化后旧令牌不可恢复，原运行句柄不会换成新进程，写响应后的失权明确返回不确定且不重试。这里模拟 SessionAccess/执行门面；真实登录会话、CSRF 及浏览器验证仍待后续记录。
- `ex22-delivery-pd07c-http.log`：14 项定向单测、7 项真实 HTTP/PG 会话检查通过。创建/撤销沿用现有 CSRF 链，匿名和跨 owner 被拒绝，不接受 URL/query 或非规范 UUID，重复撤销成功，实际退出使旧授权失效；管理 OPTIONS 没有 Origin null CORS 许可。本组仅模拟执行门面，不启动应用。

## 预览 HTTP 接入

- `ex22-delivery-pd08a-policy-delete.log`：11 项请求/浏览器策略检查通过，包括无正文 DELETE。
- `ex22-delivery-pd08a-http-admission.log`：15 项单测、14 项真实 HTTP/PG 检查通过。覆盖 HTML、JSON 写入/409、OPTIONS、请求头隔离、慢上传 30 秒截止、断开释放、登录退出和会话不续期。执行返回值受控，尚不是实际 Vue/Java 工程。
- 前两次 HTTP 失败保留：`ex22-delivery-pd08a-http.log` 发现 Tomcat 不会以 null 移除已有 XFO，改为明确 SAMEORIGIN 后备并由 CSP frame-ancestors 限定父页；`ex22-delivery-pd08a-http-fixed.log` 的并发测试中探测请求抢占慢请求名额，改为等待四个请求实际入场后检查，没有修改容量政策。
- `ex22-delivery-pd08a-lifecycle.log`：5 项单测通过。被中断仍未退出的 worker 保持名额；实际完成才释放，不能把超时响应覆盖为成功。

测试 URI 拒绝经过实际 Tomcat/Spring firewall；错误 JSON 没有回显令牌。管理路径仍保留原 CSRF/CORS 语义。浏览器能力与下载工程验收分别留待 PD08b/PD13。

## 固定源码 ZIP

`ex22-delivery-pd09-zip.log` 4 项测试全部通过。JDK ZipInputStream 能读取每个条目，固定排序/1980 时间/UTF-8/STORED；本机 `/usr/bin/unzip` 实际解压后文件字节相同，`backend/mvnw` 有执行位、普通文件没有。清单按固定源码/构建及可选运行身份生成，独立放在 `delivery/`，不修改 `project/` 文件。也覆盖完整 16 MiB 源码树、超限、错误身份/路径/模式以及中途预算到期。

ZIP writer 按 [PKWARE 6.3.10 的本地头、中央目录及 Unix 属性约定](https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT)写有界 classic ZIP。标准 unzip 只在验证中使用，生产代码不调用外部 zip/unzip。没有下载 API、鉴权或独立业务工程验收的完成声明。

## 真实应用预览（已通过）

- `ex22-delivery-pd08b-native.log` 首次编译发现测试夹具用了不可访问的 JdbcSession 实现类型，已改用公开 Session 接口；未进入应用构建。
- `ex22-delivery-pd08b-native-session.log` 原生构建成功：构建 `79815b82-d390-46b6-acbe-832932118b16`，源码 `a3d2b3eb-65cc-4c5f-9ea2-1f899b90ac7c`，树 `29013c47de04b8a3f645291511a8178cce1646598d45ea3d1fb7d28e84625193`，JAR `7cc015cfaf29f07bedd0e913791a05a79fa8ca8274b9ea07daf20e1fd0e1b507`。实际 22 项前端与 15 项 Java 测试通过，原 owned 进程启动成功。
- 首次 Chrome 失败于添加待办：HTML/JS/CSS/初始 API 均为 200、没有 pageerror，但没有 POST。`.local/delivery-form-probe.mjs` 在独立 Chrome 比较证明：只有 allow-scripts 时 submit 事件不触发；增加 allow-forms 后 JS 可处理，保留 form-action none 时原生 POST 计数为 0。已将这一兼容性修正写入规格，仍禁止同源及平台凭据。原失败证据保留在 `/private/tmp/atoms-ex11-contract.APT7Jb/todo-applications-y6dt7dkp/delivery-browser-1247067733290532586`。
- 复用构建时 `ex22-delivery-pd08b-browser-forms.log` 暴露验收夹具仅登记旧整树摘要，拒绝本轮正常产生的新源码；新增两个精确的可移植配置样本摘要，保留旧摘要与拒绝未知源码的断言。`ex22-delivery-pd08b-browser-reviewed.log` 的四项夹具单测已通过；真实浏览器结果另记，不以夹具修复冒充浏览器成功。

- `ex22-delivery-pd08b-browser-diagnostic.log` 保留 POST 已派发但浏览器十秒取消的失败；`ex22-delivery-pd08b-timing.log` 分段计时定位全执行根容量扫描，单次检查约五秒，页面/静态请求约 11–19 秒。只读访问改为完整目标文件/字节核验，不再重复扫描其他构建；创建/封存/运行准入容量规则不变，无摘要缓存，无修改旧源码/JAR。
- `ex22-delivery-pd08b-readonly.log`：**17 单元 + 4 实际集成，零失败/错误/跳过**。包括文件篡改、准确版本/权限、真实 Chrome 的模块加载、新增/编辑/查询、跨页 409 及恢复刷新、浏览器隔离、桌面与移动布局。原生 owned 应用确实停止；重新启动同一构建后旧授权仍拒绝、刚写入的数据保留、原五条记录和 A/B 测试库不变。
- 浏览器证据：`/private/tmp/atoms-ex11-contract.APT7Jb/todo-applications-y6dt7dkp/delivery-browser-14882716247113117923` 的 `result.json`、`desktop.png`、`mobile.png`。逐请求耗时无令牌；文档约 1.7 秒、新增约 1.8 秒、修改约 2.1 秒。私有 `preview.json` 含短期能力地址，不提交、不打印。
- 成功运行 `6650fb44-930d-49e4-b23f-4a6a16db07a1` 和重启运行 `c862d3f5-5fd9-4ee2-902b-b3f08b5575ef` 均有原 owner 的停止证明。此结果完成 PD08b；下载 API、恢复 API、ZIP 独立构建运行及全量兼容性回归仍待后续，模块尚未整体完成。

## 下载 API（PD10）

`ex22-delivery-pd10a-source.log` 14 项单测通过；`ex22-delivery-pd10b-http.log` 7 单元 + 7 真实 HTTP/PG 集成通过。实际 Tomcat 非阻塞 ZIP 响应可被标准 ZIP reader 读取，逐文件字节与固定输入一致，清单保持原 build/source/run/execution，附件名仅使用 UUID，长度准确，no-store 与原管理 CSP 保留，无 CORS 放宽。缺少登录、跨 owner、异常 ID/query 拒绝；退出后不能下载；原运行停止时预览下载失败，已成功历史构建仍可下载。

生命周期验证覆盖全局 2/会话 1、超时后生成器不响应中断仍保留名额、迟到结果不覆盖错误、慢写出继续占名额、首字节之前超时不会留下成功 ZIP 状态。这里使用执行门面控制故障；真实下载工程的自身脚本构建及 B 数据库运行仍归 PD13。

## 源码恢复与管理 API（PD11/12）

`ex22-delivery-pd11-restore-status.log`：34 策略单测 + 3 真实 Git/PG 集成通过。验证恢复追加新版本、basedOn/restoredFrom、原两份树字节不变、恢复当前版本也追加历史、相同幂等键返回原操作、冲突键/旧 revision/越权拒绝、不可用协调器不创建新意图。模拟写 Git 后未知异常时保留 `NEEDS_RECONCILIATION` 和原 head；最初 `pd11-restore.log` 的断言误期望 FAILED，原失败保留，修正测试尊重已有恢复语义，未改工作器规则。

`ex22-delivery-pd12-http.log`：5 单测 + 7 真实 HTTP/PG 集成通过，覆盖恢复 202、状态查询、登录/CSRF/归属、严格请求字段/UUID/revision/幂等键、409 与未知结果展示、下载兼容性。状态 DTO 不暴露内部执行 ID、请求/幂等摘要、owner 或磁盘路径。HTTP 测试模拟源码门面；实际发布算法由上述 Git/PG 集成验证。


## PD13 初次阻塞与只读检查（已在下文解决）

已补齐真实下载 ZIP 的逐文件/执行位比对、自身 `build.mjs`/`start.mjs`、B 库浏览器 CRUD/409、重启数据保留，以及恢复后旧预览仍下载原源码的验收代码。**这些新增步骤尚未实际走通，不能计为通过。**

- `ex22-delivery-pd13-independent.log`：4 项契约单测通过，实际应用启动前失败，`RUN_START_FAILED`；记录 `7d7b0851-59c7-4746-830e-ab8336b2c1bf` 未签发 execution ID，已结束。该通用错误也覆盖启动准备失败，先前将它认定为操作系统创建进程失败不准确；目前没有证据定位其具体内部原因。
- `ex22-delivery-pd13-diagnostic.log`：4 项契约单测通过，实际运行 `dab70c67-efd9-4ff9-a155-c6069afaa4cb` 启动超时。数据库保留 `NEEDS_RECONCILIATION / RUN_START_TIMEOUT`、`execution_pending=false` 和停止摘要，未进入 READY。验收主线程随后因通用等待期限失败，关闭断言也暴露未结束记录；没有声称该轮成功或静默重试。
- 原 JVM 已结束，因此此前仅支持原协调器的显式数据确认接口不能处理本条记录。[具体恢复方案](design/project-delivery/startup-recovery-review.md)待用户确认；当前未更新状态、追加恢复审计或改业务数据，也未创建替代数据库/绑定。
- `.local/InspectDeliveryRuns.java` 只读确认旧五份未决审计及其原绑定/运行记录摘要未变；当前执行槽与协调锁均为 0。A 待办 6 条、B 待办 3 条，各有 3 条迁移历史。没有本轮失败前的完整行摘要基线，不能仅凭当前读取声称全部业务数据前后相同。
- `.local/delivery-startup-readonly/InspectStartupCosts.java` 只读工具检查通过：全工具捕获约 8.12 秒，Java 再检查约 1.68 秒；当前 Java 的 109 个文件、279705349 字节、SHA-256 `cf900a03e3bf843231bbf4758c8359b6cf97f49e1823fe04de145db88fc3b7dc` 与原成功构建清单一致。此观察不能替代完整启动各阶段计时，也不能证明所有负载下均在启动预算内。
- 临时生产诊断已移除。验收夹具改为及时识别 NEEDS_RECONCILIATION 并报告真实错误，而不是再等待通用超时；不改变生产启动预算或失败状态规则。

## PD14 前端检查（先行记录）

`.local/delivery-pd14-frontend-{typecheck,lint,format:check,test:unit,build}.log`：类型检查、Lint、格式检查、54 文件 / 372 项单元测试及生产构建全部成功，执行脚本退出 0。浏览器脚本通过 Node 语法检查。前端源文件未改动。

这不替代 PD13 的下载应用运行和最终后端兼容性回归；任务清单中的 PD13、PD14 和 C5 继续保持未完成。

最终 `ex22-delivery-pd13-compile-and-zip.log` 的 8 项源码契约/ZIP 单测和平台打包通过（退出 0），包括本机标准 unzip。报告已归档 `.local/delivery-pd13-compile-evidence-mdrp7_ja`；打包 JAR 已核对不含临时生产诊断。9 项已配置秘密值对本轮差异、拟提交新文件及相关日志扫描零匹配。新增独立工程验收代码仅完成编译，仍不宣称原生运行通过。


## 已确认的重启后恢复（2026-10-27）

用户确认[恢复方案](design/project-delivery/startup-recovery-review.md)后完成仅打开已有根的维护能力与固定 owner/project/run 核对。14 项目录/锁检查、31 单元 + 11 集成通过；pending 拒绝条件变异能被检出，恢复后 7 项通过。`ex22-delivery-recovery-approved-run.log` 的 7 单元 + 1 实际恢复通过，准确来源、真实绑定和当前历史均经原输入校验；追加审计 `c4d7e03a-58e9-47ac-bc37-77387c0cae09` 后结束唯一获准运行。A/B 的 6/3 条记录、结构、迁移历史和其他运行/绑定/源码元数据在恢复前后相同，幂等重放没有第二份审计。未重建进程能力或修改业务数据。


## PD13 完整原生验收通过

`ex22-delivery-pd13-recovered.log`：30 单元 + 1 真实原生集成，零失败/错误/跳过，Maven 退出 0。基于原构建 `79815b82-d390-46b6-acbe-832932118b16`，没有替换旧源码或 JAR。

- 平台预览实际加载 Vue 模块，Chrome 完成新增/修改、跨页旧 revision 的 409、刷新及 opaque-origin 隔离检查。
- 实际 HTTP 下载 ZIP，经标准 unzip 解压后逐文件字节及 Git 执行位与原快照一致；清单固定原 source/build/run/execution。
- 原协调器执行源码恢复，追加版本 `d8106bb9-aace-405b-ba8b-a0a8c30cb4ee`；原运行保持 READY，恢复前后的同一预览 ZIP 字节完全一致。
- 解压工程自己的 `scripts/build.mjs --test-config ...` 完成 Node 脚本检查、npm 安装/类型/Lint、22 项 Vue 测试、前端构建、15 项 Java 测试和自身 JAR 打包。构建不使用平台 classpath 或 worker；源码构建后仍逐字节相同。
- 工程自己的 `scripts/start.mjs --config ...` 启动 B 应用。真实 Chrome 完成同样的新增、编辑优先级、409 和刷新；停止后重启，新增记录仍在。B 原三条记录保留，A 在下载/构建/独立运行期间的七条记录完全不变；原 A 六条记录在平台预览写入之前也已逐条保留核对。
- 原 A 应用停止及重启后旧预览均拒绝，新写入数据保留。原进程和解压工程的两次运行都保留自有停止证明，A/B 测试数据库身份及空 schema 不变。

独立目录 `/private/tmp/atoms-ex11-contract.APT7Jb/todo-applications-y6dt7dkp/delivery-independent-17277187989348381113`，包含源码 ZIP、manifest、构建/运行输出、实际停止证明及桌面/手机截图；私有运行配置在解压工程之外，未进入 ZIP。截图已实际检查，无横向溢出。归档 `.local/delivery-pd14-native-evidence-eqjskd4h`；日志、报告、ZIP 和 JAR 应用资源对 9 项真实配置秘密值扫描零匹配。

新增固定阶段 DEBUG 日志只记录内部 UUID、固定阶段和耗时，不记录命令/环境/凭据。此次初次启动前检查约 39 秒；120 秒启动上限未改变。较早失败缺乏完整阶段计时，不能声称已由这次成功定位其全部原因。后端全量兼容性与原平台浏览器回归仍在 PD14 完成。

## PD14 首次完整回归及测试夹具修正

`ex22-delivery-pd14-full.log` 于 15:30:56 结束，退出 1，33:34。127 个单元测试类的 647 项通过；139 个集成测试类共 612 项，其中 598 项通过、1 项断言失败、13 项错误，零跳过。日志与本轮新 XML 逐类核对，归档 `.local/delivery-pd14-full-evidence-b6xqmd91`；没有将旧报告混入本轮结果。

- `ExecutionDatabaseLaunchIT` 三项失败：PD03 增加 `APP_TEST_CONFIG` 后，旧受控子 JVM 仍断言只有四个 APP 变量，因而主动以 13 退出。独立最小复现使用非秘密合成环境证明此分支，不访问数据库。夹具改为精确检查五个变量名、预期私有配置路径和 0600 权限，并继续检查父/子 JVM 的数据库身份、原调用不被修改及结果不泄密；不是删除隔离断言。
- `DeliveryPreviewIT` 七项与 `DeliveryRestorationIT` 四项因上下文初始化失败而未执行：日志显示 PostgreSQL 非超级用户连接槽耗尽。进入交付测试前已有十九个缓存 Spring 上下文连接池，新增上下文使其累积到二十个，每池上限五。交付模块四个 HTTP 测试类改为在类结束后关闭自身上下文，释放连接池及服务器，不修改数据库上限、生产池配置或其他会话。
- 修正仅涉及上述五个测试文件，生产源码、模板、前端及原生交付实现未变。接着定向复验全部交付 HTTP 测试和真实数据库启动契约；复验结果单独记录，首次完整回归仍保留失败状态。

完整回归显式开启 `project-execution-contract,agent-cli-contract`，实际 CLI 为本机 2.1.303 的受控离线上游，不调用付费模型。单独排除六个既有原生构建/有状态契约，沿用 [EX29 及原生业务验收记录](project-execution-verification.md#2026-10-27--ex29b-种子通过及最终资源核对)；本轮 `ExecutionDeliveryPreviewContractIT` 与固定记录 `ExecutionStoppedRunRecoveryContractIT` 已分别实际执行，不在全量任务中重复改写状态。八项排除不计为本轮完整回归通过数。

## PD14 后端定向复验通过

`ex22-delivery-pd14-fixtures-corrected.log`：42 单元 + 30 集成通过，退出 0，零失败/错误/跳过。实际子 JVM 的准确测试库、配置路径、重复授权/并发拒绝均通过；交付管理、预览、下载、恢复全部 HTTP 测试通过。四个交付测试上下文逐个关闭，日志核对同时存活的连接池峰值 1，结束遗留 0。较早 `ex22-delivery-pd14-fixtures-fixed.log` 因 DirtiesContext import 包名错误编译失败，未执行测试，已修正并保留原日志。

归档 `.local/delivery-pd14-focused-evidence-k647568_`，逐项覆盖首次全量的三个失败测试类，没有排除或跳过失败项。修正提交 `fa7072c`、`db396d2` 只改变五个测试文件；两批实际生产源码、前端与模板的组合 SHA-256 均为 `99104f10128b06df09e208d2f18e491416b9cb8ef4063ec188589037694e2ffc`。因此最终后端证据覆盖 **647 单元 / 612 集成的不同用例**，由一次完整回归及定向修复复验组成，不称为单次全量绿色，也不把重跑用例重复相加。日志、XML、差异对 9 项实际秘密值扫描零匹配。

复验命令：

```sh
sh .local/run-ex22-checks.sh ex22-delivery-pd14-fixtures-corrected.log \
  '-Dtest=ExecutionTestDatabaseTest,ExecutionBusinessConfigTest,Delivery*Test' \
  -Pproject-execution-contract \
  '-Dit.test=ExecutionDatabaseLaunchIT,ExecutionTestDatabaseIT,Delivery*IT' \
  -Dexecution.test-config-directory=/Users/wubingheng/atoms-demo/.local/project-execution-acceptance.x5cvui \
  verify
```

首次完整回归使用同一 runner，参数如下；不要删去有状态契约的排除项后在已有 V2 库重放 V1 初始化：

```sh
sh .local/run-ex22-checks.sh ex22-delivery-pd14-full.log \
  -Pproject-execution-contract,agent-cli-contract \
  '-Dit.test=*IT,!ExecutionSeedBuildContractIT,!ExecutionTodoBackendContractIT,!ExecutionApplicationContractIT,!ExecutionApplicationUpgradeIT,!ExecutionApplicationMigrationFailureIT,!ExecutionLockPreparationContractIT,!ExecutionDeliveryPreviewContractIT,!ExecutionStoppedRunRecoveryContractIT' \
  -Dexecution.test-config-directory=/Users/wubingheng/atoms-demo/.local/project-execution-acceptance.x5cvui \
  -Dexecution.contract-directory=/private/tmp/atoms-ex11-contract.APT7Jb \
  -Dexecution.runtime-fixture-root=/Users/wubingheng/atoms-demo/.local/project-runtime-ex22-6kc9y53e \
  -Datoms.agent.test.cli=/Users/wubingheng/atoms-demo/.local/ex11-cli-2.1.303.XX2V2d/node_modules/@anthropic-ai/claude-code-darwin-x64/claude \
  verify
```

## PD14 浏览器、资源核对与最终汇总

`delivery-pd14-browser-host.log`：22 项通过，5.1 分钟；`delivery-pd14-browser-host-runtime.log`：49 项通过，4.5 分钟。两套真实 Chrome 用例均零失败/跳过，runner 退出 0；覆盖邮箱切页/登录返回、账号同步、响应式及无障碍、生成请求恢复、共享预览、工程下载、版本恢复、发布、数据冲突和工作台内部滚动。邮件传输与模型响应使用既有受控夹具，不能当成真实 SMTP/付费模型联调。沙箱内首次 webServer 启动失败的 `delivery-pd14-browser.log` 保留，未执行用例；随后在获准主机环境运行成功。

两套浏览器依次执行，均为 `CI=true`，由 Playwright 自行启动和停止现有 8081/5174 夹具，没有复用个人浏览器会话；JAVA_HOME 使用项目隔离 17.0.14，Node 为 24.20.0，EX22 测试 schema，新交付开关为 false。用户默认 Java 仍为 17.0.5。

最终只读核对：当前测试 schema 的 **157 条运行全部结束**，执行任务槽 0、协调/绑定锁 0；3 条迁移批准及 2 条核对审计保留。新增的第二条核对正是本轮已批准的固定停止运行恢复。原五份未决审计及此前执行/绑定记录摘要保持不变；A/B 绑定及数据库/角色 OID 未变。当前 A/B 分别 7/4 条业务记录，各 3 条迁移历史；原数据保留的前后比较见 PD13 实际契约。8081、5174、18181、18182 均无监听。

| 验证层级 | 最终证据 |
|---|---|
| 平台后端 | 647 单元、612 集成的不同用例；一次全量及定向修复复验，保留首次 14 项失败 |
| 前端静态与单元 | 类型、Lint、格式、372 单测、生产构建通过 |
| 原平台 Chrome | 22 页面 + 49 运行时通过 |
| 新完整工程交付 | 1 个完整原生契约；自身脚本、22 Vue + 15 Java 检查、真实 A 预览 / B 下载独立运行、重启与源码恢复通过 |
| 固定记录恢复 | 1 个显式批准的实际恢复契约，原子审计及幂等重放通过 |
| 私有信息与资源 | 10 项实际秘密值对最终差异、浏览器证据、源码 ZIP、平台及独立 JAR 应用资源扫描零匹配；端口和执行资源已释放 |

收口归档 `.local/delivery-pd14-close-evidence-qelel48d`（目录 0700，文件 0600），包含两套浏览器输出/截图、首次失败、静态检查日志、只读审计、runner 及汇总，并关联原生/全量/定向三份归档。未推送 GitHub、未部署、未调用真实模型或发送邮件、未修改个人配置。

**PD01–PD14 与 C1–C5 完成内部验收。** 本结论限于交付后端及样本业务；正式工作台的新模式入口、真实模型端到端生成和公开发布/部署尚未完成，默认开关仍关闭。
