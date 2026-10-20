# Spec: project-execution · 完整工程受控构建与本机进程

状态：2026-10-21，用户已确认本规格及 §10 三项取舍，并确认[实施计划](tasks/project-execution/plan.md)与[任务清单](tasks/project-execution/todo.md)；EX01–09、C-EX03 及 EX10a–c 已验证，下一项 EX10d Java 阶段与 worker 接线。EX10 整体未完成，不是完整执行器已交付或已启用的声明。本地完整闭环的总体开发范围已获准，见 [Agent 规格 §10](SPEC-project-agent.md#10-2026-10-19-完整闭环实施授权)。

模块 ID 沿用[能力地图](FULL-PROJECT-CAPABILITY-MAP.md)的 `project-execution`，依赖 `project-source`，不反向依赖 Agent、交付或工作台。上游只读门面 PS21 已完成；当前网页仍走旧固定模板链路。

## 1. 目标与边界

把已保存的完整 Vue + Spring Boot 17 + PostgreSQL 源码版本，变成经检查的构建结果，并由平台在本机启动真实 Java 进程。新增业务接口、Java 服务和 SQL 不是导出时才套入的固定通用后端。

一次构建只针对一个不可变源码版本；生成器继续修改项目不会改变正在构建的输入。构建失败保留此前可用结果。启动是独立操作，不因“源码已保存”自动宣称应用已运行。

本模块不实现公网发布、通用终端、任意语言/构建工具、容器或新服务器。预览代理、同快照 ZIP 与网页入口分别由 `project-delivery`、`project-workbench` 消费本模块契约。旧生成、下载、构建、运行和发布保持原行为。

## 2. 技术栈与执行信任

沿用仓库现有 Java 17（本机 Oracle 17.0.5）、Spring Boot 3.5.16、Node 24.20.x、npm 11.19.x、Maven 3.9.11 和 PostgreSQL；本模块不升级依赖或改为 Go。具体工具补丁版本、可执行文件身份和构建策略摘要绑定到构建记录，工具变化不得悄悄接续旧任务。

普通进程不是操作系统沙箱。即使平台固定 argv、禁用依赖安装脚本，测试、Vite 配置、Maven 插件及业务代码仍能以本机账号权限执行。本模式默认关闭，只向显式允许的维护者开放；项目所有权仍独立校验，正式执行拒绝 root。不得宣传为对任意公网用户安全的不可信代码执行环境。

平台不赋予 Claude Shell/MCP 工具，也不执行模型返回的命令字符串。下载工程里的 `scripts/build.mjs`、`mvnw` 是用户可读源码，不自动成为平台的受信控制程序。平台使用自身发布的编排逻辑及维护者指定工具，不修改个人 npm/Maven/Claude 配置、登录或 HOME。

## 3. 输入、磁盘和构建身份

1. 接收账号、项目、源码版本 UUID、稳定幂等键；不接收绝对路径、Git SHA、端口、数据库 URL 或命令行。
2. 使用 `ProjectSourceReadGateway.read` 核对归属和完整快照。源文件继续遵守 512 文件、单文件 2 MiB、整树 16 MiB 上限。
3. 在执行专用根下创建本任务工作副本。正式 Git、Agent 可写目录和运行目录不作为构建 cwd；不暴露 `.git`、平台私有配置或其他项目内容。
4. 构建前后检查源码路径、字节、模式及摘要；只允许既定输出目录变化。工具擅自改动锁文件或业务源码时拒绝成功，不把变化偷偷写回正式版本。
5. 构建成功记录源码版本/树摘要、锁文件及依赖策略摘要、工具身份、构建器版本、检查结果、JAR SHA-256 和大小。只接受普通文件产物，拒绝链接、越界和不完整结果。

目录建议（由平台生成 UUID，不由客户端指定）：

```text
执行专用根/
  标记与协调者租约
  builds/构建UUID/project/       可丢弃构建副本
  artifacts/构建UUID/            平台封存的 JAR 与构建清单
  runs/运行UUID/                 进程证据与临时目录
```

源码下载将读取该构建绑定的源码版本，不从可写构建目录打包，不用最新模板重新组装。构建清单不含数据库密码、平台账号、模型密钥或业务数据；相同源码不承诺跨机器生成逐字节相同 JAR。

## 4. 依赖准备与构建流水线

### 4.1 依赖不能靠“给 Agent 开 Shell”解决

依赖声明与锁文件属于源码。首次使用已验收模板策略；新增依赖、插件、仓库或脚本例外先经过维护者审核，批准信息保存在源码外的执行策略中，并绑定准确的清单/锁文件摘要。模型改写“批准文件”不产生批准。

- npm 只接受已批准的 HTTPS registry 来源及完整锁文件，拒绝未批准的 git/file/任意 URL、本地链接或替换 registry；传递依赖也须检查来源与完整性。
- 安装显式使用 `npm ci --ignore-scripts --no-audit --no-fund`。不因安装报错而改用普通 `npm install`、放开生命周期脚本或强制审计修复。
- 固定 npm 11.19.0 的文档说明 `ci` 不修改清单/锁文件，清单不匹配会失败；平台据此把不匹配作为独立准备状态，而不是构建时隐式修正。[官方版本文档](https://raw.githubusercontent.com/npm/cli/v11.19.0/docs/lib/content/commands/npm-ci.md)
- `ignore-scripts` 不禁止显式 `npm run` 的目标脚本，因此不是禁用工程代码执行的保证；受控流水线不能仅靠这个开关信任可修改的 scripts。[npm 11.19.0 配置定义](https://raw.githubusercontent.com/npm/cli/v11.19.0/workspaces/config/lib/definitions/definitions.js)
- Maven 使用平台指定的发行版、私有 settings 和专用仓库缓存；检查 POM 的仓库、插件、父 POM、profiles 和 `.mvn` 注入入口。Maven 的用户 settings/仓库是独立配置层，不能默认继承个人凭据。[官方配置说明](https://maven.apache.org/guides/mini/guide-configuring-maven.html)

需要更新锁文件时，另建有界的依赖准备任务：取得源码编辑租约 → 在任务副本按批准策略执行固定的 lock-only 操作 → 确认子进程结束 → 验证只有允许的锁文件发生变化 → 通过源码模块发布新版本。请求绑定原基线/修订号，竞争修改则拒绝；不构建一个未保存的锁文件版本。缺少批准或无法安全解析时返回明确诊断，不无限重试。

### 4.2 构建顺序

固定快照 → 依赖策略校验 → 冻结安装 → 前端类型/规范/单测/构建 → 将前端产物装入后端资源 → 后端测试/打包 → 检查并封存产物。

平台选择固定工具入口及参数，检查必需测试实际执行，不使用跳过测试选项，不接受模型“构建成功”的文本。源码自带工具配置和测试仍属执行风险；检查通过不等于所有业务正确。

前端阶段是一次性的 `npm ci → vue-tsc → ESLint → Vitest → Vite`，复用受信命令目录而不调用工程的 npm scripts。开始前要求输出目录不存在，单测前新建私有空报告文件并固定其 inode；已有报告、缺失/空/替换/链接报告均拒绝，不通过删除旧结果重试。解析上限为 1 MiB、32 层、512 测试文件和 10000 用例/套件；必须存在实际 passed 用例，无 failed/pending/todo，报告汇总与实际断言数一致。只返回文件/套件/用例计数，报告原文只在任务私有副本内，不进事件、浏览器或模型。[Vitest 5.0.3 报告格式](https://github.com/vitest-dev/vitest/blob/v5.0.3/packages/vitest/src/node/reporters/json.ts)

Java 报告校验器 `ExecutionJavaReport` 按固定 Surefire 3.5.6 XML 格式逐条核对：每份报告必须包含实际 testcase，汇总与条数一致，不允许失败、错误、跳过或重跑失败。解析最多 512 份、单份 1 MiB、合计 16 MiB、合计 10000 用例；只返回文件/用例计数。使用 JDK 自带 StAX，不加载 schema，禁用并拒绝 DTD/实体，不回传系统属性、用例名称或日志。调用者仍须先证明文件来自本次干净构建且进程已停止；解析成功本身不证明来源新鲜、测试质量或构建成功。[Surefire 3.5.6 reporter](https://github.com/apache/maven-surefire/blob/surefire-3.5.6/maven-surefire-common/src/main/java/org/apache/maven/plugin/surefire/report/StatelessXmlReporter.java)

`ExecutionPackaging` 将 `frontend/dist` 复制为任务目录下的 `resources/static`，不写 `backend/src/main/resources` 或任何源码文件；固定 Maven 命令把该目录作为 `full-project.resources` 传给已批准 profile。装配要求 `resources` 和 `backend/target` 尚不存在，不自动删除旧结果。首版拒绝源码中的 `backend/src/main/resources/static`，避免两套资源覆盖顺序不明确；前端资源须由本次 dist 提供。[Maven 资源目录](https://maven.apache.org/plugins/maven-resources-plugin/examples/resource-directory.html)

沿用种子工程资源打包的 8 MiB/文件、32 MiB/总量限制，并限制 1024 条目（含根）、12 层、每个名称 128 字符；名称仅接受字母数字开头的 ASCII 字母数字/点/横线/下划线，拒绝隐藏项及密钥/属性文件扩展名。拒绝非普通文件、符号/硬链接、非当前用户所有及组/其他用户可写输出；不改变原 dist 权限。新资源目录/文件为 0700/0600，封装返回不可自行构造的内部句柄及路径/字节摘要。复验会核对输入与副本的文件身份、权限、内容和目录集合，并调用来源/授权复验；旧输出或失败副本保留，不重试覆盖。此处只是停止后的资源一致性检查，调用者仍须保证前一工具已停止；不是 OS 沙箱或整个构建成功。

`ExecutionArtifact` 只从持有的构建任务中读取 `project/backend/target/app.jar`，复制到新建的 `artifacts/构建UUID/app.jar`。输入必须为当前用户拥有、非空且不超过 256 MiB 的普通单链接文件，无符号链接及组/其他用户可写权限；输出目录/文件保持 0700/0600。复制前后复核原文件和目标目录身份，副本逐字节摘要相同；封存句柄复验 JAR、清单的 inode、权限、长度、修改时间、SHA-256 和产物目录成员。共享预算覆盖分配/扫描/复制，事务内禁止文件 I/O。此层只检查产物字节及证据绑定，不自行运行或验证 Java 业务，也不解释 JAR 内部布局；实际测试、停止证明与 JAR 内容验收仍由 worker 和 EX11 完成。

固定格式 `manifest.json` 最大 16 KiB，保存构建/项目/源码版本 ID、源码树摘要、四个工具的文件摘要、npm 清单/锁及 Maven POM/批准策略摘要、前后端测试数、静态资源摘要及 JAR 长度/摘要。字段只允许 UUID、枚举、数值和 SHA-256；不包含账号、路径、日志、凭据或自由文本。清单形状检查不是执行授权，调用者必须使用真实校验结果，并在封存前后复验来源、授权及取消状态。

封存使用 `CREATE_NEW`，重复调用或中途失败不得覆盖/删除已有目录；失败副本保留，旧成功构建不受影响。JAR 和清单分别 `force(true)`，这不是跨文件/数据库原子提交，也不保证断电后目录项持久；只有持有封存结果且后续数据库成功结算，才允许宣称构建成功。重启后的核对留给 EX13，不能凭目录或清单存在自动认领。[Java 17 CREATE_NEW](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/file/StandardOpenOption.html#CREATE_NEW)、[FileChannel.force](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/channels/FileChannel.html#force(boolean))

使用清空继承环境后的白名单，不继承模型、SMTP、平台数据库、Cookie、个人代理或注入参数；子进程私有缓存、配置和临时目录不写入源码。Java 的 `ProcessBuilder` 支持独立 cwd、参数列表和清空环境，但它本身不提供沙箱。[Java 17 接口](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/ProcessBuilder.html)

## 5. 本机进程与数据库

成功构建后，平台校验原构建身份和当前账号权限，使用该封存 JAR 启动 Java 17，绑定维护者登记的 loopback 端口。启动健康检查成功后才标为 `READY`；PID 存在或端口响应不等于业务验收成功。

运行配置保存在源码/构建副本之外的私有文件，只传给对应应用。使用明确的 Spring 配置位置，不把平台 env 整份复制给应用；Spring 的 `spring.config.location` 替换默认查找位置，但生成代码仍可能主动读文件，不能据此宣称隔离。[Spring Boot 3.5 配置说明](https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html)

数据库采用维护者预置的专用绑定：每项目独立数据库及最小权限角色，另有测试数据库。禁止平台日常库、系统库、超级用户和跨项目复用；运行服务不持有建库/建角色的管理员密码。创建这些环境需要单独明确目标，不能由网页提交连接字符串。

测试库检查器读取维护者提供的完整、不可变项目注册表（最多 4 项），不按网页请求临时登记连接。每份文件仅允许 `database/user/password/purpose/bindingId/jdbcUrl/schema` 七个键，拒绝重复键、环境占位符和任意 JDBC 参数；仅支持明确的 `127.0.0.1` TCP、`purpose=test` 与 `generated_app` schema。文件须为本机非 root 用户拥有的 0600 普通文件，父目录 0700，拒绝链接、硬链接、源码/构建等受保护根内位置，读取后固定身份及内容并在每次检查前后复验。配置最大 8 KiB；不读取个人或平台 env。

注册表拒绝重复数据库名、角色名或绑定 UUID，并额外拒绝调用者传入的实际平台/业务库及角色集合，不能只依赖默认库名黑名单。数据库连接在只读事务中执行固定的系统目录查询，核对实际 DB/会话角色、库与角色的 `atoms-project-execution:test:<bindingId>` 标记，以及非超级用户/无建库建角色等高权限/无角色成员关系、目标 schema 所有权及访问权限。拒绝数据库 PUBLIC 授权、其他非系统 schema 权限、模板库或备库；不执行 DDL、DML、Flyway 或清理。返回项目/绑定 UUID、实际 DB/角色 OID 和 relation 数量，不返回 URL、密码或配置摘要。

这些检查防止误配置及越界复用，不是 OS 隔离或对生成代码的安全认证；管理员可改变数据库，返回值仅是本次检查证据，执行前仍须重新核对。当前完整注册表内的唯一性检查不替代 EX17 的持久业务库绑定，也没有提供向任意进程发放数据库凭据的公开接口。

- 首次启动允许在已确认归该项目所有的空业务库初始化迁移；测试不得连接该业务库。
- 同一项目重启使用同一绑定，数据保留；停进程不删除数据库，绑定不自动分配给其他项目。
- 已有非空库的迁移集合发生变化时，默认返回 `RUN_MIGRATION_REVIEW_REQUIRED`，不启动新进程或修改数据库。维护者核对迁移、备份及兼容性后，批准记录须绑定项目、数据库身份和准确迁移摘要；普通“启动”按钮不能冒充批准。
- 构建失败不切换旧预览。数据库升级并非原子操作：批准执行后若失败，保留审计并报告数据状态待核对，不能声称自动回退成功。
- 恢复旧源码/JAR不回滚业务库。旧版与已应用迁移不兼容则拒绝启动，不自动执行 Flyway repair、clean、删表或降级 SQL。

首期每项目最多一个运行进程。切换版本前保留旧构建身份，明确停止旧进程再启动新进程；允许短暂停机，不承诺零停机。停止失败时不再启动第二个同项目写入者。新进程失败不覆盖上一成功构建记录；若涉及数据迁移，恢复旧运行也需要重新核对兼容性。

## 6. 状态、幂等与恢复

V023 已增加持久构建及阶段事件记录；运行、数据库绑定仍待后续任务。迁移只追加，不改现有源码/Agent 或旧构建表的含义。字段保存引用、状态与摘要，不保存完整源码、原始输出或密码。

```text
构建：QUEUED → PREPARING → INSTALLING → CHECKING → PACKAGING → SUCCEEDED
运行：STARTING → READY → STOPPING → STOPPED
异常：FAILED / CANCELLED / TIMED_OUT / NEEDS_RECONCILIATION
```

同幂等键同输入返回原任务，不重复启动；不同输入冲突。任务执行意图在启动外部进程前持久化。取消先记请求，拥有该进程的执行器确认退出和 I/O 收尾后结算；确认不了则待核对，不凭 PID 或时间过期假定已停止。

唯一协调者管理队列与本任务进程。不收养未知进程、不杀用户的 Java/Node/Docker，也不自动重放已启动但结果未知的任务。服务重启后查询既有任务和证据，未经确认的运行不继续展示为 `READY`。

执行模块不调用模型。失败诊断可供上层编排创建新的 Agent 修复任务，必须绑定新的当前基线和模型预算；本模块不做无限自动修复，也不形成 `project-agent ↔ project-execution` 循环依赖。

## 7. 已确认配置与资源预算

以下新模块默认值已确认，不修改既有 Agent 默认 4 并发或模型 tokens 配置：

| 项目 | 默认值/限制 |
|---|---|
| 启用 | `false`；维护者白名单为空；开启也不自动执行已有项目 |
| 构建并发/排队 | 1 / 8；构建并发可显式调至 1–4，避免默认同时启动四套 Maven/Vite；Agent 仍为 4 |
| 同项目约束 | 一个活动构建、一个活动运行；依赖准备还需源码编辑租约 |
| 构建/排队/启动期限 | 1800 / 300 / 120 秒；构建各阶段共享总期限 |
| 进程收尾 | 单次固定 10 秒预算；不因重复取消刷新期限 |
| 运行容量 | 最多 4 个已配置项目绑定；无空位明确拒绝，不共用别人的库/端口 |
| 执行根容量 | 逻辑上限 8 GiB、至少留 1 GiB；不是 OS 硬配额；不自动删证据 |
| 单个 JAR | 最多 256 MiB，非空普通文件且摘要匹配 |
| 诊断/事件 | 每任务结构化正文最多 64 KiB / 1000 事件，分页最多 100 |

工具绝对路径、私有执行根、受信策略和数据库配置由维护者提供，不从用户 prompt 或 HTTP 参数取值。未知根标记、链接、配置漂移或工具不兼容均拒绝。

工具命令目录固定 npm/检查入口与 Maven Classworlds 启动方式，不使用源码包装器或继承个人 JVM 参数。Java 主进程显式设置私有 `user.home` / `java.io.tmpdir`；任务环境的 `JDK_JAVA_OPTIONS` 由平台只生成这两个属性，使正常继承环境的子 JVM 也使用任务目录，拒绝无法安全表示的双引号/反斜杠路径。该环境不修改宿主配置，不能阻止同 UID 代码主动改写环境或读取其他磁盘位置；仍不是 OS 沙箱。工具版本探测只返回版本与文件摘要，每路输出最多 16 KiB、单次最多 10 秒且服从任务总剩余期限。

日志仅包含任务/项目/执行标识、阶段、错误码、耗时和有界数值。仅持久化可解析的白名单诊断字段，如相对文件名、行号、错误分类；原始 stdout/stderr、源码行、SQL 值及凭据不回传浏览器或模型。无法安全提取时提供阶段/退出码，不能以调试方便为由记录整份输出。

## 8. 模块接口和代码位置

上游现有接口：`ProjectSourceReadGateway` 读取不可变输入；需要发布锁文件新版本时使用 `ProjectSourceEditGateway` 的拥有权与停写证据，不公开任意写路径。

当前 `ProjectExecutionService` 仅提供内部 Java 准入/查询，不是 HTTP 接口，也没有自动调度：

```java
BuildView submit(UUID owner, UUID project, UUID sourceVersion, String key);
BuildView get(UUID owner, UUID project, UUID build);
```

`submit` 必须在数据库事务外调用。先锁项目检查所有权/归档和幂等键，再在事务外读取准确版本的完整快照，最后重新授权并锁项目、分配等待槽位、原子保存意图/首条事件。不在持有项目锁时等待 Git。相同项目/键/版本返回原任务（包括终态），不同版本返回 `EXECUTION_IDEMPOTENCY_CONFLICT`，项目已有其他活动构建返回 `EXECUTION_PROJECT_BUSY`，队列满返回 `EXECUTION_QUEUE_FULL`。键最多 128 个 Java 字符，不允许空白键、控制字符或不完整的 Unicode 代理项；只保存 SHA-256，不保存原键。

`get` 同样校验当前维护者、项目所有权及归档状态；视图只含任务/项目/来源 UUID、树摘要、状态、取消标记、稳定错误码、创建/完成时间和最后事件序号，不包含持久请求摘要、内部容量槽、路径或配置。配置及授权检查失败不能靠重放绕过。准入快照仅固定来源，不代表完成依赖批准、工具/应用库验证或可执行调度；后续 worker 仍需重新核对这些边界。

取消构建、启动/停止/查询运行、列表/事件及交付身份仍按 EX12–24 实施。状态迁移事件包含状态、取消标记和平台错误码，任务 UUID 是关联标识；不提供任意原始输出写入 API。可按任务查询是否入队、当前阶段及拒绝/失败码，不用源码或凭据作为诊断。

`ExecutionFrontend` 是单次内部前端阶段，不是自动 worker。调用者须提供当前授权、不可变快照及批准策略复验；每一步还复验工具与私有 home，并共享同一取消信号和 deadline。`ExecutionBuildJournal` 仅服务已被协调者领取为 `PREPARING` 的持久任务，不负责领取或启动。每次 begin 使用项目行 → 任务行的短事务重新授权，核对前一步完成和唯一执行 UUID，原子记录阶段/执行意图；拒绝嵌套在调用者事务中，避免尚未提交就启动进程。

finish 核对执行 UUID、操作及真实停止证明，才清除 pending；结果和计数事件同事务落账。错误证明拒绝，缺证明保留 `NEEDS_RECONCILIATION` 及容量；权限撤销不阻止拥有者提交已有进程的停止结果。结果落库失败不重复完成或启动下一步；数据库已记录的取消优先于迟到的成功。前端成功仍处于 `CHECKING`，不释放 worker 或标记整体 `SUCCEEDED`，后续 Java 打包与调度仍属 EX10–12。事件新增固定操作/执行 UUID/结果类别及数值字段，沿用 V023 的 1000 条/64 KiB 总预算，不保存命令、路径或原始输出。

列表/事件使用有界游标分页。给交付模块的是经核对的构建/运行身份与内部受控句柄，不是浏览器可指定的磁盘路径或代理目标；每次访问仍重新授权。错误采用既有 `ApiException` 稳定码，不泄露命令、路径或底层异常。

实现位于 `backend/src/main/java/dev/atoms/demo/projectexecution/`，对应测试位于 `backend/src/test/java/dev/atoms/demo/projectexecution/`，受控样本位于 `backend/src/test/resources/projectexecution/`；追加数据库迁移，维护说明/验证记录放入 `docs/`。现有 `execution/OwnedProcess` 有 Agent 专用错误码，由 `ExecutionProcess` 明确适配，不能直接把其结果当新构建证据。旧 `workspace`/`instances` 固定模板代码不改成混杂的双模式执行器。

`ExecutionTestDatabase.load` 只装载上述内部注册表，不连接数据库；`verify(projectId, budget)` 才创建并关闭一个有界只读连接。未登记项目、无效/漂移配置、重复绑定、实际目标不符、连接不可用和预算耗尽分别返回 `EXECUTION_DB_BINDING_NOT_FOUND`、`EXECUTION_DB_CONFIGURATION_INVALID`、`EXECUTION_DB_BINDING_CONFLICT`、`EXECUTION_DB_TARGET_REJECTED`、`EXECUTION_DB_UNAVAILABLE`、`EXECUTION_TIMEOUT`。异常不携带底层 cause/suppressed 连接详情。连接/读超时和语句期限从剩余整秒预算取值且最多 3/5/5 秒，不把 0 传给 JDBC 表示无限等待；不足 1 秒直接拒绝。该内部类不是自动启动的 Spring 组件，后续协调者须先按现有开关和权限准入。

沿用 Java 四空格、构造器注入、不可变 DTO、参数化 SQL、短事务。不得在数据库事务中等待 Git、安装或构建，不为本模块引入通用插件框架或新平台依赖。

## 9. 验收、命令与回退

先按 JUnit 红绿循环实现准入、身份、预算和失败状态；真实 Git/PG 验证快照与持久状态；受控子进程验证取消、期限和未知结果；最后运行人工审阅过的 Vue/Java/SQL 测试工程。假进程通过不能代替真正构建与 HTTP 业务验收。

必须覆盖：

1. 关闭、非维护者、非所有者、归档项目均不能执行；不影响旧网页及旧导出。
2. 构建 V1 时生成 V2，构建仍绑定 V1；工作副本或产物被修改则不能成功。
3. 锁文件不匹配不会隐式修正；受控依赖准备发布新版本；未批准来源/脚本/插件拒绝。
4. 固定工程完成真实前端测试和 Java 构建，生成实际业务接口；测试报告缺失/跳过或任一阶段失败不标成功。
5. 两个项目启动不同进程、端口和数据库；一个停止或失败不改变另一个的运行记录。
6. 示例待办应用能通过真实 HTTP 新增/查询数据、重启后保留；第二版改动能体现在 Java API 与前端，而非只替换 HTML。
7. 非空库新迁移需准确批准；未批准、失败升级和源码恢复均不冒称数据已回滚。
8. 幂等、重复点击、取消、超时、服务崩溃和租约丢失不会重复启动或伪造停止证据。
9. 原始日志秘密 canary 不出现在事件、诊断或构建清单；缓存与运行配置不进入源码。
10. 交付消费者可核对源码版本、树摘要和 JAR 摘要；此处只验收交接契约，浏览器预览和 ZIP 另在交付模块验收。

平台验证继续使用已有命令；新测试类及分步命令在后续实施计划中列出，不把尚未存在的测试写成已通过：

```sh
cd /Users/wubingheng/atoms-demo/backend
env PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH \
  SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/atoms_demo_test?currentSchema=atoms_verify_20261019_pa32b&connectTimeout=3&socketTimeout=5' \
  ./mvnw -B verify
```

这是平台专用测试库命令，不是新生成应用的数据库配置。实际应用验收另配专用测试/业务库，确认具体目标后才创建，不复用日常库或旧未决审计。

回退：先停止新准入并安全结束自有执行，关闭模块开关，保留源码、构建、运行及数据库证据；不删表/仓库、不修改旧迁移、不释放未决锁。旧固定模板链路继续使用原开关与数据。

## 10. 已确认的取舍

用户于 2026-10-20 确认以下三项；实际数据库目标、依赖例外或日常启用不因此自动获准。

- 继续按维护者可控的本机普通进程实现，不增加容器/服务器；明确接受其不是沙箱的限制。
- Agent 并发保持 4，新构建器默认 1、最多可调至 4；本轮仍不授权真实模型费用、推送或远程部署。
- 首期使用预置的独立数据库绑定，已有业务库迁移先确认；不做自动建库管理员服务或自动数据回滚。

Always：先测试、再实现；冻结输入、逐项授权、完整证据、错误真实可见。Ask first：改变已确认预算或迁移边界、实际创建数据库/角色、扩大依赖策略或启用不可信项目执行。Never：继承平台秘密、执行模型命令字符串、修改个人配置、删除失败审计、以假构建替代真实验收。
