# engineering-bundle · 开发任务

状态：2026-10-12 用户确认继续后，EB01–EB17 已实现并验证；当前交付 C-EB07，下一步 EB18–EB19。
依据：[计划及验证命令](plan.md)、[已批准规格](../SPEC-engineering-bundle.md)。本清单只负责 `engineering-bundle`；原平台任务清单不变。

路径缩写：`P/` = `backend/src/main/java/dev/atoms/demo/engineering/`；`PT/` = 对应平台 `src/test/java/dev/atoms/demo/engineering/`；`T/` = `templates/standalone-v1/`；`B/` = `T/backend/src/main/java/dev/atoms/standalone/`；`BT/` = 对应模板 `src/test/java/dev/atoms/standalone/`；`R/` = `T/backend/src/main/resources/`；`F/` = `T/frontend/src/`。各路径的实现进度以下列任务勾选及切片记录为准。

每项列出最多 5 个主要文件（包含测试），S=1–2，M=3–5。实际超出时先拆子任务，不把额外逻辑塞进巨型文件。清单勾选和统一验收记录随每次检查点更新，不计入业务文件数量。脚手架中未接通的模板不被平台公开使用；严禁为了中间切片通过而增加空接口/空测试或跳过最终检查。

## A · 工程边界

### EB01 · 可复现的工程清单（M）

- [x] 完成 EB01。依赖：用户批准本计划与任务。
- 文件：`P/BundleSource.java`、`P/EngineeringBundle.java`、`P/TemplateSnapshot.java`、`P/EngineeringBundleAssembler.java`、`PT/EngineeringBundleTest.java`。
- 验收：① 输入快照再次经现有产物验证，错误不替换为空白模板；② 有序清单、规范 JSON、SHA-256 及 bundleId 可复现，数组/集合防御性复制；③ sourceContentHash 与版本 UUID 作为来源保存，不冒充运行身份。
- 验证：平台 `backend/` 执行 `./mvnw -B -Dtest=EngineeringBundleTest test`；先证明错序/变更字节/非法产物测试失败，再实现。组装器使用受控内存模板样本，不接磁盘或 HTTP。

### EB02 · 路径、配额与数据排除（M）

- [x] 完成 EB02。依赖：EB01。
- 文件：`P/TemplateSnapshot.java`、`P/TemplatePolicy.java`、`P/EngineeringBundle.java`、`PT/TemplatePolicyTest.java`、`PT/EngineeringBundleTest.java`。
- 验收：① 固定应用路径及模板白名单拒绝穿越、绝对路径、反斜杠、NUL、重复/大小写冲突与符号链接元数据；② 按计划拟定的 128 文件/单文件 2 MiB/总量 8 MiB 校验含 manifest 的最终输出，原产物限制不变；③ 密钥、真实状态与会话 canary 不进入文件清单，应用字符串不变成模板命令。
- 验证：V-P；测试恰好上限和超出 1 的边界、UTF-8 字节而非字符数、输入修改不污染已组装输出。
- 实施证据：[验证记录](../docs/engineering-bundle-verification.md)。文件模型只接受常规文件名/字节，无法表示链接元数据；真实磁盘符号链接/祖先链接由 EB20 校验，本阶段不读取磁盘。

### EB03 · 独立后端编译骨架（M）

- [x] 完成 EB03。依赖：EB02。
- 文件：`T/backend/pom.xml`、`T/backend/mvnw`、`T/backend/.mvn/wrapper/maven-wrapper.properties`、`B/StandaloneApplication.java`、`R/application.yml`。
- 验收：① Java 17/Boot 与必要依赖锁定，不依赖平台 Java 包；② 配置无平台地址/密码，单元及集成测试插件都禁止空跑成功；③ Wrapper 来源及版本可审阅，默认监听 loopback，测试和运行配置不互相 fallback。
- 验证：模板 `backend/` 执行 `./mvnw -B compile`；检查 effective POM 的版本与测试插件。此时没有足够测试，不能宣称 V-B 通过。

### C-EB01 · 边界检查点

- [x] EB01–EB03 定向测试与编译通过，评审不可变性、配额及文件列表；新增记录 `docs/engineering-bundle-verification.md` 并汇报。尚无可运行完整应用。
- 检查点交付：组装/路径 46 项通过；原后端 72 单元 + 50 PostgreSQL 集成通过；模板 Java 17 编译通过，空测试集按预期失败。下一步 EB04 配置初始化与 Java 入口校验。

## B · 独立启动与身份

### EB04 · 显式配置与拒绝默认凭据（M）

- [x] 完成 EB04。依赖：EB03。
- 文件：`T/config/application.properties.example`、`T/scripts/init-config.mjs`、`T/scripts/init-config.test.mjs`、`B/StartupConfig.java`、`BT/StartupConfigTest.java`。
- 验收：① 初始化只创建缺失文件并生成稳定实例 ID，不覆盖已有文件、不打印密码；② Java 入口拒绝缺失配置、占位符及非法 origin/监听配置；③ 默认 loopback HTTP 与明确 HTTPS 部署的 Cookie 安全设置分开，实例 Cookie 名由稳定 ID 派生。
- 验证：模板根 `node --test scripts/init-config.test.mjs`；模板后端 `./mvnw -B -Dtest=StartupConfigTest test`；检查错误不包含配置值。
- [x] 切片 EB04a（3 文件）：配置示例、Node 初始化器及其测试，只创建缺失配置；5 项真实 CLI 测试通过（含并发与符号链接），不创建数据库。
- [x] 切片 EB04b（4 文件）：StartupConfig、Java 入口接线、application.yml、StartupConfigTest；在 Spring 启动前校验并加载显式配置，避免先触发数据库连接。61 项测试、模板 package、实际 JAR 拒绝配置检查通过；外部文件仅接受示例列出的配置项，不继承进程中的平台/Spring 连接配置；数据库实际身份验证仍由 EB06 完成。
- [x] 切片 EB04c（3 文件）：对齐 Node 初始化器与 Java 入口的配置路径约束，更新示例说明及 Node 测试；含逗号/分号的路径反例先失败、修复后 5 项 CLI 测试全部通过。

### EB05 · 专用测试库与配置引导（M）

- [x] 完成 EB05。依赖：EB04。
- 文件：分为下述三个切片；新增固定 SQL 模板与真实数据库 IT，共享 EB04 的配置解析，不增加生产测试开关。
- 验收：① 从显式管理员连接准备专用测试/浏览器库及受限角色，不读取平台应用密码、不重置已有角色；② 输出配置在已忽略目录，分别对应 IT 与浏览器运行 A/B，重复执行安全；③ 测试要求 STANDALONE_TEST_CONFIG 与专用库标记，验证实际库/角色，不接受运行配置或平台库。
- 验证：仓库根 `node --test scripts/provision-standalone-db.test.mjs`；V-BU；经权限流程后执行脚本的 `--config-dir .local/standalone` 模式，核对 schema/角色权限，不输出凭据。
- [x] EB05a（3 文件）：管理员专用 Node 脚本、固定 SQL 模板、脚本测试；6 项脚本测试通过，按权限流程在本地创建 `atoms_standalone_it` / `atoms_standalone_a` / `atoms_standalone_b` 库与同名受限角色。再次执行成功，4 个私有配置文件逐字节未变。Java 实际连接身份验证仍待后续切片。
- [x] EB05b（5 文件）：TestDatabase、单元测试、application-test.yml，以及 StartupConfig/StartupConfigTest 的共享解析接线；86 项单元测试通过。反转角色安全判断时反例失败，恢复后全部通过；正式入口仍拒绝测试标记，不加入生产跳过选项。
- [x] EB05c（1 文件）：DedicatedDatabaseIT（避免 Test 前缀同时被 Surefire 发现）；模板 `clean verify` 通过 86 项单元 + 6 项真实 PostgreSQL IT，无跳过。只读核对库名、角色、权限、schema 和管理员标记；错误安装 ID 在上下文刷新前拒绝。未配置专用测试库时，86 项单元通过而 Failsafe 明确失败。正式 JAR 不含测试引导/配置；安装身份表的并发认领仍归 EB06。
- 本轮回归：Node 6 项建库脚本 + 3 项原发布脚本 + 5 项配置初始化测试全部通过；原平台工程组装 46 项通过。没有连接平台运行库执行测试、没有更新云端；详细边界与复现命令见新增验收记录。

### EB06 · 安装身份、迁移与健康检查（M）

- [x] 完成 EB06。依赖：EB05。
- 文件：`R/db/migration/V001__identity.sql`、`B/DatabaseInstallation.java`、`BT/DatabaseInstallationIT.java`、`B/HealthController.java`、`BT/HealthIT.java`。
- 验收：① 新库建立安装记录、访客/JDBC 会话表；schema/角色/实例身份不符在迁移及业务写入前拒绝，同库首次并发认领无交叉成功；② health 数据库正常 200、断开 503且脱敏，重复启动不重置数据；③ Flyway/JDBC Session 只作用于独立 schema，不修改平台迁移。
- 验证：V-B；覆盖重复迁移、错误实例 ID、平台库目标、超权限角色、数据库断开与并发首次启动。
- [x] EB06a：DatabaseTarget 与单元测试；复用实际角色/schema 核验到 TestDatabase，避免测试和正式启动维护两套权限规则。新类型缺失 RED 后，模板 clean verify 96 项单元 + 6 项真实 IT 全部通过；支持无开发标记的人工准备运行库，运行/测试用途标记不能互换。
- [x] EB06b：DatabaseInstallation、V001、DatabaseInstallationIT、固定连接/迁移配置及测试专用清理助手；会话级数据库锁覆盖迁移前安装校验与 V001 原子身份写入。96 项单元 + 15 项真实 IT 通过（含 9 项迁移/并发场景）；非空未认领 schema、错误实例 ID、用途混淆均拒绝。首轮 Flyway 日志选项错误已修正，未跳过测试。
- [x] EB06c：HealthController、HealthIT 与最小 SecurityConfiguration；仅 GET health 开放、其他入口默认拒绝。5 项真实 HTTP 用例先失败后通过，模板 96 单元 + 20 IT 全部通过；验证独立连接池故障、安装 ID 异常、重启保留表数据和安全头。访客认证仍归 EB08。
- [x] EB06d 安全复核：测试引导在清理前复用正式安装身份核验；错误实例 ID 的 Flyway 回调确认未执行。修复携带会话 Cookie 且连接池失效时 health 提前报 500 的问题，仅精确 GET health 不读取会话 Cookie，其他路径及 Cookie 安全属性不变。关键角色判断变异触发失败，恢复后 96 单元 + 23 真实 PostgreSQL/HTTP IT 全部通过；原组装 46 项、脚本组合 14 项通过。实际 JAR 启停结果及限制见验收记录。

### C-EB02 · 可安全连接自己的数据库

- [x] 技术验收：EB04–EB06 单元/脚本/真实 PostgreSQL 测试通过；配置/安装身份误用在迁移或测试清理前失败；私有配置位置与复现命令已记录，未记录凭据。
- [x] 已向用户交付数据库护栏、取舍与验证记录，用户回复“继续吧”，批准继续 EB07–EB08；不包含合并或部署授权。

### EB07 · 独立 API 错误与入口限制（M）

- [x] 完成 EB07。依赖：EB06。
- 文件：`B/ApiError.java`、`B/ApiException.java`、`B/ApiExceptionHandler.java`、`B/RequestBoundaryFilter.java`、`BT/ApiBoundaryIT.java`。
- 验收：① 错误含稳定 code/retryable/requestId，日志关联但不泄露 SQL/路径/配置；② JSON 大小/深度和非法请求明确拒绝，未知 API 返回 404 JSON 而非页面；③ 401/403/409/413/422/503 状态映射按新契约，不修改平台的错误映射。
- 验证：V-B，覆盖超限、截断 JSON、未知路由、内部异常脱敏与 requestId 对应。
- [x] EB07a：ApiError、ApiException、ApiExceptionHandler、ApiErrorsTest、HealthController，统一可信错误码与脱敏响应；110 单元 + 23 IT 通过。
- [x] EB07b：RequestBoundaryFilter、SecurityConfiguration、ApiBoundaryIT、HealthIT、ApiExceptionHandler，入口字节/JSON 深度限制、安全链错误接线及媒体协商拒绝；6 项真实 HTTP 反例先失败，修正测试专用故障路由装配后 110 单元 + 29 IT 全部通过。
- 入口约束细化：请求体最多 320 KiB（为 256 KiB 状态及外层字段留余量），JSON 最大容器深度 64；拒绝重复键、尾随文档和非 JSON 写入。该上限不是业务状态上限，状态值仍单独限制 256 KiB。

### EB08 · 独立访客与 CSRF（M）

- [x] 完成 EB08。依赖：EB07。
- 文件：`B/SecurityConfig.java`、`B/SessionController.java`、`B/GuestIdentityService.java`、`B/SecurityHeadersFilter.java`、`BT/SessionIT.java`。
- 验收：① csrf → 空对象 session 建立自己的访客、持久会话和身份，重复初始化不换访客；② 登录旋转 Cookie/CSRF，7 天有效期，缺/错 CSRF、跨访客与平台 Cookie 都不能越权；③ Cookie 名区分实例，宿主 CSP 严格、无 wildcard CORS，未认证应用/状态端点 401。
- 验证：V-B；两个 Cookie jar、旧 Cookie/令牌和另一实例 Cookie 反例；跨真实进程重启在 EB22 再验。
- [x] EB08a：GuestIdentityService、SessionController、既有 SecurityConfiguration、SessionIT、ApiExceptionHandler；复用已有安全配置类，接通 JDBC 会话和 CSRF/会话 ID 轮换。7 项会话用例先失败，补齐数据库事务异常映射后 110 单元 + 36 IT 全部通过。
- [x] EB08b：SecurityHeadersFilter、SecurityHeadersTest、RequestBoundaryFilter、SecurityConfiguration、SessionIT；统一早期拒绝的安全响应头、同源策略与仅请求头 CSRF。查询参数 CSRF 先被错误接受，修复后拒绝；反转 Origin 判断导致两项测试失败，恢复后通过。
- [x] EB08c：application.yml、SessionIT；注入会话最终保存故障，确认不会发送缓冲的成功响应，并补齐重复框架日志的脱敏保护。日志反例先失败，修复后完整验证为 112 单元 + 39 IT，无跳过。

### C-EB03 · 真实会话切片

- [x] 技术验收与自审：EB07–EB08 接口可调用且鉴权失败路径通过；真实 JAR 的 A/B 实例各有独立库及 Cookie 名称，A Cookie 即使改成 B 的名称也不能鉴权；A 的有效 Cookie/CSRF 跨真实进程重启恢复同一访客且不影响 B。进程已停止，数据库保留；未完成业务状态保存或浏览器工程验收。
- [x] 2026-10-12 用户确认继续下一切片；未授权主分支合并、GitHub 推送或部署，完整代码人工审阅仍留在合并前。

## C · 应用进入沙箱

### EB09 · 应用资源与内容运行标识（M）

- [x] 完成 EB09。依赖：EB08。
- 文件：`B/ApplicationArtifactLoader.java`、`BT/ApplicationArtifactTest.java`、`BT/TestApplication.java`、`T/scripts/package-application.mjs`、`T/scripts/package-application.test.mjs`。
- 验收：① 只读取四个固定 application 文件，校验 manifest/初始状态/源码，非法或缺失明确失败；② 打包脚本对实际应用与受控运行资源计算稳定 runtime ID，变化会更新，路径/时间不影响；③ 公开描述不泄露内部配置，sourceContentHash 仅标示历史来源。
- 验证：V-BU；模板根 `node --test scripts/package-application.test.mjs`；TestApplication 提供独立工程自身检查需要的固定输入与 V-D 引导，不在正式包缺文件时 fallback 到夹具。实际应用不可变模型作为 loader 的嵌套 record，若扩散则拆子任务；构建接线在 EB18 完成。
- 实施拆分（每组最多 5 个主要文件）：EB09a 应用校验及 HTML 白名单（loader、ApplicationHtml、单元测试）；EB09b 内容指纹、资源打包脚本及双语言一致性测试；EB09c 测试专用引导与现有 HTTP 测试接线。测试夹具只通过显式测试引导注入，不成为生产缺文件的后备。
- [x] EB09a：固定四文件、严格 manifest/UTF-8/体积/深度校验、初始值防御性复制；HTML 白名单与平台一致。缺少实现的测试先失败，实现后通过。
- [x] EB09b：进一步分为 Node 打包器/测试（2 文件）、RuntimeResources/测试（2 文件）；摘要覆盖实际四个应用文件与两个受控 runtime 文件，Java/Node 共享固定摘要向量，加载时重新核对打包指纹。修改任一文件会改变指纹，路径/时间/历史来源元数据不影响。
- [x] EB09c：TestApplication 与夹具单元测试接线（3 文件），再接既有 HTTP IT（3 文件）；显式 test-run 已由真实 Chrome 验证，正式入口缺资源时拒绝启动。没有向正式 JAR 加入测试应用。

### EB10 · 受限运行文档端点（M）

- [x] 完成 EB10。依赖：EB09。
- 文件：`B/RuntimeDocumentService.java`、`B/AppController.java`、`R/runtime/bootstrap.js`、`R/runtime/document.html`、`BT/RuntimeDocumentIT.java`。
- 验收：① GET app/document 只服务当前独立应用并要求身份；② 复用净化与上下文编码语义，iframe 专用 CSP 不放宽宿主策略；③ 保持 Atoms.ready/storage 协议，恶意闭合 script、事件属性/导航/联网输入不能扩大权限。
- 验证：V-B；测试与平台运行文档共用的攻击向量，核对 document 的 CSP/X-Frame-Options 组合，不能通过删除冲突头之外的所有保护来放行。
- 实施拆分：EB10a 原样移植受控 runtime 资源及兼容校验；EB10b 渲染服务、端点、生产资源 Bean 和 HTTP 测试；EB10c 专用响应头策略与失败响应回归。先有 runtime 资源再完成 EB09b 指纹验收；最终前端构建产物纳入标识在 EB18 接线时补齐。
- [x] EB10a：bootstrap.js/document.html 与平台逐字节一致；仓库级一致性测试同时核对 HTML 白名单。
- [x] EB10b：GET app/document 先返回 404 的两项反例失败；接线后返回当前应用公开描述及安全编码 HTML，访客校验不允许选择平台项目/文件。
- [x] EB10c：RuntimeDocumentPolicy、RequestBoundaryFilter、AppController、HTTP/单元测试（5 文件）；修复 CSP 追加造成的严格策略叠加，只在最终成功 HTML 上允许 SAMEORIGIN，会话最终保存失败恢复严格头。反转成功状态判断，策略测试失败，恢复后完整回归通过。
- [x] EB10d：独立文档 Playwright 配置/测试（2 文件）；真实 Chrome 的 opaque iframe、SDK 握手/点击、直接访问和未认证拒绝通过。宿主和状态响应是浏览器测试夹具，不冒充 EB11–EB17 的产品实现。

### C-EB04 · 内容边界检查点

- [x] 技术验收及自审：模板 121 单元 + 44 PostgreSQL/HTTP IT；Node 20 项、原平台组装 46 项、原 SDK 单元 4 项、真实 Chrome 2 项通过，无跳过。文档专用 CSP 与原平台语义一致，新增最终响应提交检查；测试进程已停止，未推送/部署。
- [x] 已向用户交付本检查点，用户回复“继续”，批准推进 EB11–EB12；不等同于代码合并或部署授权。最终前端/受控运行层构建输入指纹与打包接线仍归 EB18，不能把当前六资源指纹当成整个工程身份。

### EB11 · 最小 Vue 构建配置（M）

- [x] 完成 EB11。依赖：EB10。
- 文件：`T/frontend/package.json`、`T/frontend/package-lock.json`、`T/frontend/vite.config.ts`、`T/frontend/tsconfig.json`、`T/frontend/index.html`。
- 验收：① Vue/TS/Vite 和必要工具固定版本，有独立真实 lockfile，不手改锁文件；② Vite 仅 loopback、同源代理，无后端密钥进入前端；③ 配置 typecheck/lint/unit/build 入口，未有测试时明确失败，不创建空测试充数。
- 验证：`npm --prefix templates/standalone-v1/frontend ci --ignore-scripts`；检查锁文件差异与开发安全头。完整 V-F 在 EB12 执行。
- 实施拆分：EB11a 包清单、真实锁文件、TS、HTML、静态图标（5 文件）；EB11b Vite 配置、开发代理边界及测试（3 文件）。额外的代理边界负责在改写同源 Origin 前拒绝外站请求，不放宽后端策略。
- 验收结果：独立锁文件与冻结安装通过；生产依赖仅 Vue，未升级原平台。开发/预览固定回环端口，精确 Host/Origin/Fetch Metadata 检查后才转译同源 Origin；无 CORS、无前端环境变量注入。8 项边界测试通过，关键条件反转使 2 项失败，恢复后全绿。

### EB12 · 可访问的启动外壳（M）

- [x] 完成 EB12。依赖：EB11。
- 文件：`T/frontend/eslint.config.js`、`F/main.ts`、`F/App.vue`、`F/App.spec.ts`、`T/.gitignore`。
- 验收：① 页面只展示真实启动/错误状态，无假的生成或保存结果；② 状态播报、键盘焦点和响应式布局可用；③ 初始化失败可重试、不丢错误，忽略真实配置/产物/依赖/测试输出。
- 验证：V-F；组件测试先覆盖启动、错误与重试。完整浏览器尺寸与 axe 在 EB23 验收。
- 实施拆分：EB12a lint/安装策略/忽略规则（3 文件）；EB12b 真实 health 客户端及测试（2 文件）；EB12c 启动组合函数/main/App/样式/组件测试（5 文件，组合函数由组件行为测试覆盖）；EB12d 仓库侧真实浏览器配置与验收（2 文件）。会话和沙箱仍归 EB13–EB14。
- 验收结果：真实 health、5 秒超时、错误分类、重试保留故障/焦点、禁止重复请求与卸载取消完成；响应限制 1 KiB，不显示上游原文。25 项单元、typecheck/lint/build 全部通过；7 项 Chrome 覆盖 320/390/768/1024/1440、axe、键盘、断网重试及开发/构建预览代理。不会显示应用运行/保存成功。

### C-EB05 · 外壳可以构建

- [x] EB11–EB12 类型、lint、单元及实际构建通过；无平台管理页面、Pinia/Router、模型 Key 或平台 URL。真实浏览器和截图已核对，详细证据见新增验收记录。
- [x] 已交付 C-EB05，用户回复“继续”，按计划推进 EB13–EB14；不等同于合并或部署授权。状态保存、新目录构建和完整工程导出仍待后续任务。

### EB13 · 连接真实会话与应用描述（M）

- [x] 完成 EB13。依赖：EB08、EB10、EB12。
- 文件：`F/api/client.ts`、`F/api/client.spec.ts`、`F/App.vue`、`F/App.spec.ts`。
- 验收：① 前端按 csrf/session/app 顺序连接本应用 API，令牌仅存内存；② 严格解码响应，身份过期/403/数据库不可用提示真实原因，不无限重试写入；③ 不持有平台 URL、项目 ID 或 API Key。
- 验证：V-F、V-B、V-D；在 Vite 同源代理下浏览器检查实际接口返回，不把模拟组件测试当成联调。
- 实施拆分：EB13a 严格响应解码与客户端/测试（3 文件）；EB13b 启动组合函数、App 与组件测试（3 文件）。实际顺序为 health → csrf → session → 旋转后 csrf → app；失败不自动重放 POST，令牌不进入 DOM/日志/持久存储。
- 验收结果：真实 Vite 代理下建立访客并读取当前应用信息，重新连接保留同一访客；整体初始化 15 秒、JSON 响应 16 KiB 上限、严格状态/字段解码。401/403/503、畸形响应、取消与失败重试有测试，错误只显示固定文案和合法 requestId；暂无业务数据写入客户端。

### EB14 · 挂载沙箱与兼容消息桥（M）

- [x] 完成 EB14。依赖：EB13。
- 文件：`F/runtime/protocol.ts`、`F/runtime/bridge.ts`、`F/runtime/mountFrame.ts`、`F/runtime/bridge.spec.ts`、`F/App.vue`。
- 验收：① 仅 allow-scripts、随机通道及严格 source 校验，MessageChannel 不传 Cookie/令牌；② Atoms.ready、协议输入校验、运行失败、加载超时和卸载清理符合原行为；③ 固定计数器真实运行，状态接口尚未完成时不显示保存成功。
- 验证：V-F、V-D；协议反例/重复 HELLO/过期通道/卸载后消息；浏览器操作计数器及检查 CSP 报错，持久化留待下一切片。
- 实施拆分：EB14a 协议/解析测试、桥/桥测试（4 文件）；EB14b 挂载器/测试、随机 ID（3 文件）；EB14c RuntimeFrame/组件测试、App/测试、样式（5 文件）；EB14d 显式测试应用夹具、原文档浏览器定位器、新外壳浏览器验收、ApplicationArtifactTest 固定规范化输入（4 文件）。夹具增加明确的“本页计数”以验证纯页面交互，原 SDK 存储计数保留；正式外壳对未接入的存储明确拒绝，不伪造初始值或保存成功。规范化单元测试不依赖可演进的浏览器布局，保留原精确输出断言。
- 验收结果：固定文档地址、allow-scripts-only、随机通道、严格 opaque origin/source、单次端口转移、16 个并发请求上限、启动/数据错误分离；文档加载和握手各限 10 秒，导航/卸载清理连接，重载前确认。独立前端 53 项、真实外壳 Chrome 9 项、原文档 Chrome 2 项通过；数据接口明确返回 READ_ONLY_PREVIEW，迟到 READY 不覆盖失败。

### C-EB06 · 第一条浏览器运行链路

- [x] EB13–EB14 真实会话 → 应用文档 → 沙箱运行可操作；源码/截图复核未放宽同源、Cookie 或联网权限。模板后端 121 单元 + 44 PostgreSQL/HTTP IT、原组装 46 项、脚本 20 项、原 SDK/桥 9 项通过，详情见新增验收记录。该证据不是持久化或完整独立构建验收。
- [x] 本轮交付 C-EB06；最终外壳浏览器 9 项连续三轮 27 次通过，无自动重试。下一步 EB15–EB17；当前不提供完整工程下载入口，不推送/合并或部署。

## D · 可持久化应用

### EB15 · 访客读取自己的状态（M）

- [x] 完成 EB15。依赖：EB14。
- 文件：`R/db/migration/V002__app_state.sql`、`B/AppStateService.java`、`B/StateController.java`、`BT/StateReadIT.java`、`F/api/client.ts`。
- 验收：① 首次 GET 返回 manifest 初始值、revision=0、schema=1，不修改平台数据；② 每个有效访客读取自己的状态，初始对象不会被共享引用污染；③ SDK get 通过真实 API 返回，并保持登录/数据库错误语义。
- 验证：V-B、V-F；两个会话读取与缺省初始值边界，GET 不意外重置已有数据。
- 实施拆分：EB15a（3 文件）V002、专用测试清理白名单、迁移断言；EB15b（5 文件）状态文档/服务/控制器、HTTP 测试助手、读取 IT；EB15c 与 EB17 客户端接线一起验证真实 SDK，避免中间界面宣称可保存。
- 验收结果：首次 GET 不建状态行、不重置已保存值；访客只来自有效会话，查询参数不能选择他人。3 项真实 HTTP 读取/隔离/数据库故障场景通过，SDK get 已接真实接口。

### EB16 · 原子保存与幂等重放（M）

- [x] 完成 EB16。依赖：EB15。
- 文件：`R/db/migration/V003__idempotency.sql`、`B/IdempotencyService.java`、`B/AppStateService.java`、`B/StateController.java`、`BT/StateWriteIT.java`。
- 验收：① 严格 value/大小/revision/schema/runtime ID 校验，旧版本和修订冲突 409；② 同键相同请求只提交一次，同键异体 409，并发由数据库约束与事务保证；③ 成功重放优先于当前 runtime ID 检查，事务失败不留下占位结果，记录保留不短于允许重试期。
- 验证：V-B；并发同键/异键、初始并发、事务回滚、更新 runtime 后旧成功请求重放、上限输入；不得用内存锁代替数据库测试。
- 实施拆分：EB16a（5 文件）V003、专用清理/迁移断言、输入规范化及单元测试；EB16b（4 文件）幂等服务、状态保存/控制器、写入 IT。事务按当前访客行锁串行化写入，3 秒锁等待和 5 秒事务预算；状态与成功响应同一事务提交，先重放再核验运行版本。键沿用平台的 1–128 位 ASCII 标识约束；仅收集访客 UUID、请求摘要和必要重放响应，不记录内容日志。当前不自动清理重放记录（至少覆盖 7 天会话重试窗口）；访客删除级联清除状态/重放，定期回收留后续。
- 验收结果：3 项输入单元及 9 项真实 PostgreSQL/HTTP 写入测试通过；同键同体/异体、异键抢写、初次建状态、结果写入失败回滚、锁等待超时/另一访客不受阻、服务上下文重启后旧版本成功重放均有证据。JSON 值精确 256 KiB 和最大安全修订号边界通过；反转上限判断使测试失败，恢复后完整后端 124 单元 + 56 IT 全绿。

### EB17 · 保存状态与未知结果重试（M）

- [x] 完成 EB17。依赖：EB16。
- 文件：`F/api/client.ts`、`F/api/client.spec.ts`、`F/runtime/bridge.spec.ts`、`F/App.vue`、`F/App.spec.ts`。
- 验收：① 待办可保存并刷新恢复，UI 明确 saving/saved/unknown/conflict/offline；② 超时/5xx/坏 2xx 不视为事务失败，只用相同 key/body 重试原意图；③ 冲突/版本变化不覆盖已存状态，不把重新加载称为失败请求已经取消。
- 验证：V-F、V-B、V-D；注入响应丢失/坏响应/慢请求，并观察真实待办保存和刷新；端到端故障验证由 EB22 固化。
- 实施拆分：EB17a（最多 5 文件）通用响应边界/错误码、状态客户端及测试；EB17b（最多 5 文件）桥状态/测试、RuntimeFrame/测试、App 重载提示；EB17c（最多 5 文件）真实待办测试应用、外壳浏览器/文档浏览器回归及必要组件断言。未知结果由 SDK 保留原意图，应用内显式重试；宿主不偷偷改键、重放或重建访客。
- 验收结果：独立前端 68 单元、类型/lint/build 通过；外壳 Chrome 14 项验证真实保存/刷新/访客隔离、同访客双标签冲突，以及实际提交后丢响应/坏 2xx/超时的同键同体重放。重载提示不承诺撤销已提交写入；复核发现并修复响应校验读取可变修订号的问题，新增反例先失败后通过。待办仍只在显式测试 classpath，不成为正式入口的默认应用。

### C-EB07 · 独立数据主流程

- [x] EB15–EB17 从真实浏览器保存至专用 PostgreSQL、刷新恢复，两个访客隔离；审阅并发与未知结果测试，报告尚缺的新目录构建/重启证据。
- [x] 本轮交付 C-EB07：180 项模板后端、68 项独立前端、14 项外壳/2 项文档浏览器通过；原组装 46、原 SDK/桥 9、脚本 20 项回归通过。当前只本地提交，未合并/推送/部署；完整工程导出、全新目录构建及真正进程重启验收仍未完成，下一步 EB18–EB19。

## E · 全新目录完整构建

### EB18 · 不跳检查的单入口构建（M）

- [ ] 完成 EB18。依赖：EB17。
- 实施拆分（每片不超过 5 个主文件）：EB18a 受控构建输入摘要（`build-inputs.mjs` 及测试）；EB18b 最终资源指纹（打包器/测试、RuntimeResources/测试、TestApplication）；EB18c 固定命令与子进程生命周期（`process.mjs` 及测试）；EB18d 构建编排（build/测试、POM、打包后校验 IT）；EB18e 同源静态页面入口（安全配置、静态路由及 HTTP 测试）。源码输入摘要不是 JAR 二进制签名；实际前端字节在启动时重新校验。新目录完整组装证据仍归 EB21。
- 文件：`T/scripts/build.mjs`、`T/scripts/build.test.mjs`、`T/scripts/package-application.mjs`、`T/scripts/package-application.test.mjs`、`T/backend/pom.xml`。
- 验收：① 固定 argv 依次执行冻结安装/type/lint/unit/build、资源打包及 Maven clean verify；② frontend dist/应用/运行资源进入 JAR，build 前后 runtime hash 一致，缺资源失败；③ 缺专用测试配置、任一步失败/取消均非零退出，不读运行凭据、不静默跳集成测试。
- 验证：V-S；用受控子进程验证固定执行顺序/argv、信号与失败退出，缺源码/测试配置时必须拒绝。白名单装配尚未完成，本任务不宣称实际导出 JAR 已构建；完整 build.mjs 与 JAR 资源检查是 EB21 的强制验收。

### EB19 · 显式启动与首次运行说明（M）

- [ ] 完成 EB19。依赖：EB18。
- 实施拆分：EB19a start/测试复用 EB18c 的退出与信号契约；EB19b 模板 README、配置示例和 Java 启动拒绝测试。仅本地构建/启动契约，不改变平台鉴权或公网策略。
- 文件：`T/scripts/start.mjs`、`T/scripts/start.test.mjs`、`T/README.md`、`T/config/application.properties.example`、`BT/StartupConfigTest.java`。
- 验收：① 只启动已有 JAR、配置必须显式给定，保留退出码/信号，不后台遗留进程；② Node 启动与直接 Java 启动都受 Java 配置/安装身份护栏保护；③ 模板 README 从数据库/测试库准备写到修改源码/构建/启动，明确初始数据与访客限制。
- 验证：V-S、V-B；用受控子进程验证信号/退出、缺配置/非法身份与不下载依赖；Java 校验由后端真实测试覆盖。EB21 必须再用真实产物分别通过 Node 与 `java -jar` 启停，不能把本任务的脚本测试当成 JAR 实测。

### C-EB08 · 自运行构建契约

- [ ] EB18–EB19 构建/启动脚本的命令、错误和配置契约通过定向测试；评审待 EB21 实测的清单。此时不宣称已有完整独立 JAR，不启动服务器部署。

### EB20 · 模板白名单进入平台产物（M）

- [ ] 完成 EB20。依赖：EB02、EB19。
- 文件：`templates/standalone-v1.files`、`P/TemplateCatalog.java`、`PT/TemplateCatalogTest.java`、`backend/pom.xml`、`T/THIRD-PARTY-NOTICES.md`。
- 验收：① 逐文件审核白名单，仅模板源码/锁文件/必需测试/许可证，无平台夹具或凭据；② 平台正式 JAR 包含白名单模板资源，非仓库 cwd 下仍可装载组装；③ 实测文件数与字节符合硬预算，任一缺项/重复/符号链接拒绝，不在超限时自动加额。
- 验证：平台 `backend/` 执行 `./mvnw -B verify`；`jar tf target/atoms-demo-0.1.0-SNAPSHOT.jar` 检查资源，扫描 canary 排除项，记录实际大小与许可证。

### EB21 · 从真实组装输出建立验收环境（M）

- [ ] 完成 EB21。依赖：EB20。
- 文件：`PT/BundleFixture.java`、`scripts/verify-standalone.mjs`、`scripts/verify-standalone.test.mjs`、`frontend/playwright.standalone.config.ts`、`frontend/e2e-standalone/fixtures.ts`。
- 验收：① 测试夹具通过真实组装器输出，脚本只在新目录物化并验证所有 hash，不引入下载 API；② 新目录内真实安装/检查/构建并检查 JAR 资源，分别用 Node 与直接 Java 启停，JAR 无测试后门；③ 浏览器环境控制只在仓库侧，记录本次进程/路径，端口冲突失败，不复用或停止平台进程。
- 验证：仓库根 `node --test scripts/verify-standalone.test.mjs`；V-X，再由 V-E 使用其明确产物；测试导出文件 manifest 没有被浏览器夹具修改。

### C-EB09 · 真正的工程文件可构建

- [ ] EB20–EB21 用全新目录而非模板开发目录完成真实构建；检查所有必需测试随包且通过，正式包无浏览器验收后门，配额实测可追溯。

## F · 独立性、重启与交付证据

### EB22 · 脱离平台后的主流程（M）

- [ ] 完成 EB22。依赖：EB21。
- 文件：`frontend/e2e-standalone/state.spec.ts`、`frontend/e2e-standalone/restart.spec.ts`、`frontend/e2e-standalone/retry.spec.ts`、`frontend/e2e-standalone/fixtures.ts`。
- 验收：① 阻断平台所有 origin 后待办添加/保存/刷新仍成功，数据只写独立库；② 只重启本次独立进程，同一有效 Cookie 恢复，第二访客读不到首个访客数据；③ 丢失已提交响应可同键确认，DB 不可用/双标签冲突不产生假成功或覆盖。
- 验证：V-X、V-E；追踪网络、数据库 revision 与幂等记录，保留失败 trace 且不泄露 Cookie/配置。

### EB23 · 跨实例与浏览器安全（M）

- [ ] 完成 EB23。依赖：EB22。
- 文件：`frontend/e2e-standalone/isolation.spec.ts`、`frontend/e2e-standalone/security.spec.ts`、`frontend/e2e-standalone/accessibility.spec.ts`、`frontend/e2e-standalone/fixtures.ts`、`BT/DatabaseInstallationIT.java`。
- 验收：① A/B 同主机不同端口用独立 Cookie/库不串身份或数据，错误安装 ID 被拒绝；② sandbox/net/parent DOM/导航边界经浏览器验证，源码修改重建产生新 runtime ID 而数据不重置；③ 320/390/768/1440px、键盘与 axe 通过，错误状态可理解可操作。
- 验证：V-B、V-X、V-E；旧页面写新构建应冲突，但旧已成功幂等请求仍能确认；不通过丢弃数据“修复”版本冲突。

### C-EB10 · 独立运行验收

- [ ] EB22–EB23 所有主流程、安全与可访问性通过；汇报实际截图/命令/失败修复证据；未通过项目保持未勾选。

### EB24 · 固化工程格式与必要说明（M）

- [ ] 完成 EB24。依赖：EB23。
- 文件：`PT/TemplateCatalogTest.java`、`PT/EngineeringBundleTest.java`、`T/README.md`、`templates/standalone-v1.files`、`docs/engineering-bundle-verification.md`。
- 验收：① 真实模板文件数、最大文件、总量与预算余量被记录并由测试锁定；② README 中配置/构建/启动命令在新目录实测，明确不含真实业务数据/账号恢复/任意后端生成；③ manifest/模板版本/许可证和白名单一致，文档变更后重新构建校验，不复用过期 hash。
- 验证：V-P、V-X、V-E；检查源码包没有绝对本机路径、密钥或配置值；暂不修改原平台交付文档。

### EB25 · 回归与模块交接评审（S）

- [ ] 完成 EB25。依赖：EB24。
- 文件：`docs/engineering-bundle-verification.md`、`CAPABILITY-MAP.md`。
- 验收：① V-R 全部通过，现有平台行为/测试未被削弱，无真实模型调用；② 规格每项有证据或明确缺口，所有 Required/Critical 发现处理完成，关键新判断做一次反向变异验证；③ 汇报“基础工程模块完成”与下载/托管运行仍未实现的差别，提供后续模块稳定契约，不提前更新线上状态。
- 验证：V-R 与最终 V-B/V-F/V-S/V-X/V-E；记录用例数、失败、退出码与待审状态。变异仅限本次代码，恢复后重跑，不覆盖用户改动。

### C-EB11 · 人工模块验收

- [ ] 审阅规格对照、工程样例、独立运行证据与原平台回归；确认后才推进 `project-export` 的规格。合并/推送/部署与原交付文档最终同步不包含在本检查点的自动操作中。
