# 完整工程构建与本机运行维护说明

状态：2026-10-27，EX01–30 与 C-EX10 的内部构建、运行、升级、故障和兼容验收已完成。新模式默认关闭，工作台、预览代理和同快照 ZIP 尚未接入。本文描述内部能力，不能用来宣称当前网页已交付完整后端代码生成。最新结果见[验证记录](project-execution-verification.md)，范围见[规格](../SPEC-project-execution.md)和[任务清单](../tasks/project-execution/todo.md)。

## 工作流程

1. 从 `project-source` 读取所有者指定的不可变 Vue/Java/SQL 版本。源码在本地 Git；PG 保存版本引用、摘要、任务与权限。
2. 如需补充获准依赖，提交 lock-only 准备任务。平台运行固定 npm 命令，只允许锁文件变化，成功后保存新源码版本；Agent 不获得 Shell 权限。
3. 对明确版本入队构建。任务持有独立工作目录，依次验证工具和输入，运行依赖安装、前端类型/lint/测试/build、Java 测试/package。所有阶段共享总期限。
4. 检查源码、工具、报告和静态资源后封存 JAR 与清单。失败不覆盖旧成功产物；保存源码和构建成功是两个状态。
5. 选择成功构建及项目业务库绑定启动本机 Java 进程。启动前检查源码/JAR 内 SQL 与实际数据库历史，需要升级批准时先阻断。
6. 原 worker 持有进程并验证 HTTP 健康，才记录 READY。停止和换版仍由该拥有者执行；换版先确认旧进程停止，再启动新构建。

两个真实待办应用已验证独立端口/进程/业务库、HTTP 增改查、V2 前后端变化和重启后数据保留。样本使用真实业务 Java/SQL，不依赖平台通用数据 SDK；它是验收样本，不会自动加入每个新用户项目。

## 配置入口与前提

配置定义在 [application.yml](../backend/src/main/resources/application.yml)。`PROJECT_EXECUTION_*` 独立于原固定模板的 `BUILD_*` 开关。当前没有运行这些内部操作的公开 HTTP API 或命令行工具；只设置开关不会给网页增加按钮。

启用前须已完成源码模块配置、维护者 UUID 授权、准确工具/依赖策略、平台迁移和项目测试库配置。源码、Agent 工作区、执行根、工具与私有凭据目录须分离。维护者名单不代替项目所有权检查，归档项目不能新增执行。

| 环境变量 | 默认值 / 用途 |
|---|---|
| `PROJECT_EXECUTION_ENABLED` | `false`；关闭时不加载执行资源 |
| `PROJECT_EXECUTION_MAINTAINER_IDS` | 空；逗号分隔的规范 UUID，启用时必填 |
| `PROJECT_EXECUTION_ROOT` | 私有绝对路径，保存构建、封存产物和运行目录 |
| `PROJECT_EXECUTION_NODE` / `NPM` / `JAVA` / `MAVEN` | 四个实际工具的绝对路径，执行前校验身份 |
| `PROJECT_EXECUTION_POLICY` | 源码树外私有依赖批准 JSON 的绝对路径 |
| `PROJECT_EXECUTION_TEST_DATABASES` | JSON 对象：项目 UUID → 私有测试库配置文件绝对路径，最多 4 项 |
| `PROJECT_EXECUTION_RUNTIME_DATABASES` | 同格式的业务库映射；可为空，项目必须已有测试库映射 |
| `PROJECT_EXECUTION_MAX_CONCURRENT_BUILDS` | `1`，范围 1–4；构建和准备共用名额 |
| `PROJECT_EXECUTION_QUEUE_CAPACITY` | `8`，范围 1–8 |
| `PROJECT_EXECUTION_MAX_RUNNING_PROJECTS` | `4`，范围 1–4；常驻运行独立于构建名额 |
| `PROJECT_EXECUTION_TASK_TIMEOUT_SECONDS` | `1800`，范围 1–1800；构建/准备整轮期限 |
| `PROJECT_EXECUTION_QUEUE_TIMEOUT_SECONDS` | `300`，范围 1–300 |
| `PROJECT_EXECUTION_START_TIMEOUT_SECONDS` | `120`，范围 1–120；READY 后不受启动期限限制 |

单次工具探测 30 秒、进程收尾 10 秒；执行根逻辑容量 8 GiB、最小空闲 1 GiB、单 JAR 256 MiB。以上不是操作系统硬配额。源码沿用 512 文件、单文件 2 MiB、整树 16 MiB 限制。原模型请求的 token/超时设置不由这些执行配置控制。

已验收工具范围为 Node 24.20.x、npm 11.19.x、Java 17、Maven 3.9.11，具体还须满足工具身份和依赖策略。实际本地验收使用项目内隔离 JDK 17.0.14；没有修改个人 Oracle 17.0.5、SDKMAN 或服务器配置。获准依赖策略见 [seed-dependency-policy.json](../backend/src/main/resources/projectexecution/seed-dependency-policy.json)。它是批准清单，不允许项目自行增加任意 Maven 插件、npm 安装脚本或自定义终端命令。

## 数据库配置与数据边界

每个项目分别配置专用测试库和业务库。使用不同低权限角色，拒绝平台库、超级用户、同项目测试/运行混用和不同项目共用绑定。配置加载还会检查真实数据库与角色身份、用途标识、schema 和权限；仅把密码文件改名并不能完成绑定。

私有 `.properties` 文件必须位于上述工作根之外，父目录 0700、文件 0600，不允许符号链接、额外键或环境变量插值。字段为：

| 字段 | 含义 |
|---|---|
| `bindingId` | 该用途的固定 UUID |
| `purpose` | 测试为 `test`，业务为 `runtime` |
| `database` / `user` / `password` | 独立数据库与角色凭据 |
| `jdbcUrl` | 已批准的 loopback PostgreSQL 目标 |
| `schema` | 固定为 `generated_app` |
| `port` | 仅业务配置存在，1024–65535 的应用端口 |

不要把密码放入源码、CLI 参数、模型上下文、诊断或 ZIP。测试库只交给获准的 Java 测试阶段，业务库只交给对应运行进程。配置文件变化会使旧授权失效；不得直接手改绑定元数据来绕过检查。

源码恢复、JAR 切换和业务数据恢复互不等价。下载源码不包含平台账号、对话、密钥或业务库备份。恢复旧版本前仍检查它是否兼容当前数据库；不自动执行 Flyway clean/repair 或向下迁移。

## 内部接口与状态

以下均为后端模块接口；部分入口仅包内可见，供后续交付模块接线，不是可直接调用的网页路由。

| 入口 | 职责 |
|---|---|
| `ProjectExecutionService.submit/get/cancel` | 固定源码版本构建、查询、取消；提交具有幂等键 |
| `DependencyPreparationService.submit/get/cancel` | 按基线版本及 revision 准备依赖，成功追加源码版 |
| `ExecutionMigrationReview.inspect/approve` | 展示与批准准确项目/构建/数据库历史/目标迁移组合 |
| `ExecutionCoordinator.startApplication/replaceApplication/stopApplication` | 常驻本机进程调度；不按客户端 PID 或命令操作 |
| `ExecutionRunReconciliation.inspectStoppedFailure/acknowledgeStoppedFailure` | 对原拥有者确认已停止但未 READY 的失败执行作明确核对 |
| `ProjectExecutionAccess.build/running/identity/source` | 签发内部句柄并逐次核对源码、产物及运行身份 |
| `ExecutionQuery.tasks/events` | 按所有者、项目、任务类型分页查询状态和白名单诊断 |

构建的阶段为 QUEUED → PREPARING → INSTALLING → CHECKING → PACKAGING → SUCCEEDED；失败、取消、超时另记。缺少停止证明或提交结果不明会进入待核对状态并保留占位，不能将其显示为已完成。运行的 READY 必须有实际进程和健康响应，数据库中的 READY 字样本身不构成运行证明。

同一项目的准备、构建和换版须通过现有互斥检查；不同项目可按配置并行。仅 QUEUED 的未领取任务能直接取消并释放名额，正在执行的任务等待原 worker 确认停止。关闭协调器先阻止新工作，再收尾自有进程；未知执行不自动释放。

分页最大 100 条，单任务最多 1000 事件 / 64 KiB。错误码、状态、阶段和计数可以返回；命令、路径、凭据、源正文及原始 stdout/stderr 不进入查询 DTO。当前不会根据失败自动调用付费模型；后续修复消费者也不能直接拿原始报告作为模型输入。

## 迁移失败与恢复

迁移批准只针对准确的构建 JAR、数据库身份、当前历史摘要和目标迁移摘要。任何一项变化都须重新核对。未批准、历史校验不匹配或旧代码不兼容时拒绝启动。

已实际启动但未 READY 的进程，即使确认停止，也不能推断数据库没有发生变化。原协调器保留对应失败 job；维护者通过核对入口重新读取源码/JAR/私有绑定/实际迁移历史并明确确认。确认在短事务中追加不可修改审计，同时将该运行结束为 FAILED；保留原错误及停止证据，只释放对应任务的资源。提交回执不明时不自动重写；同幂等键的明确重试可读取原审计并完成原拥有者的释放。

此入口要求原 worker 返回的准确停止记录，不能用 PID、数据库状态或重启后的新进程推造证明。它不修改业务表、不回滚 SQL，也不解除其他未决运行。适用范围及拒绝条件见[失败迁移核对设计](design/project-execution/failed-migration-reconciliation.md)。

## 验证与后续交付

普通平台 `verify` 默认排除真实工程和安装版 CLI 契约。实际契约分别使用 `project-execution-contract`、`agent-cli-contract` profile，要求显式批准的工具、目录和测试数据库；缺参数失败，不能按跳过算通过。完整命令、失败记录和逐批次通过数见[验证记录](project-execution-verification.md)及[计划 §6](../tasks/project-execution/plan.md#6-验证命令与证据规则)。不要在未知数据库上复制运行真实迁移契约；已有 A/B 业务验收库的 V2 历史和数据须保留。

已有真实证据包括原生种子构建、真实 npm 锁准备、两个待办应用 V1/V2 前后端构建、Java HTTP 与 PostgreSQL、重启持久化、失败迁移明确确认和兼容版本重新启动。受控 JVM/Node 故障测试、离线假上游 CLI、浏览器测试和真实业务验收分别记录，不能相互替代。

后续仍需 `project-delivery` 接入实际运行预览和同源码快照 ZIP，`project-workbench` 展示文件树、差异、任务、构建和运行；真实 DeepSeek 联调与远程部署另行验收。当前用户网页继续使用原固定模板链路。

本机普通进程不隔离同一系统用户的文件和网络权限。当前仅供受信维护者控制的工程验证，不能据此向公开访客开放任意生成后端执行。
