# Atoms Demo Studio

Java 17 + Spring Boot + Vue 的本地开发工程。已实现访客与项目持久化、代码版本与受限预览、应用数据保存，并接通 DeepSeek 网关、后台规划/构建/有限修复及普通工作台的生成与修改交互。已提交的需求、规划和任务结果会保存到数据库。**真实 DeepSeek 的“生成待办 → 操作保存 → 刷新恢复 → 修改生成 V2 → 筛选且保留原数据”闭环已通过**，详见下文。发布与在线链接尚未实现。设计见 [设计目录](docs/design/README.md)，唯一进度清单见 [tasks/todo.md](docs/design/tasks/todo.md)。

本阶段仅本地开发，不配置云服务器或公开域名。挑战最终仍需在线链接，后续交付阶段再安排。

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

本地实现为单实例、1 个执行 Worker，最多 8 个活动任务；单请求默认 120 秒、最长 180 秒，整轮最长 6 分钟，上游响应最大 2 MiB。重启时旧 RUNNING 任务标记 INTERRUPTED，不自动重发；QUEUED 可继续。页面每 1.5 秒读取活动任务，网络失败停止自动刷新并给出重试入口；当前是阶段轮询，不是 SSE/token 流。更多应用的生成质量、SSE、按身份频率限制及完整上线预算仍需验收。细节见 [ADR-006](docs/decisions/006-deepseek-generation.md)。

真实验收现状（2026-10-07）：前一轮累计 5 次之后，用户明确追加最多 6 次，临时启动累计上限设为 11。新一轮先遇到构建未完整结束（2 次），补充结束原因脱敏诊断并要求精简完整实现后，再用 4 次完成首次生成和一次修改，未触发修复。浏览器实际新增/完成/刷新恢复待办，V2 的“仅看未完成 / 显示全部”生效，旧任务与完成状态保留；完整 live 用例通过，console error/warning 为零。当前累计已用 11 次、剩余 0，不再发起模型调用。原配置文件和单次 token 上限未改动，11 仅用于本轮启动参数；普通重启仍使用本地配置或默认 0。历史失败保留在 [验收记录](docs/verification.md)，一次闭环通过不代表任意需求的生成质量保证。

可重复真实验收位于 `frontend/e2e-live`，不会混入普通测试或自动扣费。需先明确本轮预算、在后端配置对应的累计上限，再在前端目录显式运行 `ATOMS_LIVE_ACCEPTANCE=up-to-6-requests npm run test:e2e:live`；脚本要求剩余 4–6 次，不自动重试，失败立即停下。此开关是执行确认而非服务端额度设置。截图在 `frontend/test-results/live`，trace 关闭，不保存/导出浏览器认证资料。验收使用独立访客，生成项目不属于个人日常浏览器，不能直接复制私有项目链接给另一个访客访问。

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

前端目录运行 `npm run test:e2e:runtime`，会自动启动或复用 8081/5174 服务，执行 8 项独立 Chrome 验收。普通 E2E 增至 13 项；建议两套按顺序运行。测试位于 `frontend/e2e-runtime`，截图/trace 位于忽略提交的 `frontend/test-results-runtime`；它与普通 E2E 输出分离。trace 可能含测试会话信息，不应公开上传。测试 profile 关闭生成 Worker；不要在 5174 测试台调用模型。实现边界见 [ADR-004](docs/decisions/004-immutable-artifacts-runtime.md) 和 [ADR-005](docs/decisions/005-preview-state-bridge.md)。

## 当前可体验的交互

- 首页自动建立/恢复访客会话；失败可重试，未连接不能创建项目。手机端也保留会话状态与重试入口。
- 首页的三个示例只填充需求；非空输入通过按钮或 Enter 创建真实项目并进入 `/projects/:id`，Shift+Enter 换行，中文组合输入期间不提交。名称从需求前 40 字符整理而来，可在工作台重命名。
- 项目名称保存到 PostgreSQL，刷新、重新打开和重启后端仍保留。我的项目仅列出当前访客自己的数据，其他访客猜测 ID 也不能打开或重命名。
- 未提交的需求草稿及未知提交的意图键仅在当前页面会话保留；提交成功后需求、规划和任务结果持久化。刷新可能丢弃未提交草稿，但不删除项目/对话；先查看恢复的任务再决定下一轮操作。`/workbench` 仍是未保存草稿入口。
- 创建失败保留输入，同一次操作的重试复用幂等键，避免响应丢失造成重复项目。
- 有版本的工作台展示运行预览、历史版本及 view.html/styles.css/main.js/manifest.json；源码只读转义展示，空项目不伪造文件。支持桌面/手机宽度和窄屏对话/预览切换。模型未配置、次数不足或任务仍在执行时不能新建生成；发布按钮仍禁用。
- 当前版本应用状态存入 PostgreSQL，代码升级不重置数据；两标签保存竞争返回冲突。断网或响应异常时保留输入，手动重试复用同一保存意图，不静默覆盖。未知结果时刷新会丢失内存草稿，且不能撤销已发出的请求，界面有提示。
- 独立预览 `/preview/:projectId/:versionId` 仍检查访客权限并复用受限 iframe，是私有页面而不是公开发布链接。
- 侧栏服务状态来自真实 `/api/health`；失败时可重试。未知路由有返回入口。
- Vite 开发页面有 CSP/基础安全响应头；仅开发 HMR 放行本地 WebSocket 与内联样式。`npm run preview` 使用更严格 CSP，但它只是本地构建预览，不是正式部署方案。

下一步推进 SSE、资源限制与发布；T24 要求的第二轮真实修改及 T31 多类型应用质量验证仍需单独预算。用户自定义模型设置后续支持；T04 的部署骨架和公网验证保留待办。OAuth 暂不接入，继续采用访客模式。

访客身份只绑定当前浏览器 Cookie，有效期最多 7 天；清除 Cookie、更换浏览器或过期后不能找回旧访客项目。统一使用上面的 `127.0.0.1` 地址，避免与 `localhost` 形成两个浏览器空间。请勿存放敏感资料。本地配置不应用于公网；部署前还需限流/总配额、数据保留与删除策略以及完整安全验收，见 [ADR-003](docs/decisions/003-guest-project-persistence.md)。

## 当前 API

`GET /api/health` 无需身份，执行真实数据库检查。可用时返回 `200 {"status":"UP"}`；不可用时返回 `503 {"error":{"code":"SERVICE_UNAVAILABLE","message":"服务暂不可用，请稍后重试。","retryable":true},"requestId":"..."}`。不提供数据库详情、版本、密钥或堆栈；失败日志只记关联 requestId。

已接通 `GET /api/csrf`、`POST /api/session`、`GET /api/me`，以及 `/api/projects` 的创建/分页列表和 `/api/projects/{id}` 的详情/重命名。新增版本列表 `GET /api/projects/{id}/versions`、详情 `GET /api/projects/{id}/versions/{versionId}` 与受限 HTML `GET /api/projects/{id}/versions/{versionId}/document?previewMode=CURRENT|HISTORY`；均验证项目归属。生产没有提交任意源码的 HTTP 接口。

预览状态使用 `GET /api/projects/{id}/preview-state?versionId=...` 与 `PUT /api/projects/{id}/preview-state`，仅项目所有者可访问；新保存意图必须绑定当前版本与预期状态 revision。所有写请求都要求 CSRF，创建项目、提交生成和保存状态还要求 Idempotency-Key。单份状态上限 256 KiB，不代表已有总配额或数据清理策略。

生成新增 `GET /api/generation`（是否配置、Worker 是否启用、剩余请求数）、`POST/GET /api/projects/{id}/runs`、`GET /api/projects/{id}/messages`、`GET /api/runs/{runId}`、`PUT /api/runs/{runId}/cancellation`。除健康检查外均需访客身份；项目、消息、任务必须属于当前访客。具体字段和当前边界见 [接口契约](docs/design/04-api-contracts.md)。SSE、发布、恢复等其他设计端点仍未实现。

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
