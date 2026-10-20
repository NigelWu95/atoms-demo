# 完整工程执行 · 验证记录

本模块依据[已确认规格](../SPEC-project-execution.md)及[EX 任务清单](../tasks/project-execution/todo.md)实施。仅记录实际运行过的检查，不以计划、假子进程或历史通过数替代完整工程构建/业务验收。当前尚无真实生成应用构建/运行结论，日常开关未启用。

## 2026-10-20 · EX01 配置

使用 Oracle Java 17.0.5；除以下专用测试 schema 外，不访问日常/远程数据库。未改私有 env、个人工具配置、模型/邮箱额度及旧 Agent 默认 4 并发。

| 完成时间（Asia/Shanghai） | 检查 | 结果 |
|---|---|---|
| 14:20:17 | 新 `ExecutionSettingsTest`，实现之前 | 编译失败，16 处均为尚未存在的 `ExecutionSettings` 引用；RED |
| 14:21:58 | 新配置及原 Agent 配置定向单元 | 14 项，0 失败/错误/跳过 |
| 14:24:22 | 全部后端单元 + `ProjectSourceReadGatewayIT` + 打包 | 297 单元、5 集成，0 失败/错误/跳过，BUILD SUCCESS；23 项既有迁移验证通过，无新迁移 |
| 14:26:39 | 暂把允许构建并发上限从 4 改为 5 | 限额反例 1 项按预期失败，变异被捕获；随即恢复 |
| 14:27:49 | 恢复后的配置定向测试及重新打包 | 14 项全通过，BUILD SUCCESS；最终包不含变异代码 |

完整后端命令（工作目录 `backend`）：

```sh
env PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH \
  SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/atoms_demo_test?currentSchema=atoms_verify_20261019_pa32b&connectTimeout=3&socketTimeout=5' \
  ./mvnw -B -Dit.test=ProjectSourceReadGatewayIT verify
```

真实 PG/子进程和 JVM 自附加使用已批准的沙箱外测试权限；不是关闭测试或访问其他数据库。统计只计本次 14:22 之后的 68 个 Surefire 报告，排除目录中 14 份历史报告。集成 5 项来自本次 Failsafe 输出，不将旧 277 项集成结果当作重跑。

定向与变异命令：

```sh
./mvnw -B -Dtest=ExecutionSettingsTest test
./mvnw -B -Dtest=ExecutionSettingsTest,AgentSettingsTest test
./mvnw -B '-Dtest=ExecutionSettingsTest#malformedOrExpandedBudgetsFailClosed' test
./mvnw -B -Dtest=ExecutionSettingsTest,AgentSettingsTest package
```

恢复后的 `ExecutionSettings.java` SHA-256 与完整回归时相同：
`92a921652d52eaa92e7417c57d1e5e75eb68ca9d10df77737586abaf01e34d37`。

测试覆盖默认关闭只读开关、加载无 I/O、不可变维护者集合、规范 UUID、路径/控制字符拒绝、数值上限、配置异常不回显原值以及实际 YAML 别名与 Spring 上下文。该切片不执行工具身份探测、项目授权、构建、运行或数据库绑定，这些分别由后续任务实现。

配置按既有 `Environment` 惰性读取，依据 [Spring Boot 3.5 配置文档](https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html)。目录租约后续依 [Java 17 FileLock](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/channels/FileLock.html)保持同一文件的唯一描述符；该规则尚不代表 EX02 已完成。

## 2026-10-20 · EX02a 根租约

先运行 `./mvnw -B -Dtest=ExecutionRootLeaseTest test`，因缺少待实现类编译失败。实现后 14:32:13，以下命令 20 项全部通过、无跳过，平台重新打包成功：

```sh
./mvnw -B -Dtest=ExecutionRootLeaseTest,ExecutionSettingsTest,ProjectSourcePathsTest,AgentStateDirectoryTest package
```

新测试 4 项包含真实两 JVM 争用、同 JVM 重复申请后仍保持 OS 锁、重复检查不释放锁、关闭后可重开，以及 root UID、未标记/宽泛/重叠/符号链接目录、权限变化、根/标记替换和硬链接反例。测试 JVM 环境清空，限时等待并确认结束；只修改 `@TempDir` 下的合成文件。

生产默认身份读取使用 [Java 17 UnixSystem](https://docs.oracle.com/en/java/javase/17/docs/api/jdk.security.auth/com/sun/security/auth/module/UnixSystem.html) 的原生 UID，不以可覆盖的 `user.name` 判断 root。文件锁是协作锁，标记与私有目录不是同 UID 恶意代码的隔离机制。EX02 的任务目录/容量尚未完成，执行器仍未接入启动流程。

## 2026-10-20 · EX02b 目录能力与容量

`ExecutionPathsTest` 先因缺少实现类产生 12 处编译错误；实现后 14:40:10：

```sh
./mvnw -B -Dtest=ExecutionPathsTest,ExecutionRootLeaseTest,ExecutionSettingsTest,ProjectSourcePathsTest,AgentStateDirectoryTest package
```

25 项通过，0 失败/错误/跳过，BUILD SUCCESS。新增 5 项验证分离的 builds/artifacts/runs 私有目录、不可跨发行者使用的任务句柄、目录/标记替换、关闭后撤销、缺失布局不补建、同 UUID 不覆盖，以及容量拒绝时不分配任务、不删除证据。真实文件只写入临时目录；8 GiB/1 GiB 精确边界通过算术测试，小预算真实文件验证容量流程，不分配 8 GiB 测试文件。

容量扫描不跟随 npm 包符号链接，普通文件按逻辑长度计算；最大深度 64、10 万节点、10 秒扫描预算，超界保守拒绝。该检查不是硬磁盘配额，不能阻止同 UID 代码并发写盘。根/控制目录重新核对身份和权限，未知顶层内容不自动收养或清理；已有任务的恢复决策仍由 EX13/22 实现。EX02 完成，但尚未启用构建。

## 2026-10-20 · EX03a 共享进程兼容入口

先增加 `OwnedProcessTest`，14:42 因 4 处尚不存在的 `runCommand` 调用 RED。新增兼容入口后，14:43:44：

```sh
./mvnw -B -Dtest=OwnedProcessTest,AgentProcessTest,AgentProcessInterruptionTest,AgentCliProbeTest,AgentStreamDecoderTest package
```

32 项全部通过且完成打包。新 4 项涵盖独立 stdout/stderr、绑定停止证明、非正预算不启动、未知子进程证据不发行证明，以及 preflight/decoder/budget 原始异常文本不泄漏。原 Agent 28 项回归包含重复中断期间继续等待自己的 I/O、阻塞 stdin、期限终止、输出洪泛和仍占管道的子进程。没有改造或复制 10 秒收尾机制，没有改变源码停止证明接口。

依据 [Java 17 ProcessBuilder](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/ProcessBuilder.html) 明确使用参数列表与分离管道；它不是操作系统沙箱。执行层自身的环境构造与输出计数适配留在 EX03b，此记录不宣称完整构建通过。

## 2026-10-20 · EX03b / C-EX01

`ExecutionProcessTest` 缺类 RED 后，实现私有调用参数副本、清空继承环境、固定白名单、单调总期限及双路有界排空（各 8 MiB，超过停止；原 Agent 限额未改）。原始输出不保存，返回计数和 `Stopped`；工具/工作目录的实际身份批准仍由 EX06/执行准入负责，不能直接把任意用户参数送入该内部接口。

14:49 第一次运行 38 项中有 1 项断言失败：测试 canary 文字实际 22 字节，手写预期 21；独立 UTF-8 计数确认后改为按字节长度断言，没有改低生产检查或跳过测试。14:50:48 同一命令 38 项全通过、打包成功：

```sh
./mvnw -B -Dtest=ExecutionProcessTest,OwnedProcessTest,AgentProcessTest,AgentProcessInterruptionTest,AgentCliProbeTest,AgentStreamDecoderTest package
```

新增 6 项还包括非零退出、启动失败（保留未知而非伪造停止）、启动前工具变化、取消/到期不启动、两路输出洪泛、另一自有夹具进程不受取消影响，以及故意卡住 decoder 并反复中断。最后一个场景实际等待约 10 秒后返回 `EXECUTION_PROCESS_UNSETTLED`，证明收尾没有因重复中断续期；测试释放阻塞的合成 decoder 后结束，不留下应用执行记录。

14:53:24 完成全量后端单元及源码兼容集成：

```sh
env PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH \
  SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/atoms_demo_test?currentSchema=atoms_verify_20261019_pa32b&connectTimeout=3&socketTimeout=5' \
  ./mvnw -B -Dit.test=ProjectSourceReadGatewayIT,ProjectSourceEditAcceptanceIT verify
```

本次 14:51 后的 72 份单元报告合计 **316 项**，集成 **9 项**，全部 0 失败/错误/跳过，BUILD SUCCESS。测试 Spring Boot 以默认关闭模式启动，23 项旧迁移校验通过、没有新迁移。C-EX01 完成；这不是完整新工程或真实模型的验收，不启用日常配置、不推送/部署。

另作环境边界变异：暂去掉 `environment().clear()` 后，14:56:15 定向环境断言失败，检测出继承的 `HTTP_PROXY`，在启动子进程之前即中止。断言只输出变量名，不输出值。随即恢复，14:58:01 `./mvnw -B -Dtest=ExecutionProcessTest package` 的 6 项全通过，重新打包排除变异产物。恢复后 `ExecutionProcess.java` SHA-256 为 `2bdbf24b1e8e0aaed4927ce41aca5ca1f26e6080560e23c9bc2461dd200dac27`。

## 2026-10-20 · EX04a 源码读取共享期限

新增只读重载测试先因 5 处尚未存在的四参数 `read` 调用编译失败。实现后 15:03:46：

```sh
env PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH \
  SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/atoms_demo_test?currentSchema=atoms_verify_20261019_pa32b&connectTimeout=3&socketTimeout=5' \
  ./mvnw -B -Dtest=ProjectSourceDeadlineTest,ExecutionProcessTest \
  -Dit.test=ProjectSourceReadGatewayIT,ProjectSourceEditDeadlineIT verify
```

9 单元、11 集成全部通过，BUILD SUCCESS。真实 Git/PG 验证已有新版本仍读取指定旧版本、读取过程中到期不将健康源码标坏、预算回调异常不回显、事务内读取拒绝及读取期间所有权改变后的最终授权检查。原三参数门面与编辑租约期限测试保留。未新增迁移、未访问日常库。

## 2026-10-20 · EX04b 完整快照物化与复验

`ExecutionSnapshotIT` 先因缺少类的 3 处引用 RED。15:44:32 首轮 39 单元 + 14 集成通过。审查补齐容量扫描共享预算、可执行文件模式与中途复制超时；容量扫描重载先有缺方法 RED。最终 15:53:06：

```sh
env PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH \
  SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/atoms_demo_test?currentSchema=atoms_verify_20261019_pa32b&connectTimeout=3&socketTimeout=5' \
  ./mvnw -B -Dtest=ExecutionPathsTest,ExecutionRootLeaseTest,ProjectSourcePolicyTest,ProjectSourceDeadlineTest \
  -Dit.test=ExecutionSnapshotIT,ProjectSourceReadGatewayIT verify
```

**47 单元 + 15 集成**全部通过，0 跳过，BUILD SUCCESS。真实快照集成 7 项验证指定 V1 在 V2 发布后仍保留原字节、新版配置文件及 0644/0755 模式、重复物化不覆盖、句柄不可冒用、字节/模式/硬链接/符号链接/额外文件/超大文件拒绝、归属变化、事务拒绝，以及期限在复制和后续复验之间共享。复制中途到期保留部分任务文件，但不返回成功句柄、更不修改 Git 正式版本。

物化及读取复用完整源码策略（512 文件 / 2 MiB 单文件 / 16 MiB 总量），逐项核对数据库元数据、快照摘要和复制字节。复验只允许 `frontend/node_modules`、`frontend/dist`、`frontend/coverage`、`frontend/test-results`、`backend/target` 这五个输出目录；输出根本身是链接仍拒绝。工具尚未运行，实际构建产物校验属于后续任务。所有测试仍只用原测试 schema 和新临时目录，没有新迁移或日常环境变更。

## 2026-10-20 · EX05 npm 依赖批准

先新增测试，因缺少 `ExecutionNpmPolicy` 编译 RED；16:08:23 种子及输入反例 39 项定向通过。审查发现 JSON 长整数转 int 可回绕，新增反例在 16:10:30 先失败，改为严格 int 类型且值匹配后，16:12:03：

```sh
./mvnw -B -Dtest=ExecutionNpmPolicyTest,ProjectSourcePolicyTest package
```

**40 项**全部通过，0 失败/错误/跳过，BUILD SUCCESS。其中 6 个 npm 测试方法包含多个反例向量，不能按向量虚增测试数。使用真实种子锁文件的 237 个包，外部批准绑定完整 `package.json` / `package-lock.json` 字节摘要；任一变化需新批准。检查根清单/锁记录一致、依赖位置和必需节点、HTTPS registry 精确路径、SHA-512 完整性、重复 JSON 字段、格式版本、workspace/配置/shrinkwrap/bundle/URL/git/file/link 绕过。npm 的完整 semver 求解和实际冻结安装仍由后续固定 `npm ci` 执行，这里不声称重写了 npm。

准备批准与构建批准使用不同结果类型；缺锁可准备但不可构建，存在旧锁时也必须匹配准确批准。生成新锁不自动获得构建权限。源码内的批准文件不参与决策；资源中的策略是维护者模板，日常实际配置文件未创建/启用。没有调用 npm 安装或网络下载。

格式依据 [npm 11.19.0 锁文件文档](https://github.com/npm/cli/blob/v11.19.0/docs/lib/content/configuring-npm/package-lock-json.md)及 [npm 11.19.0 ci 文档](https://github.com/npm/cli/blob/v11.19.0/docs/lib/content/commands/npm-ci.md)。非根包锁记录里已有的 `workspaces`（种子的 eslint 元数据）不是根 workspace 准入，不因该描述字段去解析本地目录；其全部字节仍绑定锁摘要。仍需 EX06 的固定工具/配置与 EX09 的安装参数才能执行，此策略不是网络或 OS 沙箱。

## 2026-10-20 · EX06a Maven 策略

先新增 `ExecutionMavenPolicyTest`，16:18 因缺少类的 4 处引用 RED；16:20:52：

```sh
./mvnw -B -Dtest=ExecutionMavenPolicyTest,ExecutionNpmPolicyTest,ProjectSourcePolicyTest package
```

**44 项**全通过，0 跳过，BUILD SUCCESS。Maven 新增 4 个测试方法覆盖真实种子 POM、Java 业务代码变化不改变依赖批准、POM 摘要变化、额外 POM/配置入口、未知 profile/仓库/属性/插件、关闭“无测试即失败”、重复元素、非法 XML 及外部实体。解析失败只有稳定错误码，无原 XML、外部位置或解析器 stderr。

批准文件新增父 POM、准确依赖坐标及 POM/包装器属性文件摘要；包装器属性只作为保留源码核对，平台不调用源 `mvnw`。受支持模型是单模块 Java 17，两个既定构建插件及一个平台控制的前端资源 profile；其他 Maven 模型能力明确拒绝，不自动放宽到任意 profile/扩展。原 npm 批准仍绑定自身完整清单与锁文件，策略整体摘要随新增 Maven 节变化。

已只读确认本机工具路径及 npm 包版本，但尚未实现工具探测/身份持有与执行参数接线，EX06b 及 C-EX02 未完成。配置入口依据 [Maven 官方说明](https://maven.apache.org/configure.html)，固定参数以 [Maven 3.9.11 CLI](https://maven.apache.org/ref/3.9.11/maven-embedder/cli.html)为准；XML 防护依据 [Java 17 JAXP 安全指南](https://docs.oracle.com/en/java/javase/17/security/java-api-xml-processing-jaxp-security-guide.html)。这些策略测试不是 Maven 实际构建验收。

16:29:38，当前实现再做全部后端单元 + 3 组源码兼容集成：

```sh
env PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH \
  SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/atoms_demo_test?currentSchema=atoms_verify_20261019_pa32b&connectTimeout=3&socketTimeout=5' \
  ./mvnw -B -Dit.test=ExecutionSnapshotIT,ProjectSourceReadGatewayIT,ProjectSourceEditAcceptanceIT verify
```

本次 16:26 后的 74 份单元报告合计 **327 项**，集成 **19 项**（7 快照 / 8 读取 / 4 停止证明及接受），全部 0 失败/错误/跳过，BUILD SUCCESS。23 项迁移仍只校验、无新迁移。不把该结果写成全量集成、前端浏览器、真实 CLI/模型或生成应用构建运行已重测。

收尾文档检查：6 份本轮相关文档中的 34 个本地文件链接全部存在，`git diff --check` 通过；该检查不包含重新验证所有历史标题锚点。后续验收环境的准确 DB/角色/端口候选记录在计划 §4.4，尚未获准或创建。

## 2026-10-20 · 获准创建本机验收资源

用户“允许”后，先核对计划 §4.4 四个准确 DB/同名角色均不存在、18181/18182 可绑定。Docker 管理查询超时；改用既有 `.env` 中管理员凭据经 loopback JDBC 连接，仅在内存读取，不重启 Docker 或变更个人配置。确认实际 PostgreSQL 17.11 后再执行创建，没有接管已有对象。

创建 `atoms_exec_{a,b}_{test,app}_20261020` 四套独立 DB/登录角色。数据库归管理员，每库空 `generated_app` schema 归对应低权限角色；撤销新库及 public schema 的 PUBLIC 权限，只授予对应角色 CONNECT。角色无超级用户、建库、建角色、复制、绕过 RLS 或角色成员关系。密码为独立随机 256 位值，以 SCRAM verifier 创建，SQL/异常不输出凭据。配置在已被 Git 忽略的 `.local/project-execution-acceptance.x5cvui`（0700）内，四份文件均以 0600 创建，未修改既有 env。

真实验收：四账号分别登录本库，检查实际库/角色/权限；在各自 schema 中建表、写入、读取后事务回滚，保持空库；12 种跨库连接均被拒绝（42501）；四次错误密码均被拒绝（28P01）。没有给应用角色管理员凭据，也未修改其他数据库的 ACL；这不是“整个 PG 实例的所有旧 PUBLIC 授权都已加固”的声明。此设置遵循 [PG 17 角色说明](https://www.postgresql.org/docs/17/sql-createrole.html)、[权限说明](https://www.postgresql.org/docs/17/ddl-priv.html)及 [SCRAM 存储格式](https://www.postgresql.org/docs/17/catalog-pg-authid.html)。

仅资源准备完成，EX08/EX11/EX17/EX27 仍待代码接线和真实工程验收。没有启动生成应用、安装依赖、调用模型、推送或部署。
