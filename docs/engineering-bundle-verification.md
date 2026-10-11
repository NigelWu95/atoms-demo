# engineering-bundle · 实施与验证记录

日期：2026-10-12。分支：`feature/standalone-engineering`。依据：[规格](../SPEC-engineering-bundle.md)、[计划](../tasks/plan.md)、[执行清单](../tasks/todo.md)。

## 当前交付边界

EB01–EB12 已实现并验证：纯内存工程组装、独立启动/数据库/安装身份、访客与持久会话、固定应用资源/内容指纹/受限运行文档，以及带真实健康检查的独立 Vue 启动外壳。当前交付 C-EB05，下一步 EB13–EB14 前端会话与沙箱接线。没有下载 HTTP API、工作台按钮、真实模板装载器、独立业务状态接口、完整工程构建执行器或托管实例，不宣称完整工程已可导出运行。本轮未改动线上服务、原平台生产源码/迁移或原交付文档；下文各节保留当时阶段的证据，不代表后续能力也已完成。

`BundleSource` 冻结来源 UUID、原内容 hash 和 Artifact；调用方必须先完成版本所有者授权。组装器再次调用原 `ArtifactValidator`，并且只读取传入的代码快照及固定模板快照；没有数据库、环境变量、网络、文件系统或进程操作。`TemplateSnapshot` 构造入口限定于 engineering 包，后续只有固定目录的 TemplateCatalog 提供真实模板。

## 工程格式 v1 的实现细节

- 模板标识固定 `vue-spring-postgres / 1.0.0`；应用写入固定的 `application/{manifest.json,view.html,styles.css,main.js}`。
- manifest 包含来源、`INITIAL_ONLY`、有序文件清单与 bundleId。清单列出路径、UTF-8/二进制字节数、SHA-256，不含 manifest 自己。
- 规范化规则：JSON 对象键按 Java String 自然顺序递归排序，数组顺序不变，Jackson 2.21.4 的紧凑 UTF-8 序列化；不是 RFC 8785 跨语言数字规范。`bundleId` 对尚未加入 bundleId 字段的完整清单计算 hash。将来更改编码语义须评审工程格式/模板版本，不能悄悄改变 v1。
- 所有 byte[] 在输入、输出做防御性复制；列表不可变；嵌套初始状态也复制。File 的 equals/hashCode 比较字节内容，避免记录类型复制后不相等。
- 配额计入最终 manifest：128 文件、单文件 2 MiB、总量 8 MiB。原源码 120 KiB/初始状态 256 KiB 校验没有修改。
- 模板逐文件白名单之外，再拒绝路径穿越、绝对/非便携路径、大小写冲突、目录被文件占位、构建缓存/真实配置/密钥文件及生成文件保留路径。

目前文件模型只有常规文件名与字节，不接受链接目标或可变文件类型元数据。真实磁盘模板的符号链接/祖先目录链接检测属于 EB20 的模板装载边界，尚未实现，不能把内存测试当成已验证磁盘读取。平台工作树从未被递归打包。

## 红绿与回归证据

环境：本地 Oracle Java 17.0.5、仓库 Maven Wrapper 3.9.11；本节仅记录本次执行。

| 检查 | 结果 |
|---|---|
| EB01 首轮测试 | 缺少新接口，编译失败；实现后 3 项通过 |
| 文件值语义 | 防御性复制后的 File 相等性测试先失败；按内容比较后通过 |
| EB02 首轮路径/配额测试 | 36 项中 33 项按预期失败，暴露尚未实现的路径/文件排除策略 |
| 保留文件的目录别名 | 新增 `bundle-manifest.json/extra` 反例先失败，修复后通过 |
| 工程定向测试 | `./mvnw -B -Dtest=EngineeringBundleTest,TemplatePolicyTest test`：8 + 38 = 46 项通过，无跳过 |
| 边界内容 | 单文件恰好 2 MiB、最终恰好 128 文件、含 manifest 恰好 8 MiB 可通过；多 1 字节/文件被拒绝 |
| 变异验证 | 暂时把单文件 `>` 上限判断反转为 `<`，边界用例因超限未被拒绝而失败；随后恢复原实现，进入完整回归 |
| 首次原后端单元回归 | 沙箱阻止本地端口及 Mockito JVM 附加，14 项环境错误，不能计为通过 |
| 环境问题定向复核 | 经权限流程在沙箱外重跑 HealthControllerTest + DeepSeekGatewayTest，14 项通过；未改旧测试/调用真实模型 |
| 原后端完整 verify | `./mvnw -B verify`：72 单元 + 50 真实 PostgreSQL 集成测试全部通过，无失败/跳过；JAR 构建成功，约 63 秒 |
| EB03 模板编译 | 模板后端执行 `./mvnw -B compile`，成功；javap 显示 class major version=61，即 Java 17 |
| EB03 测试质量门槛 | 模板 `./mvnw -B test` 因尚无测试明确退出 1（No tests to run）；这是预期门槛验证，不是测试通过 |
| EB03 生效构建配置 | `./mvnw -B help:effective-pom -Doutput=target/effective-pom.xml` 成功；Java release=17，Surefire/Failsafe 均 failIfNoTests=true，finalName=app；生效 POM 仅为忽略的构建产物 |
| Wrapper 与配置 | Wrapper 和平台已审核的 3.3.4 脚本逐字节一致，Maven 固定 3.9.11；sh -n 通过、保留可执行权限；模板配置无平台库名/角色、模型 Key 或 .env.local 导入 |

以上 `DeepSeekGatewayTest` 使用本地模拟上游，不消耗真实模型额度。集成回归使用已有 `atoms_demo_test`，不迁移运行库或云端库。

## 评审与保留项

正确性：清单及 bundleId 由测试独立复算；输入/输出修改不污染快照；非法历史源码明确失败。架构：仅新增内部包，无公开路由和 Spring 自动扫描组件，不改变旧主流程。安全：模型字段只进入固定数据文件，无 shell/文件路径插值；错误不包含被拒绝的路径和文件内容。资源：输入模板及最终输出均有限额；没有无上限的磁盘扫描。

EB01 提前引入 TemplateSnapshot（仍在 5 个主要文件内），因为不可变模板是纯组装契约的一部分。EB01/EB02 作为同一个有限、安全的组装切片验收，不让下游接触未设限入口。真实模板白名单、物理链接校验、独立工程数据库/浏览器/重启验收均保留为未完成项。

EB03 模板是未公开的编译骨架：只扫描 `dev.atoms.standalone`，沿用最小所需后端依赖，不依赖平台工程；默认 loopback:8088、独立 schema、显式实例 ID，禁止自动 SQL 初始化和 Flyway clean。EB04 已接通配置文件与 Java 入口校验（含本地 HTTP Cookie 切换）；会话与业务数据接口仍在后续任务，因此没有尝试把骨架称为可用服务。真正导出目录的构建和重启验收仍需 EB18–EB23。

原前端源码未变，本轮尚未重跑其 UI/E2E；不复用旧结果冒充本轮通过。没有推送、合并或部署。

## EB04 · 显式配置与 Java 启动护栏

EB04a 已完成：Node 初始化器要求绝对 `.properties` 路径，使用 `wx` 排他创建与 `0600` 权限。两个进程同时初始化只有一个成功；已有文件、正常/断开的符号链接都不会被覆盖。每个新安装生成一次 UUID，重复执行不会重置。数据库三项均保留明确占位符，不读取平台环境，不生成真实数据库密码，也不输出路径或内容。父目录须由操作者预先准备。

- RED：先添加 5 项真实 CLI 测试，初始化脚本不存在，5 项全部失败。
- GREEN：模板根执行 `node --test scripts/init-config.test.mjs`，5 项通过、0 跳过。

EB04b 在 `StandaloneApplication.main` 调用 Spring **之前**读取并校验配置，早于 ConfigData 处理、日志配置、DataSource 与 Flyway 初始化。仅接受一个完整的 `--spring.config.location=classpath:/application.yml,file:/绝对路径/application.properties` 参数；文件必须是至多 16 KiB 的常规 `.properties`，拒绝文件链接、非法 Unicode 转义与额外配置键。错误仅含稳定代码和已知字段名，不保留包含路径/值的底层异常 cause。

这不是开放的 Spring 配置透传接口：读取文件后，构造只含已验证值的 Environment，移除系统属性与进程环境 PropertySource；Spring 只再加载包内 `application.yml` 的固定默认值。不会因同机平台的 `SPRING_*`、数据库环境变量或工作目录配置而换库。配置中的 import、任意 Spring 覆盖项和 `standalone.test-database=true` 均被拒绝。这个更窄的入口符合显式独立配置契约，但修改未开放的框架参数需要修改模板源码，不支持随意追加 `--server.*` 或 `-Dspring.*`。

配置接受单主机 PostgreSQL JDBC URL（无查询参数/内嵌凭据），数据库/角色名限定 ASCII 标识符；拒绝缺值、示例占位符、常见默认凭据、非 UUID v4 安装 ID、非法端口/origin。HTTP 仅接受与监听地址和端口匹配的 `127.0.0.1` 或 `::1`；公网 origin 必须 HTTPS，允许本地/通配监听以供 TLS 反向代理。Cookie 名固定为 `STANDALONE_<安装ID>`，HTTPS 为 Secure，本地 HTTP 为非 Secure；HttpOnly、SameSite=Lax 和 7 天有效期来自固定配置。填写 HTTPS origin **不会自动配置 TLS 或开放防火墙**。

| EB04 检查 | 结果 |
|---|---|
| Java RED | 测试先于 StartupConfig 添加，修正测试中 BindResult API 的 Supplier 用法后，因缺少实现类型明确编译失败 |
| 首轮 Java 实现 | 57 项中 1 项失败：无 scheme 的 origin 触发 Set.contains(null)；改为 null 安全的 scheme 判断，没有降低断言 |
| Java 全部单元测试 | `./mvnw -B test`：61 项通过，无跳过；包含端口 1/65535、文件恰好 16 KiB/多 1 字节、配置中 Unicode 密码不变、错误脱敏、真实子进程非零退出 |
| Spring 实际绑定 | 无自动配置/数据库 Bean 的小型真实 Spring 上下文中绑定 DataSourceProperties、ServerProperties，验证固定默认值、Cookie 属性及系统属性不能覆盖连接 |
| 变异验证 | 将 HTTP loopback 校验反转，39 个反例中 5 项失败；恢复后重新构建，全部 61 项通过 |
| 模板 JAR 构建 | `./mvnw -B package` 成功，运行了全部 61 项单元测试；没有跳测试参数；这不是含数据库 IT 的 `verify` 验收 |
| 直接 JAR 冒烟 | 用 Node 初始化的无真实凭据配置运行 `java -jar`：缺参数、占位配置两项均退出 1，分别为 CONFIG_USAGE / CONFIG_INVALID，在 Spring 启动前拒绝 |
| 平台组装定向回归 | `./mvnw -B -Dtest=EngineeringBundleTest,TemplatePolicyTest test`：46 项通过，无跳过 |
| Node/Java 路径对齐 | EB04c 新增逗号/分号路径反例先失败；初始化器与 Java 都拒绝配置列表分隔符，最终 5 项 CLI 测试全部通过 |

EB04 评审：正确性覆盖拒绝路径和实际 Spring 绑定；架构中不复用平台包、配置和数据库；配置读取有大小上限且不做 DNS/网络连接；测试只用明确的假凭据。没有新增依赖、修改质量门槛或引入校验跳过选项。脚本初始化时的排他创建不等于跨任意网络文件系统的锁，也不承诺抵抗有权限改写上级目录的本机攻击者。

本轮没有创建数据库，没有调用模型、修改平台运行配置、推送或部署。**这些配置检查不是数据库隔离证明**：实际角色权限、schema、安装 ID、测试/运行库误用校验仍需 EB05/EB06，后续会话 API/CSRF/浏览器流程也尚未实现。模板完整 `verify` 与独立应用构建/保存/重启验收保持未完成；原平台完整集成/UI 测试未在本轮重跑。

## EB05 · 专用测试数据库与测试引导

本节记录 EB05 本轮结果，不把前文 EB04 的阶段性限制或历史回归当成本轮结论。

### 本地准备与使用

管理员脚本仅在仓库开发环境使用，不进入导出工程。经权限流程在现有 `atoms-demo-postgres` 容器（PostgreSQL 17.11，loopback:5432）新增以下资源；没有改平台数据库、已有应用角色或云端服务：

| 用途 | 数据库及受限角色 | 私有配置 | 预留应用端口 |
|---|---|---|---|
| Java 集成测试 | `atoms_standalone_it` | `.local/standalone/test.properties` | 18088 |
| 后续浏览器实例 A | `atoms_standalone_a` | `.local/standalone/runtime-a.properties` | 18089 |
| 后续浏览器实例 B | `atoms_standalone_b` | `.local/standalone/runtime-b.properties` | 18090 |

配置和 `provisioning.json` 均为 0600，目录为 0700，已被 Git 忽略。三套密码和 UUID 分别生成且互不相同；重复执行不会重置或覆盖。端口只是配置预留，目前没有三个独立应用服务在运行。不要打印或提交这些配置，也不要复制到工程导出包。

仓库根初始化命令（必须明确容器、管理员和本地映射端口；不读取平台 `.env.local`）：

```sh
node scripts/provision-standalone-db.mjs \
  --container atoms-demo-postgres --admin-user postgres --port 5432 \
  --config-dir .local/standalone
```

模板后端目录使用 Java 17 执行完整验证，`STANDALONE_TEST_CONFIG` 必须是绝对路径：

```sh
env JAVA_HOME=/Users/wubingheng/.sdkman/candidates/java/17.0.5-oracle \
  STANDALONE_TEST_CONFIG=/Users/wubingheng/atoms-demo/.local/standalone/test.properties \
  ./mvnw -B clean verify
```

以上路径是本机复现示例，其他安装应替换为自己的绝对路径。单元测试可以单独运行 `./mvnw -B test`；完整 `verify` 未提供专用配置会失败，不会自动跳过 IT 或使用平台连接兜底。

### 数据库与测试边界

管理员拥有数据库，应用角色仅拥有自己的 `standalone_app` schema；新库撤销 PUBLIC 的数据库权限及 public schema 权限，仅授予对应角色 CONNECT。角色没有超级用户、建库、建角色、复制、绕过 RLS 权限或角色成员关系。固定 SQL 标识符经过校验，SQL 从 stdin 传给固定 `docker exec … psql` 参数，不经过 shell；密码只保存到私有配置，SQL 使用 SCRAM verifier，不携带明文密码。错误不回显 SQL、底层 stderr 或配置内容。

脚本先检查既有角色/数据库的管理员标记，再创建缺失对象；不删除数据库、不重置密码。私有文件锁拒绝同目录并发，数据库 advisory lock 保护初始对象准备。`CREATE DATABASE` 不能与后续 COMMENT 放入同一事务，所以它不是原子部署：中断后若遗留未标记对象或锁文件，脚本会拒绝接管，必须先人工确认，不会自动删库或抢锁。不要删除 `provisioning.json` 来“修复”失败，它保存重试需要的稳定身份与凭据。

`TestDatabase` 仅位于测试类路径。先要求显式测试配置与 `standalone.test-database=true`，共享正式入口的凭据/origin 校验，再用有连接/语句超时的只读事务查询 PostgreSQL 系统目录。它核对实际库名、实际角色、角色/数据库标记、权限、schema 归属及服务器确认的只读状态，拒绝平台 `atoms` schema 或可访问的其他业务 schema。校验通过才加载固定测试 YAML、允许 Spring 上下文刷新；正式 Java 入口仍拒绝测试配置，没有新增生产绕过开关。

EB05 校验的是准备好的测试数据库与连接身份，**不是 EB06 的运行库安装身份/并发认领证明**。当前 IT 不运行 Flyway、不建立业务/会话表，也不启动完整 Web 服务。运行 A/B 配置拒绝和不安全身份组合已做单元测试；真实 IT 验证专用库及错误 UUID 拒绝，没有对平台运行库实施负面写入试验。数据库管理员后续变更权限的竞态，以及正式实例迁移前的身份锁定仍由 EB06 处理。

### 红绿、回归与产物检查

| 检查 | 本轮实际结果 |
|---|---|
| 建库脚本 RED → GREEN | 先因缺少实现模块失败；实现后 6 项通过，覆盖显式参数、私有文件、稳定身份、错误脱敏、拒绝覆盖/链接及并发 |
| 实际本地准备 | 创建三套库/角色成功；再次运行成功，4 个私有文件逐字节不变，没有重置密码 |
| Java RED → GREEN | 新测试先因缺少 TestDatabase/共享解析入口编译失败；实现后 StartupConfig 62 项 + TestDatabase 24 项全部通过 |
| 关键判断变异 | 暂时反转 safeRole 判断，9 项反例中 1 项失败；恢复后 86 项全部通过 |
| 测试分层修复 | 最初 `TestDatabaseIT` 同时匹配 Surefire 的 Test 前缀和 Failsafe 的 IT 后缀，导致重复运行；重命名为 `DedicatedDatabaseIT`，没有跳过用例或放宽发现规则 |
| 无测试配置的完整构建 | 86 项单元通过；Failsafe 因 TEST_DATABASE_CONFIG 退出 1，按预期拒绝“未验库也通过” |
| 最终模板完整构建 | `clean verify`：86 项单元 + 6 项真实 PostgreSQL IT，0 失败/跳过，JAR 构建成功；两组各运行一次 |
| 实际数据库 IT | SCRAM 凭据认证、重复只读检查不新增表、错误 UUID 拒绝、连接失败脱敏、上下文刷新前拦截及正式入口拒绝真实测试配置 |
| 脚本组合回归 | 建库脚本 6 + 原发布脚本 3 + 模板配置初始化 5 = 14 项全部通过，无跳过 |
| 平台工程组装回归 | EngineeringBundleTest 8 + TemplatePolicyTest 38 = 46 项通过，无跳过 |
| JAR 检查 | 包含正式 StartupConfig，不含 TestDatabase、DedicatedDatabaseIT、application-test.yml 或私有 properties 配置 |

EB05 评审未发现需阻塞本切片交付的问题：没有新增依赖、平台公开接口、生产测试开关或质量门槛削弱；实际连接失败保留稳定错误语义。脚本假 SQL 单元测试不冒充真实权限验证，真实数据库证据单独列出。原平台完整 IT、UI/E2E 未在本轮重跑；没有真实模型调用、GitHub 推送、合并或部署。原 README/交付/部署文档继续保持不变，待整项功能实现后统一更新。

## EB06 · 安装身份、迁移与健康检查

### 实现与关键取舍

`DatabaseTarget` 统一测试入口与正式迁移入口的只读系统目录核验：检查实际库/角色、权限、schema 归属及用途标记，不只相信配置字符串。测试库必须有一致的管理员测试标记；正式运行拒绝测试标记，也接受操作者自行准备、权限合规且没有开发用途标记的数据库。安装记录另外校验实例 UUID，不能以准备脚本的标记代替安装身份。

`DatabaseInstallation` 作为 FlywayMigrationStrategy，先检查实际目标，再取得 PostgreSQL 会话级 advisory lock，重新核验目标和已有安装记录后才调用 Flyway。锁覆盖 Flyway 的独立连接/事务；返回连接池前释放。首次只接受完全空的独立 schema；V001 在迁移事务中写入唯一安装记录并创建访客及 JDBC Session 表。重复启动核对原身份，不重置记录；不同实例 ID 不能接管同一安装。真实并发测试分别覆盖相同 ID 两者成功、不同 ID 恰好一个成功。

测试引导也复用安装核验，避免错误安装 ID 在测试清理时被抹除。只有核验通过的专用 IT 库允许清理固定 5 张夹具表，没有 CASCADE、删库或平台连接兜底。负面测试通过已核验连接恢复其自身引入的 UUID/夹具；正式代码没有修复、接管或重置接口。

`GET /api/health` 检查数据库中的安装身份，正常返回 `200 {"status":"UP"}`；连接池故障或身份异常返回脱敏的 503，含稳定 `SERVICE_UNAVAILABLE`、retryable 和 requestId。响应不创建 Cookie，含 no-store 与安全头。除 GET health 外当前所有入口默认拒绝，访客 API 尚未实现。

单纯设置 STATELESS 仍可能让 Spring Session 在安全过滤器读取现有 Cookie 时先查询数据库。新增真实 Cookie + 连接池故障测试捕获了提前 500；最终通过 DefaultCookieSerializer 仅忽略精确 GET health 的会话读取。其他路径保留常规 Cookie 解析，名称、HttpOnly、SameSite、有效期与 HTTP/HTTPS Secure 设置继续来自已校验配置。没有全局关闭会话、CSRF 或匿名过滤器来绕过问题。

启动/health 使用结构化日志，记录 event、entryPoint、requestId 及成功耗时/health 状态；失败启动记录稳定错误码，不输出底层数据库异常、SQL 或凭据。Hikari/Flyway 原始日志关闭，保留应用自己的脱敏事件。监控面板、指标采集、链路追踪和告警尚未实现，没有为此新增依赖。

### 本轮验证证据

环境沿用 Oracle Java 17.0.5、Boot 3.5.16、Flyway 11.7.2、Spring Session 3.5.7 和 PostgreSQL 17.11，未升级依赖。

| 检查 | 实际结果 |
|---|---|
| 数据库目标/迁移 RED → GREEN | 新类型缺失时编译失败；实现后真实目录核验、首次迁移、重复迁移与并发用例通过 |
| 首轮 IT 配置修正 | Flyway 不支持日志选项 none，首轮失败；按锁定 API 改为 slf4j 后通过，没有跳过测试 |
| health RED → GREEN | 初始 5 项失败，暴露 health 未开放、默认登录入口及故障错误响应；实现最小安全链与控制器后通过 |
| 清理前安装校验 | 新反例先失败：原测试引导未拒绝已存在的错误安装 ID；共享正式身份校验后通过，错误记录及迁移历史保持不变 |
| 迁移前拒绝证明 | 不匹配实例 ID 时 BEFORE_MIGRATE 回调未触发；相同回调在合法实例重复迁移时触发，避免无效探针产生假绿 |
| Cookie + 数据库故障 | 初始实际 HTTP 返回 500；尝试调整匿名认证/自定义 resolver 未通过，已撤回。最终仅 health 免读 Cookie 后返回 503，无 Set-Cookie，其他路径 Cookie 行为断言通过 |
| 关键判断变异 | 暂时将 !safeRole 反转为 safeRole，8 项目标拒绝反例中 1 项失败；恢复后重新完整 verify，通过 |
| 最终模板 clean verify | StartupConfig 62 + TestDatabase 24 + DatabaseTarget 10 = **96 单元**；DatabaseInstallation 10 + DedicatedDatabase 6 + Health 7 = **23 真实 PostgreSQL/HTTP IT**；0 失败/跳过，JAR 构建成功，约 21 秒 |
| HTTP 故障注入范围 | 关闭单个测试上下文的连接池；专用 PostgreSQL 仍可连接，未停止共享数据库或平台服务 |
| 重复启动数据保持 | 实际 Spring Web 上下文关闭后重新启动，安装记录、插入的访客/会话行仍存在，health 200；不是已完成访客登录 Cookie 的跨进程验收 |
| 平台定向回归 | EngineeringBundleTest 8 + TemplatePolicyTest 38 = **46 项**通过，无跳过 |
| 脚本组合回归 | 建库脚本 6 + 原发布脚本 3 + 配置初始化 5 = **14 项**通过，无跳过 |
| JAR 边界 | 包含 DatabaseInstallation、HealthController 与 V001；不含 TestDatabase、迁移/health IT、application-test.yml 或 .local 私有配置 |
| 实际 JAR 启停 | 首次沙箱内连接失败，返回脱敏 DATABASE_UNAVAILABLE；经权限流程重跑后，专用运行库 A 的首次启动和同配置新进程重启均成功，GET health 两次 200。两次均只停止本次创建的 Java 进程，未停止 PostgreSQL/平台服务 |

复现模板完整验证仍使用 EB05 中的 Java 17 + 显式 `STANDALONE_TEST_CONFIG` 命令。平台定向命令在平台 backend 执行 `./mvnw -B -Dtest=EngineeringBundleTest,TemplatePolicyTest test`；脚本组合在仓库根执行：

```sh
node --test scripts/provision-standalone-db.test.mjs scripts/release.test.mjs templates/standalone-v1/scripts/init-config.test.mjs
```

实际 JAR 冒烟在模板 backend 目录执行下列命令；仅使用 `.local/standalone/runtime-a.properties`，监听 `127.0.0.1:18089`、连接 `atoms_standalone_a`。此库已建立安装/访客/会话表，验收后保留，不删除数据库。运行库 B 未使用。验证进程均已停止；尚未通过新目录导出工程构建。

```sh
/Users/wubingheng/.sdkman/candidates/java/17.0.5-oracle/bin/java -jar target/app.jar \
  --spring.config.location=classpath:/application.yml,file:/Users/wubingheng/atoms-demo/.local/standalone/runtime-a.properties
curl --noproxy '*' --fail --max-time 5 -i http://127.0.0.1:18089/api/health
```

### 审阅与未完成项

本轮代码审阅核对迁移顺序、锁释放、参数绑定、错误脱敏、配置隔离、测试清理边界及 Cookie 行为，未新增依赖或削弱质量门槛。C-EB02 的人工审阅仍待完成，不将测试通过或代理自审视为人工批准。

- 数据库锁协调遵循该协议的启动实例；不承诺抵抗拥有数据库管理权限的操作者修改权限、对象或连接。
- 首次迁移若在 Flyway 建立历史表后、V001 成功前中断，可能留下未认领的非空 schema。后续启动会拒绝并要求人工核查，不自动清空/接管；这是保数据优先的取舍。
- health 只表示数据库与安装身份就绪，不表示应用源码、浏览器或业务保存流程已完成。
- 访客/会话表已建立，但访客初始化、CSRF 流程、统一 API 错误、应用资源与独立状态接口仍在后续任务。
- 原平台完整 IT、UI/E2E 未在本轮重跑；没有模型调用、GitHub 推送、主分支合并或云端部署。原 README/交付/部署文档继续等整项功能完成后统一更新。

## EB07–EB08 · 独立 API 与持久访客

### 当前接口契约与取舍

| 接口 | 当前行为 |
|---|---|
| GET /api/health | 保持 200/503 健康语义，不读取会话 Cookie，不创建访客 |
| GET /api/csrf | 返回 `{ token, headerName: "X-CSRF-TOKEN" }`；匿名也可用，令牌存于独立 JDBC 会话，JSON 返回掩码令牌；客户端仅保存在内存 |
| POST /api/session | 仅接受 JSON 空对象及有效 CSRF 请求头；首次返回 `{ userId, isGuest: true }` 并轮换会话 ID/CSRF；再次初始化返回原身份，不新建访客 |
| GET /api/app、/api/app/document、/api/state；PUT /api/state | 已建立鉴权边界，但业务实现仍属 EB09/EB10/EB15；未认证读请求 401，不能把鉴权通过后的 404 说成接口完成 |

客户端先 GET csrf，再 POST session；首次认证后必须再次 GET csrf，旧令牌和旧 Cookie 均失效。Cookie 为实例专属名称、HttpOnly、SameSite=Lax、Path=/、7 天 max-age；JDBC Session 的空闲有效期 7 天。本地回环 HTTP 为非 Secure，HTTPS 配置为 Secure。用户名/密码、OAuth、找回访客、账号合并不在此模块范围。身份只来自持久化的服务端认证上下文，不能由请求体选择其他 UUID。

错误统一为 `{ error: { code, message, retryable }, requestId }`，响应头含同一 X-Request-Id、no-store。错误消息来自固定枚举，不透传异常 message/cause。未认证 401、CSRF/来源拒绝 403、未知 API 404、输入 422、请求体超限 413、冲突类 409、内部错误 500、数据库不可用 503；409 当前只完成公共错误映射，实际状态冲突和幂等逻辑仍待 EB15–EB16。5xx 的 retryable 不表示事务肯定回滚，未来状态客户端仍须遵守原意图重试契约。

请求入口先于 JDBC Session 和安全链运行：总字节上限 320 KiB，包含分块传输；JSON 容器深度最多 64，拒绝重复键、尾随文档、非 JSON/非 UTF-8 声明及压缩请求。状态值自身的 256 KiB 限制仍由后续业务接口实施，不能用入口余量替代。固定 API 路径白名单之外返回 JSON 404，不转成页面；未来新增 API 必须显式更新契约。

Origin 存在时必须唯一且精确匹配外部配置，拒绝 `null`/外站及 Sec-Fetch-Site=cross-site，不启用 CORS。无 Origin 的命令行客户端仍可使用，但写入同样需要 Cookie + CSRF。CSRF 仅从请求头读取，不接受 URL 参数；保留 Spring 的掩码/BREACH 保护。安全头在所有早期拒绝之前写入，目前严格 CSP 不开放宿主脚本；Vue 外壳允许同源资源的策略会随 EB11–EB14 实际页面落地。

有限同步 API 响应先缓冲，待 Spring Session 的请求结束保存完成后才发送，避免数据库提交会话失败时客户端已收到 200。数据库异常只输出脱敏错误；MVC 在错误响应再次触发会话保存失败时的重复原始堆栈日志关闭，应用自己的 requestId/错误码日志保留。此缓冲策略不适用于 SSE、异步或无限流，这些不在独立应用接口契约中。

### 安全与可观测性审阅

边界为不可信 HTTP/JSON/Cookie → CSRF/认证 → 独立库。保护资产为实例会话、访客隔离及后续业务状态；使用固定字段、绑定 SQL、服务端随机身份、Cookie/CSRF 轮换和显式授权，无新依赖/平台会话复用。匿名 UUID 不写日志，不采集姓名/邮箱/密码。失效会话由 Spring Session 清理；访客记录/未来业务数据的删除与保留策略、公开服务的频率限制尚未实现，不能据此声称已经满足生产隐私或抗滥用要求。

可诊断问题：请求为何被拒绝、故障在会话加载还是健康检查入口、初始化是否走到身份建立阶段。`api_rejected`、`guest_initialized`、已有 `health_checked` 用稳定事件/入口/requestId 关联，不记录请求体、Cookie、CSRF 或异常 SQL。guest_initialized 表示准备好身份，不冒充最终响应成功；最终会话保存故障另有 api_rejected。尚无指标面板、分布式追踪或告警采集器，不新增未批准的监控依赖。

创建访客与请求结束保存会话不在同一个事务中；连接中断可能遗留不可访问的匿名访客记录。当前保证不发送假成功、有效会话恢复和跨访客隔离，不承诺失败初始化恰好一次、跨进程并发初始化同一未认证 Cookie 的合并或孤儿记录自动清理。后续业务写入的原子性由 EB15–EB16 的事务协议承担。

### 本轮红绿与真实运行证据

| 检查 | 实际结果 |
|---|---|
| EB07a | 缺少错误类型时编译失败；实现后新增 14 单元通过，health 回归保持通过 |
| EB07b | 6 项真实 HTTP 反例最初全部失败；实现入口约束后通过。专用测试 profile 未装配故障控制器、Accept=text/html 引发媒体协商的测试问题已定位，改为单测试显式注册夹具，不加入生产测试入口 |
| EB08a | 7 项访客用例先失败；实现后验证两访客、空对象校验、轮换/旧凭据拒绝、过期会话及 Spring 上下文重启；另补 JDBC Session 事务异常中 SQLException 的 503 映射 |
| EB08b | URL 参数中的 CSRF 原先导致 200，新反例失败；仅请求头策略后 403。错误 Origin、跨站标记均在会话处理前拒绝 |
| 关键判断变异 | 暂时反转 Origin 相等判断，2 项安全测试均失败；恢复后完整回归通过 |
| 会话最终保存故障 | 真实请求中读取会话后关闭该测试上下文连接池，返回 503，成功响应内容没有发出；共享 PostgreSQL 仍运行 |
| 日志脱敏回归 | 新断言发现 MVC 异常解析器额外输出数据库堆栈，先失败；关闭该条重复框架日志后通过，503 错误码与 requestId 日志仍可定位 |
| 最终模板完整 verify | **112 单元 + 39 真实 PostgreSQL/HTTP IT**，0 失败/跳过，JAR 构建成功；SessionIT 为 10 项 |
| 原平台工程组装 | **46 项**通过；原后端源码与平台数据库迁移未改动 |
| 脚本组合回归 | **14 项**通过；建库、原发布打包及初始化脚本无回归 |
| 正式 JAR 内容 | 包含访客、会话、请求边界和安全头组件；不含 SessionIT、ApiBoundaryIT、测试引导、application-test.yml 或私有配置 |
| 两个实际 JAR 实例 | A/B 分别连接 atoms_standalone_a / atoms_standalone_b，Cookie 名及访客不同；A Cookie 原样或改成 B Cookie 名均在 B 被拒绝 |
| 真实 OS 进程重启 | 停止本次 A 进程后用原配置重启，保留的 Cookie 与 CSRF 仍返回同一访客；B 不受影响。全部本次创建的进程已停止，两个专用运行库与夹具访客保留 |

模板与平台命令沿用 EB05/EB06 记录。真实双实例辅助脚本是本机已忽略的 `.local/standalone/verify-session-runtime.mjs`，仅供这次验收，配置仍为同目录 runtime-a/b.properties。脚本使用 Java 17 与正式 JAR，拒绝复用占用端口，只停止自身启动的进程，不输出/保存 Cookie、CSRF 或数据库凭据。这不是可移植的最终工程验收脚本；EB22 会交付仓库内正式验收装配。

本轮没有修改旧 README/提交说明/部署文档，没有真实模型调用、GitHub 推送、合并或云端部署。没有 UI 改动，未把 HTTP Cookie jar 验收称为浏览器验收；原平台完整 IT/UI、独立应用保存状态及全新目录构建仍未在此阶段完成。

## EB09–EB10 · 应用资源与受限文档

### 接口与实现边界

新增两个已认证访客接口，仅服务安装的一个应用，不接受项目、版本或文件选择：

| 接口 | 成功响应 |
|---|---|
| `GET /api/app` | `{ title, summary, runtime: "web-static-v1", stateSchemaVersion: 1, loadedRuntimeId }` |
| `GET /api/app/document` | UTF-8 HTML、no-store；仅成功文档使用专门 sandbox CSP 与 SAMEORIGIN |

描述不返回会话、配置、初始状态或历史来源信息。初始状态只保存在不可变加载模型中，未来通过每访客状态接口初始化。请求仍有认证、访客存在性校验与原有统一脱敏错误；错误响应不能获得文档的 iframe/inline 权限。

ApplicationArtifactLoader 只解析 `application/manifest.json`、`view.html`、`styles.css`、`main.js`。源码合计 120 KiB，初始 JSON 对象 256 KiB，manifest 输入最多 272 KiB（为描述字段及编码留余量），容器深度 64；拒绝缺文件、额外文件、错误字段/类型、重复键、尾随 JSON、非法 UTF-8、源码 NUL、超限和危险 HTML。HTML 使用与平台完全相同的 jsoup 白名单，校验后再净化序列化。CSS/JS 以转义 `< > & U+2028 U+2029` 的 JSON 数据进入受控文档，不作为 HTML 模板片段拼接。

打包器只读四个应用文件及 `runtime/bootstrap.js`、`runtime/document.html`；校验常规文件、符号链接、字节上限和 UTF-8，创建全新的临时输出目录，不覆盖已有工程，不执行生成源码。完整应用语义由 Java 加载器校验，不能把 Node 字节打包成功当成产物合法。构建假设项目树不会被另一个用户并发替换；O_NOFOLLOW 与有界描述符读取不宣称解决可写祖先目录的所有竞态。

内容摘要格式：SHA-256，域前缀 `atoms-runtime-content-v1\0`，随后按固定路径排序连接 `path + NUL + 十进制字节数 + NUL + 原始字节`。打包写入 `runtime/content.sha256`，Java 加载同一批实际字节并核对该值；摘要不包含时间、绝对路径、部署身份或数据库密码。`sourceContentHash` 仍是 bundle manifest 的历史来源，不参与冒充修改后应用身份。**当前指纹覆盖六个资源，不是整个工程或整个 JAR 的 hash**；EB18 接入真实 Vue 产物及受控运行层完整构建输入时，必须扩展并冻结最终身份格式，补充“只改宿主/策略代码也改变运行身份”的验收后才能用于正式状态写入。

TestApplication 仅在 test classpath：显式注入固定计数器，使用已验证的专用测试库，不提供 HTTP 夹具创建路由。生产 main 在 Spring/数据库启动之前读取实际打包资源，缺文件或指纹不符直接失败，不回退到测试数据。目前直接对模板执行 Maven 产生的是尚未嵌入应用的 JAR，不能作为完整导出成品；应用/前端与 Maven 的正式构建接线仍是 EB18 的工作。

### 安全取舍与实际排错

不可信文件/模型源码只能进入固定数据位置；受控运行层是可信构建代码，不由模型覆盖。浏览器中的生成代码仍为 opaque origin，保留 `sandbox allow-scripts`、禁止联网、worker、嵌套 frame、表单及宿主导航的策略，没有 allow-same-origin、unsafe-eval、外部脚本或 wildcard CORS。bootstrap 和 HTML 文档与原平台逐字节一致，继续使用 Atoms.ready/storage 与原有超时、未知保存结果、关闭清理协议。

新增 RuntimeDocumentPolicy 在 JDBC Session 最终提交之后、缓冲响应发出之前判断方法/路径/200/HTML/准确 CSP；只有全部匹配才允许嵌入，其余返回严格头。这比只在 MVC 返回时检查更晚，可以覆盖最终会话提交变成 503 的情况；没有关闭 Spring Security 整套 headers。

首版使用 ResponseEntity.header 追加文档 CSP，真实 HTTP 测试仍收到 DENY。检查 Spring 6.2.19 ServletServerHttpResponse 的 addHeader 行为及平台已有实现后，改为 response.setHeader 替换默认 CSP；两条 CSP 会取交集，单纯追加并不能开放文档。已加唯一 CSP、普通 API 不放宽及最终保存故障恢复严格策略的回归。代码评审还反转了 `status == 200` 条件，策略测试明确失败，恢复后完整验证通过。

### 本轮验证与复现

| 检查 | 结果 |
|---|---|
| 资源解析/打包红绿 | 新类/模块缺失时先失败，实现后通过；Java 与 Node 摘要向量相同，逐文件修改及失配拒绝通过 |
| 文档 HTTP 红绿 | 已认证 app/document 最初 404，两项失败；接线后通过，未认证保持 401 |
| 模板 `clean verify` | **121 单元 + 44 PostgreSQL/HTTP IT**，0 失败/跳过，JAR 构建成功，最终执行约 62 秒 |
| 仓库 Node 组合 | **20 项**通过（新打包 4、一致性 2、既有脚本 14） |
| 原平台组装回归 | **46 项**通过；未修改原平台生产实现 |
| 原 SDK 单元 | **4 项**通过，覆盖来源握手、超时/关闭、队列与未知结果同意图重试 |
| 独立文档真实 Chrome | **2 项**通过，约 24.5 秒；iframe 可执行、点击/空格计数、握手、opaque origin、直接访问沙箱及未认证拒绝 |
| 前端检查 | 仓库 typecheck、新增浏览器测试/config 的 ESLint 与 Prettier 通过 |
| JAR 排除 | 包含生产加载器/文档服务和受控 runtime，不含 TestApplication、资源测试、RuntimeDocumentIT 或测试应用文件 |

浏览器测试使用隔离 Chrome，真实会话/文档请求连接模板后端。**宿主页面和 STATE_GET/SAVE 回复是浏览器内协议夹具，只证明 SDK 通道可用，不证明数据库保存或产品 Vue 外壳完成**。没有 trace/Cookie/CSRF 落盘；截图只包含测试计数器。reuseExistingServer=false，仅关闭测试创建的进程；完成后 18088 没有监听，专用测试库保留，平台服务未停用。首次定向 HTTP/浏览器启动受沙箱访问限制，按权限流程重试后通过，未修改数据库护栏绕过限制。

模板后端执行（Java 17 已显式配置）：

```sh
STANDALONE_TEST_CONFIG=/absolute/path/test.properties ./mvnw -B clean verify
```

仓库根执行：

```sh
node --test scripts/standalone-runtime-parity.test.mjs scripts/provision-standalone-db.test.mjs scripts/release.test.mjs templates/standalone-v1/scripts/init-config.test.mjs templates/standalone-v1/scripts/package-application.test.mjs
```

仓库 frontend 执行（已安装锁定依赖，测试库配置需含明确的回环监听端口）：

```sh
STANDALONE_TEST_CONFIG=/absolute/path/test.properties ./node_modules/.bin/playwright test --config playwright.standalone-document.config.ts --project chromium
./node_modules/.bin/vitest run src/runtime/bootstrap.spec.ts
npm run typecheck
./node_modules/.bin/eslint playwright.standalone-document.config.ts e2e-standalone-document/document.spec.ts
./node_modules/.bin/prettier --check playwright.standalone-document.config.ts e2e-standalone-document/document.spec.ts
```

本切片只做本地提交，没有主分支合并、GitHub 推送、模型调用、SSH 部署或原交付文档更新。完整产品外壳、真实状态保存、新目录构建及最终工程导出仍待后续任务。

## EB11–EB12 · 独立 Vue 启动外壳（C-EB05）

### 范围与设计取舍

主入口：`templates/standalone-v1/frontend/`。这是后续独立应用的宿主，不是平台工作台或测试面板。本切片仅接真实 `GET /api/health`；API 返回 200 且严格 `{status:"UP"}` 后显示“服务连接正常”，明确应用界面尚未接入，不制造运行、生成或保存成功。会话/应用描述/沙箱属于 EB13–EB14，真实持久化属于 EB15–EB17。

界面设计契约：沿用平台的深色底与绿色强调色、系统中文字体；单列启动区域，移除平台侧栏和管理功能。状态由图形与文字共同表达；原生按钮、跳过导航、可见焦点、稳定 live region，重试不清除前次错误、不移除当前聚焦按钮。无动画或外部图片/字体，320px 不横向溢出。请求、启动生命周期与 Vue 模板分离，未引入全局状态库或路由。

健康检查不携带 Cookie、不建访客、不写应用数据；5 秒截止，响应体即使分块传输也最多读取 1 KiB。非 JSON/错误状态/未知字段/非法 UTF-8/格式异常不能算成功。错误使用固定文案，丢弃上游异常或响应原文；卸载取消请求和计时器，没有自动轮询或无限重试。

### 开发与依赖边界

- Vue 3.5.43、Vite 8.3.3、TS 5.9.3 及必要 lint/test 工具沿用平台的直接版本；生产依赖仅 Vue。不带 Pinia/Router、管理页面、浏览器验收依赖或模型密钥。独立 npm 11.19.0 lock 由 npm 生成，没有复制/手改平台锁文件。
- `.npmrc` 固定 `ignore-scripts=true`、`engine-strict=true`；实际冻结安装、类型检查、lint、单元和构建成功，无需放行第三方生命周期脚本。锁中 237 个条目均有 integrity 且仅来自 registry.npmjs.org；本机实际安装 213 个包，其他主要为跨平台可选依赖。
- Vite 开发 127.0.0.1:5273、构建预览 127.0.0.1:4273，端口冲突失败；只代理精确 `/api` 命名空间至固定 127.0.0.1 后端。`STANDALONE_DEV_API_PORT` 仅接受 1024–65535 的规范端口，默认 8088，不接受任意 URL。
- 先校验 Host、单个精确 Origin 和 Fetch Metadata，再将本机前端 Origin 转译为后端 Origin。外站/null/重复来源不会因代理而被洗成可信来源；没有放宽后端同源或 CSRF 策略，没有 CORS。
- 关闭 `.env` 自动装载与客户端 env 前缀注入；开发文件服务只允许当前前端目录，拒绝配置/密钥/.git。HMR 的 inline style 与 loopback WebSocket 仅开发启用，构建预览没有这两项放宽；两者都禁止 inline script、外部资源、嵌入宿主页面。
- 模板自己的忽略规则覆盖真实配置、密钥、依赖、dist/target 和测试结果；不会依赖原仓库的 .gitignore 才能安全使用。

### 真实验证

| 检查 | 本轮结果 |
|---|---|
| 红绿 | 代理、health、App 的模块缺失测试先失败，随后实现通过；健康状态测试发现说明文案混入成功状态词，调整说明后通过 |
| 模板前端 | 冻结安装、typecheck、lint、**25 项单元**与生产 build 均通过；无测试匹配时明确 exit 1 |
| 条件变异 | Origin 计数 `<= 1` 临时反转为 `> 1`，2 项断言失败；恢复后 25 项全绿 |
| 真实 Chrome | **7 项**通过，约 28.2 秒；320/390/768/1024/1440px、键盘/焦点/44px 触控、axe、断网后重新连接真实后端、开发与构建预览代理安全边界 |
| 浏览器状态 | 正常页面无 console error/warning，axe 无违规；错误场景仅测试注入首次断网，重试是真实 Java/PostgreSQL；查看了 320/1440 成功及断网截图 |
| 前端资源 | HTML 0.61 kB、CSS 3.09 kB、JS 65.58 kB（gzip 26.36 kB）；这是本切片构建值，不是最终完整工程预算 |
| 依赖检查 | 安装审计 0 个已知漏洞；`npm audit signatures`：213 包 registry 签名、90 个来源证明通过；不代表未知供应链风险不存在 |
| 回归 | 原平台工程组装/策略 **46 项**、仓库脚本及 runtime 一致性 **20 项**通过；原前端 typecheck、新增浏览器脚本 ESLint/Prettier 通过。本轮没有改平台生产代码或模板 Java，不把历史 121+44 后端全量结果称为本轮重跑 |

排障记录：首次锁生成因 registry 网络访问受限遗漏可选平台依赖，冻结安装拒绝；授权访问官方 registry 后由 npm 补全并重跑冻结安装成功。Playwright 最初通过继承的代理误判端口占用；本轮验收配置仅为本机地址追加 NO_PROXY，保留其他代理配置且不改用户 shell。实际页面随后暴露 `/favicon.ico` 404，补齐受控 SVG 图标及 HTML 引用后全绿。一次开发服务运行中执行条件变异触发热重载，污染当轮 390px 验收；该轮作废，恢复后在固定源码上重跑上述完整 7 项，无跳过或降低断言。

仅使用专用 `TestApplication`/测试数据库启动 Java，健康检查实际查询安装记录。浏览器用隔离 Chrome，不复用用户登录态；不记录 trace、Cookie、CSRF 或数据库配置。测试完成后本次 18088/5273/4273 监听均关闭，未停止共享 PostgreSQL 或平台进程。当前仍不是正式 JAR 的 Vue 静态资源接线；该接线、宿主正式 CSP 与最终 runtime hash 由 EB18 完成。

### 复现

先配置本机 Java 17、Node 24.20.x/npm 11.19.x。模板前端执行：

```sh
npm ci --ignore-scripts
npm run typecheck
npm run lint
npm run test:unit
npm run build
```

仓库 frontend 执行（测试配置对应已准备的专用库；自动启动并关闭本次 Java、Vite 与 preview）：

```sh
STANDALONE_TEST_CONFIG=/absolute/path/test.properties ./node_modules/.bin/playwright test --config playwright.standalone-shell.config.ts --project chromium
```

手动查看时，在模板 backend 执行 `STANDALONE_TEST_CONFIG=/absolute/path/test.properties ./mvnw -B spring-boot:test-run`；另一个终端在模板 frontend 执行 `STANDALONE_DEV_API_PORT=18088 npm run dev`，访问 `http://127.0.0.1:5273/`。端口应替换为该专用测试配置的实际 `server.port`。只适用于模板联调，不是导出后正式启动说明；正式入口仍拒绝测试配置，也不会用测试应用补缺文件。

本轮保持单执行流，仅本地提交；未合并、推送 GitHub、更新线上服务或修改原 README/交付说明。后续仍按 EB13–EB25 推进，不提前勾选完整工程导出。

## 已核对的官方依据

- [Vite 开发服务与代理](https://vite.dev/config/server-options)：固定回环、strictPort、自定义代理和前置中间件；本模板主动收紧默认本机 CORS 与 Host 行为。
- [Vue 生命周期](https://vuejs.org/guide/essentials/lifecycle.html)、[Vitest 配置](https://vitest.dev/config/)：挂载初始化/卸载清理及真实组件测试，不创建空测试补绿。
- [npm ci](https://docs.npmjs.com/cli/v11/commands/npm-ci/)：独立冻结安装和禁用安装脚本，实际以 npm 11.19.0 验证。

- [Java 17 Record](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Record.html)：可变组件需要防御性复制，复制后仍需保持值语义。
- [Java 17 MessageDigest](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/security/MessageDigest.html)：使用平台 SHA-256，避免新增摘要依赖。
- [Jackson 2.21 JsonNode 源码](https://github.com/FasterXML/jackson-databind/blob/2.21/src/main/java/com/fasterxml/jackson/databind/JsonNode.java)：deepCopy 与 properties 契约；新代码没有使用已废弃的 fields 方法。
- [Spring Boot 3.5 Maven Plugin](https://docs.spring.io/spring-boot/3.5/maven-plugin/using.html)：后续模板沿用 3.5.16 parent 的 Java 17、依赖管理和 repackage 配置，不升级平台依赖。
- [Node 24.20 文件打开标志](https://nodejs.org/download/release/v24.20.0/docs/api/fs.html#file-system-flags)：使用排他创建而非先检查存在再覆盖；不承诺网络文件系统的排他语义。
- [Spring Boot 3.5 SpringApplication](https://docs.spring.io/spring-boot/3.5/api/java/org/springframework/boot/SpringApplication.html)：自定义 Environment 与禁止命令行属性重新覆盖；本实现的预检发生在其 run 之前。
- [Spring Framework StandardEnvironment](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/core/env/StandardEnvironment.html)：默认系统属性/环境属性源可以显式移除。
- [Java 17 Properties.load](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Properties.html#load(java.io.InputStream))：遵循标准 ISO-8859-1 和 Unicode 转义，避免重复解析产生不同配置值。
- [PostgreSQL 17 psql](https://www.postgresql.org/docs/17/app-psql.html)、[CREATE DATABASE](https://www.postgresql.org/docs/17/sql-createdatabase.html)：固定非交互输入、错误即停及建库不能位于事务块的限制。
- [PostgreSQL 17 角色目录](https://www.postgresql.org/docs/17/catalog-pg-authid.html)、[角色创建](https://www.postgresql.org/docs/17/sql-createrole.html)、[权限查询](https://www.postgresql.org/docs/17/functions-info.html)：SCRAM verifier 格式、角色权限和实际权限核对。
- [pgJDBC 连接参数](https://jdbc.postgresql.org/documentation/use/)、[Java 17 Connection](https://docs.oracle.com/en/java/javase/17/docs/api/java.sql/java/sql/Connection.html#setReadOnly(boolean))：显式超时与只读事务；实际服务器只读状态另行查询确认。
- [Spring 测试上下文初始化器](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/ctx-management/initializers.html)、[Boot 3.5 ConfigDataEnvironmentPostProcessor](https://docs.spring.io/spring-boot/3.5/api/java/org/springframework/boot/context/config/ConfigDataEnvironmentPostProcessor.html)：测试环境在上下文刷新前校验并装载固定默认配置。
- [Surefire 用例发现](https://maven.apache.org/surefire/maven-surefire-plugin/examples/inclusion-exclusion.html)、[Failsafe 用例发现](https://maven.apache.org/surefire/maven-failsafe-plugin/examples/inclusion-exclusion.html)：避免 Test 前缀和 IT 后缀交叉命中；仓库锁定的 3.5.6 已实际验证，不升级插件。
- [Boot 3.5 数据初始化](https://docs.spring.io/spring-boot/3.5/how-to/data-initialization.html)、[PostgreSQL 17 advisory lock](https://www.postgresql.org/docs/17/explicit-locking.html#ADVISORY-LOCKS)：使用自定义 FlywayMigrationStrategy 在迁移前检查，数据库会话锁覆盖迁移连接，健康检查依赖数据库初始化完成。
- [Flyway 11.7.2 配置源码](https://github.com/flyway/flyway/blob/flyway-11.7.2/flyway-core/src/main/java/org/flywaydb/core/api/configuration/FluentConfiguration.java)：核对严格 schema/placeholder、迁移回调及日志选项，未使用不存在的 none 日志实现。
- [Spring Session 3.5.7 PostgreSQL schema](https://github.com/spring-projects/spring-session/blob/3.5.7/spring-session-jdbc/src/main/resources/org/springframework/session/jdbc/schema-postgresql.sql)：会话表字段、索引与关联删除来自锁定版本，由独立 Flyway 迁移管理。
- [Spring Security 6.5 请求授权](https://docs.spring.io/spring-security/reference/6.5/servlet/authorization/authorize-http-requests.html)、[会话管理](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/session-management.html)：health 单一路由放行，其他默认拒绝；STATELESS 不等于整个过滤链不会查询请求 Cookie。
- [SessionRepositoryFilter 3.5.7](https://github.com/spring-projects/spring-session/blob/3.5.7/spring-session-core/src/main/java/org/springframework/session/web/http/SessionRepositoryFilter.java)、[Boot 3.5.16 SessionAutoConfiguration](https://github.com/spring-projects/spring-boot/blob/v3.5.16/spring-boot-project/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/session/SessionAutoConfiguration.java)：核对会话按需加载和 CookieSerializer 自动配置退让条件；自定义序列化器显式保留配置中的 Cookie 安全属性。
- [Boot 3.5 结构化日志](https://docs.spring.io/spring-boot/3.5/reference/features/logging.html#features.logging.structured)：使用内置 Logstash JSON 格式和 SLF4J key-value 关联故障，不新增日志依赖。
- [Spring Security 6.5 CSRF](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html)、[认证持久化](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/session-management.html)：会话令牌、JSON csrf 入口、认证后刷新令牌，以及自定义认证的显式 SecurityContext 保存与会话轮换。
- [Spring Session 3.5 JDBC](https://docs.spring.io/spring-session/reference/3.5/configuration/jdbc.html)：独立数据库持久会话；依赖版本仍由 Boot 3.5.16 锁定。
- [Jackson 2.21 StreamReadConstraints](https://github.com/FasterXML/jackson-core/blob/2.21/src/main/java/com/fasterxml/jackson/core/StreamReadConstraints.java)、[Spring MVC 错误响应](https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-ann-rest-exceptions.html)：专用解析器深度约束与异常处理边界，不改变全局 Jackson 默认值。
- [Spring 6.2 ContentCachingResponseWrapper](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/web/util/ContentCachingResponseWrapper.html)、[安全响应头](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/headers.html)：缓冲完整响应直到会话过滤器完成，保留其他框架安全头；早期拒绝由外层过滤器提供同等响应保护。
- [jsoup Cleaner](https://jsoup.org/apidocs/org/jsoup/safety/Cleaner)：通过 isValidBodyHtml 不免除 clean 规范化步骤，避免解析器差异。
- [CSP3 sandbox](https://www.w3.org/TR/CSP3/#directive-sandbox)：受限文档的 HTTP sandbox 策略，在直接访问时也生效。
- [Spring Boot 3.5 test-run](https://docs.spring.io/spring-boot/3.5/maven-plugin/run.html#run.examples.test-run)：使用测试 classpath 与测试入口，不把夹具放入正式 JAR。
- [Playwright webServer](https://playwright.dev/docs/test-webserver)、[APIRequestContext](https://playwright.dev/docs/api/class-apirequestcontext)、[route.fulfill](https://playwright.dev/docs/api/class-route#route-fulfill)：管理自有测试进程、隔离会话及仅浏览器内的宿主协议夹具。
