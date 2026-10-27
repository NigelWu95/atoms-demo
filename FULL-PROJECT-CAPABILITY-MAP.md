# 完整源码工程与 Coding Agent · 能力地图

状态：2026-10-27，源码模块、Agent PA01–PA33 离线能力、执行模块 EX01–30 / C-EX10 及交付模块 PD01–PD14 / C1–C5 已完成内部验收。真实 Vue/Java/SQL 应用已验证构建、独立 PG、版本更新、迁移核对、路径预览、固定源码 ZIP 独立运行和重启持久化；见[执行记录](docs/project-execution-verification.md)及[交付记录](docs/project-delivery-verification.md)。正式工作台 PW01–PW09 已实现，本地 A/B 页面启动、预览和下载闭环通过；见[工作台说明](https://github.com/NigelWu95/atoms-demo/blob/main/docs/project-workbench.md)与[本轮验证](https://github.com/NigelWu95/atoms-demo/blob/main/docs/project-workbench-verification.md)。新模式默认关闭，真实付费模型闭环和部署尚未完成；旧未决审计保留。

## 目标与现状

执行模块现已接通源码就绪检查、私有测试库装配和 Spring 生命周期：队列取消/到期/权限变化原子结算，默认 1、最多 4 个 worker，已领取/待核对任务保留容量等待实际停止。修复了扫描途中容量释放导致后项超车的问题。EX12 阶段的原始种子构建包含 15 项前端、2 项 Java 测试和实际 JAR；前端失败、Java 编译失败的后续版本均保留旧产物，关闭后资源可重新取得，详见[证据](docs/project-execution-verification.md#2026-10-24--ex12c2b2b-spring-调度与原生组合验收)。EX29 补验当前种子已包含 15 项前端和 4 项 Java 测试，两个损坏后续版本仍被拒绝。默认网页仍使用原有链路；维护者可显式启用完整工程工作台，页面已接入原协调器的构建与本机应用启动。

平台重启先核对中断构建：未启动或已有明确停止记录的非封存阶段可以结束并释放名额；结果丢失和封存/提交间隙保留待核对，不自动重跑。成功终态和旧产物不变，恢复未决时不启动新构建。该行为通过独立 JVM 异常退出、另一个全新 JVM 恢复验证，见[恢复证据](docs/project-execution-verification.md#2026-10-24--ex13c-真实-jvm-崩溃与重新启动)。

让用户描述需求后得到可持续修改的完整 Vue + Spring Boot 17 + PostgreSQL 工程，包含实际前端、后端业务接口和数据库迁移。预览运行该工程的构建结果，下载提供对应的同一份源码快照。

默认旧工作台生成链路仍是模型生成 `view.html`、`styles.css`、`main.js`，数据库保存不可变产物，再与固定工程模板组装。新源码模块已能在磁盘 Git 管理完整 Vue/Java/SQL 树及增量版本，内部 Claude Code 适配器已通过真实 CLI + 假模型服务的两轮编辑与恢复验收，执行 worker 已能实际构建完整种子工程。正式页面已经接入需求提交、源码检查与运行交付；真实 DeepSeek 的新模式生成效果仍未验收，不宣称任意后端需求均可成功。新工程常驻运行已完成内部实际业务验收。

原有固定模板能力与验收记录保留在 [原能力地图](CAPABILITY-MAP.md)。本地图描述新模式，不追溯修改旧模式的功能及安全结论。

| 稳定模块 ID | 职责 | 依赖 | 规格状态 |
|---|---|---|---|
| `project-source` | 每项目完整源码 Git 仓库、不可变版本、工作副本、元数据、旧产物显式导入 | 现有项目身份与所有权 | 内部模块已验收、默认关闭；[使用说明](docs/project-source.md)、[测试证据](docs/project-source-verification.md)、[任务清单](tasks/project-source/todo.md) |
| `project-agent` | Claude Code + DeepSeek 在任务副本中读取及修改 Vue、Java、SQL 等文件，提交可检查的变更 | `project-source` | PA01–PA33 内部离线验收完成，默认 4 并发；最终后端 290 单元 + 272 集成通过，CLI 2.1.302 / 2.1.303 精确适配。见[维护说明](docs/project-agent.md)、[证据](docs/project-agent-verification.md)、[任务清单](tasks/project-agent/todo.md)。真实模型 PA-LIVE 未完成，旧两份测试未决审计保留；此处只记录 Agent 自身结果，构建运行及页面结果见下方模块 |
| `project-execution` | 对固定源码版本测试、构建、启动本机进程；构建失败保留上一可用结果，数据库迁移另行核对 | `project-source` | EX01–30 与 C-EX10 已完成，实际业务应用及两轮前后端版本、迁移/失败核对、重启持久化、交付身份、有界查询和原链路回归均已内部验收。[维护说明](docs/project-execution.md)、[任务](tasks/project-execution/todo.md)、[证据](docs/project-execution-verification.md)。预览/下载已由交付模块验证，正式工作台已调用这些能力 |
| `project-delivery` | 实际应用预览、同快照下载、代码版本恢复；明确业务数据边界 | `project-source`、`project-execution` | PD01–PD14 / C1–C5 完成内部验收。真实 Vue 页面、HTTP ZIP 标准解压与逐文件比对、自身脚本独立构建/启动、CRUD/409 和重启持久化通过；源码恢复追加版本，原预览 ZIP 不变。默认关闭，正式页面已由工作台模块接入。[使用说明](docs/project-delivery.md)、[证据](docs/project-delivery-verification.md)、[任务](tasks/project-delivery/todo.md) |
| `project-workbench` | 文件树、差异、任务与构建状态、实际进程预览及增量开发入口 | 以上模块 | PW01–PW09 本地实现，受控完整交互与真实 A/B 页面预览/下载通过；默认关闭。[使用说明](https://github.com/NigelWu95/atoms-demo/blob/main/docs/project-workbench.md)、[证据](https://github.com/NigelWu95/atoms-demo/blob/main/docs/project-workbench-verification.md)、[任务](https://github.com/NigelWu95/atoms-demo/blob/main/tasks/project-workbench/todo.md) |

建设顺序：`project-source` → `project-agent` → `project-execution` → `project-delivery` → `project-workbench`。

## 共同边界

- 平台仍使用 Spring Boot + Vue；生成工程首版也限定此技术栈。模板用于初始化，不再永久限定后端业务代码不可修改。
- 源码存入平台管理的本地 Git 仓库；PostgreSQL 保存版本引用、任务、权限及业务元数据，不再将新模式完整工程塞入单个字段。
- 正式源码仓库、Agent 工作副本、构建目录、运行配置和业务数据相互分开。每个目录的职责不同，不将它们误称为安全沙箱。
- Claude Code 通过子进程适配器接入 DeepSeek；使用独立配置目录和显式子进程环境，不修改个人 Claude Code 登录、默认模型或全局配置。适配器不绑定平台业务模型，便于后续替换执行引擎。
- 旧项目、旧版本、共享预览、原下载按钮和发布链路保留。导入由所有者显式触发；导入旧代码不等于自动重写成原生 Vue/Java 业务。
- 源码保存成功、构建成功、进程健康、功能验收通过是不同状态。Agent 自称完成不作为验收依据。
- 新模式采用本机进程，不要求新增服务器；首期仅供受信任维护者受控验证，不向公开访客自动开放模型生成后端的执行。独立目录、配置和端口不能隔离同一系统用户的文件及网络权限。
- 预览和下载匹配源码及构建身份，不承诺两处业务数据相同。代码恢复不自动回滚数据库迁移或运行数据。
- 密钥、邮箱授权码、数据库密码、平台 Cookie、个人 Agent 会话不进入源码、模型上下文或 ZIP。模型提供方必要的调用凭据仅交给受控执行进程，不写入工作副本。
- 当前不自动调用付费模型、修改个人配置、推送 GitHub、部署或变更服务器数据库。真实模型联调和新后端执行另行确认具体范围。
- Claude Code 对第三方模型及对外托管场景的适用条款尚未完全确认，列为正式对外开放前核查项；不以修改认证、隐藏使用或规避限制作为实现方案。

## 最终验收场景

1. 生成具有真实 Java 业务 API、Vue 页面和 SQL 表结构的小型应用，完成新增、修改、查询及重启后持久化检查。
2. 在同一工程追加第二个需求，查看实际文件差异，验证原功能仍可用。
3. 构建失败、取消和并发修改不覆盖上一可用版本；代码恢复不删除业务数据。
4. 从实际预览对应的快照下载工程，在另一目录配置独立数据库后构建启动，验证相同业务行为。
5. 旧项目仍可生成、访问、下载和恢复；新模式失败不使旧链路不可用。

设计取舍见 [ADR-016](docs/decisions/016-full-project-source-and-coding-agent.md)。各模块分别评审规格、计划和实现，不将这份地图当作已通过的执行清单。
