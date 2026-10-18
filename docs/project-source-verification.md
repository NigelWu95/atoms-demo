# 完整工程源码模块 · 验证记录

2026-10-18，`project-source` 内部模块本地验收完成。后端 213 项单测 + 170 项集成、前端 372 项单测及静态检查/构建、Python 11 项、浏览器 22 + 49 项均通过，无跳过。进度唯一入口为 [任务清单](../tasks/project-source/todo.md)，维护方法见 [使用说明](project-source.md)。这不是整体改造完成声明：默认关闭，尚未接入 Claude Code、公开源码 API 或新工作台，未推送/部署。

## 已验证的切片

环境：本机 Oracle Java 17.0.5、Git 2.39.5、Node 24.20.0；Git 测试仅使用 JUnit 临时私有目录。所有 Maven 命令从 `backend/` 执行，PATH 前置既有 Node 24。没有安装依赖、修改个人配置、调用真实模型/邮件、推送或部署。

| 切片 | 实际验证 | 结果 |
|---|---|---|
| PS01 | `./mvnw -B -Dtest=ProjectSourceEnvironmentTest,ProjectSourcePathsTest test` | 7 项通过；包含跨 JVM 根租约、目录替换与禁用无 I/O |
| PS02 | `ProjectSourcePolicyTest`，随后全量 `./mvnw -B test` | 新增 32 项通过；全量 182 项通过 |
| PS03 | `ProjectSourceGitProcessTest`，随后全量 `./mvnw -B test` | 新增 6 项通过；全量 188 项通过；超时和取消后的自有进程均结束 |
| PS04 | `./mvnw -B -Dtest=ProjectSourceGitStoreTest test` | 6 项真实 Git 测试通过；大于 120 KiB 的 Vue/Java/SQL 树、两版 blob 不变性、重开读回、不可变重试、损坏/别名拒绝、钩子不执行 |
| 回归与数据库前检 | `./mvnw -B -Dit.test=BackendSmokeIT verify` | 194 项单元测试、2 项集成测试通过；实际连接 PostgreSQL 17.11 的 `atoms_demo_test`，角色 `atoms_test`，非超级用户，原 18 个迁移校验通过 |
| PS05 | `./mvnw -B -Dit.test=ProjectSourceRepositoryIT verify` | 194 项单元、3 项集成通过；V018 在测试库追加迁移成功，同项目 FK、不可变版本/意图和旧指针检查通过 |
| PS06 | `./mvnw -B -Dit.test=ProjectSourceAdmissionIT verify` | 194 项单元、4 项集成通过；并发幂等、同键异体、归档、归属变化及旧修订号不变 |
| PS07 | `./mvnw -B '-Dit.test=ProjectSource*IT' verify` | 194 项单元、累计 10 项集成通过；真实 Git + PostgreSQL 发布/读回、幂等、归属变化拒绝、Git 在事务外 |
| PS08 | 同上 | 累计 15 项集成通过；三个中断窗口、未知目录、已发布仓库缺失均保留正确可见性 |
| PS08a/b | 同上 | 194 项单元、累计 17 项集成通过；V019 在专用测试库追加成功，未确认结束的执行不可自动恢复、重启或发布 |
| PS09a | 同上 | 194 项单元、累计 19 项集成通过；数据库单协调者租约、真实租约连接丢失检查 |
| PS09b 第一轮 | 同上 | 194 项单元、累计 22 项集成通过；独立测试 JVM 在 Git 写完后 `halt(71)`，重启恢复不发布旧候选，显式重试成功；禁用无 Worker、正常结束后拒绝再准入 |
| PS09b 队列及关闭、存储复核 | 同上 | 195 项单元、累计 23 项集成通过；第 65 个待处理请求拒绝，活动中关闭停止该任务并结束队列；根目录中途替换时下一条 Git 命令前停止 |
| PS10/11 | `./mvnw -B -Dtest=ProjectSourcePolicyTest,ProjectSourceLegacyImportIT,ProjectSourceQueryIT test` | 39 项通过（34 策略、2 导入、3 查询）；完整旧工程导入保留所有旧版本/指针/PREVIEW/LIVE 数据；跨项目/账号读取拒绝、分页有界、缺失存储根不重建 |

## 红绿阶段与异常

- 每个切片先新增测试并运行，确认缺少实现导致编译失败，再实现并重跑。
- 初次全量单测在执行沙箱内因 JVM attach 与 socket 权限失败；真实 Git 测试也遇到操作系统禁止查询子进程。没有修改测试绕过权限，改为申请沙箱外运行后通过。
- Git 超时测试最初依赖脚本先写 PID 文件，300 ms 内不保证发生。改为观察 `ProcessBuilder` 已创建的真实进程句柄，再断言超时后句柄不存活；未放宽生产超时。
- PS03 做过一次变异验证：临时去掉子进程 `environment.clear()`，环境白名单测试按预期失败；已恢复代码并全量通过。测试仅记录 HOME 是否存在，未打印实际路径或密钥。
- 审阅发现父目录大小写别名未被识别，新增 `Frontend/a.js` / `frontend/b.js` 用例，确认失败后补充路径分量校验；32 项策略测试重新通过。
- 恢复审阅发现：文件存在不足以证明孤儿 Git 进程已结束。新增执行阻断用例先失败，再追加 V019 和 Worker 生命周期保护；对未知执行只保留阻断，不通过旧 PID 结束进程或自动清除标记。
- 数据库租约测试主动断开了它自己的测试连接，连接池可产生 closed-connection 警告；测试通过，没有停止数据库进程或其他连接。
- Docker CLI 的只读状态查询没有及时返回，但 TCP 检查和真实 JDBC 测试证实测试数据库正常；没有因此重启 Docker、退出登录或触碰容器。
- 根目录替换回归用例先观察到外来仓库被写入 15 个对象；改为每条 Git 命令前复核根身份后对象数为零。随后完整源码模块测试通过。
- PS10 导入首先被模板示例密码和测试占位符拦截。现在只增加四个模板已有的精确占位符，不豁免测试文件；同一行里的其他凭据仍检测，shell 变量展开不误作赋值。导入测试逐一验证受控模板文件。
- 一次全量重跑中，Java 17 的 `ProcessHandle.descendants()` 抛出系统 `Cannot allocate memory`，该次结果计为失败；随后独立真实 Git 的 7 项测试通过。最终全量复验仍需包含此检查，不通过重试抹去异常证据。

## 实现依据

- [Java 17 FileLock](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/channels/FileLock.html)：稳定租约由同一个打开的通道持有和检查；不另开再关闭标记通道。
- [Git 环境变量](https://git-scm.com/docs/git/2.39.0)、[hash-object](https://git-scm.com/docs/git-hash-object/2.39.0)、[update-ref](https://git-scm.com/docs/git-update-ref)：固定工具、清理继承环境、直接处理对象、仅创建不可变引用。
- [PostgreSQL 17 约束](https://www.postgresql.org/docs/17/ddl-constraints.html)、[触发器](https://www.postgresql.org/docs/17/plpgsql-trigger.html)：新增元数据的同项目外键和不可变版本/意图约束。

## 中途待办记录（后续结果见文末）

PS11 完成时，PS12 起的工作副本、取消竞态、磁盘容量与完整回归尚未执行；后续逐项结果记录于下文，不以这段历史待办作为当前状态。V018/V019 的回退方式是关闭新功能、保留新增表及 Git 证据，不做破坏性删表降级。源码树扫描不是任意文本无秘密的证明；目录和环境分离不是操作系统沙箱。新模块默认关闭。

## PS12 本机兼容性阻碍（已解决，保留原失败证据）

- `./mvnw -B -Dtest=ProjectSourceWorkspaceIT test`：3 项均因工作副本目录接口不可用失败，未计为通过。
- `./mvnw -B -Dtest=ProjectSourceWorkspaceIT#localProviderSupportsDescriptorRelativeWorkspaceAccess test`：1 项失败，实际 provider 返回 `sun.nio.fs.UnixDirectoryStream`，不是 `SecureDirectoryStream`。这是当前 macOS + Oracle Java 17.0.5 的实际观察，不宣称所有 Java 17 平台均不支持。
- [Java 17 官方接口](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/file/SecureDirectoryStream.html) 明确此能力依赖底层支持；[OpenJDK 17 provider 实现](https://github.com/openjdk/jdk17u/blob/master/src/java.base/unix/classes/sun/nio/fs/UnixFileSystemProvider.java) 在相关能力不可用时回退普通目录流。
- 只读探测确认本机 Python 3.11.2 提供 `open/mkdir/stat` 的 `dir_fd`、目录 fd 枚举和 `O_NOFOLLOW`。用户随后批准此运行依赖；未安装/升级解释器。
- 已以随平台发布的 Python 标准库工具替换 Java 不支持的目录流。没有跳过测试、访问 JDK 内部 API 或放松链接检查。实现参数数组、受控脚本、清空环境、stdin 数据传输、24 MiB 协议限制、60 秒默认工具超时及退出确认。
- `python3 -I -S -B backend/src/test/python/test_workspace_helper.py`：6 项通过。新增 64 文件句柄上限下 128 目录用例先复现 `Too many open files`，改为按层关闭后通过。临时移除硬链接拒绝条件时相应用例失败，恢复后全绿。
- `./mvnw -B -Dtest=ProjectSourceFileProcessTest,ProjectSourceEnvironmentTest,ProjectSourceWorkspaceIT test`：2026-10-18 21:19:46，10 项通过（0 失败/错误/跳过）。包括真实 Python/Git/专用 PostgreSQL、任务副本与正式版本分离、跨所有者/项目/过期基线拒绝、已有任务目录及替换副本拒绝、超时进程退出。
- 此检查点未推送、部署、更改日常运行配置或调用真实模型/邮箱；当时的候选封存及完整回归留待后续切片（结果见下文），不把工作副本测试当作完整 Agent 能力验收。

## PS13–PS15 候选封存、增量提交及取消

- Python 文件系统用例扩展至 **11 项通过**：新增扫描增删、符号/硬链接/FIFO、文件超限、读取期间替换/增长、独立 staging 与拒绝接管无标识目录。
- `ProjectSourceFileSnapshotTest,ProjectSourceFileProcessTest`：21:25:06 **5 项通过**；`ProjectSourceCandidateTest,ProjectSourceWorkspaceIT`：21:28:12 **6 项通过**。封存后修改副本不影响候选；暂存区篡改、陌生句柄、错误账号/项目、秘密文件均拒绝。候选单测的授权依赖使用 mock，真实授权由 WorkspaceIT 与 AcceptanceIT 验证，不混称全链路。
- `ProjectSourceAcceptanceIT`：21:32:00 **3 项通过**，真实 Git/专用 PG；Vue、Java、SQL 修改产生第二版，未改文件与旧历史仍可读回，父版本正确；同基线提交只成功一次；幂等重试和发布前归属变化覆盖。
- `ProjectSourceCancellationIT`：21:34:39 **7 项通过**。覆盖 Git 前、实际 Git 子进程启动、Git 完成后、发布事务前、提交后五个窗口，以及归档后取消/越权拒绝、未确认执行的取消与恢复阻断。活动 Git 不通过旧 PID 或名称终止；取消请求在其结束后确认，未知执行继续待核对。
- 以上全部 0 失败/错误/跳过，未运行项目脚本。该检查点时完整回归与容量/总时间预算尚未完成，后续结果见 PS18–PS19；同系统用户原生进程隔离仍不是沙箱。

## PS16–PS17 恢复与差异

- `ProjectSourceRestorationIT`：21:37:16 **2 项通过**。V1 → V2 → 恢复 V1 生成 V3，树相同、父版本为 V2、恢复来源为 V1，旧历史、项目记录及 PREVIEW/LIVE 数据未变。覆盖越权、缺失目标、过期基线、取消及幂等重试；SQL 只保存不执行。
- `ProjectSourceDiffTest,ProjectSourceDiffIT`：21:40:13 **3 项通过**。增删改、可执行位变化、空差异、128 KiB UTF-8 截断及完整文件统计；两端版本必须属于当前授权项目，账号归属变化后重新授权。不调用 Git diff/textconv，文本是展示用替换块，不是可执行补丁。

## 调度器运行记录

运维问题及证据对应如下，不引入新的遥测依赖或对外监控入口：

- 哪个任务完成/失败？`source_operation_finished` 记录 requestId（操作 ID）、projectId、status 和稳定 errorCode。
- 重启后为何未继续写入？`source_operation_reconciled` 区分已结束和待核对；结合该操作的 executionPending 读取持久事实。
- 一次源码保存用了多久？完成记录中的 durationMs；这是单次运行记录，不宣称已部署独立指标平台。

日志包含 entryPoint，区分启动核对、任务调度和关闭路径；不记录源码、文件绝对路径、原始异常、邮箱、密码或模型凭据。真实生命周期测试已观察到带操作 ID 的恢复和完成事件；异常 canary 检查结果见后续 `ProjectSourceObservabilityIT` 记录。

## PS18–PS19 资源、协调器与兼容性

以下是 2026-10-18 的本轮实际结果，均使用 Java 17.0.5、真实临时 Git、已有 Python 3.11.2 和专用 `atoms_demo_test` / `atoms_test`，不涉及日常或线上数据库：

| 检查 | 通过时间 | 结果 |
|---|---|---|
| `ProjectSourceDeadlineTest,ProjectSourceGitProcessTest` | 21:47:08 | 10 项通过；共享总时限、子进程枚举失败仍停止自有父进程并保留不确定结果 |
| `ProjectSourceStorageBudgetTest,ProjectSourceEnvironmentTest` | 21:50:56 | 5 项通过；微小预算、空间预留、目录替换与无删除 |
| `ProjectSourceFailureIT,ProjectSourceWorkspaceIT,ProjectSourceCandidateTest` | 22:06:57 | 10 项通过；真实对象目录写失败、空间准入、操作超时且不发布损坏版本 |
| `ProjectSourceAcceptanceIT,ProjectSourceCandidateTest` | 22:13:55 | 7 项通过；同一候选并发重试只交出一次可执行输入、释放内存句柄后持久幂等仍有效 |
| `ProjectSourceJourneyIT,ProjectSourceLifecycleIT` | 22:17:20 | 5 项通过；超过 120 KiB 的 Vue/Java/SQL 树，初始化 → 两轮修改 → 恢复 → 重开后四版均读回；真实测试进程中断恢复 |
| `ProjectSourceCompatibilityIT` | 22:22:08 | 1 项通过；默认关闭无协调者，旧保存/恢复/工程组装不产生新源码记录或目录 |
| `ProjectSourceFileSnapshotTest,ProjectSourceStorageBudgetTest,ProjectSourceDiffIT,ProjectSourceRecoveryIT` | 22:27:09 | 14 项通过；文件操作、差异和恢复共享总时限；保留 Git 启动中断的执行阻断 |
| Python 文件工具全量 | 22:28 后复验 | 11 项通过；无安装额外 Python 包 |

补充失败证据：

- 并发幂等测试曾拿到两份执行输入，现通过候选发行者共享锁和持久回放避免重复消费，磁盘证据不删除。
- 多步文件时限测试先失败（过期后仍能读），传递同一个单调时限后通过。
- 恢复枚举用例因新增 `GIT_STARTED` 阶段自动扩展而暴露过时预期：原断言所有未提交窗口均普通失败，新窗口必须待核对。已增加未结束标记、禁止重试和不可见版本断言，没有降低生产保护。
- 该次失败留下的唯一测试记录，在确认本次子进程已通过 finally 结束后按固定操作/项目/执行 UUID 清理为测试结束；只更新专用测试库 1 行，没有删除源码或正式数据。之后用例自行按同样证据收尾，生产无自动解除阻断入口。

前端在 22:15–22:16 完成 `typecheck`、`lint`、`format:check`、`test:unit`、`build`：**54 个文件 / 372 项单测通过**，Vite 构建通过；本轮无前端源码变更。完整后端及两组隔离浏览器结果在完成后追加，不把针对性检查当作全量通过。

- 22:29:44 的工作副本/接受回归中 10 项真实集成通过；2 项候选单测因 mock 尚未匹配新增时限参数而失败。同步准确的调用签名后，22:30:49 `ProjectSourceCandidateTest,ProjectSourceObservabilityIT` **3 项通过**。日志用例直接捕获协调者输出，验证秘密 canary、私有路径、源码与原始异常不被输出，操作/项目 ID、稳定错误码及耗时可用。
- 五轴审阅处理了候选重复消费、子进程清理异常、复合操作预算重置和新增崩溃窗口；保留单 Worker/有界队列、UTF-8 树限制及默认关闭，不新增公开执行接口。Python 硬链接守卫和 Git 环境白名单的变异测试均曾证明相关测试会失败，恢复后通过。

## 规格验收与可重复测试映射

下列是代码中的实际测试类；完整命令结果单独记录，映射本身不代表尚在运行的回归已经通过。

| 规格 §7 | 主要证据 |
|---|---|
| 1 完整工程、大于旧上限 | `ProjectSourcePolicyTest`、`ProjectSourceGitStoreTest`、`ProjectSourceJourneyIT` |
| 2 单文件增量、未改 blob、旧版重建 | `ProjectSourceGitStoreTest`、`ProjectSourceAcceptanceIT` |
| 3 所有权、账号变化 | `ProjectSourceAdmissionIT`、`ProjectSourceQueryIT`、`ProjectSourceWorkspaceIT`、`ProjectSourceAcceptanceIT`、`ProjectSourceRestorationIT` |
| 4 并发基线、幂等与重复消费 | `ProjectSourceAdmissionIT`、`ProjectSourceAcceptanceIT` |
| 5 三类中断和跨进程恢复 | `ProjectSourceRecoveryIT`、`ProjectSourceLifecycleIT` |
| 6 路径/文件类型/秘密/钩子 | `ProjectSourcePathsTest`、`ProjectSourcePolicyTest`、`ProjectSourceGitProcessTest`、`ProjectSourceGitStoreTest`、`ProjectSourceCandidateTest`、`ProjectSourceObservabilityIT`、11 项 Python 文件测试 |
| 7 取消、损坏及未知状态 | `ProjectSourceCancellationIT`、`ProjectSourceFailureIT`、`ProjectSourceRecoveryIT`、`ProjectSourceCoordinatorLeaseIT` |
| 8 新版本恢复、业务数据不变 | `ProjectSourceRestorationIT` |
| 9 旧导入与产品兼容 | `ProjectSourceLegacyImportIT`、`ProjectSourceCompatibilityIT`，以及完整后端和 entry/runtime 浏览器回归 |
| 10 默认关闭、无模型/工程执行 | `ProjectSourceEnvironmentTest`、`ProjectSourceCompatibilityIT`；显式测试 profile、生成 Worker 关闭、隔离邮件适配器，测试使用受控源码夹具 |

本轮没有执行 Claude Code、付费模型或真实 SMTP，没有运行新生成业务后端，没有修改个人 Agent 配置或日常 env，也没有推送/部署/远程迁移。平台旧流程的确定性模型适配器测试不等于真实模型联调，新源码保存也不等于新模式已经可构建运行。

## 最终回归

- **后端全量通过**：22:39:55，`env PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH ./mvnw -B verify`，耗时 8 分 23 秒；44 个单测类 / **213 项单测**、59 个集成类 / **170 项集成测试**，均为 0 失败、0 错误、0 跳过。新源码包占其中 70 项单测和 57 项集成测试，不额外重复计数。
- 真实专用 PostgreSQL 17.11 中 20 个迁移校验通过，当前 schema V019；没有本轮远程迁移。
- 打包 JAR 含 `source/workspace_helper.py` 与源码模块，未含 `FixtureApplication`/`EmailFixture` 测试入口。前端 372 项单测、静态检查和构建、Python 11 项测试结果见上文。
- 已确认 8081/5174 无监听占用，使用 `CI=1` 启动既有隔离服务，不复用日常平台。当前环境无 Chrome DevTools MCP，按浏览器技能采用项目现有 Playwright + 独立 Chrome profile。
- `CI=1 npx playwright test --config playwright.entry.config.ts`：**22 项通过**，耗时 4.2 分钟。包括邮箱验证码切页保留、跨标签/浏览器账号切换、密码设置/修改/登录、首页导航、320–1440px 响应式及无障碍、创建/重开项目、SSE 断线恢复与取消；验证码冷却等待保持原规则。已查看本次 `code-after-revalidation.png`，验证码输入框和登录按钮存在，输入状态保留。截图使用自动生成的测试邮箱与模拟验证码。
- `CI=1 npm run test:e2e:runtime -- --project=chromium`：**49 项通过**，耗时 4.4 分钟。真实 iframe、当前/历史版本 ZIP、草稿/差异、报告与修复确认、发布/下线、恢复、应用状态及组件内滚动均通过；无失败或跳过。已查看本次 1280×600 的 `workbench-initial.png`：对话输入与预览同屏，长对话没有撑长文档；320×700 到 1440×900 的布局也有测试断言。
- 两组浏览器服务使用专用测试 profile 和模拟邮件；结束后按测试生命周期退出，不停止日常平台。新模块真实服务旅程仍以 `ProjectSourceJourneyIT` 为证据，旧 UI 回归不冒充新 Coding Agent 已接入。
- Markdown 本地链接检查和 `git diff --check` 通过。没有改动原 C-EB11 人工验收或 OAuth 草稿；使用说明明确固定文件策略、软磁盘预算、未知执行保留阻断、停写备份以及同系统用户非沙箱边界。
