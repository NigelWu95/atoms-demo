# 云服务器部署记录

2026-10-08 实际部署；这是 Demo 验收环境，不是完整生产上线签收。用户明确不等待域名，使用 IP + 端口访问。

## 访问和当前边界

- 工作台：<https://118.195.239.8:8443>；发布链接 origin 同此地址，应用沿用 `/a/<slug>`。
- 当前使用单独在服务器生成的 30 天自签名证书，不是公信证书。浏览器会警告，须核对证书后信任；未关闭 Secure Cookie 或改用公网 HTTP。
- 证书有效期至 **2026-11-07 02:15:58（Asia/Shanghai）**，没有自动续期。SHA-256 指纹：`C5:4F:98:43:36:47:87:F5:AA:3F:01:C6:6B:5C:CD:69:4F:9E:AA:85:09:BE:B2:C7:D9:7F:A1:FF:97:34:4D:34`。
- 使用全新数据库，没有上传本地项目、会话或 `.env.local`。云端 Key 为空、累计调用上限为 0，因此 **尚不能生成应用**；密钥同步和新的调用预算须另行授权。云端模型请求记账为 0，本地 11 次记录未改动。
- 全站采用既有访客模式，项目按所有者隔离。身份频率限制、总存储配额、长期数据清理仍待完成；不适合无人监管地长期开放。侧栏个别“本地”字样仍是既有展示文案，不代表请求发往开发机。

## 实际安装

服务器为 TencentOS Server 3.3 / x86_64，2 vCPU、约 3.5 GiB 内存。安装 Java 17.0.20.1、PostgreSQL 17.11、Nginx 1.30.5；没有安装 Docker、Node 或 Maven。Java 17 来自现有系统源，数据库和 Nginx 使用 [PostgreSQL 官方 RPM](https://www.postgresql.org/download/linux/redhat/) 与 [Nginx 官方源](https://nginx.org/en/linux_packages.html)，保留 RPM 签名校验。依赖预演无卸载和系统升级。

原有 `/usr/bin/java` 仍指向 Tencent Kona 8。应用通过 systemd drop-in 指定 `/usr/lib/jvm/jre-17-openjdk`，堆上限 768 MiB；不改变本地 17.0.5-oracle 或服务器默认 Java。

| 项目 | 位置 / 状态 |
|---|---|
| 发布版本 | `atoms-demo-XmTJCF`，源码 `d2602bf5c257658e824be49340c5a2e987830a7d`，打包时 dirty=false |
| 程序 | `/opt/atoms-demo/releases/atoms-demo-XmTJCF`；`/opt/atoms-demo/current` 为其符号链接 |
| 外部配置 | `/etc/atoms-demo/application.properties`，root:atoms / 0640，目录 0750 |
| TLS | `/etc/atoms-demo/tls/server.crt`、`server.key`；私钥 root / 0600，目录 0700 |
| 后端服务 | `atoms-demo.service`，非 root 的 `atoms` 账号，单实例 |
| 主机特有配置 | `/etc/systemd/system/atoms-demo.service.d/runtime.conf`，Java 路径、PG17 启动顺序、内存和正常退出码 |
| Nginx 站点 | `/etc/nginx/conf.d/atoms-demo.conf`，监听 `8443 ssl`；路由/安全头在 `/etc/nginx/atoms-demo/` |
| 数据库 | `atoms_demo` / schema `atoms` / 受限角色 `atoms_app`；数据目录 `/var/lib/pgsql/17/data` |
| 数据库服务 | `postgresql-17.service`；本机 peer、回环 TCP SCRAM-SHA-256，数据页校验已开启 |
| 公网监听 | SSH 22、HTTPS 8443；8080 和 5432 仅回环监听 |

三个服务均 active/enabled；没有修改 SSH、系统防火墙或云安全组。安装时新增的 Nginx 默认欢迎页配置保留为 `default.conf.atoms-disabled`，不会额外监听 80。程序目录中所有文件仍通过发布包 SHA256SUMS 校验；服务器特有配置在包外，不回写已发布归档。

第一次重启时，JVM 已优雅关闭，但 systemd 将退出码 143 记作失败。按 [Spring Boot systemd 示例](https://docs.spring.io/spring-boot/how-to/deployment/installing.html) 在 drop-in 加入 `SuccessExitStatus=143`；原配置保留为 `runtime.conf.before-exitfix`。再次实际启停验证 `ExecMainStatus=143`、`Result=success`，启动后 health=UP、NRestarts=0。仓库服务模板和回归测试已同步修正，历史发布包不改写。

## 已验收

- SSH 严格校验已有主机密钥；上传前后归档校验、解包后 13 个文件哈希一致。
- Flyway V000–V009 共 10 次迁移成功，重复启动不重新初始化。
- 从开发机通过公网直连 HTTPS health=UP；客户端使用经 SSH 取得的公开证书校验，不下载服务端私钥。
- 独立 Chrome 实际创建一个“云服务器部署验收：不调用模型”项目，刷新/深链接可恢复，另一个访客读取该私有项目被拒绝；正常主流程 console warning/error 和 pageerror 为空。
- 同一浏览器保持打开，实际重启后端后仍恢复原访客和项目。已检查桌面和 390px 页面截图；未进行服务器整机重启或活动生成中断演练。
- Secure/HttpOnly/SameSite=Lax Cookie；未知公开 API 是 JSON 404，缺失 assets、测试页、点文件 404，超过 512 KiB 请求体 413。
- `npm audit` 当前锁文件 0 已知漏洞；服务模板回归先失败后修复，发布打包测试 3 项通过。此轮没有修改业务 JAR、前端产物或依赖锁文件，不冒称重跑了全套业务测试。

浏览器只对从 SSH 证书计算得到的 SPKI 放行；这证明应用在信任该证书后的表现，不等于普通浏览器已公信。验收脚本和截图在开发机临时目录 `/private/tmp/atoms-cloud-deploy.A3b2w7/`，不上传会话或 trace。

## 备份及运维

已执行 `pg_dump -Fc`，备份为 `/var/backups/atoms-demo/atoms-demo-initial-20261008.dump`，root / 0600，目录 0700。恢复到独立临时库验证了 10 次迁移、1 个项目、调用预算记账 0；临时恢复库已删除，正式库未覆盖，备份保留。该备份在同机且未加密，不是异地灾备；自动定时、加密异地备份及保留策略仍待落实。未迁移或备份开发机业务数据。

常用命令（通过 SSH 在服务器执行）：

```sh
systemctl status atoms-demo postgresql-17 nginx
journalctl -u atoms-demo -n 100 --no-pager
curl --noproxy '*' -fsS http://127.0.0.1:8080/api/health
nginx -t
systemctl restart atoms-demo
```

配置修改后重启后端，Nginx 配置改动须先 `nginx -t` 再 reload。不要输出完整配置、密码或 Cookie。启用 DeepSeek 前，确认密钥转移授权、累计调用预算和工作台开放范围；不删除预算记账。后端仍只允许单实例。

更新/回退遵循 [部署说明](../deploy/server/README.md)：备份 → 停后端 → 切换已校验版本 → 启动检查。当前是首个部署版本，没有更早的云端版本可回退；失败时停止应用入口并保留数据库/配置，重新部署已验证版本。不得将恢复数据库当成无损代码回退。

尚未验收：云端真实模型生成、代理后的生成 SSE/重连、云端实际生成版本发布与 LIVE 数据闭环、活动任务崩溃恢复、整机重启。它们不因站点已可访问而算完成，T29 总项继续保留未完成。
