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
