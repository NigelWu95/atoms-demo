# Atoms Demo Studio

Java 17 + Spring Boot + Vue 的本地开发工程。已实现基础网站、真实访客会话和项目持久化：可创建、打开、重命名和分页浏览自己的项目。当前不是完整生成平台；真实模型生成、预览运行和发布尚未实现。功能设计和执行计划位于 [设计目录](docs/design/README.md)，唯一进度清单位于 [tasks/todo.md](docs/design/tasks/todo.md)。

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

## 当前可体验的交互

- 首页自动建立/恢复访客会话；失败可重试，未连接不能创建项目。手机端也保留会话状态与重试入口。
- 首页的三个示例只填充需求；非空输入通过按钮或 Enter 创建真实项目并进入 `/projects/:id`，Shift+Enter 换行，中文组合输入期间不提交。名称从需求前 40 字符整理而来，可在工作台重命名。
- 项目名称保存到 PostgreSQL，刷新、重新打开和重启后端仍保留。我的项目仅列出当前访客自己的数据，其他访客猜测 ID 也不能打开或重命名。
- 原始需求及本次意图标识在页面路由切换中保留，尚未作为聊天消息落库；刷新会清空需求草稿，但不删除项目。`/workbench` 仍可作为未保存草稿入口。
- 创建失败保留输入，同一次操作的重试复用幂等键，避免响应丢失造成重复项目。
- 工作台支持预览/代码空态、桌面/手机宽度和窄屏对话/预览切换；生成和发布按钮明确禁用。
- 侧栏服务状态来自真实 `/api/health`；失败时可重试。未知路由有返回入口。
- Vite 开发页面有 CSP/基础安全响应头；仅开发 HMR 放行本地 WebSocket 与内联样式。`npm run preview` 使用更严格 CSP，但它只是本地构建预览，不是正式部署方案。

下一步是 T10 起的产物版本、受限运行文档和交互预览。T04 的部署骨架和公网验证保留待办；模型 API 与用户自定义模型设置均未实现。

访客身份只绑定当前浏览器 Cookie，有效期最多 7 天；清除 Cookie、更换浏览器或过期后不能找回旧访客项目。统一使用上面的 `127.0.0.1` 地址，避免与 `localhost` 形成两个浏览器空间。请勿存放敏感资料。本地配置不应用于公网；部署前还需限流/总配额、数据保留与删除策略以及完整安全验收，见 [ADR-003](docs/decisions/003-guest-project-persistence.md)。

## 当前 API

`GET /api/health` 无需身份，执行真实数据库检查。可用时返回 `200 {"status":"UP"}`；不可用时返回 `503 {"error":{"code":"SERVICE_UNAVAILABLE","message":"服务暂不可用，请稍后重试。","retryable":true},"requestId":"..."}`。不提供数据库详情、版本、密钥或堆栈；失败日志只记关联 requestId。

已接通 `GET /api/csrf`、`POST /api/session`、`GET /api/me`，以及 `/api/projects` 的创建/分页列表和 `/api/projects/{id}` 的详情/重命名。所有写请求都要求 CSRF，创建项目还要求 Idempotency-Key。具体约束见 [接口契约](docs/design/04-api-contracts.md#21-身份项目生成)。其他设计端点仍未实现。

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

首版由平台维护者在服务端配置模型端点、模型 ID、API Key 和预算。普通使用者不需要输入自己的 Key；用户自定义模型或自带 Key 是可选后续能力，当前不实现。

本次没有配置或调用任何付费模型。API 未就绪时可以开发基础页面、项目与数据库流程，但不能把测试产物冒充真实 AI 生成。
