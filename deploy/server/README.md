# 单服务器部署

适用：Linux + systemd，原生安装 Java 17、PostgreSQL 17、Nginx。应用只运行一个后端实例。服务器不需要 Node / Maven / Docker；发布包在开发机构建。下列是部署步骤，不表示已经部署；发行版、SSH、域名、TLS 证书和防火墙须在接入服务器后核实。

本项目已于 2026-10-08 完成一台 TencentOS 3.3 主机的基础部署；地址、主机特有配置、证书期限和验收边界见[云部署记录](../../docs/cloud-deployment.md)。模板步骤和目标机事实分别记录，不将已验证主机的结论套用于其他服务器。

## 1. 本地构建与交付

项目根执行 `nvm use`，首次先在 `frontend` 执行 `npm ci --ignore-scripts`。Java 使用现有的 17.0.5-oracle；开发测试库须已启动。回到项目根执行：

```sh
node scripts/release.mjs
```

命令运行打包测试、前端类型/格式/lint/单元测试/构建、后端 clean verify，再输出 `dist/releases/atoms-demo-*.tar.gz` 及 `.sha256`。不调用真实模型，不连接远程机器。发布包包含 `backend/atoms-demo.jar`、`frontend/`、`server/`、`release.json` 与 `SHA256SUMS`；不包含源码库、数据库数据、真实配置或测试入口。`release.json` 记录源码提交与 dirty 状态；正式交付应使用干净工作树构建，并额外完成浏览器验收与依赖审计。

上传 tar.gz 及对应校验文件，通过 `sha256sum -c <包名>.tar.gz.sha256` 校验，再解压到 `/opt/atoms-demo/releases/` 的新目录；不要覆盖旧版本目录。进入解压目录执行 `sha256sum -c SHA256SUMS`。由 root 持有发布文件，目录 0755、普通文件 0644；Nginx 需要读取 frontend。将 `/opt/atoms-demo/current` 链接指向本次发布目录。若该路径不是预期的符号链接，先检查，不能直接覆盖。

## 2. 数据库与凭据（首次安装）

先检查现有数据库、角色、磁盘空间和备份，避免撞名。仅对**不存在**的角色和数据库运行（不要把这些命令当重复安装脚本）：

```sh
sudo -u postgres createuser --login --no-superuser --no-createdb --no-createrole --pwprompt atoms_app
sudo -u postgres createdb atoms_demo
sudo -u postgres psql -X -v ON_ERROR_STOP=1 -d atoms_demo -f /opt/atoms-demo/current/server/init-db.sql
```

通过交互提示设置新的强随机密码，不使用命令行 `--password=<密码>` 或复制本机 `.env.local`。确认 PostgreSQL 只监听回环地址，并且应用角色的 `127.0.0.1/32` 连接使用 `scram-sha-256`；不要添加公网网段或 `trust`。可用 `psql -h 127.0.0.1 -U atoms_app -d atoms_demo -W -c 'SELECT current_user'` 验证。Flyway 在应用启动时应用 V000–V009 及后续增量迁移，重复启动不重建数据。

创建专用无登录 Linux 服务账号 `atoms`。首次将 `application.properties.example` 复制到 `/etc/atoms-demo/application.properties`，替换数据库密码和站点 origin；属主 root、组 atoms、权限 0640（目录 0750）。已有文件只编辑必要键，不覆盖。配置是 Java properties，不执行 `source`；复杂密码注意 properties 转义，建议数据库使用随机十六进制密码。

`PUBLIC_APP_ORIGIN=https://实际域名`，不含路径；公开链接为该 origin 加 `/a/<slug>`。`DEEPSEEK_API_KEY` 只放在服务器配置中。`GENERATION_MAX_MODEL_CALLS=0` 默认禁用付费生成；需要另外明确累计调用预算后修改，不能以清空记账表恢复额度。服务器默认新库，不自动迁移本机项目和会话；迁移属于独立备份/恢复操作。

## 3. 启动后端

先以前台方式检查，避免将配置错误藏在自动重启中：

```sh
sudo -u atoms sh /opt/atoms-demo/current/server/start.sh /etc/atoms-demo/application.properties
```

另开终端 `curl --noproxy '*' -fsS http://127.0.0.1:8080/api/health`，应返回 `{"status":"UP"}`。Ctrl+C 停止前台后，安装 `atoms-demo.service` 到 `/etc/systemd/system/`，执行 `systemctl daemon-reload`、`systemctl enable --now atoms-demo`。根据服务器实际 Java 安装位置设置服务 PATH；不要同时运行前台和 systemd 两个 Worker。

查看状态和日志：`systemctl status atoms-demo`、`journalctl -u atoms-demo -n 100 --no-pager`。后端端口保持回环，不开放 8080；数据库不开放 5432。`start.sh` 要求显式可读的外部配置，缺失即退出，不读取开发机相对路径配置。

PGDG 包的数据库服务名可能是 `postgresql-17.service`，须在本机 drop-in 中补充启动顺序；用 drop-in 的 PATH/JAVA_HOME 指定应用 Java 17，保留其他程序的默认 Java。模板以 `SuccessExitStatus=143` 识别 JVM 收到 SIGTERM 后的正常关闭，其他故障仍使用 `Restart=on-failure`；验收须检查停止时 `Result=success` 和启动后的 health。

## 4. Nginx 与 HTTPS

安装 `nginx-routes.conf`、`nginx-static-headers.conf` 到 `/etc/nginx/atoms-demo/`；将 `nginx-site.conf` 安装到发行版实际启用的站点目录（如 `sites-enabled` 或 `conf.d`）。修改 `atoms.example.com`、证书链和私钥路径，避免覆盖服务器上其他站点。模板要求证书已就绪；证书签发/续期按实际 DNS 和服务器配置办理，不在脚本中自动申请外部资源。

先 `nginx -t`，再 reload；不要在校验失败时继续。只开放需要的 80/443 和受限来源 SSH。测试、Vite 开发端口以及后端/数据库端口不对外开放。HTTP 只重定向到固定 HTTPS 域名；不能为直接 IP HTTP 访问关闭生产 Secure Cookie。没有域名/证书时先通过本机或 SSH 转发验收，再完成 TLS。

首页、`/projects/<id>` 和 `/a/<slug>` 共用一个站点；API 同源。深链接刷新回到 Vue 外壳，API/运行 document/缺失 assets 不回落 HTML。SSE 禁止代理缓冲，响应间隔不能超过代理读超时。Nginx 不覆盖 API 的 CSP/X-Frame-Options，以保留受限 iframe 的运行策略；不要再在外层叠加全站 `DENY` 头。模板禁用访问日志，避免记录项目地址/查询参数；若需要访问统计，先定义脱敏与保留规则。

用户明确选择 IP + 端口时，可将站点改为 `listen 8443 ssl`、`server_name <IP>`，设置含 IP SAN 的证书，并将 `PUBLIC_APP_ORIGIN` 设为 `https://<IP>:8443`。不需要 HTTP 入口，只公开该 HTTPS 端口。自签证书仅适合知情、受控的 Demo 体验，普通浏览器会警告；通过可信 SSH 核对指纹后再信任，并记录到期时间/更换计划。不得为省去证书提示关闭 Secure Cookie 或全局禁用浏览器证书校验。

## 5. 上线检查与运维

### 明确授权的临时 HTTP Demo

仅在用户接受明文传输风险后使用；默认服务器模板仍要求 HTTPS。配置 `PUBLIC_APP_ORIGIN=http://<IP>:8443` 和 `ALLOW_INSECURE_HTTP_PUBLIC_ORIGIN=true`；systemd 独立 drop-in 设置 `SERVER_SERVLET_SESSION_COOKIE_SECURE=false`、`SERVER_SERVLET_SESSION_COOKIE_NAME=ATOMS_HTTP_SESSION`，保留 HttpOnly / SameSite / CSRF / 所有者鉴权。HTTP Cookie 使用新名称，避免浏览器拒绝覆盖已有 Secure Cookie；原 HTTPS 访客项目不删除，但不会自动归属新 HTTP 访客。

备份当前版本、数据库、外部配置和 Nginx 站点后，将站点改为 `listen 8443`，删除该站点的证书/TLS 指令，保留原路由和其他安全头。HTTP 站点不发送 HSTS；不要同时以同一套非 Secure Cookie 对外提供 HTTPS。`nginx -t` 后切换、重启后端并 reload。回退需同时恢复 HTTPS 站点、原 origin 和 Secure Cookie 配置，不只切换 JAR。

公网 HTTP 没有 `crypto.randomUUID`，平台及受限 runtime 使用 `crypto.getRandomValues` 构造 v4 标识，保持重试幂等性；不使用弱随机数。发布响应只接受与当前 HTTP 页面同源的非回环 HTTP 链接，不允许从 HTTPS 页面降级到远端 HTTP。剪贴板 API 不可用时，面板提示手动复制链接。此例外不改变模型额度或开放数据库端口，也不满足生产加密要求。

- 用独立浏览器完成访客建项目、刷新恢复、查看已有版本、发布 `/a/<slug>`、公开访客保存/刷新、更新/下线；第二个访客不能读写第一人的私有项目或状态。
- 检查 Cookie 的 Secure/HttpOnly/SameSite、无混合内容、受限 document 的唯一 CSP/XFO；确认 API 错误不是 HTML，SSE 阶段及时到达。
- 实际启用模型前确认允许哪些人进入工作台以及预算。当前全站访客可创建项目；已有队列/超时/调用数限制不等于完整防滥用，身份限流与存储总配额仍须补齐或通过受控访问限制开放范围。
- 监测 health、服务日志、CPU/内存、磁盘容量和数据库连接；验证数据库备份可以恢复。备份用 `pg_dump -Fc`，放在发布目录之外、限制权限；备份含源码、状态、会话，不公开上传。
- 重启验证项目、版本、LIVE/PREVIEW 数据、有效会话和生成终态；迁移完成不等于此项已验收。验证模型调用记账未被清零。

更新：先备份数据库与受限配置，保留旧包；校验新包、停后端、切换 current、启动、检查 health 和关键页面，再 reload Nginx。只替换程序文件，配置在 `/etc`、数据在 PostgreSQL，均不随包覆盖。停机窗口明确告知体验者；旧 RUNNING 任务重启后中断，不自动重发。

回退：停止后端，只有确认数据库迁移与旧 JAR 兼容后才切回旧包，重新检查 health。若新增迁移不兼容，优先前向修复；数据库恢复会丢失备份后的写入，必须另行获批。不要删除表/数据库、改 Flyway 历史或运行 `down -v`。

本方案提供单机可恢复部署，不提供高可用、每应用独立资源隔离或任意生成后端。原生 Linux systemd、真实 TLS/外网及备份恢复必须在目标服务器验证；本地通过不能替代这些检查。
