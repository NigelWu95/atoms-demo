# workspace-build · 本机工程快照与自动构建规格

状态：2026-10-13 用户已确认，包括 V011、单执行槽及 10 分钟构建预算，并批准详细计划一次性实施。当前开发中，不表示已经可以在工作台启动独立实例。
归属：[能力地图](CAPABILITY-MAP.md)。执行取舍：[ADR-013](docs/decisions/013-native-process-engineering-preview.md)。代码基线：`21571cd`，沿用 `feature/standalone-engineering`，不合并/推送/部署。

## 1. 目标与模块边界

项目所有者选择一个已保存代码版本，由平台自动在本机新目录构建完整工程，获得可追踪的构建状态、固定源码 ZIP 和可供后续启动的 JAR。不是终端模拟，也不只生成目录；验收必须实际执行构建并校验产物。

本模块解决“预览与下载如何对应到同一工程”的提供方契约：**先冻结快照 → 构建快照 → 保存产物身份 → 下游以 buildId 启动/下载**。下载不得在构建后重新读取当前模板进行组装，版本更新或平台模板升级也不能暗中改变已有快照。

本模块不负责应用进程端口分配、运行数据库供给、应用启动/停止、私有 iframe 授权、发布切换或平台数据迁移。这些分别属于 `app-runtime` 和 `runtime-access`。原生成、共享预览、路径发布和版本恢复保持可用，不能把本模块通过说成最终预览已经切换。

假设：单台受信任开发机、单个平台后台调度器、固定模板与依赖、Java 17/Node/npm 已安装。没有任意后端生成、上传工程、远程仓库导入或用户终端。模型产物仍仅限原有 HTML/CSS/JS/manifest 契约，浏览器中才执行生成的 JavaScript。

## 2. 技术与来源

沿用当前 Java 17.0.5-oracle、Spring Boot 3.5.16、Vue/TypeScript、PostgreSQL、Node 24.20.x、npm 11.19.x、Maven Wrapper 3.9.11，不新增第三方依赖或升级锁文件。

复用 `EngineeringBundleAssembler`、`TemplateCatalog`、`TemplatePolicy`、`EngineeringZip` 的格式和白名单；提取必要的内部授权/组装入口，避免下载与构建各自拼一套文件。模板按实际文件集合计算摘要，不能仅用 `templateVersion=1.0.0` 认定其内容相同。

`templates/standalone-v1/scripts/build.mjs` 已提供完整构建顺序；仓库 `scripts/verify-standalone.mjs` 和 `standalone-process.mjs` 提供验收经验，但依赖受信任报告/目录，不直接作为新增公开 API。受控本机监督器必须重新校验执行来源与进程归属。

## 3. 用户流程与状态

在已保存版本区域增加“构建工程”，显示目标 Vn、阶段、结果以及对应快照下载。请求提交后立即返回任务；刷新能读取真实持久化状态，不启动重复构建。界面明确“构建完成，实例预览尚未接入”，不会把旧平台预览标为本次 JAR 的运行结果。

后续 `runtime-access` 通过同一创建接口组合“构建并预览”，并在生成完成/选定版本时触发；不能把 GET 预览页面变成隐式的构建写操作。本模块不先把构建调度塞进模型事务，也不让后台新版本的晚到结果覆盖用户当前选择。

- `QUEUED → BUILDING → SUCCEEDED | FAILED`。
- 未执行时取消直接 `CANCELLED`；执行中取消先 `CANCELLING`，自有进程确认退出后 `CANCELLED`。
- 监督中断、退出情况未知或平台重启时，未完成任务置为 `INTERRUPTED`；不冒充失败回滚，不自动重发。确认退出之前不得重用执行槽位。
- 阶段为 `QUEUED / PREPARING / INSTALLING / CHECKING_FRONTEND / BUILDING_FRONTEND / CHECKING_BACKEND / VERIFYING / COMPLETE`，来自可信控制通道，不从任意 stdout 文本推断成功。
- 重试是用户明确的新意图和新 buildId；旧快照、失败记录与上次成功产物保留。网络超时时首先以原幂等键查回同一提交，不直接创建新任务。

## 4. 不可变工程与构建产物

任务创建时完成所有者/版本归属验证，并冻结来源 versionId、contentHash、模板内容摘要和构建策略版本。异步准备阶段将校验过的模板及业务源码保存为不可变快照和原始 ZIP；若模板内容与入队时不一致，返回 `BUILD_INPUT_CHANGED`，不偷偷换新版。

专用工作根由维护者显式配置，必须是普通私有目录；拒绝 home、仓库根、系统根、符号链接及用户请求指定目录。所有子路径由服务端 UUID 派生，代码标题、文件名和客户端字段不能控制路径。目录权限 0700，归档、报告及私有日志 0600；不改动用户已有源码目录。

目录契约示意：

```text
<configured-build-root>/
  <buildId>/
    snapshot/engineering.zip     # 永远返回这一份源包
    work/                       # 全新物化目录，不能在别的任务目录构建
    result/build.json           # 可信构建结果，非命令输入
    result/app.jar              # 验证后的构建成品
    private/                    # 有界内部诊断，不通过静态服务暴露
```

原始 ZIP、物化源码和最终 JAR 分离。构建前后逐文件核对源快照；node_modules/target/dist 不进入源 ZIP。只有固定白名单模板的执行文件能运行，hash 清单不是对未知用户工程的执行授权。为 Maven Wrapper 设置执行位只改变权限，不改变来源字节。

完成条件为：完整检查命令退出 0、来源文件未变、实际 JAR 是本次新产物、资源指纹匹配。随后原子保存成功结果；缺报告、残留旧 JAR、报告自称成功或某个端口返回 200 均不算构建成功。

对下游提供的只读描述包含 `buildId / projectId / versionId / templateDigest / buildPolicyVersion / bundleId / archiveSha256 / loadedRuntimeId / jarSha256`。所有归档/JAR 读取都再次检查文件类型、路径归属和摘要；损坏返回 `BUILD_ARTIFACT_UNAVAILABLE`，不重新组装替代。内部服务可获取受验证的 JAR 文件句柄/路径，浏览器 API 不返回绝对路径、PID、命令或凭据。

## 5. 数据持久化与 HTTP 契约

已批准新增 **V011 增量迁移**记录构建任务和有界阶段事件，目前尚未编写或应用。使用新表 `engineering_builds` / `engineering_build_events`，包含 owner/project/version 外键、来源/模板摘要、状态/阶段、相对 workspaceKey、结果摘要、错误码、时间戳及事件序号。不改 V000–V010，不修改 versions/app_states/publications，不把源文件或真实数据库密码保存到事件表。

任务创建与幂等结果在现有 PostgreSQL 事务内提交；使用现有 IdempotencyService 原子唯一约束，不在此事务里安装依赖、写长时间文件或启动进程。项目级活动任务约束和全局调度容量必须由事务/唯一约束保护，不能靠先查后写。

所有端点使用现有访客会话并检查项目与 buildId 归属；非所有者统一 404。写入还需要 CSRF 和 Idempotency-Key。同键异体沿用平台 422 `IDEMPOTENCY_PAYLOAD_MISMATCH`。

| 方法/路径 | 请求与响应 |
| --- | --- |
| `POST /api/projects/{projectId}/builds` | 仅 `{versionId}`；202 `{buildId, projectId, versionId}`，随后查询状态。同意图重放返回同一 buildId，不再次启动进程 |
| `GET /api/projects/{projectId}/builds?cursor=…&limit=20` | `{items, nextCursor}`，limit 1–100；按既有游标方式分页，不返回源码/路径 |
| `GET /api/projects/{projectId}/builds/{buildId}` | 下述 BuildSummary；未知执行状态不能只由前端计时推断 |
| `GET /api/projects/{projectId}/builds/{buildId}/events?after=0&limit=100` | `{items, nextCursor}`，递增 seq；每次最多 100 条，只给有限类型的阶段事件与脱敏错误 |
| `POST /api/projects/{projectId}/builds/{buildId}/cancellations` | 仅 `{}`；202 `{buildId}`。未完成则请求取消，已终态为无副作用确认；相同意图安全重放，详情接口提供最终状态 |
| `GET /api/projects/{projectId}/builds/{buildId}/engineering.zip` | 从冻结归档读取；同一 buildId 的字节固定，未形成有效快照为 409 `BUILD_SNAPSHOT_NOT_READY`；沿用有界下载及不缓存响应头 |

`BuildSummary` 字段：`id, projectId, versionId, status, stage, templateDigest, buildPolicyVersion, bundleId, archiveSha256, loadedRuntimeId, jarSha256, errorCode, errorMessage, createdAt, startedAt, finishedAt, lastEventSeq`。尚未产生的结果/时间为 null；ID 使用标准 UUID，摘要为小写 64 位 hex，时间为 UTC ISO-8601，事件序号受 JavaScript 安全整数范围约束。

错误统一沿用 `{error:{code,message,retryable},requestId}`。新增错误包括 409 `BUILD_ALREADY_ACTIVE`、503 `BUILD_DISABLED` / `BUILD_ENVIRONMENT_NOT_READY` / `BUILD_QUEUE_FULL` / `BUILD_CAPACITY_EXCEEDED`、422 `INVALID_ENGINEERING_BUNDLE`；构建执行错误则记入任务 `BUILD_FAILED` / `BUILD_TIMEOUT` / `BUILD_INPUT_CHANGED` / `BUILD_INTERRUPTED`，不返回 shell 输出或异常堆栈。

旧按 versionId 的 ZIP API 保留原含义；“下载本次构建工程”明确使用 buildId API。后续实例预览必须使用同一个 buildId，不能回退到旧版本下载 API 而声称预览与下载完全对应。

## 6. 本机执行、安全与初始预算

默认关闭本机执行，维护者通过服务器配置显式开启；首轮仅 loopback 本地开发模式。配置包括专用工作根、Java/Node 可执行文件位置、固定测试配置文件位置和资源预算。不从 HTTP 接受命令、环境变量、JVM 参数、数据库配置、URL 或文件路径。

构建监督器只运行当前平台携带的可信入口；参数用数组传递，禁止拼接 shell。固定流程沿用模板的 `npm ci --ignore-scripts`、类型/lint/单测/前端构建和 Maven `clean verify`。不跳过测试、不接受任意依赖、不继承模型 Key、平台数据库密码、`NODE_OPTIONS` / `JAVA_TOOL_OPTIONS` 等覆盖项。构建环境不是安全沙箱，也不能声称清理环境变量后完全无法访问同用户文件。

测试必须使用现有契约的专用 PostgreSQL 测试库、受限角色和管理员用途标记；运行库与平台库绝不能作为构建测试库。构建执行角色不需要 CREATE DATABASE/CREATE ROLE。不自动打印或改写现有 `.env.local`；维护者保证该测试库只供这个串行构建器使用，不能同时跑共享此库的手工构建。运行项目的数据库供给留到 `app-runtime`。

以下初始预算已获用户确认，不是压测或 OS 硬隔离结果：

- 全局一个正在执行的构建，活动队列总量最多 4（包含执行中），同项目最多一个活动任务；超过容量立即拒绝。
- 总构建最长 10 分钟；取消先终止自有进程组，5 秒宽限后处理仍存活的自有子进程，等待退出后才释放执行槽。平台退出时也执行此流程。
- 父级连接丢失时监督器终止所管任务；正常退出、平台强制退出、任务取消均需真实子孙进程测试。监督器自身遭 SIGKILL 等无法证明退出的情况标记中断并阻止不确定任务被自动接管；不凭旧 PID 或进程名杀进程。
- 同一执行根只允许一个调度器持有 OS 文件锁。重启后不自动重跑非终态任务、不删除未知目录；处理可证实的自有任务后保留中断记录。
- 归档沿用 128 文件 / 单项 2 MiB / 总源 8 MiB / ZIP 9 MiB；JAR 上限 128 MiB；任务事件最多 1000 条、单条 2 KiB；内部诊断输出最多 1 MiB，超限持续排空但不继续保存，不因管道写满卡死。
- 专用工作根最多保留 32 个任务目录，软件磁盘预算 8 GiB、启动准入要求至少 2 GiB 可用空间；达到上限拒绝新构建，不自动递归删旧工程。运行时监测不能作为严格磁盘隔离承诺；管理员显式清理契约另行设计。

日志中只暴露任务/阶段、耗时、稳定错误码与 requestId。诊断原文不作为网页构建日志直接转发，不能包含配置正文/凭据/会话/源码；测试用 canary 证明 API、事件和日志排除敏感内容。子进程输出是不可信数据，不是可以再次执行的命令或可信完成标志。

## 7. 文件归属与代码风格

拟新增 `backend/.../workspace/` 的任务/快照/调度接口和对应单元、PostgreSQL HTTP 集成测试；受控监督入口位于平台打包资源而非用户可上传目录，仓库侧生命周期测试仍在 `scripts/`。构建 UI 放入独立组件及 `api/`，接入现有 ArtifactPanel，不把生成、发布和构建状态混成一个字段。

沿用 Java record、构造器注入、JdbcTemplate 参数化 SQL，TypeScript 严格模式、判别联合和现有格式化规则。接口示例（契约示意，非已存在代码）：

```java
public record BuildRequest(UUID versionId) {}
public record BuildAccepted(UUID buildId, UUID projectId, UUID versionId) {}
// 创建接口只登记一个已授权意图；执行器不接受 ownerId/command/path 等浏览器字段。
```

不把现有验收脚本的“仅允许手工 todo ZIP”校验放宽成任意上传执行。生产任务来自授权快照和可信平台模板；测试 fixture 与生产执行入口继续分离。

## 8. 验证与完成条件

先写失败测试，按小切片实施；不删除断言、不静默跳过浏览器或 PostgreSQL 检查。以下命令是实现后的验收要求，本轮仅规格审查，未运行新功能测试：

```sh
# 平台 backend/，使用已配置的专用平台测试库
./mvnw -B clean verify

# 平台 frontend/
npm run typecheck
npm run lint
npm run format:check
npm run test:unit
npm run build
npm run test:e2e:runtime -- --project=chromium

# 根目录：保留已有运行层一致性与工程工具回归
node --test scripts/standalone-runtime-parity.test.mjs scripts/verify-standalone.test.mjs
```

成功标准：

1. 所有者实际从工作台提交已保存版本，后台创建全新目录并完整构建出 JAR；刷新可恢复状态，成功不是前端模拟。
2. 同一提交并发/断网重试只产生一个任务和一次执行；陌生访客、混用项目/版本/buildId 均被拒绝。队列准入、取消/完成竞争和重启中断由真实数据库验证。
3. 当前版本变化、模板内容升级、取消晚到结果都不能替换原任务快照或悄悄切换预览。构建前后文件 hash 一致，下载 buildId 归档与实际构建源逐字节对应。
4. 在隔离验收环境中，实际 API 产物启动后报告的 loadedRuntimeId 与构建记录一致；解压该任务 ZIP 重新构建后的运行内容指纹一致。JAR ZIP 元数据可影响文件字节，不能把不同构建的 jarSha256 必须相同当成已有可复现构建保证。
5. 真实进程测试覆盖子孙进程退出、取消、超时、父进程断开、无关进程与文件不受影响；不依赖只断言 PID 存在或 mock ProcessBuilder。
6. 缺工具链/配置、依赖网络失败、坏源码、磁盘不足、文件篡改/符号链接、超限输出、损坏报告/JAR 均明确失败，不能降级为跳过检查。
7. Chrome 验证提交、刷新恢复、取消、失败保留版本/输入、准确快照下载；320/390/768/1440px 和键盘/axe 通过。固定测试产物，不调用真实模型。
8. 原项目、生成、共享预览、PREVIEW/LIVE、历史恢复和原工程下载回归通过；原用户数据不被构建测试清理。实例管理和真实实例预览仍按后续模块单独验收。

## 9. Always / Ask first / Never

- Always：先鉴权再读取/物化；冻结来源；按已保存版本构建；有限队列/超时/输出；状态持久化；仅管理自有子进程；错误脱敏；本地验证后提交。
- Ask first：V011 与本规格范围内的本地执行已批准，不重复申请；新增依赖、数据库管理员权限、新建或改写专用数据库配置、新会话/代理/CORS 方案、原预览默认切换、公网启用或云端部署都不能顺带处理。验证只复用已准备的专用测试配置，不修改现有平台启动配置。
- Never：执行模型生成的命令/后端；公开任意文件路径/命令参数；用平台库跑模板测试；读出/提交私有配置；按端口杀别人进程；未知状态直接重跑；自动删除旧工程/业务数据；绕过 sandbox/CSRF/CSP；自动调用模型、推送或部署。

## 10. 确认记录与下一阶段

用户于 2026-10-13 回复“好的可以”，确认本模块的固定模板本机构建范围、V011 构建记录，以及上述单执行槽/10 分钟/有限存储默认预算。确认不代表批准未来任意后端执行、数据库管理员供给或远程部署。对应小步执行计划与待办追加于 [tasks/plan.md](tasks/plan.md#7-workspace-build-扩展计划) 和 [tasks/todo.md](tasks/todo.md#workspace-build-执行任务)，保留前两模块的完成记录和未完成的人工验收项。

规格确认后再细化小步任务并实现。后续 `app-runtime` 规格将解决每项目的运行配置/数据库分配、端口与进程监督、升级保数据；`runtime-access` 将解决私有预览鉴权、嵌入策略与 Cookie 隔离、历史只读语义及 UI 切换。只有这些完成，才能宣称“平台预览运行的就是下载工程对应实例”。
