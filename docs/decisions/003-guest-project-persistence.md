# ADR-003 · 访客会话、项目与事务幂等

## 状态

已接受，2026-10-07。实现 T05–T09；补充 ADR-002 的本地只读基础阶段，不改变 Java 17、本地优先和暂不调用模型的决定。

## 背景与决定

首个业务闭环需要稳定的服务端身份和项目所有权，不能把浏览器草稿当数据库项目。采用 Spring Boot BOM 管理的 Spring Security 6.5.11 / Spring Session 3.5.7，复用 PostgreSQL JDBC，无新增 Redis、JWT 或账号密码系统。

- GET `/api/csrf` 取得经过 BREACH 掩码的 token；POST `/api/session` 创建或恢复访客；认证成功后再次取得 CSRF。前端只在内存保存 CSRF，认证 Cookie 为 HttpOnly。
- 首次认证变更 session ID、更新 CSRF，并显式保存 SecurityContext。会话及其属性在 JDBC 表中，应用重建不依赖原 Java 进程内存。
- Cookie 名 `ATOMS_SESSION`、SameSite=Lax、最长 7 天；服务端不活动超时 7 天。基础配置 Secure=true，只有 local/test HTTP 环境覆盖为 false。默认启动是 local，监听回环地址；未来部署必须显式使用非 local 配置及 HTTPS。
- 数据库访客 UUID 是服务端生成的伪匿名标识；只收集项目名称，不收集邮箱、电话或真实姓名。所有项目 SQL 均同时使用会话 ownerId 和项目 ID；不接受客户端 ownerId。
- 项目名称去除首尾空白后 1–80 个 UTF-16 单元、不含控制字符。只接受 name 字段，重命名不改变源码 revision；相同名称不会更新 updatedAt。
- 列表按不可变 `(created_at DESC, id DESC)` 使用游标分页，而非按会被重命名改变的 updatedAt 分页，减少翻页时重复/遗漏。索引以 owner_id 开头。游标不构成授权；每页继续检查 ownerId。
- 幂等记录唯一键 `(actor_id, method, resource, intent_key)`。规范化业务输入后递归排序 JSON 对象属性并做 SHA-256；数组顺序保留。唯一 INSERT 争抢和业务回调、原始状态码/JSON 结果在同一事务；冲突等待至多 3 秒，事务至多 5 秒。数据库暂不可用/锁超时返回可重试 503，不自动重做操作。
- 幂等服务的回调只允许短数据库事务，不得在事务中等待模型或调用外部收费服务。后续生成任务只在此事务中入队。幂等记录当前不自动删除，至少覆盖整个评审期。
- 前端为一次意图生成键，失败重试保留；提交中拒绝重复点击。共享 HTTP 层不自动重试写操作，401/403 要求显式重新连接；非 JSON 的错误响应也保留 HTTP 错误语义。
- 首页原始需求和意图键按项目保留在 Pinia 内存，供 T23 接入。当前只持久化项目元信息，刷新会丢失未提交的需求草稿，页面明确提示。

## 安全边界与后果

浏览器输入、Cookie 和分页参数都是不可信边界。主要资产是访客会话、私有项目以及未来的付费生成预算。防护涵盖 CSRF、会话固定、越权、重复执行、SQL 参数绑定和 Vue 文本转义；对应失败路径有测试。

清除 Cookie、更换浏览器或超过有效期无法恢复旧访客项目，暂不提供跨设备账号恢复或退出按钮。访客项目不适合存放敏感数据。会话过期会由 Spring Session 清理，但项目和幂等记录不会随之删除。

本阶段仍为回环地址上的本地开发，不是可直接公开的生产认证系统。上线前必须补验共享限流、请求体/访客创建/项目数量总配额、数据保留及删除/导出策略、HTTPS 代理和后端依赖安全扫描；不能把 Cookie 隔离当作滥用防护。不能复制 local/test 的不安全 HTTP Cookie 配置到公网。没有实施云部署、付费模型调用或用户自带 API Key。

迁移 V001–V003 是增量迁移，不改写已应用的 V000。回退代码时保留表和数据，不通过删库、删卷、Flyway clean 恢复。

## 官方实现依据

- [Spring Security 6.5 手动保存认证与会话策略](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/session-management.html#store-authentication-manually)
- [Spring Security 6.5 CSRF 与 JavaScript 接入](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html)
- [Spring Session 3.5 JDBC](https://docs.spring.io/spring-session/reference/3.5/configuration/jdbc.html)
- [Spring Session 3.5.7 官方 PostgreSQL 表定义](https://github.com/spring-projects/spring-session/blob/3.5.7/spring-session-jdbc/src/main/resources/org/springframework/session/jdbc/schema-postgresql.sql)
- [PostgreSQL 17 INSERT / ON CONFLICT](https://www.postgresql.org/docs/17/sql-insert.html#SQL-ON-CONFLICT)
- [Spring 事务代理语义](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)
- [Vue Router 路由变化后的数据读取](https://router.vuejs.org/guide/advanced/data-fetching.html)
- [Pinia 异步 actions](https://pinia.vuejs.org/core-concepts/actions.html)
