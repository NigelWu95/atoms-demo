# 本机应用进程与真实预览

## 使用流程

选中当前 Vn → 构建工程 → 构建成功 → **启动应用**。就绪后工作台切换到“本机运行预览”：文档来自实际 JAR，保存请求到该进程的 Spring Boot 服务与专用 PostgreSQL 库，不再使用平台 PREVIEW 数据。这是本机新进程，不是单独的云实例产品；内部 API 仍沿用 instance 命名。

停止等待自有进程退出；预览清空，不偷偷显示旧共享预览。再次启动同一项目沿用安装 ID 和数据库，保留数据。历史版本可以下载、构建及共享只读预览；需要运行时，先恢复为当前版本再构建。选择版本不会自动替换已运行实例。

主入口“下载预览工程 ZIP”只在当前版本的本机应用完成身份校验和预览握手后可用，返回该运行进程 buildId 的冻结完整工程源码，不按最新模板重组，也不取构建列表中后来选中的任务。切换项目、版本、所有者、共享预览，或停止应用、预览报错，都会取消下载资格和在途请求；回到本机预览需要重新握手。

服务端复验归档摘要，ZIP 不含 JAR、业务数据或凭据，解压后仍需配置自己的数据库并构建启动。预览与下载的功能/源码对应，不等于带走运行数据。构建记录中的“下载本次构建工程 ZIP”仅对应该条构建；折叠的“其他导出”中“导出版本源码 ZIP”按当前模板组装，两者都不能冒充当前预览下载。

旧“平台共享预览”与 `/a/<slug>` 发布保留 PREVIEW/LIVE 数据与原实现。共享预览不启用“下载预览工程 ZIP”，不能作为独立工程一致性验收。新进程预览是**所有者私有预览**，不是新的公网实例发布功能。

## 显式启用

默认关闭，仅适用于可信的回环本地环境。先按[构建说明](https://github.com/NigelWu95/atoms-demo/blob/main/README.md#在平台本机构建工程)准备工具和独立测试库，再由维护者准备专用运行库。

每份运行配置沿用[独立工程配置](https://github.com/NigelWu95/atoms-demo/blob/main/templates/standalone-v1/config/application.properties.example)：`STANDALONE_DB_URL/USER/PASSWORD`、`APP_INSTANCE_ID`、`server.address/port`、`standalone.origin`、`standalone.test-database`。只允许回环地址、非测试库；不同配置的数据库、角色、安装 ID 和端口不能重复。数据库账号不能有管理员/建库/建角色权限，准备 SQL 见[工程 README](https://github.com/NigelWu95/atoms-demo/blob/main/templates/standalone-v1/README.md)。

配置文件 0600，专用工作根预先创建为 0700；运行根与构建根不能相同或互相包含。保留有效的 `WORKSPACE_BUILD_*` 配置，并在 backend 启动前设置：

```sh
export APP_RUNTIME_ENABLED=true
export APP_RUNTIME_ROOT=/absolute/private/runtime-workspaces
export APP_RUNTIME_CONFIGS=/absolute/private/app-a.properties,/absolute/private/app-b.properties
./mvnw -B spring-boot:run
```

池接受 1–16 份配置，首次分配后永久绑定项目；停止不回收给其他项目。全局最多同时运行 2 个实例、每项目 1 个。池耗尽需要维护者补充，不会使用管理员权限自动建库。

不要填写平台库、构建测试库或另一项目的运行库，不要提交凭据。仓库 `.local/standalone/runtime-a.properties` / `runtime-b.properties` 已绑定隔离测试项目，**不能直接拿它们启用日常平台**。本轮不改写 `.env.local`，不自动重启原 8080 服务、不推送 GitHub、不更新云端。

## 错误与资源边界

- `RUNTIME_DISABLED` / `RUNTIME_ENVIRONMENT_NOT_READY`：检查开关、工具、目录、配置后重启平台。
- `RUNTIME_DATABASE_POOL_FULL`：运行库都已绑定项目。补充新库，不能直接复用他人数据。
- `RUNTIME_ALREADY_ACTIVE` / `RUNTIME_CAPACITY_EXCEEDED`：先停止已有实例，未知退出也占配额。
- `RUNTIME_START_FAILED` / `RUNTIME_START_TIMEOUT`：检查端口、产物、数据库权限/身份。旧构建不支持执行身份时重新构建；不会因端口冲突杀其他进程。
- `INTERRUPTED`：平台重启不自动重跑实例。仅匹配的退出证明可自动解除占位；整组强杀等无法确认的情况保留占位，需维护者核查。
- 写操作不自动重发；未知结果使用原意图确认，冲突在应用内读取最新状态，不把超时当作回滚。

启动限时 90 秒，Java 堆 256 MiB，停止宽限 5 秒。工作根最多 32 个执行目录、软件预算 8 GiB、至少留 2 GiB 空闲；不自动删除缓存或业务数据。目录/进程/端口不是 OS 沙箱，不支持任意后端代码、依赖、终端或不可信公网多租户执行。

## API 与安全

所有接口校验项目所有者，写接口还要求 CSRF 和幂等标识：

- `GET/POST /api/projects/{p}/instances`：最近 20 项，以 `{buildId}` 启动。
- `GET …/instances/{id}`、`POST …/instances/{id}/stops`：状态、以 `{}` 请求停止。
- `GET …/instances/{id}/{app,document,state}`、`PUT …/instances/{id}/state`：固定能力访问，不接受目标 URL/端口。

平台 Cookie 不传给实例，实例 Cookie 留在服务端；访问核验执行身份，不跟随重定向。iframe 保持 opaque sandbox，只有成功受控 HTML 可嵌入。请求/响应大小与等待时间有界。

取舍见[实例规格](https://github.com/NigelWu95/atoms-demo/blob/main/SPEC-app-runtime.md)、[访问规格](https://github.com/NigelWu95/atoms-demo/blob/main/SPEC-runtime-access.md)及[ADR-013](https://github.com/NigelWu95/atoms-demo/blob/main/docs/decisions/013-native-process-engineering-preview.md)。实际结果见[验收记录](https://github.com/NigelWu95/atoms-demo/blob/main/docs/app-runtime-verification.md)。
