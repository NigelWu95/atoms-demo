# project-delivery 任务清单

依据[规格](../../SPEC-project-delivery.md)及[计划](plan.md)，2026-10-27 开始实施。未勾选不代表完成；测试通过与真实浏览器/下载工程验收分别记录。

- [x] **PD01：独立构建配置入口**（无依赖；2–3 文件）
  - `build.mjs` 接受唯一 `--test-config` 私有外部文件，只有 Maven 获得文件路径，npm 不获得配置或凭据；无参数种子构建不变。
  - 文件：模板 `scripts/build.mjs`、`build.test.mjs`、README；验证 N：脚本次序、拒绝非法文件/参数、环境和参数泄漏检查。
  - 证据：`.local/delivery-pd01-red.log` 两项新增失败复现缺口；`.local/delivery-pd01-green.log` 全部 14 个脚本测试通过。只证明参数和执行边界，真实 Java 配置/下载构建待 PD02/03/13。
  - 模板打包检查发现新增测试占位符不在已有源码策略白名单，改用已有 `RUNTIME_CANARY`，未放宽策略。`.local/ex22-delivery-pd01-template-fixed.log` 模板单测 5 项、真实 Boot JAR 模板打包检查 2 项通过；原失败日志保留。
- [x] **PD02：可移植 Java 测试配置**（PD01；拆为 PD02a/b）
  - 模板测试辅助类读取显式 test 配置，检查真实数据库/角色/schema、非超级用户、用途标记与空 schema；初始化 MVC 测试仍不需要 DB。
  - 文件：模板测试辅助类及测试、目录清单、平台 POM、模板断言；验证 J：模板清单/打包一致性及配置拒绝路径。超出五文件时拆成 PD02a/b。
  - [x] PD02a：独立 Java 配置辅助类及 4 个文件边界测试。`.local/delivery-pd02-red.log` 记录实现前失败；沙箱 JVM 自附加失败原日志保留，使用隔离 JDK 在主机重跑 `.local/delivery-pd02-green-host.log`，8 个模板 Java 测试全部通过。真实 DB 身份检查待 PD03。
  - [x] PD02b：两份测试源纳入清单/POM，更新三个实际目录/种子断言，共五个文件；`.local/ex22-delivery-pd02-template.log` 模板/依赖策略及真实 Boot JAR 打包检查通过。原生完整种子构建尚未重跑，不将断言更新视为原生通过。
- [x] **PD03：待办样本和 worker 接入**（PD02；五个实现/测试文件）
  - 新待办版本去掉固定 A/B 名称，worker 传显式测试配置路径；独立脚本可使用 B_test，事务回滚，业务配置被拒绝。
  - 文件：ExecutionPrivateConfig、相关测试、待办测试样本、原生契约辅助入口；验证 J + A 定向配置与业务测试。记录新快照，不改旧版本。
  - 证据：`.local/ex22-delivery-pd03-red.log` 复现缺失路径；`ex22-delivery-pd03-worker.log` 10 项通过；新目录 `.local/delivery-pd03-native-3cilnunr/project/backend` 直接执行自身 Maven（无平台类路径），`delivery-pd03-native.log` 16 项通过，包括 A/B 真实身份只读检查、伪造业务用途拒绝和 B_test 六项待办业务测试。
  - 新增 `@AfterAll` 再检查实际空 schema，`delivery-pd03-rollback.log` 六项通过。以上均不启动应用、不迁移业务库；新源码持久版本及完整 worker 构建留在 PD08b，ZIP 自身全脚本验收留在 PD13。

**C1 已通过：** 构建参数入口、模板打包及真实独立 Java/DB 测试已验证；不据此宣称完整下载工程验收完成。

- [x] **PD04：交付开关与 origin**（无依赖；4 文件）
  - 默认关闭、启用时严格校验明确 origin、常量容量/期限；禁用不启动执行资源。
  - 文件：DeliverySettings、测试、application.yml、配置说明；验证 J：禁用、非法 origin、无 Host 派生。
  - `.local/ex22-delivery-pd04-green.log` 新旧设置共 12 项通过；不包含任何预览 HTTP 路由或执行资源启动。
- [x] **PD05：受限 HTTP 请求及有界响应**（PD04；拆为 PD05a/b）
  - 限根/静态/api 路径、方法、JSON、头及大小；固定 loopback、无代理/凭据/跳转、有界读取和取消。
  - 文件：执行 HTTP 请求策略、transport、单测/故障夹具；验证 J：规范化、超量/慢响应、3xx、MIME、预期 JSON 409。
  - 拆为 PD05a 请求对象与策略、PD05b 有界上游传输；分别验证提交，合并后才标记 PD05 完成。
  - [x] PD05a：`.local/ex22-delivery-pd05a-green.log` 五项策略测试通过，涵盖多组路径/查询/头注入、UTF-8 JSON、大小及不可变性；尚未建立任何应用连接。
  - [x] PD05b：`.local/ex22-delivery-pd05b-green.log` 11 项请求/loopback 传输测试通过；新增静态大文件和派发前超时验证后，`ex22-delivery-pd05b-bounds.log` 7 项传输测试通过。覆盖业务 409、模块字节、恶意头、跳转、MIME、超大/慢正文、写入不确定及 HEAD/204；这是故障夹具，不是实际 Vue 应用验收。
- [x] **PD06：原运行句柄 HTTP 交换**（PD05；拆为 PD06a/b）
  - 在执行模块内部获取原 owner 的目标，校验前后共享总预算；替换/失权/产物漂移失败，写入不确定结果明确。
  - 文件：ProjectExecutionAccess、ExecutionCoordinator、交换 DTO、执行集成测试；验证 J：旧句柄不复用新运行、总期限、未派发与已派发写故障。
  - 分为 PD06a 将健康验证过的固定目标保存在原 process owner，PD06b 句柄交换/共享预算。复用原 process 引用，避免将端口逐层暴露到协调器或交付 API。
  - [x] PD06a：`ex22-delivery-pd06a-green.log` 13 项进程/真实健康 HTTP 检查通过，目标在健康前不可取、之后不可换、停止即失效。此前 `pd06a-red` 因未等编译退出而与后次编译重叠，输出无效并保留，不计为有效 RED；PD06b 使用单 Maven 的全新编译再次检查本组。
  - [x] PD06b：`ex22-delivery-pd06-second.log` 全新编译后的 30 项相关单测及 3 项实际 owner/PG 集成通过；正常 HTTP 返回原 run/execution，期限和跨 owner/停止边界通过。
  - 响应后期限耗尽的新增检查发现底层产物校验会转换非标准预算异常，已让预算沿用 `EXECUTION_TIMEOUT` 协议。保留 `ex22-delivery-pd06-postcheck.log` 失败；修正后 `ex22-delivery-pd06-postcheck-fixed.log` 7 项传输与 3 项 owner/PG 集成通过，读取返回超时、已派发写入返回 `DELIVERY_WRITE_UNCERTAIN`。没有重复请求或重新绑定目标。

**C2 已通过：** 受限 transport 已接到准确运行，包含返回前重新校验；尚不声称浏览器可用。

- [x] **PD07：短期会话预览授权**（PD04、PD06；至多 5 文件）
  - 256-bit token，仅存摘要；十分钟、不续期、32/8 容量；创建/撤销检查归属、CSRF，逐请求核验原会话及准确运行。
  - 文件：授权 registry、service、管理 controller、单测/集成测试；验证 J：串用/登出/过期/容量/重启/归档拒绝。
  - [x] PD07a：授权内存注册表及并发名额；`ex22-delivery-pd07a-green.log` 9 项注册表/设置检查通过，覆盖 256-bit 随机令牌、单调过期、跨项目/重启失效、32/8 与 16/4 容量、撤销后实际完成才释放名额。
  - [x] PD07b：执行模块的轻量项目授权入口；会话/准确句柄服务层分别提交验证。
    - 项目授权入口已完成：`ex22-delivery-pd07b-project-green.log` 4 项授权注册表单测、3 项实际 owner/PG 集成通过。管理撤销无需重新取得已停止运行的句柄，仍检查维护者、归属、归档和期限。
    - `ex22-delivery-pd07b-session-green.log` 14 项会话服务/注册表/设置单测通过；原会话撤销、配置变化和原句柄失效会撤销授权，响应后写入状态不确定不重试。HTTP 会话与 CSRF 留在 PD07c 验证。
  - [x] PD07c：创建/撤销管理 API 与真实会话/CSRF HTTP 集成。`ex22-delivery-pd07c-http.log` 14 项定向单测、7 项真实 HTTP/PG 会话集成通过；覆盖缺少 CSRF、匿名、跨 owner、非法目标/ID、撤销幂等、实际退出后授权永久失效。执行门面在此 HTTP 测试中模拟，实际进程证据见 PD06。
- [x] **PD08a：预览 HTTP 与浏览器策略**（PD07；至多 5 文件）
  - 精确 stateless 过滤链、CSP sandbox、Origin null CORS/OPTIONS；16/4 交换容量；不转发平台或上游敏感头，错误不回显令牌。
  - 文件：preview controller、security configuration、header policy、边界测试；验证 J：管理 CSRF/CORS 不被放宽，取消释放容量。
  - 拆为 PD08a1 纯请求/响应浏览器策略、PD08a2 异步有界 HTTP 接入及生命周期验证，避免在一组修改中同时引入策略与并发状态处理。
  - [x] PD08a1：`ex22-delivery-pd08a-policy-delete.log` 11 项策略检查通过；确切令牌的 assets/api CSP、无凭据 Origin null、请求头白名单、预检、非法路径与无正文 DELETE。此步尚未注册预览过滤链。
    - HTTP 验证发现 Tomcat 不以 `setHeader(name, null)` 移除已有 XFO；改为 `SAMEORIGIN` 后备，现代浏览器由明确 CSP frame-ancestors 约束。`ex22-delivery-pd08a-http-fixed.log` 中该项已通过，整组因并发测试自身入场竞争未通过，保留失败证据并另行修正测试同步。
  - [x] PD08a2：`ex22-delivery-pd08a-http-admission.log` 15 项单测与 14 项真实 HTTP/PG 集成通过；严格过滤链、JSON 409、无凭据转发、30 秒慢上传、断开释放、管理 CSRF、不续期、firewall 错误不回显令牌。并发测试等待四个实际入场完成，避免探测请求抢占名额。
  - `ex22-delivery-pd08a-lifecycle.log` 5 项单测通过；客户端已超时但 worker 忽略中断未返回时仍占名额，实际返回后才释放，迟到结果不覆盖超时。此阶段执行响应为受控夹具，真实应用和 Chrome 留在 PD08b。
- [x] **PD08b：真实 Vue 页面预览**（PD08a、PD03；至多 5 文件）
  - 原生样本+Chrome 完成模块加载、CRUD/409，检查 cookie/存储/外部请求限制及换版旧授权失效。
  - 文件：原生应用契约、浏览器测试入口/脚本及最小夹具；验证 A + B；页面不纳入正式工作台。
  - 拆为 PD08b1 实际验收暴露的只读校验性能修复、PD08b2 Chrome 业务与隔离验收，分开提交。
  - 失败证据：`ex22-delivery-pd08b-timing.log`，单次校验约 5 秒，其中两次产物检查各约 2 秒；真实 Chrome 文档/静态资源等待约 11–19 秒，API 在 10 秒由客户端取消。原因是产物完整性校验同时反复扫描全执行根容量。修复区分目标完整性与全根容量，保留所有字节、文件身份、源码、权限和运行前后检查；修正后 `ex22-delivery-pd08b-readonly.log` 的 17 项单测与 4 项实际集成通过。

  - 实际 Chrome 的新增 201、修改 200、跨页旧数据写入 409、刷新、opaque origin、cookie/storage/外部连接/顶层导航限制通过；截图和去令牌请求耗时保存在 `delivery-browser-14882716247113117923`。新增约 1.8 秒、修改约 2.1 秒；原 5 条业务数据不变，新增数据重启保留，旧授权在停止及重启后均拒绝，A/B 测试 schema 未改变。

**C3 已通过：** 实际 Vue/Java 应用经路径预览可用；原生表单事件兼容性修正已写入规格，仍禁止 same-origin、平台凭据和额外网络目标。

- [x] **PD09：确定性完整源码 ZIP**（无依赖；至多 4 文件）
  - 固定顺序/时间/STORED/Unix mode，project 文件不变，delivery 元数据独立；严格大小、路径和取消检查。
  - 文件：DeliveryZip、manifest、单测；验证 J + 标准 ZIP 解压：每文件字节/hash/mode，ZIP 上限和执行权限。
  - `ex22-delivery-pd09-zip.log` 4 项通过：JDK 读取及本机标准 unzip 解压逐文件比对、UTF-8 文件名与 100755 执行位、固定身份与确定性、非法路径/软链接模式、16 MiB 满额树、超限与中途预算到期。不读取当前模板；这里只完成打包，下载授权/API 待 PD10。
- [ ] **PD10：准确构建/预览下载**（PD07、PD09；至多 5 文件）
  - 固定构建读取可停止历史版本；预览下载绑定仍有效原 run；2/1 容量、单一三十秒预算、返回前复核，清单不含私有数据。
  - 文件：下载 service/controller、DTO、集成测试；验证 J：非 latest、跨项目拒绝、停止/换版/取消、旧模板不重组。
- [ ] **PD11：源码恢复窄门面**（无依赖；至多 4 文件）
  - 源码模块提供 head/restore/operation 窄接口，复用现有 coordinator；revision/CAS/幂等，不重复持久化算法。
  - 文件：source gateway、集成测试；验证 J：冲突/重放/当前版本/异步失败，无业务数据库写入。
- [ ] **PD12：交付恢复 API**（PD04、PD11；至多 3 文件）
  - 管理身份+CSRF、规范 UUID/Idempotency-Key、202/operation DTO；恢复不自动构建/启动。
  - 文件：controller、DTO、HTTP 集成测试；验证 J：越权/错误格式/幂等/恢复后新源码身份。

**C4：** 下载与恢复 API 有固定身份及权限证据，旧源码与业务数据未改写。

- [ ] **PD13：下载工程独立业务验收**（PD03、PD08b、PD10、PD12；至多 5 文件）
  - A 预览 ZIP 解压后用自身脚本和 B_test 构建，再 B_app 启动；新增/修改/查询/409/重启数据保留，原 A/B 记录不受影响。
  - 标准解压完整比对 Git 树与模式；V2 及恢复身份可辨；所有 owner 进程真实停止并保留审计。
  - 文件：DeliveryApplicationContractIT 与受控辅助夹具；验证 A + B，保留命令、来源和退出证据，不用平台 classpath 代构建。
- [ ] **PD14：兼容性回归与交付说明**（全部前置；文档及必要回归）
  - 默认关闭完整平台回归，账号/旧生成/导出/发布保留；受影响前端检查及浏览器测试；token/密钥不在日志、ZIP、前端中。
  - 文件：docs/project-delivery.md、docs/project-delivery-verification.md、能力地图与本清单；验证 J 全量一次及受影响 B；失败逐项说明，不把排除项写成通过。

**C5：** 满足规格完成标准才标记模块完成；后续工作台/真实模型/部署另记，不提前宣称完成。
