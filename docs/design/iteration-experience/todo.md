# 实施任务
每项完成前检查测试与构建；本轮用户已授权连续推进，不以中间人工检查点阻断。
- [x] IC01：当前版本谱系历史上下文；恢复分支、跨用户隔离、有界上下文 IT 通过。
- [x] AP01：精确补丁合并；错基线/歧义/路径越界/不合规 HTML 拒绝，单测红绿；不将结构校验称为任意代码安全认证。
- [x] AP02：工作流与 prompts 接入；CREATE 兼容、MODIFY 补丁、最多一次修复。替身模型经真实 Worker 连续三轮修改验证，前几轮内容与业务状态保留。
- [x] 检查点 A：完整快照预览/原下载链路、失败不改指针，后端及 Chrome 回归通过。
- [x] WR01：版本审阅 API；所有权、实际基线、关联需求、恢复版本 HTTP IT 通过。
- [x] WR02：文件前后源码对比、文本安全、重试入口，旧导出直接展示；前端单测/类型/Chrome 通过。
- [x] WR03：显示真实阶段和结果，不宣称功能已验收；单测及浏览器通过。
- [x] DP01：V015 与草稿 API；CAS、并发竞争、精确重试、上限和 owner 隔离通过。
- [x] DP02：自动保存与恢复、过期回包隔离、冲突处理、保存反馈与离开提醒；包含审阅发现的三项边界回归。
- [x] 检查点 B：账户切换不串草稿；原邮箱登录与工程下载测试通过，真实邮件未重发。
- [x] PL01：检索归档 API；排序分页、过滤绑定游标、字面搜索、归档恢复与所有权通过。
- [x] PL02：项目列表、加载/空/错反馈、账号文案；多尺寸、浏览器键盘路径和无障碍检查通过。
- [x] 最终：后端 138 单元 + 103 集成、前端 221 单元与检查/构建通过；Chrome 基础 16 项、运行时覆盖 35 项（含失败修正后的局部复跑）。代码审阅与 mutation check 完成，文档更新；未推送、未部署、未重启日常后端。

详细证据和失败修正见 [verification.md](verification.md)。以上为第一轮记录；第二轮补充逐行差异和一个真实模型连续修改样本，见下文。离线/自动合并草稿及生产迁移仍未验收。

## 第二轮（2026-10-17）

- [x] WR04（M，6 个实现/测试文件）：有界逐行差异与 UI；增删/行号/上下文/全文回退/XSS 单测通过；前端全量 228 项及类型/风格/构建通过，Chrome iteration 2 项含四尺寸与 axe 通过，独立审阅与边界 mutation check 完成。未推送/部署。
- [x] WR05：预览优先布局；主运行入口在前，导出不折叠，高级操作和修改记录后置；单套控制器不重建。前端 230 单测通过，Chrome iteration/restoration/engineering 共 11 项通过，含四尺寸布局/axe 与真实 ZIP 下载。
- [x] PR01：固定诊断摘要与准备修复 UI；原始错误不进入需求、历史无修复入口、准备不调用模型；单测与实际浏览器准备/取消通过。
- [x] PR02：显式确认、身份/版本绑定、原任务幂等提交；草稿不覆盖、并发/身份切换/迟到回包/跨路由重挂安全；前端 251 单测及检查构建、Chrome 运行时 36 项通过，四尺寸/axe 和独立审阅、mutation check 完成。整页重载元数据恢复仍未覆盖。
- [x] 检查点：前端 251 单测及检查构建、后端 138 单元 + 103 集成、Chrome 运行时 36 项和基础/邮箱 16 项全部通过；独立代码审阅与文档完成。仅本地提交，未推送/部署或调用真实模型。
- [x] PR03：独立测试 schema 中真实 DeepSeek 首次生成 + 三轮修改，4 个版本均成功，8 / 12 次请求；实际任务、完成状态和版本谱系保留。最终下载 ZIP 源码一致，完整构建与 Java 独立启动、刷新/进程重启恢复、访客隔离、手机操作通过。结果及脚本故障单独记录，未重发成功的生成任务；不推送/部署、不改日常环境。
- [ ] 后续质量评测：真实错误辅助修复、多应用类型和筛选/搜索/单项修改的组合边界；本轮单个待办样本不代表通用生成成功率。需重新确认后续真实模型调用预算，不沿用本轮剩余额度自动调用。

## PR04：整页刷新请求恢复（2026-10-17）

规格保存范围、以下计划和任务均已获用户整体确认，连续实施。此处勾选只表示实际实现并验证，不把前轮测试计作本轮结果。目录选择沿用已确认的 recovery-spec.md；保留根目录独立工程未完任务。

- [x] R01：有界恢复缓存（S，2 文件，无前置依赖）。
  - 文件：新增 `frontend/src/runtime/generationRecovery.ts` 与 `.spec.ts`。
  - 验收：白名单编解码保留原请求；24 小时且读/重试不续期；10 项目/长度上限、损坏/过期/存储异常明确失败而非静默淘汰。
  - 验证：先失败后通过的 `npm run test:unit -- src/runtime/generationRecovery.spec.ts`；`npm run typecheck`、`npm run build`。
- [x] R02：身份绑定与失效清理（M，4 文件，依赖 R01）。
  - 文件：`frontend/src/stores/generation.ts`、`generation.spec.ts`、`session.ts`、`session.spec.ts`。
  - 验收：身份验证前不暴露正文；所有者/访客类型/账号代次匹配才恢复；退出、切换、失效和迟到响应不会带回旧内容，正常刷新不误删。
  - 验证：`npm run test:unit -- src/stores/generation.spec.ts src/stores/session.spec.ts`；`npm run typecheck`、`npm run build`。
- [x] 检查点 A：缓存与身份回归通过，外部尚未开放半成品恢复入口；没有依赖、API、schema 或认证协议变化。
- [x] R03：接通原请求与修复标记恢复（M，4 文件，依赖 R02）。
  - 文件：`frontend/src/stores/generation.ts`、`generation.spec.ts`、`frontend/src/composables/useGeneration.ts`、`useGeneration.spec.ts`。
  - 验收：POST 前记录原 key/body，重建状态后仍完全相同；只有项目归属已确认且用户点击才能提交；修复结果保留原草稿，成功/拒绝/未知结果按原语义清理或保留。
  - 验证：`npm run test:unit -- src/stores/generation.spec.ts src/composables/useGeneration.spec.ts`，覆盖过期边界、写失败降级、重复点击和账号/路由切换；`npm run typecheck`、`npm run build`。
- [x] R04：恢复与降级提示（M，最多 5 文件，依赖 R03）。
  - 实际文件：`frontend/src/components/ConversationPanel.vue`、新拆出 `GenerationRecovery.vue` 与 `.spec.ts`，以及 `frontend/src/composables/useGeneration.ts` 与 `.spec.ts`；恢复展示独立测试，原面板不继续膨胀。
  - 验收：可审阅原需求/基线/保存时间，明确本地暂存与可能计费；过期/损坏/存储失败状态可见；放弃本地记录需确认且不冒充取消，下载入口不变。
  - 验证：`npm run test:unit -- src/components/GenerationRecovery.spec.ts src/composables/useGeneration.spec.ts`；`npm run typecheck`、`npm run lint`、`npm run format:check`、`npm run build`。
- [x] 检查点 B：正常生成和修复均可完整走通；刷新零生成 POST、同请求手动重试、空/非空草稿保护、缓存失败文案均通过，不存在未接通的占位入口。
- [x] R05：真实刷新与账号浏览器验收（M，最多 5 文件，依赖 R04）。
  - 实际文件：`frontend/e2e-runtime/preview-repair.spec.ts`、新增 `generation-recovery.spec.ts`、固定测试配置 `frontend/playwright.runtime.config.ts`、`frontend/e2e-runtime/state.spec.ts` 的 iframe 可见性前置约束。复用已有邮箱测试与后端夹具，无 Java 改动。
  - 验收：真实 page.reload 后不自动 POST；已提交但响应丢失的重试返回同一 runId、任务数不增加；账号切换/复制标签页/存储异常不串数据，四尺寸、键盘、axe 和页面控制台通过。
  - 验证：前端 `npm run test:unit`、`npm run typecheck`、`npm run lint`、`npm run format:check`、`npm run build`、`npm run test:e2e:runtime`；邮箱用例在专用本地测试后端/邮件替身就绪后执行 `npm exec playwright -- test e2e/email-account.spec.ts`。如增补 Java 夹具，另在专用库执行 `./mvnw -B verify`。
  - 实际结果：276 单测、全部前端门禁、专项 6 项、数据保存组 5 项、最终运行时 41 项及基础/邮箱 16 项均通过；完整结果与两次全量 40/41 的失败调查见 verification.md。旧保存用例仅增加居中滚动及完整可见性前置条件，未改产品/SDK、原业务断言或超时；浏览器底层原因尚未确证。
- [x] R06：审阅与交付记录（M，最多 5 个文档文件，依赖 R05）。
  - 文件：本目录 `todo.md`、`verification.md`、`recovery-spec.md`，以及 `README.md`、`docs/project-summary.md`。
  - 验收：独立审阅阻断项清零，身份或期限条件的变异能被测试捕获并恢复；记录真实结果/失败和降级边界；只做本地提交，不推送或部署。
  - 验证：`git diff --check`、范围与敏感内容检查、临时测试进程清理；文档明确是否完成新浏览器回归，不继承旧测试数字。
  - 实际结果：独立审阅无剩余 Critical/Required，期限变异使 3 项测试失败且还原后全量通过；本轮改动不含私有配置或测试认证 trace。测试端口 8081/5174 已退出，日常服务 8080/5173 仍在运行；保留无关 OAuth 草稿，仅本地提交。

- [x] R05-FIX（S，4 文件，专项浏览器发现的竞态）：相同 project 快照刷新不再清除修复建议/重建预览；真正版本、修订号、身份变化仍失效。`ArtifactPanel.vue/.spec.ts`、`WorkbenchPage.vue/.spec.ts` 两项新增回归先红后绿，聚焦 8 项、全量 276 项及随后 Chrome 专项 6 项通过。此项独立提交，不扩大为其他 watcher 的重构。
