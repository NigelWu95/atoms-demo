# project-source · 开发任务

状态：2026-10-18，用户已授权连续实施；PS01–PS11（含执行阻断补充）、C-PS01/02/03 已验证。PS12 的本机兼容性测试失败，待确认新增受控文件系统适配依赖；PS12 不计完成，其余未开始。证据见 [验证记录](../../docs/project-source-verification.md)。唯一计划入口为 [plan.md](plan.md)，规格见 [SPEC-project-source.md](../../SPEC-project-source.md)。原任务清单不变。

路径缩写：`S` = `backend/src/main/java/dev/atoms/demo/source/`；`T` = `backend/src/test/java/dev/atoms/demo/source/`；`R` = `backend/src/main/resources/`。所有缩写文件名均为拟新增/修改位置，不代表已存在。每项列出的主实现和测试文件不超过五个，若实际超出，先拆分任务。

命令均从 `backend/` 执行；测试库、工具链及禁用真实模型/邮件规则见计划 §5。每个 `test`/`verify` 都包含编译；集成命令不跳过单测。验收条件只在证据成立后勾选。

## A · 受控存储基础

### PS01 · 禁用默认值与专用存储根（M）

建立源码配置和私有根管理，确保新模块不会因启动平台而擅自写盘。

- [x] 开关关闭时不启动 Git、不创建根目录；启用必须显式指定私有源码/工作副本根和 Git 路径。
- [x] 拒绝链接根、工作区根、非平台已有目录、同根第二个写入者；根租约丢失后停止写入，关闭不删除稳定锁文件。
- 验证：`./mvnw -B -Dtest=ProjectSourceEnvironmentTest,ProjectSourcePathsTest test`；用临时目录确认拒绝操作前后原文件及锁不被改写。
- 依赖：无。
- 文件（5）：`S/ProjectSourceEnvironment.java`、`S/ProjectSourcePaths.java`、`R/application.yml`、`T/ProjectSourceEnvironmentTest.java`、`T/ProjectSourcePathsTest.java`。

### PS02 · 完整源码树校验（M）

实现新源码树的数据模型、独立文件策略及稳定内容摘要，不修改旧三文件协议。

- [x] Vue、Java、SQL、非敏感配置可以共同存储；大小/数量/深度按规格精确计量；超限拒绝而非截断。
- [x] 覆盖路径穿越、大小写冲突、非法 UTF-8、链接/异常文件类型、秘密文件与可执行位；摘要对顺序无关、对内容/路径/模式变化敏感。
- 验证：`./mvnw -B -Dtest=ProjectSourcePolicyTest test`；包含每项边界前后值及大于 120 KiB 的完整树。
- 依赖：PS01。
- 文件（3）：`S/SourceTree.java`、`S/ProjectSourcePolicy.java`、`T/ProjectSourcePolicyTest.java`。

### PS03 · 无用户配置的 Git 调用（S）

建立唯一 Git 子进程适配器，执行前核对官方文档和本机 Git 所需命令支持情况；不安装或升级工具。

- [x] 固定可执行路径及参数数组、环境白名单；不加载用户/系统配置、模板钩子、外部过滤器、凭据助手或网络远端。
- [x] 数据输出与错误诊断分别限流；超时/取消确认自有进程结束；日志不含文件内容、环境和 canary 秘密。
- 验证：`./mvnw -B -Dtest=ProjectSourceGitProcessTest test`；真实 Git 与测试用超时进程验证不执行钩子、不遗留自有任务。
- 依赖：PS01。
- 文件（2）：`S/SourceGitProcess.java`、`T/ProjectSourceGitProcessTest.java`。

### C-PS01 · 基础约束检查点

- [x] PS01–PS03 针对性测试通过；平台原配置/个人 Git/Claude 设置未修改。
- [x] 审阅失败路径和进程结束证据；无法确认终止时不继续写入。

## B · 不可变树与持久操作

### PS04 · Git 快照写入与读回（M）

将已校验树保存为 Git 对象并按平台版本 UUID 读取，先独立证明存储字节一致性。

- [x] 支持完整树保存/读回及不可变引用；重复同一候选可核对重放，不覆盖不同内容的既有引用。磁盘任务副本按 PS12 实现。
- [x] 修改一个文件形成第二版，未改文件 blob 保持一致；读回核对模式、长度及整树摘要；异常引用/缺失对象返回明确错误。
- 验证：`./mvnw -B -Dtest=ProjectSourceGitStoreTest test`；临时真实 bare 仓库关闭后重开读回，两版均完整保留。
- 依赖：PS02、PS03。
- 文件（3）：`S/SourceGitStore.java`、`S/SourceGitProcess.java`（增加只读引用查询）、`T/ProjectSourceGitStoreTest.java`。数据库版本 DTO 随 PS05 实现。

### PS05 · 源码元数据增量迁移（M）

新增规格定义的三张表及最小 JDBC 映射，不改变旧产物表。

- [x] 新表具备同项目引用、版本编号唯一、不可变版本及操作幂等约束；源码内容不写入表字段。
- [x] 迁移在已有数据的专用测试库可应用；旧指针、版本内容和原迁移文件未变化。
- 验证：`./mvnw -B -Dit.test=ProjectSourceRepositoryIT verify`；核对库/角色身份、跨项目外键及不可变触发器拒绝非法写入。
- 依赖：PS04。
- 文件（5）：`R/db/migration/V018__project_sources.sql`（已复核编号）、`S/SourceRepository.java`、`S/SourceOperation.java`、`S/SourceVersion.java`、`T/ProjectSourceRepositoryIT.java`。

### PS06 · 持久准入与幂等键（M）

先落操作意图再触发文件操作，建立可恢复且重新授权的任务入口。

- [x] 所有者、未归档、基线和队列准入合格才创建意图；同键同请求返回同操作，同键异体拒绝，未经授权不产生目录。磁盘容量由 PS18 验证。
- [x] 请求摘要和候选 ID 不可变；权限变化后旧调用者不能读幂等结果；源码修订号独立于旧项目修订号。
- 验证：`./mvnw -B -Dit.test=ProjectSourceAdmissionIT verify`；并发准入和幂等冲突使用真实数据库断言。
- 依赖：PS05。
- 文件（5）：`S/ProjectSourceService.java`、`S/SourceRepository.java`、`S/SourceRequest.java`、`T/ProjectSourceAdmissionIT.java`、`T/SourceIntegrationSupport.java`（共用真实数据库身份检查及本测试收尾）。

### C-PS02 · 存储及数据库检查点

- [x] PS04–PS06 通过；大于旧上限的树可读回，数据库仅含元数据。
- [x] 未出现提前发布版本、旧版本变更、隐式目录接管或测试库越界。

## C · 首个可靠初始化闭环

### PS07 · 模板初始化并发布首版（M）

从受控文件树完成意图、Git 存储和数据库发布的最小服务闭环；此时不连接模型。

- [x] 首次初始化生成一个源码版本；已有工程不可被不同初始化请求静默重置。导入来源与模板摘要在 PS10 绑定。
- [x] Git 在事务外处理，发布事务复查所有权/归档/基线；失败不影响旧当前版本或其他成功操作。
- 验证：`./mvnw -B -Dit.test=ProjectSourceInitializationIT verify`；测试用完整树含 Vue/Java/SQL，检查授权到读回的完整服务路径。
- 依赖：PS04、PS06。
- 文件（5）：`S/SourceInitializer.java`、`S/SourceWorker.java`、`S/SourceRepository.java`、`S/ProjectSourceService.java`、`T/ProjectSourceInitializationIT.java`。

### PS08 · 中断状态核对（M）

优先证明跨 Git/数据库失败可解释，再开放更多写入方式。

- [x] 三个故障窗口分别验证：对象前失败、Git 引用已写但 DB 未发布、DB 已发布但响应丢失；未发布候选不成为当前版本。
- [x] 已提交返回原结果；无法确认进入待核对状态并封锁项目，不能靠重新执行掩盖；不自动清理残留引用。
- 验证：`./mvnw -B -Dit.test=ProjectSourceRecoveryIT verify`；数据库事务与真实 Git，重建服务对象后检查持久事实。
- 依赖：PS07。
- 文件（4）：`S/SourceRecovery.java`、`S/SourceRepository.java`、`S/ProjectSourceService.java`、`T/ProjectSourceRecoveryIT.java`。

### PS09 · 有界调度与平台重启恢复（M）

实施前补充两项 PS08 子切片：代码审阅发现数据库/Git 内容核对不足以证明孤儿 Git 进程已退出，必须持久化执行阻断，不以文件存在代替进程结束。

- [x] PS08a：新增 V019 执行标识/未结束标记及约束、DTO/JDBC 映射；真实数据库证明不可换执行标识、未确认结束不能提交/取消。文件：迁移、`SourceOperation`、`SourceRepository`、`ProjectSourceRepositoryIT`。
- [x] PS08b1：恢复遇到未结束标记保持待核对，禁止自动重跑。文件：`SourceRecovery`、`ProjectSourceRecoveryIT`。
- [x] PS08b2：Worker 在 Git 前持久化执行标记，只在本次自有进程确认结束后清除；事务边界也拒绝未结束执行的自动恢复。文件：`SourceWorker`、`ProjectSourceService`、`SourceRepository`、`ProjectSourceInitializationIT`。

以下调度任务依赖 PS08a/b 完成；原进程结束、未知状态停止写入的要求不变。

拆分 PS09a：先实现数据库级单协调者租约及真实连接丢失测试（`SourceCoordinatorLease.java`、`ProjectSourceCoordinatorLeaseIT.java`），避免不同源码根的两个平台误处理同一数据库队列；PS09b 再接调度、队列、停止及测试子进程。后者文件为 `SourceCoordinator.java`、`SourceRepository.java`、`ProjectSourceLifecycleIT.java`、`SourceProcessProbe.java`。

将服务闭环接到单 Worker 生命周期，未完成操作从持久记录识别，不依赖内存 Future。

- [x] 启用时取得租约并先完成遗留操作核对，再接收新写入；队列满拒绝；关闭不接收新工作且等待自有活动结束。
- [x] 模拟执行进程中断并重新启动后仍能解释版本与操作状态；不依据旧 PID 杀进程，不把停止请求当作已停止。
- 验证：`./mvnw -B -Dit.test=ProjectSourceLifecycleIT verify`；使用受控 Java 测试子进程和专用临时根，不终止日常平台。
- 依赖：PS08、PS01。
- 文件（5）：`S/SourceCoordinator.java`、`S/ProjectSourceService.java`、`S/SourceWorker.java`、`T/ProjectSourceLifecycleIT.java`、`T/SourceProcessProbe.java`。

### C-PS03 · 持久化闭环检查点

- [x] 初始化、响应丢失和重启恢复均有真实 Git/数据库证据。
- [x] 初始化关闭或失败时旧链路不变；无模型调用、用户工程执行或远程写入。

## D · 导入、读取与工作副本

### PS10 · 旧项目显式导入（M）

复用授权工程快照入口导入指定旧版本，固化模板与来源，不替换旧预览。

- [x] 按所有者、指定旧版本和模板摘要创建新模式首版；模板变化拒绝继续，重试不生成重复版本。
- [x] 只含源码及初始数据；历史 `source_json`、PREVIEW/LIVE 数据、旧指针不变；旧派生清单不成为新版本树校验依据。
- 验证：`./mvnw -B -Dit.test=ProjectSourceLegacyImportIT verify`；测试前后对旧数据快照逐项断言。
- 依赖：C-PS03。
- 实际文件（4）：`S/LegacySourceImporter.java`、`S/ProjectSourcePolicy.java`、`T/ProjectSourceLegacyImportIT.java`、`T/ProjectSourcePolicyTest.java`；增加固定模板占位符识别，不放宽旧 TemplatePolicy。

### PS11 · 授权源码读取（M）

提供模块内部的版本列表、树及文件读取，供后续工作台使用，不新增公开文件接口。

- [x] 仅允许平台版本 UUID 和规范相对路径；拒绝跨项目/跨账号/任意 SHA；当前权限变化后立即生效。
- [x] 列表最多 100 项、有界分页；文件缺失、对象损坏或摘要不符明确失败；读取不隐式初始化或修改仓库。
- 验证：`./mvnw -B -Dit.test=ProjectSourceQueryIT verify`；分页边界、历史版本与未授权读取均覆盖。
- 依赖：PS07、PS10。
- 文件（5）：`S/SourceQuery.java`、`S/SourceRepository.java`、`S/ProjectSourceService.java`、`S/ProjectSourceEnvironment.java`（读取不得创建缺失存储根）、`T/ProjectSourceQueryIT.java`。

### PS12 · 物化任务工作副本（M）

从授权不可变版本创建普通文件副本，作为后续 Agent 的唯一项目输入。

2026-10-18 实施阻碍：本机 Oracle Java 17.0.5 返回 `UnixDirectoryStream`，不提供 `SecureDirectoryStream`，当前描述符相对访问实现的 3 项测试均未通过；独立能力探测也已确认。未以普通路径操作替代安全要求。拟议采用小型 Python 3 POSIX 文件工具（本机已有 3.11.2，只读能力检查通过），保留 Java 17 主流程；该新增运行依赖尚未获批，未实现、安装或启用。相关工作副本代码保持未提交 WIP，不能作为已通过能力交付。

- [ ] 副本绑定项目、任务及源码基线；不含 `.git`、正式仓库指针、平台密钥或私有配置；不接管已有目录。
- [ ] 文件内容与模式对应版本摘要；修改/删除副本不改变正式版本；过期权限/基线拒绝准备。
- 验证：`./mvnw -B -Dit.test=ProjectSourceWorkspaceIT verify`；两项目副本互不混淆，旧版本读回不受修改影响。
- 依赖：PS11、PS01。
- 文件（4）：`S/SourceWorkspace.java`、`S/SourceWorkspaceStore.java`、`S/ProjectSourceService.java`、`T/ProjectSourceWorkspaceIT.java`。

### C-PS04 · 兼容与副本检查点

- [ ] 指定旧版本可导入；授权文件读取和任务副本均通过；旧数据保持原样。
- [ ] 没有将“可导入完整工程”描述为“已能生成业务后端”或“已能安全运行任意工程”。

## E · 增量接受与取消

### PS13 · 候选内容封存（M）

将任务修改复制到平台受控 staging 并重新校验，产生内部候选句柄。

- [ ] 仅接受平台创建且项目/任务/基线匹配的工作副本；封存后再改副本不会改变候选；模型自报路径和 hash 无效。
- [ ] 读取中链接替换、硬链接、越界、文件增长及秘密 canary 被拒绝；封存前不执行工程脚本。
- 验证：`./mvnw -B -Dtest=ProjectSourceCandidateTest test`；含受控并发文件替换测试及 staging 不可变性检查。
- 依赖：PS12、PS02。
- 文件（4）：`S/SourceCandidate.java`、`S/SourceCandidateStore.java`、`S/SourceWorkspaceStore.java`、`T/ProjectSourceCandidateTest.java`。

### PS14 · 基线比较与增量提交（M）

将封存候选接入既有持久操作流程，完成第二轮源码修改。

- [ ] 修改 Java/Vue/SQL 文件后生成新版本，记录父版本；未改文件不变，旧版本仍可完整读回。
- [ ] 同基线并发提交最多一次成功；同键重试返回同结果；归属/归档变化拒绝发布，不能强制覆盖。
- 验证：`./mvnw -B -Dit.test=ProjectSourceAcceptanceIT verify`；真实并发事务与 Git 引用检查，拒绝结果不移动指针。
- 依赖：PS13、PS06、PS08。
- 文件（4）：`S/ProjectSourceService.java`、`S/SourceWorker.java`、`S/SourceRepository.java`、`T/ProjectSourceAcceptanceIT.java`。

### PS15 · 取消与提交竞态（M）

让取消结果反映实际发布事实，未确认结束的操作不释放准入。

- [ ] Git 前、Git 中、发布事务前取消阻止新版本可见；已提交后返回已提交，绝不删除已发布版本来伪装取消。
- [ ] 取消不终止其他任务、不删历史；无法确认进程或写入状态时进入待核对，重启后仍保持正确阻断。
- 验证：`./mvnw -B -Dit.test=ProjectSourceCancellationIT verify`；使用确定性屏障覆盖取消/提交两种先后结果。
- 依赖：PS14、PS09。
- 文件（4）：`S/ProjectSourceService.java`、`S/SourceWorker.java`、`S/SourceRepository.java`、`T/ProjectSourceCancellationIT.java`。

### C-PS05 · 增量开发底座检查点

- [ ] 第二轮修改、同基线冲突、幂等和取消均有完整服务路径证据。
- [ ] 失败或取消不覆盖当前版本；日志、源码及暂存区未写入平台秘密。

## F · 恢复、差异与运行防护

### PS16 · 恢复为一个新版本（M）

恢复历史源码树时保持完整历史和正确父子关系，不运行 SQL。

- [ ] V1 → V2 → 恢复 V1 得到 V3：树与 V1 一致，父版本是 V2，并记录恢复来源；V1/V2 不被修改。
- [ ] 恢复重新授权并比较基线；幂等/取消复用同一操作流程；业务数据及旧模式指针不变。
- 验证：`./mvnw -B -Dit.test=ProjectSourceRestorationIT verify`；比较 Git 树、元数据及业务数据前后值。
- 依赖：PS14、PS15。
- 文件（3）：`S/ProjectSourceService.java`、`S/SourceWorker.java`、`T/ProjectSourceRestorationIT.java`。

### PS17 · 有界源码差异（M）

提供版本间文件增删改统计和文本差异，供后续 UI 展示。

- [ ] 两个版本都需属于授权项目；返回路径、变更类型和内容摘要；不调用外部 diff/textconv。
- [ ] 大差异返回真实截断标志和完整变更文件统计，不将截断输出用于候选完整性校验。
- 验证：`./mvnw -B -Dit.test=ProjectSourceDiffIT verify`；包含增加、删除、修改、模式变化、空差异及跨权限读取。
- 依赖：PS11、PS14。
- 文件（4）：`S/SourceDiff.java`、`S/SourceQuery.java`、`S/SourceGitStore.java`、`T/ProjectSourceDiffIT.java`。

### PS18 · 容量和失败可观测性（M）

对真实磁盘异常、预算拒绝及状态卡住提供有限诊断，不记录源码或凭据。

- [ ] 队列/根容量/空间准入生效；模拟磁盘写失败不发布损坏版本；不自动删历史或未知目录。
- [ ] 日志包含项目/操作标识、阶段、耗时和稳定错误码，不含源码、个人路径、邮箱/模型凭据；待核对状态可经内部查询诊断。
- 验证：`./mvnw -B -Dit.test=ProjectSourceFailureIT verify`；小预算夹具触发上限而非填满本机磁盘，秘密 canary 在产物和日志中均不存在。
- 依赖：PS09、PS15。
- 文件（5）：`S/ProjectSourceEnvironment.java`、`S/ProjectSourcePaths.java`、`S/SourceCoordinator.java`、`S/SourceWorker.java`、`T/ProjectSourceFailureIT.java`。

### C-PS06 · 恢复与异常检查点

- [ ] 历史恢复、差异截断和失败防护测试通过；旧数据没有因源码操作改变。
- [ ] 安全审阅确认无新公开执行入口、无个人配置读取、无凭据继承；明确同用户原生进程的隔离局限。

## G · 回归与交接

### PS19 · 新旧完整链路回归（M）

从初始化到两轮修改、恢复、服务重开完成真实验收，同时保持旧产品链路可用。

- [ ] 规格 §7 十项全部有测试映射；真实 Git、专用 PostgreSQL 和中断恢复共同通过，不用 mock 冒充集成结果。
- [ ] 完整后端检查与现有前端/隔离浏览器回归通过；新功能关闭时不触发 Git 或改变原创建、生成、预览、导出、恢复及登录行为。
- 验证：`./mvnw -B verify`；随后按计划 §5 完整执行前端检查及两组浏览器命令，记录失败与未覆盖项。
- 依赖：PS16–PS18、此前全部任务。
- 文件（3）：`T/ProjectSourceJourneyIT.java`、`T/ProjectSourceCompatibilityIT.java`、`docs/project-source-verification.md`。

### PS20 · 使用说明与模块交接（M）

只根据通过的证据更新模块状态和操作说明，区分源码管理已完成与 Coding Agent 尚未接入。

- [ ] 说明配置开关、源码根、Git/PG 双份备份、停写备份一致性、恢复核对、残留候选及安全关闭；不包含真实凭据。
- [ ] 能力地图与验收记录指向真实结果；给后续模块明确内部接口、版本身份和不可变快照约束，不修改原未完成人工验收。
- 验证：检查 Markdown 链接、命令及源码一致性；`git diff --check`；对照规格/验收记录人工审阅，不将计划当作测试证据。
- 依赖：PS19。
- 文件（4）：`docs/project-source.md`、`docs/project-source-verification.md`、`FULL-PROJECT-CAPABILITY-MAP.md`、`SPEC-project-source.md`。

### C-PS07 · 模块交付检查点

- [ ] 全部任务测试证据、代码审阅和文件/权限边界检查完成；相关文档真实反映实现状态。
- [ ] 向用户展示初始化 → 文件变更 → 新版本 → 恢复的证据及剩余模块；经评审后进入 `project-agent` 规格。
- [ ] 未推送或部署、未调用真实模型/SMTP、未修改个人 Claude Code 配置；原 C-EB11 与 OAuth 草稿保留。

## 规格验收映射

| 规格 §7 条件 | 负责任务 |
|---|---|
| 1 完整工程及大于旧上限的读回 | PS02、PS04、PS07、PS19 |
| 2 单文件增量与两版一致性 | PS04、PS14 |
| 3 所有权与账号变化 | PS06、PS11、PS14、PS16 |
| 4 并发基线及幂等 | PS06、PS14 |
| 5 三类中断窗口 | PS08、PS09 |
| 6 路径、类型、秘密及钩子防护 | PS01–PS03、PS13、PS18 |
| 7 取消、损坏及未知状态 | PS08、PS09、PS15、PS18 |
| 8 代码恢复与数据不变 | PS16 |
| 9 旧导入和原链路兼容 | PS10、PS19 |
| 10 默认禁用与不执行模型/工程 | PS01、PS03、PS09、PS19 |

每项验收与任务级“测试通过、运行验证、无回归、文档准确”共同构成完成条件。计划确认不勾选任何实现任务；新增范围、权限或预算调整需另行确认。
