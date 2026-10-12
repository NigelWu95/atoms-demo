# 本机构建验证记录

2026-10-13，用户批准 WB01–WB21 一次性实施。当前记录按实际切片追加，不代表整个模块完成；无 GitHub 推送或服务器部署。

## WB01 · 共享快照入口

- 新增 `EngineeringSnapshotService`，原工程下载改用同一授权/组装入口，保留授权错误、打包两路并发及确定性 ZIP。
- 模板摘要采用排序路径和长度前缀的源字节 SHA-256；内容或路径变化会改变摘要。Worker 所用预期模板摘要不符时拒绝，不替换快照。
- RED：`EngineeringSnapshotServiceTest` 因新接口缺失编译失败；实现后定向 `EngineeringSnapshotServiceTest,EngineeringExportTest,EngineeringBundleTest,TemplatePolicyTest` 共 51 项通过。
- 首次 GREEN 执行中，沙箱阻止 Mockito/Byte Buddy 的 JVM attach；旧导出测试同样受影响。经权限批准后原命令成功，未跳过测试或改变 MockMaker。
- `./mvnw -B -Dit.test=EngineeringExportIT verify` 成功，包含所有平台单元测试及原下载 HTTP 2 项。仅使用已有 `atoms_demo_test`，Flyway 仍为 V010，没有新迁移。
- 当前未接通构建任务/进程/UI。代码基于用户指定 Oracle Java 17.0.5，不升级模板或依赖。

## WB02 · 私有工作区

- `WorkspacePaths` 验证绝对专用根、0700、所有者、无符号链接及根目录文件身份；替换根目录后旧对象拒绝继续操作。每次派生路径都重新检查根，不接受浏览器路径。
- `WorkspaceStore` 先复用 ZIP 路径/配额校验，再以 CREATE_NEW 写入全新目录；源文件 0600，目录和 Maven Wrapper 0700。不覆盖旧任务、不自动删除文件。
- RED：新增路径/存储测试因接口不存在失败；GREEN：`WorkspacePathsTest,WorkspaceStoreTest,EngineeringZipTest` 共 6 项通过，包括穿越、重复任务、非私有目录、链接与替换根反例。
- 文件原子新建语义依据 [Java 17 Files](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/file/Files.html#newByteChannel(java.nio.file.Path,java.util.Set,java.nio.file.attribute.FileAttribute...))。本机同用户信任边界未改变，不宣称这些检查可隔离同 OS 用户的恶意程序。

## WB03 / C-WB01 · 冻结归档复验

- 按已保存摘要返回原始 ZIP；逐个源文件校验实际字节。源文件改变时原 ZIP 保持原字节，归档被改写后拒绝读取，不自动重新打包。
- 读取检查普通文件、所有者、私有权限、链接数、祖先/文件无符号链接和大小；打开后仍以 limit + 1 有界读取。目录、硬链接和超限拒绝。
- 新测试先因接口缺失失败，实现后定向 8 项通过。完整平台 `./mvnw -B verify`：**93 单元 + 54 集成通过，0 跳过，61 秒**；Java 17.0.5、本机平台专用测试库。包括原下载、模板 JAR 白名单/体积及生成/发布/恢复回归。
- 未变更模板字节、依赖或平台 V000–V010；没有真实模型请求。进程监督、构建任务与工作台仍未完成。

## WB04 · 本机进程监督

- 新增随平台打包的 `workspace/supervisor.mjs` 与私有 IPC keeper。keeper 作为任务组 leader 保持存活；收到停止或上级 IPC 断开后向自身当前进程组发 TERM，宽限后 KILL。上层从不向缓存 PID/PGID 发破坏性信号，仅在退出后观察组是否消失。
- 工具输出持续排空但不保存正文，只保留饱和至 1 MiB 的计数；工具不继承 keeper IPC。取消不可逆，后续 run 拒绝；父 stdin EOF 转中断。观察失败/退出未知会报告未确认，不当作已取消成功。
- 新模块缺失时测试 RED；首批 7 项通过。新增“启动后立即取消”发现 keeper 尚未收到初始化消息时使用错误的测试宽限：5.1 秒，断言失败；改为启动时给定受限宽限，最终 **8 项通过，3.4 秒**，0 跳过。
- 真实测试覆盖超量输出、非零退出、工具父进程退出但孙进程持有 stdout、取消、超时、拥有者 SIGKILL 导致 stdin EOF、无关进程存活、停止后拒绝再启动。使用临时夹具，不运行生成代码。
- 只读 fresh-context 设计审查：缓存 PID 风险、永久停止状态、初始化/重复停止竞态为有效发现并已处理；执行身份/持久 fence 归 WB05/WB16；固定工具链不得脱离任务组的前提归 WB13 实际流程审计。没有调用外部跨模型 CLI。
- **边界**：进程组消失不证明任意 daemon/setsid 程序退出。本模块只用于平台固定、需审计为不自行脱离任务组的构建流程；不支持任意命令、任意插件或不可信后端，不宣称操作系统级隔离。依据 [Node 24 子进程文档](https://nodejs.org/download/release/v24.20.0/docs/api/child_process.html#optionsdetached)；`killed` 标记不能作为退出证明。

## WB05 · Java 控制桥接

- `NativeBuildProcess` 只供 Worker 使用，清空并白名单恢复子进程环境，参数按数组传递；不提供 HTTP 命令入口。
- 控制消息限 2048 字节/1000 帧，匹配本次 executionId，阶段不得倒退；错误执行 ID、超长/伪造输出、缺失完成帧、监督器 SIGKILL、成功帧后非零退出均报告未确认中断。
- 超时/取消发送固定 CANCEL 控制消息，待进程退出及可信完成帧后才返回已确认；停止期限届满或线程中断保留 unknown，不把发信号当作退出。持久 fence 和 JAR 复验留给 Worker/恢复切片。
- RED：缺少桥接类导致测试编译失败；GREEN：3 项真实 Java→Node 测试通过（包含多个协议反例），再运行全部平台单元 **96 项通过，0 跳过**。正常启动测试 3 秒预算，专门总超时案例仍为 500ms，生产总期限 10 分钟不变。
- 进程等待及管道语义依据 [Java 17 Process](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Process.html)。官方 ProcessBuilder 页面抓取失败，未将它记为已核验来源；本次启动/等待由实际 Java 17.0.5 运行验证。

## WB06a · 执行根互斥与预算

- 增加稳定 inode 的 `.scheduler.lock`，关闭后保留文件；同 JVM 注册表与 OS 文件锁配合。32 个任务目录、8 GiB 软件总量及准入最低 2 GiB 可用空间检查；只扫描元数据，不跟随 npm 链接、不删除旧目录。
- 新测试最初因类缺失编译失败。实现后真实双 JVM 测试进一步捕获：第二次同 JVM 尝试获得锁时读取/关闭同 inode 的描述符，会释放第一个持有者的 POSIX 锁，外部 JVM 错误取得执行权。
- 根因符合 [Java FileLock 平台依赖说明](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/channels/FileLock.html)。修复为打开任何同根锁文件描述符之前先原子取得 JVM 内持有权，外部互斥仍由 OS 锁执行；最终 2 项测试通过，含真实第二 JVM、关闭后重获和超量拒绝保留原文件。
- 此时 WB06b/c 未完成，本地自动执行尚未启用。

## WB06b/c · 显式配置与专用测试库

- 默认关闭执行；只允许 local/fixtures/test 和 loopback，管理员显式指定私有根、Node 24.20.x/npm 11.19.x/Java 17 及私有测试配置。版本探针有 3 秒期限，环境不继承平台密钥与解释器覆盖变量。2 项测试 RED 后 GREEN。
- 只读连接验证服务端 transaction_read_only、数据库/角色用途标记、非管理角色权限、独立 schema 与 installation 身份。非法配置测试 RED 后 GREEN；现有 `.local/standalone/test.properties` 的实际只读探针通过。沙箱阻止本机连接，授权后复验成功；没有修改文件或数据库。
- 进程套件回归发现测试 PID 报告的读取与未完成写入竞态（JSON 意外结束），改为测试夹具先写私有临时报告再 rename 发布，保留所有进程退出断言。生产协议不使用这份测试文件。

- C-WB02：平台完整 verify 为 **101 单元 + 54 集成**通过，进程套件 Node 24.20.0 **8 项通过**，均无跳过。

## WB07 · 增量持久记录

- V011 已在 `atoms_demo_test` 应用；新增构建/事件表及项目 `(id, owner_id)` 唯一约束支持复合外键，不更新旧表数据。原 V000–V010 未改。默认执行仍关闭，回退方式为关闭功能并保留表/证据，不运行删表 down SQL。
- 来源、模板、执行身份与冻结归档受不可变约束；活动项目唯一、执行 fence 唯一和状态/终态字段约束生效。事件仅存固定类型/阶段/错误码，字段总量小于 2 KiB，序号最多 1000。
- 测试先因仓库类缺失失败，实现后真实 PostgreSQL 4 项通过，包含事务失败整体回滚、跨 owner、重复活动项目、非法终态和来源修改拒绝。没有开启调度或模型请求。

## WB08 · 幂等准入

- 环境资源持有器的 2 项生命周期测试 RED 后 GREEN，持有单一根锁且关闭后不重开。后续复验一次容量检查失败；本机当时可用空间约 2.7 GiB，已告知用户风险，未调低 2 GiB 门槛；最终全套仍须复验。
- API 的 4 项真实 HTTP/PG 测试由缺失路由的 404 转为通过；6 个同键并发响应一致，8 项跨项目并发仅 4 项被接收，同项目不同意图 409，未知字段/CSRF/陌生会话被拒绝。开关关闭后旧意图仍可重放，同键异体 422。
- 根扫描、工具/数据库核验和模板摘要均在短事务外；事务只锁定独立 advisory lock、项目、幂等结果并保存任务。未启动任何构建进程。

## WB09 · 状态与阶段查询

- 新增详情、keyset 历史分页、单调事件游标；DTO 固定 17 个字段，不带路径、PID、执行身份或源码。陌生访客/混用项目拒绝，GET 不触发构建。
- 2 项真实 HTTP/PG 测试先因 GET 未实现返回 405，接通后通过，含分页、空尾页、越界游标和敏感字段排除。测试在 256 MiB JVM 堆下通过，不改变生产执行配置。
- 环境容量失败已定位：真实 Java FileStore 报 **412,766,208 bytes** 可用，小于 **2,147,483,648 bytes**；门槛断言保留，未跳过或降低。已请求用户释放空间，暂停大型工程构建，源码接线继续。C-WB03 完整回归检查点尚未完成。

## WB10 · 取消意图与原子状态竞争

- QUEUED 取消直接终态；BUILDING 仅转 CANCELLING，不释放执行 fence。重复取消和终态取消不额外产生事件。所有更新与幂等结果同事务，并与准入/未来领取共用独立 advisory lock。
- 3 项初始 HTTP 测试先 404 失败后通过，再增加真实双连接取消/领取、取消/完成竞争，最终 **4 项通过**。竞争测试的执行/完成由明确标注的数据库夹具模拟，不宣称实际 JAR 或进程退出；真实退出接线仍归 WB14/WB16。
- WB11a：JSON 客户端新增调用方 AbortSignal，两个反例先失败后通过；晚到 403 不再污染新会话，预先取消不发请求，原有错误语义不变。11 项客户端测试与类型检查通过。

## WB11 · 前端协议与原意图恢复

- DTO 严格校验标准 UUID、有限状态/阶段、64 位摘要、UTC 时间、有界事件序号与成功产物字段；响应必须对应原请求项目/buildId。协议 3 项从模块缺失 RED 到 GREEN。
- 状态管理仅轮询 GET；原提交/取消 key 保存在按访客/项目/版本隔离的 sessionStorage（不含会话凭据），网络不确定时手动确认同一意图。存储不能保存 key 时不发新请求；已接收提交后详情读取失败也保留 key。
- 6 项状态管理测试通过：刷新/终态停轮询、组件重挂同键恢复、旧项目晚到响应、取消响应丢失、提交成功后读取失败、浏览器存储失败拒绝。JSON/协议/状态共 20 项定向测试，类型和相关 lint 通过。UI/浏览器实际接线尚未验收。

## WB12 · 工作台任务入口

- ArtifactPanel 接入独立构建区：选中版本、提交/取消/刷新、原意图确认、受控阶段与近期历史；成功明确“构建完成，实例预览尚未接入”。没有变更 PreviewFrame、生成输入或发布状态。
- 新组件 4 项由缺失模块失败转通过，连同旧恢复组件 4 项通过；全前端单元 **147 项通过（单 worker，109 秒）**，lint、格式、生产构建通过。实际浏览器、键盘/axe 与真实构建仍待后续闭环，不把组件夹具当作成功 JAR。
- 磁盘可用空间在约 0.4–3.6 GiB 之间波动；没有清理用户文件、降低门槛或扩大预算。真实工程执行前会重新核验。

## WB13 · 固定流程与可信入口

- 平台通过内部 `run/onStage` 回调复用 ZIP 中的同一 build.mjs；6 项模板脚本测试通过。新入口只读取 Java 物化/验证过的固定脚本，工具输出丢弃，仅输出受控阶段/错误码；不存在用户命令参数。
- 真实 Node runner 4 项由文件缺失 RED 到 GREEN：正常子进程、取消、父管道 EOF、错误原文排除与伪造运行指纹拒绝。结束前独占写入并同步本次 executionId 的 settlement，只有全部工具组收尾后才可标记 settled。此证据不是实际工程 JAR 构建。
- 清单仍为 106 文件，实测 526,400 字节，最大文件 121,072 字节；现有 128 文件/2 MiB 单文件/8 MiB 总量及 600 KiB 模板维护预算不变。
- 固定命令树审计：npm ci 使用锁文件和 ignore-scripts；package.json 无 pre/post 脚本，typecheck/lint/build 为有限 CLI，vitest 明确 run；Maven wrapper 执行常规 Maven（非 mvnd），插件仅固定 Boot/Surefire/Failsafe 与标准生命周期，无 spring-boot:start 或 exec daemon。测试 Java/Node 子进程均在工具组内。[npm ci](https://docs.npmjs.com/cli/v11/commands/npm-ci/)、[Surefire fork](https://maven.apache.org/surefire/maven-surefire-plugin/examples/fork-options-and-parallel-execution.html)、[Vitest CLI](https://vitest.dev/guide/cli.html) 支持这些入口语义；具体未脱组仍须 WB18 真实执行检验。
- 原生模式信任本机管理员及其 npm/Maven 配置和已安装工具，不读取/输出这些配置，不支持恶意本机同用户、任意依赖/插件或后台 daemon；不是 OS 沙箱。启动环境不传模型 Key/平台数据库密码，专用测试配置路径只传 Maven。
- 可观测性问题：当前任务在哪一阶段、为何失败、旧进程是否收尾。持久受控事件回答前两项，执行 fence/settlement 回答第三项；后续调度日志只允许 buildId、executionId、entryPoint 和有限结果字段，禁止源码、凭据与原始工具输出。

## WB14–WB17 · 执行闭环与边界修正（真实工程验收仍待 WB18）

- JAR 验证器 2 项、真实 PG 生命周期 4 项、Worker 3 项和 Java→Node 控制 4 项通过；Worker 使用明确标注的小 JAR 夹具验证分支，不冒充 Maven 实际构建。
- 调度生命周期 2 项、真实数据库协调租约 1 项、环境生命周期 2 项通过。只有取得根 OS 锁、数据库 session advisory lock 并完成旧任务中断处理后才开放准入；跨根共享同一平台 DB 也不能有两个协调器。
- 构建归档 HTTP 2 项从缺少路由 RED 到 GREEN；冻结字节一致、陌生访客拒绝、未就绪/篡改均明确失败。新下载客户端/控件 6 项、工作台组件 4 项通过，类型/lint 通过。历史与当前构建按 buildId 下载，不重新组装。
- 重启恢复真实 PG 1 项通过：缺失/不完整/错误执行 ID/未收尾/伪造错误码/硬链接报告均保留 fence；有效报告只解除占位，任务仍为 INTERRUPTED，不变成成功或重跑。实际父进程退出证据由现有监督套件及 WB18/WB19 补验。
- 只读独立审查的 3 项发现全部接受：运行目录正常删除临时文件不再误判磁盘超限（仅容忍 NoSuchFile，3 项根锁/容量测试通过）；Node 接受 Java 传入的剩余绝对期限，不重置十分钟（独立截止测试 RED→GREEN，runner 总计 5 项通过）；退出报告改为独占创建单链接文件，读取未完整 JSON 时保持 fence，完成帧前 fsync，消除 hardlink/unlink 中断窗。不放宽链接检查或资源门槛。
- 跨模型复核已询问，未获授权，未调用外部 CLI。当前为单模型只读审查与实际测试证据。
- 一次平台完整回归因同时在同一 target 编译新测试导致 FixtureIT 的 TodoFixture 类暂时缺失（NoClassDefFoundError）；已停止这种并发方式，串行 FixtureIT 复验通过。该次完整回归不算通过，最终必须重新完整执行。

- 后续串行完整 `./mvnw -B -DargLine=-Xmx256m verify` 已通过：**115 单元 + 77 集成，0 跳过**，耗时 3 分 02 秒；包含新增授权优先/容量错误分类的 RED→GREEN、真实恢复/归档/跨根租约，以及原生成/版本/发布/状态/导出回归。模型测试使用原有 stub，不是真实模型调用。

## WB18 · 真实 API 构建与下载重建

- `verify-workspace-build.mjs --config-dir .local/standalone --output-parent .local/standalone` 退出 0。本机证据保存在已忽略的 `.local/standalone/workspace-verification-TF0paR/verification.json`；buildId `e9c4ae02-f044-4568-9612-d946812b3db2`，实际 Worker 151,893 ms，所有阶段到 SUCCEEDED/COMPLETE，退出已确认。
- 按 buildId 下载冻结 ZIP，111 文件 / 547,486 源码字节，在另一新目录冻结安装并完成脚本测试、前端检查、Maven verify、实际 JAR 资源检查。归档摘要 `ce3d6644ca6a415f2064fe5d5ee6e1c306ed3135a1e946fa35c06c3076cf7003` 与持久任务一致。
- 原构建与 ZIP 重建实际运行身份同为 `7fd42cd3ae35dd48371eebe7cf90d1e6b82513e0dae1181201f7054dd5275fea`；JAR 二进制 hash 不同是构建时间等元数据差异，不冒充字节可复现构建。
- 两个 JAR 在各自现有专用运行库启动，同一 Chrome context 中分别添加不同待办、刷新、逐一真实进程重启，数据和访客保持且互不串用。控制台 warning/error 与页面错误为空，人工查看两张截图确认 UI 和独立数据。Node 入口与直接 Java 启动都通过，测试只停止自有进程。
- 验证器参数/成功合同与双实例 helper 的 3 项守卫测试通过。本次临时启动仅为验收，不是正式的实例管理或替换平台 iframe；未修改凭据、创建数据库、调用模型、推送或部署。
