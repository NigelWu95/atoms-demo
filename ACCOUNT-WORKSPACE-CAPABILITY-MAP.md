# 账号管理、密码登录与工作区导航

状态：2026-10-17 用户已批准三个规格并要求一次完成；三个模块已实现并完成本地验收。基线为 `8ee0808`，未推送或部署，不表示云端已包含本轮功能。证据与复跑边界见[本轮记录](https://github.com/NigelWu95/atoms-demo/blob/main/docs/account-workspace-verification.md)。

| 模块 ID | 职责 | 依赖 | 规格 |
|---|---|---|---|
| account-profile | 展示本人账号资料，区分访客和正式账号，提供独立管理页面 | 现有身份与会话 | [账号资料](SPEC-account-profile.md) |
| password-auth | 首次设置密码、验证原密码修改、密码登录，保留默认验证码入口 | account-profile | [密码认证](SPEC-password-auth.md) |
| workspace-navigation | 首次欢迎页和工作区创作页分开；进入工作区后保留侧栏及上下文 | account-profile、password-auth 的页面契约 | [工作区导航](SPEC-workspace-navigation.md) |

实施顺序：`account-profile → password-auth → workspace-navigation`。这是能力边界，不是实施任务清单。

## 共同约束

- 沿用 Java 17、Spring Boot、PostgreSQL、Vue；不接入新的账号平台。
- 新邮箱注册仍升级当前访客；登录已有账号仍切换身份，不合并访客项目。
- 保留验证码表单切换标签页后不丢失、登录后安全返回来源页、项目归属检查。
- 工作区布局标记不能成为认证凭据；账号、密码、验证码不能进入浏览器持久存储或模型上下文。
- 规格确认后的变更范围限本地开发和隔离验收；不修改私有配置、推送 GitHub 或再次部署。
- 保留现有未完成任务和未跟踪 OAuth 草稿。本轮不把这些历史项目标为完成。

## 与已有规格的关系

- [邮箱账号规格](https://github.com/NigelWu95/atoms-demo/blob/main/SPEC-email-accounts.md) 的验证码、身份归属和会话安全继续有效；本轮密码模块扩展其初版“不支持密码”边界。
- [欢迎体验规格](SPEC-welcome-experience.md) 的独立登录页、idea 创建和返回路径规则继续有效；本轮修订常驻工作区入口和登录后的账号管理位置。
- 密码忘记后的重置、修改邮箱、OAuth、用户资料编辑、任意后端生成不在本轮范围内。

## 审批记录

- 已确认：模块范围、构建顺序、首次设置密码验证邮箱、修改密码验证原密码。
- 已确认：三个模块规格中的接口、增量迁移、安全限制、导航与验收条件；用户追加“一次性完成”授权连续计划、实现与本地验收。
- 已实施：本人资料、可选密码认证、常驻工作区；细项见 `tasks/todo.md` 的 AW01–AW09。
- 不在本轮授权中：修改私有配置、推送和部署；公网 HTTP 密码功能仍默认关闭。
