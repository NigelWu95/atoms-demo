# 云服务器部署记录

2026-10-08 首次部署，2026-10-14 更新当前程序；这是 Demo 验收环境，不是完整生产上线签收。用户明确不等待域名，使用 IP + 端口访问。下方按日期保留历史操作，不将历史参数视为当前配置。

## 访问和当前边界

- 工作台：<http://118.195.239.8:8443>；发布链接 origin 同此地址，应用沿用 `/a/<slug>`。用户明确要求直接 HTTP，已关闭此端口的 TLS，不自动跳 HTTPS。
- 这是显式的临时 Demo 例外，HTTP 流量未加密。独立 Cookie `ATOMS_HTTP_SESSION` 保留 HttpOnly / SameSite=Lax，但不含 Secure；旧 HTTPS 访客数据保留，不自动映射到新 HTTP 访客。
- 原 HTTPS 自签证书仅为回退保留，有效期至 **2026-11-07 02:15:58（Asia/Shanghai）**，没有自动续期。SHA-256 指纹：`C5:4F:98:43:36:47:87:F5:AA:3F:01:C6:6B:5C:CD:69:4F:9E:AA:85:09:BE:B2:C7:D9:7F:A1:FF:97:34:4D:34`。
- 使用全新数据库，没有上传本地项目、会话或整个 `.env.local`。已按用户授权单独同步 DeepSeek 密钥，并将云端累计模型请求上限设为 **100 次**，公网访客共用。启用时已预留 0、剩余 100；不是每日/每用户额度，也不是 100 次完整应用生成。配置验证没有发起生成或更改本地数据、模型记账。
- 当前模型限制为 **163840 tokens / 单次 300 秒 / 整轮 900 秒**；2026-10-14 部署验收时累计已预留 32、剩余 68，本轮没有调用服务器模型。未将本地 10000 次上限同步到公网。
- 全站采用既有访客模式，项目按所有者隔离。身份频率限制、总存储配额、长期数据清理仍待完成；不适合无人监管地长期开放。侧栏个别“本地”字样仍是既有展示文案，不代表请求发往开发机。

## 实际安装

服务器为 TencentOS Server 3.3 / x86_64，2 vCPU、约 3.5 GiB 内存。安装 Java 17.0.20.1、PostgreSQL 17.11、Nginx 1.30.5；没有安装 Docker、Node 或 Maven。Java 17 来自现有系统源，数据库和 Nginx 使用 [PostgreSQL 官方 RPM](https://www.postgresql.org/download/linux/redhat/) 与 [Nginx 官方源](https://nginx.org/en/linux_packages.html)，保留 RPM 签名校验。依赖预演无卸载和系统升级。

原有 `/usr/bin/java` 仍指向 Tencent Kona 8。应用通过 systemd drop-in 指定 `/usr/lib/jvm/jre-17-openjdk`，堆上限 768 MiB；不改变本地 17.0.5-oracle 或服务器默认 Java。

| 项目 | 位置 / 状态 |
|---|---|
| 发布版本 | `atoms-demo-vM9VRY`，源码 `f2bd4e807d45599d64c3d2528db5a661855af251`，打包时 dirty=false；2026-10-14 更新 |
| 程序 | `/opt/atoms-demo/releases/atoms-demo-vM9VRY`；`/opt/atoms-demo/current` 为其符号链接；旧 `atoms-demo-MImuq3` 及此前发布包保留 |
| 外部配置 | `/etc/atoms-demo/application.properties`，root:atoms / 0640，目录 0750 |
| TLS（未启用） | `/etc/atoms-demo/tls/server.crt`、`server.key` 为回退保留；私钥 root / 0600，目录 0700 |
| 后端服务 | `atoms-demo.service`，非 root 的 `atoms` 账号，单实例 |
| 主机特有配置 | `/etc/systemd/system/atoms-demo.service.d/runtime.conf`，Java 路径、PG17 启动顺序、内存和正常退出码；`http-demo.conf` 配置独立 HTTP Cookie |
| Nginx 站点 | `/etc/nginx/conf.d/atoms-demo.conf`，监听明文 `8443`；路由/安全头在 `/etc/nginx/atoms-demo/`，HTTP 不发 HSTS |
| 数据库 | `atoms_demo` / schema `atoms` / 受限角色 `atoms_app`；数据目录 `/var/lib/pgsql/17/data` |
| 数据库服务 | `postgresql-17.service`；本机 peer、回环 TCP SCRAM-SHA-256，数据页校验已开启 |
| 公网监听 | SSH 22、HTTP 8443；8080 和 5432 仅回环监听 |

三个服务均 active/enabled；没有修改 SSH、系统防火墙或云安全组。安装时新增的 Nginx 默认欢迎页配置保留为 `default.conf.atoms-disabled`，不会额外监听 80。程序目录中所有文件仍通过发布包 SHA256SUMS 校验；服务器特有配置在包外，不回写已发布归档。

第一次重启时，JVM 已优雅关闭，但 systemd 将退出码 143 记作失败。按 [Spring Boot systemd 示例](https://docs.spring.io/spring-boot/how-to/deployment/installing.html) 在 drop-in 加入 `SuccessExitStatus=143`；原配置保留为 `runtime.conf.before-exitfix`。再次实际启停验证 `ExecMainStatus=143`、`Result=success`，启动后 health=UP、NRestarts=0。仓库服务模板和回归测试已同步修正，历史发布包不改写。

## 首次 HTTPS 部署验收（历史）

- SSH 严格校验已有主机密钥；上传前后归档校验、解包后 13 个文件哈希一致。
- Flyway V000–V009 共 10 次迁移成功，重复启动不重新初始化。
- 从开发机通过公网直连 HTTPS health=UP；客户端使用经 SSH 取得的公开证书校验，不下载服务端私钥。
- 独立 Chrome 实际创建一个“云服务器部署验收：不调用模型”项目，刷新/深链接可恢复，另一个访客读取该私有项目被拒绝；正常主流程 console warning/error 和 pageerror 为空。
- 同一浏览器保持打开，实际重启后端后仍恢复原访客和项目。已检查桌面和 390px 页面截图；未进行服务器整机重启或活动生成中断演练。
- Secure/HttpOnly/SameSite=Lax Cookie；未知公开 API 是 JSON 404，缺失 assets、测试页、点文件 404，超过 512 KiB 请求体 413。
- `npm audit` 当前锁文件 0 已知漏洞；服务模板回归先失败后修复，发布打包测试 3 项通过。此轮没有修改业务 JAR、前端产物或依赖锁文件，不冒称重跑了全套业务测试。

浏览器只对从 SSH 证书计算得到的 SPKI 放行；这证明应用在信任该证书后的表现，不等于普通浏览器已公信。验收脚本和截图在开发机临时目录 `/private/tmp/atoms-cloud-deploy.A3b2w7/`，不上传会话或 trace。

## HTTP 切换验收（2026-10-08，协议仍沿用）

- 原 400 正文为 `The plain HTTP request was sent to HTTPS port`，与 Nginx `listen 8443 ssl` 一致。现公网首页 200、`/api/health` 为 UP，三个服务 active，后端 NRestarts=0。
- 本地打包测试 3 项、前端 106 项、后端单测 20 项和真实 PostgreSQL 集成测试 43 项通过；类型/格式/lint/构建通过；实际 Chrome runtime 回归 15 项通过，包含预览、保存、发布与下线。新增测试先失败后修复；反转 HTTP 同源判断时 4 项测试失败，恢复后 13 项发布解码测试通过。
- 完整构建的候选包与提交后的正式包 13 个载荷哈希完全一致；服务器再次校验归档与解包文件。未引入依赖或数据库迁移，也未使用模型。
- 独立 Chrome 无证书放行、无安全上下文覆盖参数：实测 `isSecureContext=false`、`crypto.randomUUID` 不存在，仍可建立 HTTP 访客、创建“HTTP 部署验收：不调用模型”项目、刷新恢复；第二访客读取私有项目被拒绝。
- 模拟浏览器保留旧 Secure `ATOMS_SESSION` 时，新的 HTTP Cookie 仍正常工作；检查 HttpOnly / SameSite=Lax / 非 Secure，缺 CSRF 的写请求为 403。管理页 CSP/nosniff 保留、无 HSTS；点文件、缺失资源、测试入口及未知公开 API 保持 404；正常页面控制台无 warning/error/pageerror。已检查桌面和 390px 截图。
- 初次切换的即时 HTTP 探测触发自动回退，数据库未恢复或删除。日志显示 Nginx ExecReload 仅发 HUP，随后新旧 worker 交替；改为有界就绪探测后实测第一笔 400、第二笔 200，成功切换。见 [Nginx 重载机制](https://nginx.org/en/docs/control.html)。这不是放宽 200 验收要求。

本轮验收脚本和截图保留在 `/private/tmp/atoms-http-deploy.F425WE/`，远端切换脚本位于 `/var/tmp/atoms-http-deploy.X6eNYZ/`。本轮未重新验收 HTTP 会话跨后端重启，也未将本地 runtime 通过冒称为云端真实生成/发布闭环。

## DeepSeek 配置同步（2026-10-08）

用户明确要求将本地 DeepSeek 配置放到服务器。密钥只通过严格校验主机密钥的 SSH 标准输入传输；不出现在命令参数、终端输出、源码或临时上传文件中。仅更新 `/etc/atoms-demo/application.properties` 的 `DEEPSEEK_API_KEY`，保留 root:atoms / 0640；未复制本地数据库密码、会话或完整环境文件。模型仍为代码固定的 `deepseek-flash`，服务器既有超时 120 秒、输出上限 16384 tokens 不变。

密钥首次同步时，服务器向固定 HTTPS 上游请求[模型列表](https://api-docs.deepseek.com/zh-cn/api/list-models/)返回 200；没有发出 chat completion。后端重启后 health=UP，独立 Chrome 首页 200、控制台无错误；访客认证后的 `/api/generation` 返回 `configured=true`、`workerEnabled=true`、`model=deepseek-flash`、`remainingRequests=0`，仅有这四个字段，未包含密钥。此验证证明连接、认证和配置装载，不等于实际生成成功。

旧配置备份：`/var/backups/atoms-demo/model-config.EA1LfF/application.properties`，父目录 root / 0700；备份中的模型密钥为空。回退此配置并重启即可撤销本次同步，数据库无需恢复。传输与验收脚本在本机 `/private/tmp/atoms-model-deploy.Jt7UIs/`，远端 `/var/tmp/atoms-model-deploy.Gly8wL/`；脚本本身不含密钥。本次仅更改外部配置，没有重新构建或部署程序，也未冒称重跑业务全套测试。

### 累计调用额度启用

用户随后明确选择 100 次。只将服务端 `GENERATION_MAX_MODEL_CALLS` 从 0 改为 100，未改密钥、超时、输出长度、工作台访问方式或数据库计数；配置权限仍为 root:atoms / 0640。重启后 health=UP、服务 active、NRestarts=0。独立 Chrome 实际读取 `remainingRequests=100`，同时确认模型已配置、worker 已启用、首页 200、控制台无错误；未创建生成任务、未消耗模型请求。这不代表云端实际生成/发布闭环已验收。

本次配置备份位于 `/var/backups/atoms-demo/model-budget.52ShPQ/application.properties`（文件 root / 0600，目录 0700），包含密钥，禁止公开或输出。备份额度为 0，可用于回退此次变更；若后续修改过其他配置，停用生成应只将额度改回 0 并重启，保留当前其他配置和调用记账。上限用尽不自动扩容，增加额度需要再次确认。

## 长度限制修复（2026-10-08）

用户报告 2048 生成失败。任务 `de708451-c4fc-4535-b8be-432d34f797b7` 的规划成功，BUILDING 约 58 秒后因 length 返回 MODEL_INCOMPLETE，没有保存版本。旧实现丢弃该响应 usage，因此不能追溯其精确 token 或推理消耗；不能将错误误认成超时或 100 次额度耗尽。

用户确认后将云端 `DEEPSEEK_MAX_TOKENS=32768`、`DEEPSEEK_TIMEOUT_SECONDS=180`；总上限仍为 100，不更改已有记账。模型密钥、HTTP、数据库及其他配置经不输出内容的比较确认未变。新版本将非 stop completion 的安全结束原因、输出上限及用量写入关联日志，并把已知失败调用用量落库；未取得响应的调用仍保留 UNKNOWN，不自动重试、不保存截断代码。详见 [ADR-006](decisions/006-deepseek-generation.md)。

升级前无活动任务。备份 `/var/backups/atoms-demo/model-length.sYQnSh/` 含 `application.properties` 和 `database.dump`，目录 0700、文件 0600，包含凭据，不公开；dump 的目录检查通过，未另做恢复演练。回退可停后端、切回 HTTP 旧版本 `atoms-demo-Dpprat`、恢复该配置为 root:atoms / 0640，再启动和检查 HTTP 健康，不改 Nginx、不恢复数据库或重置调用额度。升级后 health=UP、首页 200、三个服务 active；切换时已用仍为 2 次。

验证：回归先复现缺少诊断和失败调用 UNKNOWN；修复后通过。反转失败记账条件会使两项测试失败，恢复后正式构建通过：打包 3 项、前端 106 项、后端单测 22 项、真实 PostgreSQL 集成 44 项，以及类型、lint、格式和构建；npm audit 为 0 已知漏洞。完整候选包与提交后发布包 13 个载荷哈希相同，服务器再次逐项验证。没有依赖升级、数据库迁移或前端行为变更；未将之前的 15 项 runtime 验收说成本轮重跑。

本机操作脚本/浏览器证据位于 `/private/tmp/atoms-length-fix.tgI9AZ/`；云端无凭据脚本位于 `/var/tmp/atoms-length-fix.fbABnp/`。归档解包时 GNU tar 忽略 macOS provenance 扩展属性，载荷校验全部通过；此警告不涉及程序文件缺失。

真实云端验收仅提交一轮原需求“帮我做一个 2048 小游戏”，使用独立临时 Chrome 访客，不复用/提取用户会话。项目 `486a94fd-30d0-4abb-8fa5-99cdb06f1200`、任务 `b391ceef-219e-4f6b-bb51-3e49fd21107f` 成功，V1 为 `55241c45-80d3-4450-a434-c3e78be32c65`。规划用量 395 / 660 / 1055、构建 1500 / 18001 / 19501（输入 / completion / 合计）；构建约 58 秒。此次 18001 completion tokens 高于旧上限，但不据此倒推原失败调用的未知计数。

生成过程中刷新页面后继续完成，前后两次 SSE 均为 200，浏览器自动装载预览。实际方向键操作发生合并、分数增至 4，棋盘与最高分通过 PREVIEW 状态 API 保存（revision=3）；刷新后棋盘数组、分数、最高分不变，页面显示正确，控制台 warning/error/pageerror 均为空。只做 CREATE，一共 2 次请求，没有 Repair 或第二轮重试；云端累计 4 / 100，剩余 96。原失败任务未修改，测试项目保留为独立访客私有项目，没有公开发布或变更用户项目所有者。截图为 `2048-preview.png`。

## 分层超时与输出上限调整（2026-10-08）

用户最终选择 `DEEPSEEK_MAX_TOKENS=16840`、`DEEPSEEK_TIMEOUT_SECONDS=300`、`GENERATION_TIMEOUT_SECONDS=600`，已更新云端配置及部署包；早先提出的 327680 tokens / 单次 600 秒方案未部署。16840 按原值传给上游，不取整为 16384。单次模型请求最多 5 分钟，整轮从 RUNNING 开始最多 10 分钟，覆盖规划、构建、有限修复与保存；每次调用也受整轮剩余时间约束，不因进入下一阶段重新计时。累计请求上限仍为 100；本地私有配置不变。

代码将单次允许的超时上界由 180 秒调整为 300 秒，整轮硬编码 360 秒改为独立配置（默认 360 秒、允许 1–1800 秒，云端实际 600 秒）。运行中检查和过期任务查询共用同一配置；取消、超时不自动重发，晚到产物不保存。token 配置上界仍为 32768，上游响应 2 MiB 和产物大小限制不变，不开放任意上游地址或用户自定义密钥。

最终参数验证：新增精确 16840 请求体测试、300 秒配置测试、非法边界测试，以及真实测试库中的整轮截止/过期查询一致性测试。300 秒配置在原实现下失败；将边界误改为排除 300 时测试同样失败，恢复正确判断后正式构建通过。打包测试 3 项、前端 106 项、后端单测 25 项、真实 PostgreSQL 集成 46 项全部通过，类型/lint/格式/构建通过；npm audit 为 0 已知漏洞。提交后的正式包与完整构建候选包 13 个载荷哈希一致，服务器再次校验归档及全部文件。未增加依赖、数据库迁移或前端行为变更。

升级前无活动任务，备份 `/var/backups/atoms-demo/model-limits.3qY5vM/` 包含私有 `application.properties` 和 `database.dump`，目录 0700、文件 0600；dump 目录检查通过，此次未做恢复演练。部署脚本仅修改三个限制配置，其他配置经不输出内容的比较确认未变，配置权限仍为 root:atoms / 0640。旧包、模型密钥、用户项目和调用记账保留。回退时停后端、切回 `atoms-demo-X4tLzj` 并恢复上述私有配置（32768 / 180 秒、未配置整轮值即默认 360 秒），再启动检查；旧包不支持 300 秒，不可只切包而保留新配置。不改 Nginx、不恢复数据库或重置额度。

远端及开发机公网 HTTP 健康检查均为 UP，首页 200，三个服务 active，后端 NRestarts=0；读取实际配置确认 16840 / 300 / 600 已生效。累计已预留 4、剩余 96，本次未发起付费模型请求，也未重跑浏览器或云端生成闭环。此前 2048 的 BUILDING 用了 18001 completion tokens，高于新上限，因此新配置仍可能触发 MODEL_INCOMPLETE，不能用历史成功保证任意需求完成。

本机更新脚本位于 `/private/tmp/atoms-limit-upgrade.r05nQs/deploy.sh`，远端位于 `/var/tmp/atoms-limit-upgrade.ATQ9hP/`；脚本不含凭据。GNU tar 忽略 macOS provenance 扩展属性，所有载荷校验通过。

## 单次额度提高至 168400（2026-10-08，历史参数）

16840 配置下的新任务 `9e4dab9a-0f99-49d0-95b6-0b1fd2f559e0` 在 BUILDING 约 63 秒后返回 length：输入 1348、completion 16840、其中 reasoning 16266；规划成功，整轮共 2 次调用，没有新版本或自动重试。累计预留增至 6。这说明额度包含思考与最终内容，不能将其视为纯代码额度；不是超时或旧包未更新。

按用户明确要求，当时云端 **DEEPSEEK_MAX_TOKENS=168400**，单次 300 秒、整轮 600 秒、thinking.enabled / high、累计 100 次均不变。代码校验上界由 32768 提高到 168400，保留有限上界和 2 MiB 响应/120 KiB 源码限制。本地私有配置、模型密钥、数据库、Nginx 和 HTTP 访问方式未改动。更高上限允许更高消耗，不保证所有需求均能完成；本次没有付费生成或浏览器闭环验收。

验证：新增精确 168400 请求参数和思考设置回归，原实现下失败、修复后通过；将条件错误地改为拒绝 168400 时测试再次失败，恢复正确实现后完整构建通过。打包测试 3、前端 106、后端单测 26、PostgreSQL 集成 46 项通过，类型/lint/格式/构建通过；npm audit 为 0 已知漏洞，无新增依赖或数据库迁移。正式包与完整候选包 13 个载荷哈希一致，上传后逐项校验通过。

升级前无活动任务，备份 `/var/backups/atoms-demo/model-168400.GjCYng/` 含私有配置与数据库 dump，目录 0700、文件 0600；仅检查 dump 目录，未做此次备份恢复演练。仅修改 token 一项，其他配置经无内容输出的比较确认一致，权限保留 root:atoms / 0640。服务重启后实际配置为 168400 / 300 / 600，三个服务 active、NRestarts=0；公网首页 200、健康检查 UP。累计仍为 6 / 100，剩余 94，没有额外模型调用。

回退：停后端，切回 `atoms-demo-RTV1eK`，同时恢复本次备份配置为 root:atoms / 0640（16840 / 300 / 600），启动后检查健康；旧包不接受 168400，不可只切包而保留新值。不恢复数据库、不删除项目、不重置记账。脚本保留于本机 `/private/tmp/atoms-token-168400.AFQH4c/`、远端 `/var/tmp/atoms-token-168400.N2kmjU/`；不含凭据。GNU tar 的 macOS provenance 扩展属性警告不影响已通过校验的文件。

## 项目状态与历史恢复上线（2026-10-11，历史程序）

新增：在工作台选择历史版本并确认后，将历史代码复制为新版本，保留所有历史、业务数据和已发布指针，不自动发布。修复：首页/项目列表按当前保存版本显示“已生成 / 尚未生成”，不再固定显示未生成。当时完整工程导出、独立后端和应用级数据库尚未实现。

源码 `ca4dac816345be8c05c180202703f21aa561b564` 已推送至 GitHub main；发布包 `atoms-demo-slKx1w.tar.gz` 的 SHA-256 为 `0680171e3c4664354075aebf1774e68efe6ea67d5f06e5b206515052eaa09cb9`，干净工作树构建，服务器校验归档与 13 个载荷文件一致。文档提交可晚于该程序提交，不改写已发布归档。

- 正式构建：3 项打包测试、前端 112 项单测、后端 26 项单测与 50 项 PostgreSQL 集成测试通过，类型、lint、格式、构建通过；npm audit 为 0 已知漏洞，未更改依赖。
- 同一源码的 18 项本地 runtime Chrome 回归已在功能验收时通过；本次额外用真实新发布包运行 Nginx/TLS/JAR 浏览器验收，3 项通过，覆盖会话、项目刷新、预览、发布/LIVE 数据隔离、下线及代理边界。
- 先在隔离测试库验证旧 `atoms-demo-txj7Bn`：V010 已存在时 Flyway 校验通过，只提示数据库比旧包最新迁移 V009 更新；旧包启动正常，3 项发布包浏览器验收通过。这是程序回退兼容性验证，不是把旧包部署回服务器，也没有恢复数据库。
- 升级前没有 QUEUED/RUNNING 任务。停后端后备份 `/var/backups/atoms-demo/restoration.zDKZ1V/`，含数据库 dump、外部配置、Nginx 和 systemd 配置；目录 root/0700，dump 与配置 root/0600，不公开。dump 目录校验通过，本次未另做备份恢复演练。
- 15:47（Asia/Shanghai）新程序启动，Flyway 从 V009 增量迁移至 V010，共 11 条成功记录。升级前后原有 7 个项目、7 个版本、5 条应用状态、发布记录、模型调用及预算的内容摘要一致；仅新增恢复来源字段，未回填或删除数据。外部配置逐字节一致，模型参数仍为 168400 / 300 秒 / 600 秒 / 累计 100 次。
- 三个服务 active，后端 NRestarts=0，公网 HTTP 首页 200、health=UP。独立 Chrome 实际验证新版资源、创建/列表/深链接刷新、恢复接口非法请求 422 且不改变项目、CSRF 403、另一访客读取私有项目 404、测试/敏感路径 404；正常 UI 无 console warning/error/pageerror，已检查 1440px 与 390px 截图。
- 公网验收只新增一个独立访客的空测试项目，没有操作真实用户项目或提交有效恢复请求。累计模型请求仍为 18 / 100，未发起任何付费模型调用。云端实际历史恢复闭环、生成发布/LIVE 闭环仍未实测，不能把本地通过等同于云端全流程通过。

本机脚本与截图位于 `/private/tmp/atoms-restoration-deploy.VQzXE8/`，服务器脚本位于 `/var/tmp/atoms-restoration-deploy.UBXZbO/`，无凭据；不上传浏览器会话。GNU tar 忽略 macOS provenance 扩展属性的警告不影响载荷哈希。Chrome DevTools MCP 不可用，使用现有 Playwright 独立 Chrome。

回退：停止 `atoms-demo`，将 `current` 切回保留的 `/opt/atoms-demo/releases/atoms-demo-txj7Bn`，启动并检查 HTTP health 与页面。**保留 V010、全部新版本和业务数据，不删列、不改 Flyway 记录、不恢复数据库**；配置及 Nginx 不需要改变。旧包不会展示恢复入口或来源信息，但仍可读取恢复后保存的新版本。旧包与迁移后 schema 的兼容性已在隔离库实测；真正恢复备份会丢失备份后的写入，必须另外获批。

## 工程下载与默认限制上线（2026-10-13，历史程序）

源码 `b9aae45bc0addbaf350bfc93baf3896be572576e`，发布包 `atoms-demo-MImuq3.tar.gz`，SHA-256 为 `f81776a4425bf1cac20f4950dbf8f4bb58d3e879699d7c2f1e2df1759649cd4d`。从干净功能分支构建，上传后归档与 13 个载荷文件全部校验通过；本轮没有推送 GitHub。文档记录提交晚于程序提交，不改写发布包。

- 新增当前/历史版本的工程 ZIP 下载，包含固定 Vue + Spring Boot 17 + PostgreSQL 工程；下载不要求平台开启构建。模型生成的业务代码仍受 HTML/CSS/JS 与 SDK 契约约束，不是任意后端工程生成。
- 本机异步构建模块随包部署，但仅允许显式开启的回环开发模式，云端保持关闭；未安装 Node/Maven、未启动用户应用实例。旧共享预览与 `/a/<slug>` 发布方式不变。
- 正式构建通过：打包测试 3 项、前端单测 159 项、后端单测 119 项、真实 PostgreSQL 集成测试 77 项，以及类型、lint、格式与生产构建。前端锁文件 `npm audit` 为 0 已知漏洞；未升级依赖。
- 本地 runtime Chrome 回归 20 项通过，覆盖真实当前/历史 ZIP 下载、私有数据排除、下载失败重试、键盘/手机访问、生成状态列表与刷新、恢复及发布/LIVE 数据隔离。首次运行遇到测试前端 5174 未就绪和 ERR_CONNECTION_REFUSED；设置回环 NO_PROXY 并强制新建测试服务后完整通过，没有放宽断言或修改产品代码。
- Docker CLI 就绪检查超时，本轮未执行 Docker/Nginx/TLS 发布包浏览器套件，也未重启 Docker。替代检查：新旧真实发布 JAR 分别连接专用 `atoms_demo_test`，在 V011 已存在时均启动且 health=UP，测试时禁用模型；另对实际服务器 Nginx/HTTP 做下述公网验收。这不是完整的线上回退演练。
- 确认无活动生成任务后停后端，备份到 `/var/backups/atoms-demo/engineering.HwpsFD/`：数据库 dump、旧外部配置及代理/服务配置；目录 root/0700，dump 与配置 root/0600。`pg_restore --list` 通过，本轮未做完整恢复演练。备份含密钥，不公开；同机备份不是异地灾备。
- 16:01（Asia/Shanghai）切换并启动成功，Flyway 从 V010 增量应用 V011，共 12 条成功记录。切换前后已有 8 个项目、7 个版本、5 条应用状态、0 条发布记录、18 次模型调用及预算的内容摘要一致；不重建数据库、不删除项目、不重置额度。后续浏览器验收仅新增一个独立访客空项目“10月13日工程版本部署验收：不调用模型”。
- 经用户确认，仅同步模型限制为 `DEEPSEEK_MAX_TOKENS=163840`、`DEEPSEEK_TIMEOUT_SECONDS=300`、`GENERATION_TIMEOUT_SECONDS=900`；其余配置经不输出内容的比较确认不变，权限仍为 root:atoms/0640，密钥保留。`GENERATION_MAX_MODEL_CALLS=100` 不变，验收时已用 18、剩余 82，模型已配置、worker 已启用，本轮未发起模型调用。
- 三个服务 active，后端 NRestarts=0，内外网 health=UP、HTTP 首页 200。独立 Chrome 验证新前端资源 `index-B1HxKYjp.js`、项目创建/列表/深链接刷新、新构建列表接口、恢复非法请求 422、缺 CSRF 403、另一访客读取项目/构建 404，以及测试/敏感路径 404。正常 UI 无 console warning/error/pageerror，已检查 1440px 桌面与 390px 手机截图，无横向溢出。
- 公网验收未使用已有用户会话，未实际下载已有用户工程 ZIP、提交有效历史恢复或生成/发布应用；这些正向功能的本地通过不能冒称为云端全流程通过。服务端建表不代表已启用独立实例预览。

上传曾因 SSH 连接中断失败，带保活参数重新上传后校验通过。正式切换由一次性 systemd 单元 `atoms-engineering-deploy-20261013.service` 执行，避免 SSH 中断打断停服/切换；该单元结果 success，平台自身仍由既有 `atoms-demo.service` 管理。本机脚本/截图位于 `/private/tmp/atoms-engineering-deploy.HaPrWH/`，远端脚本位于 `/var/tmp/atoms-engineering-deploy.lYzuEo/`；不上传浏览器会话或凭据。

当时版本回退：停止 `atoms-demo`，将上述备份内的 `application.properties` 恢复为 `/etc/atoms-demo/application.properties`（root:atoms/0640），把 `current` 原子切回保留的 `/opt/atoms-demo/releases/atoms-demo-slKx1w`，启动并检查回环和公网 HTTP 健康。旧配置为 168400 / 300 秒 / 600 秒 / 累计 100 次。**保留 V011、所有业务数据与调用记账，不删表、不改 Flyway 记录、不恢复数据库**；Nginx 与 systemd 配置无需改变。旧包与 V011 的启动兼容性已在专用测试库验证；真正恢复数据库会丢失备份后的写入，须另外获批。

## 一键运行界面与直接导出上线（2026-10-14，当前程序）

按用户要求部署当前分支，不推送 GitHub。发布包 `atoms-demo-vM9VRY.tar.gz`，源码 `f2bd4e807d45599d64c3d2528db5a661855af251`；SHA-256 为 `4fb6103304f938caaf1209f9def52e8fbff1c8b4dfbd2fe6629d5e731bb9c4a8`。构建时工作区干净；以下记录晚于打包，不改写已发布归档。

- 正式发布构建：打包测试 3 项、前端 199 项、后端 123 单元 + 83 真实 PostgreSQL 集成测试通过，后端无失败或跳过；类型、lint、格式及生产构建通过。平台和导出模板前端锁文件审计均为 0 已知漏洞，没有升级依赖。
- 停服前确认没有活动生成或构建；数据库 dump、外部配置、Nginx/systemd 配置备份在 `/var/backups/atoms-demo/runtime.7iNq4z/`。目录 root/0700，配置和 dump 不公开；`pg_restore --list` 验证通过，未执行恢复演练。
- 归档及解包后 13 个载荷哈希全部通过，原子切换 `current` 后重启成功；Flyway 从 V011 增量应用 V012。切换前后 13 个项目、16 个版本、8 条应用状态、0 条发布记录、32 次模型记账及预算、0 条工程构建的数量和内容摘要均相同。新增运行表为空；外部配置逐字节不变，不重建数据库、不重置额度。
- 后端、Nginx、PostgreSQL 均 active；后端 NRestarts=0，回环/公网 HTTP 首页 200、health=UP。独立 Chrome 验证新资源 `index-By1liB2b.js`，旧“导出版本源码 ZIP”直接可见、新“运行应用”入口可见，项目创建/刷新、首页/列表、新运行记录接口、所有者隔离和 CSRF 保护通过；390px 无横向溢出，正常页面无 pageerror/console error。
- 远程冒烟仅新增一个独立验收访客的空项目“10月14日新版部署验收（不调用模型）”。模型可用，剩余额度检查前后均为 68，没有调用服务器模型，也未访问其他用户的私有工程或会话。
- **服务器构建与本机进程开关仍关闭**：保留现有 server profile 与安全边界，未安装 Node 或应用专用运行数据库池。界面与代码上线不等于服务器已支持一键构建启动；原共享预览、直接版本导出及 `/a/<slug>` 发布方式不变。本地真实 2048 验收另记于[运行验收记录](https://github.com/NigelWu95/atoms-demo/blob/main/docs/app-runtime-verification.md)。

本机部署脚本/截图：`/private/tmp/atoms-runtime-deploy.VP06fc/`；服务器脚本：`/var/tmp/atoms-runtime-deploy.gOwT3L/`。Chrome 使用独立验收会话，不使用或上传用户浏览器凭据。Docker 管理接口超时，未重启/退出登录 Docker；因此未执行依赖 Docker 的 Nginx/TLS 发布包套件，远程实际 HTTP 冒烟不代替该套件。

当前程序回退预案：先确认无活动任务，停止 `atoms-demo`，将 `current` 原子切回 `/opt/atoms-demo/releases/atoms-demo-MImuq3`，再启动并核对回环/公网 health、页面与日志。配置本次未变，无需恢复配置。**保留 V012、业务数据及额度记账；不删除运行表、不修改迁移记录、不直接恢复数据库。** 本次升级未触发回退，尚未实测旧包在 V012 下的启动；执行回退时须核验兼容性，不能只以切换成功作为验收。备份仍为同机备份，不是异地灾备。

## 备份及运维

已执行 `pg_dump -Fc`，备份为 `/var/backups/atoms-demo/atoms-demo-initial-20261008.dump`，root / 0600，目录 0700。恢复到独立临时库验证了 10 次迁移、1 个项目、调用预算记账 0；临时恢复库已删除，正式库未覆盖，备份保留。该备份在同机且未加密，不是异地灾备；自动定时、加密异地备份及保留策略仍待落实。未迁移或备份开发机业务数据。

HTTP 切换前再次备份到 `/var/backups/atoms-demo/http-switch.hzH19S/`（目录 0700）：`database.dump` 为 0600，`pg_restore --list` 通过，但未对此次新备份再做恢复演练；另含原外部配置、HTTPS 站点、静态安全头。第一次自动回退的备份 `http-switch.GKMCaI/` 同样保留。数据库没有被重建或覆盖。

常用命令（通过 SSH 在服务器执行）：

```sh
systemctl status atoms-demo postgresql-17 nginx
journalctl -u atoms-demo -n 100 --no-pager
curl --noproxy '*' -fsS http://127.0.0.1:8080/api/health
nginx -t
systemctl restart atoms-demo
```

配置修改后重启后端，Nginx 配置改动须先 `nginx -t` 再 reload。不要输出完整配置、密码或 Cookie。DeepSeek 密钥已授权同步，累计预算已授权为 100 次，公网访客共用；不删除记账或自动放开上限。长期开放前仍需补齐身份限流和资源配额，后端仍只允许单实例。

更新/回退遵循 [部署说明](../deploy/server/README.md)：备份 → 停后端 → 切换已校验版本 → 启动检查。当前程序回退使用上节 2026-10-14 的步骤。以下仅为首次 HTTPS 版本的历史回退方案：回退至旧 `atoms-demo-XmTJCF` 时，必须同时恢复 HTTP 切换前备份内的外部配置、HTTPS 站点和静态安全头，将 `http-demo.conf` 移出 systemd drop-in 目录后 daemon-reload，再启动后端、检查代理配置并重载。等待协议入口就绪，不以发出 reload 信号视为完成；旧证书必须仍有效。不恢复数据库、不删除新 HTTP 期间产生的数据，不将恢复数据库当成无损代码回退。

尚未验收：云端实际生成版本发布与 LIVE 数据闭环、活动任务崩溃恢复、整机重启，以及不刷新页面的 SSE 断网游标恢复。云端真实 CREATE、生成中刷新后 SSE 重连、预览交互保存与刷新恢复已通过；不把一款 2048 的成功等同于任意需求稳定性保证，T29 总项继续保留未完成。
