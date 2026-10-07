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

## DeepSeek 与普通生成工作台 · 本地实现通过，真实调用待验收

2026-10-07，本轮重点为 5173 普通工作台，不扩展 8081/5174 测试台功能。T16/C07 完成；T17–T24 的已实现部分与剩余验收分别列入 todo，未把替身测试视为真实模型生成。

| 检查 | 最终结果 |
|---|---|
| `./mvnw -B verify` | 14 个单元 + 26 个真实 PostgreSQL 集成测试通过；约 71 秒，无失败/跳过 |
| `npm run typecheck / lint / format:check` | 全部通过 |
| `npm run test:unit` | 11 文件、43 项通过，无跳过 |
| `npm run build` | 通过，JS gzip 52.37 kB、CSS gzip 4.03 kB |
| 普通 Chrome E2E | 13 项通过，约 1.3 分钟 |
| 受限预览/持久状态 Chrome E2E | 8 项通过，约 1 分钟；两套顺序执行 |
| 正式 JAR 检查 | 包含 prompts，不含测试夹具或测试类 |
| 普通后端最终健康检查 | 8080 `/api/health` 返回 `{"status":"UP"}` |

网关证据：JDK17 HttpClient 按官方协议发送 deepseek-flash、thinking enabled、reasoning_effort high、stream false 与 JSON 输出要求。测试只连接本地 HTTP 替身，验证 final content/usage、拒绝不完整 finish_reason、401/402/429/5xx/重定向脱敏、不自动重试、超时、取消和 2 MiB 响应上限。没有发送真实 DeepSeek 请求，没有读取或输出用户密钥；reasoning_content 不保存、记录或展示。

持久与执行证据：生成短事务写入幂等响应、run、USER 消息、首事件与项目活动标记；相同键并发得到一个 run，版本推进后仍重放原 202。跨身份 run、消息、列表、取消均拒绝，写入仍校验 CSRF。Worker 真实执行数据库事务，模型响应由 test-only Bean 提供：完成规划、生成、最多一次产物修复；产物/版本/指针/终态原子保存；修改后已有应用 count=7 仍保留。认证失败仅一请求；取消期间晚到结果不保存。默认额度 0，计数预留持久化；限定为剩余 1 次时第二次预留失败且不多建调用记录，未知用量不退回。启动恢复直接调用启动处理验证 RUNNING → INTERRUPTED、不请求模型；不是进程崩溃演练。

普通工作台证据：真实创建项目后显示缺配置提示；生成相关 HTTP 响应的显式浏览器替身验证提交丢响应→保留输入→原键重试、生成阶段、刷新恢复、历史消息、取消。替身没有向普通数据库注入生成源码。前端 composable 验证首次成功后读取新版本、下一轮提交 MODIFY/currentVersionId/revision，以及失败输入恢复不覆盖新草稿。活动状态 1.5 秒轮询，不伪造 token 流或阶段百分比；SSE 尚未实现。

UI 检查：已查看普通生成阶段桌面/390px 截图，沿用已有深色与青柠 tokens；320/768/1024/1440px 对话区无横向溢出，axe WCAG 2 A/AA、2.1 AA 标签无 violation。无配置正常流程 console error/warning 为零；刻意中断网络用例预期有失败请求，不声称故障用例控制台无错误。截图位于 `frontend/test-results/generation-normal-workbenc-3eff0-sk-on-reload-and-cancels-it-chromium/`；生成物/trace 不提交。仍使用隔离 Chrome/Playwright，未访问个人浏览器，无 DevTools MCP，也未做人工读屏或真实性能基准。

测试驱动、调试与审查记录：

- 网关、工作流和前端 API 测试先因缺少实现失败；提交接口先 404；工作台提交按钮先禁用，实现对应切片后通过。
- 首次任务接口集成测试暴露 Repository 代理对象字段直接访问导致空引用；改为构造器注入 JdbcTemplate 后通过，不关闭代理或降低检查。
- 后续针对终态与项目并行读取的竞态、失败输入丢失先增加两个失败用例，再改为终态读取在前、项目/消息读取在后，以及只恢复空草稿。新增修改基线回归通过。
- 按代码审查技能做边界变异：暂将前端最小额度 `>= 2` 改成 `> 2`，两个用例正确失败；恢复后完整 43 项单元、类型、lint、格式与构建通过。未保留变异代码。
- 浏览器首次沙箱启动受 EPERM 限制，申请权限后正常运行；最终健康检查沙箱内连接失败，按审批在沙箱外检查返回 UP，没有为测试更改网络/安全策略。

五轴自审：正确性覆盖幂等、权限、版本基线、原子提交、取消及错误恢复；结构按网关/工作流/短事务/调度/界面分层；安全限制固定上游地址、服务端密钥、受限产物、脱敏错误与成本预留；读取按项目索引分页，轮询只在活动时启用；沿用已有风格、无新增第三方依赖。新表 V006/V007 增量应用于正常与测试库，未改旧迁移、删除数据或操作 Docker 登录。

局限与下一步：仍缺真实 API Key、明确的调用上限和一次真实生成/修改的质量证据；测试通过不证明模型语义正确。预算为数据库生命周期累计请求绝对上限，不是人民币或每日预算。单实例恢复不适用多副本，SSE、身份频率、队列满/并发预算全面验收、公开发布及线上资源仍待推进；既有 Java 安全补丁、后端依赖扫描、HTTP 总请求体上限和数据清理等上线要求不变。没有推送、合并或公网部署。实现决策与官方资料见 [ADR-006](decisions/006-deepseek-generation.md)，启动配置见 README。

## 真实 DeepSeek 验收 · 首次生成通过，修改受阻

2026-10-07，用户本地配置 DEEPSEEK_API_KEY 并要求继续。只输出配置是否非空与文件权限（600），不读取到对话、不修改密钥或数据库凭据。只读核对正式库原始 requests_reserved=0、无活动任务后，重启普通 8080 后端，临时启动参数 `--GENERATION_MAX_MODEL_CALLS=6`，未写回 .env.local。真实模型仍为用户指定参数，没有切换供应商或降低产物安全校验。

| 真实任务 | runId | 请求数 | 结果 | 上游报告 total_tokens |
|---|---|---:|---|---:|
| 首次 CREATE | e9793139-2830-4fd4-b179-604eb89ecf7e | 1 | 规划 MODEL_INVALID_JSON，无新版本 | 1,146 |
| 第二次 CREATE | 4eaf1aac-bb34-4391-8efe-8e9877edb389 | 3 | Planner/Builder/一次 Repair 后保存 V1 | 32,293 |
| 在 V1 上 MODIFY | bdbe67ec-3f80-4eb4-8817-d4e5bcde1a8d | 1 | 规划字段数量不等于 6，V1 保留 | 7,729 |

数据库累计预留数为 5。三项任务 usage 合计 prompt=14,848、completion=26,320、total=41,168；这是供应商报告的 token 统计，不是货币费用，未根据未经核实的单价推算账单。残余 1 次不足新一轮至少 2 次请求，停止付费调用，没有提高累计上限。首次失败没有保留模型原文，无法追溯具体字段；后续脱敏诊断定位了 MODIFY 的字段数量问题，不能声称已经证明第一次失败与它原因相同。

真实交互：独立 Chrome 在 5173 首页创建项目，点击生成，后台产生不可变 V1，页面自动加载预览。生成源码不是测试夹具，未通过外部 SQL/代码注入替换产物。在应用内填写“真实生成验收任务”→添加→勾选完成，两次保存 HTTP 200；刷新后任务和勾选状态仍在。随后正常工作台提交增加筛选的 MODIFY，任务失败，没有新版本。整个“创建并修改”live 用例因此为失败，不能报告全闭环通过或筛选功能可用。

成功子路径截图当时已人工查看，位于 `frontend/test-results/live`，但该临时目录在后续重复验收时被测试运行器覆盖，当前文件对应下节的新一轮结果。生成应用为浅色卡片式待办，处于真实受限 iframe；项目属于隔离验收访客，不属于日常浏览器身份。截图/失败上下文仅保留在忽略目录，未保存或导出认证 Cookie，未访问个人浏览器。完整用例未走到最终 console 断言，不能宣称本次 live 全程零 console 错误；窄屏/axe 的历史平台结果也不能代替此生成应用的完整无障碍验收。

针对发现的问题做了两个小调整：

- GenerationWorkflow 的错误现在只暴露固定字段名/格式要求，可区分语法、根类型、字段数、布尔/字符串/数组约束；不包含模型原文、动态未知字段名或思考内容。先写失败用例，再实现并通过。
- Planner 提供明确的六字段 JSON 示例，要求空数组字段也必须存在，修改上下文仅作为参考，不返回 Artifact/输入外层对象。对应提示词测试先失败再通过。严格校验保持不变；提示词调整仅完成本地验证，**尚未再次发出真实请求验证其效果**。

最新本地回归：`./mvnw -B verify` 16 个单元 + 26 个 PostgreSQL 集成测试通过（约 34 秒）；前端 43 个单元、类型、lint、格式与 build 通过。新增 live 专用配置/脚本，默认测试不包含它，缺少显式 `ATOMS_LIVE_ACCEPTANCE=up-to-6-requests` 时在启动前拒绝；预算由服务端独立强制，脚本要求余量 4–6 次、retries=0、trace=off。没有把失败 live 用例跳过或改成成功，也没有为了它放宽业务断言。

按审查技能暂时反转规划字段数条件后，工作流用例正确失败；恢复后重新定向验证。五轴复核保持变更只涉及校验诊断、规划提示和显式 live 验收，无新依赖或安全头变化。未删除数据、清除记账、变更 Docker 登录、推送、合并或公网部署。真实修改、提示词稳定性与更广泛应用质量仍待追加额度后验证。

## 真实 DeepSeek 复验 · 生成、保存与一次修改闭环通过

2026-10-07，用户明确同意最多再调用 6 次。启动前只读核对累计已用 5 次、无活动任务，临时设置 `--GENERATION_MAX_MODEL_CALLS=11`（5 + 6，而非把旧余量再次叠加），未更改 `.env.local`、模型/思考参数、单请求 token 限额或产物校验。仍使用 Java 17.0.5、普通 8080/5173、本地 PostgreSQL 和隔离 Chrome，未扩展测试工作台。

| 本轮真实任务 | runId | 请求数 | 结果 | 已知 total_tokens |
|---|---|---:|---|---:|
| 规划提示调整后 CREATE | 7b4dd639-7b38-4fda-a43d-4f90da736e03 | 2 | 规划通过，构建 MODEL_INCOMPLETE，无新版本 | 1,109（仅规划） |
| 精简构建提示后 CREATE | 42de3ebd-5c89-4b0e-9f32-28034a1dd01c | 2 | 规划/构建直接成功，保存 V1 | 15,013 |
| 在新 V1 上 MODIFY | 341a6e06-5aae-4217-a309-b8e3d59e3831 | 2 | 规划/构建直接成功，保存 V2 | 17,389 |

本轮使用 6 次，累计 requests_reserved=11、剩余 0；数据库任务汇总为 3 个 SUCCEEDED、3 个 FAILED，无活动任务。已停止模型调用，不自动增加预算。本轮 5 个有记录的 usage 合计 prompt=12,117、completion=21,394、total=33,511。第一行构建在 decode 完成前被拒绝，usage 未留存，计 UNKNOWN；这些 token 只是已知部分，不能当作全部消耗或货币费用。旧的通用 MODEL_INCOMPLETE 没记录 finish_reason，无法追溯为长度限制、过滤或其他中断。

针对可观察性缺口，网关按官方 finish_reason 枚举返回固定脱敏消息，未知值不回显；依然拒绝非 stop 输出，不保存部分源码、不自动重试。Builder 提示增加小应用 <=12 KiB 的设计目标，避免冗长注释和未请求功能，同时明确不能删除任何所需交互、无障碍、保存、冲突或显式重试能力。模型额度和硬性 120 KiB 校验未改变。精简提示后的成功是该次实测结果，不足以证明前一次失败根因或所有需求的稳定性。

完整验收命令：前端目录 `ATOMS_LIVE_ACCEPTANCE=up-to-6-requests npm run test:e2e:live`，首次用例约 1.1 分钟失败；修订后同一用例约 1.4 分钟 **1 passed**，retries=0。成功用例从正常首页创建项目，不注入源码或使用模型替身：

1. 提交需求，生成 V1 后自动载入受限预览。
2. 添加“真实生成验收任务”、勾选完成，两次持久化保存返回 200；刷新后仍勾选。
3. 在正常对话框要求增加“仅看未完成 / 显示全部”，生成新的完整 V2。
4. 已完成任务仍在；筛选未完成时隐藏，显示全部时恢复；再次刷新任务与完成状态保留。
5. 最终控制台 error/warning 收集为空，页面错误为空，API 返回剩余请求 0。没有把仅后端 SUCCEEDED 当作浏览器功能通过。

已查看两张截图，目录为 `frontend/test-results/live/deepseek-real-DeepSeek-cre-fdeef-modifies-a-working-todo-app-chromium/`：`created-and-persisted.png`、`modified-and-persisted.png`。截图宽 1280px、全页截取；V2 可见两个筛选按钮和原勾选任务。当前对话与产物说明偏长，尚非最终展示打磨；未重新执行此生成应用的窄屏/axe/人工读屏检查，不挪用先前平台结果。项目属于隔离验收访客，未导出 Cookie/trace，不向另一访客提供失效的私有项目链接。

本地修改验证：先新增结束原因和精简提示用例，测试编译时纠正 AssertJ 单字符串断言用法，随后两项行为断言按预期失败；实现后定向 12 项通过。把 length 分支临时改为不匹配值做变异验证，首次受沙箱回环端口限制，批准后用例正确失败；恢复分支后 `./mvnw -B verify` 全量 **18 个单元 + 26 个 PostgreSQL 集成测试通过**（约 24 秒），无失败/跳过。正式 JAR 构建通过。前端源码未改，43 单元/13 普通 E2E/8 运行时 E2E 的旧结果不宣称本轮重跑。最终普通后端 health=UP。

五轴复核：只改网关错误映射、构建提示及对应测试；保留原错误码及全部拒绝行为，无新依赖、存储结构、鉴权、CORS 或安全头变化；字符串白名单不会暴露上游内容，不新增网络请求或循环。代码保存为本地提交 `360dd5f`。没有修改密钥、删除数据库数据、重置预算、操作 Docker 登录、推送、合并或公网部署。

进度：T18/C10 的本地核心闭环通过；T24 仍需同一代表应用第二轮真实修改，T31 仍需其他类型应用。SSE、身份频率/完整预算验收、公开发布与线上链接继续列为待办，不把当前 Demo 称为完整交付。

## 持久 SSE 与普通工作台实时进度 · 本地验收通过

2026-10-07，继续 T20，并将事件流接入普通 5173 工作台。保持 Java 17.0.5、Spring MVC、Vue 与现有 PostgreSQL；未扩展测试工作台功能。后台只读取已提交 run_events，执行 Worker 不依赖 SSE 连接；模型请求仍为 stream=false，不把阶段事件称为逐 token 输出。

| 检查 | 结果 |
|---|---|
| `./mvnw -B verify` | 18 单元 + 31 PostgreSQL 集成测试通过，约 64 秒，无失败/跳过 |
| 前端 `test:unit` | 12 文件、55 项通过，无跳过 |
| 前端 typecheck / lint / format:check | 全部通过 |
| 前端 build | 通过；JS gzip 53.29 kB，CSS gzip 4.03 kB |
| 普通工作台 Chrome E2E | 14 项通过，59.7 秒 |
| 受限预览/持久状态 Chrome E2E | 8 项通过，40.8 秒；与普通套件顺序运行 |
| 模型累计请求只读核对 | 仍为 11；3 SUCCEEDED、3 FAILED，无活动任务，本轮没有增加调用 |

服务端新增 5 个真实数据库集成场景：终态前立即收到 connected/queued；断开不取消任务，Last-Event-ID 覆盖旧 after 并补发取消终态；同一连接尾随新提交的取消事件；107 条分批有序回放且回滚记录不发送；15 秒注释心跳不改变 seq/状态。另覆盖无身份 401、他人任务 404、非法/越界游标 400、已补齐终态 204。每批 100 条，独立 4 线程 SSE 池、最多 32 连接、60 秒轮换；没有宣称已做慢客户端或容量压测。

普通工作台从 Run.lastEventSeq 继续订阅，显示规划/构建阶段及连接状态；plan.ready 刷新持久对话，run.completed 补齐后读取新项目指针。单元测试验证重复事件去重、跳号/畸形/跨任务拒绝、终态关闭、连续异常暂停、健康空闲轮换、切换项目清理及迟到回调无效。重连只读事件，不再次 POST run；已有输入恢复、取消、同键重试及旧版预览行为保留。

浏览器的生成响应为明确本地替身，不向普通数据库注入模型产物。实际 Chrome 原生 EventSource 经本地 HTTP 服务接收事件并断线重连，服务端观察 Last-Event-ID 为无、2、4，after 保持初始 1；重复 seq=2 不重复展示，规划只出现一次，最终 CANCELLED 后读取快照并停止连接，POST run 只有一次。已有用例同时覆盖刷新恢复与取消。终态成功自动切换新版本由 composable 单元及上一轮真实模型闭环提供证据，本轮未重复付费生成。

UI 沿用已有深色/青柠 tokens，增加实时连接状态的 aria-live 文案。320/768/1024/1440px 无页面横向溢出，axe WCAG 2 A/AA、2.1 AA 检查无 violation；SSE 场景 console error/warning 和 pageerror 均为空。已查看桌面与 390px 截图，路径为 `frontend/test-results/generation-native-SSE-reco-7d490-nd-reads-the-final-snapshot-chromium/sse-building-desktop.png` 和 `sse-building-mobile.png`。无 Chrome DevTools MCP，使用隔离 Chrome/Playwright；不连接个人资料，不读取认证凭据，不上传 trace。没有声称人工读屏或性能基准完成。

测试驱动、调试及五轴复核：

- 事件接口先由失败集成测试驱动实现；SSE Accept 头曾使正常权限/游标错误变成内容协商 500，改为明确 JSON 错误响应后通过，未放宽认证或游标断言。
- composable 新行为先失败后实现。复核发现每次 open 都重置失败计数会导致快速 200/EOF 无限重连；新增用例复现，再改为新事件或至少 15 秒稳定连接才重置，健康轮换用例同时通过。
- 原 route.fulfill 事件替身的拦截请求中读不到 Last-Event-ID，改用真实回环 HTTP 服务后原游标断言通过。没有删断言、伪造请求头或改业务重连协议。
- 首次浏览器运行与后端重启重叠，首页遇到 502；等 health=UP 后执行，未调高超时或弱化检查。最终两套完整浏览器回归通过。
- 按审查技能暂时反转 seq 连续性判断，2 个单元测试正确失败；恢复后完整 55 项通过，未保留变异代码。
- 结构上分离只读 SSE 调度与生成 Worker，无新增依赖或迁移；边界继续所有者校验、固定错误信息、受限解码与有界批次。接口契约、README、任务清单和 ADR-007 已同步。后端与前端分别存为本地提交 `33522d4`、`44dfcba`，没有推送或合并。

T20 完成，T23 的 SSE 界面部分通过；T21 进程重启演练、T22 身份频率/完整资源边界仍未完成，所以不勾选 T23 全项。第二轮真实修改、多类型生成、预览检查报告、公开发布与在线链接仍待推进。普通 8080 已加载新接口；没有修改 .env.local、提高调用上限、删除业务数据或操作 Docker 登录。

## 版本预览检查与持久报告 · 本地切片验收

2026-10-07，按用户确认采用“创作平台本地、仅已发布应用公开”的后续部署方向。本轮实现 T25 的报告切片，未开启发布或隧道，不增加真实模型调用。Java 仍为 17.0.5；正常 8080 与测试 8081 已加载增量 V008，原项目、版本、状态和模型用量未删除或重置。

| 检查 | 结果 |
|---|---|
| `./mvnw -B verify` | 18 单元 + 34 PostgreSQL 集成测试通过，无失败/跳过，JAR 构建通过 |
| 前端 `test:unit` | 15 文件、73 项通过，无跳过 |
| typecheck / lint / format:check / build | 全部通过 |
| 普通工作台 Chrome E2E | 14 项通过，57.1 秒 |
| 受限运行时 Chrome E2E | 13 项通过，最终 58.8 秒，包括新增 5 项报告场景；与普通套件顺序运行 |
| `npm ci --ignore-scripts` / `npm audit` | 冻结安装通过；0 项已知公告，不表示没有未知漏洞 |
| `npm audit signatures` | 253 包注册签名通过，114 包有经验证的 attestations |
| 构建体积 | JS gzip 91.88 kB、CSS gzip 4.07 kB；未做性能基准 |

实现：普通工作台/独立私有预览在创建 iframe 前按 ES2022 经典脚本解析，SDK 启动完成才产生 PASSED；语法、启动/连接、运行异常形成有界错误，失败在同次观察中不会被保存成功/重复 ready 清除。观察与已保存报告分别显示；用户点击保存后持久到当前版本，刷新可读，历史版本只读报告。解析器不执行代码，不使用 eval/Function，不改变宿主或运行文档 CSP。

后端新增 3 组真实数据库场景：UNKNOWN→通过→失败→旧意图重放仍保持最新失败；所有者/跨项目版本/身份/CSRF 检查；畸形枚举、重复检查、缺检查、超长错误、错误状态、非法视口、额外 actor 字段及旧版本拒写。报告不改变项目 revision 或 run/source。幂等键同体重放，异体拒绝；提交序号确定最新记录，不依赖同毫秒时间戳。

新浏览器场景使用 test-run 已有固定待办，不扩展测试台、不向正常库注入产物。报告全部调用真实 API 与 PostgreSQL；畸形脚本/SDK 启动失败/window error/unhandledrejection 通过该隔离浏览器的 HTTP 响应夹具构造，保留受限 iframe 和 CSP，不能称为真实模型故障实测。语法失败断言 iframe/document 请求均为零；错误可保存。正常场景故意丢失已成功保存的响应，手动重试的 body/key/reportId 完全相同，刷新后报告恢复。正常场景 console error/warning/pageerror 为空，故障场景允许其明确构造的错误。

320/768/1024/1440px 无页面横向溢出，新增报告页面 axe WCAG 2 A/AA、2.1 AA 无 violation。已查看桌面与 390px 截图，位于 `frontend/test-results-runtime/preview-reports-saves-a-ve-70091-nd-restores-it-after-reload-chromium/`。初次全页桌面截图中 iframe 内容未显示，随后通过真实输入框填写与值断言确认控件可交互，重新截图后应用及报告可见；未修改应用实现或删断言。未进行人工读屏、所有设备或 CPU 隔离验收。使用隔离 Chrome/Playwright，不连接个人资料，不读取 Cookie；截图/trace 不提交或公开上传。

测试驱动与审查：后端先出现报告接口 404，前端先因缺失解析/API/行为失败，再逐步实现；失败错误中的空白文本由响应解码测试驱动拒绝。按 code-review-and-quality 技能暂时反转“失败不能被 ready 清除”的条件，5 个组件测试中 4 个失败；恢复后完整 73 项通过，未保留变异代码。浏览器首次在沙箱内无法探测本地 webServer，按审批改为沙箱外运行，没有改超时或禁用断言。

五轴复核：报告属于 preview 模块；界面拆为 PreviewChecks 与有界解析辅助函数，未把报告状态混进 run/store。参数化 SQL、现有认证/CSRF/幂等、版本归属复用既有边界；错误仅作文本输出。最新报告用版本/序号索引，每次只读一条，无轮询或后台自动保存。新增直接依赖 Acorn 8.19.0（MIT，锁文件原有同版本），不升级其他依赖、不运行安装脚本；已知包体增长明确记录。采用 incremental-implementation 将报告、发布、公开状态和隧道分为相邻切片，security-and-hardening 将公网入口限定为后续公开路由白名单。

剩余范围：T25 原验收含发布门禁，随 T26 继续，故清单仍未全量勾选；T26–T29 发布指针、LIVE 数据、公开外壳、网关/隧道尚未实现。T21/T22 的重启演练、身份限流、HTTP 总请求体上限、总量配额/保留策略仍待办。浏览器报告可伪造，PASSED 不证明后续交互、语义正确或安全；直接 document 不经过宿主静态解析，但原 HTTP sandbox 仍有效。更多真实模型验收需另有预算，本轮未提高累计上限或运行 live 测试。没有修改 .env.local、操作 Docker 登录、推送、合并或开放公网。

决策见 [ADR-008](decisions/008-local-studio-public-apps.md)、[ADR-009](decisions/009-preview-check-reports.md)，公共接口与部署任务已同步。后端、客户端解析/API、报告界面、浏览器验收分别保存为本地提交 `5480836`、`580c5e9`、`3ae5ff0`、`c871786`。最终正常 8080 健康检查返回 UP。

## 固定版本发布、LIVE 数据与独立应用页 · 本地验收通过

2026-10-07，继续 T26–T28，并补齐 T25 的服务端发布门禁。正常 5173 工作台已接通发布入口；`/a/:slug` 可直接使用已发布应用。本轮没有启动网关/ngrok，没有产生新的模型请求，不把本地访问链接当作公网交付。

| 检查 | 结果 |
|---|---|
| `./mvnw -B verify` | 19 单元 + 40 PostgreSQL 集成测试通过，约 94 秒，无失败/跳过；正式 JAR 构建通过 |
| 前端 `test:unit` | 19 文件、99 项通过，无跳过 |
| typecheck / lint / format:check / build | 全部通过；JS gzip 95.50 kB、CSS gzip 4.40 kB |
| 运行时 Chrome 全量 | 15 项通过，约 1.8 分钟，含新增 2 项真实发布场景 |
| 普通工作台 Chrome 全量 | 14 项通过，约 1 分钟；两套浏览器顺序执行 |
| 正常后端与模型用量 | 8080 health=UP；只读核对累计预留仍为 11，3 SUCCEEDED/3 FAILED，无活动任务 |

后端验证包括：发布前必须有最新 PASSED 报告；所有者、CSRF、合法字段/修订号、当前版本和活动任务校验；两发布竞争只有一个成功；V1 发布后编辑 V2 不影响公开 V1，显式更新后才展示 V2，旧文档返回 409；旧发布意图重放不改变最新指针；下线再重新发布后，旧 DELETE 重放不会下线新发布。未发布/下线返回 404，不删除源码或数据。匿名元信息字段精确为 7 项，未暴露私有项目名、项目/所有者 ID。运行 HTML 保持 sandbox/CSP，只有符合条件的 document 响应允许同源嵌入。

LIVE 数据验证包括：匿名读状态 401；访客只读写自己的状态；相同项目 PREVIEW、LIVE 与其他访客/项目隔离；CSRF、非法 actor、CAS 冲突、同键异体及旧版本拒绝。代码更新保留已有 LIVE 数据，旧成功保存重放不写入；下线后包括重放在内的访问均 404，重新发布数据仍在。服务端从 principal 取 actor，客户端不能切换环境。

浏览器使用已有 test-run 固定待办和真实 API/PostgreSQL，不扩展测试装载器，不向正常库注入源码，不调用模型。用户流程为保存检查报告 → 发布确认 → 获取明确标注的本地链接 → 独立 Chrome 访客新增/刷新 → 第二访客空状态 → 下线 → 旧标签保存被拒绝且输入保留 → 下线页不创建 iframe → 重新发布原链接并恢复已存数据。另对已提交的 PUT/DELETE 响应故意返回损坏 JSON，显式重试的 URL/body/key 一致，最终 publication revision 只有 2。

界面单元测试覆盖当前 V2/上次发布 V1、明确同意才能更新、Clipboard 权限拒绝后手工复制、未知写结果原意图保留、成功写后读取失败不重复写、历史重放响应之后重新读取最新发布。V1→V2 更新指针由真实数据库测试覆盖；本轮未重新付费生成/修改应用，不将该部分称作第二轮真实模型或公网端到端验收。

公开页面和发布面板在 320/768/1024/1440px 无页面横向溢出，axe WCAG 2 A/AA、2.1 AA 无 violation；正常公开页面的 console warning/error 和 pageerror 为空，没有管理/健康/生成 API 请求。下线故障是明确构造的预期 404，不将故障阶段 404 算作正常阶段“零错误”。已查看桌面、390px 公开页面和发布面板截图：`frontend/test-results-runtime/publication-publishes-a-lo-9d0de--and-restores-the-same-link-chromium/` 下的 `public-app-desktop.png`、`public-app-mobile.png`、`publication-panel.png`。没有人工读屏或性能基准结论，不公开上传 trace/会话资料。

测试驱动与调试证据：

- 发布与 LIVE 接口分别先由真实数据库 404 失败驱动，再实现事务/权限逻辑；来源 origin 用例先发现非法端口未拒绝，补齐范围校验后通过。
- 客户端 204 解码、发布/PublicApp API、composable 与公开路由先失败后实现；保留现有错误、CSRF 和协议检查。
- 浏览器首次受沙箱本地 webServer 探测限制，确认服务正常后使用已批准的隔离 Chrome 方式执行，没有修改超时或跳过用例。
- 浏览器实际发现公开页初始化时短暂挂载工作台、请求 `/api/health`。根因是路由解析前 route.meta 尚为空；增加 matched 就绪门禁，补充初始公开地址回归。测试的网络过滤同时精确锚定 API 路径，不把 Vite 的 `/src/api/*.ts` 模块下载误算为 API 请求。
- 按 code-review-and-quality 要求，临时去掉上述 matched 门禁；初始公开路由用例正确失败，观察到多余 health/csrf 请求。恢复代码后 99 项全部通过，变异代码未保留。

五轴复核：发布模块管理固定指针，应用状态复用既有 CAS/幂等服务；前端拆分有界 API 解码、发布状态流程、页面组件和共享 frame 生命周期。无新依赖/无限重试/自动发布，发布失败不覆盖旧版本。SQL 参数化，匿名白名单只放行元信息与 document，数据接口仍需会话；输出按文本渲染，公网 origin 不取自请求头。项目级短事务锁、读取时装载发布快照适用于本地 Demo，未宣称高并发性能；总请求体上限/频率、总配额和清理仍属 T22/上线阻断项。

Java 保持 17.0.5。只正常重启本项目 8080/8081 服务，V009 已在正常/测试库增量应用，未删除数据或改变 Docker 登录。普通后端启动仍保留累计上限 11（剩余 0）；`.env.local` 未改动。实现分项本地提交：`4eaeb6f` 发布事务、`44752fa` LIVE 状态、`2dd9315` frame 复用、`483d530` API 客户端、`6b92a2c` 公开页面、`cf4a36b` 发布面板、`48d2b2f` 浏览器验收；未推送或合并。

T25–T28 的本地能力已完成。下一步为 T29 独立白名单网关和受控隧道接入；公开 HTTPS/独立域名 Cookie/外网验证尚未完成，不允许把 5173/8080 整站转发公网。T21 重启演练、T22 完整限制、T24 第二轮真实修改/T31 多类型模型质量继续保留待办。设计及回退边界见 [ADR-010](decisions/010-publication-and-live-state.md)。

## 单服务器发布包 · 本地 HTTPS 验收

2026-10-08。用户改为云服务器原生运行整套平台及 PostgreSQL，应用继续 `/a/<slug>` 路径发布；ADR-011 取代前述 ngrok 部署方向。本轮只准备并验证可上传的发布包，没有 SSH、没有实际公网部署，也没有调用 DeepSeek 或修改 `.env.local`。

| 检查 | 结果 |
|---|---|
| `node scripts/release.mjs` | 完整构建通过，输出约 25 MB tar.gz、归档 SHA-256、逐文件 SHA256SUMS 和源码 revision/dirty 元信息 |
| 后端 `clean verify` | 19 单元 + 42 PostgreSQL 集成测试通过，无失败/跳过；包含新增 2 项 server profile / Secure Cookie / 本机 TLS 代理测试 |
| 前端 typecheck / lint / format:check / test:unit / build | 通过，19 文件 100 项单元测试；JS gzip 95.51 kB、CSS gzip 4.40 kB |
| `node --test scripts/release.test.mjs` | 2 项通过：白名单和哈希/重复打包不覆盖；源码映射、符号链接和缺失产物拒绝 |
| `npm audit --audit-level=high` | 当前锁文件 0 已知漏洞；没有新增或升级前后端依赖 |
| `node scripts/smoke-release.mjs .../atoms-demo-<release-id>` | 生产 JAR + 静态资源经回环 HTTPS 代理，3 项真实 Chrome 测试通过，24.9 秒 |
| 本地 `test:e2e:runtime` 回归 | 15 项全部通过，1.7 分钟；没有跳过或修改既有断言 |

实际验收使用发布包内 `server/start.sh` 启动 8082，显式独立配置连接 `atoms_demo_test` / `atoms_test`，模型 Key 为空、预算 0、Worker 关闭。已有 8081 夹具入口只用于向测试库装载固定待办，测试代码没有打入生产 JAR。Docker Desktop 仅运行一次性 Nginx TLS 测试代理，公网服务器不需要 Docker。使用官方镜像镜像源 `public.ecr.aws/docker/library/nginx`，固定 digest `sha256:0985e772fb9f729e6fa0980da05fca5d9c468e870eed43071545afa9d2e27d94`，只绑定 `127.0.0.1:18443`。测试证书不进入发布包；结束后停止拥有的代理/Java 子进程并删除临时凭据和测试私钥，未删除用户数据。

浏览器证据：HTTPS 首页创建访客和项目，深链接刷新恢复；已有固定产物通过预览检查后发布，链接是配置的 HTTPS origin 加 `/a/<slug>`；访客新增任务刷新仍在，另一访客看不到；显式下线后无法新访问。正常首页与公开页没有 console warning/error 或 pageerror。运行 document 只有一个 sandbox CSP 且 XFO 为 SAMEORIGIN，未被 Nginx 静态页策略覆盖；未知公开 API 返回 JSON 404、缺失 assets/测试页/点文件返回 404、超过 512 KiB 的请求返回 413；伪造 X-Forwarded-Proto 被代理覆盖，仍返回 HTTPS HSTS 和安全 Cookie。已查看桌面及 390px 截图，位于 `frontend/test-results-release/release-packaged-preview-p-074de-tate-work-through-nginx-TLS-chromium/`，不公开上传测试会话或 trace。

测试驱动/审查记录：ServerProfileIT 先因缺少 Secure 标记失败，新增 server 配置后通过；前端新增服务器 origin 文案测试先因旧“工作台仅本地”文案失败，改为准确描述私有数据边界后通过。首次归档拒绝未列入白名单的 favicon.svg，新增最小复现再明确允许该既有静态资源，没有放宽为复制整个目录。按 code-review-and-quality 暂时允许 `.map`，拒绝测试正确失败；恢复后 2 项全通过。Maven clean 插件下载和 npm audit 初次受沙箱网络限制，按审批重跑后成功；Docker Hub 返回 EOF 后从官方镜像 ECR 源拉取，没有退出/更改 Docker 登录。

本地运行时回归的首轮 4 通过 / 11 失败，失败快照为浏览器 ERR_CONNECTION_REFUSED，未到达 5174 工作台；后续沙箱内 webServer 启动检查也失败。对同一已启动端口，沙箱内 curl 连接失败、获批的沙箱外 curl 返回 200。显式让回环请求绕过系统代理，并在沙箱外重跑后 15 项全部通过，没有改业务代码、延长断言超时或忽略失败。

五轴复核：复用全部既有业务 API 和发布数据模型，无新增迁移/账号流程/应用容器；配置与发布物分离，启动脚本不执行配置为 shell；JAR 和白名单静态文件不含夹具、密码、个人会话、数据库备份或 node_modules。后端只监听回环，仅信任本机代理；Nginx 不添加 API CSP/XFO、不缓存/缓冲 SSE，更新采用单实例停机切换，不同时运行两个 Worker。编译期包检查不会替代目标主机的安全审核。

边界：本地 HTTPS 使用测试证书，不代表真实证书/DNS/外网已通过。原生 Linux 安装、systemd 启停、数据库首次初始化/备份恢复、实际反代下的 SSE 重连和进程重启持久化仍等 SSH 后验收；身份限流与存储总配额也没有因打包而完成。新服务器默认新库且预算 0，不自动迁移本机业务数据或恢复模型额度。T29 总任务继续未完成。部署流程由 incremental-implementation / shipping-and-launch 约束为可验证发布包、独立凭据、回退说明和明确的远程验收门槛。

实现提交：`aac1823` 服务器配置、`52258d0` 部署中立文案、`02bb49e` 发布包/模板、`3c1c604` 生产包浏览器验证。未推送或合并。打包命令和远程操作说明见 [服务器部署说明](../deploy/server/README.md)。

## 云服务器首次部署 · 功能验收

基础部署与持久化已进行验证。实际主机参数、证书、备份及运行记录由维护者私下保存，通用步骤见[部署说明](cloud-deployment.md)。

## 官方实现依据

- [Vue 表单及中文输入法](https://vuejs.org/guide/essentials/forms.html)
- [Vue Router 路由](https://router.vuejs.org/guide/)
- [Pinia setup store](https://pinia.vuejs.org/core-concepts/)
- [Vite 同源开发代理](https://vite.dev/config/server-options.html#server-proxy)
- [Vitest 配置](https://vitest.dev/config/)
- [Playwright 独立测试与配置](https://playwright.dev/docs/test-configuration)
- [Vue ESLint](https://eslint.vuejs.org/user-guide/)
- [Spring OncePerRequestFilter](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/web/filter/OncePerRequestFilter.html)
