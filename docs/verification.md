# 本地验收记录

记录成功和失败证据；未实测的内容不标记完成。各阶段保留当时的状态，最新范围以文末 T12–T15 / C06 记录和任务清单为准。

## T01 · 后端骨架（本地通过）

- Java 17.0.5 / Spring Boot 3.5.16。
- RED：先写 HealthControllerTest，`mvn -B test` 因缺少 HealthController 失败。
- GREEN：实现后 2 个单元测试通过；数据库错误被转换为脱敏的 503，带 requestId。
- 集成 RED：连接独立 PostgreSQL 17.11 测试库与 Flyway 迁移成功；HTTP 响应缺 nosniff 导致断言失败。
- GREEN：新增安全响应头，`./mvnw -B verify` 通过：2 个单元测试 + 2 个真实 PostgreSQL 集成测试，无跳过。
- 后端实际监听 127.0.0.1:8080；GET /api/health 返回 200 和 `{"status":"UP"}`，安全头、no-store 已读取确认。
- 直接访问本机需绕过环境中的 HTTP 代理；curl 验证使用 `--noproxy 127.0.0.1`，未修改全局代理。
- 本阶段没有业务表；仅创建 Flyway 迁移历史。测试写入 TEMP 表并回滚，不影响应用库。
- 真实启动失败验证：另起短暂进程，使用不可达的回环端口 1 作为数据库目标，预期以退出码 1 结束并报告连接拒绝；未停止现有后端或数据库。
- 回归保护：暂将健康故障响应由 503 改为 200，测试按预期失败；随后恢复原代码并重新全量验证。不保留任何变异。

## 前端设计契约

参考原产品研究中的首页、聊天、App Viewer 三类界面结构，而非复制品牌资产。

- 首页任务：描述应用；本切片主动作是“进入工作台”，明确还未创建项目或启动生成。
- 工作台任务：浏览对话/预览布局；未实现的生成、源码和发布有禁用或清晰空态。
- 风格：墨黑画布、浅灰表面、黄绿色主动作；系统字体，不加载第三方字体或图片。
- 桌面约 224px 导航、380px 对话及弹性预览；移动端使用对话/预览切换。
- Enter 提交、Shift+Enter 换行、IME 组合期不提交；所有控件有可访问名称与可见焦点。
- 必须验证加载、服务断开、重试和空态；不使用假进度、假项目、假生成结果。

## T02–T03 / C01 · 本地通过

2026-10-07，Node 24.20.0、npm 11.19.0；版本细节以 frontend/package-lock.json 为准。

| 检查 | 结果 |
|---|---|
| npm ci --ignore-scripts | 按锁文件干净安装通过，依赖生命周期脚本保持禁用 |
| npm run typecheck | 通过，strict 且未跳过依赖声明检查 |
| npm run lint / format:check | 通过 |
| npm run test:unit | 3 个文件、13 项测试通过，无跳过 |
| npm run build | 通过，JS gzip 约 41.11 kB，CSS gzip 约 3.18 kB |
| npm run test:e2e -- --project=chromium | 8 项真实 Chrome 测试通过，无重试或跳过 |
| npm audit | 0 项已知漏洞；不等于完整安全审计 |
| npm audit signatures | 253 个包注册表签名通过，其中 114 个包具有已验证来源证明 |

浏览器验收覆盖：真实同源 API、正常/错误/重试状态、示例填充不提交、Enter/Shift+Enter、路由往返草稿、未实现功能禁用、深链接刷新、404 恢复、五种宽度（320、390、768、1024、1440）、axe WCAG 2 A/AA 与 2.1 AA 标签检查。主流程浏览器 console 没有 error/warning。中文 IME 防误提交通过组合事件单元测试验证，未宣称测试了每种操作系统输入法。

已查看 390/1440px 首页与工作台截图；文件由 Playwright 写入 `frontend/test-results/`，不提交生成物。无横向溢出；手机工作台按区域切换，桌面左右分栏。未做真实性能基准或完整人工屏幕阅读器审计。

RED/GREEN：PromptComposer、health 请求解析及 App 路由测试先因实现缺失失败，实现后全部通过。类型检查曾暴露缺少 @types/node 与 ESNext.Disposable，补齐声明后通过；jsdom 没有 scrollTo 实现，单元环境使用明确的环境替身，滚动/导航保留浏览器验收。

前端首次依赖安装约 3 分钟，干净锁文件安装约 5 秒；第一次后端 Wrapper 完整验收约 34 秒，后续约 14 秒；第二次浏览器验收约 39 秒。以上是命令耗时，不代表本次总开发工时。

## M1 当时的边界与自审（历史记录）

- 按 correctness/readability/architecture/security/performance 五轴自审：固定规模只读健康 API、受限数据库账号、前端请求有超时、输入长度受限、Vue 转义输出、不执行生成代码；未发现当前切片的阻塞项。
- 已验证故障状态回归测试可捕获代码变异；恢复后后端 4 项测试和构建再次通过。
- 只在本地 feature 分支保存检查点，没有推送、合并、云部署或付费模型调用。
- T00 未完成：真实模型和线上资源仍待落实；T04/C02 仍未通过：没有部署骨架或在线链接。
- T05 起尚未实现。当前浏览器草稿不持久化、没有真实项目与身份、没有生成/预览运行/发布；不能作为挑战最终提交版本。
- Java 17.0.5 的安全补丁、公网运行限制、后端依赖漏洞扫描、完整登录/权限/沙箱测试在上线前单独验收。本次 npm 的 0 漏洞结果仅针对前端锁文件。

## T05–T09 / C03–C04 · 本地通过

2026-10-07，访客身份与项目形成真实持久化闭环，保持 Java 17.0.5 / Spring Boot 3.5.16 和原前端依赖版本。

| 检查 | 最终结果 |
|---|---|
| `./mvnw -B verify` | 2 个单元 + 12 个真实 PostgreSQL 集成测试通过；约 60 秒，无跳过 |
| `npm run typecheck / lint / format:check` | 全部通过 |
| `npm run test:unit` | 4 文件、23 项通过，无跳过 |
| `npm run build` | 通过，JS gzip 45.29 kB、CSS gzip 3.58 kB |
| `npm run test:e2e -- --project=chromium` | 11 项真实 Chrome 测试全部通过，约 1.4 分钟，无重试或跳过 |
| `npm audit --json` | 0 项已知漏洞；前端锁文件未变，后端尚未做完整依赖漏洞扫描 |

身份证据：CSRF → session → me 建立并恢复同一用户；独立 Cookie jar 得到不同用户；会话可在 spring_session 中按 principal_name 查询。缺失/错误 CSRF 返回结构化 403，未认证私有读取返回 401。首次认证后 Cookie 和 CSRF 轮换，旧 Cookie 不再能访问 /api/me。SecureCookieIT 验证启用安全配置时含 Secure、HttpOnly、SameSite=Lax 和 604800 秒 Max-Age；这是配置测试，不等于线上 TLS 已验收。

重启证据：SessionRestartIT 创建访客与项目，关闭第一个 Spring 应用上下文及其 HTTP 服务，再启动新的上下文与 HTTP 服务。原 Cookie jar 可读取同一个 userId、项目名称，并继续重命名。测试使用独立 atoms_demo_test，不依赖内存会话缓存；它验证了服务重建，不宣称已经做容器/公网故障演练。

项目与事务证据：创建、读取、重命名、默认/游标分页、80 字符边界、空白/非字符串/额外字段/坏 JSON/坏游标均测试。不同所有者 GET/PATCH 返回 404，列表隔离；重命名不改变源码 revision。并发两请求同键返回同一 201 内容，只有一个项目；同键异体 422，跨身份同键独立。幂等回调内业务写入后抛异常，业务行和幂等占位一起回滚，再试可以成功；嵌套 JSON 属性顺序不同仍重放原结果。

浏览器证据：真实创建 → 原始需求传入工作台 → 重命名 → 刷新 → 我的项目 → 再次打开；第二个隔离浏览器看不到项目名称并显示无权限/不存在。模拟服务端已提交但响应丢失后，输入仍在，用户重试沿用同一 key，列表仅一个项目。会话失败阻止创建，可重新连接；项目列表失败可重试。正常主流程没有浏览器 console error/warning。

UI 验收延续原设计 tokens：首页主动作改为“创建项目”，真实项目以紧凑列表展示；项目标题可编辑，有加载/空/失败/无权限状态，生成与发布仍禁用。320/390/768/1024/1440px 无横向溢出；axe 的 WCAG 2 A/AA、2.1 AA 标签检查通过。查看了最终桌面与 390px 项目截图，位于 frontend/test-results（生成物不提交）。没有 Chrome DevTools MCP，使用独立临时 Chrome 的 Playwright/axe 做替代验证，未访问个人浏览器。未宣称完成性能基准或人工读屏审计。

RED/GREEN 与审查发现：

- SessionIT 最初 404 而预期 200/401；实现后通过。ProjectIT 的三项先因接口缺失 404 失败；实现后通过。幂等机制先因缺少实现不能编译，再通过事务测试。
- 前端 HTTP 测试先因模块不存在失败；App 主流程先进入旧草稿路由而失败，接通项目后通过。
- 浏览器定位“项目名称已保存”匹配两处，改为精确匹配状态文本，未删除断言。
- 视觉审查发现手机原布局隐藏 profile，连带隐藏会话重试。先增加手机故障用例复现，再调整布局，最终全量浏览器验收通过。
- HTTP 边界审查发现 HTML 401 会丢失状态码：回归测试先失败，修复后能触发会话失效，不自动重试写操作。
- 临时反转请求摘要相等判断后，IdempotencyIT 正确失败；已恢复正确条件，并完成最终全量后端验证。没有保留变异代码。

当前边界：T05–T09 完成；T10 起尚未实现。项目元信息持久化，需求草稿/聊天尚未持久化，模型生成、运行预览、发布与公网链接仍缺失。访客过期或 Cookie 丢失无法恢复。数据库集成测试只写专用测试库；浏览器测试使用真实本地 API，因此会留下独立测试访客和其项目，不会出现在使用者自己的项目列表中，也未清理或删除业务数据。

五轴自审通过本地阶段要求；上线阻塞项仍包括共享限流与总配额、请求体上限、数据保留/删除机制、后端依赖扫描和 HTTPS 实测，详见 [ADR-003](decisions/003-guest-project-persistence.md)。未推送、合并、部署或调用付费模型。代码按身份、项目、前端接入及验收切片保存本地提交。

## T10–T11 / C05 · 本地通过

2026-10-07，继续保留访客方案，OAuth 暂不接入。新增不可变源码版本、受限 HTTP 运行文档和只存在于 test classpath 的固定产物测试台。T12–T15 仍未完成，不将这一检查点当作完整 M3。

| 检查 | 最终结果 |
|---|---|
| `./mvnw -B verify` | 6 个单元 + 16 个真实 PostgreSQL 集成测试通过，无跳过；最后约 61 秒 |
| `npm run typecheck / lint / format:check` | 全部通过 |
| `npm run test:unit` | 原有 23 项通过，无跳过 |
| `npm run build` | 通过；管理前端源码未变，JS gzip 45.29 kB、CSS gzip 3.58 kB |
| `npm run test:e2e -- --project=chromium` | 新版 8080 后端上的原有 11 项全部通过，约 1.3 分钟 |
| `npm run test:e2e:runtime` | 4 项专用测试库/Chrome 用例全部通过，最后约 33 秒，无重试或跳过 |
| `npm audit --json` | 前端 0 项已知漏洞；没有据此宣称后端依赖已完成漏洞扫描 |
| 正式 JAR 检查 | 有 runtime/document.html；无 fixtures 包、测试页面、test/fixture 配置 |

版本与事务：固定文件和 manifest 原样存取；有字节上限、额外字段、危险 HTML 等拒绝测试。相同源码可保存两个不同版本且 hash 相同；列表按版本分页，不附带全量源码。并发相同基线只成功一次，另一请求 409；外层事务故意回滚后版本数仍为 0、项目指针仍为 null。更新/删除版本被数据库触发器拒绝；跨项目版本读取返回 404，跨项目当前版本外键不能写入。他人身份与匿名身份均不能读取私有源码或运行文档。

运行边界：HTTP MIME/no-store/CSP 和单份 CSP 响应头均有断言。真实 Chrome 中 iframe 和直接打开的 document 都报告 opaque origin（window.origin 为 null）；鼠标与空格键可以操作计数器。CSS/JS 中的闭合标签保持为文本，不产生额外执行；运行脚本不能访问父页面 DOM，fetch 平台 API 和加载外部 script 被 CSP 拦截。CURRENT 拒绝过期版本，HISTORY 可以查看旧源码；它尚无 SDK 或业务状态写入能力。管理 /api/me 与项目 API 继续是 XFO DENY 和严格 CSP。

测试台与交付边界：只接受四个固定场景，不接受任意源码。创建使用真实访客、CSRF、幂等和事务；同键重试返回同一份版本结果。入口启动校验 atoms_demo_test/atoms_test 和回环绑定，独立 Cookie 不覆盖普通开发会话。测试代码不进入正式 JAR；标准应用上下文中测试装载路由不存在。

浏览器替代验收：无 Chrome DevTools MCP，使用独立临时 Chrome、Playwright 和 axe。正常测试台及直接文档无 console error/warning；安全攻击场景预期有 CSP 拒绝日志，并断言没有相应网络响应。320/768/1024/1440 宽度无横向溢出，axe 的 WCAG 2 A/AA、2.1 AA 标签检查无 violation；查看了桌面与 390px 截图。测试台沿用深色/青柠色调，明确标注测试产物、保存源码与未保存业务数据的区别。没有宣称人工读屏或全平台兼容性认证。

截图保存在 `frontend/test-results-runtime/documents-saved-fixture-ru-8e731-agement-headers-stay-strict-chromium/runtime-desktop.png` 与 `runtime-mobile.png`，生成物不提交；原有 E2E 使用独立的 frontend/test-results。trace 可能包含测试会话信息，不公开发布。

RED/GREEN 与复核：

- Artifact 测试先因服务/校验器缺失编译失败；实现后验证通过。RuntimeDocumentIT 最初 404 而预期 200，实现路由后进入头部断言。
- 第一次头部验收发现默认 CSP 与 ResponseEntity CSP 被叠加，导致 XFO 仍为 DENY。改为鉴权、装配成功后精确替换 CSP，保留单份策略断言；没有全局放宽管理页。
- 独立复核发现 nonce 可被生成脚本复制到外部 script，有效且已处理：运行文档改为 inline-only、无 URL 源，实测外部脚本被拒绝。已向用户提供跨模型复核选项，未调用未经授权的外部 CLI；复核范围为单模型独立审查及实际测试。
- 首轮 Chrome 3/4 通过，正常场景因 favicon.ico 的 401 控制台错误失败。检查 trace 只提取错误 URL/状态，定位后提供内嵌图标，保留零错误断言；最终 4/4 通过。
- 按审查要求把基线条件从 OR 临时改成 AND，ArtifactIT 确实因“正确 revision + 错误基线没有拒绝”失败。已恢复 OR 并重新完成全量后端验收，没有提交变异。
- 新测试服务首次在工具沙箱中无法启动；经明确授权后启动本机测试服务与隔离 Chrome。没有绕过权限、连接个人浏览器或修改 Docker 登录状态。

本地正常后端已重启，Flyway 在 atoms_demo 增量执行 V004；没有更改 V000–V003，没有删除项目或数据库卷。5173 代理健康检查为 UP；8081/test-lab 返回 200。后端与测试服务可分别按 README 启动。五轴自审未发现本检查点阻塞项，代码按版本存储、运行文档、测试夹具/浏览器验证及文档分批提交，只保存在本地分支。

限制继续保留：计数变化刷新归零；没有 Atoms.ready、MessageChannel、业务状态持久化、正式工作台源码/运行预览、真实 AI 生成、发布或公网访问。iframe 不提供完整 CPU/内存/导航隔离，不是通用恶意代码容器。共享限流/总配额、请求体限额、数据保留/删除、Java 安全补丁、后端依赖扫描和 HTTPS 公网实测仍在上线前验收。下一步 T12–T15；设计依据见 [ADR-004](decisions/004-immutable-artifacts-runtime.md)。

## T12–T15 / C06 · 本地通过

2026-10-07，完成受限消息桥/SDK、独立应用状态、工作台源码与交互预览、历史只读和私有独立预览。保持 Java 17.0.5-oracle、现有依赖锁定与访客模式；未增加第三方依赖。

| 检查 | 最终结果 |
|---|---|
| `./mvnw -B verify` | 6 个单元 + 19 个真实 PostgreSQL 集成测试通过，共 25 项，无跳过；约 24 秒 |
| `npm run typecheck / lint / format:check` | 全部通过，未关闭检查或添加抑制规则 |
| `npm run test:unit` | 8 文件、36 项通过，无跳过 |
| `npm run build` | 通过，JS gzip 49.60 kB、CSS gzip 3.90 kB |
| `npm run test:e2e -- --project=chromium` | 原有 11 项通过，46.4 秒，无重试或跳过 |
| `npm run test:e2e:runtime` | 8 项通过，34.3 秒，无重试或跳过 |
| `npm audit --json` | 前端 0 项已知漏洞；不是后端依赖扫描或完整安全认证 |
| 正式 JAR 检查 | 包含 runtime/bootstrap.js、runtime/document.html；没有 fixtures 或 test/fixture 配置 |

数据证据：PREVIEW GET/PUT 检查项目归属和当前代码版本；额外 actor 字段、非对象、超限 UTF-8、无效 schema、非安全整数与缺少 CSRF 被拒绝。初次写入和后续修订号竞争均只有一个成功，另一请求 409；相同已提交意图在状态或代码推进后仍重放原响应，不二次保存。代码版本更新保留已有状态。

桥接证据：测试伪造 source/channel/type、错误输入、超时和旧通道销毁；fragment 绑定的运行文档先握手再加载应用脚本，避免首次 load 前导航获得宿主能力。SDK 未知保存保留原请求与不可变输入，读操作不能清除它；队列已满不产生尚未发送的保存意图，且不丢弃启动/错误通知。HISTORY 仅返回 JSON 初始快照并拒绝保存。

新增四个真实 Chrome 场景：

1. 待办新增/完成后刷新恢复，HTML 输入按文本显示；源码转义、manifest、独立预览复用状态与其他访客拒绝访问。
2. 服务端实际提交后拦截为无效 200 JSON，用户重试仍使用同一幂等键；另一个标签保存冲突且保留输入，读取最新可恢复。
3. 当前版本保存后切换历史，历史只读初始数据，没有状态 API 请求；切回当前版本数据仍在。
4. 浏览器离线保存失败保留输入，恢复网络后手动重试并刷新，只有一份保存结果。

UI 验收：沿用墨黑/青柠工作台，白色待办卡片；正常主流程 console error/warning 为零。320/768/1024/1440px 无页面横向溢出，axe WCAG 2 A/AA、2.1 AA 标签检查无 violation。已查看 1440/390px 截图，桌面分栏、手机区域切换正常。无 Chrome DevTools MCP，使用独立临时 Chrome/Playwright/axe，不连接个人浏览器；没有宣称人工读屏或真实性能基准完成。

截图：`frontend/test-results-runtime/state-todo-adds-completes--ba0e6-source-in-a-private-wrapper-chromium/workbench-preview-desktop.png` 和同目录 `workbench-preview-mobile.png`。生成物不提交，trace 可能含测试会话，不公开上传。

RED/GREEN、审查与失败记录：

- PreviewStateIT 先因接口不存在得到 404，实现后全部通过；前端桥/SDK/context 先因缺少模块失败，再通过行为测试。
- 三轮独立安全复核提出的提前导航、未知保存误清除、异常成功响应、幂等键长度、无效输入卡死、JSON 校验不一致、满队列意图与生命周期通知问题，已落实到契约、实现或回归用例。最后一项先复现失败再修复，没有无限扩大审查循环。跨模型选项已提供但未收到选择，没有调用外部 CLI。
- 临时反转 HELLO 的 iframe source 检查，三项桥测试按预期失败；随后恢复正确条件并重新通过全部单元测试，未保留变异。
- 首次新增浏览器验收发现 Vue 响应式 initialState 不能 structuredClone，先增加失败单元测试，再改为 JSON 快照，历史只读场景通过。无权限提示的测试文本补齐“当前访客”，没有弱化权限断言。
- 首次并发运行两套浏览器验收时，普通套件 10/11 通过，首页遇到一次服务连接/会话响应格式异常。只读 trace 显示 health/csrf 为 200 application/json，未确定根因。随后健康检查 UP、该用例单独通过、两套顺序全量通过；未调高超时、删断言或跳过测试。此瞬态异常仍需观察，不能据此声称已定位修复。

本地运行：正常后端 8080、正常工作台 5173；独立测试后端 8081、测试工作台 5174。两套数据库和 Cookie 分离。Flyway 在应用/测试库增量应用 V005，没有修改已应用迁移或删除数据。测试台选择“持久化待办（工作台）”→装载→打开测试工作台即可体验，启动命令见 README。

五轴自审完成本地阶段检查。后端、桥与工作台已分别保存为本地提交 `d3d02bd`、`2df6f26`、`b1a7c24`，测试夹具/浏览器与文档另存切片提交。没有推送、合并、公网部署、付费模型调用、Docker 登录变更或凭据输出。

当前边界：T12–T15/C06 已完成，但手工待办不是 AI 生成。聊天、任务流、模型生成、发布和在线链接仍未完成，下一步从 T16 任务持久化提交开始。当前 256 KiB 是单份状态上限，幂等响应累计存储没有总配额/清理；HTTP 总请求体限额、限流、数据保留/删除、Java 安全补丁、后端依赖扫描与 HTTPS 仍须上线前完成。沙箱不是完整 CPU/内存/导航隔离容器。详细边界及官方依据见 [ADR-005](decisions/005-preview-state-bridge.md)。

## 官方实现依据

- [Vue 表单及中文输入法](https://vuejs.org/guide/essentials/forms.html)
- [Vue Router 路由](https://router.vuejs.org/guide/)
- [Pinia setup store](https://pinia.vuejs.org/core-concepts/)
- [Vite 同源开发代理](https://vite.dev/config/server-options.html#server-proxy)
- [Vitest 配置](https://vitest.dev/config/)
- [Playwright 独立测试与配置](https://playwright.dev/docs/test-configuration)
- [Vue ESLint](https://eslint.vuejs.org/user-guide/)
- [Spring OncePerRequestFilter](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/web/filter/OncePerRequestFilter.html)
