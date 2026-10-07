# 本地验收记录

记录成功和失败证据；未实测的内容不标记完成。

## T01 · 后端骨架（本地通过）

- Java 17.0.5 / Spring Boot 3.5.16。
- RED：先写 HealthControllerTest，`mvn -B test` 因缺少 HealthController 失败。
- GREEN：实现后 2 个单元测试通过；数据库错误被转换为脱敏的 503，带 requestId。
- 集成 RED：连接独立 PostgreSQL 17.11 测试库与 Flyway 迁移成功；HTTP 响应缺 nosniff 导致断言失败。
- GREEN：新增安全响应头，`./mvnw -B verify` 通过：2 个单元测试 + 2 个真实 PostgreSQL 集成测试，无跳过。
- 后端实际监听 127.0.0.1:8080；GET /api/health 返回 200 和 `{"status":"UP"}`，安全头、no-store 已读取确认。
- 直接访问本机需绕过环境中的 HTTP 代理；curl 验证使用 `--noproxy 127.0.0.1`，未修改全局代理。
- 本阶段没有业务表；仅创建 Flyway 迁移历史。测试写入 TEMP 表并回滚，不影响应用库。
- 数据库故障路径在单元测试中验证，未为验收停止或重启用户的数据库服务。

## 前端设计契约

参考原产品研究中的首页、聊天、App Viewer 三类界面结构，而非复制品牌资产。

- 首页任务：描述应用；本切片主动作是“进入工作台”，明确还未创建项目或启动生成。
- 工作台任务：浏览对话/预览布局；未实现的生成、源码和发布有禁用或清晰空态。
- 风格：墨黑画布、浅灰表面、黄绿色主动作；系统字体，不加载第三方字体或图片。
- 桌面约 224px 导航、380px 对话及弹性预览；移动端使用对话/预览切换。
- Enter 提交、Shift+Enter 换行、IME 组合期不提交；所有控件有可访问名称与可见焦点。
- 必须验证加载、服务断开、重试和空态；不使用假进度、假项目、假生成结果。

## 官方实现依据

- [Vue 表单及中文输入法](https://vuejs.org/guide/essentials/forms.html)
- [Vue Router 路由](https://router.vuejs.org/guide/)
- [Pinia setup store](https://pinia.vuejs.org/core-concepts/)
- [Vite 同源开发代理](https://vite.dev/config/server-options.html#server-proxy)
- [Vitest 配置](https://vitest.dev/config/)
- [Playwright 独立测试与配置](https://playwright.dev/docs/test-configuration)
- [Vue ESLint](https://eslint.vuejs.org/user-guide/)
