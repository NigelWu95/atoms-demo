# ADR-006 · DeepSeek 驱动的主工作台生成

## 状态

已采纳并实现本地接入，2026-10-07；已完成真实生成、交互保存、刷新恢复及一次修改保留数据的浏览器闭环验收。用户选择 DeepSeek 官方服务与 deepseek-flash；保持 Java 17、访客身份、本地单实例与已有运行沙箱。下文短轮询为首次接入时的决定，现由 [ADR-007](007-persisted-run-events.md) 的持久事件 SSE 方案替代；其余模型与执行决策不变。

## 决定与实施切片

1. 服务端使用 JDK HttpClient 调用固定 `https://api.deepseek.com/chat/completions`，参数 thinking=enabled、reasoning_effort=high、stream=false。不增加 SDK 依赖，不接受访客提供上游 URL 或密钥。
2. 先完成有超时、取消、响应大小上限和脱敏错误的网关；随后接入数据库任务、消息、后台 Planner/Builder、校验及最多一次修复；最后接入普通 5173 工作台。每个切片分别测试提交，不扩展测试工作台功能。
3. 用户需求和当前代码会发送给 DeepSeek；业务状态、会话、平台密钥和其他项目不会进入模型上下文。网页明确提示勿输入敏感资料。reasoning_content 不保存、不展示、不记录日志。
4. 请求先原子落库再由单个 Worker 执行，事务不跨模型等待；保存代码与任务终态同事务。消息与任务查询均验证所有者，提交按用户意图幂等。
5. 本轮先用短轮询展示真实持久阶段并恢复刷新后的任务；SSE 保留 T20 待办，不把轮询说成流式模型输出。网页首次生成及追加修改优先于完善 SSE。
6. 凭据缺失或预算未启用时明确拒绝生成，不返回固定模板成功。真实调用需用户确认额度；本地协议替身仅用于自动测试，不能算真实模型验收。
7. 本地调用次数预算持久化，默认 0；每次外部请求先预留，未知结果不退回、不自动重试。请求数并非货币额度，同时限制 prompt、输出 token、总响应大小、并发和运行时间。上线前仍需完整 T22 预算/限流验收。

## 当前交互与限制

普通工作台支持首次生成、基于当前版本追加修改、查询对话、取消、刷新恢复和结果自动预览。已提交内容保存在 PostgreSQL；草稿和结果未知的提交意图键仅在前端内存保存。成功状态表示结构校验通过且版本已保存，不代表功能或浏览器运行已验收。

单实例 1 Worker、活动队列 8、整轮 6 分钟；每轮正常 2 请求，契约失败最多再修复 1 次。环境变量 GENERATION_MAX_MODEL_CALLS 是数据库生命周期累计请求绝对上限，默认 0；上限不自动按天重置。请求预留与调用记录同事务提交，未知用量保守记账。所有者级频率限制、SSE 与多实例协调尚未实现。

界面每 1.5 秒读取活动任务；终态之后才读取项目指针和消息，避免并发快照使预览停留在旧版。断网暂停轮询，可手动刷新；未知提交保留原请求和幂等键。后台不依赖浏览器连接；启动恢复把原 RUNNING 标为 INTERRUPTED，QUEUED 留待领取。此策略仅适用于单实例，不能直接水平扩容。

真实验收暴露 Planner 可能返回字段数量不符的对象。保持严格校验，未删除断言、默默丢弃字段或用模板代替模型；错误只携带平台定义的字段名/格式要求，不携带模型原文。Planner 增加确切 JSON 示例，并显式要求修改时也只输出六字段规划而非输入 Artifact；调整后的真实 CREATE/MODIFY 规划均已通过，但不是任意需求的稳定性保证。

后续还遇到一次非 stop 结束，旧诊断没有保留具体结束原因，不能倒推为 token 超限。网关现按官方枚举将长度限制、过滤、推理资源不足、中断及不支持的工具调用映射为固定脱敏消息，未知值仍使用通用错误；保持 MODEL_INCOMPLETE 和拒绝保存语义，不自动重试。Builder 对小应用增加 <=12 KiB 的精简设计目标，必须保留全部需求、无障碍、持久化、冲突和重试；120 KiB 校验上限及模型参数不变。该提示不是安全边界，也不是硬性截断。

用户追加最多 6 次后，本地累计上限临时设为原已用 5 + 6 = 11；本轮先失败 2 次，再以 4 次完成待办 V1 → V2 筛选及数据保留。本地额度现为 0，不自动提高。原密钥/数据库配置未改。当时非 stop 响应的 usage 未持久化，保留 UNKNOWN 而非记为免费或零消耗；历史 token 统计仅为已知部分，第二轮修改及多类型应用仍待验收。

## 截断诊断与用量记账

2026-10-08 云端 2048 需求在 BUILDING 收到 length 结束，旧网关先抛异常、后解析 usage，导致失败调用缺少诊断。现只携带经过边界校验的结束枚举与非负整数计数，不保留响应正文或 reasoning_content。网关原有成功返回值及 MODEL_INCOMPLETE 对外语义不变，无数据库迁移。

排障需要回答三个问题：

1. 哪个任务、哪个阶段因何停止？`model_call` JSON 日志包含 runId、callId、stage、outcome、durationMs；非 stop completion 附 finishReason，未知原因归一为 other，不回显上游字符串。
2. 截断时限制和消耗是多少？日志附 outputTokenLimit、promptTokens、completionTokens、totalTokens、reasoningTokens；model_calls 持久化前三项用量及 FAILED。缺失、负数、非整数或超出 Long 的计数保留 null，不当成 0，不由长度反推用量。reasoningTokens 只取官方 usage 计数，不读取思考正文。
3. 失败会重试或扣回额度吗？不会。未取得 completion 的连接错误等仍为 UNKNOWN；所有已预留请求都计入累计上限，不退额度，不保存残缺产物。旧失败记录无法追溯补齐。

用户授权云端单次上限提高至 32768 tokens、超时至 180 秒，累计上限仍为 100 次；这是云端配置调整，不改本地保守默认 16384 / 120，也不表示任意需求都可一次生成。整轮 6 分钟、响应 2 MiB、源码校验 120 KiB 均保持不变。验证最多一轮、3 次请求；若仍失败则保留证据，不连续耗费额度。

回归覆盖 length 用量、未知结束原因、非法/缺失计数、32768 请求参数、失败落库/关联日志、不重试/不保存/不退额度；反转失败记账条件后测试会失败，恢复后通过。

## 安全与回滚

不可信边界为访客→任务 API、模型→产物校验器、应用→已有沙箱。保护资产为项目源码、业务数据、模型密钥和调用额度。拒绝跨项目访问、任意 URL、错误基线、重复活动任务及未校验产物。新表增量迁移；回滚旧应用忽略新表，不删数据或更改已应用迁移。模型错误只向用户返回固定错误类型；没有上游响应/完整 prompt 日志。

## 官方依据

- [DeepSeek 首次调用](https://api-docs.deepseek.com/zh-cn/)
- [Chat Completions 参数、JSON 格式与响应](https://api-docs.deepseek.com/zh-cn/api/create-chat-completion)
- [DeepSeek 错误码](https://api-docs.deepseek.com/zh-cn/quick_start/error_codes/)
- [JDK 17 BodySubscriber：限制响应并取消订阅](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpResponse.BodySubscriber.html)
- [JDK 17 HttpRequest 超时](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpRequest.Builder.html#timeout(java.time.Duration))
- [Spring Framework 6.2 调度任务](https://docs.spring.io/spring-framework/reference/6.2/integration/scheduling.html)
- [Spring Framework 6.2 事务代理边界](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/declarative/annotations.html)
- [Vue watcher 副作用清理](https://vuejs.org/guide/essentials/watchers.html#side-effect-cleanup)
