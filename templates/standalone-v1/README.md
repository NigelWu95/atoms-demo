# 独立应用工程

一个应用一套 Vue 外壳、Spring Boot 3.5.16 后端和独立 PostgreSQL。生成的 HTML/CSS/JS 仍在受限 iframe 内运行，通过 `Atoms.ready` 的 storage SDK 保存当前访客的数据；它不是任意后端代码生成器。成品不连接 Atoms 平台，也不需要模型 API Key。

## 环境与配置

构建使用 Java 17（开发验证版本 17.0.5-oracle）、Node 24.20.x、npm 11.19.x、Maven Wrapper 3.9.11 和 PostgreSQL 17。当前 Node 脚本支持 Linux/macOS；Windows 可在相应 Linux 环境构建。首次构建需要访问依赖仓库。运行成品只需要 Java 17、JAR、外部配置和 PostgreSQL。

先准备一个自己有权限的外部配置目录，下列 `/absolute/path` 均需替换成真实绝对路径。分别初始化运行配置和测试配置；不会覆盖已有文件，也不打印密码：

```sh
node scripts/init-config.mjs --output /absolute/path/application.properties
node scripts/init-config.mjs --output /absolute/path/test.properties
```

保留两个文件各自生成的 `APP_INSTANCE_ID`，不要使用同一个实例 ID。文件初始权限是 0600。编辑时遵循 Java `.properties` 语法（反斜杠需要转义，Unicode 用 `\uXXXX`），不要使用 `.env` 语法或环境变量占位符。

运行配置保留 `standalone.test-database=false`；测试配置改成 `true`。两个文件必须使用**不同的专用数据库及受限角色**。默认只监听 `127.0.0.1:8088`，origin 是 `http://127.0.0.1:8088`；测试配置可使用 `18088` 并同步修改 origin。真实配置不得提交到 Git 或放入 application/frontend/backend 资源目录。

## 准备数据库

由受信任的 PostgreSQL 17 管理员（示例使用超级用户）做一次性准备，应用自身没有建库或创建角色的权限。以下示例只用于**全新且名称未占用**的 `demo_runtime` / `demo_test`。如名称已有对象，应先检查归属，不能通过删库、重置密码或修改标记来绕过保护。使用 `psql -X -v ON_ERROR_STOP=1` 连接管理员的 `postgres` 数据库；管理员凭据绝不用于应用或测试连接，也不要写进应用配置。

运行库（数据库归当前管理员所有，应用角色只拥有自己的 schema）：

```sql
CREATE ROLE demo_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
  NOINHERIT NOREPLICATION NOBYPASSRLS;
\password demo_runtime
CREATE DATABASE demo_runtime TEMPLATE template0;
REVOKE ALL ON DATABASE demo_runtime FROM PUBLIC;
GRANT CONNECT ON DATABASE demo_runtime TO demo_runtime;
\connect demo_runtime
REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM demo_runtime;
CREATE SCHEMA standalone_app AUTHORIZATION demo_runtime;
REVOKE ALL ON SCHEMA standalone_app FROM PUBLIC;
```

在 `application.properties` 填入 `jdbc:postgresql://127.0.0.1:5432/demo_runtime`、角色 `demo_runtime` 和刚设置的强密码。URL 不允许携带账号、查询参数或多主机地址。

测试库需另建。先把下面两处 `REPLACE_WITH_TEST_INSTANCE_UUID` 替换成 **test.properties 中的 APP_INSTANCE_ID**，保留前面的 `atoms-standalone:test:`：

```sql
\connect postgres
CREATE ROLE demo_test LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
  NOINHERIT NOREPLICATION NOBYPASSRLS;
\password demo_test
CREATE DATABASE demo_test TEMPLATE template0;
COMMENT ON DATABASE demo_test IS 'atoms-standalone:test:REPLACE_WITH_TEST_INSTANCE_UUID';
COMMENT ON ROLE demo_test IS 'atoms-standalone:test:REPLACE_WITH_TEST_INSTANCE_UUID';
REVOKE ALL ON DATABASE demo_test FROM PUBLIC;
GRANT CONNECT ON DATABASE demo_test TO demo_test;
\connect demo_test
REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM demo_test;
CREATE SCHEMA standalone_app AUTHORIZATION demo_test;
REVOKE ALL ON SCHEMA standalone_app FROM PUBLIC;
```

在 `test.properties` 填测试库 URL、`demo_test` 角色及独立密码。测试会重建这个专用库内的固定测试表，**不能存放业务数据**。配置文件的 `true` 标记不足以授权清理：测试还会核对数据库实际名称、角色、两处管理员标记、schema、权限和已存在的安装身份。不要并行运行共用同一个测试库的构建。

两种角色都不能成为数据库所有者，不能有 CREATE/TEMP 数据库权限、额外角色成员关系、对其他业务 schema 的访问权或超级权限。专用库不能含平台 `atoms` schema。运行库允许无数据库/角色备注；若使用用途备注，则两处都必须为同一合法 `atoms-standalone:runtime:<UUID v4>`。第一次运行会原子认领安装身份并迁移自己的表，后续启动必须沿用原配置 ID。

依据：[PostgreSQL 17 角色](https://www.postgresql.org/docs/17/sql-createrole.html)、[psql 的交互密码设置](https://www.postgresql.org/docs/17/app-psql.html)、[COMMENT](https://www.postgresql.org/docs/17/sql-comment.html)。备注不是秘密，不应包含密码。

## 检查与构建

在工程根目录执行，先确保 `JAVA_HOME` 指向 Java 17 且 PATH 使用上述 Node/npm：

ZIP 不保留 Unix 可执行位，解压后先为 Maven Wrapper 恢复执行权限（Linux/macOS）：

```sh
chmod u+x backend/mvnw
node --test scripts/*.test.mjs
STANDALONE_TEST_CONFIG=/absolute/path/test.properties node scripts/build.mjs
```

构建入口不接受额外参数，顺序执行 `npm ci --ignore-scripts`、typecheck、lint、test:unit、build，然后资源打包和 `./mvnw -B clean verify`。不跳过集成测试、不修改锁文件、不运行生成应用中的 shell。Node 不读取数据库密码；只有后端测试收到测试配置的位置。不会从进程环境继承模型 Key、运行数据库配置或 `MAVEN_ARGS` / `JAVA_TOOL_OPTIONS` / `NODE_OPTIONS` 等覆盖选项。

前端静态资源、应用文件和受控编译输入摘要共同决定 `loadedRuntimeId`，启动时重新校验实际资源字节。它标识运行内容，不是整个 JAR 的数字签名或供应链证明，也不等于来源版本的 `sourceContentHash`。构建时不要同时编辑源码。

输出为 `backend/target/app.jar`。验证会打开实际 JAR 校验资源及指纹，失败不能视为可发布。前端阶段失败时可能仍保留**上次**构建的 JAR，不能将其误当成本次产物。资源暂存使用系统临时目录中的新子目录，避免 Maven clean 删除输入；脚本不会递归删除操作者指定的目录，临时资源可由操作者定期清理。

单独在 backend 执行 `./mvnw -B clean verify` 可检查后端，但不会自动组装应用或 Vue，**不是成品构建命令**。缺测试配置时真实集成测试必须失败，不自动切换 H2。

## 启动与停止

```sh
node scripts/start.mjs --config /absolute/path/application.properties
```

或不使用 Node：

```sh
java -jar backend/target/app.jar --spring.config.location=classpath:/application.yml,file:/absolute/path/application.properties
```

打开配置中的 origin，例如 `http://127.0.0.1:8088/`。同一个端口提供页面和 API，不需要启动 Vite。start 只启动已有 JAR，不构建、不安装依赖、不自动转后台；配置路径不允许逗号、分号或控制字符。Java 入口也会拒绝缺失/占位配置、测试配置、非法实例 ID 和不安全数据库，不依赖 Node 的预检。

Ctrl-C / SIGTERM 会传给本次启动的进程组，最多等待 5 秒，然后清理仍未退出的自有进程；退出码/信号返回调用方。操作系统强制终止包装进程（SIGKILL）不能触发清理，应由服务管理器管理服务组。停止、重建、替换 JAR 不清空数据库；生产替换之前仍需自行备份数据库。第二个实例使用另一套配置、实例 ID、端口和数据库，不要复制已有库冒充新安装。

对外部署需自行配置 HTTPS 反向代理和明确的 HTTPS origin；这会启用 Secure Cookie，不会让 Spring 自动提供 TLS。本模板不自动开放公网端口、不修改防火墙、不继承平台的临时公网 HTTP 例外。遇到 `DATABASE_UNSAFE` / 安装身份冲突先核对配置与权限，不要通过删表修复。

## 修改应用与数据边界

- 修改 `application/view.html`、`styles.css`、`main.js`；应用源码合计上限 120 KiB。修改 `application/manifest.json` 中标题、摘要和 JSON 初始状态（最多 256 KiB），保持 runtime=`web-static-v1`、stateSchemaVersion=1。
- 可自行编辑 Vue 外壳与固定 Java 后端，再重新执行完整构建。前端打包仅接受 index.html、favicon.svg 和 assets 中的 JS/CSS，不接受 source map、私有配置、符号链接或任意其他资源；有新的资源类型需显式调整并测试打包策略。
- 工程默认只携带**初始状态**，不携带平台已保存的真实业务数据、账号、对话或模型凭据。修改初始值不会覆盖已保存数据。
- 每位访客的数据相互隔离；有效会话最长 7 天，可跨服务重启恢复。清除 Cookie、会话过期或换浏览器后，不提供账号找回或数据迁移。没有共享协作、业务权限管理或账户登录。
- 保存采用修订号和幂等键；请求超时不能断言没有保存。页面保留“结果未知”，只允许显式重试原意图。刷新/关闭页面会丢失内存里的重试意图，但不会撤销数据库中可能已经完成的保存。
- 生成应用仍不能直接联网、读取宿主 Cookie/DOM、安装依赖或导航宿主；不能靠关闭 iframe sandbox 解决兼容问题。幂等结果目前没有自动清理任务，长期运行需另行规划保留、容量和回收策略。

此工程不包含平台下载 API、托管构建执行器、实例调度或自动重新导入。改动后的工程不自动成为平台已经验证的新版本；也不承诺数据库迁移回滚。
