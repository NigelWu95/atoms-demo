# ADR-007 · 持久任务事件与工作台 SSE

## 状态

已采纳并实现，2026-10-07。替代 ADR-006 的工作台短轮询部分，不改变模型调用、任务执行和付费预算策略。仅本地验收，公网代理行为尚待验证。

## 背景与决定

生成主流程已经可用，需要在刷新、断线及项目切换后继续观察同一个任务，而不重复发起付费请求。复用现有 `run_events` 作为唯一事件源：在任务事务中分配 seq 并保存，SSE 只读已提交记录；每次先读 run 快照水位，再读不超过该水位的下一批事件。历史回放和尾随使用同一条查询路径，没有内存订阅切换空窗。不增加 Redis、消息队列、模型 SDK 或数据库迁移。

继续全量轮询会重复读取项目、消息和配置；仅内存事件无法恢复断线。采用 Spring MVC SseEmitter 和原生 EventSource，与现有 Cookie 会话及 Java 17 兼容。SSE 写入可能阻塞，使用独立 4 线程池、全局最多 32 条连接，不占用生成 Worker。每批 100 条，每 500ms 尾随，15 秒注释心跳，60 秒轮换连接；满连接返回 429。这是小规模本地 Demo 的资源边界，不是吞吐量或慢客户端压测证明。

## 协议和用户体验

- 先验证所有者，再检查规范非负安全整数游标。非空 Last-Event-ID 优先于 after；终态已补齐时返回 204。
- 每帧包含 runId、seq、createdAt，终态另含 lastEventSeq；不发送思维链、模拟 token 或虚构百分比。事件缺口返回明确错误，不能假装完整。
- 客户端从快照游标继续，去重并拒绝跳号、跨任务和畸形事件。规划完成读取持久对话，阶段直接更新；完整终态之后再依次读取任务及依赖的项目/消息，避免使用旧版本快照。
- 自动重连只重建只读连接。连续三次失败暂停，手动“刷新生成状态”恢复。收到新事件或连接已稳定至少 15 秒才重置失败计数，防止“200 后立即关闭”的无限重连，也允许健康空闲连接轮换。
- 页面卸载/项目切换关闭流，晚到回调被忽略；断线不取消任务。已有预览、输入恢复、取消与原键重试保留。

## 验证、安全与限制

真实 PostgreSQL 集成测试覆盖即时帧、同连接尾随取消、断线补发、107 条有序回放、回滚事件不泄露、心跳、终态 204、越权和非法游标。隔离 Chrome 在普通工作台使用本地 HTTP 事件替身验证 native Last-Event-ID、重复事件去重、阶段/规划展示及终态刷新；不调用 DeepSeek。route.fulfill 的拦截请求没有呈现 Last-Event-ID，改用真实回环 HTTP 服务在服务端检查请求头后通过，未削弱游标断言。

沿用身份与 CSRF 边界；GET 事件接口不修改业务状态。错误响应显式 JSON Content-Type，避免 Accept: text/event-stream 导致正常业务错误转成内容协商 500。校验错误仅固定平台消息，不回显外部内容。

剩余限制：事件暂不清理；没有跨实例协调、按身份 SSE 连接配额或慢客户端负载验收。Nginx/云代理缓冲、超时与 HTTPS 需上线时实测。真实 DeepSeek 生成闭环沿用上轮证据，本轮未以付费请求重跑；累计请求仍为 11、授权余量 0。详细证据见 [verification.md](../verification.md)。

## 官方依据

- [HTML EventSource 协议与 Last-Event-ID](https://html.spec.whatwg.org/multipage/server-sent-events.html)
- [Spring Framework 6.2 MVC 异步与 SSE](https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-ann-async.html)
- [Playwright 路由与请求继续发送](https://playwright.dev/docs/api/class-route#route-continue)
