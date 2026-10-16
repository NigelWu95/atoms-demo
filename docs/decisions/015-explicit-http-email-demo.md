# ADR-015 · 显式允许临时 HTTP 邮箱账号演示

## 状态

已采纳，2026-10-16。用户明确表示没有域名、当前只能先使用 HTTP。补充 [ADR-014](https://github.com/NigelWu95/atoms-demo/blob/main/docs/decisions/014-smtp-email-accounts.md) 的传输策略，不变更账号、SMTP 或数据库架构；未自动部署。

## 背景与决定

原策略拒绝公网明文发码、验证和账号会话；只放开发码会导致认证成功后立即被会话过滤器拒绝。提供后端 `EMAIL_AUTH_ALLOW_INSECURE_HTTP`，默认关闭，三个认证入口与账号会话过滤使用同一判断。已有发布链接 HTTP 开关不隐式开放账号认证。

HTTP 演示还需维护者明确使用非 Secure、HttpOnly、SameSite=Lax 的 HTTP 专用 Cookie。开关不自动改 Cookie 或启用发信，不降低 SMTP TLS、不放宽代理信任、CSRF、原子验证码消费、预算和会话撤销。配置示例仍默认关闭，实际私有配置及远程服务器不自动修改。

## 替代方案

- 继续强制 HTTPS：仍是正式账号服务的目标，但不满足当前临时 HTTP 演示要求。
- 全局删除 HTTPS 检查或复用发布开关：会让既有部署无意开放明文账号，故不采用。
- 只允许发送/验证走 HTTP：后续账号请求仍被拒绝，不是可用的完整流程。

## 后果与边界

这是用户选择的安全例外，不是等价的加密方案。网络攻击者可能读取或篡改验证码、会话 Cookie 及私有数据；HMAC、CSRF、HttpOnly、SMTP TLS 和限流都不能弥补这一风险。只用于临时演示，不宣称满足生产账号安全。

回归覆盖默认拒绝、显式启用后发码/登录/持续访问/退出，以及错误验证码、重发预算和 CSRF。测试使用模拟 HTTP 请求与真实测试 PostgreSQL，不向真实邮箱发送邮件。

恢复默认策略时关闭例外、启用可信 HTTPS / Secure Cookie，并更换为 HTTPS 专用 Cookie 名称后重新登录；不删除账号和项目。操作步骤见[邮箱说明](https://github.com/NigelWu95/atoms-demo/blob/main/docs/email-accounts.md)。
