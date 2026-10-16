# Spec: project-library
## Objective / Success Criteria
GET /api/projects 增加 q（≤80 字符字面子串）、sort=created|updated、archived=true|false；默认不显示归档。POST /api/projects/{id}/archive 与 DELETE 同路径恢复，幂等。只加 archived 字段、不修改代码 revision；界面支持检索排序、归档恢复并说明发布链接不受影响。查询参数变更必须清空旧分页。
## Tech Stack / Commands / Project Structure / Code Style / Testing Strategy
遵循 [本轮实施约定](docs/design/iteration-experience/plan.md) 的技术栈、命令、目录、代码示例与验证标准。模块范围及依赖见 [能力图](ITERATION-CAPABILITY-MAP.md)。
## Boundaries
只修改本模块及必要调用方，不改变会话安全、模型预算、导出协议或部署。新增接口遵循现有 JSON 错误格式与 CSRF。测试替身只证明协议，不证明真实模型稳定性。
## Open Questions
无阻断项；任何范围扩大另行询问。用户已批准连续设计、实现、测试。
