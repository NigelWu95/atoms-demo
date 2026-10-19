# project-agent · 开发任务

状态：2026-10-19，用户要求一次完成完整本地开发闭环，范围更新见规格 §10。PA01–PA30 已完成对应内部切片：默认并发 4、查询/保留、原生 Java/Vue 工程、打包/初始化、完整工程两轮增量及非法工具/候选拒绝。真实 CLI 已补充当前 2.1.303 的精确兼容，最近定向回归 17 单元 + 14 集成通过；实际业务构建运行、工作台及真实 DeepSeek 仍未验收。下一项 PA31 中断/并发恢复；必须处理 PA29b 夹具留下的合成待核对记录，不得删证据或跳过协调者回归。PA-LIVE 授权询问尚无答复，不作已授权处理。唯一依据为 [已确认规格](../../SPEC-project-agent.md)和[本模块计划](plan.md)。不重复使用旧 EB/PS 清单，不将旧人工验收勾为完成。

每项 S=1–2、M=3–5 个主要文件（含测试）；实际超出先拆子任务，不藏进巨型文件。路径缩写：A=backend/src/main/java/dev/atoms/demo/agent，AT=backend/src/test/java/dev/atoms/demo/agent，S/ST=对应 source 包，M=backend/src/main/resources/db/migration，T=templates/full-project-v1。Agent 包和原生后端模板目录已创建。U/I/CLI/ALL/PY/FRONT/BROWSER/DOC 命令定义在计划 §6。

每项还需满足项目 Definition of Done：新行为有红绿测试、运行证据与失败路径、原功能回归、不泄漏秘密、记录限制。模板静态测试和假模型不是应用可运行证明。检查点不授权新费用、执行用户代码、合并或部署。

## PA01 · 默认关闭的 Agent 配置

- [x] 完成 PA01。依赖：用户批准本计划。规模：M（4 文件）。
- 描述：建立独立配置读取，不触碰日常私有 env。
- 验收：禁用时无 I/O、无密钥读取、无进程；启用必须有 CLI/状态根/维护者 UUID；严格校验已批准的 128000 tokens、24 轮及时间预算。
- 验证：U(AgentSettingsTest)；检查关闭状态不影响既有启动。
- 主要文件：`A/AgentSettings.java`；`AT/AgentSettingsTest.java`；`backend/src/main/resources/application.yml`；`.env.example`。
- 证据（2026-10-18）：先运行 U(AgentSettingsTest)，缺少类而失败；实现后配置及源码环境 8 测试通过，全后端 `env PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH ./mvnw -B test` 218 通过、0 失败/错误/跳过。Java 17.0.5；无数据库迁移、无真实模型、无私有配置变更。默认关闭，仅新增纯配置读取；目录权限及执行准入仍由后续任务实现。

## PA02 · 固定 CLI 参数与环境

- [x] 完成 PA02。依赖：PA01。规模：M（4 文件）。
- 描述：将程序路径、argv 和环境组装为受控值，先验证能力。
- 验收：不拼 shell、不重设 HOME、不继承平台秘密；拒绝未知版本/文件身份变化且不自动升级；没有 Shell/MCP/子 Agent/个人会话或 fallback 入口。
- 验证：U(AgentInvocationTest,AgentCliProbeTest)；用 canary 环境断言白名单。
- 主要文件：`A/AgentInvocation.java`；`A/AgentCliProbe.java`；`AT/AgentInvocationTest.java`；`AT/AgentCliProbeTest.java`。
- 证据（2026-10-18）：新增测试先因缺类失败，实现后 U(AgentSettingsTest,AgentInvocationTest,AgentCliProbeTest) 11 通过、0 失败/错误/跳过。固定 argv、精确环境白名单、版本拒绝、同长度/时间戳内容篡改及文件权限反例通过；CLI 实际探测和工具往返在 PA03 验收，不以纯参数测试冒充。

## PA03 · 真实 CLI 的最短离线文件往返

- [x] 完成 PA03。依赖：PA02。规模：M（3 个夹具/profile 文件，另更新 PA02 调用参数及单测）。
- 描述：用本机 CLI 和 loopback 假 Anthropic 服务验证一次 Read→Edit→成功结果。
- 验收：使用假 Key 与新临时副本，真实文件发生预期修改；捕获认证方式、模型、工具列表和有效 max_tokens，未达目标即失败而非改期望；仅显式测试 profile 运行，测试夹具不进正式 JAR。
- 验证：CLI；检查实际请求及文件字节；缺 CLI 或协议不兼容不得跳过算通过。
- 主要文件：`AT/AgentProtocolFixture.java`；`AT/AgentCliContractIT.java`；`backend/pom.xml`。
- 阻断证据（2026-10-18）：真实 CLI 2.1.302、假 Key、仅 loopback 服务，逗号分隔及分立 argv 两种写法均完成 Read→Edit→成功 result/退出，文件由 `before\n` 变成 `after\n`，无 permission_denials。每次 3 个请求，认证为 x-api-key、模型 deepseek-flash、max_tokens=128000。但每个请求只包含 Edit/Read，缺少计划要求的 Glob/Grep/Write；CLI 契约测试 **2 失败、0 错误/跳过**，不得下调期望冒充通过。11 个 Agent 单元测试通过；完整旧后端单元回归 224 通过。详细命令与后续选择见 [离线验证记录](../../docs/project-agent-verification.md)。
- 处理更新（2026-10-19）：用户批准完整闭环实施，正式 `safe-mode + restricted` 已通过四项真实 CLI 离线测试：两种参数格式、五种文件工具的实际执行、目录外/链接访问拒绝；项目和私有配置 CLAUDE.md canary 未进入请求。保留全部五种工具要求，不开放 Shell/MCP，不安装/升级 CLI。224 单元 + 4 CLI 集成测试通过，0 失败/错误/跳过，构建成功；命令及限制见验证记录。

### C-PA01 · CLI 最短往返

- [x] C-PA01：前三项实际通过，保留原 bare 失败证据，正式参数完成 224 单元 + 4 CLI 集成测试及构建。启动方式调整已获批准；未新增 Shell/MCP 权限。完整隔离和真实模型尚未验收，继续 PA04。

## PA04 · 任务私有目录

- [x] 完成 PA04。依赖：PA01、PA03。规模：M（4 文件）。
- 描述：建立 config/tmp 与所有权标记，分离源码和运行资料。
- 验收：专用绝对根、目录 0700/文件 0600，不接管未知目录；拒绝重叠根、链接/替换及 root 真实执行；容量不足拒绝新任务，不自动删除证据。
- 验证：U(AgentStateDirectoryTest,AgentEnvironmentTest)；临时目录权限与替换反例。
- 主要文件：`A/AgentStateDirectory.java`；`A/AgentEnvironment.java`；`AT/AgentStateDirectoryTest.java`；`AT/AgentEnvironmentTest.java`。
- 证据（2026-10-19）：两组测试先因缺类失败；实现后 5 个目录测试 + 3 个环境测试通过。连同已有 Agent/源码环境共 22 单元、4 真实 CLI 离线测试通过，构建成功。覆盖关闭无 I/O/密钥读取、0700/0600、未知/重叠/链接根、目录替换、伪造句柄、重复任务、标记篡改、容量拒绝保留证据和 root 真实执行拒绝。配置与任务目录不构成 OS 沙箱。

## PA05 · 有界事件解析

- [x] 完成 PA05。依赖：PA03。规模：M（4 文件，另增强既有 CLI 夹具）。
- 描述：将 CLI 事件解码为内部结果和平台阶段，不保存原始流。
- 验收：4 MiB/行、64 MiB stdout、4096 事件等预算按规格执行；缺失/冲突终态、未知终态或权限错误不能成功；丢弃源码、thinking、绝对路径、原始错误和任意模型文本。
- 验证：U(AgentStreamDecoderTest)；分块 UTF-8、洪泛、截断和敏感 canary 反例。
- 主要文件：`A/AgentStreamDecoder.java`；`AT/AgentStreamDecoderTest.java`；`backend/src/test/resources/agent/stream-success.ndjson`；`backend/src/test/resources/agent/stream-invalid.ndjson`。
- 证据：先缺类失败；真实 CLI 揭示 init 前 ui_invalidate 和两个内置模块元数据，精确适配版本/名称/path/source 后验证，不放行外部插件。9 个解析单测，连同环境共 31 单元 + 4 CLI 集成通过。覆盖 UTF-8 拆分、行/总量/事件限制、截断、冲突终态、未结算工具、路径及未知能力；不输出原始正文。新增 AGENTS.md canary 未进入请求。详见验证记录。

## PA06 · 自有进程生命周期

- [x] 完成 PA06。依赖：PA02、PA04、PA05。规模：M（4 文件，另复用 PA03 的两个 CLI 夹具文件）。
- 描述：将 stdin、并行读流、取消、deadline、退出证据纳入同一拥有者。
- 验收：只终止本任务持有的进程关系，不按名称/旧 PID 查杀；限制 stderr、等待及收尾，结果与退出同时满足才返回成功；无法证明自有进程全部结束时返回不确定，不发行成功结束证据。
- 验证：U(AgentProcessTest)；真实受控 Java 子进程覆盖挂起/洪泛/忽略终止/管道未关闭。
- 主要文件：`A/AgentProcess.java`；`A/AgentDeadline.java`；`AT/AgentProcessTest.java`；`AT/AgentChildFixture.java`。
- 证据：先缺类失败，9 项真实 Java 子进程测试通过：正常/非零退出、截断、两种流洪泛、过期不启动、取消不杀旁路进程、忽略 TERM、子进程占管道、stdin 阻塞、无法枚举时不发结束证据。真实 CLI 经正式进程拥有者完成 Read/Edit；40 单元 + 5 CLI 集成通过。沙箱禁进程枚举导致失败后按权限重跑，不削弱检查。

### C-PA02 · 进程与流边界

- [x] C-PA02：目录、有界流、进程生命周期通过，40 单元 + 5 CLI 集成、0 失败/错误/跳过；仅本机假服务和受控 Java 夹具。无 Shell/MCP、个人配置、真实 Key 或部署变更。仍不宣称 OS 沙箱或后台任务集成完成，继续 PA07。

## PA07 · 源码编辑租约持久化

- [x] 完成 PA07。依赖：PA06。规模：M（3 文件）。
- 描述：给项目完整编辑周期建立源模块所有的持久阻断。
- 验收：同项目仅一条活动租约且执行身份不可替换；来源、基线、任务与结束证据约束由数据库保证；预留 source_operations 到租约的可空关联，V018/V019 不改。
- 验证：I(ProjectSourceEditLeaseIT)；真实 PG 并发领取、非法更新、回退关闭验证。
- 主要文件：`M/V020__source_edit_leases.sql`；`S/SourceEditLeaseRepository.java`；`ST/ProjectSourceEditLeaseIT.java`。
- 证据：新增 V020，仅应用至 atoms_demo_test；6 项 PG 集成测试通过，覆盖两连接竞争、归属/基线、不可替换执行身份、未停写/未结算拒绝释放、数据库基线 FK 与伪造初始停止证据拒绝、开关关闭保留证据。先缺类失败；修正 Repository 不可为 final 的 Spring 代理问题后通过。旧 V018/V019 未改。

## PA08 · 所有源码写入口检查租约

- [x] 完成 PA08。依赖：PA07。规模：M（5 文件）。
- 描述：避免其他接受、恢复或导入从 Agent 编辑旁路写入。
- 验收：统一项目行锁顺序后检查租约；已排队旧操作也不能在 Worker 发布时越过新租约；持有者绑定明确而非以 task UUID 自报放行，纯读取不受影响。
- 验证：I(ProjectSourceEditGuardIT,ProjectSourceAdmissionIT,ProjectSourceRestorationIT)；两连接竞态及旧无租约行为。
- 主要文件：`S/ProjectSourceService.java`；`S/SourceRepository.java`；`S/SourceOperation.java`；`S/SourceEditLeaseRepository.java`；`ST/ProjectSourceEditGuardIT.java`。
- 证据：3 项反例先失败，包含两个竞争任务均成功；接入项目锁内租约校验后通过。新增仅已停写且绑定租约的接受操作实际写入 Git 的正例，共 4 项 guard 测试。源码/Agent 相关 107 单元 + 全部 67 源码集成通过，0 失败/错误/跳过；历史读取和取消仍可用，旧无租约行为回归通过。

## PA09 · 发行受控工作副本句柄

- [x] 完成 PA09。依赖：PA08。规模：M（3 文件，复用已完成租约 DAO）。
- 描述：向 Agent 提供窄门面，不公开整套源码包内实现。
- 验收：句柄不可由外部路径/UUID 重建，绑定 owner/project/base/lease；领取和准备期间反复核对权限与基线；无模型时可释放未启动租约，释放不自动删除目录。
- 验证：I(ProjectSourceEditWorkspaceIT)；伪造句柄、交叉账号、旧基线及工作目录替换。
- 主要文件：`S/ProjectSourceEditGateway.java`；`S/SourceWorkspaceStore.java`；`S/SourceEditLeaseRepository.java`；`ST/ProjectSourceEditWorkspaceIT.java`。
- 证据：先缺类失败，4 项真实 Git/PG 检查通过；工作副本/接受/编辑锁组合 24 集成通过，0 失败/错误/跳过。私有构造句柄按发行实例校验，拒绝跨账号、旧基线、其他发行者和替换目录；外层事务回滚撤销句柄；执行中拒绝释放，释放后文件仍保留。

### C-PA03 · 编辑互斥

- [x] C-PA03：PA07–PA09 通过，写入互斥的 107 单元/67 源码集成及副本接入后的 24 组合集成通过；仍未启动 Agent Worker 或真实模型。继续 PA10。

## PA10 · 副本与封存继承任务期限

- [x] 完成 PA10。依赖：PA09。拆为 PA10a 预算契约（3 文件）与 PA10b 副本/封存传递（5 文件），不把超出 5 文件的变更隐藏为单一切片。
- 描述：给物化、扫描及候选读取提供共享剩余预算，保留旧调用重载。
- 验收：每次步骤使用任务剩余与源码 300 秒上限中更小者；复制/检查/重读不重置时间；既有源码调用的默认时限不变。
- 验证：I(ProjectSourceEditDeadlineIT)；PY；短预算/慢文件工具注入。
- 主要文件：PA10a `S/SourceExecutionBudget.java`、`S/SourceDeadline.java`、`ST/ProjectSourceDeadlineTest.java`；PA10b `S/SourceWorkspaceStore.java`、`S/SourceCandidateStore.java`、`S/ProjectSourceEditGateway.java`、`S/SourceQuery.java`、`ST/ProjectSourceEditDeadlineIT.java`。
- [x] PA10a：预算契约与本地/上游时限取最小值，3 单元测试通过；仍需 PA10b 将契约传入实际复制与封存。异常上游预算不泄漏原始内容。
- [x] PA10b：先缺少重载编译失败，再通过 17 项副本/封存/接受集成（含 3 项期限反例），Python 文件工具 11 项通过。任务过期不创建副本、不封存/读取候选，也不把健康源码误标为存储故障；已发行句柄不能换新预算重置期限。

## PA11 · 发布队列继承任务期限

- [x] 完成 PA11。依赖：PA10。规模：M（5 文件，增加 ProjectSourceService 在数据库锁内再次检查期限，不能只在锁外检查）。
- 描述：让等待源码队列的时间也计入同一任务期限。
- 验收：封存到接受再到 Git 发布共享期限且排队不重置；过期未提交时不更换当前指针；提交已发生则报告事实，不因响应超时伪报失败或删除版本。
- 验证：I(ProjectSourceEditPublishDeadlineIT,ProjectSourceCancellationIT)；U(ProjectSourceDeadlineTest)；检查排队消耗同一 deadline。
- 主要文件：`S/SourceCoordinator.java`；`S/SourceWorker.java`；`S/SourceAcceptance.java`；`ST/ProjectSourceEditPublishDeadlineIT.java`。
- 证据：缺重载红测后，19 项队列期限/取消/接受/生命周期集成通过。排队过期不启动 Git；提交前过期保留旧指针，提交后过期重试仍返回 COMMITTED。发布事务取得行锁后再次检查预算。未启动 Agent。

## PA12 · 结束证据到新源码版本

- [x] 完成 PA12。依赖：PA06、PA09、PA11。拆为 PA12a 进程凭据绑定（执行拥有者、Agent 适配层及测试，3 文件）与 PA12b 源码接受门面（2 文件）。
- 接口细化：进程生命周期抽为 `execution/OwnedProcess`，由它在自有进程和 I/O 确认结束后发行私有构造的凭据，绑定 task/execution/work directory。Agent 与源码依赖该基础类，源码不反向引用 Agent；不是裸 UUID/PID/布尔 stopped，也不允许从 HTTP 创建凭据。
- [x] PA12a：凭据绑定先红测缺少新重载，10 项真实 Java 子进程测试通过；原超时、洪泛、取消、后代管道与未知进程关系反例保持通过。
- [x] PA12b：先缺少接口红测，11 项接受/期限/句柄集成通过，含新增 4 项真实进程凭据与 Git/PG 联动。重复接受只发布一次，提交后重放返回持久事实；无变化不造版本；超时仍可记录停止证据和安全释放；外层执行意图回滚不会污染句柄。
- 描述：实现执行标记、停写确认、封存接受与幂等结果交接。
- 验收：CLI 启动前持久记录执行意图，模型自报结束不能解锁；确认退出后才接受且重试返回同一源码操作；CLI 和 Git 未决标记分别保留，源操作结算后才能释放活动租约。
- 验证：I(ProjectSourceEditAcceptanceIT)；未退出封存拒绝、并发重复接受及提交响应丢失。
- 主要文件：`S/ProjectSourceEditGateway.java`；`S/SourceEditLeaseRepository.java`；`ST/ProjectSourceEditAcceptanceIT.java`。

### C-PA04 · 版本接受

- [x] C-PA04：PA10–12 实际通过，PA12b 的 11 项组合集成、PA12a 的 10 项进程测试已记录。没有真实模型或页面入口，继续 PA13，不当作整体完成。

## PA13 · 原子提交 Agent 任务

- [x] 完成 PA13。依赖：PA01、PA12。拆为 PA13a 任务结构与迁移（3 文件）、PA13b 测试模块桥接（2 文件）、PA13c 原子准入（3 文件）。执行额度的原子预留已随 PA14a 生命周期验证；不会为测试把源码内部存储类改为 public。
- [x] PA13a–c 任务准入部分：2 项输入单测、5 项 PG/Git 集成通过。V021 仅应用测试库；并发同键只建一个任务和租约，所有权/归档重新校验，外层事务失败不留句柄，8 项队列上限及第 9 项拒绝通过。还没有启动 Worker，尚未验证真实执行意图的额度预留。
- 描述：保存任务意图、幂等、队列和真实启动额度，不先调用 CLI。
- 验收：严格输入、维护者与所有权校验，队列 8/项目 1；任务与源码租约在同一事务准入，失败不遗留收费意图；不同请求同键拒绝，重复请求不启动第二任务，不借用旧 generation_budget。
- 验证：I(ProjectAgentAdmissionIT)；真实 PG 并发/额度/归档/当前所有者变化。
- 主要文件：`M/V021__project_agent_tasks.sql`；`A/AgentTaskRepository.java`；`A/AgentTask.java`；`A/ProjectAgentService.java`；`AT/ProjectAgentAdmissionIT.java`。

## PA14 · 首条编辑保存链路

- [x] 完成 PA14。依赖：PA05、PA06、PA12、PA13。拆为 PA14a 生命周期/额度（4 文件）、PA14b 真实适配器（3 文件）、PA14c Worker 与双轮旅程（5 文件）。
- [x] PA14a：3 项新增生命周期/额度集成和 5 项准入回归通过。模拟付费意图的额度与源码执行意图原子提交；额度不足回滚源码执行标记；失败不退额度；未知退出不能转终态或解锁。没有网络模型调用。PA14b 前增加短切片修复 CLI 探测有界读流，复用已验证进程拥有者，避免版本探测在后代占管道时无界等待。
- [x] PA14b 前置探测：有界版本探测、共享 hash 检查期限与后代管道反例通过。首次 CLI 命令写错属性名，5 项失败未计通过；改正 `atoms.agent.test.cli` 后，44 单元 + 5 真实 CLI 离线集成通过。
- [x] PA14b：一任务一次 Session，私有配置、固定 DeepSeek 端点和模型参数，权限/关闭/超时/预算变化先于读取密钥或目录；使用既有进程拥有者和绑定凭据。2 项适配器准入单测包含在上述 44 单元内。真实适配器尚未调用 DeepSeek，完整 Worker 联动仍待 PA14c。
- [x] PA14c：缺 Worker 红测后，11 项 Worker/准入/生命周期集成通过。两个真实受控 Java 编辑进程分别改 Vue/Java/SQL，真实 Git 连续保存，未改文件保持字节不变；重放不再执行，无变化不造版本，非法文件/截断结果保留旧版本。仅假适配器链路，尚非付费 DeepSeek、构建、运行或 UI 验收。
- 描述：用可替换适配器连接已有源码版、真实副本、任务阶段和最终源版本。
- 验收：阶段与一次性执行身份持久化，事务外运行 CLI；严格成功判定后接受、NO_CHANGES 不造新版本；确定性假 CLI 修改 Vue/Java/SQL 后可从真实 Git 重建。
- 验证：I(ProjectAgentWorkerIT)；从当前版启动两次、失败保留旧版。
- 主要文件：`A/AgentWorker.java`；`A/CodingAgent.java`；`A/ClaudeCodeAgent.java`；`A/AgentTaskRepository.java`；`AT/ProjectAgentWorkerIT.java`。

## PA15 · 取消与发布竞态

- [x] 完成 PA15。依赖：PA14。拆为 PA15a 源码取消标记及锁顺序（4 文件），PA15b Agent 取消与提交事实（4–5 文件），PA15c 中断收尾。源码取消必须持久化并由发布事务核对；只加一个任务布尔值不能阻止已排队的 Git 发布。取消/启动意图不等待源码文件 I/O 的 JVM 监视器，避免项目数据库锁与文件操作锁反向等待。
- [x] PA15a：新增 V022（只到测试库），17 项取消/副本/接受集成通过。文件复制受控阻塞时，持有项目行锁的取消仍在 2 秒内返回；提交前取消不换指针，提交后返回 COMMITTED；取消标记不能回退。准入容量改用非阻塞信号量与并发句柄表，事务回滚返还容量。
- [x] PA15b：先补测试夹具（2 文件），再改任务取消/生命周期/持久化和竞态测试（4 文件）。新增取消 4 项与相关回归共 15 项通过；运行中先请求取消，已提交事实优先于超时/取消。
- [x] PA15c：启动前失败签发无进程凭据，中断后的数据库收尾临时清除中断标记并最终恢复。先验证缺接口及 JDBC 收尾带中断的红测；组合 18 单元 + 15 集成通过。
- 描述：区分取消请求与已确认停止，不覆盖已提交事实。
- 验收：排队取消不启动模型，运行取消传递给自有进程；提交前取消阻断版本接受，提交后返回 SOURCE_SAVED；无法确认结束保留 NEEDS_RECONCILIATION 和租约。
- 验证：I(ProjectAgentCancellationIT)；精确屏障控制取消前/后两种顺序。
- 主要文件：`A/ProjectAgentService.java`；`A/AgentWorker.java`；`A/AgentTaskRepository.java`；`AT/ProjectAgentCancellationIT.java`。

### C-PA05 · 任务主流程

- [x] 主流程离线验证通过，具体数量与红绿测试见验证记录；未扩大工具/模型权限、未启用公网或 UI，不把源码保存当作构建运行成功。

## PA16 · 重启与未决结果恢复

- [x] 完成 PA16。依赖：PA15。规模：M（4 文件，含 SourceEditLeaseRepository 的持久待核对标记）。恢复不重建可写句柄、不复用旧目录，仅结算数据库中可证明已停止的记录；4 项恢复及相关回归通过。
- 描述：以持久证据恢复任务状态，不把目录存在或 PID 消失当作已完成。
- 验收：执行意图已写但退出未知不重跑；源操作已提交可补齐任务结果且不重复计费；归属变化仍保留必要阻断，不向旧账号暴露数据。
- 验证：I(ProjectAgentRecoveryIT)；重新实例化协调者/连接，覆盖启动和发布中断窗口。
- 主要文件：`A/AgentRecovery.java`；`A/AgentTaskRepository.java`；`S/ProjectSourceEditGateway.java`；`AT/ProjectAgentRecoveryIT.java`。

## PA17 · 单协调者与有界调度

- [x] 完成 PA17。依赖：PA16。规模：M（分配置、调度、回归切片）。独立协调租约、多 Worker、停止与队列过期已接通；用户追加默认 4（可配置 1–8），10 项调度测试及相关回归通过。
- 实施拆分：PA17a 数据库独占租约及关闭/丢失证明（2 文件）；PA17b 调度循环、队列查询、阶段排序和 Worker 停止信号（5 个实现文件及单独集成夹具/测试）。关闭和失去租约都阻止新执行；任何未决执行阻止新协调者自动接单。
- [x] PA17a：2 项专用测试库集成通过；单个调度器不等于单个 Claude 会话，Worker 并发调整独立处理。
- [x] PA17b 前置：源码 phase 降至 MAX_VALUE-100，Agent 采用 MAX_VALUE；门面提供只读就绪信号，4 项原源码生命周期回归通过。
- [x] PA17b/c：默认 4 及显式 1/3 个任务同时运行；工作路径、执行 ID、PID 各自独立。达到上限才等待，取消后补位；同项目拒绝并发修改。显式 2 个任务关闭、调度租约丢失、异常收尾、重复领取与数据库时钟域过期通过；未启用真实模型。
- 描述：接入 Spring 生命周期，先完成源码和 Agent 恢复再接收执行。
- 验收：唯一数据库协调者，Worker 默认 4、可配置 1–8，不同项目并行且同项目互斥；队列 300 秒过期不启动；先启源码后启 Agent、先停 Agent 后停源码，不凭相同 phase 假设顺序；正常关闭有界，所有自有进程停止前不提前丢租约。
- 并发调整切片：配置范围与默认值测试 → 有界多 Worker 及移除全局执行锁 → 真实测试子进程验证 1/3/4 个运行槽位、溢出排队、取消补位、同项目冲突、关闭/租约丢失 → 回归与文档。不得启用真实付费调用或修改个人 Claude 配置。
- 验证：I(ProjectAgentCoordinatorIT)；双协调者、租约丢失、队列过期、关闭竞态。
- 主要文件：`A/AgentCoordinator.java`；`A/AgentCoordinatorLease.java`；`A/AgentTaskRepository.java`；`AT/ProjectAgentCoordinatorIT.java`；`S/SourceCoordinator.java`。

## PA18 · 授权查询与阶段事件

- [x] 完成 PA18。依赖：PA13、PA17。规模：M（4 文件）。
- PA18a 查询/事件存储可先独立验证，不依赖调度循环；PA18b 再接状态迁移和 Worker 用量，最终调度旅程仍须 PA17 验证。每片最多 5 个实现/测试文件。
- [x] PA18a：5 项查询/存储集成通过，当前所有权与 100 项分页、事件容量和终态预留、明确过期/缺损错误、不泄漏非白名单 payload。各阶段的自动写入由 PA18b 补齐。
- [x] PA18b：状态与事件同事务写入，确认模型进程停止后记用量和访问路径数；重复阶段不重复插入。补验自动调度的 QUEUED → PREPARING → RUNNING → VALIDATING → ACCEPTING → SOURCE_SAVED 完整有序阶段；与授权查询、保留及脱敏合计 11 项集成通过。
- 描述：提供内部任务列表、快照及有界 after 游标读取。
- 验收：每次读取核对维护者及当前所有权，最多 100 条；单调序号和持久 256 KiB 预算，终态留空间；只输出平台阶段和校验统计，不直接复用旧 run SSE 或新开 HTTP 路由。
- 验证：I(ProjectAgentQueryIT)；跨账号/项目、非法游标、断档和事件量上限。
- 主要文件：`A/AgentQuery.java`；`A/AgentEventRepository.java`；`A/ProjectAgentService.java`；`AT/ProjectAgentQueryIT.java`。

### C-PA06 · 恢复与查询

- [x] PA16/17 既有 49 项集成回归和 PA18 自动调度阶段/查询补验通过；检查点已汇报，证据见验证记录。无付费模型、无新 HTTP 入口或工具权限扩大。

## PA19 · 保留期限与脱敏审计

- [x] 完成 PA19。依赖：PA18。规模：M（4 文件）。
- 描述：清理过期提示与事件正文，同时保留任务幂等和结算证据。
- 验收：终态满 30 天按有界批次清理，活动/待核对不清；旧游标明确过期，同键重试仍返回原任务；日志、事件、SQL 诊断不含 canary/源码/邮箱/原始 stderr。
- 验证：I(ProjectAgentRetentionIT,ProjectAgentObservabilityIT) 7 项通过；数据库时钟域的 719/721 小时边界、幂等与结果保留、失败回滚、并发清理及日志 canary。U(AgentRetentionSchedulingTest) 2 项通过，证明不依赖旧生成器开关。
- 主要文件：`A/AgentRetention.java`；`AT/AgentRetentionSchedulingTest.java`；`AT/ProjectAgentRetentionIT.java`；`AT/ProjectAgentObservabilityIT.java`。复用既有事件水位及过期错误，无需迁移。

## PA20 · 新模板 Maven Wrapper 来源

- [x] 完成 PA20。依赖：PA03。规模：M（5 文件）。
- 描述：从已审阅现有模板保留 Wrapper 与许可证，建立独立新模板目录。
- 验收：工具版本与来源可核对，不新下载安装器；仅 backend/mvnw 保留允许的执行位；内容满足源码策略且不带个人 Maven 配置。
- 验证：U(FullProjectWrapperTest)；逐字节来源及文件模式检查，不运行生成脚本。
- 主要文件：`T/backend/mvnw`；`T/backend/.mvn/wrapper/maven-wrapper.properties`；`T/licenses/maven-wrapper-LICENSE.txt`；`T/licenses/maven-wrapper-NOTICE.txt`；`AT/FullProjectWrapperTest.java`。

## PA21 · 原生 Java 启动骨架

- [x] 完成 PA21。依赖：PA20。规模：M（5 文件）。
- 描述：建立不依赖平台类的 Spring Boot 17 工程配置。
- 验收：仅选既有依赖/版本，保留真实项目测试目标；数据库/端口配置外置且无可用默认密码；配置示例不混入平台密钥或旧应用 iframe 运行器。
- 验证：U(FullProjectBackendSeedTest)；静态结构与源码策略检查，不冒充真实启动验收。
- 主要文件：`T/backend/pom.xml`；`T/backend/src/main/java/dev/atoms/generated/GeneratedApplication.java`；`T/backend/src/main/resources/application.yml`；`T/config/application.properties.example`；`AT/FullProjectBackendSeedTest.java`。

### C-PA07 · 保留与后端骨架

- [x] PA19–PA21 验收通过；保留相关 7 集成、调度注册 2 单元及模板 Wrapper/后端/原生 API 3 单元通过。旧 PA21 缺文件红测已解决，未删除或跳过；仅专用测试库清理合成历史，未清理日常数据。

## PA22 · Java API 与 SQL 扩展起点

- [x] 完成 PA22。依赖：PA21。规模：M（4 文件）。
- 描述：提供原生 API、迁移与有意义的测试骨架，供模型扩展业务。
- 验收：包内无 dev.atoms.demo 依赖；SQL 只作为源码保存，不连平台库执行；API/测试骨架对应且不宣称已生成用户业务。
- 验证：U(FullProjectApiSeedTest)；检查 Java/SQL/测试相互引用与模板政策。
- 附加实测：在 `templates/full-project-v1/backend` 执行 `./mvnw -B -o test`，独立编译并运行 2 项 MVC 测试通过。无数据库迁移或监听端口，不能代替完整应用运行验收。首次缺测试端口配置的 2 项错误已通过测试专用 APP_PORT=0 修复，正式配置仍无隐式端口/密码。
- 主要文件：`T/backend/src/main/java/dev/atoms/generated/HealthController.java`；`T/backend/src/main/resources/db/migration/V001__application_metadata.sql`；`T/backend/src/test/java/dev/atoms/generated/HealthControllerTest.java`；`AT/FullProjectApiSeedTest.java`。

## PA23 · 原生 Vue 可复现依赖配置

- [x] 完成 PA23。依赖：PA20。规模：M（5 文件）。
- 描述：独立锁定 Vue/TypeScript/Vite 与测试依赖，不复制整个旧平台。
- 验收：依赖与锁文件一致，不擅自添加新库/升级版本；不运行 install 生命周期脚本；前后端路径与后续构建约定一致。
- 验证：U(FullProjectFrontendConfigTest)；核对锁文件；若需要下载/新增依赖先确认。
- 实测：缺 package.json 红测后，配置/锁文件一致性单测通过；`npm ci --ignore-scripts --offline --no-audit --no-fund` 从缓存安装 213 项，无依赖升级、下载或安装生命周期脚本。
- 主要文件：`T/frontend/package.json`；`T/frontend/package-lock.json`；`T/frontend/.npmrc`；`T/frontend/tsconfig.json`；`AT/FullProjectFrontendConfigTest.java`。

## PA24 · 原生 Vue 入口与页面

- [x] 完成 PA24。依赖：PA23。规模：M（拆为两片）。
- 细分 PA24a（本机开发边界 + Vite/入口/静态测试）与 PA24b（组件行为测试 + 页面），每片不超过 5 个文件；复用旧模板的 loopback 边界并使用独立 5275/4275 端口，不修改旧模板。PA25 继续补前端测试契约和检查规则。
- 页面设计契约：这是工程初始化状态页，不是广告页或用户业务成品；主操作为检查/重试应用自身健康 API。沿用平台深色/浅绿语义色、单栏紧凑排版；必须显示加载、成功、连接失败/超时和初始化未完成业务的说明，320–1440 宽度不横向溢出，键盘可访问。无 iframe、平台 SDK、外部字体/图片或成功业务假数据。
- 描述：让模型有真实 Vue 组件可改，不继续以内嵌旧三文件为主体。
- 验收：页面访问自身应用 API，不请求平台 SDK；提供加载/失败反馈与语义化基础控件；无 iframe 兼容层、硬编码私有服务器或业务完成宣称。
- 验证：U(FullProjectFrontendSeedTest)；检查入口引用及 API 路径；运行视觉验收留给执行/工作台模块。
- 补充实测：静态入口检查、7 项组件行为测试通过；浏览器验证开发页和构建产物的 4 种宽度、键盘及重试。API 为本机确定性夹具，未代替真实 Java/数据库或工作台验收。组件样式拆为 `src/app.css`，小型同源 SVG 图标单独保存。
- 主要文件：`T/frontend/vite.config.ts`；`T/frontend/index.html`；`T/frontend/src/main.ts`；`T/frontend/src/App.vue`；`AT/FullProjectFrontendSeedTest.java`。

### C-PA08 · 原生前端骨架

- [x] PA22–PA24 及 PA25 的前端测试通过。6 项模板契约检查、Java 自身 2 项 MVC、前端 15 项测试/类型/代码检查/生产构建和隔离浏览器证据已记录。保持旧平台/模板不变，不扩大 Agent 工具权限；未执行数据库迁移或付费模型。

## PA25 · 模板前端测试契约

- [x] 完成 PA25。依赖：PA24。规模：M（4 文件；组件测试在 PA24b 已提交）。
- 描述：保存工程自己需要的组件测试和静态检查规则。
- 验收：测试包含请求成功及失败分支，不是空断言；package scripts 与配置/文件匹配；不把平台测试专用登录或服务夹具打入模板。
- 验证：U(FullProjectFrontendTestContractTest)；模板测试实际运行由执行模块单列验证。
- 实测：7 项页面 + 8 项开发边界测试，0 失败/错误/跳过；固定 Node 24.20 后类型检查、ESLint 与生产构建均通过。增加仓库级浏览器脚本，仅验收受控模板，不随工程导出。
- 主要文件：`T/frontend/dev/boundary.spec.ts`；`T/frontend/eslint.config.js`；`AT/FullProjectFrontendTestContractTest.java`；`scripts/verify-full-project-seed.mjs`。

## PA26 · 模板构建和启动说明

- [x] 完成 PA26。依赖：PA22、PA25。规模：拆为三个 M 切片。
- PA26a：独立脚本的进程/环境工具及行为测试（2 文件），不依赖平台仓库路径。PA26b：构建入口、打包资源门禁、后端 Maven profile、行为/契约测试（5 文件）。PA26c：启动入口、README、第三方说明、忽略规则及启动测试（5 文件），并扩展已有契约断言。测试仅运行受控命令或注入 runner，不执行模型生成的后端；真实安装/构建/启动留给执行模块。
- 实测：脚本 12 项、全部模板契约 8 项通过。命令/环境和自有子进程取消有实际执行证据；构建/启动顺序使用假 runner，仅检查参数、资源打包及失败拒绝，不声称真实 JAR 构建或数据库启动通过。
- 描述：定义工程自带的构建和启动入口，但本模块不执行用户工程。
- 验收：使用固定 argv、不拼用户 shell、不继承平台秘密；说明源码/配置/数据库分离和所需本机工具；文档明确脚本尚待真实执行验收，不能以静态测试宣称能跑。
- 验证：U(FullProjectScriptContractTest)；检查脚本阶段、路径约束及 README 命令契约。
- 主要文件：`T/scripts/build.mjs`；`T/scripts/start.mjs`；`T/README.md`；`T/THIRD-PARTY-NOTICES.md`；`AT/FullProjectScriptContractTest.java`。

## PA27 · 受控模板清单进入平台包

- [x] 完成 PA27。依赖：PA26。规模：拆为 PA27a 清单/读取器/单测（3 文件），PA27b 平台 POM/包装集成/隔离读取夹具/旧包装测试资源域限定（4 文件）。旧模板内容和条目断言不变；新增模板使用独立 classpath 前缀。
- 实测：17 项新旧模板单测 + 4 项打包集成通过，35 个原生模板文件在磁盘、清单、Maven 配置与正式 JAR 中逐字节一致；实际 Boot JAR 在仓库外空环境通过测试入口加载，不启动平台或用户应用。
- 描述：让正式平台 JAR 可读取完整新模板，旧模板保持原样。
- 验收：显式文件白名单、规范路径和确定性摘要；源码树整体经 ProjectSourcePolicy 校验；正式 JAR 与清单逐字节一致且不带 Agent 测试夹具/配置/秘密。
- 验证：U(FullProjectTemplateCatalogTest)；I(FullProjectTemplatePackagingIT)；旧 TemplatePackagingIT 回归。
- 主要文件：`A/FullProjectTemplateCatalog.java`；`templates/full-project-v1.files`；`backend/pom.xml`；`AT/FullProjectTemplateCatalogTest.java`；`AT/FullProjectTemplatePackagingIT.java`。

### C-PA09 · 模板包装

- [x] PA25–PA27 通过：前端测试、脚本契约及真实平台 JAR 包装有记录；独立模板资源域不污染旧导出。没有模型调用、应用实际启动或部署。

## PA28 · 初始化来源与编辑任务交接

- [x] 完成 PA28。依赖：PA12、PA13、PA27。规模：PA28a 来源元数据/协调者/门面/旧项目拒绝保护及集成（5 文件）；PA28b Agent 初始化及夹具/集成（3 文件）。
- 实测：原生 35 文件模板通过真实 Git/PG 保存来源与摘要；同键重放保持首版，显式提交绑定首版的任务，第二步无效输入不丢首版。模板损坏、禁用、越权、归档、事务内调用拒绝；旧应用在准入和发布前均保护。最新 17 单元 + 26 集成通过，含旧导入/初始化/生命周期/任务准入及新旧包装。不调用模型、不启动应用。
- 描述：固化模板标识及摘要到源码首版，再显式提交绑定该版的需求。
- 验收：初始化幂等，模板失败不创建错误任务；首版与 AI 修改版分别可查，初始化不冒充业务完成；旧项目仍只显式导入，旧 source_json/版本指针/业务数据不变。
- 验证：I(ProjectAgentInitializationIT,ProjectSourceLegacyImportIT)；验证模板来源与第二步失败后的首版状态。
- 主要文件：`A/ProjectAgentInitialization.java`；`S/ProjectSourceEditGateway.java`；`S/SourceInitializer.java`；`S/SourceCoordinator.java`；`AT/ProjectAgentInitializationIT.java`。

## PA29 · 两轮完整源码增量旅程

- [x] 完成 PA29。依赖：PA17、PA18、PA28。拆为 PA29a 完整模板受控进程旅程（4 个测试/夹具文件）和 PA29b 真实 CLI 同旅程（2 个文件），另同步文档；安装版本变化的兼容切片另改 4 文件。
- [x] PA29a：12:10 两项真实 Git/PG 集成通过。初始化、连续两轮修改、精确差异、未改文件字节/权限、重开读取、持久停止证据及重复调度验证；无改动/结果截断保留原版。此项仅受控 Java 编辑进程，实际 CLI 证据见下一项。
- [x] PA29b：12:22 **14 单元 + 6 真实 CLI 离线集成通过**。本机已变为 2.1.303，新增精确兼容后重测；完整模板两轮均经 Read/Glob/Grep/Write/Edit 实际修改并保存，两个任务副本不同，最终摘要与确定性进程旅程一致。保留未知版本拒绝、默认关闭及真实模型未验收；首次夹具准备顺序问题的合成待核对记录见验证文档，后续全量回归不能忽略。
- 描述：串通初始化、首轮、追加需求、查询差异及重开服务读取。
- 验收：真实 Git/PG 和假 CLI 完整闭环，未修改文件字节保持；真实 CLI + loopback 单列重复同旅程；模型失败/无改动不替换旧版本，不把代码保存等同于运行验收。
- 验证：I(ProjectAgentJourneyIT)；CLI；记录版本 UUID/tree hash/任务 ID/进程退出。
- 主要文件：`AT/ProjectAgentJourneyIT.java`；`AT/AgentCliContractIT.java`；`AT/AgentProtocolFixture.java`；`docs/project-agent-verification.md`。

## PA30 · 真实 CLI 的拒绝能力验收

- [x] 完成 PA30。依赖：PA29。规模：M（5 个测试/夹具文件，包含受控子进程与协议反例）。
- 12:40 实测：**17 单元 + 14 集成通过**，0 失败/错误/跳过。真实 CLI 拒绝 Bash、Agent、Task、MCP 和两种合成私有配置读取，canary 未进入请求；受控进程产生的秘密、软/硬链接、超量候选均不能接受。原目录外/链接读取及五工具正常往返回归通过。不是 OS 沙箱证明。
- 描述：对工具和配置边界发起确定性恶意调用，不只看 help。
- 验收：副本外读取、Bash/MCP/子 Agent、个人配置路径尝试被拒绝；秘密/链接/超量候选无法接受，canary 不进入后续 API 请求；只在合成私有目录放 canary，不读写真实个人配置。
- 验证：CLI；I(ProjectAgentBoundaryIT)；无法观察的访问边界如实记录，不把无泄漏 canary 当成 OS 隔离证明。
- 主要文件：`AT/AgentCliBoundaryContractIT.java`；`AT/AgentProtocolFixture.java`；`AT/ProjectAgentBoundaryIT.java`；`AT/AgentChildFixture.java`；`AT/AgentStreamDecoderTest.java`。

### C-PA10 · 完整增量与拒绝验证

- [x] PA28–PA30 的初始化、两轮源码旅程与拒绝边界通过，实际版本/摘要/命令见验证文档；未扩工具或启用真实调用。PA31–33、全量回归及待核对测试记录结算仍未完成，不把定向检查当作整体交付。

## PA31 · 中断与并发压力验收

- [ ] 完成 PA31。依赖：PA15、PA16、PA29。规模：M（4 文件）。
- [x] 2026-10-19：新增 3 个 Agent 中断窗口、3 个重复 Worker 与停止场景、4 个源码发布/恢复并发场景。定向 11 单元 + 10 Agent 集成、另一命令 12 源码集成通过；反向变异确认恢复状态断言有效。详见验证文档 PA31。
- [ ] 协调者租约丢失及完整组合回归仍受旧测试库唯一未决任务阻断。保留审计和关闭保护；未删除记录、伪造凭据或忽略全局守卫。新的隔离测试环境方案需确认，不把定向通过当作 PA31 全项完成。
- 描述：扩大崩溃窗口覆盖，核对恢复不会重复调用或覆盖其他成果。
- 验收：CLI start 前后/退出后封存前/源码提交前后分别中断重开；取消/超时/归属变化/租约丢失不错误解锁；源码恢复与 Agent 接受竞态的结果可由唯一持久证据解释。
- 验证：I(ProjectAgentCrashIT,ProjectAgentConcurrencyIT,ProjectSourceAgentCoexistenceIT)；故障进程使用可控夹具，不杀个人进程。
- 主要文件：`AT/ProjectAgentCrashIT.java`；`AT/ProjectAgentConcurrencyIT.java`；`ST/ProjectSourceAgentCoexistenceIT.java`；`docs/project-agent-verification.md`。

## PA32 · 完整回归与安全审阅

- [ ] 完成 PA32。依赖：PA19、PA30、PA31。规模：S（2 文件）。
- 描述：验证模块关闭无回归、全部离线检查可重跑，审阅自己的改动。
- 验收：后端/源码 Python/前端与既有浏览器回归通过；关键租约与结果判定移除时测试能失败；清点仅本任务进程，证据明确离线与真实模型的区别。
- 验证：ALL、PY、FRONT、BROWSER、CLI；审阅 diff/源码及敏感 canary；不合并、不推送、不部署。
- 主要文件：`AT/ProjectAgentDisabledCompatibilityIT.java`；`docs/project-agent-verification.md`。

## PA33 · 使用边界与交接文档

- [ ] 完成 PA33。依赖：PA32。规模：M（5 文件）。
- 描述：按真实证据记录启停、错误、预算和下一模块交接。
- 验收：列出实际配置名、失败恢复和安全关闭；区分内部完成/真实联调未完成/执行交付未实现；原功能完成说明仅在对应能力实际验收后更新。
- 验证：DOC；逐条核对规格 §8 与证据；保留未授权 PA-LIVE 和旧 C-EB11。
- 主要文件：`docs/project-agent.md`；`docs/project-agent-verification.md`；`SPEC-project-agent.md`；`FULL-PROJECT-CAPABILITY-MAP.md`；`docs/decisions/016-full-project-source-and-coding-agent.md`。

### C-PA11 · 离线交付

- [ ] 前三项实际通过，补记具体命令、数量、失败/跳过、进程和版本证据；检查未扩大权限并审阅变更。向用户汇报，按批准的连续实施范围推进；验收不成立则暂停该切片，不扩大权限或把未测项记为通过。

## PA-LIVE · 真实 DeepSeek 联调（需另行授权）

- [ ] 完成真实联调。依赖：PA33，以及用户指定无秘密项目、最多启动次数和可接受费用边界。
- 描述：真实 Claude Code + DeepSeek 完成首轮及第二轮 Vue/Java/SQL 变更，并保存可追溯源码版本。
- 验收：实际工具调用及有效参数有证据；两轮基线和差异正确，未改文件保留；失败/取消费用风险如实说明，未执行新工程后端或部署。
- 验证：在批准后才记录具体 live 命令、私有配置引用、模型 usage、任务和源码 hash；不能用 CLI loopback 测试代替。
- 主要文件：`docs/project-agent-verification.md`、`docs/project-agent.md`。规模 S；若需要新增 live 验证程序，先拆测试任务，仍不写入真实 Key。

## 交接要求

- PA01–PA33 全部有证据后可交接内部接口，状态写“离线适配验收完成”。PA-LIVE 未完成则保留未完成，不称真实接入成功。
- 后续 project-execution / project-delivery / project-workbench 仍按能力地图单独评审，不在此偷偷启用 Shell 或改 UI。
