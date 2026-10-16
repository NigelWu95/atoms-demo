# Spec: artifact-patches
## Objective / Success Criteria
支持 artifact-patch-v1，含 baseHash、edits（file/before/after），可选 manifest 的 title/summary。1–24 条替换，每条 before 非空且恰好一次匹配；只允许 view.html/styles.css/main.js。单次输出 ≤512 KiB，合并后复用产物校验。错误基线/歧义/非法路径全部拒绝；不修改原 JsonNode。兼容完整产物，CREATE 不接受补丁。
## Tech Stack / Commands / Project Structure / Code Style / Testing Strategy
遵循 [本轮实施约定](docs/design/iteration-experience/plan.md) 的技术栈、命令、目录、代码示例与验证标准。模块范围及依赖见 [能力图](ITERATION-CAPABILITY-MAP.md)。
## Boundaries
只修改本模块及必要调用方，不改变会话安全、模型预算、导出协议或部署。新增接口遵循现有 JSON 错误格式与 CSRF。测试替身只证明协议，不证明真实模型稳定性。
## Open Questions
无阻断项；任何范围扩大另行询问。用户已批准连续设计、实现、测试。
