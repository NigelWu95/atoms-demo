# 欢迎体验与旧任务收口验收

日期：2026-10-17。本地 `feature/standalone-engineering`；Vue/TypeScript 前端变更，无新增依赖、数据库迁移或后端认证改动。本轮没有推送 GitHub 或部署服务器。

## 交付范围

- `/`：去除左侧栏和历史项目列表，提供介绍、idea 输入、示例填充和三步流程说明；顶栏进入项目或注册/登录。已有账号显示“我的账号”。
- idea 提交：沿用真实创建 API、自动命名和幂等键；成功进入项目工作台，失败保留文本，未知结果重试同一意图。点击示例不创建项目，创建不自动调用模型。
- `/account`：独立整页、居中卡片，可直达、刷新、浏览器后退；保留验证码发送、冷却、更换邮箱、错误/成功、退出、已有账号不合并访客项目的提示。不是模态覆盖层，不增加焦点陷阱。
- 项目列表、工作台、私有预览仍保留侧栏；公开应用不挂载平台会话或私有资源请求。入口页保留会话重连与服务检查。

依据：[已确认规格](https://github.com/NigelWu95/atoms-demo/blob/main/SPEC-welcome-experience.md)、[合并任务清单](https://github.com/NigelWu95/atoms-demo/blob/main/tasks/todo.md)。以上链接在本轮代码推送后才会包含最新内容。

## 验证证据

- 布局测试先失败：两个入口仍有侧栏；实现后 App 13 项通过。首页移除列表及账号返回/重连文案也分别先红后绿。
- 前端全量 **48 文件、280 项单元测试**通过；`typecheck`、`lint`、`format:check`、生产 `build` 通过。没有跳过断言或放宽超时。
- 基础/邮箱/项目 **17 项真实 Chrome 测试**通过，零重试。覆盖真实项目创建、重命名/刷新、越权、未知创建重试、邮箱注册保留归属、跨标签清理、退出、另一浏览器登录、无效验证码、实际 60 秒冷却、键盘进入和后退、会话失败重连，以及原有 SSE 恢复/取消。
- 工作台 **41 项真实 Chrome 回归**最终完整通过，零重试（4.3 分钟）。覆盖受限文档、工程下载、任务恢复、修复确认、预览报告、版本恢复、发布/下线、不同访客隔离、保存冲突与离线恢复。与基础组合共 **58 项不同用例**，不包含新的付费模型质量评测。
- 首页、账号页和工作台在 **320 / 390 / 768 / 1024 / 1440px** 检查溢出与 axe；没有 WCAG 违规。检查了桌面和手机截图，包括启用的验证码发送按钮与焦点样式。
- 关键条件变异：临时反转 SessionStatus 的 `session.ready` 判断，两个已登录入口测试如期失败；立即恢复原条件，App 13 项再次通过。变异未提交。
- 本地日常站点只读检查：5173 返回 200，8080 `/api/health` 为 UP；未重启日常后端、未修改其配置。

代码复核重点：入口布局标记与 `publicApp` 分离；会话监听仍只由一个 SessionStatus 管理，旧私有视图隐藏/身份清理逻辑不变；创建 API、CSRF、幂等与邮箱逻辑复用原实现。首页不再发起项目历史查询，没有引入依赖、外部素材或新的持久化副本。

### 截图发现的问题与修复

首轮 16 项浏览器测试通过后，人工查看截图发现手机服务状态遮住登录按钮：旧 `.service-status` 手机规则在整个页面使用绝对定位。补上浏览器几何位置断言后，390px 用例明确失败（状态 y=16，页脚 y≥1221）。修复将旧定位限定到 `.sidebar .service-status`，入口页状态正常留在页脚；最终 17 项全部通过。补齐账号与重连使用的 secondary 按钮主题，启用态也经过 axe 检查。

红色证据保留在忽略目录 `.local/welcome-layout-red/`。最终截图在 `frontend/test-results/iteration-foundation/`，不上传包含验收会话的浏览器 trace。Chrome DevTools MCP 不可用，使用项目已有 Playwright 驱动独立 Chrome profile，没有访问用户日常浏览器。

工作台首轮回归 40 项通过、1 项失败：预览修复用例的 `getByLabel('代码版本')` 在历史状态下同时匹配下拉框与“代码版本恢复”区域，触发 strict-mode 错误。改为 `getByRole('combobox', { name: '代码版本', exact: true })`，没有改动版本切换业务逻辑、超时或断言；修复后完整重跑 41 项通过。首次失败截图/trace 保留在 `.local/welcome-preview-repair-red/`，最终结果在 `frontend/test-results-runtime/`。

## 可复现命令

要求 Node 24.20.0、Java 17.0.5-oracle、现有专用 `atoms_demo_test` / `atoms_test` 和私有测试数据库配置，8081/5174 空闲。不要把测试指向平台运行库。

在 `frontend/` 执行（先在当前终端配置 Node PATH 与 JAVA_HOME，不改系统配置）：

```sh
npm run test:unit
npm run typecheck
npm run lint
npm run format:check
npm run build
CI=1 npx playwright test --config playwright.entry.config.ts
CI=1 npx playwright test --config playwright.runtime.config.ts
```

新增 `playwright.entry.config.ts` 复用运行时夹具的测试后端和 5174 前端：禁止真实模型执行，邮件由测试适配器捕获，不使用 QQ SMTP。17 项完整验收使用既有私有等价配置 `.local/iteration-browser.config.ts`；新增仓库配置随后实际启动并通过 `foundation.spec.ts` 全部 8 项（重复验证，不额外计入独立用例总数），截图在 `frontend/test-results/entry/`。不要为了查看 Playwright 启动日志把通用 `DEBUG` 环境变量传给 Spring，避免意外开启含响应细节的后端调试日志。

## 旧任务核对与剩余边界

- **L04 已完成**：10 月 14 日晚已通过 JDBC 准备独立日常库，私有启动脚本显式启用本机构建/运行；之前 Docker 管理接口超时只是早期检查点，不是当前缺失功能。
- **L05 已完成有界闭环**：10 月 15 日 2048 V2 的平台/独立工程一致性、首次存档、快速保存、计分、刷新和进程重启恢复已有证据。仅核对并补记状态，本轮没有重新调用模型、建库或重复跑 2048；胜利/满盘结束仍未验证。
- C-EB11 的用户人工评审保持未勾选；多类型应用和真实错误修复质量评测仍需新预算，不冒称全部产品路线图完成。
- 未改变云端执行开关、SMTP/HMAC/模型密钥、生成参数或后端逻辑。本轮不声称重跑了全套 Java、下载重建或云端验收。
- 原首页专用旧 CSS 与 ProjectList 的 recent 支持暂留兼容，未借 UI 调整扩展为清理重构。已知归档筛选竞态不在本轮修复范围。

历史来源：[本机/2048 验收](https://github.com/NigelWu95/atoms-demo/blob/main/docs/app-runtime-verification.md)、[运行配置说明](https://github.com/NigelWu95/atoms-demo/blob/main/docs/app-runtime.md)、[迭代质量评测待办](https://github.com/NigelWu95/atoms-demo/blob/main/docs/design/iteration-experience/todo.md)。
