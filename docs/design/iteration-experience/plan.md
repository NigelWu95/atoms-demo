# 连续开发与工作台优化实施计划
## 范围与授权
按已确认五模块实施；不覆盖工程导出旧任务及 OAuth 草稿，不新增基础设施、依赖、自动部署或任意后端执行。
## 技术与代码约定
沿用 Java 17 / Spring Boot 3.5 / JDBC / PostgreSQL、Vue 3 / TypeScript / Pinia。后端 `backend/src/main/java/dev/atoms/demo/`，测试同包 `src/test/java`；前端 `frontend/src` 同目录 spec 测试。
服务端先校验 owner，再读取关联对象；前端用 Vue 文本插值，不渲染模型 HTML。沿用小型 service、record 和 ApiException：
```java
if (!project.ownerId().equals(owner)) throw new ApiException(404, "PROJECT_NOT_FOUND", "项目不存在或无权访问。");
```
## 决策
- 历史需求仅遍历当前基线的版本祖先，恢复节点沿 restoredFromId 跳转，排除被回滚分支。最多遍历 64 层、取 8 条成功请求、总计 12000 字符。当前产物始终为事实基线；历史是参考，不得覆盖最新明确需求。无需额外模型调用。
- MODIFY 优先返回 artifact-patch-v1：基线哈希 + 1–24 个精确文本替换，每次匹配必须唯一。只允许三份既有文件；manifest 只允许 title/summary 更新。整份产物输出继续兼容。补丁不直接执行，合并后完整校验，再走现有版本 CAS。失败最多一次修复。
- 版本审阅由真实快照计算差异，不把模型自述当验收结论；保留完整快照，因此预览/导出继续消费同一产物。
- 草稿保存到 PostgreSQL 项目草稿表，一项目一份，owner 从会话推导。最大 8000 字符（沿用现有输入上限），版本号 CAS；重复同内容重试可确认成功，冲突显式展示，不静默覆盖。首页未创建项目的输入仍仅内存，界面明确范围。
- 归档是可恢复的列表整理，不删除版本、会话、业务数据，不停用发布链接，也不禁止访问工作台。归档与恢复使用独立资源端点。默认 API 保留创建时间排序，页面显式选择最近编辑；查询采用参数化 SQL、稳定游标。
## 边界与风险
始终：输入上限、会话隔离、模型输出校验、失败旧版本保留、直接展示旧导出入口。
先问：新外部服务/依赖、破坏性操作、部署、范围扩大。
禁止：提交 .env/密钥、打印邮箱验证码、降低测试门槛、给模板生成宣称任意工程能力。
本轮加法迁移用于草稿与归档；不修改已有迁移，不自动执行生产迁移。回退代码可忽略新增表/列，不 drop 数据。
## 验证命令
在 backend：`./mvnw -B -Dtest=GenerationWorkflowTest,ArtifactPatchTest test`；全量 `./mvnw -B verify`（现有专用测试库配置，不使用日常库）。
在 frontend：`npm run test:unit`、`npm run typecheck`、`npm run lint`、`npm run build`；真实 Chrome 使用现有 Playwright 隔离测试服务。
凭据只由已有私有配置加载，不进入命令输出。离线模型替身验证协议；真实 DeepSeek 多轮质量必须单独标识，不能用替身冒充。

工具链需先执行 `nvm use`（Node 24.20.x / npm 11.19.x），后端完整测试也会验证本机构建工具链，不能用系统旧 Node。Chrome 和本机 Socket/Mockito 测试需要环境允许相应本地权限；不能通过删断言、关闭浏览器检查或修改数据库配置规避权限问题。

## 接口与一致性

| 接口 | 契约 |
| --- | --- |
| `GET /api/projects/:id/versions/:versionId/review` | `versionId/baseVersionId/baseVersionNo/restoredFromId/runId/request/changes[]`；changes 仅允许四项文件/manifest 前后文本，首次版本与空基线比较，恢复版本与实际恢复前版本比较 |
| `GET /api/projects/:id/draft` | `prompt/revision`；尚未保存为 `""/0` |
| `PUT /api/projects/:id/draft` | 仅 `prompt/expectedRevision`，0–8000 字符；项目行锁串行首次插入和更新。只接受紧邻修订号的同内容重试，其他旧写返回 `409 DRAFT_CONFLICT` |
| `GET /api/projects` | 新增 `q`（0–80 字符字面子串）、`sort=created\|updated`、`archived=true\|false`；游标绑定搜索/排序/归档范围，不跨条件复用 |
| `POST /api/projects/:id/archive` / `DELETE /api/projects/:id/archive` | 归档 / 恢复，重复同状态不推进 updatedAt；不改版本号、当前应用或发布指针 |

草稿编辑缓冲留在现有 Pinia store；600 ms 合并输入、仅一笔在途写，未知响应先精确重试旧值与旧 revision，再发送新值。服务器已清空的非零修订号也视为远端修改；重读期间继续输入不被覆盖。会话重验证暂停写入但保留未保存提醒；真实身份切换清空私有缓冲。页内文本仍可能在未保存时被强行关闭而丢失，不宣称离线草稿保障。

## 参考与边界说明

- [PostgreSQL 17 递归查询](https://www.postgresql.org/docs/17/queries-with.html)：采用显式深度上限的版本谱系查询；不遍历全项目聊天内容。
- [Spring 事务注解](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)：草稿保存入口处建立事务，调用独立 ProjectService 的强制事务行锁；不在锁中访问模型或网络。
- [Vue watcher 清理与同步时序](https://vuejs.org/guide/essentials/watchers.html)：同步失效身份/项目请求，使用 epoch 丢弃过期回包；销毁时停止定时器与离开提醒。

API 所有者均来自现有会话，跨所有者资源返回 404。新增内容采用文本显示，不把差异源码作为 HTML 执行。归档不是访问撤销；需要停止公开访问仍须执行下线。文件前后对比暂非逐行高亮或自动合并。
## 完成标准
新行为有先失败后通过的测试；全量回归、浏览器主流程通过；更新实际完成与限制。任务索引见 todo.md。

## 2026-10-17 第二轮

用户确认继续工作台布局、逐行差异和错误辅助修复。复用现有模块和计划目录；旧任务不改写。先实现纯函数有界差异及组件（4 文件），再调整 ArtifactPanel/ApplicationRunPanel 的 DOM 顺序和紧凑运行入口（不重建执行控制器），最后接入 PreviewFrame 的确认修复建议与已有生成提交逻辑。每片先写失败测试、跑聚焦检查后提交，最后真实 Chrome 与完整回归。

设计约定：预览是右侧主内容，运行主按钮和版本选择在前，工程下载不折叠；高级构建/启停与修改详情在画面之后。保留载入/空/错误/历史状态，源码用文本插值。差异 LCS 比较量上限 250000、行数 2000，超界回退全文，不引入编辑器依赖。

修复复用 run 资源：准备和审阅都不调用模型，确认后以绑定的当前基线创建普通 MODIFY，结果未知沿用同一意图键。只发送固定诊断类别和用户确认的问题描述，原始错误可能含业务数据，不自动发送。所有权、版本 CAS、额度、超时仍由原后端执行；不增加自动发布或无限重试。真实验收以最多 12 次调用为上限，单独记录，不能把调用次数视为金额预算。

## 导出应用首屏优化（2026-10-17，SU01）

PR03 手机截图显示长 summary 与连接卡片把应用推到首屏以下。本片只改独立工程 Vue 外壳，沿用现有深色/黄绿设计，不引入新依赖或外部参考页面：已连接时使用紧凑标题和连接栏，应用画面优先；完整说明放在画面之后的原生 details，默认收起、可用键盘展开，仍仅按文本渲染。启动中/连接失败继续完整显示反馈与手动重试；错误不放入折叠区。

先为默认折叠、DOM 顺序、完整安全文本写失败单测，再实现 App.vue/styles.css。Chrome 用真实独立测试后端和长说明元数据场景，验证 320/768/1024/1440 px：常规标题 + 2000 字符内说明下 iframe 顶部不超过 480 px；说明展开/收起不重建 iframe、不丢未保存输入、不发 API；键盘、44 px 触摸目标、无横向溢出、axe 与控制台检查通过。加载/错误、原保存与重载确认回归不降低断言。DevTools MCP 不可用时沿用隔离 Playwright Chrome。

模板仍使用原生成源码、SDK、数据库和来源快照；构建资源指纹随外壳字节变化，不改协议、存储、模型额度或平台导出按钮。既有下载 ZIP/JAR 不自动更新，新外壳需重新导出/构建。使用上轮本助手新建的专用测试库，不接日常库；不调用模型、不发邮件、不推送/部署。通过后更新当前能力与实际验收记录。

SU01 已完成本地实现与验收：69 项独立工程前端单测、57 项工程导出相关测试、18 项 Chrome 外壳回归通过；类型、规范、格式和前端构建通过。详见[实际验收](https://github.com/NigelWu95/atoms-demo/blob/main/docs/design/iteration-experience/verification.md)。剩余交付项仍包括真实错误辅助修复、更多应用类型与组合边界、云端发布/LIVE 闭环及活动任务中断演练；不将本轮布局优化记为这些项目已完成。
