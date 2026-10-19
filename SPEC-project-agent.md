# Spec: project-agent · Claude Code + DeepSeek 完整工程编辑

状态：2026-10-18～19，用户已确认规格、[实施计划](tasks/project-agent/plan.md)与[任务清单](tasks/project-agent/todo.md)，并将范围扩展至完整本地开发闭环（§10）。检查点用于汇报证据，不重复请求逐项批准；真实付费模型 PA-LIVE 与部署仍需另行授权。PA01–PA27 内部切片已完成：文件编辑、默认 4 并发调度、查询/正文保留、原生 Java/Vue 模板、脚本契约及正式平台 JAR 模板包装；前端自身测试、构建及本机测试 API 的浏览器验收通过。初始化、完整工程实际构建运行及工作台接入仍在实施，不是完整产品交付声明。

模块索引：[完整工程能力地图](FULL-PROJECT-CAPABILITY-MAP.md)。上游：[源码模块](SPEC-project-source.md)。总体方向：[ADR-016](docs/decisions/016-full-project-source-and-coding-agent.md)。本文不是整个工程改造的完成声明，也不是执行生成后端的授权。

实施验证补充（2026-10-19）：原 `bare + restricted` 只暴露 Read/Edit；经用户批准验证替代方式，`safe-mode + restricted` 在真实本机 CLI + loopback 下暴露完整五种工具，并通过搜索、新增 Vue/Java/SQL、目录外读写和软链接越界拒绝测试。仅测试假密钥，不代表真实模型或完整运行链路已验收。见 [完整验证记录](docs/project-agent-verification.md)。

## 1. 目标与范围

使受信任维护者能提交需求，让 Claude Code 通过 DeepSeek 在平台发行的任务副本中新增、修改 Vue、Java、SQL、测试和非敏感配置；平台验证后保存新的完整源码版本。第二轮需求从上一已保存版本开始，保留实际文件及修改历史，不重新生成固定的三个前端文件。

本模块交付可测试的内部服务、Claude 子进程适配器、持久任务与事件、新工程初始化模板，以及源码模块必要的窄接口扩展。首期默认关闭，不接管旧网页生成器，不开放 HTTP 任意文件写入或终端。

成功意味着“完整源码变更已保存且可追溯”，不意味着代码已编译、应用已启动或功能已验收。测试构建和本机启动由 `project-execution` 负责，预览和下载由 `project-delivery` 负责，页面入口由 `project-workbench` 负责。

### 1.1 本规格采用的假设

- 平台及新工程继续使用 Java 17 + Spring Boot + Vue + PostgreSQL，不引入其他语言后端。
- 使用现有 DeepSeek API Key 的受控子进程副本，不切换个人 Claude Code 的默认模型或登录。
- 首期只开放文件读取、新增、修改工具，不开放 Bash、安装依赖、运行测试脚本、外部浏览、MCP 或子 Agent。重命名/删除文件不借助 Shell 绕过：本阶段不承诺自动完成这类操作，需扩展受控文件接口后另行验收。
- 只有显式配置的维护者账号可提交；“拥有项目”不自动获得本机 Agent 执行权限。仍沿用现有账号与项目所有权，不新建管理员认证体系。
- 本模块默认只允许确定性假进程及本地协议测试。真实 DeepSeek 调用需要后续确认试验项目、次数和费用边界。

### 1.2 “不开放 Shell”的含义

限制的是 Claude Code 的终端执行工具，不是平台自身启动子进程的能力。Agent 可以通过专用工具读取、搜索、新增和修改工程文件，但不能自行执行 `npm install`、`mvn test`、`java -jar`、`curl`、`ssh` 或系统文件管理命令；平台仍可启动 Claude CLI 本身。

后续 `project-execution` 由平台按受控流程运行测试、构建和本机应用进程，不把任意命令输入权直接交给模型。将脱敏构建错误反馈给 Agent 再修改代码，可以作为后续跨模块闭环设计；本阶段尚无自主“执行测试—修复—重测”能力。

这是减少模型在平台机器上任意执行命令的权限，不是强隔离保证。受控构建仍可能执行工程内的脚本、插件及代码，必须在执行模块单独设计权限、凭据和资源边界，不能仅靠命令白名单宣布安全。

## 2. 技术栈与已取得的证据

仓库版本：Java 17（用户指定 Oracle 17.0.5）、Spring Boot 3.5.16、Vue 3.5.43、TypeScript 5.9.3、Vite 8.3.3、Node 24.20.x / npm 11.19.x。数据库沿用 PostgreSQL；源码模块依赖 Git 与 Python 3.11+ 标准库文件工具。不新增 Agent SDK 包，首选 Java `ProcessBuilder` 适配 CLI；需要 SDK 特有权限回调时再评审，不偷偷扩大依赖或权限。

本机探测：

- `/Users/wubingheng/.nvm/versions/node/v20.15.0/bin/claude` 指向包内 `bin/claude.exe`；实际是 macOS x86_64 原生可执行文件，不是通过该目录的 Node 20 运行的 JS 文件。包声明安装工具所需 Node >=22；不据此变更平台 Node 24。
- 独立临时配置目录、空继承环境、无密钥条件下，`--version` 返回 `2.1.302 (Claude Code)`，`--help` 正常退出。
- §4 所列主要参数与 `--max-turns 24` 组合后追加 `--version`，退出码为 0。这只证明解析器接受参数，不能证明权限、工具执行或 DeepSeek 协议已工作。
- 未运行 `auth`、`doctor`、`update` 或普通模型会话；未读取个人认证。远程服务器的 CLI 版本、系统策略和可执行路径尚未探测。

### 2.1 官方依据及不能直接推断的部分

1. [Claude Code 非交互运行](https://code.claude.com/docs/en/headless)：支持 print 模式、标准输入及结构化事件；bare 模式避免自动加载定制内容，并不使用个人 OAuth / keychain。文档不替代针对已安装版本的行为测试。
2. [CLI 参数](https://code.claude.com/docs/en/cli-reference)：`--restricted` 约束内置文件工具的工作目录；`--tools` 才是工具集合限制，`--allowedTools` 是免询问规则。两者不能互相替代，也不构成操作系统沙箱。
3. [DeepSeek Claude Code 接入](https://api-docs.deepseek.com/quick_start/agent_integrations/claude_code/)：当前示例为 `https://api.deepseek.com/anthropic`、`deepseek-flash[1m]`，普通模式示例使用 `ANTHROPIC_AUTH_TOKEN`。
4. [DeepSeek Anthropic 兼容接口](https://api-docs.deepseek.com/guides/anthropic_api/)另有 `ANTHROPIC_API_KEY` 示例，支持流式及工具消息。结合 bare 的认证要求，本适配器拟使用 API Key 方式，而非继承个人 token；组合仍需联调。部分参数会被忽略，不能宣称完整等价。
5. [Claude Code 环境变量](https://code.claude.com/docs/en/env-vars)：可指定配置目录、关闭自动更新及非必要流量、设置输出上限。未识别模型的文档输出 cap 为 128000，超 cap 会下调；不能承诺把 163840 填入变量就实际生效。
6. [配置优先级](https://code.claude.com/docs/en/settings)：受管策略仍可能生效；独立配置不等于绕过机器管理员策略。冲突时拒绝启动并报告，不修改系统策略。

## 3. 源码、运行状态与配置分离

```text
正式 Git 源码版本
  → 经授权的普通任务副本
  → Claude 文件编辑
  → 确认进程结束
  → 扫描、封存候选
  → 基线校验与接受新版本
  → 后续模块才测试、构建和启动
```

继续使用源码模块的仓库、工作副本、候选目录。Agent 另有维护者配置的专用私有状态根：`<agent-state-root>/<task-uuid>/config/` 和 `tmp/`；不得与源码根、工作根、平台仓库、运行配置或用户主目录重叠。只接管平台新建且有所有权标记的目录，目录 0700，私有文件 0600。

配置和临时状态不入 Git、模型上下文、工程 ZIP。`CLAUDE_CONFIG_DIR` 指向该任务的 config；临时路径也显式指向任务私有目录。不重设系统 `HOME`，不读写个人 `.claude`、`.claude.json`、shell profile、OAuth 或 keychain。

源码仍受现有 UTF-8 / 512 文件 / 单文件 2 MiB / 总树 16 MiB 策略约束，不在此放宽二进制、链接、真实凭据等限制。这里的总树大小不是模型上下文大小；Claude 按需读取，不要求一次把整工程发送给模型。

### 3.1 新工程起点

新增独立模板 `templates/full-project-v1/`，包含原生 Vue 页面、Spring Boot API 入口、Flyway 迁移目录、测试骨架、非敏感配置示例及明确启动契约；不用旧 iframe + 三文件兼容层冒充原生工程。由平台受控模板清单和摘要初始化源码版本，模型可以修改业务 Vue/Java/SQL；初始化不是生成完成。

Agent 任务始终绑定非空源码版本和修订号。新项目先完成无模型的模板初始化，再提交编辑任务；模型失败保留初始化版并标明“尚未生成业务功能”。模板自身固定文件清单/哈希、配置排密及包装契约须有测试；本模块不宣称模板或新代码已通过运行验收。

旧项目继续显式导入指定版本。导入兼容层不会自动重写业务；后续要求改造成原生页面时要提交明确的新需求。不得覆盖旧产物、当前指针、共享预览、发布数据或旧导出模板。

## 4. Claude 子进程契约

### 4.1 启动与工具边界

可执行文件必须为维护者配置的绝对路径，解析真实文件、检查权限及版本/内容身份；一次任务中不可被静默替换。首次目标验收版本固定为 2.1.302，其他版本重新执行离线能力验收；缺少必需能力返回 `AGENT_CLI_UNSUPPORTED`，不退回宽权限模式。

以下是拟采用的参数数组，不是本轮应执行的模型命令。提示通过 stdin 一次写入后关闭，不拼到 shell 或进程参数；工作目录由源码门面给出：

```text
--safe-mode --restricted --print
--input-format text --output-format stream-json --verbose
--no-session-persistence --disable-slash-commands --no-chrome
--setting-sources "" --strict-mcp-config --mcp-config {"mcpServers":{}}
--tools Read,Glob,Grep,Write,Edit
--allowedTools Read,Glob,Grep,Write,Edit
--permission-mode dontAsk --permission-prompts none
--max-turns 24 --model deepseek-flash[1m] --effort high
```

不传 `--add-dir`、`--continue`、`--resume`、`--worktree`、`--plugin-dir`、`--fallback-model` 或 bypass 权限参数。每轮从平台当前源码重新准备副本，平台历史摘要仅作辅助，不依赖 CLI 私有会话恢复源码。

使用空环境白名单重新构造子进程环境：必要系统路径/语言、上述私有配置与临时目录、固定 DeepSeek HTTPS 端点、模型标识、API Key、已核验的资源控制变量。明确关闭更新、遥测、错误上报等非必要流量。禁止继承平台数据库/SMTP/会话秘密、个人认证、其他云提供方开关、代理、Node 注入或任意 shell 环境。

禁止向 Agent 提供真实平台源目录、其他项目目录或运行密钥。启用 restricted、工具白名单以及后验文件校验是纵深控制；同 UID 进程仍不是强隔离。默认不支持 root 运行真实 Agent；服务器适配需另行确认专用非 root 本机账号及目录权限，不要求另一台服务器。

机器受管策略、加载的工具或 MCP/plugin 状态与批准配置不符时拒绝继续。`system/init` 核对是侦测补充，不是事前防泄漏保证；真实密钥启用前，必须在同机通过无秘密的离线 canary 行为验收。

2026-10-19 实测补充：2.1.302 在 safe-mode 下仍报告 `cc-plugin-agents-md`、`cc-plugin-plugin-authoring` 两个内置模块。解析器仅识别该精确版本及 `path=builtin`、同名 `source=@builtin` 的元数据组合，不接受外部目录插件、未知模块或 MCP/skills。测试中的 CLAUDE.md、AGENTS.md 未被自动加入请求；这不是机器受管策略或 keychain 的完整隔离证明。

### 4.2 模型、预算与时间

以下均为新模块的拟定配置，不修改现有 `DEEPSEEK_MAX_TOKENS=163840` 默认值、旧生成轮超时或旧调用额度：

| 控制 | 拟定值与语义 |
|---|---|
| 模块 / 真实调用开关 | `PROJECT_AGENT_ENABLED=false`、`PROJECT_AGENT_LIVE_ENABLED=false`；均显式打开才可能启动真实调用 |
| 维护者名单 | 服务端 UUID 白名单，默认为空；请求不能自报维护者身份 |
| 并发 / 队列 | `PROJECT_AGENT_MAX_CONCURRENT_TASKS=4`，允许 1–8，重启后生效；不同项目并行，最多 8 个待处理任务；同项目 1 个活动编辑租约，冲突请求等待当前任务结束后重新提交 |
| 单任务工具轮数 | 24；不是 API 请求数，包含工具往返且仍可能有内部辅助请求 |
| 输出 tokens | 新模式建议 128000；记录配置目标及兼容性验证结果，禁止 UI 冒充更高有效上限 |
| 任务执行时限 | 1800 秒，从出队开始覆盖准备、模型、封存和接受；排队最长 300 秒，过期不启动模型 |
| API 超时参数 | `API_TIMEOUT_MS=300000`；流式行为依 CLI，不能当作整轮硬期限，平台仍强制任务 deadline |
| 重试 | 平台不自动重启模型任务；拟设置 `CLAUDE_CODE_MAX_RETRIES=0`，实际效果须通过本地协议测试确认 |
| 收尾 | 最多额外 10 秒确认自有进程退出；无法确认则保留阻断，不宣布成功取消 |
| 真实任务额度 | `PROJECT_AGENT_MAX_LIVE_RUNS=0` 默认禁止；显式授权后按启动意图持久预留，不因失败/取消自动返还 |

源码准备和发布仍受源码模块各自最多 300 秒预算，且不得超过 Agent 剩余 deadline。等待数据库、流式输入和输出排空不得重新开始计时。

2026-10-19 用户确认并发调整：单个数据库协调者管理可配置的多个 Worker，不是共享 Claude 会话。每个任务仍有独立进程、工作副本、私有配置目录及取消信号；达到总并发上限才等待。短暂的源码准备/发布临界区仍串行。关闭或协调租约丢失时停止所有自有任务，确认全部退出后才释放协调租约；无法确认退出时保留原有待核对阻断。并发设置不放大真实调用额度，也不改变维护者白名单或默认关闭状态。

CLI turn、真实请求数和费用是三种数据。旧链路 10000 次不能被显示为新 Agent 可精确计量的剩余请求；旧 `generation_budget` 不借给新模块。已发出的请求取消后仍可能计费；任务额度只能控制任务启动数，不提供金额硬上限。第三方模型的 CLI 美元估计不作为结算依据。真实试验前需确认可接受的任务数及提供方费用约束，不能仅依赖 `--max-budget-usd` 宣称封顶。

### 4.3 事件与结果

stdout / stderr 并行有界消费，JSON 每行最多 4 MiB、stdout 总量最多 64 MiB、stderr 最多 256 KiB；超限停止任务，不截掉尾部再判成功。总事件最多 4096 条，聚合后的持久展示事件最多 256 KiB；最终状态预留空间，超限写明确错误。

只解析白名单字段：初始化能力、工具种类及经校验的相对路径、阶段、数值 usage 和最终结果。原始工具输入/输出、源码正文、thinking、全量 system/init、绝对路径和原始 stderr 不进入数据库、普通日志或事件。模型自然语言不是平台状态；首期只持久展示平台生成的阶段说明和受校验的变更统计，不转发模型任意文本。

必须同时满足：受支持的成功 result、无结果冲突/协议损坏、进程正常退出、无未决工具权限错误、自有进程结束已确认、文件校验及基线检查通过，才能接受版本。只有“完成”文本、退出码 0、文件存在或部分流输出均不够。缺少最终结果、上下文/轮数/输出限制、权限拒绝均保存失败状态而不保存新源码版本。无实际内容变化返回 `NO_CHANGES`，不创建重复版本，也不宣称需求已完成。

## 5. 源码模块提供的窄接口（待实现）

提供者契约归 `project-source` 所有，依赖仍是 `project-agent → project-source`。新增源包门面和不透明编辑句柄，不把 `SourceWorkspaceStore`、`SourceCandidateStore` 等所有内部类改为 public。

- `claimEdit(owner, project, base, taskId)`：短事务重新授权并领取持久编辑租约；同项目唯一；基线来自源码修订号，不是旧 `projects.project_revision`。
- `prepareEdit(handle)`：从不可变快照物化副本；只有进程适配器可获得已经核对的工作路径，模型/外部调用者不能指定目录。
- `markExecutionStarted(handle, executionId)`：在启动 CLI 前记录一次性执行意图；宁可保守标记待核对，也不能崩溃后自动重复付费调用。
- `confirmExecutionStopped(handle, evidence)`：只接受平台进程拥有者给出的结束证据，不接受模型、客户端或裸 PID 自报。重启后没有原进程拥有关系时不伪造证据。
- `sealAndAccept(handle, idempotencyKey)`：在已停写、重新授权、基线有效的前提下调用既有封存/接受/协调者流程；返回持久源码操作 ID，按操作事实确认完成。
- `releaseEdit(handle)`：只有未启动或已确认结束且源码操作已结算时才释放活动租约；只释放能力，不删除磁盘证据。

所有源码写入口（包括恢复、其他接受和导入）必须在相同锁顺序下检查编辑租约；只有持有当前租约的接受流程可继续。只在 Agent 服务内加内存锁不合格。源码存储可见性读取保持可用。

新增通用 `source_edit_leases` 持久结构，拥有者是源码模块，不引用 Agent 表；保留执行未决标识及历史证据。其约束不得削弱既有 V019 的 Git 执行阻断；CLI 执行和 Git 写入是两个不同执行身份。追加迁移，不改 V018/V019；具体迁移编号由后续计划确认。

## 6. 任务服务、状态和恢复

拟提供内部 `submit`、`get`、`list`、`events`、`cancel` 服务；不在此增加公开路由。调用必须显式传入经现有身份系统验证的 owner，并再次检查维护者名单、当前项目归属和归档状态；UUID 是标识不是认证凭据。

提交字段为 `prompt`、`baseSourceVersionId`、`expectedSourceRevision` 和幂等键；提示为 1–8000 字符，拒绝 NUL、未知字段、非规范 UUID 或超安全整数修订号。固定供应商/模型和配置版本进入请求摘要，调用方不能传可执行路径、API Key、上游 URL、附加参数或环境变量。

`agent_tasks` 保存项目/所有者、请求摘要、幂等摘要、基线、执行身份、阶段、取消请求、源码操作/结果版本、预算预留和错误码；不保存完整工程。幂等键以项目为范围，通过数据库唯一约束原子领取，同键不同请求返回 `AGENT_IDEMPOTENCY_CONFLICT`，重试只返回原任务，不再次调用模型。

```text
QUEUED → PREPARING → RUNNING → VALIDATING → ACCEPTING → SOURCE_SAVED
                                         ↘ NO_CHANGES
已确认失败/停止 → FAILED / CANCELLED / TIMED_OUT
执行或发布结果不确定 → NEEDS_RECONCILIATION（保留写入阻断）
```

`cancelRequested` 单独持久化，不把“已请求取消”当成已停止。在源码提交事务前胜出的取消阻止发布；若新版本先提交，任务必须反映 `SOURCE_SAVED`，不得删版本或伪报取消。每个结束状态包含稳定错误码及时间；`NEEDS_RECONCILIATION` 不是可重试终态。

`agent_events` 保存 task + 单调 seq + 类型 + 白名单 payload；读接口支持 after 游标、每页最多 100 条，列表最多 100 项。权限每次重新检查，其他用户/项目不可枚举。UI 的 SSE 或轮询传输由后续工作台模块设计，不能假定已有旧 run SSE 可直接传新事件。

崩溃恢复采用单协调者租约，先核对再接新任务。启动前持久写入执行意图，运行期间不持有数据库事务；重启不恢复个人 CLI 会话，不重跑未决任务，不凭进程名称或旧 PID 杀进程。已提交源码操作可补齐任务结果；未确认退出或结果不明确则保留写入阻断，维护者核查后按有证据的流程结算，不提供“强制成功/强制解锁”按钮。

提示只复制任务所需信息；不自动注入邮箱、其他项目、整份聊天或密钥。任务提示副本在终态 30 天后清除；事件正文同样按 30 天清理，保留不含提示正文的任务、结果引用、幂等摘要和终态审计，防止过期重试重新计费。活动/待核对证据不自动清理；清理游标过期返回明确的 `AGENT_EVENT_HISTORY_EXPIRED`，不是假装没有事件。磁盘工作副本和配置目录首期不自动 GC，达到容量准入时拒绝新任务，由维护者在确认停写后处理。

稳定错误码区分未启用、未授权、CLI 不兼容、配置无效、认证失败、提供方限流、协议错误、输出/轮数限制、超时、文件策略拒绝、基线冲突及待核对。错误沿用 `ApiException(status, code, message)`，不泄漏原始上游响应或本机路径。日志只含项目/任务 ID、状态、码、耗时、计数；观测不可依赖保存完整模型流。

## 7. 代码位置、风格与验证命令

```text
backend/src/main/java/dev/atoms/demo/agent/        任务领域、适配器、协议与恢复
backend/src/main/java/dev/atoms/demo/source/       上游编辑租约、窄门面
backend/src/main/resources/db/migration/           新增迁移
backend/src/test/java/dev/atoms/demo/agent/        协议/假进程/集成测试
backend/src/test/java/dev/atoms/demo/source/       上游租约回归
backend/src/test/resources/agent/                 无秘密工具事件与受控协议样本
templates/full-project-v1/                        新原生工程模板（待新增）
docs/project-agent.md                             实施后的使用边界与维护说明
docs/project-agent-verification.md                实施后的可重复验收证据
```

沿用 Java 四空格、构造器注入、不可变 DTO、绑定 SQL、短事务。对外不透明句柄和进程拥有权不使用客户端可构造的 record 冒充。普通输入 DTO 示例（设计示例，尚非已存在接口）：

```java
public record AgentTaskInput(
        String prompt,
        UUID baseSourceVersionId,
        long expectedSourceRevision) {}

public interface ProjectAgentService {
    AgentTaskView submit(UUID ownerId, UUID projectId,
            AgentTaskInput input, String idempotencyKey);
    AgentTaskView get(UUID ownerId, UUID projectId, UUID taskId);
    AgentTaskView cancel(UUID ownerId, UUID projectId, UUID taskId);
}
```

基础检查命令如下，本轮未执行测试，也不将源码模块历史结果计为新模块通过：

```sh
cd /Users/wubingheng/atoms-demo/backend
./mvnw -B test
env PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH ./mvnw -B verify
python3 -I -S -B src/test/python/test_workspace_helper.py

cd /Users/wubingheng/atoms-demo/frontend
npm run typecheck
npm run lint
npm run format:check
npm run test:unit
npm run build
```

后端集成测试只使用既有专用 `atoms_demo_test`、`atoms_test` 非超级用户及私有测试密码；先检查连接目标，禁止日常库或远程库。测试默认不读真实 DeepSeek/SMTP 密钥。新测试类、离线 CLI 契约命令在批准后的实施计划中补充，不写不存在的测试命令假装已执行。

## 8. 验收标准

先用 JUnit / Surefire / Failsafe、受控假 CLI、真实临时 Git 和专用 PG 完成确定性验证；然后使用真实已安装 CLI + 仅测试可注入的 loopback Anthropic 协议夹具，零付费验证工具循环。生产配置不接受客户端自定义上游 URL。真实 DeepSeek 是另一个必须明确标注的验证层，不能被假模型通过代替。

以下场景全部要求断言，不以未经测量的覆盖率百分比代替：

1. 禁用、无维护者权限、缺密钥、CLI 缺能力或版本变更时无真实调用；模块关闭不启动进程或建立 Agent 目录。
2. 验证空继承环境、任务独立配置、无个人配置读取/写入；用测试 canary 而非真实个人秘密证明无跨任务串用。工具集合、认证头、实际 model / max_tokens / retry 行为均由本地协议夹具检查。
3. 恶意提示要求读副本外文件、写个人设置、运行 Shell、调用 MCP/子 Agent、读平台 .env 时被拒绝；canary 不进入请求、事件、日志或候选。验证链接、路径穿越和受保护配置文件不能绕过既有源码策略。
4. 受控事件驱动真实文件新增/修改，首轮形成 Vue/Java/SQL 新版本，第二轮从该版本编辑并保留未改字节；源码、版本引用和任务结果一一对应。拒绝将未运行代码标为运行成功。
5. 同键并发最多启动一次；同项目第二任务、源码恢复或其他接受不能越过编辑租约；不同所有者读取、取消、接受失败。
6. 取消/超时终止受控自有进程且确认退出，不遗留可接受候选；未确认结束保留阻断。验证超时、流排空、标准错误洪泛和任务 deadline 不因重复事件延长。
7. 模型输出截断、缺最终结果、相互矛盾结果、异常退出、权限拒绝、非法 JSON/字段、过大事件均失败，原当前版本不变；无修改不给新版本。
8. CLI 启动前后、模型退出后封存前、源码发布前后分别注入崩溃；重启不重复调用、不错误释放租约，已提交结果可重建，未知状态明确待核对。
9. 事件序列与分页/清理可重放；身份变化后访问失效；提示/事件过期不丢失幂等证据。新模板与旧模板包装互不污染，旧生成、账号、预览、下载、恢复全部回归。
10. 最终真实联调（另行授权）：指定无秘密小工程、限定任务启动数，DeepSeek 实际完成首轮及第二轮 Vue/Java/SQL 修改，记录工具调用、有效参数、source version / hash 和失败边界。没有这项就只能标“离线适配验收完成，真实模型未验收”。实际启动、重启后业务持久化与 ZIP 一致性留给后续模块。

## 9. 行为边界、确认结果与后续事项

- Always：先授权再读写、持久幂等、单项目编辑租约、执行结束证据、原始数据不出日志、使用源码模块校验和 CAS；保持旧网页/下载可用。
- Ask first：新增 SDK/依赖或工具安装升级、改变上述默认资源预算、真实模型试验、允许 Shell/代码执行、启用新模式到日常环境、修改服务器运行用户、远程迁移、推送/部署或清理历史数据。
- Never：修改个人 Claude Code 配置/登录、绕过机器受管策略或产品认证、把整个平台环境传给子进程、将进程/目录隔离称为安全沙箱、静默强制合并、自动重试未决付费调用、以模型自述替代验收。

用户已确认以下规格取舍；Shell 边界说明见 §1.2：

1. 首期按文件编辑模式实施，默认关闭、内部入口先行，Shell/构建/启动与 UI 继续按能力地图后续模块推进。
2. 新 Agent 采用 128000 tokens 目标、24 工具轮、出队后 30 分钟执行期限及 5 分钟 API 参数；旧链路配置不变。有效值仍须实测，不从 CLI help 推断。

真实调用的具体项目、启动次数和费用约束可在离线适配完成后单独确认，不阻塞确定性实现。第三方模型组合及对外托管的适用条款仍是正式开放前核查项，沿用 ADR-016 的边界，不将技术连通性等同于商业授权。

下一步形成实施计划与任务；批准本规格不自动开启真实调用、执行生成代码或远程部署。

## 10. 2026-10-19 完整闭环实施授权

用户在了解 npm 安装、构建错误反馈与文件编辑的区别后，要求“一次性按照完整实现来完成”。本节更新先前只交付文件编辑模块的范围：继续完成完整源码编辑，并衔接受控依赖安装、构建/测试、错误反馈修复、本机进程运行、同快照预览/下载及工作台入口。按现有能力地图顺序推进，不逐小项重复请求开始，也不把内部文件编辑通过当作整体交付。

先验证 `--safe-mode --restricted` 候选启动组合，保留独立配置、空环境、文件工具白名单及无 Shell/MCP 边界。只有真实已安装 CLI 的离线行为测试通过才更新正式调用参数；不因为文档列出参数就宣布兼容，不修改个人认证或机器受管策略。

后续执行器将安装依赖与构建作为平台动作，不接受模型提供的任意命令字符串。锁文件归工程源码，node_modules、构建产物与运行秘密不进入源码版本。新增依赖先解析并审核来源/安装脚本，构建日志脱敏后反馈；失败不得替换上一可用预览。独立目录与进程不等于安全沙箱，新模式仍仅向明确允许的维护者开放。

本次不授权推送/远程部署、修改个人配置或放宽既有安全验收。真实付费模型仍须确定试验项目与次数预算后再执行；在此之前通过离线协议和受控工程夹具验收，并明确区分“离线通过”与“真实模型通过”。
