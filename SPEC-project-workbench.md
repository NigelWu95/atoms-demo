# Spec: project-workbench · 完整工程工作台

2026-10-27。依据[能力地图](FULL-PROJECT-CAPABILITY-MAP.md)、[完整闭环连续开发授权](SPEC-project-agent.md#10-2026-10-19-完整闭环实施授权)及用户继续开发的要求实施。本规格记录已授权工作台的具体交互与接口边界；不把实现检查点变成逐项开始许可。源码、Agent、执行和交付模块已完成内部验收，本模块将其接入真实页面。任务状态见[任务清单](tasks/project-workbench/todo.md)。

## 1. 目标

受信维护者能从已有项目进入完整工程工作台：初始化固定 Vue + Spring Boot 工程，描述需求，查看已保存的文件和修改差异，显式准备依赖、构建、核对数据库迁移、启动本机进程，预览真实页面并下载同一源码。后续需求基于当前完整源码继续修改。任务提交、后台执行、源码保存、构建成功和应用就绪分别展示。

新入口为 `/projects/:id/engineering`，沿用已有侧栏、账号和项目身份；已有 `/projects/:id` 与旧导出入口保留。新模式只有开关及维护者权限满足才展示入口；直接访问也必须经过后端授权。首次初始化不是模型生成业务成功，不自动导入、覆盖或启动旧项目。

## 2. 页面与交互

- 顶部紧凑展示项目名、源码版本、运行版本、刷新及返回原工作台；说明放在可展开帮助中。
- 左侧需求输入、已提交任务和阶段事件，输入区固定，内容独立滚动。右侧预览/源码/差异切换；源码文件列表可滚动，正文作为文本展示，不执行源码或使用 v-html。
- 新需求提交携带准确 baseSourceVersionId、expectedSourceRevision 和幂等键。提交结果不明保留原请求，可显式重试同一请求；不自动重发模型、构建、迁移批准或启动。
- 初始化、依赖准备、构建及启动互不伪装；按钮说明实际动作。归档、配置关闭、无绑定、协调器未就绪或待核对时展示原因，不绕过拒绝。
- 迁移查看只读；批准明确绑定所选构建与实际数据库历史/目标摘要。启动只能消费准确批准，不能让客户端选择主机、端口、路径、命令或连接凭据。替换先按原执行器规则停止旧进程；不承诺无停机。
- 已运行预览不随源码选择或轮询重建。按需创建十分钟授权；退出、切换账号/项目、停止或失效时清理。iframe 固定 `sandbox="allow-scripts allow-forms"`、`referrerpolicy="no-referrer"`，禁止 allow-same-origin。
- 两个直接展示的下载入口分别为“下载当前预览源码”和“下载选中构建源码”。只有准确成功构建可以下载；保留原工作台旧导出入口。
- 版本恢复调用现有交付 API，明确只追加源码版本，不回滚业务数据或自动切换进程。
- 320/768/1024/1440 像素下无页面横向溢出；小屏在需求/工程区域切换，保留草稿及预览。遵循现有深色和荧绿色 token、键盘焦点与错误提示规则。

## 3. 接口及模块边界

管理请求沿用平台会话、CSRF、no-store 和项目所有权。新增 `PROJECT_WORKBENCH_ENABLED=false`，不自动开启任何下游模块。页面只有显式动作产生任务；读取不得初始化磁盘、执行代码或调用模型。

新增接口前缀 `/api/projects/{projectId}/workbench`：

| 路径 | 契约 |
|---|---|
| GET `/capabilities` | 本身份和项目的可用状态与固定原因，无路径/配置秘密 |
| GET `/source/head` | 当前源码引用、revision 和状态 |
| GET `/source/versions`、`/source/versions/{versionId}/tree`、`/file?path=…` | 固定不可变源码、版本游标、相对源码路径；白名单元数据及有界 UTF-8 正文 |
| GET `/source/diff?before=…&after=…` | 已有 128 KiB 差异预览及截断标记 |
| POST `/source/initialize`、GET `/source/operations/{id}`、GET `/source/operations` | 服务器固定模板；只追加初始源码，返回已持久化操作；刷新恢复最近 10 次初始化/恢复记录 |
| GET/POST `/agent/tasks`、GET `/agent/tasks/{id}`、GET `/events`、POST `/cancel` | 既有 Agent 提交/读取/取消及有界事件，不接受 CLI 参数或工具列表 |
| GET/POST `/builds`、GET `/builds/{id}`、POST `/builds/{id}/cancel` | 固定源码构建、既有队列及取消 |
| GET/POST `/preparations`、POST `/preparations/{id}/cancel` | 既有受控 npm 锁准备，准确基线与幂等请求 |
| GET `/runtime`、GET `/runs`、GET `/builds/{id}/migration`、POST `/builds/{id}/migration-approvals` | 协调器/绑定可用性、运行列表及显式迁移核对/批准 |
| POST `/runs`、POST `/runs/{id}/stop` | 经原协调器启动/替换/停止准确运行，禁止 PID 操作 |
| GET `/execution/{kind}/{id}/events` | 复用执行模块绑定 owner/project/kind/task 的游标与脱敏事件 |

具体参数复用 provider 的类型与校验；请求 UUID 必须规范，未知 JSON 字段拒绝；新增写请求带 Idempotency-Key。预览、下载、恢复继续使用已经实现的 `/delivery` 接口，不新增第二份令牌/ZIP/恢复逻辑。

源码读门面留在 source 包，初始化复用现有受控模板和 SourceCoordinator；Agent 复用 ProjectAgentService/AgentQuery；执行命令留在 projectexecution 包，维护现有资源租约和准入；workbench 包只拥有 HTTP 输入、开关和白名单 DTO。不得把内部 SourceOperation、数据库凭据、磁盘路径、Git commitId、原始异常或进程句柄整体序列化给页面。

## 4. 状态、刷新与一致性

Vue composable 持有当前项目请求代次、AbortController 和轮询计时器。路由、身份或会话变化立即中断旧读取并清空私有内容；迟到响应不能写入另一个项目。只在可见页面且有活动任务时串行轮询，不叠加请求；分页及事件使用服务端游标，去重，错误停止自动轮询并提供刷新。

需求草稿复用已有项目草稿保存；源码和任务的事实来自服务器。预览令牌只存当前组件内存，不入 URL 查询、localStorage、日志或分享链接。已提交需求及执行事件必须能在刷新后查询；没有后台持久化的信息不得标为“已保存”。

页面展示最近 50 次 Agent 提交和最近 1000 条所选任务事件；版本、构建、依赖准备和运行记录通过显式分页加载。单文件显示至多 256 KiB UTF-8 正文，截断不改变 Git 原文件或下载。未确认写请求的原请求/幂等键只保留在当前页面内存；整页刷新后查询持久任务，不能承诺仍能重试原请求。

## 5. 技术与文件

使用现有 Java 17 / Spring Boot 3.5、Vue 3、Pinia、TypeScript 与 Vue Router，不增加依赖或数据库迁移。实现位于 `backend/src/main/java/dev/atoms/demo/{workbench,source,agent,projectexecution}` 和 `frontend/src/{api,composables,components,pages}`；测试与现有目录同置。接口使用 Java record，前端用 `api.request` 并校验响应字段，例如：

```ts
export type SourceSelection = { projectId: string; sourceVersionId: string; revision: number }
// 不以当前选中源码代替实际预览对应的 sourceVersionId。
```

## 6. 验证命令与标准

后端沿用已批准 runner（隔离 JDK 17.0.14、现有 EX22 schema），一次只运行一个 Maven。定向命令按实际类名执行：

```sh
sh .local/run-ex22-checks.sh ex22-workbench-focused.log \
  '-Dtest=*Workbench*Test' '-Dit.test=*Workbench*IT' verify
# frontend，PATH 使用现有 Node 24.20.0
npm run typecheck
npm run lint
npm run format:check
npm run test:unit
npm run build
CI=true npm run test:e2e
CI=true npm run test:e2e:runtime
```

接口验证开关/会话/CSRF/跨 owner、归档、规范参数、幂等冲突、来源变化及取消。真实 Git/PG 验证文件和版本读取；受控 Agent/执行响应仅验证页面状态，不冒充真实运行。最后在原获准 A/B 资源验证正式页面到实际预览/下载，源码和业务数据边界延续交付模块，不重放 V1 或清库。

## 7. 边界与完成条件

始终：保留旧流程，遵循现有维护者与进程权限，提交前测试，区分假上游、真实 CLI、真实模型和原生运行证据。

另需明确授权：真实付费模型项目/预算、新持久数据库或角色/绑定、改变迁移批准规则、开放额外端口、推送或部署。现有源码、执行及 A/B 验收资源继续复用。

禁止：修改个人 Claude 配置、开放任意 Shell、自动批准 SQL、按 PID 接管旧进程、删除失败审计、将源码选择当作运行切换、把未知提交结果当成未提交自动重试。

完成条件：正式页面能在受控授权环境完成初始化/需求任务/文件与差异/构建与迁移/运行预览/准确下载/源码恢复；刷新与切账号不泄漏或串写；长对话和大文件只在组件内部滚动；默认关闭的旧流程通过兼容回归。真实付费模型和部署结果单独列示。

## 8. 构建失败后的确认式修复

补齐[Agent 规格 §10](https://github.com/NigelWu95/atoms-demo/blob/main/SPEC-project-agent.md#10-2026-10-19-完整闭环实施授权)连续开发范围内的错误反馈入口：已结束、无执行未决且基于当前源码的失败构建可准备修复建议。建议从现有有界执行事件中提取固定失败环节及类别，不传原始日志、命令、路径或任意诊断文字；环境/依赖审批/超时/取消/待核对故障提示先处理对应原因，不自动认定为源码错误。

“准备修复需求”仅查询，不调用模型。建议有独立输入与完整发送预览，用户点击“确认提交修复”后复用现有 Agent 提交、基线和幂等逻辑；原需求草稿保持不变。项目、账号、源码 revision 或所选构建变化即清空建议并中断读取。旧构建不能直接修复当前新源码；需要重新构建核实。修复不自动构建、批准迁移、启动或替换应用，已运行预览保留。

新增前端状态/组件及定向单测和浏览器验证，不新增后端执行器或数据表。UI 沿用顶部可滚动工具面板、可见错误、明确输入标签和键盘焦点，维持小屏内部滚动。
