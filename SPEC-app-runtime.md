# app-runtime · 本机独立实例

状态：2026-10-13，用户要求一次完成独立运行与真实预览；在既有能力地图上连续实施、验证，不自动推送或部署。Java 17 / Spring Boot / PostgreSQL，不增加依赖。

## 目标与边界

从成功的不可变 buildId 启动实际 JAR，每项目最多一个活动实例，最多两个同时运行。实例状态、安装绑定持久化。停止、平台重启、切换构建不删除业务数据；不执行模型生成后端/命令，不开放任意端口、目录或 URL 输入。

运行库来自维护者预备的专用配置池，首次使用即永久绑定项目，不回收给其他项目。平台不持有数据库管理员凭据，不自动 CREATE/DROP DATABASE。安装 ID、数据库、角色和端口不得重复；缺少配置或池耗尽明确拒绝。沿用独立模板启动前的真实数据库权限/用途/安装身份校验。

## 生命周期与契约

- 新增 V012：运行安装映射与实例记录，只增表，不修改已有业务表。状态 QUEUED → STARTING → RUNNING → STOPPING → STOPPED；失败 FAILED，中断 INTERRUPTED。未知退出保留占位，不按旧 PID 杀进程。
- POST `/api/projects/{projectId}/instances`：仅 `{buildId}`，CSRF + Idempotency-Key，202 `{instanceId}`。先授权，再检查成功构建及运行环境；相同意图重放不重复启动。
- GET 同一路径：项目最新 20 条实例，响应 `{items}`，历史列表有界。GET `/{instanceId}` 返回详情。
- POST `/{instanceId}/stops`：仅 `{}`，CSRF + 幂等；202，确认退出后才报告 STOPPED。重启使用新的实例记录、相同项目安装与运行库。
- 对外描述仅 id/projectId/buildId/versionId/status/loadedRuntimeId/errorCode/createdAt/startedAt/finishedAt；不含 PID、端口、目录、数据库密码或 Cookie。

## 执行与安全

默认关闭，只允许回环 local/fixtures/test。显式 APP_RUNTIME_ENABLED、APP_RUNTIME_ROOT、APP_RUNTIME_CONFIGS（逗号分隔绝对配置路径）；Java/Node 复用已验证的构建工具。专用 0700 工作根与 0600 文件，源 JAR SHA-256 校验后复制，不在构建目录运行。最多保留 32 次执行目录，达到上限拒绝，不自动清理。

监督器使用现有可信进程组 keeper，stdin 丢失即停止自有子进程。退出证明按实例 ID 保存；平台强制退出后，只有验证过的退出证明才能解除占位。数据库 advisory lock 与目录文件锁保证单调度器。Java 堆 256 MiB；启动限时 90 秒、停止宽限 5 秒；这不是 OS 级资源沙箱。

模板新增可选 RUNTIME_EXECUTION_ID，仅在受控配置中指定并通过健康响应头确认本次执行，避免其他监听者的 200 被误认为成功。旧构建没有该契约时须重新构建，不冒充就绪。

## 实现位置、风格与验证

`backend/.../instances/` 保存配置/生命周期/受控访问，复用 workspace 的只读构建产物入口；record、构造注入、参数化 SQL。示例：`record StartRequest(UUID buildId) {}`，HTTP 不接收进程参数。

先失败测试再实现：配置错误/越权/CSRF/重复提交/容量/真实进程退出，真实 PostgreSQL 验证状态；JAR 健康与保存/重启用独立 Chrome 证明。命令：backend `./mvnw -B verify`；根目录 `node --test scripts/runtime-supervisor.test.mjs`；frontend `npm run test:unit`、`npm run typecheck`、`npm run lint`、`npm run build`。

Always：实例与冻结构建绑定、错误脱敏、保留运行数据；Ask first：新增管理员权限、远程部署、删除数据；Never：执行用户命令、借平台库当运行库、自动付费模型调用。
