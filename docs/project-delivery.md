# 完整工程交付

2026-10-29 更新：本地已向访客和登录账号启用完整工程开发，并增加独立端口发布。就绪应用可在新窗口直接访问，工作台提供链接及停止 / 下线入口；不接入远程服务器。使用方法和实际 2048 启停验收见[本地发布说明](https://github.com/NigelWu95/atoms-demo/blob/main/docs/local-publication.md)。下文的默认关闭描述保留原模块验收背景，当前本地配置已覆盖。

本模块提供真实应用预览、固定源码 ZIP 下载和源码版本恢复的后端能力。真实 Vue + Spring Boot + PostgreSQL 待办应用已通过平台预览、下载后独立构建运行及重启持久化验收；最终兼容性结果见[验证记录](project-delivery-verification.md)。**正式工作台已接入这些接口，新模式仍默认关闭。** 原有工作台和导出流程继续保留。

规格见 [SPEC-project-delivery.md](../SPEC-project-delivery.md)，实施项见[任务清单](../tasks/project-delivery/todo.md)。

## 预览与下载如何保持一致

预览固定在一个已就绪的运行，并绑定其 owner / project / sourceVersion / tree / build / JAR / manifest / run / execution 身份。网页和 JSON 业务 API 由该应用自己的 Java 进程提供，经平台的受控路径转发；不是再把生成内容装进平台 SDK 页面。

下载从该构建对应的不可变 Git 快照读取 Vue、Java、SQL、锁文件、脚本、配置样例和测试。不会读取正在修改的目录，也不会按最新模板重新拼工程。ZIP 中原文件在 `project/`，身份清单和交付说明在 `delivery/`。文件字节与 Git 执行位保留；不含 JAR、node_modules、平台账号/对话、私有配置或业务数据。

相同的是源码及验收过的业务行为。下载工程使用单独的数据库和配置，已有业务数据不会随源码复制；它自己的新增数据会在自己的数据库中持久化。构建工具与依赖环境不同也可能使 JAR 字节不同，不将“同源码”表述成跨环境可重复二进制构建。

实际验收覆盖了：平台页面新增/修改/冲突刷新；HTTP 下载 ZIP 后逐文件比对；下载工程自己的脚本构建和启动；下载版同样的浏览器操作；两次进程重启后数据保留；原 A/B 数据不被覆盖。恢复源码产生新版本后，仍在运行的旧预览继续下载它原来的源码，ZIP 字节保持相同。

## 管理接口

以下路径均在 `/api/projects/{projectId}/delivery` 下，ID 为规范 UUID。沿用平台登录会话、维护者配置、项目归属和归档检查；写操作需要原 CSRF。接口默认关闭，不能通过猜测 ID 获得运行或文件访问权。

| 方法与路径 | 作用 |
|---|---|
| GET `/builds/{buildId}` | 固定构建身份和源码文件数/大小 |
| GET `/builds/{buildId}/source.zip` | 下载成功构建的固定源码；运行已停止仍可下载 |
| POST `/runs/{runId}/previews` | 为准确就绪运行创建短期预览，返回 previewId、expiresAt、iframeUrl 和身份 |
| DELETE `/previews/{previewId}` | 撤销自己的预览授权，不停止应用进程 |
| GET `/previews/{previewId}/source.zip` | 下载原登录会话的有效预览源码；失效后拒绝，不转为最新版本 |
| GET `/source-head` | 当前源码版本、revision 和状态 |
| POST `/restorations` | 按准确目标及预期 head 追加恢复版本，返回 202 |
| GET `/operations/{operationId}` | 查询该项目的源码恢复操作 |

创建/撤销预览只接受无正文或空对象，不接受目标 URL、端口、路径或额外 query。预览访问路径是 `/api/delivery/preview/{token}/...`；`iframeUrl` 含短期访问令牌，应按秘密处理，不记录日志或长期保存。下载使用登录会话，不使用 iframe 路径令牌。

## 源码恢复

先取得 `/source-head`，再向 `/restorations` 提交 `targetSourceVersionId`、`expectedSourceVersionId`、`expectedRevision`，同时携带 CSRF 和唯一 `Idempotency-Key`。202 表示操作已接收；以查询到的 `COMMITTED` 和 `resultSourceVersionId` 判断成功。

相同请求与幂等键只对应同一操作，旧 revision 冲突返回 409。恢复追加历史，不移动或删除旧 Git 版本，不自动构建、启动、切换预览或回滚业务数据。未知结果保留 `NEEDS_RECONCILIATION`，不能换一个请求标识盲目重复提交。

启动失败后的数据库确认与源码恢复是两种不同操作。已停止但未 READY 的运行可能需要维护者核对当前数据库状态。重启后的维护入口只处理明确选择、具备持久停止证明的运行；复核源码/产物/绑定/迁移历史后，显式确认并追加审计。它不会接管旧进程或回滚数据。实现和本轮固定记录的确认见[恢复说明](design/project-delivery/startup-recovery-review.md)。

## 下载工程怎么运行

在解压的 `project/` 中，使用工程自己的脚本：

```sh
node scripts/build.mjs --test-config /absolute/private/test.properties
node scripts/start.mjs --config /absolute/private/runtime.properties
```

准备 Java 17、Node 24.20.x、npm 11.19.x，以及独立的 PostgreSQL 运行库和测试库。Maven 由工程 wrapper 使用；首次依赖安装需要可用的依赖源；平台执行器使用的工具版本与摘要仍须符合其绑定策略。具体配置字段、用途标记、非特权角色和空测试 schema 的要求见[工程模板说明](../templates/full-project-v1/README.md)。运行库和测试库不能混用。

两个配置文件放在源码目录之外，权限 0600。构建脚本向 Maven/Java 测试传递测试配置路径，不向 npm 传递数据库凭据；测试回滚自己的数据，不清库。启动脚本以前台本机 Java 进程运行已构建 JAR，地址/端口由独立运行配置确定。旧源码快照不在下载时补丁升级。

## 平台配置和边界

- `PROJECT_DELIVERY_ENABLED` 默认 `false`；开启时必须设置明确的 `PROJECT_DELIVERY_ORIGIN`，如开发页面的 `http://127.0.0.1:5174`。只含协议、主机、端口，不含末尾 `/`、路径、query 或 fragment；不从 Host/转发头推导。
- 开启交付不自动开启执行模块或创建数据库绑定。仍需既有执行器、源代码协调器、受信维护者及项目自己的绑定。
- 正式嵌入保持 `sandbox="allow-scripts allow-forms" referrerpolicy="no-referrer"`，不加入 allow-same-origin。allow-forms 支持 Vue 的 JS 表单事件，CSP `form-action 'none'` 仍阻止原生表单提交。浏览器不能读取平台 Cookie、父页或平台管理 API。
- 每次读取持续核对原会话和准确运行，不靠缓存的 READY 状态。停止、换版、撤销、登出或过期使旧预览失效；浏览预览不会续期平台登录。
- 预览授权十分钟，最多 32 个/每会话 8 个；HTTP 交换最多 16 个/每授权 4 个；下载最多 2 个/每会话 1 个。交换和下载各使用单一 30 秒预算；取消后实际未结束的工作继续占名额，避免重复派发。
- 首版支持相对资源 URL、ES modules 和有界 JSON CRUD；也支持不声明 Content-Type 且无请求体的 POST 动作接口，以及无请求体 DELETE。非空请求仍要求合法 JSON；空 POST 仍按写操作校验权限和转发。Cookie 登录、localStorage/IndexedDB、Service Worker、WebSocket/SSE、大文件传输、外站资源和 history 路由回退不在预览契约内；不通过开放同源执行来掩盖不兼容。
- 应用账号可以使用自身后端签发的 Bearer 会话：预览仅在 `/api/` 接受显式 `Authorization: Bearer …`，令牌字符和长度受限；静态资源、Basic 认证及异常头拒绝。隔离 iframe 的 CORS 预检允许该头，仍不开放凭据 Cookie。平台会话 Cookie、CSRF 头和 Referer 不转发给应用。生成的前端应使用 `credentials: 'omit'` 并把应用令牌留在内存；刷新后重新登录，从应用数据库恢复数据。平台预览授权和应用登录是两个独立层次。

正式工作台的文件树、Agent 需求、构建/启动编排见[工作台模块](https://github.com/NigelWu95/atoms-demo/blob/main/docs/project-workbench.md)。完整工程新模式的真实付费模型闭环、公开发布和远程部署未在本次验收中完成；本次没有增加服务器、外网隧道或独立实例服务。
