# 完整工程工作台

开启 `PROJECT_WORKBENCH_DEFAULT_MODE=true` 后，普通项目入口 `/projects/:id` 直接进入 Claude Code 完整工程工作台，显式 `/projects/:id/engineering` 也保留。开启 `PROJECT_WORKBENCH_OWNER_ACCESS=true` 后，注册账号和临时访客均可开发自己的项目；跨用户权限检查继续生效。旧版本、对话、预览及导出可从 `/projects/:id/legacy` 查看，旧生成接口拒绝新任务，不会静默回退。模块、真实模型和执行环境仍需按配置启用。

## 使用流程

1. 首次直接提交需求，会先创建 Vue + Spring Boot 工程，再提交 Claude Code 任务；也可单独初始化检查源码。旧项目会将原 HTML/CSS/JS 放入 `legacy/` 作为迁移参考，并记录原版本 ID。Agent 根据旧实现继续开发；初始化本身不调用模型、不宣称业务已转换。原运行数据仍保留在旧应用，不复制到源码或模型上下文。
2. 在左侧输入需求。Agent 任务基于当前完整源码及 revision 提交，阶段、取消和最终源码结果独立展示。刷新后从服务端读取最近 50 次需求，未提交草稿沿用项目自动保存。
3. 在“源码”中查看 Git 快照的文件树和正文；“差异”查看所选版本与其父版本的修改。前端、Java、SQL 和配置样例均属于同一个完整工程。源码保存在平台管理的磁盘 Git 仓库，PostgreSQL 保存任务、版本引用及权限。
4. 展开顶部“构建与运行”。根据需要显式准备依赖，再对固定源码版本构建。准备依赖走既有审核策略，不接收自由 Shell 命令；保存源码成功不等于构建成功。
5. 首次构建会从 `PROJECT_EXECUTION_DATABASE_POOL` 核验并分配一组预先准备的测试库和运行库，之后固定复用。固定映射与池合计最多 4 组，容量不足时明确提示；平台不持有建库管理员密码。对成功构建核对准确 SQL，需要迁移时显式批准当前构建、目标与数据库历史；配置或历史变化会使批准失效。
6. 点击“启动应用”，等待真实进程状态为 READY，再打开预览。替换运行会先停止原进程；如果返回仍在停止，页面保留原请求供明确重试，不把它显示为新应用已启动。
7. 两个下载按钮直接展示：“下载当前预览源码”固定到正在看的运行，“下载选中构建源码”固定到选中的成功构建。下载取原 Git 快照，不按最新模板重组；包含 Vue、Java、SQL、脚本、测试和配置样例，不含 JAR、依赖目录、业务数据或私有配置。

源码选择、刷新状态及预览/源码标签切换不会重载已经打开的应用。源码版本和运行版本分别显示，避免把新源码误当成已运行版本。恢复历史源码会追加新版本，不自动切换进程或回滚数据库。

## 构建失败后继续开发

在“构建与运行”选中当前源码的失败构建，点击“准备修复需求”。页面读取固定失败类别与执行环节，展示实际将发送的修复需求；可补充要求，点击“确认提交修复”后才进入 Agent 队列。原需求输入保留，取消建议不会调用模型，运行中的旧应用继续可用。

建议绑定账号、项目、源码 revision 与构建。切换这些信息会清空建议；历史源码的失败记录不能直接修复新版本。只传平台固定摘要，不复制原始日志、堆栈或任意诊断文本；环境、依赖审批、超时和待核对故障需要先处理对应原因。源码修复完成后仍需重新构建和确认功能，不自动启动、替换或批准迁移。

## 状态和布局

左右区域分别内部滚动，需求输入固定在底部，顶部操作收纳在可滚动面板中；手机切换“需求与任务”和“工程与预览”。旧下载入口不折叠。

提交结果未知时不会自动重发。当前页面保留原请求及幂等键，可点击“重试原请求”；放弃前提示先核对任务记录。整页刷新只恢复服务端事实，原请求键不持久化到浏览器；此时应先检查任务状态。切项目、账号或退出清理旧内容并中止读取，迟到响应不能写入新页面。

版本、构建、依赖准备和运行支持加载更早记录。页面显示最近 50 次 Agent 提交、最近 10 次初始化/源码恢复，以及所选任务最近 1000 条已加载事件。单文件展示上限 256 KiB，差异上限 128 KiB，并显示截断提示；源码原文件和 ZIP 不因预览截断改变。

## 配置

在已有源码、Agent 和执行模块配置基础上增加：

```properties
PROJECT_WORKBENCH_ENABLED=true
PROJECT_WORKBENCH_DEFAULT_MODE=true
PROJECT_WORKBENCH_OWNER_ACCESS=true
PROJECT_DELIVERY_ENABLED=true
PROJECT_DELIVERY_ORIGIN=http://127.0.0.1:5173
```

origin 必须是实际前端的协议、主机和端口，不加末尾斜线。此示例不代替执行器工具、依赖策略、独立测试库和业务库绑定。`OWNER_ACCESS` 开启后普通项目操作不再要求维护者名单，维护性核对仍要求维护者。真实模型仍要求 Agent 的 live 开关及独立预算；旧生成接口的调用额度不会自动变成 Agent 额度。仅开启工作台不会创建存储、启动应用、调用模型或修改个人 Claude Code 配置。

具体准备见[源码模块](https://github.com/NigelWu95/atoms-demo/blob/main/docs/project-source.md)、[Agent 模块](https://github.com/NigelWu95/atoms-demo/blob/main/docs/project-agent.md)、[执行模块](https://github.com/NigelWu95/atoms-demo/blob/main/docs/project-execution.md)和[交付模块](https://github.com/NigelWu95/atoms-demo/blob/main/docs/project-delivery.md)。

## 交付边界

本地日常环境已按用户要求向项目所有者开放，包括访客，使用本机应用进程，不增加实例服务或服务器。页面复用原协调器的进程和任务所有权，不根据 PID 接管应用。文件树只读，开发修改通过 Agent；首版工程技术栈仍限定 Vue + Spring Boot + PostgreSQL。该开关不提供 OS 沙箱，不能据此认定已适合公网不可信代码托管。

iframe 使用隔离来源，预览授权十分钟，仅存在页面内存；停止、退出或失效后清理，过期可重新打开。相对资源、模块脚本及有界 JSON 业务接口按已有交付协议工作，WebSocket、Cookie 登录等不在该预览协议范围内。

2026-10-29 的默认模式切换已更新本地私有环境配置。个人 Claude 配置、全局 Java 默认及云端部署保持原状；本地平台进程采用项目已有的 Java 17.0.14。旧阶段验收见[工作台验收记录](https://github.com/NigelWu95/atoms-demo/blob/main/docs/project-workbench-verification.md)，当前默认模式及访客真实调用见[本轮记录](https://github.com/NigelWu95/atoms-demo/blob/main/docs/default-engineering-verification.md)。
