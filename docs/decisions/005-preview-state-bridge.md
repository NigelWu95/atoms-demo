# ADR-005 · 预览消息桥与独立应用状态

## 状态

已接受，2026-10-07。实现 T12–T15 的本地预览基础，不接入 OAuth、模型调用或公开发布。

## 背景与决定

代码版本不可变，但应用数据需要持续修改。应用脚本属于不可信输入；可信工作台、会话、其他项目和持久数据是需要保护的资产。边界分别是 HTTP 会话→项目、运行 iframe→宿主消息桥、宿主→状态 API。

- V005 添加 app_states，主键为 `(project_id, environment, actor_id)`；当前只有 PREVIEW 接口。actor 来自服务端会话，owner 检查不能由 iframe 覆盖。value 是至多 256 KiB 的 JSON 对象；不是动态 SQL 或通用数据库接口。
- 缺少记录时返回所选当前版本 initialState/revision=0，不在读取时写库。首次 INSERT 唯一约束与后续修订号条件 UPDATE 防止覆盖；两次并发写入只有一个成功。源码更新不重置状态。
- 状态读写与代码指针变更使用同一项目行锁。保存先重新鉴权，再进行事务内幂等重放；旧已提交意图重放原成功结果，新意图必须校验当前代码版本。事务内不做网络请求。
- 每次预览创建全新 iframe、UUID channelId，src fragment 为 `#atoms-channel=<UUID>`。fragment 不含会话、CSRF、模型密钥或业务数据。受限文档在此模式下必须先握手，再注入应用 CSS/JS，防止首次 load 发生前应用导航到其他文档。
- Bootstrap 只接受 window.parent 且 origin 等于运行文档 URL origin 的 INIT，再返回 HELLO。宿主同时验证 source===iframe.contentWindow、channel 和字段结构，之后一次性转移 MessagePort。opaque iframe 方向使用 `*`，只发送非秘密初始化和通道，不以 `origin=null` 授权。
- 仅允许 STATE_GET/STATE_SAVE/RUNTIME_READY/RUNTIME_ERROR。生命周期通知不受数据请求的 16 个并发上限阻挡。保存输入无法指定项目、身份、环境、URL、header 或发布操作。结构有效但状态无效时明确返回 INVALID_INPUT，防止误判为未知结果。
- 宿主握手/启动期限 10 秒；SDK RPC 期限 15 秒。销毁通知关闭旧 port、拒绝 SDK pending；旧异步结果不更新新界面。后续 iframe load 表示导航，旧通道关闭，需用户手动创建新的预览。iframe load 本身不代表运行成功，启动提示来自 ready 回调完成。
- 两端 ID 都要求 UUID，宿主保存键为 channelId:requestId，共 73 字符。SDK 保存失败若结果不明（网络、超时、异常 2xx）保留不可变输入及原 requestId；相同内容重试复用键，不自动重试或静默覆盖。队列已满且尚未发出保存时不创建假“未知”意图。显式业务拒绝允许更正输入。
- 重新读取不代表取消一笔可能仍在提交的请求。未知保存必须先重试原意图；重新加载页面会丢失内存输入，且不能撤销已发出的写请求，UI 明示该限制。
- HISTORY 只读取该版本 initialState，保存明确拒绝，不访问当前持久数据。初始数据先序列化为 JSON 快照，避免 Vue Proxy 无法通过 structured clone 的问题。源码使用转义文本展示，不进入管理 DOM 执行。
- 版本历史分页；独立 `/preview/:projectId/:versionId` 先读取有权限的项目/版本，再复用 PreviewFrame。它是私有预览，不是发布链接。

## 复核与取舍

独立单模型审查三轮提出的提前导航、未知保存误清除、异常成功响应、幂等键字符限制、无效输入卡死、校验不一致、满队列意图与生命周期通知问题均已落实到契约、实现或回归测试。跨模型选项已向用户提供，未调用外部 CLI。三轮后不扩大审查循环，最后一处队列通知问题通过失败用例和修复后验证处理。

这里没有 iframe 内部的“可信 SDK 密钥”：生成脚本能访问自己的 SDK 和本项目数据，MessagePort 只限制它能请求什么。仍保留 ADR-004 的 CPU、内存和导航隔离限制，不能作为通用恶意代码执行环境或安全认证。

256 KiB 是单份状态上限，不是总存储配额。幂等成功响应保留完整原状态用于精确重放，目前不自动清理；上线前需要总配额、明确的重试/保留窗口与删除路径。HTTP 总请求体上限、限流、后端依赖扫描、Java 安全补丁及 HTTPS 仍是上线门槛。演示请勿输入敏感个人数据。

回滚使用前一应用版本即可忽略新增表，不删除 app_states、幂等记录、项目或卷；不提供破坏性回滚迁移。手工待办只在 test classpath 和测试库存在，不进入正式 JAR/生成入口。5174 是相同前端的测试库代理，5173 保持正常开发数据库。

## 官方实现依据

- [MDN postMessage：source、origin 与传递端口](https://developer.mozilla.org/en-US/docs/Web/API/Window/postMessage)
- [MDN MessagePort.close](https://developer.mozilla.org/en-US/docs/Web/API/MessagePort/close)
- [PostgreSQL 17 INSERT / ON CONFLICT](https://www.postgresql.org/docs/17/sql-insert.html)
- [Vue watcher 清理异步副作用](https://vuejs.org/guide/essentials/watchers.html#side-effect-cleanup)
- [Vite mode 条件配置](https://vite.dev/config/#conditional-config)
- [Playwright 多服务测试配置](https://playwright.dev/docs/test-webserver#multiple-web-servers)
- [Jackson 2.19 默认流式解析约束](https://github.com/FasterXML/jackson-core/blob/2.19/src/main/java/com/fasterxml/jackson/core/StreamReadConstraints.java)
