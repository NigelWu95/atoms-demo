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

## 2026-10-20 · EX06b1 任务私有工具配置

先补 `ExecutionToolHomeTest`，17:20 因缺类的 6 处引用 RED。实现私有配置、缓存和目录身份复验，17:22 首轮 25 项定向测试及打包通过。随后增加等长字节篡改、目录越界、可变缓存及容量反例；17:24 暂时移除配置内容比较，等长篡改测试正确失败，随后恢复检查。

`ExecutionToolHome` 仅为持有的构建任务分配新 `tool-home`，目录 0700/文件 0600；不接管已有目录或自动修复篡改。文件内容、inode、权限、硬链接数、目录身份和额外配置均复验，复用执行路径的原有身份检查而非另建信任标准。缓存和 tmp 内容可变，但其目录身份与整根容量仍检查；期限继续共享，过期不返回成功句柄。

固定 npm registry 为已批准的 HTTPS npmjs，禁止安装脚本、关闭 audit/fund/更新提示和 npm 文件日志；Maven 提供独立用户/全局 settings、空 toolchains、每任务本地仓库及仅指向 Maven Central 的 mirror。生成固定 `-B/-C/-N/-ntp/-itr` 与配置位置前缀，没有拼接源码 scripts。依据 [npm 11.19.0 npmrc](https://github.com/npm/cli/blob/v11.19.0/docs/lib/content/configuring-npm/npmrc.md)、[Maven settings](https://maven.apache.org/settings.html)及 [Maven 3.9.11 CLI](https://maven.apache.org/ref/3.9.11/maven-embedder/cli.html)。已有 `ExecutionProcess` 的显式环境新增 `MAVEN_BASEDIR=cwd`，防止 Maven 向祖先查找 `.mvn`；不继承个人环境。

最终 17:29:05，工作目录 `backend`：

```sh
env PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH \
  SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/atoms_demo_test?currentSchema=atoms_verify_20261019_pa32b&connectTimeout=3&socketTimeout=5' \
  ./mvnw -B -Dit.test=ExecutionSnapshotIT,ProjectSourceReadGatewayIT,ProjectSourceEditAcceptanceIT verify
```

本轮 17:25 后 75 份单元报告共 **331 项**，选定集成 **19 项**，全部 0 失败/错误/跳过，BUILD SUCCESS（3 分 39 秒）；Java 17.0.5 保持不变。原测试 schema 的 23 项迁移仅验证，无新迁移。新增四库未执行应用迁移。配置一致性测试不是工具实际构建验收；EX06b2 仍须补工具身份/版本/漂移、完整 argv 接线及 JVM 实际私有 home 的验证，C-EX02 未勾选。没有运行全部集成、前端浏览器、真实 CLI/模型或生成应用。

## 2026-10-20 · EX06b2a 工具文件身份

`ExecutionToolFilesTest` 先缺类 RED；补混合 `"."` 子树选择反例后再次 RED，改为仅允许单独选择整棵树。17:49 暂停身份比较的变异导致 2 个测试失败，随后恢复。17:50:35：

```sh
./mvnw -B -Dtest=ExecutionToolFilesTest,ExecutionToolHomeTest,ExecutionMavenPolicyTest,ExecutionNpmPolicyTest package
```

**17 项**通过，0 失败/错误/跳过，BUILD SUCCESS。新增 3 个测试方法覆盖加载库等长篡改、文件替换、增删、符号/硬链接、权限变化、根替换及扫描期限。按已安装工具的指定运行子树计算文件摘要，记录 inode/owner/mode，最多 20,000 项、单文件 512 MiB、总量 1 GiB、深度 32；超时/非法安装只返回稳定错误码，不回显路径或内容。摘要只描述初次认可安装的文件一致性，不是发行版真实性认证，也不是对同 UID 恶意进程的 OS 隔离。

本次还没有执行版本探测或 npm/Maven 构建，EX06b2b 继续接线；未修改任何已安装工具、个人配置或数据库。

## 2026-10-20 · EX06b2b 固定命令与真实工具探测

`ExecutionToolchainTest` / `ExecutionToolProbeTest` 分别先缺类 RED。固定 npm 由绝对 Node 启动已固定的 npm CLI；Maven 由绝对 Java 直接启动安装发行版的 Classworlds，不经过 shell、个人 mavenrc 或源码包装器。安装布局及配置路径映射也复验，npm 入口符号链接重新指向其他文件会被拒绝。固定操作包含 ci、lock-only、类型检查、lint、单测、前端构建和 Maven verify；这是内部命令目录，不代表流水线已接入，也不能代替依赖/源码批准。前端资源装配、测试数据库与构建产物验收仍在 EX08–11。

版本仅接受 Node 24.20.x / npm 11.19.x / Java 17.0.x / Maven 3.9.11，输出每路最多 16 KiB、严格 UTF-8，只保留版本与文件摘要；未知版本返回 `EXECUTION_REPORT_INVALID`。探测复用 `OwnedProcess`，单次最多 10 秒且不超过任务总剩余期限；错误/取消/身份漂移保留原停止证明，未知停止状态不改写为成功。

18:03 补充命令反例时，测试错误地把 Vitest 自身的 `run` 子命令当成 npm script；修正为只约束 npm CLI 的操作为 ci/install，不禁止固定 Vitest 入口。18:05:08，以下 **22 项**通过且打包成功：

```sh
./mvnw -B -Dtest=ExecutionToolProbeTest,ExecutionToolchainTest,ExecutionToolFilesTest,ExecutionToolHomeTest,ExecutionMavenPolicyTest,ExecutionNpmPolicyTest package
```

显式无下载探测命令（工作目录 `backend`）：

```sh
/Users/wubingheng/.sdkman/candidates/java/17.0.5-oracle/bin/java \
  -cp target/test-classes:target/classes:/Users/wubingheng/.m2/repository/org/slf4j/slf4j-api/2.0.18/slf4j-api-2.0.18.jar \
  dev.atoms.demo.projectexecution.ExecutionToolProbeTest \
  /Users/wubingheng/.nvm/versions/node/v24.20.0/bin/node \
  /Users/wubingheng/.nvm/versions/node/v24.20.0/bin/npm \
  /Users/wubingheng/.sdkman/candidates/java/17.0.5-oracle/bin/java \
  /Users/wubingheng/.m2/wrapper/dists/apache-maven-3.9.11/a2d47e15/bin/mvn
```

沙箱内首次返回 `EXECUTION_PROCESS_UNSETTLED`；获准在沙箱外原样执行后，四个真实工具通过且均有匹配停止证明。未修改停止逻辑、未把第一次失败计为通过。实际版本 Node **24.20.0**、npm **11.19.0**、Java **17.0.5**、Maven **3.9.11**；Java 和 Maven `-XshowSettings:properties` 均确认主 JVM 的 `user.home` / `java.io.tmpdir` 是任务私有路径，`java.home` 是固定 JDK。探测末尾再次核对工具/home 身份，无 npm 安装或 Maven 插件下载。SLF4J 无 provider 的提示只来自手动测试的最小类路径。

依据 [Classworlds 启动方式](https://codehaus-plexus.github.io/plexus-classworlds/launcher.html)、[Java 17 启动参数](https://docs.oracle.com/en/java/javase/17/docs/specs/man/java.html)、[Maven 3.9.11 CLI](https://maven.apache.org/ref/3.9.11/maven-embedder/cli.html)和 [npm 11.19.0 ci](https://raw.githubusercontent.com/npm/cli/v11.19.0/docs/lib/content/commands/npm-ci.md)。这仅证明工具探测及主进程设置；子 JVM 继承还需 EX06b2c，不据此勾选整个 EX06 或真实工程构建。

## 2026-10-20 · EX06b2c 子 JVM 配置继承

18:07 私有 JVM 继承测试先因缺方法 RED；18:09 真实父/子 JVM 与工具接线定向 **12 项**通过。平台只在本模块工具调用的环境中生成含两个固定属性的 `JDK_JAVA_OPTIONS`，不复制宿主同名值；基础 `Invocation` 和 Agent 入口保持原行为。父、子 JVM 在未额外传递 `-D` 的情况下分别校验 `user.home` / `java.io.tmpdir`，包含空格及单引号的路径通过；双引号/反斜杠组合明确拒绝，不能突破参数边界。随后断开工具目录到该配置的接线，18:10 变异测试正确失败，恢复后再回归。

补充 npm 私有配置 canary：被修改时在探测进程启动前拒绝，有匹配的未启动/已停止证明，错误不带 canary 内容或路径。四个实际安装工具在新增环境参数后重新执行上节同一手动探测命令，全部通过且文件摘要与之前一致。根据 [Java 17 的 JDK_JAVA_OPTIONS 规则](https://docs.oracle.com/en/java/javase/17/docs/specs/man/java.html#using-the-jdk_java_options-launcher-environment-variable)，这里只传固定 JVM 属性，没有 shell、任意 JVM flag 或源码参数入口；启动器产生的提示在内存中有界读取，不记录原始 stderr。

该验证证明正常继承环境的 Java 子进程，不是生成工程的 Surefire 测试或任意子进程安全隔离；EX11 仍须真实 Maven 构建验收。同 UID 代码主动改环境/访问磁盘无法靠这些属性阻断，执行功能继续默认关闭、限定维护者。

最终 18:14:46，工作目录 `backend`：

```sh
env PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH \
  SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/atoms_demo_test?currentSchema=atoms_verify_20261019_pa32b&connectTimeout=3&socketTimeout=5' \
  ./mvnw -B -Dit.test=ExecutionSnapshotIT,ProjectSourceReadGatewayIT,ProjectSourceEditAcceptanceIT verify
```

本轮 18:11 之后 78 份单元报告合计 **340 项**，3 份集成报告 **19 项**（7 快照 / 8 读取 / 4 编辑接受），全部 0 失败/错误/跳过，BUILD SUCCESS（3 分 30 秒）。既有测试 schema 的 23 个迁移只校验，未新增迁移，未连接四个新业务/测试库；Java 仍为 17.0.5。进程表只读检查未发现本轮执行夹具或工具探测类残留，未停止其他进程。

EX06 / C-EX02 完成，下一项 EX07。没有运行全量集成、浏览器、模型/Claude 真实会话或生成应用的 npm/Maven 构建；没有修改私有 env、安装版本、推送或部署。

## 2026-10-20 · EX07a 构建准入表

19:04 真实 PG 测试因缺 `execution_builds` 表 RED；V023 只在 `atoms_verify_20261019_pa32b` 应用，旧 V000–V022 校验未改变。新表保存固定来源、请求摘要、阶段及容量槽位，不保存源码、凭据、原始输出或用户路径。数据库约束固定项目只能存在一个非终态构建，等待槽位最大 8、执行槽位最大 4，单行还受准入配置限制。

阶段变化由数据库触发器同时记录类型固定的 JSON 事件和累计字节/序号，1000 条 / 64 KiB 任一超限导致状态变化一起回滚。测试覆盖来源篡改、跳过阶段、槽位越界、事件修改/删除、事件配额耗尽、未决进程不能直接释放容量及排队取消。测试本身整笔回滚；没有删除既有审计。依据 [PostgreSQL 17 部分唯一索引](https://www.postgresql.org/docs/17/indexes-partial.html)和[触发器事务行为](https://www.postgresql.org/docs/17/trigger-definition.html)。

19:09:42，同上 Node/schema 环境前缀执行 `./mvnw -B -Dit.test=ExecutionBuildSchemaIT verify`：**340 单元 + 1 数据库集成**全部通过，0 失败/错误/跳过，打包成功。五轴自审核对：仅追加表/函数，无旧业务写入；所有身份/来源受外键和不可变约束；事件仅固定字段；容量索引有界。关闭模块可回退，不执行删除证据的 down migration。内部提交服务与双连接幂等测试仍待 EX07b；未启动应用构建或进程。
