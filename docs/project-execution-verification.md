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
