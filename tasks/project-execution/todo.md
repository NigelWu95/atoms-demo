# project-execution · 开发任务

状态：2026-10-26，规格及 EX01–EX30 计划已获确认，EX01–26、C-EX08 已完成。EX24 的 557 单元 + 180 相关集成基线保留；EX26 的 Vue 待办/优先级两版均通过原生构建，各 22 项前端测试、10/11 项 Java 测试及 59 项相关单元通过，浏览器验证过的静态资源与对应 JAR 逐字节一致，见[证据](../../docs/project-execution-verification.md#2026-10-26--ex26-vue-业务页面)。EX27a 兼容与 EX27b 真实双应用闭环已完成：两个实际 Spring Boot 进程、独立业务库、HTTP 增改查及重启数据保留通过；10 单元 + 1 原生契约通过，两项目各 22 前端 + 10 Java 测试。验收后 81 条运行全部终结，五份旧未决审计和旧 schema 运行/绑定不变，见[记录](../../docs/project-execution-verification.md#2026-10-26--ex27b-双应用真实-http-与重启持久化)。下一项 EX27c 为 V2 升级及失败迁移，C-EX09 尚未完成。新模式默认关闭，网页接入仍待后续。唯一依据为[规格](../../SPEC-project-execution.md)与[计划](plan.md)，不复用旧工程导出的 PE 编号；勾选须附真实证据。

执行环境沿用已获准的项目隔离 Liberica 17.0.14+10，仅命令级指定；原 Oracle 17.0.5、全局默认、私有 env、服务器和旧审计均不变。原生枚举故障未捕获原 errno，不能宣布根因完全证实，保留监测。EX11 原生构建、完整回归和变异证据见[验证记录](../../docs/project-execution-verification.md#ex11b--实际-jar-与失败版本验收)。

路径缩写：`E` = `backend/src/main/java/dev/atoms/demo/projectexecution`；`ET` = 对应 `src/test/java` 包；`ER` = `backend/src/test/resources/projectexecution`；`R` = `backend/src/main/resources/projectexecution`；`S/ST` = 现有 source 主/测试包；`X/XT` = execution 主/测试包；`M` = `backend/src/main/resources/db/migration`；`T` = `templates/full-project-v1`。这些是具体文件的前缀，不是用一个目录隐藏多文件变更。

U/I/REAL/ALL/COMPAT/DOC 的命令与权限条件见计划 §6。S = 1–2、M = 3–5 个主要实现/测试文件；实际超过先拆子任务。每项均要求红绿测试、失败路径、自审、对应构建验证及证据；本地原子提交，不合并、推送或部署。检查点报告结果，不新增环境授权。

## EX01 · 默认关闭与资源预算

- [x] 完成 EX01。依赖：用户批准本计划。规模：M（4 文件）。
- 描述：建立独立执行配置，不读取或修改日常私有 env。
- 验收：禁用无目录/子进程/应用 DB 连接；启用须维护者 UUID、私有根及固定工具配置；严格保持构建 1/队列 8、各期限与容量，Agent 默认 4 不变。
- 验证：U(ExecutionSettingsTest)；默认 Spring 上下文启动与配置非法值反例；DOC。
- 主要文件：`E/ExecutionSettings.java`、`ET/ExecutionSettingsTest.java`、`backend/src/main/resources/application.yml`、`.env.example`。
- 证据：RED 为缺类编译失败；14:24 全后端单元 297 + 源码读取集成 5 通过并打包；放宽并发上限的变异被拦住，恢复后 14 项定向及打包通过。见[验证记录](../../docs/project-execution-verification.md)。没有启用执行或改私有 env。

## EX02 · 私有执行根与排他租约

- [x] 完成 EX02。依赖：EX01。规模：M（4 文件）。
- [x] EX02a：根身份、私有权限、同 JVM 与跨 JVM 排他租约。先缺类 RED，14:32 定向 20 项及打包通过。
- [x] EX02b：任务子目录、持有句柄和磁盘容量核对；先缺类 RED，14:40 定向 25 项及打包通过，见验证记录。
- 描述：仅在批准根下分配构建/产物/运行目录，持有根身份和跨 JVM 文件租约。
- 验收：拒绝 root、未知/被替换根、链接/权限错误及与源码/Agent 根重叠；同根双协调者不能并存；容量不足拒绝新任务且不自动清理。
- 验证：U(ExecutionPathsTest,ExecutionRootLeaseTest)，含真实临时目录、两个 JVM 和 8 GiB/1 GiB 边界；核对无外部目录变更。
- 主要文件：`E/ExecutionPaths.java`、`E/ExecutionRootLease.java`、`ET/ExecutionPathsTest.java`、`ET/ExecutionRootLeaseTest.java`。

## EX03 · 复用自有进程与停止证据

- [x] 完成 EX03。依赖：EX01、EX02。规模：M（5 文件）。
- [x] EX03a：共享进程基元兼容入口；缺方法 RED 后 14:43 新旧进程/流 32 项通过及打包。
- [x] EX03b：内部执行调用的白名单环境、有界输出与总期限适配及失败路径；38 项定向通过，14:53 全部 316 单元 + 9 项源码集成通过及打包。
- 描述：以兼容重载/适配使有限执行命令使用现有进程拥有机制，不复制终止逻辑。
- 验收：原 Agent 调用及源码 `Stopped` 契约不变；无 shell 拼接、环境为显式白名单，未启动/已停止/未知分开；重复中断、I/O 阻塞、子进程未退出均受原 10 秒收尾界限约束。
- 验证：U(ExecutionProcessTest) + 原 Agent 进程/流测试；真实临时子进程、秘密 canary、失败启动；COMPAT 中源码停写证明测试。
- 主要文件：`X/OwnedProcess.java`、`XT/OwnedProcessTest.java`、`E/ExecutionProcess.java`、`ET/ExecutionProcessTest.java`、`SPEC-project-source.md`。
- 备注：若需行为重构，先单独兼容提交再增加新策略；不把业务构建阶段塞进共用进程类。

## C-EX01 · 启用与进程边界

- [x] EX01–03 定向测试及后端构建通过；默认旧启动不变，真实自有进程退出可证明，个人配置未触碰。此阶段不含 npm/Maven 实际应用构建、常驻运行或真实模型；继续 EX04。

## EX04 · 只物化请求指定的源码版本

- [x] 完成 EX04。依赖：EX02、EX03。拆为 EX04a、EX04b 各最多 5 个实现/测试文件。
- [x] EX04a：只读源码门面的共享预算兼容重载和集成测试；5 处缺重载 RED，15:03 定向 9 单元 + 11 集成通过并打包。
- [x] EX04b：独立构建目录的物化/复验及真实快照集成；测试夹具补只读门面与后续版本发布方法，不扩大生产 API；容量扫描补共享预算。15:53 定向 47 单元 + 15 集成通过及打包，见验证记录。
- 描述：把授权不可变快照写入新任务目录，并给只读门面增加共享剩余期限。
- 验收：V1 请求在 V2 提交后仍取 V1；文件/字节/模式/摘要及 512/2 MiB/16 MiB 限制有效；读取、复制和复验受同一剩余期限约束，拒绝事务内 Git/I/O。
- 验证：I(ExecutionSnapshotIT,ProjectSourceReadGatewayIT)，包括版本竞争、超时、权限改变及链接/篡改；旧无预算重载回归。
- 主要文件：`E/ExecutionSnapshot.java`、`ET/ExecutionSnapshotIT.java`、`S/ProjectSourceReadGateway.java`、`ST/ProjectSourceReadGatewayIT.java`、`SPEC-project-source.md`。

## EX05 · npm 清单与锁图批准

- [x] 完成 EX05。依赖：EX04。规模：M（3 文件）。
- 描述：为种子工程建立源码外的准确依赖策略，验证根清单及完整传递锁图。
- 验收：清单/锁摘要绑定批准，缺锁或不匹配不自动安装修复；拒绝未批准 registry、git/file/URL/link、完整性缺失及 workspace 绕过；准备批准与构建批准明确分开。
- 验证：U(ExecutionNpmPolicyTest)，用种子真实 lock 和最小恶意向量；审批摘要改变必须使旧批准失效。
- 主要文件：`E/ExecutionNpmPolicy.java`、`ET/ExecutionNpmPolicyTest.java`、`R/seed-dependency-policy.json`。
- 证据：缺类 RED 后种子及反例通过；另加格式版本整数溢出反例先 RED、修复后 16:12 共 40 项定向及打包通过。未下载/安装依赖，没有真实构建结论。

## EX06 · 固定工具与 Maven 执行策略

- [x] 完成 EX06。依赖：EX03、EX05。规模：拆分为下面的小片，每片 1–5 个主要文件。
- [x] EX06a：POM、源码配置入口与外部批准策略；npm 策略兼容新增 Maven 策略节。缺类 RED 后 16:20 定向 44 项及打包通过，见验证记录。
- [x] EX06b：固定工具身份、探测/构建 argv 与私有配置，分开提交。
  - [x] EX06b1：任务私有 home/settings/cache、配置身份复验、明确 Maven 根目录；主要文件 `ExecutionToolHome`、`ExecutionPaths`、`ExecutionProcess` 及两个测试类。缺类 RED、移除等长字节比较的变异被拦截；恢复后 17:29 全后端 331 单元 + 19 源码集成及打包通过，见验证记录。
  - [x] EX06b2：固定工具身份及漂移检测、版本探测、固定操作 argv；核对 JVM 实际 `user.home` 与子进程继承，不能只以生成了配置文件推断已隔离实际工具。
    - [x] EX06b2a：可执行文件及加载库树的有界 SHA-256、inode/权限/链接复验；17:50 定向 17 项与打包通过，移除身份比较的变异被拦截。
    - [x] EX06b2b：安装布局、版本探测及固定命令目录；22 项定向及打包通过，本机四工具真实探测与主 JVM 私有 home 验收通过。
    - [x] EX06b2c：子 JVM 私有 home 继承、配置 canary 与完整回归；真实双 JVM 通过，去掉工具接线的变异被拦截，18:14 全后端 340 单元 + 19 源码集成及打包通过。不把父 JVM 的 `-D` 视为自动继承。
- 描述：核对工具身份，构造平台拥有的 argv/settings/cache，检查 Maven 的全部受支持配置入口。
- 验收：拒绝未知工具版本、个人配置继承与工具漂移；POM 父/插件/仓库/profile、`.mvn`/扩展或注入参数未批准则拒绝；不执行源码 `mvnw`/`build.mjs`，不静默改 registry/证书。
- 验证：U(ExecutionToolchainTest,ExecutionMavenPolicyTest)，XML 外部实体/解析失败、属性覆盖和配置注入反例；官方固定版本参数核对记录。
- 主要文件：`E/ExecutionToolchain.java`、`E/ExecutionMavenPolicy.java`、`ET/ExecutionToolchainTest.java`、`ET/ExecutionMavenPolicyTest.java`、`R/seed-dependency-policy.json`。

## C-EX02 · 输入与依赖可控

- [x] EX04–06 定向测试及构建通过；固定版本与配置 canary 验证完成，未批准依赖不会执行。此处只证明策略，不宣称工程已实际构建。四个真实工具版本/主 JVM 设置与双 JVM 继承验证通过，具体证据见验证记录；后续 worker 仍须实际调用这些准入与 preflight。

## EX07 · 幂等的持久构建准入

- [x] 完成 EX07。依赖：EX01、EX04、EX06。拆为以下小片，分别验证后提交。
  - [x] EX07a：V023 构建/事件新表、槽位/不可变约束和真实 PG 测试（2 个主要文件）。缺表 RED 后，19:09 全部 340 单元 + 1 项数据库约束集成通过及打包；只迁移批准的 pa32b schema。
  - [x] EX07b：内部提交/查询、固定版本读取、幂等与并发容量、权限及原子回滚（最多 5 个主要文件）；授权变异被拦截、Unicode 键与缩容边界先 RED 后修复。
  - [x] EX07c：完整回归发现测试上下文累计占满 PG 连接；补 `ExecutionSnapshotIT` 的类级资源关闭（1 个主要文件）。本轮新建准入/schema 测试也按类关闭，不改变生产连接数、测试断言或数据库上限。
- 描述：追加构建/阶段事件记录及内部服务，接受一次稳定的固定版本构建意图。
- 验收：维护者、所有权/归档独立检查；同键同体并发只产生一任务、异体冲突，活动/等待容量有 DB 保护；状态、来源不可变及事件配额原子更新，默认关闭不影响旧表。
- 验证：I(ExecutionAdmissionIT)，真实双连接竞争、事务回滚、越权、活动唯一及迁移兼容；ALL。
- 主要文件：`M/V023__project_execution_builds.sql`、`E/ExecutionBuild.java`、`E/ExecutionBuildRepository.java`、`E/ProjectExecutionService.java`、`ET/ExecutionAdmissionIT.java`。
- 证据：22:25:37 完整后端 `verify`，340 单元 + 297 集成全部通过，0 失败/错误/跳过并打包；包括 8 项准入、2 项 schema、7 项快照测试。先前连接池失败和修复过程保留在[验证记录](../../docs/project-execution-verification.md)。这是内部构建意图准入，不是实际工程构建/运行或网页交付。

## EX08 · 测试数据库真实目标检查

- [x] 完成 EX08。依赖：EX01、EX06。拆成以下小片，维持每片最多 5 个主要文件。
  - [x] EX08a：私有配置读取与固定项目注册表、只读目标校验及拒绝路径（2 个实现 + 1 个单元测试文件）。
  - [x] EX08b：四套已批准本机库的真实只读契约（1 个集成测试 + `backend/pom.xml`）。提前引入原定 EX11 的独立 `project-execution-contract` profile，默认不连接私有验收库；与 Agent profile 分开启用，缺配置明确失败，不 skip。
- 描述：只允许维护者预置、用途明确的独立测试 DB，凭据不进入任务表或源码。
- 验收：实际连接身份与配置匹配且非超级用户，拒绝平台/系统/业务库和跨项目复用；检查前不执行清理或迁移；测试配置私有且无个人/平台 env 注入。
- 验证：U(ExecutionTestDatabaseTest) + I(ExecutionTestDatabaseIT)；真实目标只读检查需先确认准确新 DB/角色，缺环境记录阻断，不能假连接代替。
- 主要文件：`E/ExecutionTestDatabase.java`、`E/ExecutionPrivateConfig.java`、`ET/ExecutionTestDatabaseTest.java`、`ET/ExecutionTestDatabaseIT.java`。
- 证据：缺类 RED、移除角色用途检查的变异被拦住；显式契约缺环境时 4 项明确失败，22:57 配置后 14 单元 + 4 真实契约通过；23:18:09 全后端含该契约共 347 单元 + 301 集成全部通过并打包，0 失败/错误/跳过。实际业务库伪装、错误绑定/密码、文件替换与重复登记均拒绝，四库无新增表；详见[验证记录](../../docs/project-execution-verification.md)。没有实际 npm/Maven 应用构建。

## EX09 · 固定前端检查流水线

- [x] 完成 EX09。依赖：EX03–08。拆为以下小片，各不超过 5 个主要文件。
  - [x] EX09a：有界 Vitest 5 JSON 报告校验，只输出数值摘要（`ExecutionDiagnostics` 及单测）；23:31:46 定向 9 项及打包通过，补齐变异揭示的用例计数反例。
  - [x] EX09b：固定前端流水线、共享取消/期限、干净报告目录与停止证据（`ExecutionFrontend` 及单测）；23:38:46 定向 11 项及打包通过，执行边界为受控 Java 夹具，真实 npm 留在 EX11。
  - [x] EX09c：持久阶段/执行意图接线及 PG 反例（`ExecutionBuildJournal` 与 `ExecutionFrontendJournalIT` 两个主要文件，无需改 repository）；2026-10-21 00:22 定向 9 单元 + 8 PG 集成通过并打包，错误停止证明变异被拦住。完整回归新报告为 356 单元 + 309 集成，零失败/错误/跳过；中断后重新执行报告校验成功，见验证记录。不提前实现 EX12 调度。
- 描述：从持久任务运行冻结安装与平台固定的类型/lint/单测/构建入口，产生阶段事件。
- 验收：显式 ci/ignore-scripts/no-audit/no-fund，源脚本不能替换平台编排；失败立即停止，取消/期限跨阶段共享；本次报告必须存在且有实际非跳过测试，原始输出不持久化。
- 验证：U(ExecutionFrontendTest,ExecutionDiagnosticsTest)，确定性进程/报告反例；真实工具闭环在 EX11 单列。
- 主要文件：EX09a 的 `E/ExecutionDiagnostics.java`、`ET/ExecutionDiagnosticsTest.java`；EX09b 的 `E/ExecutionFrontend.java`、`ET/ExecutionFrontendTest.java`；EX09c 的 `E/ExecutionBuildJournal.java`、`ET/ExecutionFrontendJournalIT.java`，每片分别验证和提交。

## C-EX03 · 可提交并观察构建阶段

- [x] EX07–09 的 PG/报告/流程测试与平台后端构建通过；356 单元 + 309 集成全部通过，含 4 项专用数据库只读契约；不存在“提交成功就标构建成功”，迁移仅作用于批准的测试 schema。未执行真实 npm/Maven 用户工程构建，此项留在 EX11，不能以当前检查点替代。

## EX10 · Java 检查与 JAR 封存

- [x] 完成 EX10。依赖：EX04、EX06–09。以下内部组合与受控进程测试均完成，每片最多 5 个主要文件；保持本模块默认关闭，真实工具构建另见 EX11。
  - [x] EX10a：有界 Surefire XML 报告校验（`ExecutionJavaReport`、`ExecutionJavaReportTest`）。只返回数值，拒绝空/失败/跳过/重跑失败及计数不符、DTD/外部实体/畸形报告；缺类 RED，计数变异被拦住，01:59:42 报告校验定向 6 项及打包通过，见验证记录。
  - [x] EX10b：前端静态产物的私有资源装配与复验，固定 Maven 资源参数（`ExecutionPackaging`、`ExecutionPackagingTest`、`ExecutionToolchain`、`ExecutionToolchainTest`）。不写源码，不覆盖旧输出；拒绝链接/篡改/容量及来源漂移。缺类 RED、副本复验变异被拦住；02:28:46 完整回归 363 单元 + 309 集成全部通过并打包，见验证记录。
  - [x] EX10c：JAR 和构建清单封存（`ExecutionArtifact`、`ExecutionArtifactManifest`、`ExecutionArtifactTest`、`ExecutionPaths`，共 4 个主要文件）。清单校验与文件 I/O 分开，目录分配新增共享预算重载；来源/工具/策略/检查与产物摘要绑定，拒绝空/越界/链接/替换及重复封存，失败保留证据。缺类 RED、复验变异被拦住；修正测试错误码及 JSON 数值节点预期后，03:13:33 全后端 369 单元 + 309 集成通过并打包，见验证记录。不代表已实际构建用户工程或发布成功状态。
  - [x] EX10d：Java 阶段与 worker 接线，按下面小片顺序完成，不提前实现调度。真实 npm/Maven 用户工程仍在 EX11。
    - [x] EX10d1：固定 Java 阶段与本轮报告目录（`ExecutionJava`、`ExecutionJavaReports` 及对应两份单测，4 个主要文件）。只在匹配的停止证据之后读取新报告；拒绝旧输出、目录替换、链接、越界、空/失败/跳过测试，提交异常不重复完成。缺类 RED，定向 11 项通过；修正沙箱干扰后停止身份变异被捕获。首次全量有 1 项旧 Agent 存储失败，单独复测及第二轮全量通过，未修改原断言或生产逻辑；13:09:49 全后端 380 单元 + 309 集成通过，详情及未定位风险见验证记录。
    - [x] EX10d2：扩展 `ExecutionBuildJournal` 的 Java 意图及后端 cwd 停止校验，新增 `ExecutionJavaJournalIT`（2 个主要文件）。真实 PG 验证取消、停止、事件事务失败、资源保留和并发意图；成功仍为 PACKAGING，不发布整体成功。接口缺失 RED，16 项新旧日志测试通过，停止证明变异被捕获；14:07:28 完整后端 383 单元 + 317 集成通过，见验证记录。
    - [x] EX10d3：整体 worker 与仓储封存成功接线，以下依赖有序小片均已验证。
      - [x] EX10d3a：专用测试库到固定 Java 阶段的受控传递（`ExecutionPrivateConfig`、`ExecutionTestDatabase`、`ExecutionProcess`、`ExecutionJava`、`ExecutionDatabaseLaunchIT`，5 个主要文件）。一次性内部授权绑定项目/构建/执行/backend cwd，实际启动前复验文件及 DB 身份；只追加四个 `APP_*` 环境变量，密码不进 argv/源码/事件，不向前端发放。真实只读 PG + 受控 JVM 验证拒绝/继承/配置漂移/脱敏；22:57:56 完整 401 单元 + 325 集成通过，含本片 8 项。不替代真实 Maven 工程验收。
      - [x] EX10d3b：持久构建领取与封存结算，按 b1/b2 两片完成（分别 3/4 个主要文件）。短事务校验状态/阶段/授权/取消，成功绑定封存摘要并释放容量；失败保留旧成功记录，未知结果不释放。b2 的 15 项 PG 测试及 401 + 350 完整回归通过，不提前实现 EX12 调度。
        - [x] EX10d3b1：原子领取排队任务（`ExecutionBuildRepository`、`ExecutionBuildClaim`、`ExecutionBuildClaimIT`，3 个主要文件）。项目行 → 构建行 → 容量事务锁；重新校验维护者/所有权/归档、原任务身份、取消和排队期限；双连接争用仅一方领取，缩容计入旧高槽位和未知执行。领取只到 PREPARING，不启动工具。锁已分为构建 `718395203`、Agent `718395202`，10 项真实 PG 测试通过且旧编号变异被拦截；获准的隔离 JDK 对照相关回归及 23:58:57 完整 401 单元 + 335 集成均通过。默认仍关闭，不代表 worker/调度器已实现。
        - [x] EX10d3b2：封存产物与整体成功的原子结算（`ExecutionArtifact`、`ExecutionBuildSettlement`、V024 追加迁移、`ExecutionBuildSettlementIT`，4 个主要文件）。绑定真实封存句柄和六个完整阶段，事务外复验文件，项目/构建锁内重新授权及保存摘要/状态/事件/释放容量；失败不改旧成功、未知不释放。锁等待期间权限撤销的 RED 已修正，移除阶段校验的变异被捕获；2026-10-22 01:01:30 完整 **401 单元 + 350 集成通过**，含本片 15 项 PG 测试。旧 V023 不改，后续拟定迁移顺延 V025/V026。只读审计无新增 Agent/构建未决，旧故障现场保留，见[验证记录](../../docs/project-execution-verification.md#2026-10-22--ex10d3b2-封存产物的原子结算)。不替代真实工程构建验收。
      - [x] EX10d3c：整体 worker 接线；先补齐版本探测的持久意图，再接通整条流程，不绕过进程证据要求。真实 npm/Maven 用户工程仍由 EX11 验收。
        - [x] EX10d3c1：工具探测持久日志（`ExecutionProbeJournal`、`ExecutionProbeJournalIT`，2 个主要文件）。在 PREPARING 按 Node/npm/Java/Maven 顺序提交一次性意图；完成必须匹配 build/execution/tool-home 停止证明及固定工具摘要、受支持版本。取消/失败原子落账，未知保留容量；锁内重新授权、事件失败整笔回滚，事件不含原始输出或路径。12 项 PG 测试通过、停止证明变异被捕获；10-22 完整报告 **401 单元 + 362 集成通过**，10-23 续接核对并独立 Failsafe 验收成功；只读审计无新增 Agent/构建未决，见[验证记录](../../docs/project-execution-verification.md#2026-10-22--ex10d3c1-工具版本探测的持久日志)。不启动真实 npm/Maven 构建，不新增迁移。
        - [x] EX10d3c2：`ExecutionBuildWorker` 串联冻结源码、策略、工具探测日志、测试库、前后端阶段和封存，分为下列两个四文件小片。持有正确执行根与协调权，共享总期限和取消；产物复验后才交给结算，不继承平台环境；I(ExecutionBuildWorkerIT) + ALL 通过。探测完成记录不得替代六个构建阶段。
          - [x] EX10d3c2a：共享期限与启动复核的兼容接口（`ExecutionPaths`、`ExecutionPathsTest`、`ExecutionToolProbe`、`ExecutionToolProbeTest`，4 个主要文件）。目录分配从检查到完成使用调用者预算，核对执行根的持有关系；探测启动前执行内部授权/源码 preflight，拒绝时仍有准确未启动证明且不泄漏原始异常；旧调用保留。清理磁盘后定向 13 项通过，移除 preflight 的变异被捕获；12:32:29 完整 **405 单元 + 362 集成通过**。沙箱内进程枚举失败及沙箱外通过的环境差异如实保留；只读审计无新增 Agent/构建未决，见[证据](../../docs/project-execution-verification.md#2026-10-23--ex10d3c2a-接线接口)。不代表整体 worker 已接通。
          - [x] EX10d3c2b：整体 worker、外部批准文件身份句柄及各自测试（`ExecutionBuildWorker`、`ExecutionApprovedPolicy`、`ExecutionBuildWorkerIT`、`ExecutionApprovedPolicyTest`，4 个主要文件）。一次性领取 → 冻结输入及维护者策略 → 四次有日志探测 → 前后端阶段 → 封存 → 原子结算；共享期限/取消/授权检查、失败不重试未知提交。3 项策略单测与 8 项真实 PG/Git/受控子进程集成通过，停止收据变异被捕获并恢复；完整新报告 **408 单元 + 370 集成通过**，18:20:18 独立 Failsafe 校验成功。只读审计无新增 Agent/构建未决，旧故障保留，见[证据](../../docs/project-execution-verification.md#2026-10-23--ex10d3c2b-整体构建-worker)。受控工具输出/JAR 字节及移除 `.npmrc` 的明确测试快照不替代 EX11 原生构建。
- 描述：接入平台控制的前端资源装配和 Maven 测试打包，成功后封存来源及 JAR。
- 验收：干净报告/测试数量和退出码通过后才封存；前后源码/lock 不变，产物非空普通文件且不超过 256 MiB；清单绑定来源/工具/策略/检查/JAR 摘要，失败保留上一成功记录。
- 验证：U(ExecutionPackagingTest) + I(ExecutionBuildWorkerIT)，篡改、硬链接、越界、跳过测试、来源改变及失败提交反例。
- 主要文件：以上各片列出的 `E/` 实现与 `ET/` 测试，分别验证和提交；相较原五文件预估，独立拆出报告与封存测试，避免单类承载所有检查。
- 待追查：旧 `ProjectAgentCoordinatorIT` 的取消后再次编辑场景曾出现一次 `SOURCE_STORAGE_UNAVAILABLE`，后续未复现，根因未确定。有界诊断小片已补 `source_snapshot_failed` 的阶段及固定错误分类，383 单元 + 309 集成通过；不把重跑通过当作修复，也不通过延长等待、跳过检查或自动重试掩盖。
- 持续监测：23:21:50 旧 Git 收尾失败已识别到 `ProcessHandleImpl.getProcessPids0` 原生枚举分支，线程未中断；原生 errno 未捕获，不能直接断定 ENOMEM，也不能与首次 Agent 观察错误合并归因。根据 JDK-8339487 线索，经用户授权用项目隔离 17.0.14 对照，相关及完整回归均通过后恢复开发；不将此写成原 17.0.5 已修复。保留诊断和全部失败证据，不清记录、放宽停止证明或隐式重试。

## EX11 · 第一次真实种子工程构建

- [x] 完成 EX11。依赖：EX08–10。先做输入兼容，再以真实工具验收；每片最多 5 个主要文件。
  - [x] EX11a：准确的模板 `.npmrc` 外部批准（`ExecutionNpmPolicy`、对应单测、`seed-dependency-policy.json`）。可选摘要授权只允许原模板两行安全配置和准确路径，不接受其他设置；无该文件的旧快照保持兼容。正式模板不删改。19:07:30 相关 17 单测和打包通过；固定内容检查变异已捕获并恢复。只验收输入兼容，完整 EX11 仍待真实构建及回归。
  - [x] EX11b：`ExecutionSeedBuildContractIT`（1 个主要文件，不另抽仅单处使用的 support）。显式现有 profile 和工具/私有测试配置，真实 worker 构建原始种子，验证 JAR/源码/报告与失败反例。20:32:57，33 源码单元 + 1 原生契约通过：35 文件原始种子产生实际 JAR，前端 15 项与 Java 2 项通过；前端断言失败及 Java 编译失败的两个后续版本均不覆盖旧成功产物。报告 `.local/ex11b-real-passed.11oZja`；随后完整 417 单元 + 377 集成回归也已通过，22:06:02 独立汇总校验成功。两次执行分别记录，不重复计算原生契约。
  - [x] EX11c（b 的兼容片）：`SourceGitStore` 单对象读取及对应测试，两个主要文件。35 文件 121 → 61 个 Git 进程，中位 4834 → 2132 ms；保留完整 SHA-1/树摘要/权限/路径/容量/期限复核，不缓存。33 项相关测试通过、摘要变异被捕获；恢复后原生构建及完整回归通过，不提高预算。
  - [x] EX11d（真实构建发现的工具兼容片）：只给 UNIT_TEST 使用既有任务私有 tmp 作为可写 HOME，容纳 Vitest 5.0.3 普通 run 产生的用户数据；npm 配置/缓存仍明确指向原私有配置目录，保持严格目录复验。`ExecutionProcess`、`ExecutionToolchain` 及各自测试，共四个主要文件。准确环境测试先 RED；20:18:12 相关 15 项通过，丢失子 JVM 属性更新的变异被捕获并恢复；20:32:57 由 b 的真实工具验收通过。随后完整 417 单元 + 377 集成通过，22:06:02 独立 Failsafe 汇总校验成功，见[记录](../../docs/project-execution-verification.md#ex11-完整后端及离线-cli-回归)。
- 描述：加入显式 profile，用原生种子快照实际运行 npm/前端/Maven，尽早验证执行方案可行。
- 验收：真实工具和实际 JAR、静态资源、Java 类及报告可检查；指定 profile 缺环境明确失败，默认不偷装/执行，CLI 与执行 profile 不互相开启；源码版本及锁摘要与构建清单一致，无模型请求。
- 验证：REAL(ExecutionSeedBuildContractIT)；记录精确命令、工具版本、耗时、测试数和 JAR 摘要；破坏一个前端测试或 Java 编译后不得成功。
- 主要文件：EX11a 三个，EX11c 两个，EX11d 四个，EX11b 一个；分片验证与提交，使用 EX08 已存在的显式 profile，不改 `backend/pom.xml`。
- 前提：使用新建受控临时工程；网络下载/DB 配置按批准范围执行，不复用个人 Maven/npm 凭据。
- 接线前发现的模板配置冲突、重复 Git 读取成本及 Vitest 用户数据问题已分别由 a/c/d 处理，并由 b 用原始种子验证；没有删除正式模板文件、放宽任意项目配置或跳过来源复验。

## EX12 · 调度、取消与容量结算

- [x] 完成 EX12。依赖：EX07、EX10、EX11。按取消入口、协调权、调度接线分别验证；每片最多 5 个主要文件。
  - [x] EX12a：持久取消入口（`ProjectExecutionService`、`ExecutionBuildRepository`、`ExecutionCancellationIT`，3 个主要文件）。维护者/当前项目归属/原任务身份校验，短事务先锁项目再锁构建；排队任务原子取消并归还队列，已领取或待核对任务只保存取消意图，不释放 worker 或伪造停止。终态/重复请求幂等，归档项目允许停止自身旧任务，配置撤销在锁后复核，数据库异常不泄漏详情。9 项 PG/真实停止收据测试通过，权限变异被捕获后恢复；22:46:54 全部 417 单元 + 42 相关集成及打包通过，零失败/错误/跳过，见[证据](../../docs/project-execution-verification.md#2026-10-23--ex12a-持久取消入口)。完整协调器/运行中断仍由 b/c 接线验收。
  - [x] EX12b：`ExecutionCoordinatorLease` 及单元/真实数据库测试（3 个主要文件），使用独立 `718395204` 会话锁。核对 backend 和锁持有，失效不可恢复；异常回收按 abort → Hikari evict → close，前两步失败不归还连接。缺类 RED 后真实测试发现并修复 Hikari 已关闭连接的回收异常；11 单元 + 4 PG 集成通过，遗漏 evict 的变异被 3 项捕获。恢复后 2026-10-24 11:06:06 全部 **428 单元 + 28 相关集成通过**，零失败/错误/跳过，见[证据](../../docs/project-execution-verification.md#2026-10-24--ex12b-独立构建协调租约)。不自动启动调度或工具。
  - [x] EX12c：协调器与 worker、取消信号、排队到期和关闭接线；容量竞争、真实子进程取消/重复中断及旧协调器共存验收。预计仍需补运行环境/生命周期边界时先拆兼容片，不将超出五文件的实现塞进单一任务；EX13 的崩溃恢复未完成前不声称重启核对已交付。
    - [x] EX12c1：`ExecutionBuildQueue`、`ExecutionBuildRepository`、`ExecutionBuildQueueIT` 及测试 profile 默认 schema 修正（4 个主要文件）。协调权下有界读取等待任务，在项目/构建行锁内处理原排队期限、取消及权限变化；已领取/待核对/终态不变，失锁整笔回滚。8 项 PG 用例与失锁变异已验证；11:38:38 全部 **428 单元 + 39 相关集成通过**，零失败/错误/跳过。首次聚焦命令误用旧 `atoms`，追加既有迁移及本次夹具；现场保留，已修正测试默认配置，具体影响及只读核对见[记录](../../docs/project-execution-verification.md#2026-10-24--ex12c1-等待任务处理)。未自动启动线程或工具。
    - [x] EX12c2：在 c1 上接实际调度器、worker 及生命周期；开始前核对所需文件并继续遵守每片五文件限制。覆盖启动前未决阻断、容量/多项目竞争、实际子进程取消和关闭；尚未完成恢复机制时不重放旧执行。
      - [x] EX12c2a：调度核心（`ExecutionCoordinator`、`ExecutionBuildRepository`、`ExecutionSchedulerIT`、`ExecutionSchedulerFixture`，4 个主要文件）。10 项真实 PG/JVM 测试覆盖默认/四并发、旧容量、取消、关闭、失锁和启动未决阻断；未决保留变异被捕获。13:49:05 全部 **428 单元 + 54 相关集成通过**，零失败/错误/跳过，见[证据](../../docs/project-execution-verification.md#2026-10-24--ex12c2a-调度核心与实际子进程取消)。内部工厂持有根及 worker，尚未增加 Spring 自动装配，不把受控 JVM 称为实际工程构建成功。
      - [x] EX12c2b：源码就绪、维护者测试库注册表及原生 `ExecutionBuildWorker` 的 Spring 启动接线；开始前按实际文件继续拆片。默认关闭保持无副作用，完整 EX12 需此接线与实际 worker 组合验收后再勾选。
        - [x] EX12c2b1：`ProjectSourceReadGateway`、`ProjectSourceReadinessTest`、`SourceAgentFixture`（3 个主要文件），添加无 I/O 的源码生命周期就绪查询，保留既有读版本接口。14:00:51，4 单元 + 8 源码读取集成及打包通过，零失败/错误/跳过；详见验证记录。
        - [x] EX12c2b2：原生资源工厂、协调器 Spring 装配、配置项与验收接线；拆为以下小片，不跳过原生构建验证。
          - [x] EX12c2b2a：`ExecutionEnvironment`、`ExecutionEnvironmentTest`、`application.yml`、`.env.example`（4 个主要文件），本地提交 `a701aa6`。懒加载维护者注册表、受保护根及原生 worker；注册表漂移变异被捕获并恢复。14:07:10，23 单元 + 8 源码集成及打包通过，零失败/错误/跳过。
          - [x] EX12c2b2b：`ExecutionCoordinator`、Spring 装配测试、`ExecutionSeedBuildContractIT`（3 个主要文件）。接入生命周期后通过实际原生构建验证，与默认关闭及旧协调器兼容检查一并验收。 14:37:51，439 单元 + 63 项相关集成及打包通过，含真实原生契约和队列顺序修复；见验证记录。
- 描述：唯一协调者驱动默认一个构建，支持显式 1–4 并发和持久取消。
- 验收：默认 1/排队 8、每项目一个活动构建，排队 300 秒及共享 1800 秒；启动意图先落库，取消先记录再由拥有者停止；未决不释放容量，Agent 并发配置不变。
- 验证：I(ExecutionSchedulerIT,ExecutionCancellationIT)，真实受控子进程、多项目竞争、排队/执行取消及重复中断；核对测试进程退出。
- 主要文件：a 的三个文件先实现；b/c 在上述边界下分别列出租约/协调器及对应测试。原估计的 `ExecutionCoordinator`、仓库、服务和两个集成测试不足以覆盖独立租约，按片验证后提交。
- 接线前锁问题已由 EX10d3b1 处理：构建准入原与 Agent 协调会话共用 `718395202`，现使用独立的 `718395203`，真实双连接共存及退回旧编号的变异已验证，隔离 JDK 完整回归通过。EX12 仍需验证完整协调器共存；锁冲突不是观察失败的已证实根因，不改原未决状态。

## C-EX04 · 真实构建成立

- [x] EX10–12 完成：有真实封存 JAR 证据，不仅是假进程；取消和容量结算准确，旧网页不变。若真实构建未通过，此检查点不得勾选。

## EX13 · 构建崩溃恢复

- [x] 完成 EX13。依赖：EX12。按每片最多 5 个主要文件拆分：a 恢复结算与 PG 验证，b 启动接线，c 真实 JVM 崩溃窗口。
  - [x] EX13a：只凭持久意图及已确认的阶段结算判断中断；未知执行、封存与数据库间隙保留容量，不重建进程/产物句柄。14:53:44，2 单元 + 52 相关集成通过；移除历史核对的变异被测试捕获并已恢复。
  - [x] EX13b：持有协调与目录租约后先恢复，核对完成前不调度；配置变化或失锁阻断启动。14:56:49，7 单元 + 29 相关集成通过；未决任务阻断时排队项保持原样。
  - [x] EX13c：真实测试 JVM 终止/重启，覆盖五个执行意图及产物结算窗口，保留故障证据。15:29:52 全部 439 单元 + 121 相关集成通过，零失败/错误/跳过；无新迁移、无真实模型调用。
- 描述：在外部执行意图、产物封存与 DB 结算之间注入崩溃，重启后按证据核对。
- 验收：已启动但结果未知不重跑；无执行意图可安全结算、封存与 DB 不一致保留未决；恢复完成前不接新任务，租约丢失后不继续启动。
- 验证：I(ExecutionRecoveryIT)，真实测试 JVM 终止/重启和配置漂移，保留故障记录；不 kill 按旧 PID 找到的未知进程。
- 主要文件：`E/ExecutionRecovery.java`、`E/ExecutionCoordinator.java`、`ET/ExecutionRecoveryIT.java`、`ET/ExecutionCrashProbe.java`。

## EX14 · 依赖准备任务与编辑互斥

- [x] 完成 EX14。依赖：EX05、EX07、EX12、EX13。按每片最多 5 个主要文件拆分。
  - [x] EX14a：共享容量迁移及原构建兼容，统一排队/执行占位；16:04:08，7 单元 + 45 相关集成通过。V025 只应用于批准的测试 schema（4 个主要文件）。
  - [x] EX14b：依赖准备持久意图、准备批准及源码编辑租约原子准入；双连接竞争、重放和回滚通过。16:29:39，439 单元 + 156 相关集成通过，容量漏算变异已捕获并恢复；原审计保持不变（4 个主要文件）。
- 描述：为 lock-only 准备增加独立持久意图，与源码编辑租约和构建容量一致准入。
- 验收：绑定基线/修订号、清单及准备批准，原子领取租约；与同项目 Agent 编辑/源码恢复/构建冲突，不超共享队列；同键重放不重复解析或遗留租约。
- 验证：I(ExecutionPreparationAdmissionIT)，真实 PG/source 双连接竞争及回滚；不调用模型。
- 主要文件：a 为 `M/V025__execution_task_slots.sql`、`E/ExecutionBuildRepository.java`、`E/ExecutionBuildClaim.java`、`ET/ExecutionAdmissionIT.java`；b 为 `M/V026__project_dependency_preparations.sql`、`E/DependencyPreparationRepository.java`、`E/DependencyPreparationService.java`、`ET/ExecutionPreparationAdmissionIT.java`。运行/绑定迁移顺延为 V027；不编辑已应用迁移。

## EX15 · lock-only 发布新源码版

- [x] 完成 EX15。依赖：EX03、EX05、EX06、EX14。接入时发现可编辑源码目录、最终候选校验及一次性执行意图需要独立适配，按每片最多 5 个主要文件拆分。
  - [x] EX15a：源码网关提供受租约保护的副本读取及最终候选校验；npm 策略检查仅锁文件变化、依赖来源/完整性及清单一致性，新锁不自动获得构建批准。16:46:28，12 单元 + 22 相关集成通过；移除文件集合限制的变异被捕获并恢复（4 个主要文件）。
  - [x] EX15b：私有工具环境中的 lock-only 命令，使用源码副本作为执行身份，固定版本/参数并保留完整停写证明。17:00:13，10 项受控进程/工具单元通过；17:05:07，实际 npm 生成 250 项依赖的锁文件并发布新源码版，第二轮无变更不新增版本。补齐未批准 yarn.lock 输入的拒绝，17:16:39，16 单元 + 10 集成通过。准备任务 worker 仍由 c 接入。
  - [x] EX15c：准备任务一次性领取、执行意图及发布结果结算；接入 worker，覆盖失败/无变更/CAS 和异常停写。
    - [x] EX15c1：`DependencyPreparationClaim`、`ProjectSourceEditGateway` 的只读未执行租约检查与 `ExecutionPreparationClaimIT`（3 个主要文件）。短事务一次性领取，复核任务绑定的编辑能力与基线、原始/当前共享容量、取消和数据库排队期限；不启动进程，不跨模块直接查询源码表。17:39:56，16 单元 + 32 相关集成通过，包括 8 项新领取用例。
    - [x] EX15c2：`DependencyPreparationLifecycle`、`DependencyPreparationService`、`ProjectSourceEditGateway` 与 `ExecutionPreparationLifecycleIT`（4 个主要文件）。短事务保存执行意图和停止事实，源码操作事实决定结算；原能力在确认停止/源码操作终结后可以收尾，不因账号切换把已提交源码误报失败，未知执行仍保留占位。17:50:53 初始 16 单元 + 34 相关集成通过；补齐未知结果反例后，17:55:42，16 单元 + 22 相关集成通过（生命周期 10 项、原源码/Agent 12 项）。
    - [x] EX15c3：单次 preparation worker 组合已验证的工具命令、最终候选校验与上述元数据链路（2 个主要文件）；18:05:25，16 单元 + 14 项相关集成通过，其中 4 项完整 worker、10 项生命周期。调度/恢复仍归 EX16。
  - [x] EX15d：实际 npm lock-only 经完整任务发布新源码版本，并核对后续构建仍需新锁批准；22:11:42 真实契约通过，250 项依赖、只改锁文件，二次 NO_CHANGES 不造版本，两任务均释放。只修改 `ExecutionLockPreparationContractIT`（1 个主要文件）；默认 1800 秒下通过，300 秒超时保留。
- 描述：在源模块持有的任务副本执行批准的固定锁文件准备，再经停写证明发布新版本。
- 验收：只允许目标锁文件变化，无 node_modules/秘密进入候选；确认进程及 I/O 停止后才能接受，CAS 冲突不覆盖他人修改；新锁不自动取得构建批准，无变更不造版本。
- 验证：U(ExecutionLockPreparationTest,ExecutionNpmPolicyTest) + I(ExecutionPreparationClaimIT,ExecutionPreparationLifecycleIT,ExecutionPreparationWorkerIT)；真实 npm lock-only 使用 REAL(ExecutionLockPreparationContractIT)，不以受控 CLI 代替 registry 解析。
- 主要文件：a 为 `source/ProjectSourceEditGateway.java`、`source/ProjectSourceEditAcceptanceIT.java`、`E/ExecutionNpmPolicy.java`、`ET/ExecutionNpmPolicyTest.java`；旧入口免额外读取的兼容调整已单独提交 `2620634`。b 为 `E/ExecutionToolchain.java`、`E/ExecutionNpmPolicy.java`、`ET/ExecutionNpmPolicyTest.java`、`ET/ExecutionLockPreparationTest.java`、`ET/ExecutionLockPreparationContractIT.java`（5 文件）。c/d 的 worker/生命周期及完整任务契约分别落地，原 4 文件估计不足以覆盖这些边界，不把 I/O 放进数据库事务或用最后一个子进程的 receipt 冒充全部执行已停止。

## C-EX05 · 构建恢复与依赖准备

- [x] EX13–15 的故障与发布测试通过；锁文件变更有独立来源版本及批准边界，旧 Agent 编辑/停写证明不退化。真实整轮使用默认 1800 秒预算，另保留 300 秒超时与正确收尾证据；115 条准备记录无未完成/pending、活动编辑或共享槽，旧审计摘要不变。

## EX16 · 准备任务取消与恢复

- [x] 完成 EX16。依赖：EX13–15。原 4 文件估计不足，按下列可验证片顺序实施，每片最多 5 个主要文件。
  - [x] EX16a：持久取消入口与查询状态；排队任务原子释放，已领取任务只提交取消意图，重复取消及源码已提交优先。22:49:09，16 单元 + 21 集成通过；23:01:48 单用例反向检查正确失败，代码逐字恢复至通过版本。Service、Repository、CancelIT、WorkerIT、LifecycleIT 共 5 文件；后三者允许准确的新隔离测试目标。首轮夹具顺序故障和未决记录保留，见验收文档。
  - [x] EX16b1：启动恢复；先读取源码恢复事实，再关联原 source operation 核对，不重建可写能力或重跑安装，未知执行保留租约与容量。2026-10-25 00:20:40，16 单元 + 30 集成通过，含 11 恢复、7 取消、8 源码编辑、4 旧 Agent 生命周期。SourceGateway、Recovery、Repository、RecoveryIT 共 4 文件；尚未接协调器，也不是 JVM 崩溃整体验收。
  - [x] EX16 测试环境接续：默认目标切换至本轮已批准的新 schema，准确目标守卫分两片同步（默认配置/ClaimIT/BuildQueueIT/RecoveryIT/CrashProbe 5 文件；SchedulerIT/两项原生契约 3 文件）。00:47:32，30 项领取/排队/恢复通过；00:49:46，12 项调度通过。旧未决现场保留，原生契约仅编译并核对目标守卫，未重跑 npm/Maven 下载。
  - [x] EX16b2：准备队列的原期限、撤权及关闭结算；只释放从未领取且仍持原能力的任务。00:56:59，30 项集成通过（队列 12、取消 7、恢复 11）；Queue、Repository、QueueIT 共 3 文件。缺能力仅保留取消意图/名额，dispatchable 避开提交后的能力交接间隙；自动调度接线仍在 c 完成。
  - [x] EX16c：构建与准备共享协调者、工作线程和关闭流程；先兼容接线，再验收公平调度、取消和重启，每片最多 5 文件。
    - [x] c1：原生资源工厂提供准备 worker 入口，保持旧构建资源兼容（Coordinator 接口、Environment、EnvironmentTest、SeedBuildContractIT，4 文件）。01:06:20，8 项工厂/Spring 单元测试通过，原生契约接线已编译；本片尚不自动派发准备任务。
    - [x] c2：协调器合并两个有序队列、共用并发与停机结算，启动先恢复准备（Coordinator、Repository、SpringTest、PreparationSchedulerIT，4 文件）。01:26:37，8 单元 + 30 集成通过，含混合调度 6、队列 12、旧调度 12；01:35:04，2 单元 + 新增关闭/未决启动场景 2 项通过。两轮保存/无变更、陈旧基线拒绝、共享四并发、公平调度、失去协调权与停止收尾均有实际 PG/Git/受控进程证据；不替代真实 npm 契约。
  - [x] EX16d：实际 JVM 在源码提交后、任务绑定前 halt，重建协调器恢复准确版本且不重复执行/发布，第二次启动不变（CrashProbe、CrashIT，2 文件）。01:37:43，2 单元 + 1 崩溃集成通过；01:42:24，全部 446 单元 + 16 原源码编辑/取消/恢复集成通过；01:43:31 补充 4 项旧 Agent 生命周期通过。两轮基线竞争沿用 c2 的实际 Spring 验收，既有五个构建崩溃窗口和真实 npm 结果不重复计数；最终 117 条准备记录均终结，无 pending/源码编辑/共享名额/协调锁残留，见验收文档。
- 描述：让准备任务使用同一协调者容量、期限及停写结算，处理源码已发布但任务响应丢失。
- 验收：取消不冻结仍可写候选；未知执行保留源码租约，已提交版本优先反映真实结果；服务重启从 source operation 关联核对，不再次安装/发布。
- 验证：I(ExecutionPreparationRecoveryIT)，队列取消、子进程中断、发布后崩溃以及两轮基线竞争；原源码恢复测试回归。
- 主要文件：`E/ExecutionCoordinator.java`、`E/ExecutionRecovery.java`、`E/DependencyPreparationService.java`、`ET/ExecutionPreparationRecoveryIT.java`。

## EX17 · 持久运行身份与独立业务库绑定

- [x] 完成 EX17。依赖：EX07、EX08、EX13。原 5 文件估计未含业务配置与原测试库兼容，实施前拆为三片，每片最多 5 个主要文件。
  - [x] EX17a：业务用途私有配置及共享只读目录检查，测试凭据和业务凭据互不接受（PrivateConfig、DatabaseInspection、TestDatabase 及两项单测，5 文件）。01:53:25，9 单元 + 12 既有真实数据库/启动契约通过，未改私有配置或业务库；不代表持久绑定已完成。
  - [x] EX17b：V027 持久项目/数据库/角色/端口绑定，容量及唯一性由 PG 约束保证（迁移、BindingRepository、DatabaseBinding、BindingIT、BindingContractIT，5 文件）。02:04:07，9 单元 + 11 集成通过；实际两套业务库只读核对，固定 A/B 测试项目持久登记，各次重跑复用；配置漂移、缩容、无事务写入、跨项目复用和第五槽拒绝通过。未创建新库或启动应用。
  - [x] EX17c：V028、Run、RunRepository、RunIT 共 4 个主要文件，运行身份固定成功构建/来源/数据库引用；同键重放、单项目单活动运行、终态不可改写与批准不可跨构建复用通过。02:17:34，全部 448 单元 + 22 项相关集成通过，耗时 4:08，无失败/错误/跳过。只读审计确认保留 A/B 两条绑定，运行/批准夹具均回滚、共享名额与绑定锁均为 0；没有启动应用或迁移业务库，EX18 接实际历史判定。
- 描述：追加运行、项目 DB 绑定和批准记录；绑定由维护者提供，不接受网页连接串。
- 验收：每项目单一持久绑定、最多四个配置项目，端口/DB/角色不能跨项目复用；只读确认实际业务库及最小权限、拒绝测试/平台/系统库；停止不删除 DB、不释放给别项目。
- 验证：I(ExecutionBindingIT)，双连接抢占、配置漂移、真实业务/测试库区分；准确目标创建须另获批准。
- 主要文件：按 a/b/c 分片记录；V027 为业务绑定，V028 为运行/批准元数据。运行层为 `E/ExecutionRun.java`、`E/ExecutionRunRepository.java`、`ET/ExecutionRunIT.java`；真实业务库契约为 `ET/ExecutionBindingContractIT.java`。

## EX18 · 迁移批准与兼容性判定

- [x] 完成 EX18。依赖：EX10、EX17。原 4 文件估计未包含源码/JAR 对照和实际历史读取，实施前拆分，每片最多 5 个主要文件。
  - [x] EX18a：有界 SQL 迁移集合、源码/JAR 一致性及纯兼容性规则（Migrations、MigrationPolicy 及两项单测，4 文件）。02:55:01，12 项单元通过，含原产物封存 6 项；不启动 JAR 或连接业务库。历史读取和批准仍待 b/c。
  - [x] EX18b：业务库只读历史快照、身份复核及 PG 契约（MigrationHistory、DatabaseBinding、MigrationHistoryIT，3 文件）。03:03:56，6 单元 + 9 真实 PG 契约通过，覆盖历史文本/数量、伪装视图、RLS、非表对象及原业务绑定；异常 SQL 夹具全部回滚，A/B 验收库仍为空。
  - [x] EX18b2：自审补充仅有排序规则等对象的非空 schema 检查（MigrationHistory、MigrationHistoryIT，2 文件）。03:19:21 真实排序规则夹具复现误判；改用 schema 依赖目录后，03:22:32，12 单元 + 37 项相关集成通过，零失败/错误/跳过，耗时 2:39。最终审计仅保留原 A/B 两条绑定，运行/批准/共享占位/绑定锁均为 0。
  - [x] EX18c：维护者批准、准确历史/构建绑定与启动前复核（MigrationReview、MigrationReviewRepository、MigrationReviewTest、MigrationReviewIT，4 文件）。03:16:03，全部 460 单元 + 36 项相关集成通过；原 6+18 首轮结果另存，编排权限变化/事务/脱敏覆盖通过。非表对象边界的补充修正及最终回归另见 b2。
- 描述：在新进程获得业务 DB 配置前检查已有历史、目标迁移集合及准确批准。
- 验收：已确认空库才允许首次初始化；非空库变化返回 RUN_MIGRATION_REVIEW_REQUIRED，批准绑定项目/库/原历史/目标摘要；失败升级、历史不兼容或旧版恢复不可自动 repair/clean/降级。
- 验证：U(ExecutionMigrationPolicyTest) + I(ExecutionMigrationReviewIT)，批准错项目/错历史/错 JAR、校验和改变和部分升级；检查拒绝时 DB 未写入。
- 主要文件：a 为 `E/ExecutionMigrations.java`、`E/ExecutionMigrationPolicy.java` 及对应单测；b 为 `E/ExecutionMigrationHistory.java`、`E/ExecutionDatabaseBinding.java`、`ET/ExecutionMigrationHistoryIT.java`；c 如上四文件。

## C-EX06 · 数据库边界明确

- [x] EX16–18 完成，准备任务恢复、真实 PG 历史读取/拒绝及批准绑定通过。批准不等于可自动回滚；本轮只用已批准四库中的 A/B 业务验收库，异常夹具全回滚，不新增 DB/角色。真实 JAR 升级和失败迁移执行仍按 EX25/后续应用验收完成。

## EX19 · 持有常驻应用进程

- [x] 完成 EX19。依赖：EX03、EX10、EX17、EX18。原 5 文件未涵盖共享进程的活动身份及固定启动参数，实施前拆片，每片最多 5 个主要文件。
  - [x] EX19a：共享拥有者签发只读活动身份，保留原停止证明和旧 Agent/构建行为（OwnedProcess、OwnedProcessLifetimeTest，2 文件）。04:17:58，34 项真实进程及原 Agent/构建回归通过，见验收记录。
  - [x] EX19b：常驻进程句柄、启动期限/运行寿命分离、持续排空、幂等停止与未知结果（RunProcess、RunProcessTest，2 文件）。15:14:38，修复停止时重开启动期限的竞态后，18 项通过；错身份变异及竞态红灯保留。
  - [x] EX19c：固定 Java/classpath/私有配置/loopback 启动命令及文件复验；复用原工具身份并让运行目录分配沿用启动预算（RunCommand、RunCommandTest、Toolchain、Paths，4 文件）。15:20:49，20 项通过；业务凭据装配及 EX18 启动前复核由 EX20 接线。
  - [x] EX19d：模板健康响应回显运行/执行标识（HealthController、HealthControllerTest，2 文件）；4 项原生 MVC 测试通过，15:26:09 全部 473 单元 + 30 项相关集成通过并打包。新模式仍关闭，EX20 负责真实 HTTP 健康与持久启动接线。
- 描述：后台拥有 Java 进程直到退出；给受控种子健康响应加入运行身份契约。
- 验收：明确 classpath/私有配置/loopback，源码脚本不负责平台启动；启动窗口与运行寿命分开，READY 后仍排空流及观察退出；健康响应回显本次运行标识，缺此契约不退化为任意 HTTP 200。
- 验证：U(ExecutionRunProcessTest)，受控常驻子进程与重复停止、输出洪泛；种子 HealthControllerTest 及原生模板测试回归。
- 主要文件：`E/ExecutionRunProcess.java`、`ET/ExecutionRunProcessTest.java`、`T/backend/src/main/java/dev/atoms/generated/HealthController.java`、`T/backend/src/test/java/dev/atoms/generated/HealthControllerTest.java`、`SPEC-project-execution.md`。
- 备注：运行标识只防止平台误认，非秘密或任意代码安全认证；既有源码快照不被新模板改动追溯重写。

## EX20 · 幂等启动与真实健康状态

- [x] 完成 EX20。依赖：EX17–19。原 5 文件估计未覆盖成功产物重新读取、业务凭据发放及运行结算；实施前拆片，每片最多 5 个主要文件。
  - [x] EX20a：固定 loopback 健康请求、有界响应、运行身份及活进程核验（Health、HealthTest，2 文件）。16:30:30，12 项进程/HTTP 组合通过；增加事务边界后 16:32:33，5 项 HTTP 测试通过。持久 READY 尚待 d–f 接线。
  - [x] EX20b：成功构建的封存产物重新读取，核对 DB 摘要及原清单/源码身份；不恢复进程或停止凭据（Artifact、ArtifactManifest、Paths、ArtifactReopenTest，4 文件）。16:38:08，20 项相关测试通过。
  - [x] EX20c：业务注册表签发本次执行专用的凭据装配能力，启动前重新核对物理绑定；同时绑定固定运行命令（DatabaseBinding、PrivateConfig、RunCommand、RuntimeLaunchContractIT，4 文件）。16:49:03，5 单元 + 7 项真实绑定/只读凭据契约通过并打包；未启动业务应用。
  - [x] EX20d：幂等领取、执行意图、健康就绪与真实停止/未知结果的持久状态流转（RunRepository、RunJournal、RunStateIT、RunJournalTest，4 文件）。16:59:47，20 单元 + 25 项相关 PG 集成通过，停止身份变异被拦住；提交结果不明不重试，未就绪应用即使停止也保留数据待核对。
  - [x] EX20e：启动准入重新核对当前权限、归档、来源、产物、绑定与迁移批准（RunService、RunAdmissionTest、RunAdmissionIT 及必要接线，最多 5 文件）。
    - [x] e1 内部准入和单次领取能力，原启动预算贯穿预检与启动前重查。17:09:39，23 单元 + 18 项现有 PG 集成通过（新增 7 项准入组件测试，真实文件/事务边界，元数据与迁移依赖为受控替身）。2 个主要文件。
    - [x] e2 真实 PG 的双连接并发准入/提交：17:26:04，7 单元 + 1 项显式契约通过，两个实际 backend PID、一个提交记录和一张 ticket，含真实 Git/封存/业务历史及权限反例。2 个主要测试文件；外部进程启动组合仍由 f 验收，不以本项替代。
  - [x] EX20f：串联真实进程、HTTP 健康与失败收尾，验证单次启动和提交/退出竞争（RunWorker、RunHealthIT 及必要契约，最多 5 文件）。运行调度的 Spring 生命周期、恢复与关闭在 EX21–22 接入。
    - [x] f1 常驻期间当前授权/停止状态检查与 READY 提交的剩余启动期限（RunService、RunJournal 及对应两项测试文件）。17:32:42，24 单元 + 13 项 PG 集成通过；4 个主要文件。
    - [x] f2 worker 串联及真实 PG/受控 HTTP 子进程组合；17:39:56，24 单元 + 1 项真实组合通过。先提交执行意图、一次启动、HTTP 就绪、持久停止及停止凭据结算，外来端口保持不动。2 个主要文件；原生生成应用仍由 EX25–27 验收。
    - [x] f3 启动/健康/提交失败与未知进程反例，完成写入失败保留原拥有句柄（RunWorker、RunWorkerTest，2 个主要文件）。38 单元 + 13 PG 集成通过，错误释放句柄的变异被拦住；18:03:49 全部 506 单元 + 61 项相关集成通过，零失败/错误/跳过。6 条运行均终结、原 2 绑定不变、共享槽位/绑定锁为 0，见验证记录。
- 描述：从封存 buildId 创建一次运行意图，健康检查成功才发布 READY。
- 验收：每次启动重新授权/校验产物、绑定、迁移批准，意图先于 launch 持久化；同键只启动一次，端口冲突及来源不匹配明确失败；持有本进程事实及预期健康身份，120 秒超时不伪造就绪。
- 验证：I(ExecutionRunAdmissionIT,ExecutionRunHealthIT)，跨所有者、归档、改 JAR、外来端口/重定向/假健康、启动中崩溃及退出竞争。
- 主要文件：`E/ProjectExecutionService.java`、`E/ExecutionRunWorker.java`、`E/ExecutionHealth.java`、`ET/ExecutionRunAdmissionIT.java`、`ET/ExecutionRunHealthIT.java`。

## EX21 · 停止与换版不产生双写者

- [x] 完成 EX21。依赖：EX20。按实际接线拆为以下小片，每片最多 5 个主要实现/测试文件。
  - [x] EX21a：当前所有者的持久停止入口、归档清理、重复/未知状态及事务失败反例（RunRepository、RunService、RunStateIT、RunAdmissionTest）。18:13:45，27 单元 + 16 PG 集成通过，4 个主要文件；仍不由 stop 调用直接关闭进程或释放容量。
  - [x] EX21b：绑定前一运行和目标构建的幂等换版准入，停止与启动分阶段返回，不在旧进程未确认停止时发放 ticket；worker 启动前取消与受控进程换版验收。18:28:00，并发/撤权/取消补充场景通过，33 单元 + 18 集成。
    - [x] b1 换版请求身份/准入及组件反例（Run、RunRepository、RunService、RunAdmissionTest，4 文件）。18:24:10 与 b2 组合 31 单元 + 17 集成通过；普通请求保持原摘要，换版摘要绑定原运行 UUID。
    - [x] b2 真实换版组合发现端口快速复用误判；真实 TCP 红灯定位后修复端口预检，两次实际启动和外来监听拒绝通过（RunWorker、RunWorkerTest、RunHealthIT，3 文件）。18:24:10，31 单元 + 17 集成通过；保留首次组合失败和端口红灯记录。
  - [x] EX21c：并发换版、取消与撤权补充反例，变异与审计收尾；18:34:47 全部 514 单元 + 65 项相关集成通过，0 失败/错误/跳过。20 条运行均终结，原 2 绑定不变，无共享占位或绑定锁；正式 Spring 运行调度及关闭/恢复由 EX22 接入。
- 描述：停止是持久操作；换版先结束旧进程再启动指定新构建。
- 验收：同项目只能一个写入进程，停止失败不启动下一版；重复停止幂等，真实退出/I/O 收尾后才释放进程容量；新版本失败保留旧构建身份及 DB，恢复旧版仍经过迁移兼容检查。
- 验证：I(ExecutionRunSwitchIT)，并发 start/stop、失去权限后的自有清理、旧进程拒停和新版本启动失败；检查没有第二写入者。
- 主要文件：`E/ExecutionRunWorker.java`、`E/ExecutionRunRepository.java`、`E/ProjectExecutionService.java`、`ET/ExecutionRunSwitchIT.java`。

## C-EX07 · 本机运行生命周期

- [x] EX19–21 的真实受控进程测试及构建通过；启动、健康、停止、换版互不混淆。当前 514 单元 + 65 相关集成通过，详情见 EX21c；完整业务持久化仍由 EX27 验收。

## EX22 · 运行恢复及数据未知状态

- [x] 完成 EX22。依赖：EX13、EX18、EX21。原 4 文件估计未包含运行资源装配与常驻调度，实施前拆为以下顺序小片，每片最多 5 个主要实现/测试文件。2026-10-26 07:54:59，552 单元 + 95 项相关集成通过，原故障场景与未决资源保留均有真实进程证据。
  - [x] EX22a1：短事务核对遗留运行；只终结从未发出执行意图的任务，旧 READY/已领取任务转待核对，保留绑定、执行与数据证据（RunRepository、RunStateIT，2 个主要文件）。18:50:10，33 单元 + 23 PG 集成通过；18:51:19 移除既有待核对保护的变异被拦截，源码逐字恢复。尚待 a2 的独占协调权包装及 b 的启动接线。
  - [x] EX22a2：独占协调权、配置/源码可用性及事务前后复核；拒绝嵌套事务，数据库故障和授权变化不部分写入（RunRecovery、RunRecoveryTest、RunAdmissionIT，3 文件）。18:54:59，42 单元 + 24 PG 集成通过；实际 UPDATE 后配置漂移回滚、真实租约关闭后拒绝恢复，提交前失锁保护的变异被拦截。尚未接 Spring 启动。
  - [x] EX22b：运行资源装配、统一协调者管理常驻任务及停机收尾；常驻进程不占构建线程，仍共用原排他协调权和执行根。按资源接线、调度、关闭测试分别提交；21:18:02 全部 548 单元 + 112 集成通过。故障组合 c2 与 EX22 整项仍未勾选。
    - [x] EX22b0：先接入启动恢复及未决运行阻断（Coordinator、RunRecoveryIT、RunCrashProbe，3 文件）；与 c 的实际崩溃窗口一起验收。19:05:16，11 单元 + 23 集成通过；19:11:46 补充关闭资源保留场景与全部 523 单元 + 89 集成通过。恢复失败的启动未拥有任何应用进程，可关闭本次根/租约；已激活调度器遇到未知运行则停止并保留资源，不伪造清理结果。
    - [x] EX22b1：运行资源工厂装配业务配置、准入与常驻 worker；复用原执行根，默认关闭不访问运行配置，按以下小片实施。20:11:57，全部 534 单元 + 91 集成通过，12 份日志与本轮 XML 归档；57 条运行全部终结，原绑定和业务历史不变，私有值核对零匹配。统一调度仍待 b2。
      - [x] b1a：worker 在原单次启动预算内惰性核对工具，保留原进程能力并提供自有停止/持有状态给生命周期管理（RunWorker、RunWorkerTest，2 文件）。19:21:13，43 单元 + 18 集成通过；错误在停止信号后丢弃未确认句柄的变异被拦截，源码逐字恢复。
      - [x] b1b：可选业务配置注册表与运行资源接线，复用根和已有准入/迁移/worker；配置为空保持原构建能力。
        - [x] b1b1：业务/测试数据库、角色、绑定及监听端口不能交叉使用，私有业务配置漂移检测（TestDatabase、DatabaseBinding、RuntimeRegistryTest，3 文件）。19:27:11，25 单元 + 7 真实绑定集成通过；移除端口交叉检查的变异被拦截，源码逐字恢复。
        - [x] b1b2：资源工厂连接准入与常驻 worker，单次附接协调权，关闭时不得丢弃未确认拥有能力（Environment、Coordinator 的资源接口、RuntimeResources、EnvironmentTest，4 文件）。19:33:10，31 单元 + 23 集成通过；配置文件漂移保护变异被拦截，源码逐字恢复。暂不自动派发常驻任务，b2 仍待完成。
      - [x] b1c：配置项、资源工厂真实契约及隔离校验（application.yml、.env.example、RuntimeResourcesIT，3 个主要文件）。20:02:48，31 单元 + 6 集成通过；实际工厂复用原 A/B 配置、源码环境及 PG，装配不创建运行或启动应用；原记录和业务历史不变。私有 env 未修改。
    - [x] EX22b2：同一协调者下的常驻任务管理及停止收尾；使用单独有界运行线程，原构建/准备共享容量不被常驻线程占用。按下列三片完成，原两组共 20 项调度集成及新常驻组合均通过；77 条持久运行全部终结，原绑定、端口和物理 OID 不变，实际私有值核对零匹配。
      - 准入提交与 ticket 交接期间须有本轮明确身份，不能被巡检误当遗留运行；并发重放不能重复占位/启动，满容量时仍需正确处理既有请求。关闭必须等待准入和 worker 收尾，长预检不能占住关闭锁；配置/租约/数据库故障只处置原拥有进程，未知结果继续保留根与证据。
      - [x] b2a：分离授权重放查询与换版停止阶段，让容量检查前的操作不能签发 ticket 或进入长预检（RunService、RuntimeResources、RunAdmissionTest，3 个主要文件）；保留原直接服务兼容入口。20:23:19，37 单元 + 8 PG 集成通过；移除查询后的配置复核使新增用例准确失败，源码逐字恢复。
      - [x] b2b：内部有界常驻调度器，将原请求身份、准入、ticket 交接及 worker 放在同一个受管理生命周期中；重复请求复用结果，关闭等待准入及实际线程收尾（RuntimeDispatcher、对应组件测试，2 个主要文件）。20:41:29，39 项相关单元通过，含 10 项实际线程组件用例；跳过关闭后迟到 ticket 的变异被拦截，源码逐字恢复。PG/实际进程组合与打包随 b2c 验证，未将组件替身称为进程验收。
      - [x] b2c：Coordinator 附接资源和调度器，运行巡检区分本轮拥有意图与未知状态；停止和根释放等待两类线程（Coordinator、RuntimeResources、RuntimeSchedulerIT、RuntimeFixture、RuntimeCoordinatorTest，5 个主要文件）。20:52:32，21 单元 + 3 集成通过；20:55:25 另 2 项关闭保留/故障派发组件通过。真实 PG 与 HTTP JVM 验证并发启动、构建同时运行、换版及主动关闭，实际停止凭据保存且根/租约可重取；完整回归随收尾执行。
  - [x] EX22c：实际测试 JVM 崩溃、重新取得租约、未知状态阻断和不重放验收；只用原拥有证据处置本轮进程，不清除未知旧审计。收尾回归暴露停止等待中断竞态，补 c2c 修复及 c2d 新环境复验后通过；失败历史保留。
    - [x] c1：b0 中三个实际 halt 窗口、两轮协调者重启及已关闭独立连接池反例通过；父测试保留原 HTTP 进程能力，收尾只使用本轮准确停止凭据。并非原生业务迁移或真实 PostgreSQL 服务断电验收。
    - [x] c2：b1/b2 接线后验证常驻管理器在失去协调权或元数据写入不可用时关闭自有进程，保留未知结果，不提前释放执行根。
      - [x] c2a：统一协调者/资源不可用的运行诊断，真实 READY 后撤销本轮专用连接的协调锁并验证自有进程停止（RunWorker、RunWorkerTest、RuntimeSchedulerIT，3 个主要文件）。21:34:30，36 单元 + 4 集成通过，实际停止凭据保存且根/租约可重新取得；移除故障映射使两项新增用例准确失败，源码逐字恢复。
      - [x] c2b：独立测试 JVM 内关闭专用运行元数据连接池，以及真实完成提交后丢失回执；父测试核对仍持有根/协调锁、原进程确已停止和无重放（RuntimeFaultIT、RuntimeFaultProbe、RuntimeFixture，3 个主要文件）。21:45:59，26 单元 + 2 集成通过；21:48:10，错误丢弃拥有能力的变异使两项单元准确失败，源码逐字恢复。只处置本轮无业务凭据的 HTTP 夹具，原拥有者在完成断言后凭原停止证据结算自己的未完成记录；运行 worker 不重试、持有资源直到测试 JVM 退出，不增加生产清理接口或重建停止凭据。
      - [x] c2c：定位并修复停止证据等待被中断时过早落入未知状态的竞态（RunWorker、RunWorkerTest，2 个主要文件）。22:03:31 两项确定性复现失败；22:08:39 修复后全部 552 单元与打包通过。停止等待共用原 12 秒截止时间，收尾后恢复中断标志，不重复完成事务。2026-10-26 原失锁场景及完整相关组合复验通过；原失败 PG 记录保留，不宣称已消除所有历史竞态原因。
      - [x] c2d：用户已确认新测试 schema `atoms_verify_20261025_ex22`；2026-10-26 创建成功，归原 atoms_test，撤销 PUBLIC 权限，不扩大数据库 CREATE 权限。五份旧未决审计和原执行记录摘要未变。测试目标集中到单一常量及测试配置，调用处按每批最多 5 个主要文件机械替换。环境守卫先红后绿，7 单元 + 4 绑定集成通过；07:49:06 原故障组合 38 单元 + 8 集成通过，07:54:59 相关完整复验通过，不把换环境等同于修复。
  - [x] EX22d：相关故障与兼容回归、变异和私有证据归档。2026-10-26 07:54:59，552 单元 + 95 集成零失败/错误/跳过；新 schema 26 条运行全部终结，原五份审计与绑定摘要不变。两次故障映射/能力保留变异与中断红绿证据均保留；9 个实际私有值核对零匹配，归档 `.local/ex22-recheck-evidence-4qmexg3s`。
- 描述：重启协调者时核对运行与迁移证据，不收养未知进程或自动恢复服务。
- 验收：旧 READY 不能未经确认继续展示；启动/迁移中崩溃保留 NEEDS_RECONCILIATION 和绑定；不按 PID/端口猜测死亡，不重放未知 start，不抹掉数据状态待核对记录。
- 验证：I(ExecutionRunRecoveryIT)，真实测试 JVM 崩溃、租约丢失及 DB 不可用；旧进程处置只凭仍持有的拥有证据。
- 主要文件：a1/a2 如上；b 为 `E/ExecutionEnvironment.java`、`E/ExecutionCoordinator.java`、独立的内部运行调度助手及对应测试，实施前按依赖进一步拆片；c 使用独立运行崩溃夹具及 `ET/ExecutionRunRecoveryIT.java`，不把运行恢复规则混入已有构建恢复类。

## EX23 · 给交付模块的构建/运行身份

- [x] 完成 EX23。依赖：EX04、EX10、EX20–22。原 3 文件估计未包含访问协调者原资源的装配，拆为以下小片，每片最多 5 个主要文件。
  - [x] EX23a：协调者签发本次资源范围，复用原封存目录和原 worker 的进程能力；关闭/失权后失效，不新开根租约（Coordinator、Environment、RuntimeResources、CoordinatorAccessTest、EnvironmentTest）。2026-10-26：RED 缺失接口，GREEN 16/16；真实 PG/Git/进程组合留 EX23b。
  - [x] EX23b：内部消费者使用不可自行构造的 BuildHandle/RunningHandle；每次校验 owner/归档/配置/原构建及文件，源码固定来源版本；运行还匹配原进程和执行身份（ProjectExecutionAccess、ExecutionAccessIT、RuntimeFixture、RunProcess、RunProcessTest）。覆盖真实 PG/Git/HTTP、V1 构建后项目更新 V2、异主/异项目/异发行者、停止与文件替换；不新增代理/下载 HTTP。2026-10-26：RED 缺失接口；修正 Java 17 switch 编译后 GREEN 22 单元 + 3 集成。
  - [x] EX23c：移除返回前复核的变异准确失败，恢复后 08:26:44 全部 555 单元 + 98 相关集成通过、打包成功。只读核对 43 条运行全部终结、资源占位归零、旧审计不变；9 个私有值扫描零匹配，证据 `.local/ex23-evidence-9van_fcg`。不修改原 A/B 源码头或业务数据，版本竞争用新临时源码项目。
- 描述：提供窄的内部访问门面，将来源版本、封存产物与真实运行关联。
- 验收：授权不可混项目，篡改产物或失效运行即拒绝；句柄不能由路径/端口/UUID DTO 自行构造，不泄漏配置；来源仍取构建 V1，项目后来变 V2 不改变下载依据。
- 验证：I(ExecutionAccessIT)，所有权变化、归档、句柄冒用、运行结束、JAR 替换和版本竞争；无新增代理/下载 HTTP 路由。
- 主要文件：`E/ProjectExecutionAccess.java`、`ET/ExecutionAccessIT.java`、`SPEC-project-execution.md`。

## EX24 · 有界任务查询与安全诊断

- [x] 完成 EX24。依赖：EX07、EX14、EX17、EX23。原 4 文件估计遗漏准备/运行尚无事件表，按每片最多 5 个主要文件拆分。
  - [x] EX24a：诊断字段、枚举和错误码白名单，恶意/异常报告仅返回固定降级结果（ExecutionDiagnostics、ExecutionDiagnosticsTest）。缺方法 RED 后，08:37:27 诊断及报告/前端兼容 14 项通过。
  - [x] EX24b：追加 V029，为准备/运行记录状态事件，与状态提交同事务；三类事件的连续序号、1000 条/64 KiB 配额及不可变性验证（迁移、ExecutionEventSchemaIT）。RED 为 2 失败/6 缺表错误；08:41:31 GREEN 5 单元 + 38 集成。仅迁移已批准测试 schema，不伪造旧任务历史，不修改旧迁移。
  - [x] EX24c：三类任务和事件查询、绑定 owner/project/kind/task 的游标、每页 1–100、权限复核与诊断 DTO（ExecutionQuery、ExecutionQueryIT）。缺类 RED 后 17 集成通过；超范围时间游标先复现错误码问题，改为先核对真实锚点，08:48:54 的 5 单元 + 17 集成通过。
  - [x] EX24d：移除 owner 过滤的变异被拒绝，源码恢复后 09:00:08 全部 557 单元 + 180 相关集成通过；59 条运行全部终结，原绑定及五份旧审计不变，证据已归档。无 HTTP 接口、模型调用或部署。
- 描述：补齐构建/准备/运行的稳定分页查询，统一此前阶段事件的可消费 DTO。
- 验收：每次归属检查，游标混用/越界拒绝，最多 100 条；任务最多 1000 事件/64 KiB，超额不无界写入；不含源正文、命令、路径、JDBC、stderr 或 canary，只返回已解析的白名单诊断。
- 验证：I(ExecutionQueryIT) + U(ExecutionDiagnosticsTest)，分页中新增任务、配额事务失败及恶意输出；日志捕获验证无敏感原文。
- 主要文件：`E/ExecutionQuery.java`、`E/ExecutionDiagnostics.java`、`ET/ExecutionQueryIT.java`、`ET/ExecutionDiagnosticsTest.java`。

## C-EX08 · 恢复及交付契约

- [x] EX22–24 通过，跨项目访问/来源替换/未知运行均拒绝；记录诊断可支持下一步处理，但执行器不会自动消耗模型额度。557 单元 + 180 相关集成及本地打包通过，见 EX24 验证记录。

## EX25 · 真实待办后端验收样本

- [x] 完成 EX25。依赖：EX11、EX18。规模：M（5 文件）。
- 描述：在受控样本中增加真正 Java 业务 API、SQL 表及测试，作为可变源码输入而非平台通用数据服务。
- 验收：新增/修改/查询待办实际访问独立 PG；V1/V2 业务改动及额外迁移向量明确，拒绝用内存 Map 或平台 SDK 代替；样本无秘密，测试只连接专用测试库。
- 验证：REAL(ExecutionTodoBackendContractIT)，执行样本 Java 测试并断言 DB 结果；样本可作为后续两轮编辑的固定预期。
- 主要文件：`ER/todo/TodoController.java`、`ER/todo/V002__todos.sql`、`ER/todo/TodoControllerTest.java`、`ET/ExecutionTodoFixture.java`、`ET/ExecutionTodoBackendContractIT.java`。
- 证据：缺夹具 RED 后修正一处夹具返回类型；10:13:49 真实 V1/V2 构建通过，各含 15 项原模板前端测试及 10/11 项 Java 测试。10:20:27 的 58 单元 + 1 原生变异契约通过，错误工程因预期 409 却返回 200 被拒绝且无封存产物。真实 SQL 与 MVC 请求在专用测试库执行，表与数据事务回滚；未将此当成常驻 HTTP、业务库升级或 Vue 页面验收。证据 `.local/ex25-evidence-x4y3ca23`，详见验证记录。

## EX26 · 对应 Vue 业务页面样本

- [x] 完成 EX26。依赖：EX09、EX25。将请求/状态逻辑放入独立 composable，主要文件由 4 个调整为 5 个，分片验证。
  - [x] EX26a：`App.vue`、`useTodos.ts`、`App.spec.ts`；两版各 22 项前端测试、类型/lint/构建通过。真实 Chrome 临时上下文通过键盘、冲突、四档宽度与 axe 检查，已查看桌面/手机截图。浏览器的可控 API 响应仅用于 UI 验收，不当作真实 Java/PG 联调。
  - [x] EX26b：`ExecutionTodoFixture`、`ExecutionTodoBackendContractIT` 接入两版 Vue 源码。缺方法 RED 后，11:05:41 的 59 单元 + 1 原生契约通过，两版各 22 前端及 10/11 Java 测试；JAR 内静态资源与实际 dist、浏览器验收资源逐字节一致且两版不同。证据 `.local/ex26-evidence-dpxn7yly`，原 A/B 绑定和旧审计不变。
- 描述：为真实待办 API 提供 Vue 页面与交互测试，第二版增加可辨识的业务字段/行为。
- 验收：页面调用样本 Java API，处理提交/失败/查询，不走原 HTML/SDK 外壳；V1/V2 有明确文件差异且旧功能仍测；不增加未批准依赖或把样本放入所有新用户工程。
- 验证：REAL(ExecutionTodoBackendContractIT) 增补前端类型/lint/单测/build；检查 Vue 编译资源进入 JAR 的清单。
- 主要文件：`ER/todo/App.vue`、`ER/todo/useTodos.ts`、`ER/todo/App.spec.ts`、`ET/ExecutionTodoFixture.java`、`ET/ExecutionTodoBackendContractIT.java`。
- 界面约定：沿用现有深色、浅绿主按钮与字体；单列紧凑布局，顶部输入和主要操作优先，列表展示已加载数量；320px 起无横向滚动，控件至少 44px。明确加载、空列表、错误、编辑和保存状态；冲突保留输入，刷新前明确会放弃编辑；写请求结果不确定时要求先刷新，不自动重放写入。V2 显示优先级选择和文字标识，不依赖颜色区分。

## EX27 · 两项目两版本真实业务闭环

- [ ] 完成 EX27。依赖：EX15、EX20–26。原 3 文件估计未包含既有运行夹具的“空业务库 + 单文件源码”前提；真实迁移后该前提不再成立，按以下顺序拆片，每片最多 5 个主要文件。
  - [x] EX27a1：受控样本选择与逐字节迁移 ZIP 共用助手及测试，调整 RuntimeFixture、RunAdmissionIT、RunHealthIT（5 文件）。仅接受原单 SQL 或已评审的待办 V1/V2 完整树，未知源变化拒绝；不修改源码头或业务数据，不将 ZIP 元数据夹具宣称为真实构建。缺类 RED 后 9 单元 + 5 集成通过；补充独立 cwd 探针先失败，改为固定评审树摘要后最终 10 单元 + 5 集成通过，原 SQL 字节和运行身份验证保留。
  - [x] EX27a2：RuntimeSchedulerIT、RuntimeFaultProbe、AccessIT 改为复用同一受控源码读取（3 文件）。11:22:27 的 10 单元 + 8 集成通过，包括调度/切换/失权、两个独立 JVM 故障探针和访问句柄；78 条运行全部终结，原绑定、旧审计不变，证据 `.local/ex27a-evidence-0tgb_dbd`。实际业务迁移后仍须再次验证这些用例，不将当前空库上的兼容检查当作 EX27b/c 已完成。
  - [x] EX27b0：原生 Node 探测触发 10 秒超时后，按固定命令核验实际使用的工具（Toolchain、ToolProbe 及两项测试，4 文件）。保留 10 秒和任务总期限、输入复核、停止证明及整套构建核验。回归 RED 后 13 单元 + 12 集成通过，真实 Node 探测 6.711 秒；测量报告及临时诊断源码保留于 `.local/ex27b-probe-scope-evidence`。完整应用仍须 EX27b 验证，未因单次探测通过宣称全流程通过。
  - [x] EX27b：ApplicationContractIT、ApplicationFixture 与 TodoFixture 的上下文装配复用（3 文件），接入原 A/B 绑定、完整构建、HTTP 增改查、两进程隔离和重启持久化。16:51:47 的 10 单元 + 1 原生契约通过，两项目各 22 前端 + 10 Java 测试；三个实际运行全部由原 worker 正常停止，当前 81 条运行全部终结，数据与迁移历史在重启后保留，HTTP 静态资源与实际 JAR 逐字节一致。证据 `.local/ex27b-evidence-d2ic5wra`，旧审计/绑定不变。
  - [ ] EX27c：V2 增量构建与迁移批准、失败升级/旧代码拒绝、运行来源及前端资源核对；汇总前后端和原夹具的真实证据。遇到未知数据库历史即停止，不自动 clean/repair。
    - [x] EX27c0：V1 迁移后兼容回归发现外层故障探针因完整源码多次逐对象 Git 读取触发累计 128 子进程上限；SourceGitStore 按树层/文件批量读取并补真实 Git/解码回归（2 主要文件）。40 文件旧实现 44 次进程的 RED 后降到 5 次；17:38:13 的 28 单元 + 13 集成通过，包括两个原故障探针。不放宽 OwnedProcess 限制、来源校验或总期限。原失败及中断证据保留于 `.local/ex27b-compat-interrupted`，修复证据 `.local/ex27c0-evidence-q_3szuf5`；未完成的 RunAdmission 回归随 c1 补跑。
    - [ ] EX27c1：独立升级契约及 ApplicationFixture（2 文件），复用明确指定的真实 V1 构建；A/B 分别追加完整 V2 源码并构建，未批准拒绝、准确批准后升级、旧行默认值、新字段写入与重启、旧代码拒绝均走实际运行入口。源码/JAR/HTTP 资源和运行身份一并验证。
      - 前置修复已验证：首次真实 V2 构建在 npm 探测 10 秒时超时。用户确认将单次工具探测调为 30 秒，整次构建仍为 1800 秒；受控 11 秒探测先 RED，18:15:26 的 12 单元 + 12 集成通过，证据 `.local/ex27c1-probe-evidence-s32fj4zg`。真实业务升级仍须继续，不以工具测试勾选 c1。
    - [ ] EX27c2：真实失败迁移及其核对流程单独设计后验证。当前生产逻辑会将未 READY 的已执行进程保留为待核对，即使已有停止收据也不代表数据库结果已确定；不能为运行测试直接改写为终态。c1 的成功升级不替代此项。
- 描述：真实构建并运行 A/B 两个工程，验证业务行为、数据分离、增量版本与重启持久化。
- 验收：不同 Java 进程/端口/DB，A 的新增/修改/查询及重启保留数据，停 A 不影响 B；V2 的 Java API 与前端资源确实变化，未改行为保持；迁移先阻断再准确批准，失败升级/旧版恢复不冒称数据回滚。
- 验证：REAL(ExecutionApplicationContractIT)；保存 HTTP 断言、版本/树/JAR 摘要及进程停止证据；同源快照交接验收，不冒称网页 ZIP 已验收。
- 主要文件：a 按上述兼容小片；b 为 `ET/ExecutionApplicationContractIT.java`、`ET/ExecutionApplicationFixture.java`、`ET/ExecutionTodoFixture.java`；c 继续在该验收夹具内实现，并按实际改动另分片。
- 前提：A/B 业务与测试库准确目标另获批准；真实依赖下载在许可范围；零 DeepSeek/SMTP 调用。

## C-EX09 · 实际应用已运行

- [ ] EX25–27 全部真实通过，不仅接口返回 READY：业务 HTTP、独立 PG、重启数据、V2 前后端改动及失败迁移均有证据。当前仍为内部入口，不是工作台已接入。

## EX28 · 组合故障、并发及秘密反例

- [ ] 完成 EX28。依赖：EX12–24、EX27。规模：M（4 文件）。
- 描述：以组合场景验证单项测试之外的跨任务串用、容量和故障路径。
- 验收：显式并发 4 时不同项目进度独立，同项目准备/构建/换版互斥不失效；工具/根/源码/锁/JAR 漂移和磁盘不足拒绝；canary 不进入源版本、清单、诊断、事件、日志或后续修复输入。
- 验证：I(ExecutionFailureMatrixIT,ExecutionConcurrencyIT) + U(ExecutionSecretBoundaryTest)；对来源等式、取消结算和迁移批准各做变异失败后恢复；不清理旧未决审计。
- 主要文件：`ET/ExecutionFailureMatrixIT.java`、`ET/ExecutionConcurrencyIT.java`、`ET/ExecutionSecretBoundaryTest.java`、`ET/ExecutionContractSupport.java`。

## EX29 · 原功能与全部验证收口

- [ ] 完成 EX29。依赖：EX28。规模：M（3 文件）。
- 描述：串行完成平台、源码、Agent 离线 CLI、旧构建运行和前端/浏览器回归，区分各层证据。
- 验收：默认关闭不改变旧登录/项目/生成/导出/预览/发布；原 Agent 停写和恢复契约不退化；完整记录失败与复验，默认排除的真实构建和 CLI 契约必须另跑，不能按 skip 算完成。
- 验证：ALL + COMPAT + Python/前端/浏览器（计划 §6）+ 本模块全部 REAL；核对自有资源，无日常进程操作/真实模型或邮件。
- 主要文件：`ET/ExecutionCompatibilityIT.java`、`backend/pom.xml`、`docs/project-execution-verification.md`。
- 备注：POM 仅在验证发现套件漏选时修正；旧测试名与命令从实际代码读取，不编造结果。

## EX30 · 维护说明与真实完成状态

- [ ] 完成 EX30。依赖：EX29。规模：M（5 文件）。
- 描述：按实际实现更新开关、受控依赖准备、构建、DB 批准、运行、失败处理及交付接口说明。
- 验收：说明准确配置/命令/权限、非沙箱限制与代码/数据回退区别；能力地图标为内部已验收而非网页已完成；保留真实模型/交付/工作台/部署未完成项，无秘密和不存在的运行链接。
- 验证：DOC，逐项对照规格 §9 与验证记录；所有未通过真实验收项仍未勾选；不重写历史通过数。
- 主要文件：`docs/project-execution.md`、`docs/project-execution-verification.md`、`SPEC-project-execution.md`、`FULL-PROJECT-CAPABILITY-MAP.md`、`docs/decisions/016-full-project-source-and-coding-agent.md`。

## C-EX10 · 本模块完成评审

- [ ] EX28–30 及共同完成定义满足；提供实际业务、故障和原链路回归证据供评审。结果最多声明“完整工程内部构建与本机运行已验收，默认关闭”，不替代后续工作台、真实模型或部署验收。
