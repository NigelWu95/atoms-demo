# Atoms Demo Studio

Java 17 + Spring Boot + Vue 工程。已实现访客与项目持久化、代码版本与受限预览、应用数据保存，并接通 DeepSeek 网关、后台规划/构建/有限修复及普通工作台的生成与修改交互。已提交的需求、规划和任务结果会保存到数据库。**本地真实 DeepSeek 的“生成待办 → 操作保存 → 刷新恢复 → 修改生成 V2 → 筛选且保留原数据”闭环已通过**，详见下文。固定版本发布、独立应用页面和 LIVE 数据隔离已实现；云端网站已部署，但模型生成尚未启用。设计见 [设计目录](docs/design/README.md)，唯一进度清单见 [tasks/todo.md](docs/design/tasks/todo.md)。

2026-10-08 已按用户要求部署到 **<http://192.0.2.10:8443>**：单台云服务器原生运行整套平台和 PostgreSQL，按 `/a/<slug>` 发布应用。不接 ngrok、不等待域名；初始 HTTPS 已按用户明确要求切换为临时 HTTP Demo，流量未加密。HTTP 使用独立访客 Cookie，旧 HTTPS 项目不删除但不会自动归属新访客。云端已按用户授权同步 DeepSeek 密钥并验证模型列表认证，调用上限仍为 0，待确认额度后才能生成；本地数据与配置不变。公网 HTTP 健康检查、浏览器建项目/刷新/隔离通过，生成/SSE/发布的云端闭环仍待验收。见 [实际部署记录与回退](docs/cloud-deployment.md)、[ADR-011](docs/decisions/011-single-server-release.md) 和 [服务器部署说明](deploy/server/README.md)。数据库和后端内部端口不直接开放公网。

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
DEEPSEEK_TIMEOUT_SECONDS=120
DEEPSEEK_MAX_TOKENS=16384
GENERATION_MAX_MODEL_CALLS=0
```

填入自己的 Key，并在确认调用预算后设置 `GENERATION_MAX_MODEL_CALLS`；随后重启普通后端使配置生效。0 表示不允许请求。上限是当前数据库自启用生成以来**累计请求次数的绝对上限**，不是每轮、每日、剩余额度或人民币金额；页面显示上限减去已预留数。首次生成/每次修改通常各需要 2 次请求，产物不合规时最多增加 1 次修复。例如首次空库设置为 3 只允许累计最多 3 次请求，后续需要显式提高上限，不通过删除记账数据重置。每次请求发出前持久预留，取消、失败或未知结果不退回，因此不会用无上限自动重试消耗额度。

端点固定 `https://api.deepseek.com/chat/completions`，模型固定 `deepseek-flash`，采用用户指定的 `thinking.enabled`、`reasoning_effort=high`、`stream=false`，额外要求 JSON 输出。密钥仅供后端读取。发送内容包括本次需求和当前源码，不自动发送应用的真实业务状态；思考内容不保存或展示。普通用户不能自定义端点/密钥，后续再扩展。

本地实现为单实例、1 个执行 Worker，最多 8 个活动任务；单请求默认 120 秒、最长 180 秒，整轮最长 6 分钟，上游响应最大 2 MiB。重启时旧 RUNNING 任务标记 INTERRUPTED，不自动重发；QUEUED 可继续。普通工作台已使用 SSE 显示数据库中真实提交的阶段，断线按事件游标补齐，不重新提交任务。连续三次异常连接会暂停并提供“刷新生成状态”；关闭网页不取消后台任务。这是阶段事件流，不是模型逐 token 输出，也不显示思维链。更多应用质量、完整重启演练、按身份频率限制及上线预算仍需验收。见 [ADR-006](docs/decisions/006-deepseek-generation.md) 和 [ADR-007](docs/decisions/007-persisted-run-events.md)。

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
./mvnw -B spring-boot:test-run
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

前端目录运行 `npm run test:e2e:runtime`，会自动启动或复用 8081/5174 服务，执行 15 项独立 Chrome 验收，包含发布/下线和公开访客数据。普通 E2E 为 14 项；建议两套按顺序运行。测试位于 `frontend/e2e-runtime`，截图/trace 位于忽略提交的 `frontend/test-results-runtime`；它与普通 E2E 输出分离。trace 可能含测试会话信息，不应公开上传。测试 profile 关闭生成 Worker；不要在 5174 测试台调用模型。实现边界见 [ADR-004](docs/decisions/004-immutable-artifacts-runtime.md) 和 [ADR-005](docs/decisions/005-preview-state-bridge.md)。

## 当前可体验的交互

- 首页自动建立/恢复访客会话；失败可重试，未连接不能创建项目。手机端也保留会话状态与重试入口。
- 首页的三个示例只填充需求；非空输入通过按钮或 Enter 创建真实项目并进入 `/projects/:id`，Shift+Enter 换行，中文组合输入期间不提交。名称从需求前 40 字符整理而来，可在工作台重命名。
- 项目名称保存到 PostgreSQL，刷新、重新打开和重启后端仍保留。我的项目仅列出当前访客自己的数据，其他访客猜测 ID 也不能打开或重命名。
- 未提交的需求草稿及未知提交的意图键仅在当前页面会话保留；提交成功后需求、规划和任务结果持久化。刷新可能丢弃未提交草稿，但不删除项目/对话；先查看恢复的任务再决定下一轮操作。`/workbench` 仍是未保存草稿入口。
- 创建失败保留输入，同一次操作的重试复用幂等键，避免响应丢失造成重复项目。
- 有版本的工作台展示运行预览、历史版本及 view.html/styles.css/main.js/manifest.json；源码只读转义展示，空项目不伪造文件。支持桌面/手机宽度和窄屏对话/预览切换。模型未配置、次数不足或任务仍在执行时不能新建生成；生成期间后端拒绝新的发布，旧发布不受影响。
- 当前版本应用状态存入 PostgreSQL，代码升级不重置数据；两标签保存竞争返回冲突。断网或响应异常时保留输入，手动重试复用同一保存意图，不静默覆盖。未知结果时刷新会丢失内存草稿，且不能撤销已发出的请求，界面有提示。
- 独立预览 `/preview/:projectId/:versionId` 仍检查访客权限并复用受限 iframe，是私有页面而不是公开发布链接。
- 工作台与独立预览在创建 iframe 前检查 ES2022 经典脚本语法，展示 SDK 启动与运行错误；点击“保存检查结果”可持久化当前版本报告，刷新后可读。UNKNOWN/PASSED/FAILED 与生成任务状态分开，历史版本不能创建新报告。结果未知时“重试原检查报告”复用原报告/幂等键，新观察需另存；报告不是功能或安全认证，见 [ADR-009](docs/decisions/009-preview-check-reports.md)。
- 侧栏服务状态来自真实 `/api/health`；失败时可重试。未知路由有返回入口。
- Vite 开发页面有 CSP/基础安全响应头；仅开发 HMR 放行本地 WebSocket 与内联样式。`npm run preview` 使用更严格 CSP，但它只是本地构建预览，不是正式部署方案。

下一步在确认云端调用预算后完成生成/SSE/发布验收（密钥已同步），不再接隧道；活动任务重启演练和完整资源限制仍待完成。T24 要求的第二轮真实修改及 T31 多类型应用质量验证仍需单独预算。用户自定义模型设置后续支持；T04/T29 未覆盖的远程验证保留待办。OAuth 暂不接入，继续采用访客模式。

访客身份只绑定当前浏览器 Cookie，有效期最多 7 天；清除 Cookie、更换浏览器或过期后不能找回旧访客项目。统一使用上面的 `127.0.0.1` 地址，避免与 `localhost` 形成两个浏览器空间。请勿存放敏感资料。本地配置不应用于公网；部署前还需限流/总配额、数据保留与删除策略以及完整安全验收，见 [ADR-003](docs/decisions/003-guest-project-persistence.md)。

## 当前 API

`GET /api/health` 无需身份，执行真实数据库检查。可用时返回 `200 {"status":"UP"}`；不可用时返回 `503 {"error":{"code":"SERVICE_UNAVAILABLE","message":"服务暂不可用，请稍后重试。","retryable":true},"requestId":"..."}`。不提供数据库详情、版本、密钥或堆栈；失败日志只记关联 requestId。

已接通 `GET /api/csrf`、`POST /api/session`、`GET /api/me`，以及 `/api/projects` 的创建/分页列表和 `/api/projects/{id}` 的详情/重命名。新增版本列表 `GET /api/projects/{id}/versions`、详情 `GET /api/projects/{id}/versions/{versionId}` 与受限 HTML `GET /api/projects/{id}/versions/{versionId}/document?previewMode=CURRENT|HISTORY`；均验证项目归属。生产没有提交任意源码的 HTTP 接口。

预览状态使用 `GET /api/projects/{id}/preview-state?versionId=...` 与 `PUT /api/projects/{id}/preview-state`，仅项目所有者可访问；新保存意图必须绑定当前版本与预期状态 revision。所有写请求都要求 CSRF，创建项目、提交生成和保存状态还要求 Idempotency-Key。单份状态上限 256 KiB，不代表已有总配额或数据清理策略。

预览报告新增 `POST /api/projects/{id}/versions/{versionId}/preview-reports`（CSRF + Idempotency-Key）与 `GET /api/projects/{id}/versions/{versionId}/preview-reports/latest`。均检查归属；新报告只写当前版本，历史可读旧报告。没有记录时返回 UNKNOWN；最多 5 条、每条 1000 字符错误，检查字段与视口严格验证。

发布新增 `GET/PUT /api/projects/{id}/publication` 和 `DELETE /api/projects/{id}/publication?expectedPublicationRevision=...`，仅所有者可管理，写入需 CSRF/幂等键。匿名 `GET /api/public/apps/{slug}`、`GET /api/public/apps/{slug}/document?versionId=...` 仅返回已发布版本；`GET/PUT /api/public/apps/{slug}/state` 要求访客会话，GET 携带 versionId，PUT 携带已加载版本和预期状态 revision。已下线返回 404，旧版本新请求返回 409。

生成新增 `GET /api/generation`（是否配置、Worker 是否启用、剩余请求数）、`POST/GET /api/projects/{id}/runs`、`GET /api/projects/{id}/messages`、`GET /api/runs/{runId}`、`GET /api/runs/{runId}/events?after=...`（SSE）及 `PUT /api/runs/{runId}/cancellation`。这些生成接口均需访客身份；项目、消息、任务和事件必须属于当前访客。SSE 优先使用重连的 Last-Event-ID，终态已补齐返回 204。具体字段和边界见 [接口契约](docs/design/04-api-contracts.md)。版本恢复等扩展端点仍未实现。

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
