# ADR-016 · 完整源码版本与 Claude Code 执行适配器

## 状态与范围

2026-10-18：用户已确认完整工程改造方向、选择评估 Claude Code + DeepSeek，并批准 [project-source 规格](../../SPEC-project-source.md)及[实现计划](../../tasks/project-source/plan.md)，授权连续开发；本记录固化方向与边界。源码模块开发中，尚未完成验收、模型联调、推送或部署。

本记录增加新模式，不撤销 [ADR-013](013-native-process-engineering-preview.md) 对现有固定模板运行的限制。后续开放模型生成 Java 的执行前，必须单独通过新执行模块评审。

2026-10-19 实施补充：源码模块和 Agent PA01–PA33 已通过内部离线验收。Agent 已实现完整模板初始化、两轮文件编辑、版本保存、默认 4 并发、取消/恢复及查询/正文保留；最终后端 290 单元 + 272 集成通过，完整结果见[验证记录](../project-agent-verification.md)。本机 CLI 2.1.302 / 2.1.303 的 `safe-mode + restricted` 经离线协议验证提供五种文件工具，采用此组合而非早期候选 `bare`。不修改个人配置、不开 Shell/MCP；真实 DeepSeek 与新工程运行/交付/工作台仍未验收。操作边界见[维护说明](../project-agent.md)。保留下文最初决策时的观察，不将旧“未运行 CLI”描述当作当前状态。

## 背景

现有模型产物是三个前端文本文件，保存在 PostgreSQL `versions.source_json` 的 TEXT 字段中。完整下载工程由固定模板加产物组装；这与可由 Agent 修改 Vue、Java、SQL 及配置的完整源工程不同。

用户要求增量需求真正修改完整源码，平台预览和下载相符，并继续在平台所在机器启动进程，不另建部署服务器。

## 决定

1. 每项目维护平台所有的本地 Git 仓库。PostgreSQL 负责身份、权限、源码版本引用、修订号及操作意图；完整源码存放 Git 对象中，不将大型工程整体写回旧字段。
2. 模板成为工程初始化来源，不再是所有后端业务永远固定不变的约束。旧项目使用显式导入，保留旧产物和运行链路。
3. Agent 操作可丢弃的文件副本；平台验证并接受不可变版本，构建器消费该版本，交付消费相应快照。Agent 会话、工作目录和自然语言完成声明都不是源码事实来源。
4. 以可替换的执行适配器接入 Claude Code 非交互子进程，模型提供方优先使用已有 DeepSeek 配置。平台仍负责所有权、任务、取消、限额、版本、测试和交付，不将这些交给一次 CLI 调用隐式处理。
5. 不切换个人 Claude Code 默认设置。计划使用独立 `CLAUDE_CONFIG_DIR`、明确的子进程配置与环境白名单，清除不相关的认证变量；不改全局 shell 配置，不重设 `HOME`，不复用个人 OAuth 登录或个人会话。
6. 优先验证官方推荐的无自动加载模式（例如 `--bare`），避免非交互执行自动读取项目 hooks、MCP、插件及个人配置。实际选项以已安装 CLI 的版本探测为准；若缺少必要能力，明确报告，不偷偷退回更宽权限模式。
7. 模型仅获得完成任务所需源码和经脱敏的反馈。DeepSeek 调用凭据通过受控进程配置提供；平台数据库、邮箱、Cookie 等秘密不进入工作目录或模型提示。环境清理不是操作系统隔离，Agent 依然是具备本机权限的程序。
8. 原生进程路线保持，但首期新后端能力仅用于受信任维护者受控 Demo。面向公开用户的任意后端执行需要另行隔离设计；不同进程和端口不能证明安全。
9. 对第三方模型组合及对外托管的适用许可仍有未确认之处，记录为正式开放前核查项，不将其断言为已获授权或必然需要单独付费。Demo 不采用修改认证、隐藏使用或规避限制的方案。

## 依据与验证状态

- Git 的 [commit-tree](https://git-scm.com/docs/git-commit-tree) 支持根据树和父提交创建提交，[update-ref](https://git-scm.com/docs/git-update-ref) 提供引用更新及比较检查。这些能力不提供与 PostgreSQL 共同提交的事务；跨存储恢复需要平台操作日志和核对流程。
- Claude Code 官方提供 [非交互运行接口](https://code.claude.com/docs/en/headless)，可输出结构化结果；[设置文档](https://code.claude.com/docs/en/settings) 和 [环境变量文档](https://code.claude.com/docs/en/env-vars) 描述配置目录、端点及认证控制。支持这些选项不等于已安装版本已验收。
- DeepSeek 提供 [Claude Code 接入说明](https://api-docs.deepseek.com/quick_start/agent_integrations/claude_code/) 和 [Anthropic API 兼容接口说明](https://api-docs.deepseek.com/guides/anthropic_api/)。首轮需验证工具调用、流式事件和失败结果；不能从端点兼容推断所有 Claude 功能等价。
- 对外集成条款核查入口：[Claude Code 法律与合规](https://code.claude.com/docs/en/legal-and-compliance)、[Anthropic 商业条款](https://www.anthropic.com/legal/commercial-terms)。本文不是法律结论。
- 本机只读检查发现 Git 2.39.5 和 `/Users/wubingheng/.nvm/versions/node/v20.15.0/bin/claude`。未运行 Claude Code、未读取个人认证、未安装或升级工具、未调用模型。该 CLI 的 Node 路径与项目 Node 24 工具链不同，联调前需明确选择，不自动改全局环境。

## 替代方案与取舍

- 继续把全工程放在数据库单字段：实现简单，但文件级读取、差异和完整工程工具交互不自然；不作为新模式事实来源。不是因为 PostgreSQL 技术上不能保存较大文本。
- 只保存可变目录：方便 Agent 修改，但崩溃、并发和恢复难以保持版本可追溯，因此仅作为工作副本。
- 直接调用大模型自行实现读写文件和工具循环：可行且更可控，但需要自行建设上下文、工具调用、重试和任务恢复；本轮按用户选择优先 Claude Code 适配器。
- 将正式 Git 仓库直接交给 Agent：更省复制，但会扩大历史修改和污染风险，故采用普通任务副本；这项设计仍不替代系统权限隔离。

## 后果

需要备份源码仓库和数据库两部分；PG 备份不再足以恢复新工程源码。需要处理 Git 与数据库间的中断窗口、磁盘容量及对象保留，而不能宣称具有跨存储原子提交。

预览和下载绑定同一工程快照只能保证来源一致；不同数据库配置和已有数据仍可能造成不同表现。代码恢复不回滚业务数据库，完整功能验收必须覆盖真实后端业务、第二轮增量修改和独立运行。

模块顺序及状态以 [完整工程能力地图](../../FULL-PROJECT-CAPABILITY-MAP.md) 为准。实现完成并验证后才更新面向使用者的功能完成说明。
