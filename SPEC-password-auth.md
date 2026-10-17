# Spec: 密码设置、修改与可选登录

模块：`password-auth`；依赖 `account-profile`；索引：[能力地图](ACCOUNT-WORKSPACE-CAPABILITY-MAP.md)。

状态：2026-10-17 用户已确认，实施与本地验收完成；V017 仅应用于隔离测试库，未推送或部署。证据见[本轮记录](https://github.com/NigelWu95/atoms-demo/blob/main/docs/account-workspace-verification.md)。

## 目标与范围

账号管理支持首次设置密码、验证原密码后修改密码；独立登录页提供“验证码登录 / 密码登录”，默认验证码。验证码仍可注册新邮箱或登录已有账号；密码入口只登录已有且已设置密码的账号，绝不自动注册。

保持现有身份所有权：新邮箱升级访客 UUID；已有邮箱登录切换账号，不合并访客项目。首次设置和修改密码都不改变账号 UUID、邮箱、项目、版本、配额或发布链接。

忘记密码时可切换验证码登录继续使用账号；本轮不加入“凭邮箱验证码覆盖已有密码”的重置能力，界面不能声称已经支持找回密码。

## 威胁模型与关键取舍

- 边界：浏览器输入、Cookie/CSRF、SMTP、认证表和 JDBC 会话；资产：密码、验证码、邮箱、用户项目及登录状态。
- 防冒用：首次设置需向当前账号绑定邮箱重新发专用验证码；修改需原密码；仅有登录 Cookie 不足以设置或覆盖密码。
- 防枚举/爆破：不存在邮箱、未设置密码、密码错误统一响应；数据库共享限流，昂贵密码校验有并发上限。
- 防重放/竞态：验证码用途隔离、单次消费；密码凭据版本与会话版本绑定，旧密码并发登录不能绕过修改后的撤销。
- 防泄露：只存单向哈希；不把认证材料放日志、URL、SSE、幂等缓存、浏览器持久存储或模型上下文。

优先复用 Spring Security 6.5.11 已有 `PasswordEncoder`，采用带算法标识的 BCrypt、cost 12，不增加加密依赖；不直接使用默认工厂的默认强度。数据库保留算法前缀，便于后续升级。

这是本 Demo 避免新增依赖的取舍，不宣称 BCrypt 是新系统的首选。OWASP 首推 Argon2id；后续若采用它，需要审查并引入相应依赖。[Spring Security 6.5 密码存储](https://docs.spring.io/spring-security/reference/6.5/features/authentication/password-storage.html)、[OWASP 密码存储](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)。

### 密码输入契约

- 新密码 15–64 个 Unicode 码点，同时 UTF-8 编码不超过 72 字节；前后端均明确提示字节限制，绝不静默截断。
- 支持空格和粘贴，不 trim、不隐式规范化，不强制大小写/数字/符号组合；拒绝非法 Unicode 和 NUL。确认密码只在前端比对，不发给服务器。
- 最小长度基于本轮密码是独立登录方式而非 MFA；验证码与密码二选一不等于双因素。[OWASP 身份认证](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html)。
- `autocomplete` 使用 `username/current-password/new-password/one-time-code`，显示密码按钮有明确标签；禁止录制含真实密码的浏览器 trace。

## 页面交互

- `/account` 默认选中验证码，不根据邮箱存在性自动切换。密码表单为邮箱、密码、登录按钮和“使用验证码登录”。
- 切换方式保留内存中的邮箱；清除密码和已输入验证码。有效 OTP challenge 及倒计时可在同页内保留，切回后继续输入，不重复发信。
- 切到邮箱页面再返回触发同一身份的会话重验证时，不清除 challenge、邮箱、已输入验证码或密码；真实身份变化、成功提交或离开账号页面才清理秘密字段。
- 验证码注册/登录成功和密码登录成功都走现有 session transition、CSRF 刷新、安全 `returnTo` 和项目权限检查，不复制另一套跳转逻辑。
- `/settings/account` 根据真实密码状态显示“设置密码”或“修改密码”。设置时邮箱只读、服务器决定收件人；修改时原密码、新密码和确认密码。
- 修改成功清空密码字段，刷新状态并提示其他会话需要重新登录。仅刷新页面/切换登录选项不会发送邮件或修改密码。

## API 契约

沿用 Cookie 会话、CSRF 和结构化错误。请求体限定 4 KiB，严格字段白名单，拒绝重复字段、尾随 JSON 和错误类型。所有认证响应 `Cache-Control: no-store`。

| 接口 | 输入 | 成功响应 | 约束 |
|---|---|---|---|
| GET `/api/auth/methods` | 无 | 原 `emailCodeEnabled` 加 `passwordEnabled:boolean` | 不查询具体邮箱；同步更新严格前端解析器 |
| POST `/api/auth/password/verifications` | `{email,password}` | 200，沿用 Identity DTO | 有效访客会话 + CSRF；不创建账号 |
| GET `/api/account/password` | 无 | 200 `{configured:boolean,updatedAt:string|null}` | 仅本人正式账号；资料模块不依赖该字段 |
| POST `/api/account/password/challenges` | `{}` | 201 `{challengeId,expiresAt,resendAfterSeconds}` | 仅未设置密码的正式账号；收件人服务端确定 |
| PUT `/api/account/password` | 下述联合类型 | 200 `{configured:true,updatedAt:string}` | 同时更新凭据版本、轮换当前会话 |

```ts
type PasswordUpdate =
  | { mode: 'setup'; challengeId: string; code: string; newPassword: string }
  | { mode: 'change'; currentPassword: string; newPassword: string }
```

- 错误码：422 `INVALID_INPUT` 或统一 `PASSWORD_CREDENTIALS_INVALID`（邮箱或密码不正确，请重试或使用验证码）；错误不区分邮箱不存在与未设置密码。
- 401 仅用于真实会话失效；403 用于 CSRF 拒绝。密码错误用 422，避免现有客户端将 401/403 当作会话失效清空访客状态。
- 409 `ACCOUNT_REQUIRED`、`PASSWORD_ALREADY_CONFIGURED`、`PASSWORD_NOT_CONFIGURED` 或并发冲突只用于已识别本人账号的设置管理，不向登录入口泄露账号状态。
- 429 限流附 `Retry-After`；503 暂时不可用、SMTP 未确认或安全传输条件不符。验证码无效/过期沿用统一失败语义。
- POST/PUT 不自动重试、不套通用请求响应幂等缓存。网络中断后先重新校验会话及查询密码状态；不得自动再次提交密码或重新发送验证码。
- 已登录账号不能利用密码登录接口切换另一个账号，需先显式退出；与现有验证码入口规则保持一致。

## 数据模型与迁移

使用下一可用 Flyway 版本（当前最新 V016，实施时再核对），只新增结构，不修改历史迁移文件：

1. `account_passwords`：`user_id` 主键并外键到 `user_accounts`、`password_hash varchar(255)`、`created_at/updated_at timestamptz`。现有账号不回填密码、不生成默认密码。
2. `user_accounts.auth_version bigint NOT NULL DEFAULT 0`，非负；每次成功设置/修改递增，用于撤销旧认证会话。
3. `email_auth_challenges` 增加 `purpose`（默认 `LOGIN`，允许 `PASSWORD_SETUP`）和设置用途绑定的 `credential_version`。旧 challenge 保持登录用途；用途参与新设置验证码的 HMAC 输入及消费条件。
4. 现有登录验证码算法保持兼容；设置验证码不得交给注册/登录验证接口使用，反向亦然。重发只替换相同用途、账号、会话的有效 challenge。

不引入明文密码、可逆加密、额外邮箱副本。哈希仅留当前有效值，修改时覆盖；账号仍存在期间保留凭据，后续删除账号必须同步删除凭据。challenge 和限流记录复用现有过期清理，不延长保留期；备份仍按既有策略处理，不声称修改密码会清除历史备份。

## 事务、限流与会话撤销

- BCrypt 校验/编码不在全局 `AuthTransactions` 锁或数据库事务内执行；在昂贵计算前，以短事务提交尝试预算，再计算，最后短事务校验快照并提交。
- 登录先读取 hash 与 `auth_version` 快照；校验后必须重新检查账号版本和 hash 未变。认证上下文携带验证时的版本，不能在提交会话时随手读取最新版本给旧密码背书。
- 设置密码必须在最终事务中验证当前账号/会话、专用验证码用途、状态、有效期、版本和尚未设置密码；消费验证码、写密码及递增版本原子完成。失败尝试次数不能随业务异常回滚。
- 修改密码必须最终比对旧 hash/version；竞争提交至多一次成功，不允许后到请求覆盖前一次修改。
- 设置/修改后轮换当前 session ID 和 CSRF，新的上下文绑定已提交版本；注销旧 session，撤销其未用验证码。其他会话在下一次 HTTP 或 SSE 检查时因版本不符失效，不依赖“只删除会话行”。
- 兼容旧已登录上下文：没有版本戳的上下文只视作版本 0；数据库版本递增后不得继续通过。访客角色和升级规则不变。
- 会话保存失败时关闭访问、要求重新登录；不回滚已提交密码，也不恢复旧版本会话。SSE 断开不取消后台生成任务。
- 每 15 分钟窗口的密码登录预算：规范化邮箱 10、会话 10、IP 50；密码管理验证：账号 10、会话 10、IP 50；两者共享密码操作全局 100 的上限。成功和失败都计数；键使用现有稳定 HMAC 的独立命名空间，不记录原始 IP。
- 密码操作进程内最多 2 个并行昂贵计算，不排无限队列；满额返回 429。不存在账号也做固定 dummy-hash 校验，避免直接快返回；不承诺网络层严格恒时。
- 设置验证码沿用 10 分钟有效、最多 5 次错误和现有发送冷却/邮箱/IP/全局发送预算，不新增可绕过邮件限流的通道；SMTP 不在事务锁内执行。
- 这些是本轮新密码流预算，不修改已有验证码登录的预算数值。代理 IP 仍按已有受信代理边界处理，不直接信任任意转发头。

## 传输与部署边界

本地回环地址允许测试；公网密码提交默认要求 HTTPS。现有 `EMAIL_AUTH_ALLOW_INSECURE_HTTP` 不自动开放密码提交。若用户后续明确接受 HTTP 演示风险，再使用独立 `PASSWORD_AUTH_ALLOW_INSECURE_HTTP=false` 的显式开关；本轮不修改任何私有 env 或服务器值。

现有邮箱认证启用条件仍是基础开关；密码可用性另检查传输和所需 HMAC 配置，不以每次 SMTP 发送是否成功决定密码登录可用性。邮箱发送服务故障可影响首次设置，但不应阻断已有密码的正确登录。

未来部署前需验证迁移与备份。回滚不删除哈希或降低 `auth_version`；不能直接切回忽略版本撤销的旧二进制而声称撤销仍有效，须使用保留安全检查的回滚版本，或先停止账号访问再处理会话。

## 技术栈、位置与代码风格

沿用 [账号资料规格](SPEC-account-profile.md) 的精确版本和构建命令，不增加依赖。

- 后端：`identity/` 中密码控制器/服务、凭据存储；复用并扩展 `AccountSessions`、`SessionAccess`、challenge 与预算组件；新增 Flyway 迁移。
- 前端：`api/` 密码接口及解码器、`stores/session.ts` 认证切换、`AccountPage.vue` 登录方式、管理页密码组件；不把秘密写入持久 store。
- 测试：同包 JUnit/数据库 IT、同目录 Vitest、`frontend/e2e/` 隔离浏览器流程；测试发件器不进入生产 JAR。
- 风格遵循已有 Java 显式 DTO 和 TypeScript 可辨识联合类型。哈希策略示意：

```java
PasswordEncoder encoder = new DelegatingPasswordEncoder("bcrypt",
        Map.of("bcrypt", new BCryptPasswordEncoder(12)));
```

## 验证命令、策略与完成标准

使用 `SPEC-account-profile.md` 的完整 Java/Node 环境与测试库前置条件。以下从仓库根目录执行：

```sh
./backend/mvnw -B -f backend/pom.xml verify
npm --prefix frontend run test:unit
npm --prefix frontend run typecheck
npm --prefix frontend run lint
npm --prefix frontend run format:check
npm --prefix frontend run build
cd frontend
CI=1 npx playwright test --config playwright.entry.config.ts
CI=1 npx playwright test --config playwright.runtime.config.ts
```

开发沿用账号资料规格的 8081 / 5174 测试入口，不开启真实发信和模型。先写失败的安全/行为断言，再实现；实际 BCrypt cost 必须参与集成测试，不能全程 mock 后声称通过。

验收必须覆盖：

1. 老账号没有默认密码；验证码仍默认且能注册/登录，密码成功返回来源页；错误不泄露邮箱是否存在。
2. 首次设置仅本人邮箱专用验证码可用；过期、重放、跨会话、跨用途、跨账号全部拒绝。
3. 修改后旧密码失败、新密码成功；错误原密码不修改数据；账号项目归属保持不变。
4. 并发设置、并发修改、旧密码校验与修改交错、旧会话迟到保存均不恢复旧权限；另一个浏览器 HTTP/SSE 访问被撤销。
5. CSRF、输入长度/UTF-8 字节边界、畸形 JSON、账号枚举路径、限流共享和全局并发控制有测试。
6. 邮箱标签页往返、同身份重验证不清空 OTP；真实换账号清理；密码/验证码不出现在存储、URL、日志、API 响应。
7. 公网 HTTP 默认不能提交密码，本地回环可验收；未知算法或异常凭据 fail closed。
8. 记录实际密码校验耗时与有界并发结果；若超过现有请求预算，调整调度/设计，不静默降低 cost 12。
9. 新旧迁移升级与旧账号登录兼容；生产打包无测试码读取入口。页面四尺寸、键盘、错误聚焦和辅助提示通过。

## 边界与待审批项

- 始终：服务端二次验证、参数化 SQL、会话撤销、无敏感日志、单用途验证码、隔离测试。
- 本规格审批覆盖：上述增量数据库字段、密码认证流程、密码限流预算、BCrypt cost 12 与输入限制。
- 需另行确认：改用 Argon2id 并增加依赖、密码重置、邮箱修改、降低安全限制、开启公网 HTTP 密码功能、推送/部署。
- 禁止：默认共享密码、仅靠当前 Cookie 设置密码、复用登录 OTP 绕过设置证明、恢复旧凭据版本、改动模型配置。
