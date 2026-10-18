# 完整工程源码模块 · 验证记录

2026-10-18，开发中。进度唯一入口为 [任务清单](../tasks/project-source/todo.md)。本记录不是全部功能完成声明；尚未接入 Claude Code、公开源码 API 或新工作台。

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

## 仍待验证

PS12 起的工作副本、取消竞态与磁盘容量检查，以及新旧完整集成/浏览器回归。V018/V019 的回退方式是关闭新功能、保留新增表及 Git 证据，不做破坏性删表降级；迁移后的旧链路完整回归仍待 PS19。源码树扫描不是任意文本无秘密的证明；目录和环境分离不是操作系统沙箱。当前新模块默认关闭。

## PS12 本机兼容性阻碍

- `./mvnw -B -Dtest=ProjectSourceWorkspaceIT test`：3 项均因工作副本目录接口不可用失败，未计为通过。
- `./mvnw -B -Dtest=ProjectSourceWorkspaceIT#localProviderSupportsDescriptorRelativeWorkspaceAccess test`：1 项失败，实际 provider 返回 `sun.nio.fs.UnixDirectoryStream`，不是 `SecureDirectoryStream`。这是当前 macOS + Oracle Java 17.0.5 的实际观察，不宣称所有 Java 17 平台均不支持。
- [Java 17 官方接口](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/file/SecureDirectoryStream.html) 明确此能力依赖底层支持；[OpenJDK 17 provider 实现](https://github.com/openjdk/jdk17u/blob/master/src/java.base/unix/classes/sun/nio/fs/UnixFileSystemProvider.java) 在相关能力不可用时回退普通目录流。
- 只读探测确认本机 Python 3.11.2 提供 `open/mkdir/stat` 的 `dir_fd`、目录 fd 枚举和 `O_NOFOLLOW`。这仅表明可评估受控文件工具方案，并非已经实现或验证；加入此运行依赖须按规格 §8 请求确认。
- 未使用跳过测试、反射访问 JDK 内部 API 或放松链接/目录替换检查来绕过失败。PS12 的两个新生产类、工作副本测试及服务基线检查仍为未提交 WIP。PS01–PS11 的已验证切片已作本地提交；未推送、部署或更改日常运行配置。

## 调度器运行记录

运维问题及证据对应如下，不引入新的遥测依赖或对外监控入口：

- 哪个任务完成/失败？`source_operation_finished` 记录 requestId（操作 ID）、projectId、status 和稳定 errorCode。
- 重启后为何未继续写入？`source_operation_reconciled` 区分已结束和待核对；结合该操作的 executionPending 读取持久事实。
- 一次源码保存用了多久？完成记录中的 durationMs；这是单次运行记录，不宣称已部署独立指标平台。

日志包含 entryPoint，区分启动核对、任务调度和关闭路径；不记录源码、文件绝对路径、原始异常、邮箱、密码或模型凭据。真实生命周期测试已观察到带操作 ID 的恢复和完成事件；异常 canary 检查继续由 PS18 完成。
