# 云服务器部署记录

2026-10-08 实际部署；这是 Demo 验收环境，不是完整生产上线签收。用户明确不等待域名，使用 IP + 端口访问。

## 访问和当前边界

- 工作台：<http://118.195.239.8:8443>；发布链接 origin 同此地址，应用沿用 `/a/<slug>`。用户明确要求直接 HTTP，已关闭此端口的 TLS，不自动跳 HTTPS。
- 这是显式的临时 Demo 例外，HTTP 流量未加密。独立 Cookie `ATOMS_HTTP_SESSION` 保留 HttpOnly / SameSite=Lax，但不含 Secure；旧 HTTPS 访客数据保留，不自动映射到新 HTTP 访客。
- 原 HTTPS 自签证书仅为回退保留，有效期至 **2026-11-07 02:15:58（Asia/Shanghai）**，没有自动续期。SHA-256 指纹：`C5:4F:98:43:36:47:87:F5:AA:3F:01:C6:6B:5C:CD:69:4F:9E:AA:85:09:BE:B2:C7:D9:7F:A1:FF:97:34:4D:34`。
- 使用全新数据库，没有上传本地项目、会话或整个 `.env.local`。已按用户授权单独同步 DeepSeek 密钥，并将云端累计模型请求上限设为 **100 次**，公网访客共用。启用时已预留 0、剩余 100；不是每日/每用户额度，也不是 100 次完整应用生成。配置验证没有发起生成或更改本地数据、模型记账。
- 全站采用既有访客模式，项目按所有者隔离。身份频率限制、总存储配额、长期数据清理仍待完成；不适合无人监管地长期开放。侧栏个别“本地”字样仍是既有展示文案，不代表请求发往开发机。

## 实际安装

服务器为 TencentOS Server 3.3 / x86_64，2 vCPU、约 3.5 GiB 内存。安装 Java 17.0.20.1、PostgreSQL 17.11、Nginx 1.30.5；没有安装 Docker、Node 或 Maven。Java 17 来自现有系统源，数据库和 Nginx 使用 [PostgreSQL 官方 RPM](https://www.postgresql.org/download/linux/redhat/) 与 [Nginx 官方源](https://nginx.org/en/linux_packages.html)，保留 RPM 签名校验。依赖预演无卸载和系统升级。

原有 `/usr/bin/java` 仍指向 Tencent Kona 8。应用通过 systemd drop-in 指定 `/usr/lib/jvm/jre-17-openjdk`，堆上限 768 MiB；不改变本地 17.0.5-oracle 或服务器默认 Java。

| 项目 | 位置 / 状态 |
|---|---|
| 发布版本 | `atoms-demo-X4tLzj`，源码 `5817a068fe2ddc465e0971a04f3c38768cfd8e8d`，打包时 dirty=false |
| 程序 | `/opt/atoms-demo/releases/atoms-demo-X4tLzj`；`/opt/atoms-demo/current` 为其符号链接；旧 `atoms-demo-Dpprat`（HTTP）和 `atoms-demo-XmTJCF`（HTTPS）保留 |
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

## HTTP 切换验收（当前）

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

更新/回退遵循 [部署说明](../deploy/server/README.md)：备份 → 停后端 → 切换已校验版本 → 启动检查。回退至旧 `atoms-demo-XmTJCF` 时，必须同时恢复上述备份内的外部配置、HTTPS 站点和静态安全头，将 `http-demo.conf` 移出 systemd drop-in 目录后 daemon-reload，再启动后端、检查代理配置并重载。等待协议入口就绪，不以发出 reload 信号视为完成；旧证书必须仍有效。不恢复数据库、不删除新 HTTP 期间产生的数据，不将恢复数据库当成无损代码回退。

尚未验收：云端实际生成版本发布与 LIVE 数据闭环、活动任务崩溃恢复、整机重启，以及不刷新页面的 SSE 断网游标恢复。云端真实 CREATE、生成中刷新后 SSE 重连、预览交互保存与刷新恢复已通过；不把一款 2048 的成功等同于任意需求稳定性保证，T29 总项继续保留未完成。
