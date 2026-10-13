# runtime-access · 工作台真实实例预览

状态：2026-10-13，与 app-runtime 一次性实施；依赖 [实例规格](SPEC-app-runtime.md)。保留原共享预览/发布，不把历史版本静默覆盖当前运行实例。

## 目标与接口

成功构建记录提供“启动独立实例”，运行后直接展示真实 JAR 返回的文档。状态读写转发到该实例独立后端、独立运行库；下载仍指向同一个 buildId 冻结 ZIP。选择其他版本不自动停止/替换已有实例，必须明确停止再启动。

仅增加固定授权接口：GET `/api/projects/{projectId}/instances/{instanceId}/app`、`/document`、`/state` 与 PUT `/state`。无通用代理、无客户端 URL/端口参数，不跟随重定向；目标固定为配置中的 127.0.0.1 监听器。每次检查所有者、RUNNING 与执行身份。请求/响应有界、超时，不自动重发写请求。

平台会话与独立应用会话不混用：平台后端维护实例访客 Cookie（保存在平台既有 JDBC session 中），不把平台 Cookie、Authorization 或任意请求头转发；不把实例 Cookie 发给浏览器。CSRF 在两端分别校验；上游状态写入沿用原 Idempotency-Key 与 loadedRuntimeId。停止后拒绝新预览/读写；在途未知写结果不冒充失败回滚。

## 页面与隔离

在构建区域显示待启动、启动中、运行中、停止中、已停止、失败/中断。按钮可键盘操作，错误保留原幂等意图以便确认；刷新只查询不新建。匹配所选构建的 RUNNING 实例自动装载独立预览，并标注数据不与旧 PREVIEW/LIVE 共用。以实例 ID 重建 iframe，晚到请求不能切换其他项目的界面。

复用平台现有 sandbox=allow-scripts、opaque origin、MessageChannel SDK。文档逐字节来自真实 JAR，平台只重申受限 CSP；模型 JS 不接触平台凭据。控制页不关闭安全头、不添加 CORS 通配符。使用现有颜色、间距、错误和 loading 样式，手机不横向溢出。

## 验证与完成条件

frontend `npm run test:unit` / `npm run typecheck` / `npm run lint` / `npm run format:check` / `npm run build`；backend `./mvnw -B verify`。真实浏览器验证构建→启动→待办保存→刷新→停止→重新启动后数据仍在、第二项目独立、越权拒绝、无控制台错误、手机与键盘访问。没有 Chrome DevTools MCP 时使用已有 Playwright 独立 Chrome，不连接个人浏览器。

Always：绑定真实运行身份、下载同一快照、有限代理；Ask first：公网开启、迁移旧 PREVIEW/LIVE 数据；Never：返回模拟就绪、代理任意 URL、向 iframe 传递会话凭据。未完成项如实保留，不以接口模拟测试冒充真实运行验收。
