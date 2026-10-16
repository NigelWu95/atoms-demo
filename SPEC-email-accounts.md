# 邮箱账号 · QQ SMTP 验证码注册登录

日期：2026-10-16。模块：`account`。状态：用户已确认，并授权一次性完成后续规划、实现和本地测试；真实 QQ 收信仍需本地配置。基线 `c551a76`，分支 `feature/standalone-engineering`。

当前账号方向为邮箱验证码；原 `SPEC-oauth-accounts.md` 与 `docs/design/oauth-accounts/` 暂缓并保留。根目录工程导出任务不覆盖。实现与验证记录存于 `docs/design/email-accounts/`，本文件为批准后的契约，实际证据见该目录 verification.md；真实 QQ 收信仍待验。

## 1. 目标、假设与范围

让其他用户能收到真实邮件、完成注册、跨浏览器登录并找回平台项目。管理员使用 QQ 邮箱发信，收件人不限定 QQ 邮箱；实际投递受收件方和供应商规则影响。

默认无密码：同一页面“邮箱 + 验证码”，首次验证自动注册，以后验证登录。保留访客体验；新注册保留当前访客所有项目、对话、版本和平台状态。登录已有账号不合并当前访客项目，页面须提前明确提示。

只交付平台账号能力。不实施手机号、OAuth、密码、邮箱换绑、多邮箱绑定、任意 SMTP 配置管理页面、批量邮件、自动账号合并或生成应用的登录改造。不改现有导出按钮、生成额度、下载内容和本机进程设计。不进行服务器部署或域名/证书配置。

## 2. 技术基线与信任边界

- 沿用 Java 17.0.5-oracle、Spring Boot 3.5.16、Spring Security、Spring Session JDBC、PostgreSQL 和 Vue / Pinia；会话为服务端 HttpOnly Cookie，CSRF 仍保留，不引入 JWT/localStorage 凭据。
- 新增 BOM 管理的 `spring-boot-starter-mail`；使用 `JavaMailSender`，不手写 SMTP 协议。不添加 Resend SDK、Redis 或邮件队列。
- 边界为浏览器 → 平台、平台 → QQ SMTP、平台 → 账号/验证码数据库。保护资产为邮箱地址、短期验证码、SMTP 授权码、平台会话及私有项目。
- 浏览器输入不能指定 ownerId、发件人、邮件主题/正文、SMTP 主机或回调地址。模板为固定中文纯文本，包含用途、验证码、有效期及非本人操作可忽略提示，不嵌入登录链接。

## 3. 账号与数据归属

1. 新邮箱验证成功且发起者仍是同一访客：在数据库事务中创建账号及邮箱映射，保留 `guest_users.id`，不搬运或复制项目。
2. 已登记邮箱验证成功：登录该邮箱唯一映射的原账号。不迁移、删除或合并当前访客空间；无法通过新的账号会话找回未绑定访客数据，须在发送前提示。
3. 已登录账号需先退出才能使用另一邮箱；首版不把登录当作绑定入口。邮箱是否已注册在验证前不通过 API 或界面披露。
4. 同一邮箱并发首次验证只能产生一个账号；同一访客并发认领不同邮箱最多一个成功，不能隐式变成多邮箱账号。失败不得留下半个账号。
5. 邮箱按本产品大小写不敏感策略建立唯一键：去除首尾空白、使用固定 Locale 转小写；仅接受单个常规 ASCII 地址，限制总长 254 / local-part 64，拒绝控制字符、显示名、多收件人和域字面量。不合并点号、加号别名或不同域名，不根据供应商推测身份。
6. 平台认证主体始终是 UUID，不把邮箱直接作为项目 owner。现有项目 API 继续以主体和资源联合检查所有权。

新增 V013 账号迁移和 V014 验证码/预算迁移，不占用其他迁移、不修改 V000–V012；已在隔离测试库验证，未应用到日常/远程库：

- `user_accounts`：user_id 主键引用现有 guest_users、最小显示名、创建时间。没有账号行仍为访客，旧数据无需批量改写。
- `email_identities`：规范邮箱唯一、user_id 唯一且引用 user_accounts、verified_at。首版不收集姓名、头像、手机号或密码。
- `email_auth_challenges`：随机 challenge ID、发起者 UUID、服务端会话绑定摘要、规范邮箱、验证码 HMAC、发送/消费状态、过期时间、剩余尝试、创建时间。
- `email_auth_budgets`：原子计数、限流键摘要及窗口到期时间；禁止只用进程内 Map 控制预算。

回退时关闭邮箱入口，保留表及用户数据；不通过删库、删表或删除账号映射恢复访客状态。

## 4. HTTP 契约

新端点沿用 `/api/` 同源代理及已有结构化错误 `{error:{code,message,retryable},requestId}`，不放宽 CORS。

| 端点 | 输入 → 输出 | 约束 |
| --- | --- | --- |
| `GET /api/auth/methods` | 无 → `{emailCodeEnabled:boolean}` | 匿名可读；只反映配置可用性，不代表 SMTP 连通或送达；不返回发件地址、凭据或原始错误 |
| `POST /api/auth/email/challenges` | `{email:string}` → 201 `{challengeId,expiresAt,resendAfterSeconds}` | 先初始化访客、有效 CSRF；严格字段校验；SMTP 明确接受且挑战激活后才能返回 201；已注册与未注册语义一致 |
| `POST /api/auth/email/verifications` | `{challengeId:string,code:string}` → 200 `{userId,isGuest:false,displayName}` | 六位数字按字符串处理，保留前导零；仅发起会话可验证；成功原子消费并注册/登录，不接受客户端邮箱覆盖挑战邮箱 |
| `GET /api/me`、`POST /api/session` | 保持 `{userId,isGuest,displayName}` | 正确识别账号；显示固定或脱敏名称，不为展示引入完整邮箱泄露 |
| `DELETE /api/session` | 无请求体 → 204 | CSRF 保护；失效当前会话和验证码意图、清 Cookie；不删除项目或停止后台生成/应用进程 |

输入错误 422 `INVALID_INPUT`；会话失效 401；CSRF 失败 403；账号已登录或当前状态冲突 409；无效/过期/已用/跨会话验证码统一 422 `EMAIL_CODE_INVALID`；限流 429 `AUTH_RATE_LIMITED` 并返回 `Retry-After`；未配置 503 `EMAIL_AUTH_UNAVAILABLE`；SMTP 失败或结果未知 503 `EMAIL_DELIVERY_UNCONFIRMED`。

以上写接口均不自动重试；验证码验证也不重放成功响应。若响应丢失，客户端先刷新 `/api/me` 确认当前身份；仍为访客时让用户明确重新开始。不能为了“幂等”把验证码或已认证会话写入通用响应缓存。

## 5. 验证码生命周期与发送失败

- 使用 `SecureRandom` 均匀生成六位数字，10 分钟有效，单个挑战最多 5 次错误验证；比较采用固定时间摘要比较。
- 保存 `HMAC-SHA-256(serverKey, version + challengeId + email + code)`，编码明确且无拼接歧义；服务器密钥至少 32 个随机字节，只存在私有配置。禁止明文或仅使用无密钥 SHA-256 储存低熵验证码。
- 挑战绑定发起访客和服务器会话随机标识；只在内存短暂持有发送所需明文，邮件发送后不保存在 Spring Session、日志或 API 响应。应用重启后依赖 PostgreSQL 及稳定私有密钥验证。
- 短事务预留预算并建立 `PENDING`，提交后调用 SMTP；成功再短事务激活 `SENT`。验证只接受 `SENT`，不在数据库锁或账号事务内等 SMTP。
- 明确失败、超时或进程中断后的发送结果未知：挑战不可验证，不自动重发。即使邮件后来到达，也提示重新请求；不宣称 exactly-once 邮件投递。
- 每个会话同时至多一个发信操作；同会话重新发送成功后替换旧挑战，发送失败不废除此前仍有效挑战。其他浏览器申请相同邮箱不能作废当前浏览器的验证码。
- 验证消费、尝试扣减及账号认领使用数据库原子操作；竞争只有一个请求成功，不靠 Java 锁。已消费、过期、退出或身份改变的挑战不可恢复。
- challenge ID 在当前页面内存保留，刷新需要重新发起；这不会删除已建立的账号。后端仍负责有效期、限流和短期数据清理。

## 6. 滥用、会话与数据保护

初始 Demo 预算（均可通过受校验的后端配置调整，以下不是 QQ 邮箱供应商配额）：

- 发送冷却：每收件邮箱和每会话均至少 60 秒。
- 每邮箱固定 15 分钟窗口最多 5 次发送、每会话 10 次、每来源地址 20 次；全局固定 1 小时最多 20 次、每日最多 100 次。
- 验证尝试（包括成功）除单挑战最多 5 次输错外，每邮箱及每会话固定 15 分钟窗口最多 10 次，每来源最多 50 次；无效 challenge 也受会话/IP 预算限制。
- 预算在调用 SMTP 前原子占用，失败不退款；各预算全部满足才能发送。固定窗口允许边界突发，不宣称严格滑动窗口。轮换访客 Cookie 不能绕过收件人/IP/全局限制。
- 来源只用服务器解析后的 remote address，沿用可信本机代理配置，不直接信任任意 X-Forwarded-For。限流键使用服务器密钥摘要，不保存原始 IP。

成功认证旋转 session ID 和 CSRF，最终 SecurityContext 只保存平台身份。访客升级时撤销其他旧访客会话；并发旧请求不得把已撤销会话重新写活。相同 userId 保留本会话自己的本机预览桥；登录另一账号或退出时清除旧桥与认证意图。保持按项目所有者的运行访问检查，不把平台 Cookie 传入生成应用。

退出/切换清空前端项目、对话、版本、预览、草稿和未知提交状态，停止旧轮询/SSE，并使迟到响应不能覆盖新身份。跨标签页与页面恢复时重新确认身份；已开启 SSE 要在会话失效后停止该会话的推送，后台任务不取消。

注册认证响应 `no-store`；Cookie 保持 HttpOnly、SameSite=Lax。本机回环可以 HTTP 测试；公网需 HTTPS 和 Secure Cookie。本轮不修改现有 HTTP 服务器，不把 SMTP TLS 等同于网站 TLS。

日志只记 requestId、耗时及有限结果码，不打印邮箱、验证码、摘要、授权码、SMTP 原始异常或邮件正文，关闭邮件协议 debug。凭据和个人数据不进入模型上下文、下载工程或浏览器存储。

邮箱是新增个人数据，仅用于认证。挑战、邮箱/IP 限流记录过期后通过有界定时清理在 24 小时内删除；账号邮箱随账号保留，不复制进遥测。首版不提供换绑或自助删除，须在文档明确限制；正式公开运营的隐私告知、保留/备份及删除流程另行评审，不宣称已经满足生产隐私合规。

## 7. QQ SMTP 配置与用户准备

QQ 邮箱网页端：设置 → 账号与安全 → 安全设置，开启 POP3/IMAP/SMTP 服务并生成授权码。应用只连接 SMTP，不读取邮件；授权码不是 QQ 登录密码，实际身份验证操作由用户完成。

采用环境变量或现有被忽略的 `.env.local`（已接线，默认关闭）：

```properties
EMAIL_AUTH_ENABLED=false
SMTP_HOST=smtp.qq.com
SMTP_PORT=465
SMTP_USERNAME=
SMTP_PASSWORD=
EMAIL_CODE_HMAC_KEY=
```

`SMTP_USERNAME` 为完整发信邮箱地址；From 固定使用同一地址。`SMTP_PASSWORD` 填邮箱授权码；HMAC 密钥与授权码分开，由本地安全方式生成。真实值不在聊天传递、不写入模板；只向已有 `.env.local` 追加所需键，不覆盖数据库和模型配置。

首版使用 465 隐式 TLS、验证服务端证书及主机名，不允许明文或 trust-all。主机为管理员配置，不接受浏览器传入；QQ 之外的服务商仅作为后续配置扩展，不实现自动切换降级。连接、读取、写入各设置 5 秒超时并限制并发发送数，不使用默认无限等待；SMTP 写请求前端等待上限单独配置为 30 秒，不改变模型超时。

默认关闭，未配置时访客平台照常运行，登录页解释不可用；启用但关键配置不全时明确拒绝启动且错误不包含值。不在启动时自动发测试邮件。真实测试需用户将配置填入本机，并指定/使用自己控制的收件邮箱。

## 8. 代码结构、风格与界面

后端放在 `backend/src/main/java/dev/atoms/demo/identity/`，划分邮箱输入、邮件发送、验证码事务、账号映射与会话切换；复用现有 `ApiException`、CSRF 和 JDBC 结构。迁移在 `backend/src/main/resources/db/migration/`，测试在对应 `backend/src/test/java/dev/atoms/demo/identity/`。

前端增加 `/account` 页面和侧栏账号入口，API 解析在 `frontend/src/api/`，身份切换集中在既有 session store。沿用 TypeScript 严格类型、Vue setup、ESLint/Prettier，不在组件里存凭据或直接决定注册归属。现有风格示例：

```java
public record Identity(String userId, boolean isGuest, String displayName) {}
// 保持现有错误格式；不把用户邮箱或上游异常插入消息。
throw new ApiException(422, "INVALID_INPUT", "请输入有效邮箱地址。");
```

页面显示邮箱、验证码、发送按钮及服务端冷却倒计时、确认登录/注册、访客继续体验、退出与明确失败态；验证码输入可粘贴并有标签/键盘焦点。修改邮箱清除旧 challenge；时间以服务端为准。支持 320/390/1440px、键盘及屏幕阅读器错误提示。

注册不改变生成应用的独立访客身份，不承诺不同浏览器自动共享生成应用存档；平台账号跨浏览器找回项目与历史是本轮验收范围。`templates/standalone-v1/` 不修改，SMTP 配置永不导出。

## 9. 验收与可执行命令

每个行为先失败测试再实现。测试仅使用专用数据库与测试 SMTP，不往任意用户邮箱发信；替身成功不能作为 QQ 实际收信证据。

- 单元：邮箱边界、前导零、摘要绑定和过期边界、配置缺失、SMTP TLS/超时设置、固定模板及无多收件人/头注入。
- 数据库/HTTP：未验证不能建账号；新注册保留 UUID/项目；已有账号跨浏览器登录；同邮箱和同访客并发认领；错误次数原子扣减、过期/重放/跨会话拒绝、CSRF、各维度冷却/预算、清理仅删到期认证记录。
- 发信：测试替身捕获邮件并从正文取码走完整 API；模拟拒绝、超时、断连、发送后数据库失败及重复点击，验证未知状态不自动重发。协议级 SMTP 测试依赖如需新增，先在实施计划中列明并固定版本。
- 会话：旧 Cookie/CSRF 失效、退出重放、并发旧请求、旧 SSE 停止、跨标签页、同 UUID 运行桥保留/不同 UUID 清空、凭据 canary 不出现在日志或持久会话中。
- 浏览器：真实 Chrome 测注册/重登/退出/错误态/项目归属/移动端/键盘和控制台网络；只用 8081/5174 专用环境，不重启日常 8080/5173 或 2048 进程。
- 真实 QQ：用户配置后，向自己控制的邮箱收信并完成首次注册、退出、再次验证码登录；条件允许再用另一邮件服务的自有邮箱验收。未收信不能写“邮件送达通过”。

项目根目录执行，先由已有私有配置准备 TEST_DB_PASSWORD，不打印值：

```sh
export JAVA_HOME=/Users/wubingheng/.sdkman/candidates/java/17.0.5-oracle
export PATH="$JAVA_HOME/bin:/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH"
(cd backend && ./mvnw -B verify)
npm --prefix frontend run typecheck
npm --prefix frontend run lint
npm --prefix frontend run format:check
npm --prefix frontend run test:unit
npm --prefix frontend run build
(cd frontend && CI=1 npm run test:e2e -- --config playwright.isolated.config.ts --project=chromium)
(cd frontend && CI=1 npm run test:e2e:runtime)
git diff --check
```

这些是验收命令，实际结果见 `docs/design/email-accounts/verification.md`。开发启动沿用 `npm --prefix frontend run dev` 和 `(cd backend && ./mvnw -B spring-boot:run)`，但不得与既有日常进程争抢端口。

## 10. 边界与待确认

- 始终：增量迁移、保留旧数据、参数化 SQL、所有权检查、CSRF、验证码保密、失败不伪装成功、真实数据库/浏览器验证、准确记录未验收部分。
- 本规格批准后允许：所述邮件依赖、账号/验证码迁移、邮箱个人数据、认证限流与登录/退出的必要改动；无需为普通在范围内的文件编辑反复询问。
- 另行确认：超范围依赖/权限、邮箱控制台操作、配置真实授权码、真实邮件收件对象、日常服务重启、公网部署、GitHub 推送和生产迁移。
- 禁止：SMTP 配置或密钥上传、绕过证书验证、收件人任意批量发送、账号自动合并、模板暗带认证配置、删除现有项目/工程数据。

免密码注册登录及默认防滥用预算已获用户确认。待用户准备：已开启 SMTP 的发信邮箱、授权码及独立 HMAC 密钥（仅本地填写）；不阻止代码和替身验收，阻止真实收信验收。公网 HTTPS 仍是对外启用账号能力前的独立待办。

## 官方依据

- [QQ 邮箱：生成及管理授权码](https://help.mail.qq.com/detail/106/985)
- [腾讯云开发：邮箱验证码与 QQ SMTP 参数](https://docs.cloudbase.net/en/authentication-v2/method/email-login)
- [Spring Boot 3.5：JavaMailSender 与有限超时](https://docs.spring.io/spring-boot/3.5/reference/io/email.html)
- [Spring Security 6.5：手动认证与会话保存](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/session-management.html#store-authentication-manually)
- [认证方式决定 ADR-014](https://github.com/NigelWu95/atoms-demo/blob/main/docs/decisions/014-smtp-email-accounts.md)（待推送后可访问）
