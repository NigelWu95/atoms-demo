# 04 · 接口与协议

本文件是实施契约草案，不表示接口已部署。字段使用 camelCase，状态枚举使用 UPPER_SNAKE_CASE，ID 为不可推断的字符串，时间为 UTC ISO-8601。

## 1. 通用约定

- JSON 请求/响应，HTTP 状态码表达结果；不使用 HTTP 200 包装所有错误。
- 列表返回 `{ "items": [], "nextCursor": null }`；默认 limit=20，最大 100。
- Cookie 会话；所有状态变更请求使用 CSRF token。
- 创建项目、提交运行、恢复版本、提交预览报告使用 `Idempotency-Key`。状态保存和发布更新也带该键，避免未知结果后重复执行。
- 客户端在一次用户意图开始时生成键，网络重试复用；新的修改意图使用新键。
- 数据库唯一约束为 `(actorId, method, resource, key)`，同时保存规范化请求摘要。相同键不同内容返回 422；相同请求返回原结果。
- 长任务的重复提交返回同一 202/runId，查询运行状态判断是否完成。幂等记录保留至少整个评审使用期，不在短时间内过期重放。
- 项目级、版本级和运行级接口都检查项目归属，不能仅凭不可猜 ID 授权。

### 错误体

```json
{
  "error": {
    "code": "BASE_VERSION_CONFLICT",
    "message": "项目已更新，请刷新后再提交。",
    "retryable": false,
    "details": { "currentVersionId": "ver_2", "projectRevision": 2 }
  },
  "requestId": "req_abc"
}
```

| 状态码 | 典型 code |
|---|---|
| 400 | INVALID_JSON、INVALID_CURSOR |
| 401 | SESSION_REQUIRED |
| 403 | CSRF_REJECTED |
| 404 | PROJECT_NOT_FOUND、VERSION_NOT_FOUND、APP_NOT_PUBLISHED |
| 409 | RUN_ALREADY_ACTIVE、BASE_VERSION_CONFLICT、STATE_CONFLICT、STALE_APP_VERSION |
| 413 | PAYLOAD_TOO_LARGE |
| 422 | INVALID_ARTIFACT、INVALID_INPUT、IDEMPOTENCY_PAYLOAD_MISMATCH |
| 429 | GENERATION_LIMIT_REACHED |
| 503 | MODEL_UNAVAILABLE、SERVICE_UNAVAILABLE |

对于他人私有项目，返回 404，避免额外泄露其存在。不要将供应商密钥、内部堆栈或原始认证响应放入错误体。

## 2. REST 端点目录

### 2.1 身份、项目、生成

| 方法与路径 | 输入 | 输出/结果 |
|---|---|---|
| GET `/api/csrf` | 无 | `{token, headerName}`，初始化 CSRF 所需会话 |
| POST `/api/session` | `{}`，CSRF | `{userId, isGuest, displayName}`；已有身份则恢复 |
| GET `/api/me` | 会话 | 当前身份 |
| POST `/api/projects` | `{name}`，幂等键 | 201 Project |
| GET `/api/projects` | cursor、limit | Project 摘要列表，仅自己的 |
| GET `/api/projects/{id}` | 项目 ID | Project |
| PATCH `/api/projects/{id}` | `{name}` | 重命名后的 Project；纯赋值幂等 |
| GET `/api/generation` | 会话 | `{configured, workerEnabled, remainingRequests, model}` |
| GET `/api/projects/{id}/messages` | cursor、limit | Message 列表 |
| POST `/api/projects/{id}/runs` | CreateRun，幂等键 | 202 `{runId, status}`；客户端按 runId 构造事件地址 |
| GET `/api/projects/{id}/runs` | cursor、limit | Run 列表，新到旧 |
| GET `/api/runs/{runId}` | runId | Run：状态、阶段、结果版本、错误、lastEventSeq |
| GET `/api/runs/{runId}/events` | after，或 Last-Event-ID | 持久事件 SSE；读取终态全部事件后关闭，已补齐返回 204 |
| PUT `/api/runs/{runId}/cancellation` | `{}` | 200 Run，取消直接持久化终态 |

Project 最小字段：`id, name, ownerId, currentVersionId?, projectRevision, activeRunId?, publication?, updatedAt`。其中 projectRevision 表示源码当前指针的修订号，重命名不改变它。

本地身份与项目实现约束（2026-10-07）：

- 本表均已实现。Project 另返回 createdAt，始终返回 currentVersionId、activeRunId（没有当前版本/活动任务时为 null），发布字段未实现。历史已保存的幂等创建响应保持原样重放，可缺少后来新增的字段。
- name 去除首尾空白后为 1–80 个 UTF-16 单元，不允许控制字符；仅接受 name 字段。列表按 createdAt/id 倒序，cursor 为不透明游标，limit 为 1–100。
- Idempotency-Key 允许 1–128 个 ASCII 字母、数字、点、下划线、冒号或短横线；缺失/无效返回 422 INVALID_INPUT。名称规范化后同键重试返回第一次 201 的原始 JSON，包括首次名称与时间，不受后续重命名影响。
- 会话首次创建变更 session ID 和 CSRF，前端在 POST /api/session 后再取一次 GET /api/csrf。Cookie 为 HttpOnly、SameSite=Lax、7 天有效；只有本地 HTTP/test 配置允许 Secure=false。
- GET 私有端点无身份返回 401；写请求若同时缺少身份和 CSRF，安全过滤器会先返回 403。错误体不暴露私有项目是否存在。

Message：`id, projectId, runId?, role, content, createdAt`。role 为 USER、PLANNER、BUILDER、SYSTEM；角色名称与是否独立模型不混为一谈。

CreateRun：

```json
{
  "prompt": "增加优先级筛选，保留现有待办功能。",
  "baseVersionId": "ver_1",
  "expectedProjectRevision": 1,
  "intent": "MODIFY"
}
```

intent 为 CREATE、MODIFY，P1 可增加 REPAIR。CREATE 使用 null 基线和 revision=0；MODIFY 要求基线为当前版本。REPAIR 还带对应 previewReportId，服务器验证报告属于项目和基线。

取消 QUEUED/RUNNING run 直接持久化 CANCELLED 并清除活动标记；重复取消已取消任务返回当前状态。其他终态不能变为取消，返回 409 RUN_TERMINAL。结果处理与取消使用同一项目/run 锁，晚到结果无法提交。停止上游是尽力而为，已发请求仍可能计费。

本地 DeepSeek 切片实现约束：

- Run 返回 `id, projectId, ownerId, prompt, intent, baseVersionId, expectedProjectRevision, status, stage, resultVersionId, errorCode, errorMessage, lastEventSeq, createdAt, startedAt`。状态为 QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED/INTERRUPTED，执行阶段为 PLANNING/BUILDING/REPAIRING；没有思维链字段或伪造百分比。
- prompt strip 后为 1–8000 UTF-16 单元且不含 NUL；输入仅接受 CreateRun 四个字段，revision 为非负安全整数，基线为 null 或规范小写 UUID。归属、当前版本与单项目活动任务同时检查；同一幂等键在版本推进后仍返回原 202/runId。认证/密钥/预算校验不影响成功提交后的同键结果重放。
- 消息与任务均按数据库递增序号倒序分页，默认 20、最大 100，cursor 为不透明正整数字符串；非法 cursor 返回 400。GET run、分页、取消都验证当前访客是所有者，跨身份返回 404。
- GET generation 只返回是否配置、Worker 开关、剩余累计请求数及固定模型名；不验证 Key 真实有效性、不返回 Key。配置与预算生效需重启后端。默认预算 0；每轮开始至少需要 2 次余量，每次出网前再次原子预留，排队期间余量可能变化，耗尽时明确失败。
- 当前固定官方 DeepSeek 端点和 deepseek-flash，不接受用户自定义 URL。thinking enabled/high、stream=false，JSON 对象模式。平台请求上限见 README；服务端记录实际可得 usage，未知值为 null，不将其伪造为 0。未知请求预留不退回。
- 活动任务订阅持久事件 SSE；前端从任务快照的 lastEventSeq 继续接收，补齐终态后再读取消息和项目版本。任务执行不依赖网页连接。消息通过 Vue 文本转义，不把模型输出作为平台页面 HTML 渲染；产物另经校验和受限运行文档展示。

### 2.2 版本、预览和发布

| 方法与路径 | 输入 | 输出/结果 |
|---|---|---|
| GET `/api/projects/{id}/versions` | cursor、limit | 版本元信息列表 |
| GET `/api/projects/{id}/versions/{versionId}` | 路径 | Version + Artifact |
| GET `/api/projects/{id}/versions/{versionId}/document` | previewMode=CURRENT/HISTORY | 带强制响应级沙箱的 HTML，需项目权限 |
| POST `/api/projects/{id}/versions/{versionId}/preview-reports` | PreviewReport，幂等键 | 201 `{reportId, status}` |
| POST `/api/projects/{id}/restorations` | `{sourceVersionId, expectedProjectRevision}` | P1，201 新版本 |
| GET `/api/projects/{id}/publication` | 项目权限 | `{slug, versionId, status, revision}` 或未发布状态 |
| PUT `/api/projects/{id}/publication` | PublishRequest，幂等键 | 200 Publication + publicUrl |
| DELETE `/api/projects/{id}/publication` | 项目权限 | 204，下线；重复请求仍成功 |
| GET `/api/public/apps/{slug}` | slug | 仅公开版本元信息和初始配置 |
| GET `/api/public/apps/{slug}/document` | versionId | 当前已发布版本的受限运行 HTML |

运行 HTML 不经过普通 SPA fallback。响应包含正确 MIME、no-store，以及 HTTP `Content-Security-Policy` 的 sandbox 与资源限制。直接打开 document URL 仍受沙箱约束；独立正常体验应打开可信 `/preview/...` 或 `/a/...` 外壳。

本地 T10–T15 实现约束（2026-10-07）：

- 已实现本表前三个 GET；其余仍是后续设计。版本写入只开放内部事务服务，没有生产 POST 源码接口。
- 版本摘要字段为 `id, projectId, versionNo, contentHash, basedOnId, createdAt`；详情在相同字段之外返回 `artifact`。UUID 为小写标准格式；版本列表按 versionNo 倒序，cursor 是上一页最后版本号的十进制字符串（客户端仍按不透明游标传回）；limit=1–100，默认 20。
- 内部保存同时验证项目 revision 和基线版本，冲突为 409 BASE_VERSION_CONFLICT；成功后 projectRevision+1。重复内容 hash 允许产生新版本，源码不可修改或删除。
- document 的 previewMode 默认 CURRENT，非 CURRENT/HISTORY 返回 422 INVALID_PREVIEW_MODE；旧版本 CURRENT 返回 409 STALE_APP_VERSION。HISTORY 的 SDK get 只返回该版本 initialState/revision=0，save 返回 READ_ONLY_PREVIEW，不调用持久状态接口。
- HTML 使用解析白名单：基本布局/文本、表格、交互控件、受限 SVG；不含 URL 属性、form、事件属性或嵌套框架。源码文件合计 120 KiB UTF-8；初始数据对象 256 KiB JSON UTF-8；不支持内容返回 422 INVALID_ARTIFACT。宿主已根据 SDK ready/error 提供真实启动反馈；完整静态经典脚本语法检查与服务端预览报告仍未实现。
- 运行文档才有 inline-only 脚本/CSS 和 HTTP sandbox；管理 API 仍禁止嵌入。具体取舍见 [ADR-004](../decisions/004-immutable-artifacts-runtime.md)。
- 固定测试装载 POST `/api/test-fixtures` 仅在 test-run 专用入口存在，接收 `{fixture: "counter"|"closing-tags"|"security"|"history"|"todo"|"todo-history"}`，必须具有访客会话、CSRF 和 Idempotency-Key；返回 `{project, version, firstVersionId, testOnly: true}`。只连接 atoms_demo_test/atoms_test，测试类和页面不进入正式 JAR。待办通过 5174 的测试工作台使用消息桥，5173 仍连接正常开发库。

CURRENT 模式要求 versionId 等于项目当前版本；历史版本只允许 HISTORY 模式。后端状态接口也校验这个条件，不能通过伪造 previewMode 绕过历史只读限制。恢复版本和发布时均拒绝同项目仍有活动 run 的操作。

公开 document 请求携带版本是为保持元信息与代码一致；若发布指针已变，返回 STALE_APP_VERSION，宿主重新加载元信息。历史公开版本不通过此接口任意访问。

PreviewReport：

```json
{
  "status": "PASSED",
  "checks": ["SCRIPT_PARSE", "SDK_STARTUP"],
  "errors": [],
  "viewport": { "width": 1440, "height": 900 }
}
```

报告属于浏览器反馈，可被用户伪造，只用于体验反馈与问题定位，不作为授权或恶意代码安全证明。限制错误数组长度和每条文本长度；用户行为不经过模型自动授权。

PublishRequest：

```json
{
  "versionId": "ver_2",
  "expectedProjectRevision": 2,
  "expectedPublicationRevision": 0
}
```

首版只发布当前版本。若有活动运行、版本变更或未完成预览检查，返回可解释的 409；用户回到预览完成检查再发布。事务固定 versionId，不在发布过程中重新查询“最新”而偷偷切换目标。下线只改状态，不删除版本和数据。

### 2.3 生成应用数据

| 方法与路径 | 范围 | 输出 |
|---|---|---|
| GET `/api/projects/{id}/preview-state?versionId=...` | 仅所有者的 PREVIEW 状态 | StateDocument |
| PUT `/api/projects/{id}/preview-state` | 当前可写预览版本 | StateDocument |
| GET `/api/public/apps/{slug}/state?versionId=...` | 当前访问者的 LIVE 状态 | StateDocument |
| PUT `/api/public/apps/{slug}/state` | 当前访问者的 LIVE 状态 | StateDocument |

这几个请求只能由可信外壳发出；实际访问仍由服务端身份与发布状态校验，不能把“来自外壳”当作服务器鉴权条件。公开 GET 元信息与 HTML 不需要登录；读写个人状态先建立访客会话。

StateDocument：

```json
{
  "value": { "todos": [{ "id": "t1", "title": "准备演示", "done": false }] },
  "revision": 3,
  "stateSchemaVersion": 1
}
```

保存输入：

```json
{
  "loadedVersionId": "ver_2",
  "expectedRevision": 3,
  "stateSchemaVersion": 1,
  "value": { "todos": [{ "id": "t1", "title": "准备演示", "done": true }] }
}
```

后端从身份确定 actorId、从路径确定项目与环境；请求不接收 ownerId 或任意数据库路径。保存成功返回 revision=4。同一保存意图因网络未知结果重试时复用幂等键；未使用幂等键的旧 revision 重试可能得到 409，必须重新读取，不能盲目覆盖。

本地 T13 实现：已实现 PREVIEW 的 GET/PUT；LIVE 尚未实现。仅接受上述四个保存字段，value 必须是序列化后不超过 256 KiB UTF-8 的 JSON 对象，schema 固定为整数 1，expectedRevision 为非负安全整数且小于 9007199254740991。所有者检查与代码指针变更共用项目行锁；不存在状态时只读返回 initialState/revision=0，首次写入用唯一约束，后续写入用修订号条件更新。新意图的旧代码返回 409 STALE_APP_VERSION，状态竞争返回 409 STATE_CONFLICT。已经提交的同键同体意图在重新鉴权后重放原结果（即使代码指针或状态修订号已推进），不再执行保存；同键异体返回 422。代码升级不会重置已有应用状态。

### 2.4 P1 账号

可增加 POST `/api/registrations`、POST `/api/login` 和 DELETE `/api/session`。注册将当前访客升级，保留 userId；登录已有账号旋转会话 ID，并重新获取 CSRF token。登录不会把当前访客项目自动合并到已有账号，避免隐式转移所有权。

## 3. 模型输出契约

### Planner 输出

```json
{
  "title": "个人任务板",
  "goal": "记录并筛选个人任务",
  "features": ["添加任务", "完成任务", "按状态筛选"],
  "preserve": ["既有任务数据"],
  "dataNeeded": true,
  "steps": ["生成布局", "实现交互", "连接状态存储"]
}
```

禁止将模型的 steps 直接当作执行过的日志；执行日志由 Worker 的真实动作产生。

### Builder 输出 Artifact

```json
{
  "formatVersion": 1,
  "manifest": {
    "title": "个人任务板",
    "summary": "新增优先级筛选，保留原有功能。",
    "runtime": "web-static-v1",
    "stateSchemaVersion": 1,
    "initialState": { "todos": [] }
  },
  "files": {
    "view.html": "<main id=\"app\"><h1>个人任务板</h1><div id=\"tasks\"></div></main>",
    "styles.css": "body { margin: 0; font-family: system-ui; }",
    "main.js": "Atoms.ready(async (sdk) => { const state = await sdk.storage.get(); document.querySelector('#tasks').textContent = String(state.value.todos.length); });"
  }
}
```

上例仅展示协议结构，不是满足完整待办业务要求的应用。源码视图中的 manifest.json 由 manifest 对象格式化产生，避免模型重复返回两份不一致的 manifest。

校验规则：

- formatVersion/runtime 必须为支持值；三个源码键恰好匹配白名单。
- view.html 为允许的 body 片段，不含 script、iframe、base、meta、object、内联事件或外部提交目标。
- main.js 为经典脚本，通过 Atoms.ready 调用异步 SDK；禁止依赖 import、require、任意外部库。
- CSS 与全部文本限制大小；危险或不支持内容报错，不静默改成另一套程序。
- initialState 为 JSON 对象，最大 256 KiB；首次读无记录可初始化，修改版本不强制覆盖真实数据。
- 没有模型密钥、数据库凭据、平台内部 token 或绝对服务器路径。

产物内容是待运行的不可信代码；输出符合 JSON Schema 不等于代码安全或业务正确。

## 4. SSE 事件协议

本轮实现契约（T20）：`GET /api/runs/{runId}/events?after=0` 使用现有访客会话，先校验归属再校验游标；`Last-Event-ID` 非空时优先于 `after`，两者均为规范的非负十进制安全整数，不得超过当前 lastEventSeq。错误在建立流前返回既有 JSON 错误结构；无权 404、游标无效 400。事件按 seq 升序发送，历史与尾随统一读取数据库已提交记录，不触发模型请求。终态 payload 含 lastEventSeq，终态已全部读取时返回 204，禁止浏览器无意义重连。

每帧 data 为现有事件数据加 `runId, seq, createdAt`；终态额外含 `lastEventSeq`。当前实际事件是 run.queued、stage.started、plan.ready、artifact.created、run.completed；下表的其他类型仍是预留，不伪造模型 token 进度。每批最多 100 条、尾随间隔 500ms、心跳 15 秒、不缓存/请求代理禁用缓冲；单连接最多 60 秒后正常轮换，浏览器携带已读游标重连。独立的 4 线程 SSE 调度池最多接纳 32 个连接，满时 429；连接/任务生命周期互不影响。

前端从 Run 快照的 lastEventSeq 开始订阅，按 seq 去重并拒绝跳号、跨任务或畸形事件；阶段实时更新，规划完成刷新持久对话，收到完整终态后再读取任务/版本/消息并关闭流。连续三次连接失败暂停自动重连，保留旧预览并提供“刷新生成状态”；收到新事件或连接已稳定至少 15 秒才重置失败计数，避免快速连上/断开无限循环。项目切换和卸载清理旧连接。当前不清理历史事件；如发现数据库回放缺口，明确报错，不能冒充完整回放。

```text
id: 12
event: artifact.created
data: {"runId":"run_1","seq":12,"versionId":"ver_2","versionNo":2,"createdAt":"2026-10-07T08:00:00Z"}

id: 13
event: run.completed
data: {"runId":"run_1","seq":13,"status":"SUCCEEDED","versionId":"ver_2","lastEventSeq":13,"createdAt":"2026-10-07T08:00:01Z"}

```

| event | 关键字段 | 产生条件 |
|---|---|---|
| run.queued | runId、seq | 任务已持久化 |
| stage.started | stage、attempt | 进入真实执行阶段 |
| plan.ready | plan | Planner 输出已解析 |
| model.progress | stage、receivedChars | 实际收到模型内容，可选聚合展示 |
| validation.finished | passed、issues | 契约校验完成 |
| repair.started | reason、attempt | 开始唯一一次修正 |
| artifact.created | versionId、versionNo | 版本事务已提交 |
| run.completed | status、versionId?、error? | SUCCEEDED/FAILED/CANCELLED/INTERRUPTED |

`model.progress` 不包含内部思维链。心跳为 SSE 注释行，不占数据库 seq。重连读取 seq > cursor；run 状态中的 lastEventSeq 用于确认结果是否补齐。历史事件超出保留期时返回明确错误并让前端重新取快照，不能假装已完整回放。

## 5. 运行 SDK 与消息桥

生成代码使用：

```typescript
interface AppState<T extends Record<string, unknown>> {
  value: T;
  revision: number;
  stateSchemaVersion: 1;
}

interface AppStorage {
  get<T extends Record<string, unknown>>(): Promise<AppState<T>>;
  save<T extends Record<string, unknown>>(
    value: T,
    expectedRevision: number
  ): Promise<AppState<T>>;
}

interface AtomsRuntime {
  storage: AppStorage;
}
```

Atoms.ready 等待宿主桥接完成后调用回调，并捕获回调异常。SDK 不允许指定 projectId、environment、actorId、HTTP URL、header 或 SQL。宿主将当前绑定上下文映射到具体 API。

桥请求：

```json
{
  "protocol": "atoms-runtime-v1",
  "channelId": "7f9c39c2-b9ec-4f1e-9981-a1259bec0eb2",
  "requestId": "72c8fca0-1207-4575-b235-b2d1209b5294",
  "type": "STATE_SAVE",
  "payload": {
    "expectedRevision": 3,
    "value": { "todos": [] }
  }
}
```

允许的 type 为 STATE_GET、STATE_SAVE、RUNTIME_ERROR、RUNTIME_READY。响应包含 requestId、ok 和 result 或统一错误；每个请求有超时，切换版本时拒绝所有未完成请求并关闭通道。

本地 T12–T15 已实现：每次创建 iframe 前生成 UUID channelId 并放入 URL fragment。此模式下运行文档在 INIT → HELLO → CONNECT 完成前不注入应用 CSS/JS。bootstrap 检查 parent/source 与 URL origin；宿主检查 iframe.contentWindow、channel 和消息结构，一次性转移 MessagePort，不以 opaque/null origin 授权。后续导航或销毁关闭旧通道。启动期限 10 秒，SDK RPC 期限 15 秒，最多 16 个并发数据请求；RUNTIME_READY/ERROR 不被请求队列上限丢弃。

channelId 和 requestId 均要求标准 UUID。宿主把每次 STATE_SAVE 映射为 `channelId:requestId` 固定幂等键（73 字符），HTTP 重试继续复用。SDK 保存不可变 JSON 快照；网络中断、超时或异常成功响应均保留原意图，只允许相同内容重试。STATE_GET 不会撤销可能已提交的保存；确定的业务拒绝才允许更正输入。队列满而尚未发送时不建立未知意图。结构有效但状态无效返回 INVALID_INPUT，伪造 source/channel 或未知 type 忽略。桥消息不能触发生成、恢复、发布或读取平台项目。实现取舍见 [ADR-005](../decisions/005-preview-state-bridge.md)。

历史预览 mode=HISTORY 时，SDK get 返回该版本 initialState，save 返回 READ_ONLY_PREVIEW；不访问当前项目持久状态。加载只读模式由宿主与服务器确定，不接收模型指令覆盖。

## 6. 契约验收重点

- 相同生成键重试只创建一个 run；相同键不同 prompt 返回 422。
- A 用户无法读取 B 的 projects、versions、runs 或 events。
- 两个 state 写入同时基于 revision=3，只允许一个成功。
- 旧 channelId、旧 iframe、未知消息 type 均被拒绝。
- currentVersionId 变化不自动更改 publication.versionId。
- 已下线 app 的 state 与 document 请求也被拒绝；已加载到客户端的页面不承诺远程擦除。
- 直接打开 runtime document 仍有 HTTP CSP sandbox；管理页不会意外使用运行文档的宽松脚本策略。
