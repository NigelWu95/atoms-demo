# 连续开发与工作台优化
用户于 2026-10-16 确认模块边界及 `docs/design/iteration-experience/` 目录，授权连续完成设计、实现与测试，不再逐阶段等待确认。未授权推送、部署或扩大范围。
| 模块 | 职责 | 依赖 |
|---|---|---|
| iteration-context | 有界、按当前版本谱系选择有效历史需求 | — |
| artifact-patches | 严格局部修改，合并后仍保存完整产物 | iteration-context |
| workbench-review | 真实阶段、修改摘要、版本对比、关联需求 | artifact-patches |
| draft-persistence | 服务端项目草稿、并发保护、身份隔离 | — |
| project-library | 搜索、最近编辑排序、归档与恢复 | — |
顺序：iteration-context → artifact-patches → workbench-review → draft-persistence → project-library。
规格入口：根目录对应 `SPEC-<模块>.md`；任务入口：[实施计划](docs/design/iteration-experience/plan.md)。

2026-10-16：五模块本地实现和回归完成，详见 [验收记录](docs/design/iteration-experience/verification.md)。未推送、未部署、未重启日常服务；真实模型连续修改质量另行实测。

2026-10-17 用户确认继续下一轮：扩展 `workbench-review`（预览优先布局、逐行差异），增加 `preview-repair`（确认错误摘要后提交现有修改任务，依赖 workbench-review 和 artifact-patches）。顺序：差异 → 布局 → 修复 → 验收。沿用现有目录和连续实施授权，不增加部署、任意后端生成或基础设施范围。
