# Spec: project-delivery · 实际运行预览与同快照源码交付

状态：2026-10-27，用户已确认本规格，进入实施；尚未完成验收。实施顺序与进度见 [计划](tasks/project-delivery/plan.md)和[任务清单](tasks/project-delivery/todo.md)。本模块沿用[能力地图](FULL-PROJECT-CAPABILITY-MAP.md)的 `project-delivery`，依赖已验收的 `project-source` 和 `project-execution`。整体连续开发授权见 [Agent 规格 §10](SPEC-project-agent.md#10-2026-10-19-完整闭环实施授权)。旧网页、固定模板预览、下载与发布继续按原规则运行。

## 1. 目标与可见结果

让指定完整 Vue + Spring Boot + SQL 版本真正通过其运行进程提供页面和业务 API，并能下载对应的不可变源码。预览、下载、源码恢复各有明确身份，不能因选择了另一个版本而静默显示旧应用或下载新模板。

用户流程：选择已就绪的运行 → 获得私有预览 → 在真实 Vue 页面新增/修改/查询业务数据 → 下载当前预览源码 → 在另一目录配置独立数据库并构建启动 → 验证相同业务行为。恢复历史源码只追加源码版本，后续构建、迁移批准与切换运行仍是明确操作。

本模块交付后端 API、预览传输及可被工作台消费的身份契约。完整文件树、Agent 对话、构建/启动按钮编排和页面布局由 `project-workbench` 接入；本模块验收页面仅在测试入口使用，不据此宣称整个平台已切换到新模式。

## 2. 范围与主要取舍

| 项目 | 本次决定 |
|---|---|
| 部署形态 | 复用平台 HTTP 服务和执行模块已启动的本机 Java 进程，不增加域名、代理进程或预览监听端口 |
| 预览路由 | 平台受控路径代理；返回真实应用字节，浏览器执行时强制不透明来源沙箱 |
| 身份 | 固定 owner / project / sourceVersion / tree / build / JAR / manifest；运行再绑定 run / execution |
| 授权 | 平台会话创建短期预览授权；后续资源请求用随机能力令牌，持续核对原会话与准确运行 |
| 下载 | 从构建对应的 Git 快照打 ZIP，不读可变工作目录、不重新拼装当前模板、不下载 JAR |
| 恢复 | 复用源码模块的 RESTORE、revision 和幂等机制；不移动旧历史，不修改业务数据 |
| 数据与依赖 | 不新增平台依赖或数据库表；预览授权只在内存存在，项目/源码/运行事实仍使用已有持久化 |
| 开放对象 | 默认关闭，首期仍限执行模块已授权的受信维护者及其拥有的项目 |

预览首版支持已验收模板的相对 URL、ES modules、静态资源和 JSON CRUD API，包括业务 4xx 冲突。源码使用 `./api/...`、相对资源路径，Vite 的 `base: './'` 保留；已有种子和待办样本符合此约定。不在代理层改写 JS/CSS/HTML 来掩盖绝对路径错误。

应用自带 Cookie 登录、localStorage/IndexedDB、Service Worker、WebSocket/SSE、大文件上传下载、外站资源、任意反向代理和 history 路由回退不在首版预览契约内。相应工程仍可作为源码下载，但不能声称这些行为已与平台预览一致；不兼容必须显示原因，不能放开同源执行兜底。公开发布、自动申请数据库、真实模型调用和远程部署不属于本模块。

## 3. 已有代码与需要补齐的边界

| 现有实现 | 复用方式 / 缺口 |
|---|---|
| [ProjectExecutionAccess](backend/src/main/java/dev/atoms/demo/projectexecution/ProjectExecutionAccess.java) | 已有 build/running 句柄、身份及不可变源码读取，每次检查归属、产物与原拥有者；尚无 HTTP 交换入口 |
| [ProjectSourceReadGateway](backend/src/main/java/dev/atoms/demo/source/ProjectSourceReadGateway.java) | 读取固定版本完整树；不能用 latest 或任意 Git 对象代替 |
| [SourceRestoration](backend/src/main/java/dev/atoms/demo/source/SourceRestoration.java) | 已有恢复操作与 CAS/幂等规则；需由源码模块提供窄公共门面，不复制仓库写入逻辑 |
| [SessionAccess](backend/src/main/java/dev/atoms/demo/identity/SessionAccess.java) | 使用 streamValid 复核原会话，不因预览访问无限延长平台登录 |
| [RuntimeDocumentPolicy](backend/src/main/java/dev/atoms/demo/preview/RuntimeDocumentPolicy.java) | 原文档只支持内联脚本和 SDK 桥接，禁止网络，不能直接用于 Vue ES modules；旧策略保留 |
| [EngineeringZip](backend/src/main/java/dev/atoms/demo/engineering/EngineeringZip.java) | 参考其固定顺序、STORED 和有界写入，不复用旧模板文件白名单或 9 MiB 上限 |
| [build.mjs](templates/full-project-v1/scripts/build.mjs) / [TodoControllerTest](backend/src/test/resources/projectexecution/todo/TodoControllerTest.java) | 当前脚本清理环境后没有测试库配置入口；样本测试只接受本机 A/B 验收库名称。现有内部 worker 注入测试配置的通过结果不能替代下载脚本验收 |

新增 `projectdelivery` 包拥有授权、预览 HTTP 边界、下载与只读清单。执行模块提供受限 HTTP 交换门面：接受其签发的运行句柄和受约束请求，内部解析准确 loopback 目标，返回有界状态/头/字节。不得公开可由客户端重建的端口、URL、PID、私有路径或停止证明；交付模块不直接查询绑定表拼 URL。

HTTP 交换共享一个总期限，动作前后重新校验授权、原进程及固定产物。已有交付句柄校验不能被缓存的 READY 状态替代。需要复用源码层的剩余时间重载，不能把多个 30 秒操作叠加为无界请求。先在真实 Chrome 验证这一传输能运行现有 Vue/Java 样本，再扩展完整交付 API。

## 4. API 契约草案

以下是待实现路径，不是当前可调用接口。管理 API 沿用平台身份和 CSRF；projectId 及各 ID 为规范 UUID。每次检查开关、维护者资格、项目归属及归档状态。

| 方法与路径 | 请求 / 结果 |
|---|---|
| GET `/api/projects/{projectId}/delivery/builds/{buildId}` | 返回固定构建身份、源码文件数量/大小、下载能力；不接受路径或客户端摘要作为权限依据 |
| GET `/api/projects/{projectId}/delivery/builds/{buildId}/source.zip` | 下载已成功构建的固定源码，可在应用已停止时使用 |
| POST `/api/projects/{projectId}/delivery/runs/{runId}/previews` | CSRF；无目标 URL 参数。返回 previewId、expiresAt、iframeUrl 和完整运行/构建身份 |
| DELETE `/api/projects/{projectId}/delivery/previews/{previewId}` | CSRF；幂等撤销自己的授权，不停止运行进程 |
| GET `/api/projects/{projectId}/delivery/previews/{previewId}/source.zip` | 必须仍是有效的当前预览；按其确切运行取快照，失效则报错，不转为“最新源码”下载 |
| GET `/api/projects/{projectId}/delivery/source-head` | 返回当前 sourceVersionId 与 revision，供恢复前确认 |
| POST `/api/projects/{projectId}/delivery/restorations` | CSRF + Idempotency-Key；body 为 targetSourceVersionId、expectedSourceVersionId、expectedRevision；返回 202 和源码 operationId |
| GET `/api/projects/{projectId}/delivery/operations/{operationId}` | 返回本项目源码操作的白名单状态和结果版本，不包含内部路径或异常原文 |

统一身份 DTO 为 projectId、sourceVersionId、treeSha256、buildId、jarSha256、manifestSha256；运行身份另含 runId、executionId。新增 DTO 沿用 Java record、规范 UUID 与固定错误码，例如：

```java
public record PreviewView(UUID id, Instant expiresAt, String iframeUrl,
        ProjectExecutionAccess.RunningIdentity identity) {}
```

源码恢复门面使用已有 SourceRestoration / SourceCoordinator，不新增一套恢复队列或操作状态。相同幂等键和请求返回同一操作，冲突 revision 返回 409；活动编辑/准备/构建按原互斥规则拒绝。恢复不刷新或关闭当前预览，也不自动构建/切换进程；工作台应同时显示“源码版本”和“正在运行的版本”。

## 5. 预览传输与浏览器边界

### 5.1 短期授权

每次成功签发生成 256-bit 随机令牌，iframe 路径形如 `/api/delivery/preview/{token}/`。内存只保存令牌摘要、管理用 previewId、会话 ID、所有者、完整身份、原运行句柄及单调时钟到期点；不把凭据或私有配置放进 URL。TTL 固定 10 分钟，不因资源请求续期；总数最多 32，每个会话最多 8，达到限额返回 429，不驱逐正在使用的其他项目。

令牌只在签发响应出现，列表/诊断/日志不重现原值；previewId 不能反推出令牌，也不能直接作为预览凭据。URL 是短期 bearer 授权，知道 URL 的人可在有效期内操作该应用，不应当作分享链接。响应 no-store / no-referrer；页面不持久化令牌。日志只记 previewId 和枚举事件，访问日志/异常日志不得记录完整令牌路径，未来部署反代须同步该规则。

每次文档、静态资源、API 和预检请求均复核原会话、账号撤销、owner、开关、过期时间和确切运行。退出/切账号、撤销、过期、归档、停止、换版或协调器失权后，旧授权不得指向新进程。平台重启丢弃授权，不能凭数据库 READY 行重建句柄。授权过期由管理页面明确提示重新打开，不能自动重放写请求或悄悄刷新导致输入丢失。

### 5.2 不透明来源与跨域资源

管理页面必须用 `sandbox="allow-scripts allow-forms"`、`referrerpolicy="no-referrer"` 挂载；所有已授权预览响应另附相同服务端 CSP sandbox，防止直接打开文档时绕过 iframe 属性。**不加入 allow-same-origin**，不向应用转交平台 Cookie、CSRF、Authorization、邮箱或账号信息。[浏览器 sandbox 语义](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Content-Security-Policy/sandbox)指出，这种文档来源为不透明来源，Origin 为 `null`，不能访问 document.cookie/localStorage。

2026-10-27 实际 Chrome 兼容性修正：仅 allow-scripts 会同时阻止 Vue `@submit.prevent` 的 submit 事件，真实页面无法添加待办。增加 allow-forms 使 JS 能处理表单事件，但服务端继续设置 `form-action 'none'` 禁止原生表单请求/导航；JSON 写入仍限原有 token/api 路径。独立 Chrome 反例已确认事件由不触发变为触发，原生 POST 计数仍为零。此调整不引入 same-origin、平台凭据或额外网络目标；真实应用复验结果单独记录。

由此带来的模块脚本和 API CORS 仅在有效令牌的精确预览路径处理：允许 Origin `null`，不返回 Allow-Credentials，也不将 `null` 本身作为认证。管理 API 不增加此 CORS 许可。新路径仍位于现有 `/api` 转发范围，复用 Vite/部署代理规则；服务端仅为精确预览路径配置无会话写入的过滤链，不全局 permitAll 或关闭 CSRF。OPTIONS 不转发给业务进程，只在授权有效、路径/方法/请求头受支持时返回短期预检结果。复用 [CORS 的无凭据模式](https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/CORS)，不依赖第三方 Cookie。

平台配置明确的管理 origin，禁止从未验证的 Host/X-Forwarded-Host 拼 CSP。脚本/样式/字体/图片只允许该令牌的 `assets/` 路径，图片可额外使用 data:；样式允许 Vue 所需的内联样式，脚本不允许 unsafe-inline/eval。connect-src 只允许该令牌的 `api/` 路径；base-uri、object-src、worker-src、frame-src、form-action 均为 none，frame-ancestors 限平台 origin。拒绝上游 Set-Cookie、Location、Refresh、Link、CORS/CSP/XFO 等头覆盖这些规则；管理页面的响应和嵌套 iframe 导航也必须保留原保护。

不能只把应用搬到另一个端口就声称隔离平台 Cookie：[RFC 6265 §8.5](https://httpwg.org/specs/rfc6265.html)明确 Cookie 不按端口隔离。本方案的边界来自无平台凭据转发、服务端沙箱与授权校验，不来自“不同 path/端口天然安全”。这也不改变普通本机进程不是操作系统安全沙箱的既有结论。

### 5.3 受限 HTTP 交换

- 只接受授权根的 `/`、`index.html`、`assets/…`、`api/…`。上游为执行模块原拥有者确认的 `127.0.0.1:固定绑定端口`；删除授权前缀后保持请求语义，不使用用户提供的 host/scheme/port。管理路径、内部 Actuator、文件路径和其他路由不作为通用代理开放。
- 严格解析原始路径一次，拒绝点段、反斜杠、编码斜杠/二次编码、重复分隔符、控制字符和 URL 用户信息；查询串独立校验，最长 2 KiB，不参与目标主机拼接。
- 静态资源只允许 GET/HEAD；业务支持 GET/HEAD/POST/PUT/PATCH/DELETE，写请求只接受 UTF-8 JSON。允许请求头仅 Accept、Content-Type、If-Match、If-None-Match、Idempotency-Key，并约束长度。Cookie、Authorization、Origin、Referer、Forwarded/X-Forwarded-*、Host 与 hop-by-hop 头均不原样转发。
- 连接上限 2 秒；整个授权/传输/复核上限 30 秒；JSON 请求/响应各最多 1 MiB，静态响应最多 4 MiB；全局最多 16 个在途交换、每授权最多 4 个，无无界排队。HEAD 及 204/304 按无正文语义处理。
- 使用 JDK [HttpClient](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpClient.html)，显式禁用代理、认证、CookieHandler 和重定向，指定 HTTP/1.1；不读代理环境或平台请求凭据。增量计数限制响应字节，超限/期限到达/取消时中断读取并释放容量，不先收完整正文再检查。
- 正文完成并复核准确运行后才返回字节；只保留受支持的 Content-Type 和受限 ETag，长度由实际字节计算，no-store。只支持明确的 HTML/CSS/JS/JSON 和少量图片/字体 MIME；不将未知类型猜成 HTML，不执行任意内容嗅探，不转发内容编码或下载响应。3xx 一律拒绝，不跟随也不改写 Location。
- 逐请求校验目标封存目录、文件身份/权限和完整 JAR/清单字节，同时读取原不可变源码；不在只读访问中反复遍历其他构建的依赖和输出目录。全执行根的容量检查保留在资源创建、产物打开/封存、构建和运行准入路径。此区分不使用摘要缓存，也不省略返回前的身份复核。
- 应用返回的预期 JSON 4xx（例如 409）保留状态和业务正文；基础设施故障使用平台固定错误码。请求已发出后写结果不确定时返回 DELIVERY_WRITE_UNCERTAIN，明确要求查询确认，不自动重试。前后复核不能撤销已经发生的业务写入。

## 6. 同快照 ZIP 与恢复后的含义

ZIP 中 `project/` 是完整 Git 源码树：路径、文件字节和 100644/100755 模式与所选快照一致。`delivery/manifest.json`、`delivery/README.md` 位于源码目录外，避免污染源树或覆盖项目文件。清单包含格式版本、构建身份、逐文件大小/摘要/模式、源码树摘要；预览下载再记录 run/execution 身份。包的源身份可独立验证，不将打包时间或平台临时目录当作来源。

不使用当前模板替换旧源码，不附加业务库数据、平台账号/对话、`.git`、node_modules、target、JAR、Agent 会话或真实运行配置。源码本身经过既有源码策略；导出不声称能自动识别用户代码中任意手写秘密，也不把“脱敏”作为偷偷修改源文件的理由。允许模板已有的占位配置示例。

采用固定顺序、固定时间、有界 classic ZIP，保留 Unix 可执行模式；以 [JDK ZIP API](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/zip/ZipOutputStream.html)和 [ZIP 格式规范](https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT)验证自身输出，不引入外部 zip 命令或通用解压服务。最高沿用 512 文件/单文件 2 MiB/整树 16 MiB，ZIP 总量不超过 20 MiB，附加交付元数据最多 512 KiB；最多 2 个并发下载、同一会话最多 1 个，整次生成 30 秒。

下载先固定身份、取不可变树、写入有界内存，最后复核权限/产物/运行，再返回 attachment；取消或校验失败不返回“成功 ZIP”。执行过完整字节传输之后无法撤回客户端下载的文件，不宣称覆盖这一竞态。无长期下载缓存或新磁盘事实副本。

新目录构建读取 `project/` 自带锁文件及工具要求，由使用者提供新的私有数据库配置。预览数据库和下载后的数据库分别持久化；相同行为不等于相同现有数据。重新构建的 JAR 不承诺与平台 JAR 二进制一致，承诺的是相同源码/锁文件身份、所记录工具约束及已验收业务行为。

**可独立构建的前置修正：** 为未来工程的构建脚本增加显式 `--test-config /absolute/private/test.properties` 入口，仅把配置路径交给 Maven/Java 测试，不放进 npm 环境、源码、参数中的密码值或前端包。Java 测试侧读取独立私有文件并核对 test 用途、真实数据库/角色/schema、非超级用户和测试前空 schema，数据修改事务回滚；拒绝使用已有业务库。不再把本机两组固定名称当成项目通用测试接口。无 DB 的初始化 MVC 测试仍可无此参数执行，不允许用 skipTests 代替配置。

此修改须进入模板/受控样本的正常新源码版本并重新构建预览；需同步模板目录清单和打包一致性验证。旧源码和旧 ZIP 保持原样，可明确说明旧版构建限制；禁止在导出阶段临时加脚本、改测试或删除失败测试。独立下载验收必须走下载工程自己的构建入口，不能偷偷用平台 worker 或平台类路径完成打包。

## 7. 配置、代码与错误约定

新增 `PROJECT_DELIVERY_ENABLED=false` 与启用时必填的 `PROJECT_DELIVERY_ORIGIN`。origin 仅含 scheme/host/port，不含凭据、path/query/fragment；支持当前本地 HTTP，HTTPS 页面不能降为 HTTP 预览。它表示浏览器看到的平台 origin（开发时可为 Vite origin），不是上游应用地址。禁用时不签发授权、不打开应用连接、不启动执行资源；旧功能完全按原开关工作。

目录：`backend/src/main/java/dev/atoms/demo/projectdelivery/`、对应 `src/test/java`；仅在 `projectexecution` / `source` 增加必要窄门面；模板与待办样本的可独立构建前置修改按 §6 纳入新版本。前端消费者类型/实际工作台集成归后续模块。维护说明和证据分别为 `docs/project-delivery.md` / `docs/project-delivery-verification.md`。未来计划使用 `tasks/project-delivery/`，不覆盖原任务清单。保持 Java 17、现有 Spring Boot 3.5.16、Vue/Node 工具版本，不新增依赖、DB 迁移、全局设置或子代理。

错误沿用现有 ApiError 形状，但预览路由不回显含令牌的 request URI：401 会话失效；403 非维护者；404 非归属项目/无效预览；409 归档、版本冲突或运行身份失效；413 超限；422 输入或内容不支持；429 容量已满；502 上游响应不符合契约；503 开关/执行资源不可用；504 期限到达。已派发写请求遇不确定结果使用固定 DELIVERY_WRITE_UNCERTAIN，不泄露下层异常。日志仅记录 requestId、owner/project/task 等必要标识、阶段、错误码、字节数和耗时，不记录令牌、请求/响应正文、源码或凭据。

## 8. 验证方法与命令

按风险先验证真实 Vue 模块加载与 JSON 请求在不透明来源下可用，再实现完整授权/下载/恢复。测试层级必须区分：纯策略单测、HTTP/PG 集成、真实运行+Chrome、下载目录中的真实工程构建/运行，不能用假上游或静态页面替代后两者。下面是规划命令，尚未运行，测试类/入口在实现时创建。

```sh
# 仓库根目录；复用已批准 runner / JDK / Node / 隔离测试 schema
sh .local/run-ex22-checks.sh ex22-delivery-focused.log \
  '-Dtest=Delivery*Test' '-Dit.test=Delivery*IT,!DeliveryApplicationContractIT' verify

# 完成阶段的平台回归；不按默认排除的原生/CLI 契约为已通过
sh .local/run-ex22-checks.sh ex22-delivery-all.log verify

# frontend；PATH 指向现有 Node 24.20.0，JAVA_HOME 采用已批准隔离 JDK
npm run typecheck
npm run lint
npm run format:check
npm run test:unit
npm run build
CI=1 npm run test:e2e
CI=1 npm run test:e2e:runtime

# 下载工程：在解压后的 project/，私有配置另放，禁止执行未知下载代码
# --test-config 是本模块拟新增的入口，当前脚本尚未支持；完成后按此验收
node scripts/build.mjs --test-config /absolute/private/test.properties
node scripts/start.mjs --config /absolute/private/runtime.properties
```

浏览器测试仍使用现有 8081/5174 夹具及 `atoms_verify_20261025_ex22` schema；实际运行只复用已批准的 A/B 测试库、业务库和 18181/18182 端口。它们已有 V2 数据，不能重放 V1 初始化、清空或 repair；换版采用兼容当前 V2 SQL 的两份源码，仍须核对身份和迁移兼容性。

独立下载验收使用 A 的源码包、B 的测试/业务配置和 18182 端口，在确认原 B 进程停止后运行；这能检查源码不绑定原 A 的测试库、平台类路径或业务数据。先保存 A/B 原数据快照，只新增验收自身业务记录，检查 B 原记录保留、A 不受影响，以及下载工程的新增/修改/查询和重启；不为验证再创建 DB/角色/绑定。若后续需要新持久资源，先列明具体资源再申请。

真实应用契约按既有 `project-execution-contract` profile 显式运行，完整参数在获准实施计划中列出；不为文档校验运行 Maven、浏览器或模型。保留原失败记录与未决审计，只操作本轮自有进程；同时仅一个 Maven。

## 9. 完成标准

1. 默认关闭时旧账号、项目、生成、预览、导出、恢复、发布回归通过；不能误将新 URI 放入普通 SPA fallback 或放宽管理 CSRF/CORS。
2. 实际 Vue/Java 样本通过私有路径完成新增/修改/查询与预期 409；页面和 API 来自同一 owned run，V2 更新能辨认，浏览器无额外未声明兼容设置。
3. 浏览器验证不透明来源、ES modules、预检、Cookie/存储隔离、伪造消息与外部导航/请求限制；实际应用拿不到平台 Cookie/CSRF。只放宽精确预览资源，不允许任意 Origin null 请求访问管理接口。
4. 跨 owner、project、run、grant 串用、到期、登出、换账号、停止/换版、归档、平台重启与配置变化均被拒绝；旧 URL 不静默指向新应用。
5. 伪造 URL、跳转、路径穿越、重复编码、慢/超大正文、意外 MIME、上游错误、客户端断开均有界结束；写结果不确定不自动重放。
6. 预览下载的每个源文件及 Unix 模式与其 Git 树一致；更改平台当前模板不影响旧构建下载。新目录使用下载工程自己的构建脚本、显式测试/运行配置，无平台类路径或硬编码验收库名称，实际构建启动后 Vue/API/数据库行为一致，原数据与应用身份按设计分别核对。
7. 当前预览失效则“预览源码下载”失败；另选历史成功构建才能下载旧源码。恢复有 CAS/幂等与不可变历史，绝不顺带回滚数据库或替换运行。
8. 无秘密进入 ZIP/诊断/日志；资源/授权数量有界，失败释放自身交换/打包名额，原审计和业务数据保留。相关单测、集成、浏览器、真实下载验收与兼容回归分别归档。
9. 维护文档区分“交付模块已验收”“工作台已接入”“真实模型已通过”“已部署”，按实际结果更新，不提前勾选后续模块。

## 10. 评审点

建议按本稿确定三项产品边界：使用现有 HTTP 路径和短期私有预览，不新增域名/端口；首版采用不透明来源及无 Cookie 的 JSON 业务预览；下载固定源码快照，恢复源码不自动切换应用或业务数据。

这些选择需要通过真实浏览器和下载工程验收才能兑现，当前仅完成了代码核对与规格设计。若浏览器无法满足该边界，先报告证据并调整规格，不临时允许同源脚本或平台凭据转发。规格通过后进入本模块实施计划与任务分解，继续遵守整体连续开发授权，不逐个小任务询问是否开始。
