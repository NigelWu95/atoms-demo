# Spec: iteration-context
## Objective / Success Criteria
只把当前有效版本祖先的成功需求提供给模型。恢复版本后不继续携带废弃分支的要求；最多 64 层、8 条、12000 字符；所有查询限定项目与 owner。验收使用分支恢复与越权 IT，历史查询不增加付费模型调用。
## Tech Stack / Commands / Project Structure / Code Style / Testing Strategy
遵循 [本轮实施约定](docs/design/iteration-experience/plan.md) 的技术栈、命令、目录、代码示例与验证标准。模块范围及依赖见 [能力图](ITERATION-CAPABILITY-MAP.md)。
## Boundaries
只修改本模块及必要调用方，不改变会话安全、模型预算、导出协议或部署。新增接口遵循现有 JSON 错误格式与 CSRF。测试替身只证明协议，不证明真实模型稳定性。
## Open Questions
无阻断项；任何范围扩大另行询问。用户已批准连续设计、实现、测试。
