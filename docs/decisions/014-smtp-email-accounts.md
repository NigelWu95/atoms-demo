# ADR-014 · SMTP 邮箱验证码账号

## 状态

已采纳并本地实现，2026-10-16。用户确认 QQ SMTP 免密码验证码注册登录，并授权连续开发与本地验收；真实 QQ 发信待维护者配置后验收。未推送或部署。

同日验收补记：维护者已完成本地私有配置。真实 QQ 向指定的 Outlook 邮箱发信返回 201，用户输入验证码后返回 200，并确认测试 OK。仅完成本地单次真实邮件登录；远程部署、重复真实发码登录与规模化投递不在这次证据范围内。

规格：[SPEC-email-accounts.md](https://github.com/NigelWu95/atoms-demo/blob/main/SPEC-email-accounts.md)。此链接须本文件及规格推送后才有效；本轮不推送或部署。

## 背景与决定

原平台仅有持久化访客会话。用户没有自有域名，希望其他人能够真正注册，不局限于发信账号自身测试。此前 GitHub / Google OAuth 只有设计文档，按最新选择暂缓；不删除历史文档或更改现有访客项目。

采用 Spring Boot BOM 管理的 `spring-boot-starter-mail` / `JavaMailSender`，通过维护者 QQ 邮箱的 SMTP 授权码发送。只使用 SMTP，不读取邮箱。发信凭据由维护者在平台后端配置，注册用户不提供 SMTP 账号或授权码。

首版采用统一验证码注册/登录，无密码：验证新邮箱时升级当前访客，验证已有邮箱时登录原账号。平台 userId 保持为现有 UUID，邮箱凭据单独映射到账号，避免改写所有项目外键。不增加 Redis、消息队列、外部身份平台或生成应用的邮箱认证。

## 替代方案

- Resend：Java 接入可行，但默认测试域只能给 Resend 账号邮箱发送；给其他收件人发信需要验证自有域名，不满足当前准备条件。
- GitHub / Google OAuth：保留设计，暂不实现；未来增加时不能仅凭相同邮箱自动合并身份。
- 邮箱 + 密码：增加密码保存、重置与恢复流程；本轮使用验证码完成同一邮箱所有权验证。
- 自建邮件服务器：需要域名、DNS 和投递信誉运维，不属于本次 Demo 范围。

## 后果与边界

验证码是短期认证凭据：强随机、带服务器密钥的摘要存储、短期有效、单次原子消费，并实施收件邮箱、会话、来源和全局发送预算。SMTP 调用不占用账号事务；失败或结果未知不伪装成功、不自动重发。

QQ 邮箱授权码与验证码不进入日志、前端包、模型上下文或下载工程。个人邮箱受供应商投递、反滥用和额度限制，不能承诺无限发送或邮件必达；SMTP 接受不等于收件人已收到，需实际收信验收。

邮箱发信不要求平台先有域名，但公网账号登录仍应使用 HTTPS；SMTP TLS 只保护平台到邮件服务器。现有公网 HTTP 配置不在本轮自动变更，不把本地验收等同于安全公网发布。

低流量 Demo 使用一个 PostgreSQL advisory lock 串行化短认证事务；SMTP 在事务外。当前是有意的吞吐取舍，不适合直接扩展成高流量身份服务。撤销 ID 摘要和 ROLE_USER / ROLE_GUEST 检查防止并发旧会话复活，SSE 在读取时复核权限，前端按身份清空私有状态。

只允许关闭新增邮箱登录入口作功能回退；不可回滚到忽略账号与撤销机制的旧访客程序。详情见[操作说明](https://github.com/NigelWu95/atoms-demo/blob/main/docs/email-accounts.md)。

## 官方依据

- [QQ 邮箱：生成及管理 SMTP 授权码](https://help.mail.qq.com/detail/106/985)
- [腾讯云开发：邮箱验证码与 QQ SMTP 配置](https://docs.cloudbase.net/en/authentication-v2/method/email-login)
- [Spring Boot 3.5：邮件发送及超时配置](https://docs.spring.io/spring-boot/3.5/reference/io/email.html)
- [Resend：默认测试域的收件人限制](https://resend.com/docs/knowledge-base/403-error-resend-dev-domain)
