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

## 2026-10-20 · EX07b 内部提交与查询

19:12 新准入测试因缺服务/仓储类 RED。19:15 Spring 上下文暴露仓储 `final` 与异常翻译代理不兼容，按仓库既有非 final 仓储约定修正；没有关闭代理或绕过上下文测试。19:16 的 7 配置单元 + 2 集成及打包通过，随后 19:18 扩展至 7 单元 + 6 集成通过。

`submit` 的两个短事务之间按准确源码 UUID 读取真实 Git 快照；第二次锁项目重新检查归属和归档。任务和首条事件在一笔事务中落库；只在数据库中保存请求/键摘要及来源引用，不初始化执行根或调用工具。对同键重放优先返回已有任务，不因重试占新队列槽；队列查询在项目锁之后取得固定事务级 advisory lock，独立部分唯一索引保护槽位/项目。查询使用当前所有者而不是旧任务 owner 作为访问授权。

19:20 暂时跳过第二次项目授权：读取结束后归档的回归测试明确失败（错误地准入）；立即恢复。19:23 不完整 Unicode 幂等键的反例先 RED，随后拒绝未配对代理项，避免 UTF-8 替换字符导致不同键散列别名。19:27 当前组合中的 **7 项准入 + 2 项 schema**通过：双事务以 barrier 会合并确认两个不同 `pg_backend_pid()`，争夺最后一格只能一个成功；旧版本不会替换成新 HEAD、跨项目版本拒绝、归档/转移/关闭后拒绝、外层事务读源码拒绝、原子回滚无任务/事件/槽残留。额外直接 SQL 证明活动项目、等待槽和执行槽的唯一性不是 JVM 判断。

此时完整回归仍在进行，另有“降低队列配置时计算既有占用”边界待验证，因此当时未勾选 EX07；最终结果见下节。CLI 离线专用 profile、真实模型和生成应用构建不在上述测试范围。

### 完整回归暴露的测试连接池保留

19:44:16，按上面的 Node/schema 前缀运行 `./mvnw -B verify`，**340 单元通过；296 集成中 286 通过、10 错误，0 断言失败/跳过**，BUILD FAILURE，耗时 20:20。该轮包含 7 项准入测试，运行开始后补写的队列缩容反例不在这 296 项中。

10 个错误全部来自 `GenerationExecutionIT` 的上下文启动：`remaining connection slots are reserved for roles with the SUPERUSER attribute`。本模块新增两种配置不同的缓存上下文，每个池最大 5 条连接、未另设 minimumIdle；结合 [HikariCP 的 minimumIdle 默认等于 maximumPoolSize](https://github.com/brettwooldridge/HikariCP#configuration-knobs-baby)与上下文日志，定位到测试结束后池仍被缓存持有，组合回归后期无法再创建新连接。没有改数据库最大连接数或重启 Docker，也没有把这些错误归为业务断言失败后删除测试。

按 [Spring 6.2 上下文缓存规则](https://docs.spring.io/spring-framework/reference/6.2/testing/testcontext-framework/ctx-management/caching.html)及 [DirtiesContext 关闭行为](https://docs.spring.io/spring-framework/reference/6.2/testing/annotations/integration-spring/annotation-dirtiescontext.html)，仅为三个 `projectexecution` 集成测试类加 `AFTER_CLASS`，使其用完后关闭本类上下文/连接池。生产池大小与所有并发断言保持不变；恢复后的定向与完整结果另记。

22:05:06，定向命令 `./mvnw -B -Dtest=ExecutionSettingsTest '-Dit.test=ExecutionAdmissionIT#loweringQueueLimitCountsOlderTasksEvenWhenTheFirstSlotIsFree,ExecutionBuildSchemaIT,GenerationExecutionIT' verify`（同 Node/schema 前缀）：7 单元通过、13 集成中 12 通过/1 预期 RED，0 错误/跳过。日志确认 HikariPool-1 和 -2 在各自测试类结束时完成 shutdown；原来无法启动的旧生成器 10 项全部通过。

唯一 RED 是新增缩容反例：旧队列还有槽位 2 的任务，把上限从 8 调成 1 后，槽位 1 空闲不能成为继续准入的理由。修复为在同一事务级全局锁内先计算全部等待占用，再挑空位；不重排旧任务、不释放未知占用、不改变其原始配置/审计。相同任务的幂等重放仍可查询，不能按新额度重新排一份。最终完整回归随后运行，不把本次有预期 RED 的命令当成全量通过。

### EX07 最终完整回归与审计

22:25:37（Asia/Shanghai），工作目录 `backend`，以下命令 BUILD SUCCESS，耗时 19:09：

```sh
env PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH \
  SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/atoms_demo_test?currentSchema=atoms_verify_20261019_pa32b&connectTimeout=3&socketTimeout=5' \
  ./mvnw -B verify
```

**340 单元 + 297 集成，全部 0 失败、0 错误、0 跳过**，平台后端 JAR 构建成功。独立汇总本轮 22:06 后生成的 78 份 Surefire / 89 份 Failsafe XML，与命令结果一致，不累计旧报告。包含 8 项准入、2 项数据库约束、7 项源码快照，以及既有账号、生成、源码/Agent 恢复、导出、版本恢复和发布回归；默认排除的真实 CLI 专用 profile 不算本次通过，也不包含浏览器、真实模型或新模式应用构建/运行。

结束后使用只读审计检查批准的测试数据库：`atoms_verify_20261019_pa32b` 的 Agent 未决任务、待核对执行和活动源码租约均为 0。旧 `atoms` 中 `f7e3d276-1c27-43f4-8c64-866bb5b95ac4` 仍是 `NEEDS_RECONCILIATION / AGENT_CLI_UNSUPPORTED`，旧 `atoms_verify_20261019_pa32` 中 `af6755c5-182b-4e97-af94-1e5bd1b5e1d4` 仍是 `NEEDS_RECONCILIATION / AGENT_PROCESS_UNSETTLED`；二者待核对执行/活动租约及停止证据缺失均原样保留，基线没有变化。本轮没有清理这些历史记录或把它们改为已解决。测试角色没有创建 schema 权限。进程表只读检查未发现 Java/Claude 进程，未执行终止操作。

自审核对了正确性、授权/数据边界、事务和并发、可维护性及回归：源码读取不持有事务；提交前再次检查身份/归档/开关；固定来源及幂等摘要不可变；阶段与有界事件原子写入；锁顺序一致且有界；公开 DTO 不含键摘要、配置、路径、日志或凭据。仅新增内部服务及追加迁移，旧网页和生产连接池行为不变。退回仍为关闭新模块并保留追加表与审计，不执行破坏性 down migration。

DOC：规格、计划、任务清单、能力地图和本文的 27 个本地文档链接/锚点校验通过；`git diff --check` 通过。保留用户未跟踪的 OAuth 草稿，不纳入本轮提交。

EX07 完成，下一项为 EX08 测试数据库真实目标检查，C-EX03 仍未完成。未启用执行模块、安装依赖、调用付费模型、修改个人配置/私有 env、推送 GitHub 或部署远程。

## 2026-10-20 · EX08 私有测试数据库与真实目标

按已批准的 EX08 拆为两片：EX08a 两个内部实现类与一个单元测试；EX08b 一项真实集成测试类与 POM 显式 profile。提前使用原定 EX11 的 `project-execution-contract`，避免普通回归或 Agent CLI 验收暗中读取私有数据库配置；并未提前实现种子工程构建。

威胁边界为维护者文件 → 内存配置 → PostgreSQL 系统目录结果。资产是平台/业务数据和专用角色凭据；反例包含业务库伪装成测试库、JDBC 参数/环境插值、跨项目重复绑定、文件/目录替换、超权角色、用途标记不符与凭据异常泄露。普通同 UID 进程的风险不变；只读校验、配置模式及目录身份不能阻止恶意生成代码主动读取宿主文件。当前完整注册表由维护者一次性装载，不是外部请求可创建的连接；持久业务库归属仍由后续 EX17 接线。

22:50:18，`./mvnw -B -Dtest=ExecutionTestDatabaseTest test` 因缺两实现类的 9 处引用 RED。实现后 22:53:03，`./mvnw -B -Dtest=ExecutionTestDatabaseTest,ExecutionSettingsTest package` **12 项**通过并打包。随后加入角色标记/权限及显式连接参数反例。

22:55 首次变异命令受沙箱的 Mockito JVM attach 限制，不能视为测试捕获变异；获准沙箱外执行同一命令后，22:56:30 正确出现 **1 项断言失败**：暂时移除角色用途标记比较，使错误角色标记被接纳。立即恢复比较，未关闭代理、跳过测试或改变 JDK。

22:57:30，显式 profile 但不提供私有目录，**14 单元通过、4 集成明确失败、0 跳过**；失败位置为配置前置条件，没有连接数据库。22:57:59 加入已批准的私有目录，以下命令 **14 单元 + 4 真实集成全部通过**，0 失败/错误/跳过，BUILD SUCCESS：

```sh
env PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH \
  ./mvnw -B -Pproject-execution-contract \
  -Dexecution.test-config-directory=/Users/wubingheng/atoms-demo/.local/project-execution-acceptance.x5cvui \
  -Dtest=ExecutionTestDatabaseTest,ExecutionSettingsTest -Dit.test=ExecutionTestDatabaseIT verify
```

实际两套测试库返回不同数据库/角色 OID 和绑定 UUID。两个业务库的本地文件即使伪造 `purpose=test`，仍因真实库/角色标记不符而拒绝。错误绑定、错误密码、已注册文件替换和重复项目绑定均拒绝；当次四库 `generated_app` relation 数均为 0。所有连接关闭，没有清理、DDL/DML 或迁移；没有读取管理员/个人配置。随后把“保持空库”的验收断言改为检查前后 relation 数一致，避免未来受控构建留下合法测试表时误报；此变化不放宽目标校验，也不把清库作为前置步骤。

22:59:38，`./mvnw -B -Pagent-cli-contract -Dit.test=ExecutionTestDatabaseIT failsafe:integration-test failsafe:verify` 选中 **0 项**，验证单独启用 Agent profile 仍排除执行数据库契约；这只证明选择边界，不计作功能测试通过。

实现依据：[PG 17 角色属性](https://www.postgresql.org/docs/17/view-pg-roles.html)、[身份/ACL/共享对象注释函数](https://www.postgresql.org/docs/17/functions-info.html)、[pgJDBC 连接属性与只读事务](https://jdbc.postgresql.org/documentation/use/)、[Java 17 Connection](https://docs.oracle.com/en/java/javase/17/docs/api/java.sql/java/sql/Connection.html)及 [Failsafe JUnit 标签](https://maven.apache.org/surefire/maven-failsafe-plugin/examples/junit-platform.html)。固定本机 loopback 模式显式关闭 SSL/GSS/JAAS 凭据查找，不是远程数据库配置方案；无任意 URL 参数、个人 env 或 TLS 配置回退。

最后增加 close/rollback 的异常脱敏，避免 suppressed driver 错误携带连接详情，随后运行下述完整回归。没有变更生产开关、用户私有 env、已安装工具或数据库授权，没有模型调用、推送或部署。

### EX08 最终完整回归与审计

23:18:09（Asia/Shanghai），工作目录 `backend`：

```sh
env PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH \
  SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/atoms_demo_test?currentSchema=atoms_verify_20261019_pa32b&connectTimeout=3&socketTimeout=5' \
  ./mvnw -B -Pproject-execution-contract \
  -Dexecution.test-config-directory=/Users/wubingheng/atoms-demo/.local/project-execution-acceptance.x5cvui verify
```

**347 单元 + 301 集成全部通过，0 失败/错误/跳过**，BUILD SUCCESS，耗时 17:43，平台后端 JAR 构建成功。按本轮 23:00 后的 79 份 Surefire、90 份 Failsafe XML 单独汇总确认一致；301 已包含 4 项实际只读契约，不重复加算。覆盖原账号、源码/Agent（含取消、崩溃恢复及并发）、旧生成、预览、导出与版本恢复等。真实 CLI 专用 profile 仍排除，未进行浏览器或真实模型/生成应用构建验收。

末尾再次只读核对：`pa32b` 的 Agent 未决任务/待核对执行/活动源码租约均为 0；旧 `atoms` 与 `pa32` 的两份未决审计、活动租约、缺停止证明和未改变的基线原样保留（具体标识同 EX07 末尾记录）。测试角色仍无建 schema 权限。进程表没有 Java/Claude 进程；未执行 kill 或清除历史证据。

五轴自审：职责局限于文件配置及只读连接；复用原路径检查与预算接口，不新增依赖/全局连接池/公开端点。文件最多 8 KiB、注册表最多 4 项、参数显式白名单；SQL 全部固定系统目录查询，错误不带凭据或底层异常。低权限/真实用途与本地文件用途分别校验，返回值不充当长期迁移或执行权限。保持默认关闭，回退不涉及删除数据库或数据。DOC 的 28 个本地链接/锚点、30 项任务编号、10 个检查点及 `git diff --check` 通过。

EX08 完成，下一项 EX09 固定前端检查流水线；C-EX03 仍未完成。用户 OAuth 未跟踪草稿保留，不纳入提交。上述结论是数据库校验能力，不是完整新应用已经可生成、构建、运行或在网页预览的声明。

## 2026-10-20–21 · EX09 固定前端流水线

拆分为报告校验、固定流水线、持久阶段接线三片。信任边界是子进程及其输出文件 → 平台数值摘要 → 持久事件；报告不是安全证明，仍只执行维护者受信代码。关心三个问题：当前停在哪一步、是否真的执行了测试、是否已经确认子进程及 I/O 结束。禁止把源码脚本、退出成功或原文“通过”作为这些问题的答案。

EX09a：按 [Vitest 5.0.3 JSON reporter 实现](https://github.com/vitest-dev/vitest/blob/v5.0.3/packages/vitest/src/node/reporters/json.ts)校验报告，套件计数包含嵌套 describe，不与文件数强行相等；要求文件/用例非空、每条用例 passed、无失败/跳过/待办、汇总与用例计数一致。单份报告解析最多 1 MiB、32 层嵌套、512 文件、10000 用例/套件；这些是解析上限，不扩大既有每任务 64 KiB 持久诊断预算。不输出标题、路径、元数据和报错原文，不新增依赖。

23:27:55，`./mvnw -B -Dtest=ExecutionDiagnosticsTest test` 因缺实现类 RED；23:29:02，`./mvnw -B -Dtest=ExecutionDiagnosticsTest,ExecutionNpmPolicyTest package` 共 9 项通过并打包，0 失败/错误/跳过。

自审暂时移除实际用例数与汇总比较，23:30:07 测试仍绿，暴露测试缺口：已有反例只改 total，先被 passed/total 不一致拦住。补上 total/passed 同时改大但实际只有一条用例的反例，23:30:48 正确出现 1 项断言失败；随后恢复真实计数比较。未保留变异、未删断言。

23:31:46，恢复后同一 diagnostics/npm 定向 `package` 命令 9 项全部通过并打包，0 失败/错误/跳过。该片只验证报告解析，尚未运行新的前端流水线或真实 npm 工具构建。

EX09b：23:34:19，`./mvnw -B -Dtest=ExecutionFrontendTest test` 缺流水线类 RED。23:36:25，`./mvnw -B -Dtest=ExecutionFrontendTest,ExecutionDiagnosticsTest,ExecutionToolchainTest package` 11 项通过。自审增加启动前 preflight 异常 canary，23:37:33 先复现原文被抛出的失败，补全入口错误白名单后，23:38:46 同一 11 项命令全部通过并打包，0 失败/错误/跳过。

流水线只使用既有固定命令目录，复验私有 home/工具及调用者提供的来源/策略检查；每阶段共享同一个 deadline/取消信号，journal 意图失败不得启动，落结果失败不得重复落账或开始下一步。输出先前存在即拒绝，绝不为重试删除它；单测前新建 0700 报告目录及 0600 空文件，读取前后核对 inode、权限、单链接、大小与预算。任何一步失败或缺匹配的停止证明都不能继续，不发布整个构建成功。原始进程输出仅计字节并排空；报告文件仅留在任务私有副本，数值摘要之外不写事件或返回上层。

流程测试在固定命令边界替换为受控 Java 子进程，验证顺序、真实停止证明、两路 canary 排空、失败/共享取消/期限、陈旧/空/跳过/超大/硬链接/软链接/替换报告及未知停止。这不是已真实安装 npm 依赖、运行 Vitest 或构建用户工程的证据；实际工具闭环仍是 EX11。EX09c 持久接线尚待完成，C-EX03 未勾选。

### EX09c · 持久意图和停止结算

2026-10-20 23:42:23，新集成测试缺 journal 实现类 RED；实现后第一次定向执行因沙箱禁止枚举子进程，在单元阶段报 `DESCENDANT_ENUMERATION_FAILED`（3 失败、1 错误），尚未进入 PG 集成阶段。没有放宽 `OwnedProcess` 校验。用户批准沙箱外执行同一命令后，2026-10-21 00:17:29 **9 单元 + 6 PG 集成通过**并打包，0 失败/错误/跳过：

```sh
env PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH \
  SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/atoms_demo_test?currentSchema=atoms_verify_20261019_pa32b&connectTimeout=3&socketTimeout=5' \
  ./mvnw -B -Dtest=ExecutionFrontendTest,ExecutionDiagnosticsTest -Dit.test=ExecutionFrontendJournalIT verify
```

随后增加完成事件写入失败、取消与迟到成功竞争、跨阶段复用执行 UUID 的反例，共 8 项集成。暂时取消 stop proof 的任务/执行/cwd 匹配，00:19:53 出现预期的 **1 项断言失败**，其余 7 项集成及 9 项单元通过；立即恢复。事件只存固定枚举、UUID、错误码、字节数和测试计数，原子更新 V023 配额，无新迁移。事务失败测试保留原 pending，不把成功启动或成功前端检查标为整个构建成功。

“结果丢失”测试先运行并持有真实子进程停止证明，再刻意不交给 journal，验证待核对及容量保留；夹具结束时只凭仍持有且匹配的证明结算它自己刚创建的记录，保留所有事件。这是测试限定的故障收尾，不是新增生产恢复入口，也未清理旧 `atoms`/`pa32` 未决记录。全部流水线 PG 测试仍使用受控子进程替身，不冒充真实 npm/Maven 应用构建。

00:22:00，恢复校验后的同一命令 **9 单元 + 8 集成全部通过**，0 失败/错误/跳过，BUILD SUCCESS，耗时 1:37；平台 JAR 已重新打包。覆盖持久意图先于真实子进程、并发 begin 仅一方获准、重复执行 UUID、陈旧步骤、错误/丢失停止证明、权限撤销后的自有停止结果、取消竞争、开始及完成事件故障原子回滚。正常前端结束仍为 `CHECKING` 并占有 worker，不伪造完整构建成功。

五轴自审：保持窄的内部流水线和 journal，未改旧 workbench/构建器、共享进程基元、已应用迁移或平台依赖。每阶段一次短事务，不持有 DB 锁等待工具；有界输出/JSON/事件与失败脱敏，原始输出无法通过 journal 参数写入。目录操作只新建任务内固定输出，不删历史证据；journal 不提供调度、恢复或公开入口，完整权限/来源/策略的 worker 接线与真实工具验收仍在 EX10–12。开始完整回归，未提前勾选 C-EX03。

### EX09 最终完整回归与中断后核验

2026-10-21 00:22 后，工作目录 `backend`，执行完整回归：

```sh
env PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH \
  SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/atoms_demo_test?currentSchema=atoms_verify_20261019_pa32b&connectTimeout=3&socketTimeout=5' \
  ./mvnw -B -Pproject-execution-contract \
  -Dexecution.test-config-directory=/Users/wubingheng/atoms-demo/.local/project-execution-acceptance.x5cvui verify
```

对话中断后原命令会话已不可读取，因此不声称取得该次命令的退出码或完整终端日志。按本次运行的新 XML 时间范围逐份汇总，排除历史残留的单测目录 IT 报告及未启用的 CLI 契约报告：

| 报告 | 本次文件数 | 用例数 | 失败 / 错误 / 跳过 | 本次报告写入时间（Asia/Shanghai） |
|---|---:|---:|---|---|
| Surefire | 81 | 356 | 0 / 0 / 0 | 00:22:29–00:24:06 |
| Failsafe | 91 | 309 | 0 / 0 / 0 | 00:25:43–00:51:31 |

`failsafe-summary.xml` 同样记录 completed=309、failures/errors/skipped/flakes 全部为 0、timeout=false，包含新增 8 项 journal 集成及 4 项专用数据库只读契约。01:48:10，单独执行 `./mvnw -B failsafe:verify` 校验现有结果，**BUILD SUCCESS，退出码 0，耗时 1.443 秒**；这不重新运行或重复计数测试。

00:22 定向构建已完成的平台后端 JAR 为 30,751,192 字节，包含 `ExecutionDiagnostics`、`ExecutionFrontend` 和 `ExecutionBuildJournal`，SHA-256 为 `68cc5754e279b46e87e1cefec2df44632b3b4838bedef00302783b24e5a850e2`。这是平台后端产物，不是新模式生成应用的封存 JAR。

收尾只读审计确认：`pa32b` 的 Agent 未决任务、待核对执行和活动源码租约均为 0；旧 `atoms`/`pa32` 两份未决审计仍为原状态，缺停止证明、活动租约及不变基线原样保留；测试角色仍无建 schema 权限。没有删除审计数据或强制释放旧租约。

DOC 的 28 个本地文档链接/锚点、30 项任务与 10 个检查点编号及 `git diff --check` 均通过；仅 EX01–09 勾选完成。三个新增主类在 JAR 中的字节摘要与当前编译结果一致。

EX09 及 C-EX03 完成，下一项 EX10 Java 检查与 JAR 封存。模块继续默认关闭；没有实际运行 npm/Maven 用户工程构建、调用模型或 SMTP、改私有 env/个人工具、推送或部署。真实种子构建仍须由 EX11 单独验收，网页继续旧链路；用户未跟踪 OAuth 草稿不纳入提交。

## 2026-10-21 · EX10 Java 检查与产物封存（实施中）

按增量实现拆成报告校验、资源装配、产物封存、worker 接线四片；每片最多五个主要文件，不把新增内部类当成已完成整条构建链路。新模块仍默认关闭，旧工作台不变。

EX10a 的信任边界是外部工具报告字节 → 数值摘要；资产是成功状态真实性和报告可能包含的凭据/路径。只读解析，不连库、不启动工具，不返回系统属性、用例名称或日志。源码由维护者信任，报告通过并非任意代码安全认证。

依据种子 POM 的 Boot 3.5.16 管理版本确认 Surefire 3.5.6，核对其[固定版本 reporter](https://github.com/apache/maven-surefire/blob/surefire-3.5.6/maven-surefire-common/src/main/java/org/apache/maven/plugin/surefire/report/StatelessXmlReporter.java)及 [Java 17 StAX](https://docs.oracle.com/en/java/javase/17/docs/api/java.xml/javax/xml/stream/XMLInputFactory.html)。使用 JDK 内置 provider、禁用 DTD 和外部实体且遇到实体/DTD 事件直接拒绝；不加载报告里的 schema URL。每份最多 1 MiB、512 份合计 16 MiB/10000 用例，逐条 testcase 与汇总相符，跳过、失败及 flaky/rerun 均拒绝。文件新鲜度和停止证明由后续执行器提供，解析器本身不证明它们。

- 01:56:16，`./mvnw -B -Dtest=ExecutionJavaReportTest test` 因缺实现类的 5 处引用 RED。
- 01:57:42，`./mvnw -B -Dtest=ExecutionJavaReportTest,ExecutionDiagnosticsTest package`，6 项通过并打包。
- 01:58:43，暂时删除实际 testcase 数与汇总比较，同一新测试正确出现 1 项断言失败；随后恢复检查。
- 01:59:42，恢复后上述 6 项 `package` 命令通过，0 失败/错误/跳过，BUILD SUCCESS，耗时 45.719 秒。

自审确认 XML 错误只返回固定错误码、无 cause/suppressed 原文，数量与体积均有界；没有增加依赖、扩大 Maven 执行策略或运行生成工程。EX10a 完成，EX10 整体及 C-EX04 未完成。

EX10b 的输入是工具写出的 dist，不将它当作可信路径：只在已持有任务目录中枚举，无链接跟随、无删除/覆盖；按照种子资源打包上限复制到源码外的私有资源目录，并检查非空 index.html。文件与目录身份/模式/修改时间和内容摘要固定，源/目标都复验；回调负责重新检查源码及授权，任务总预算贯穿扫描/复制。组或其他用户可写、链接、隐藏/密钥文件、条目/深度/字节越界均拒绝。来源中已有 static 目录明确拒绝，避免 Maven 资源覆盖歧义；不是由平台改写源文件来消除冲突。

平台固定 Maven argv 增加 `-Dfull-project.resources=<本任务目录>/resources`，不接受调用者提供任意资源路径，不运行源码 build.mjs 或改变批准 POM。实现依据 [Maven 资源目录](https://maven.apache.org/plugins/maven-resources-plugin/examples/resource-directory.html)、[Java 17 Files](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/file/Files.html)，仍是本机协作式一致性检查，不是对抗同 UID 恶意进程的沙箱。

- 02:01:55，`./mvnw -B -Dtest=ExecutionPackagingTest,ExecutionToolchainTest test` 因缺装配实现类的 8 处引用 RED。
- 02:03:58，`./mvnw -B -Dtest=ExecutionPackagingTest,ExecutionToolchainTest,ExecutionJavaReportTest package`，9 项通过并打包，0 失败/错误/跳过。
- 02:05:14，暂时去掉装配副本复验，`./mvnw -B -Dtest=ExecutionPackagingTest test` 的副本篡改用例出现预期的 1 项断言失败；随后立即恢复。

自审范围包括不改源码/个人配置、固定资源参数、过期及替换输出拒绝、有界文件 I/O、错误脱敏和失败证据保留。装配不会调用 Maven、连接数据库或自行宣称 SUCCEEDED，真实资源进入应用 JAR 的验收仍待 worker/EX11。恢复后开始完整后端回归。

### EX10a/b 完整回归及收尾

2026-10-21 02:28:46（Asia/Shanghai），工作目录 `backend`，完整命令：

```sh
env PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH \
  SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/atoms_demo_test?currentSchema=atoms_verify_20261019_pa32b&connectTimeout=3&socketTimeout=5' \
  ./mvnw -B -Pproject-execution-contract \
  -Dexecution.test-config-directory=/Users/wubingheng/atoms-demo/.local/project-execution-acceptance.x5cvui verify
```

**363 单元 + 309 集成全部通过，0 失败/错误/跳过**，BUILD SUCCESS，退出码 0，耗时 **22:53**。按本次 02:06 后的 83 份 Surefire 和 91 份 Failsafe XML 独立汇总一致，未混入旧报告；309 包含 4 项专用测试数据库的真实只读契约，不重复加算。覆盖原账号、源码/Agent（含并发、崩溃与恢复）、旧生成、预览、导出和版本恢复；没有启用真实 CLI 专用 profile 或真实模型，也未执行浏览器及新生成工程构建验收。

平台后端 JAR 为 **30,767,427 字节**，SHA-256 `5b841f4ba9d6afa13e82a5d83c02e42dc4572fd564e6fa997ea741465041811e`；其中 `ExecutionJavaReport`、`ExecutionPackaging` 和 `ExecutionToolchain` 的字节摘要与当前编译结果一致。这不是 EX10c/EX11 的用户应用封存 JAR。

收尾只读审计：`pa32b` 的 Agent 未决任务/待核对执行/活动源码租约均为 0，旧 `atoms`/`pa32` 两份未决审计及活动租约、缺停止证明、不变基线保留；测试角色仍无建 schema 权限。进程表未见 Java/claude.exe，本轮没有执行 kill 或清理旧证据。

DOC 的 28 个本地链接/锚点、30 项任务与 10 个检查点编号、EX10 子项状态及 `git diff --check` 均通过。

本轮按增量实现及测试驱动拆分并完成 EX10a/b；EX10c 封存、EX10d worker 接线、EX10 整体和 C-EX04 仍未完成。未改变日常开关、私有 env、工具安装、模型/SMTP 或旧网页；不合并、推送或部署。用户未跟踪 OAuth 草稿保留。

### EX10c · 产物与固定清单封存

本片 4 个主要文件：`ExecutionArtifact` 负责固定路径文件封存与复验，`ExecutionArtifactManifest` 负责有界证据形状，`ExecutionPaths` 为产物分配提供共享预算重载，`ExecutionArtifactTest` 验证真实临时文件。没有改旧 workspace/instances、源快照、状态表或日常配置；本片不发布数据库成功状态。

威胁边界是构建工具写出的文件和内部收集的证据 → 平台私有产物。只接收持有任务的 `backend/target/app.jar`，拒绝空文件、超过 256 MiB、非普通文件、链接、非当前 UID 和组/其他用户可写输入。复制和原件/副本复验使用有界缓冲、共享期限及中断检查；同长度/同 mtime 的改写仍以 SHA-256 检出，字节相同但 inode 替换也拒绝。产物目录不能多出未声明成员。清单只保存绑定 ID、摘要和数值，前端/Java 测试数必须有效、四工具齐全、npm/Maven 批准策略摘要一致；不保存自由文本、账号、原始日志或秘密。

`CREATE_NEW` 和 `force(true)` 依据 [Java 17 StandardOpenOption](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/file/StandardOpenOption.html#CREATE_NEW) 与 [FileChannel](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/channels/FileChannel.html#force(boolean))。文件写入不是目录与 DB 的原子提交；存在文件不代表成功，失败内容保留而不覆盖/删除。EX10d 仍须把实际授权/来源/策略复验、干净报告、成功退出和停止证明接入，EX11 仍须实际构建并检查 JAR 内的 Java 类与静态资源。本片文件夹具仅证明字节封存，不伪称已执行用户应用或验证可执行 JAR。

- 02:45:17，`./mvnw -B -Dtest=ExecutionArtifactTest test`，缺实现类的 4 处引用 RED。
- 02:47:19，实现后正常封存及重复封存测试通过，BUILD SUCCESS。
- 02:49:06，新增反例发现测试错误码预期不符：符号链接由 `ExecutionRootLease.noLinks` 返回 `EXECUTION_STORAGE_UNAVAILABLE`，不是 `EXECUTION_ARTIFACT_INVALID`。核对底层实现后，仅把符号链接/目标目录链接两例的预期精确改为既有存储错误码，没有放开生产拒绝逻辑。
- 02:50:17，`./mvnw -B -Dtest=ExecutionArtifactTest,ExecutionPathsTest,ExecutionPackagingTest test`，16 项通过，0 失败/错误/跳过，耗时 40.839 秒。含 8 类不安全输入、8 类封存后篡改、6 类无效证据，以及来源后改写、复制中期限耗尽、容量、事务和中断反例。
- 02:51:32，暂时去掉封存 JAR 的复验比较；`./mvnw -B '-Dtest=ExecutionArtifactTest#rejectsTamperingWithJarManifestPermissionsMembershipAndFileIdentity' test` 正确失败 1 项（篡改未被拒绝）。随后恢复，并补充清单全字段及两份摘要的独立断言。

五轴自审：固定职责，不引入第三方依赖；大小/身份/模式/摘要与共享预算覆盖复制和复验；失败只返回固定错误码且无原始 cause；无删除、无任意路径入口和 DB 长事务。证据类型不是授权或停止证明，恢复/发布/实际 JAR 验收不在此 helper 中暗自实现；同 UID 进程仍不是安全沙箱。已启动完整回归，未提前勾选 EX10c、EX10 或 C-EX04。

02:53:56，首次完整命令在单元阶段以 369 项、1 项断言失败结束，未进入集成测试。失败来自刚补的全字段断言：`valueToTree` 保留 Java `long` 的数值节点类型，而落盘 JSON 经 `readTree` 对小整数使用 `int` 节点，字段和值相同但节点不相等。测试改为将期望值也序列化再解析，比较同一 JSON 表示；仍逐字段完整比较，JAR/清单 SHA-256 独立断言保留，不修改生产逻辑或跳过测试。

### EX10c 完整回归与收尾

2026-10-21 **03:13:33（Asia/Shanghai）**，在 `backend` 使用上文相同的显式 `project-execution-contract` 完整 `verify` 命令，**369 单元 + 309 集成通过，0 失败/错误/跳过，BUILD SUCCESS，退出码 0，耗时 19:02**。独立汇总本次新报告：84 份 Surefire（02:54:47–02:55:58）和 91 份 Failsafe（02:57:30–03:13:32），未混入旧报告；309 已包含 4 项专用测试数据库只读契约，不重复加算。旧账号、源码/Agent、生成、发布、预览、导出和恢复后端回归通过；本轮无浏览器测试、真实模型或生成应用的真实构建验收。

平台后端 JAR 为 **30,782,287 字节**，SHA-256 `124cd09f72d0225bb779ebe64416ffb71dfb675e70654dbb2d2d04e0a818b2bf`；`ExecutionArtifact`、`ExecutionArtifactManifest`、`ExecutionPaths` 及嵌套类共 10 个 class 的包内字节与编译目录一致。这是平台包，不是 EX11 用户工程构建结果。

只读审计确认 `pa32b` Agent 未决任务/待核对执行/活动源码租约均为 0；旧 `atoms`/`pa32` 两份未决记录、活动租约、缺停止证明和不变基线均保留，测试角色仍无创建 schema 权限。只读进程检查未见 Java/claude.exe，没有执行 kill。文档 28 个本地链接/锚点、30 项任务/10 个检查点编号与 diff 格式检查通过。

EX10c 按增量实现、TDD 与安全审查完成并记录边界；EX10d、EX10 整体和 C-EX04 仍未完成。下一步接入 Java 阶段与持久 worker，再做 EX11 真实种子工程构建。默认开关、私有 env、个人工具安装及服务器不变；只做本地提交，不合并、推送、部署或调用模型/SMTP，用户 OAuth 草稿不纳入提交。

### EX10d1 · Java 阶段与本轮报告目录

本片 4 个主要文件：`ExecutionJava`、`ExecutionJavaReports` 及对应两份测试。将报告文件读取与执行编排分开；复用固定命令、工具身份、私有工具 HOME、已有进程适配器和报告解析器，不增加第三方依赖。EX10d2 接持久日志，EX10d3 接整体 worker 与封存成功，均未在本片宣称交付。

威胁边界：工具写出的报告和退出结果进入平台检查证据。平台新建并固定私有 target/报告目录，拒绝旧输出，不删除重试；只在构建/执行/backend cwd 均匹配的真实停止证明后读取。读取只接受有界普通单链接文件，拒绝未知成员、目录替换和可写权限越界，读取前后复核目录清单/文件身份。报告内容不进入事件或错误原因，只返回数值。零退出码、存在 JAR 或符合 XML 格式均不单独构成构建成功。

目录而非 XML inode 的新鲜性校验来自 [Surefire 3.5.6 实现](https://github.com/apache/maven-surefire/blob/surefire-3.5.6/maven-surefire-common/src/main/java/org/apache/maven/plugin/surefire/report/StatelessXmlReporter.java)：它删除并重新创建报告文件。私有目录新建、NOFOLLOW 读取和文件属性复核依据 [Java 17 Files](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/file/Files.html)；不声称属性检查是跨文件原子快照或能隔离同 UID 恶意进程。

- 2026-10-21 12:10:34，`./mvnw -B -Dtest=ExecutionJavaReportsTest test`，缺报告目录实现类 RED。
- 12:12:08，正常报告及私有目录测试 1 项通过，BUILD SUCCESS。
- 12:14:35，`./mvnw -B -Dtest=ExecutionJavaTest,ExecutionJavaReportsTest test`，缺 Java 阶段实现类 RED。
- 12:16:21，实现后同命令 11 项通过，0 失败/错误/跳过，耗时 01:08。包含真实受控 Java 子进程、固定 Maven 参数断言、意图/完成错误、错构建/执行/cwd 的真实停止证据、取消/期限/来源变化与不安全报告反例；不是实际 Maven 构建用户工程。

- 12:17:56，暂时移除 `Stopped.matches` 检查后，聚焦反例在沙箱内出现 `DESCENDANT_ENUMERATION_FAILED` 并假绿。这不能作为变异验证通过：进程观察权限失败掩盖了错误身份分支。
- 12:23:56，获准在沙箱外执行相同变异命令 `./mvnw -B '-Dtest=ExecutionJavaTest#unknownOrMismatchedStopNeverAllowsReadingReports' test`，正确失败 1 项（错误身份未抛异常）。恢复生产校验，并在反例中要求真实受控进程确实完成，避免用枚举权限拒绝替代身份不匹配验证。

当前正在进行恢复后的全后端回归，EX10d1 尚未勾选。测试中的 JAR 是明确标注的普通字节夹具，专用测试库凭据传递、真实 npm/Maven/JAR 验收仍属于后续 EX10d/EX11；没有新增公开入口、模型调用、日常启用或部署。

五轴自审：执行阶段与报告文件读取各自独立，生产类为 101/110 行；沿用既有进程与工具边界，没有新增通用 Shell、外部依赖、网络集成、数据库写入或配置选项。失败只返回固定错误码，保留停止证明供日志层结算，不把原始 XML、文件路径或异常 cause 返回上层。目录枚举、文件读取、解析均有条目/字节上限，并使用共享期限；旧输出和失败目录不清理。来源/资源检查由调用方传入，持久日志与整体成功提交仍需 EX10d2/d3 验证，内部回调不是可由客户端提供的授权。

12:49:28，恢复后的完整显式 `project-execution-contract verify` 耗时 24:42：380 单元全部通过，309 集成中 1 项失败、0 错误/跳过，BUILD FAILURE。失败是已有 `ProjectAgentCoordinatorIT.aRunningProjectRejectsAnotherEditUntilItsLeaseIsReleased`：第一任务取消后，第二任务在 PID 文件出现前以 `SOURCE_STORAGE_UNAVAILABLE` 结束（执行 831 ms）；不能将它仅归为 PID 等待时间不足。新 Java 类未接入该 Agent 路径，但底层失败原因尚未确定，因此不据此宣布无回归，也不放宽等待、跳过断言或勾选 EX10d1。其余源码/Agent、账号、生成、发布、预览、导出/恢复和四库只读契约均通过。接下来单独复现此场景并保留失败记录。

12:50:25，同一隔离 schema 下单独执行 `./mvnw -B '-Dit.test=ProjectAgentCoordinatorIT#aRunningProjectRejectsAnotherEditUntilItsLeaseIsReleased' failsafe:integration-test failsafe:verify`，1 项通过，0 失败/错误/跳过，耗时 12.664 秒。两轮受控任务均实际启动后按测试要求取消；未修改原用例或 Agent/源码实现。磁盘剩余约 8.6 GiB，进程只读检查未见额外遗留测试 Java 子进程；这些信息不能确证首次存储错误的根因。下一次完整重跑用于检查前置顺序/环境是否触发，不能把这次单例通过与旧 XML 拼接成“完整套件已通过”。

### EX10d1 完整复测与收尾

2026-10-21 **13:09:49（Asia/Shanghai）**，同代码、同原断言、同显式 `project-execution-contract verify` 完整命令，**380 单元 + 309 集成通过，0 失败/错误/跳过，BUILD SUCCESS，退出码 0，耗时 18:33**。原失败所在的协调器组本轮 10 项通过（89.07 秒），Agent 完整流程 3 项通过（150.8 秒）。首次存储失败仍未定位，单例及整轮复测通过不是修复证明；已列入任务清单待追查，未增加隐式重试、延长等待或修改源模块来掩盖。

独立汇总本次新报告：86 份 Surefire（12:51:20–12:53:03）与 91 份 Failsafe（12:54:16–13:09:48），计数与 Maven 一致。核对脚本初版误将目录内历史残留全部视为本轮报告并停止；确认后按本轮开始时间筛选、再强校验总数与零失败。保留且不计入 14 份 10 月 18 日的 Surefire IT 报告及 3 份 10 月 19 日的 CLI profile 报告；没有通过删除旧证据或拼接单例结果凑数。309 已包含 4 项真实专用数据库只读契约；未运行真实模型、CLI 专用 profile、浏览器或用户工程真实构建。

平台后端 JAR 为 **30,793,775 字节**，SHA-256 `5950c17723429ac08bf5eae9a8d882f54d54e385d0fdf372f5a1dcbd5fba675f`；`ExecutionJava`、`ExecutionJavaReports` 及嵌套类共 5 个 class 的包内字节与当前编译目录一致。这仍是平台包，不是已完成 EX11 的用户应用 JAR。

只读审计确认 `pa32b` Agent 未决/待核对执行/活动源码租约均为 0，测试角色无创建 schema 权限；旧 `atoms`/`pa32` 两份未决审计、活动租约、缺停止证明及不变基线保留。测试结束后的进程检查未见 Java/claude.exe，没有执行 kill 或清理旧证据。

本片按增量实现、TDD、官方来源核对及安全审查完成本地 Java 阶段，持久 Java 日志/整体 worker、测试库凭据传递和真实工程验收仍未完成，EX10/EX10d 整体与 C-EX04 保持未勾选。默认开关、私有 env、个人配置与服务器不变；不合并、推送、部署或调用模型/SMTP，OAuth 草稿不纳入提交。

## 源码读取异常 · 有界诊断

2026-10-21，追查 EX10d1 首轮的取消后再次编辑失败。原事件仅能确认第二个任务在子进程启动前报 `SOURCE_STORAGE_UNAVAILABLE`，未保留底层类别；根因仍未确定。新增快速退出夹具，让子进程先退出再启动 I/O 线程，13:20:14 聚焦测试通过，未重现“空输入写入已退出子进程”的猜测，因此不修改 `SourceGitProcess` 的执行逻辑，也不据此断言所有时序均无竞态。

本片回答三个诊断问题：哪份快照失败、读取的哪一阶段失败、属于哪种 I/O/Git 故障。`SourceQuery` 每次失败只记一条 `source_snapshot_failed`，包含读取事件 UUID、项目/源码版本 UUID、固定入口 `source_snapshot`、`ENVIRONMENT/REFERENCE/TREE/METADATA` 阶段、固定错误分类及单调耗时。读取 UUID 只关联这次源码读取，不冒充 Agent 任务 ID；需结合项目、版本及时间定位上游任务。未知 Git 码归为 `GIT_FAILURE`，不记录异常原文、堆栈、绝对路径、源码、凭据或所有者邮箱；对外错误和阻断写入逻辑不变，预算耗尽仍走原超时分支，不标记存储损坏。

验证：13:21:43 新诊断测试先因缺少分类实现编译失败；实现后 13:22:31，`SourceQueryDiagnosticsTest,ProjectSourceGitProcessTest` 共 12 项通过。13:23:06 临时允许未知 Git 错误原文的变异使分类反例失败，随后恢复白名单。真实 PG 的缺失源码根场景增加实际日志断言，同时保留原“根不重建、状态待核对、版本不变”断言。

排查入口：搜索 `source_snapshot_failed`，用项目/版本/时间关联 `agent_task_finished`；先看 `stage`，再看 `failureKind`。缺失文件、权限、被中断通道与 Git I/O/超时/停止不确定分别保留固定分类。该日志不能证明目录或进程安全，也不授予重试、解锁或删除证据的权限。后续只有拿到明确复现或新的故障类别才能声称修复原异常。

13:40:56，同 EX10d1 的 Node/schema/私有测试配置前缀及 `-Pproject-execution-contract verify`，**383 单元 + 309 集成通过，零失败/错误/跳过，17:36，退出码 0**。独立汇总本轮 87/91 份新报告；保留、不计入 14/3 份历史残留报告。原协调器组 10 项通过（74.43 秒），原异常未重现。平台 JAR 30,794,898 字节，SHA-256 `84df953fa1b000a692f7319f438c21f09b873a5e9c08bd8cc9bd3a41bb8a9117`。只读审计确认 `pa32b` 未决/待停止/活动源码租约均为 0，旧两份未决审计及测试角色权限不变。

本片仅完成诊断增强，不代表原偶发异常已修复；EX10d2 尚未完成。无 Git 执行逻辑、超时、重试、API、迁移、依赖或私有配置变更，无真实模型、邮件或部署。

## EX10d2 · Java 阶段持久日志

`ExecutionBuildJournal` 接入既有 `ExecutionJava.Journal`，仅在前端构建阶段已完成、权限/开关有效、未取消、后端私有目录有效时允许新的 `MAVEN_PACKAGE` 执行。一次性 UUID 不可跨阶段复用；项目行 → 构建行的短事务提交 `PACKAGING` 与执行意图后才允许启动。前端入口不能冒充 Java 阶段。

结算复核 build/execution/backend cwd 的真实停止证明、最新执行意图、输出字节边界及有效 Java 测试数量，持久化数字摘要但不写原始输出。Java 成功只清除本阶段 pending，仍为 `PACKAGING` 并持有 worker 容量；整体成功、产物封存和调度接线留给 EX10d3。取消优先于迟到的成功；缺停止证明保持待核对及占用；事件写入失败将状态和事件一并回滚，保留原 pending。停写后的清理不因权限撤回或项目归档被阻断。

新增 `ExecutionJavaJournalIT` 使用真实 PostgreSQL、受控短 Java 子进程和实际新报告目录；前端前置阶段是日志夹具，Maven 调用由固定测试进程替代，**不是 EX11 的真实 npm/Maven/JAR 验收**。首测于 13:43:55 因尚未实现 Java Journal 编译失败；实现后 13:45:54，首条真实 PG 测试通过。没有新增迁移或依赖，不改旧前端阶段契约，不提供新网页入口。

13:48:34，新增 8 项 Java 日志 + 原 8 项前端日志测试通过（1:20）。覆盖阶段顺序、UUID 重用、事务边界、缺/错停止证明、并发意图、授权撤回、归档、缺失 backend、提前/迟到取消、异常测试计数和输出字节、事件事务回滚及容量保留。13:49:52 临时移除停止证明身份校验，错误 build/execution/cwd 反例按预期失败；恢复后启动完整回归，未留下放宽校验的变异。

14:07:28（Asia/Shanghai），同上述 Node/schema/私有测试配置前缀，`./mvnw -B -Pproject-execution-contract -Dexecution.test-config-directory=/Users/wubingheng/atoms-demo/.local/project-execution-acceptance.x5cvui verify`：**383 单元 + 317 集成通过，零失败/错误/跳过，BUILD SUCCESS，17:23，退出码 0**。独立核对本轮 87 份 Surefire、92 份 Failsafe 新报告；保留、不计入 16/3 份历史报告（含先前定向 IT/变异报告）。新增 Java 日志组本轮 8 项通过（38.42 秒），旧协调器组 10 项通过（95.93 秒），存储偶发异常未复现。

平台 JAR **30,798,466 字节**，SHA-256 `713f03e4f94b4ced25a2e8f0f39c5f082a307e1ed3df35c5d190b5e57db7e741`；`ExecutionBuildJournal` 与 3 个嵌套记录的包内 class 字节和本轮编译输出一致。该包仍是平台包，不是用户工程真实构建结果。只读审计确认 `pa32b` Agent 未决/待停止/活动源码租约为 0、测试角色无创建 schema 权限；旧两份未决审计及基线不变。过滤后的进程清单未见 Java/claude.exe，未执行 kill 或删除历史证据。

本片按增量实现、TDD、事件观测和安全/质量审查完成；只勾选 EX10d2，EX10/EX10d/C-EX04 和 EX11 均未完成。下一步接整体 worker、专用测试库私有配置与封存成功事务。源码诊断片是独立提交 `9eab1d3`，不把它当成偶发异常修复。默认开关、私有 env、个人工具、服务器和 OAuth 草稿均未改；未推送、合并、部署、调用真实模型或邮件。

## EX10d3a · 专用测试库传递到 Java 阶段

核对 EX10d3 后，将缺少的测试库传递、构建领取/封存结算和整体 worker 拆为 a/b/c，沿用每片最多 5 个主要文件的既定约束，不扩大产品或环境权限。本片只改 4 个执行模块类，新增 `ExecutionDatabaseLaunchIT`；无数据库迁移、依赖升级或公开接口。

注册表在实际只读校验后签发不可自行构造的一次性句柄，绑定项目/构建/执行/backend cwd。实际子进程启动前再次校验原私有文件及实际 DB/角色身份与用途/权限；密码只进入本次 builder 的四项 `APP_*` 环境，原 Invocation 无凭据且保持不变，未写入源码、配置副本、argv 或 JVM 系统属性。前端仍走原无测试库入口。生产 `ExecutionJava` 构造器要求注册表和项目；测试 Runner 缝隙不作为生产无库兜底。

测试使用真实专用 PostgreSQL 的只读查询、实际父子 JVM 和 Spring `StandardEnvironment` 占位符解析，不执行 npm/Maven 用户工程构建，不执行应用迁移或数据写入。进程可读取传给自己的凭据，其他同 UID 代码也不受本机制隔离；最后一次校验不锁住管理员后续改库行为。若 Java 意图提交后发放授权失败、尚未进入进程拥有者，则按原保守约定没有停止证明、保留待核对状态；worker 还需在阶段前检查绑定，不能把本片当作完整结算或调度实现。

14:52:30 首次 RED 同时暴露新增测试中局部变量 `java` 遮蔽包名的编译笔误；修正后 14:53:51 仅因缺少 `authorizeLaunch` 接口失败。实现后 14:56:02 首 2 项真实 PG/父子进程测试通过（35.595 秒含编译）；14:58:57 新增 7 项及原数据库/执行器/Java 阶段共 **31 项通过**，零失败/错误/跳过（1:06）。覆盖 A/B 凭据分离、重放/错 build/execution/cwd、配置内容/文件/权限/硬链接变化、伪装业务库、错误绑定/密码、取消/超时、事务边界和生产入口拒绝未登记项目。

15:00:30 临时移除进程启动前 `database.verify`，配置漂移反例按预期失败（“Expecting code to raise a throwable”，30.541 秒含编译）；随后恢复校验。补同一授权并发争用测试，再启动完整后端回归；完整结果在结束后补录，不将历史报告或默认排除测试算作通过。

### EX10d3a 完整回归失败与保留现场

2026-10-21 本次完整命令仍为上文 Node/schema/私有配置前缀下的 `project-execution-contract verify`。按 15:01 后的新报告独立汇总：87 份 Surefire 共 **383 项通过**，93 份 Failsafe 共 **325 项中 319 通过、6 失败，0 错误/跳过**。最后集成报告于 15:27:12 写入，Failsafe summary 为 `completed=325, failures=6, result=255, timeout=false`。恢复工作时原命令会话已不可读取，因此不补造 Maven 控制台最终退出码或精确总耗时。18/3 份旧报告不计入本轮；新增 `ExecutionDatabaseLaunchIT` 8 项通过，不能以局部通过替代完整通过。

6 项失败均在旧 `ProjectAgentCoordinatorIT`：默认四槽位、单槽位、三槽位、关闭、单调度者和同项目串行编辑。逐个 testcase 对照日志确认，15:19:42 的停止属于主动释放锁的 `lostDispatchOwnershipStopsTheCurrentProcess`，该项通过；它不是随后故障的起点。四槽位用例中，任务 `2f263256-a499-4a61-ab1b-ae53849434e1` / 执行 `9c4ee2c1-d012-4907-95a6-3bff361dd015` 于 15:20:16 记录 `DESCENDANT_ENUMERATION_FAILED`，随后调度器保留未知执行与协调租约；后续用例拿不到协调权。这只定位到了观察失败及连锁阻断，原日志没有底层类别，尚不能归因于权限、JDK、线程中断或具体竞态，也不是此前源码读取异常的修复证据。

只读 PG 审计确认 `atoms_verify_20261019_pa32b` 中该任务为 `NEEDS_RECONCILIATION / AGENT_PROCESS_UNSETTLED`，源码租约仍 ACTIVE、`execution_pending=true`、停止证明缺失、源码基线未改变；其余五个未启动任务已由原测试收尾为 FAILED 且释放各自租约。审计时该数据库没有对应的活动 advisory lock，Execution 模块未决/待停止记录均为 0；进程清单未见遗留 Java/claude.exe。连接或进程后来消失不能补发原拥有者的停止证明，因此不清记录、不伪造证明、不重放此任务。

完整失败报告已复制至忽略的私有目录 `.local/ex10d3a-failed-regression.KD4BRu`（目录 0700），后续定向报告不覆盖这份证据。既有 `atoms`/`pa32` 审计也不修改。该 schema 不能再作为干净的协调器回归环境；新的隔离 schema 需额外环境授权，尚未创建。EX10d3a、EX10 整体及 EX11 保持未完成，不继续叠加 worker 功能，不提交为验收完成或推送/部署。

### 观察失败诊断小片（未声称修复原故障）

依据调试与可观测性流程，先补足两个问题：异常发生在进程枚举还是子进程观察？首次异常属于哪个固定类别、当时线程是否已中断？仅修改 `OwnedProcess` 并新增 `OwnedProcessDiagnosticsTest`；不改数据库、任务恢复、停止证明发行、重试或超时规则。既有单条 `owned_process_unsettled` 增加固定 `observationStage`、固定 `failureKind` 及布尔 `observationInterrupted`，保留第一次原因，不让收尾时的另一个错误覆盖。非观察错误显示 NONE，不从日志猜测缺失的历史类别。异常消息、动态类名、堆栈、ProcessHandle.Info、argv、路径和凭据均不输出。

21:12:26，8 项新诊断测试因日志缺字段全部 RED（26.353 秒，退出码 1）。实现后 21:13:47，`OwnedProcessDiagnosticsTest,OwnedProcessTest,AgentProcessInterruptionTest,AgentProcessTest,ExecutionProcessTest,ExecutionJavaTest` 共 **38 项通过**，零失败/错误/跳过（48.787 秒，退出码 0）。覆盖固定类别、未知类名/消息 canary、首次原因保留、子进程身份读取及中断标志恢复；均保留未知执行无停止证明的断言。测试使用受控本地 Java 子进程，无平台 PG 写入、模型或邮件调用。后续验证与阻断状态继续记录在此，不把故障注入结果当作原四并发故障已复现或已修复。

21:15:03，临时让收尾异常覆盖首次诊断，6 项分类反例中 5 项按预期失败（33.551 秒，退出码 1）；未知类别本来就是 RUNTIME_FAILURE，保持通过。随后恢复首次原因条件。为隔离数据库状态影响，在既有 `OwnedProcessTest` 补充四个真实子进程并行观察、取消及绑定停止证明的最小场景；本诊断小片共 3 个主要文件，不计入 EX10d3a 的 5 个文件。没有放宽进程观察失败后的拒绝策略。

只读检查确认拟申请的 `atoms_verify_20261021_ex10` 不存在，`atoms_test` 没有数据库 CREATE 权限；尚未创建或授权。后续如获准，只需管理员一次创建该测试 schema 并交给现有测试角色，不需扩大角色对数据库的创建权限。旧未决记录仍原样保留。

21:18:58，恢复生产条件后执行以下**定向验证，不是完整集成回归**：

```sh
env PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH \
  ./mvnw -B -Pproject-execution-contract \
  -Dexecution.test-config-directory=/Users/wubingheng/atoms-demo/.local/project-execution-acceptance.x5cvui \
  -Dit.test=ExecutionDatabaseLaunchIT verify
```

**392 单元 + 8 定向集成通过，0 失败/错误/跳过，BUILD SUCCESS，2:24，退出码 0**。按 21:16 开始后的 mtime 独立核对 88/1 份新报告；18/95 份旧报告保留但不加算。四并发最小场景通过，没有复现完整协调器用例中的观察错误；不能据此归因或标修复。测试库传递 8 项再次通过，未运行平台 PG 集成、真实用户工程 Maven/npm 或模型。平台 JAR 30,804,478 字节，SHA-256 `a1fac16380cf2f158c2e1cd587d0c143031a5def151ec34b1756fd347650edef`，仅证明平台打包成功。

收尾只读进程清单未见 Java/claude.exe；没有终止外部进程或补发停止证明。文档 19 个本地链接/锚点、30 项任务/10 个检查点及 diff 格式检查通过。诊断改动按正确性、结构、安全与性能自审：新增字段均为枚举/布尔，每次失败仍仅一条事件；首次原因不被覆盖，原未知执行不发停止证明；不增加异常原文、凭据、数据库访问、依赖或重试。已另记 Agent/构建共用 advisory lock 编号的问题供接线前处理，它不是本次故障的已证实根因。

**完整验收仍阻断**：未删除旧审计、未创建新的 schema，EX10d3a 和整体执行链路不勾选、不作为已完成提交。以上本地工作留在当前分支待续，无合并、推送、服务器部署或私有 env 改动，用户 OAuth 草稿不纳入此次变更。

### 新隔离 schema 授权与复测

2026-10-21 用户回复“允许”，明确批准 `atoms_demo_test.atoms_verify_20261021_ex10`，供现有 `atoms_test` 角色使用。创建前再次检查本机数据库/管理员身份、名称不存在和测试角色无数据库 CREATE 权限；仅新建准确 schema、设置 owner 并撤销 PUBLIC 权限。事务内对 `atoms`、`pa32`、`pa32b` 三份指定未决任务及租约逐项比较行摘要，前后相同后才提交。没有删除、解锁、修改旧任务，也没有提升测试角色的数据库 CREATE 权限。现有业务库、四个应用验收库、私有 env 与远程服务器未改。

同一当前编译产物，先以新 schema 的 `SPRING_DATASOURCE_URL` 执行 `./mvnw -B -Dit.test=ProjectAgentCoordinatorIT failsafe:integration-test failsafe:verify`。这是聚焦原失败组，保留原等待时限、断言及默认四槽位，未跳过用例或添加隐式重试；不调用模型。Flyway 已在新 schema 成功应用现有 24 份迁移（V000–V023）。结果在命令完成后记录，新环境不是故障修复证明。

21:25:30，聚焦调度组 **10 项通过，0 失败/错误/跳过，BUILD SUCCESS，1:54，退出码 0**。原四并发观察异常未复现。只读核对新 schema 的 Agent 未决/执行 pending/活动编辑租约及构建未决均为 0，测试角色仍无数据库 CREATE 权限，`pa32b` 的旧未知执行和不变基线保留。报告单独保存在忽略的 0700 目录 `.local/ex10-new-schema-focused.TG3IhN`。接下来执行同新 schema 前缀的完整显式 `project-execution-contract verify`，不把局部通过与旧报告拼接为完整通过。

### 新 schema 完整回归结果与源码读取异常

2026-10-21 21:53:32（Asia/Shanghai），完整命令结束，**392 单元通过；325 集成中 324 通过、0 失败、1 错误、0 跳过，BUILD FAILURE，17:31，退出码 1**：

```sh
env PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH \
  SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/atoms_demo_test?currentSchema=atoms_verify_20261021_ex10&connectTimeout=3&socketTimeout=5' \
  ./mvnw -B -Pproject-execution-contract \
  -Dexecution.test-config-directory=/Users/wubingheng/atoms-demo/.local/project-execution-acceptance.x5cvui verify
```

按本次运行开始后的报告 mtime 独立汇总 88 份 Surefire / 93 份 Failsafe XML；18/3 份旧报告不加算。`ExecutionDatabaseLaunchIT` 8 项通过，`ProjectAgentCoordinatorIT` 10 项通过（58.59 秒），原 Agent 四并发异常未复现。整套报告在后续复测前复制至忽略目录 `.local/ex10-new-schema-full-failed.od7gzt`（0700）；与首次 `pa32b` 失败报告分别保留。

唯一错误是 `ProjectSourceQueryIT.readsImmutableHistoryWithKeysetPaginationAndRechecksCurrentOwner` 第 47 行，在切换所有者后读取先前版本时发生。21:44:38.720 的固定诊断定位为 `TREE / GIT_PROCESS_UNSETTLED`，读取事件 `1b8daec9-0616-475b-bb87-d963398fe5e4`、项目 `e92b1db9-a442-456e-b99f-f747f8099078`、读取版本 `8d0e77b7-c33b-4109-8c2c-d3855f709c17`，471 ms。代码追踪到独立的 `SourceGitProcess.stop`，并非本轮增强的 `OwnedProcess`；该旧 Git 路径仍把枚举、终止、等待及中断的不确定性合为同一错误，现有证据不能断言具体分支，更不能把它与旧 Agent 故障当作已证实的同一根因。

只读审计：新 schema 的未决 Agent、与 Agent 关联的 pending/活动源码租约及未决构建均为 0；测试角色仍无数据库 CREATE 权限。不要将源码故障注入夹具留下的全表租约数量误称为 Agent 遗留。本次读取失败项目保持 `NEEDS_RECONCILIATION`，当前版本 `3414ba18-be70-4cbb-b0f5-62383d4b4892`，没有 pending 源码操作；未手工恢复 READY、删除项目或修改审计。`pa32b` 原未知执行、租约及未改基线保持原状，旧三份证据均保留。测试结束后的过滤进程清单没有 Java/claude.exe；这不是对历史未知执行补发停止证明。

本机 Oracle 17.0.5 的两个独立探针只验证进程枚举：沙箱内 2000 次均因原生调用异常失败，不能作为沙箱外测试根因；获准在沙箱外枚举 2000 次无异常。随后另获准运行有界进程变化探针，最多 20 秒、20000 次枚举、500 个自有 `/usr/bin/true` 子进程均退出，0 枚举错误；不访问 DB、不终止其他进程、不输出进程信息或原始异常文本。这些未复现结果不证明环境没有竞态。核对[OpenJDK 17 macOS 原生实现](https://github.com/openjdk/jdk17u/blob/master/src/java.base/macosx/native/libjava/ProcessHandleImpl_macosx.c)发现其当前实现包含 sysctl 的 ENOMEM 处理；这仅是后续排查线索，未据此认定当前异常原因，也未更换用户指定的 JDK 或修改系统配置。

保留完整失败报告后，同一 Node/schema 前缀运行 `./mvnw -B -Dit.test=ProjectSourceQueryIT failsafe:integration-test failsafe:verify`，不重新编译、不修改原断言/超时、不自动重试。**22:02:06，原 3 项聚焦测试通过，0 失败/错误/跳过，13.492 秒，退出码 0**。该结果单列，不覆盖完整失败结论。

本次按调试技能保留现场并缩小问题范围，按文档与质量技能保持验收门槛：EX10d3a、EX10、C-EX04、EX11 均未完成，未继续新增 worker、提交为通过版本、合并、推送或部署。下一步先补旧 Git 收尾的有界分类并定位偶发原因；不靠新建 schema、清除证据、放宽停止检查或隐式重试换取绿灯。默认开关、用户 OAuth 草稿、私有 env、真实模型/邮件与服务器均未改。

本轮五份维护文档的 31 个本地链接/锚点、30 项连续任务/10 个检查点及 `git diff --check` 通过；任务 diff 与两个新增测试文件未包含已配置的私有凭据值。以上仅为文档/秘密检查，不抵消完整回归的 1 项错误；改动保留在本地工作区，未提交为已验收切片。

### Git 收尾诊断补齐

用户继续后，先处理旧 Git 路径的诊断缺口；本片为 `SourceGitProcess`、`SourceQuery` 和两份定向测试，共 4 个主要文件。保持原 `GIT_PROCESS_UNSETTLED`、停止判定、父/子进程等待时限和中断恢复，不增加重试或伪造停止证明。内部失败携带固定枚举/布尔：枚举、子进程终止、父进程终止、父进程等待或子进程等待，以及权限/不支持/状态/原生枚举/等待失败/超时/中断等类别，第一次不确定原因不被后续收尾覆盖。

既有单条 `source_snapshot_failed` 增加 `gitStopStage/gitStopKind/gitStopInterrupted`，非收尾错误为 NONE/NONE/false。对原生枚举只识别固定 JDK native 栈帧并转换成枚举，不保存/输出原始栈、动态类名、异常消息、路径、源码或凭据；仍需真实故障事件才能判定历史失败是否来自该分支。源码读取公共错误及存储待核对行为保持不变。

2026-10-21 22:25:44，收尾测试因缺 `stopDetail` 接口编译失败；22:27:30，实现后原 Git 进程 10 项 + 新诊断 8 项通过（51.864 秒，退出码 0）。22:31:21，日志传播测试 3 项中 2 项因仅有原 8 字段而非预期 11 字段失败（26.118 秒，退出码 1）；接线后 22:32:18 三组共 **21 项通过，零失败/错误/跳过**（28.960 秒，退出码 0）。22:39:44 临时取消首因保留的变异被反例捕获：预期 ENUMERATE，实际 PARENT_WAIT（26.428 秒，退出码 1）；随后恢复原条件并在同一获准 schema 启动完整 `project-execution-contract verify`。完整结果另行记录，不把故障注入当作原偶发错误复现或根因修复。

**22:57:56，完整回归 401 单元 + 325 集成通过，零失败/错误/跳过，BUILD SUCCESS，17:56，退出码 0**。命令同上新 schema 的显式契约命令。按开始时间独立汇总本轮 89/93 份新报告，18/3 份历史报告不计入。测试库传递 8 项、源码读取 3 项、Agent 协调器 10 项（75.78 秒）均通过。报告保存于忽略的 0700 目录 `.local/ex10-git-full-passed.kqSVOu`，不覆盖既有失败证据。

本轮平台 JAR 为 30,809,614 字节，SHA-256 `794bd7712eb380b3df4d2dbeecae4aa98403a2c7d9cdfef0415d9225a8612224`，仍不是用户工程构建结果。结束后过滤进程清单没有 Java/Git；没有 kill 或删除证据。本轮凭据值扫描、31 个本地文档链接及 30 项任务/10 个检查点、diff 格式检查通过。

按调试流程对无法按需复现的风险保留条件和固定分类监测，并在完整验证通过后恢复开发：只勾选 EX10d3a；EX10d3b1 原子领取、b2 封存结算、c 整体 worker 以及 EX11 仍待实施。不把本次通过写成旧故障修复，不升级 JDK、不放宽停止条件、不增加重试；旧未决审计仍只读保留，默认执行器仍关闭。

结束后的只读审计确认新 schema 的未决 Agent、关联 pending/活动源码租约及未决构建均为 0；测试角色仍没有数据库 CREATE 权限。`pa32b` 原未知执行仍为 ACTIVE/pending、缺停止证明、基线不变；上一轮源码读取失败项目仍为 NEEDS_RECONCILIATION，当前版本不变且无 pending 操作。未对这些记录作任何修复性写入。

## EX10d3b1 · 原子领取与容量锁分离

遵循原每片不超过 5 个主要文件的约束，把 EX10d3b 拆为 b1 领取、b2 封存结算。本片 3 个主要文件：`ExecutionBuildClaim`、`ExecutionBuildRepository`、`ExecutionBuildClaimIT`；无迁移、依赖、新公开入口或进程启动。内部领取按项目行 → 构建行 → 容量锁，短事务检查授权、原 owner、状态/取消、当前及原任务容量和最终排队期限，仅提交 PREPARING 与事件，不声称应用构建成功。

原准入容量锁与 Agent 协调锁同为 `718395202`，提前修复为独立的 `718395203` 并供领取复用；旧 Agent 锁不变，EX12 仍负责完整协调器接线共存。容量统计包含未知执行和缩容前的高槽位，不凭槽 1 空闲就认为可以运行。队列期限按 PG 实时时间在 UPDATE 时验证；取消/超时/容量拒绝不改变任务，不私自补终态。默认关闭，不改私有 env、工具版本、服务器或旧审计。

23:06:01，首测因缺领取类和容量锁接口编译失败（23.730 秒，退出码 1）。实现后 **23:08:10，10 项真实 PG 领取测试 + 8 项 Git 诊断单测通过**，零失败/错误/跳过，BUILD SUCCESS，1:19，退出码 0。覆盖双连接抢最后槽位、同任务重复领取、权限/归档/转让、原容量绑定、缩容及待核对容量保留、取消、事务边界、等待期间超时、故障回滚和锁编号共存。测试仅对本例从未执行工具的记录做显式终态收尾，记录/事件保留。

23:14:48，临时把锁改回旧 `718395202` 的变异按预期失败：共存测试在准入时收到 PostgreSQL `55P03` 锁等待超时（单项错误，55.873 秒含编译，退出码 1），自身测试连接回滚后释放自身事务锁。随后恢复 `718395203`，运行全部单元测试及领取/原准入/约束/源码读取/Agent 调度相关集成回归。此变异证明了锁编号冲突的修复，**不是先前进程枚举或 Git 收尾偶发故障的根因证据**。

### 相关回归失败与原生枚举定位

2026-10-21 23:22:43（Asia/Shanghai），在上述已获准 Node/schema 前缀下执行：

```sh
./mvnw -B \
  -Dit.test=ExecutionBuildClaimIT,ExecutionAdmissionIT,ExecutionBuildSchemaIT,ProjectAgentCoordinatorIT,ProjectSourceQueryIT verify
```

**401 单元通过；33 定向集成中 32 通过、1 失败、0 错误/跳过，BUILD FAILURE，4:47，退出码 1**。按本次开始时间独立汇总 89 份 Surefire / 5 份 Failsafe 新报告，18/92 份旧报告不计入。领取 10 项、原准入 8 项、数据库约束 2 项、源码查询 3 项全部通过；协调器 10 项中 1 项失败。报告另存忽略的 0700 目录 `.local/ex10-claim-native-failed.zkdnSe`，未覆盖 22:57 完整通过或其他失败证据；不将历史全绿当作最新代码已全绿。

失败为 `ProjectAgentCoordinatorIT.unexpectedAdapterFailureCannotStrandAQueuedTaskOrSilentlyRetryIt` 第 199 行：适配器调用预期 1、实际 0。23:21:50.782 的真实生产路径事件 `source_snapshot_failed` 为 `TREE / GIT_PROCESS_UNSETTLED`，新增字段首次将该故障定位为 `gitStopStage=ENUMERATE`、`gitStopKind=NATIVE_ENUMERATION`、`gitStopInterrupted=false`，283 ms。请求 `3fe3ef87-c50b-400b-b999-0216f58b2486`、项目 `b7feb5fe-2625-4b35-b323-bdb6dbfef7ed`、源码版 `ac80827f-6f4c-469a-9ac6-08295e18878b`。固定分类只在前 16 个栈帧识别到 `java.lang.ProcessHandleImpl.getProcessPids0` native 调用时成立；本例不是注入枚举异常的诊断测试。源码准备提前失败，因此没有进入该测试预期的适配器；不改其断言、等待上限或自动重试。

只读审计确认任务 `777da2c2-cf09-49c0-8e78-4453c4e4c1ce` 为 `FAILED / SOURCE_STORAGE_UNAVAILABLE`，未分配执行 ID；自身源码租约已 RELEASED、pending=false，源码基线不变，项目保留 `NEEDS_RECONCILIATION`。新 schema 的 Agent 未决、关联 pending/活动编辑租约及构建未决均为 0；这不表示失败源码项目已恢复。`pa32b` 原未知执行仍 ACTIVE/pending、停止证明缺失，先前源码失败项目也保留待核对状态；测试角色仍无数据库 CREATE 权限。结束后获准的只读进程清单未见 Java/Git/claude.exe，没有 kill、删除或强制恢复记录。

当前机器仍用 Oracle 17.0.5。核对官方 [Liberica 17.0.14+10 PSU 发布记录](https://download.bell-sw.com/documentation/liberica-jdk/17.0.14%2B10-psu/release-notes-17.0.14%2B10-psu-liberica-jdk.pdf)第 26 页，列出 JDK-8339487：针对 `ProcessHandleImpl` 的 `sysctl` 调用在 ENOMEM 时重试并改善异常消息。当前 [OpenJDK 17 macOS 原生实现](https://github.com/openjdk/jdk17u/blob/master/src/java.base/macosx/native/libjava/ProcessHandleImpl_macosx.c)也可核对相关逻辑。这是有依据的排查方向，**现有事件未捕获 errno，尚不能认定为 ENOMEM 或保证更换补丁版即可解决**；更不能倒推所有历史观察故障为同一根因。

按调试流程暂停新增功能并保存证据。下一步拟申请在项目忽略目录单独安装一份明确包含该修复的较新 JDK 17，使用命令级配置做同条件对照；不替换 Oracle 17.0.5、不改全局默认、日常私有 env 或服务器。工具安装/切换尚未获准，当前未执行。EX10d3b1、EX10、C-EX04、EX11 保持未完成，领取代码保留未提交工作区；前三个已验证小片分别本地提交为 `e9d1e2a`、`5d4958b`、`fa6d7db`，无合并、推送或部署。

### 获准的项目隔离 JDK 对照

用户随后回复“允许”，批准安装包含相关修复的独立 JDK 17 用于项目对照测试，原 Oracle 17.0.5、全局默认和服务器保持不变。2026-10-21 23:38，在本机 macOS 15.8.1 / x86_64 下载并解包 Liberica **17.0.14+10 PSU** 到忽略的私有目录 `.local/jdk17-comparison.LlvDQi/jdk-17.0.14.jdk`。按 [BellSoft 官方发现 API](https://bell-sw.com/pages/api/)核对唯一匹配的版本、平台、架构、包类型和校验值，使用官方 GitHub release 归档，不运行系统安装器、不写 SDKMAN 或 shell 配置。独立解包方式见[该版安装说明](https://docs.bell-sw.com/liberica-jdk/17.0.14b10/general/install-guide/)。

安装包 `bellsoft-jdk17.0.14+10-macos-amd64.tar.gz` 为 **202,700,165 字节**；与官方元数据一致的 SHA-1 为 `801e44e9a8735cd79521b4940cd2caebf98ddaa0`，另记本地 SHA-256 `4cc0f3b7599abca5927ae86a0024b945a19a3c90f700efa56af28661f3bee464`。选择这个明确列有 JDK-8339487 的版本是为建立诊断基线；官方元数据标记该旧补丁 EOL，不将其推荐为长期生产升级版本。解包前检查全部 549 个条目的路径，单一根目录，无绝对路径或 `..` 越界。

运行前确认全局 `java -version` 仍为 Oracle `17.0.5+9-LTS-191`，SDKMAN `current` 仍指向 `17.0.5-oracle`，没有其他 Java/Git/claude.exe 进程。命令级 `JAVA_HOME` 指定隔离目录后，Maven `-version` 显示 Java 17.0.14 / BellSoft / 该目录，Maven 仍是 3.9.11。源码、编译产物、测试断言/超时、Node 路径与已批准测试 schema 不变；没有清理数据或新建 schema。

在 `backend` 目录运行与上一轮相同的 401 单元及 33 相关集成命令，只增加命令级 `JAVA_HOME`。结果另行记录，不以安装成功表示故障已修复。

```sh
env JAVA_HOME=/Users/wubingheng/atoms-demo/.local/jdk17-comparison.LlvDQi/jdk-17.0.14.jdk \
  PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH \
  SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/atoms_demo_test?currentSchema=atoms_verify_20261021_ex10&connectTimeout=3&socketTimeout=5' \
  ./mvnw -B \
  -Dit.test=ExecutionBuildClaimIT,ExecutionAdmissionIT,ExecutionBuildSchemaIT,ProjectAgentCoordinatorIT,ProjectSourceQueryIT verify
```

**23:42:49，对照相关回归 401 单元 + 33 集成全部通过，0 失败/错误/跳过，BUILD SUCCESS，3:57，退出码 0**。按本次开始时间独立汇总 89/5 份新报告，18/92 份旧报告不加算。`ProjectAgentCoordinatorIT` 10 项通过（70.82 秒）；实际 Surefire 与 Failsafe XML 的 `java.version/java.vendor/java.home` 均确认隔离的 BellSoft 17.0.14，非只检查命令行显示。原生枚举错误本轮未复现，不据此宣称偶发根因已完全证实或原 Oracle 17.0.5 已修复。报告复制到忽略的 0700 目录 `.local/ex10-jdk17-focused-passed.lB9LSh`。

随后在同一命令级 JDK/Node/schema 前缀下运行完整回归：

```sh
./mvnw -B -Pproject-execution-contract \
  -Dexecution.test-config-directory=/Users/wubingheng/atoms-demo/.local/project-execution-acceptance.x5cvui verify
```

完整结果待结束后单独记录；尚不勾选 EX10d3b1，不将相关集成通过替代全量兼容验收。此次 profile 仍不启用真实 Agent CLI、模型或邮件调用，也不执行尚未实施的用户工程 npm/Maven 构建。

**23:58:57，完整对照回归 401 单元 + 335 集成全部通过，0 失败/错误/跳过，BUILD SUCCESS，15:31，退出码 0**。按完整命令开始时间独立核对 89/94 份新 XML，排除 18/3 份旧报告；全部本轮报告的 Java 版本为 17.0.14。源码查询 3 项、原子领取 10 项、测试库传递 8 项、Agent 协调器 10 项（58.98 秒）及多轮编辑流程均通过。完整报告复制至忽略的 0700 目录 `.local/ex10-jdk17-full-passed.gE3KrZ`，相关回归及旧失败档案保留。

平台 JAR 为 **30,813,197 字节**，SHA-256 `5109d051f1f41911177d1f9e38e81f252882280205a8becea22a81bbccfeeb38`；这仅是平台打包产物，不能替代 EX11 的真实用户工程构建证据。回归后全局 Java 仍为 Oracle `17.0.5+9-LTS-191`，SDKMAN `current` 仍指向 `17.0.5-oracle`。只读进程清单没有 Java/Git/claude.exe，无进程终止操作。

回归后的只读 PG 审计确认新 schema 的 Agent 未决、关联 pending/活动编辑租约及构建未决均为 0，测试角色仍没有数据库 CREATE 权限。`pa32b` 原未知执行仍 ACTIVE/pending、缺停止证明且基线不变；两个旧源码失败项目保留 NEEDS_RECONCILIATION，任务 `777da2c2-cf09-49c0-8e78-4453c4e4c1ce` 仍 FAILED、无 execution ID、自身租约 RELEASED 且基线不变。未用本轮通过补发旧停止证明或恢复旧项目。

按代码审查技能复核 b1 的正确性、结构、安全与性能：固定项目行 → 构建行 → 独立容量锁，原/当前容量取小值且计入未知及高槽位，所有 SQL 参数化，授权/归档/原所有者重新检查；事务外不发布可重放的领取成功，异常保留通用错误，未新增执行权限、公开端点、依赖或 I/O。领取测试中的双连接竞争和旧锁编号变异已有真实失败/通过证据，不重复无变化的变异测试。全量通过后只勾选 b1；b2 封存结算、c 整体 worker、EX10 整体、C-EX04 与 EX11 仍未完成。

调试结论限定为：在获准的隔离 JDK 17 对照中，原场景和完整回归都未复现原生枚举错误，因此恢复后续开发；**尚无原 errno 或可按需复现的最小样本，不能保证已完全证明历史根因，也不能宣称原 17.0.5 已修复**。保留固定诊断，不修改停止判定、业务重试或超时来掩盖风险；隔离旧补丁不是长期生产版本升级承诺。没有改用户 OAuth 草稿、日常 env、服务器、模型或邮件配置，也没有合并、推送或部署。

## 2026-10-22 · EX10d3b2 封存产物的原子结算

沿用已批准的项目隔离 Liberica 17.0.14+10 和 `atoms_verify_20261021_ex10`，没有切换全局 Oracle、清理旧失败现场或启动子 Agent。主要文件仅四个：`ExecutionArtifact`、`ExecutionBuildSettlement`、追加的 `V024__execution_build_artifacts.sql`、`ExecutionBuildSettlementIT`。实施前确认已有迁移最大编号为 V023，后续计划中的依赖准备/运行迁移顺延到 V025/V026，不修改已应用 V023。

威胁边界是封存文件 → 内存句柄 → 持久构建身份。调用者不能提供一个摘要或路径来声称已封存；句柄绑定构建/项目/原所有者/来源/请求。结算先在事务外复验文件，再在项目/任务短锁内验证授权、取消、pending 及六个完整阶段，最后一起写入成功状态、三项摘要、释放容量和已有触发器阶段事件。未知执行保留名额；普通失败及权限撤销后的收尾不改旧成功记录。错误不携带原始 SQL、配置或路径，不增加依赖、公开接口或模型调用。

首条测试先出现夹具 Budget/Deadline 返回类型不一致，修正测试类型后，**00:22:29 RED** 仅剩尚未实现的 `ExecutionBuildSettlement` 缺类编译失败。实现后，**00:24:43** 为 6 项封存单元 + 1 项真实 PG 结算测试通过，迁移只应用于指定测试 schema（Flyway V000–V024，共 25 个）。**00:27:43** 增加反例后为 6 单元 + 13 集成通过，零失败/错误/跳过。

审查补充了锁等待期间撤销维护者的时序测试：通过 JDBC 边界在实际取到构建行锁后改变测试配置，**00:29:14 RED** 确认原实现在等待前缓存授权、错误接受成功（预期拒绝而未抛异常）。报告保存在忽略的 0700 目录 `.local/ex10b2-auth-red.tsGefz`；仅测试构建产生的记录保留，不代表真实应用构建。修正为项目行 → 构建行 → 当前配置/维护者复查，再进行最终结算；不借放宽权限或重试消除失败。

本片按 Spring Boot 3.5.16 管理的 Spring Framework 6.2.19 使用 `TransactionTemplate`，最长 5 秒事务/3 秒锁等待；哈希和文件扫描不放入事务。PG CHECK 显式拒绝 NULL，完成的摘要不可改写，非成功状态不能提前带上摘要。[Spring 6.2 编程式事务](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/programmatic.html)、[PG 17 CHECK 约束](https://www.postgresql.org/docs/17/ddl-constraints.html#DDL-CONSTRAINTS-CHECK-CONSTRAINTS)。

回退采用默认关闭执行开关并回退代码，保留新增元数据及约束，不 DROP/回填或改旧审计。集成测试中关闭执行开关后仍能查询既有成功记录、收尾无活动执行的失败任务；实际旧平台回归另由本轮完整 verify 验证。文件系统与数据库不构成共同事务，锁等待期间也没有 OS 级不可变文件保证；调用者仍须持有正确执行根/协调权及停止证明，后续使用产物必须复验。本片只用受控 no-op JVM + 七字节产物验证结算边界，**不是 EX11 真实 npm/Maven 或可执行用户 JAR 验收**。

修正后的定向命令（工作目录 `backend`）：

```sh
env JAVA_HOME=/Users/wubingheng/atoms-demo/.local/jdk17-comparison.LlvDQi/jdk-17.0.14.jdk \
  PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH \
  SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/atoms_demo_test?currentSchema=atoms_verify_20261021_ex10&connectTimeout=3&socketTimeout=5' \
  ./mvnw -B -Dtest=ExecutionArtifactTest -Dit.test=ExecutionBuildSettlementIT verify
```

**00:31:14，6 单元 + 15 集成全部通过，0 失败/错误/跳过，BUILD SUCCESS，1:28，退出码 0**。按本轮开始时间核对各 1 份新 XML，排除 106/97 份旧 XML，Java 均为隔离 17.0.14。报告保存在 `.local/ex10b2-focused-passed.6yc2UO`（0700）。新增验证分别覆盖前端/Java 清单计数不符、缺阶段、真实句柄跨身份冒用、同长度/同修改时间篡改、授权/归档/所有者/取消变化、未知执行保留、后续失败保留旧成功、并发单赢家、UPDATE 后故障回滚及超时在 UPDATE 后发生时仍回滚。数据库自身拒绝不完整/越界摘要和成功后改写。

**00:33:37 变异检查被捕获**：临时移除 `completedStages(build, evidence)`，运行 `-Dit.test=ExecutionBuildSettlementIT#rejectsMissingStagesEvenWithPackagingStatusAndARealSealedHandle`，1 项集成如预期失败（没有阶段证据却被错误接受）；不是凭代码阅读声称测试有效。报告保存于 `.local/ex10b2-mutation.SQgDUO`（0700），随后使用补丁恢复校验。实际坏变异产生的测试记录保留，不清库、不触碰真实应用记录。恢复后启动同一 JDK/schema 前缀的完整 `-Pproject-execution-contract -Dexecution.test-config-directory=/Users/wubingheng/atoms-demo/.local/project-execution-acceptance.x5cvui verify`；该 profile 不启用真实 Agent CLI、模型或 SMTP。

**01:01:30，完整后端回归 401 单元 + 350 集成全部通过，0 失败/错误/跳过，BUILD SUCCESS，27:30，退出码 0**。按本次开始时间汇总 89/95 份新 XML，18/3 份旧报告不加算；所有新报告均为隔离 Java 17.0.14。包括封存结算 15 项、原子领取 10 项、Agent 协调器 10 项（74.74 秒）、多轮完整源码编辑 3 项（175.1 秒）及原账号、发布、预览、导出/运行链路。全报告复制到忽略的 0700 目录 `.local/ex10b2-full-passed.VzG9oZ`。真实 CLI、模型、SMTP 和尚未实现的用户工程构建不是本次通过范围。

平台 JAR **30,821,531 字节**，SHA-256 `3b5004962559b1747d9dbdfc2ce4dc22f151109ffa5d3547724220df9efdecf5`；不是用户应用产物。回归后全局仍为 Oracle `17.0.5+9-LTS-191`，SDKMAN `current` 仍指向 `17.0.5-oracle`。只读进程清单未见 Java/Git/claude.exe，没有终止进程。

结束后的只读 PG 审计：当前 schema 的 Agent 未决、Agent 关联 pending/活动编辑租约、构建未决均为 0；测试角色仍无数据库 CREATE 权限。旧 pa32b 未知执行仍 ACTIVE/pending、缺停止证明且来源基线未变；此前两个源码失败项目继续 NEEDS_RECONCILIATION，旧失败 Agent `777da2c2-cf09-49c0-8e78-4453c4e4c1ce` 仍 FAILED、租约 RELEASED、无执行 ID。没有写入旧 schema、强制恢复或清理证据。

本轮日志中的 `source_operation_uncertain`（请求 `678e209a-cd0d-4457-8646-9e1a378a95d0`）已核对为 `ProjectSourceAgentCoexistenceIT` 主动注入 `Crash` 的恢复反例，不是新的原生枚举故障。该组 4 项通过；结束后准确记录为 FAILED / SOURCE_INTERRUPTED / pending=false，对应编辑租约 RELEASED / pending=false 且具有停止摘要。历史原生故障在隔离 JDK 本轮未复现，仍不据此断言根因完全确定或原 Oracle 已修复。

按代码审查技能复核正确性、可读性、架构、安全、性能：四个主要文件保持单一结算职责，无公开端点/调度/新依赖；文件 I/O 在事务外，SQL 参数化，锁顺序固定且锁后重新核对权限；最多读取 13 个有界事件识别六阶段及多余记录；复用既有状态事件，无原始日志/路径/凭据落库。授权时序反例与阶段校验变异均有真实 RED/GREEN 证据。新增迁移的默认关闭回退路径、既有读写链路及旧功能已回归，不声称已执行远程回滚演练。只验收 b2 及 b 父项，EX10d3c、EX10 整体、C-EX04、EX11 继续未完成；无推送、合并、部署或配置变更。

提交前 34 个本地文档链接/锚点全部有效，`git diff --cached --check` 通过；9 个任务文件及暂存补丁对 5 项本地私有配置值的内存扫描均无匹配，输出不包含配置值。平台 JAR 检查未包含 env、邮件测试夹具或本片测试类。用户原有 OAuth 草稿仍保持未跟踪，不加入本次提交。

## 2026-10-22 · EX10d3c1 工具版本探测的持久日志

整体 worker 接线前核对发现，四种工具的版本探测本身也启动进程，现有 `ExecutionToolProbe` 要求调用者先登记持久意图。按已批准的增量/测试驱动流程，实施前把 c 拆为 c1 探测日志和 c2 整体 worker；本片两个主要文件为 `ExecutionProbeJournal`、`ExecutionProbeJournalIT`，不修改已应用迁移、旧六阶段日志或当前网页。沿用获准的隔离 JDK 17.0.14 和测试 schema，默认执行开关关闭，不启动子 Agent、不推送或部署。

信任边界是本次固定工具身份/受控进程结果 → 任务 pending 与事件。begin 在 PREPARING 依 Node/npm/Java/Maven 顺序登记意图，项目行 → 构建行锁后检查当前权限及原身份；finish 必须匹配 build/execution/tool-home 停止证明，成功摘要及版本必须符合固定工具。缺证据或明确未知保留容量和 pending，取消优先于迟到成功；数据库异常不自动重试。工具 home 身份检查在事务外；事件预算及不可改写约束复用 V023，工具版本使用既有解析器。事务方式核对了 Spring Boot 3.5.16 管理的 Framework 6.2.19 [TransactionTemplate 文档](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/programmatic.html)和 [PG 17 行锁文档](https://www.postgresql.org/docs/17/explicit-locking.html#LOCKING-ROWS)，未增加依赖。

**01:17:30 RED**：新测试因尚无 `ExecutionProbeJournal` 类而编译失败。实现后，**01:20:16 首轮通过 3 项工具探测单元 + 3 项真实 PG 日志测试**，零失败/错误/跳过，1:34。随后补充并发、身份、取消、权限撤销及事件写库失败等反例，**01:23:27，3 单元通过、12 集成中 1 项失败**：明确 `EXECUTION_PROCESS_UNSETTLED` 同时带有停止证明时，第一版错误将其视为普通 FAILED 并释放容量。将未知结果分支改为缺停止证明或显式未知均保留，不能自行解释矛盾证据。失败 XML/txt 保留于 `.local/ex10c1-uncertain-red.c0EEnS`（0700），测试记录不删除。

定向命令（工作目录 `backend`）：

```sh
env JAVA_HOME=/Users/wubingheng/atoms-demo/.local/jdk17-comparison.LlvDQi/jdk-17.0.14.jdk \
  PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH \
  SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/atoms_demo_test?currentSchema=atoms_verify_20261021_ex10&connectTimeout=3&socketTimeout=5' \
  ./mvnw -B -Dtest=ExecutionToolProbeTest -Dit.test=ExecutionProbeJournalIT verify
```

PG 测试使用受控 no-op JVM 获取真实停止证明，工具身份来自临时固定安装目录的字节摘要，版本值为明确夹具；不以这些夹具声称实际运行了 npm/Maven 用户工程。测试中的丢失结果仅在 teardown 有准确真实停止证明时核对收尾，原状态/事件始终保留；不清理任何历史未知进程或旧失败源码项目。整体 worker、EX11 真构建和网页接入仍待后续完成。

**01:26:12，定向 3 单元 + 12 集成全部通过**，0 失败/错误/跳过，BUILD SUCCESS，2:10，退出码 0。按启动时间核对各 1 份新 XML，排除 106/98 份旧报告；Java 均为隔离 17.0.14。报告保存于 `.local/ex10c1-focused-passed.FXKFhj`（0700）。反例覆盖不支持/越界版本及工具摘要、错执行/cwd 停止证明、重复和乱序意图、权限/归档/所有者变更、锁后权限撤销、取消、超时、未知保留、并发单赢家、事件插入后抛错整笔回滚及拒绝调用者事务。

**01:29:42 变异被捕获**：临时移除 `stopped.matches(build.id(), execution, cwd)`，执行 `-Dit.test=ExecutionProbeJournalIT#rejectsOutOfOrderDuplicateAndWrongStopProofWithoutClearingIntent`，1 项集成如预期失败（错误证明未被拒绝）。报告保存于 `.local/ex10c1-mutation.Xhnmtu`（0700），随后使用补丁恢复该校验，再启动完整后端回归；不存在变异代码部署或未恢复即验收。

审查关注的可观测性问题为：哪个构建/哪次执行在探测哪个工具；它完成、失败还是结果未知；成功时具体核对了哪个版本和工具摘要。对应 `build_id`、`executionId`、`probe`、`phase`、固定 `code` 和成功摘要字段，事件自带时间；真实 PG 测试核对了事件数量、字节预算及失败回滚，不保存整段输出或账号信息。本片没有新 HTTP 端点、运行队列或外部遥测服务，不额外引入指标平台。未知时只查看该 build 的有序 probe 事件和 pending/容量，不能从 PID 或超时推断停止；生产恢复仍由后续 EX13 完成。

### 2026-10-23 续接核对

上轮工具会话在用户中断后已不可恢复，不能补称捕获了原命令的退出码。先只读检查确认无遗留 Java/Git 进程，再依据变异结束后的 `2026-10-22T01:29:45+08:00` 重建报告时间门槛：89 份 Surefire / 96 份 Failsafe 新 XML 合计 **401 单元 + 362 集成，0 失败/错误/跳过**，排除 18/3 份旧报告；新报告 Java 均为 17.0.14。首份报告为 01:31:35，最后一份为 01:55:09，Failsafe 汇总于 01:55:09 写入，completed=362、timeout=false、无 failureMessage。没有把先前定向或变异失败报告混入成功计数。

不重复运行已完成且代码未变的测试；在 `backend` 仅执行 `env JAVA_HOME=/Users/wubingheng/atoms-demo/.local/jdk17-comparison.LlvDQi/jdk-17.0.14.jdk ./mvnw -B failsafe:verify` 读取现有汇总，**10-23 01:25:34 BUILD SUCCESS，1.590 秒，退出码 0**。完整报告复制到忽略的 0700 目录 `.local/ex10c1-full-passed.hEOUN9`。平台 JAR 于 10-22 01:34:51 生成，30,829,336 字节，SHA-256 `6f3e3f47b396665d3dbe00c360580edd012b4649646d786dff7f5c9c9c49dbcc`，包含新日志类，不含 `.env` 或本片测试类；原有工程模板自带的 `application-test.yml` 不属于平台私有配置。

续接后的只读 PG 审计确认当前 schema 的 Agent 未决、关联 pending/活动编辑租约及构建未决均为 0，旧 Agent 协调锁无持有者，测试角色仍无数据库 CREATE 权限。旧 pa32b 未知执行仍 ACTIVE/pending、无停止证明且基线不变；两个旧源码失败项目及此前主动注入 Crash 的审计仍保留。SDKMAN 全局默认仍为 Oracle 17.0.5。未以新回归通过推断旧未知进程停止，也未删除旧记录。

代码审查覆盖正确性、可读性、模块边界、安全和性能：两个主要文件只增加探测日志及其 PG 契约，SQL 参数化，短事务锁顺序固定、取锁后重新授权；最多读取 9 条探测事件，版本和事件体有界，文件检查不进入事务，拒绝调用者事务与提交失败重试。前述错误证明变异已捕获且恢复；未新增依赖、公开端点或默认执行。仅验收 c1，c2 整体 worker、EX10 整体、C-EX04 和 EX11 仍未完成；本次没有真实模型、SMTP、用户工程构建、推送或部署。

## 2026-10-23 · EX10d3c2a 接线接口

c1 已本地提交为 `15967ff`。继续接线前，四个既有接口/测试文件加 worker/集成测试至少六个主要文件，按计划的五文件上限将 c2 在原清单内分为 c2a 兼容接口和 c2b 整体组合；不新增另一份任务清单，也未改 OAuth 草稿。

新增 `ExecutionPaths.createBuild(id, budget)` 复用既有分配器，容量检查、扫描及分配完成均消耗调用者预算；失败保留已分配证据。`checkedRoot()` 复用执行根租约和区域身份校验，返回路径不是可转移的执行权限。`ExecutionToolProbe.run(..., inputs)` 增加仅限内部调用的启动复核，在 `OwnedProcess` 的 preflight 内执行，拒绝能得到准确未启动证明；随后原进程基元仍复查取消和总期限。旧重载保持兼容，没有新的框架调用、公开端点、命令解释或 shell 权限。

先增加四项测试，覆盖预算在分配前/扫描中/分配后耗尽、根区域替换/租约关闭、启动复核拒绝及异常脱敏、复核期间取消/超时。**01:32:12 RED**：`./mvnw -B -Dtest=ExecutionPathsTest,ExecutionToolProbeTest test` 因上述接口尚不存在而编译失败，54.947 秒，退出码 1。实现接口后，同一隔离 JDK 命令于 **01:33:37 在 testCompile 阶段失败**：写入既有 `ProjectAgentQueryIT.class` 时操作系统返回 `No space left on device`，51.667 秒，退出码 1。未运行到测试，不能声称 GREEN，也未做变异或全量回归。

随后暂停源代码改动，只读检查确认无 Java/Git 遗留进程；Data 卷显示总容量 233 GiB、剩余约 1.5 GiB、Capacity 100%。项目 `.local` 约 4.6 GiB，`backend/target` 仅 41 MiB，前端依赖约 175 MiB。采样时空间已不为零，无法由此判断编译报错瞬间的占用来源；不将其误报为业务测试失败，也不修改断言或降低磁盘保留线。没有删除任何缓存、失败证据、私有配置或数据库，没有更改全局 JDK。需先解决磁盘空间，再重跑本片定向测试、变异与全量验证；c2a/c2b/EX10 保持未完成，本片代码未提交，不推送或部署。

### 清理后恢复验证

用户自行清理后明确要求继续，只读检查 Data 卷可用空间约 12 GiB，工作区仍为原四个主要文件及计划/证据修改，没有遗留 Java/Git 进程。沿用原 RED，不重复删除实现来制造编译失败；未改源代码即重新编译和测试。

**12:11:48，沙箱内定向运行 13 项，1 失败、1 错误**：8 项目录测试通过，工具探测新旧两条真实执行路径遇到 `DESCENDANT_ENUMERATION_FAILED / PROCESS_ENUMERATION / RUNTIME_FAILURE`，得到 `EXECUTION_PROCESS_UNSETTLED`。没有将未知结果改成成功。根据沙箱权限要求，申请沙箱外同代码、同 JDK 重跑；**12:13:16，13 项全部通过，0 失败/错误/跳过，BUILD SUCCESS，4.786 秒，退出码 0**。这支持与运行环境有关，不能仅凭一次通过宣称已定位原生错误的全部根因。首次失败保留在本次工具输出及此记录；XML 在复制前已被后续成功运行覆盖，不误称保存了失败 XML。成功的两份新报告保存在 `.local/ex10c2a-focused-passed.pSqMpj`（0700），Java 均为 17.0.14。

**12:14:44，启动复核变异被捕获**：临时移除 `inputs.check()`，沙箱外执行 `-Dtest=ExecutionToolProbeTest#launchGuardDenialPreventsExecutionAndKeepsNoProcessProof test`，1 项如预期失败（Expecting code to raise a throwable），无错误/跳过，26.646 秒，退出码 1。报告保存在 `.local/ex10c2a-mutation.veVsMx`（0700）。随后用补丁恢复校验，并只读确认第 44 行仍调用 `inputs.check()`，没有变异代码遗留。

12:15:14 启动完整回归，命令如下；运行期间不修改生产/测试源代码，最终结果另行记录，不提前勾选任务：

```sh
env JAVA_HOME=/Users/wubingheng/atoms-demo/.local/jdk17-comparison.LlvDQi/jdk-17.0.14.jdk \
  PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH \
  SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/atoms_demo_test?currentSchema=atoms_verify_20261021_ex10&connectTimeout=3&socketTimeout=5' \
  ./mvnw -B -Pproject-execution-contract \
  -Dexecution.test-config-directory=/Users/wubingheng/atoms-demo/.local/project-execution-acceptance.x5cvui verify
```

开始前只读审计确认当前 schema 的 Agent 未决、关联 pending/活动编辑租约及构建未决均为 0，旧未知执行及失败源码状态保留。恢复过程没有删除测试记录、目录证据或私有配置，也未授权真实模型、SMTP、推送或部署。

**12:32:29，完整后端回归 405 单元 + 362 集成全部通过，0 失败/错误/跳过，BUILD SUCCESS，17:12，退出码 0。** 按 `12:15:14.322` 启动门槛独立核对 89/96 份新 XML，排除 18/3 份旧报告，所有新报告 Java 均为 17.0.14；本片四项新增测试包含在 405 项中。完整报告保存于 `.local/ex10c2a-full-passed.yBuaWS`（0700）。原账号、发布、源码编辑、Agent 协调/恢复、多轮任务、导出等既有链路通过，真实 CLI、付费模型、SMTP 及尚未实现的真实用户工程构建不在本次验收范围内。

本轮平台后端 JAR 为 30,829,588 字节，SHA-256 `33f4bb5476e0a5d45cd470878a3d5d3d02c6f9e104d35c8293dadddf30c50b58`，不含 `.env`、平台测试配置或本片测试类。结束后只读 PG 核对当前 schema 的 Agent 未决、关联 pending/活动编辑租约及构建未决仍全为 0，旧 Agent 协调锁无持有者、测试角色无数据库 CREATE 权限；旧 pa32b 未知执行及两个源码失败项目未改。恢复测试的 `source_operation_uncertain` 对应 `ProjectSourceAgentCoexistenceIT` 主动注入的四阶段 `Crash`，该组 4 项通过，不混同于首次沙箱内枚举失败。SDKMAN 默认仍为 Oracle 17.0.5，磁盘可用约 13 GiB。

按代码审查技能检查四个主要文件：只增加兼容重载和已有租约校验，不复制执行/文件拥有关系逻辑，不增加配置开关、依赖、数据库迁移或外部接口；预算沿用有界扫描，失败保留分配证据，启动复核位于准确未启动证明的发行边界内，取消/超时在复核后再次检查。固定命令、清洁环境和输出脱敏规则不变；这些合作式检查仍不能隔离同 UID 的恶意代码，也不能保证操作系统调用本身瞬时返回。变异已捕获并恢复，完整回归验证通过后仅勾选 c2a；c2b、EX10、C-EX04 与 EX11 保持未完成。没有修改私有 env、OAuth 草稿、日常服务或远程服务器。

## 2026-10-23 · EX10d3c2b 整体构建 worker

从本地 `d5d3d55` 继续，使用增量实现、测试驱动、接口设计、安全检查及代码审查流程。范围为 `ExecutionBuildWorker`、外部批准文件的有界身份复验 `ExecutionApprovedPolicy`，及对应两份测试，共四个主要文件；无新依赖、迁移、公开入口、自动协调器或开关变更。

**13:21:46 RED**：先新增测试，`-Dtest=ExecutionApprovedPolicyTest test` 因两个目标类尚不存在而编译失败，18.827 秒、退出码 1。随后实现同步单次组合：领取 → 固定源码 → 外部批准文件/工具/测试库 → 四个有日志探测 → 五个前端阶段 → 私有资源装配 → Java 阶段 → 产物封存 → 原子结算。任务期限取准入时与当前配置的较小值，过程中不重置；不自动重试未知日志或结算提交。

沿用 Spring Boot 3.5.16 / Framework 6.2.19。只读参数绑定查询和各既有短事务组件分别使用 [JdbcTemplate](https://docs.spring.io/spring-framework/reference/6.2/data-access/jdbc/core.html#jdbc-JdbcTemplate) 与 [TransactionTemplate](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/programmatic.html)，磁盘/工具/子进程不包在元数据事务中。接口仍要求未来协调者提供存活校验并保持执行根租约；本片不宣称已实现 EX12 的数据库协调锁。

运维问题沿用既有持久事件回答：停在哪个阶段（build/probe/operation/phase）、是否确实停止（匹配的执行证明与 pending）、哪份产物成功（来源及摘要）。不复制原始 stdout/stderr、路径、SQL、密码或源码到事件；日志提交失败由调用者收到固定状态不可用错误，保留原意图等待 EX13 核对，不追加猜测性成功/失败事件。

测试使用真实测试 PG、Git 和拥有停止证明的受控 JVM。版本输出、前端产物及 JAR 字节由测试夹具产生，不代表真实 npm/Maven 构建或可执行 JAR 已验收。完整模板样本移除项目内 `frontend/.npmrc` 后冻结为新版本，因为当前执行策略明确只允许平台私有 npmrc；未修改正式模板或放宽策略。专用应用测试库只读检查及向受控 Java 子进程的凭据传递不执行应用 DDL/DML。定向、变异和完整回归结果如下；不以夹具结果勾选 EX11 或 C-EX04。

**13:43:42，首轮定向 3 单元 + 5 集成全部通过，零失败/错误/跳过，BUILD SUCCESS，14:48，退出码 0。** 私有归档 `.local/ex10c2b-focused-initial.Em6Lsi`（目录 0700、两份 XML 0600）。整体流程集成类耗时 856.6 秒；两次主线程只读采样分别在完整版本成功场景、取消/策略漂移场景的 Git 对象读取中，已确认推进而非凭无输出猜测卡死。完整源码复验仍逐对象启动 Git，未跳过检查或做未经测量的缓存优化；该耗时作为后续 EX11 性能观察项，不据此声称真实构建已满足时延要求。然后补充启动权限/根/事务边界、配置漂移、停止收据丢失测试，并加强入队上限测试为 60 秒入队后调大到 600 秒仍在原上限停止。

**13:48:06，补充定向 3 单元 + 4 集成全部通过，零失败/错误/跳过，BUILD SUCCESS，03:04，退出码 0。** 归档 `.local/ex10c2b-boundaries-passed.ZTVF9T`。集成测试总数为 8，补充运行含 3 个新增场景及加强后的原期限场景，不把两轮相加误报为 9 个不同测试。

**13:52:15，停止收据变异被捕获**：临时移除 worker 探测结果的 `stopped == null / matches` 校验，单独运行 `lostStopReceiptKeepsIntentAndCapacityWithoutAnotherLaunch`。出现 1 个预期错误 `EXECUTION_STATE_UNAVAILABLE`，无跳过，BUILD FAILURE，01:23，退出码 1；缺少该检查后流程继续尝试下一探测意图，未按契约直接返回待核对状态。持久日志仍拒绝后续执行，没有真正启动第二个工具。报告保存 `.local/ex10c2b-mutation.zREDqi`。随后补丁恢复第 162 行校验，再只读审计：当前 schema 新 Agent 未决、pending、关联活动编辑及构建未决均为 0，旧审计和源码失败项目保持不变，测试角色 CREATE 权限仍为 false。只有确实持有真实受控子进程停止证明的故障夹具才由测试收尾，不从 PID 猜测停止。

**13:53 启动完整回归**，之后冻结生产/测试源码，仅补文档；报告新鲜度门槛为 `2026-10-23T13:53:00+08:00`，排除先前定向和变异报告。命令（`backend`）：

```sh
env JAVA_HOME=/Users/wubingheng/atoms-demo/.local/jdk17-comparison.LlvDQi/jdk-17.0.14.jdk \
  PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH \
  SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/atoms_demo_test?currentSchema=atoms_verify_20261021_ex10&connectTimeout=3&socketTimeout=5' \
  ./mvnw -B -Pproject-execution-contract \
  -Dexecution.test-config-directory=/Users/wubingheng/atoms-demo/.local/project-execution-acceptance.x5cvui verify
```

### 完整回归续接核对与验收

会话中断后，原 Maven 会话句柄已不可用，没有取得该次命令的最终退出码，故不补写为原命令 `exit 0`。只读检查确认没有遗留 Java/Git 进程；按上述启动门槛核对 **90 份单元报告：408 项，97 份集成报告：370 项，全部零失败/错误/跳过**，排除 18/3 份旧报告。新报告均使用 Java 17.0.14，最后一份集成报告时间为 **14:26:25**，`failsafe-summary.xml` 于 **14:26:26** 记录 completed=370、errors/failures/skipped/flakes=0、timeout=false。本片 3 项策略测试及 8 项 worker 集成均包含在总数内；worker 类耗时 864.188 秒，不作为真实工具构建性能结论。

**18:20:18，独立校验已有报告成功，BUILD SUCCESS，1.210 秒，退出码 0**；没有重复运行测试、跳过测试或覆盖原报告。命令（`backend`）：

```sh
env JAVA_HOME=/Users/wubingheng/atoms-demo/.local/jdk17-comparison.LlvDQi/jdk-17.0.14.jdk \
  PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH \
  ./mvnw -B -Pproject-execution-contract failsafe:verify
```

完整新报告归档于 `.local/ex10c2b-full-passed.vnnL8g`：目录 0700、XML 0600，90 份 Surefire、97 份 Failsafe 及一份汇总。本轮平台后端 JAR 为 **30,845,687 字节**，SHA-256 `c84ceb0f0dc7587b2da2c2dd729e965548b1def227ffb733990586fa3490f068`，包含两个新增生产类；这不是生成应用的可运行 JAR 证据。

结束后只读审计确认当前 schema 的 Agent 未决、pending、关联活动编辑以及构建未决全为 0，旧 Agent 协调锁无持有者、测试角色数据库 CREATE 权限为 false。旧 pa32b 未知执行仍保留 pending/活动租约，两个旧源码失败项目状态及恢复夹具证据不变。SDKMAN 全局默认仍是 `17.0.5-oracle`；没有清理旧现场、改私有 env 或个人 Claude 配置、调用真实模型/SMTP、推送或部署。

五轴自审结论：

- 正确性：一次性领取、固定版本与旧产物保留、共享期限/取消、启动控制变化、停止收据丢失及未知提交均有测试；移除收据校验的变异已捕获且恢复后进入完整回归。
- 可读性与架构：四个主要文件，worker 只组合既有快照/策略/日志/阶段/封存/结算，不复制进程拥有关系或恢复逻辑；测试接缝不向配置/API 暴露。无新依赖、迁移或公开接口。
- 安全：授权在领取/启动/短事务内复核，参数化 SQL；外部批准文件按身份、字节和 64 KiB 上限检查，专用 DB 凭据仅进入受控 Java 启动环境，持久事件无原始输出。仍非 OS 沙箱，不对任意用户开启。
- 性能：阶段数量固定，文件读取有界且受共享期限约束，Git/I/O/子进程不占元数据事务；重复 Git 对象读取已有明显耗时，EX11 须测真实探测预算并处理兼容，不宣称性能验收通过。

据此勾选 c2b 及 EX10 的父项；**EX11、EX12、C-EX04 以及运行/交付/工作台保持未完成**。下一步为正式种子的 `.npmrc` 兼容及真实 npm/Maven 构建，不将测试内移除文件或受控输出等同于实际工程成功。

提交前仅暂存本片 4 个主要文件与 5 份规格/任务/证据文档；`git diff --cached --check` 通过，38 个相对文档链接目标存在。内存中将暂存内容与私有配置中的 5 个已知密钥/密码值比对，命中为 0，没有输出这些值；OAuth 草稿保持未跟踪且不纳入本片提交。

## 2026-10-23 · EX11 原始种子真实构建接入

从 `5c3949d` 继续。先将原模板 `.npmrc` 的不兼容作为 EX11a：正式模板不删改，外部批准加可选 `npmrcSha256`，只准许准确路径的固定两行安全配置；任意其他内容即使摘要获准也拒绝，旧无该文件的快照仍兼容。依据 [npm 11.19.0 npmrc](https://raw.githubusercontent.com/npm/cli/v11.19.0/docs/lib/content/configuring-npm/npmrc.md) 与 [npm ci](https://raw.githubusercontent.com/npm/cli/v11.19.0/docs/lib/content/commands/npm-ci.md)，保留冻结安装和禁用脚本，私有配置不变。

**18:31:15 RED**：`ExecutionNpmPolicyTest` 的 10 项中 1 失败、2 错误，原始 35 文件准入被拒绝、新授权字段不支持；0 跳过，退出码 1，01:04。归档 `.local/ex11a-red.O5T47C`，目录 0700、XML 0600。随后实现准确字节与外部摘要的双重检查，种子批准记录 `33452a1dec6251147fd8c556a0c5d426b7af2e837fc12ca4e102bf1dd6e6262f`，未修改模板、依赖或锁文件。

**18:33:12 GREEN**：`-Dtest=ExecutionNpmPolicyTest,ExecutionMavenPolicyTest,ExecutionApprovedPolicyTest package`，**17 项通过，0 失败/错误/跳过**，BUILD SUCCESS，01:10，退出码 0。这只证明原始模板准入和策略，不是实际工程构建。

EX11b 新增 `ExecutionSeedBuildContractIT`，直接调用原生 worker/工具，不注入模拟 runner；显式 profile 缺工具或私有目录参数即失败。成功工程检查 JAR 内 Java 类、静态首页、迁移及清单/数据库摘要，然后发布两个测试版本分别引入前端断言失败和 Java 编译失败，验证不能覆盖旧产物。仅执行原始种子的 MVC 测试，不启动监听服务或应用迁移；本机受控目录与失败状态保留，未知执行不由测试强制结算。四个主要文件（策略、策略测试、批准资源、真实契约），无新生产依赖/迁移/公开入口。真实验收结果待后续实际命令完成记录，不预先勾选 EX11。

**18:36:48，首次真实契约未通过**：17 项策略测试通过；1 项契约在分配执行根时返回 `EXECUTION_STORAGE_UNAVAILABLE`，没有创建构建任务或运行 npm/Maven。准确原因是测试参数错误地指定仓库 `.local` 作为父目录，`ExecutionRootLease.validateRoot` 明确拒绝与平台仓库重叠。报告保存在 `.local/ex11b-root-failure.4JH4gb`；只读审计无新增未决，旧记录不变。未改生产保护，改用 `mktemp -d` 创建的仓库外私有目录 `/private/tmp/atoms-ex11-contract.APT7Jb` 后重新运行；原目录保留。

真实验收命令（`backend`，仅路径入参数，无密码；获准沙箱外固定工具与网络）：

```sh
env JAVA_HOME=/Users/wubingheng/atoms-demo/.local/jdk17-comparison.LlvDQi/jdk-17.0.14.jdk \
  PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH \
  SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/atoms_demo_test?currentSchema=atoms_verify_20261021_ex10&connectTimeout=3&socketTimeout=5' \
  ./mvnw -B -Pproject-execution-contract \
  -Dexecution.test-config-directory=/Users/wubingheng/atoms-demo/.local/project-execution-acceptance.x5cvui \
  -Dexecution.contract-directory=/private/tmp/atoms-ex11-contract.APT7Jb \
  -Dexecution.contract-node=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin/node \
  -Dexecution.contract-npm=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin/npm \
  -Dexecution.contract-java=/Users/wubingheng/atoms-demo/.local/jdk17-comparison.LlvDQi/jdk-17.0.14.jdk/bin/java \
  -Dexecution.contract-maven=/Users/wubingheng/.m2/wrapper/dists/apache-maven-3.9.11/a2d47e15/bin/mvn \
  -Dtest=ExecutionNpmPolicyTest,ExecutionMavenPolicyTest,ExecutionApprovedPolicyTest \
  -Dit.test=ExecutionSeedBuildContractIT verify
```

**18:41:05，第二次真实契约失败**：17 项策略测试通过，1 项契约因原始种子构建为 `TIMED_OUT / EXECUTION_TIMEOUT` 而断言失败，退出码 1；worker 耗时 48,976 ms，尚未开始依赖安装。私有报告 `.local/ex11b-probe-timeout.tgeb4x` 与 `/private/tmp/atoms-ex11-contract.APT7Jb/seed-build-4668504190981952349` 原样保留。只读查询确认 NODE 探测完成，NPM 探测超时，任务 `35898596-960a-4c08-ac6a-0179946eec1e` 的 pending=false、worker_slot=null，拥有停止证据后正常结算，没有强制解锁。

随后独立原生 `ExecutionToolProbeTest.main` 使用相同四个工具通过，版本为 Node 24.20.0、npm 11.19.0、隔离 Java 17.0.14、Maven 3.9.11，私有 HOME/工具身份有效。该检查不含工程启动前复核，不能替代完整构建。只读定点测量同一 35 文件源码（`cee9efc4-2c51-477c-9ff0-100f0a0a91ca` / `b14b7bc6-252e-48e3-b868-c379cb24e16c`），三次均启动 **121 个 Git 进程**，耗时 **4522 / 4970 / 4834 ms**，摘要均为 `836bbb221c92332f3d3f99a2f9c915a667867f34a7f78daa5031e54fe3070fc4`。诊断类先由 javac 编译到私有 `/private/tmp/atoms-ex11-timing.3YU5BT` 再运行；此前 source-launcher 因不同 classloader 的包私有访问而报 `IllegalAccessError`，未更改生产可见性或源码内容。

据此单列 EX11c 两文件兼容片：每个 Git 对象原先分别启动大小查询与内容查询，尝试以 `cat-file --batch` 单对象调用合并，保留对象类型、大小、摘要和所有权限/目录/树检查。当前 Git 为 2.39.5；[官方手册](https://git-scm.com/docs/git-cat-file/2.38.0#_batch_output) 的默认格式为对象 ID、类型、大小、原始内容与 LF，2.38.1–2.39.5 无该手册变更。不缓存、不放宽探测或总期限；只有同条件复测收益及正确性通过才保留。EX11a、c、b 按各自范围验证和提交，完整 EX11 仍以真实构建及失败反例为准。

**19:06:36，EX11a 变异被捕获**：临时移除固定两行内容比较，保留路径与外部摘要，`externalHashCannotApproveArbitraryNpmConfigOrAnotherLocation` 1 项按预期失败（任意配置被错误批准），0 错误/跳过，26.242 秒、退出码 1。报告 `.local/ex11a-mutation.1DmtT3`。恢复后 **19:07:30，17 项相关策略测试全部通过，0 失败/错误/跳过，打包 BUILD SUCCESS，26.971 秒、退出码 0**。EX11a 仅提交策略/资源/测试及文档；真实构建测试仍在下一片，不把两次失败契约写为通过。审查确认不新增依赖/公开配置/迁移，不执行项目任意 npm 配置；固定字节、路径、外部授权三项同时成立，读取/扫描预算不变。

EX11a 本地提交 `858bda8`，绿色报告归档 `.local/ex11a-passed.Q2e45z`；提交前 27 个相对文档链接有效、5 个私有值内存比对命中为 0。未纳入 OAuth 草稿或未通过的真实构建测试。

### EX11c · Git 单对象读取优化

**19:10:22 RED**：先增加真实 Git 进程计数与空文件/中文/换行保持测试，期望单次完整读取为 5 个进程，原实现为 9，1 项失败、0 错误/跳过，22.707 秒、退出码 1；归档 `.local/ex11c-red.v7h74J`。随后仅改 `SourceGitStore` 的对象读取和其测试，默认 batch 格式准确匹配预期对象 ID/类型/非负规范大小，要求只有一个响应、准确内容长度和结尾 LF，重新计算对象摘要；输出仍有硬容量与共享期限，实际 Git 的停止/取消规则未改。

**19:12:11 GREEN**：`ProjectSourceGitStoreTest,ProjectSourceGitProcessTest,SourceGitStopDiagnosticsTest,ProjectSourceDeadlineTest,ProjectSourceStorageBudgetTest` 共 **33 项全部通过，0 失败/错误/跳过**，43.324 秒、退出码 0；归档 `.local/ex11c-focused.AZdQMu`。包含损坏摘要、错误对象/类型/大小、缺失对象、超额输出、截断、别名目录、root 替换及既有取消/收尾场景。

同一 JVM/命令/35 文件与三次样本、无同时运行测试的复测结果为 **61 个 Git 进程，2591 / 2132 / 2095 ms**；摘要三次均与基线相同。中位数由 4834 降为 2132 ms，约下降 **55.9%**，差值超过本轮样本波动，保留优化继续验证实际构建。该数据仅代表本机定点读取，不称为整轮构建时延或线上 SLA。

**19:13:17，摘要变异被捕获**：临时取消返回内容的 SHA-1 检查，`rejectsWrongBatchIdentityTypeSizeDigestTruncationAndAdditionalOutput` 因损坏内容未被拒绝而失败，1 项失败、0 错误/跳过，25.821 秒、退出码 1；归档 `.local/ex11c-mutation.WZkKpu`。随后恢复校验；只读审计当前 schema 新 Agent/构建未决及关联活动租约均为 0，原未决记录保留。恢复后真实契约使用上方 EX11 命令，将 `-Dtest` 改为五个源码相关测试类（共 33 项），`-Dit.test` 保持原生种子构建；没有扩大工具 10 秒或任务 1800 秒期限。

**19:29:19，本轮 33 单元通过、1 原生契约失败，03:50、退出码 1**。恢复后的源码测试及平台打包通过；真实构建 `4c9a3c6a-cd9a-4d66-b2dc-56891954a94d` 用时 166,707 ms，四个工具探测、npm ci、类型检查和 lint 均完成，原探测超时未重现。构建在 UNIT_TEST 后检查报 `EXECUTION_STORAGE_UNAVAILABLE`，pending=false、worker_slot=null；没有 JAR 或成功构建。报告 `.local/ex11b-vitest-home-failure.py6fhi`、`seed-build-5807344048823581784` 现场保留。

定点检查发现实际 `test-results/unit.json` 为 0600/单链接/6034 字节，**15 项前端测试全部通过**。Vitest 5.0.3 在 `tool-home/Library/Application Support/vitest/.vitest-secret-token` 新增用户数据，而 `ExecutionToolHome.verify` 正确拒绝私有配置 HOME 的未批准顶级目录；未读取或输出该令牌。文件总数 8763、目录 1569、链接 21、特殊文件 0，不能误归因于十万项容量限制。新增 EX11d 仅调整 UNIT_TEST 的任务内 HOME，让此工具数据落在既有私有临时区；配置文件位置、摘要/身份复验、容量与无继承凭据保持不变。EX11c 的 33 项验证及性能收益足以保存本地兼容提交，完整 EX11 和整轮回归仍未完成。

EX11c 本地提交 `59d82d9`。EX11d 依据固定版本官方 [apiToken.ts](https://raw.githubusercontent.com/vitest-dev/vitest/v5.0.3/packages/vitest/src/node/config/apiToken.ts) 和 [配置解析](https://raw.githubusercontent.com/vitest-dev/vitest/v5.0.3/packages/vitest/src/node/config/resolveConfig.ts) 核对：macOS 使用 HOME 的 Library/Application Support，Linux 使用 XDG_DATA_HOME 或 HOME 的 .local/share；此步骤不是仅开启 UI/API 后才发生。修复只让固定 UNIT_TEST 命令的 HOME 指向任务既有 tmp，并重新生成一致的子 JVM 属性；不从宿主读取 XDG/凭据，不扩大可变配置目录集合，不停用令牌或检查。

**19:42:46 RED**：新增准确命令环境测试，UNIT_TEST 的 HOME 仍为配置目录而非 tmp，1 项失败、0 错误/跳过，31.120 秒、退出码 1；`.local/ex11d-red.Ak9JSr`。实现后 **20:18:12 GREEN**：`ExecutionProcessTest,ExecutionToolchainTest,ExecutionToolHomeTest` 共 **15 项通过，0 失败/错误/跳过**，54.663 秒、退出码 0；`.local/ex11d-focused.HyHmrz`。两个新增测试分别验证所有工具的环境区别与真实父子 JVM 的 HOME/属性一致，npm 配置/缓存路径不漂移。

**20:19:55，EX11d 变异被捕获**：临时取消切换 HOME 后重新生成 JVM 属性的步骤，命令环境测试因 `user.home` 仍指向配置目录而失败，1 项失败、0 错误/跳过，37.409 秒、退出码 1；`.local/ex11d-mutation.e1aFIm`。随后恢复原实现，以已批准的 33 源码单元 + 1 原生种子契约命令继续验证；完整结果另记，不将前端 15 项通过等同于最终工程通过。

### EX11b · 实际 JAR 与失败版本验收

**20:32:57，33 项源码单元 + 1 项原生种子契约全部通过，0 失败/错误/跳过，BUILD SUCCESS，12:43、退出码 0。** 真实契约自身 703.6 秒，连续检查三个不可变源码版本，直接使用实际 npm/Node/Java/Maven，没有注入模拟命令或人工创建测试报告：

| 构建 | 结果 | worker 用时 | 验证 |
|---|---|---|---|
| `bf09cff5-2299-4def-b3e2-5982dd1f833c` | SUCCEEDED | 291,358 ms | 原始 35 文件；前端 15 项、Java 2 项通过；JAR 包含 Java 类、静态首页和 SQL 迁移，首页与本次 dist 字节一致 |
| `af3dae59-c456-4009-9eaa-ee086fc2246d` | FAILED / EXECUTION_PROCESS_FAILED | 164,217 ms | 添加前端失败断言，停止在 UNIT_TEST，无封存产物；旧成功记录与 JAR 摘要不变 |
| `e903c8a0-e401-4187-bafd-3b4259d6ab3a` | FAILED / EXECUTION_PROCESS_FAILED | 218,635 ms | 添加无法编译的 Java 类，停止在 MAVEN_PACKAGE，无封存产物；旧成功记录与 JAR 摘要不变 |

成功源码版本 `c7a6207c-798a-4743-9238-58a0dcf562db`，tree SHA-256 `836bbb221c92332f3d3f99a2f9c915a667867f34a7f78daa5031e54fe3070fc4`。实际应用 JAR **24,023,687 字节**，SHA-256 **`1833cd77564b203c9647134fda2a076e7b6360c0a5d14f612615f832bbb232d9`**，磁盘、清单和数据库记录一致。三次结束均 pending=false、worker_slot=null；专用测试库只读身份与 relation 数量前后相同。源码快照不变，不包含工具 HOME、私有 env 或测试报告。报告与 Failsafe 汇总保存 `.local/ex11b-real-passed.11oZja`（0700/0600），工程和产物保留在 `/private/tmp/atoms-ex11-contract.APT7Jb/seed-build-4079350537997949556`。

这验证了原始 Vue + Spring Boot 种子的实际检查/编译/打包及失败保留；尚未启动应用 HTTP 服务、执行应用迁移或将新模式接到网页。EX12 协调/取消、常驻运行和后续交付仍未完成。

### EX11 完整后端及离线 CLI 回归

兼容回归前只读发现个人 npm 安装已为 Claude Code **2.1.308**，原 `bin/claude.exe` 是 500 字节不可执行占位文件，真正 Mach-O 位于平台包目录；没有修改该安装或配置。执行适配当前仅验收 2.1.302/303，故未放宽版本表。按计划申请并获准在忽略的私有 `.local/ex11-cli-2.1.303.XX2V2d` 安装单个官方 `@anthropic-ai/claude-code-darwin-x64@2.1.303`，npm 使用清空继承环境、私有 HOME/缓存/配置、固定官方 registry、`--ignore-scripts --no-audit --no-fund --save-exact`。官方包完整性 `sha512-U8onr6uQGcl6vOcelUmcwB+rtKljia90U82q5OLxfjbw415odmc1XHXRJcIa+TPxprFSIKbZH3x+EHwCHMOqEg==` 与 lock 精确匹配，实际二进制 SHA-256 `6c5d63b43568c5d60963d40f05b0087cb03ba44a90740d27adc505f4157806db`；未安装到个人全局目录。

获准脚本 `.local/ex11-compat-regression.sh` 随后运行下列命令（`backend`）。本次不重复已通过且源码未变的 12 分钟原生构建；明确选择其他全部 IT 并开启真实 CLI 的 loopback 假上游契约，使用 canary Key，不连接真实模型。最终记录分别报告这轮与上述原生契约，不能伪装为同一次执行。新报告新鲜度门槛为 **2026-10-23T21:26:25+08:00**，旧报告保留但不计数；执行期间冻结生产/测试源码。

```sh
env JAVA_HOME=/Users/wubingheng/atoms-demo/.local/jdk17-comparison.LlvDQi/jdk-17.0.14.jdk \
  PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH \
  SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/atoms_demo_test?currentSchema=atoms_verify_20261021_ex10&connectTimeout=3&socketTimeout=5' \
  ./mvnw -B -Pproject-execution-contract,agent-cli-contract \
  -Dexecution.test-config-directory=/Users/wubingheng/atoms-demo/.local/project-execution-acceptance.x5cvui \
  -Datoms.agent.test.cli=/Users/wubingheng/atoms-demo/.local/ex11-cli-2.1.303.XX2V2d/node_modules/@anthropic-ai/claude-code-darwin-x64/claude \
  '-Dit.test=*IT,!ExecutionSeedBuildContractIT' verify
```

**21:58:27，完整回归报告完成：417 单元 + 377 集成，全部零失败/错误/跳过。** 按上述时间门槛核对 90 份 Surefire 和 100 份 Failsafe 新 XML；包括 7 项真实 CLI + loopback 假上游契约，不计此前的原生种子契约旧 XML。会话中断后原 exec 句柄已失效，没有捕获原命令最终退出码；只读确认原测试 JVM 已退出。**22:06:02** 使用相同隔离 JDK、`./mvnw -B -o -Pproject-execution-contract,agent-cli-contract failsafe:verify` 对现有报告独立校验，**BUILD SUCCESS，1.811 秒、退出码 0**，未重跑测试。Failsafe 汇总 completed=377、failures/errors/skipped=0、timeout=false。报告和汇总归档 `.local/ex11-full-passed.zifl9wqq`（0700/0600）。与 20:32:57 的独立原生契约合计覆盖 378 个不同集成测试；这不是一次运行的 378 项。

回归后的只读数据库核对：当前 schema 的新增 Agent 未决、pending、关联活动编辑租约和构建未决均为 0；旧 Agent 协调锁无持有者，测试角色仍无数据库 CREATE 权限。三份旧审计及已记录的源码失败/中断现场保留，没有删除、强制解锁或按 PID 收养进程。个人 Java 默认仍为 `17.0.5-oracle`，磁盘剩余约 11 GiB；没有修改日常私有 env、个人 Claude 安装或服务器。

本轮自审覆盖正确性、接口边界、安全和性能：正式 35 文件模板不删改，`.npmrc` 必须同时满足准确路径、固定内容和外部摘要；Git 合并读取仍检查对象/大小/摘要，三个变异均被捕获后恢复；UNIT_TEST 的可变用户数据仅改到任务已有临时目录，npm 配置路径保持固定，真实子 JVM 验证了属性继承；实际 JAR 的数据库/清单/磁盘摘要一致，两个失败版本没有覆盖旧产物。未增加公开 API、数据库迁移或依赖，也未把本机定点读取约 55.9% 的收益声称为整轮构建性能保证。默认仍关闭，不构成任意代码安全沙箱；EX12、C-EX04、真实 HTTP 业务运行和新网页入口仍未完成。

EX11d 本地提交 `2c65c1b`，仅四个实现/测试文件及对应文档；提交前 5 个私有值内存比对命中为 0，19 个相对链接有效。EX11b 单独保存原生契约与最终状态文档；该契约显式启用才执行，默认不连接真实工具或安装依赖。OAuth 草稿保持未跟踪，未推送或部署。

EX11b 本地提交 `59c9700`。提交前 5 个私有值内存比对命中为 0，41 个相对链接有效；源码/测试在原生与完整回归后没有改动，状态文档更新不重跑验收。

## 2026-10-23 · EX12a 持久取消入口

按已确认的 EX12 拆成取消入口、独立协调租约、调度接线，先实现三个主要文件：`ProjectExecutionService`、`ExecutionBuildRepository`、`ExecutionCancellationIT`。沿用 [PostgreSQL 17 行锁](https://www.postgresql.org/docs/17/explicit-locking.html#LOCKING-ROWS) 与 [Spring 6.2 TransactionTemplate](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/programmatic.html)，项目 → 构建锁的顺序和事务结束释放规则不变；锁后复核权限，取消 API 不持有进程句柄、不直接终止或启动进程。

**22:15:06，首次 RED 为缺少 cancel 方法的编译失败，01:04、退出码 1**；同时发现新测试的 Stage 构造参数顺序错误，按实际记录声明修正。此时没有执行测试，不能将编译失败记作业务断言覆盖。实现后按下列命令在批准 schema 验证：

```sh
env JAVA_HOME=/Users/wubingheng/atoms-demo/.local/jdk17-comparison.LlvDQi/jdk-17.0.14.jdk \
  PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH \
  SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/atoms_demo_test?currentSchema=atoms_verify_20261021_ex10&connectTimeout=3&socketTimeout=5' \
  ./mvnw -B -o -Dtest=ExecutionSettingsTest -Dit.test=ExecutionCancellationIT verify
```

**22:36:49，7 单元通过，9 集成中 2 错误、0 失败/跳过，39.160 秒、退出码 1**。两项错误均在设置 Mockito stub 时发生，`spy` Spring AOP 代理导致 `SpringMockResolver` 的 `UnfinishedStubbingException`；业务方法尚未执行。报告保留 `.local/ex12a-proxy-fixture-failure.etg7hgq0`。修正为窄的项目服务委托，将锁操作继续交给原 Spring 代理，保留真实事务/行锁及原断言；不绕开生产权限检查。其他七项已覆盖排队原子取消、已领取与待核对容量、真实停止收据迟到时取消优先、终态/归档、越权、回滚脱敏和外部事务拒绝。最终通过及变异结果另记。

**22:39:10 GREEN，7 单元 + 9 集成全部通过，零失败/错误/跳过，BUILD SUCCESS，01:15、退出码 0**；报告 `.local/ex12a-focused-passed.3kozv2wj`。取消/领取竞争实际使用两个 PG backend，结果只允许“先取消而不可领取”或“先领取，取消后仍保留 worker 容量”。真实子进程测试使用 Java `-version`，其停止收据暂不提交时仍为 pending；取消入口不能清除该意图，原 journal 收到实际停止收据后才结算 CANCELLED。不是模拟 npm 成功，也不替代 EX12c 的正在执行进程取消验收。

**22:41:04，权限变异被捕获**：临时移除拿到项目/构建锁后的维护者复核，同一 9 项集成中恰有 `permissionIsRecheckedAfterTheProjectLock` 失败，0 错误/跳过，01:34、退出码 1；报告 `.local/ex12a-permission-mutation.9noaojgs`。随后恢复检查，运行全部单元及本片影响的准入/领取/结算集成，不重复刚通过且未改动的原生构建或 CLI 契约：

```sh
env JAVA_HOME=/Users/wubingheng/atoms-demo/.local/jdk17-comparison.LlvDQi/jdk-17.0.14.jdk \
  PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH \
  SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/atoms_demo_test?currentSchema=atoms_verify_20261021_ex10&connectTimeout=3&socketTimeout=5' \
  ./mvnw -B -o -Dit.test=ExecutionCancellationIT,ExecutionAdmissionIT,ExecutionBuildClaimIT,ExecutionBuildSettlementIT verify
```

自审：新增入口复用现有五秒事务、项目/构建行锁和 V023 事件触发器，没有引入迁移或依赖；所有权与维护者检查相互独立，归档的放行仅适用于取消。请求范围只含 UUID，不读取源码或接收命令/路径。排队释放与事件同事务，执行中/待核对状态不能仅凭 pending=false 就释放容量；返回字段沿用 BuildView，异常不包含 SQL/驱动信息。测试夹具仅保留其自身元数据，真实子进程通过原 journal 的停止收据收尾，没有清理旧未决现场。EX12a 通过后继续协调租约/调度，完整 EX12 与 C-EX04 仍不勾选。

**22:46:54，恢复后的全部 417 单元 + 42 相关集成全部通过，0 失败/错误/跳过，BUILD SUCCESS，05:19、退出码 0。** 集成为取消 9、准入 8、领取 10、结算 15；按 22:41:05 门槛仅计 90 份新单元报告及 4 份新集成报告，旧 XML 不计数。报告与汇总归档 `.local/ex12a-related-passed.zl8hkqun`（0700/0600）。EX11 的完整 377 集成和独立原生契约保持原记录，不将本次 42 项称为完整集成回归。

完成后的只读审计仍为新增 Agent 未决/pending/关联活动编辑租约及构建未决全部 0，旧协调锁无持有者，测试角色 CREATE 权限为 false；旧 Agent 未决和源码失败/中断记录不变。仅保存本地提交，未启用新模式、推送或部署。

EX12a 本地提交 `24fe978`；提交前 5 个私有值内存比对无命中，44 个本地文档链接及锚点有效，OAuth 草稿未纳入。

## 2026-10-24 · EX12b 独立构建协调租约

三个主要文件：`ExecutionCoordinatorLease`、对应单元测试和 `ExecutionCoordinatorLeaseIT`。只读核对现有源码/Agent/新旧构建锁编号后，选择未占用的会话锁 `718395204`。开工时既有只读审计无新增未决，数据库连接正常；一次 Docker 状态查询无响应，用其 exec 会话的 Ctrl-C 结束（退出码 130），未停止 Docker Desktop 或数据库。

**10:04:42 RED，缺少租约类的编译失败，18 处引用缺类，01:51、退出码 1**；没有运行测试。实现复用现有协调租约的独立连接模式，同时增加失效不可恢复、错误脱敏及 abort 失败不归还连接的约束，未修改旧模块。

**10:51:48，9 单元通过，4 集成中 2 错误、0 失败/跳过，01:34、退出码 1**。归档 `.local/ex12b-pool-failure.bw7a4isq`。真实 HikariCP 6.3.3 在 `abort` 后的 `ProxyConnection.close → clearWarnings` 报连接已关闭：一次来自新租约收尾，另一次来自共存测试自身连接的相同回收方式。没有把该异常归为数据库不可用，也没有忽略关闭错误。

核对固定版本官方 [HikariDataSource.evictConnection](https://raw.githubusercontent.com/brettwooldridge/HikariCP/HikariCP-6.3.3/src/main/java/com/zaxxer/hikari/HikariDataSource.java)、[ProxyConnection.close](https://raw.githubusercontent.com/brettwooldridge/HikariCP/HikariCP-6.3.3/src/main/java/com/zaxxer/hikari/pool/ProxyConnection.java) 与 [HikariPool](https://raw.githubusercontent.com/brettwooldridge/HikariCP/HikariCP-6.3.3/src/main/java/com/zaxxer/hikari/pool/HikariPool.java)：物理连接 abort 不等于池条目已移除；明确驱逐后 close 才按驱逐状态完成处理。修复为 abort → evict → close，不改池全局校验窗口、不升级依赖；真实夹具的自有连接也采用同样回收规则。已取得的句柄失效后不可恢复，unknown pool 在借连接前拒绝。依据同时包括 [PostgreSQL 17 会话锁](https://www.postgresql.org/docs/17/explicit-locking.html#ADVISORY-LOCKS) 与 [JDBC 17 abort](https://docs.oracle.com/en/java/javase/17/docs/api/java.sql/java/sql/Connection.html#abort(java.util.concurrent.Executor))。

**10:55:51 GREEN，11 单元 + 4 真实数据库集成全部通过，0 失败/错误/跳过，BUILD SUCCESS，01:06、退出码 0**；归档 `.local/ex12b-focused-passed.dpu2mtjj`。覆盖单一持有者、关闭后新租约、失锁后重新加锁仍不可恢复、仅断开自己的物理连接，以及源码/Agent/新旧构建六个独立锁与新协调租约共存。本次命令（backend）如下；新模式保持关闭，没有自动调度：

```sh
env JAVA_HOME=/Users/wubingheng/atoms-demo/.local/jdk17-comparison.LlvDQi/jdk-17.0.14.jdk \
  PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH \
  SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/atoms_demo_test?currentSchema=atoms_verify_20261021_ex10&connectTimeout=3&socketTimeout=5' \
  ./mvnw -B -o -Dtest=ExecutionCoordinatorLeaseTest -Dit.test=ExecutionCoordinatorLeaseIT verify
```

**10:59:06，回收变异被捕获**：仅对模拟连接的单测临时取消 `evict`，11 项中 3 项失败、0 错误/跳过，24.827 秒、退出码 1；归档 `.local/ex12b-eviction-mutation.lzibh6o4`。本次不运行真实数据库测试，避免将故意错误的回收策略用于真实连接。随后恢复驱逐，移除构造器的无用占位参数，再运行全部单元及相关回归。

**11:06:06，428 单元 + 28 相关集成全部通过，0 失败/错误/跳过，BUILD SUCCESS，04:53、退出码 0**。91 份单元报告、6 份集成报告按 11:00:15 新鲜度门槛核对，归档 `.local/ex12b-related-passed.0rjmb02a`（0700/0600）；未将旧完整回归 XML 混入计数。命令沿用上方 JDK/Node/schema 前缀，改为 `./mvnw -B -o -Dit.test=ExecutionCoordinatorLeaseIT,ExecutionCancellationIT,ExecutionBuildClaimIT,AgentCoordinatorLeaseIT,ProjectSourceCoordinatorLeaseIT,BuildCoordinatorLeaseIT verify`。

完成后的只读审计无新增 Agent 未决/pending/活动编辑租约或构建未决；旧失败、未决、源码中断记录保持原样，测试角色无 CREATE 权限。自审确认新类无 Spring 自动启动副作用，固定锁编号和限定查询，不接受请求提供的 SQL/路径/连接目标；异常无驱动 cause/suppressed。真实连接支持现有 Hikari，未知连接池在借出前拒绝；回收回调仅供可信内部接线和模拟测试，不能通过配置/HTTP 指定。没有修改旧协调器、平台依赖、私有 env 或个人配置。EX12c 及 C-EX04 仍未完成，仅本地提交，不推送或部署。

EX12b 本地提交为 `d8dd219`，随后继续 c1 队列处理。

## 2026-10-24 · EX12c1 等待任务处理

主要文件为 `ExecutionBuildQueue`、`ExecutionBuildRepository`、`ExecutionBuildQueueIT`，另因下述测试目标错误修正 `application-test.yml`，共四个。只处理未领取的队列元数据；无调度线程、工具、模型或 Git 调用。固定最多 8 条，按数据库创建时间及 ID 排序；候选须重新取得项目/构建行锁，核对不变身份、最新权限及原排队期限。取消优先于期限，归档/转让/维护者撤销结束等待任务；已领取及待核对任务不能借此释放 worker。

**11:18:50 RED**：缺少 `ExecutionBuildQueue` 的两处编译错误，54.704 秒、退出码 1，未运行测试。随后补实现；租约失效时统一返回不含底层原因的 `EXECUTION_STATE_UNAVAILABLE`，结束前的失锁观察使事务、事件与名额整体回滚，不重试提交结果不明的操作。

### 测试目标操作失误与修正

**11:21:05 的第一次聚焦运行误用了旧 `atoms` schema**：命令漏传 `SPRING_DATASOURCE_URL`，测试 profile 旧默认仍为 `atoms`。该次确为 7 单元 + 8 集成通过、38.225 秒、退出码 0，但不作为指定回归 schema 的通过证据。发现日志后尝试中断时进程已结束；不能表述为已阻止迁移。报告保存在 `.local/ex12c1-wrong-schema.lnsfvx9w`。

此错误实际在旧 `atoms` 应用了既有 V023/V024（从 V022 到 V024），并留下本次 15 条元数据夹具：11 CANCELLED、3 FAILED、1 TIMED_OUT，34 条事件，pending/占用名额均为 0。未启动任何任务进程。没有回退迁移、删除新旧记录或强制释放旧租约。只读核对三份历史未决 Agent 的状态、pending、停止证明缺失及基线，与历史记录一致：`atoms/f7e3d276…`、`pa32/af6755c5…`、`pa32b/2f263256…` 仍为 NEEDS_RECONCILIATION、ACTIVE/pending、缺停止证明、基线未变。当前取得行摘要留供后续比对；本轮没有运行前的三份行摘要，不能称为本轮逐字节前后比较。执行协调锁无剩余持有者，指定新 schema 未新增未决任务。

已将测试 profile 的默认 URL 修正到已批准的 `atoms_verify_20261021_ex10`，不修改私有 env、生产配置或数据库授权；新集成测试同时断言实际数据库与 schema。后续命令仍显式传入准确 URL。README 同步说明覆盖规则；旧发布 smoke 脚本另有显式 `atoms` 地址，本次未运行。该修正防止遗漏覆盖值继续落入旧目标，但不能防止操作者显式给错 URL。

**11:30:28，指定 schema 的聚焦 GREEN：7 单元 + 8 集成全部通过，零失败/错误/跳过，BUILD SUCCESS，01:19、退出码 0**；归档 `.local/ex12c1-focused-passed.cu_nj6js`。Flyway 确认目标已为 V024，无迁移执行。命令（backend）：

```sh
env JAVA_HOME=/Users/wubingheng/atoms-demo/.local/jdk17-comparison.LlvDQi/jdk-17.0.14.jdk \
  SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/atoms_demo_test?currentSchema=atoms_verify_20261021_ex10&connectTimeout=3&socketTimeout=5' \
  ./mvnw -B -o -Dtest=ExecutionSettingsTest -Dit.test=ExecutionBuildQueueIT verify
```

覆盖持锁延迟后的数据库时钟、调高配置不能延长旧排队期限、重复取消无新增事件、禁用后仅关闭路径可结束队列、陈旧候选不影响已领取/待核对任务、伪造 owner 被拒绝、更新后失锁整笔回滚，以及拒绝调用者已有事务。夹具不启动外部进程，保留记录，仅结束自身明确未执行的行。

**11:32:22，失锁变异被捕获**：临时移除事务结束前的 `requireLease()`，仅运行对应一个数据库用例，1 失败、0 错误/跳过，01:27、退出码 1。主断言发现该调用错误地成功返回；收尾另附租约关闭的固定异常，因为故意省略的观察使失锁直到关闭才被发现，连接仍经 abort/evict/close 处理。归档 `.local/ex12c1-lease-mutation.a56i6b83`。随后立即恢复检查，再运行全部单元及相关集成；未把故意错误的状态转换用于任何已有任务。

**11:38:38，恢复后的相关回归 GREEN：428 单元 + 39 集成全部通过，零失败/错误/跳过，BUILD SUCCESS，05:35、退出码 0**。按 11:32:40 新鲜度门槛核对 91 份单元和 5 份集成 XML，归档 `.local/ex12c1-related-passed.xf3nlvp_`（0700/0600），未混入历史报告。命令沿用上方准确 JDK/schema，另指定 Node 24.20.0 PATH，执行 `./mvnw -B -o -Dit.test=ExecutionBuildQueueIT,ExecutionCancellationIT,ExecutionBuildClaimIT,ExecutionAdmissionIT,ExecutionCoordinatorLeaseIT verify`。没有重跑未受本片影响的原生构建/CLI 契约，不把相关回归称为全部集成回归。

结束后的只读核对：指定 schema 无新增 Agent 未决、关联 pending/活动编辑租约或构建未决，旧源码失败/中断记录保留；测试角色仍无数据库 CREATE 权限。三份旧未决任务及租约的行摘要与本次误用后首次只读采样相同，误运行的 15 条任务/34 条事件也未再变化。执行协调锁无持有者。过滤进程清单未见残留 Java/Maven 测试进程；现有个人 Claude 进程未操作。

自审确认项目行 → 构建行的顺序与已有领取/取消相同，数据库约束保证 QUEUED 没有执行意图；固定 SQL 无请求拼接，查询最多 8 条。原身份匹配覆盖 owner、来源版本/树、幂等/请求摘要与创建时间；陈旧候选只读取已领取/待核对状态，不释放其容量。测试配置的变更限于 test profile，日常 env、个人配置与远程部署未改。c1 完成，c2 实际调度/线程/关闭、EX13 恢复以及运行/页面仍待实施，EX12/C-EX04 不勾选。

EX12c1 本地提交为 `867e559`，随后继续 c2 调度接线。

## 2026-10-24 · EX12c2a 调度核心与实际子进程取消

四个主要文件：`ExecutionCoordinator`、`ExecutionBuildRepository`、`ExecutionSchedulerIT`、`ExecutionSchedulerFixture`。启动先检查配置和源码就绪，再取得独立数据库协调租约；既有非 QUEUED 未完成任务直接阻断，不按 PID 接管或重新执行。通过可信内部工厂延迟打开执行根及 worker 资源，使用一个轮询线程和默认 1、最多 4 个工作线程。每次派发同时检查启动时容量、当前容量和原任务容量，最终仍由已有原子 claim 分配持久槽位；队列满员时继续处理到期任务。

线程池/有界交接依据 [JDK 17 ThreadPoolExecutor](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html)；生命周期 phase 预留在源码模块之后，依据 [Spring 6.2 SmartLifecycle](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/context/SmartLifecycle.html)。本片尚无 `@Component` 自动装配，工厂不是 HTTP 或生成源码可指定的执行入口；原生 worker/Spring 接线仍属于 c2b。

关闭先停止接收新调度；协调权仍有效时在短事务保存本协调者活动任务的取消标记，再中断并等待拥有者。数据库不可用/失锁不能作为继续运行自己进程的理由，也不能谎称取消意图已保存。线程结束后只取消仍排队的任务，数据库存在未决执行时保留资源；原始停止收据被可靠落库后才能再次完成关闭。轮询线程不等待自己，外部关闭等待工作线程与轮询结束后再释放根及租约；重复关闭不会追加状态事件。

**12:06:11 RED**：新协调类/内部资源契约不存在，8 处编译错误，01:54、退出码 1，未运行测试。随后实现；**13:13:43 聚焦报告为 7 单元 + 10 集成全部通过，零失败/错误/跳过**，归档 `.local/ex12c2a-focused-passed.jaxqnhyg`。该命令的工具会话被用户中断，接续时后台执行已结束，不能恢复原始命令退出码；依据新鲜 XML 与 Failsafe 汇总记录通过，最终成功退出由后续完整单元/相关回归确认。恢复前只读进程清单未见 Java/Maven 测试残留，没有重复运行这一条聚焦命令。

十项集成场景：禁用/源码未就绪无资源打开；默认单并发及持久取消；四个项目并行且第五个等待；提高当前配置不绕过旧任务容量；活动 worker 满员时队列仍按期结束；第二协调者不能接管队列或打开根；启动前已领取/待核对任务原样阻断；释放本测试自己的数据库会话锁后停止实际子进程且不更改未领取任务；禁用/源码停止后收尾；真实子进程已经停止但完成结果未写入时保留资源，只有原 journal 收到原停止收据后才释放。夹具通过平台 `ExecutionProcess` 运行真正的 JVM，记录持久意图和真实停止证据；它不会产生 SUCCEEDED 或伪造 npm/Maven/JAR 验收。

**13:43:44，未决保留变异被捕获**：仅移除最终释放前的未决检查，执行一个已持有真实停止收据的夹具场景，1 失败、0 错误/跳过，01:05、退出码 1；断言发现 close 错误地成功返回。归档 `.local/ex12c2a-retention-mutation.1l9w5mhd`。夹具通过原 journal 记录自身已经持有的停止收据，未遗留活动进程或处理旧审计；随后立即恢复检查，开始全部单元及相关回归。

**13:49:05，428 单元 + 54 相关集成全部通过，零失败/错误/跳过，BUILD SUCCESS，03:29、退出码 0**。按 13:44:45 新鲜度门槛核对 91 份单元和 9 份集成报告，归档 `.local/ex12c2a-related-passed.9po36i_e`（0700/0600）。命令使用既有隔离 JDK、Node 24.20.0 PATH 和显式 `atoms_verify_20261021_ex10` URL，执行 `./mvnw -B -o -Dit.test=ExecutionSchedulerIT,ExecutionBuildQueueIT,ExecutionCancellationIT,ExecutionBuildClaimIT,ExecutionAdmissionIT,ExecutionCoordinatorLeaseIT,AgentCoordinatorLeaseIT,ProjectSourceCoordinatorLeaseIT,BuildCoordinatorLeaseIT verify`。未混入历史报告，也没有将旧模式未决审计清零。

自审：worker 的等待队列有界，持久任务仍是等待来源；内存任务不授予重复 claim 权限。关闭不持有 worker 需要的资源锁等待线程，不在轮询线程中等待自身；元数据提交失败不重试。工厂只为可信内部装配和测试使用，真实进程意图/停止收据仍由原组件管理。没有新增数据库迁移、模型/SMTP 调用或私有配置变更；c2b 原生装配与 EX13 恢复尚未完成，本片不勾选整个 EX12/C-EX04。

回归后的只读核对：当前 schema 的 Agent 未决、关联 pending/活动编辑租约与构建未决均为 0；执行协调锁没有残留持有者。三份旧审计行摘要与 c1 保留采样一致，旧源码失败/中断现场及 c1 误运行记录未改变，测试角色仍无数据库 CREATE 权限。没有删除记录、按 PID 补发证明或操作个人 Claude 进程。

调度核心本地提交为 `07fe636`。

## 2026-10-24 · EX12c2b1 源码生命周期就绪

`ProjectSourceReadGateway.readyForExecution()` 只查询开关与可选协调器当前运行状态；禁用、缺实例、未启动、已停止及配置/提供者异常均返回 false，不创建目录、读版本或取得执行权。旧构造及两个 read 重载保留。测试夹具通过 supplier 观察真实源码协调器，为下一片原生装配提供环境，不增加 HTTP 能力。

13:57:44 RED：缺就绪方法/构造的 11 处编译错误，退出码 1。第一次实现后测试因沙箱阻止 Mockito JVM attach 报 2 错误；获准沙箱外执行后发现测试 Spring 环境未开启条件 bean，修正测试上下文开关并断言 bean 已注册，没有修改生产开关或个人 Java 配置。

**14:00:51，4 单元 + 8 源码读取集成全部通过，零失败/错误/跳过，BUILD SUCCESS，01:10、退出码 0**。使用既有隔离 JDK 和显式 `atoms_verify_20261021_ex10` URL，执行 `./mvnw -B -o -Dtest=ProjectSourceReadinessTest -Dit.test=ProjectSourceReadGatewayIT verify`。验证生命周期变化、失败关闭、Spring 可选提供者以及指定历史版本、越权、预算、事务和存储漂移拒绝。该片不启动原生构建；EX12/C-EX04 继续待 b2 装配与实际 worker 验收。

本地提交 `f145042`；报告归档 `.local/ex12c2b1-passed.wml1jju9`，0700/0600。

## 2026-10-24 · EX12c2b2a 原生资源工厂

四个主要文件实现懒加载原生 worker 装配。私有测试库注册表只由维护者配置，最多四个规范项目 UUID；严格 JSON、绝对规范路径、平台实际库/角色排除沿用已有私有配置校验。源码/编辑/Agent/执行根作为受保护位置，配置通过后才打开排他执行根。资源检查复核就绪、根身份和配置一致性，关闭只释放自身句柄；没有在构造 bean 时读私有配置或连接测试库。

14:02:50 缺类 RED（2 编译错误、退出码 1）；14:04:19，19 项相关单元通过。**14:05:20 注册表漂移变异被捕获**：临时移除持有期间的注册表一致性检查，1 项测试失败、无错误/跳过，资源由原 try-with-resources 关闭；归档 `.local/ex12c2b2a-registry-mutation.t8af8m3c`，随后恢复校验。

**14:07:10，23 单元 + 8 源码读取集成通过，零失败/错误/跳过，BUILD SUCCESS，01:36、退出码 0**。沿用隔离 JDK/显式批准 schema，命令为 `./mvnw -B -o -Dtest=ExecutionEnvironmentTest,ExecutionTestDatabaseTest,ExecutionSettingsTest,ProjectSourceReadinessTest -Dit.test=ProjectSourceReadGatewayIT verify`。验证禁用/源码未就绪无副作用、无效/过量/重复注册、真实临时根排他与释放、运行期间配置变化、平台身份及受保护目录拒绝、事务内拒绝和异常脱敏。新工厂的测试没有启动 npm/Maven 或实际测试库连接，不将这些结果记为原生构建验收；该验收由 b2b 完成。未更改私有 env、个人工具配置或历史审计，没有推送/部署。

原生工厂本地提交 `a701aa6`，报告归档 `.local/ex12c2b2a-passed.kbc7nl9q`，0700/0600。

## 2026-10-24 · EX12c2b2b Spring 调度与原生组合验收

增加执行开关条件 bean 和实际源码/原生工厂构造接线，保留内部夹具构造。生命周期先启动源码、后启动执行，关闭顺序相反；不开开关不注册协调器。原生契约改为真实源码协调器 + Spring 执行上下文，通过 `ProjectExecutionService.submit` 入队，等待持久结果，不再直接调用同步 worker。测试启动前要求批准 schema 的源码操作没有未决项，避免新临时根恢复其他测试现场；子上下文不接管外部连接池和源码环境的关闭责任。

14:09:27 装配 RED：两项测试因缺条件注册/原生构造发生依赖错误。实现后，14:11:28 定向回归中 11 单元、18 集成通过，但默认串行场景等待第二任务失败。只读检查失败 owner `3c340fc1-491e-4258-ad9b-6020f1275272` 的记录：第一 `4a884282-4d06-4f00-b6c2-bc048f267fce` 与第三 `1500bf85-fd75-4bdd-9601-26e365ed8f5b` 各有两条 NPM_CI 阶段事件，第二 `10d91ac1-6f17-4a3d-b2f6-fada28e78f67` 没有执行事件，三者最终均由原关闭流程取消、pending=false。确认容量在扫描中途释放导致后项超车，不是增大测试等待即可解决。失败报告 `.local/ex12c2b2b-scheduler-failure.yuu0jj_0` 保留。

修复为一次扫描遇到容量阻塞后不再派发后项，下一轮从最早等待项重试；仍继续对后项结算到期、取消和权限撤销，不让清理因满员停止。保持原 20 秒测试等待、共享任务期限与并发上限，未修改旧审计或测试成功标准。后续恢复结果及实际工程构建证据另记。

**14:16:14，恢复后 2 项 Spring 装配单元 + 10 项调度集成全部通过，零失败/错误/跳过，BUILD SUCCESS，01:13、退出码 0**；归档 `.local/ex12c2b2b-order-fixed.9h2ktz5u`，0700/0600。随后开始全部单元、相关集成及实际原生组合验收。

本轮原生组合命令（同一获准 JDK、工具、schema 和私有测试库；外层 Maven 离线，实际 worker 的依赖安装仍按既有批准策略访问仓库）：

```sh
env JAVA_HOME=/Users/wubingheng/atoms-demo/.local/jdk17-comparison.LlvDQi/jdk-17.0.14.jdk \
  PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH \
  SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/atoms_demo_test?currentSchema=atoms_verify_20261021_ex10&connectTimeout=3&socketTimeout=5' \
  ./mvnw -B -o -Pproject-execution-contract \
  -Dexecution.test-config-directory=/Users/wubingheng/atoms-demo/.local/project-execution-acceptance.x5cvui \
  -Dexecution.contract-directory=/private/tmp/atoms-ex11-contract.APT7Jb \
  -Dexecution.contract-node=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin/node \
  -Dexecution.contract-npm=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin/npm \
  -Dexecution.contract-java=/Users/wubingheng/atoms-demo/.local/jdk17-comparison.LlvDQi/jdk-17.0.14.jdk/bin/java \
  -Dexecution.contract-maven=/Users/wubingheng/.m2/wrapper/dists/apache-maven-3.9.11/a2d47e15/bin/mvn \
  -Dit.test=ExecutionSeedBuildContractIT,ExecutionSchedulerIT,ExecutionBuildQueueIT,ExecutionCancellationIT,ExecutionBuildClaimIT,ExecutionAdmissionIT,ExecutionCoordinatorLeaseIT,AgentCoordinatorLeaseIT,ProjectSourceCoordinatorLeaseIT,BuildCoordinatorLeaseIT,ProjectSourceReadGatewayIT verify
```

首个真实任务 `1d1ca3b5-d0ce-4be9-998f-12d4f75397b3` 已由 Spring 调度构建为 SUCCEEDED，耗时 419,139 ms；来源 `ca9125aa-5dc7-40ac-8b5f-a2fcb42402c3`。前端 15 项、Java 2 项测试通过，JAR 24,023,687 字节，SHA-256 `f90d09b47d7688619cd03a0742305e81a86458c205524f622846fe86410b8bf3` 与清单/数据库一致。实际根为 `/private/tmp/atoms-ex11-contract.APT7Jb/seed-build-15860576047448330585`。后续失败版本、关闭和回归尚在本命令内验证，此处不提前记整体通过。

后续原生契约于 **14:35:44 完整通过**，耗时 997.8 秒：前端失败构建 `a4e3749f-05c9-4356-b654-809f0172b319` 在 UNIT_TEST 失败，188,846 ms；报告实际 16 项测试中 1 项失败。Java 失败构建 `c797a7ce-b8a5-4713-9a7b-5961de84e1ae` 在 MAVEN_PACKAGE 失败，354,021 ms；编译输入清单包含故意引用缺失类型的 `AcceptanceFailure.java`。两个失败任务均无新封存产物，旧成功数据库行与 JAR 摘要保持不变。源码版本/静态首页/Java 类/迁移均与成功产物核对，测试库身份与 relation 数量未变；Spring 上下文关闭后实际重新取得执行根文件租约和独立数据库协调租约。

**14:37:51，全部 439 单元 + 63 项相关集成通过，零失败/错误/跳过，BUILD SUCCESS，20:07、退出码 0**。按 14:17:00 新鲜度门槛仅统计本次 94 份单元、11 份集成 XML；归档 `.local/ex12c2b2b-native-passed.blyztie_`，0700/0600。63 项包含上述真实原生组合、调度/队列/取消/领取/准入、四类协调租约和原源码读取；不是所有集成测试的完整回归，也未重跑无变更的离线 CLI 契约。

自审确认三个主要实现/测试文件内完成原生 Spring 接线和扫描公平性修复，未更改默认开关、工具/任务预算或真实模型预算。EX12 与 C-EX04 已完成；EX13 崩溃恢复、本机常驻应用、HTTP 业务验收及工作台仍待继续，不能把本次构建 JAR 当作网页已切换新链路或应用已部署。

收尾只读审计：当前批准 schema 的新 Agent 未决/pending/活动编辑与构建未决均为 0，执行协调锁持有者为 0；测试角色仍无建 schema 权限。三份旧未决审计的任务/租约 MD5 与原记录一致，旧源码失败现场与 c1 误运行的 15 个任务、34 条事件保持不变。未删除或强行结算未知记录，未读取旧 PID 补发停止证明。

## 2026-10-24 · EX13a 中断构建的元数据恢复

增加内部 `ExecutionRecovery` 与 9 项真实 PG 集成测试。仅在独立协调租约、源码就绪和固定配置下核对原构建绑定；短事务统一项目→构建加锁。无意图、或非 PACKAGING 阶段完整成对结束的执行可结算为 FAILED/EXECUTION_INTERRUPTED，已请求取消则为 CANCELLED。pending、未配对历史和 PACKAGING 保留容量转为 NEEDS_RECONCILIATION；原未决、终态和排队任务不被重复处理。没有重新执行工具、重建产物句柄或按旧 PID 寻找进程。

14:48:22 RED 为缺少恢复类，另有一处测试断言 API 拼写错误，已修正。14:49:56 首轮 2 单元 + 9 集成通过。14:51:08 临时删除历史核对条件，针对 pending 标志与执行历史不一致的测试失败（预期 NEEDS_RECONCILIATION，实际 FAILED），证明确实能发现错误释放；变异归档 `.local/ex13a-history-mutation.ai9d68fy`，随后恢复源码。

**14:53:44，2 单元 + 52 相关集成通过，零失败/错误/跳过，BUILD SUCCESS，02:19、退出码 0**。命令沿用隔离 JDK 与显式批准 schema：`./mvnw -B -o -Dtest=ExecutionCoordinatorSpringTest -Dit.test=ExecutionRecoveryIT,ExecutionBuildSettlementIT,ExecutionProbeJournalIT,ExecutionFrontendJournalIT,ExecutionJavaJournalIT verify`。本轮只增加恢复结算，启动接线与真实 JVM 崩溃窗口分别由 EX13b/c 完成，尚不标记整个 EX13 完成。私有配置、旧审计和原网页功能不变。

## 2026-10-24 · EX13b 启动先恢复再调度

EX13a 本地提交 `93d4994`，报告 `.local/ex13a-passed.yw3k2aym`。EX13b 修改协调器及调度集成测试两个主要文件：获得协调锁、打开并检查原生资源，再核对旧任务；恢复前后复验源码就绪、配置与资源，仍有未决则不创建 worker 或启动队列。启动失败时只释放本实例刚取得的资源，不取消既有队列；已经开始运行的协调器仍执行原有停止与未知资源保留规则。

14:55:14 三个新启动场景 RED（3 失败、0 错误/跳过）。实现后 **14:56:49，7 单元 + 29 相关集成全部通过，零失败/错误/跳过，BUILD SUCCESS，01:16、退出码 0**。命令 `./mvnw -B -o -Dtest=ExecutionCoordinatorSpringTest,ExecutionEnvironmentTest -Dit.test=ExecutionSchedulerIT,ExecutionRecoveryIT,ExecutionBuildQueueIT verify`，同一显式 schema 与隔离 JDK。恢复未执行任务后下一队列项实际启动；原未决阻断时排队项不变；目录打不开时不修改旧任务。EX13c 的真实 JVM 异常退出场景尚待完成。

## 2026-10-24 · EX13c 真实 JVM 崩溃与重新启动

EX13b 本地提交 `feddef1`，报告 `.local/ex13b-passed.yfoeszar`。本片只增加测试夹具并扩充恢复集成测试两个主要文件。独立测试 JVM 持有真实数据库/目录租约，使用原领取、阶段日志和产物封存/结算代码，在五个指定节点调用 `Runtime.halt(71)`，确认关闭钩子未运行。随后启动另一个全新 JVM 重新获取租约并执行恢复，最后原测试进程重复恢复核对幂等。恢复进程不会取得原任务或封存产物的内存句柄。

14:57:55 缺失夹具类 RED（8 编译错误）；14:59:10 修正测试异步回调需使用 Callable 承载 IOException 的编译错误。15:00:33 初次真实子 JVM 未到达注入节点（1 失败、4 错误），15:02:37 增加仅输出阶段/异常类名的诊断后确认五项都被执行目录校验拒绝。夹具原把目录放在代码仓库内且位于子进程 cwd 下，违反已有目录排除规则。只把夹具改到仓库外私有临时目录，并使用与执行根平级的进程 cwd；没有放宽生产目录保护。失败目录及固定诊断保留于 `backend/target/execution-crash-*`。

**15:04:21，2 单元 + 14 恢复集成通过，零失败/错误/跳过，BUILD SUCCESS，57.939 秒、退出码 0**，归档 `.local/ex13c-first-pass.5nn7bqbc`。五个真实窗口：

| 窗口 | 测试构建 ID | 恢复结果 |
|---|---|---|
| 启动命令前 | `6401d0c4-8c54-43a8-8f6f-958afc801640` | FAILED / EXECUTION_INTERRUPTED |
| 命令停止、结果尚未记录 | `2917aac3-e013-483f-9b36-7105f8c71da6` | NEEDS_RECONCILIATION，pending 与容量保留 |
| 阶段完成后 | `5e62c404-5083-41d3-a78d-75239eb8a709` | FAILED / EXECUTION_INTERRUPTED |
| 产物封存、DB 成功未提交 | `d6163480-8361-4951-812f-4a6b5de824b7` | NEEDS_RECONCILIATION，不发布摘要，文件原样保留 |
| DB 成功提交、响应未返回 | `5a8e22d2-86ab-4ebd-9b38-16ad8b29aeec` | SUCCEEDED，行、事件、文件均不变 |

证据保留在本机临时目录的 `atoms-ex13-crash-*`，包含窗口、重启状态、verified 构建 ID 以及相关构建/封存文件。测试输入凭据只放 JUnit 私有临时文件，未进入这些证据目录、argv 或日志。结果丢失场景由测试宿主持有真实外部命令停止收据，崩溃 JVM 无法收到该收据；测试核对完后仅凭该原收据结算自身夹具，既有未知记录不变。产物使用受控字节封存夹具验证事务窗口，不将它当作可执行应用；实际 npm/Maven 工程验收仍是 EX11/12 的独立证据。本轮没有新数据库迁移，最终相关回归另记。

最终回归第一次于 15:06:29 停在单元阶段（439 项中 1 错误）：命令未带原验收用 Node 24 PATH，拾取终端默认 Node 20.15.0，旧 `BuildEnvironmentTest` 正确拒绝。错误报告保留于 `.local/ex13-final-tools-failure.xos3y4ur`。只为重跑命令显式指定已安装 Node 24.20.0，不改测试门槛、生产代码或全局配置。

**15:29:52，全部 439 单元 + 121 项相关集成通过，零失败/错误/跳过，BUILD SUCCESS，04:30、退出码 0**。15:25 新鲜度门槛核对 94 份单元、15 份集成 XML，归档 `.local/ex13-final-passed.dfp4qxeh`（0700/0600，含计数摘要）。重跑命令：

```sh
env JAVA_HOME=/Users/wubingheng/atoms-demo/.local/jdk17-comparison.LlvDQi/jdk-17.0.14.jdk \
  PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH \
  SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/atoms_demo_test?currentSchema=atoms_verify_20261021_ex10&connectTimeout=3&socketTimeout=5' \
  ./mvnw -B -o \
  -Dit.test=ExecutionRecoveryIT,ExecutionSchedulerIT,ExecutionBuildQueueIT,ExecutionBuildSettlementIT,ExecutionProbeJournalIT,ExecutionFrontendJournalIT,ExecutionJavaJournalIT,ExecutionBuildWorkerIT,ExecutionCancellationIT,ExecutionBuildClaimIT,ExecutionAdmissionIT,ExecutionCoordinatorLeaseIT,AgentCoordinatorLeaseIT,ProjectSourceCoordinatorLeaseIT,BuildCoordinatorLeaseIT,ProjectSourceReadGatewayIT verify
```

本命令未开启 `project-execution-contract`，列表中的 `ExecutionBuildWorkerIT` 被原有标签规则排除，不计入 121 项；实际通过的是其余 15 个集成类。原生工程构建和离线 CLI 契约没有变更，本轮未重复执行，也不以本次字节夹具替代此前真实 JAR 验收。

收尾只读核对：批准 schema 的新 Agent 未决/pending/活动编辑和构建未决均为 0，执行协调锁持有者为 0；三份历史未决审计 MD5、旧源码失败现场和此前误运行的 15 任务/34 事件不变。自审确认恢复不执行命令、不清理未知文件、不按旧 PID 获取停止证明；参数化 SQL、最多 21 条执行历史、短事务和原租约检查保持有界。私有 env 五个敏感值与本轮差异核对零匹配。EX13 完成，下一项 EX14；没有推送、部署、真实模型调用、SMTP 调用或修改个人工具配置。

## 2026-10-24 · EX14a 共享执行容量

V025 增加由任务事务维护的 `execution_task_slots`：构建和后续依赖准备共用项目、队列及 worker 唯一占位；旧未决构建原样回填。任务转执行/结算同步投影，事务回滚恢复原占位，禁止直接删除占位绕过任务状态。原构建准入和领取改为统计共享表；尚未提交准备任务或执行 npm。

- RED：16:01:01，新用例因缺少共享表失败，7 单元通过、1 集成错误。
- GREEN：16:04:08，7 单元 + 45 相关集成全部通过，零失败/错误/跳过，退出码 0。覆盖准入双连接、动态降容量、领取、调度及真实 JVM 恢复；新增用例核对占位随任务迁移、结算回滚和审计保留。
- 命令：项目隔离 JDK 17 + Node 24 PATH，显式 `atoms_demo_test?currentSchema=atoms_verify_20261021_ex10`，`./mvnw -B -o -Dtest=ExecutionSettingsTest -Dit.test=ExecutionAdmissionIT,ExecutionBuildClaimIT,ExecutionSchedulerIT,ExecutionRecoveryIT verify`。
- 本地日志 `.local/ex14a-red.log`、`.local/ex14a-green.log`；不修改日常库、旧 schema、模型配置或远程部署。

## 2026-10-24 · EX14b 依赖准备准入

V026 新增独立 `dependency_preparations`，固定基线版本、修订号、完整树摘要、清单/原锁/准备批准摘要，并通过复合外键绑定同一个源码编辑租约。提交先在事务外读取指定版本和私有批准文件，再在项目锁下重新检查身份、配置与源码基线，把编辑租约、任务和共享占位一并提交。重复已落库的请求不再读取源码或批准文件；无文件副本、npm 执行、模型调用或源码发布。

内部提交和查询沿用默认关闭及维护者限制，没有新增 HTTP 入口。准备占用源码模块原有编辑租约，因此与 Agent 编辑、源码恢复或新版本提交互斥；构建和准备通过共享占位互斥、共用队列及 worker 容量。动态降低容量时按实际占位总数检查，不能利用低编号空槽超额。批准文件的捕获不是执行许可，EX15 执行时仍须核对该固定摘要；新锁图也不会自动获得构建批准。

局部证据：缺少服务时红灯编译失败；16:09:51 基础 2 项集成通过；16:13:56，13 单元 + 10 准入集成通过。双连接测试确认两个不同 PG backend 同时争抢最后名额，另覆盖同键重放、源码变更/恢复标记、权限及配置变化、写入后抛错回滚，以及不可改写的批准和租约绑定。16:20:05 临时恢复构建独占计数后，降容量用例按预期失败（本应拒绝却接受）；正确实现已恢复，日志及 XML 保留于 `.local/ex14-capacity-mutation.kf5r8_7r`。

最终回归：**16:29:39，全部 439 单元 + 156 项相关集成通过，零失败/错误/跳过，BUILD SUCCESS，退出码 0，耗时 7:30**。94 份本轮 Surefire XML、19 份 Failsafe XML 和各阶段日志归档于 `.local/ex14-final-passed.u2l5740j`，目录 0700、文件 0600，仅统计本次运行新报告。使用项目隔离 JDK 17、Node 24 PATH 和批准的 `atoms_verify_20261021_ex10` schema；未启用真实原生构建/CLI 契约 profile，不把本次准入回归视为再次生成 JAR。

```text
./mvnw -B -o -Dit.test=ExecutionPreparationAdmissionIT,ExecutionAdmissionIT,ExecutionBuildClaimIT,ExecutionSchedulerIT,ExecutionRecoveryIT,ExecutionBuildQueueIT,ExecutionBuildSettlementIT,ExecutionProbeJournalIT,ExecutionFrontendJournalIT,ExecutionJavaJournalIT,ExecutionCancellationIT,ExecutionCoordinatorLeaseIT,ProjectSourceReadGatewayIT,ProjectSourceEditLeaseIT,ProjectSourceEditGuardIT,ProjectSourceEditAcceptanceIT,ProjectSourceEditCancellationIT,ProjectSourceRecoveryIT,ProjectAgentAdmissionIT verify
```

收尾只读核对：V025/V026 仅在批准 schema 生效；累积 17 条准备任务审计保留，未完成/pending、关联活动编辑、共享槽、投影不一致和协调锁均为 0。原三份未知 Agent 审计摘要、旧源码失败记录及原误运行的 15 个构建/34 条事件不变；无 Java/Maven 遗留进程。自审核对参数化 SQL、输入/读取/事务上限、权限二次检查、复合外键与触发器占位一致性；五项私有 env 敏感值与本轮差异核对零匹配。

EX14 完成。真实 npm lock-only、停写核对、只接受锁文件变化及发布新版本属于 EX15，准备任务的调度/取消/重启恢复接线属于 EX16。没有推送、部署或调用模型/SMTP；日常 `.env.local`、个人工具配置和旧 OAuth 草稿未改动。

## 2026-10-24 · EX15a 封存候选的锁文件校验

源码编辑网关增加有界 `readEdit` 和带最终候选校验的 `sealAndAccept` 重载；旧 Agent 接口保持兼容。校验发生在真实停写证明确认之后、创建发布操作或返回无变更之前，读取的是已经封存的候选。验证器拒绝时不发布；验证后原工作副本再变化，也不能改变将要保存的候选内容。

`ExecutionNpmPolicy.checkPrepared` 比较基线与候选的全部路径、文件模式和字节，只允许普通文本模式的 `frontend/package-lock.json` 新增或变化；新锁必须与清单匹配，且满足 registry、完整性及依赖图检查。返回独立的 `Prepared` 结果，不复用构建批准类型；仅有准备批准时，合法的新锁仍不能通过 `checkBuild`。

16:43:26，缺少新接口的红灯编译失败；16:46:28，12 单元 + 22 相关集成全部通过，零失败/错误/跳过。16:49:15 移除文件集合检查后，“额外源码不得进入候选”的测试按预期失败，已恢复并重新验证。日志为 `.local/ex15a-red.log`、`ex15a-green.log`、`ex15a-mutation.log`、`ex15a-restored.log`。数据库仍使用批准 schema，无新迁移、模型或 SMTP 调用；本片不代表实际 npm 准备 worker 已完成。

## 2026-10-24 · EX15b 实际 npm 锁文件与源码发布

新增固定的 lock-only 命令适配：在源码副本根目录启动一个受控 Node 进程，先核对 Node 24.20.x/npm 11.19.0，再通过 `npm install --prefix frontend --package-lock-only --ignore-scripts --no-audit --no-fund` 生成锁。两次子调用同属该进程，返回根目录身份的停止证明；不使用 shell，HOME、npm 配置及缓存均为执行任务私有目录。旧 Agent 无验证器入口继续走原发布路径，不因新校验接口额外读取候选；新入口仍校验最终封存内容。

命令语义依据 [npm 11.19.0 install](https://raw.githubusercontent.com/npm/cli/v11.19.0/docs/lib/content/commands/npm-install.md)、[prefix 配置](https://raw.githubusercontent.com/npm/cli/v11.19.0/docs/lib/content/using-npm/config.md)和 [Node 24.20.0 child_process](https://raw.githubusercontent.com/nodejs/node/v24.20.0/doc/api/child_process.md)。代码检查与真实测试不代表同 UID 恶意进程隔离。

受控 CLI 的真实 Node 父子进程覆盖正确参数和隔离环境、版本不符不执行安装，以及版本探测中取消后的停止证明；17:00:13，10 项相关单元通过。17:05:07，显式 `project-execution-contract` 的 3 单元 + 1 实际 npm/source 集成通过，耗时 2:32；没有假 npm runner，没有生成或启动 JAR，也没有访问业务数据库。结果如下：

- 完整种子移除锁后作为基线，真实 npm 只新增 `frontend/package-lock.json`，项目内未生成 `node_modules`。250 个依赖条目通过来源、完整性和清单一致性检查。
- 项目 `a34e8fa4-2353-4055-8214-58469c3c2b06`，基线 `8b81db1a-ce1c-42c3-9167-88a916eed2b7`，新源码版 `44f41410-50d9-4398-84d4-d89482e311c6`；旧基线摘要不变。
- 新锁 SHA-256：`19f70f91a7c9f65b0d794e9e8abd8d72adfb2fbaf61f50c441c2b8290862c8cc`。准备批准不含锁摘要时，构建检查仍拒绝新锁；测试明确更新源码外批准后再次运行，返回无变更，项目仍为三版（初始化、缺锁基线、准备结果）。
- 原始现场保留在 `/private/tmp/atoms-ex11-contract.APT7Jb/lock-preparation-11705763089731212365`；报告/日志私有归档 `.local/ex15b-native-passed.5zu7xobm`。

这是命令、源码网关和 npm 策略的真实组合验收。准备任务的领取、执行意图/状态结算及 worker 尚待 EX15c 接入；完整任务路径的真实契约仍是 EX15d，不提前勾选 EX15 或 C-EX05。

17:11:41，全部 **444 单元 + 68 项相关集成通过**，零失败/错误/跳过，BUILD SUCCESS、5:20、退出码 0。95 份新单元报告、14 份相关集成报告与日志私有归档 `.local/ex15ab-compat-passed.h6qe578p`；覆盖源码编辑、租约、停写、发布期限、恢复及旧 Agent 准入/执行/取消/恢复。旧入口避免额外候选读取的兼容修正单独提交 `2620634`。

核对固定版本 npm install 文档发现，它也可能以 `yarn.lock` 为解析输入。17:12:43 新拒绝用例先红灯（1 失败）；随后在已有清单扫描中明确拒绝该文件。17:16:39，**16 单元 + 10 项准备准入集成通过**，零失败/错误/跳过，BUILD SUCCESS、1:49、退出码 0；归档 `.local/ex15b-policy-passed.n50hvmo7`（0700/0600）。此定向运行与前一轮 444 单元分开记载，不声称执行过 445 项全单元回归。

收尾只读审计：当前 schema 累计 38 条准备记录均已结束，pending、活动编辑、共享槽、投影不一致与协调锁均为 0；新 Agent/构建没有未决执行。三份历史未知执行的任务/租约摘要、旧源码失败现场及误运行的 15 条任务/34 条事件不变。自审确认固定 argv、不继承个人环境、校验最终封存内容、真实父子进程停止证明和新锁批准边界；命令、策略及测试共 5 个主要文件。未推送、部署、调用模型/SMTP或修改私有 env；准备后台任务链路仍待下一片。

## 2026-10-24 · EX15c1 准备任务一次性领取

新增内部 `DependencyPreparationClaim`：项目、准备任务、共享容量按原锁序处理；只有持有任务对应的原源码编辑能力，才能在短事务把 QUEUED 转为 PREPARING。`ProjectSourceEditGateway.requireUnstartedEdit` 在源模块内部复核所有权、基线、租约取消及从未执行/发布的事实，不读取文件或授权进程启动。重复领取不返回第二份执行权；原任务与当前配置容量取较小值，计入构建和准备的全部活动占位。等待锁期间到期或配置撤销会回滚，不刷新排队期限。

17:33:37，新增测试因缺少领取类而红灯；17:39:56，**16 单元 + 32 项相关集成通过**，零失败/错误/跳过，BUILD SUCCESS、3:02、退出码 0。包括准备领取 8 项、原准备准入 10 项、原构建领取 10 项、源码租约护栏 4 项。双连接竞争同任务及跨类型最后槽位、容量缩减、取消/缺少能力/权限变化、等待中超时和数据库异常后回滚均已验证。日志 `.local/ex15c1-red.log`、`.local/ex15c1-green.log`，报告私有归档。

自审：仅 3 个主要文件；源码事实通过门面读取，未增加跨模块源码 SQL；固定参数 SQL、五秒事务和三秒锁等待保持有界；数据库错误不输出驱动详情。此片仅领取任务，未增加后台线程、执行意图或进程启动，EX15c2/c3 仍待完成。

## 2026-10-24 · EX15c2 执行意图与源码结算

`DependencyPreparationLifecycle` 在短事务中同时保存源码执行意图与准备任务 RESOLVING/pending；只有原进程拥有者提供的真实停止证明才能清除两处 pending。没有停止证明时保留名额；源码操作尚未终结时进入待核对。ACCEPTING 后按源码门面的实际事实结算，不能凭调用方构造的 NoChanges DTO 宣告成功。已提交版本优先于迟到的取消、超时、配置禁用或账号归属变化。

源码门面新增 `noChangesFact`（原编辑能力已完成候选校验的内存事实）及 `releaseSettledEdit`（原能力的清理操作）。后者沿用源码租约仓库对活动执行/未完成源码操作的拒绝，只允许释放已停止或从未执行的本任务租约；不授予新写入或进程接管权。准备任务终态、槽位释放与源码租约释放同事务，两个模块的内存能力都在提交后遗忘。终态写入失败时数据库与内存能力均保留，测试注入的已知回滚可复核，生产调用不盲目重试不确定提交。

17:45:20 初始红灯为缺失生命周期/清理接口，同时修正测试 stderr 回调应返回 Void 的编译错误。17:50:53，**16 单元 + 34 相关集成通过**，零失败/错误/跳过；归档 `.local/ex15c2-initial-passed.yf57khb2`。初始生命周期 8 项覆盖真实 JVM 停止收据、错误任务收据、取消/权限撤销、真实 Git 发布及开始/停止/终态写入的原子回滚。

自审补充未知结果反例：17:52:18，1 集成明确失败（期望 NEEDS_RECONCILIATION，实际 FAILED）。修正为即使 pending 已清除，显式进程/源码结果未知仍保留容量；状态数据库不可用不能作为普通失败再次结算。再补实际源码停在 BEFORE_PUBLISH 的场景：释放被拒绝、超时保留容量，随后真实提交成功可结算为 SOURCE_SAVED。

17:55:42，**16 单元 + 22 相关集成通过**，零失败/错误/跳过，BUILD SUCCESS、退出码 0。生命周期 10 项、源码发布/取消 8 项与原 Agent 生命周期 4 项；报告和日志私有归档。此片 4 个主要文件，无新增依赖或数据库迁移；实际工具仍由下一片 worker 组合，尚无自动准备调度或网页入口。

## 2026-10-24 · EX15c3 单次准备 worker 与整轮契约

新增 `DependencyPreparationWorker`：固定准入源码、清单/原锁/策略摘要，领取任务后在原源码编辑副本执行；准备、目录扫描、工具身份核验、实际 npm 和发布共用原任务与当前配置较小的总期限。实际进程启动的 preflight 再读副本并核对全部输入；停止后校验最终封存候选，只接受锁文件变化。版本发布和名额释放使用 c2 的事实结算，没有后台调度线程、HTTP 入口或自动模型修复。

17:59:49 因缺少 worker 的编译红灯后，18:05:25，**16 单元 + 14 项相关集成通过**，零失败/错误/跳过，BUILD SUCCESS、4:03、退出码 0；归档 `.local/ex15c3-passed.br0591di`。其中 worker 的 4 项使用真正 Node 父子进程与 Git/PG，CLI 内容是受控夹具：验证正常发布及二次无变更、修改非锁文件被拒绝且旧基线不变、准入后批准字节变化在执行前拒绝、实际子进程中取消后得到停止证明并释放租约。另有生命周期 10 项回归。

### EX15d 首次整轮真实 npm 超时

整轮真实契约改为 `DependencyPreparationService.submit → DependencyPreparationWorker.run`，不再由测试直接组合源码门面与进程。21:54:18，沿用底层命令契约的 **300 秒**显式任务配置时，16 单元通过、1 实际契约失败：期望 SOURCE_SAVED，实际 TIMED_OUT，5:49、退出码 1。失败现场 `/private/tmp/atoms-ex11-contract.APT7Jb/lock-preparation-10777538922422815181`；任务 `576f9b06-dc12-45d6-bffd-bacb6c0570a4`，私有归档 `.local/ex15d-native-timeout.n6s836oz`。任务正常停止并释放；只读审计 113 条准备记录全部终结，pending、活动编辑、槽位、投影不一致与协调锁均为 0。此失败未删除或改写。

只读 npm 缓存元数据显示本轮 21:50:44–21:54:12 已获取 223 条记录、尚未生成锁；此前底层契约对应缓存时间为 17:03:29–17:04:05，共 277 条。普通 Vite 元数据请求 10 秒只接收约 2.8 MB/39 MB 后超时；按 npm 安装元数据 Accept 与压缩方式请求为 HTTP 200、2.038 秒、401270 字节。证据表明本轮冷缓存解析持续取得数据但超过 300 秒；未定位更底层网络变化，不宣称网络问题已修复。

复核已批准规格 §7 的整轮默认期限及 `ExecutionSettings` 均为 **1800 秒**（300 秒是排队上限）。因此整轮契约移除自设的 300 秒覆盖，断言新准入任务确实保存 1800 秒，并在全新目录、私有空缓存与新任务上验证；不修改平台默认值、依赖来源、个人代理或已有超时记录。此次契约即使通过也不能证明五分钟性能目标；原 300 秒超时作为独立失败证据保留。

## 2026-10-24 · EX15d 真实整轮验收通过

22:11:42，**16 单元 + 1 实际 npm 整轮集成通过**，零失败/错误/跳过，BUILD SUCCESS、7:43、退出码 0；两轮契约自身耗时 399.8 秒。使用同一批准 schema、实际 Node 24.20.0/npm 11.19.0、隔离 JDK 17.0.14、Maven 3.9.11 和显式 `project-execution-contract`。归档 `.local/ex15d-native-passed.l088rkiz`（0700/0600），新现场 `/private/tmp/atoms-ex11-contract.APT7Jb/lock-preparation-10851276435736324679`。

- 项目 `93df5258-41fa-4604-b25c-0ec41846dac9`，原基线 `50a6cb2d-48ac-46e5-b499-abdb97db9810`，新版本 `5484ae17-afa8-4508-8189-1656791c3aab`。第一任务 `2049dc98-b756-4e71-8fcb-2b5b7b0e2348` 为 SOURCE_SAVED，绑定实际 COMMITTED 操作 `4d3c7285-a2af-4e8e-89ea-6434fce6aa94`。
- 新源码只改变 `frontend/package-lock.json`；原基线不变，250 条依赖，锁 SHA-256 为 `19f70f91a7c9f65b0d794e9e8abd8d72adfb2fbaf61f50c441c2b8290862c8cc`。仅准备批准仍不能通过构建检查。
- 测试明确更新源码外批准后，第二任务 `dc3010b3-dab6-4b0e-ae74-531b41bae339` 为 NO_CHANGES，无 source operation/result，项目版本数保持三版。两任务均持有实际停止证明，编辑租约 RELEASED、共享槽删除、内存能力释放。

最终只读审计：累计 115 条准备记录均终结，pending、活动编辑、共享槽、投影不一致与协调锁均为 0；当前 schema 无新 Agent/构建未决执行。三份历史未知执行的任务/租约 MD5、原源码失败现场及旧误运行的 15 任务/34 事件均未改变。没有清理或改写之前的 300 秒超时记录。

自审核对固定命令与环境、共享期限、提交时批准摘要、实际启动 preflight、最终候选、停止证明和源码事实优先的结算；本片仅 1 个主要契约测试文件。五项私有 env 敏感值与本轮差异核对零匹配。EX15 与 C-EX05 完成，下一项 EX16；该结果不是五分钟性能验收，也不代表自动准备调度、新应用常驻运行或网页已交付。未推送、部署、调用模型/SMTP或改动个人配置。

## 2026-10-24 · EX16a 依赖准备取消

内部 `DependencyPreparationService.cancel` 按项目→准备任务→源码证据顺序锁定；复核当前维护者、项目归属和原任务 owner，归档后仍能查询/取消。QUEUED 且持有原能力时，源码取消、释放、任务终态及共享名额在一个事务完成；已领取任务只保存单调取消意图。丢失内存能力时不重建编辑授权，只提交意图等待启动恢复。终态重复操作不修改记录，原 source operation 已 COMMITTED 时不添加晚到取消。查询增加取消、pending、源码操作/结果版本和固定错误码，未新增 HTTP 端点。

首轮 16 单元通过，21 集成中 1 失败、12 错误。真实 Node worker 的 4 项通过；新取消夹具在记录执行意图后先取消，再读取工作目录以执行测试 JVM，正确触发 SOURCE_EDIT_CANCELLED，导致一条夹具意图未结算，并阻断之后依赖共享容量的测试。任务 `6182b45d-d976-49e8-93b5-f11596356ebc`、项目 `49ac525b-d7bd-4e8f-b6f9-b28a8e3df697`、源码租约 `7424130d-1d9b-418a-90c3-b1baef9fe1bd` 保留于原 `atoms_verify_20261021_ex10`；不补造停止证明。报告存 `.local/ex16a-cancel-failed.aj0sf88t`。夹具改为取消前执行并持有真实收据，异常收尾也只能使用本夹具已有收据。

准确目标创建命令经本轮权限审批，已创建 `atoms_demo_test.atoms_verify_20261024_ex16`，归 `atoms_test`、无 PUBLIC 权限，不扩大数据库 CREATE；创建前后包含本次记录在内的四份未决审计摘要一致。复测只在新 schema 进行，环境隔离不冒充业务故障修复。`application-test.yml` 默认值尚未切换，必须显式传 `SPRING_DATASOURCE_URL` 指向上述新 schema。

22:49:09，`ExecutionNpmPolicyTest,ExecutionLockPreparationTest` 的 **16 单元**和 `ExecutionPreparationCancelIT,ExecutionPreparationWorkerIT,ExecutionPreparationLifecycleIT` 的 **21 集成**全部通过，无跳过，耗时 4:08。覆盖取消/领取竞争、归档、转让与权限、外部事务拒绝、事务失败回滚、内存能力丢失、已提交源码结果和真实 Node 父子进程通过持久取消意图停止。私有报告 `.local/ex16a-cancel-passed.xzxjvj3w`。23:01:48 将 QUEUED 判断反向后单用例正确失败（期望 CANCELLED、实际 QUEUED）；立即恢复并逐字比对通过版本，反向日志 `.local/ex16a-cancel-mutation.log`。

最后新 schema 23 条准备记录无未完成/pending、活动源码编辑、共享槽、投影差异或协调锁。EX16a 的主要文件共 5 个，准备队列、恢复和统一调度仍待完成；本片不改变现有网页、服务器或个人配置。

## 2026-10-25 · EX16b1 依赖准备恢复

`DependencyPreparationRecovery` 仅在独占协调权、源码就绪、无当前 worker 时使用。先经源码门面只读查询原编辑租约对应的 source operation，再决定是否释放；不从 PID、目录或停止收据推导新编辑能力。QUEUED 且仍持原能力留给正常队列；丢失能力的旧排队任务按取消/原始数据库期限/中断结算，不重新安装。ACCEPTING 的原操作已 COMMITTED 时绑定准确操作与结果版本，一次结算为 SOURCE_SAVED；重复恢复不新增操作或版本。任务或源码仍 pending、原操作未终结、以及已有未知结果但尚无实际提交时，保留源码租约与占用。内存中的 NoChanges 结果丢失后不凭停止收据宣称成功，只结算已确认安全中断。

2026-10-25 00:20:40，**16 单元 + 30 集成全部通过**，无跳过，耗时 4:33。集成为恢复 11、取消 7、源码接受 6、源码取消 2、旧 Agent 生命周期 4。覆盖已发布但绑定/响应未登记、发布进行中后实际提交、丢失停止登记、取消优先于排队期限、原 owner 变化、重复恢复、源码租约/名额与任务结算原子回滚，以及写入后失去协调权回滚。使用真实 PG、Git 和本测试持有的 JVM 停止收据；重启通过服务对象丢失内存能力模拟，真正 JVM 崩溃窗口和统一调度将在后续接线验证，不以本组替代。

本片 4 个主要文件，源码门面的原 `reconcileAbandonedEdit` 保持原行为，新增只读 inspect；未新增依赖、迁移或启用开关。验证命令继续显式限定 `atoms_demo_test.atoms_verify_20261024_ex16`。取消源码已恢复为 EX16a 通过版本，反向检查修改未保留。

随后完成测试环境接续：`application-test.yml` 和现有准确目标守卫同步至新 schema，历史失败记录不变。00:47:32，`ExecutionPreparationClaimIT,ExecutionBuildQueueIT,ExecutionRecoveryIT` 的 **30 项集成通过**（含五个真实 JVM 崩溃窗口），耗时 3:00，报告 `.local/ex16-schema-core-passed.m3fldvce`。00:49:46，`ExecutionSchedulerIT` 的 **12 项通过**，耗时 1:19。两项原生契约只有目标名守卫变化，已编译核对，未再运行实际 npm/Maven 下载。默认测试目标现已安全接续，无需依赖记忆手动覆盖；命令示例也已更新。

## 2026-10-25 · EX16b2 准备队列策略

`DependencyPreparationQueue` 持有效协调租约读取最多 8 个有序候选，在五秒事务内按项目→任务锁顺序复核。使用任务原本保存的数据库排队期限，当前配置增大不会延长已入队任务。取消优先于超时，归档、转让及维护者撤权分别记固定失败码。关闭时可在执行开关关闭或归属变化后释放从未领取、仍持原能力的排队任务，源码释放、任务终态与共享名额原子提交；不会通过队列处理已领取或待核对任务。

没有原编辑能力时不重建能力，不释放源码或名额；取消只能写入意图，等待能力交接或启动恢复。`dispatchable` 避免在 admission 的 afterCommit 能力尚未交接时派发；返回可派发也不代替 worker 的原子领取和重新授权。写入失败或提交前观察到协调权丢失会整体回滚，包括释放回调。

00:56:59，`ExecutionPreparationQueueIT,ExecutionPreparationCancelIT,ExecutionPreparationRecoveryIT` 的 **30 项集成全部通过**，无跳过，耗时 3:41：队列 12、取消 7、恢复 11。报告 `.local/ex16b2-queue-passed.caspa75v`。队列场景没有启动工具或分配执行身份；另有 EX16a 的真实 Node 取消及 EX15 的实际 npm 证据，不混淆验证层级。本片 3 个主要文件。EX16c 自动协调器、统一公平调度和关闭接线仍待完成，不能将队列策略通过称为整体调度交付。

最终只读核对：新 schema 共 87 条准备记录，未完成、pending、活动准备编辑、共享名额、投影差异和协调租约均为 0。旧 `atoms_verify_20261021_ex10` 保留首轮失败现场：135 条准备记录中 1 条执行意图 pending，另有后续测试领取失败留下的 8 条 QUEUED，合计 9 条未完成/活动编辑/占用；未清理或强制结算，投影仍一致、无协调租约。所有后续验证已显式使用新目标，默认测试配置也已同步。源码、文档本地提交，未推送、部署、调用真实模型或 SMTP，也未改动私有 env 与全局工具配置。

## 2026-10-25 · EX16c1 原生工厂准备入口

原生 `ExecutionEnvironment` 在同一执行根租约下装配已有 `DependencyPreparationWorker`，提供 `runPreparation`；源码未就绪时在读取任务或领取编辑能力之前拒绝。打开工厂不提前领取任务、创建工作副本或启动 npm；旧只构建资源适配器默认拒绝准备调用。原生种子契约的手动 Spring 上下文补齐实际源码编辑门面与准备服务。

01:06:20，`ExecutionEnvironmentTest` 6 项与 `ExecutionCoordinatorSpringTest` 2 项，共 **8 单元通过**，耗时 44.861 秒；所有测试源码含原生契约已编译，未重跑网络安装。命令中误写的 `ExecutionConfigurationTest` 在仓库不存在，不计入验证；实际配置/默认关闭保护由上述两类覆盖。4 个主要文件；自动派发逻辑在 c2 接通，未宣称本片完成统一调度。

## 2026-10-25 · EX16c2 统一准备与构建调度

`ExecutionCoordinator` 启动时在执行根与数据库独占租约下恢复准备任务；仍有未决执行则不派发。把准备和构建按创建时间、UUID 合并排序，共用工作线程和原始/当前较小并发上限；名额满时仍结算后续排队任务的超时或撤权，但不让后项抢占刚释放的名额。原准备能力交接尚未完成时等待，不重建可写句柄。旧只构建测试适配器遇到准备积压会拒绝启动。

关闭先停止准入、保存取消意图，再通知自己的工作线程停止；确认子进程停止、任务及源码结算后才关闭资源。失去协调权不取消其他协调者的新队列。Spring 原生工厂和生命周期已接通准备 worker；默认配置与网页入口未变化。

01:26:37，**8 单元 + 30 集成通过**，无失败/错误/跳过，耗时 8:44。集成为新混合调度 6、准备队列 12、旧构建调度 12。01:35:04，**2 单元 + 2 新增集成通过**，耗时 3:30。两次命令分别为 `-Dtest=ExecutionEnvironmentTest,ExecutionCoordinatorSpringTest -Dit.test=ExecutionPreparationSchedulerIT,ExecutionSchedulerIT,ExecutionPreparationQueueIT verify`，以及 `-Dtest=ExecutionCoordinatorSpringTest -Dit.test=ExecutionPreparationSchedulerIT#disablingExecutionStopsItsRealPreparationAndCancelsItsOwnedQueue+unresolvedPreparationBlocksStartupWithoutDispatchingOrChangingTheFreshQueue verify`；均经私有 runner 使用获准 EX16 schema 和隔离 JDK 17.0.14。归档 `.local/ex16c2-passed.go9d91rt`；第二次仅新增两项，不把它算作重跑八项。

八个新场景覆盖：原生 Spring 工厂两轮 SOURCE_SAVED/NO_CHANGES、陈旧基线拒绝；构建→准备→构建的顺序；两构建与两准备共占四名额、第五项等待真实停止；满载时排队期限仍生效；丢失能力的旧队列恢复后派发新队列；协调租约丢失；配置禁用；未决任务阻止启动。Node/JVM、Git/PG 均实际执行，CLI 和构建内容为受控夹具，不替代 EX15 的真实 npm 或 EX11 的真实 Maven 契约。

只读审计：116 条准备记录均终结，pending、活动源码编辑、共享名额、投影差异和协调锁均为 0。自审核对锁顺序、能力交接、合并队列排序、停止前持久取消及终态查询，未发现阻断项。4 个主要文件，无新增依赖或迁移。EX16d 独立 JVM 发布后崩溃及原源码恢复兼容仍待验收，不能据此宣称完整工程网页链路已经完成。

## 2026-10-25 · EX16d 发布后进程崩溃与兼容验收

新增测试专用 `ExecutionPreparationCrashProbe` 和 `ExecutionPreparationCrashIT`，2 个主要文件。独立 JVM 持有真实源码/执行根与数据库协调租约，创建基线、领取准备任务、执行并登记实际 JVM 停止证明，再发布受控锁文件变更。在 source operation 已 COMMITTED、准备任务仍 ACCEPTING 且尚无操作绑定时执行 `Runtime.halt(173)`，绕过 finally 和关闭钩子。父进程只用自己的进程停止证明确认该 JVM 已退出，不拿它代替准备任务的停止事实。

重新装配源码与执行协调器，原编辑能力已丢失；启动恢复仅读取持久事实，绑定原操作及准确结果版本，得到 SOURCE_SAVED，释放原源码租约和共享名额。再次启动结果不变，整个恢复没有派发构建或准备工具，源码保持三版、该编辑租约仅一个 source operation。真实根锁及数据库协调租约必须重新取得；原基线与新版本的差异只含新增锁文件。夹具没有实际 npm 安装，不替代 EX15 的原生依赖准备验收。

01:37:43，`-Dtest=ExecutionCoordinatorSpringTest -Dit.test=ExecutionPreparationCrashIT verify` 的 **2 单元 + 1 崩溃集成通过**，无失败/错误/跳过，耗时 1:54。归档 `.local/ex16d-crash-passed.0t8dgmxu`；现场 `/private/var/folders/51/83lhk1js0698vn8fgmlgtgxc0000gn/T/atoms-ex16-preparation-crash-5847192551075943062`，目录 0700，记录 0600；凭据只在 JUnit 临时私有文件，由测试生命周期清理。项目 `91124533-e287-4f04-b5ca-078a11941144`，任务 `57fa4f3a-de88-4056-831b-f30e8cda3f56`，真实提交操作 `4a49b070-4990-4489-bdae-5a676abf7f7e`、版本 `bb46689e-1f85-4e58-a5f9-0bef7db653a5`。

01:42:24，完整 **446 单元 + 16 源码兼容集成通过**，无失败/错误/跳过，耗时 4:15，归档 `.local/ex16d-compat-passed.ns_y2i5h`。运行 `-Dit.test=ProjectSourceRecoveryIT,ProjectSourceEditAcceptanceIT,ProjectSourceEditCancellationIT,AgentLifecycleIT verify`；实际为源码恢复 8、编辑接受 6、编辑取消 2。最后一个类名误少写 Project 前缀，未执行、不计数；发现后单独执行 `-Dit.test=ProjectAgentLifecycleIT failsafe:integration-test failsafe:verify`，01:43:31 **4 项通过**，43.385 秒，归档 `.local/ex16d-agent-passed.p8_vqs_o`。补跑复用本轮已编译、未改动的代码，不重复完整单元。该回归使用受控子进程，不调用真实模型。

最终只读审计：117 条准备任务全部终结，pending、活动准备编辑、共享名额、投影差异及协调锁均为 0；本次崩溃任务为 SOURCE_SAVED，原源码操作 COMMITTED、租约 RELEASED、停止记录存在。自审确认没有用父进程停止证明冒充子任务事实、恢复不会重新取得编辑能力、没有隐式执行或发布；目录/凭据权限及故障现场保留。旧源码和 Agent 兼容通过，c2 两轮基线竞争已覆盖，EX16 标记完成。

本轮只提交本地代码和记录；既有原生构建/npm 证据不重复运行或计数，默认开关、私有 env、全局配置与服务器未改动。下一项 EX17 持久运行身份和独立业务库绑定；完整工程的常驻应用、HTTP 业务验收与网页接入仍未交付。

## 2026-10-25 · EX17a 业务配置与测试库兼容

`ExecutionPrivateConfig.readBusiness` 读取明确 `purpose=runtime` 的私有八键文件，包含固定应用端口；原 `read` 仍只接受七键 test 文件。端口限定规范十进制 1024–65535，文件/父目录身份、权限、无链接、用途及连接参数限制沿用原检查。实际验收文件已存在 runtime/port，不改写原配置。测试环境注入函数再次核对 test 用途，拒绝把业务凭据附加到构建 JVM。

把现有只读连接及系统目录查询原样提取到 `ExecutionDatabaseInspection`，两种用途共用实际身份、标记及最小权限检查；没有业务启动凭据接口、DDL/DML 或新配置开关。测试注册表仍只能读 test 文件，旧返回类型和启动句柄不变。

新测试先因缺少 `readBusiness` 编译失败。01:53:25，**9 单元 + 12 既有真实数据库/受控 JVM 契约全部通过**，无跳过，耗时 1:38。命令：`-Dtest=ExecutionBusinessConfigTest,ExecutionTestDatabaseTest -Pproject-execution-contract -Dit.test=ExecutionTestDatabaseIT,ExecutionDatabaseLaunchIT -Dexecution.test-config-directory=<原已批准私有目录> verify`，沿用获准隔离 JDK。只读连接既有四库，测试库身份检查 4 项、环境传递和拒绝 8 项；未启动业务应用或改写库/角色。原红灯及通过日志私有归档。5 个主要文件，持久绑定和运行记录留给 EX17b/c。

## 2026-10-25 · EX17b 项目业务库的持久绑定

追加 V027 `execution_database_bindings`：固定项目、维护者 binding UUID、loopback 数据库端口、实际 DB/角色 OID、数据库/角色名、应用端口和不含凭据的身份摘要。项目、库名、角色名、应用端口、绑定 UUID 和同一数据库端口下的物理 OID 均有唯一约束。唯一的 1–4 槽位限制持久绑定总数；触发器拒绝更新归属、身份、摘要或删除，停止应用没有释放绑定接口。采用 [PG 唯一/非空/检查约束](https://www.postgresql.org/docs/17/ddl-constraints.html)，避免只靠 JVM 内存限额。

内部 `ExecutionDatabaseBinding` 装载最多四项完整业务注册表；拒绝维护者给出的平台/测试库和角色、跨项目目标重复及应用端口与 DB 端口冲突。每次 verify 只读连接核对实际 runtime 标记及权限，返回无凭据的身份快照，快照本身不能获得启动权限。元数据登记要求短事务，按项目锁→专用绑定容量事务锁（718395205）顺序，重复相同身份返回原记录；跨重启变更身份返回 RUN_DB_BINDING_CONFLICT，缩容不驱逐旧绑定。文件与业务库检查仍在元数据事务之外；用户授权、实际启动前重新核验及凭据传递归后续 EX20。

先因缺少仓库类编译失败，02:01:28 初始 9 单元 + 9 集成通过；自审新增缩容、无事务拒绝和真正触发 CHECK 的第五槽测试，02:04:07 **9 单元 + 11 集成全部通过**，无失败/错误/跳过，耗时 1:37。7 项元数据测试全部事务回滚，不伪装为实际业务库；4 项 opt-in 契约实际只读核对此前获准的 A/B 业务库和测试库。完整命令：`-Dtest=ExecutionBusinessConfigTest,ExecutionTestDatabaseTest -Pproject-execution-contract -Dit.test=ExecutionBindingIT,ExecutionBindingContractIT -Dexecution.test-config-directory=<原已批准私有目录> verify`，只在获准 EX16 schema 应用新迁移，不改历史 schema。

契约持久项目 A=`f7a7917c-7b41-4fd6-b224-25aeeaa9e579`、B=`cdcd56cb-98a1-4ef4-aac9-180589576a91`，均属于测试身份 `f0818b24-e2e7-4a67-bf5d-248eae92fa92`。分别绑定原业务库 A/B、18181/18182；原 binding UUID 分别为 `5055d9cb-9203-4235-bea7-d4732f53f0d0`、`514938e1-b354-4f7b-a26a-e47a933876dd`。两连接竞争 A 得到同一记录，新注册表重复核对仍保持原创建时间；这些绑定有意保留供后续运行验收，不在测试收尾删除或换绑。业务 schema relation 数前后一致，没有 DDL/DML、启动端口占用或新资源创建。

运行身份与迁移批准记录尚在 EX17c 实施，不能把业务绑定登记当作应用已启动。退回旧版本须关闭新入口并保留 V027 及绑定记录，不 DROP 表或释放持久归属。

## 2026-10-25 · EX17c 运行身份与迁移批准元数据

V028 追加 `execution_runs` 和 `execution_migration_reviews`。运行身份固定原成功构建的源码版本、树/JAR/清单摘要和持久数据库绑定摘要；项目归属、归档及幂等冲突在项目行锁内复核。每项目只允许一个未终结运行，未知状态继续占用；同键同输入返回原记录，已取消任务也不隐式重启。STARTING 行没有进程意图；没有执行意图不能伪装 READY，活动执行不能直接写成终态，完成记录及来源均不可改写/删除。此片没有进程启动、停止或凭据注入方法。

批准记录固定项目、绑定、成功构建/JAR、历史/目标迁移摘要、批准人及时间，复合外键拒绝跨项目/构建复用，触发器拒绝改写/删除和错误 JAR。这里只提供记录约束，不读取业务库迁移历史、不执行或自动批准迁移；实际兼容性判断属于 EX18。记录或 UUID 不是进程拥有权或数据库凭据授权。

新增测试先因缺少运行仓库/类型编译失败。02:12:06，**9 单元 + 13 集成通过**，耗时 1:25；运行元数据 6、绑定元数据 7。全部事务回滚；源码版本、成功构建摘要和批准行为是明确的元数据夹具，不宣称实际构建、JAR 或业务迁移通过，也没有伪造进程停止证明。自审补充完成记录不可改写及批准不能用于另一个真实存在的构建。

02:17:34 最终兼容回归：**全部 448 单元 + 22 项相关集成通过**，零失败/错误/跳过，耗时 4:08。命令 `-Dit.test=ExecutionRunIT,ExecutionBindingIT,ExecutionAdmissionIT verify`，运行 6、绑定 7、既有构建准入 9 项；全部沿用获准测试 schema 与隔离 JDK。EX17a/b 的真实数据库及受控启动契约是前述独立运行，不合并或重复计数；本轮没有重跑 npm/Maven 用户工程构建。

最终只读审计确认 V027/V028 均成功，固定 A/B 项目各保留一条独立绑定（2 项目、2 端口，物理数据库/角色 OID 不同）；运行与批准测试记录均回滚，运行数、未终结运行、批准数、共享占位及绑定协调锁均为 0。没有回收既有绑定或改动业务库。红灯、初次通过、最终回归及本次审计输出私有归档。

自审核对参数化 SQL、项目锁与幂等作用域、不可变证据、凭据用途隔离和对外无执行能力的边界；4 个主要文件。EX17 完成，下一项 EX18 实际迁移历史检查与批准流程。回退代码须保留 V027/V028 及不可变记录，不 DROP 表或释放项目归属。本轮只做本地提交与文档更新，未推送、部署、调用模型/SMTP或改变日常配置；新完整工程链路尚未接入网页。

## 2026-10-25 · EX18a 迁移集合与兼容性规则

`ExecutionMigrations` 在事务外读取已有封存句柄，复核构建、源码树及产物摘要，逐字节比较源码与 JAR 的固定迁移目录。首版只接受 UTF-8、正整数版本的 `V数字__英文描述.sql`；最多 128 份、单份 2 MiB、合计 16 MiB。重复版本、额外/缺失/变化 SQL、Java/重复迁移、回调、旁路配置与 SQL 占位符均拒绝。只读取 ZIP，不加载类或执行 SQL；生成代码主动迁移或自定义配置不属于这一检查的安全保证。

`ExecutionMigrationPolicy` 只允许已确认空 schema 初始化；已应用历史须是目标的准确前缀，核对顺序、名称、类型、成功标记及校验和。新增迁移需批准；修改、删除、乱序、失败或回退到缺失已应用版本的目标一律不兼容。CRC32 对照当前 Flyway 11.7.2 实现验证，另以 SHA-256 固定原 SQL 字节及目标集合。[官方校验说明](https://documentation.red-gate.com/flyway/reference/commands/validate)、[11.7.2 校验源码](https://github.com/flyway/flyway/blob/flyway-11.7.2/flyway-core/src/main/java/org/flywaydb/core/internal/resolver/ChecksumCalculator.java)。

初次测试编译暴露缺少类型；该次未等待编译结束便启动下一命令，运行重叠且日志还含其他缺符号错误，因此不把该红灯当作有效的独立回归证据。两命令结束后单独执行验证：02:55:01，`-Dtest=ExecutionMigrationsTest,ExecutionMigrationPolicyTest,ExecutionArtifactTest test`，**12 项通过**，零失败/错误/跳过，48.924 秒。实际 ZIP 字节和封存句柄受测，构建成功记录是显式元数据夹具，不表示运行过生成应用；本片未连接业务库。4 个主要文件，EX18b/c 仍待完成。

## 2026-10-25 · EX18b 业务库迁移历史只读检查

`ExecutionDatabaseBinding.history` 从原私有配置开独立、限时、只读 REPEATABLE READ 连接，复核实际库/角色标记、权限及物理身份；不持平台元数据事务。`ExecutionMigrationHistory` 固定查询 `generated_app`，先验证历史表是当前角色所有的普通表、无继承/RLS、十列类型正确，再读取最多 129 行以拒绝超过 128 的历史；文本在 SQL 端限长，并拒绝超长值，失败只返回固定错误码。历史摘要含表 OID、对象数、顺序、SQL 校验和、结果和安装元数据。函数/独立类型计入非空检测。[PG 17 事务快照](https://www.postgresql.org/docs/17/transaction-iso.html)、[Flyway 11.7.2 历史表定义](https://github.com/flyway/flyway/blob/flyway-11.7.2/flyway-database/flyway-database-postgresql/src/main/java/org/flywaydb/database/postgresql/PostgreSQLDatabase.java)。

测试先独立编译失败（缺少 history 方法/类型），再实现。03:01:27 初次 6 单元 + 8 契约通过；补充查询端文本上界及 RLS 后，03:03:56 **6 单元 + 9 契约全部通过**，零失败/错误/跳过，耗时 1:46。命令 `-Dtest=ExecutionMigrationsTest,ExecutionMigrationPolicyTest -Pproject-execution-contract -Dit.test=ExecutionMigrationHistoryIT,ExecutionBindingContractIT -Dexecution.test-config-directory=<原已批准私有目录> verify`。

两套真实业务库的完整生产入口只读重复检查；异常历史夹具只在原已批准 B 业务验收库的显式回滚事务中创建，直接调用同一历史查询器，没有提交 DDL/DML 或调用 Flyway。夹具每次先确认空 schema，拒绝覆盖已有数据；回滚后再次用完整只读入口确认对象数为 0。五项新历史契约、四项原绑定契约全部通过，未创建新 DB/角色或更改凭据。此项证明历史读取/拒绝，不是应用迁移执行验收；真实升级与失败升级仍由 EX25/后续常驻进程验收承担。3 个主要文件，EX18c 待接批准及启动前复查。

## 2026-10-25 · EX18c 维护者批准与启动前复核

内部 `ExecutionMigrationReview` 提供检查、明确批准和启动前复核三个操作：每次通过当前维护者权限取得成功构建身份，在事务外读取原版本源码、原封存 JAR 和最新业务历史，再在项目锁内核对来源、产物和永久绑定。输出只含项目/构建/绑定 UUID、摘要与判定，无配置路径、角色、JDBC 或 SQL 内容；默认开关和 HTTP 接口不变。批准必须携带用户所确认的准确历史/目标摘要；变化则拒绝，不自动批准最新状态。

`ExecutionMigrationReviewRepository` 仅做短元数据事务：现有 V028 记录保存准确批准，同身份重复操作返回原 UUID；实际启动检查必须匹配当前批准人、项目、绑定、构建/JAR、原历史和目标摘要。错误库、错误 JAR、另一个构建、历史变化及部分升级后不能复用批准；失败/不兼容迁移不能通过批准绕过。空库初始化和完全一致重启不新增批准。该内部检查结果不是进程/凭据能力，EX19–22 还需在启动 preflight 接入并管理真实进程。

新增仓库测试先编译失败（缺少类型），03:09:57 `-Dtest=ExecutionMigrationsTest,ExecutionMigrationPolicyTest -Dit.test=ExecutionMigrationReviewIT,ExecutionRunIT,ExecutionBindingIT verify` **6 单元 + 18 项集成通过**，零失败/错误/跳过，耗时 1:59。其中批准 5、运行 6、绑定 7；元数据夹具均回滚，来源/构建/历史为显式受控样本，不声称真实迁移或应用启动已通过。后续 6 项编排单测使用真实封存 ZIP、Spring 事务边界和受控外部依赖，覆盖外层事务拒绝、当前维护者、读源码时撤权、批准后撤权回滚、敏感连接异常脱敏及每次重新读取历史。

03:16:03 **全部 460 单元 + 36 项相关集成通过**，零失败/错误/跳过，耗时 4:40。命令 `-Pproject-execution-contract -Dit.test=ExecutionMigrationReviewIT,ExecutionRunIT,ExecutionBindingIT,ExecutionAdmissionIT,ExecutionMigrationHistoryIT,ExecutionBindingContractIT -Dexecution.test-config-directory=<原已批准私有目录> verify`。36 项为批准 5、运行 6、绑定 7、原构建准入 9、历史 5、真实绑定 4；没有重跑 npm/Maven 用户工程构建、启动 JAR 或执行真实迁移。自审发现 schema 只有排序规则时原对象计数不完整，已拆为 b2 补测修正；EX18 尚未标记整体完成。

## 2026-10-25 · EX18b2 空 schema 判定修正与收尾

自审发现仅计表、函数与类型会漏掉独立排序规则。03:19:21，在原 B 验收库的回滚事务内创建 `generated_app.ex18_collation`，新单例准确失败：读取对象数为 0，证明原实现会把该 schema 当空库；没有留下数据库对象。修复改查 `pg_depend` 中引用准确 `generated_app` namespace 的依赖记录，覆盖排序规则等 schema 对象，仍是固定只读查询；该数量只用于非空检查和历史摘要，不宣称数据库结构完整性证明。[PG 17 依赖目录](https://www.postgresql.org/docs/17/catalog-pg-depend.html)。

03:22:32，`-Dtest=ExecutionMigrationsTest,ExecutionMigrationPolicyTest,ExecutionMigrationReviewTest -Pproject-execution-contract -Dit.test=ExecutionMigrationReviewIT,ExecutionRunIT,ExecutionBindingIT,ExecutionAdmissionIT,ExecutionMigrationHistoryIT,ExecutionBindingContractIT -Dexecution.test-config-directory=<原已批准私有目录> verify`，**12 单元 + 37 项相关集成通过**，零失败/错误/跳过，耗时 2:39。37 项中历史检查增加为 6，其他组与 c 相同；本次仅修改历史查询及其契约两个主要文件，没有重新计数此前全部 460 单元。业务库真实读/拒绝夹具与元数据夹具均回滚，测试最后确认 A/B schema 仍为空。

最终只读核对原获准平台 schema：V027/V028 均成功，既有 2 项目/2 端口/2 独立物理库角色绑定不变；运行、未结束运行、批准、共享占位与绑定锁均为 0。没有新增库/角色、启动生成应用、调用模型/SMTP、改私有 env 或全局配置。日志及审核输出按 0700 目录/0600 文件私有归档，公开差异的敏感值检查零匹配。

EX18 与 C-EX06 完成，下一项 EX19 真实常驻进程与健康身份。当前交付的是内部可调用的只读检查/批准流程，不是网页启动功能；执行升级、失败迁移、进程启停/恢复、真实模型及网页接入仍按后续计划验收。本轮本地提交，未推送或部署；回退代码保留已有迁移表和不可变审计记录，不清理历史现场。

## 2026-10-25 · EX19a 真实进程的只读活动身份

`OwnedProcess.runObservedCommand` 在实际启动并取得三个标准流的所有权后通知内部调用者，签发只读 `Running`。该对象绑定本次任务、执行 UUID、工作目录和实际 `Process`，只能匹配身份及查询根进程是否存活；不暴露 PID、停止接口，也不能替代健康响应或停止凭据。观察回调异常仍进入原固定收尾；启动前失败不签发活动身份。原 Agent/构建调用沿用原有行为。

04:11:58 独立红灯编译明确缺少新增类型/方法；实现后 04:17:58，`-Dtest=OwnedProcessLifetimeTest,OwnedProcessTest,OwnedProcessDiagnosticsTest,ExecutionProcessTest,AgentProcessTest test`，**34 项通过**，零失败/错误/跳过，耗时 1:55。测试使用项目私有 JDK 与真实临时子进程，验证启动身份、取消后失活、回调失败仍收尾；原不确定子进程/阻塞输出流的拒绝行为保留。用户中断后核对最终日志与进程列表，确认命令已完成，没有重复启动 Maven。主要文件 2 个；EX19b–d 尚待完成。本片未启动生成应用、连接业务库或修改私有配置。

## 2026-10-25 · EX19b 常驻进程与启动期限分离

`ExecutionRunProcess` 在内部后台线程持续持有原进程拥有者；启动前复制固定命令和环境，拒绝在数据库事务中启动。活动身份与回显的运行/执行 UUID 一致且仍在启动窗口内才接受内部就绪确认；此方法不读取 HTTP，EX20 必须先完成真实健康握手。就绪后原启动期限不再限制寿命，仍持续观察进程及读取两路输出，使用固定缓冲并仅保存饱和字节计数，不保留原文或应用 lifetime 总量限制。停止请求幂等，结果只由原拥有者完成一次；调用者等待超时不会产生停止凭据或触发新收尾。未知执行仍无停止凭据。

新增测试先因缺少实现编译失败；15:03:10，16 项通过。15:05:15 临时反转运行 UUID 检查，定向测试准确失败（错误 UUID 被接受）；随后逐字恢复并核对 SHA-256。15:10:43 回归暴露真实竞态：就绪后主动停止若落在 cancellation 检查和下一次 budget 检查之间，会重新读取已过期的启动期限，误报 `RUN_START_TIMEOUT`。15:12:29 新增受控时序单例稳定复现该错误，没有放宽断言。

修复让 STOP_REQUESTED 沿用有限轮询间隔，由下次 cancellation 检查进入原固定收尾，不重新启用启动期限；已经观察到的启动超时仍不被晚到停止覆盖。15:14:38，`-Dtest=ExecutionRunProcessTest,OwnedProcessLifetimeTest,ExecutionProcessTest test`，**18 项通过**，零失败/错误/跳过，1:27。其中常驻进程 8 项验证无健康轮询时主动超时、就绪后的持续运行、停止竞态、重复停止同一凭据、18 MiB/路输出洪泛、退出/启动失败及未知子进程；全部使用真实受控 JVM，未运行生成应用或接入数据库。主要文件 2 个，尚不发布持久 READY，也未暴露网页启动入口。

## 2026-10-25 · EX19c 固定本机启动命令

`ExecutionRunCommand` 复核成功构建与原封存身份、项目绑定及构建时的 Java 工具摘要，分配新的 0700 运行目录和 home/tmp/config，写入 0600 外置配置。目录分配、工具/JAR 扫描与文件写入共用原启动预算；已存在的目录、失败半成品不覆盖或清理。命令只使用已固定 Java、封存 JAR classpath 和 Spring Boot `JarLauncher`，不运行工程启动脚本或 Shell。私有配置指定 loopback/绑定端口、运行/执行 UUID、固定迁移目录及 schema，禁用 baseline/out-of-order/clean/SQL init；数据库连接仅保留占位符，业务凭据发放和 EX18 复核仍待 EX20 接线。

环境从空白白名单构造，包含私有 JVM home/tmp，不继承平台环境。调用方取得的 builder 是独立副本；preflight 重新检查目录/配置 inode、权限、成员、内容，以及封存 JAR 和 Java 文件；失败只返回固定码。显式 `spring.config.location` 替换默认配置查找，因此这一受控启动模式不自动加载工程内任意应用 YAML；也不限制应用 Java 代码自行读取其他配置，不宣称同 UID 安全隔离。[Spring Boot 3.5 外部配置](https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html)、[JarLauncher 契约](https://docs.spring.io/spring-boot/3.5/specification/executable-jar/launching.html)。

新增测试先独立编译失败（缺少命令类型）；15:20:49，`-Dtest=ExecutionRunCommandTest,ExecutionToolchainTest,ExecutionPathsTest,ExecutionArtifactTest test`，**20 项通过**，零失败/错误/跳过，1:15。包含新命令 3 项（多个篡改向量）、原目录 8、工具 3、封存 6；验证固定 argv/环境、权限、重复分配、错项目/失败构建、期限及事务边界。这里的工具和 JAR 是明确的文件夹具，未启动这些文件，也不计作真实应用运行验收。4 个主要文件，无新依赖或数据库变更。

## 2026-10-25 · EX19d 模板健康身份与收尾

新种子健康响应增加 `runId` / `executionId`，由固定外部属性提供；完整规范 UUID 对才接受，错误消息不回显配置。独立启动两项均未提供时保留 HTTP 存活/INITIALIZED，并返回空身份；平台不能把这种响应作为托管运行成功。该响应不是用户业务或数据库健康检查，也没有改写历史源码快照。

按正式 35 文件清单复制到新的私有临时目录，15:22:36 原生 Maven 红灯明确缺少带身份的构造器；实现后 15:23:02，`./mvnw -B -o test` **4 项原生 MVC 测试通过**，零失败/错误/跳过，9.568 秒。验证 Spring 属性注入及 JSON 身份回显、默认独立启动兼容、无效/缺项身份拒绝和未知接口 404。使用已批准的私有 JDK 与本地缓存，无依赖下载、监听端口、业务数据库访问或真实应用启动；模板构建目录仅在忽略的 `.local` 下。两个模板主要文件。

15:26:09，`-Dit.test=ExecutionRunIT,ExecutionBindingIT,ExecutionMigrationReviewIT,ProjectAgentLifecycleIT,ProjectAgentInitializationIT,ProjectSourceTemplateInitializationIT verify`，**全部 473 单元 + 30 项相关集成通过**，零失败/错误/跳过，BUILD SUCCESS，2:23，平台本地打包完成。只统计本次日志创建之后的 102 份 Surefire / 6 份 Failsafe XML；相关集成为运行元数据 6、绑定 7、迁移批准 5、Agent 生命周期 4、Agent 初始化 4、模板初始化 4。模板 4 项是上面单独的原生运行，不混入平台计数；此次未重跑无变化的实际 npm 构建或真实业务迁移验收。

最后固定只读核对已授权的 `atoms_demo_test.atoms_verify_20261024_ex16`：V027/V028 成功，原 2 项目/端口/物理库角色绑定不变，运行、未结束运行、迁移批准、共享占位和绑定锁均为 0。未访问其他旧故障 schema 或清理历史记录。9 个实际私有值与公开差异及平台包内容核对零匹配；日志、红灯/变异证据及新鲜报告归档于忽略目录 `.local/ex19-evidence-ba15irlb`，目录 0700、文件 0600。

EX19 完成，C-EX07 仍等待 EX20/21。下一项 EX20 接入当前授权、业务凭据发放、EX18 迁移复核、持久执行意图和实际 HTTP 身份握手；后续仍有停止换版、重启恢复及真实业务验收。当前实现的是内部进程及命令能力，不表示生成应用已经从网页启动；新模式保持关闭。本轮本地提交，未推送、部署、调用模型/SMTP或改变日常配置。自审覆盖身份绑定、预算/停止竞态、环境与输出边界、文件复验及兼容性；没有引入新依赖、公共接口或数据库迁移。

## 2026-10-25 · EX20a 实际 HTTP 健康身份

`ExecutionHealth` 只向内部数据库绑定的 `127.0.0.1:port/api/health` 发 GET，不接受 URL、头部或代理配置；显式直连、不携带认证/Cookie、不跟随重定向。响应最多 4096 字节，每次请求最长 2 秒且包含正文等待，所有尝试受原剩余启动预算约束；超大正文主动取消订阅，超时取消 future。只接受 JSON 的 UP/INITIALIZED 和准确 runId/executionId 四字段，拒绝重复字段、尾随 JSON、非 JSON/压缩正文或跳转；未连接/503 可继续等待，但不延长总期限。[Java 17 HttpClient](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpClient.html)、[BodySubscribers](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpResponse.BodySubscribers.html)。

身份通过且原进程仍允许确认就绪时才签发只读 `Confirmed`；其匹配还依赖实际进程继续存活、运行/执行 UUID 及完整绑定身份。进程停止后不能继续使用此对象宣称就绪。网络请求拒绝在元数据事务内执行；该内部凭据尚未写入运行状态，不能把本片视作启动编排完成。

新增测试先编译失败（缺少实现）；16:30:30，HTTP 与常驻进程共 **12 项通过**，41.219 秒。补充错误绑定与事务边界后，16:32:33，`-Dtest=ExecutionHealthTest test` **5 项通过**，零失败/错误/跳过，47.423 秒。HTTP 服务来自真实受控 JVM，验证身份、停止失效、错误/缺失/重复/超大/非 JSON 正文、跳转不跟随、慢正文超时与事务拒绝；夹具在监听就绪后发出单字节信号，避免依赖固定睡眠。未启动生成业务应用、接触业务数据库或调用外部服务。2 个主要文件；新模式与网页不变。

## 2026-10-25 · EX20b 按成功构建重新读取封存产物

`ExecutionArtifact.openSuccessful` 接受内部授权后读取的成功构建及数据库中原 JAR/清单摘要，重新核对私有目录、任务标记、权限/链接、大小及完整字节摘要。清单必须与该构建/项目/源码树、JAR 大小/摘要及有界检查证据的规范序列化完全一致，不能仅凭文件存在或可解析 JSON 认领成功。重新取得的文件身份继续由原 `verify` 复验；不修改旧文件、重新封存或覆盖失败目录，也不取得构建/运行目录的编辑能力。这里可恢复的是封存文件读取能力，绝不恢复进程、PID 所有权或停止凭据。

新增测试先编译失败（缺少读取方法）；16:38:08，`-Dtest=ExecutionArtifactReopenTest,ExecutionArtifactTest,ExecutionPathsTest,ExecutionMigrationsTest test` **20 项通过**，零失败/错误/跳过，39.429 秒。新 3 项覆盖释放原执行根后重新打开、持续复验、字节/标记/链接/成员篡改、失败构建、错误摘要和非规范清单；这些是文件与明确构建元数据夹具，没有执行 JAR 或把磁盘内容提升为数据库成功状态。主要文件 4 个，无新迁移或业务数据库连接；EX20 的准入与实际启动仍待后续分片。

## 2026-10-25 · EX20c 绑定固定运行命令的业务凭据

业务注册表仅对准确项目、运行/执行 UUID 和完整物理数据库绑定签发内部 `Launch`。取得固定命令 builder 时再次只读核对数据库、私有配置以及原封存命令；一次领取即消耗，即使复验失败也不能重复使用。额外 preflight 复验可再次执行，但不能再领取 builder。凭据只装入该固定命令的白名单环境，不返回配置属性、写入源码/外置配置或作为命令参数；测试用途配置不能注入业务启动。此能力不是用户授权、迁移批准或持久的单次启动保证，后者仍由 d–f 的数据库意图与编排提供。

16:41:30 独立红灯明确缺少授权及业务环境方法；16:49:03，`-Dtest=ExecutionBusinessConfigTest,ExecutionRunCommandTest -Pproject-execution-contract -Dit.test=ExecutionRuntimeLaunchContractIT,ExecutionBindingContractIT -Dexecution.test-config-directory=<原批准目录> verify`，**5 单元 + 7 项契约通过**，零失败/错误/跳过，1:00，平台打包成功。新 3 项使用原批准业务库执行只读身份/历史查询，验证环境注入、错项目/运行/执行、预算耗尽、配置变更撤销以及重复使用拒绝；涉及实际秘密的断言只输出布尔值。原 4 项绑定契约同时通过。命令/JAR 为明确文件夹具，未执行，没有实际应用迁移或新 DB/角色；原私有配置未改动。主要文件 4 个，默认开关及网页入口不变。

## 2026-10-25 · EX20d 运行意图、健康与停止证据持久化

运行仓库增加项目锁内的幂等查询和单次执行领取；同一运行已经获得执行编号时，即使重复相同编号也拒绝再次领取。发布 READY 复核当前所有权/归档、完整持久绑定、本次执行意图及仍有效的 HTTP/活进程凭据；停止后原健康凭据不能复用。清理仍可在撤权或归档后提交，但必须匹配原不可变运行身份和实际停止证明。

缺失/错误停止证明不能清除 pending；错误证明拒绝，缺失证明保留待核对。已启动但尚未持久 READY 的进程，即使已停止，也可能执行过部分业务迁移，保留 `NEEDS_RECONCILIATION` 与同项目阻断；不会把进程退出等同于数据无变化。已有待核对记录不能靠重复完成调用自动终结。READY 后的退出先在同一事务写入停止摘要并清 pending，再写终态，遵守 V028 的双语句约束；中间写入失败，两步一并回滚。只有尚无执行意图的原新任务可以按启动前失败终结。

`ExecutionRunJournal` 用独立五秒事务封装领取/就绪/完成，拒绝外层事务；领取和就绪在操作前后核对维护者配置。清理不因配置撤销而被阻断。数据库/提交异常仅返回固定错误，无 cause/suppressed，不重试不明提交。该接口仍是内部组合，不能单独代表协调租约、当前源码/迁移检查或真正调用启动器；e–f 继续接线。

状态测试红灯明确缺少仓库方法；16:54:50，2 单元 + 24 集成通过。16:56:21 将停止凭据身份条件反转，定向测试准确失败（错误执行的停止凭据被接受）；随后恢复原文件并核对 SHA-256 一致。事务编排测试独立红灯缺少类型；增加终态写入故障后，16:59:47，`-Dtest=ExecutionRunJournalTest,ExecutionHealthTest,ExecutionRunProcessTest -Dit.test=ExecutionRunStateIT,ExecutionRunIT,ExecutionBindingIT,ExecutionMigrationReviewIT verify` **20 单元 + 25 集成通过**，零失败/错误/跳过，1:20。新状态集成 7 项使用可回滚元数据夹具和真实受控 HTTP 子进程，进程操作时挂起测试事务；新增事务单测 7 项注入配置漂移/数据库失败/提交回执丢失，不能据此宣称真实提交丢失已发生。所有生成业务数据库仍未迁移，没有新表迁移、依赖、模型调用或网页接口。4 个主要文件，本地提交。

## 2026-10-25 · EX20e1 内部启动准入与一次领取

`ExecutionRunService` 在短项目事务内检查当前维护者及已有幂等记录；重放不重新打开文件、连接业务库或签发启动能力。新请求取得成功构建身份，在事务外重新读取封存 JAR、核对完整物理绑定并调用 EX18 的源码/迁移复核。最终项目锁内再检查当前身份、幂等竞争、配置与预算，只有新插入运行记录的请求取得不可自行构造的内存 `Ticket`。提交回执丢失不返回 ticket、不重试写入；重放也不从已有 STARTING 行重建 ticket。这个能力尚未实际启动进程。

ticket 限原服务领取一次；持有的配置、产物、绑定和原单调启动预算随 claim 传递。`recheck` 在实际命令准备前（尚无执行编号）、及之后的进程 preflight（准确执行编号）重新核对当前元数据、固定版本/产物、物理绑定、迁移批准和权限，拒绝改版、停止、完成或配置变化。该组合还没有 Spring 调度或 HTTP 入口，持有执行根不等于已经取得数据库协调租约；后续 worker 必须接入当前独占协调权。

17:04:46，新测试独立红灯缺少服务类型。17:06:53 首轮失败发生在 Mockito 重设 `when` 时触发旧 answer 的事务断言（3 项），没有删除/放宽断言；改用 `doReturn` / `doAnswer` 重设。17:09:39，`-Dtest=ExecutionRunAdmissionTest,ExecutionRunJournalTest,ExecutionArtifactReopenTest,ExecutionMigrationReviewTest -Dit.test=ExecutionRunStateIT,ExecutionRunIT,ExecutionMigrationReviewIT verify`，**23 单元 + 18 项相关集成通过**，零失败/错误/跳过，1:35，平台打包通过。7 项新组件测试覆盖单次领取、原预算复用、重放、最终锁竞争替身、改 JAR/错库/迁移失败、权限漂移及提交回执失败；封存文件真实，元数据/业务库/迁移依赖为明确受控替身，不宣称真实双连接准入或生成应用启动验收。e2 与 f 仍待完成，EX20 不勾选整体完成。

本轮收尾只读核对原批准 schema：V027/V028 成功，2 项目/2 端口的原业务绑定不变；运行、未结束运行、批准、共享占位和绑定锁均为 0。未清理其他旧故障现场。8 个实际私有值与本轮公开差异及平台包内 classes/resources 核对，零匹配。15 份 EX20 日志（包含红灯、失败和变异）已归档到忽略目录 `.local/ex20-progress-evidence-q5leastt`，目录 0700、文件 0600。已本地提交，未推送、部署、改私有 env、调用模型或 SMTP；默认旧网页流程不变。接下来完成 e2 真实并发准入及 f 启动 worker，再验收 EX20 整体。

## 2026-10-25 · EX20e2 双连接真实准入

新增显式 `project-execution-contract` 契约，复用已批准 A 项目、所有者和原业务绑定，不新增数据库/角色/绑定。`SourceAgentFixture.initializeExisting` 通过真实源码初始化保存一份 SQL 样本，之后仅在原快照可读且摘要完全相同的情况下复用。源码及 Git 保存在指定私有根，测试保留审计和文件，不删除既有记录。构建成功元数据与只含 SQL 的 ZIP 是明确测试夹具，没有声称实际 npm/Maven 构建或业务应用启动通过。

实际 `ExecutionRunService` / PG 仓库 / 源码读取 / 封存校验 / 物理库检查 / 迁移复核组合运行。两条事务连接通过真实 `pg_backend_pid()` 证明不同，在首次项目锁前同步，并在事务外完成迁移复查后再次同步：两请求只提交一个运行 UUID，只有一个取得 ticket。重放、同键异体、活动项目、非所有者、归档、篡改 JAR 和重复领取均校验；未发出执行意图的本次测试记录通过正式 `notStarted` 入口终结，不清理已执行或未知状态。业务历史前后完全相同。

17:19:21 红灯明确缺少跨模块测试适配。17:22:54 首轮在创建执行根前失败，原因是测试把执行根放进平台仓库；已有保护正确拒绝，未改保护规则。源码快照保留，执行根改为显式仓库外目录。17:26:04，`-Dtest=ExecutionRunAdmissionTest -Pproject-execution-contract -Dit.test=ExecutionRunAdmissionIT -Dexecution.test-config-directory=<原配置目录> -Dexecution.runtime-fixture-root=<固定源码夹具根> -Dexecution.contract-directory=<仓库外执行父目录> verify`，**7 单元 + 1 项真实契约通过**，零失败/错误/跳过，2:02。两个主要测试文件；生产逻辑未修改。EX20e 完成，真正的进程启动及提交/退出组合由 f 继续验收。

## 2026-10-25 · EX20f1 常驻状态监控与就绪提交期限

内部 `stopRequested` 在短项目事务中复核原服务 claim、当前维护者、所有权/归档、封存构建与永久绑定、准确执行编号及活动状态，然后读取持久停止标记。READY 后不再读取原启动预算，也不反复执行迁移批准检查；迁移后的业务历史已经变化，不能用启动前的批准摘要误杀正常常驻应用。调用者已有事务时仍拒绝，外部停止与原进程收尾由 worker 接线。

READY 提交新增显式共享预算参数，在仓库写入前后检查原剩余期限；等待事务锁等期间过期则回滚，返回固定 `RUN_START_TIMEOUT`，不会另开一个启动窗口。边界测试使用可控预算，在写入前耗尽和写入后耗尽均不提交；监控测试将原启动预算置零，仍能查询真实绑定的 READY/STOPPING 元数据，并拒绝错执行及配置撤销。

独立红灯明确缺少监控入口与预算参数；17:32:42，`-Dtest=ExecutionRunAdmissionTest,ExecutionRunJournalTest,ExecutionRunProcessTest -Dit.test=ExecutionRunStateIT,ExecutionRunIT verify`，**24 单元 + 13 项 PG 集成通过**，零失败/错误/跳过，2:41，平台打包成功。4 个主要文件，没有业务迁移或新运行启动；f2 继续完成真实进程组合。

## 2026-10-25 · EX20f2 worker 与真实持久状态组合

内部 `ExecutionRunWorker.run(ticket)` 持有本次运行直至进程结束：检查独占协调权/执行根/准确 Java、最新权限与迁移，组装固定命令与凭据，检查端口后提交执行意图，再调用原进程拥有者。preflight 再检查同一组来源/绑定/授权；HTTP 身份通过且预算仍有效才持久 READY。之后继续排空输出并每秒检查当前授权、协调权与持久停止标记，不以启动期限限制应用寿命。外来端口明确失败，不杀外来进程；端口检查与进程绑定仍不是跨进程原子操作，因此就绪继续要求本次准确身份。

意图提交回执不明不调用 launcher、不伪造未启动结果；已拥有的进程遇到失败由原拥有者停止。缺失停止结果保留未知状态，完成写入在失败捕获之外，不因写入异常再结算一次。worker 不关闭调用者的根/协调租约、不发布 HTTP 接口，也没有提前启用 Spring 调度；EX21–22 接入管理生命周期。

17:36:32 独立红灯缺少 worker 类型；17:39:56，`-Dtest=ExecutionRunJournalTest,ExecutionRunAdmissionTest,ExecutionRunProcessTest -Pproject-execution-contract -Dit.test=ExecutionRunHealthIT -Dexecution.test-config-directory=<原批准目录> -Dexecution.runtime-fixture-root=<固定源码根> -Dexecution.contract-directory=<仓库外父目录> verify`，**24 单元 + 1 项真实组合通过**，零失败/错误/跳过，2:16。真实 PG、独占租约、Git/封存/私有配置/业务历史与状态服务全部参与；最后 launcher 是明确的受控 HTTP JVM，工具/JAR 为命令验证夹具，不把此项当作原生 Spring 应用/SQL 执行验收。夹具进程不接收业务凭据，也不执行样本 SQL。

独立连接在 `Process.start` 前已能读到准确执行意图；健康握手后 READY、重复 ticket 拒绝；测试生产持久停止标记（正式停止 API 仍属 EX21），原拥有者实际停止并将匹配摘要写入 STOPPED。随后外来监听占用原端口，新请求以 `RUN_PORT_IN_USE` 在发出执行意图前失败，外来 socket 保持打开，launcher 总计只执行一次。原业务历史前后相同，审计记录保留。2 个主要文件；f3 继续异常矩阵和收尾回归。

## 2026-10-25 · EX20f3 异常收尾与拥有句柄保留

worker 增加内部拥有句柄表，保留原 launcher 返回的同一个 `ExecutionRunProcess`。完成写入获得成功回执、pending 已清除且持久停止摘要匹配本次真实凭据后才释放句柄；停止结果不明、完成写入失败或提交回执不明均保留，供后续协调者使用原拥有能力清理。不根据数据库行、PID 或端口重建进程拥有权，不重试不明完成操作。尚未 READY 的数据待核对状态仍由原数据库规则保留，进程已停止并不等于可再次运行。

新增 9 项 worker 组件验收：真实 HTTP 错身份、提前退出、执行意图提交回执丢失、READY 提交回执丢失、完成 SQL 失败、完成提交回执丢失、启动前配置撤销、READY 后协调权丢失，以及缺失停止凭据。实际子进程和停止收据由原进程拥有者产生；只有最后一项使用无 OS 进程的未知结果替身，明确不伪造停止证明。元数据/物理库边界与提交故障为受控替身，真实 PG 的状态/事务约束由 `ExecutionRunStateIT` 另行核对，不宣称实际数据库发生过网络丢包。

17:48:53，独立红灯缺少拥有句柄查询方法。17:51:01 首轮编译指出测试 launcher 内声明受检异常的位置不正确；将未知结果替身准备移到测试 setup，未放宽断言。17:54:30，`-Dtest=ExecutionRunWorkerTest,ExecutionRunJournalTest,ExecutionRunAdmissionTest,ExecutionRunProcessTest,ExecutionHealthTest -Dit.test=ExecutionRunStateIT,ExecutionRunIT verify`，**38 单元 + 13 项 PG 集成通过**，零失败/错误/跳过，2:33，平台打包成功。2 个主要实现/测试文件；后续完整回归及变异结果在下方续记。

17:56:47，将完成后的句柄释放条件改为“无条件释放”，定向未知结果测试准确失败：预期原拥有对象，实际得到 null。随后恢复原条件，源码 SHA-256 与变异前完全一致，继续全部单元及相关集成回归；没有保留变异代码或放宽测试。

18:03:49，完整单元与相关集成回归 **506 单元 + 61 集成通过**，零失败/错误/跳过，6:10，平台打包成功。命令为 `-Pproject-execution-contract -Dit.test=ExecutionRunAdmissionIT,ExecutionRunHealthIT,ExecutionRunStateIT,ExecutionRunIT,ExecutionBindingIT,ExecutionMigrationReviewIT,ExecutionRuntimeLaunchContractIT,ExecutionBindingContractIT,ExecutionMigrationHistoryIT,ExecutionAdmissionIT,ProjectAgentLifecycleIT,ProjectAgentInitializationIT,ProjectSourceTemplateInitializationIT`，继续传入上文三个准确私有夹具目录并执行 `verify`；未指定 `-Dtest`，全部后端单元均运行。按本轮开始时间筛选实际 XML，107 个单元类、13 个集成类与日志计数一致，没有拿旧报告补数。

收尾只读核对 `atoms_demo_test.atoms_verify_20261024_ex16`：V027/V028 成功，原 A/B 两项目绑定、端口和物理 DB/role OID 不变；6 条运行均终结，未结束运行、共享占位、绑定锁与迁移批准均为 0。真实业务库历史检查和可回滚历史夹具通过，没有把样本 SQL 交给生成应用执行。9 个实际私有值与本轮公开差异、新测试及最终平台包内 classes/resources 核对，零匹配。27 份 EX20 日志及本轮全部 XML 已归档到忽略目录 `.local/ex20-final-evidence-bscns6t3`，目录 0700、文件 0600；旧审计和失败日志保留。

据此完成 EX20。这里验证的是内部 worker 与真实 PG/受控 HTTP JVM，不替代生成 Spring 应用的业务验收。EX21 接正式停止/换版，EX22 接运行恢复与管理生命周期，网页入口及原生完整工程验收仍按后续计划推进；新模式保持默认关闭。本轮只做本地提交，不推送、部署、修改私有 env、调用真实模型或 SMTP。

## 2026-10-25 · EX21a 正式持久停止入口

`ExecutionRunService.stop` 在独立短事务中核对当前维护者配置、项目所有者和原运行 owner，提交单调的停止标记。允许原所有者停止归档项目；其他用户、换主后的旧任务和跨项目运行均拒绝。READY 转 STOPPING，尚未启动的 STARTING 与 NEEDS_RECONCILIATION 保持原状态；不清 pending、不签发停止凭据、不开放新运行容量。重复停止和终态重放不产生额外变更。实际停止仍由原拥有 worker 执行；启动前观察到已提交的停止请求时，返回明确取消原因。

独立红灯明确缺少服务及仓库 stop 方法。18:13:45，`-Dtest=ExecutionRunAdmissionTest,ExecutionRunWorkerTest,ExecutionRunJournalTest -Dit.test=ExecutionRunStateIT,ExecutionRunIT verify`，**27 单元 + 16 项 PG 集成通过**，零失败/错误/跳过，1:29，平台打包成功。新场景包括重复停止、归档时停止、真实停止凭据前阻止下一运行、未知状态保留、所有者变化、禁用/配置变化/事务嵌套和提交回执失败；最后一类采用受控事务故障，不宣称真实网络故障。4 个主要文件，EX21b 继续接换版与完整进程组合。

## 2026-10-25 · EX21b 换版准入与快速重启

内部 `replace` 返回两种明确结果：原运行仍待停止时只返回 `stopping`；它已持久结算后，再做完整产物/物理库/迁移检查并返回新运行 `admission`。调用方观察状态后以同一参数重试，不在事务中等进程、不创建暗中的自动重启队列。停止标记本身不保存尚未提交的目标构建；调用方离开或平台重启不会自动接续新启动。请求摘要新增独立换版格式，包含准确原运行 UUID、目标 build/binding/review；普通启动保留旧 v1 摘要。已提交的新运行重放不再次停止任何进程、不再发 ticket；换原运行或目标会发生幂等冲突。

目标构建的所有权、成功状态和绑定在停止前检查；完整文件/物理库/迁移复核在旧运行结算后进行。新准入在同一项目锁中核对前一运行已终结、同 owner/绑定及全项目无未结束运行，因此不能绕过“先停后启”。换版后新启动失败不会改写旧成功构建、旧运行或业务绑定，也不承诺自动回滚数据库。

b1 独立红灯缺少换版请求/服务方法。首次组合 29 单元、16 项原 PG 测试通过，新增真实换版于 18:18:27 失败：旧运行已真实停止，新运行在 launcher 前报 `RUN_PORT_IN_USE`。随后只读核对当时 8 条运行均终结、原绑定不变、共享槽位/绑定锁为 0，保留失败记录。因需新增端口修复及回归，拆为 b1（4 个主要文件）与 b2（3 个主要文件），完成组合验收后再分别提交。

18:21:51，真实 TCP 定向测试复现：服务端主动关闭已接受连接并关闭监听后，旧预检仍阻止启动；活跃外来监听测试正常拒绝。原预检显式关闭 `SO_REUSEADDR`，把关闭连接的 TIME_WAIT 当成监听占用；[JDK 17 官方说明](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/ServerSocket.html#setReuseAddress(boolean))明确该选项用于绑定时避开关闭连接的等待状态。改为绑定前开启地址复用，不开启 SO_REUSEPORT；原有独占运行/真实停止/健康身份要求保持。外来监听即使开启地址复用仍被拒绝；没有抢占或终止外来 socket。

18:24:10，`-Dtest=ExecutionRunAdmissionTest,ExecutionRunWorkerTest,ExecutionRunJournalTest -Pproject-execution-contract -Dit.test=ExecutionRunHealthIT,ExecutionRunStateIT,ExecutionRunIT` 加原三个准确私有目录并 `verify`，**31 单元 + 17 集成通过**，零失败/错误/跳过，1:53，平台打包成功。真实 PG + 受控 HTTP JVM 证明：旧版 READY → 正式换版返回 STOPPING → 真实停止凭据持久化 → 新构建一次启动至 READY；启动新版前原进程已不存活。重复换版不停止新运行，修改前一运行产生冲突；下一次端口占用失败保留前版的成功构建及停止记录。业务历史未改变；两个构建沿用同一真实源码/迁移快照，未将此测试冒称原生生成应用或业务升级验收。

## 2026-10-25 · EX21c 并发换版与兼容收尾

补充真实双线程换版竞争：两条同时进入服务的请求取得同一运行 UUID，只有一张新 ticket，然后才启动新进程；这不是把两次顺序调用称为并发。真实 PG 下，启动前已提交的停止请求最终由 worker 结算 CANCELLED，execution UUID 为空且 launcher 计数不增加。仓库反例验证原普通启动键的重放仍兼容、无效前一运行不能准入、相同换版键改变前一运行会冲突，旧记录保持不变。

权限撤销组件测试使用真实 HTTP 子进程，撤销后的元数据授权检查为受控替身，原 worker 仍实际停止并保存匹配凭据；真实项目换主后的仓库清理另由 `ExecutionRunStateIT` 覆盖。未知前一运行保持阻断，无效目标构建在发出停止请求前拒绝。未刻意遗留拒停的 OS 子进程，未知/拒停分支复用 EX19–20 的进程观察与缺失停止凭据契约，不能将这些测试称为原生故障迁移或数据库自动恢复验收。

18:28:00，同 b2 的定向命令 **33 单元 + 18 集成通过**，零失败/错误/跳过，1:29。18:29:13，故意从换版摘要中删除前一运行 UUID，定向测试准确失败（不同前一运行产生相同摘要）；恢复后源码 SHA-256 与变异前一致，再运行完整单元与相关集成。此片仅 4 个主要测试文件，生产变异未保留。

18:34:47，未限制 `-Dtest`，全部后端 **514 单元 + 65 项相关集成通过**，零失败/错误/跳过，4:58，平台打包成功。显式 `project-execution-contract`，选择 EX20f3 最终命令列出的同一组 13 个集成类，继续使用原三个准确私有目录；包括本轮改造的真实运行/状态检查、原双连接准入、迁移策略/物理库契约，以及 Agent 生命周期、初始化和源码模板兼容。按本轮起始时间核对实际 XML：107 个单元类和 13 个集成类，未引用旧报告充数。

最终只读核对原 schema：V027/V028 成功，A/B 两组绑定 UUID、项目、端口和物理 OID 保持不变；20 条运行全部终结，未结束运行、共享占位、绑定锁和迁移批准均为 0。包括首次端口误判在内的失败记录均保留，没有重写它们或强制清除 pending。9 个实际私有值与本轮公开差异及最终包 classes/resources 核对，零匹配；9 份日志与全部本轮 XML 归档在忽略目录 `.local/ex21-final-evidence-pgfchjam`（目录 0700、文件 0600）。

据此完成 EX21 与 C-EX07。控制入口仍为内部服务，等待停止阶段由调用者继续观察/重试；没有新增公开 HTTP、工作台按钮、自动恢复或 Spring 运行调度。EX22 负责管理生命周期、关闭和重启核对，原生生成应用/真实业务升级仍按 EX25–27 验收。未推送、部署、改变私有 env 或调用真实模型/SMTP。

## 2026-10-25 · EX22a1 遗留运行的持久状态核对

新增内部恢复持久操作，复用项目/运行行锁与完整不可变身份核对。STARTING 且从未发出执行 UUID 的遗留任务结算为 FAILED/RUN_INTERRUPTED；已有停止请求则为 CANCELLED/RUN_CANCELLED，不伪造停止凭据。其余未终结、非既有待核对运行转为 NEEDS_RECONCILIATION/RUN_RECOVERY_UNCERTAIN，保留执行 pending、原健康时间、停止意图、绑定和全部身份。既有待核对及终态逐字保持；实际进程停止但启动迁移结果未知，也不自动解除项目阻断。

先缺方法编译红灯，再于 18:50:10 通过 **33 单元 + 23 PG 集成**，零失败/错误/跳过，平台打包成功（`ExecutionRunJournalTest,ExecutionRunAdmissionTest,ExecutionRunWorkerTest` 与 `ExecutionRunStateIT,ExecutionRunIT`）。新增 6 个恢复用例，READY/STOPPING 场景使用真实受控 HTTP JVM；恢复只更新元数据，原进程仍存活且只能由测试原拥有句柄停止。元数据全事务回滚，不留下永久绑定或不明运行；这不是真实协调者崩溃或生成应用业务迁移验收。

18:51:19，移除“既有 NEEDS_RECONCILIATION 原样保留”条件的变异，使 RUN_HEALTH_INVALID 被错误覆盖为 RUN_RECOVERY_UNCERTAIN，定向用例准确失败。随后源码恢复并核对字节一致。日志为忽略目录中的 `ex16-ex22a1-{red,green,mutation}.log`。本片 2 个主要文件；独占协调权包装与 Spring 启动接线仍待后续小片，不据此勾选 EX22 整项。

## 2026-10-25 · EX22a2 独占恢复与事务边界

`ExecutionRunRecovery` 在事务外、事务开始后和写入后三次检查原数据库协调租约、源码就绪状态及完整配置快照；独立事务上限 5 秒，拒绝调用方事务。执行根由调用者持有并在事务外检查，恢复器不访问文件、业务库或进程。底层故障仅返回 RUN_RECOVERY_UNAVAILABLE，无驱动消息、路径、cause 或自动重试；提交回执不明不被当作已回滚。

新增 9 个组件测试覆盖失锁、配置变化、源码不可用、嵌套事务、数据库故障及提交回执丢失。扩展原双连接准入契约：持有真实文件根/数据库租约，在真实 UPDATE 后故意改变配置，PG 完整回滚且原运行逐字段保持；正常恢复同一未发出意图的任务后，旧键重放不再发 ticket。关闭实际协调租约后恢复拒绝，业务历史保持不变。此契约仍沿用原有元数据/ZIP 夹具，不声称执行了原生应用。

18:54:59，**42 单元 + 24 PG 集成**通过，零失败/错误/跳过，平台打包成功；命令为 a1 三项单元加 `ExecutionRunRecoveryTest`，集成增加 `ExecutionRunAdmissionIT`，显式使用原契约 profile 与三个批准目录。删除写后 authority 检查的变异被对应失锁测试拦住，源码逐字恢复。日志 `ex16-ex22a2-{red,green,mutation}.log`。3 个主要文件；下一片接 Spring 启动恢复和实际 JVM 崩溃，EX22 整项仍未完成。

## 2026-10-25 · EX22b0 启动接线与实际 JVM 崩溃

协调者取得原数据库租约和执行根后，先核对遗留运行，再处理构建/准备恢复；任何未终结运行都阻止启动派发。当前尚无常驻运行 ticket 交给调度器，运行中发现未拥有的意图即停止派发；已激活调度器的关闭流程保留未决运行对应的根和协调资源。启动尚未接管任务就因待核对状态失败时，可关闭本次打开的资源，但不改运行证据或放开项目准入。

新增独立 `ExecutionRunCrashProbe`，实际测试 JVM 取得两个租约，在 BEFORE_INTENT、AFTER_INTENT、AFTER_READY 三处 `Runtime.halt(71)`，验证 shutdown hook 未运行。后两处 HTTP JVM 由父测试的原进程拥有器持有，原真实停止能力不会随着协调者测试 JVM 退出丢失；应用始终只有受控 HTTP 行为，没有业务凭据或 SQL 执行。重启实际 `ExecutionCoordinator` 两次，重新取得两个租约，分别核对 FAILED 或 NEEDS_RECONCILIATION，旧健康时间保留但不继续宣称 READY，原 HTTP 子进程仍存活且没有被新协调者收养或终止。业务历史与原绑定保持不变。另用已关闭的独立连接池验证元数据数据库不可用时固定报错、原行不变；未停止真实 PostgreSQL 服务。

测试收尾只处理本轮准确夹具：父测试使用原拥有句柄停止 HTTP 子进程，核对 run/execution/cwd 的真实凭据；因该受控子进程从未取得业务凭据或执行迁移，可在测试代码中保存凭据并将自己的审计行结算为 RUN_FIXTURE_STOPPED。此收尾保留行及原停止摘要，不使用 PID/端口猜测，不对生产未知运行提供通用清理入口，也不将其声称为生成应用的数据恢复。恢复前后状态另写入保留的私有验收目录。没有清除此前失败或未知审计。

18:58:50，三个真实崩溃窗口在旧入口全部红灯：预期 FAILED/NEEDS_RECONCILIATION，实际仍是 STARTING/STARTING/READY。接入后，19:05:16 **11 单元 + 23 集成**通过，零失败/错误/跳过，其中恢复 3、原构建调度 12、依赖准备调度 8。后两组使用同一最终生产代码，XML 单独保存，后续汇总不重复计算。

补充运行中出现未拥有意图的关闭场景：停止派发后，close 必须拒绝释放资源，原行不变，第二次打开文件根/协调租约均被拒绝；测试只结算自己从未发出执行指令的意图后，close 才成功。19:06:48，删除关闭阶段的运行阻断检查，定向用例因 close 错误成功而失败，其他两个崩溃窗口通过；源码逐字恢复。日志 `ex16-ex22b0-{red,green,mutation}.log`。本片 3 个主要文件，常驻资源装配与管理仍待 EX22b 后续片。

## 2026-10-25 · EX22a/b0 兼容与审计收尾

19:11:46，未限制 `-Dtest`，全部 **523 单元 + 89 项集成**通过，零失败/错误/跳过，4:38，平台打包成功。实际本轮 XML 为 108 个单元类、16 个集成类，按本轮开始时间筛选，无旧报告充数。集成选择 EX21c 的原 13 类，加 `ExecutionRunRecoveryIT,ExecutionRecoveryIT,ExecutionPreparationCrashIT`，包括本轮补充的关闭资源保留、原五个构建崩溃窗口与准备提交后崩溃。显式契约 profile 与三个准确私有目录沿用原授权，未启用真实模型或安装新环境资源。

同一最终生产代码在 19:05:16 已通过 `ExecutionSchedulerIT` 的 12 项和 `ExecutionPreparationSchedulerIT` 的 8 项；之后仅补充运行恢复测试，关闭变异逐字恢复，没有重跑这两组未改变的用例。其 XML 单独归档，检查与最终 16 类无重名，因此本轮合计 **109 项不重复集成**。三个变异分别验证待核对状态保留、写后协调权复核和关闭资源阻断，全部被拦截。

最终只读核对批准 schema：V027/V028 成功；原 A/B 绑定 UUID、项目、端口及物理 OID 不变；40 条运行全部终结，未完成运行、共享占位、绑定锁与迁移批准为 0。测试红灯运行保留，受控 HTTP 夹具只凭原停止证明收尾，没有生成业务迁移或通用未知记录清理。9 个实际私有值与公开代码差异、最终包 classes/resources 比较，零匹配。

10 份日志、本轮全部 XML、单独的调度 XML、只读审计及汇总保存在忽略目录 `.local/ex22-final-evidence-44mgiw5m`，目录 0700、文件 0600；最终 JAR SHA-256 为 `925d923221a97f162a1c4cd9531905a21f284208aa16d01eca5a2c283a8cb1b5`。本轮完成 EX22a、b0 及 c1；EX22 整项仍未完成，下一步 b1/b2 为常驻资源装配、统一管理及其关闭验收。源码/业务端到端与网页接入继续沿既定后续计划。只保存本地提交，未推送、部署、修改私有 env 或调用模型/SMTP。

## 2026-10-25 · EX22b1a 常驻 worker 的资源接入

原运行 worker 增加惰性工具检查：nativeWorker 在领取原始单次 ticket 后，使用原 Claim 的剩余启动预算捕获固定工具身份；应用引导和构造对象不提前扫描工具，也不为工具检查重开 120 秒。错误发生在执行意图之前时，由原 worker 的 notStarted 收尾。既有显式工具句柄入口保留，原健康、换版、停止及不明提交处理不变。

供后续管理器使用的 requestStopOwned 只向当前持有的原进程能力发送停止信号；hasHeldProcesses 反映真实能力是否仍被保留。停止信号不清空拥有表，必须等匹配停止凭据持久化确认后由 worker 原结算路径移除，数据库提交回执不明仍保留。

先缺接口/构造入口编译红灯。19:21:13，**43 单元 + 18 PG 集成**通过，零失败/错误/跳过；包含 RuntimeWorker 的惰性检查、检查超时/缺失工具、原进程与事务故障，以及原实际换版/状态契约。随后故意在停止信号后清空拥有表，两个未确认完成用例准确失败；生产源码逐字恢复。日志 `ex16-ex22b1a-{red,green,mutation}.log`，2 个主要文件。原生资源工厂和统一调度尚未接线，不把本片视为整个运行管理已完成。

## 2026-10-25 · EX22b1b1 运行与测试注册表隔离

装配运行配置时可显式核对测试注册表，拒绝跨用途复用数据库名、角色、绑定 UUID，以及让应用占用任何配置测试库的监听端口。比较只读取已加载身份，不暴露凭据或建立连接；实际物理目的标记检查仍由原启动契约负责。运行注册表新增只读文件复验，配置文件被改动即撤销，事务内禁止执行该文件检查。

缺方法红灯后，19:27:11 **25 单元 + 7 项真实绑定集成**通过，零失败/错误/跳过；新注册表 4 项、原测试配置 7 项、运行 worker 14 项，以及业务绑定/凭据装配的 7 项真实 PG 契约。移除交叉监听端口检查的变异使对应反例失败，源码逐字恢复。没有连接虚构单元测试地址或修改真实业务库。日志 `ex16-ex22b1b1-{red,green,mutation}.log`，3 个主要文件；工厂接线由 b1b2 完成。

## 2026-10-25 · EX22b1b2 原生运行资源工厂

资源工厂增加可选 runtime-databases 注册表，沿用严格 JSON/私有路径检查；空白表示没有运行资源，原测试/构建资源仍可打开。配置项目必须属于测试注册表，加载时排除平台库/角色及测试用途冲突，再打开原执行根。原生资源 check 同时复核注册表路径和业务私有配置文件身份；运行配置变化使继续使用失败，不动态切换数据库。

runtime 附接入口只接受一次内部协调回调，装配同一 service/worker 对；没有额外根租约、线程池或进程。准入、换版、停止经过资源/协调检查，execute 交给原 worker 先领取准确 ticket，再执行同预算预检；因此撤权仍能收尾未发出指令的任务。requestStopOwned 不依赖已撤销的配置，原进程能力仍由 worker 保留；根关闭额外拒绝仍持有的能力。调用方还必须等待所有准入/worker 线程结束才能关闭根，这一统一生命周期由 b2 管理器实现。

缺入口红灯后，19:33:10 **31 单元 + 23 PG 集成**通过，零失败/错误/跳过。新增 5 项工厂场景覆盖可选配置、非法注册表/未配置项目、单次附接、跨用途与平台角色拒绝、配置漂移及关闭后的根重新取得；不在装配时捕获工具、发起应用连接或创建运行任务。移除业务文件复验后，漂移场景准确失败，源码逐字恢复。日志 `ex16-ex22b1b2-{red,green,mutation}.log`，4 个主要文件。接口仍只在模块内部，协调者尚未调用该运行附接入口或自动派发应用。

## 2026-10-25 · EX22b1c 实际运行资源装配契约

新增公开配置模板 `PROJECT_EXECUTION_RUNTIME_DATABASES`，默认空白；未改私有 env 或启用任何实际应用。缺少 Spring 默认属性时，契约先明确红灯（预期空白、实际 null）。补齐配置后，20:02:48 **31 单元 + 6 项集成**通过，零失败/错误/跳过，平台打包成功；包括真实资源工厂 1、原运行/换版 1、原业务绑定 4。

新契约使用已有 A/B 测试和业务私有配置、实际 SourceAgentFixture 环境与 PG 协调租约，通过正式 ExecutionEnvironment 装配运行服务。工具路径刻意不存在，装配仍不扫描工具或生成应用进程；run/build 目录为空且未创建新运行。对 A 原终态运行执行真实授权的幂等 stop，返回原行且未改审计；撤销注册表后同一入口拒绝继续使用，原行和两组业务历史保持不变。关闭后实际重新取得根及协调租约。此项不执行原生 Spring 应用、迁移或常驻调度，完整业务验收仍属于 EX25–27。

日志 `ex16-ex22b1c-{red,green}.log`，3 个主要文件；新增配置仍独立于旧工作台运行功能，b2 的统一派发和关闭管理尚未完成。

## 2026-10-25 · EX22b1 兼容与审计收尾

20:11:57，未限制 `-Dtest`，全部 **534 单元 + 91 项相关集成**通过，零失败/错误/跳过，7:45，平台打包成功。显式使用 `project-execution-contract` 与原三个准确私有目录；在 EX22a/b0 最终 16 个集成类上增加 `ExecutionRuntimeResourcesIT` 和原 `ExecutionPreparationSchedulerIT#nativeSpringFactoryDispatchesTwoRoundsAndRejectsAnObsoleteBaseline`，核对原生资源工厂改造仍兼容原准备/构建派发。按本轮开始时间筛选，实际 XML 为 109 个单元类、18 个集成类，未引用旧报告或重复累加定向测试。

最终只读核对批准 schema：V027/V028 成功，原 A/B 两组绑定 UUID、项目、端口及物理 DB/role OID 保持不变；57 条运行全部终结，未结束运行、共享占位、绑定锁与迁移批准均为 0。业务历史核对在实际契约中通过，失败记录保留。9 个实际私有值与本轮公开差异及最终 JAR 的 classes/resources 比较，零匹配。

12 份日志、全部本轮 XML、只读审计及汇总保存在忽略目录 `.local/ex22b1-final-evidence-idpel20q`，目录 0700、文件 0600；最终 JAR SHA-256 为 `5c489810015437b23d0e52bfb8e3ceb1501d2d93f2d8569bf58e1549fa9224ee`。三个变异分别验证停止后保留未确认拥有能力、业务与测试监听端口隔离、运行配置文件身份复核，均被拦截并逐字恢复。

据此完成 EX22b1。资源装配尚未由协调者调用，EX22b2 的常驻派发、准入交接和关闭管理仍待实施；不将内部契约视为网页或原生生成应用的业务验收。新配置默认空白，本轮仅本地提交，未推送、部署、改变私有 env 或调用真实模型/SMTP。

## 2026-10-25 · EX22b2a 调度前的重放与换版停止

分离短事务 `existing` 查询和 `prepareReplacement` 停止阶段。查询始终重新核对维护者、项目权限、请求摘要和前后配置；满容量时可以返回原任务，不打开产物或签发新 ticket。换版准备即使发现旧任务已经终结，也只返回该事实；由后续调度器先保留名额，再调用完整准入。原直接 `replace` 仍组合这两个阶段，兼容既有调用。

独立缺方法红灯后，20:23:19 **37 单元 + 8 项 PG 集成**通过，零失败/错误/跳过，2:39，平台打包成功。定向单元为 RunAdmission/Worker/Journal，集成为 RunIT/RunHealthIT/RunAdmissionIT，显式契约 profile 与原三个私有夹具目录。覆盖实际停止/并发换版及旧单次 ticket 语义；新增组件证明重放不触碰不可读产物、配置撤销和已停止旧任务不会越过容量阶段。移除查询后的配置复核，新增用例明确失败，源码逐字恢复。日志 `ex16-ex22b2a-{red,green,mutation}.log`。3 个主要文件，尚未附接常驻调度器。

## 2026-10-25 · EX22b2b 有界常驻任务生命周期

内部 RuntimeDispatcher 使用独立 1–4 个常驻线程，一项任务覆盖准入预检、原 ticket 交接和 worker 全生命周期。请求身份先登记，巡检读取未结束记录时与登记共用短锁，避免提交后交接窗口的误判；文件/工具/物理库预检不占该锁。未拿到 ticket 的登记只容许准确同请求、尚未发出执行 UUID 的 STARTING，不收养 READY/未知意图。授权重放先于容量检查，重复尚未完成准入的请求共享结果；调用方取消返回的 Future 副本不取消实际任务。

关闭拒绝新操作，中断受管理线程并通知原拥有进程；任何已经返回的 fresh ticket 都仍交给原 worker 结算。外部短查询/停止调用也计入关闭等待；未结束元数据、未确认的拥有句柄或仍在执行的线程均不宣称结束。启动结果的异步完成只表示准入或正在等待旧运行停止，不代表应用已 READY。换版准备不占第二个运行名额，也不建立等待自动重启的隐藏队列。

缺类红灯后，首轮测试发现 Mockito 重新配置桩时触发旧阻塞逻辑，改用 doAnswer；另一次编译暴露新增测试方法的返回类型笔误，均保留失败日志。20:41:29 **39 项相关单元**通过，零失败/错误/跳过（10 个调度组件、15 个准入、14 个 worker）。包含四项目实际独立线程、满容量重放、冲突、调用方取消、迟到 ticket、查询期间关闭、未知完成保留、已结束元数据但仍持有句柄、换版停止阶段及事务拒绝。依赖为受控元数据/worker 替身，没有创建 OS 应用进程或持久运行行。

20:43:23，故意不交付关闭后迟到的 ticket，定向用例因任务无法终结准确失败，随后源码逐字恢复。日志 `ex16-ex22b2b-{red,green,green2,green3,mutation}.log`。2 个主要文件，尚待 b2c 接入 Coordinator 并完成 PG/实际进程组合和打包回归。

## 2026-10-25 · EX22b2c 统一协调者与实际常驻进程

协调者完成启动恢复后，通过原资源工厂附接运行服务与独立线程池。内部 start/replace/stop 入口仍分别核对维护者、所有者和原请求身份，不开放新的 HTTP 路由。巡检允许本轮登记且匹配的运行意图，其余未知或待核对状态停止共同派发；关闭等待构建线程、常驻线程及正在执行的准入/查询操作，再检查持久未决任务与原拥有句柄，最后释放原根和协调租约。两类线程共用原有 30 秒关闭预算。

独立红灯缺少协调入口和受控资源组合构造。20:52:32 **21 单元 + 3 集成**通过，零失败/错误/跳过，2:52，平台打包成功；单元为 RuntimeDispatcher/Environment，集成为 RuntimeScheduler 两场景与原 RuntimeResources 工厂契约。命令还选了一个不存在的 SpringDefaults 类名，未据此计数，实际 XML 只有上述两类单元；最终完整单元回归会覆盖已有 Spring 配置测试。

新组合使用原源码/绑定/业务历史和真实 PG 协调租约，实际启动持有停止能力的 HTTP JVM。并发 start 返回同一 UUID、只启动一次；应用 READY 时，同一项目的构建线程仍能启动并取消另一个实际受控 JVM。正式换版先持久停止旧应用，再启动新版本；显式 stop 保存真实停止摘要。另一个场景直接关闭协调者，实际运行进程退出，保存匹配 run/execution/cwd 的停止凭据，根和数据库租约随后能重新取得。协调者主动关闭按中断路径保存终态，不将其冒称用户显式 stop 的 STOPPED 成功路径；两类行为分别验证。两组业务历史保持不变，HTTP 夹具无业务凭据，封存 ZIP 仅作准确输入身份，不声称原生 Spring 应用/业务迁移验收。

20:55:25，另 **2 项组件测试**证明常驻任务未终结时不能释放共同资源，以及运行巡检故障会停止共同派发。该故障边界使用受控租约/调度器替身，真实 PG 和进程结果以此前组合为准。5 个主要文件；完整回归和变异结果续记于下方。

20:57:39，故意绕过运行巡检接线，故障用例因共同协调者错误保持运行而失败，另一个关闭保留用例通过；生产源码逐字恢复。日志 `ex16-ex22b2c-{red,green,guards,mutation}.log`。

## 2026-10-25 · EX22b2 兼容与审计收尾

21:18:02，全部 **548 单元 + 112 项相关集成**通过，零失败/错误/跳过，19:44，平台打包成功。未限制 `-Dtest`，启用原 `project-execution-contract` profile 与三个准确私有目录；在 b1 最终 18 类基础上加入 RuntimeSchedulerIT、原 ExecutionSchedulerIT，并运行完整 ExecutionPreparationSchedulerIT（不再只选一个方法）。实际 XML 按本轮开始时间核对：111 个单元类、20 个集成类，没有引用旧报告或重复累计定向结果。完整单元包含真正的 `ExecutionCoordinatorSpringTest` 两项。

本轮运行了原构建调度 12 项与依赖准备调度 8 项；后者涉及真实工具/源码操作及多种关闭恢复，耗时 583.8 秒，8 项均通过。原恢复、运行状态、物理绑定、迁移、准入、Agent 生命周期及初始化兼容一并通过。三次变异分别核对重放查询后的配置复核、关闭后迟到 ticket 不丢失，以及运行故障必须接入共同巡检，均明确失败并逐字恢复。

最终只读核对批准 schema：V027/V028 成功，原 A/B 两组绑定 UUID、项目、端口和物理 DB/role OID 保持不变；77 条运行全部终结，未结束运行、共享占位、绑定锁与迁移批准均为 0。失败日志和旧审计保留，没有强制结算未知历史运行。9 个实际私有值与本轮公开代码差异、新增测试和最终 JAR 的 classes/resources 比较，零匹配。

13 份日志、全部本轮 XML、只读审计及汇总保存在忽略目录 `.local/ex22b2-final-evidence-pnk0yosc`，目录 0700、文件 0600；最终 JAR SHA-256 为 `14930f0ea69336a40fe390c780ceb9821ce0e8d9814039c50993163742b15f8f`。据此完成 EX22b2 与 b。EX22c2 的实际常驻失权/元数据故障组合和 d 收尾仍待完成，未将组件故障替身等同于真实数据库断连；EX22 整项、原生业务应用和网页交付仍保持未完成。新模式默认关闭，只保存本地提交，未推送、部署、改私有 env 或调用模型/SMTP。

## 2026-10-25 · EX22c2a 常驻运行失去协调权

将实际协调者和资源工厂的不可用错误归入 `RUN_COORDINATOR_UNAVAILABLE`，避免 READY 后被误记为普通启动失败。两项新增用例先复现原错误码，21:28:21 红灯；21:34:30 **36 单元 + 4 集成**通过，零失败/错误/跳过，4:14，打包成功。单元为 RunWorker/RunJournal/RuntimeDispatcher/RuntimeCoordinator，集成为 RuntimeScheduler 的三种场景及 RunHealth。

真实失权场景仅撤销本轮专用连接持有的协调 advisory lock，不终止外来会话。共同协调者停止派发，原 HTTP JVM 确实退出，匹配 run/execution/cwd 的停止摘要持久化，运行进入 FAILED，根和租约可重新取得。工作线程直接观察失权与收到调度器停止信号存在竞态，允许明确的协调不可用/取消/停止诊断，不允许泛化的启动失败；精确映射另由上述两项确定性用例验证。原业务绑定和迁移历史不变。

21:39:55，移除新错误码映射，两项新增用例均因实际错误回退到 `RUN_START_FAILED` 而失败；生产源码逐字恢复。日志 `ex16-ex22c2a-{red,green,mutation}.log`。元数据不可用和真实提交后回执丢失的组合留待 c2b，未据此完成 EX22 整项。

## 2026-10-25 · EX22c2b 未确认完成时保留原拥有能力

新独立测试 JVM 装配真实 PG、源码读取、协调者、运行调度器和原进程拥有者。父测试只传入 0600 私有输入文件路径，子 JVM 不继承日常环境；受控 HTTP JVM 不含业务数据库凭据。夹具从实际类来源取得 classpath，使其能在独立 cwd 运行，不依赖 Maven 工作目录。21:42:38 缺探针类红灯，21:45:59 **26 单元 + 2 集成**通过，零失败/错误/跳过，2:48，平台打包成功。

第一场景在实际 READY 后关闭仅供本轮运行元数据使用的连接池。worker 停止原 HTTP 进程，完成写入失败；真实 PG 中的 READY/执行意图仍原样保留，不臆造终态。第二场景仅在本轮完成事务确实提交 PostgreSQL 后，由测试事务适配器丢失一次确认；PG 已有 STOPPED 和真实停止摘要，worker 因未收到确认仍保留原进程句柄，不重试写入。该场景是实际提交后的受控确认异常，不是网络丢包；第一场景也没有停止 PostgreSQL 服务。

两个场景均核对实际进程已退出、仅一次启动、相同和新幂等键均被共同协调者拒绝、关闭不成功、资源未关闭。父测试在探针仍存活时实际尝试取得同一执行根与数据库协调锁，均被拒绝。断言完成后，探针的原拥有者只用原停止凭据结算自己的无业务凭据 HTTP 夹具；第二场景不再写入已完成记录。该测试清理没有为生产 worker 增加重试或清理接口，也没有重建停止凭据；清理后拥有能力仍保留，第二次关闭仍拒绝，直到测试 JVM 退出，父测试才能重新取得根与租约。原绑定和业务历史保持不变。

21:48:10，在单元边界故意让 worker 在异常后丢弃原句柄，两项失败完成用例均因缺失原对象而准确失败；源码逐字恢复。该变异没有对真实 PG 故障夹具执行，避免将错误实现写入持久验收状态。日志 `ex16-ex22c2b-{red,green,mutation}.log`；3 个主要测试文件，无新增生产端点。EX22c 完成，整项仍待 d 的兼容、审计与归档收尾。

## 2026-10-25 · EX22c2 收尾失败与停止等待修复

后续结果撤回上述 EX22c 的整项完成状态。21:58:14 收尾 `verify` 失败，8:58：**550 单元通过；95 项集成中 89 通过、5 失败、1 错误，零跳过**。本轮使用 19 个集成类，覆盖原恢复、运行状态、绑定、迁移、源码/Agent 兼容及新增故障探针；此前已通过的 20 项构建/准备调度未重复运行。真实失锁场景在关闭时不能结算，随后恢复/准入/资源及两个故障探针被未决运行阻断；没有跳过它们或宣布全部兼容通过。

原 A 项目留下运行 `5f0bdb38-0816-42fd-b6de-075da6ef4dce`：`NEEDS_RECONCILIATION`、pending=true，已有 READY 时间但没有停止摘要和完成时间。只读核对共 99 条运行、1 条未完成；原两组绑定、项目、端口、物理库/角色 OID 不变，迁移批准、共享占位、绑定锁和协调锁均为 0。原测试 JVM 已退出，不能从 PID、端口、目录或日志重建停止能力；该记录和四份更早的失败审计均未清理。失败前完整 XML 和日志保存在 `.local/ex22c2-failed-final-o835id18`。

定位发现 worker 在停止等待遭遇 `InterruptedException` 时立即选择未知结果，未用完原停止预算。新增两项用例用真实 HTTP JVM 和委托原拥有者的等待适配器，在清理等待分别注入 1 次/3 次中断；初次有一处测试适配器的受检异常编译错误，修正后 22:03:31 两项均准确失败。修复为共用原 12 秒截止时间继续等待同一进程的实际凭据，超时仍保留未知状态，最终恢复线程中断标志；完成事务仍只调用一次，不重试数据库写入。实际综合失败没有记录此次等待究竟因中断还是超时结束，故这证明了一个确定缺陷，仍不能单凭复现认定原场景已完全修复。

22:08:39，修复后 **552 单元通过**（111 类，零失败/错误/跳过），`package` 成功，4:37。18 项 RunWorker 测试包含上述两个确定性中断场景；它们验证同一实际停止凭据、一次完成写入、原句柄在确认后释放及中断标志恢复。没有重跑会被原待核对记录阻断的 PG 集成，也未新建环境。日志 `ex16-ex22c2c-{red,red2,green}.log`。

修复后的本轮 XML、10 份日志、只读审计和汇总保存在 `.local/ex22c2-unit-evidence-x2nfox8o`（0700/0600），明确标记集成未复验并链接失败归档。9 个实际私有值与公开代码差异、最终 JAR classes/resources 比对零匹配；最终 JAR SHA-256 为 `8d397f2873cc134e1bb70074b9c6effe79abb28b8f6a6861737e73aaf155b55c`。源码与记录只作本地提交，没有推送、部署、真实模型/SMTP 或私有 env 修改。EX22c2c、c/d 与整项保持未完成；拟议的新测试 schema 尚未获准或执行。

## 2026-10-26 · EX22 新环境授权与故障复验

用户回复“确认”后，在原 `atoms_demo_test` 创建准确目标 `atoms_verify_20261025_ex22`，仍归原 `atoms_test`，撤销 PUBLIC 权限，不授予数据库 CREATE，不新增数据库或角色。创建事务核对五份旧未决审计、原 99 条运行和 A/B 绑定摘要保持不变。业务库继续使用原四份私有配置和原 A/B 物理身份；源码夹具新建于忽略目录 `.local/project-runtime-ex22-6kc9y53e`，原源码及执行证据均保留。

07:44:51，7 项设置单元通过、4 项绑定契约均因实际新 schema 与旧守卫不匹配而失败；拒绝发生在写入项目/绑定前。将准确目标集中到测试常量 `ExecutionTestTarget` 和测试 profile，28 个调用处按每批最多 5 个主要文件机械替换，不改生产逻辑或私有 env。旧历史 schema 的宽松候选同步收紧为准确当前目标。07:46:44 **7 单元 + 4 项真实绑定集成通过**，零失败/错误/跳过，36.773 秒，平台打包成功。日志 `ex22-target-{red,green}.log`。

07:49:06 **38 单元 + 8 项实际进程/PG 集成通过**，零失败/错误/跳过，1:33，平台打包成功。定向单元为 RunWorker/RunJournal/RuntimeDispatcher/RuntimeCoordinator；集成为 RunAdmission、RunHealth、RuntimeScheduler、RuntimeFault、RuntimeResources。上次失败的 READY 后撤销协调锁场景，此次确实停止原 HTTP JVM、持久保存准确停止凭据并释放原根/租约。另两个故障探针验证完成未知时保留能力和不重放，父测试在探针退出前不能取得根/租约，退出后才可取得。只停止本轮实际拥有的无业务凭据 HTTP 夹具，未处置旧失败运行，也没有执行实际业务迁移。

本次说明的是修复后的原场景复验通过，不把换 schema 本身当作修复，不据此消除原失败记录或宣称已完全证明所有历史竞态根因。日志 `ex22-runtime-recheck.log`，相关完整回归及只读收尾仍待下方最终结果。

## 2026-10-26 · EX22 整项收尾

07:54:59，新批准目标上的相关收尾 **552 单元 + 95 集成通过**，零失败/错误/跳过，5:16，平台打包成功。未限制单元选择，按本轮开始时间核对 111 个单元类和 19 个集成类的真实 XML；未累计此前定向数量或引用旧报告。集成选择与上次失败的收尾一致，包括原 RunRecovery/PreparationCrash/Recovery、运行准入/状态/健康/实际调度/故障探针、绑定/迁移，以及源码/Agent 初始化与生命周期。真实失锁场景在定向及收尾各通过一次。此前已经通过且本次未改逻辑的 20 项构建/准备调度没有再次运行，不计入本次 95。

最终只读核对：新 schema V027/V028 成功，26 条运行全部终结，未决运行、共享占位、迁移批准、协调/绑定锁均为 0；原 A/B 绑定 UUID、项目、端口和物理 DB/role OID 一致。五份旧未决审计、原 schema 的全部 99 条运行及绑定摘要与创建新环境前完全一致。新 schema 仍归 atoms_test，PUBLIC 权限撤销，数据库 CREATE 权限未增加。没有新建业务库、角色或改私有 env，没有删除旧记录或重建停止凭据。

4 份本轮日志、完整命令与测试 runner、本轮 XML、创建/只读审计、两个故障探针的保留/退出证明保存在 `.local/ex22-recheck-evidence-4qmexg3s`，目录 0700、文件 0600；此前失败归档 `.local/ex22c2-failed-final-o835id18` 保留并在汇总中关联。9 个实际私有值与公开差异及最终 JAR classes/resources 比对零匹配。最终 JAR SHA-256 为 `8d397f2873cc134e1bb70074b9c6effe79abb28b8f6a6861737e73aaf155b55c`，与上轮已修复的生产代码一致；本轮只更新测试目标守卫和文档。先前两次故障映射/句柄保留变异、停止中断的确定性红绿测试及本轮环境红绿证据均保留。

据此完成 EX22c2c/c2d、c/d 和 EX22 整项；C-EX08 仍须 EX23–24。当前只证明内部执行模块的构建/常驻生命周期与故障处理，EX25–27 的实际业务样本、交付模块、工作台和真实模型联调保持未完成。新模式默认关闭，修改只保存本地提交，未合并、推送、部署或调用模型/SMTP。

## 2026-10-26 · EX23 交付身份契约

EX23a 补齐协调者的只读资源范围，复用原封存目录和原 worker 的进程能力；不另外取得执行根租约。句柄签发前、读取后及每次复用均检查协调权和资源寿命，事务内访问、失锁或关闭后拒绝。先获得缺失接口的编译 RED，08:11:27 定向 16 项通过。新增用例明确只证明资源生命周期装配；下面的 EX23b 再组合真实 PG、文件、Git 和进程。

EX23b 增加内部 `ProjectExecutionAccess`，公开不可自行构造的 BuildHandle/RunningHandle，返回固定来源版本、树/JAR/清单摘要及运行/执行 ID。每次使用重新核对维护者配置、项目归属/归档、原成功构建和文件；运行还要求原 worker 持有相同执行身份的存活进程。元数据使用短事务，源码及文件 I/O 共用 30 秒预算并在事务外执行，返回前再次复核。来源快照不读取构建 cwd，也不追随项目最新版本。错误通过白名单转换为固定说明，不外传底层异常。

先获得缺失接口 RED；首次 GREEN 因 Java 17 不接受全 throw 分支的 switch 表达式而编译失败，改为返回异常值再 throw 后，08:17:24 **22 单元 + 3 集成通过**，零失败/错误/跳过，1:35，平台打包成功。真实 Git 在读取 V1 的中途接受 V2，仍返回 V1 的源码/摘要；受控测试项目验证跨 owner/project/签发者、归档、转让、配置变更和读取途中关闭。封存 JAR 同字节换 inode 使原句柄失效，新句柄重新验证后可读，随后篡改字节连新授权也拒绝。实际 HTTP JVM 到 READY 后签发运行句柄，停止意图一旦提交即拒绝旧访问，最终停止摘要匹配原进程凭据。业务库历史未变，A/B 既有源码头未改，版本竞争只在本轮新临时源码项目中进行。HTTP JVM 和封存元数据为显式受控夹具，不作为真实生成应用构建/业务功能验收。

08:19:11，故意移除返回前的 `compare`，读取途中归档用例因“应拒绝却返回”准确失败（1 项失败，零错误/跳过）。原生产源码逐字恢复并核对 Git 无差异，保留变异 patch、失败 XML 和日志。没有在变异实现中启动运行夹具或修改原业务数据。

08:26:44，最终 **555 单元 + 98 相关集成通过**，零失败/错误/跳过，7:08，平台打包成功。按本轮开始时间核对 112 个单元类与 20 个集成类的新 XML，不累计定向或旧报告。集成选择为 EX22 收尾的原 19 类加 ExecutionAccessIT，覆盖原运行/恢复/故障探针、绑定/迁移及源码/Agent 初始化与生命周期；未重复没有改逻辑的较早构建/准备调度验收，也不把这些数量计入本轮。

只读核对当前 schema 的 43 条运行全部完成，共享占位、迁移批准、协调/绑定锁均为 0。原 A/B 绑定项目、端口和物理 DB/role OID 未变；五份旧未决审计及旧 schema 的 99 条运行与绑定摘要均与原基线一致。未新增数据库/角色或扩大权限。9 个实际私有值与本轮公开差异及最终 JAR classes/resources 比对零匹配。JAR SHA-256：`2716fe7e3b267cd65e30dc407725895110776a20a87705f8bd23f8eb175b5dc4`。

7 份日志、当前 XML、变异 patch/失败 XML、完整命令、runner、只读审计和两个故障探针证明归档到 `.local/ex23-evidence-9van_fcg`，目录 0700、文件 0600，继续关联旧失败归档。实现与自审已完成，EX23 整项据此勾选；C-EX08 仍待 EX24。仅本地提交，未合并/推送/部署或调用真实模型/SMTP，未修改私有 env。此处交付完成指内部身份/源码交接契约，HTTP 代理、ZIP 与工作台接入和 EX25–27 真实业务闭环仍未完成。

## 2026-10-26 · EX24 有界查询与安全诊断

EX24a 为任务与事件增加只返回已解析字段的诊断 DTO。状态、操作、工具、阶段和错误码必须匹配明确白名单，计数及 UUID 受类型和范围检查；未知错误前缀不能透传任意字符串。非法 JSON、重复键、过长内容或已知字段类型错误返回固定不可用诊断，未知附加字段不复制，结果不可变。缺方法 RED 后，08:37:27 的诊断、前端与 Java 报告兼容 **14 项单元通过**。

EX24b 追加 V029，为依赖准备和运行补充不可变事件流；迁移前的任务不补造历史。事件与状态变化同事务，无效顺序、单条超过 1024 字节、累计超过 1000 条或 64 KiB 均拒绝。计数按实际事件记录核对，构建表也补同样的直接插入约束；未修改已应用迁移或终态任务。RED 为 2 项失败及 6 项缺表错误，08:41:31 的 **5 单元 + 38 集成通过**，仅迁移已批准测试 schema。配额反例还检查结算失败后完成时间、占位和编辑租约一并回滚；合成绑定及测试元数据均随测试事务回滚，不新建永久数据库绑定。

EX24c 增加内部 `ExecutionQuery`，统一构建、准备和运行的任务列表及事件页。任务使用 `(created_at,id)` 倒序游标，事件按序号增量读取；每页 1–100 条并最多多取一条判断后续页。游标绑定所有者、项目、类型及用途，事件再绑定任务，须对应真实锚点。每页重新核对当前项目归属和维护者权限，只返回原任务所有者的记录；归档可读审计，转让不会把旧任务直接暴露给新所有者。事件尾游标保留供后续轮询。原始日志、源码正文、命令、路径、JDBC 和异常原因不返回。

缺类 RED 后 **5 单元 + 17 集成通过**。追加超出 PG 时间范围的伪造游标时，先准确复现 503 而非无效游标的错误；改为先按作用域/ID 读取真实锚点并比较，再绑定已核对时间。08:48:54 的 **5 单元 + 17 集成再次通过**，零失败/错误/跳过。权限、分页上限、新任务插入、相同时间顺序、事件尾续读、跨任务游标、未知错误和秘密 canary 均有覆盖。随后故意移除任务 owner 过滤，归属变更用例因新 owner 错误读取旧任务而失败，正确源码逐字恢复；保留变异 diff、失败 XML 和日志。

09:00:08，最终 **557 单元 + 180 相关集成通过**，零失败/错误/跳过，7:56，平台打包成功。按本次开始时间核对 112 个单元类、29 个集成类的新 XML，不累计此前定向数量。事件触发器影响构建及准备/运行状态写入，因此除 EX23 原有相关验证外，补选构建/事件 schema、构建准入/结算、探测/前端/Java 阶段日志及准备准入/生命周期；实际进程故障、恢复、绑定/迁移和源码/Agent 兼容也通过。这不是全部集成套件，亦未替代 EX25–27 的 npm/Maven 真实业务应用验收。

只读审计确认当前 59 条运行全部完成，共享占位、迁移批准和协调/绑定锁均为 0；原 A/B 绑定、端口及物理 DB/role OID 不变。五份旧未决审计、旧 schema 的 99 条运行和绑定摘要保持原样，新 schema 仍归原角色且未增加 CREATE/PUBLIC 权限。未新建业务库或角色。9 个实际私有值与公开差异及最终 JAR classes/resources 比对零匹配。最终 JAR SHA-256：`e54939f19e22c08594a00b96eeb1e2147795b1b092a635e5cb01a34a310acd5d`。

10 份日志、本轮 XML、变异证据、命令、runner、只读审计与两个实际故障探针证明归档到 `.local/ex24-evidence-i873itl2`，目录 0700、文件 0600，继续关联旧失败归档。完成 EX24 和 C-EX08；下一项 EX25 为真实待办后端样本。新模式默认关闭，本轮仅本地提交，未合并/推送/部署、调用模型/SMTP 或修改私有 env；网页接入与预览/下载交付模块仍待后续完成。

## 2026-10-26 · EX25 真实待办后端样本

新增 5 个主要样本/验收文件，不改平台生产代码、默认模板或依赖政策。V1 直接用 `JdbcTemplate` 和独立应用表实现新增、分页、单条查询与修改；revision 在单条 SQL 中比较和更新，过期提交返回 409。标题按 Unicode 字符数校验，拒绝空值、超长及控制字符，SQL 参数绑定。V2 是新的完整源码快照，实际改变 Java 输入/输出与查询，新增优先级和 V003 SQL；未传新字段的旧客户端保留原值。两个版本拥有不同源码和 JAR 摘要，不通过平台通用数据接口代替应用后端。

先编写契约得到缺少 `ExecutionTodoFixture` 的 RED；初次实现的夹具混用了提交视图与数据库任务类型，编译准确失败，分开 accepted/task 后修复。10:13:49，**5 平台单元 + 1 原生契约通过**，零失败/错误/跳过，10:04，平台打包成功。该契约串行调用真实源码接受、调度、npm、类型/lint/单测/Vite、Maven 和 JAR 封存，产生两份真正业务工程产物：

| 样本 | 工程内前端测试 | 工程内 Java 测试 | 源码版本 | JAR SHA-256 |
| --- | --- | --- | --- | --- |
| V1 待办 | 15 | 10（健康 4 + 业务 6） | `e08ecb09-201c-4a7e-9fb9-2a6d98345109` | `86478b2ec3943d4e13f5431ae324aaf79970530b5616eaf278b3e1a66de82aef` |
| V2 优先级 | 15 | 11（健康 4 + 业务 7） | `c258b56b-1e96-419e-9905-5c6a5d1f6226` | `a2cfd26003a611aca5273e14cd576fceb38375894c4d5c9f8869d43facf0c8ba` |

前端 15 项来自尚未改成待办页面的原模板，不宣称已完成 EX26。Java 测试使用真实 Spring MVC 调度和 PostgreSQL，实际检查行数据、重新实例化控制器后的查询、分页边界、输入拒绝、参数绑定及过期修改。测试初始化限制准确 A/B 测试库及实际数据源属性；DDL 与数据操作都在测试事务内执行并回滚，禁用自动 Flyway，不做 clean/repair，不迁移业务库。每次构建后重新核对测试库物理身份与对象数未变，JAR 内包含编译后的 TodoController 和对应 SQL，V2 多出 V003。这里没有启动常驻业务 HTTP 服务，也没有把事务内 SQL 执行等同于业务库升级或重启持久化验收。

10:20:27，**58 相关单元 + 1 原生变异契约通过**，零外层失败/错误/跳过，4:45，平台打包成功。仅在新测试项目的源码副本中把 `AND revision=?` 改成 `AND ?>0`，保持合法 Java/SQL 及参数数量；原始样本文件始终不变。实际构建执行到 Maven，业务报告 6 项中准确失败 1 项、零错误/跳过：过期修改应返回 409 却返回 200。工程状态为 FAILED / EXECUTION_PROCESS_FAILED，没有封存产物，停止意图及占位正常结算；测试表和数据依旧回滚。这个内层失败是预期证据，不能算成正常样本的通过项。两版正常构建未重复执行；本轮没有生产改动，故按影响范围验证模板、源码政策、npm/Maven 策略及诊断单元，未重复上轮全平台 557 项或全部集成。

只读核对当前 59 条运行均已完成，共享占位、迁移批准和协调/绑定锁为 0；原 A/B 绑定、端口和物理身份，以及五份旧未决审计和旧 schema 全部运行/绑定摘要保持不变。9 个实际私有值与全部本轮差异、两个业务 JAR 及平台 JAR 的 classes/resources 比对零匹配。平台 JAR SHA-256 仍为 `e54939f19e22c08594a00b96eeb1e2147795b1b092a635e5cb01a34a310acd5d`，符合本轮未改生产类/资源。

日志、两版 manifest/前后端报告、变异失败报告、外层报告、完整命令、runner 和只读审计归档于 `.local/ex25-evidence-x4y3ca23`（0700/0600），原源码、构建目录和成功 JAR 保留在本轮私有契约根，继续关联旧失败归档。完成 EX25，C-EX09 仍待 EX26–27。仅本地提交，未推送/部署、调用模型/SMTP 或修改私有 env；下一步为对应 Vue 业务页面和后续常驻应用闭环。

## 2026-10-26 · EX26 Vue 业务页面

EX26a 新增 App、请求 composable 和组件测试，保持默认模板不变。V1 展示真实待办 API 对应的添加、分页、编辑和完成操作；V2 增加优先级控件。输入/响应校验与后端契约一致，列表只统计已加载条目；未知写入结果不自动重试，409 保留草稿并明确放弃编辑后刷新。纯 UI 快速验证使用独立临时副本和已有同版本 node_modules，不修改默认工程或全局工具配置；真实 worker 的依赖安装与打包另在 EX26b 验证。

组件测试先在原初始化页面上得到功能缺失 RED。首次实现发现列表内 `ref` 是集合、导致编辑聚焦异常，修正为在当前列表内定位唯一编辑输入；无未处理异常后两版通过。另补“已完成筛选下新增”回归，先准确失败为新增条目不可见，成功添加时切回全部后通过。最终两版各 **22 项前端测试**（待办 14 + 原开发边界 8），类型检查、lint 和 Vite 构建通过，无跳过。覆盖重复提交、确认后更新、冲突草稿、未知写入、去重分页、异常响应、Unicode 长度、超时及卸载取消。

Playwright 自带浏览器缓存缺失，保留失败日志；改用已安装 Chrome 的全新临时上下文，未安装新浏览器或连接个人会话。两版在 **320/768/1024/1440** 宽度下均无横向溢出，所有可见输入/按钮/select 至少 44px，axe WCAG A/AA 扫描零违规。实际键盘跳转、新增后的焦点、编辑聚焦/保存后焦点、完成筛选、V2 优先级和冲突输入保留均通过。健康流程无控制台错误/警告、无 JS 异常；注入 409 后各有一条预期资源错误，单独记录，不将其伪装成健康流程零错误。已查看桌面与 320px 截图，均无遮挡或截断。API 是浏览器受控响应，明确不是 Java/PG 联调；原生日志、截图和脚本连同 b 的原生构建结果统一归档。

EX26b 先由缺少样本装配方法得到编译 RED，接入后 **11:05:41，59 项相关单元 + 1 原生契约通过**，零失败/错误/跳过，耗时 10:01。该契约串行执行两版完整工程的源码接受、受控 npm 安装、类型/lint/Vitest/Vite、Maven 与封存；未改变默认模板、依赖政策或平台生产代码。

| 样本 | 工程内前端测试 | 工程内 Java 测试 | 源码版本 | JAR SHA-256 |
| --- | --- | --- | --- | --- |
| V1 Vue 待办 | 22 | 10 | `ff1f2194-2c9b-408b-bc84-9daab6d18c76` | `8973110e361e95ab83641bf79905f3e78bd4c1aeede840fbb1392b72bcbfd636` |
| V2 Vue 优先级 | 22 | 11 | `036d78ed-2a2d-421e-a156-3eeec41d2f7c` | `d70ebab66b0016e750ef5ed01e5d994cd71eb09708ab390fdd4369c159526cbb` |

每版 JAR 均核对 TodoController 与迁移文件；V2 含新增 V003。静态资源清单数量和每个文件的字节与本次 worker 的 dist 完全一致；归档时又逐项对比已通过浏览器验收的快速构建 dist，两个版本均一致。两版 Vue 编译资源摘要不同，V1 为 `eaa78f00f0c851446da0e2e3ec27f2258145813348d1cde3cefb1a63d78cd344`，V2 为 `adfeb41fd369b896f1708bf3019235ac800f21f8f13a6d4ab61bf8ad980e2c97`。这证明浏览器检查的页面确实进入对应工程包，但浏览器 API 仍为受控响应；实际 Java 业务测试仍是专用 PG 上的 Spring MVC 测试和事务回滚，不冒称已验证常驻 HTTP、业务迁移或重启持久化。

只读核对当前 59 条运行均终结、占位/批准/协调与绑定锁均为 0，原 A/B 绑定、端口及 DB/role OID 不变，五份旧未决审计和旧 schema 全部运行/绑定摘要不变。测试 schema 已在 V029，无新增迁移；两次构建均重新检查测试库身份与对象数。9 个实际私有值与本轮完整差异、两个工程 JAR 及平台 JAR 的 classes/resources 比对零匹配。平台 JAR SHA-256 仍为 `e54939f19e22c08594a00b96eeb1e2147795b1b092a635e5cb01a34a310acd5d`。

全部红绿日志、浏览器摘要/四张截图、脚本与完整命令、两版 manifest/前后端报告及逐文件摘要、外层 XML 和只读审计归档于 `.local/ex26-evidence-dpxn7yly`（0700/0600），原工程与 JAR 保留，继续关联旧失败归档。完成 EX26，C-EX09 仍待 EX27 两项目两版本的常驻业务闭环。仅本地提交，未推送/部署、调用真实模型/SMTP 或修改私有 env；本次业务页面为验收样本，工作台和新的预览/下载交付仍未接入。

## 2026-10-26 · EX27 常驻业务验收前的夹具兼容

实施前发现原运行夹具把保留项目固定为单 SQL 源码，ZIP 也固定写入该 SQL。EX27 的实际应用会保留完整源码并迁移业务库，因此先拆出 EX27a1/a2，保持原运行与故障测试可在业务验收之后继续复用，不通过清库或覆盖源码版本恢复旧前提。真正的 HTTP/迁移/重启业务验收仍在 EX27b/c。

EX27a1 新增测试专用 `ExecutionContractSupport`：仅从固定 A/B 项目及当前已批准测试 schema 读取真实源码快照，重新核对完整树摘要，只允许原单 SQL 样本及 EX26 两份已评审待办样本。未知源变化直接失败。运行元数据 ZIP 逐字节包含来源中的全部迁移；没有迁移的通用访问夹具只写明确的测试标记，不凭空添加 SQL。该 ZIP 没有业务类，也不算成功原生构建。生产迁移检查、真实运行器和默认模板均未改变。

缺共用助手时编译 RED；初次实现的 **9 单元 + 5 集成通过**。自审发现故障探针的 cwd 不在仓库，补充受控 Java 子进程后准确失败；将允许范围固定为 EX26 已评审树摘要，并由单测对照实际样本，消除运行时对仓库相对路径的依赖。11:19:10，最终 **10 单元 + 5 集成通过**，零失败/错误/跳过，耗时 1:23。集成包括真实 PG/Git、受控 HTTP 子进程、启动/替换/停止和交付句柄，业务库仅只读检查，未启动待办 Spring Boot 应用或迁移业务数据。日志 `.local/ex22-ex27a1-*.log`，最终 XML 独立保留于 `.local/ex27a1-final-reports`。

EX27a2 将调度、故障探针和运行访问三个消费者接到同一来源读取。11:22:27，**10 单元 + 8 集成通过**，零失败/错误/跳过，耗时 2:00。覆盖调度中的构建与常驻进程并行、换版、关闭、租约失权、两个独立 JVM 中的元数据不可用/提交回执丢失，以及运行访问句柄的撤销。原停止能力和保留证据规则不变；两个探针均保留各自 retained/verified 证明。此处仅调整测试夹具，不改变平台生产构建/运行行为。

最终只读审计为当前 schema 78 条运行全部终结，任务占位、迁移批准和协调/绑定锁均为 0；原 A/B 绑定/端口/物理身份以及五份旧未决审计和旧 schema 全部运行/绑定摘要不变。9 个私有值与全部 EX27a 差异及平台 JAR classes/resources 比对零匹配；平台 JAR 摘要仍为 `e54939f19e22c08594a00b96eeb1e2147795b1b092a635e5cb01a34a310acd5d`。红绿日志、两组最终 XML、故障证明、命令/runner 与只读审计归档于 `.local/ex27a-evidence-0tgb_dbd`（0700/0600），旧失败归档保留。

EX27 整项及 C-EX09 仍未勾选：接下来需要真实待办 Spring Boot 进程、两个独立业务数据库、HTTP 增改查、V2 迁移批准和重启持久化，并在实际业务迁移后复验旧运行夹具。当前不能把空库上的受控 HTTP 夹具检查当作该业务闭环。本轮仅本地提交，未推送/部署、调用模型/SMTP、修改私有 env 或清理原数据库。

## 2026-10-26 · EX27b0 原生工具探测耗时修复

EX27b 首次构建在 Node 版本探测的 10 秒期限内未完成，尚未安装依赖或启动业务应用。诊断测得原探测的工具核对约 3.392 秒、源码复核约 4.634 秒；工具核对同时扫描了该命令未使用的 npm/JDK/Maven。该耗时说明超时余量不足，单次测量不构成稳定性能基准。失败构建、源码版本和原报告均保留。

`verifyProbe` 改为核对固定命令实际使用的工具：Node；Node + npm；JDK；JDK + Maven。整套捕获、构建及封存核对保持不变，输入复核仍在原进程 preflight 内，原 10 秒及整轮预算、取消和停止收据不变。回归先证明旧实现在无关 Maven 文件变动时错误地阻断 Node；修复后 **16:26:42，13 单元 + 12 集成通过**，覆盖依赖组合、工具变更、取消、期限及持久探测日志。该命令中的 `ExecutionBuildWorkerIT` 因契约标签未被选择，未计入通过数。

**16:32:46，6 单元 + 1 真实测量契约通过**：Node 探测前的工具核对 967 ms，源码复核 5493 ms，整次探测 6711 ms；工具全量核对仍为 3912 ms。临时测量方法未进入正式测试源，诊断源码、红绿日志和 XML 保留于 `.local/ex27b-probe-scope-evidence`。原生应用完整构建/启动仍需继续 EX27b；不把这项性能修复当作业务 HTTP、重启或 V2 升级已完成。

## 2026-10-26 · EX27b 双应用真实 HTTP 与重启持久化

新增 `ExecutionApplicationFixture` 与契约，复用 TodoFixture 的 Spring 上下文装配（3 个主要文件）。准确使用获准的 A/B 项目、四份私有业务/测试配置和 loopback 18181/18182；只接受原单 SQL 或评审过的完整 V1/V2 来源。源码更新均通过原编辑网关追加不可变版本，构建通过原持久服务与 worker，启动/停止通过原协调器，不直接操作 PID 或清理历史。

缺夹具编译 RED 后，首次真实执行暴露 npm 官方符号链接被测试入口过早拒绝；把目录路径验证与工具路径验证分开，后者由现有工具身份机制负责。第二次执行的 Node 超时保留为失败记录，经 EX27b0 修复后，**16:51:47，10 单元 + 1 原生契约通过**，零失败/错误/跳过，耗时 16:50。两个完整 V1 工程各通过 22 前端测试、10 Java 测试及类型/lint/Vite/Maven，实际封存如下：

| 项目 | 构建 | 源码版本 | JAR SHA-256 |
|---|---|---|---|
| A | `cfbfb3bf-31bd-4404-99ea-5e3199436386` | `eb7ad71c-ca91-49ea-9997-56b899345e90` | `3b7fc6f69857f0154a50547358547615510c1a02a10983b7395bcc158fcfc741` |
| B | `218804a9-dccc-4197-8ac6-9633a83fdbae` | `bf502c88-0b11-4f19-8428-34f5ab72721a` | `7d72b05248a24f0b82f03ddc146ee4b3354a273ea55a1cd2d9f873971872eaa5` |

两份源码树均为已评审 V1 `f17d13529edc70c4936cabc73e8b3bc4d569dc2416305033c480a5633cb22f73`；独立构建的 JAR 摘要不要求相同。两个真实 Spring Boot 进程 PID 分别为 51701、52016，使用原独立业务 DB。HTTP 验证新增、修改完成状态、revision 增加、过期写入 409、非法输入 400、单条/列表查询及跨项目数据不混用。停止 A 时 B 保持原进程并继续响应；A 以新 run/execution/PID 启动后，原修改及 revision 保留，迁移历史完全一致。两项目及 A 重启后的根页/静态资源均与对应封存 JAR 逐字节匹配，交付身份绑定准确的源码/构建。这里没有声称网页代理或下载按钮已接入新模式。

三次实际运行均由原 worker 持有能力停止，并核对停止收据、记录摘要和 pending=false；最终只读审计为当前 81 条运行全部终结、任务槽/协调锁均为 0。四份配置的数据库物理身份、A/B 原绑定、五份旧未决审计及旧 schema 记录保持不变；业务库保留实际迁移及测试行，测试库仍无新增对象。9 个私有值与本轮公开差异（含新文件）、平台/应用 JAR classes/resources 比对零匹配。

红绿日志、前后端及外层 XML、manifest、停止身份和审计归档于 `.local/ex27b-evidence-d2ic5wra`，原构建目录、源码版本和失败证据保留。EX27b 完成；V2 增量迁移、失败升级与旧代码拒绝仍待 EX27c，EX27 整项和 C-EX09 未勾选。业务库实际迁移后的原运行夹具复验另记。

## 2026-10-26 · EX27c0 批量读取 Git 对象

V1 业务迁移后的兼容回归中，BuildWorker 的 8 项、RunHealth 的 1 项、Access 的 3 项、RuntimeScheduler 的 3 项通过；两个 RuntimeFault 外层探针因 `DESCENDANT_LIMIT` 提前结束，RunAdmission 尚未产生本次最终报告时会话被中断。因此该命令未标为通过。日志及当时 XML 保留于 `.local/ex27b-compat-interrupted`，两个新故障目录也保留；只读核对为 90 条运行全部终结，原五份未决审计和旧 schema 记录不变。

根因是完整源码反复核对时逐个对象启动 Git，外层探针的拥有者累计观察超过 128 个子进程。未提高该上限，也未过滤、遗漏或按 PID 接管子进程。先用真实 Git 写入/读取 40 文件样本，准确得到旧实现 **44 次而非期望 5 次**的 RED；双文件样本原 5 次而非 4 次，同样失败。实现只修改 SourceGitStore 和对应测试：逐层批量读取原始 tree，一次批量读取去重 blob，逐对象类型/长度/内容 SHA-1、文件路径/模式/数量/总量和最终 SHA-256 校验均保留，使用原有有限子进程和共享期限。新增多帧截断、错序、后续对象损坏、重复身份及累计超量反例；不缓存跨次读取。

**17:38:13，28 单元 + 13 集成通过**，零失败/错误/跳过，耗时 6:31。真实 40 文件读取降至 5 个 Git 进程（含两个同层目录及重复内容）；单元覆盖重开存储、版本不可覆盖、未改 blob、hooks/attributes、根替换、缺失对象、输出限额与取消。集成包括两个原 RuntimeFault（均得到 retained/verified 证明）、8 项 ReadGateway 权限/期限及 3 项 SourceQuery 检查。记录与证明保留于 `.local/ex27c0-evidence-q_3szuf5`，此前失败未删除。该修复不是 V2 业务升级通过的证据；c1 与未完成的 RunAdmission 随后继续执行。

## 2026-10-26 · EX27c1 首次升级验收中断点

已增加 V2 升级契约及夹具方法，覆盖原 V1 产物复用、准确迁移批准、旧行默认值、优先级更新与重启，以及旧代码/过期批准拒绝。缺方法时的编译 RED 和随后成功编译均保留，但这些测试代码尚不代表实际升级通过。

首次原生验收于 **17:46:02 失败**：10 项单元通过，升级契约在 A 的 V2 构建 `a975ae73-77bd-49ed-a80b-ad4e7042c422` 的 npm 工具版本探测阶段触发 `EXECUTION_TIMEOUT`。持久事件表明 Node 探测已完成，npm 从开始事件到失败约 10.09 秒；构建总期限仍为 1800 秒，尚未进入依赖安装或业务数据库升级。后续 RunAdmission 契约拒绝当前“V2 源码、V1 业务历史、无迁移批准”的组合，未降低该检查以取得通过结果。

只读复核当前 94 条运行全部终结，任务占位、迁移批准及协调/绑定锁均为 0；原 A/B 物理绑定、五份旧未决审计和旧 schema 记录保持不变。9 个私有值与 c0 后公开差异、新增升级测试及当前平台 JAR classes/resources 比对零匹配。原始失败日志/XML、只读审计和检查摘要保留于 `.local/ex27c1-native-failure`；两个 V1 产物、V1 业务数据和已追加的 A/V2 源码不删除、不覆盖。

此时单次工具探测从 10 秒调整到 30 秒的补丁已准备并通过应用前校验，按规格 §10 等待预算变更确认，未直接应用。EX27c1、c2 与 C-EX09 均未标记完成。

用户随后明确确认 30 秒调整。回归先用真实受控子进程等待 11 秒后输出合法版本，旧实现于约 10 秒得到 `EXECUTION_TIMEOUT` 的 RED；同组已启动进程遇到较短整轮期限的停止收据检查通过。生产改动仅将单次探测期限从 10 改为 30，仍取其与任务剩余期限的较小值；取消、输出上限、进程收尾期限和持久状态规则不变。

**18:15:26，12 单元 + 12 集成通过**，零失败/错误/跳过。包括慢探测正常完成、已启动进程超时及准确停止收据、取消/检查阶段期限、工具漂移、版本和输出校验，以及探测日志的持久开始/结束与不确定结果保留。红绿日志/XML、runner 和摘要归档于 `.local/ex27c1-probe-evidence-s32fj4zg`。实际 V2 构建与迁移验收在此后继续，未由这些工具测试替代。

## 2026-10-26 · EX27c1 双应用 V2 升级与重启

按已确认的 30 秒工具探测期限，复用 EX27b 的两个真实 V1 产物及其业务数据。A 使用上次失败时已追加的 V2 源码版本，B 通过原编辑网关追加 V2；没有重写失败版本、清库或回退迁移。`ExecutionApplicationUpgradeIT` 与 `ExecutionApplicationFixture` 的升级方法共两个主要文件。

**18:39:29，10 单元 + 1 原生升级契约通过**，零失败/错误/跳过，耗时 22:39。两项目各自重新完成 npm、类型检查、lint、22 项前端测试、Vite、11 项 Java 测试和 Maven 打包，固定来源如下：

| 项目 | V2 构建 | 源码版本 | JAR SHA-256 |
|---|---|---|---|
| A | `8c024b1b-3b9d-44a9-91b4-8414c4248bee` | `4abf7318-4a2f-412f-8775-9dc842d1676c` | `870a315665b62f997c6b08152d2de9fd5bf6e0072b21a627d6949056e0776bb7` |
| B | `b044ed8e-68f3-489e-bd36-f7bfb8b4219d` | `6c179a79-fd6d-47b2-8eca-bfc93eca3893` | `765ee32b37e8d1d586bb955bb91515b726eef2eeed5c97c45792420011bd4192` |

两份 V2 树摘要均为已评审的 `a786407f59d6ad08be8c77aaad27458c5552b1061c9af5438b5ebc3b4c9363d0`。先启动两个 V1 进程并写入不同待办，再在旧应用运行期间构建新版。每个项目未批准时拒绝启动；准确历史/目标批准可幂等复用，实际启动后各追加 V003。原待办字段与 revision 保留，priority 默认 0；更新到 2 后，旧客户端省略 priority 不会清除新值，非法 priority 返回 400。停止后原批准因历史变化而失效；V1 JAR 因迁移不兼容被拒绝，V2 按当前历史正常重启并保留修改后的完整行和迁移记录。A/B 逐个升级期间，另一个项目保持原 PID、READY 状态并持续返回其数据。

两项目升级后与重启后的所有 HTTP 静态资源逐字节匹配本次封存 JAR，交付句柄复验对应源码版本/树摘要/构建身份。共 6 个真实运行均由原 worker 停止并核对准确收据与持久摘要；当前 100 条运行全部终结、任务占位和协调/绑定锁为 0，保留两条真实迁移批准。四份数据库配置的物理身份、原绑定、五份旧未决审计及旧 schema 全部运行/绑定摘要不变，测试库仍无新增对象。

9 个私有值与公开差异、新增测试、完整本轮原生日志及平台/两个应用 JAR classes/resources 比对零匹配。日志、前后端/外层报告、清单、批准/运行/停止身份和只读审计归档于 `.local/ex27c1-upgrade-evidence-j78hpa_2`，原工程与全部失败证据保留。当前平台 JAR 摘要为 `f057558fd5455d3c826daf42993c72d1231cdfd8880665344f1569b388452859`。

**18:41:16，4 单元 + 1 RunAdmission 集成通过**，零失败/错误/跳过。原并发准入夹具在真实 V2 历史与当前完整源码上正常工作，覆盖同一请求只产生一条记录/一个 ticket、重新核对输入、失权/归档拒绝、产物变化及从未启动任务的恢复；没有启动假业务应用或改写业务库。该回归完成此前中断及 V2/V1 临时不匹配阶段未完成的检查。报告与最终只读审计追加至上述归档的 `compatibility/`；最终 101 条运行全部终结，无占位和协调/绑定锁，保留两条真实批准，旧审计不变。

EX27c1 完成。本记录证明真实成功升级、数据保留和旧代码拒绝；真实失败迁移及确认后恢复仍属于 c2，不据此勾选 EX27 整项或 C-EX09。当前仍是默认关闭的内部执行模块，尚未将新模式接入工作台、预览代理或 ZIP 按钮。本轮仅本地实现与验收，未推送、部署、调用真实模型/SMTP 或修改私有配置。

## 2026-10-26 · EX27c2a 失败核对的追加审计与原子状态更新

用户确认显式核对方案后，新增 V030、`ExecutionRunReconciliationRepository` 与对应 PG 集成测试，共 3 主要文件。审计绑定原运行/执行、构建/源码/绑定身份、停止摘要、原错误、确认的数据库历史和幂等输入；原失败码不改写。仅允许未 READY、已记录停止且 pending=false 的待核对状态；当前项目所有权/归档及完整运行快照必须匹配。审计拒绝修改/删除，延迟约束确保审计不能在缺少对应 FAILED 状态转换时提交；原 V028/V029 不改写。

先以缺少仓库类得到编译 RED，首轮 8 单元 + 2 集成通过。补充异常与兼容场景后，**19:00:57，17 单元 + 29 集成通过**，零失败/错误/跳过。新增 7 项集成使用回滚元数据与实际 `java --version` 子进程的停止收据；验证重复确认、未知停止拒绝、历史/运行变化、错误身份/归档/非法输入、审计不可修改、终态写入失败整体回滚，以及数据库拒绝“只有审计没有终态”的不完整事务。另有原 RunState 17 项和 MigrationReview 5 项通过。该测试不运行业务 SQL，也不等价于原协调器维护入口或实际迁移失败已完成。

V030 已应用到获准的测试 schema；最终 101 条运行均终结，保留原两条迁移批准，核对审计为 0（测试均回滚），任务槽与协调/绑定锁为 0。原 A/B 绑定、五份旧未决审计及旧 schema 记录摘要不变。9 个私有值与本次差异、新文件及平台 JAR classes/resources 比对零匹配。红绿日志、最终 XML、runner、迁移/资源审计与摘要保存在 `.local/ex27c2a-evidence-eyzmn6na`。仅完成内部持久层，c2b/c 继续实施；没有新增数据库/角色、修改私有配置、推送或部署。

## 2026-10-26 · EX27c2b1 核对服务的事务与权限边界

新增内部服务，将源码、封存产物、物理数据库身份及迁移历史核对放在短元数据事务之外；事务前后复核维护者、配置、原协调器授权和共享 30 秒期限，事务自身仍限制 5 秒。明确重试先读取原确认审计，不重复数据库接受操作；只有明确收到提交成功后才调用原拥有者的释放接口。提交回执未知不自动重试。服务未注册为公开 API 或自动启动组件。

初次验证因事务内误用禁止事务的迁移检查方法而失败，保留失败报告后改为仅检查剩余期限；11 单元 + 7 集成通过。随后附加异常回归先复现底层关闭详情可附着于 ApiException，再通过重新构造对外错误去除 cause/suppressed。**19:27:24，30 单元 + 29 集成通过**，零失败/错误/跳过。13 项服务测试覆盖事务边界、准确重试、提交回执未知、权限撤销、配置/运行变化、期限、来源读取失败、原拥有者失权，以及已提交后释放失败的明确重试；另含原运行状态和迁移批准回归。没有用 mock 输入测试冒充真实来源、停止或业务库验收。

共 4 主要文件，包含仓库新增明确读取/重试接口和原 PG 集成测试补充。最终只读核对 101 条运行全终结、两条迁移批准、核对审计 0、槽及协调/绑定锁 0；原五份旧未决审计和绑定保持不变。9 个私有值与差异、新文件、测试日志及平台 JAR classes/resources 比对零匹配。红绿报告、XML、runner 与只读审计归档 `.local/ex27c2b1-evidence-c9bkwnek`。原协调器接入及实际失败迁移仍待 c2b2/c2c，不将本片标为整个恢复流程已完成。

## 2026-10-26 · EX27c2b2 原协调器的确认与资源释放

调度器保留原 worker 实际返回的准确已停止失败快照；核对要求执行线程池已终止、没有进行中的准入/停止操作、没有未知进程句柄，并逐项匹配原准入身份。数据库行和停止摘要不能重建原拥有权。明确确认仅移除对应 job，不清空其他未确认任务，也不复用已经停止的线程池。worker 返回丢失时，即使数据库看似没有未完成项也继续保留原 job。

原协调器实现内部核对 Owner，维护范围持有原执行根和协调锁，关闭范围前不允许 finish 释放；短事务中的权限检查不扫描文件系统。原租约/源码权限失效、配置变化或原任务不匹配均拒绝。已释放后的明确重复确认不接管旧任务。

两个缺入口编译 RED 后，首轮调度测试发现 Mockito 重设回调时误触发原阻塞等待，保留证据后改用 doAnswer；13 项调度检查通过，随后 17 项协调检查通过。补充“数据库看似结束但 worker 回执丢失”及“只确认两个失败任务之一”后，**19:45:22，32 单元 + 12 集成通过**，零失败/错误/跳过。集成包括原 RuntimeScheduler 的 3 项实际受控 JVM 调度/关闭/失锁、RuntimeFault 的 2 个独立 JVM 故障探针，以及 7 项核对仓库测试。受控 HTTP 夹具不当成真实应用 SQL 迁移证据。

共 4 主要文件；最终只读核对 107 条运行全终结、两条迁移批准、核对审计 0、槽和协调/绑定锁 0，五份旧未决审计与旧记录/绑定保持不变。9 个私有值与差异、新文件、日志和平台 JAR classes/resources 比对零匹配。红绿日志、失败夹具报告、最终 XML、runner 与审计保存在 `.local/ex27c2b2-evidence-ow_s3dy7`。未推送/部署或调用模型/邮件；c2c 的真实失败迁移及后续兼容启动继续验收。

## 2026-10-26 · EX27c2c 真实失败迁移、确认与兼容版本重启

新增原生失败契约及固定数据库观察夹具，扩展 ApplicationFixture 复用原 V2 产物、准确准入、只读等待原 worker 结束和追加源码版本，共 3 主要文件。缺少夹具接口的编译 RED 后完成编译；**20:30:02，10 单元 + 1 原生契约通过**，零失败/错误/跳过，耗时 10:19。实际新工程完整执行依赖安装、类型/lint、22 项前端测试、前端构建、11 项 Java 测试与 JAR 打包，源码、清单和 JAR 摘要对应。

沿用原 A 业务库/角色/绑定/18181 端口，先运行保留的 V2 并读取全部待办，再正常停止。失败版本仅追加 V004：创建测试表、经过 `pg_advisory_xact_lock` 观察点、确定的除零错误。测试连接只持有自己的会话锁，通过同库同角色活动会话准确等待该锁证明实际 Flyway 已进入新增迁移，然后释放自己的锁；没有新建数据库、角色、持久绑定或修改数据库配置。

实际应用退出为 RUN_PROCESS_FAILED；从原协调器在迁移等待期间取得的进程句柄返回真实停止收据，与保存的执行身份、运行目录和停止摘要匹配。任务保持 NEEDS_RECONCILIATION、pending=false，未达到 READY，新的启动被拒绝。只读观察确认测试表不存在、原全部待办行及完整迁移历史不变，B 的历史不变。随后使用正式核对服务读取实际来源/JAR/数据库历史、明确接受并记录审计；相同确认返回同一审计，任务结束为 FAILED，原错误和停止摘要保留。接受当前数据库状态不等同于通用数据回滚能力。

原协调器正常结束后，通过源码网关将已评审 V2 文件追加为新版本，保留失败源码和构建。重新创建的协调器正常启动原兼容 V2 产物，全部待办和历史与失败前一致；通过 HTTP 逐字节核对静态资源与封存 JAR，运行/构建/源码身份准确，最后由原 worker 正常停止。

| 证据 | 身份 |
|---|---|
| 失败源码 | `0a1d1087-2e00-4276-be92-2708cb58c30b` |
| 新构建 | `f336f59e-63b4-43e8-a2e8-f99a129eaf9f` |
| 失败运行 | `986c2e0f-53a6-4821-b1ec-642cab0b847b` |
| 明确确认审计 | `7b05105f-0158-4d7b-8e81-22008b9692e2` |
| 追加恢复源码 | `f98a10a5-2787-4a55-ac34-be91d31e92e7` |
| 新协调器运行 | `5205dd4d-abd1-40f5-901e-2eeb8bc11b4c` |

最终只读核对 110 条运行全部终结、三条准确迁移批准及一条核对审计保留、槽和协调/绑定锁为 0；原五份旧未决审计、旧记录摘要和 A/B 绑定不变。9 个私有值与差异、新文件、日志及平台/应用 JAR classes/resources 比对零匹配。红绿日志、原生 XML、前后端报告、产物清单与摘要、runner 和资源审计保存在 `.local/ex27c2c-evidence-tbiey6bt`。

EX27 和 C-EX09 完成。EX28–30 的组合故障验证、全量兼容回归及最终说明仍待后续；当前新执行模式默认关闭，尚未接入工作台、预览代理或工程下载按钮。本轮没有推送/部署、调用真实模型/SMTP 或修改私有配置。


## 2026-10-26 · EX28a 并发与跨任务凭证组合

复用原准备调度夹具的包内入口（仅放开 9 个测试辅助方法），新增两个真实 PG/Git、受控 Node/JVM 的组合场景。并发 4 时两项构建和两项准备实际启动，取消其中一项准备后第五项接替；其余三项 PID 不变且仍存活，原源码摘要不变，其他任务的实际停止收据无法释放正在执行的编辑租约。原协调器正常关闭后准备任务全部取消、容量归零。相同项目同时提交构建和准备只接受一个，另一个明确返回忙碌；排队取消不发布新源码版，源码头、版本数及原快照均保留。

辅助入口不可访问的编译 RED 保留于 `.local/ex22-ex28a-red.log`；20:43:47 首个组合用例通过。20:53:48，`sh .local/run-ex22-checks.sh ex22-ex28a-final.log -Dtest=ExecutionSettingsTest -Dit.test=ExecutionConcurrencyIT,ExecutionPreparationSchedulerIT -Pproject-execution-contract verify` 的 **7 单元 + 10 集成**全部通过，零失败/错误/跳过。随后增强源码头/版本数断言，EX28abc 批次中的两个组合用例也已通过；该批次整体结果另记，不将尚在执行的其他类提前算通过。原调度 8 项报告、更新后的组合 2 项报告及前序日志归档 `.local/ex28a-evidence-gtgqj3r6`，0700/0600。

仅测试代码及计划/证据文档变更，不改变执行权限、环境或业务绑定。受控工具不代表再次运行原生 npm/Maven；原生证据仍见 EX27。未推送、部署或调用模型/SMTP，EX28b–d 尚待完成。
