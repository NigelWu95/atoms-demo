# Spec: workbench-review
## Objective / Success Criteria
从已保存版本获取来源请求及基线，展示文件变更和只读文本对比、真实执行阶段。所有权检验覆盖版本与基线；新 API GET /api/projects/{projectId}/versions/{versionId}/review。界面明确差异不是自动运行通过证明；保留导出按钮。回滚对比的是回滚前基线。
## Tech Stack / Commands / Project Structure / Code Style / Testing Strategy
遵循 [本轮实施约定](docs/design/iteration-experience/plan.md) 的技术栈、命令、目录、代码示例与验证标准。模块范围及依赖见 [能力图](ITERATION-CAPABILITY-MAP.md)。
## Boundaries
只修改本模块及必要调用方，不改变会话安全、模型预算、导出协议或部署。新增接口遵循现有 JSON 错误格式与 CSRF。测试替身只证明协议，不证明真实模型稳定性。
## Open Questions
无阻断项；任何范围扩大另行询问。用户已批准连续设计、实现、测试。
