# ADR-006 · DeepSeek 驱动的主工作台生成

## 状态

实施中，2026-10-07。用户选择 DeepSeek 官方服务与 deepseek-flash；保持 Java 17、访客身份、本地单实例与已有运行沙箱。

## 决定与实施切片

1. 服务端使用 JDK HttpClient 调用固定 `https://api.deepseek.com/chat/completions`，参数 thinking=enabled、reasoning_effort=high、stream=false。不增加 SDK 依赖，不接受访客提供上游 URL 或密钥。
2. 先完成有超时、取消、响应大小上限和脱敏错误的网关；随后接入数据库任务、消息、后台 Planner/Builder、校验及最多一次修复；最后接入普通 5173 工作台。每个切片分别测试提交，不扩展测试工作台功能。
3. 用户需求和当前代码会发送给 DeepSeek；业务状态、会话、平台密钥和其他项目不会进入模型上下文。网页明确提示勿输入敏感资料。reasoning_content 不保存、不展示、不记录日志。
4. 请求先原子落库再由单个 Worker 执行，事务不跨模型等待；保存代码与任务终态同事务。消息与任务查询均验证所有者，提交按用户意图幂等。
5. 本轮先用短轮询展示真实持久阶段并恢复刷新后的任务；SSE 保留 T20 待办，不把轮询说成流式模型输出。网页首次生成及追加修改优先于完善 SSE。
6. 凭据缺失或预算未启用时明确拒绝生成，不返回固定模板成功。真实调用需用户确认额度；本地协议替身仅用于自动测试，不能算真实模型验收。
7. 本地调用次数预算持久化，默认 0；每次外部请求先预留，未知结果不退回、不自动重试。请求数并非货币额度，同时限制 prompt、输出 token、总响应大小、并发和运行时间。上线前仍需完整 T22 预算/限流验收。

## 安全与回滚

不可信边界为访客→任务 API、模型→产物校验器、应用→已有沙箱。保护资产为项目源码、业务数据、模型密钥和调用额度。拒绝跨项目访问、任意 URL、错误基线、重复活动任务及未校验产物。新表增量迁移；回滚旧应用忽略新表，不删数据或更改已应用迁移。模型错误只向用户返回固定错误类型；没有上游响应/完整 prompt 日志。

## 官方依据

- [DeepSeek 首次调用](https://api-docs.deepseek.com/zh-cn/)
- [Chat Completions 参数、JSON 格式与响应](https://api-docs.deepseek.com/zh-cn/api/create-chat-completion)
- [DeepSeek 错误码](https://api-docs.deepseek.com/zh-cn/quick_start/error_codes/)
- [JDK 17 BodySubscriber：限制响应并取消订阅](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpResponse.BodySubscriber.html)
- [JDK 17 HttpRequest 超时](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpRequest.Builder.html#timeout(java.time.Duration))
- [Spring 调度任务](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)
