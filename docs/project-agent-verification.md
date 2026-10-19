# project-agent · 离线实施与兼容性记录

日期：2026-10-18～19。规格与计划已批准，模块仍未完成。只使用本机假 Anthropic 服务和公开测试字符串，不调用真实 DeepSeek、不改个人 Claude 配置、不部署、不推送。任务状态以 [清单](../tasks/project-agent/todo.md)为准。

## PA18–PA22：历史保留与原生后端（2026-10-19）

- 自动调度旅程补上完整有序阶段断言。查询、保留、脱敏、自动调度定向组合 **11 集成通过**，0 失败/错误/跳过，08:51 完成。
- 保留红测先得到 3 项中 2 失败：空实现不清理正文、不消耗批次。实现后采用数据库时钟判断 30 天，沿用项目行锁和任务锁；标记过期、提示清空和事件删除同事务，保留幂等摘要、最终结果和最后事件序号。只处理已释放编辑租约的终态任务；磁盘、项目、源码及业务数据不清理。
- 独立调度测试的第一轮因夹具 `registerBean` 方法引用重载歧义编译失败；改为明确 Supplier 后，暴露预期行为红测：关闭旧生成器时没有定时器。新清理组件独立启用调度，Agent 关闭不创建该组件。首批延迟 60 秒、以后每 300 秒，最多 100 个候选、逐任务短事务。
- 反向验证：临时把清理组件条件从 `havingValue=true` 翻成 false，`AgentRetentionSchedulingTest` 两个开关场景均明确失败；随后立即恢复 true。证明测试能发现启停条件被反转，正式实现未保留变异。
- 原生后端使用现有 Spring Boot 3.5.16 + Java 17 依赖子集，Wrapper 与许可证逐字节继承既有已审阅模板。独立包 `dev.atoms.generated`；外置 APP_PORT/APP_DB_URL/APP_DB_USERNAME/APP_DB_PASSWORD，没有平台类、SDK 或 iframe。SQL 仅保存到模板，未在平台数据库执行。
- 原有 `FullProjectBackendSeedTest` 缺文件错误已修复。新增 API 模板测试先因 HealthController 缺文件失败；补齐控制器、初始元数据迁移及工程自身测试后通过。健康接口只说明 HTTP 存活/INITIALIZED，不宣称已生成用户业务或数据库健康。
- 定向 `verify`：**5 单元 + 7 集成通过**，0 失败/错误/跳过，08:56 完成且平台打包成功。覆盖 719/721 小时边界、活跃/待核对/未释放租约保护、清理前后幂等和结果、410 过期游标、批次边界、禁用、失败回滚、双清理者竞争；日志及事件的私有 canary 不外泄。
- 新工程自身第一次 `./mvnw -B -o test` 编译成功但 MVC 上下文有 2 错误：测试没有提供必填 APP_PORT。仅给 MVC 测试添加 `APP_PORT=0` 后，**2 项独立 MVC 测试通过**（08:55），不下载依赖、不连数据库、不监听端口。正式配置要求不放宽；完整启动和 SQL 迁移仍未验收。

对应命令：

```sh
# backend 目录
./mvnw -B '-Dit.test=ProjectAgentRetentionIT,ProjectAgentObservabilityIT,ProjectAgentQueryIT,ProjectAgentCoordinatorIT#oneDispatcherRunsNewTasksAutomaticallyAndRejectsAnotherDispatcher' test-compile failsafe:integration-test failsafe:verify
./mvnw -B '-Dtest=AgentRetentionSchedulingTest,FullProject*Test' '-Dit.test=ProjectAgentRetentionIT,ProjectAgentObservabilityIT' verify
# templates/full-project-v1/backend 目录
./mvnw -B -o test
```

自审：无新增平台迁移/依赖/HTTP 入口；清理数据库异常不打印异常文本或 SQL 诊断。只在 `atoms_demo_test` / `atoms_test` 非超级用户下创建并清理合成测试正文，未清理日常或远程数据。恢复禁用开关可以停止后续清理，但已过期正文不可恢复；幂等和结果审计仍保留。原生模板尚未进入平台 JAR 清单，尚无 Vue 页面、构建脚本或初始化入口，不修改旧模板或现有生成体验。

框架依据：[Spring 定时调度](https://docs.spring.io/spring-framework/reference/integration/scheduling.html#scheduling-annotation-support)、[Spring Boot 3.5 外部配置](https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html)、[MVC 切片测试](https://docs.spring.io/spring-boot/3.5/reference/testing/spring-boot-applications.html#testing.spring-boot-applications.spring-mvc-tests)。这些文档说明框架契约；上面的本地测试是当前版本的执行证据。

### 全量回归发现的旧生成器时钟问题

- 09:18 第一次完整 `./mvnw -B verify`：**267 单元通过；242 集成中 234 通过、5 失败、3 错误、0 跳过**。8 项均在旧 `GenerationExecutionIT`，新 Agent/源码测试通过。没有把失败总结果标为通过。
- 根因：旧任务 `started_at` 由 PG `now()` 写入，Worker 检查和过期扫描却使用 Java `Instant.now()`。10:12 的只读诊断得到主机 `2026-10-19T02:12:40.396465Z`、数据库 `2026-10-19T00:01:51.824600Z`，相差约 2 小时 11 分钟。统一使用 PG `clock_timestamp()` 比较，不修改系统时间、不放宽超时上限、不改模型额度。
- `RunExecutionClockTest` 分别将数据库时间设为 2000/2100 年：新任务应有效、901 秒旧任务应超时，扫描与 Worker 同源。修复前 **4 项明确失败**，修复后 **4 项通过**。一次沙箱运行因 Mockito 无法附加 JVM 报错，获准原样重跑后通过，没有禁用 Mockito 或跳过测试。
- 首轮失败留下 1 RUNNING，随后复测留下 4 QUEUED，阻塞旧生成器单槽位及容量上限。按精确 UUID、测试项目名、提示和创建时间核实后，仅在 `atoms_demo_test`/`atoms_test` 将这 5 项标记取消、追加终态事件、清除对应活跃指针；不删除记录、不触碰其他历史排队项或开发库。Docker 的只读诊断客户端挂起后仅结束该自有 CLI 进程，未停止 Docker/数据库。
- 为 `GenerationExecutionIT` 记录本测试提交成功的任务，即使后续断言失败也能通过正常取消服务收尾。第一版清理未检查终态，导致 24 项定向集成中 7 个 afterEach 错误（业务断言无失败）；已修正为只取消仍活跃的自有任务，保留错误证据并重新验证。
- 10:18 修正后的定向 `verify`：**4 单元 + 24 集成全部通过，0 失败/错误/跳过，BUILD SUCCESS**，包括生成/追加修改、取消、失败、预算、恢复、截止时间和 SSE 回放。命令（backend 目录）：`env PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH ./mvnw -B -Dtest=RunExecutionClockTest '-Dit.test=GenerationExecutionIT,RunAdmissionIT,EventReplayIT,ArtifactIT,RestorationIT' verify`。
- 10:38 再次完整 `verify`：**271 单元全部通过；243 集成中 242 通过、0 失败、1 错误、0 跳过**。旧生成器的 10 项在完整组合中也已通过。唯一错误为 `ProjectAgentCancellationIT.interruptStillRecordsStoppedEvidenceAndRestoresInterruptFlag`：夹具 PID 文件创建即触发中断，子进程可能在写入 PID 内容前结束，最后解析空字符串；取消状态、恢复中断标记及解除执行未决的断言已通过。此命令整体仍为失败，不将其报告为全绿。
- PID 修正仅影响受控 Java 测试夹具：先写完整 `.pending` 文件再同目录原子移动，PID 路径存在才表示就绪。新增两个确定性单测检查发布前不可见、完成后 PID 完整，以及发布前异常不产生空就绪信号；不删除进程退出断言、不放宽超时、不修改生产进程管理逻辑。后续复测覆盖该夹具的所有直接使用者。
- 10:40 临时恢复旧的直接写 PID 文件语义，`./mvnw -B -Dtest=AgentChildFixtureTest test` **2 项均明确失败**；随即还原为原子发布，未保留该反向验证变更。两项失败均是就绪路径过早可见，不依赖随机调度碰到空文件。
- 10:48 原子发布修复后的定向 `verify`：**18 单元 + 27 集成通过，0 失败/错误/跳过，BUILD SUCCESS**。覆盖所有直接使用夹具的 AgentProcess、CLI 探测、Worker、取消、恢复、并发协调和生命周期。命令：`env PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH ./mvnw -B '-Dtest=AgentChildFixtureTest,AgentProcessTest,AgentCliProbeTest' '-Dit.test=ProjectAgentCancellationIT,ProjectAgentWorkerIT,ProjectAgentRecoveryIT,ProjectAgentCoordinatorIT,ProjectAgentLifecycleIT' verify`。最后仅测试夹具有变化，没有第三次重复无关全量套件；不把跨次结果拼成“一次全量全绿”。

## PA23–PA25：原生 Vue 工程（2026-10-19）

复用已审阅 standalone-v1 的 Vue/TypeScript/Vite/Vitest/ESLint 依赖版本及锁文件传递依赖，只改新包名；不升级库，不改旧模板。固定 Node 24.20.x / npm 11.19.x，`npm ci --ignore-scripts --offline --no-audit --no-fund` 从缓存安装 213 项，不下载依赖、不执行安装生命周期脚本。

原生页面调用自己的 `./api/health`，不使用平台 SDK 或 iframe。页面覆盖加载、成功、错误、10 秒超时、重试及卸载取消，明确说明“尚未生成业务功能”；连接成功不冒充数据库或业务验收。Vue 生命周期依据 [官方说明](https://vuejs.org/guide/essentials/lifecycle.html)。

开发/预览固定 loopback 5275/4275，继承旧模板的 Host/Origin/Fetch Metadata 边界与响应头；代理只指向 127.0.0.1 的经校验端口。Vite 关闭 `.env` 加载和客户端环境注入，关闭 publicDir，限制文件读取，不将平台或应用密码放进页面。依据 [Vite envDir/envPrefix](https://vite.dev/config/shared-options.html#envdir)、[文件读取限制](https://vite.dev/config/server-options.html#server-fs-deny)；这些开发设置不是任意代码执行的 OS 沙箱。

红绿和执行记录：

- PA23 配置测试先缺 package.json 失败，PA24 入口测试先缺 vite.config.ts 失败，补齐后分别通过。组件测试第一次因缺 App.vue 加载失败、0 项执行，不计作行为断言失败；实现后 7 项通过。PA25 契约先缺 app.css 失败，再补齐样式、边界测试和 ESLint 配置。
- 首次组合命令仅在第一条命令设置 Node PATH，后续落到 Node 20，Vitest 发生 ERR_REQUIRE_ESM；没有修改依赖或跳过用例。将整个命令链置于固定 Node 24 PATH 后，完整检查通过。
- 11:07 最终前端命令：类型检查、ESLint、**15 项测试 / 2 文件，0 失败/错误/跳过**及生产构建全部通过。生产 JS 63.83 kB（gzip 25.60 kB），CSS 1.92 kB（gzip 0.89 kB）；这是体积记录，不是性能 SLA。
- 11:07 平台 `FullProject*Test` **6 项通过，0 失败/错误/跳过**，包括依赖/锁文件一致、模板文件源码策略、Java/Vue 入口和测试契约。不重复声明完整后端全量通过，前一节保留完整回归及定向修复的真实结果。
- 反向验证：11:13 临时反转 `body.stage !== 'INITIALIZED'`，组件测试 **2 项明确失败、5 项通过**。立即恢复后 11:14 全部 **15 项通过**，未保留变异。

可重跑命令：

```sh
# templates/full-project-v1/frontend 目录；整个命令链固定 Node 24
env PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH /bin/sh -c 'npm run typecheck && npm run lint && npm run test:unit && npm run build'
# backend 目录
./mvnw -B '-Dtest=FullProject*Test' test
# 仓库根目录；需先安装现有锁定依赖、构建前端，且本机已安装 Chrome
env -i PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:/usr/bin:/bin /Users/wubingheng/.nvm/versions/node/v24.20.0/bin/node scripts/verify-full-project-seed.mjs
```

浏览器验收：本会话没有 Chrome DevTools MCP，使用仓库已有 Playwright + 已安装 Chrome 的全新隔离上下文，不读取个人浏览器资料。默认 Playwright headless shell 未安装时没有下载浏览器，改用仓库既有 Chrome channel。经授权监听本机测试端口后，开发页和构建产物均通过 320/768/1024/1440 宽度、无横向溢出、44 px 操作目标、键盘跳转/操作、自动 axe 检查。成功路径控制台无警告/错误；另验加载、503 通用错误和重试，错误正文 canary 不显示，无未捕获异常。浏览器请求只允许本机两端口；API 请求无 Cookie/Authorization。

最终脚本退出 0，截图在 `.local/native-seed-browser-Zjzsfy/`；开发与构建各有桌面/手机图，已核对布局。API 是本机确定性夹具，**没有启动真实 Java 或数据库**。脚本关闭自己创建的浏览器和服务，不触碰日常平台；不随应用模板打包。模板尚未进入正式 JAR 清单，构建/启动脚本、初始化交接、完整工程运行和工作台仍未完成。没有真实模型调用、个人配置修改、推送或部署。

## PA26：工程自带构建/启动契约（2026-10-19）

分三片交付：进程工具、构建打包、显式配置启动。进程工具沿用已审阅旧模板实现，固定 argv、`shell:false`、白名单环境，取消传播至本次进程组；没有为 Agent 开放 Shell。构建先执行脚本测试，再依次安装锁定前端依赖（禁用安装脚本）、检查/测试/构建，复制 dist 至独立临时资源目录，由原生 Maven profile 打进 JAR。链接、私有 dot 文件、缺少入口和过量资源拒绝；不重写源码或递归删除目录。

启动要求工程外的 0600 普通配置文件和已有 JAR，只启动前台 Java、绑定 loopback，不隐式构建或发布。源码忽略规则、运行配置说明与第三方许可证索引齐备。脚本仍不是 OS 沙箱：过滤环境不防止同 UID 文件访问，工具可能读取本机用户配置；维护者应只执行已审阅工程，平台执行准入另行实现。

- 三组 Node 测试先因缺少对应脚本无法加载；实现后 **12 项通过、0 失败/错误/跳过**（11:24）。其中 4 项进程测试真实运行无网络 Node 子进程，其余注入 runner，检查顺序、argv、环境、资源复制与拒绝路径；没有执行 npm 安装、Maven 打包或 Java 应用启动。
- 一项构建测试初次失败是 macOS `/var` 与 `/private/var` 路径别名断言不一致；测试临时根统一 `realpath` 后通过，生产路径校验未放宽。测试目录和资源暂存只含合成内容，保留而不自动清理未知目录。
- Java 构建契约先缺 build.mjs 失败，补齐后通过；扩展启动说明/权限断言后，11:25 **全部 8 项 FullProject 模板测试通过**。模板源码通过既有 ProjectSourcePolicy，未扩充秘密占位符白名单。

命令（仓库根）：`node --test templates/full-project-v1/scripts/process.test.mjs templates/full-project-v1/scripts/build.test.mjs templates/full-project-v1/scripts/start.test.mjs`，实际 Node 为 `/Users/wubingheng/.nvm/versions/node/v24.20.0/bin/node`。平台 backend 目录：`./mvnw -B '-Dtest=FullProject*Test' test`。未实际运行 `node scripts/build.mjs` 或 `node scripts/start.mjs`；完整 JAR/数据库/业务启动仍未验收。进程组依据 [Node 24 detached](https://nodejs.org/download/release/v24.20.0/docs/api/child_process.html#optionsdetached)，资源配置依据 [Maven 资源目录](https://maven.apache.org/plugins/maven-resources-plugin/examples/resource-directory.html)。

## PA27：原生模板进入正式平台 JAR（2026-10-19）

独立 `project-agent-templates/` 资源域，固定 `full-project-v1.files` 的 35 项清单；先验证排序、路径、冲突与数量，再限量读取，拒绝缺失/重复 classpath 来源、链接、超限、非 UTF-8 和秘密内容。完整树使用原有 ProjectSourcePolicy 计算确定性摘要；只有 `backend/mvnw` 标为可执行。不扫描源码工作目录、不打包 node_modules/target/dist 或本机环境。

- 读取器先缺类编译失败，实现后 4 单测通过。打包集成先得到 1 失败 + 1 错误：平台包未包含清单，真实 Boot JAR 外部启动的测试入口退出 1。旧模板 2 项集成当时仍通过。
- 添加精确 Maven includes 后，11:32 `./mvnw -B '-Dtest=FullProject*Test,TemplateCatalogTest' '-Dit.test=FullProjectTemplatePackagingIT,TemplatePackagingIT' verify`：**17 单元 + 4 集成通过，0 失败/错误/跳过，BUILD SUCCESS**。
- 清单、源文件、Maven 资源和最终 JAR 逐字节核对；实际 Boot JAR 在全新临时目录、空环境，由测试专用入口读取新旧各自 catalog。测试入口/Agent 夹具未混入生产类。旧 TemplatePackagingIT 仅将 POM include 检查限定到原 engineering-templates 资源域，原 106 文件、字节和 JAR 精确集合断言不变；新资源域有对称的精确集合断言。

这是平台分发模板的包装验收，不是生成应用 JAR 构建、数据库迁移或应用启动验收。自有读取子进程退出并已收尾；没有实际模型、日常配置、数据库迁移、推送或部署。

## PA28：初始化来源与编辑任务交接（2026-10-19）

内部初始化使用固定模板 ID `full-project-v1` 和完整源码树摘要，经已有源码协调者/Git 发布首版。来源保存在既有 source_operations/source_versions 元数据列，不新增数据库迁移、不把源码放回 PG。初始化与需求提交明确分两步：首版 COMMITTED 后再绑定其版本 ID / 修订号 1 提交 Agent 任务；第二步失败保留首版。没有模型密钥读取、模型预算预留或自动生成业务成功状态。

红绿与边界修复：

- 来源重载先缺方法编译失败；补齐后 11:38 原生来源、旧初始化与旧显式导入 **7 项集成通过**。
- Agent 初始化先缺类编译失败；实现后 3 项交接测试通过。同轮新增旧应用反例 **2 项明确失败**：已有旧版仍可准入；Git 写入期间出现旧版，原生首版仍会提交。
- 修复在共同项目行锁内判断 INITIALIZE + 模板来源，只针对原生首版拒绝已有旧版本/活跃旧任务；准备、启动、ready 和发布阶段复查，不改变 IMPORT/ACCEPT/RESTORE。11:44 来源/旧应用保护 **4 项通过**。同轮事务反例仍失败：调用方事务内确实读取了模板。随后在 Agent 初始化入口先拒绝外层事务，不将文件读取放入数据库事务。
- 11:48 最新组合回归 **17 单元 + 26 集成通过，0 失败/错误/跳过，BUILD SUCCESS**。包含 4 项原生来源/旧应用保护、4 项 Agent 初始化、5 项原任务准入、3 项旧源码初始化、2 项旧显式导入、4 项生命周期及 4 项新旧模板包装。完整工作树全量套件仍以本文之前的完整运行记录为准，不把这次定向结果称为全量全绿。

实际命令（backend）：

```sh
env PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH ./mvnw -B '-Dtest=FullProject*Test,TemplateCatalogTest' '-Dit.test=ProjectAgentInitializationIT,ProjectAgentAdmissionIT,ProjectSourceTemplateInitializationIT,ProjectSourceInitializationIT,ProjectSourceLegacyImportIT,ProjectSourceLifecycleIT,FullProjectTemplatePackagingIT,TemplatePackagingIT' verify
```

只连接 `atoms_demo_test` / 非超级用户 `atoms_test`；Flyway 显示 V022 已是最新，没有新迁移。35 文件首版经真实 Git 保存并读回 Vue 页面；幂等、来源、无模型任务、第二步失败保留、所有权、禁用、归档、损坏模板和外层事务都有断言。旧 source_json、当前版本指针和运行数据不改写，旧导入的精确数据回归通过。自审确认模块依赖仍单向、入口默认关闭、没有公开 HTTP 或 Shell 权限变更。

后续 PA29 继续完整模板上的两轮编辑/差异/重开读取及真实 CLI + loopback 旅程；PA30–33 和真实 DeepSeek 尚未验收。构建脚本虽已提供契约，完整生成应用实际构建、数据库迁移、运行、同快照交付和工作台入口仍未完成。未推送或部署。

## PA29：完整模板的连续编辑旅程（2026-10-19）

PA29a 使用正式 35 文件模板、真实 Git/PG、正式 Worker/进程拥有者/源码接受流程，但编辑器是确定性 Java 子进程。两轮各修改 Vue 和 Java，并新增一份 SQL；逐文件检查未改字节和模式，差异必须精确匹配三个路径。重新创建源码服务/协调者及任务查询服务后，首版和最终版摘要、任务结果均保持。每轮停止凭据已持久化、租约释放，重复调度不再启动进程；无修改以及修改后缺最终结果均保留原版。

- 先缺少旅程支持类编译失败；补齐后修正测试误写的 TaskView 字段名。沙箱拒绝本机数据库连接后，获准原条件重跑。
- 12:10 `./mvnw -B -Dit.test=ProjectAgentJourneyIT test-compile failsafe:integration-test failsafe:verify`：**2 集成通过，0 失败/错误/跳过，BUILD SUCCESS**，用时 3:16；Java 17.0.5、Node 24 PATH、专用非超级用户测试库。不是新工程业务运行或真实模型验收。
- 首版 `905a4ba9-226b-4f1f-baba-8faa0b1e64f7` → 第一轮任务 `07062e31-ce87-46bf-a188-22a6bf6f797b` / 版本 `3363edb7-3510-467a-ba21-2368070155e3` / 树摘要 `0282e14a08cea9c4b50229eed9233c18b4d07cce9073fb6782909a96f1f93252`。
- 第二轮任务 `cb9acd44-e665-4b17-b5b0-a6fa1c5fc092` / 版本 `8f97011b-0c10-46fe-be28-b9f4fcb88e7c` / 树摘要 `88329a7a813e6386dc88b6104299cd192745bea9e48e9802fb2d654c75e33f37`。两个任务均保存 SOURCE_SAVED 和真实停止凭据；这些 UUID 仅是测试审计证据，不是用户项目。

### 当前安装 CLI 发生版本变化

PA29b 新增真实 CLI 同旅程夹具后，12:14 离线 CLI 组合 **6 项中 1 失败、5 错误、0 跳过**，均为 `AGENT_CLI_UNSUPPORTED`，没有把结果记作兼容通过。空环境、独立临时配置目录、不带密钥的 `--version` 探测确认当前为 **2.1.303**；可执行文件修改时间为 11:56:33。检查的本机原生版本目录及项目临时目录未找到旧版。没有运行安装/更新/登录命令或修改个人配置。

[官方更新记录](https://github.com/anthropics/claude-code/blob/main/CHANGELOG.md#21303)只将 2.1.303 描述为修复和可靠性改进，不能据此推断工具权限或事件协议不变。保留 2.1.302 的既有证据，针对 2.1.303 做精确兼容验证，未知版本仍拒绝；未验证前不修改默认关闭状态或启用真实调用。

首次新夹具把版本探测误放在执行阶段，留下合成测试任务 `f7e3d276-1c27-43f4-8c64-866bb5b95ac4`（项目 `cec9f0ee-3d3e-4dd2-a43a-73aa62b9fddb`）为 NEEDS_RECONCILIATION。探测已退出、未启动编辑会话、没有 API 请求；该测试记录保留，不伪造停止摘要或删证据解锁。已将夹具探测移至 prepare，与生产适配器一致；12:16 新增准备期拒绝测试 **1 集成通过**，证明版本不兼容时没有执行身份、没有额度预留、释放租约并保留旧版。该次定向命令选择 `ProjectAgentJourneyIT#unsupportedCliAtPreparationCreatesNoExecutionIntentAndReleasesTheLease`；不把它当作真实 2.1.303 工具验收。

### PA29b 完成证据

2.1.303 的版本/内置模块单测先有 **14 项中 2 错误**；仅新增精确版本到同一集合，不接受版本范围，不修改命令/工具/环境权限。未知 2.1.304、beta、非零退出和混入正文仍拒绝。12:22 以下命令完成 **14 单元 + 6 真实 CLI 离线集成通过，0 失败/错误/跳过，BUILD SUCCESS**：

```sh
env PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH ./mvnw -B -Pagent-cli-contract \
  -Datoms.agent.test.cli=/Users/wubingheng/.nvm/versions/node/v20.15.0/lib/node_modules/@anthropic-ai/claude-code/bin/claude.exe \
  '-Dtest=AgentCliProbeTest,AgentStreamDecoderTest' '-Dit.test=AgentCli*ContractIT' verify
```

其中原有 5 项权限/工具/进程契约通过，新完整旅程使用新配置目录和不同任务副本分别启动两次 CLI。每轮 7 个真实文件工具往返、8 个仅 loopback 请求，假 Key、deepseek-flash、实际 max_tokens=128000、工具集合固定五项。首版 `400287d3-09af-4b92-948d-c04f91b3d077`；第一轮任务 `a3007be3-cf3f-4570-8b5d-c5a8760373c2` / 版本 `ca809214-ec2b-4124-8815-c4bb75458ce0`；第二轮任务 `44f75293-5b27-43f3-9076-b6caa4af1fd0` / 版本 `994b9f7d-2c9b-408d-8da5-e8b6f49b5b81`。两轮树摘要分别与 PA29a 的 `0282e14a…f93252` / `88329a7a…e33f37` 完全一致，测试读取完整摘要而非只比前缀。停止凭据、版本差异、未改内容及重开读取均通过。

这是同一源码流程的实际 CLI **离线**验收；没有真实 DeepSeek 质量/账单、应用构建运行或工作台入口验收。PA30–33 尚未完成；上述合成待核对记录仍保留，不能宣称全部协调者或全量回归已通过。

## PA30：工具拒绝与候选边界（2026-10-19）

真实 CLI 2.1.303 的离线假服务依次发起 Bash、Agent、Task、虚构 MCP 工具，以及读取合成 settings / credentials 的请求。6 次工具结果全部明确为错误，7 次请求仍只公布五种文件工具；两个 canary 均未进入请求。测试目录内专门创建 `synthetic-user/.claude`，不读取或写入真正的个人配置。CLI 最终文本即使声称成功，平台流解析器也因首个未公布工具调用返回 `AGENT_PROTOCOL_INVALID`，不发行成功摘要。

另有 4 个真实受控 Java 子进程分别写入公开秘密格式 canary、软链接、硬链接和超过 2 MiB 的文本。即使子进程正常退出并返回 success，正式 Worker 仍拒绝候选：旧 Git/PG 版本与摘要不变、没有新接受操作、停止证据保留且租约释放，重复调度不启动第二次。受控副本证据保留至测试临时目录的正常收尾；不是任意恶意程序的 OS 隔离保证。

- 新工具夹具先缺方法编译失败。首次完整组合为 **16 单元通过，14 集成中 13 通过/1 失败**：拒绝和不泄漏断言均通过，测试误把未知工具的协议拒绝期望为能力/路径错误。检查正式 decoder 的工具名守卫后改为精确 `AGENT_PROTOCOL_INVALID`，不改生产拒绝条件，并补上“未知工具伪造成功结果仍拒绝”的独立单测。
- 12:40 **17 单元 + 14 集成通过，0 失败/错误/跳过，BUILD SUCCESS**，包含原 5 项 CLI 契约和 4 项 Worker 回归。
- 12:51 反向验证：临时移除 `!TOOLS.contains(name)`，15 单元中新增反例 **1 项明确失败**（未抛拒绝异常），其余 14 通过；随后立即恢复该条件，未提交或部署变异。恢复后 `git diff` 确认该生产文件无变化；12:54 原组合命令重新编译、打包并完成 **17 单元 + 14 集成通过，0 失败/错误/跳过，BUILD SUCCESS**，没有留下变异构建产物。
- 只读排查合成待核对记录时，Docker CLI 无输出挂起；按完整命令定位本次自有 PID 19277，仅结束该 CLI，退出码 143。没有停止 Docker Desktop/容器/数据库，未执行任何数据库 UPDATE/DELETE 或人工解锁。该待核对记录和全量恢复回归仍待处理。

组合命令（backend）：

```sh
env PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH ./mvnw -B -Pagent-cli-contract \
  -Datoms.agent.test.cli=/Users/wubingheng/.nvm/versions/node/v20.15.0/lib/node_modules/@anthropic-ai/claude-code/bin/claude.exe \
  '-Dtest=AgentChildFixtureTest,AgentCliProbeTest,AgentStreamDecoderTest' \
  '-Dit.test=AgentCliContractIT,AgentCliBoundaryContractIT,ProjectAgentBoundaryIT,ProjectAgentWorkerIT' verify
```

自审：PA30 仅添加测试/夹具；候选策略、权限、源码限额、模型调用额度和默认开关没有放宽。PA29 的版本兼容只添加精确 2.1.303；本次没有安装/更新任何工具或依赖。真实模型、完整后端全量回归、运行/交付/工作台仍未完成。

## 并发调度调整（2026-10-19）

用户确认不同项目并行，同项目保持编辑互斥，随后要求默认并发提高到 4。新增 `PROJECT_AGENT_MAX_CONCURRENT_TASKS`，默认 4、范围 1–8、重启生效；最大等待队列仍为 8，排队期限仍为 300 秒。此设置只影响新 Agent 模块，不修改旧生成器或默认关闭状态。

- 配置红测：原实现忽略 `max-concurrent-tasks=0`，`AgentSettingsTest` 6 项中 1 项失败。加入范围校验和默认值后，配置/命令/任务单测共 12 项通过。
- 并发红测：`ProjectAgentCoordinatorIT#distinctProjectsUseConfiguredSlotsAndCancellationAdmitsTheNextTask` 共 3 项，默认 2 和显式 3 均因第二个进程未启动而失败；显式 1 通过。随后将单槽位改为有界活动任务集合、固定大小线程池，移除 Worker 跨任务执行锁；同项目仍依赖项目行锁、活动编辑租约和任务唯一索引。
- 运行证据使用真实 PG、Git、确定性 Java 子进程。不同项目的工作目录、执行 ID、PID 必须不同，且同时存活；一个任务取消后，其他任务保持 RUNNING，队列自动补位。不是实际模型的质量/吞吐/费用验收。
- 第一轮 10 项调度测试中，9 项通过；异常收尾测试在数据库已提交、内存句柄尚未清理的时间窗口失败。等待条件改为同一 30 秒期限内同时满足终态和句柄清理，保留两项断言，不以固定睡眠掩盖问题。随后异常收尾和重复调度 2 项定向测试通过。
- 防回退实验：临时恢复 Worker 的全局 `synchronized` 后，`duplicateDispatchDoesNotWaitForOrStartAnotherSession` 在重复领取处明确超时失败；已还原此变异，正式代码不持有跨任务执行锁。
- 全量单元检查 `./mvnw -B test`：264 项，262 通过，2 错误、0 跳过。一项是调用命令的 PATH 先找到 Node 20.15.0，而既有 BuildEnvironmentTest 要求项目固定的 Node 24.20.x / npm 11.19.x；另一项是本轮开始前已存在的 PA21 红测 FullProjectBackendSeedTest，完整工程模板的 backend/pom.xml 等文件尚未实现。本次不删除或屏蔽该测试，也不把整个工作树报为全绿；后续回归使用显式 Node 24 PATH。
- 不增加依赖，不改变个人 Claude 设置、模型工具权限或日常 env。独立进程不是 OS 安全隔离；目录校验和维护者限制保留。
- 完整工程模板、安装构建和工作台接入属于尚未完成的后续工作；并发调度完成不能表述为整个 Agent 产品已上线。

最终定向回归命令（backend 目录；不包含仍为红测的 PA21 模板）：

```sh
env PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH ./mvnw -B \
  '-Dtest=Agent*Test,ClaudeCodeAgentTest,BuildEnvironmentTest' \
  '-Dit.test=ProjectAgent*IT,AgentCoordinatorLeaseIT,ProjectSourceLifecycleIT,ProjectSourceEditLeaseIT' verify
```

2026-10-19 08:41 完成：**49 单元 + 49 集成通过，0 失败/错误/跳过，BUILD SUCCESS**。包含默认 4、显式 1/3 的并发与补位、显式 2 个任务一起关闭、同项目互斥、重复领取不启动第二会话、租约丢失停止所有当前进程，以及原有取消/恢复/事件/额度/源码租约回归。Node 24 的构建环境测试已通过；全量工作树中 PA21 的模板缺文件问题仍未解决。未新增数据库迁移、未触碰日常服务、未推送或部署。

本次自审：活动槽位包含准备/执行/封存/收尾阶段；单个调度线程准入、关闭共用短锁、任务退出释放各自槽位。Worker 不再全局加锁，同项目数据库约束保留。子进程环境、工具白名单、真实调用额度和身份授权未放宽；没有新增依赖、外部服务或秘密配置。需要回退并发时设置 `PROJECT_AGENT_MAX_CONCURRENT_TASKS=1` 并重启。

## 已完成底座

- PA01：懒加载默认关闭配置，维护者 UUID、绝对路径与预算校验。先缺类失败，再 5 个配置测试通过。配置关闭不读取密钥、不做文件/进程 I/O。
- PA02：固定参数数组、空继承环境白名单、CLI 2.1.302 精确版本与文件身份校验。同长度、同时间戳内容修改仍被 SHA-256 校验发现。新增 6 个测试先缺类失败，之后共 11 个 Agent 单元测试通过、0 失败/错误/跳过。
- Oracle Java 17.0.5 保持不变。新增 Agent 不接入旧生成器，不改旧 tokens、额度或超时配置。日常服务与远程数据库未变。
- 2026-10-19 默认后端回归：`env PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH ./mvnw -B verify` 在沙箱外完成，224 单元测试 + 170 集成测试通过，0 失败/错误/跳过，BUILD SUCCESS。仅使用专用测试 PG，日志确认无需新增迁移。需要安装 CLI 的独立 tag 不在默认运行范围；上述数字不包含下述 2 个失败用例，也不表示 Agent 模块验收通过。前端未修改，本轮未重复前端测试。

## PA03：原 bare 组合的失败证据

真实可执行文件：`/Users/wubingheng/.nvm/versions/node/v20.15.0/lib/node_modules/@anthropic-ai/claude-code/bin/claude.exe`。先用无认证版本探测验证 2.1.302，再在新临时项目、config/tmp 目录中执行。测试服务固定绑定 `127.0.0.1`，不转发网络请求。正式代码没有用户可配置的 API 地址；loopback 覆盖只存在于 test 源码。

复现命令（backend 目录）：

```sh
./mvnw -B -Pagent-cli-contract \
  -Datoms.agent.test.cli=/Users/wubingheng/.nvm/versions/node/v20.15.0/lib/node_modules/@anthropic-ai/claude-code/bin/claude.exe \
  '-Dtest=Agent*Test' '-Dit.test=AgentCli*ContractIT' verify
```

最近结果：11 单元测试通过；2 个参数化 CLI 测试均因缺少工具而失败，0 错误/跳过。测试没有改低验收期望。

2026-10-19 最后补验：将文件读回与 Edit 工具结果断言放在能力集合断言之前，再以同一 CLI/profile 执行 `test-compile failsafe:integration-test failsafe:verify`（保留 `-Dit.test` 选择器），2 个用例仍仅因缺少 Glob/Grep/Write 失败；之前的读改、正常退出和 128000 参数检查通过。没有为重复验证重跑已通过的完整默认回归。

| 观测项 | 两种参数格式的实际结果 |
| --- | --- |
| 工具白名单参数 | 逗号分隔 / 独立 argv，均为 Read、Glob、Grep、Write、Edit |
| 受控 API 请求数 | 每次 3 次，全部到本机模拟接口 |
| 认证方式 | x-api-key，固定假值 offline-fixture-key |
| 上游模型字段 | deepseek-flash（CLI 参数带 `[1m]`） |
| 上游 max_tokens | 128000 |
| 上游 stream | true |
| 实际工具集合 | **Edit、Read**，没有 Glob、Grep、Write |
| 文件变化 | hello.txt 从 `before\n` 变成 `after\n` |
| 最终结果 | success、is_error=false、退出码 0、permission_denials 为空 |

CLI 确实能读改已有文件，不等于完整工程编辑契约已满足。这里没有新增文件、搜索、恶意越界、真实模型、持续任务、源码版本接受或生成应用运行的验收证据。工具轮次/重试预算也不能由这次正常往返推断为已全面验证。

生产 JAR 中只有 AgentSettings、AgentInvocation、AgentCliProbe 及其内部值类型；`jar tf` 确认未包含 AgentProtocolFixture 或 AgentCliContractIT。默认 Maven verify 按 tag 排除需要安装 CLI 的测试，显式 profile 才运行；缺少 CLI 路径会失败，不会跳过当成功。

## 排查与待确认的调整

1. 首轮完整 verify 在沙箱中发生 Mockito JVM self-attach 和自有子进程检查失败；按权限流程在沙箱外重跑，同一代码的 **224 个单元测试全部通过、0 失败/错误/跳过**，随后暴露上述真实 CLI 工具兼容性失败。没有关闭 Mockito、禁用失败测试或改旧实现。
2. 请求中记录的工具字段仍是 Read 的 file_path/offset/limit/pages/allow_large，以及 Edit 的 file_path/old_string/new_string/replace_all。未看到搜索工具被合并到这些字段的证据。切换相同白名单的参数分隔方式后结果不变。
3. 官方 [bare 说明](https://code.claude.com/docs/en/headless#start-faster-with-bare-mode)描述其受限工具和无个人 OAuth/keychain 行为；[工具参考](https://code.claude.com/docs/en/tools-reference#glob-tool-behavior)说明通常可显式恢复 Glob/Grep，但这不证明当前 bare 组合一定恢复。以上实测优先于假定。
4. `--safe-mode + --restricted + 独立配置目录 + 原文件工具白名单` 已在 2026-10-19 用户要求完整实施后验证，见下节。根据 [CLI 参考](https://code.claude.com/docs/en/cli-reference)，safe-mode 的认证行为与 bare 不同，受管策略仍生效；不能宣称是等价替换或 OS 沙箱。

## PA03：safe-mode 离线通过

正式 argv 改为 `--safe-mode --restricted`，版本探测仍使用无密钥 `--bare --restricted --version`。未升级 CLI，文件工具白名单与 128000 输出目标不变，不开放 Shell/MCP。测试覆盖：两种参数写法的 Read/Edit；Glob/Grep；新增 Vue、Java 和 SQL；目录外读写、软链接读取拒绝；项目和私有配置 CLAUDE.md canary 未进入请求。

红绿证据：新增夹具方法前测试编译失败；补齐夹具后参数单测明确因仍使用 bare 失败；切换正式参数并移除测试中的替换后，执行下面命令通过（2026-10-19 00:59，Java 17.0.5）：

```sh
env PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH ./mvnw -B \
  -Pagent-cli-contract \
  -Datoms.agent.test.cli=/Users/wubingheng/.nvm/versions/node/v20.15.0/lib/node_modules/@anthropic-ai/claude-code/bin/claude.exe \
  '-Dit.test=AgentCli*ContractIT' verify
```

结果：224 单元测试、4 CLI 集成测试通过，0 失败/错误/跳过，构建成功。此命令没有运行其余数据库集成测试，其上次完整回归见本文开头。第一次候选验证在沙箱内因禁止绑定 loopback 端口报 SocketException，获准后原命令在沙箱外运行；没有跳过该测试。

这些证据只证明已安装版本在本机测试场景下的文件操作及部分权限边界。没有证明真实 DeepSeek、任意受管策略、个人 keychain 完全不可访问、任务恢复、工程构建或运行。完整隔离/canary 反例仍在 PA30，真实模型仍属 PA-LIVE。PA04 及之后按任务清单继续。

## PA04：私有状态目录

任务状态根采用持有期间的文件锁、目录/标记文件身份检查及任务不透明句柄。只创建新任务，不接管未知目录、不删除失败证据；私有目录 0700、标记 0600。默认逻辑容量 1 GiB、磁盘余量 512 MiB，不是 OS 配额。检查扫描有条目数和时间上限。真实模式以原生 Unix UID 拒绝 root，禁用时不触碰文件或凭据。

红绿：目录类、环境类各自先缺类失败；补齐后目录 5 项、环境 3 项通过。一次测试代码误用了不存在的 Path 断言方法，修正为 Path.startsWith 布尔断言后继续验证，没有跳过用例。最新命令为上述 CLI verify 命令增加 `'-Dtest=Agent*Test,ProjectSourceEnvironmentTest'`，结果 **22 单元 + 4 CLI 集成通过**、0 失败/错误/跳过，2026-10-19 01:04 构建成功。CLI 额外确认 system/init 的工具集合也是同五种。

PA04 不持有运行中的模型进程，不代替后续任务所有权、源码租约及真实模型预算准入。没有日常配置、数据库或远程变更。

## PA05：有界流与真实 CLI 差异

实现逐行严格 UTF-8/JSON 解码，限制单行 4 MiB、总 stdout 64 MiB、4096 事件，只返回工具计数、受校验相对路径和数值 usage。缺结果、冲突结果、工具未结算/错误、权限拒绝、未知能力、越界路径均不能成功；模型正文、源码、thinking、原始错误不进入摘要。

实测纠正了两项初始假设：2.1.302 会在 init 前发送 `system/ui_invalidate`；safe-mode 仍在 init 报告两个 `path=builtin`、`source=<name>@builtin` 的内置模块 cc-plugin-agents-md / cc-plugin-plugin-authoring。先保留失败证据，再添加回归并精确适配，未删除插件校验。未知版本/模块/路径/来源或重复条目仍拒绝；外部插件不放行。新增项目/私有配置 AGENTS.md 与原 CLAUDE.md canary 均未进入假模型请求。依据为已安装 CLI 的离线实测；[官方 safe-mode 说明](https://code.claude.com/docs/en/cli-reference)并不单独证明机器策略或 OS 隔离。

红绿：缺类编译失败 → 单元通过 → 真实 CLI 揭示事件差异 → 精确重现与修正 → 补充未结算工具反例先失败再修正。2026-10-19 01:20，同 PA04 命令得到 **31 单元 + 4 CLI 集成通过，0 失败/错误/跳过**。一次诊断误写测试方法选择器导致 0 测试，未计为通过，随后以正确选择器及整类回归验证。无真实模型、个人配置、日常数据库或远程变更。

## PA06：自有进程与共享期限

新增单调 deadline，stdin/stdout/stderr 并行处理，等待不重新计时。只跟踪本次启动的进程及其观察到的后代；观察记录最多 128 个，超过或无法核对身份时保持不确定。取消/超限/失败先温和停止，之后强制停止已核对身份的自有进程，收尾共享额外最多 10 秒；原始 stderr 丢弃且限制 256 KiB。未证明进程和 I/O 线程结束时不发行结束凭据。

9 项真实 Java 夹具测试覆盖成功/异常退出、截断、两种输出洪泛、过期/取消、忽略 TERM、后代保留管道、stdin 阻塞和无法枚举后代时的保守失败。不是恶意进程 OS 沙箱；脱离父进程的任意程序不在此保证范围。首次沙箱内枚举被拒，按权限在沙箱外原样重跑通过。真实 CLI 增加正式 AgentProcess 调用，而不是只验证测试夹具自己的启动代码。

2026-10-19 01:25，同 PA04 verify 命令：**40 单元 + 5 CLI 集成通过，0 失败/错误/跳过，BUILD SUCCESS**。未运行真实模型，也未接入任务服务/源码发布；旧后端全量回归在后续集成阶段重跑。

## PA07：持久编辑锁

V020 新增 source_edit_leases 和 source_operations 的可空关联，不引用 Agent 表，不修改 V018/V019。项目行锁 + 活动项目唯一索引互斥；版本/项目/修订号由组合 FK 约束，任务、基线、执行身份及停止摘要不可替换。执行未结束或关联源码操作未结算时不能释放，删除审计证据被拒绝。

红绿：新增测试先缺类失败；沙箱禁止连接 PG 后按权限重跑。V020 仅应用到非超级用户 atoms_test 使用的 atoms_demo_test。Spring 实例化发现 Repository final 与 CGLIB 不兼容，修正类声明后 2026-10-19 01:32 命令 `./mvnw -B -Dit.test=ProjectSourceEditLeaseIT test-compile failsafe:integration-test failsafe:verify` **6 测试通过、0 失败/错误/跳过**。此前已应用的迁移不重写。

回退是关闭新入口、保留表/锁/历史，而非 DROP 数据：测试验证关闭源码开关后原版本指针及租约证据保留，旧 projects 指针不变。仅有表和 DAO 尚不能阻止旧代码的全部写入口，PA08 继续接入；不得在此启用日常 Agent。

## PA08：统一写入口互斥

提交/幂等重放、工作副本基线、Worker 准备/启动/ready/发布和恢复结算均在项目行锁下检查当前编辑租约。普通写入口遇到活动编辑拒绝；专用接受入口要求同项目/所有者/基线/任务绑定且停止已确认，源码操作持久保存 edit_lease_id。历史版本读取、查询状态、请求取消和保守失败标记不被锁死。

3 项反例先实际失败：普通写未阻止、已排队写绕过、两个连接同时获准。实现后通过，并增加已停写租约的真实 Git 发布正例，共 4 项 guard 检查通过。2026-10-19 01:41：

```sh
env PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH ./mvnw -B \
  '-Dtest=Agent*Test,ProjectSource*Test' '-Dit.test=ProjectSource*IT' verify
```

**107 单元 + 67 源码集成通过，0 失败/错误/跳过**。包含旧导入、恢复、回滚、工作副本、取消和源码旅程。测试中的 Coordinator 争抢连接警告是拒绝第二个拥有者的故障注入用例；该测试通过。尚非新 Agent 到 UI 的完整旅程。

## PA09：受控编辑工作副本

新增源模块门面 ProjectSourceEditGateway。句柄私有构造，绑定发行实例及持久租约，不从路径/UUID 重建。准备副本前后核对当前账号与基线，工作副本保留租约许可；纯副本不暴露 canonical Git 仓库。数据库事务内拒绝文件 I/O，外层任务准入事务回滚时撤销内存句柄。释放仅在允许的持久状态下执行，不删除工程文件或未知目录。

先因缺类编译失败，4 项新集成通过。2026-10-19 01:45，以 `./mvnw -B '-Dit.test=ProjectSourceEdit*IT,ProjectSourceWorkspaceIT,ProjectSourceAcceptanceIT' test-compile failsafe:integration-test failsafe:verify` 得到 **24 集成通过、0 失败/错误/跳过**，包含原工作副本/候选接受流程。此时仅可领取、准备、核对及释放，执行确认和封存接受仍在 PA10–PA12。

PA10a（01:47）：增加 SourceExecutionBudget 内部契约，SourceDeadline 同时服从本地操作、单步和调用者剩余任务预算。先缺类/构造器失败，`./mvnw -B -Dtest=ProjectSourceDeadlineTest test` 3 项通过；异常预算只返回 SOURCE_OPERATION_TIMEOUT，不暴露调用者错误正文。实际文件操作传递待 PA10b。

PA10b（01:50）：预算传入副本准备、再次核对、封存与候选读取；句柄绑定预算实例，不允许换一个新计时器。查询中的正常超时不再错误地锁成存储损坏。先缺重载编译失败，再运行 `./mvnw -B '-Dit.test=ProjectSourceEditDeadlineIT,ProjectSourceWorkspaceIT,ProjectSourceAcceptanceIT,ProjectSourceEditWorkspaceIT' test-compile failsafe:integration-test failsafe:verify`，17 项通过（包含新期限用例 3 项），0 失败/错误/跳过。`python3 -I -S -B src/test/python/test_workspace_helper.py` 11 项通过。没有真实模型调用或运行用户应用。

PA11（01:57）：SourceAcceptance/Coordinator/Worker 传递同一 deadline，队列等待不重置；publish 在数据库行锁内再检查期限。缺重载红测后，`./mvnw -B '-Dit.test=ProjectSourceEditPublishDeadlineIT,ProjectSourceCancellationIT,ProjectSourceLifecycleIT,ProjectSourceAcceptanceIT' test-compile failsafe:integration-test failsafe:verify` 19 项通过、0 失败/错误/跳过。过期排队没有 Git execution_id，提交前过期无新当前指针，提交后过期重试保持 COMMITTED。

PA12a（01:59）：将已验证的生命周期抽为 `execution/OwnedProcess`，Agent 保留协议适配器。结束凭据构造器私有，绑定本次 task/execution/work directory 并生成稳定停止摘要；其他任务、执行或目录不能匹配。先缺重载红测，`./mvnw -B -Dtest=AgentProcessTest test` 10 项通过、0 失败/错误/跳过。另外审阅时修正 PA11 的无准入断言，使用真实哈希幂等键查询；组合回归随后重跑。

PA12b（02:04）：源码门面新增持久启动意图、持有者凭据确认、封存/接受与无变更结果。采用基础进程拥有者而非 source→agent 反向依赖；停止证据可在期限到达后记录，但不能因此授权发布。先缺少接口红测，`./mvnw -B '-Dit.test=ProjectSourceEditAcceptanceIT,ProjectSourceEditPublishDeadlineIT,ProjectSourceEditWorkspaceIT' test-compile failsafe:integration-test failsafe:verify` **11 项通过、0 失败/错误/跳过**。使用真实受控 Java 子进程、Git 和专用 PG；无真实模型。并发重试仅一次提交，已提交结果不因重试变成失败，无实际变更无新版本，执行意图外层事务回滚后可安全重试。PA11 修正后的幂等键断言也通过。

PA13 准入部分（02:11）：输入先缺类红测，`AgentTaskTest` 2 项通过；新增任务服务先缺类红测，再以 `./mvnw -B -Dit.test=ProjectAgentAdmissionIT test-compile failsafe:integration-test failsafe:verify` 5 项通过，0 失败/错误/跳过。V021 只迁移 atoms_demo_test，保留新任务/事件/额度表与源码租约组合 FK，不修改旧迁移。真实两连接同键仅一个任务，跨账号/归档/归属切换拒绝，事务回滚不遗留源码锁或内存句柄，队列 8 项限制和数据库不可变身份通过。测试专用源码桥接不放宽正式包内部可见性。任务额度表尚未接启动预留，PA13 仍保持未完成，随 PA14a 验证；没有调用真实模型。

PA14a（02:17）：先缺生命周期类红测，再以 `./mvnw -B '-Dit.test=ProjectAgentLifecycleIT,ProjectAgentAdmissionIT' test-compile failsafe:integration-test failsafe:verify` **8 项通过、0 失败/错误/跳过**。源码执行意图、任务 RUNNING 和额度增量在一个短事务中提交；超额整个回滚，双启动拒绝，已记录的付费意图在失败后不退额度。测试只模拟预算预留，实际只运行本地 Java 夹具，没有请求 DeepSeek；测试库预算计数保留，不借用旧 generation_budget。未知退出保留 NEEDS_RECONCILIATION，确认证据后才能结算和释放。由此完成 PA13 剩余额度验收，Worker/真实适配器仍未接入。

PA14b 及探测修复（02:31）：原版本探测在主进程退出后同步 readNBytes，存在后代占管道的无界等待风险；新增反例先缺接口失败，再复用 OwnedProcess 有界读流/收尾，hash 扫描也检查同一预算。通用进程允许空 stdin 用于无交互版本探测，Agent 提示仍拒绝空输入。CLI Session 使用固定端点和私有配置，拒绝未授权/关闭/已超时/预算改变时不碰状态或密钥；终止时清空父进程持有的 builder 环境。不改变个人配置。

首次实测命令误写 `atoms.agent.test-cli`，42 单元通过但 5 CLI 测试因缺显式路径失败，未计兼容性通过。修正 `CodingAgent.Result` 在 interface 中的 public 构造器后，使用正确命令：

```sh
env PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH ./mvnw -B \
  -Pagent-cli-contract \
  -Datoms.agent.test.cli=/Users/wubingheng/.nvm/versions/node/v20.15.0/lib/node_modules/@anthropic-ai/claude-code/bin/claude.exe \
  '-Dtest=Agent*Test,ClaudeCodeAgentTest' -Dit.test=AgentCliContractIT verify
```

**44 单元 + 5 真实 CLI 离线集成通过，0 失败/错误/跳过，BUILD SUCCESS**。仍只使用假 Key 和 loopback 假服务；尚非真实 DeepSeek 或用户功能验收。

PA14c（02:38）：新增 Worker 先缺类红测。`./mvnw -B '-Dit.test=ProjectAgentWorkerIT,ProjectAgentLifecycleIT,ProjectAgentAdmissionIT' test-compile failsafe:integration-test failsafe:verify` **11 项通过、0 失败/错误/跳过**。受控 Java 编辑进程实际修改 Vue/Java/SQL，经正式进程拥有者、源码门面和 Git 协调者保存两轮连续版本；README 和旧 SQL 等未改内容逐字节一致。重复调度不再执行，NO_CHANGES 无新版本，非法 .env 与缺结果的进程不能覆盖旧版，已确认停止后释放租约。结构化任务日志只记录 ID/阶段/稳定码/耗时。尚未接自动调度、取消、恢复或公开入口，也没有编译/启动生成工程。

PA15a（02:44）：新增 V022 持久取消标记，只迁移测试库，不重写 V020/V021。领取/启动意图/停止确认/取消等数据库动作不再获取文件 I/O 的全局监视器，容量采用 64 个非阻塞许可；取消标记与项目行锁阻止后续接受/发布。源操作已提交时只返回持久事实。先缺接口红测，`./mvnw -B '-Dit.test=ProjectSourceEditCancellationIT,ProjectSourceEditAcceptanceIT,ProjectSourceEditWorkspaceIT,ProjectSourceCancellationIT' test-compile failsafe:integration-test failsafe:verify` **17 项通过、0 失败/错误/跳过**。受控复制阻塞下，持有项目行锁的取消 2 秒内完成；提交前/后竞争分别得到 CANCELLED / COMMITTED，原取消回归通过。

PA15b（02:49）：任务取消 API 与发布结果判定接通。`./mvnw -B '-Dit.test=ProjectAgentCancellationIT,ProjectAgentWorkerIT,ProjectAgentLifecycleIT,ProjectAgentAdmissionIT' test-compile failsafe:integration-test failsafe:verify` **15 项通过、0 失败/错误/跳过**。排队取消不启动进程，运行取消先持久标记再由拥有者确认退出；提交前取消不新增版本，提交后请求保留 SOURCE_SAVED。测试使用本地受控子进程及专用测试库，不使用真实模型。

PA15c / PA16（03:03）：启动前检查回调由进程拥有者执行，尚未启动时能签发绑定的停止证据。线程中断后暂时清除中断标记以保存数据库退出事实，完成后恢复，不继续发布。新增反例先缺重载编译失败；随后 JDBC 收尾带中断标记的定向测试实际失败，再修复。恢复先缺类红测，实现后仅根据持久编辑租约和源码操作结算：未启动失败、退出未知保留待核对，已提交补齐 SOURCE_SAVED；不重跑模型、不重建可写句柄、不操作旧 PID，归属变化不向旧用户返回数据。

```sh
env PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH ./mvnw -B \
  '-Dtest=AgentProcessTest,AgentCliProbeTest,ClaudeCodeAgentTest' \
  '-Dit.test=ProjectAgentCancellationIT,ProjectAgentRecoveryIT,ProjectAgentWorkerIT,ProjectAgentLifecycleIT' verify
```

**18 单元 + 15 集成通过，0 失败/错误/跳过，BUILD SUCCESS**。包括提交后超时和 Agent 尚未记下源码操作 ID 的恢复，均保留唯一提交版本、不追加预算。真实模型未调用，自动调度尚未接入。

PA17a（03:06）：新增调度器独占会话租约，与 Worker 并发数无关。先缺类红测；`./mvnw -B -Dit.test=AgentCoordinatorLeaseIT test-compile failsafe:integration-test failsafe:verify` **2 项通过、0 失败/错误/跳过**。双连接拒绝第二调度者，关闭后可重新领取；连接仍存活但释放 advisory lock 时健康检查失败。连接中止警告来自受控争抢失败会话的主动丢弃，不能把可能持锁的会话还给连接池。调度循环尚未完成。

PA18a（03:12）：独立完成授权列表和有界阶段事件存储，不依赖尚未完成的调度循环。先缺类红测；`./mvnw -B -Dit.test=ProjectAgentQueryIT test-compile failsafe:integration-test failsafe:verify` **5 项通过、0 失败/错误/跳过**。每次读取在项目行锁下核对当前维护者/归属，页长至多 100，序号连续；总量 4096 项/256 KiB，保留 1 项/1 KiB 给终态。白名单字段不包括提示、源码和原始输出，损坏/过期明确报错。工具访问路径数命名 observedPaths，不冒充实际修改文件数；终态后不追加用量。阶段自动写入和真实模型用量验收仍待接通。

PA18b / PA17b 前置（03:39）：阶段和结果事件随任务状态在同一事务写入，停止证据确认后才追加结构化用量；从 QUEUED 到 NO_CHANGES 的真实受控进程旅程有完整单调记录。先运行定向测试实际失败（阶段为空），再接入。源码生命周期 phase 改为 MAX_VALUE-100，提供就绪读取，确保未来 Agent MAX_VALUE 的启停先后；先缺接口红测。`./mvnw -B '-Dit.test=ProjectAgentWorkerIT,ProjectAgentQueryIT,ProjectAgentCancellationIT,ProjectAgentRecoveryIT,ProjectSourceLifecycleIT' test-compile failsafe:integration-test failsafe:verify` **21 项通过、0 失败/错误/跳过**，含旧源码恢复/有界关闭。仍无真实模型调用或新 Agent 调度循环。
