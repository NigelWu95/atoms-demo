# ADR-011 · 单服务器原生部署与路径发布

## 状态

2026-10-08 用户确认部署方向；取代 ADR-008 的本地工作台 / ngrok 部署限制，不改变 ADR-010 的固定版本与访客数据语义。同日提供 SSH 并要求 IP + 端口直接访问；已按此方向完成基础部署，模型生成尚未启用，详见[部署记录](../cloud-deployment.md)。

## 决定与边界

- 一台 Linux 服务器运行 Nginx、Java 17 Spring Boot 单实例和原生 PostgreSQL 17；不要求服务器安装 Docker、Node 或 Maven。
- Nginx 提供 Vue 构建产物及同源 `/api/` 代理。整套平台可通过服务器站点访问；已发布应用继续用 `/a/<slug>`，不启动每项目容器、端口、域名或 ngrok。
- 后端只绑定 `127.0.0.1:8080`，数据库只监听回环地址。对外只开放受 TLS 保护的站点入口；目标环境 TencentOS 3.3，服务账号 atoms。当前使用 IP:8443 和 30 天自签证书，浏览器不自动信任，不代表已取得公信证书。SSH 来源限制建议由维护者配置，本次没有修改 SSH/防火墙。
- 公网访客仍可创建访客身份和项目，私有资源继续按所有者鉴权；这不是正式账号系统。开放生成前必须显式配置调用预算并完成访问范围和限流审核；默认预算为 0，不自动付费。
- 发布包白名单包含生产 JAR、Vue 静态资源、启动脚本、Nginx/systemd 模板、初始化 SQL 和说明。排除真实凭据、数据库备份、测试夹具、node_modules、源映射与个人会话。
- 服务器凭据独立放在 `/etc/atoms-demo/application.properties`，不随发布覆盖。默认使用新库，不搬运本地用户数据和模型记账；如需迁移另行备份并确认。
- 保持现有本地开发入口、Java 17.0.5-oracle 和数据库数据不变。公网主机是否采用更新的 Java 17 安全补丁，在远程准备时确认，不自动升级本机。

## 验收与切片

1. `server` 配置正确识别本机 TLS 代理，保留 Secure / HttpOnly / SameSite 会话；本地配置不回归。
2. 单命令构建并输出可校验的 tar.gz；记录源码 revision / dirty 状态，失败不产出成功包；启动必须显式提供外部配置，不悄悄读取开发凭据。
3. 代理深链接刷新可用；API、缺失静态资源与运行 document 不落入 SPA fallback；SSE 不缓存、不缓冲；代理不叠加会破坏 iframe 的管理 CSP / X-Frame-Options。
4. 发布包通过本地实际启动/代理及浏览器验证；不调用真实模型、不使用或改写正常业务库。Linux systemd、真实域名/证书及外网访问留待服务器验收，不以模板测试代替。
5. 交付记录初始化、启动、日志、健康检查、备份与回退。应用更新不删除数据库、不修改已应用迁移，不同时运行两个生成 Worker。

## 风险与取舍

同机同服务意味着共同的故障域与资源上限；本次不实现独立应用后端或多实例。生成代码仍只在受限浏览器 iframe 内运行。数据库备份包含用户源码、状态和会话，必须限制访问；访客 Cookie 丢失后不能靠换服务器找回身份。已有总存储配额、身份频率限制和完整重启演练仍是待办，不因改部署方式而消失。

## 官方依据

- [Spring Boot 3.5 外部配置](https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html)
- [Spring Boot 3.5 反向代理与转发头](https://docs.spring.io/spring-boot/3.5/how-to/webserver.html#howto.webserver.use-behind-a-proxy-server)
- [Nginx 代理缓冲](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_buffering)
- [Nginx 响应头继承](https://nginx.org/en/docs/http/ngx_http_headers_module.html#add_header)
