# Spec: draft-persistence
## Objective / Success Criteria
项目内草稿 GET/PUT /api/projects/{projectId}/draft，响应 prompt/revision；PUT 为 prompt/expectedRevision，≤4000 字符，非负安全整数 revision，409 DRAFT_CONFLICT。自动保存节流、同页顺序写、失效响应不回填、冲突提供显式重新读取；未保存时警示离开。数据库只留当前草稿，可保存空串清除，不发送给模型。首页未建项目草稿仍内存。
## Tech Stack / Commands / Project Structure / Code Style / Testing Strategy
遵循 [本轮实施约定](docs/design/iteration-experience/plan.md) 的技术栈、命令、目录、代码示例与验证标准。模块范围及依赖见 [能力图](ITERATION-CAPABILITY-MAP.md)。
## Boundaries
只修改本模块及必要调用方，不改变会话安全、模型预算、导出协议或部署。新增接口遵循现有 JSON 错误格式与 CSRF。测试替身只证明协议，不证明真实模型稳定性。
## Open Questions
无阻断项；任何范围扩大另行询问。用户已批准连续设计、实现、测试。
