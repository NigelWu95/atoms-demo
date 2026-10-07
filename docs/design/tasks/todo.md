# Atoms Demo · 可执行任务清单

状态：T01–T03、T05–T19、C01、C03–C08、C10 已通过相应本地验收；已完成真实模型生成待办、保存/刷新及一次修改保留数据。T20–T24 的完整验收仍未完成，其中 T24 还要求第二轮真实修改。累计请求 11 次，授权剩余 0。T00 的公网资源验证仍缺失，T04 按本地优先决定暂后置。`[ ]` 表示尚未完成，不代表没有对应代码。总体顺序、路径缩写和验证命令见 [开发计划](plan.md)。

说明：范围 S 为约 1–2 个主要文件，M 为约 3–5 个；自动脚手架文件按计划约定整体审阅。每项必须同时满足自己的验收条件和统一完成标准。所有代码路径都是拟建工程中的相对路径。

每个检查点完成后记录证据、汇报结果；未通过就修复当前切片，不堆叠后续复杂功能。检查点本身不替代用户对付费、部署或范围变化的授权。

当前实施记录：[验收证据](../../verification.md)。T01 增加 V000 基线迁移；浏览器测试位于 frontend/e2e，避免独立前端包之外的依赖解析。T09 已接通 /projects/:id；/workbench 保留未保存草稿入口。项目、已提交需求、规划与任务结果持久化；未提交草稿仍只保留在页面会话中。

T10–T15 使用固定手工计数器/待办检验源码落库、执行隔离、SDK 与应用状态保存；测试入口为独立 test-run 的 `/test-lab`，仅连接 atoms_demo_test，测试工作台为 5174，浏览器用例位于 frontend/e2e-runtime。计数器仍刷新归零；待办已可刷新恢复，并验证源码视图、私有独立预览、历史只读、断网重试和双标签冲突。这不是 AI 生成。用户决定保留访客方案，OAuth 暂不实现。

## M0 · 开工准备

### T00 · 冻结实施基线

- [ ] 完成 T00（P0，M）
- 说明：明确本次实现的技术组合、模型、数据库和部署资源，记录已知环境差异。
- 依赖：无。
- 主要文件：`README.md`、`.env.example`、`.gitignore`、`docs/verification.md`。
- 验收：
  1. Java、Node、数据库及依赖版本锁定，数据库只选择一种；记录当前环境是否兼容。
  2. 在用户提供的凭据和预算内完成最小模型请求；记录响应协议、超时/取消支持和用量，真实 .env 加入忽略规则，不把密钥写进文档。
  3. 部署账号、目标、HTTPS、SSE、后台进程和持久盘条件已核实；缺资源明确列为待补，不虚构在线链接。
- 验证：读取版本信息；按所选供应商官方说明做小请求；检查部署条件。若有资源缺口，可以先做不依赖资源的任务，T00 保持未完成。

## M1 · 基础网站

### T01 · 建立可测试的后端骨架

- [x] 完成 T01（P0，M）
- 说明：建立 Spring Boot 工程、健康检查、数据库连接和测试运行约定，不提前实现所有业务。
- 依赖：T00 的技术基线已明确。
- 主要文件：`backend/pom.xml`、`B/AtomsDemoApplication.java`、`R/application.yml`、`BT/BackendSmokeIT.java`、`backend/mvnw`。
- 验收：
  1. 本地能启动并返回有限信息的健康响应，敏感配置从环境注入。
  2. Maven Wrapper 可运行，Surefire/Failsafe 分别执行单元与集成测试，空测试集不能冒充覆盖。
  3. 可连接独立测试数据库；迁移由 Flyway 管理，生产不使用自动破坏性建表。
- 验证：V-B；数据库断开时观察明确的启动/健康错误。审阅自动生成的 Wrapper 与构建配置。

### T02 · 建立前端工程和检查脚本

- [x] 完成 T02（P0，M）
- 说明：建立 Vue/TS 工程及可重复安装、类型检查、单元测试、构建入口。
- 依赖：T00 的技术基线已明确。
- 主要文件：`frontend/package.json`、`frontend/package-lock.json`、`frontend/vite.config.ts`、`F/main.ts`、`F/App.spec.ts`。
- 验收：
  1. 锁文件提交后可使用 npm ci 安装；开发模式能打开真实页面。
  2. 提供计划约定的 typecheck、test:unit、test:e2e、build 脚本，脚手架建立 Vitest/Playwright 配置，基础测试实际运行。
  3. 开发代理通过 /api 访问后端，前端包和环境模板没有模型密钥。
- 验证：V-F；浏览器检查页面和网络。TS、测试等机械脚手架配置一并审阅。

### C01 · 工程检查点

- [x] 前后端构建通过，浏览器能打开页面，测试不是空跑；记录依赖安装和环境的实际耗时。

### T03 · 完成首页与工作台基础布局

- [x] 完成 T03（P0，M）
- 说明：落实首页主输入、导航、工作台对话/预览分区和空态，形成可演示的网站骨架。
- 依赖：T02。
- 主要文件：`F/App.vue`、`F/router.ts`、`F/styles.css`、`F/pages/HomePage.vue`、`F/pages/WorkbenchPage.vue`。
- 验收：
  1. 首页、工作台路由和返回操作可用，示例按钮只填充需求文本。
  2. 1440px 有清楚的双栏，390px 不强挤双栏，键盘焦点可见，中文输入法不会误发送。
  3. 尚未接入的操作明确禁用或标注开发状态，不展示假的生成成功和进度。
- 验证：V-F；手动走导航、Tab、Enter、Shift+Enter 和中文输入；记录两种宽度截图。

### T04 · 提前验证部署骨架

- [ ] 完成 T04（P0，M）
- 说明：尽早验证选定环境能运行前后端和数据库，不等核心功能完成才第一次上线。
- 依赖：T01、T03；外部部署依赖 T00 的资源与授权。
- 主要文件：`backend/Dockerfile`、`frontend/Dockerfile`、`deploy/compose.yaml`、`deploy/nginx.conf`、`e2e/smoke.spec.ts`。
- 验收：
  1. Compose 本地可启动；数据库仅内部可访问，使用命名持久卷。
  2. SPA 深链接和 /api/health 或已选健康端点能通过同一入口访问，API 错误不会被当作 index.html。
  3. 已获部署授权时外部环境返回基础页面和健康状态；尚无资源时如实保留线上验证缺口。
- 验证：V-D、本地构建启动、浏览器烟测；外部部署使用 V-X。不要执行删卷来“修复”启动。

### C02 · 基础网站检查点

- [ ] 网站可导航、接口可访问，部署路径已经尝试；区分“本地通过”和“线上通过”。

## M2 · 真实会话与项目

### T05 · 建立可恢复的访客身份

- [x] 完成 T05（P0，M）
- 说明：使用服务端会话建立真实用户身份，为所有项目和数据访问提供基础。
- 依赖：T01。
- 主要文件：`R/db/migration/V001__identity.sql`、`B/identity/SecurityConfig.java`、`B/identity/GuestIdentityService.java`、`B/identity/SessionController.java`、`BT/identity/SessionIT.java`。
- 验收：
  1. GET /api/csrf → POST /api/session → GET /api/me 可以创建并复用同一访客身份。
  2. 会话存入 JDBC；有效会话在后端重启后仍可用，生产 Cookie 具备正确安全属性。
  3. 缺少或错误 CSRF 的写请求被拒绝；另外一个会话不是同一用户。
- 验证：V-B；两个 Cookie jar/浏览器身份测试；不要把开发 HTTP Cookie 配置直接用于线上。

### T06 · 接入前端会话与错误处理

- [x] 完成 T06（P0，M）
- 说明：页面启动先建立身份，统一 HTTP、CSRF 和失败提示，不在每个组件重复拼请求。
- 依赖：T03、T05。
- 主要文件：`F/api/client.ts`、`F/api/session.ts`、`F/stores/session.ts`、`F/App.vue`、`F/api/client.spec.ts`。
- 验收：
  1. 首页加载后恢复身份；初始化失败显示重试入口，不能继续提交需要身份的操作。
  2. 统一解析协议中的错误 code/requestId，处理网络失败，不吞掉非 2xx 响应。
  3. 写请求带 CSRF；请求重试不生成新的用户意图幂等键，不无限重试扣费操作。
- 验证：V-F；模拟 401/403、网络中断并重试；浏览器检查凭据不进入页面日志。

### C03 · 身份检查点

- [x] 同一浏览器可恢复身份、不同浏览器身份不同、CSRF 测试通过；初始化失败有真实错误态。

### T07 · 实现事务内幂等机制

- [x] 完成 T07（P0，M）
- 说明：先把“同一操作重试只执行一次”做成可复用机制，后续项目、运行、状态和发布共用。
- 依赖：T05。
- 主要文件：`R/db/migration/V002__idempotency.sql`、`B/common/IdempotencyService.java`、`B/common/ApiError.java`、`B/common/ApiExceptionHandler.java`、`BT/common/IdempotencyIT.java`。
- 验收：
  1. actor/method/resource/key 唯一；业务写入与幂等结果在同一数据库事务中提交。
  2. 相同请求返回原 HTTP 结果；相同键不同规范化请求体返回 422。
  3. 并发同键请求不双写；业务回滚不留下永久占用或虚假的成功结果。
- 验证：V-B；两个并发事务、异常回滚和跨身份同键用例。

### T08 · 实现项目接口

- [x] 完成 T08（P0，M）
- 说明：提供新建、列表、详情和重命名，先让一个真实的产品功能闭环落库。
- 依赖：T07。
- 主要文件：`R/db/migration/V003__projects.sql`、`B/project/Project.java`、`B/project/ProjectService.java`、`B/project/ProjectController.java`、`BT/project/ProjectIT.java`。
- 验收：
  1. 新建、分页列表、详情、重命名符合现有接口契约，重复新建意图只创建一个项目。
  2. 所有读取和修改按 ownerId 检查，猜测他人 ID 返回 404。
  3. 重命名不改变源码 revision，刷新或应用重启后项目仍在。
- 验证：V-B；名称边界、重复提交、越权和分页测试。

### T09 · 完成首页到项目页的真实链路

- [x] 完成 T09（P0，M）
- 说明：把首页和“我的项目”接到真实接口，暂不伪装成已经接入生成器。
- 依赖：T06、T08。
- 主要文件：`F/api/projects.ts`、`F/pages/HomePage.vue`、`F/pages/ProjectsPage.vue`、`F/router.ts`、`e2e/projects.spec.ts`。
- 验收：
  1. 可以新建、打开、重命名项目；列表不是本地写死数据；首页原始需求与本次操作标识保留到工作台，供 T23 接入生成。
  2. 创建失败保留输入，重复点击不重复建项目；刷新列表仍能找到项目。
  3. 项目空态、加载、失败、无权限/不存在有对应界面。
- 验证：V-F、项目 E2E、V-B；独立浏览器不能打开创建者的工作台资料。

### C04 · 第一个业务闭环

- [x] 现场演示“访客进入 → 新建项目 → 重命名 → 刷新 → 再次打开”；记录数据库和浏览器证据。

## M3 · 可运行预览与业务数据

### T10 · 建立产物与不可变版本

- [x] 完成 T10（P0，M）
- 说明：定义 web-static-v1 快照及版本读取，不引入任意文件系统写入。
- 依赖：T08。
- 主要文件：`R/db/migration/V004__artifacts.sql`、`B/artifact/Artifact.java`、`B/artifact/ArtifactService.java`、`B/artifact/ArtifactController.java`、`BT/artifact/ArtifactIT.java`。
- 验收：
  1. 固定三个源码文件与 manifest 可存取；项目/versionNo 唯一，历史源码不可原位覆盖。
  2. 保存新版本与更新 currentVersionId/revision 同事务；可读取版本列表和指定版本，均检查所有者。
  3. 提供供 M3 浏览器验证使用的测试快照装载方式，仅操作专用测试库/配置；生产没有匿名写入任意快照的调试接口。
- 验证：V-B；事务回滚、版本编号竞争、越权与内容 hash 非唯一测试。

### T11 · 实现受限运行文档

- [x] 完成 T11（P0，M）
- 说明：后端把快照装配为独立 HTTP 运行文档，验证最危险的执行边界。
- 依赖：T10。
- 主要文件：`B/preview/RuntimeDocumentService.java`、`B/preview/RuntimeDocumentController.java`、`R/runtime/document.html`、`BT/preview/RuntimeDocumentIT.java`。
- 验收：
  1. document 端点返回正确 MIME、no-store 和响应级 CSP sandbox allow-scripts；CURRENT 只接受当前版本。
  2. body 片段按契约解析，CSS/JS 序列化防止 script 结束标签逃逸；生成内容不插入宿主 DOM。
  3. 普通管理页和运行文档安全头独立配置；没有 allow-same-origin、任意外部脚本或服务器执行生成代码。
- 验证：V-B；真实浏览器打开 iframe 和直接 document URL，检查 CSP；测试恶意闭合标签与越权。

### C05 · 运行文档检查点

- [x] 快照可保存读取，受限文档真实运行；直接打开也受沙箱约束，管理站点没有被全局放宽。

### T12 · 实现最小消息桥与 SDK

- [x] 完成 T12（P0，M）
- 说明：让生成脚本只通过 Atoms.ready 和固定 storage API 请求宿主能力。
- 依赖：T11、T06。
- 主要文件：`F/runtime/bridge.ts`、`F/runtime/protocol.ts`、`R/runtime/bootstrap.js`、`F/runtime/bridge.spec.ts`、`e2e/runtime-channel.spec.ts`。
- 验收：
  1. 握手检查 iframe.contentWindow、每次加载的 channelId 和消息结构，再使用 MessageChannel；不以 null origin 授权。
  2. 只处理 STATE_GET、STATE_SAVE、RUNTIME_READY、RUNTIME_ERROR；无任意 URL、SQL、项目或发布能力。
  3. RPC 有超时；刷新/切换 iframe 关闭旧 port、拒绝未完成请求；凭据不会传入 iframe。
- 验证：V-F、桥接 E2E；伪造 source/type/channel、旧通道回包及超时测试。

### T13 · 实现预览状态存储

- [x] 完成 T13（P0，M）
- 说明：保存生成应用的真实 JSON 业务状态，与代码版本分开。
- 依赖：T10、T07。
- 主要文件：`R/db/migration/V005__app_states.sql`、`B/appdata/StateDocument.java`、`B/appdata/AppStateService.java`、`B/appdata/PreviewStateController.java`、`BT/appdata/PreviewStateIT.java`。
- 验收：
  1. 无记录返回 initialState/revision=0；首次保存原子插入，后续 expectedRevision 条件更新。
  2. 两标签同时保存只有一个成功，另一个 409；同一幂等键网络重试返回原成功结果。
  3. 仅所有者可写当前版本；拒绝过期 loadedVersionId、超限 JSON 和调用者指定 actorId。
- 验证：V-B；初次保存竞争、CAS、过期版本、越权、大小限制和重复写用例。

### T14 · 接通交互预览和保存反馈

- [x] 完成 T14（P0，M）
- 说明：组合运行文档、消息桥和状态 API，让明确标注的测试待办真正可操作。
- 依赖：T12、T13。
- 主要文件：`F/components/PreviewFrame.vue`、`F/runtime/previewContext.ts`、`F/api/preview.ts`、`F/runtime/bridge.ts`、`e2e/preview-state.spec.ts`。
- 验收：
  1. 测试待办能新增/完成并刷新恢复；每个桥请求固定绑定项目、版本和 PREVIEW 环境。
  2. 保存中、已保存、未保存/冲突真实可见；HTTP 重试复用该次桥请求的幂等键。
  3. 支持桌面/手机宽度和重新加载；HISTORY 上下文只读 initialState，不写当前状态。
- 验证：V-F、预览状态 E2E；离线保存和双标签冲突手测。测试夹具不可进入正式生成入口。

### C06 · 预览与数据检查点

- [x] 手工产物可操作和持久化；两个写入竞争不丢数据；桥接隔离测试通过。此时尚不能宣称“AI 生成”完成。

### T15 · 接入源码视图与独立预览页

- [x] 完成 T15（P0，M）
- 说明：工作台展示真实版本的源码和预览，独立打开仍使用可信外壳。
- 依赖：T14、T09。
- 主要文件：`F/api/artifacts.ts`、`F/pages/WorkbenchPage.vue`、`F/components/SourceViewer.vue`、`F/pages/StandalonePreviewPage.vue`、`F/router.ts`。
- 验收：
  1. 代码页按版本显示 view.html/styles.css/main.js，manifest.json 由对象格式化产生。
  2. 只读展示源码，HTML 不被当成宿主 DOM 执行；空项目没有伪造文件。
  3. 独立预览路由检查权限并复用 PreviewFrame，不能通过新标签绕过沙箱。
- 验证：V-F、V-B；查看含 HTML 标签的源码，打开私有独立预览并用其他身份访问。

## M4 · 真实 AI 生成内核

### T16 · 实现生成任务的持久化提交

- [x] 完成 T16（P0，M）
- 说明：先把短请求提交与长任务执行分开，定义可查询的任务和事件记录。
- 依赖：T10、T07。
- 主要文件：`R/db/migration/V006__runs.sql`、`B/run/RunController.java`、`B/run/RunAdmissionService.java`、`B/run/RunRepository.java`、`BT/run/RunAdmissionIT.java`。
- 验收：
  1. 提交短事务原子保存幂等、用户消息、QUEUED run、首事件和项目活动标记，返回 202/runId。
  2. 同项目只允许一个活动 run；CREATE/MODIFY 检查 baseVersionId 和 expectedProjectRevision。
  3. 同键并发仅创建一轮，成功后再重试也返回原 runId，不因 revision 已变化报错；GET run 和消息只向所有者开放。
- 验证：V-B；A05/A06、基线冲突、活动任务冲突、事务回滚；不在提交接口同步等待模型。

### C07 · 产物与任务检查点

- [x] 源码和独立预览可用；并发提交只得到一轮持久化任务。Worker 已接入；缺凭据/预算时明确禁用，不擅自发出收费请求。

### T17 · 接入一个真实模型供应商

- [x] 完成 T17（P0，M）
- 进度：协议与安全测试通过，实际调用 DeepSeek 返回 final content 和 usage；累计 11 次请求记录可查，其中一次非 stop 响应 usage 未留存、记为 UNKNOWN。按用户选择固定官方端点与 deepseek-flash，服务端配置凭据和额度，用户自定义端点/模型后置。
- 说明：在 ModelGateway 后隐藏具体协议，先验证最小真实接入，再复用到工作流。
- 依赖：T01、T00 的真实模型资源。
- 实际主要文件：`B/model/ModelGateway.java`、`B/model/DeepSeekGateway.java`、`B/model/LimitedBody.java`、`.env.local.example`、`BT/model/DeepSeekGatewayTest.java`。
- 验收：
  1. 可配置端点/模型/服务端密钥，能解析所选供应商实际响应与 usage；不假定所有兼容接口能力相同。
  2. 超时、认证失败、限流、连接失败和取消有明确错误映射，不向前端泄露原始认证响应。
  3. 先在测试中用供应商协议替身验证边界，再做一次有预算的真实调用；不让访客指定任意上游 URL。
- 验证：V-BF 定向测试；V-M 小请求。若流协议未选用，阶段事件仍可提供真实进度，不伪造逐 token 输出。

### T18 · 实现规划、生成、校验及有限修复

- [x] 完成 T18（P0，M）
- 进度：真实 Planner → Builder → 一次 Repair 曾生成可交互 V1；补充六字段规划示例和精简构建目标后，最新 CREATE/MODIFY 均用两次请求成功，无模板兜底。有限修复与失败保护已有测试和实测；一次完整浏览器闭环不代替 T31 的多类型质量验收。
- 说明：实现有界工作流，角色阶段对应真实模型或程序动作。
- 依赖：T17、T10。
- 主要文件：`B/model/GenerationWorkflow.java`、`B/artifact/ArtifactValidator.java`、`R/prompts/planner.md`、`R/prompts/builder.md`、`BT/model/GenerationWorkflowTest.java`。
- 验收：
  1. Planner 和 Builder 各发起真实请求；输出完整 Artifact，Validator 检查文件白名单、结构、体积和受限内容。
  2. 契约失败最多一次带真实错误的修复；认证/额度失败不盲目重试，无固定模板成功兜底。
  3. 修改上下文只含当前快照、请求和示例数据契约，不含用户真实业务状态、密钥或所有历史版本。
- 验证：V-BF；正确产物、缺文件、截断 JSON、超大输出、修复仍失败、恶意字段用例。结构通过不等于业务正确。

### T19 · 接入后台执行与原子版本提交

- [x] 完成 T19（P0，M）
- 进度：数据库任务领取、版本原子保存和修改保留数据通过 PostgreSQL 测试；真实模型任务完成 V1 提交与浏览器预览。修改失败未推进版本指针。
- 说明：Worker 从数据库领取任务，执行工作流，将产物与终态原子落库。
- 依赖：T16、T18。
- 实际主要文件：`B/run/GenerationWorker.java`、`B/run/GenerationScheduler.java`、`B/run/RunExecutionService.java`、`B/artifact/ArtifactService.java`、`BT/run/GenerationExecutionIT.java`。
- 验收：
  1. 有限 Worker 领取数据库任务，阶段状态和事件持久化；数据库事务不跨模型网络等待。
  2. 统一锁顺序检查取消、activeRunId 和项目 revision，再提交版本、当前指针、消息与终态；失败保留旧版。
  3. 完成事件对应已提交结果，释放活动标记；SUCCEEDED 仅表示产物保存，previewStatus 初始 UNKNOWN。
- 验证：V-B；测试替身跑完整事务和失败路径，再 V-M 生成一次并在已有预览中打开。

### C08 · 首次真实生成检查点

- [x] 新需求通过真实 Planner/Builder/一次 Repair 产生 V1，真实浏览器新增、完成、刷新恢复待办；请求数与 usage 已记录，不把替身测试计为模型实测。

### T20 · 实现可回放 SSE

- [ ] 完成 T20（P0，M）
- 说明：SSE 只观察数据库事件，不承担任务执行生命周期。
- 依赖：T19。
- 主要文件：`B/run/RunEventService.java`、`B/run/RunEventsController.java`、`B/run/RunRepository.java`、`BT/run/EventReplayIT.java`。
- 验收：
  1. seq 在 run 锁内分配，事件先提交再发送；按 after/Last-Event-ID 持续尾随同一记录源，无回放/订阅切换空窗。
  2. 心跳、断线重连、权限与游标校验有效；断开 SSE 不取消后台生成。
  3. 终态包含 lastEventSeq，补齐后关闭连接；不暴露思维链或虚构进度百分比。
- 验证：V-B；注入断连、重复事件、并发取消事件和非法游标；流式客户端观察事件不是最终一次性返回。

### T21 · 实现取消与重启中断处理

- [ ] 完成 T21（P0，M）
- 进度：持久取消、晚到结果保护及启动恢复策略已实现并有测试；恢复测试直接调用启动处理，不等于进程崩溃演练。SSE 及完整重启/关闭页面场景仍待验证。
- 说明：把用户取消和进程重启变成明确状态，禁止晚到输出覆盖结果。
- 依赖：T19、T20。
- 主要文件：`B/run/RunCancellationController.java`、`B/run/RunControlService.java`、`B/run/GenerationWorker.java`、`B/run/RunRecovery.java`、`BT/run/RunControlIT.java`。
- 验收：
  1. 取消持久化标记并尽力停止上游；每阶段和最终提交重新核对，晚到结果无法成为当前版本。
  2. 重复取消已取消任务保持终态；已成功/失败任务返回契约规定的 409，不被改写。
  3. 单实例重启将旧进程已执行任务标为 INTERRUPTED，QUEUED 可继续；不自动重新调用结果未知的付费请求。
- 验证：V-B；延迟上游配合取消/提交竞态，重启恢复与关闭浏览器后继续执行测试。

### T22 · 加入生成资源边界

- [ ] 完成 T22（P0，M）
- 进度：已实现单 Worker/活动队列 8、整轮 6 分钟、prompt/output/响应限制、持久累计请求次数预留，未知结果不退回。次数不是货币预算；身份频率、并发预算争抢及队列满完整验收尚待补齐。
- 说明：在准备开放线上生成前，限制队列、并发、输出与整体预算。
- 依赖：T19、T21。
- 主要文件：`B/run/GenerationLimits.java`、`B/run/GenerationBudgetService.java`、`B/run/RunAdmissionService.java`、`R/db/migration/V007__generation_budget.sql`、`BT/run/GenerationLimitsIT.java`。
- 验收：
  1. 全局 Worker/队列、单项目活动任务、单身份频率、prompt/output 大小和整轮截止时间有可配置限制。
  2. 全局预算原子预留，避免并发穿透；未知上游用量保守记账，重启不重置额度；预算单位和估算局限写清。
  3. 超限返回可解释 429/失败终态，不无限排队；日志仅保留 runId、阶段、耗时、错误类型及可得 usage。
- 验证：V-B；并发预算争抢、队列满、超时、重启和未知 usage 测试。访客 Cookie 限额不能替代全局预算。

### C09 · 任务可靠性检查点

- [ ] A04–A08 相关服务端用例通过，事件可补齐，取消后不提交，重启后不永久转圈；额度边界已生效。

## M5 · 网页内完成生成与修改

### T23 · 接通聊天式生成工作台

- [ ] 完成 T23（P0，M）
- 进度：普通 5173 已真实提交模型需求并自动预览生成产物，持久化交互通过；当前 1.5 秒短轮询，不宣称 SSE 已完成。取消/同键重试等错误场景仍由明确替身覆盖，完整 SSE 断线恢复验收待做。
- 说明：把已有生成内核接到首页/工作台，用户无需使用 API 工具。
- 依赖：T15、T20、T21、T22。
- 实际主要文件：`F/api/runs.ts`、`F/composables/useGeneration.ts`、`F/components/ConversationPanel.vue`、`F/pages/WorkbenchPage.vue`、`e2e/generation.spec.ts`；未知意图缓存位于 `F/stores/generation.ts`。
- 验收：
  1. 接收首页保留的需求或工作台新输入；用户消息、计划、阶段、结果、错误和取消有清楚状态；发送防重、重试复用意图键。
  2. 刷新通过项目 activeRunId、run 快照和事件游标恢复；按 seq 去重，不重新创建 run。
  3. 生成中保留上一版预览，不执行未完成代码；完成并补齐事件后读取新版本，失败保留输入和旧产物。
- 验证：V-F、工作台 E2E；首次需求从项目创建进入工作台后提交，中文输入、断线重连、取消、错误重试逐项手测。

### T24 · 完成基于当前版本的继续修改

- [ ] 完成 T24（P0，M）
- 进度：当前版本基线和保留数据的单元/集成测试通过；修订提示后真实 MODIFY 成功生成 V2，“仅看未完成 / 显示全部”生效，旧任务与完成状态刷新后保留。已完成一轮成功修改；仍缺验收要求的第二轮，未将 T24 全项勾选。
- 说明：把修改做成新的完整快照，而非原地覆盖文件或丢弃已有业务数据。
- 依赖：T23、T18。
- 主要文件：`B/run/RunAdmissionService.java`、`B/model/GenerationWorkflow.java`、`F/stores/run.ts`、`BT/run/ModifyRunIT.java`、`e2e/modify.spec.ts`。
- 验收：
  1. 请求明确包含当前 baseVersionId/revision，旧标签提交被拒绝，历史查看不偷偷改变修改基线。
  2. 成功产生新版本，失败保持旧版；stateSchemaVersion=1，现有状态不被 initialState 覆盖。
  3. 对代表应用完成两轮修改，旧功能和已有数据可用；不把结构校验说成任意代码语义兼容证明。
- 验证：V-B、V-F、修改 E2E；真实模型质量最终在 T31 再统一验收。

### C10 · 核心交互检查点

- [x] 通过普通 5173 网页完成“新需求 → 真实生成 → 操作保存 → 追加修改生成 V2”，筛选生效、旧任务与完成状态保留，刷新恢复和版本基线正确；完整 live 用例通过。

### T25 · 完成预览检查与错误反馈

- [ ] 完成 T25（P0，M）
- 说明：把生成成功和页面检查拆开，错误必须能定位到具体版本。
- 依赖：T14、T23。
- 主要文件：`F/runtime/validateScript.ts`、`F/components/PreviewFrame.vue`、`R/db/migration/V008__preview_reports.sql`、`B/preview/PreviewReportController.java`、`BT/preview/PreviewReportIT.java`。
- 验收：
  1. JS 语法不通过不执行，捕获 error/unhandledrejection 和 SDK 启动错误，界面可查看受限长度的错误。
  2. 版本检查报告可保存，UNKNOWN/PASSED/FAILED 与 run 状态分开；仅 iframe.onload 不能产生 PASSED。
  3. 报告验证所有者/版本、幂等和大小；发布要求对应版本有通过记录，但不把浏览器报告当安全证明。
- 验证：V-B、V-F；语法错误、初始化失败、生成期间关页后重新打开、伪造/超长报告用例。

## M6 · 发布与公开访问

### T26 · 实现固定版本发布

- [ ] 完成 T26（P0，M）
- 说明：为公开访问建立独立发布指针，不能将“当前编辑版本”直接当作线上版本。
- 依赖：T25、T07。
- 主要文件：`R/db/migration/V009__publications.sql`、`B/publication/Publication.java`、`B/publication/PublicationService.java`、`B/publication/PublicationController.java`、`BT/publication/PublicationIT.java`。
- 验收：
  1. 发布当前、已检查版本，检查 project/publication revision 和无活动 run，事务固定指定 versionId。
  2. 稳定 slug 可读取公开元信息和当前发布运行文档；文档版本过期明确返回 STALE_APP_VERSION。
  3. 更新需要显式操作；下线幂等且不删除源码或状态，越权与重复请求按契约处理。
- 验证：V-B；编辑 V2 时公开仍 V1，发布竞争、检查未通过、下线及猜测私有版本测试。

### T27 · 实现公开访问者状态隔离

- [ ] 完成 T27（P0，M）
- 说明：复用同一数据服务，增加 LIVE 命名空间和公开版本校验。
- 依赖：T26、T13。
- 主要文件：`B/appdata/PublicStateController.java`、`B/appdata/AppStateService.java`、`B/publication/PublicationService.java`、`BT/appdata/PublicStateIT.java`。
- 验收：
  1. 公开元信息可免登录读取，状态读写先建立访客会话，actorId 只由服务端确定。
  2. LIVE 与 PREVIEW 分离，不同访客互不混入；发布更新不复制预览数据、不清空线上数据。
  3. 旧公开页在版本更新后不能继续写，已下线应用不能读取/保存状态或请求运行文档。
- 验证：V-B；双访客、相同项目跨环境、更新后的旧标签和下线请求用例。

### C11 · 发布服务检查点

- [ ] 当前版本、公开版本、预览状态和访客状态四者边界经测试；无匿名私有资源泄漏。

### T28 · 完成发布交互与公开外壳

- [ ] 完成 T28（P0，M）
- 说明：让用户发布、复制链接、更新、下线，访问者通过可信外壳使用同一运行机制。
- 依赖：T27、T23。
- 主要文件：`F/api/publication.ts`、`F/pages/WorkbenchPage.vue`、`F/pages/PublicAppPage.vue`、`F/router.ts`、`e2e/publication.spec.ts`。
- 验收：
  1. 展示“当前 V2 / 线上 V1”，发布失败保留旧线上版本；复制实际 URL，可显式更新和下线。
  2. /a/:slug 不展示编辑控件或私有聊天，复用 PreviewFrame/SDK，版本过期重新取元信息。
  3. 无痕访问可新增、刷新保留自己的数据；未发布或下线有明确页面，不永久白屏。
- 验证：V-F、发布 E2E、V-X；A10–A12 主流程并手动确认 PREVIEW 数据没有带到 LIVE。

## M7 · 在线候选版本

### T29 · 完成实际部署配置

- [ ] 完成 T29（P0，M）
- 说明：将骨架部署升级为包含真实模型、任务、运行文档和持久化的候选版本。
- 依赖：T28、T04；实际部署须有对应授权和资源。
- 主要文件：`deploy/compose.yaml`、`deploy/nginx.conf`、`R/application.yml`、`.env.example`、`README.md`。
- 验收：
  1. HTTPS、Secure 会话、数据库持久卷、健康检查和服务端密钥生效；数据库和管理诊断接口不暴露公网。
  2. SSE 不缓存、读取超时足够；运行 document 路径不走 SPA fallback，安全头不被代理冲突覆盖。
  3. 记录部署版本和回退方法；重建应用保留数据，不执行 down -v，不将密钥打入镜像。
- 验证：V-D、V-X；在外部网络观察真实生成事件，重启应用后读取项目、版本、状态和有效会话。

### T30 · 验证浏览器与权限边界

- [ ] 完成 T30（P0，M）
- 说明：集中检查实际浏览器行为，补齐 API 测试不能证明的隔离和交互问题。
- 依赖：T29、T25、T12。
- 主要文件：`e2e/fixtures.ts`、`e2e/runtime-security.spec.ts`、`e2e/permissions.spec.ts`、`e2e/responsive.spec.ts`、`frontend/playwright.config.ts`。
- 验收：
  1. A14–A17：他人私有资源不可读；生成内容不能读取宿主 DOM/凭据或通过桥调用平台管理操作，直接 document 仍受限。
  2. 未知消息/旧 iframe 回包无效；禁止资源请求受到配置限制，异常在 UI 可见。
  3. 390px/1440px 关键流程、键盘操作、深链接、公开错误页面可用，没有被忽略的核心控制台错误。
- 验证：V-E、浏览器网络/控制台检查；如 DevTools 工具不可用，使用可用的真实浏览器测试，不以静态阅读替代。

### C12 · 上线候选检查点

- [ ] 外部环境可访问平台和公开应用；持久化、SSE 和浏览器隔离验证通过。发现缺陷先修复，不能用录屏掩盖。

### T31 · 验证真实模型的生成质量

- [ ] 完成 T31（P0，M）
- 说明：用有限数量的实际调用验证 Demo 真正能生成不同应用，而不只是协议测试通过。
- 依赖：T30、T24。
- 主要文件：`e2e/real-model.spec.ts`、`e2e/prompts.ts`、`docs/verification.md`。
- 验收：
  1. 待办、交互计算器、表单草稿三类不同提示各完成一次真实生成，检查源码差异与对应真实交互。
  2. 至少一个应用完成两轮修改，已有操作和数据保留；表单不宣称已经提交给不存在的组织后台。
  3. 记录实际耗时、调用数、可得用量、失败原因和重试；模型测试显式启用，普通测试/CI 不自动扣费。
- 验证：V-M、V-X；预算达到上限就停止并记录未完成用例，禁止把样例结果冒充现场生成。

## M8 · 优先延展：历史恢复

### T32 · 实现恢复为新版本

- [ ] 完成 T32（P1 优先，M）
- 说明：复用快照能力，历史恢复不覆盖历史记录，也不回滚业务数据。
- 依赖：T31、T10、T07。
- 主要文件：`B/artifact/RestorationController.java`、`B/artifact/ArtifactService.java`、`B/project/ProjectService.java`、`BT/artifact/RestorationIT.java`。
- 验收：
  1. V1 → V2 后恢复 V1 得到 V3，记录 restoredFromId，V2 仍可读取；相同源码 hash 允许产生新版本。
  2. 检查所有者、expectedProjectRevision 和无活动 run；相同幂等请求不重复恢复。
  3. 当前指针更新，但 app_states 和 publication 不变；新版本检查状态按契约重新建立。
- 验证：V-B；A20、恢复竞争、越权、重复请求和活动 run 冲突用例。

### T33 · 完成历史查看和恢复交互

- [ ] 完成 T33（P1 优先，M）
- 说明：提供历史入口、只读预览和明确说明后果的恢复确认。
- 依赖：T32、T15。
- 主要文件：`F/components/VersionHistoryPanel.vue`、`F/pages/WorkbenchPage.vue`、`F/api/artifacts.ts`、`F/runtime/previewContext.ts`、`e2e/history.spec.ts`。
- 验收：
  1. 可查看各版本源码；历史预览标注示例数据，只读且不切换当前修改基线。
  2. 确认弹窗说明“恢复代码、不回滚数据、不自动发布”，恢复后展示新的版本号。
  3. 恢复失败保留现状；检查旧版本和业务数据仍在，线上版本未改变。
- 验证：V-F、历史 E2E、V-X；录制完整 V1 → V2 → V3 过程。

### C13 · 延展能力检查点

- [ ] 已做 M8 时，通过 A20 并记录证据；未做时明确标注跳过，不把该功能写入已实现清单。

## M9 · 最终回归与交付

### T34 · 冻结范围并完成最终回归

- [ ] 完成 T34（P0，M）
- 说明：停止增加新功能，对最终待提交版本重新验证，不沿用旧版本的绿色结果。
- 依赖：T31；如果实施 M8，还依赖 T33。
- 主要文件：`e2e/acceptance.spec.ts`、`e2e/restart.spec.ts`、`docs/verification.md`、`tasks/todo.md`。
- 验收：
  1. A01–A19 的适用 P0 用例通过；实现恢复则 A20 也通过，阻断主流程/隔离/数据正确性的缺陷归零。
  2. 重启应用容器后验证项目、版本、状态、有效会话和旧 run 终态；不以重建数据库重置问题。
  3. 三套构建/测试和最后一次外部主流程有记录；新增缺陷拆为有验证的修复子任务，不跳过测试。
- 验证：V-B、V-F、V-E、V-X；所有结果关联最终代码标识。无法通过的硬要求如实报告，不标记完成。

### T35 · 整理可复现交付包

- [ ] 完成 T35（P0，M）
- 说明：提交可访问、可复现且描述真实的成果，而不只交概念文档。
- 依赖：T34。
- 主要文件：`README.md`、`.env.example`、`docs/verification.md`、`docs/demo-script.md`、`docs/ai-usage.md`。
- 验收：
  1. 提供实际平台 URL、应用 URL、源码获取方式和部署版本；从外部无痕窗口再次打开成功。
  2. 启动命令、环境要求、配置名称、数据持久化方式、生成范围、访客限制和已知问题与实际一致。
  3. 提供 2–4 分钟演示和真实 AI 使用说明；未实现 P1 明确列出，录像/历史项目不伪装为实时生成。
- 验证：按 README 复核启动流程；检查提交包无真实密钥；人工从用户视角走完演示。上传/提交到外部系统按用户授权执行。

### C14 · 交付检查点

- [ ] 任务与证据一致；真实访问链接、源码、配置模板、演示、限制说明齐全。只有完成到这里，才能称为“已交付”。

## 可选增强 · 不计入基础 8 小时承诺

以下任务仅在基础交付稳定、仍有明确时间预算时选择。不可因某个增强任务看起来容易而挤占 T34/T35。

### T36 · 错误辅助修复后端

- [ ] 完成 T36（P1 可选，M）
- 说明：将经过归属校验的真实预览错误作为新 REPAIR run 输入，不修改已经完成的 run。
- 依赖：T25、T24、T34。
- 主要文件：`B/run/RunController.java`、`B/run/RunAdmissionService.java`、`B/model/GenerationWorkflow.java`、`BT/run/RepairRunIT.java`。
- 验收：
  1. REPAIR 引用当前版本的 previewReportId，错误文本限长并作为不可信数据处理。
  2. 用户明确触发后新建一轮，沿用预算、幂等、取消和版本提交规则，不自动无限修复。
  3. 他人报告、旧基线、没有报告和失败修复均有明确结果，旧版不丢失。
- 验证：V-B；固定语法/运行错误报告、越权报告和晚到结果测试。

### T37 · 错误辅助修复前端

- [ ] 完成 T37（P1 可选，M）
- 说明：在错误面板中提供用户可控的修复入口和新版本反馈。
- 依赖：T36。
- 主要文件：`F/components/PreviewFrame.vue`、`F/stores/run.ts`、`e2e/repair.spec.ts`。
- 验收：
  1. 点击修复前展示将提交的真实错误摘要；无报告时不显示假可用按钮。
  2. 修复沿用真实阶段和取消体验，产生新版本后重新检查，而非直接标记 PASSED。
  3. 修复失败仍可查看原版本和错误，不形成自动扣费循环。
- 验证：V-F、修复 E2E、一次预算内真实修复；完成后重跑受影响的 T34 用例并更新 T35。

### C15 · 修复增强检查点

- [ ] 实现了 T36/T37 才展示修复入口；有真实错误与新版本证据，基础生成未回归。

### T38 · 访客升级为账号

- [ ] 完成 T38（P1 可选，M）
- 说明：增加邮箱密码注册，保留当前 userId，避免迁移项目所有权。
- 依赖：T05、T34。
- 主要文件：`R/db/migration/V010__accounts.sql`、`B/identity/RegistrationController.java`、`B/identity/AccountService.java`、`B/identity/SecurityConfig.java`、`BT/identity/RegistrationIT.java`。
- 验收：
  1. 注册验证输入和唯一邮箱，使用适当密码哈希，不存明文密码。
  2. 升级保留 userId 和所有项目，旋转会话标识并重新获取 CSRF。
  3. 重复注册、弱输入和并发相同邮箱安全失败；不承诺邮箱验证、找回密码或 OAuth。
- 验证：V-B；密码存储、会话旋转、并发唯一约束和原项目归属测试。

### T39 · 登录与退出

- [ ] 完成 T39（P1 可选，M）
- 说明：实现已有账号登录，明确访客与账号之间的身份边界。
- 依赖：T38。
- 主要文件：`B/identity/LoginController.java`、`B/identity/AccountService.java`、`B/identity/SessionController.java`、`B/identity/SecurityConfig.java`、`BT/identity/LoginIT.java`。
- 验收：
  1. 正确凭据登录后可读取原账号项目，错误凭据不泄露是否存在该邮箱，并限制暴力尝试。
  2. 登录旋转会话并更新 CSRF；退出使旧会话失效。
  3. 不自动把当前访客项目合并给已有账号，登录前提示这一行为。
- 验证：V-B；跨设备会话、退出后旧 Cookie、错误凭据和越权访问测试。

### T40 · 账号页面与切换提示

- [ ] 完成 T40（P1 可选，M）
- 说明：给已完成的账号接口添加可用页面，不只展示装饰性登录入口。
- 依赖：T39、T06。
- 主要文件：`F/pages/AccountPage.vue`、`F/api/session.ts`、`F/stores/session.ts`、`F/router.ts`、`e2e/account.spec.ts`。
- 验收：
  1. 注册/登录/退出可通过键盘完成，错误清楚且不丢失非敏感输入。
  2. 注册升级后原项目保留；登录已有账号前说明当前访客项目不会自动合并。
  3. 切换身份清理当前私有界面状态和旧 SSE，再加载新身份资源。
- 验证：V-F、账号 E2E；独立浏览器登录读取项目；重跑相关 T34 并更新 T35。

### C16 · 账号增强检查点

- [ ] 注册、登录、退出、会话旋转和归属测试全部通过才宣称支持跨设备登录；没有邮箱验证则明确说明。

### T41 · 导出源码 ZIP

- [ ] 完成 T41（P1 可选，M）
- 说明：导出当前选择版本的源码及使用边界，避免给出“脱离平台仍有数据库”的错误承诺。
- 依赖：T15、T34。
- 主要文件：`B/artifact/ExportController.java`、`B/artifact/ExportService.java`、`F/components/SourceViewer.vue`、`BT/artifact/ExportIT.java`。
- 验收：
  1. 仅所有者可导出指定版本；文件名白名单，无路径穿越，ZIP 中无平台密钥或真实业务数据。
  2. 导出包含源码和 manifest，以及平台 SDK/数据服务依赖说明；不宣称现成独立全栈项目。
  3. 导出失败有明确提示，原版本和项目数据不变化。
- 验证：V-B、V-F；检查 ZIP 条目、内容和权限；完成后更新交付功能清单。

### C17 · 可选能力收尾检查点

- [ ] 只公布经过验收的增强功能；完成任一增强后重新验证受影响的基础流程，更新最终代码标识和证据。
