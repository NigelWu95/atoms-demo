# 实施任务
每项完成前检查测试与构建；本轮用户已授权连续推进，不以中间人工检查点阻断。
- [ ] IC01：当前版本谱系历史上下文；文件：IterationContext、GenerationWorker、GenerationWorkflow、上下文 IT（M）。验收：恢复分支正确、跨用户不可见、有界；验证：聚焦 IT + WorkflowTest。
- [ ] AP01：精确补丁合并；文件：ArtifactPatch、ArtifactPatchTest（S）。验收：错基线/歧义/越界/危险代码拒绝；验证：单测红绿。
- [ ] AP02：生成工作流与 prompt 接入补丁、历史上下文；文件：GenerationWorkflow、builder/planner prompts、WorkflowTest（M）。验收：CREATE 兼容、MODIFY 可补丁、仍仅一次修复；验证：单测与 GenerationExecutionIT。
- [ ] 检查点 A：完整快照仍可预览/下载，失败不改指针；后端回归。
- [ ] WR01：审阅 API；文件：VersionReviewService、ArtifactController、ReviewIT（M）。验收：所有权、基线、请求关联、真实变更信息；验证：HTTP IT。
- [ ] WR02：前端差异查看；文件：review API、VersionReview、测试、ArtifactPanel（M）。验收：文本安全、加载失败可重试、旧导出入口仍可见；验证：单测/类型。
- [ ] WR03：工作台阶段和结果；文件：ConversationPanel、相关测试（S）。验收：显示真实阶段、需求可关联版本、不宣称运行验收；验证：单测与浏览器。
- [ ] DP01：草稿持久化 API；文件：V015、ProjectDraftService/Controller、DraftIT（M）。验收：CAS、重复重试、上限、owner 隔离；验证：专用库 IT。
- [ ] DP02：草稿自动保存 UI；文件：draft API/composable、测试、WorkbenchPage（M）。验收：刷新恢复、晚响应隔离、冲突恢复、保存反馈；验证：单测、浏览器。
- [ ] 检查点 B：账户切换不串草稿；原登录和导出测试通过。
- [ ] PL01：项目检索归档 API；文件：V016、ProjectService/Controller/record、ProjectIT（M）。验收：排序分页、搜索、归档恢复、所有权；验证：HTTP IT。
- [ ] PL02：项目列表界面；文件：projects API、ProjectList、ProjectsPage、相关测试（M）。验收：键盘可用、空/错/加载态、账号文案；验证：单测/浏览器。
- [ ] 最终：全量后端/前端检查、真实浏览器验证、代码审阅、文档更新。不推送、不部署。
