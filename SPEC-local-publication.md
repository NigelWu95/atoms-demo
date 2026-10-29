# 本地独立端口发布

2026-10-29：用户确认使用独立端口访问，每个用户应用由独立本机进程承载。沿用已授权的完整实施范围；本轮不部署服务器。

## 目标与行为

- “本地发布”复用完整工程的构建、迁移核对、启动和停止流程。一个项目只运行一个已选构建，每个项目使用自己的端口和数据库；不另建重复的发布进程或发布状态表。
- 工作台显示启动入口、就绪应用的直接访问链接以及停止 / 下线入口。启动过程中不把数据库 READY 标记或预先分配的端口冒充可访问应用。
- 后端仅向项目所有者返回当前协调器实际持有的就绪进程地址，绑定运行、构建、源码版本；不接受客户端传入端口、主机或目标 URL。
- 浏览器新窗口直接访问应用，不经过平台代理，不依赖平台预览的十分钟授权。退出平台或关闭工作台不停止应用；下线通过原协调器停止真实进程，保留业务数据。
- 地址只用于平台与浏览器在同一台机器的本地场景。平台 origin 为 `127.0.0.1` 时应用链接使用 `localhost`，反之使用 `127.0.0.1`，避免把平台的主机 Cookie 发给应用。进程仍仅监听 IPv4 回环地址。
- 更新使用原有停止后替换流程和项目固定端口，会有短暂中断。只保存新源码或构建不会自动切换运行版本。平台服务重启会停止其管理的应用，需要重新启动；本轮不增加后台自启动。

## 实现与边界

在 `project-execution/ProjectExecutionAccess` 添加实际进程端口读取边界；在 `project-delivery` 添加所有者会话授权的地址查询；前端独立组件展示地址和发布操作，继续使用现有工作台命令。

API：`GET /api/projects/{projectId}/delivery/runs/{runId}/address`，返回 `runId/buildId/sourceVersionId/url`。非所有者、未就绪、停止中、已停止或原进程丢失均拒绝。请求不接受额外参数或正文。响应不缓存，不暴露数据库配置、路径或会话。

不新增依赖、数据库迁移、公共路径代理、公网映射或额外服务器。保留原预览、工程下载和旧版发布。仅修改本功能相关文件，不纳入现有未跟踪 OAuth 文档。

代码沿用 Java 17 record、现有短事务与能力校验边界以及 Vue Composition API；例如 `record LocalAddress(UUID runId, UUID buildId, UUID sourceVersionId, String url) {}`。

## 顺序与验收

1. 地址查询：验证所有权、真实进程、配置和状态变化；后端定向单元 / HTTP 集成测试。
2. 工作台入口：展示链接、复制、启动与停止；测试身份 / 项目 / 运行切换、迟到响应和失败提示；运行类型检查、lint、构建。
3. 用原 2048 实际本地浏览器验证直接访问、后端登录、存档、停止后端口关闭，以及重启后相同端口与数据恢复。保留运行供用户体验，记录结果。

命令（仓库根目录，沿用独立测试数据库）：

```sh
sh .local/run-ex22-checks.sh ex22-local-publication.log -Dtest=DeliveryAccessTest,DeliveryManagementIT,DeliverySettingsTest test
cd frontend
npm run test:unit -- src/components/LocalApplicationLink.spec.ts src/api/projectDelivery.spec.ts
npm run typecheck
npm run lint
npm run build
```

测试使用项目配置的 Java 17 与 Node 24。测试不调用模型、不发送邮件、不清空用户数据。发布原 2048 前保留数据库基线，正常停止 / 启动，无强制杀进程。
