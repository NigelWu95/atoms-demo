# Spec: 本人账号资料与管理页

模块：`account-profile`；索引：[能力地图](ACCOUNT-WORKSPACE-CAPABILITY-MAP.md)。

状态：2026-10-17 用户已确认并实施，本地验收收口中；未推送或部署。验收边界见[本轮记录](https://github.com/NigelWu95/atoms-demo/blob/main/docs/account-workspace-verification.md)。

## 目标与依据

账号管理应回答“我登录了哪个账号”，展示真实资料而不是只有“邮箱创作者”。现有 `/api/me` 返回 `userId/isGuest/displayName`，账号页没有读取邮箱和注册时间；不能通过更换文案解决资料缺失。

新增工作区内 `/settings/account` 页面；`/account` 保持独立注册登录页。已登录时，“我的账号 / 管理账号”指向管理页；访客的“注册 / 登录”继续指向 `/account`。

## 页面与数据契约

- 正式账号展示：显示名、完整已验证邮箱、账号 ID、注册时间、邮箱已验证标记。邮箱仅在本人管理页完整展示，不放入侧栏、日志或广播消息。
- 注册时间来自 `user_accounts.created_at`，不是访客最初建立时间；时间以 ISO 8601 UTC 传输，界面按本地时区显示并标注。
- 访客管理状态显示“尚未注册”、访客 ID、注册入口；不伪造邮箱、注册日期或密码状态。
- 页面有加载、失败重试、会话失效、退出登录状态；邮箱不在载入完成前使用示例值冒充。
- 保留退出操作和进入项目入口。密码操作由 `password-auth` 模块嵌入独立区域，本模块不推断是否设置密码。
- 管理页在工作区布局中显示；登录页仍是无侧栏整页。直达 `/account` 时若已登录，展示“已登录”与“管理账号”链接，不自动退出或发邮件，也不因后台会话检查主动跳转。

### 新接口

`GET /api/account`：必须有有效会话，身份只取服务端 principal，不接收 `userId/email` 查询参数作为查询目标。

```ts
type AccountProfile =
  | { userId: string; isGuest: true; displayName: string }
  | {
      userId: string
      isGuest: false
      displayName: string
      email: string
      emailVerified: true
      createdAt: string
    }
```

- 200 返回上述联合类型；401 会话失效；503 暂时无法读取。沿用 `{error:{code,message,retryable},requestId}` 错误格式。
- `Cache-Control: no-store`；无批量查询、用户目录或任意用户 ID 查询接口。
- `/api/me`、`POST /api/session` 的既有响应不变，不将邮箱加入全局会话对象。
- 查询复用 `guest_users/user_accounts/email_identities`；本模块不新增数据库字段。
- 前端严格解析响应；仅当前会话代次与页面请求仍匹配时才显示结果。身份切换时立即隐藏并丢弃旧资料；迟到响应不得覆盖新账号。
- GET 可由用户显式重试；不能在错误处理里反复新建访客或触发邮件。

## 技术栈与代码位置

沿用 Java 17.0.5、Spring Boot 3.5.16 / Security 6.5.11、Spring Session JDBC、PostgreSQL 17；Vue 3.5.43、Vue Router 5.3.1、Pinia 4.0.3、TypeScript 5.9.3，不新增依赖。

- 后端：`backend/src/main/java/dev/atoms/demo/identity/` 下账号控制器和查询服务；测试放同包 `backend/src/test/java/`。
- 前端：`frontend/src/pages/AccountSettingsPage.vue`（新增）、`api/account.ts`（新增）、现有 `components/SessionStatus.vue`、`router.ts`。
- 组件单测同目录 `*.spec.ts`；浏览器回归 `frontend/e2e/`，沿用隔离 entry 配置。
- 实施后更新 `docs/email-accounts.md` 的管理页说明；当前不修改已上线功能完成度。

Java 使用现有显式 DTO、参数化 SQL 和异常封装；Vue 使用 `<script setup lang="ts">`、单引号、无分号，不使用 `v-html` 展示账号资料。例如：

```java
public record RegisteredProfile(String userId, boolean isGuest, String displayName,
        String email, boolean emailVerified, java.time.Instant createdAt) {}
```

## 命令与测试策略

以下命令从仓库根目录运行；使用已有隔离测试库 `atoms_demo_test`，测试凭据通过已有本地环境提供，禁止改用日常库。Node/Java PATH 仅对当前终端设置，不切换全局版本。

```sh
export JAVA_HOME=/Users/wubingheng/.sdkman/candidates/java/17.0.5-oracle
export PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$JAVA_HOME/bin:$PATH
./backend/mvnw -B -f backend/pom.xml verify
npm --prefix frontend run test:unit
npm --prefix frontend run typecheck
npm --prefix frontend run lint
npm --prefix frontend run format:check
npm --prefix frontend run build
cd frontend
CI=1 npx playwright test --config playwright.entry.config.ts
```

开发命令（分别在 `backend/` 和 `frontend/`，隔离配置）：

```sh
./mvnw -B spring-boot:test-run -Dspring-boot.run.main-class=dev.atoms.fixtures.FixtureApplication -Dspring-boot.run.arguments=--atoms.generation.worker-enabled=false
npm run dev:fixtures
```

先写失败测试：本人查询、访客字段、越权参数、无效会话、延迟响应碰到账号切换；随后验证组件和浏览器。保持现有测试门槛，不删断言或增加重试掩盖失败。测试不发送真实邮件或调用模型。

## 验收条件

1. 两个隔离账号登录后分别看到自己的邮箱、ID 和注册时间，不泄露另一账号资料。
2. 正式账号和访客都有准确状态；管理页刷新后可以重新加载，不出现空白“已登录”卡片。
3. 私有资料加载失败可重试；退出或跨标签切换账号后，不残留原邮箱。
4. 注册登录页仍可直达；管理页保留侧栏；密码功能未实施时不展示虚假的可用按钮。
5. 320 / 768 / 1024 / 1440px 无水平溢出；长邮箱和 UUID 换行合理，键盘焦点、输入标签、状态提示可访问。

## 边界与开放项

- 始终：服务端鉴权、按本人取数、禁止缓存敏感资料、保留身份切换隔离。
- 另行确认：修改邮箱/显示名、上传头像、账号合并或注销、增加资料字段、部署。
- 禁止：日志打印邮箱/会话凭据、用浏览器缓存的资料判断授权、改动 QQ SMTP/HMAC、修改用户 OAuth 草稿。
- 无需额外产品选择；上述资料展示与页面契约待本次规格审批。资料编辑不属于“展示账号信息”的隐含范围。
