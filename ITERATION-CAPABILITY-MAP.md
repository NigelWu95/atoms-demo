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
