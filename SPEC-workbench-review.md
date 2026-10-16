# Spec: workbench-review
## Objective / Success Criteria
从已保存版本获取来源请求及基线，展示文件变更和只读文本对比、真实执行阶段。所有权检验覆盖版本与基线；新 API GET /api/projects/{projectId}/versions/{versionId}/review。界面明确差异不是自动运行通过证明；保留导出按钮。回滚对比的是回滚前基线。
## Tech Stack / Commands / Project Structure / Code Style / Testing Strategy
遵循 [本轮实施约定](docs/design/iteration-experience/plan.md) 的技术栈、命令、目录、代码示例与验证标准。模块范围及依赖见 [能力图](ITERATION-CAPABILITY-MAP.md)。
## Boundaries
只修改本模块及必要调用方，不改变会话安全、模型预算、导出协议或部署。新增接口遵循现有 JSON 错误格式与 CSRF。测试替身只证明协议，不证明真实模型稳定性。
## Open Questions
无阻断项；任何范围扩大另行询问。用户已批准连续设计、实现、测试。

## 2026-10-17 扩展

- 预览优先：主运行按钮、版本/环境选择与应用画面靠前；构建/进程高级操作和修改记录放到画面下方。源码导出与预览工程下载保持直接可见，不折叠。保持原组件挂载和运行/下载身份校验。
- 逐行差异：显示旧/新行号、明确增删标志、默认只看变更与上下文，可切换全文；所有源码只作为文本。使用有界 LCS，超过 2000 行或 250000 比较单元回退全文对比，不阻塞页面。
- 采用既有深色/浅绿设计系统；320/768/1024/1440 px、键盘、axe、预览/导出回归均须通过。原界面截图作为对比参考，不复制外部品牌布局。
