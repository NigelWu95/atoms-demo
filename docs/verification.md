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
- 真实启动失败验证：另起短暂进程，使用不可达的回环端口 1 作为数据库目标，预期以退出码 1 结束并报告连接拒绝；未停止现有后端或数据库。
- 回归保护：暂将健康故障响应由 503 改为 200，测试按预期失败；随后恢复原代码并重新全量验证。不保留任何变异。

## 前端设计契约

参考原产品研究中的首页、聊天、App Viewer 三类界面结构，而非复制品牌资产。

- 首页任务：描述应用；本切片主动作是“进入工作台”，明确还未创建项目或启动生成。
- 工作台任务：浏览对话/预览布局；未实现的生成、源码和发布有禁用或清晰空态。
- 风格：墨黑画布、浅灰表面、黄绿色主动作；系统字体，不加载第三方字体或图片。
- 桌面约 224px 导航、380px 对话及弹性预览；移动端使用对话/预览切换。
- Enter 提交、Shift+Enter 换行、IME 组合期不提交；所有控件有可访问名称与可见焦点。
- 必须验证加载、服务断开、重试和空态；不使用假进度、假项目、假生成结果。

## T02–T03 / C01 · 本地通过

2026-10-07，Node 24.20.0、npm 11.19.0；版本细节以 frontend/package-lock.json 为准。

| 检查 | 结果 |
|---|---|
| npm ci --ignore-scripts | 按锁文件干净安装通过，依赖生命周期脚本保持禁用 |
| npm run typecheck | 通过，strict 且未跳过依赖声明检查 |
| npm run lint / format:check | 通过 |
| npm run test:unit | 3 个文件、13 项测试通过，无跳过 |
| npm run build | 通过，JS gzip 约 41.11 kB，CSS gzip 约 3.18 kB |
| npm run test:e2e -- --project=chromium | 8 项真实 Chrome 测试通过，无重试或跳过 |
| npm audit | 0 项已知漏洞；不等于完整安全审计 |
| npm audit signatures | 253 个包注册表签名通过，其中 114 个包具有已验证来源证明 |

浏览器验收覆盖：真实同源 API、正常/错误/重试状态、示例填充不提交、Enter/Shift+Enter、路由往返草稿、未实现功能禁用、深链接刷新、404 恢复、五种宽度（320、390、768、1024、1440）、axe WCAG 2 A/AA 与 2.1 AA 标签检查。主流程浏览器 console 没有 error/warning。中文 IME 防误提交通过组合事件单元测试验证，未宣称测试了每种操作系统输入法。

已查看 390/1440px 首页与工作台截图；文件由 Playwright 写入 `frontend/test-results/`，不提交生成物。无横向溢出；手机工作台按区域切换，桌面左右分栏。未做真实性能基准或完整人工屏幕阅读器审计。

RED/GREEN：PromptComposer、health 请求解析及 App 路由测试先因实现缺失失败，实现后全部通过。类型检查曾暴露缺少 @types/node 与 ESNext.Disposable，补齐声明后通过；jsdom 没有 scrollTo 实现，单元环境使用明确的环境替身，滚动/导航保留浏览器验收。

前端首次依赖安装约 3 分钟，干净锁文件安装约 5 秒；第一次后端 Wrapper 完整验收约 34 秒，后续约 14 秒；第二次浏览器验收约 39 秒。以上是命令耗时，不代表本次总开发工时。

## 当前边界与自审

- 按 correctness/readability/architecture/security/performance 五轴自审：固定规模只读健康 API、受限数据库账号、前端请求有超时、输入长度受限、Vue 转义输出、不执行生成代码；未发现当前切片的阻塞项。
- 已验证故障状态回归测试可捕获代码变异；恢复后后端 4 项测试和构建再次通过。
- 只在本地 feature 分支保存检查点，没有推送、合并、云部署或付费模型调用。
- T00 未完成：真实模型和线上资源仍待落实；T04/C02 仍未通过：没有部署骨架或在线链接。
- T05 起尚未实现。当前浏览器草稿不持久化、没有真实项目与身份、没有生成/预览运行/发布；不能作为挑战最终提交版本。
- Java 17.0.5 的安全补丁、公网运行限制、后端依赖漏洞扫描、完整登录/权限/沙箱测试在上线前单独验收。本次 npm 的 0 漏洞结果仅针对前端锁文件。

## 官方实现依据

- [Vue 表单及中文输入法](https://vuejs.org/guide/essentials/forms.html)
- [Vue Router 路由](https://router.vuejs.org/guide/)
- [Pinia setup store](https://pinia.vuejs.org/core-concepts/)
- [Vite 同源开发代理](https://vite.dev/config/server-options.html#server-proxy)
- [Vitest 配置](https://vitest.dev/config/)
- [Playwright 独立测试与配置](https://playwright.dev/docs/test-configuration)
- [Vue ESLint](https://eslint.vuejs.org/user-guide/)
- [Spring OncePerRequestFilter](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/web/filter/OncePerRequestFilter.html)
