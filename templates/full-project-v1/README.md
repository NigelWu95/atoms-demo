# 原生 Vue + Java 应用工程

这是独立源码工程的初始化模板，不是已完成的用户业务。Vue 组件、Java API、SQL 迁移和工程自身测试均可继续修改；不使用平台 SDK、平台账号接口或 iframe 兼容层。初始页面只检查本应用的 HTTP 存活，不证明数据库和业务功能验收通过。

## 本机工具与边界

需要 macOS/Linux、Java 17、Node 24.20.x、npm 11.19.x，以及独立 PostgreSQL 数据库。`backend/mvnw` 固定 Maven 版本并校验下载摘要。首次构建可能下载 Maven/npm 依赖；先审阅锁文件、POM、构建插件、脚本及生成的业务代码。

这些脚本是源码拥有者在可信机器上使用的本地入口，不是平台任意代码执行沙箱。它们过滤传给子进程的环境变量，但同 UID 进程仍可能读取机器文件/工具用户配置；不要在含平台秘密的服务账号下执行不可信工程。脚本不调用模型、不自动发布、不管理平台任务状态。平台受控执行需要另行准入、期限、日志与进程管理。

## 检查与构建

从工程根目录执行：

```sh
node --test scripts/process.test.mjs scripts/build.test.mjs scripts/start.test.mjs
node scripts/build.mjs
```

含数据库业务测试的工程应显式传入工程目录之外、当前用户拥有且权限为 0600 的测试配置：

```sh
node scripts/build.mjs --test-config /absolute/private/test.properties
```

脚本仅向 Maven/Java 测试传递该文件路径，不向 npm 传递数据库配置或环境变量，不支持跳过测试。测试配置的用途与实际数据库由工程自身测试再次检查；不能用运行数据库代替独立测试库。无数据库的初始化 MVC 测试可以使用无参数构建。

若解压工具未保留执行位，先执行 `chmod u+x backend/mvnw`。配置样例见 `config/application.properties.example`，复制到工程外并设置 0600 权限后再填入运行值。

构建依次执行脚本测试、`npm ci --ignore-scripts`、前端类型/代码/组件检查与生产构建，再将前端产物复制到新的临时资源目录，执行 Maven `clean verify` 并打包 `backend/target/app.jar`。任一阶段失败即停止，不跳过测试。前端构建产物作为 JAR 的 `static/` 资源，不重写源文件。中间临时资源保留供诊断，不自动删除其他目录；只有确认进程结束后再由操作者清理。

只执行 `backend/mvnw verify` 不包含 Vue 产物，不能当作完整应用包。脚本行为测试使用注入 runner 和合成文件，不会安装依赖或启动 Java 应用；通过这些测试不等于整个工程已构建、启动或业务验收。当前模板的 Java MVC 和 Vue 检查有独立证据，完整脚本执行及数据库迁移仍待实际验收。

## 数据库和私有运行配置

每个应用使用独立数据库/非超级用户，管理员预先创建由该角色拥有的 `generated_app` schema。Flyway 只迁移应用自己的 SQL；模板不会创建数据库、角色或 schema，也不读取平台 PG 连接。不要指向平台数据库或已有业务库。

在工程目录之外保存仅当前用户可读的 `.properties` 文件（权限 0600），例如 `/absolute/private/runtime.properties`：

```properties
APP_PORT=8088
APP_DB_URL=jdbc:postgresql://127.0.0.1:5432/generated_app
APP_DB_USERNAME=generated_app
APP_DB_PASSWORD=REPLACE_WITH_DATABASE_PASSWORD
```

占位符必须由操作者替换；不要提交实际密码、运行配置或数据库备份。Properties 特殊字符需要正确转义。启动：

```sh
node scripts/start.mjs --config /absolute/private/runtime.properties
```

启动要求已存在的 JAR，不隐式安装或构建；以前台 Java 进程运行，只监听 `127.0.0.1`。访问 `http://127.0.0.1:8088/`，通过 Ctrl-C 停止本次进程组。需要网络访问时另行配置受保护的反向代理，初始模板没有登录授权，不应直接对公网开放。配置参数与 JAR 路径均按独立 argv 传递，不接受任意附加命令。

## 前端开发与源码边界

后端实际启动后，在 `frontend/` 执行 `npm ci --ignore-scripts`、`npm run dev`；开发地址 `http://127.0.0.1:5275/`。`FULL_PROJECT_DEV_API_PORT` 可指定本机后端端口，默认 8088；不支持任意远程代理目标。`npm run preview` 使用 4275，仅预览已构建前端。开发代理不是 JAR 启动验收。

源码包括 Vue/Java/SQL、锁文件、脚本、配置模板和测试；不包括 node_modules、target、dist、运行凭据、平台对话、账号或业务数据库。恢复代码版本不回滚业务数据。新增数据库迁移应使用新版本文件；构建成功也不代表数据迁移安全、完整业务正确或预览与下载已验收一致。

第三方依赖及许可证见 `THIRD-PARTY-NOTICES.md`。
