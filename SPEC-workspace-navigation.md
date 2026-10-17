# Spec: 常驻工作区与右侧创作页

模块：`workspace-navigation`；索引：[能力地图](ACCOUNT-WORKSPACE-CAPABILITY-MAP.md)。

状态：2026-10-17 用户已确认，实施与本地验收完成；未推送或部署。依赖账号管理与登录页的路由契约，不依赖密码字段或凭据存储实现。证据见[本轮记录](https://github.com/NigelWu95/atoms-demo/blob/main/docs/account-workspace-verification.md)。

## 目标与当前原因

首次未登录访问展示无侧栏介绍页，输入 idea 后自动创建项目并进入工作区；进入后，“开始创作”始终在右侧页面工作，保留左侧导航。

现有侧栏“开始创作”和品牌链接固定指向 `/`，而 `/` 固定使用无侧栏 `HomePage`。这是路由/布局契约的问题，不是简单让侧栏 CSS 常驻。

本轮不改变“提交 idea 创建项目，然后由用户明确启动生成”的既有边界，不在路由跳转或刷新时付费调用模型。

## 路由与布局契约

| 路径/操作 | 首次匿名、未进入工作区 | 已登录或本浏览器已进入工作区 |
|---|---|---|
| `/` | 欢迎介绍页，无侧栏 | replace 到 `/create`，保留工作区布局 |
| `/create` | 进入工作区创作页，记录已进入 | 右侧创作页，侧栏保留 |
| `/projects`、`/workbench`、`/projects/:id` | 按原功能进入工作区 | 不改原路由功能，侧栏保留 |
| `/settings/account` | 工作区访客状态与登录入口 | 工作区本人账号管理 |
| `/account` | 独立整页注册登录 | 已登录提示和管理入口；不嵌进右侧，不自动退出 |
| `/a/:slug` | 原公开应用布局 | 同左；不设置工作区进入标记 |
| 私有独立预览 | 保持现有预览布局和鉴权 | 不套新创作布局，不改变预览地址 |

- 工作区侧栏的品牌和“开始创作”都指向 `/create`。工作台、项目列表、管理页之间切换保持同一个侧栏布局组件。
- 会话初始化尚未完成时不先绘制欢迎页再闪入工作区；显示中性的连接状态，并提供失败重试。不能用缓存中的“已登录”标记决定身份。
- 首次访问 `/account` 不等于进入工作区；浏览公开应用也不改变创作入口状态。
- 已进入工作区后退出登录仍到 `/create` 的访客工作区，不回欢迎页；身份变化仍清理私有项目/账号视图，不把上一账号的内容当作访客数据继续展示。
- 浏览器后退到 `/` 时按相同规则 replace，不来回跳转；不会把用户强制留在站内或改写站外历史。

## 状态存储与恢复

- 使用一个版本化、本域的 `localStorage` 布局标记，例如 `atoms.workspace.entered.v1=1`；只存布尔意图，不保存账号 ID、邮箱、项目路径、idea、密码或验证码。
- 仅工作区路由成功进入后设置；首次创建失败留在欢迎页，保留输入，不虚报进入成功。
- 已登录账号访问 `/` 直接进工作区，不依赖该标记；持久标记仅决定匿名回访的布局，不授予任何接口权限。
- 存储被禁用、配额错误、无效值时安全降级到内存。此时当前页面生命周期仍保留工作区；匿名用户关闭后重访可能再次看到欢迎页，不能承诺存储被清除后仍识别首次访问。
- 登出/切账号清理认证和项目数据，但保留无身份含义的布局标记。清除站点数据会清除标记，符合首次访问行为。
- 不依赖存储事件自动跨标签导航；另一个标签进入工作区只影响之后访问根路由的选择。

## 创建与登录返回

- 从 `HomePage.vue` 提取同一个 idea 输入/创建流程，供欢迎页与 `WorkspaceCreatePage.vue` 使用。使用原项目创建 API、原幂等键、命名规则、草稿关联和失败恢复，不复制两套业务实现。
- 工作区创作页保留 idea 输入、示例和主要创建动作；欢迎页的长篇介绍不重复塞入右侧。
- 重复提交不创建多个项目；请求结果迟到时核对会话代次和页面意图，不在其他账号里挂载旧访客创建的项目。
- 从 `/create` 进入登录页携带安全返回目标；登录成功回 `/create`，保留同标签页内尚未提交的 idea；首页来源登录后进入工作区创作页并带回该 idea，而不是短暂重现欢迎页。
- 原项目来源继续先查项目访问权；目标不属于登录后的账号时回列表并说明原因，不合并项目。
- 既有 `accountReturnTarget` 白名单只新增 `/create` 和 `/settings/account`；继续拒绝站外、协议相对 URL、API、账号循环及未知 query/hash。
- 当前页面明确提交登录成功才自动返回；会话后台重验证、跨标签登录通知不自动重播提交或导航。
- 已经保存的项目草稿和生成状态仍按服务端/现有恢复逻辑加载；本模块不额外承诺未提交 idea 跨刷新持久化。

## 技术栈、代码位置与风格

使用现有 Vue 3.5.43、Router 5.3.1、Pinia 4.0.3、TypeScript 5.9.3、Vite 8.3.3，不新增依赖或后端接口。

- `frontend/src/router.ts`：路由和安全根入口选择。
- `frontend/src/App.vue`、`components/SessionStatus.vue`、入口导航：布局和入口目标。
- `pages/HomePage.vue`、新增 `pages/WorkspaceCreatePage.vue`、`components/` 共享创作组件：单一创建逻辑。
- `runtime/accountReturn.ts` 和布局标记模块：纯函数/受控存储封装；不要将全局 router guard 散落到页面监听器。
- 同目录 `*.spec.ts` 和 `frontend/e2e/`：单测及真实浏览器入口回归。实施后更新 `docs/welcome-experience-verification.md`。

沿用 `<script setup lang="ts">`、局部样式、现有设计 tokens；导航用 RouterLink，操作用 button。例如：

```vue
<RouterLink to="/create" class="nav-link">开始创作</RouterLink>
```

进入工作区与 `publicApp` 分开建模；绝不为了布局绕过身份初始化或私有视图重验证。

## 命令与测试策略

从仓库根目录运行，Java/测试数据库环境与 [账号资料规格](SPEC-account-profile.md) 一致：

```sh
export PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH
npm --prefix frontend run test:unit
npm --prefix frontend run typecheck
npm --prefix frontend run lint
npm --prefix frontend run format:check
npm --prefix frontend run build
cd frontend
CI=1 npx playwright test --config playwright.entry.config.ts
CI=1 npx playwright test --config playwright.runtime.config.ts
```

本地开发使用 `npm --prefix frontend run dev:fixtures`，后端沿用账号资料规格中的 FixtureApplication 命令；不能误连日常 SMTP/模型环境。

先为旧行为写失败断言：工作台点击“开始创作”后侧栏消失；再实现路由和共用组件。Vitest 验证入口决策、存储异常、单一创建逻辑和会话竞态；Playwright 验证实际跳转、刷新、后退、登录返回及四尺寸布局。Chrome DevTools MCP 不可用时使用仓库已有 Chrome/Playwright，并明确记录替代证据。

## 验收条件

1. 全新浏览器、未登录访问 `/`，无侧栏；首次 idea 提交后真实创建且进入项目工作台。
2. 工作台点击“开始创作”到 `/create`，侧栏实例不被卸载，右侧可以再次创建项目。
3. 同浏览器访问根地址、刷新或后退到根地址均保持工作区；正式账号在新浏览器已恢复会话后也不会显示首次欢迎页。
4. 访客仍能在工作区创作；未注册不被强制送到登录页；退出后不展示旧私有内容，也不回欢迎页。
5. 独立登录、账号管理、已有项目返回、验证码往返邮箱页面全部继续正常；输入和来源状态不因相同身份重验证丢失。
6. 禁用存储不会白屏或跳转循环；仅公开应用访问不污染创作入口标记。
7. 一次提交只创建一个项目；创建失败、重试、身份切换、迟到响应有断言；没有新增隐式模型调用。
8. 320 / 768 / 1024 / 1440px 导航可用、无横向溢出；当前页高亮正确，主标题/键盘焦点和状态提示可访问，控制台无新增错误。

## 边界与开放项

- 始终：单一创建流程、保留安全返回检查和私有视图隔离、保留旧导出按钮、记录测试证据与未验收项。
- 另行确认：自动启动模型生成、持久保存未提交 idea、强制注册、重写工作台或私有预览、推送/部署。
- 禁止：认证信息写 localStorage、用“进入过工作区”代替授权、为了保留侧栏保留旧用户私有数据、覆写其他未完成任务。
- 无额外产品开放问题；浏览器清理/禁用存储后的降级限制需随规格确认。
