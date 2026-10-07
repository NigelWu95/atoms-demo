# ADR-010 · 固定版本发布与公开应用数据

## 状态与范围

2026-10-07，T26–T28 本地实现。普通 5173 工作台具备发布面板，`/a/:slug` 具备独立应用外壳。没有启动公网隧道；独立网关、公开域名会话隔离和 HTTPS 验收仍属 T29。不能直接将 5173/8080 转发到公网。

## 决定

V009 新增 publications，一项目一条发布指针，首次发布分配不可变的随机 32 位十六进制 slug。发布引用已有不可变版本，不复制源码、不修改项目 revision。发布/更新必须由项目所有者显式确认，且同时满足当前版本、项目修订号、发布修订号、无活动生成和最新预览报告 PASSED。浏览器报告不是安全认证，所有者仍需检查公开内容。

所有修改先锁项目行，再读取发布指针及最新检查报告。公开文档/状态也采用相同锁顺序，并在取得锁后重新读取 PUBLISHED 指针，避免读取旧指针后跨过下线/更新继续写入。此 Demo 有意采用简单的项目级串行化；高并发访问同一应用会争用锁，尚未做容量压测，不能宣称生产规模支持。

发布 PUT 与下线 DELETE 均要求 CSRF、幂等键和预期 publication revision。DELETE 查询参数携带修订号，服务端将其放入幂等摘要；旧成功下线请求在重新发布后重放只返回原 204，不再次执行下线。下线保留 slug、代码和访客数据，重新发布继续使用同一链接。新意图发生冲突返回 409，不覆盖其他操作。

匿名元信息只包含 slug、versionId、versionNo、title、summary、stateSchemaVersion、initialState；不会返回 projectId、ownerId、私有需求、版本历史或 PREVIEW 状态。匿名文档只允许当前发布版本，保留原 sandbox/CSP；不存在或下线为 404 APP_NOT_PUBLISHED，已过期版本为 409 STALE_APP_VERSION。知道 slug 不是管理权限。

LIVE 与 PREVIEW 复用 app_states，主键按项目、环境、访问者隔离。访问者先建立原有访客会话，服务端从 principal 获取 actor，桥消息不能选择项目、环境或 actor。GET 空状态返回初始数据；PUT 使用 256 KiB JSON 上限、schema 1、CAS 和幂等键。发布更新不清空或复制应用状态。应用仍上线时，旧保存意图可重放原成功响应但不再写；下线后包括成功重放在内的读写均拒绝。

## 界面与失败恢复

工作台明确区分当前版本和上次发布版本，用户确认公开页面、代码与初始数据后才能发布。报告未通过时指向预览检查，并提供只读刷新。下线另有确认；复制失败保留可手工选择的只读链接。

发布/下线结果未知时，保留原请求体、版本、修订号和幂等键，阻止新的发布操作。确认后重新 GET 最新发布状态，不盲信可能来自历史重放的响应。确认成功后的读取失败只重试读取，不重复写。待确认意图保留于当前组件会话，刷新/离开页面会丢失该内存意图；返回时应先读取服务器状态，不能把刷新当撤销。

公开外壳不挂载工作台导航、项目列表或服务状态组件；路由尚未解析时也不挂载，避免启动阶段误请求私有功能。私有预览和公开应用共享 mountFrame/bridge/SDK，而不是伪造私有 Version 对象。公开桥固定绑定 slug 和加载版本，仅提供状态读写；不会提供生成、发布或源码管理能力。状态失败保留输入；用户显式重新加载时再读取元信息，界面提示未保存内容将丢失。

链接来自受信任的服务端 `PUBLIC_APP_ORIGIN`，只接受 HTTPS origin 或本机 loopback HTTP origin，不接受路径、认证资料、查询、fragment 或非法端口，不读取 Host/转发头构造链接。未配置时 API 返回 publicUrl=null，UI 展示明确标注的“本地访问链接（尚未接入公网）”。只配置域名不等于已经部署网关或开启隧道。

## 限制与回退

- 当前本地工作台与公开外壳同源，同一浏览器可复用访客身份，但 PREVIEW/LIVE 数据仍分离。未来独立公开域名的 Secure Cookie、路由白名单、编码绕过防护、请求体/全局频率限制必须在 T29/T22 完成。
- 已下载到浏览器的源码不能因下线被收回；下线仅拒绝后续服务器访问。浏览器 Cookie 丢失/过期无法找回访客状态，不承诺跨设备同步或共享数据。
- 总状态/报告配额、数据保留清理、CPU 隔离、完整重启演练仍未完成。当前没有上线安全或可用性承诺。
- 回退优先隐藏发布入口并保持无公网入口；保留 V009 和业务数据。不要改已应用迁移、删表、回滚数据库卷或重置模型用量。旧版本应用回退需先评估 Flyway 对额外迁移的校验，不能假设直接换旧 JAR 即可。

## 官方依据与验证

- [PostgreSQL 17 行级锁](https://www.postgresql.org/docs/17/explicit-locking.html#LOCKING-ROWS)
- [Spring Security 6.5 请求授权与匹配顺序](https://docs.spring.io/spring-security/reference/6.5/servlet/authorization/authorize-http-requests.html)
- [Spring 6.2 声明式事务与代理边界](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/declarative/annotations.html)
- [Vue 副作用清理](https://vuejs.org/guide/essentials/watchers.html#side-effect-cleanup)、[Vue Router 路由元信息](https://router.vuejs.org/guide/advanced/meta.html)
- [Clipboard.writeText 安全上下文与拒绝行为](https://developer.mozilla.org/en-US/docs/Web/API/Clipboard/writeText)

完整验证与测试夹具边界见 [verification.md](../verification.md)。没有新增依赖，不调用模型。
