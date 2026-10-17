# Spec: 常驻工作区与右侧创作页

模块：`workspace-navigation`；索引：[能力地图](ACCOUNT-WORKSPACE-CAPABILITY-MAP.md)。

状态：2026-10-17 版本已部署；2026-10-18 按用户反馈修正规则：根地址始终展示介绍首页，只有工作区入口使用 `/create`。本次修正尚未推送或部署。依赖账号管理与登录页的路由契约，不依赖密码字段或凭据存储实现。证据见[本轮记录](https://github.com/NigelWu95/atoms-demo/blob/main/docs/account-workspace-verification.md)。

## 目标与当前原因

根地址 `/` 始终展示无侧栏介绍页和 idea 输入框，未登录时右上角提供注册/登录入口，已登录时显示“我的账号”；提交 idea 后创建项目并进入工作区。工作区里的“开始创作”始终在右侧页面工作，保留左侧导航。

侧栏“开始创作”和品牌链接使用 `/create`，首页品牌使用 `/`。不再根据进入记录或登录状态把首页强制改成工作区；两种入口通过明确路由区分。

本轮不改变“提交 idea 创建项目，然后由用户明确启动生成”的既有边界，不在路由跳转或刷新时付费调用模型。

## 路由与布局契约

| 路径/操作 | 首次匿名、未进入工作区 | 已登录或本浏览器已进入工作区 |
|---|---|---|
| `/` | 欢迎介绍页，无侧栏 | 同样展示欢迎介绍页，不自动跳转 |
| `/create` | 进入工作区创作页 | 右侧创作页，侧栏保留 |
| `/projects`、`/workbench`、`/projects/:id` | 按原功能进入工作区 | 不改原路由功能，侧栏保留 |
| `/settings/account` | 工作区访客状态与登录入口 | 工作区本人账号管理 |
| `/account` | 独立整页注册登录 | 已登录提示和管理入口；不嵌进右侧，不自动退出 |
| `/a/:slug` | 原公开应用布局 | 同左 |
| 私有独立预览 | 保持现有预览布局和鉴权 | 不套新创作布局，不改变预览地址 |

- 工作区侧栏的品牌和“开始创作”都指向 `/create`。工作台、项目列表、管理页之间切换保持同一个侧栏布局组件。
- 会话初始化尚未完成时保留现有中性连接状态与失败重试，不用缓存中的“已登录”标记决定身份。
- 首次访问 `/account` 和浏览公开应用不改变根地址的页面语义。
- 已进入工作区后退出登录仍到 `/create` 的访客工作区，不回欢迎页；身份变化仍清理私有项目/账号视图，不把上一账号的内容当作访客数据继续展示。
- 显式访问、刷新或浏览器后退到 `/` 时展示首页，不 replace 到工作区，也不改写站外历史。

## 状态存储与恢复

- 布局完全由当前路由决定，不再读取或写入 `atoms.workspace.entered.v1`。旧浏览器已有的这个布尔标记被忽略，无需清除站点数据，不影响账号 Cookie、项目或草稿。
- 会话状态只控制本人账号入口、鉴权和内容可见性，不触发首页自动导航；其他标签页的登录重验证也不改变当前路由。
- 首次创建失败仍留在当前页并保留输入；退出/切账号继续清理旧私有数据，管理页退出后仍到 `/create`。

## 创建与登录返回

- 从 `HomePage.vue` 提取同一个 idea 输入/创建流程，供欢迎页与 `WorkspaceCreatePage.vue` 使用。使用原项目创建 API、原幂等键、命名规则、草稿关联和失败恢复，不复制两套业务实现。
- 工作区创作页保留 idea 输入、示例和主要创建动作；欢迎页的长篇介绍不重复塞入右侧。
- 重复提交不创建多个项目；请求结果迟到时核对会话代次和页面意图，不在其他账号里挂载旧访客创建的项目。
- 从 `/create` 进入登录页后回 `/create`；从首页进入后回 `/`。两种入口都保留同标签页内尚未提交的 idea，不自动创建项目或调用模型。
- 原项目来源继续先查项目访问权；目标不属于登录后的账号时回列表并说明原因，不合并项目。
- 既有 `accountReturnTarget` 白名单只新增 `/create` 和 `/settings/account`；继续拒绝站外、协议相对 URL、API、账号循环及未知 query/hash。
- 当前页面明确提交登录成功才自动返回；会话后台重验证、跨标签登录通知不自动重播提交或导航。
- 已经保存的项目草稿和生成状态仍按服务端/现有恢复逻辑加载；本模块不额外承诺未提交 idea 跨刷新持久化。

## 技术栈、代码位置与风格

使用现有 Vue 3.5.43、Router 5.3.1、Pinia 4.0.3、TypeScript 5.9.3、Vite 8.3.3，不新增依赖或后端接口。

- `frontend/src/router.ts`：显式路由与布局元信息。
- `frontend/src/App.vue`、`components/SessionStatus.vue`、入口导航：布局和入口目标。
- `pages/HomePage.vue`、新增 `pages/WorkspaceCreatePage.vue`、`components/` 共享创作组件：单一创建逻辑。
- `runtime/accountReturn.ts`：安全返回路径白名单；不要用全局 guard 或页面会话监听器改写首页目标。
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

先为错误跳转写失败断言：旧进入标记、已登录状态和后退不应把 `/` 改成 `/create`，首页来源登录也应返回 `/`。Vitest 同时保留侧栏实例、单一创建逻辑和会话竞态断言；Playwright 验证跳转、刷新、后退、登录返回及四尺寸布局。Chrome DevTools MCP 不可用时使用仓库已有 Chrome/Playwright，并明确记录替代证据。

## 验收条件

1. 全新浏览器、未登录访问 `/`，无侧栏；首次 idea 提交后真实创建且进入项目工作台。
2. 工作台点击“开始创作”到 `/create`，侧栏实例不被卸载，右侧可以再次创建项目。
3. 同浏览器访问根地址、刷新或后退均显示介绍首页；正式账号恢复会话后仍显示首页及“我的账号”。直接刷新 `/create` 保留工作区。
4. 访客仍能在工作区创作；未注册不被强制送到登录页；退出后不展示旧私有内容，也不回欢迎页。
5. 独立登录、账号管理、已有项目返回、验证码往返邮箱页面全部继续正常；输入和来源状态不因相同身份重验证丢失。
6. 旧进入标记不影响首页；无需清空浏览器存储、账号 Cookie 或项目数据，没有导航循环。
7. 一次提交只创建一个项目；创建失败、重试、身份切换、迟到响应有断言；没有新增隐式模型调用。
8. 320 / 768 / 1024 / 1440px 导航可用、无横向溢出；当前页高亮正确，主标题/键盘焦点和状态提示可访问，控制台无新增错误。

## 边界与开放项

- 始终：单一创建流程、保留安全返回检查和私有视图隔离、保留旧导出按钮、记录测试证据与未验收项。
- 另行确认：自动启动模型生成、持久保存未提交 idea、强制注册、重写工作台或私有预览、推送/部署。
- 禁止：认证信息写 localStorage、用“进入过工作区”代替授权、为了保留侧栏保留旧用户私有数据、覆写其他未完成任务。
- 本次首页修正以用户最新反馈为准；不需要用户清除站点数据，也不新增跨会话 idea 存储。
