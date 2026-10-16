# QQ 邮箱验证码注册 / 登录

2026-10-16，代码已推送 GitHub 并部署到现有服务器；仓库示例仍默认关闭。**本地真实 QQ 发信、Outlook 收信及一次验证码登录通过；云端配置已启用，SMTP TLS/认证与公网页面检查通过，云端实际收信登录待用户复核。** 见[部署与备份记录](https://github.com/NigelWu95/atoms-demo/blob/main/docs/cloud-deployment.md)。

## 功能与归属

侧栏「邮箱注册 / 登录」打开 `/account`。输入自己的邮箱 → 收取 6 位验证码 → 验证并登录；不需要设置密码。发信账号使用 QQ 邮箱，收件用户可以使用其他邮箱，实际投递以邮件服务商为准。

- 新邮箱：把当前访客升级为账号，保留 UUID、项目、对话、版本、平台应用数据和发布链接，不复制数据。
- 已注册邮箱：登录原账号；不会合并当前访客项目。重要访客项目请先下载，登录前页面有提示。
- 退出：撤销当前会话，建立新访客空间；不删除项目，不停止生成或本机应用进程。再次验证同一邮箱可找回账号项目。
- 同浏览器其他标签页会重新验证身份；旧私有页面、草稿、请求和 SSE 被清理/停止。切换不同账号不保留旧本机预览桥；同 UUID 注册保留当前桥。
- 生成应用自身仍沿用既有访客身份，不会因平台登录自动共享不同浏览器的游戏存档。

未配置时明确显示「邮箱登录暂未开放」，访客创作不受影响。没有模拟发信成功、管理员通用验证码、密码登录、手机号、OAuth、自动合并、邮箱换绑或账号自助删除。

## 本地启用（维护者操作）

1. 在 QQ 邮箱网页设置中开启 SMTP 服务并生成授权码。菜单参考[QQ 官方说明](https://help.mail.qq.com/detail/106/985)。使用授权码，不是 QQ 登录密码；用户不需要把自己的发信授权码提供给平台。
2. 只在已有项目根目录 `.env.local` 中追加/更新以下键，**不要复制模板覆盖原来的数据库和 DeepSeek 配置**。私有文件权限保持 `0600`，不要发到聊天、提交 GitHub 或放入导出工程。

```properties
EMAIL_AUTH_ENABLED=true
SMTP_HOST=smtp.qq.com
SMTP_PORT=465
SMTP_USERNAME=你的完整发信邮箱@qq.com
SMTP_PASSWORD=你的SMTP授权码
EMAIL_CODE_HMAC_KEY=独立生成的64位十六进制密钥
```

3. 可在自己终端用 `openssl rand -hex 32` 生成 HMAC 密钥，粘贴到私有文件，保持稳定并备份到安全位置。不要复用数据库密码、模型密钥或 SMTP 授权码。轮换 HMAC 密钥会让未完成验证码和原有限流摘要失效，不删除账号；必须谨慎安排。
4. 检查端口未被占用后按现有方式启动/重启自己的本地后端和前端。后端 Java 17；前端 Node 24.20.x / npm 11.19.x。

```sh
cd backend
./mvnw -B spring-boot:run
# 另一个终端，项目根目录：
npm --prefix frontend run dev
```

5. 用 `http://127.0.0.1:5173/account`，向自己控制的邮箱发送测试验证码。确认收件箱或垃圾邮件收到邮件、首次注册保留项目、退出后再次登录以及另一浏览器找回项目。初次配置不会自动发邮件，也不会读取邮箱收件箱。

QQ 参数为 `smtp.qq.com:465`，隐式 TLS；发件地址固定为认证邮箱。参考[腾讯官方 SMTP 示例](https://docs.cloudbase.net/en/authentication-v2/method/email-login)。启用但缺密钥/发件账号/授权码会拒绝启动，错误只提示配置键名，不回显值。

## 公网注意

发信不要求平台有域名，公网账号登录**默认要求 HTTPS 和 Secure Cookie**。没有域名且暂时只能使用 HTTP 时，支持维护者显式开启临时演示模式。它只是放开协议限制，**不会加密验证码、Cookie 或项目数据，也不能防止网络窃听、篡改和账号冒用**；不适合正式账号服务。QQ SMTP 仍使用 TLS，不能代替网站 HTTPS。

在服务器**已有的私有配置**中保留上面的 QQ 发信配置，追加/更新下列项，然后重启后端（不要覆盖数据库/模型配置）：

```properties
EMAIL_AUTH_ALLOW_INSECURE_HTTP=true
server.servlet.session.cookie.secure=false
server.servlet.session.cookie.name=ATOMS_HTTP_SESSION
```

若用 systemd 环境变量配置 Cookie，对应为 `SERVER_SERVLET_SESSION_COOKIE_SECURE=false` 与 `SERVER_SERVLET_SESSION_COOKIE_NAME=ATOMS_HTTP_SESSION`。环境变量优先级高于配置文件；两处不要留冲突值。已有 HTTP 部署若已使用上述 Cookie 名称则保留，避免无必要地切换访客会话。

该开关默认 `false`，统一控制能力发现、发送、验证及登录后的账号请求；它不自动启用 `EMAIL_AUTH_ENABLED`，不修改 Cookie，也不放宽 CSRF、验证码、限流、归属或退出撤销。只设置发布链接的 `ALLOW_INSECURE_HTTP_PUBLIC_ORIGIN=true` 不会开放邮箱 HTTP 登录，这两个开关用途不同。本机回环开发不需要这个例外开关。

服务器 [账号页](http://192.0.2.10:8443/account) 已按用户授权开放：本地邮箱/模型键通过 SSH 合并到 `/etc/atoms-demo/application.properties`，权限 root:atoms / 0640。没有覆盖服务器数据库密码、origin 或 Cookie 名称。显式 HTTP 例外已启用，QQ SMTP TLS/认证与公网页面检查通过；没有自动发送云端测试邮件，仍需用户在账号页收信并验证。不要暴露 8081/5174 测试服务或放开可信代理来源。`GET /api/auth/methods` 的 `emailCodeEnabled=true` 只代表配置/传输条件允许，不单独证明邮件送达。

以后改用 HTTPS 时，把 `EMAIL_AUTH_ALLOW_INSECURE_HTTP=false`、Cookie `secure=true`，并换回 HTTPS 专用 Cookie 名称，重启后重新登录；旧账号和项目不删除。仅关闭 HTTP 例外而仍通过 HTTP 访问时，已登录账号请求会被拒绝。详见 [ADR-015](https://github.com/NigelWu95/atoms-demo/blob/main/docs/decisions/015-explicit-http-email-demo.md)。

## 限制与故障排查

验证码有效 10 分钟，最多输错 5 次；收件邮箱和会话均有 60 秒重发冷却。发送预算在数据库中共享且在发送前占用，失败不退款：

| 后端配置 | 默认值 | 窗口 |
| --- | ---: | --- |
| `EMAIL_MAX_SENDS_PER_EMAIL` | 5 | 固定 15 分钟 |
| `EMAIL_MAX_SENDS_PER_SESSION` | 10 | 固定 15 分钟 |
| `EMAIL_MAX_SENDS_PER_IP` | 20 | 固定 15 分钟 |
| `EMAIL_MAX_SENDS_PER_HOUR` | 20 | 固定 1 小时，全平台 |
| `EMAIL_MAX_SENDS_PER_DAY` | 100 | 固定每日，全平台 |

这是平台防滥用预算，不是 QQ 配额，不影响 DeepSeek 调用次数。验证尝试（包括成功尝试）另有限制：每会话/邮箱 10 次、每来源 IP 50 次 / 固定 15 分钟。429 带 `Retry-After`；页面据此等待，不自动重发。固定窗口在边界可能突发，个人邮箱不适合作大规模验证码通道。

- `EMAIL_AUTH_UNAVAILABLE`：后端未启用，检查私有配置及启动进程加载的文件。
- `EMAIL_AUTH_SECURE_TRANSPORT_REQUIRED`：默认模式下使用 HTTPS；本机回环开发例外。临时公网 HTTP 演示需维护者按上文显式配置，不接受浏览器传入的开关。
- `EMAIL_DELIVERY_UNCONFIRMED`：认证、连接、拒收或超时，可能邮件已到但服务器无法确认。检查授权码是否有效、出站 465、防火墙及供应商限制；稍后重新获取，不使用失败请求的旧邮件。
- `EMAIL_CODE_INVALID`：过期、错误、耗尽次数、已使用，或在不同浏览器验证；仅在发起页面使用该验证码。发送成功后刷新页面需要重新申请。
- `ACCOUNT_ALREADY_SIGNED_IN`：先退出当前账号，首版不支持绑定另一个邮箱。
- 网络中断：不自动重放验证码验证；先「重新连接会话」确认当前身份，仍为访客再明确重新申请。

服务端设置连接/读取/写入各 5 秒超时、每进程至多 2 个 SMTP 调用，前端发码等待 30 秒。SMTP 接受不等于最终送达。普通运维只看 `requestId`、`email_send` / `email_verify` / `session_logout` 的固定结果与耗时；不要打开邮件协议 debug、Spring MVC/HTTP 请求正文调试或把认证 trace 上传到公共仓库。

## 数据与回退

V013/V014 为增量表，旧项目外键和 `guest_users` 不变。验证码只存带密钥的摘要，邮箱用作认证映射；限流保存摘要，不保存原始 IP。每 5 分钟有界清理到期挑战、预算和撤销标记（每表每批 500 条），撤销标记保留 14 天，超过当前 7 天会话寿命。账号邮箱持续保留；正式运营前仍需隐私告知、自助删除/人工删除与备份保留政策。

停止新注册/登录可设 `EMAIL_AUTH_ENABLED=false` 后重启，已登录账号及退出继续有效。**不能回滚到不认识账号的旧访客程序**：旧程序会把账号会话当作访客，无法保留完整的认证保证。应向前修复或使用仍包含账号/会话防护的版本，保留迁移和数据；不删账号映射、项目或表。

## 本地验收

自动验收使用 `atoms_demo_test` / `atoms_test`、8081 / 5174、独立 Chrome 和测试发件器；测试码读取端点只编译到 test classpath，受测试库/角色/回环保护，生产 JAR 不包含。没有真实邮件或模型调用。测试不会重启 8080 / 5173 或改动云端。

另经用户授权完成真实邮件验收：生产 SMTP 适配器的 TLS/认证检查通过；本地 8080 / 5173 的真实页面向指定 Outlook 邮箱只发出一次邮件（201），用户收信并自行输入验证码，验证接口返回 200，用户确认测试 OK。日常本地库增量应用 V013/V014，未删除原有项目。未读取收件箱，也未记录验证码、授权码或 HMAC 密钥。真实邮件下退出后的重新发码登录尚未复测；自动浏览器已用测试发件器覆盖该逻辑，不代表更多真实邮箱或批量投递通过。

这次邮件测试启动的本地后端通过命令行临时关闭生成 Worker，未修改私有模型配置或额度、未调用模型。继续测试应用生成前需恢复日常启动方式并检查活动任务，避免把邮件测试的进程状态当作完整创作环境。

最终数量与失败修正见[本轮验收记录](https://github.com/NigelWu95/atoms-demo/blob/main/docs/design/email-accounts/verification.md)；详细契约见[规格](https://github.com/NigelWu95/atoms-demo/blob/main/SPEC-email-accounts.md)、[ADR-014](https://github.com/NigelWu95/atoms-demo/blob/main/docs/decisions/014-smtp-email-accounts.md)。测试截图/trace 为忽略提交的本地文件，可能含测试认证信息，不公开上传。
