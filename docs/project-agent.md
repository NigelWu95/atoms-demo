# 完整工程 Agent · 使用与维护

本模块在完整源码副本中调用 Claude Code 修改 Vue、Java、SQL、测试和配置，校验后保存不可变源码版本。它是默认关闭的内部服务，**不是当前网页已经切换到的新生成器**。实际测试结果见[验证记录](project-agent-verification.md)，进度见[任务清单](../tasks/project-agent/todo.md)。

截至 2026-10-19，PA01–PA33 内部离线验收完成，包括本地受控进程和真实 CLI + 假模型服务的连续两轮编辑、取消恢复及旧链路回归。真实 DeepSeek 联调、平台受控安装/构建/反馈修复、生成应用启动、同快照预览/下载和工作台仍需后续验收。`SOURCE_SAVED` 只表示源码保存，不表示构建或业务功能通过。

## 源码与增量开发

1. 对新项目用 `ProjectAgentInitialization.initialize(owner, project, key)` 初始化 `full-project-v1` 的 35 文件完整模板，等待源码操作 `COMMITTED`。初始化不调用模型，也不生成用户业务。
2. 用 `ProjectAgentService.submit(owner, project, input, key)` 提交需求，绑定当前源码版本 UUID 和修订号。平台检查维护者资格、项目所有权、归档状态、幂等键和编辑租约。
3. Worker 从该版本创建普通文件副本。每个任务启动新的 CLI 会话，不依赖个人会话或 `--resume`。模型可以读取旧文件并修改业务前端、Java 接口及追加 SQL。
4. 平台确认自有进程和 I/O 结束，扫描并封存候选，再检查项目归属与基线，保存新的 Git/数据库版本关联。失败保留原版；没有修改则返回 `NO_CHANGES`。
5. 下一轮绑定刚保存的版本和修订号。正式 Git 仓库不直接交给 Agent，历史版本不会被模型覆盖。

源码和非敏感配置在磁盘 Git；PostgreSQL 保存版本引用、任务、事件、额度与租约，任务提示也单独持久化，不将整工程放入一个字段。目录结构、文件限制与备份方式见[源码维护说明](project-source.md)。现有 UTF-8、512 文件、单文件 2 MiB、整树 16 MiB 限制继续生效。

旧三文件项目不自动迁移；旧预览、下载、发布和数据不变。显式导入只保留旧兼容实现，不等于自动改写为原生业务后端。

## 配置与启用前提

这些变量由 `backend/src/main/resources/application.yml` 映射到 `atoms.agent.*`；任务保存当次输出、轮数和期限预算，调度并发修改后需重启。不要为试验修改个人 Claude 设置或日常平台的私有 env。

| 环境变量 | 默认值及含义 |
|---|---|
| `PROJECT_AGENT_ENABLED` | `false`；关闭时不注册 Agent 调度和正文清理组件 |
| `PROJECT_AGENT_LIVE_ENABLED` | `false`；正式 Claude 适配器另行检查此开关 |
| `PROJECT_AGENT_CLI` | 空；维护者指定的 CLI 可执行文件绝对路径 |
| `PROJECT_AGENT_STATE_ROOT` | 空；专用私有状态根绝对路径，不与源码根/工作根重叠 |
| `PROJECT_AGENT_MAINTAINER_IDS` | 空；逗号分隔、不重复的账号 UUID，不是邮箱地址 |
| `PROJECT_AGENT_MAX_LIVE_RUNS` | `0`；持久的付费启动意图总上限，不按重启清零 |
| `PROJECT_AGENT_MAX_OUTPUT_TOKENS` | `128000`；有效范围 1–128000 |
| `PROJECT_AGENT_MAX_TURNS` | `24`；有效范围 1–24 |
| `PROJECT_AGENT_TASK_TIMEOUT_SECONDS` | `1800`；有效范围 1–1800，出队后共享期限 |
| `PROJECT_AGENT_QUEUE_TIMEOUT_SECONDS` | `300`；有效范围 1–300，排队等待期限 |
| `PROJECT_AGENT_MAX_CONCURRENT_TASKS` | `4`；有效范围 1–8，独立项目可同时执行 |

同时要求 `PROJECT_SOURCE_ENABLED=true`，并配置 `PROJECT_SOURCE_ROOT`、`PROJECT_SOURCE_WORK_ROOT`、`PROJECT_SOURCE_GIT`、`PROJECT_SOURCE_PYTHON`，参见源码说明。继续使用 Java 17；本机离线验证为 Oracle 17.0.5。CLI 精确兼容版本为 2.1.302 / 2.1.303，未知版本拒绝启动；不能只修改版本白名单跳过契约验收。

维护者授权和项目所有权是两次独立校验。真实模式拒绝 root；不同目录、环境白名单和端口都不是 OS 沙箱，不得以此向任意公开用户开放不可信代码执行。

配置开关不等于授权本次实际收费调用。PA-LIVE 仍需指定无秘密测试项目、最多启动次数和可接受费用边界；旧生成器的 10000 次额度、163840 tokens 设置不适用于此模块。一次 CLI 会话可能发多次模型请求，启动额度不是 API 次数或金额硬上限，失败不自动退还已预留额度。

### 模型和个人配置隔离

正式适配器固定 DeepSeek Anthropic 端点 `https://api.deepseek.com/anthropic`，CLI 模型参数 `deepseek-flash[1m]`，effort 为 `high`。离线请求中已观察到实际模型 `deepseek-flash` 和 `max_tokens=128000`；这不是 DeepSeek 在线服务行为的验证。

只有通过真实模式准入后才读取 `DEEPSEEK_API_KEY`，通过子进程环境提供，不写到工程或 settings 文件。每任务拥有独立 `CLAUDE_CONFIG_DIR` 与临时目录；目录 0700、标记 0600，不接管未知状态根、不修改个人 `.claude`、登录、OAuth、shell profile 或 `HOME`。

环境白名单不继承平台数据库、SMTP、Cookie、个人认证、代理和注入配置。CLI 自动更新与非必要流量关闭，API 参数超时为 300000 毫秒；整轮任务仍服从共享截止时刻。机器受管策略冲突时拒绝，不绕过管理员设置。

### 并发与工具

唯一数据库协调者管理默认 4 个独立任务，待处理队列上限 8；每个任务有自己的进程、执行 UUID、工作副本和私有配置。同一项目同时只允许一个编辑，后续需求需在上一轮结束后基于最新版本提交，不排队修改过期基线。

模型工具仅有 `Read / Glob / Grep / Write / Edit`。Bash、MCP、子 Agent、外部目录及未验收插件拒绝；不能用 Shell 绕过删除/重命名限制。依赖可写入清单，但此阶段不会替模型安装、构建或运行工程。工程自带的构建脚本也不等于平台已经管理这些执行。

## 状态、查询与取消

内部查询入口为 `ProjectAgentService.get`、`AgentQuery.list/events`，取消为 `ProjectAgentService.cancel`。没有对应公开 HTTP 或工作台按钮；不要让客户端传入服务器路径、命令或自报进程退出凭据。

```text
QUEUED → PREPARING → RUNNING → VALIDATING → ACCEPTING → SOURCE_SAVED
                     ├─ 无实际修改 → NO_CHANGES
                     ├─ 已确认结束 → FAILED / CANCELLED / TIMED_OUT
                     └─ 缺少可靠证据 → NEEDS_RECONCILIATION
```

取消先持久记录请求，由拥有该进程的 Worker 收尾；刚点取消不代表已经退出。源码提交已经发生时以 `SOURCE_SAVED` 为准，不能把成功发布伪报成取消。待核对不是普通终态，不释放编辑租约，也不自动重试模型。

事件包含有界阶段、稳定错误码及数值用量，不含原始模型正文、源码、thinking 或 stderr。每任务最多 4096 事件/256 KiB；查询每页最多 100。已释放租约的终态满 30 天后，清理任务提示副本和事件正文，保留版本、幂等及结算审计。启用后首次 60 秒、随后每 300 秒清理最多 100 条；活动/待核对记录不清理，磁盘证据没有自动 GC。

## 排查与恢复

| 症状/错误 | 维护动作 |
|---|---|
| `AGENT_DISABLED` / `AGENT_LIVE_DISABLED` | 核对分别对应的开关与批准范围；不要为了消除报错直接开启真实调用 |
| `AGENT_CONFIGURATION_INVALID` / `AGENT_CONFIGURATION_CHANGED` | 核对绝对路径、UUID、数值范围及任务绑定配置；不悄悄使用新参数重放旧任务 |
| `SOURCE_EDIT_BUSY` / 基线冲突 | 查询同项目任务及当前源码版本；不强制覆盖或删锁 |
| `AGENT_CLI_UNSUPPORTED` / `AGENT_CLI_CHANGED` | 核对版本与文件身份，重新做离线契约验证；不自动升级或换宽权限模式 |
| `AGENT_MODEL_INCOMPLETE` / 协议、工具、路径拒绝 | 保留原版与失败任务；先查看稳定错误码，不把模型完成声明当作新版本 |
| `AGENT_STATE_CAPACITY` / `AGENT_STATE_UNAVAILABLE` | 核对私有目录标记/权限和空间；逻辑上限 1 GiB、余量 512 MiB，不自动删历史 |
| `AGENT_PROCESS_UNSETTLED` / `AGENT_EXECUTION_UNCERTAIN` | 停止新调度，保留执行意图、租约及目录；按下文核对，不凭 PID 强制解除 |
| `AGENT_COORDINATOR_UNAVAILABLE` | 核对源码协调者就绪、数据库独占会话与未决任务，避免同时启动第二个拥有者 |

先关联 `agent_task_finished` 的 requestId（任务 UUID）、projectId、状态、错误码和耗时。`owned_process_unsettled` 另含 executionId 与固定 reason：后代数量/身份/枚举异常、进程退出超时、I/O 收尾超时或收尾异常。上述结构化日志不记录提示、绝对路径、命令、密钥或原始异常。

维护者以只读方式核对 `agent_tasks`、`source_edit_leases`、关联 `source_operations/source_versions`：任务/执行/租约关联、`execution_pending`、停止摘要、基线、源码提交是否存在。PID 不证明进程身份或已停止；候选文件存在也不证明提交成功。当前没有管理员强制解锁入口，不得删除审计、伪造停止摘要或替换一个无关进程的凭据。

启动时先恢复源码，再恢复 Agent；未执行者结算中断，已提交者补齐版本结果，退出不明者继续待核对，不重新调用模型。若存在未决任务，协调者保守拒绝启动。隔离测试 schema 的成功不能证明旧未决执行已被结算；两份历史测试失败审计仍保留在验证记录中。

## 安全停止、备份与下一步

正常停止平台时，先停 Agent 调度和自有 Worker，之后停源码协调者。自有进程与 I/O 使用原固定 10 秒收尾预算，重复中断不重置；协调者最多等待 Worker 30 秒，无法证明结束则保留阻断。不要先停止源码存储，再强行释放正在编辑的租约。

在确认停写的同一窗口备份 PG、正式 Git、工作副本和 Agent 私有证据，保留目录标记、权限及版本映射；密钥配置另行保管，不能放入源码 ZIP。关闭 `PROJECT_AGENT_ENABLED` 只停止后续能力，不删除表/历史，也不会自动解除未决任务。代码回退不能绕过新租约保护。

后续实施顺序：受控依赖安装/测试/构建及错误反馈 → 本机应用进程 → 同快照预览/源码下载 → 工作台文件树与增量入口。代码恢复不回滚业务数据库；正式开放前仍需核对模型组合许可和不可信代码执行隔离。详见[能力地图](../FULL-PROJECT-CAPABILITY-MAP.md)与[ADR-016](decisions/016-full-project-source-and-coding-agent.md)。

给下一模块的交接约束：

- 构建输入必须是已发布源码版本及树摘要，通过源码模块授权读取；不能直接使用仍可修改的 Agent 工作目录。当前 `SourceQuery` 为包内接口，应增加窄读取门面，不暴露任意磁盘路径或 Git 对象。
- 旧 `workspace/NativeBuildProcess` 与 `instances/RuntimeEngine` 服务于固定模板，不能仅复用其“构建成功”字段就宣称任意 Java 工程兼容。新构建结果需绑定源码版本、工具/依赖策略和实际产物摘要。
- 平台自己控制安装、测试、构建步骤；可修改的工程脚本、npm scripts、Maven 插件和生成代码仍具有本机执行风险。审阅依赖来源与脚本策略、维护者 opt-in、最小运行环境都是前置条件。
- 构建失败只产出有界脱敏诊断，不能替换上一可用预览。反馈修复需要显式的新 Agent 任务及基线；不得无限自动重试，也不能绕过真实调用预算。
- 运行配置和业务数据库不进入源码快照。下载交付绑定实际预览的版本与构建身份，不用最新模板重新组装后冒充同一构建；不承诺不同数据库里的业务数据相同。
