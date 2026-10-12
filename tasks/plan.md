# engineering-bundle · 实施计划

状态：2026-10-12 用户确认继续后，EB01–EB14 已实现并验证；当前交付 C-EB06 真实会话与沙箱运行检查点，下一步 EB15–EB17 独立访客数据读写与幂等保存。完成情况见任务清单。
依据：[已批准规格](../SPEC-engineering-bundle.md)、[能力地图](../CAPABILITY-MAP.md)。代码基线 `109bcb5`，工作分支 `feature/standalone-engineering`。

## 1. 本轮交付什么

交付一个可重复组装的固定源码工程：选定的 HTML/CSS/JS 应用 + Vue 外壳 + Spring Boot 17 数据服务 + 独立 PostgreSQL。把工程放入全新目录后，可以检查、构建、启动、保存数据，并在重启后恢复有效访客的数据。

原基础模块不交付下载按钮、任意工程生成、平台托管构建、容器实例管理或新发布网关。2026-10-12 用户明确追加并确认一次完成 `project-export`，下载入口按下列扩展实施；其余模块仍不在范围内。原有平台生成、预览、路径发布、历史恢复保持不变。

### project-export 扩展（2026-10-12，用户已确认一次完成）

依据 `SPEC-project-export.md`，顺序执行、不再逐阶段请求批准，每片先红后绿、本地原子提交：

1. PE01：标准库确定性 ZIP 和路径/配额测试；README 解压后执行位说明及模板预算。
2. PE02：所有权校验后的只读下载端点、两路打包并发限制、脱敏错误；真实 HTTP 当前/历史/越权测试。
3. PE03：共享会话错误处理的有界二进制传输、下载组件及工作台接入；生命周期与错误测试。
4. PE04：真实 Chrome 下载、键盘/窄屏/axe；实际 ZIP 解压到新目录，按 README 构建、独立启动和持久化验收。
5. PE05：原平台完整回归、关键判断变异、自审、更新原功能文档和验证记录。EB24/EB25 只按实际证据勾选；人工验收不冒充已完成。

不调用真实模型，不改部署/数据库架构，不新增依赖，不推送或部署。下载只取授权不可变版本和固定模板，不读取运行数据/密钥，不执行生成代码。主要实现文件每片不超过五个，沿用既有质量门槛与隔离测试环境。

本轮任务的唯一进度清单为 [todo.md](todo.md)，使用 `EB` 前缀。原 `docs/design/tasks/` 仍保留原项目的执行历史与未完成项；本计划不覆盖、搬迁或重新勾选旧清单。按用户要求，原 README、交付说明及部署文档等到实现完成后再更新。新模板自己的 README 和新增验收记录属于本轮交付。

## 2. 架构与关键落点

| 边界 | 实现位置 | 约束 |
|---|---|---|
| 纯组装器 | `backend/.../engineering/` | 输入已授权不可变快照；再次验证；只输出文件及 manifest，不读库、不启动进程 |
| 固定模板 | `templates/standalone-v1/` | 独立依赖锁、后端、前端、检查脚本及必要测试；不是复制整个平台 |
| 模板装载 | 平台构建资源 + 显式文件白名单 | 正式平台 JAR 可读模板，无需依赖当前工作目录；不扫描仓库打包 |
| 独立运行 | 导出工程自己的 Spring Boot JAR | 同源静态资源/API；生成代码仍在 opaque iframe，保留受限 SDK |
| 独立数据 | 专用数据库、受限角色、`standalone_app` schema | 会话、访客、状态、幂等结果持久化；不复用平台数据库与迁移 |
| 验收装配 | 仓库内测试脚本及独立 Playwright 配置 | 固定夹具、全新临时目录、专用测试库；不进入正式导出物 |

固定版本沿用规格，不借此升级平台依赖。直接依赖只选现有栈中实际需要的部分；不带 Pinia/Router 等未用组件。新增第三方依赖需单独说明用途并先确认。

### 2.1 不让复用破坏边界

- 组装器直接复用现有 `ArtifactValidator`；不重构原平台的校验/发布链路。
- 独立模板不能 import 平台包。运行文档编码、SDK 与桥接只移植最小兼容实现，提供共享行为向量/一致性测试；不同时维护两套不同协议，也不为一次迁移抽出泛化插件框架。
- 原平台同键异体返回 422；独立应用按新规格返回 409。差异仅存在于独立 API 的错误映射，不修改平台现有接口。
- 源码中的标题、摘要、HTML、CSS、JS 只进入固定数据文件，绝不插值到依赖、命令、Java 类名或路径。
- 明确白名单包含独立工程正常构建所需的单元/集成测试；“不自动导出测试目录”指不递归携带平台测试夹具、开发配置和浏览器验收环境，不是删除验证所需测试。

### 2.2 三种标识分工

`sourceContentHash` 保留导出来源；`bundleId` 标识原始工程文件集合；`loadedRuntimeId` 对实际应用文件与受控运行层构建内容取 hash。修改应用重新构建后 runtime ID 必须改变，不把原平台版本 UUID 当成新构建身份。

文件清单使用规范序列化、稳定排序和防御性复制，不混入时间戳、绝对目录、部署身份或密码。每次启动不重算随机实例身份。

### 2.3 配置与数据库护栏

运行参数保持 `node scripts/start.mjs --config /absolute/path/application.properties`；直接 Java 启动也必须经过同样的配置校验，不能只在 Node 包装脚本检查。

测试统一采用 `STANDALONE_TEST_CONFIG=/absolute/path/test.properties`，由模板测试引导读取。文件必须显式声明 `standalone.test-database=true`，且 JDBC 连接实际指向专用测试库、受限测试角色。正常启动拒绝测试配置；测试入口拒绝运行配置、平台库和安装身份不匹配。拒绝条件先于清理/迁移/写入。不能仅靠文件名或配置中的数据库名宣称隔离。

本地验收准备独立的集成测试库与浏览器运行库 A/B，凭据写入仓库已忽略的 `.local/standalone/`，不打印密码、不复用 `.env.local` 中的平台应用密码。初始化只创建缺失的专用对象，不重置已有角色、不删库；需要新增数据库权限时按环境权限流程请求批准。导出用户自行准备运行库和独立测试库，包不包含管理员密码或自动创建数据库的特权。

安装身份由外部稳定实例 ID 与数据库安装记录核对；同库并发首次启动要原子认领，失败实例不能先执行业务写入。迁移前验证连接目标、schema 与角色权限。首次建立身份与迁移的次序要在 EB06 的真实数据库测试中证明，不假设 Flyway 默认顺序已经满足。

### 2.4 有限打包上限：已批准细化

规格要求有限配额且据实校验，但真实新模板尚不存在，当前不能提供其“实测大小”。计划提议先锁定保守工程预算：**最多 128 个文件、单文件 2 MiB、总计 8 MiB**，覆盖 manifest 和允许导出的测试/锁文件；应用源码 120 KiB、初始状态 256 KiB 的原限制不变。

这些是已批准的初始硬限制，不是实测结果或性能保证。EB02 从第一版就测试并执行上限，EB20 形成真实模板后测量，EB24 记录余量并锁定回归。超限就停止并调整设计/提交评审，不能自动放宽。用户已确认“先有限预算、后实测确认”的时序，相应同步规格 §4。

## 3. 交付顺序与可见检查点

| 切片 | 任务 | 能验证的结果 |
|---|---|---|
| 确定性工程边界 | EB01–EB03 | 同一快照产生相同清单；路径和体积越界被拒绝；后端模板能编译 |
| 独立启动安全 | EB04–EB06 | 外部配置、专用库、安装身份与数据库健康检查真实可用 |
| 访客身份 | EB07–EB08 | 新应用自行建立会话，拒绝缺 CSRF 的写请求，不使用平台身份 |
| 安全运行文档 | EB09–EB10 | 实际应用资源有构建身份；运行 HTML 按现有隔离规则生成 |
| 最小可见外壳 | EB11–EB14 | Vue 同源调用真实会话/API；固定计数器通过沙箱 SDK 启动 |
| 数据闭环 | EB15–EB17 | 待办保存/刷新、冲突、同键重试及未知结果提示真实工作 |
| 脱离平台启动 | EB18–EB21 | 全新目录完整构建，JAR 可独立启动，显式白名单形成完整工程 |
| 独立性验收 | EB22–EB25 | 断开平台仍可保存，重启恢复、跨访客/跨实例隔离及旧平台回归通过 |

任务依赖在 todo 中逐项列出。每 2–3 项设检查点，记录真实命令、失败与结果并向用户汇报；未过关不叠加后续复杂功能。编译/组装基础完成不冒充用户主流程完成。

默认单执行流。配置/迁移、接口 DTO、SDK 状态机与模板白名单顺序推进。契约冻结后纯前端与纯组装测试理论上可并行，但本计划不自行启动多 Agent，也不让不同执行者同时改数据库迁移或打包清单。

## 4. 执行与验证命令约定

以下新增命令是待实现契约，不是本轮已经跑过的测试。命令从注明的目录执行；先显式准备 Java 17、Node 24.20.x 与 PostgreSQL。实际执行时记录耗时和用例数，不承诺固定测试总数。

本机工具路径（不改系统默认版本）：

```sh
export JAVA_HOME=/Users/wubingheng/.sdkman/candidates/java/17.0.5-oracle
export PATH="$JAVA_HOME/bin:/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH"
```

- **V-P**：仓库 `backend/` 执行 `./mvnw -B -Dtest=EngineeringBundleTest,TemplatePolicyTest test`。完整模板装载测试加入后执行 `./mvnw -B verify`，不跳过既有测试。
- **V-BU**：模板 `backend/` 执行 `./mvnw -B test`；任务也可用 `-Dtest=类名` 做红绿验证。不能把仅单元测试成功当成 `verify` 成功。
- **V-B**：模板 `backend/` 执行 `STANDALONE_TEST_CONFIG=/absolute/path/test.properties ./mvnw -B clean verify`。真实 PostgreSQL，无配置必须失败，不自动换 H2。
- **V-D**：EB09 增加仅在测试 classpath 的 `TestApplication`，提供可移植的小型计数器/待办夹具；模板 `backend/` 执行 `STANDALONE_TEST_CONFIG=/absolute/path/test.properties ./mvnw -B spring-boot:test-run`，前端执行 `npm --prefix frontend run dev`（模板根）。它只连接专用测试库，不进入正式 JAR，不提供平台测试接口；正常启动仍拒绝测试配置。早期浏览器切片用它联调，不能作为最终新目录/JAR 验收证据。
- **V-F**：模板根执行下列命令，任一失败即停止；EB11–EB12 尚在脚手架阶段时，只运行当时实际存在的检查，不用空测试补绿。

```sh
npm --prefix frontend ci --ignore-scripts
npm --prefix frontend run typecheck
npm --prefix frontend run lint
npm --prefix frontend run test:unit
npm --prefix frontend run build
```

- **V-S**：模板根执行 `node --test scripts/*.test.mjs`。脚本的假进程测试不能代替 V-X 的真实构建/启动。
- **V-X**：测试组装脚本在仓库根运行 `node scripts/verify-standalone.mjs --config-dir .local/standalone --output-parent .local/standalone`，只在该父目录中新建临时子目录，不覆盖现有工程。执行实际导出的 `build.mjs` 后，把运行路径与测试配置交给浏览器；记录产物 hash、JAR、独立数据库身份和命令退出码，不记录凭据。
- **V-E**：仓库 `frontend/` 执行 `npx playwright test --config playwright.standalone.config.ts --project=chromium`；要求 V-X 已生成的专用运行配置。配置设置 `reuseExistingServer=false`，只管理本次创建的 PID，禁止停用平台服务或复用未知已有服务；无夹具/数据库应失败。
- **V-R**：原平台 `backend/` 执行 `./mvnw -B verify`；原 `frontend/` 执行 `npm run typecheck`、`npm run lint`、`npm run format:check`、`npm run test:unit`、`npm run build`、`npm run test:e2e -- --project=chromium`、`npm run test:e2e:runtime`；仓库根执行 `node --test scripts/release.test.mjs` 和 `git diff --check`。沿用原隔离测试环境，不运行 live 模型验收。

完整导出工程构建需 `STANDALONE_TEST_CONFIG=/absolute/path/test.properties node scripts/build.mjs`。脚本只调用固定 argv、固定目录的审核命令，不使用 shell 拼接；前后端任一检查失败必须退出，Maven Surefire/Failsafe 保留 `failIfNoTests=true`。安装默认禁用生命周期脚本，不能因安装失败直接改成全放行。

## 5. 风险、停止条件与完成标准

| 风险 | 应对与最早验证 |
|---|---|
| 错误配置连上平台/运行库 | EB04–EB06 提前验证配置及实际连接身份；迁移/清理前失败，绝不以删库修复 |
| 模板与平台 SDK 漂移 | EB10/EB14 保留相同恶意输入与协议行为向量；修复复制边界，不关 sandbox |
| 同键并发或超时重复保存 | EB16 数据库唯一约束及事务提交；EB17/EB22 模拟已提交但响应丢失 |
| 新构建身份破坏旧请求重放 | EB09 锁定内容 hash；EB16/EB23 先重放已提交结果再判断新写入版本 |
| 工程能打包但不能自运行 | EB18–EB23 强制新目录、真实 JAR、进程重启、平台请求阻断及独立库断言 |
| 打包夹带配置/真实数据 | 白名单 + canary 排除测试；EB20 在正式平台 JAR 内检验资源，EB24 实测配额 |
| 进程清理误伤用户服务 | 只持有本次启动的 PID 与临时目录；不按端口杀进程，不自动删数据库/卷 |

统一完成标准沿用项目已有质量要求：新增行为先红后绿；错误/竞争路径有测试；不删除断言、不跳过测试、不吞异常伪装成功；前后端检查、真实数据库与浏览器证据齐全；安全边界不退化；每个切片可独立说明、审阅和本地提交。配置/运行日志脱敏，错误含可追踪 requestId。浏览器覆盖 320/390/768/1440px、键盘与 axe；不凭截图代替数据验证。

验收记录新建为 `docs/engineering-bundle-verification.md`。记录实际状态与未验证项，不把历史回归结果称为本轮结果。最终评审核对规格全部完成条件，之后才将本模块作为导出模块的稳定依赖。

本轮不执行 GitHub 推送、主分支合并、SSH 部署、付费模型调用、真实业务数据迁移或第三方账号操作。若发现需要改变已批准的数据模型、运行隔离或依赖范围，先报告依据和影响，再调整规格与计划。

## 6. 评审入口

用户已确认本计划与 [EB01–EB25 任务清单](todo.md)，包括 §2.4 的初始打包预算及实测时点。从 EB01 的失败测试开始，按检查点交付；这不是对后续四个模块或云端发布的提前批准。
