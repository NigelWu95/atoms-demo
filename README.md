# Atoms Demo Studio

提交说明：[实现思路、完成程度与后续优先级](https://github.com/NigelWu95/atoms-demo/blob/main/docs/project-summary.md)。

2026-10-17 **生成/修复请求的整页刷新恢复已在本地实现，未推送或部署**：同标签页暂存原请求，恢复有效期 24 小时。刷新只读取状态；点击“重试原提交”才发送原 key/body，已接受时返回同一任务，未送达时仍可能首次创建并消耗额度。身份和项目归属确认后才展示正文，账号变更清理；过期、损坏或存储不可用有明确提示，放弃本地记录不取消后台任务。最多保留 10 个项目；缓存不是加密存储，请勿输入敏感资料。真实验收及边界见 [PR04 记录](https://github.com/NigelWu95/atoms-demo/blob/main/docs/design/iteration-experience/verification.md#2026-10-17pr04-整页刷新请求恢复)，新增链接需推送后可访问。

2026-10-17 **独立导出应用首屏优化已完成（本地，未推送或部署）**：连接就绪后优先展示应用画面，完整说明移到画面之后，默认收起；不折叠平台原有下载按钮。长说明在 320/768/1024/1440 px 下不会把应用推到首屏以下，展开说明不重建应用、不丢输入。独立工程前端 69 项单测、工程导出相关 57 项测试及 Chrome 外壳 18 项回归通过。已有 ZIP、JAR 和冻结构建不自动变化，新样式需重新导出或构建。此次没有调用模型；详见[SU01 验收记录](https://github.com/NigelWu95/atoms-demo/blob/main/docs/design/iteration-experience/verification.md)。

2026-10-17 **真实 DeepSeek 首次生成 + 连续三轮修改及下载工程独立运行验收通过（本地）**：同一待办项目依次增加筛选、搜索、全部完成，旧任务和完成状态保留；共 8 次真实请求，未超过本轮 12 次上限。最终 ZIP 的应用源码与平台 V4 逐字节一致；下载工程完整构建、独立 Java 进程启动、实际操作、刷新和进程重启恢复、访客隔离通过，运行时未请求平台或模型 API。导出不包含平台业务数据。这里只验证一个应用样本，不代表任意需求成功率，也不等于真实错误修复已验收；未推送或部署。详见[本轮验收记录](https://github.com/NigelWu95/atoms-demo/blob/main/docs/design/iteration-experience/verification.md#2026-10-17pr03-真实模型连续修改与下载工程验收)。

2026-10-17 **逐行差异、预览优先布局和确认式修复已在本地实现，尚未推送或部署**：修改记录显示增删行、行号与上下文，大文件回退全文；应用画面和两种直接展示的下载入口排在高级管理之前。当前共享预览报错后可准备修复建议、补充说明并确认，再复用原 MODIFY 流程；原始报错不自动发送，未提交草稿保持不变，不自动发布。前端 251 项单测、类型/规范/构建检查通过，修复流程四尺寸 Chrome/无障碍检查通过。真实错误修复效果仍待验收，不把受控响应测试当作模型效果。详见[验收记录](https://github.com/NigelWu95/atoms-demo/blob/main/docs/design/iteration-experience/verification.md)。

2026-10-16 **连续开发与工作台优化已在本地实现，尚未推送或部署**：修改时带入当前版本谱系的有效历史需求，支持基线哈希校验的局部补丁；工作台增加真实阶段、关联需求及文件前后对比；项目草稿自动保存到服务端并检测多标签冲突；项目列表支持搜索、最近编辑排序与归档恢复。旧导出按钮继续直接展示。此次没有调用真实模型或发送真实邮件，多轮模型质量仍需另行实测。设计与验证见[迭代体验计划](https://github.com/NigelWu95/atoms-demo/blob/main/docs/design/iteration-experience/plan.md)和[验收记录](https://github.com/NigelWu95/atoms-demo/blob/main/docs/design/iteration-experience/verification.md)（新增文档链接需推送后才可访问）。以下带日期的部署条目为历史记录，不代表此次改动已上线。

2026-10-16 **QQ SMTP 邮箱验证码注册/登录已推送并部署**：[账号入口](http://192.0.2.10:8443/account)。新邮箱保留当前访客项目，已有邮箱跨浏览器登录原账号，支持退出和跨标签页切换。真实发信/收信及一次验证码登录已在本地通过；云端已通过 SMTP TLS/认证、公网页面与健康检查，云端实际收信登录仍待用户复核。邮箱及模型配置通过 SSH 写入服务器私有文件，未进入仓库；模型累计上限同步为 10000，验收时已用 39、剩余 9961。当前保留用户指定的 HTTP 演示模式，流量未加密；仓库默认仍要求公网 HTTPS。见[邮箱说明](https://github.com/NigelWu95/atoms-demo/blob/main/docs/email-accounts.md)和[部署记录](https://github.com/NigelWu95/atoms-demo/blob/main/docs/cloud-deployment.md)。

Java 17 + Spring Boot + Vue 工程。已实现访客与项目持久化、代码版本与受限预览、应用数据保存，并接通 DeepSeek 网关、后台规划/构建/有限修复及普通工作台的生成与修改交互。已提交的需求、规划和任务结果会保存到数据库。**本地真实 DeepSeek 的“生成待办 → 操作保存 → 刷新恢复 → 修改生成 V2 → 筛选且保留原数据”闭环已通过**，详见下文。固定版本发布、独立应用页面和 LIVE 数据隔离已实现；云端真实 2048 生成、预览操作和保存/刷新恢复已通过，云端发布与 LIVE 闭环仍待验收。设计见 [设计目录](docs/design/README.md)，原平台进度见 [原任务清单](docs/design/tasks/todo.md)，新增独立工程与下载模块见 [模块任务清单](tasks/todo.md)。

2026-10-08 已按用户要求部署到 **<http://192.0.2.10:8443>**：单台云服务器原生运行整套平台和 PostgreSQL，按 `/a/<slug>` 发布应用。不接 ngrok、不等待域名；初始 HTTPS 已按用户明确要求切换为临时 HTTP Demo，流量未加密。HTTP 使用独立访客 Cookie，旧 HTTPS 项目不删除但不会自动归属新访客。云端已按用户授权同步 DeepSeek 密钥，累计调用上限 100 次由公网访客共用；当时配置为单次上限 **168400 tokens**（包含思考与最终输出）、单次超时 300 秒、整轮超时 600 秒，本地数据与私有配置不变。此前云端 2048 在 32768 tokens / 180 秒配置下已验证生成中刷新后 SSE 恢复、自动预览、游戏合并计分和状态刷新恢复。随后 16840 配置下的一次构建因 length 失败，其中 16266 tokens 用于推理；用户因此要求提高至 168400。本次调整不调用模型，部署后累计 6 / 100；更高额度不保证所有需求均能在超时和响应大小限制内完成。云端发布/LIVE 闭环仍待验收。见 [实际部署记录与回退](docs/cloud-deployment.md)、[ADR-011](docs/decisions/011-single-server-release.md) 和 [服务器部署说明](deploy/server/README.md)。数据库和后端内部端口不直接开放公网。

2026-10-11 已更新到源码 `ca4dac8` 对应发布包，首页/列表状态修复与历史代码恢复已上线；原有项目、版本、业务数据和模型配置保留。此次升级不调用模型，切换前后累计记账均为 18 / 100。公网基础交互与接口验收已通过，云端有效恢复闭环仍待实测；详见[更新与回退记录](docs/cloud-deployment.md)。

2026-10-13 云端已更新为源码 `b9aae45`、发布包 `atoms-demo-<release-id>`，工程 ZIP 下载随版本上线。按用户确认同步为 **163840 tokens / 单次 300 秒 / 整轮 900 秒**，线上累计上限仍为 **100 次**，验收时已用 18、剩余 82；没有同步本地 10000 次上限。原有数据与密钥保留，V011 增量迁移完成，公网健康与独立 Chrome 基础交互检查通过。本次未调用模型；云端原生构建仍关闭、独立实例预览尚未实现。备份、验证范围和回退步骤见[部署记录](docs/cloud-deployment.md)。

## 下载自己的应用工程

2026-10-14 本地入口：选中当前 Vn → **“运行应用”**（复用或构建工程 → 启动本机进程 → 打开预览）→ 本机预览就绪 → **“下载预览工程 ZIP”**，取得正在运行的同一个 buildId 的冻结完整工程。预览未就绪、已停止或使用共享预览时，主下载不可用；不会改下其他构建或重新组装版本来代替。包内包含 Vue + Spring Boot 17 + PostgreSQL 源码及配置、构建和启动说明，成品不依赖平台或模型 Key。

历史版本/无需构建的“导出版本源码 ZIP”直接展示，不折叠。它使用当前模板重新组装，不承诺与运行预览相同。云端 10 月 13 日的旧入口仍名为“下载工程 ZIP”，此次改动未部署。

只携带所选源码与初始数据，不携带平台会话、对话、PREVIEW/LIVE 真实数据或密钥。生成业务代码仍受 HTML/CSS/JS 与 SDK 契约约束。平台支持固定工程的本机工作区、异步构建；2026-10-14 本地新增独立实例启停和对应 iframe 预览。详见[工程下载说明](https://github.com/NigelWu95/atoms-demo/blob/main/docs/project-export.md)与[能力地图](https://github.com/NigelWu95/atoms-demo/blob/main/CAPABILITY-MAP.md)。云端仍为 10 月 13 日版本，未更新此次实例功能；新代码及文档未推送 GitHub。云端未开启本机构建，按版本下载 ZIP 不依赖此开关。

## 在平台本机构建工程

选择已保存的 Vn → “构建 Vn 工程” → 查看持久化阶段和结果 → “下载本次构建工程 ZIP”。刷新页面不会重新提交；网络结果未知时使用“确认上次构建请求”，沿用原操作标识。取消会等待自有工具进程退出，不把请求已发出冒充已取消。当前和历史构建都有对应的冻结源码包；构建记录下载只代表那条构建，需要预览同款请使用主入口“下载预览工程 ZIP”。

此功能默认关闭，只允许本机回环开发模式。需要 Node 24.20.x / npm 11.19.x / Java 17、网络依赖下载，以及已准备好的**独立工程专用测试库**，不能填写平台数据库或应用运行库。示例在项目根执行，路径按本机调整；不要覆盖已有私有配置：

```sh
# 只在缺失时建立新目录；已有目录需要确认归属和 0700 权限。
test -d .local/workspace-build || mkdir -m 700 .local/workspace-build
export WORKSPACE_BUILD_ENABLED=true
export WORKSPACE_BUILD_ROOT="$PWD/.local/workspace-build"
export WORKSPACE_BUILD_NODE="/Users/wubingheng/.nvm/versions/node/v24.20.0/bin/node"
export WORKSPACE_BUILD_JAVA="/Users/wubingheng/.sdkman/candidates/java/17.0.5-oracle/bin/java"
export WORKSPACE_BUILD_TEST_CONFIG="$PWD/.local/standalone/test.properties"
cd backend
./mvnw -B spring-boot:run
```

模板测试配置的准备沿用[独立工程数据库说明](https://github.com/NigelWu95/atoms-demo/blob/main/templates/standalone-v1/README.md)。同一测试库必须串行独占，不同时手工运行其他独立工程构建。配置不能来自网页；现有 `.env.local` 不会自动改写。V011 是增量迁移，已在专用平台测试库验证，并于 2026-10-13 应用到云端；迁移完成不等于启用本机构建。

初始预算：一个执行槽、最多四个活动任务、每项目一个活动任务；单次构建十分钟、停止宽限五秒；最多 32 个任务目录、工作根软件预算 8 GiB、保留至少 2 GiB 空间（建议开始前留出 6 GiB 以上余量）。达到门槛会明确失败，不跳测试、不自动删旧目录，也不代表操作系统级资源隔离。

- `BUILD_DISABLED` / `BUILD_ENVIRONMENT_NOT_READY`：检查显式开关、工具版本、回环 profile、私有目录与测试库身份，修正后重启平台。
- `BUILD_CAPACITY_EXCEEDED`：检查磁盘/目录数量；先停止构建再由维护者处理已确认的缓存，平台不提供自动清理。
- `BUILD_INPUT_CHANGED` / `BUILD_ARTIFACT_UNAVAILABLE`：来源或产物完整性失败；旧记录保留，不用当前模板替换历史归档。
- `INTERRUPTED`：重启不重跑旧任务；若不能核验匹配的进程退出证明，执行槽继续保留。不要手工清空数据库占位或按旧 PID 查杀；保留原工作根，核实原因后再处理。关闭开关仍可查询记录，但冻结包下载也需要原工作根和有效执行配置。

**手动构建完成不等于已启动应用。** 可以直接点击“运行应用”串联整个流程，也可手动构建后点击“启动应用”。本机新进程就绪后显示该 JAR 的真实页面，保存到专用运行库。停止后再次启动保留数据，主下载指向相同 buildId 的冻结 ZIP，只带初始数据。原共享预览和 `/a/:slug` 发布保持原状，不冒充本机运行预览。进程启动需显式启用并配置专用运行库池，不会给平台账号数据库管理权限；默认关闭，未自动改写日常开发配置或部署云端。新增日常专用库的显式准备命令与当前启用阻塞见[本机运行说明](https://github.com/NigelWu95/atoms-demo/blob/main/docs/app-runtime.md)，证据见[验收记录](https://github.com/NigelWu95/atoms-demo/blob/main/docs/app-runtime-verification.md)。

## 打包到服务器

本机准备好开发依赖与测试数据库后，在项目根执行 `node scripts/release.mjs`。脚本运行检查与构建，输出 `dist/releases/atoms-demo-*.tar.gz` 和 SHA-256 校验文件；服务器不需要 Node、Maven 或 Docker。真实密码、API Key、数据库数据与测试夹具不会进入包。服务器配置另存 `/etc/atoms-demo/application.properties`，首次初始化/启动/更新/回退步骤见[部署说明](deploy/server/README.md)。脚本不会上传、部署或调用模型；远程 TLS、systemd 与外网访问仍须单独验收。

本机发布包验收：`node scripts/smoke-release.mjs /绝对路径/解包目录`。需要本地 Docker Desktop、Chrome、OpenSSL、测试数据库，以及脚本锁定 digest 的官方 Nginx 镜像（可通过 `docker pull public.ecr.aws/docker/library/nginx@sha256:0985e772fb9f729e6fa0980da05fca5d9c468e870eed43071545afa9d2e27d94` 准备）。它在回环 8082/18443 启动真实 JAR 和一次性 HTTPS 代理，执行 `frontend/e2e-release`，结束后停服务、清理临时凭据和测试私钥；截图在 `frontend/test-results-release`。这只是 Docker Desktop 上的本地验证工具，不是服务器对 Docker 的依赖；其他容器环境的 host gateway 需另行适配。测试证书只用于此流程，不放入发布包，不用于公网。

## 开发环境与启动

- Java：用户指定 `17.0.5-oracle`；Maven Wrapper 固定 3.9.11，Spring Boot 固定 3.5.16。
- 前端：Node 24.20.0、npm 11.19.0；从项目根目录执行 `nvm use` 选择已有版本，不修改全局默认。
- 数据库：先按下文启动 Docker PostgreSQL。应用和测试凭据存放在项目根 `.env.local`（权限 600，禁止提交），键名为 `APP_DB_PASSWORD`、`TEST_DB_PASSWORD`，分别使用独立的 64 位随机十六进制密码。新环境可参照 `.env.local.example` 创建，不能直接使用占位值，更不能覆盖已有凭据。
- 首次配置应用账号：在根目录执行 `node scripts/provision-db.mjs`；需要本地 Docker 权限。它仅创建缺失的账号、测试库和 schema，不重置已有密码，也不删除数据。

在两个终端分别运行：

```sh
cd /Users/wubingheng/atoms-demo/backend
./mvnw -B spring-boot:run
```

```sh
cd /Users/wubingheng/atoms-demo
nvm use
cd frontend
npm ci
npm run dev
```

前端地址 `http://127.0.0.1:5173`；后端 `http://127.0.0.1:8080/api/health`。后端命令从 backend 目录执行，以加载 `../.env.local`。关闭对应终端服务使用 Ctrl+C；不要关闭 Docker Desktop 或删除数据库卷。

前端 `.npmrc` 禁止依赖安装脚本，npm ci 遵守此策略。不要使用忽略锁文件的安装方式或强制执行全部生命周期脚本。

## 验证命令

```sh
cd backend
./mvnw -B verify
```

该命令实际运行单元测试与集成测试（Surefire/Failsafe），需要本地数据库可达；集成测试只连接 `atoms_demo_test`，不使用业务数据库或 H2。

前端目录提供 `npm run typecheck`、`npm run test:unit`、`npm run lint`、`npm run format:check`、`npm run build` 和 `npm run test:e2e`。验收证据见 [verification.md](docs/verification.md)，环境决策见 [ADR-002](docs/decisions/002-local-java17-baseline.md)。

浏览器测试位于 `frontend/e2e`，当前配置使用本机已安装的 Google Chrome，并创建独立临时测试会话。运行前启动后端；Playwright 可复用本地前端或自动启动它。不连接个人浏览器资料，不依赖登录账号。没有 Chrome 的机器需准备对应浏览器并调整测试 channel，不能把未运行的浏览器测试当作通过。

## DeepSeek 主工作台（5173）

正常入口是 `http://127.0.0.1:5173`：描述需求 → 创建项目 → 点击“生成应用” → 查看规划与构建状态 → 自动装载新版本预览 → 继续输入需求“修改应用”。创建项目本身不调用模型。生成中保留旧版本；取消后晚到结果不会保存；刷新页面可以恢复已提交的任务及对话。

维护者在**项目根目录已有的 `.env.local` 中追加或更新下列键**，不要覆盖原数据库配置，不要把真实密钥粘贴到聊天、源码或浏览器：

```properties
DEEPSEEK_API_KEY=
DEEPSEEK_TIMEOUT_SECONDS=300
DEEPSEEK_MAX_TOKENS=163840
GENERATION_TIMEOUT_SECONDS=900
GENERATION_MAX_MODEL_CALLS=0
```

填入自己的 Key，并在确认调用预算后设置 `GENERATION_MAX_MODEL_CALLS`；随后重启普通后端使配置生效。0 表示不允许请求。上限是当前数据库自启用生成以来**累计请求次数的绝对上限**，不是每轮、每日、剩余额度或人民币金额；页面显示上限减去已预留数。首次生成/每次修改通常各需要 2 次请求，产物不合规时最多增加 1 次修复。例如首次空库设置为 3 只允许累计最多 3 次请求，后续需要显式提高上限，不通过删除记账数据重置。每次请求发出前持久预留，取消、失败或未知结果不退回，因此不会用无上限自动重试消耗额度。

端点固定 `https://api.deepseek.com/chat/completions`，模型固定 `deepseek-flash`，采用用户指定的 `thinking.enabled`、`reasoning_effort=high`、`stream=false`，额外要求 JSON 输出。密钥仅供后端读取。发送内容包括本次需求、当前源码和当前版本谱系中有界的成功历史需求，不自动发送应用的真实业务状态；思考内容不保存或展示。普通用户不能自定义端点/密钥，后续再扩展。

本地实现为单实例、1 个执行 Worker，最多 8 个活动任务；单请求默认及最长均为 300 秒，输出默认 163840、最高可配置 168400 tokens（包含思考与最终输出）。整轮时间由 `GENERATION_TIMEOUT_SECONDS` 控制，默认 900 秒、可配置 1–1800 秒；它独立于单次请求超时，作用于所有阶段及最终保存。上游响应仍最大 2 MiB。重启时旧 RUNNING 任务标记 INTERRUPTED，不自动重发；QUEUED 可继续。普通工作台已使用 SSE 显示数据库中真实提交的阶段，断线按事件游标补齐，不重新提交任务。连续三次异常连接会暂停并提供“刷新生成状态”；关闭网页不取消后台任务。这是阶段事件流，不是模型逐 token 输出，也不显示思维链。更多应用质量、完整重启演练、按身份频率限制及上线预算仍需验收。见 [ADR-006](docs/decisions/006-deepseek-generation.md) 和 [ADR-007](docs/decisions/007-persisted-run-events.md)。

真实验收现状（2026-10-07）：前一轮累计 5 次之后，用户明确追加最多 6 次，临时启动累计上限设为 11。新一轮先遇到构建未完整结束（2 次），补充结束原因脱敏诊断并要求精简完整实现后，再用 4 次完成首次生成和一次修改，未触发修复。浏览器实际新增/完成/刷新恢复待办，V2 的“仅看未完成 / 显示全部”生效，旧任务与完成状态保留；完整 live 用例通过，console error/warning 为零。当前累计已用 11 次、剩余 0，不再发起模型调用。原配置文件和单次 token 上限未改动，11 仅用于本轮启动参数；普通重启仍使用本地配置或默认 0。历史失败保留在 [验收记录](docs/verification.md)，一次闭环通过不代表任意需求的生成质量保证。

可重复真实验收位于 `frontend/e2e-live`，不会混入普通测试或自动扣费。需先明确本轮预算、在后端配置对应的累计上限，再在前端目录显式运行 `ATOMS_LIVE_ACCEPTANCE=up-to-6-requests npm run test:e2e:live`；脚本要求剩余 4–6 次，不自动重试，失败立即停下。此开关是执行确认而非服务端额度设置。截图在 `frontend/test-results/live`，trace 关闭，不保存/导出浏览器认证资料。验收使用独立访客，生成项目不属于个人日常浏览器，不能直接复制私有项目链接给另一个访客访问。

## 发布应用（目前本地访问）

在普通 `http://127.0.0.1:5173` 打开已有生成版本的项目：

1. 在当前版本预览中确认应用正常，点击“保存检查结果”，等待已保存报告为“检查通过”。
2. 点击工作台顶部“发布应用”，确认当前/上次发布版本。如果刚保存报告，点击“刷新发布状态”。
3. 勾选公开页面、代码及初始数据的确认项，点击“确认发布当前版本”。打开面板给出的 `/a/:slug` 链接。
4. 新浏览器访客会获得独立的 LIVE 数据；私有预览数据不会复制过去。修改代码后仍展示原发布版本，需再次检查并显式“更新已发布版本”。
5. “下线应用”需再次确认；下线后无法新访问/读写。重新发布沿用同一链接，并保留已保存访客数据。

结果未知时只点“重试原发布操作”，不要把刷新当作撤销。链接当前标注“本地访问链接（尚未接入公网）”，其他设备不可通过本机的 127.0.0.1 地址访问。用户生成应用的初始数据与代码会公开，请勿放入密钥或敏感资料。下线不能收回浏览器已经下载的源码。

部署到服务器后，由维护者将 `PUBLIC_APP_ORIGIN` 配置为站点 origin 并重启后端；默认只接受 HTTPS（本机开发允许 loopback HTTP），不含路径。用户明确接受明文风险的临时 Demo 可设置 `ALLOW_INSECURE_HTTP_PUBLIC_ORIGIN=true`，并按[部署说明](deploy/server/README.md)同步配置代理和 HTTP Cookie；前端只额外接受与当前 HTTP 页面同源的非回环 HTTP 链接。面板显示服务器上的应用链接；未配置时仍显示本地链接。无需新增发布 API 或启动隧道。不要直接将 5173/8080/5432/测试端口映射公网。

## 运行与持久化测试台（T10–T15）

在额外终端运行：

```sh
cd /Users/wubingheng/atoms-demo/backend
./mvnw -B spring-boot:test-run -Dspring-boot.run.main-class=dev.atoms.fixtures.FixtureApplication
```

再在另一个终端启动测试工作台：

```sh
cd /Users/wubingheng/atoms-demo
nvm use
cd frontend
npm run dev:fixtures
```

访问 `http://127.0.0.1:8081/test-lab`，选择“持久化待办（工作台）”→“装载测试产物”→“打开测试工作台”。5174 的测试工作台支持新增/完成待办、刷新恢复、保存状态提示、真实源码查看和私有独立预览。选择“待办历史只读（工作台）”可切换 V1/V2，历史版本只读初始数据，不读写当前持久状态。

这里不是 AI 生成入口。固定手工源码与待办数据写入专用 `atoms_demo_test`；不会向正常开发数据库注入测试源码，也不会出现在 5173 的个人项目列表。测试 Cookie 名为 ATOMS_TEST_SESSION，与普通本地会话分离。待办使用 Atoms.ready/MessageChannel/状态 API；计数器场景仍只在页面内计数，刷新归零。

测试台还提供交互计数器、闭合标签、隔离与资源拦截、历史计数器四个场景，可直接打开受限运行文档。安全场景预期产生 CSP 拦截提示；正常计数器和待办无控制台错误。SDK 应用应通过工作台或私有预览外壳打开，不直接打开原始 document。

装载器只编译到 test classpath，校验数据库名、角色和回环绑定，不进入正式 JAR。不要把 test-run 入口暴露到公网。普通后端仍用 spring-boot:run，二者端口不同，可同时运行。

前端目录运行 `npm run test:e2e:runtime`，会自动启动或复用 8081/5174 服务，执行 20 项独立 Chrome 验收，包含发布/下线、公开访客数据、版本恢复、项目生成状态和工程 ZIP 下载。普通 E2E 为 14 项；建议两套按顺序运行。测试位于 `frontend/e2e-runtime`，截图/trace 位于忽略提交的 `frontend/test-results-runtime`；它与普通 E2E 输出分离。trace 可能含测试会话信息，不应公开上传。测试 profile 关闭生成 Worker；不要在 5174 测试台调用模型。实现边界见 [ADR-004](docs/decisions/004-immutable-artifacts-runtime.md) 和 [ADR-005](docs/decisions/005-preview-state-bridge.md)。

## 当前可体验的交互

- 首页自动建立访客会话或恢复当前邮箱账号会话；失败可重试，未连接不能创建项目。手机端也保留会话状态与重试入口。
- 首页的三个示例只填充需求；非空输入通过按钮或 Enter 创建真实项目并进入 `/projects/:id`，Shift+Enter 换行，中文组合输入期间不提交。名称从需求前 40 字符整理而来，可在工作台重命名。
- 项目名称保存到 PostgreSQL，刷新、重新打开和重启后端仍保留。我的项目仅列出当前身份自己的数据，其他访客或账号猜测 ID 也不能打开或重命名。
- 已创建项目的需求草稿自动保存到服务端（输入停止 600 ms 后），显示保存/失败/冲突状态；刷新或同账号重新打开可读取已保存文本。草稿最大 8000 字符，多标签冲突保留当前输入，复制需要的内容后可明确重新读取。离开前仍未确认保存会提醒；首页未创建项目的输入及未知生成提交的意图键仍仅内存保存。提交成功后的需求、规划和结果另外持久化。`/workbench` 仍是未保存草稿入口。
- 修改应用优先使用精确局部补丁，最终仍保存完整代码快照；失败不会覆盖旧版本。版本修改记录展示实际基线、关联需求及变更文件的前后源码，并不证明业务功能已经测试通过。项目列表默认按最近编辑排序，可搜索名称和归档/恢复；归档不删除数据，也不停用已发布链接。
- 创建失败保留输入，同一次操作的重试复用幂等键，避免响应丢失造成重复项目。
- 有版本的工作台展示运行预览、历史版本及 view.html/styles.css/main.js/manifest.json；源码只读转义展示，空项目不伪造文件。支持桌面/手机宽度和窄屏对话/预览切换。模型未配置、次数不足或任务仍在执行时不能新建生成；生成期间后端拒绝新的发布，旧发布不受影响。
- 当前版本应用状态存入 PostgreSQL，代码升级不重置数据；两标签保存竞争返回冲突。断网或响应异常时保留输入，手动重试复用同一保存意图，不静默覆盖。未知结果时刷新会丢失内存草稿，且不能撤销已发出的请求，界面有提示。
- 独立预览 `/preview/:projectId/:versionId` 仍检查访客权限并复用受限 iframe，是私有页面而不是公开发布链接。
- 工作台与独立预览在创建 iframe 前检查 ES2022 经典脚本语法，展示 SDK 启动与运行错误；点击“保存检查结果”可持久化当前版本报告，刷新后可读。UNKNOWN/PASSED/FAILED 与生成任务状态分开，历史版本不能创建新报告。结果未知时“重试原检查报告”复用原报告/幂等键，新观察需另存；报告不是功能或安全认证，见 [ADR-009](docs/decisions/009-preview-check-reports.md)。
- 侧栏服务状态来自真实 `/api/health`；失败时可重试。未知路由有返回入口。
- Vite 开发页面有 CSP/基础安全响应头；仅开发 HMR 放行本地 WebSocket 与内联样式。`npm run preview` 使用更严格 CSP，但它只是本地构建预览，不是正式部署方案。

下一步在明确的单轮验收预算内完成云端生成/SSE/发布验收，不再接隧道；云端累计上限已随本地配置同步为 10000，本次配置变更未发起生成。活动任务重启演练和完整资源限制仍待完成。T24 要求的第二轮真实修改及 T31 多类型应用质量验证不能自动扩大预算或重置记账。用户自定义模型设置后续支持；T04/T29 未覆盖的远程验证保留待办。OAuth 暂缓；可选邮箱验证码账号已部署，访客模式仍保留。

访客身份只绑定当前浏览器 Cookie，有效期最多 7 天；清除 Cookie、更换浏览器或过期后不能找回旧访客项目。统一使用上面的 `127.0.0.1` 地址，避免与 `localhost` 形成两个浏览器空间。请勿存放敏感资料。本地配置不应用于公网；部署前还需限流/总配额、数据保留与删除策略以及完整安全验收，见 [ADR-003](docs/decisions/003-guest-project-persistence.md)。

## 当前 API

`GET /api/health` 无需身份，执行真实数据库检查。可用时返回 `200 {"status":"UP"}`；不可用时返回 `503 {"error":{"code":"SERVICE_UNAVAILABLE","message":"服务暂不可用，请稍后重试。","retryable":true},"requestId":"..."}`。不提供数据库详情、版本、密钥或堆栈；失败日志只记关联 requestId。

已接通 `GET /api/csrf`、`POST /api/session`、`GET /api/me`，以及 `/api/projects` 的创建/分页列表和 `/api/projects/{id}` 的详情/重命名。新增版本列表 `GET /api/projects/{id}/versions`、详情 `GET /api/projects/{id}/versions/{versionId}` 与受限 HTML `GET /api/projects/{id}/versions/{versionId}/document?previewMode=CURRENT|HISTORY`；均验证项目归属。生产没有提交任意源码的 HTTP 接口。

邮箱认证新增 `GET /api/auth/methods`、`POST /api/auth/email/challenges`、`POST /api/auth/email/verifications`、`DELETE /api/session`。沿用服务端 Cookie 与 CSRF，不使用浏览器存储 JWT；`/api/me` 和 `/api/session` 的字段保持不变，已注册身份返回 `isGuest:false`。具体契约见[邮箱账号规格](https://github.com/NigelWu95/atoms-demo/blob/main/SPEC-email-accounts.md)。

预览状态使用 `GET /api/projects/{id}/preview-state?versionId=...` 与 `PUT /api/projects/{id}/preview-state`，仅项目所有者可访问；新保存意图必须绑定当前版本与预期状态 revision。所有写请求都要求 CSRF，创建项目、提交生成和保存状态还要求 Idempotency-Key。单份状态上限 256 KiB，不代表已有总配额或数据清理策略。

预览报告新增 `POST /api/projects/{id}/versions/{versionId}/preview-reports`（CSRF + Idempotency-Key）与 `GET /api/projects/{id}/versions/{versionId}/preview-reports/latest`。均检查归属；新报告只写当前版本，历史可读旧报告。没有记录时返回 UNKNOWN；最多 5 条、每条 1000 字符错误，检查字段与视口严格验证。

发布新增 `GET/PUT /api/projects/{id}/publication` 和 `DELETE /api/projects/{id}/publication?expectedPublicationRevision=...`，仅所有者可管理，写入需 CSRF/幂等键。匿名 `GET /api/public/apps/{slug}`、`GET /api/public/apps/{slug}/document?versionId=...` 仅返回已发布版本；`GET/PUT /api/public/apps/{slug}/state` 要求访客会话，GET 携带 versionId，PUT 携带已加载版本和预期状态 revision。已下线返回 404，旧版本新请求返回 409。

生成新增 `GET /api/generation`（是否配置、Worker 是否启用、剩余请求数）、`POST/GET /api/projects/{id}/runs`、`GET /api/projects/{id}/messages`、`GET /api/runs/{runId}`、`GET /api/runs/{runId}/events?after=...`（SSE）及 `PUT /api/runs/{runId}/cancellation`。这些生成接口均需有效平台会话（访客或邮箱账号）；项目、消息、任务和事件必须属于当前身份。SSE 优先使用重连的 Last-Event-ID，终态已补齐返回 204，退出后停止旧会话推送而不取消任务。具体字段和边界见 [接口契约](docs/design/04-api-contracts.md)。

历史恢复：在工作台选择历史代码版本，点击“恢复此版本”并确认，服务端通过 `POST /api/projects/{id}/restorations` 复制源码为新版本（例如恢复 V1 得到 V3），保留 V2、业务数据和已发布指针，不自动发布。要求项目所有者、CSRF、幂等键、项目修订号匹配且无活动生成；结果未知时可重试原恢复操作。V010 是新增可空恢复来源列的迁移，详见 [ADR-012](docs/decisions/012-version-restoration.md)。2026-10-11 已部署云端，完整恢复流程在本地验证，云端基础接口检查通过。首页与项目列表按 `currentVersionId` 显示是否已生成，不再使用固定“尚未生成”文案；“已生成”不代表预览检查或发布通过。

## PostgreSQL

使用 [PostgreSQL 官方 Docker 镜像](https://hub.docker.com/_/postgres)，固定标签为 postgres:17.11-bookworm。不是通过 Homebrew 安装的原生后台服务；使用时需要 Docker Desktop 正在运行。

| 配置 | 值 |
|---|---|
| 地址 | 127.0.0.1 |
| 端口 | 5432 |
| 数据库 | atoms_demo |
| 管理用户名 | postgres |
| 管理密码 | 本目录 .env 文件中的 POSTGRES_PASSWORD，不在文档中展示 |
| 容器名 | atoms-demo-postgres |
| 持久卷 | atoms-demo-postgres-data |
| JDBC URL | jdbc:postgresql://127.0.0.1:5432/atoms_demo |

postgres 仅用于本地数据库管理；后端使用 `atoms_app` 访问 atoms_demo 的 atoms schema，集成测试使用 `atoms_test` 访问 atoms_demo_test 的 atoms schema。两个账号均不是超级用户，不能创建数据库或角色，不把任何数据库凭据发送给浏览器。

数据库端口只绑定本机回环地址。密码必须非空并随机生成；.env 权限设为仅当前用户可读写，已加入忽略规则。Docker 控制权限仍属于可信管理边界：能控制本机 Docker 的人可能读取容器配置。

所有命令在本目录执行：

```sh
docker compose --env-file .env -f deploy/compose.db.yaml config --quiet
docker compose --env-file .env -f deploy/compose.db.yaml up -d --wait --wait-timeout 90
docker compose --env-file .env -f deploy/compose.db.yaml ps
```

使用容器内的 psql，无需另外安装宿主机客户端：

```sh
docker compose --env-file .env -f deploy/compose.db.yaml exec postgres psql -U postgres -d atoms_demo
```

停止和重新启动，不删除数据：

```sh
docker compose --env-file .env -f deploy/compose.db.yaml stop postgres
docker compose --env-file .env -f deploy/compose.db.yaml start postgres
```

数据卷挂载到 PostgreSQL 17 的 /var/lib/postgresql/data。停止或重启容器不删除该卷；不要执行带 --volumes / -v 的 down 或删除数据卷。持久卷不是备份，正式使用前需另设备份。

官方镜像的初始化参数仅在空数据目录首次启动时生效。初始化后修改 .env 不会自动修改数据库中已有角色的密码；修改密码应同步更新数据库和本地配置，不可通过删除数据卷处理。

## 模型 API 的用途

这里的模型 API 是平台运行时依赖：用户输入需求 → Spring Boot 后端调用模型 → 生成/修改代码 → 浏览器展示生成应用。

它不是 AI 编程助手帮助开发本网站时使用的账号，也不默认与编程助手订阅或 Atoms 赠送额度互通。

首版由平台维护者在服务端配置 DeepSeek API Key 和调用上限，官方端点与模型 ID 已固定。普通使用者不需要输入自己的 Key；用户自定义模型或自带 Key 是可选后续能力，当前不实现。

普通自动测试仍使用明确替身；只有显式选择的 live 验收调用真实模型。少量真实成功案例也不等于任意应用生成质量保证。
