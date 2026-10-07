# 01 · 挑战分析与 Atoms 产品研究

## 1. 这道题需要交付什么

依据用户提供的挑战说明，硬性要求是：可运行网页应用、Agent 驱动的代码/应用生成、可视化展示、真实交互、数据持久化和可测试在线链接。要求“类似 Atoms 的能力与 UI 交互体验”，并未要求完整复刻商业平台。

初始化、注册和至少一个延展能力属于在时间资源允许时尽量覆盖的项。建议把“进入产品 → 开始生成 → 可体验结果”做成 P0，注册作为 P1；以版本恢复满足延展方向。48 小时是提交窗口，6–8 小时是建议专注开发时长，两者不能混用为工作量预算。

以下是对评审关注点的推断，不是官方公布的评分权重：

| 可能关注点 | 可以提交的证据 | 容易失分的情况 |
|---|---|---|
| 产品理解 | 对话、执行状态、代码产物和可操作预览衔接 | 只有聊天框和预置截图 |
| 真实 AI 能力 | 任意合理新提示能调用模型生成新代码 | 模板切换伪装成生成 |
| 工程完整性 | 项目恢复、版本、失败状态、数据保存 | 刷新后丢失、失败永远转圈 |
| 可用体验 | 新访问者可独立完成主流程 | 需要开发者手工改库或运行命令 |
| 范围判断 | 有明确取舍和扩展接口 | 在登录、计费、基础设施上耗尽时间 |
| AI 协作方法 | 真实记录工具使用、验证与修正 | 只强调模型品牌，缺少交付证据 |

## 2. 研究方法与证据等级

- **页面访问**：打开产品官网、帮助中心、登录页及下表中的文档页。
- **文档核对**：阅读与构建、预览、数据、版本及发布有关的操作说明；长文重点核对相关功能章节。
- **截图观察**：下载并查看官方公开的首页、聊天交接、App Viewer 截图。
- **未完成的验证**：注册登录、提交真实任务、实际生成质量、消费额度、恢复数据库、连接 GitHub、发布线上应用。

本研究不能证明登录后每个账号都具备文档列出的功能；一些能力与项目类型、权限、套餐有关。帮助中心也会更新，以下为 2026-10-07 访问时记录。

## 3. 原站功能清单与 Demo 映射

下面的“原站行为”均是简要归纳；“Demo 处理”是本方案自己的设计。

| 功能 | 原站行为：文档所述 | Demo 处理 | 来源 |
|---|---|---|---|
| 注册与初始化 | Google 或邮箱路径，初始化工作区 | 先访客会话，邮箱账号可后加 | [Create an Account](https://help.atoms.dev/en/articles/12129544-create-an-account) |
| 首页与项目入口 | 中央输入框、侧栏项目、工作区、发现和模板入口 | 首页输入框、我的项目、示例提示 | [Dashboard Overview](https://help.atoms.dev/en/articles/12129546-dashboard-overview) |
| 基本构建闭环 | 描述、检查、修改、测试、发布、验证、更新 | 完整保留核心闭环 | [Quick Start](https://help.atoms.dev/en/articles/12129545-quick-start) |
| 输入控制 | 附件、团队模式、主题、研究及竞速等选项 | P0 只保留提示和发送，增强项明确后置 | [Input Chat](https://help.atoms.dev/en/articles/12129552-input-chat) |
| 项目聊天 | 用户请求、Agent 活动卡、工具动作、交接与版本卡 | 真实运行事件、折叠步骤、版本结果 | [Project Chat](https://help.atoms.dev/en/articles/12129550-project-chat) |
| Agent 协作 | 单工程师或团队模式；可出现问题卡、计划审批、角色交接 | 两个模型阶段加确定性校验，标注角色职责 | [Working with Agents](https://help.atoms.dev/en/articles/12129548-working-with-agents) |
| 模型与竞速 | 普通项目默认模型与 Race Mode 分开配置，Auto 为自动选择 | 一个验证过的模型配置，暂不做竞速 | [Choose an LLM](https://help.atoms.dev/en/articles/12129549-choose-an-llm) |
| App Viewer | 可点击运行，设备切换、刷新、页面选择、新窗口、日志入口 | iframe 交互预览、移动宽度、刷新、错误面板 | [App Viewer](https://help.atoms.dev/en/articles/12129554-app-viewer) |
| 设计调整 | Theme、Visual Editor、Library；主题与元素覆盖不同 | 首版用自然语言改样式，点选编辑后置 | [Design and preview](https://help.atoms.dev/en/articles/12129553-design-and-preview) |
| 版本历史 | 查看历史，Restore 恢复原项目，Remix 创建另一项目 | 快照保存；P1 恢复成新版本 | [Project History](https://help.atoms.dev/en/articles/12129557-project-history) |
| 源码文件 | 文件树、源码查看、编辑会话、Diff、下载 | P0 只读源码；导出与手工编辑后置 | [Code and files](https://help.atoms.dev/en/articles/12129559-code-and-files) |
| 云后端 | 数据库、用户、密钥、文件和应用内 AI 等模块 | 平台托管一个受限 JSON 状态接口 | [Atoms Cloud](https://help.atoms.dev/en/articles/12129561-atoms-cloud) |
| 数据管理 | 开发/生产环境、表和行、Schema、CSV | 预览/线上数据分区，不生成任意 SQL | [Database](https://help.atoms.dev/en/articles/12129562-database) |
| 生成应用的账号 | 应用用户与平台协作者不同，可生成认证流程 | 首版不生成独立认证服务 | [Authentication](https://help.atoms.dev/en/articles/12129563-authentication) |
| 外部服务 | 连接外部工具；可用动作取决于具体连接器 | 仅模型调用，第三方业务集成后置 | [Integrations](https://help.atoms.dev/en/articles/12129568-integrations) |
| GitHub | 连接账号并创建仓库，提供手动推送和拉取 | 暂不做 OAuth 与同步 | [Connect GitHub](https://help.atoms.dev/en/articles/12129569-connect-github) |
| 分享 | 控制项目链接和可见性，提供导出入口 | 首版只分享成品应用 | [Share a project](https://help.atoms.dev/en/articles/12129574-share-a-project) |
| 发布 | 创建公开应用地址；后续编辑需 Update 才上线上版本 | 显式发布固定版本，保持公开地址稳定 | [Publish your project](https://help.atoms.dev/en/articles/12129577-publish-your-project) |
| 错误恢复 | Issue Report 展示问题，Resolve 发起修复 | 捕获预览错误；P1 一键送回模型 | [Report an issue](https://help.atoms.dev/en/articles/12129595-report-an-issue) |
| 研究和增长 | 官网还描述研究、SEO、广告等业务能力 | 不纳入笔试首版范围 | [产品官网](https://atoms.dev/) |

## 4. UI 与交互观察

### 4.1 首页

官方首页截图显示：深色工作区、固定左导航、居中的大输入框、输入区上方的 Agent 头像和欢迎文案，下方有发现/项目/模板区域。这里最有价值的体验是“让用户立即开始描述需求”。Demo 建议保留布局节奏与清晰入口；不需要复制所有营销入口。

来源：[Dashboard Overview 中的首页截图](https://help.atoms.dev/en/articles/12129546-dashboard-overview)。截图主题不证明整个产品只能使用深色主题。

### 4.2 项目聊天

官方聊天截图显示：用户请求与 Agent 信息分层，角色名称、可展开步骤和交接信息相邻，输入框固定于底部。Demo 应让每个运行阶段对应实际执行结果，避免用计时动画制造虚假 Agent 活动。

来源：[Project Chat 中的交接截图](https://help.atoms.dev/en/articles/12129550-project-chat)。

### 4.3 预览与发布

App Viewer 截图展示顶部项目工具条和预览工具条，公开版本更新入口位于右上；预览区域承担主要视觉面积。Demo 据此采用左侧对话、右侧预览、顶栏发布的工作台布局，具体尺寸为本方案设计。

来源：[App Viewer 工具条截图](https://help.atoms.dev/en/articles/12129554-app-viewer)。

## 5. 值得保留的产品逻辑

1. 用户提交的是想达到的结果，系统负责转成可执行任务。
2. 过程可观察：当前阶段、产物和错误应能解释当前状态。
3. 每一轮修改有可定位版本，而非无限覆盖一个字符串。
4. 预览可操作，且反馈能进入下一轮修改。
5. 编辑与线上发布分离，版本更新由用户明确触发。
6. 平台账号、生成应用访客和业务数据是不同概念。

以上是基于前述资料的设计归纳，不是对 Atoms 内部技术实现的陈述。

## 6. 题目中容易低估的难点

| 难点 | 为什么会影响交付 | 建议 |
|---|---|---|
| 输出不确定 | JSON 截断、无效 JS、错用依赖都可能发生 | 限制生成契约，保存原错误，有限修复 |
| 生成代码的执行 | 页面样式和脚本会影响宿主；任意服务器执行风险更大 | 浏览器受限 iframe，固定产物格式 |
| 增量修改 | 重生成容易覆盖之前功能 | 基于明确版本与完整源码，保存新快照 |
| 流式任务恢复 | 浏览器断开不能导致丢失结果或重复扣费 | 后台任务独立运行，数据库事件回放 |
| 数据究竟存在哪里 | 聊天保存不等于生成应用的数据保存 | 两类数据分别设计、分别验收 |
| 分享与部署 | 编辑器能看见不等于评审能访问 | 无痕窗口测试平台链接和成品链接 |
| 时间管理 | 全栈代码沙箱、计费和团队权限都可单独成为项目 | 先完成受控生成闭环，再加版本恢复 |

## 7. 登录后建议补充验证的项目

有可用账号后，可用 20–30 分钟走一条最短路径：创建待办应用，增加一个功能，刷新验证数据，查看源码及版本，发布并从无痕窗口打开。记录首个结果耗时、Agent 消息结构、错误提示、预览与线上版本区别。该体验有助于 UI 细节对齐，但不应成为自建 Demo 核心开发的无限期前置条件。

仍未证实：原站模型的实际成功率、执行隔离技术、内部编排框架、真实基础设施成本及账号可用权限。不能从前端截图或营销文案推出这些结论。
