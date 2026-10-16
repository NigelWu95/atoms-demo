# 邮箱账号进度

每项在所列范围内实现并验证后勾选。用户已批准连续实施；不推送或部署。

- [x] E01 输入/密钥/预算配置：严格单邮箱、HMAC、缺省关闭、缺项不泄密；EmailAuthSettingsTest 先红后绿。
- [x] E02 SMTP 适配：BOM mail、465 TLS、超时、固定正文、有限并发；EmailCodeMailerTest。
- [x] E03 账号/撤销基础：V013、稳定 UUID、旧访客识别与会话撤销；AccountIdentityIT。
- [x] E04 验证码预算：V014、冷却、共享原子计数、到期清理；AuthBudgetIT / EmailAuthCleanupIT。
- [x] E05 发信/验证事务：状态、并发认领、失败不重发/不建账号；EmailChallengeIT。
- [x] C1 后端领域检查点：单元及真实 PostgreSQL 定向测试。
- [x] E06 HTTP 与会话：严格契约、CSRF、轮换、退出、HTTPS 边界；EmailAuthIT / EmailTransportSecurityTest。
- [x] E07 请求/SSE 撤销：旧请求不能恢复权限，旧流停止而后台生成继续；会话及 SSE 回归。
- [x] E08 前端协议：能力、发码、验证、退出、30 秒发送等待与旧响应隔离；Vitest。
- [x] E09 前端身份切换：私有视图/草稿清空、跨标签页、失败遮蔽；Vitest；普通 iframe 焦点不触发切换。
- [x] E10 账号页面：表单/状态/冷却/路由/侧栏，移动端及键盘；组件测试与 Chrome。
- [x] C2 全栈检查点：后台 verify、前端类型/静态/单元/构建。
- [x] E11 浏览器与 SMTP 适配测试：测试专用 sender、真实 Chrome、账户重登/归属/退出/axe；现有隔离回归；不包含真实 QQ 收信。
- [x] E12 配置及交付文档：私有配置口子、QQ 操作、限制、证据、回退；diff 检查/代码审查。
- [x] E13 临时公网 HTTP：显式开关、完整登录会话链、默认拒绝与防护回归、配置及风险说明；不自动部署。
- [x] 真实 QQ 收信及一次验证码登录：用户完成本地配置，指定受控 Outlook 收件邮箱；发送 201、验证 200，用户确认测试 OK。
- [ ] 真实邮件补充复测：退出后重新发码登录；目前该场景通过的是测试发件器浏览器验收，不额外自动发送邮件。

## 验证记录

E01–E12 验收：后端 131 单元 + 95 集成通过；前端 208 单元及 typecheck / lint / format:check / build 通过。焦点回归修复后，基础 Chrome 16 项与运行时 Chrome 33 项全部通过。

E13 HTTP 补充验收：后端完整 134 单元 + 96 集成通过；前端上述检查及 208 单元重新通过，邮箱 Chrome 2 项回归通过。详细结果、失败修正及未验收边界见[验收记录](https://github.com/NigelWu95/atoms-demo/blob/main/docs/design/email-accounts/verification.md)（待推送后可访问）。

2026-10-16 真实邮件验收：生产 SMTP 适配器 TLS/认证检查通过；本地 8080 / 5173 与独立 Chrome 完成一次用户授权发信和用户自行输入验证码。未记录验证码或凭据，未调用模型；仅本地验证，不等于云端已开放注册。
