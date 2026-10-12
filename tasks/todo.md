# 独立工程导出与运行 · 开发任务

当前续作：2026-10-13，用户以“一次性实施”批准 [WB01–WB21](#workspace-build-执行任务)，正在连续实施；完成状态以下方勾选与验证记录为准。下方 EB/PE 历史记录和未完成的 C-EB11 保持原状。

状态：2026-10-13，EB01–EB25、C-EB10 与新增 PE01–PE05 已实现并完成本地验证；工作台可以下载当前/历史版本工程。真实下载包的新目录构建和完整独立 Chrome 23 项通过，无重试；原平台全回归通过。C-EB11 用户人工验收仍未完成。仅本地功能分支提交，未合并、推送或部署。下文早期检查点保留当时状态，以此处和最后的 PE 记录为最新状态。
依据：[计划及验证命令](plan.md)、[已批准工程规格](../SPEC-engineering-bundle.md)。本清单记录独立工程方向的 EB/PE/WB 模块；原平台任务清单不变。

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

- [x] 完成 EB18。依赖：EB17。
- 实施拆分（每片不超过 5 个主文件）：EB18a 受控构建输入摘要（`build-inputs.mjs` 及测试）；EB18b 最终资源指纹（打包器/测试、RuntimeResources/测试、TestApplication）；EB18c 固定命令与子进程生命周期（`process.mjs` 及测试）；EB18d 构建编排（build/测试、POM、打包后校验 IT）；EB18e 同源静态页面入口（安全配置、静态路由及 HTTP 测试）。源码输入摘要不是 JAR 二进制签名；实际前端字节在启动时重新校验。新目录完整组装证据仍归 EB21。
- 文件：`T/scripts/build.mjs`、`T/scripts/build.test.mjs`、`T/scripts/package-application.mjs`、`T/scripts/package-application.test.mjs`、`T/backend/pom.xml`。
- 验收：① 固定 argv 依次执行冻结安装/type/lint/unit/build、资源打包及 Maven clean verify；② frontend dist/应用/运行资源进入 JAR，build 前后 runtime hash 一致，缺资源失败；③ 缺专用测试配置、任一步失败/取消均非零退出，不读运行凭据、不静默跳集成测试。
- 验证：V-S；用受控子进程验证固定执行顺序/argv、信号与失败退出，缺源码/测试配置时必须拒绝。白名单装配尚未完成，本任务不宣称实际导出 JAR 已构建；完整 build.mjs 与 JAR 资源检查是 EB21 的强制验收。
- 结果：固定顺序/失败停止/环境隔离、v2 前端字节与受控编译输入指纹、Maven profile 和实际 JAR 检查完成。129 单元 + 59 真实 PostgreSQL/HTTP/打包 IT 通过；当前实际包来自明确标注的资源探针，不是 EB20 白名单组装的导出工程。

### EB19 · 显式启动与首次运行说明（M）

- [x] 完成 EB19。依赖：EB18。
- 实施拆分：EB19a start/测试复用 EB18c 的退出与信号契约，并统一 build/package 的入口路径解析（macOS `/var` 别名不能导致静默不执行）；EB19b 模板 README、配置示例和 Java 启动拒绝测试。仅本地构建/启动契约，不改变平台鉴权或公网策略。
- 文件：`T/scripts/start.mjs`、`T/scripts/start.test.mjs`、`T/README.md`、`T/config/application.properties.example`、`BT/StartupConfigTest.java`。
- 验收：① 只启动已有 JAR、配置必须显式给定，保留退出码/信号，不后台遗留进程；② Node 启动与直接 Java 启动都受 Java 配置/安装身份护栏保护；③ 模板 README 从数据库/测试库准备写到修改源码/构建/启动，明确初始数据与访客限制。
- 验证：V-S、V-B；用受控子进程验证信号/退出、缺配置/非法身份与不下载依赖；Java 校验由后端真实测试覆盖。EB21 必须再用真实产物分别通过 Node 与 `java -jar` 启停，不能把本任务的脚本测试当成 JAR 实测。
- 结果：显式 start、退出码/信号、macOS 路径别名、直接 Java 参数拒绝和模板首次运行说明完成。资源探针 JAR 通过 Node 入口启动，四尺寸 Chrome 同源页面/真实会话/初始状态读取、键盘与 axe 通过；自有进程已退出。直接 Java 的最终导出物启动仍归 EB21。

### C-EB08 · 自运行构建契约

- [x] EB18–EB19 构建/启动脚本的命令、错误和配置契约通过定向测试；35 项脚本回归通过，指纹比较条件实际变异被测试捕获并恢复。已列出 EB21 待验清单，不把探针包冒充完整独立工程；未推送、合并或部署。

### EB20 · 模板白名单进入平台产物（M）

- [x] 完成 EB20。依赖：EB02、EB19。
- 实施拆分：EB20a 模板许可证（notice + Apache license/notice）；EB20b 显式清单、Classpath Catalog 及单元测试、Maven 资源接线（4 文件）；EB20c 仓库源文件/正式 JAR 一致性与非仓库工作目录验收（测试夹具与打包 IT，2 文件）。构建仍使用既有 Maven 插件，不新增依赖。
- 文件：`templates/standalone-v1.files`、`P/TemplateCatalog.java`、`PT/TemplateCatalogTest.java`、`backend/pom.xml`、`T/THIRD-PARTY-NOTICES.md`。
- 验收：① 逐文件审核白名单，仅模板源码/锁文件/必需测试/许可证，无平台夹具或凭据；② 平台正式 JAR 包含白名单模板资源，非仓库 cwd 下仍可装载组装；③ 实测文件数与字节符合硬预算，任一缺项/重复/符号链接拒绝，不在超限时自动加额。
- 验证：平台 `backend/` 执行 `./mvnw -B verify`；`jar tf target/atoms-demo-0.1.0-SNAPSHOT.jar` 检查资源，扫描 canary 排除项，记录实际大小与许可证。
- 结果：106 项显式模板清单与 POM/源文件/正式 JAR 逐字节一致；catalog 拒绝缺项、重复、路径/符号链接与配额问题。平台 77 单元 + 52 集成测试通过；测试入口在仓库外用真实 Boot JAR 完成组装，不启动平台。修复资源复制后 JAR 默认丢弃 `.gitignore` 的问题，保留 Maven Wrapper 完整许可证与上游 notice。

### EB21 · 从真实组装输出建立验收环境（M）

- [x] 完成 EB21。依赖：EB20。
- 实施拆分：EB21a 测试态组装输出与新目录 hash 验证（BundleFixture、物化脚本及测试）；EB21b 真实构建/自有进程启停编排及测试；EB21c 仓库侧 Playwright 环境与浏览器烟测。不修改或重用平台服务/数据库，不把浏览器控制端点打进独立 JAR。
- 文件：`PT/BundleFixture.java`、`scripts/verify-standalone.mjs`、`scripts/verify-standalone.test.mjs`、`frontend/playwright.standalone.config.ts`、`frontend/e2e-standalone/fixtures.ts`。
- 验收：① 测试夹具通过真实组装器输出，脚本只在新目录物化并验证所有 hash，不引入下载 API；② 新目录内真实安装/检查/构建并检查 JAR 资源，分别用 Node 与直接 Java 启停，JAR 无测试后门；③ 浏览器环境控制只在仓库侧，记录本次进程/路径，端口冲突失败，不复用或停止平台进程。
- 验证：仓库根 `node --test scripts/verify-standalone.test.mjs`；V-X，再由 V-E 使用其明确产物；测试导出文件 manifest 没有被浏览器夹具修改。
- 结果：真实组装输出 111 文件 / 542,975 字节 / 最大文件 121,072 字节。新目录冻结安装、24 脚本测试、68 前端测试、129 后端单元 + 59 集成测试及实际 JAR 检查通过；Node 和直接 Java 启停、四尺寸 Chrome 同源/真实状态读取/沙箱/axe 通过。仓库侧验证脚本 21 项回归通过（17 项普通脚本 + 4 项自有端口测试）；指纹比较实际变异被捕获并恢复。所有源文件在构建及浏览器验收后重新验 hash，未改变；18089 已释放。

### C-EB09 · 真正的工程文件可构建

- [x] EB20–EB21 用全新目录而非模板开发目录完成真实构建；检查所有必需测试随包且通过，正式包无浏览器验收后门，配额实测可追溯。
- 边界：尚无用户下载 API/按钮；当前是源码组装与独立构建/启动能力。最终成品上的待办保存/重启恢复/多实例隔离及升级重放归 EB22–EB23，配额回归、手工首次建库教程和原平台完整回归归 EB24–EB25。证据见 [验证记录](../docs/engineering-bundle-verification.md)。

## F · 独立性、重启与交付证据

### EB22 · 脱离平台后的主流程（M）

- [x] 完成 EB22。依赖：EB21。
- 实施拆分：EB22a（5 文件）仓库侧可重启进程夹具：`scripts/serve-standalone.mjs`、对应 Node 测试及 `.d.mts` 契约、`fixtures.ts`、Playwright 配置；EB22b（4 文件）真实产物保存/刷新、重启、响应丢失/双标签验收与共享页面动作；EB22c（最多 5 文件）独立进程专属数据库连接故障及只读数据库证据。所有进程只通过自有句柄结束，不关闭共享 PostgreSQL。
- 夹具设计：从 `webServer` 改为 worker 生命周期管理，保留等价的「端口占用即失败、不复用服务」约束，重启前等待自有进程退出并释放端口；JAR 不增加控制接口。每次启动重验实际 JAR hash 与显式配置。浏览器阻断一切非独立应用 origin；关闭原始 trace，改存不含请求头/正文的状态码诊断及截图，避免 Cookie/CSRF 泄漏。
- [x] EB22a：可重启 worker 与显式 JAR/配置检查接线；缺少生命周期导出的 RED 后 Node 测试、类型检查、lint 及四尺寸真实 JAR 浏览器烟测通过。发现 Playwright `serviceWorkers: block` 初始化脚本在 opaque iframe 访问 getter 产生 SecurityError；取消该测试脚本注入，保留应用真实 CSP 和零页面错误断言，另断言新上下文中没有 service worker。正常同源响应不经测试代理改写。
- [x] EB22b：6 项真实工程包浏览器用例通过：保存/勾选/刷新、真实 Java 进程重启后同访客恢复与第二访客隔离、已提交响应丢失/畸形/超时后同键确认、双标签冲突及显式读取最新数据。丢失响应场景在重试前另重启进程，仍只留下 revision=1；没有调用平台 API。测试 TypeScript、lint、格式检查通过。数据库故障与 SQL 层证据仍待 EB22c，不提前勾选 EB22。
- EB22c 再拆：c1（3 文件）仓库侧专属 TCP 连接闸门、私有临时配置和受限角色只读证据查询（脚本/测试/类型）；c2（最多 4 文件）worker 接线、真实数据库断连浏览器用例、重试/重启的 SQL 证据。只支持已准备的 `atoms_standalone_a/b` 和本地容器；临时配置新建为 0600，不改变原配置/源文件；故障只销毁本次闸门的 socket，不执行数据库停机、权限变更或数据清理。证据查询以应用角色执行固定只读事务，只返回修订号/行数/安装身份匹配布尔值。
- [x] EB22c1：3 项连接闸门/配置拒绝测试通过；实际受限角色只读查询可连接。库/角色匹配条件反向变异被正反例捕获，恢复后全绿；补充反例意外成功时也释放测试自有句柄，避免变异验收遗留进程。临时私有配置在闸门结束时移除，原配置逐字节不变。
- EB22d 新发现修复（2 文件）：真实断连虽正确返回 503、保留数据并可重试，但 `TransactionTemplate` 在业务异常叠加 rollback 异常时先输出原始 SQL/堆栈；先在 `BT/SessionIT.java` 补 HTTP/日志反例，再收紧 `R/application.yml` 的该框架日志，保留应用自身 requestId/code/status 事件。修复改变模板字节，必须重新组装、新目录构建并重跑最终浏览器，不能沿用旧 JAR/hash。
- EB22e 夹具修正（1 文件）：真实重启导航时 CDP `Network.getResponseBody` 偶发失效，页面已有恢复的数据；会话观察改为一次真实 `route.fetch` 后原样转发响应（无重试/伪造成功/额外初始化），避免依赖调试协议旧响应缓存，仍验证真实 Cookie 与独立库状态。测试 `retries: 0` 不变。
- [x] EB22c2/d/e：新反例证明原始 SQL/异常 canary 会被框架日志输出，修复后保留关联 503 且不含堆栈；新目录真实构建 24 脚本、68 前端、129 后端单元 + 60 IT 全绿；新 JAR 的 11 项 Chrome 验收全部通过（54.9 秒、无重试）。数据库断连前后 revision/状态行/幂等行保持 1/1/1，恢复并显式重试后 2/1/2；丢失响应跨进程重试仍 1/1/1。最后运行保留 503/409 结构化事件、原始堆栈 0。原平台后端 77 单元 + 52 IT、仓库 24 项 Node 回归、测试 TS/lint/格式检查通过。
- 产物：`.local/standalone/verification-4oZItT/engineering-bundle-HOJltI`，111 文件 / 545,934 字节，报告在同级 `verification.json`；浏览器后重验源文件与 JAR hash 未变、自有服务端口已释放。没有重置运行库/停止共享数据库。具体 ID、截图、复验方式见 [验证记录](../docs/engineering-bundle-verification.md#eb22--脱离平台后的持久化故障恢复与真实重启)。EB23/C-EB10 保持未完成；本轮没有实现下载 API/按钮。
- 文件：`frontend/e2e-standalone/state.spec.ts`、`frontend/e2e-standalone/restart.spec.ts`、`frontend/e2e-standalone/retry.spec.ts`、`frontend/e2e-standalone/fixtures.ts`。
- 验收：① 阻断平台所有 origin 后待办添加/保存/刷新仍成功，数据只写独立库；② 只重启本次独立进程，同一有效 Cookie 恢复，第二访客读不到首个访客数据；③ 丢失已提交响应可同键确认，DB 不可用/双标签冲突不产生假成功或覆盖。
- 验证：V-X、V-E；追踪网络、数据库 revision 与幂等记录，保留失败 trace 且不泄露 Cookie/配置。

### EB23 · 跨实例与浏览器安全（M）

- [x] 完成 EB23。依赖：EB22。
- 文件：`frontend/e2e-standalone/isolation.spec.ts`、`frontend/e2e-standalone/security.spec.ts`、`frontend/e2e-standalone/accessibility.spec.ts`、`frontend/e2e-standalone/fixtures.ts`、`BT/DatabaseInstallationIT.java`。
- 验收：① A/B 同主机不同端口用独立 Cookie/库不串身份或数据，错误安装 ID 被拒绝；② sandbox/net/parent DOM/导航边界经浏览器验证，源码修改重建产生新 runtime ID 而数据不重置；③ 320/390/768/1440px、键盘与 axe 通过，错误状态可理解可操作。
- 验证：V-B、V-X、V-E；旧页面写新构建应冲突，但旧已成功幂等请求仍能确认；不通过丢弃数据“修复”版本冲突。

实施切片（先拆分验收所需生命周期支持，避免一次修改超过 5 个主要文件）：

- [x] EB23a：同一已验证 JAR 可显式选择第二个回环监听地址；默认仍要求报告地址一致，拒绝占用端口且不复用进程。文件：`scripts/serve-standalone.mjs`、对应 `.d.mts` / `.test.mjs`。新增用例先红后绿；反转地址匹配判断，2 项均失败；恢复后仓库常规 Node 18 项通过。无 JAR/模板字节变更。
- [x] EB23b：同一 Chrome context 双端口分别创建访客/保存/交替刷新，两个数据库只含各自访客的记录；停止 B 不影响 A，重启 B 保留原身份和数据。文件：`frontend/e2e-standalone/isolation.spec.ts`、`fixtures.ts`、`restart.spec.ts`、`retry.spec.ts`。网络放行判断反向变异被真实浏览器捕获；恢复后 12 项完整 Chrome 回归通过（3.5 分钟，retries=0），类型/lint/格式通过。完整回归中真实重启用例曾耗尽默认 30 秒总预算；仅这两个用例的总预算对齐已有 JVM 120 秒启动边界 + 30 秒浏览器阶段，应用/断言超时、无重试和数据断言不变。
- [x] EB23c：错误安装 ID 的最终 JAR 启动拒绝，原安装和数据不变。以私有临时配置测试，不改原运行配置。文件：`scripts/standalone-database.mjs`、对应 `.d.mts` / `.test.mjs`、`frontend/e2e-standalone/installation.spec.ts`。实际记录 `INSTALLATION_MISMATCH`，恢复正确配置后同一访客/数据保留且继续写入成功；完整 Chrome 13 项通过（3.6 分钟、无重试），配置注释边界修正后定向复验通过（1.0 分钟）。Node 数据库工具 4 项通过，原 ID 不变变异及注释同名值反例先失败再修正；111 源文件与 JAR hash 未变。详情见[验证记录](../docs/engineering-bundle-verification.md#eb23--同机双实例与安装误配部分验收)。
- [x] EB23d：真实源码变体重新构建，旧页面新写入冲突、旧成功请求仍能确认，原数据不被初始化覆盖。组装/构建夹具与浏览器用例分别提交。
  - [x] EB23d1：固定 `upgraded` 夹具仅改变待办 HTML 标题；来源版本 UUID、初始数据和全部模板字节不变。文件：`PT/BundleFixture.java`、`PT/BundleFixtureTest.java`、`scripts/verify-standalone.mjs`、对应 `.test.mjs`。新增测试先红后绿，CLI 条件反转被捕获；平台 80 单元 + 52 集成、仓库 Node 19 项通过。新目录 `verification-rKkiBw` 完整构建：脚本 24、前端 68、后端 129 单元 + 60 集成通过；Node/Java 均从工程外启动并停止成功。实际新 runtime ID，原工程/JAR 字节未改。见[验证记录](../docs/engineering-bundle-verification.md#eb23d--真实源码升级验收)。
  - [x] EB23d2：真实升级替换由测试持有的 JAR 进程；相同配置/数据库保留会话和数据，旧页面新意图返回 `STALE_APP_VERSION`，旧成功意图重试不覆盖升级后新数据。文件：`frontend/e2e-standalone/fixtures.ts`、`upgrade.spec.ts`。切换前验证报告和 JAR，结束/回调失败恢复原测试产物，不清库。修正测试错误响应取值及异步中断清理竞态；反向变异捕获、故障探针无配置提前移除，最终完整 Chrome **15 项通过（3.0 分钟，retries=0）**，严格类型/lint/格式通过。截图已实际查看，SQL 证明重放后 revision=2 / stateRows=1 / idempotencyRows=2；原版/升级版源文件与 JAR hash 均未改，自有端口和临时配置已释放。见[验证记录](../docs/engineering-bundle-verification.md#eb23d--真实源码升级验收)。
- [x] EB23e：最终成品浏览器 sandbox/网络/宿主 DOM/导航、键盘与错误状态 axe 验收。
  - [x] EB23e1：在已验证 JAR 的真实 iframe 中执行仓库固定探针，验证 opaque origin、宿主 DOM/导航、联网/脚本/worker/弹窗拒绝，以及自身导航后的连接关闭与手动恢复；直接文档仍受 HTTP sandbox 约束。文件：`frontend/e2e-standalone/sandbox-probe.js`、`security.spec.ts`。不读凭据；请求目标仅本机专用探针路径，另设拦截护栏，不改实际 JAR/CSP。弹窗判断反向变异被捕获，恢复后 Chrome 2 项通过（27.8 秒、无重试），类型/lint/格式通过；导航关闭截图已查看。完整回归和错误态可访问性仍归 EB23e2。
  - [x] EB23e2：真实冲突、未知保存结果和数据库不可用启动状态下，键盘焦点/按钮恢复、宿主与 iframe 的 axe、移动端布局通过。文件：`frontend/e2e-standalone/accessibility.spec.ts`、`accessibility.ts`。新增 320/1440px 六项用例，iframe 标签/恢复按钮有明确扫描证据；唯一 nonBmp 装饰符号 incomplete 逐项复核并锁定颜色/文字，不禁用规则或排除节点。实际 Tab/Shift+Tab/Enter 恢复与 SQL 断言通过，反转 iframe 覆盖判断被捕获。最终完整 Chrome **23 项通过（3.9 分钟，retries=0）**，严格类型/lint/格式通过，六张错误态截图已查看。未改模板/JAR/依赖；两份工程的 hash 未变，自有端口与临时配置释放。见[验证记录](../docs/engineering-bundle-verification.md#eb23e2--错误态键盘恢复与可访问性验收)。

### C-EB10 · 独立运行验收

- [x] EB22–EB23 所有主流程、安全与可访问性通过；实际截图/命令/失败修复及人工复核边界已记录。此检查点仅为独立运行验收，不等于下载入口或工程基础模块最终交付；EB24–EB25/C-EB11 保持未完成。

### EB24 · 固化工程格式与必要说明（M）

- [x] 完成 EB24。依赖：EB23。106 个固定模板文件、111 个工程文件，最大项 121072 / 总量 546106 / ZIP 564274 bytes；预算回归锁定。实际下载包按 README chmod、完整构建及两个入口启动通过，manifest/hash/许可证核对通过。手工首次建库说明按现有数据库权限契约复核，本轮复用已准备的专用库，不重新创建或重置角色。
- 文件：`PT/TemplateCatalogTest.java`、`PT/EngineeringBundleTest.java`、`T/README.md`、`templates/standalone-v1.files`、`docs/engineering-bundle-verification.md`。
- 验收：① 真实模板文件数、最大文件、总量与预算余量被记录并由测试锁定；② README 中配置/构建/启动命令在新目录实测，明确不含真实业务数据/账号恢复/任意后端生成；③ manifest/模板版本/许可证和白名单一致，文档变更后重新构建校验，不复用过期 hash。
- 验证：V-P、V-X、V-E；检查源码包没有绝对本机路径、密钥或配置值；暂不修改原平台交付文档。

### EB25 · 回归与模块交接评审（S）

- [x] 完成 EB25。依赖：EB24。平台前端 132、后端 86+54、普通/运行时浏览器 14/20、下载工程完整浏览器 23 项通过；关键超限判断变异被捕获，独立代码审阅的取消竞态已修复。已更新原说明的本地完成状态，云端记录不变；C-EB11 人工评审另行保留。
- 文件：`docs/engineering-bundle-verification.md`、`CAPABILITY-MAP.md`。
- 验收：① V-R 全部通过，现有平台行为/测试未被削弱，无真实模型调用；② 规格每项有证据或明确缺口，所有 Required/Critical 发现处理完成，关键新判断做一次反向变异验证；③ 汇报基础工程与新增下载模块的真实完成范围，托管运行仍未实现，提供后续模块稳定契约，不提前更新线上状态。
- 验证：V-R 与最终 V-B/V-F/V-S/V-X/V-E；记录用例数、失败、退出码与待审状态。变异仅限本次代码，恢复后重跑，不覆盖用户改动。

### project-export · 用户确认一次完成（2026-10-12）

- [x] PE01：确定性 ZIP、路径/体积边界、解压说明与预算。提交 `3e01684`。
- [x] PE02：真实授权下载接口、当前/历史版本、失败/并发 HTTP 验证。提交 `6d41818`。
- [x] PE03：有界下载客户端、工作台按钮、错误/取消/资源释放。提交 `004507a`、`722de23`；审阅发现的取消后旧错误影响会话由 `0e64743` 修复并回归。
- [x] PE04：真实浏览器下载及 ZIP 解压构建、独立持久化验证。提交 `a0946b7`、`5e7c9c1`；保留真实网页 ZIP、源码和 JAR 摘要，完整独立浏览器 23 项通过。
- [x] PE05：完整回归、变异与代码审阅、文档交付。证据见 [PE01–PE05 验收记录](../docs/engineering-bundle-verification.md)，使用方式见 [工程下载说明](../docs/project-export.md)。没有新依赖、付费模型调用或平台迁移，线上未更新。

### C-EB11 · 人工模块验收

- [ ] 审阅规格对照、工程样例、独立运行证据与原平台回归。用户于 2026-10-12 另行授权 `project-export` 一次完成，不再以此项阻塞下载开发；人工评审本身仍未完成。合并/推送/部署不包含在自动操作中。

## workspace-build 执行任务

状态：规格与细化任务均已批准，开发中，尚未执行 V011。依据：[扩展计划](plan.md#7-workspace-build-扩展计划)、[规格](../SPEC-workspace-build.md)。用户要求一次性实施，不要求逐任务批准；检查点用于提供证据及处理失败，不代表自动授权扩展范围。

新增缩写：`W/` = `backend/src/main/java/dev/atoms/demo/workspace/`；`WT/` = 对应 `backend/src/test/java/dev/atoms/demo/workspace/`；`WR/` = `backend/src/main/resources/workspace/`；`PF/` = `frontend/src/`。原 P/PT/T 缩写不变。验证命令 W-U/W-I/W-P/W-F/W-X/W-E/W-R 见计划 §7.4；新增类名和脚本名是拟定落点。每项至多五个主要文件，若实际超出先拆子任务，不省测试。所有完成框保持空白直到存在本轮证据。

### WB01 · 统一授权快照入口（M）

- [x] 完成 WB01。依赖：用户批准扩展计划。共享授权/组装入口与内容摘要已实现；新增测试先编译失败再通过，定向 51 项与全单元/原下载 HTTP 集成回归通过。首次 Mockito attach 被沙箱阻止，经授权在沙箱外复验成功，未修改测试机制。证据见 [构建验证记录](../docs/workspace-build-verification.md)。
- 描述：让构建与原工程下载复用授权版本读取和模板内容摘要，避免分别组装。
- 文件：`P/EngineeringSnapshotService.java`、`P/EngineeringExportService.java`、`P/TemplateSnapshot.java`、`PT/EngineeringSnapshotServiceTest.java`、`PT/EngineeringExportIT.java`。
- 验收：① 同一来源与模板字节得到稳定标识，模板改一字节即变；② 越权在组装前失败，客户端不能选择模板；③ 原下载归档、错误与并发语义不变。
- 验证：W-U `EngineeringSnapshotServiceTest`，W-I `EngineeringExportIT`；先写摘要漂移与越权失败测试。

### WB02 · 私有目录物化（M）

- [x] 完成 WB02。依赖：WB01。新目录物化、私有权限、根目录身份/符号链接校验和 CREATE_NEW 已实现；新增测试先失败，路径/物化/原 ZIP 定向 6 项通过。只新建任务目录，无清理操作。
- 描述：将可信工程文件在服务端派生的新任务目录中物化，不接收浏览器路径。
- 文件：`W/WorkspacePaths.java`、`W/WorkspaceStore.java`、`WT/WorkspacePathsTest.java`、`WT/WorkspaceStoreTest.java`。
- 验收：① 拒绝系统/home/仓库根、符号链接祖先、非私有目录和已存在任务目标；② UUID 派生路径与固定文件白名单生效，目录/敏感文件权限正确；③ 失败不覆盖已有文件或递归删除未知内容。
- 验证：W-U `WorkspacePathsTest,WorkspaceStoreTest`；临时目录检查穿越、祖先替换、同 ID 并发和外部 canary 文件未变。

### WB03 · 冻结 ZIP 与源文件复验（M）

- [x] 完成 WB03。依赖：WB02。新增归档与源文件复验、有界 no-follow 读取；篡改、链接、硬链接和超限拒绝测试先失败后通过。完整平台后端 93 单元 + 54 集成通过。
- 描述：保存一次生成的源 ZIP，支持按已知摘要读取与构建前后核验。
- 文件：`W/WorkspaceStore.java`、`W/BuildArtifacts.java`、`WT/WorkspaceStoreTest.java`、`WT/BuildArtifactsTest.java`。
- 验收：① 原始 ZIP 与物化源逐项对应，临时写入未完成时不能读成有效快照；② 128/2 MiB/8 MiB/9 MiB 配额及文件类型复验生效；③ 源文件篡改、归档被替换或缺失明确拒绝，不重新组装替代。
- 验证：W-U `WorkspaceStoreTest,BuildArtifactsTest`；边界超出一字节、摘要篡改和构建输出目录排除测试。

### C-WB01 · 快照边界

- [x] WB01–WB03 定向测试与平台构建通过；完整后端 93 单元 + 54 集成通过，ZIP 篡改被拒绝，源文件变化不会重新组装旧 ZIP。模板仍为 106 文件/工程 111 文件，原大小预算回归通过；尚无自动构建 UI。

### WB04 · 可信本机监督器（M）

- [x] 完成 WB04。依赖：C-WB01。可信监督模块与进程组 keeper、父连接丢失、取消/超时和有界排空已实现；8 项真实 Node 测试通过。审查发现的缓存 PID 误杀风险改为 keeper 仅向自身当前组发信号；初始化前取消的宽限竞态先失败再修正。正式 Java 接线与固定命令树审计仍由 WB05/WB13 承担。
- 描述：实现随平台发布的固定进程监督入口，先使用无副作用仓库夹具验证生命周期。
- 文件：`WR/supervisor.mjs`、`WR/protocol.mjs`、`scripts/workspace-supervisor.test.mjs`、`scripts/fixtures/workspace-child.mjs`。
- 验收：① 固定 argv、受限环境、可信控制消息与不可信输出分离；② 取消、10 分钟策略的缩短测试时钟、父连接 EOF 能清理自有子孙进程，确认退出后完成；③ 超量输出持续排空且有界，外部对照进程不被终止。
- 验证：W-P；取消宽限/超时用可注入测试时钟缩短，不改正式预算；正常及被强杀父进程均做真实进程测试。

### WB05 · Java 与监督器生命周期契约（M）

- [x] 完成 WB05。依赖：WB04。Java worker 专用桥接器通过真实 Node 进程验证，严格有界帧/执行 UUID/阶段顺序校验，取消与总超时走控制管道并等待退出，未知结果不可回收槽位。新增测试先编译失败再通过，平台全单元 96 项通过；正式 runner/持久恢复接线分别归 WB13/WB16。
- 描述：Java 持有自己启动的监督器句柄，校验消息并等待退出，不依赖旧 PID 恢复。
- 文件：`W/NativeBuildProcess.java`、`W/BuildProcessResult.java`、`WT/NativeBuildProcessTest.java`、`scripts/workspace-supervisor.test.mjs`。
- 验收：① 参数/资源入口不可由用户修改，协议帧有界且状态顺序合法；② stdout 假成功、提前退出、监督器被强杀均不产生成功或已取消假象；③ 退出未知会返回明确中断/需要核查状态，不能立即复用槽位。
- 验证：W-U `NativeBuildProcessTest`、W-P；用真实节点进程覆盖管道断开与终止竞态，测试结束核对自有进程释放。

### WB06 · 配置与工作根准入（M）

实施拆片：WB06a 工作根锁与资源准入（锁类/测试/测试进程入口，3 个文件）；WB06b 默认关闭配置与工具链校验（配置类/测试/application.yml，3 个文件）；WB06c 专用测试库只读身份校验（校验类/测试/配置类/真实库测试，4 个文件）。保持已批准范围和预算，不在一个切片混写配置、SQL 与进程锁。

- [x] WB06a：真实双 JVM 排他锁及目录/字节预算测试通过。测试捕获同 JVM 再次打开/关闭文件描述符会释放 POSIX 锁的问题；改为打开文件前先取得 JVM 内唯一持有权，保留 OS 锁负责跨进程互斥。无清理或解锁文件删除。
- [x] WB06b：默认关闭、loopback/本地 profile、显式工具路径与版本、私有配置检查完成；2 项新测试先编译失败再通过，子进程环境不带平台凭据或启动覆盖变量。
- [x] WB06c：专用配置、实际库/角色用途标记、权限、schema 和已有 installation 身份只读核验完成；非法配置单测及现有专用库真实只读探针通过，无配置改写或数据库写入。

- [x] 完成 WB06。依赖：WB03、WB05。
- 描述：增加默认关闭的本机构建配置与工具、专用测试库配置、执行根资源检查。
- 文件：`W/BuildEnvironment.java`、`W/BuildRootLease.java`、`WT/BuildEnvironmentTest.java`、`WT/BuildRootLeaseTest.java`、`backend/src/main/resources/application.yml`。
- 验收：① 非显式本地启用、工具不符、平台/运行测试配置混用均失败；② 同根 OS 锁互斥，保留目录数、磁盘预算与最低可用空间按规格检查；③ 环境不透传平台密码/模型 Key/执行覆盖变量，不改私有配置。
- 验证：W-U `BuildEnvironmentTest,BuildRootLeaseTest`，真实双进程锁测试；记录检查仅是软资源预算而非 OS 沙箱。

### C-WB02 · 执行安全前置验证

- [x] WB04–WB06 真实进程测试、工具校验与平台构建通过：101 单元、54 集成、8 进程测试。外部服务/目录未受影响；无法确认子孙退出时停止后续执行并报告，不弱化验收要求。

### WB07 · V011 构建记录（M）

- [x] 完成 WB07。依赖：C-WB02。V011 仅应用平台测试库：新增两表、项目 owner 复合唯一约束与不可变转移约束；4 项真实库测试覆盖原子事件、归属、活动唯一、来源不可改、终态与事件上限回滚。旧项目指针不变。
- 描述：新增任务和事件表，建立状态转移、归属、来源与事件序号约束。
- 文件：`backend/src/main/resources/db/migration/V011__engineering_builds.sql`、`W/EngineeringBuild.java`、`W/BuildRepository.java`、`WT/BuildRepositoryIT.java`。
- 验收：① project/version/owner 不能交叉绑定，活动任务唯一约束和终态字段一致；② 状态与事件在同事务更新，事件上限与安全整数规则可验证；③ 不修改历史迁移、项目生成指针或业务状态表。
- 验证：W-I `BuildRepositoryIT`；仅平台专用测试库执行新迁移，测试非法状态、并发活动记录、级联/外键行为及原迁移回归。

### WB08 · 幂等提交与容量控制（M）

实施拆片：WB08a 准入资源持有器（BuildExecutionEnvironment/测试，2 文件）；WB08b HTTP/事务准入（Admission/Controller/Repository/IT，4 文件）。环境与文件扫描发生在事务外；短事务只使用核验结果并冻结来源。

- [x] 完成 WB08。依赖：WB07、WB01、WB06。准入 API 的 4 项真实 HTTP/PG 测试先 404 失败后通过；同键并发返回同一 buildId、同项目冲突、8 项并发恰好接收 4 项、开关变更后旧意图仍重放。环境生命周期另有 2 项通过；一次后续容量检查失败已保留，完整回归需复验，不降低磁盘门槛。
- 描述：创建只登记意图的 202 API，事务内冻结来源/模板摘要与幂等结果。
- 文件：`W/BuildAdmissionService.java`、`W/BuildController.java`、`W/BuildRepository.java`、`WT/BuildAdmissionIT.java`。
- 验收：① 仅接收 versionId，所有者/CSRF/规范 UUID/未知字段验证完整；② 同键同体只创建一次，同键异体 422，多线程不突破全局四项/项目一项；③ 事务不启动进程或长时间写文件，已接受意图重放不受新容量/开关变化影响。
- 验证：W-I `BuildAdmissionIT`；多连接并发与提交响应丢失重放，检查数据库记录及模型网关零调用。

### WB09 · 状态与有界事件读取（M）

- [x] 完成 WB09。依赖：WB08。详情、稳定游标列表和有界事件接口完成；2 项真实 HTTP/PG 测试由缺 GET 路由失败转通过，覆盖陌生会话、混项目、字段排除和分页边界。
- 描述：提供持久化列表、详情和事件游标读取，供刷新恢复和排查使用。
- 文件：`W/BuildQueryService.java`、`W/BuildController.java`、`W/BuildRepository.java`、`WT/BuildQueryIT.java`。
- 验收：① 列表分页稳定，事件 seq 单调且限额执行，不由客户端推断终态；② 陌生访客与混用项目/buildId 均 404；③ DTO 不返回文件路径/PID/命令/raw 输出，错误含 requestId。
- 验证：W-I `BuildQueryIT`；非法/边界游标、并发新增、空列表与敏感 canary 测试。

### C-WB03 · 持久任务 API

- [ ] WB07–WB09 真实 PostgreSQL/HTTP 测试及平台构建通过；记录幂等与队列并发证据。正常配置仍关闭执行，未接通 Worker 不模拟产物。

### WB10 · 取消状态机（M）

- [x] 完成 WB10（C-WB03 完整回归仍待环境容量恢复）。取消 API 与状态机完成：4 项真实 HTTP/PG 测试通过，覆盖同键/终态重放、并发取消、取消与领取/完成竞争；CANCELLING 保留执行 fence。没有模拟成真实进程退出。
- 描述：实现幂等取消意图与数据库状态竞争规则，为 Worker 提供可信取消信号。
- 文件：`W/BuildCancellationService.java`、`W/BuildController.java`、`W/BuildRepository.java`、`WT/BuildCancellationIT.java`。
- 验收：① 排队任务取消后不会被领取，运行中变 CANCELLING，不立即释放活动约束；② 终态取消无副作用，完成/取消竞争只有一个合法结果；③ 同键重试稳定，未授权无法影响任务或查询其存在。
- 验证：W-I `BuildCancellationIT`；真实数据库并发闩锁覆盖领取/取消/完成窗口。

### WB11 · 前端构建协议与请求生命周期（M）

实施拆片：WB11a 通用 JSON 请求可取消（client 与测试，2 文件）；WB11b 构建协议与严格解码（builds 与测试，2 文件）；WB11c 构建状态管理（composable 与测试，2 文件）。取消旧请求不得让晚到 401/403 破坏新的会话状态。

- [ ] 完成 WB11。依赖：WB09、WB10。
- 描述：实现强校验构建 API 客户端和可测试的任务状态管理。
- 文件：`PF/api/builds.ts`、`PF/api/builds.spec.ts`、`PF/composables/useEngineeringBuild.ts`、`PF/composables/useEngineeringBuild.spec.ts`。
- 验收：① 严格解析状态、摘要、时间和有界事件，不默认为成功；② 创建/取消未知结果保留幂等键，明确新意图才换键；③ 切换项目/版本、卸载或会话变化后旧响应不污染当前状态。
- 验证：W-F 对应两个测试文件；假时钟检查轮询停止、断网、重放、终态与晚到响应。

### WB12 · 工作台构建操作入口（M）

- [ ] 完成 WB12。依赖：WB11。
- 描述：接入选定版本的构建按钮、真实任务状态、取消/重试及恢复交互。
- 文件：`PF/components/EngineeringBuild.vue`、`PF/components/EngineeringBuild.spec.ts`、`PF/components/ArtifactPanel.vue`、`PF/components/ArtifactPanel.spec.ts`。
- 验收：① 目标 Vn 清晰，重复点击不产生新任务，刷新可查询原记录；② 失败保留当前版本/输入，关闭或环境缺失给可理解提示；③ 成功显示“构建完成，实例预览尚未接入”，旧预览不改为新实例状态。
- 验证：W-F；组件键盘/焦点与窄宽度测试。此阶段只证明界面契约，实际按钮到 JAR 留待 WB14/WB19。

### C-WB04 · 工作台任务流程

- [ ] WB10–WB12 数据库、前端测试与构建通过；展示实际状态来源和未知结果处理，不以 fixture 结果宣称已完成真实构建。

### WB13 · 固定模板构建阶段接入（M）

- [ ] 完成 WB13。依赖：C-WB04、WB05。
- 描述：让可信监督器调用已有完整构建流程，阶段通过内部接口传递而非解析普通日志。
- 文件：`T/scripts/build.mjs`、`T/scripts/build.test.mjs`、`WR/build-runner.mjs`、`scripts/workspace-runner.test.mjs`、`templates/standalone-v1.files`。
- 验收：① npm 安装禁用生命周期脚本，type/lint/unit/build 与 Maven clean verify 全部执行；② 平台构建与下载后手工构建共享同一固定流程，无跳测分支；③ 外部测试配置仅给必要子进程，阶段来自可信入口，日志不携带秘密或源码。
- 验证：模板脚本测试、`node --test scripts/workspace-runner.test.mjs` 和 W-P；若白名单无需改变则不改，新增额外测试落点先拆 WB13 子任务。

### WB14 · 队列到真实构建产物（M）

- [ ] 完成 WB14。依赖：WB13、WB03、WB08–WB10。
- 描述：领取任务、准备冻结快照、监督执行并验证 JAR，写入真实结果。
- 文件：`W/BuildWorker.java`、`W/BuildScheduler.java`、`W/BuildRepository.java`、`W/BuildArtifacts.java`、`WT/BuildWorkerTest.java`。
- 验收：① 在 DB 事务外物化/执行，模板摘要变化则失败，同项目/全局限制贯穿执行；② 仅在完整检查成功、源未变、真实新 JAR 指纹匹配后原子成功；③ 超时/取消直到自有进程退出才终结，失败保留快照和记录且不启动模型请求。
- 验证：W-U `BuildWorkerTest`、W-P、runner 测试；先用可控执行器覆盖竞争，再在 WB18 以真实 API 构建验收，禁止将 mock 结果当真实产物。

### WB15 · 下载本次构建工程（M）

- [ ] 完成 WB15。依赖：WB14、WB12。
- 描述：增加 buildId 绑定的快照下载 API 与工作台入口，复用已有有界二进制下载能力。
- 文件：`W/BuildArchiveController.java`、`WT/BuildArchiveIT.java`、`PF/api/builds.ts`、`PF/components/EngineeringBuild.vue`、`PF/components/EngineeringBuild.spec.ts`。
- 验收：① 每次授权后复验冻结 ZIP 类型/摘要，同一 buildId 字节不随版本/模板更新改变；② 尚无快照为 409，损坏明确错误而不回退当前模板；③ UI 下载准确目标任务，取消下载/切项目不影响新页面会话或泄漏对象 URL。
- 验证：W-I `BuildArchiveIT`、W-F；真实 ZIP 内容对照来源，旧 versionId 下载 API 保持原义。

### C-WB05 · 构建闭环接通

- [ ] WB13–WB15 全部定向测试、前后端构建及旧导出回归通过；API、Worker 和快照下载接线完成。此处不以可控执行器测试宣称真实工程成功，实际 API 成品再构建/启动证明由 WB18 承担。

### WB16 · 重启中断与执行槽恢复（M）

- [ ] 完成 WB16。依赖：C-WB05。
- 描述：处理平台正常关闭、强制退出及再次启动，保留任务和未知进程边界。
- 文件：`W/BuildScheduler.java`、`W/BuildRootLease.java`、`W/BuildRepository.java`、`WT/BuildRecoveryIT.java`、`scripts/workspace-supervisor.test.mjs`。
- 验收：① 重启不重放旧非终态任务，转 INTERRUPTED 并保留追踪身份；② 无法证明旧进程退出则拒绝继续调度，不能只因拿到根锁就认定子进程已死；③ 不按旧 PID/名称/端口终止未知进程，不删除旧目录。
- 验证：W-I `BuildRecoveryIT`、W-P；实际终止测试所启动的平台/监督父进程，重新启动后检查不重复执行。

### WB17 · 故障与敏感信息护栏（M）

- [ ] 完成 WB17。依赖：WB16。
- 描述：系统验证磁盘、构建失败、伪造输出和产物篡改，不通过 fallback 掩盖错误。
- 文件：`WT/BuildFailureIT.java`、`WT/BuildArtifactsTest.java`、`WT/BuildEnvironmentTest.java`、`scripts/workspace-runner.test.mjs`、`scripts/workspace-supervisor.test.mjs`。
- 验收：① 缺工具/测试配置、依赖失败、源码失败、磁盘与数量超限均产生正确失败或准入拒绝；② 超量/伪造输出、链接、旧 JAR、坏报告不能制造成功；③ API/事件/日志 canary 排除凭据和源码，原平台数据及外部进程不变。
- 验证：W-U 相关单测、W-I `BuildFailureIT` 与两个 Node 套件；故障注入限定临时目录与本次进程，不真实填满用户磁盘或停数据库。

### WB18 · 真实 API 与 ZIP 二次构建验收（M）

- [ ] 完成 WB18。依赖：WB17。
- 描述：通过真实所有者会话提交固定应用，下载该构建 ZIP，在另一新目录完整构建对照。
- 文件：`scripts/verify-workspace-build.mjs`、`scripts/verify-workspace-build.test.mjs`、`scripts/verify-workspace-build.d.mts`、`WT/BuildFixture.java`、`WT/BuildFixtureTest.java`。
- 验收：① 实际 API 任务成功，保存归档/source/template/JAR 摘要及命令退出码，不打印配置；② 新 ZIP 全流程构建和两份成品临时启动后 loadedRuntimeId 对应一致，不要求非确定性 JAR 元数据 hash 相同；③ 各自专用运行库可保存/刷新/重启恢复，测试停止自有进程，正式平台仍没有实例 API。
- 验证：W-X，Node helper 单测与 W-U `BuildFixtureTest`；串行独占专用模板测试库，保留失败证据，不复用旧产物证明新代码。

### C-WB06 · 成品与恢复验收

- [ ] WB16–WB18 完成，实际来源、冻结 ZIP、JAR 和运行身份对照可审阅；中断不重发、失败不假成功，测试进程已退出。提供可复查本地证据，不冒充云端已更新。

### WB19 · 真实浏览器工作台验收（M）

- [ ] 完成 WB19。依赖：C-WB06。
- 描述：Chrome 操作真实构建入口，验证刷新/取消/失败/下载及无障碍布局。
- 文件：`frontend/e2e-runtime/workspace-build.spec.ts`、`frontend/e2e-runtime/fixtures.ts`、`frontend/playwright.runtime.config.ts`、`PF/components/EngineeringBuild.spec.ts`。
- 验收：① 真实 API 构建、刷新恢复、未知结果同键确认、取消和下载与 W-X 产物匹配；② 多访客/项目/版本切换不串任务，失败保留旧预览/输入；③ 320/390/768/1440px、键盘、axe 与清晰状态标识通过。
- 验证：W-E，浏览器网络/控制台/截图实际检查；新增 fixture 按现有测试应用约定接入，此构建验收禁止 reuseExistingServer，不复用未知后台或并发占用同一模板测试库，不开启真实模型或线上端口。

### WB20 · 完整回归与代码审阅（S）

- [ ] 完成 WB20。依赖：WB19。
- 描述：回归平台原功能及独立工程，审查安全/并发/生命周期边界并记录真实证据。
- 文件：`docs/workspace-build-verification.md`；发现修复按独立小切片列出实际文件。
- 验收：① W-R、W-X、W-E 完整通过，保留首次失败及修复信息，不把历史计数当本轮结果；② 关键判断做反向变异，恢复后重跑，无禁用/删除断言；③ 审阅全部 Required/Critical 发现得到处理或明确阻塞，测试库和已有服务未损坏。
- 验证：W-R、W-X、W-E、`git diff --check`；核对无密钥、运行数据、缓存或构建输出进入提交。

### WB21 · 更新原文档与模块交接（M）

- [ ] 完成 WB21。依赖：WB20。
- 描述：按实现后的事实更新本地使用方式、完成程度和下游依赖，保留线上与后续模块边界。
- 文件：`README.md`、`CAPABILITY-MAP.md`、`SPEC-workspace-build.md`、`docs/workspace-build-verification.md`、`docs/project-summary.md`。
- 验收：① 配置/启动/构建/取消/下载命令与错误恢复可复现，写明本机资源及独占测试库前提；② 如实列出 app-runtime/runtime-access 未完成、旧预览不变和未部署；③ 下游可凭 buildId 与验证后的不可变产物描述接入，不需要猜路径或重新组装。
- 验证：文档链接及命令与实现对照，`git diff --check`，完成情况逐项映射规格 §8；不创建无证据的上线记录。

### C-WB07 · 本地交付检查点

- [ ] WB19–WB21 完成，提交可审阅的本地变更及验证证据。汇报构建模块完成与未完成的实例模块；GitHub 推送、合并、部署和人工验收仍单独记录，不自动勾选。
