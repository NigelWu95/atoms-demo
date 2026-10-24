# project-execution · 开发任务

状态：2026-10-23，规格及计划/任务已获用户确认，EX01–11 已完成。原始种子通过真实 npm/Maven 构建，JAR 与两个失败后续版本的保留行为通过独立原生契约；随后完整回归 **417 单元 + 377 集成通过，零失败/错误/跳过**，22:06:02 独立报告汇总校验成功。下一项为 EX12 调度/取消；C-EX04 及运行/网页接入尚未完成，默认保持关闭。本机四套验收库已获准并创建。唯一依据为[规格](../../SPEC-project-execution.md)与[本模块计划](plan.md)，不复用旧工程导出的 PE 编号。任务勾选需附真实证据，不把受控输出或下列拟新增类、命令、样本当作真实工程验收。

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

- [ ] 完成 EX12。依赖：EX07、EX10、EX11。按取消入口、协调权、调度接线分别验证；每片最多 5 个主要文件。
  - [x] EX12a：持久取消入口（`ProjectExecutionService`、`ExecutionBuildRepository`、`ExecutionCancellationIT`，3 个主要文件）。维护者/当前项目归属/原任务身份校验，短事务先锁项目再锁构建；排队任务原子取消并归还队列，已领取或待核对任务只保存取消意图，不释放 worker 或伪造停止。终态/重复请求幂等，归档项目允许停止自身旧任务，配置撤销在锁后复核，数据库异常不泄漏详情。9 项 PG/真实停止收据测试通过，权限变异被捕获后恢复；22:46:54 全部 417 单元 + 42 相关集成及打包通过，零失败/错误/跳过，见[证据](../../docs/project-execution-verification.md#2026-10-23--ex12a-持久取消入口)。完整协调器/运行中断仍由 b/c 接线验收。
  - [x] EX12b：`ExecutionCoordinatorLease` 及单元/真实数据库测试（3 个主要文件），使用独立 `718395204` 会话锁。核对 backend 和锁持有，失效不可恢复；异常回收按 abort → Hikari evict → close，前两步失败不归还连接。缺类 RED 后真实测试发现并修复 Hikari 已关闭连接的回收异常；11 单元 + 4 PG 集成通过，遗漏 evict 的变异被 3 项捕获。恢复后 2026-10-24 11:06:06 全部 **428 单元 + 28 相关集成通过**，零失败/错误/跳过，见[证据](../../docs/project-execution-verification.md#2026-10-24--ex12b-独立构建协调租约)。不自动启动调度或工具。
  - [ ] EX12c：协调器与 worker、取消信号、排队到期和关闭接线；容量竞争、真实子进程取消/重复中断及旧协调器共存验收。预计仍需补运行环境/生命周期边界时先拆兼容片，不将超出五文件的实现塞进单一任务；EX13 的崩溃恢复未完成前不声称重启核对已交付。
    - [x] EX12c1：`ExecutionBuildQueue`、`ExecutionBuildRepository`、`ExecutionBuildQueueIT` 及测试 profile 默认 schema 修正（4 个主要文件）。协调权下有界读取等待任务，在项目/构建行锁内处理原排队期限、取消及权限变化；已领取/待核对/终态不变，失锁整笔回滚。8 项 PG 用例与失锁变异已验证；11:38:38 全部 **428 单元 + 39 相关集成通过**，零失败/错误/跳过。首次聚焦命令误用旧 `atoms`，追加既有迁移及本次夹具；现场保留，已修正测试默认配置，具体影响及只读核对见[记录](../../docs/project-execution-verification.md#2026-10-24--ex12c1-等待任务处理)。未自动启动线程或工具。
    - [ ] EX12c2：在 c1 上接实际调度器、worker 及生命周期；开始前核对所需文件并继续遵守每片五文件限制。覆盖启动前未决阻断、容量/多项目竞争、实际子进程取消和关闭；尚未完成恢复机制时不重放旧执行。
- 描述：唯一协调者驱动默认一个构建，支持显式 1–4 并发和持久取消。
- 验收：默认 1/排队 8、每项目一个活动构建，排队 300 秒及共享 1800 秒；启动意图先落库，取消先记录再由拥有者停止；未决不释放容量，Agent 并发配置不变。
- 验证：I(ExecutionSchedulerIT,ExecutionCancellationIT)，真实受控子进程、多项目竞争、排队/执行取消及重复中断；核对测试进程退出。
- 主要文件：a 的三个文件先实现；b/c 在上述边界下分别列出租约/协调器及对应测试。原估计的 `ExecutionCoordinator`、仓库、服务和两个集成测试不足以覆盖独立租约，按片验证后提交。
- 接线前锁问题已由 EX10d3b1 处理：构建准入原与 Agent 协调会话共用 `718395202`，现使用独立的 `718395203`，真实双连接共存及退回旧编号的变异已验证，隔离 JDK 完整回归通过。EX12 仍需验证完整协调器共存；锁冲突不是观察失败的已证实根因，不改原未决状态。

## C-EX04 · 真实构建成立

- [ ] EX10–12 完成：有真实封存 JAR 证据，不仅是假进程；取消和容量结算准确，旧网页不变。若真实构建未通过，此检查点不得勾选。

## EX13 · 构建崩溃恢复

- [ ] 完成 EX13。依赖：EX12。规模：M（4 文件）。
- 描述：在外部执行意图、产物封存与 DB 结算之间注入崩溃，重启后按证据核对。
- 验收：已启动但结果未知不重跑；无执行意图可安全结算、封存与 DB 不一致保留未决；恢复完成前不接新任务，租约丢失后不继续启动。
- 验证：I(ExecutionRecoveryIT)，真实测试 JVM 终止/重启和配置漂移，保留故障记录；不 kill 按旧 PID 找到的未知进程。
- 主要文件：`E/ExecutionRecovery.java`、`E/ExecutionCoordinator.java`、`ET/ExecutionRecoveryIT.java`、`ET/ExecutionCrashProbe.java`。

## EX14 · 依赖准备任务与编辑互斥

- [ ] 完成 EX14。依赖：EX05、EX07、EX12、EX13。规模：M（4 文件）。
- 描述：为 lock-only 准备增加独立持久意图，与源码编辑租约和构建容量一致准入。
- 验收：绑定基线/修订号、清单及准备批准，原子领取租约；与同项目 Agent 编辑/源码恢复/构建冲突，不超共享队列；同键重放不重复解析或遗留租约。
- 验证：I(ExecutionPreparationAdmissionIT)，真实 PG/source 双连接竞争及回滚；不调用模型。
- 主要文件：`M/V025__project_dependency_preparations.sql`、`E/DependencyPreparationRepository.java`、`E/DependencyPreparationService.java`、`ET/ExecutionPreparationAdmissionIT.java`。

## EX15 · lock-only 发布新源码版

- [ ] 完成 EX15。依赖：EX03、EX05、EX06、EX14。规模：M（4 文件）。
- 描述：在源模块持有的任务副本执行批准的固定锁文件准备，再经停写证明发布新版本。
- 验收：只允许目标锁文件变化，无 node_modules/秘密进入候选；确认进程及 I/O 停止后才能接受，CAS 冲突不覆盖他人修改；新锁不自动取得构建批准，无变更不造版本。
- 验证：U(ExecutionLockPreparationTest) + I(ExecutionPreparationPublicationIT)；真实 npm lock-only 另由 REAL(ExecutionSeedBuildContractIT) 增补场景，不仅用手写锁夹具。
- 主要文件：`E/DependencyPreparationWorker.java`、`ET/ExecutionLockPreparationTest.java`、`ET/ExecutionPreparationPublicationIT.java`、`ET/ExecutionSeedBuildContractIT.java`。

## C-EX05 · 构建恢复与依赖准备

- [ ] EX13–15 的故障与发布测试通过；锁文件变更有独立来源版本及批准边界，旧 Agent 编辑/停写证明不退化。

## EX16 · 准备任务取消与恢复

- [ ] 完成 EX16。依赖：EX13–15。规模：M（4 文件）。
- 描述：让准备任务使用同一协调者容量、期限及停写结算，处理源码已发布但任务响应丢失。
- 验收：取消不冻结仍可写候选；未知执行保留源码租约，已提交版本优先反映真实结果；服务重启从 source operation 关联核对，不再次安装/发布。
- 验证：I(ExecutionPreparationRecoveryIT)，队列取消、子进程中断、发布后崩溃以及两轮基线竞争；原源码恢复测试回归。
- 主要文件：`E/ExecutionCoordinator.java`、`E/ExecutionRecovery.java`、`E/DependencyPreparationService.java`、`ET/ExecutionPreparationRecoveryIT.java`。

## EX17 · 持久运行身份与独立业务库绑定

- [ ] 完成 EX17。依赖：EX07、EX08、EX13。规模：M（5 文件）。
- 描述：追加运行、项目 DB 绑定和批准记录；绑定由维护者提供，不接受网页连接串。
- 验收：每项目单一持久绑定、最多四个配置项目，端口/DB/角色不能跨项目复用；只读确认实际业务库及最小权限、拒绝测试/平台/系统库；停止不删除 DB、不释放给别项目。
- 验证：I(ExecutionBindingIT)，双连接抢占、配置漂移、真实业务/测试库区分；准确目标创建须另获批准。
- 主要文件：`M/V026__project_execution_runs.sql`、`E/ExecutionRun.java`、`E/ExecutionRunRepository.java`、`E/ExecutionDatabaseBinding.java`、`ET/ExecutionBindingIT.java`。

## EX18 · 迁移批准与兼容性判定

- [ ] 完成 EX18。依赖：EX10、EX17。规模：M（4 文件）。
- 描述：在新进程获得业务 DB 配置前检查已有历史、目标迁移集合及准确批准。
- 验收：已确认空库才允许首次初始化；非空库变化返回 RUN_MIGRATION_REVIEW_REQUIRED，批准绑定项目/库/原历史/目标摘要；失败升级、历史不兼容或旧版恢复不可自动 repair/clean/降级。
- 验证：U(ExecutionMigrationPolicyTest) + I(ExecutionMigrationReviewIT)，批准错项目/错历史/错 JAR、校验和改变和部分升级；检查拒绝时 DB 未写入。
- 主要文件：`E/ExecutionMigrationPolicy.java`、`E/ExecutionMigrationReview.java`、`ET/ExecutionMigrationPolicyTest.java`、`ET/ExecutionMigrationReviewIT.java`。

## C-EX06 · 数据库边界明确

- [ ] EX16–18 完成，准备任务恢复与真实迁移护栏通过；批准不等于可自动回滚，所有新 DB 精确目标已另行获准并记录。

## EX19 · 持有常驻应用进程

- [ ] 完成 EX19。依赖：EX03、EX10、EX17、EX18。规模：M（5 文件）。
- 描述：后台拥有 Java 进程直到退出；给受控种子健康响应加入运行身份契约。
- 验收：明确 classpath/私有配置/loopback，源码脚本不负责平台启动；启动窗口与运行寿命分开，READY 后仍排空流及观察退出；健康响应回显本次运行标识，缺此契约不退化为任意 HTTP 200。
- 验证：U(ExecutionRunProcessTest)，受控常驻子进程与重复停止、输出洪泛；种子 HealthControllerTest 及原生模板测试回归。
- 主要文件：`E/ExecutionRunProcess.java`、`ET/ExecutionRunProcessTest.java`、`T/backend/src/main/java/dev/atoms/generated/HealthController.java`、`T/backend/src/test/java/dev/atoms/generated/HealthControllerTest.java`、`SPEC-project-execution.md`。
- 备注：运行标识只防止平台误认，非秘密或任意代码安全认证；既有源码快照不被新模板改动追溯重写。

## EX20 · 幂等启动与真实健康状态

- [ ] 完成 EX20。依赖：EX17–19。规模：M（5 文件）。
- 描述：从封存 buildId 创建一次运行意图，健康检查成功才发布 READY。
- 验收：每次启动重新授权/校验产物、绑定、迁移批准，意图先于 launch 持久化；同键只启动一次，端口冲突及来源不匹配明确失败；持有本进程事实及预期健康身份，120 秒超时不伪造就绪。
- 验证：I(ExecutionRunAdmissionIT,ExecutionRunHealthIT)，跨所有者、归档、改 JAR、外来端口/重定向/假健康、启动中崩溃及退出竞争。
- 主要文件：`E/ProjectExecutionService.java`、`E/ExecutionRunWorker.java`、`E/ExecutionHealth.java`、`ET/ExecutionRunAdmissionIT.java`、`ET/ExecutionRunHealthIT.java`。

## EX21 · 停止与换版不产生双写者

- [ ] 完成 EX21。依赖：EX20。规模：M（4 文件）。
- 描述：停止是持久操作；换版先结束旧进程再启动指定新构建。
- 验收：同项目只能一个写入进程，停止失败不启动下一版；重复停止幂等，真实退出/I/O 收尾后才释放进程容量；新版本失败保留旧构建身份及 DB，恢复旧版仍经过迁移兼容检查。
- 验证：I(ExecutionRunSwitchIT)，并发 start/stop、失去权限后的自有清理、旧进程拒停和新版本启动失败；检查没有第二写入者。
- 主要文件：`E/ExecutionRunWorker.java`、`E/ExecutionRunRepository.java`、`E/ProjectExecutionService.java`、`ET/ExecutionRunSwitchIT.java`。

## C-EX07 · 本机运行生命周期

- [ ] EX19–21 的真实受控进程测试及构建通过；启动、健康、停止、换版互不混淆。完整业务持久化仍由 EX27 验收。

## EX22 · 运行恢复及数据未知状态

- [ ] 完成 EX22。依赖：EX13、EX18、EX21。规模：M（4 文件）。
- 描述：重启协调者时核对运行与迁移证据，不收养未知进程或自动恢复服务。
- 验收：旧 READY 不能未经确认继续展示；启动/迁移中崩溃保留 NEEDS_RECONCILIATION 和绑定；不按 PID/端口猜测死亡，不重放未知 start，不抹掉数据状态待核对记录。
- 验证：I(ExecutionRunRecoveryIT)，真实测试 JVM 崩溃、租约丢失及 DB 不可用；旧进程处置只凭仍持有的拥有证据。
- 主要文件：`E/ExecutionRecovery.java`、`E/ExecutionCoordinator.java`、`ET/ExecutionRunRecoveryIT.java`、`ET/ExecutionCrashProbe.java`。

## EX23 · 给交付模块的构建/运行身份

- [ ] 完成 EX23。依赖：EX04、EX10、EX20–22。规模：M（3 文件）。
- 描述：提供窄的内部访问门面，将来源版本、封存产物与真实运行关联。
- 验收：授权不可混项目，篡改产物或失效运行即拒绝；句柄不能由路径/端口/UUID DTO 自行构造，不泄漏配置；来源仍取构建 V1，项目后来变 V2 不改变下载依据。
- 验证：I(ExecutionAccessIT)，所有权变化、归档、句柄冒用、运行结束、JAR 替换和版本竞争；无新增代理/下载 HTTP 路由。
- 主要文件：`E/ProjectExecutionAccess.java`、`ET/ExecutionAccessIT.java`、`SPEC-project-execution.md`。

## EX24 · 有界任务查询与安全诊断

- [ ] 完成 EX24。依赖：EX07、EX14、EX17、EX23。规模：M（4 文件）。
- 描述：补齐构建/准备/运行的稳定分页查询，统一此前阶段事件的可消费 DTO。
- 验收：每次归属检查，游标混用/越界拒绝，最多 100 条；任务最多 1000 事件/64 KiB，超额不无界写入；不含源正文、命令、路径、JDBC、stderr 或 canary，只返回已解析的白名单诊断。
- 验证：I(ExecutionQueryIT) + U(ExecutionDiagnosticsTest)，分页中新增任务、配额事务失败及恶意输出；日志捕获验证无敏感原文。
- 主要文件：`E/ExecutionQuery.java`、`E/ExecutionDiagnostics.java`、`ET/ExecutionQueryIT.java`、`ET/ExecutionDiagnosticsTest.java`。

## C-EX08 · 恢复及交付契约

- [ ] EX22–24 通过，跨项目访问/来源替换/未知运行均拒绝；记录诊断可支持下一步处理，但执行器不会自动消耗模型额度。

## EX25 · 真实待办后端验收样本

- [ ] 完成 EX25。依赖：EX11、EX18。规模：M（5 文件）。
- 描述：在受控样本中增加真正 Java 业务 API、SQL 表及测试，作为可变源码输入而非平台通用数据服务。
- 验收：新增/修改/查询待办实际访问独立 PG；V1/V2 业务改动及额外迁移向量明确，拒绝用内存 Map 或平台 SDK 代替；样本无秘密，测试只连接专用测试库。
- 验证：REAL(ExecutionTodoBackendContractIT)，执行样本 Java 测试并断言 DB 结果；样本可作为后续两轮编辑的固定预期。
- 主要文件：`ER/todo/TodoController.java`、`ER/todo/V002__todos.sql`、`ER/todo/TodoControllerTest.java`、`ET/ExecutionTodoFixture.java`、`ET/ExecutionTodoBackendContractIT.java`。

## EX26 · 对应 Vue 业务页面样本

- [ ] 完成 EX26。依赖：EX09、EX25。规模：M（4 文件）。
- 描述：为真实待办 API 提供 Vue 页面与交互测试，第二版增加可辨识的业务字段/行为。
- 验收：页面调用样本 Java API，处理提交/失败/查询，不走原 HTML/SDK 外壳；V1/V2 有明确文件差异且旧功能仍测；不增加未批准依赖或把样本放入所有新用户工程。
- 验证：REAL(ExecutionTodoBackendContractIT) 增补前端类型/lint/单测/build；检查 Vue 编译资源进入 JAR 的清单。
- 主要文件：`ER/todo/App.vue`、`ER/todo/App.spec.ts`、`ET/ExecutionTodoFixture.java`、`ET/ExecutionTodoBackendContractIT.java`。

## EX27 · 两项目两版本真实业务闭环

- [ ] 完成 EX27。依赖：EX15、EX20–26。规模：M（3 文件）。
- 描述：真实构建并运行 A/B 两个工程，验证业务行为、数据分离、增量版本与重启持久化。
- 验收：不同 Java 进程/端口/DB，A 的新增/修改/查询及重启保留数据，停 A 不影响 B；V2 的 Java API 与前端资源确实变化，未改行为保持；迁移先阻断再准确批准，失败升级/旧版恢复不冒称数据回滚。
- 验证：REAL(ExecutionApplicationContractIT)；保存 HTTP 断言、版本/树/JAR 摘要及进程停止证据；同源快照交接验收，不冒称网页 ZIP 已验收。
- 主要文件：`ET/ExecutionApplicationContractIT.java`、`ET/ExecutionContractSupport.java`、`ET/ExecutionTodoFixture.java`。
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
