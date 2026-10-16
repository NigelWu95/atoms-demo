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
- 草稿保存到 PostgreSQL 项目草稿表，一项目一份，owner 从会话推导。最大 4000 字符，版本号 CAS；重复同内容重试可确认成功，冲突显式展示，不静默覆盖。首页未创建项目的输入仍仅内存，界面明确范围。
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
## 完成标准
新行为有先失败后通过的测试；全量回归、浏览器主流程通过；更新实际完成与限制。任务索引见 todo.md。
