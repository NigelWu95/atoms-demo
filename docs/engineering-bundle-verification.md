# engineering-bundle · 实施与验证记录

更新日期：2026-10-13。分支：`feature/standalone-engineering`。依据：[规格](../SPEC-engineering-bundle.md)、[计划](../tasks/plan.md)、[执行清单](../tasks/todo.md)。

## 当前交付边界

EB01–EB25、C-EB10 与新增 `project-export` PE01–PE05 已实现并完成本地验证：工作台可下载当前选中版本（包括历史版本）的完整工程 ZIP；实际下载包已解压到全新目录构建并独立运行。独立数据库/持久会话、原子幂等保存、双实例隔离、真实源码升级、浏览器沙箱和错误恢复共 23 项 Chrome 验收通过（3.9 分钟、无重试）。平台普通/运行时浏览器 14/20 项及完整前后端回归通过；不调用模型。

用户下载 HTTP API 和工作台按钮已接通；没有平台托管构建/运行实例，也不是任意后端源码生成。C-EB11 用户人工模块验收仍待完成。本轮新增平台下载接口与 UI、模板解压说明及验证工具，已更新原 README/交付说明；没有改动平台数据库迁移、依赖或线上服务，没有合并主分支、推送 GitHub、SSH 部署。下文早期段落保留各阶段当时的证据，“尚未完成”以当时为准；最新状态以本节及文末 PE01–PE05 记录为准。

`BundleSource` 冻结来源 UUID、原内容 hash 和 Artifact；调用方必须先完成版本所有者授权。组装器再次调用原 `ArtifactValidator`，并且只读取传入的代码快照及固定模板快照；没有数据库、环境变量、网络、文件系统或进程操作。`TemplateSnapshot` 构造入口限定于 engineering 包，后续只有固定目录的 TemplateCatalog 提供真实模板。

## 工程格式 v1 的实现细节

- 模板标识固定 `vue-spring-postgres / 1.0.0`；应用写入固定的 `application/{manifest.json,view.html,styles.css,main.js}`。
- manifest 包含来源、`INITIAL_ONLY`、有序文件清单与 bundleId。清单列出路径、UTF-8/二进制字节数、SHA-256，不含 manifest 自己。
- 规范化规则：JSON 对象键按 Java String 自然顺序递归排序，数组顺序不变，Jackson 2.21.4 的紧凑 UTF-8 序列化；不是 RFC 8785 跨语言数字规范。`bundleId` 对尚未加入 bundleId 字段的完整清单计算 hash。将来更改编码语义须评审工程格式/模板版本，不能悄悄改变 v1。
- 所有 byte[] 在输入、输出做防御性复制；列表不可变；嵌套初始状态也复制。File 的 equals/hashCode 比较字节内容，避免记录类型复制后不相等。
- 配额计入最终 manifest：128 文件、单文件 2 MiB、总量 8 MiB。原源码 120 KiB/初始状态 256 KiB 校验没有修改。
- 模板逐文件白名单之外，再拒绝路径穿越、绝对/非便携路径、大小写冲突、目录被文件占位、构建缓存/真实配置/密钥文件及生成文件保留路径。

文件模型只有常规文件名与字节，不接受链接目标或可变文件类型元数据。早期纯内存阶段未覆盖的真实磁盘符号链接/祖先目录检查，已在下文 EB20 的模板装载边界实现并验证。平台工作树从未被递归打包。

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

## EB13–EB14 · 真实访客与沙箱接线（C-EB06）

### 用户可见变化与接口边界

独立 Vue 页面现在按 `health → csrf → POST session → 新 csrf → app → app/document` 连接真实后端。标题/摘要来自严格解码的应用描述；不会将“信息已就绪”当成生成或保存成功。整体初始化最多 15 秒，卸载中止请求，失败由用户手动重试；不自动重放结果未知的 POST。重新加载应用前提示未保存输入将丢失，取消时保留当前 iframe 和输入。

认证会清除旧 CSRF，因此 POST session 后重新获取并验证令牌；本阶段令牌仅在初始化调用的内存中使用，随后释放，没有尚未使用的长期令牌缓存。访客 ID 不持久化到浏览器存储，也不交给应用代码。API 客户端只允许固定同源路径、固定 CSRF header，拒绝重定向、非 200 成功码、非 JSON、超 16 KiB/非法 UTF-8 及不符合契约的响应。401/403/503 等错误使用固定用户文案与合法 UUID requestId，不显示上游原文。

沙箱来自固定 `/api/app/document#atoms-channel=<随机 UUID>`，只允许 `allow-scripts`。通道使用 `crypto.getRandomValues`，兼容本地 HTTP；不使用 Math.random。HELLO 同时核对当前 iframe、opaque origin `null`、协议、通道与精确字段，端口只转移一次；消息不带 Cookie/CSRF、平台 URL、项目 ID 或任意 fetch 能力。SDK/bootstrap 和运行 HTML 仍与原平台逐字节一致。

宿主协议拒绝额外授权字段、过期通道、非法修订号、非 JSON 对象及 UTF-8 超 256 KiB 的状态。状态容器最多 63 层，为独立后端 64 层的 JSON 请求体预留外层；这是既有独立后端限制的前置校验，不改变平台解析器。最多 16 个未完成数据请求，重复请求 ID 不重复调用，RUNTIME_READY/ERROR 不被数据队列阻塞。文档加载与握手分别最多 10 秒；导航、卸载、超时关闭旧连接，迟到异步结果不再回写 UI。

业务数据服务仍归 EB15–EB17：当前 GET/SAVE 能力明确拒绝为 `READ_ONLY_PREVIEW`，不返回假初始状态、不进行本地伪保存；迟到 READY 不能覆盖数据失败，运行错误不能被后续数据结果遮掉。只有显式 test-classpath 计数器增加了“本页计数”，用于验证点击/键盘运行，标签说明刷新归零且不保存；原 SDK 存储计数器保留，读失败时禁用并说明原因。没有将测试夹具或数据兜底放进正式 JAR。

### 本轮验证与排错

| 检查 | 实际结果 |
|---|---|
| 红绿 | API、协议/桥、挂载器及 RuntimeFrame 缺失时测试先失败；App 的真实描述/重载确认断言先失败后通过。浏览器 320px 在新增本页计数器前因找不到按钮失败，补齐测试夹具后通过 |
| 模板前端 | typecheck、lint、Prettier、**53 项单元**、生产 build 通过；没有新增/升级依赖或修改 lockfile |
| 安全条件变异 | 将 `event.origin !== 'null'` 临时反转，错误 origin 创建端口导致断言失败；恢复后完整 53 项全绿。变异期间未运行 Vite/浏览器验收 |
| 真实外壳 Chrome | **9 项 × 3 轮 = 27 次**全部通过，约 2.1 分钟，retries=0；五种尺寸、点击/空格、本页计数、真实访客不变、取消/确认重载、新通道、宿主键盘/焦点/axe、断网恢复、401 与安全 requestId、握手超时、dev/preview 边界 |
| 浏览器隔离 | iframe origin=null，不能读取 parent.document/localStorage；宿主 document.cookie 为空，会话 Cookie HttpOnly/SameSite=Lax，localStorage/sessionStorage 不保存会话。正常链路无控制台 error/warning，宿主 axe 无违规；查看 320/1440 截图无横向溢出 |
| 原文档协议 Chrome | **2 项**通过，16.4 秒；原 SDK 的两次计数交互仍通过（此套状态响应是明示协议夹具，不是持久化服务）；直接访问仍 opaque，陌生访客 401 |
| 独立后端 | Java 17 `clean verify`：**121 单元 + 44 PostgreSQL/HTTP IT** 全部通过、无跳过，48.304 秒，JAR 构建成功 |
| 原平台及脚本回归 | 组装/策略 **46 项**、SDK/bootstrap 与桥 **9 项**、Node 脚本与资源一致性 **20 项**通过；平台前端 typecheck、两份浏览器测试 lint 通过；未重跑平台全量 E2E/后端 verify |
| 构建体积 | HTML 0.61 kB、CSS 3.37 kB、JS 75.38 kB（gzip 29.72 kB）；非最终工程配额测量 |

真实回归暴露过一处测试输入耦合：ApplicationArtifactTest 精确期待旧版单按钮 HTML，但复用了已扩展的浏览器夹具。修复为该规范化用例显式指定原输入，**原精确断言及其余断言全部保留**；随后完整 121+44 重跑通过。原文档浏览器的首次启动因沙箱无法连接专用测试库失败，经权限流程重跑后 2 项通过，没有改数据库连接/降低安全校验。格式检查曾要求 client.spec.ts 再格式化一次，最终统一检查通过。

外壳首轮 9 项通过后，重建产物重复验收时 768px 曾在测试端延迟调用 `response.json()` 报 `Network.getResponseBody: No data found`；当时宿主已读取应用信息并挂载新 iframe，并非业务客户端报错。测试改为监听到真实响应后立即读取，与键盘操作一同等待；保留身份相等断言，增加 `performance.timeOrigin` 不变断言排除意外整页刷新。另据 Playwright 响应契约将 health 无 Set-Cookie 的检查改为 `headerValue`，避免只检查过滤后的 `headers()`。随后完整三轮 27 次全部通过，未加自动重试、延时兜底或删除断言。

复现命令沿用上节的独立前端检查、`playwright.standalone-shell.config.ts` 和 EB05 的后端 `clean verify`；原文档使用 `playwright.standalone-document.config.ts`。它们共用专用 IT 库，必须顺序运行，不能在浏览器运行中执行数据库清理或源码变异。浏览器测试自动启动/关闭自己的进程，不复用用户登录态，不记录 trace/令牌，不停共享 PostgreSQL。

### 评审与后续

按五轴复核：边界输入严格校验、异步销毁与错误状态有反例；启动、解析、消息协议和 Vue 展示各自独立；无新增第三方依赖、网络权限或原平台生产改动。所有字符串由 Vue 文本渲染，没有宿主 v-html/eval；生成代码只在既有受控 iframe 执行。当前前端存储拒绝属于明确的未完成状态，不是假实现成功。

本轮只本地提交，不合并/推送/部署。按增量计划先交付本检查点；下一步 EB15–EB17 接真实状态读取、原子幂等保存与未知结果处理，再做新目录工程构建/启动和独立性验收。正式 JAR 的 Vue 静态资源及最终 runtime 指纹仍待 EB18。原 README、方案/交付说明继续留待完整功能完成后统一更新。

## EB15–EB17 · 独立应用的数据闭环

### 接口与数据库决策

`GET /api/state` 只读取当前已认证访客；未保存时返回 manifest 初始值、revision 0、schema 1，但不创建状态行。已有值从独立 schema 读取，不因 GET 或重启被初始值覆盖。访客不来自请求体或查询参数；身份无效为 401，数据库故障为 503，不伪装成空数据。

`PUT /api/state` 接受规格中的四个字段，拒绝多余字段、非对象 value、非整数/越界 revision、非 schema 1、非法运行指纹。value 独立限制为紧凑 JSON 的 256 KiB UTF-8 字节及 63 层容器，入口仍限制 320 KiB/64 层；没有提高原平台限额。Idempotency-Key 沿用 `[A-Za-z0-9._:-]{1,128}`；对象键递归排序后连同运行指纹/修订号/schema 做 SHA-256，数组顺序保留。该规范化不是跨语言 RFC 8785 数字规范。

V002 每访客一份状态，V003 保存按访客、方法、路径、key 唯一的成功结果。选择 `json` 而不是 `jsonb`：当前读写整个文档，无字段索引需求，同时保留 JSON 转义的 U+0000；数据库约束限制对象类型、字节数、schema 和修订号，真实 HTTP 已验证包含引号和 U+0000 的值往返。此选择依据 [PostgreSQL 17 JSON 类型说明](https://www.postgresql.org/docs/17/datatype-json.html)。

保存事务使用 `TransactionTemplate`（READ COMMITTED，5 秒预算），先设置 3 秒锁等待，再锁当前 guest_users 行。这样初次尚无状态行时也能串行化同访客写入，另一个访客不持有这把行锁。持锁后先查成功重放；匹配则返回原响应，即使应用已升级；同键异体 409。只有新意图才检查 loadedRuntimeId 和 expectedRevision，并把状态及完整成功响应在一个事务内提交。数据库唯一键作为额外一致性约束，没有 JVM 锁或可遗留的“处理中”占位行。依据 [Spring 6.2 编程式事务](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/programmatic.html)及 [PostgreSQL 17 行锁](https://www.postgresql.org/docs/17/explicit-locking.html#LOCKING-ROWS)。

代价是同一访客写入串行化，适合当前单文档模型，不适合高吞吐的协作数据库。重放结果目前不自动清理，因此不会因重启/短 TTL 破坏 7 天有效会话的重试链，但存储会增长；删除访客时状态和重放表有 FK 级联，尚未提供用户删除 API 或周期回收任务。对外规模化使用前必须补留存/回收策略，不能直接按请求年龄删掉仍可能重试的结果。V001 未编辑；专用测试清理只扩展固定表白名单，迁移拒绝身份不匹配的保护不变。

### 前端行为

- SDK get/save 接真实 API；每次保存先取新 CSRF，令牌只留调用内存，不交给 iframe。宿主补充当前运行指纹，不让生成代码选择访客/平台项目。
- 一次显式调用最多 8 秒，响应流有字节上限和严格状态/字段校验。请求体与期望响应修订号都在 await 前快照；组件卸载取消网络等待，但不宣称回滚服务端事务。
- 显示 saving/saved/unknown/conflict/offline。网络中断、超时、5xx、坏 2xx 等已发送但未确认结果保持 unknown；SDK 保留原 requestId 和内容，应用内“重试原操作”用相同 key/body，不自动重放或重新建立访客。
- 重载确认明确说明会丢失输入和重试信息，但不会撤销服务器可能完成的保存。未知意图仅在当前沙箱内存保留，不承诺关闭页面后继续重试。
- 显式测试应用增加待办添加/完成切换、读取最新状态及原意图重试；与 Vue 外壳、真实 Spring API 和 PostgreSQL 联调。它仅在测试 classpath，正式工程仍必须携带自己的应用资源，不会缺文件时变成待办 Demo。

### 验证证据

读取接口先以 404 失败，保存接口先以 405/缺表失败，随后实现；输入类型缺失、前端 transport 缺失、unknown 状态未接线以及待办控件缺失也分别记录了 RED。沙箱阻止数据库/监听端口时按权限流程重试，该环境失败不算业务 RED。没有跳过测试或降低断言。

| 检查 | 结果与范围 |
|---|---|
| 模板 `./mvnw -B clean verify` | Java 17.0.5-oracle：124 单元 + 56 真实 PostgreSQL/HTTP IT，全通过、无跳过 |
| StateWriteRequestTest / StateReadIT / StateWriteIT | 新增 3 + 3 + 9 项；大小/深度/修订号边界，读取隔离，并发同键同体/异体、异键抢写、事务回滚、锁等待及重启后旧结果重放 |
| 独立前端 | 68 单元，typecheck/lint/build 通过；保存响应修订号改为快照的反例先失败后修复 |
| 外壳 Chrome | 14 项通过；320/390/768/1024/1440、axe、键盘、真实保存刷新、两访客隔离、双标签冲突；修订号快照修复后五项真实数据链路再次全部通过 |
| 未知结果 | 浏览器先让真实 PUT 提交，再注入响应丢失、畸形 2xx、8 秒以上延迟；三种情形都保持 unknown，显式同键同体重试后数据库 revision 仍为 1 |
| 文档 Chrome | 2 项通过，opaque-origin/SDK、直接导航和未授权拒绝不回归 |
| 原平台定向回归 | 组装/路径 46、原 SDK/桥 9、Node 脚本组合 20 项通过；平台前端类型/lint/格式检查及新增浏览器文件检查通过 |

代码复核按正确性、可读性、架构、安全和性能五轴进行：没有新增依赖/平台写入，SQL 使用参数绑定，写入有数据库边界和超时，错误日志沿用 requestId 与固定错误码且不写状态内容。实际把输入修订号的 `>= MAX_SAFE_INTEGER` 改成 `>`，边界测试失败；恢复后完整后端回归通过。复核另发现前端响应校验读取可变修订号的问题，已通过新增反例修复，不带入下一任务。

浏览器截图在忽略的 `frontend/test-results/standalone-shell/`，已人工查看 320px 与未知结果页面；关键操作需在 iframe 内滚动时仍可键盘访问。运行期页面正常路径无 console error/warning；终端 NO_COLOR/FORCE_COLOR 提示是测试进程环境提示，不是浏览器应用错误。

复现新增浏览器检查（在仓库 `frontend/`；先完成模板前端 build，并设置 Java 17/Node 24.20 的路径及 `STANDALONE_TEST_CONFIG`）：

```sh
./node_modules/.bin/playwright test --config playwright.standalone-shell.config.ts --project=chromium
# 只复验五项数据链路
./node_modules/.bin/playwright test --config playwright.standalone-shell.config.ts --project=chromium state.spec.ts
```

后端 verify、文档浏览器和外壳浏览器共用专用 IT 库，必须顺序执行；不得边运行浏览器边迁移清理、变异代码或重建其依赖。测试程序只管理自己的本地服务，不停止共享 PostgreSQL。

### 尚未完成的边界

本轮只有本地提交，没有推送 GitHub、合并主分支或部署服务器。数据层的重启验证是同 JVM 重建 Spring 上下文，并非最终成品 JAR 的真正进程重启验收。全新目录构建、前端静态资源进入 JAR、最终运行层指纹、阻断平台的真正独立运行、工程下载入口/工作台接线仍归后续任务。下一步 EB18–EB19 构建与启动入口；原 README、架构/交付说明继续等整个功能完成后再统一更新。

## EB18–EB19 · 自运行构建与启动契约

### 本次实现与取舍

新增单入口 `build.mjs`，固定顺序执行前端冻结安装（禁用依赖生命周期脚本）、类型、lint、单元、build，再组装资源并执行 Maven `clean verify`。外部专用测试配置缺失、任一命令失败、取消、构建中途修改输入或缺少最终 JAR 都不能输出成功。不提供任意命令透传，不把运行数据库/模型凭据、Node/JVM/Maven 覆盖环境传给工具；前端连测试配置的位置也不会收到。

受控编译输入摘要覆盖 backend/src/main、后端 POM、前端 package 与 lock；最终 runtime v2 指纹包含原应用/沙箱资源、该摘要和实际 frontend/dist 字节。`runtime/build.txt` 明确记录受控输入摘要与排序后的静态文件路径。Node 与 Java 使用相同的固定 hash 向量；Java 启动重新读取并校验每份前端资源，缺失/篡改即失败，不回落到测试夹具。这是内容一致性标识，不是对任意手工修改 JAR 的二进制签名，也不证明整个供应链可信。

静态资源只接受 index.html、favicon.svg 和 assets 下有限的 JS/CSS，拒绝 map、私有配置、链接、大小写冲突与越界路径；最多 64 个前端文件，单项 2 MiB、总运行资源 8 MiB。原应用源码/初始状态上限未变。资源放入新建系统临时子目录，Maven clean 不会删掉输入；没有递归删除用户目录。源树由本地操作者控制，不承诺抵御同用户恶意并发改写；构建后会重新核对内容。正式白名单导出预算仍由 EB20/EB24 实测，不以这些运行资源限额代替工程配额。

POM 新增受控资源 profile；PackagedRuntimeIT 在 package 之后打开实际 Boot JAR，核对运行指纹、迁移与入口，确认测试启动类不在正式包。backend-only clean verify 仍可用于后端检查，但不冒充完整应用构建。实际页面由 MVC 的显式入口返回已验证的不可变静态快照，避免默认静态路径暴露未列出的文件；同端口 API 保持鉴权、CSRF、JSON 错误以及未知 API 404，不做 SPA 回退。外壳使用 self-only CSP，只有受限 document 保留沙箱专用策略。

start 只启动已有 JAR，配置路径通过一个固定 argv 传给 Java；配置内容仍只由 Java 的同一预检解析。Node CLI 在 Linux/macOS 使用自有进程组，SIGINT/SIGTERM 连带转发给其工具后代，最多 5 秒宽限；保留退出码/信号，显式停止不误报“启动失败”。不按端口杀服务，不静默后台运行。SIGKILL 不能被包装脚本捕获，实际部署需服务管理器监管。

新模板 README 说明了独立运行库和可清理测试库的管理员准备、双重测试标记、配置/构建/启动、修改与重建、初始数据、访客有效期及未知保存结果限制。没有更新原项目 README/架构/交付文档，也没有扩大为账号系统、任意工程生成或公网部署。

### 验证及修正记录

先有失败用例再实现：原打包器忽略前端修改/缺失；新增 Node 模块尚不存在；后端根页面返回 401；实际 JAR 缺应用资源时拒绝验证。实现后逐项变绿。额外发现并修正三个实际问题：进程启动失败的 Node 内部负码不能作为 CLI 退出码；macOS `/var` 与 `/private/var` 别名让入口判定静默不执行；ResponseEntity 追加第二份 CSP 导致策略交集仍禁用脚本。最后修正正常 SIGTERM 错报启动失败的文案，并以真实 CLI 子进程重验。未删断言或跳过失败检查。

| 检查 | 本次证据 |
|---|---|
| 模板 Java 17.0.5-oracle clean verify（资源 profile） | 129 单元 + 59 PostgreSQL/HTTP/打包 IT，全通过、无跳过，完整 Maven 用时约 51 秒 |
| 实际 JAR 资源 | 固定资源探针 + 已构建 Vue；最终指纹 `d12e862d6ef441f5ad9ac3797afd9214521ab41aed891c99f8d870e9d55175a3`；PackagedRuntimeIT 从 ZIP 条目读回验证，而非使用测试 classpath |
| Node 组合回归 | 35 项通过：原发布/专用库/兼容性回归及模板初始化、打包、指纹、构建、子进程、启动；最终停止文案改动后 start/process 的 6 项再次通过 |
| 原平台组装回归 | EngineeringBundleTest + TemplatePolicyTest 共 46 项通过，未修改原平台生成/发布实现 |
| 实际变异实验 | 把 build 后的指纹不等比较 `!==` 改成 `===`，5 项编排测试中 2 项失败（正常输入被拒绝、变更输入未被拒绝）；恢复后 5 项全部通过 |
| 单端口真实 Chrome | Node start 运行探针 JAR，连接专用运行库 A、监听 18089；320/390/768/1440、键盘、axe、CSP、沙箱、真实会话和初始状态读取通过；无 console error/warning、无跨站请求 |
| 进程与配置 | Node 真实 CLI 验证 SIGINT/SIGTERM 和退出码 29；专用进程组后代退出；运行/测试/模型凭据没有继承给错误阶段；最终 18089 无监听，自有 Chrome 已退出 |

初次从 Node 资源探针执行完整 verify 时，沙箱阻止数据库连接，返回 TEST_DATABASE_UNAVAILABLE；按权限审批在沙箱外重跑通过，没有换数据库、跳 IT 或重启共享 Docker。数据库故障/身份拒绝测试预期会产生脱敏告警，这些不是最终构建失败。

本次探针辅助脚本位于被忽略的 `.local/standalone/check-package.mjs` 与 `shell-probe.mjs`，不进模板白名单。前者只为验证资源接线，物化最小固定应用并复用模板和现有 Vue dist；后者只管理本次创建的进程。截图在 `.local/standalone/shell-probe-results-GdkdAj/`，已查看 320 与 1440px。探针不是可重复导出验收器，下一任务必须由正式组装器生成全新目录，不能依赖这些本地辅助文件。

可复验命令（模板根；真实成品 build 需先由 EB20/EB21 补齐组装 application 文件）：

```sh
node --test scripts/*.test.mjs
STANDALONE_TEST_CONFIG=/absolute/path/test.properties node scripts/build.mjs
node scripts/start.mjs --config /absolute/path/application.properties
```

### C-EB08 交付边界 / 下一步强制检查

此处完成构建/启动契约，不宣称完整工程导出已经交付。单入口编排目前通过受控工具替身验证；资源 profile、真实 Java/数据库和实际 JAR 页面另有实测，但**尚未从正式工程白名单组装的全新目录完整执行 npm 安装到 Maven verify 的整条命令**。

EB20–EB21 必须补：真实 TemplateCatalog 白名单与许可证、正式平台 JAR 内模板装载、真实快照组装/所有文件 hash、全新目录冻结安装和全部检查、分别用 Node 与直接 Java 启停真实导出包。EB22–EB25 继续完成最终待办保存、跨进程恢复、阻断平台的独立性/实例隔离、升级幂等重放及完整原平台回归。README 手工建库说明也要在最终新目录验收时核对，当前不是全流程部署教程已实测的声明。

本轮按技能要求分为有限小片提交，并实际进行变异检查和独立浏览器核验；没有新增依赖、改变平台业务数据库或扩大身份模型。只本地提交，未推送 GitHub、合并主分支或部署服务器。

## EB20–EB21 / C-EB09 · 白名单组装的独立工程

### 已实现的边界

平台 `TemplateCatalog` 仅从固定 classpath 资源流读取 `standalone-v1.files` 和清单中的 106 个文件；无工作目录扫描、用户自选模板路径或缺文件回退。装载前检查路径、顺序、重复/大小写冲突，按上限读取内容，拒绝文件和祖先符号链接；打包 IT 还检查原始仓库源文件，避免 Maven 复制阶段掩盖链接。Maven 的显式 includes 与文本清单有意保留两份，并通过严格相等测试阻止漂移，不引入新的执行插件。

实际打包测试发现并修复 `.gitignore` 被 JAR 默认排除的问题：资源复制和归档两阶段均保留明确允许的文件。独立工程需要的 Java/Node/Vue 测试包含在源码模板内；平台浏览器夹具、开发凭据、真实业务数据不在清单中。Maven Wrapper 3.3.4 的完整 LICENSE/NOTICE 随模板保留；前后端依赖由已有锁文件/POM 下载，没有把依赖缓存打入源码工程。

`BundleFixture` 只存在于平台 test-classes，通过 Boot PropertiesLauncher 加载正式平台 JAR 中的组装器和模板，在仓库外目录组装固定手工待办快照。它没有 HTTP 入口，不连接平台数据库、不调用模型；页面也明确标明不是 AI 生成。该测试入口及平台 fixtures 不进入正式平台 JAR。

仓库侧 `verify-standalone.mjs` 验证 manifest 规范化摘要、每个文件大小/哈希和完整清单，使用 mkdtemp + 排他写入建立新工程目录；不覆盖、复用或删除旧目录。随后在该目录运行随包脚本测试、`npm ci --ignore-scripts`、类型检查、lint、前端单测/构建和 Maven `clean verify`。打包后再验所有源码 hash，分别通过 Node 与直接 `java -jar` 启停实际产物。`serve-standalone.mjs` 只管理验收产物，启动前校验 JAR 与明确报告匹配、端口空闲；没有按端口杀进程或复用已有服务的逻辑。

### 实测结果

| 验收项 | 结果 |
| --- | --- |
| 平台后端完整构建 | `./mvnw -B clean verify`：77 单元 + 52 集成通过，无跳过；正式 JAR 资源逐字节检查与仓库外装载通过 |
| 真实独立工程 | 106 模板文件 + 4 应用文件 + 1 bundle manifest，共 111 文件、542,975 字节；最大文件 121,072 字节 |
| 固定配额余量 | 文件剩余 17；单文件剩余 1,976,080 字节；总量剩余 7,845,633 字节。未提高 128 / 2 MiB / 8 MiB 上限 |
| 新目录脚本 | 随包 24 项 Node 测试通过；冻结安装成功，npm 报告 0 漏洞 |
| 新目录前端 | typecheck、lint、68 项单测和真实 Vite build 通过 |
| 新目录后端 | 129 单元 + 59 专用 PostgreSQL/HTTP/打包集成测试通过；实际 JAR 无 TestApplication/TestDatabase 入口 |
| 真实启动 | 同一成品通过 Node CLI 与直接 Java 17.0.5 启动，实际 health 与静态页面正常，分别退出后端口可重新绑定 |
| 浏览器 | 隔离 Chrome、320/390/768/1440px 共 4 项通过；真实会话/应用描述/初始状态、内容指纹、opaque iframe、同源请求、键盘焦点、axe 无违规、console 无 error/warning |
| 仓库脚本回归 | 17 项常规脚本 + 4 项端口/生命周期测试通过；已有发布、数据库配置和运行时一致性脚本均包含在回归内 |
| 实际变异 | 将 manifest 摘要比较从 `!==` 改为 `===`，正常物化测试失败；恢复后通过。复查增加畸形条目拒绝测试，先捕获 null 导致的 TypeError，再修复为一致的拒绝结果 |
| 最后核验 | 浏览器结束后用真实平台 JAR 重新组装并比对产物：bundleId 一致，111 个源文件字节均未变化；18089 无监听，自有浏览器退出 |

源工程位于 `.local/standalone/verification-YzIPoQ/engineering-bundle-HNsK7G/`，报告为其父目录的 `verification.json`。源码目录和构建输出留存供检查，不自动删除；真实配置仍是既有、被忽略的外部文件。截图在 `frontend/test-results/standalone/`，已查看 320px 和 1440px 图像。浏览器只读取初始业务状态，本阶段没有把此烟测宣称为最终持久化/重启验收。

- bundleId：`f758f09d65e24e56f258f58b3002fe1654bed15c74ab71f2d7b3da141b6e026f`
- loadedRuntimeId：`6bc78d3e4b443eeb25b28e1dedb9dae3ff2db3afc11a442beafcb6a3fde16199`
- JAR SHA-256：`1456b8c22adec78940ccbd02ab70be6787683d54e0d98c529b1f9896be72ef98`

哈希证明本次文件一致性，不是来源签名。验收输出目录和报告是本机开发者控制的可信输入；此工具不是任意用户工程上传/运行接口，也不声称防御同一 OS 用户并发篡改目录。不要把它直接暴露为平台执行服务。

### 复验命令与剩余范围

先在平台 backend 运行 `./mvnw -B clean verify`，使用既有专用平台测试库配置。再在仓库根（Java 17.0.5、Node 24.20.x、npm 11.19.x 已选定）执行：

```sh
node scripts/verify-standalone.mjs --config-dir .local/standalone --output-parent .local/standalone
```

此命令每次建立不同目录，必须使用它本次输出的绝对报告路径。`config-dir` 应已有独立的 `test.properties` 与 `runtime-a.properties`；Java 在数据库操作前检查配置和实际数据库身份。配置文件内容、密码和会话令牌不写入报告。然后在平台 frontend 运行：

```sh
STANDALONE_VERIFICATION_REPORT=/absolute/path/to/verification.json \
STANDALONE_RUNTIME_CONFIG=/absolute/path/to/runtime-a.properties \
./node_modules/.bin/playwright test --config playwright.standalone.config.ts --project=chromium
```

Playwright 由 worker 持有独立进程句柄，保留与 `reuseExistingServer: false` 等价的端口占用即失败约束，支持真实重启；不启用原始 trace 或保存登录态，监听仅回环。当前浏览器工具无 DevTools MCP，按浏览器技能采用已有 Playwright + 隔离 Chrome，无新增工具依赖。沙箱拒绝监听时按权限审批重跑，没有降低测试标准或更换数据库。

五轴复查：固定路径和预算/错误路径有测试；catalog、纯组装器、物化器、进程控制与浏览器验收边界分离；没有新增依赖/模型调用/生产业务库迁移；循环和输入有配额；原文件字节在实际构建后核验。只本地提交，未推送、合并或部署。技能促使本轮按小片提交并实际验证归档和浏览器，未把纯脚本测试当成成品运行证据。

C-EB09 不等于整项完整工程导出交付：没有新增下载 API/按钮，也没有任意后端代码生成。EB22–EB23 继续覆盖最终成品待办保存、跨进程恢复、平台断开时独立运行、多访客/实例隔离、升级后幂等重放；EB24–EB25 覆盖配额回归、README 首次手工建库教程与原平台完整回归，再更新原项目设计/交付文档。

## EB22 · 脱离平台后的持久化、故障恢复与真实重启

### 实现与边界

独立工程的最终 JAR 通过浏览器执行真实会话、数据读取及写入；除独立应用自身外的全部 origin 被阻断，包括平台。测试不是对 `/api/state` 返回模拟成功：响应丢失、畸形和超时场景先把请求送至真实后端，确认提交后才破坏传输。手动重试沿用原意图键及请求体，且丢失响应场景在重试前真正结束、重新启动 Java 进程。

仓库侧 worker 只管理它创建的进程。数据库连接通过该 worker 专属的回环 TCP 闸门；故障只关闭这些 socket，不停止 PostgreSQL、不修改数据库权限、不删除运行数据。闸门只接受既有引导工具生成的私有 A/B 运行配置，拒绝平台库、测试库、公网目标及重复/越界配置。临时配置排他创建为 0600，结束时移除；原配置和工程源文件不变。此限制属于验收工具，不是独立工程运行配置的额外产品限制。

SQL 证据使用 `atoms_standalone_a` 应用角色在固定本地容器内执行只读事务，核对安装 ID/数据库标记，只返回 revision、状态行数和幂等行数。不输出 Cookie、CSRF、密码、业务正文或原始 SQL 结果。失败浏览器诊断只记录固定 API 路径/状态码和截图，不保存含凭据的原始 trace。会话采集通过单次真实请求转发取响应体，避开导航时 CDP 响应缓存失效；不增加初始化请求或重试。

### 实际结果

| 验收场景 | 数据与行为证据 |
| --- | --- |
| 独立保存/刷新/勾选 | 无平台请求；revision 从 0 到 1、2，刷新保留待办和完成状态 |
| 真正进程重启 | 同浏览器仍是原访客，状态完全相同；第二个独立浏览器上下文为空且只能写自己的数据 |
| 已提交响应丢失/畸形/超时 | 不显示成功，保留输入和重试入口；重试前后 SQL 均为 revision=1、状态行=1、幂等行=1 |
| 双标签并发修改 | 后提交者返回 409，保留输入且不覆盖首个版本；明确读取最新数据后才继续保存 |
| 数据库连接中断 | PUT 和 health 返回真实 503；SQL 保持 1/1/1；恢复连接并手动重试后为 2/1/2 |

新产物使用 Java 17.0.5-oracle、Node 24.20.0、npm 11.19.0，从全新目录完成冻结安装、构建和以下验证，无测试跳过：

- 随工程交付的 Node 脚本：24 项；独立前端：68 项，类型/lint/真实 Vite 构建通过；安装审计 0 漏洞。
- 独立后端：129 单元 + 60 真实 PostgreSQL/HTTP/打包集成测试通过，包含新增的事务回滚日志回归。
- 新 JAR：Node CLI 和直接 Java 启停通过；Chrome 11 项通过，54.9 秒，`retries: 0`。四尺寸 320/390/768/1440px 的烟测、键盘入口、axe 与同源检查也在此次运行内通过；尚不替代 EB23 全部安全/错误态验收。
- 原平台后端 `clean verify`：77 单元 + 52 IT 通过；仓库 Node 回归 24 项（17 常规 + 4 进程 + 3 数据库闸门）；新增浏览器测试独立 TypeScript 检查、eslint、Prettier 通过。
- 结束后从正式平台 JAR 再取真实组装输出，逐一核验 111 个源码文件，bundleId 与实际 JAR SHA-256 未变；自有 18089 服务已退出。

新工程与报告位于仓库忽略目录：

```text
.local/standalone/verification-4oZItT/verification.json
.local/standalone/verification-4oZItT/engineering-bundle-HOJltI/
```

- bundleId：`25c6fd4e01b3b18fda76380b31d77a91486fef7fcd09e9042e08036ac2b70e23`
- loadedRuntimeId：`dfe54b3e7f40d84e771ecf4760d9cb4d18bf287f00156f70a881dcefa2c513d4`
- JAR SHA-256：`a6361f3de5216156c7a80e0145526a848b38ebe3ba86463b51d7364f81c13c2c`
- 文件数 111，总量 545,934 字节，最大文件 121,072 字节；未提高既有配额。

截图已实际查看，位于 `frontend/test-results/standalone/` 下对应测试目录：`assembled-320.png`、`assembled-1440.png`、`persisted-todo.png`、`restored-after-restart.png`、`unknown-save.png`、`database-disconnected.png`。这些是本机验收产物，不进入源码包或 Git。

### 本轮发现与修正

1. 实际断连导致业务操作和 rollback 同时失败，Spring `TransactionTemplate` 先记录原始数据库异常，虽不影响前端正确返回 503，仍不符合日志边界。新增 HTTP canary 回归先失败；仅关闭该框架 logger 的原始异常输出，保留应用自身关联 requestId/code/status 事件。新包最终运行记录到 503 和 409，原始 `stack_trace` 数为 0。
2. Playwright 的 `serviceWorkers: block` 初始化脚本访问 opaque iframe 的 `navigator.serviceWorker`，引发 SecurityError。取消该测试脚本注入，保留应用实际 CSP、零页面错误和无 service worker 断言；没有降低沙箱策略。
3. 导航/重启时调试协议读取会话响应体偶发失效，实际页面已经恢复数据。夹具改为一次真实请求转发并保留响应，修复后整套 11 项通过，不增加自动重试或删除断言。
4. 库名/角色一致性条件做了反向变异，正反例均捕获，恢复后全绿；反例如果意外成功也清理自有连接，避免测试错误遗留监听。

复验沿用上一节的新目录构建命令，再把 `STANDALONE_VERIFICATION_REPORT` 指向本次报告运行浏览器套件。当前 SQL 证据需要 Docker CLI 在 PATH 中及已启动的 `atoms-demo-postgres`，只用已创建的独立数据库角色。不要把运行配置换成平台配置，也不要为断连测试停止整个数据库。

五轴复核结论：真实持久化/重试与错误路径可追溯；进程、连接闸门、固定只读证据各自有明确边界；凭据不进入生成工程或浏览器报告；连接数量/输入/执行时间有上限；未新增依赖或生产控制接口。技能促使本轮先补反例、修正日志并重新构建实际产物，而非仅记录开发目录测试通过。

EB22 完成不等于 C-EB10 或完整工程导出产品完成。EB23 的 A/B 同主机跨端口隔离、错误安装 ID 的最终 JAR 验证、源码重建升级与旧意图重放、安全攻击及错误态可访问性仍待验收；下载 API/工作台按钮、任意后端生成和托管运行仍未接入。仅本地功能分支提交，未合并、推送、部署，也未更新原平台交付文档。

## EB23 · 同机双实例与安装误配（部分验收）

本阶段补齐 EB23a–c 的仓库验收能力；不修改应用模板、平台接口或既有运行配置。继续使用 EB22 的 `verification-4oZItT/verification.json` 和实际 JAR：源码 111 个文件与 JAR hash 再校验均未变化，因此没有重新生成或冒充一个新构建版本。

### 实际验证的边界

- 同一非持久化 Chrome context 同时访问 A（`127.0.0.1:18089`）与 B（`127.0.0.1:18090`），共享浏览器 Cookie 存储。两个实例运行相同 JAR/runtime ID，分别使用 `atoms_standalone_a`、`atoms_standalone_b` 和既有安装 ID。依次在 A/B 保存不同待办，再 A → B → A 刷新；两边访客身份稳定且不相同，状态仍是各自内容。没有用两个浏览器上下文掩盖 Cookie 同名覆盖问题。
- 只读 SQL 核验：A 库中 A 访客为 revision/stateRows/idempotencyRows=`1/1/1`，B 访客为 `0/0/0`；B 库相反。停止本次 B 进程后，A 继续保存得到 `2/1/2`；重启 B 后仍恢复同一访客及 `1/1/1` 的原状态。
- 安装误配：B 正确配置保存数据后停止，只把新建 0600 临时配置中的安装 ID 改为另一个 UUID。最终 JAR 记录 `installation_rejected` / `INSTALLATION_MISMATCH` 后退出。核对原安装标记和该访客计数不变；恢复正确配置，原会话与内容恢复并可继续保存。测试即使意外接受错误配置也停止它创建的进程，不遗留监听。
- 浏览器仍拒绝全部平台和外部 origin；只有双实例场景显式放行固定 B 地址。新增启动参数只属于仓库验收工具：默认使用报告端口，显式第二地址必须与私有配置匹配；JAR 每次启动均校验，已占用端口拒绝，不寻找/停止外部进程。

### 结果、修正与证据

完整 Chrome 套件 13 项通过，3.6 分钟，`retries: 0`；覆盖本次两项及原保存、双标签冲突、真实重启、响应丢失/畸形/超时、数据库断连恢复与四尺寸 smoke/axe。完整沙箱攻击、升级和错误态可访问性仍不在此完成声明中。

仓库 Node 检查 26 项通过（常规 18、进程 4、数据库验收工具 4）；浏览器测试严格 TypeScript、eslint、Prettier 通过。本轮未改平台/模板源文件，未重跑其编译，不把 EB22 的 Java/模板前端用例数列为本轮运行结果。

增量、TDD 与审查流程发现并修正：

1. 启动器最初只能匹配报告中的 A 地址；新增第二监听地址用例先失败，最小实现后通过。反转配置地址匹配判断使两项启动测试失败，恢复后通过。
2. 反转第二 origin 放行判断使真实浏览器在 B 导航失败，证明双实例用例实际访问第二进程而非 A 的另一个标签。
3. 首次完整回归为 11 通过 / 1 失败：真实重启占用了默认 30 秒用例总预算，重试点击阶段被测试框架中断。只为两个含 JVM 重启的既有用例设置 150 秒总预算（沿用启动器 120 秒边界并留浏览器阶段时间）；应用 8 秒保存确认、请求/断言超时、无自动重试和数据检查未放宽。后续完整 12 项与加入安装误配后的 13 项通过。
4. 配置副本只允许改变有效安装 ID 行。保持原 ID 的变异、注释内同名字段反例均使测试失败；修正为锚定配置行替换，确认成功/失败都会清理副本、原配置内容保持不变。边界修正后安装误配定向复验 1 项通过（1.0 分钟），使用独立 `test-results/standalone-installation` 输出目录，不覆盖完整验收截图。

安装拒绝包含应用脱敏事件；框架 `SpringApplication` 还记录一次由该固定拒绝异常构成的启动栈。本次检查不含 JDBC URL 或数据库密码，不宣称所有启动失败均无栈；EB22 中零原始数据库异常栈的结论仅对应其故障恢复运行场景。

截图已查看，位置为 `frontend/test-results/standalone/isolation-same-host-instan-76e54-rate-in-one-browser-context-chromium/instance-a.png`、`instance-b.png`。截图仅含手工验收数据；不保存浏览器存储、Cookie、CSRF 或原始 trace。最终清理只结束自有 Java/Chrome/连接闸门和精确临时配置，不停止共享 PostgreSQL、不清空运行库。

复验使用上节已验证的报告，另显式提供 B 配置（以下在仓库根执行，Java/Node 使用计划锁定版本；Docker CLI 需在 PATH 中）：

```sh
atoms_repo="$PWD"
cd frontend
STANDALONE_VERIFICATION_REPORT="$atoms_repo/.local/standalone/verification-4oZItT/verification.json" \
STANDALONE_RUNTIME_CONFIG="$atoms_repo/.local/standalone/runtime-a.properties" \
STANDALONE_SECONDARY_RUNTIME_CONFIG="$atoms_repo/.local/standalone/runtime-b.properties" \
./node_modules/.bin/playwright test --config playwright.standalone.config.ts --project=chromium
```

五轴复核：共享浏览器存储而非两组隔离浏览器来验证隔离；启动目标、原配置、临时副本与数据库证据边界清晰；没有新增依赖、管理员 SQL 或生产控制接口；输入/文件大小/连接数量/启动时间均受既有约束限制。变更仅为仓库验收工具和对应证据。

EB23d–e / C-EB10 保持未完成：下一步是真实源码修改重建、旧页面新意图拒绝与旧成功意图确认，以及最终 JAR 的沙箱攻击/错误态键盘与可访问性。下载 API/工作台导出按钮、任意后端生成、托管构建运行仍不包含在基础工程模块中。本轮未推送、部署或更新原平台交付文档。

## EB23d · 真实源码升级验收

### 固定升级工程（EB23d1）

只为仓库验收入口增加 `--fixture upgraded`：固定修改待办页面标题，不接受任意路径/源码/命令。沿用同一个来源版本 UUID、初始空待办和全部模板文件，变化仅为 `application/view.html` 与相应的 `bundle-manifest.json`。这样证明运行身份来自实际构建字节，不靠伪造版本标记。测试夹具仍不进入正式平台 JAR。

- 红→绿：新增 Java 3 项中的升级用例先因不支持参数失败；新增 Node 用例先因 CLI 拒绝参数失败。实现后分别全通过。反转 CLI 的 `upgraded` 等值判断，新用例再次失败；恢复后仓库 Node **19 项**通过。
- 平台 `backend/`：Java 17 `./mvnw -B clean verify`，**80 单元 + 52 集成**，无跳过，退出 0（1:38）。平台测试仍使用既有专用测试库和模型模拟服务，不调用真实模型。
- 根目录 `node scripts/verify-standalone.mjs --config-dir .local/standalone --output-parent .local/standalone --fixture upgraded`：**24 脚本、68 前端、129 后端单元 + 60 集成**全通过；冻结安装、typecheck、lint、前端构建、Maven clean verify，以及工程目录外 Node/Java 两种启动与自有进程停止全部成功。
- 报告：`.local/standalone/verification-rKkiBw/verification.json`；新工程 `engineering-bundle-3HzoM7`，111 文件、545,949 字节、最大 121,072 字节。
- 新 bundle ID：`9e7a9df53f57fba688f9981e5b9a2a5134a3060443f2ceb9541273d069f9711f`；runtime ID：`a44d7fe0172900695c21c68ca5e7b43804a5b9db15929fe95dfeada810aa4f14`；JAR SHA-256：`1c4f3c24eb965719bd3daba6deb3a203cc723e10a6f8986a6e2655b306210db1`。
- 原 `verification-4oZItT` 完整保留。复核两份清单的所有源文件 hash 和实际 JAR hash，原产物未变；同来源 UUID、新 runtime ID。一次性复核命令曾把 manifest 元数据误传给接收字节 Map 的函数；修正为逐文件摘要核对后通过，不涉及产品代码变更。

五轴复核：固定变体、输入边界和构建检查均未扩权，复用既有组装器；没有新依赖、配置/迁移或生产接口。

### 跨构建浏览器行为（EB23d2）

`withBuild` 只切换测试持有的进程，先校验显式报告、JAR hash 和原监听地址，再使用**同一份外部配置、安装 ID 与运行数据库**启动新 JAR。嵌套切换被拒绝；正常结束或回调抛错均恢复原测试产物，worker 清理等待正在进行的切换后才移除私有代理配置。这是验收生命周期，不是平台部署接口，也不承诺任意 schema 变更的回滚。

- 保留旧页面，升级后新意图得到实际 **409 / `STALE_APP_VERSION`**。旧标题和输入草稿保留，数据库仍是原 revision=1、1 行状态、1 行成功幂等记录。新标签页沿用同一访客，呈现升级标题和已有数据，可继续保存为 revision=2。
- 原页面的首次保存先真实提交，再丢弃响应。切换新 JAR 后，新标签页先写入 revision=2，旧页面再显式重试。两次旧请求的 key/body 完全相同且仍携带旧 runtime ID；服务端两次均确认 revision=1。数据库保持新数据和 revision=2、1 行状态、2 行幂等记录，没有重复保存或回退。恢复原验收产物后再次确认相同访客和两条数据。
- 两项定向 Chrome 用例通过（44.3 秒、retries=0）；最终完整 Chrome **15 项通过（3.0 分钟，retries=0，退出 0）**，新增页面未捕获异常为 0；TypeScript 严格检查、ESLint、Prettier 通过。包含四尺寸/axe、断库恢复、安装误配、同浏览器双实例、真实重启、三种未知结果重试、多标签页冲突及源码升级。
- 红→绿：原 `readEvidence` 不支持第二份报告时，用例因 runtime ID 未改变而失败。首次真实 409 断言误读为顶层 `code`；核对 `ApiError` 后修正为 `error.code`，并把断言放到真实响应已交付后的测试控制流中。
- 该异步断言失败还暴露了 worker 清理先删配置、切换仍尝试恢复进程的竞态。增加切换 Promise 的等待后，再注入一次异步路由失败探针：测试按预期失败，但无 `CONFIG_READ_FAILED`，自有子进程和代理配置正常收尾。探针已移除，未把失败标为通过。回调失败后的恢复、嵌套切换拒绝保留为常规断言。
- 反转新增的单切换判断后，真实浏览器用例失败；已恢复。所有实验仅修改本次新增验收逻辑，不修改成品 JAR 或数据。
- 已实际查看截图：`stale-page-draft.png`、`upgraded-preserved-data.png`、`newer-data-after-old-replay.png`，位于 `frontend/test-results/standalone/upgrade-*/`。旧页面显示版本冲突提示和草稿，新页面显示升级标题与持久待办。

完整浏览器复验从仓库根目录开始，先设置上述 Java 17 / Node 24 工具路径：

```sh
atoms_repo="$(pwd)"
cd "$atoms_repo/frontend"
STANDALONE_VERIFICATION_REPORT="$atoms_repo/.local/standalone/verification-4oZItT/verification.json" \
STANDALONE_UPGRADE_REPORT="$atoms_repo/.local/standalone/verification-rKkiBw/verification.json" \
STANDALONE_RUNTIME_CONFIG="$atoms_repo/.local/standalone/runtime-a.properties" \
STANDALONE_SECONDARY_RUNTIME_CONFIG="$atoms_repo/.local/standalone/runtime-b.properties" \
./node_modules/.bin/playwright test --config playwright.standalone.config.ts --project=chromium
```

报告路径必须替换为本机真实完成全门禁构建的输出，不能伪造。缺少升级报告会明确失败，不静默跳过。现有数据库、稳定安装身份及原源码工程均不重置。

收尾复核：两份原始工程源文件及 JAR hash 未变，18089/18090 无监听，自有数据库代理/错误安装临时配置目录剩余 0；共享 PostgreSQL 仍为 Up 5 days (healthy)。本轮只提交本地功能分支，未推送、部署或更新原平台交付文档。

边界：本次仅证明同数据结构的固定应用源码升级，不包含数据库 schema 升降级或任意生成后端。EB23e 安全/错误态可访问性、EB24–EB25 及 C-EB10/C-EB11 仍待完成；下载 API/按钮和托管构建运行属于后续模块。

## EB23e1 · 最终 JAR 的真实沙箱边界

仍使用 EB22 的 `verification-4oZItT` 成品，未修改工程、JAR、安全响应头或平台服务。仓库固定的 `sandbox-probe.js` 只在测试浏览器的真实 iframe 中执行；不读取凭据，URL 仅指向本机 `/security-probe/`，另有网络拦截护栏。探针不进入导出模板。

- 真实文档 origin 为 `null`；读取父页面 DOM、导航宿主均被 `SecurityError` 拒绝；弹窗返回 `null`。
- fetch、外部脚本、Blob worker 均被 CSP 阻断，观察到 `connect-src` / `script-src-elem` / `worker-src` 违规事件；护栏没有捕获任何漏出的请求。
- 探针之后正常保存仍成功；应用自行导航到 `about:blank` 后旧 iframe/连接关闭，页面明确提示手动重载。接受真实确认弹窗后恢复应用和已保存待办；SQL 为 revision=1 / stateRows=1 / idempotencyRows=1。
- 直接打开 `/api/app/document` 仍为 opaque origin、HTTP `sandbox allow-scripts`，没有宿主桥接就无法保存；外壳/API 保持无 inline 的 CSP 和 `X-Frame-Options: DENY`。

测试调试记录：初版探针在 `addScriptTag` 完成前触发预期 CSP 拒绝，Playwright 将其归为脚本装载失败，改为脚本装载后点击按钮运行；worker 拒绝在 Chromium 中由异步 error 事件报告，不能用构造函数是否抛错判断；手动恢复需要显式接受确认弹窗，默认自动关闭会取消恢复。以上均修正测试方法，没有放宽产品策略。

代码审查按正确性、可读性、边界、安全及有界等待检查；反转新增弹窗判定 `=== null` 后用例实际失败（收到 allowed），恢复后 `security.spec.ts` **2/2 通过（27.8 秒，retries=0）**；严格 TypeScript、ESLint、Prettier 均通过。截图 `frontend/test-results/standalone/security-real-sandbox-reje-9a2f1-avigation-closes-the-bridge-chromium/navigation-closed.png` 已实际查看：错误原因与重载入口清晰，保存内容恢复由实际浏览器/SQL 断言确认。EB23e2、C-EB10 尚未完成。

复验沿用上一节完整浏览器命令并附加 `security.spec.ts`。本切片仅本地分支，不推送、不部署。

## EB23e2 · 错误态键盘恢复与可访问性验收

新增 `accessibility.spec.ts` 与 `accessibility.ts`，在 **320 / 1440px** 各执行三条真实错误路径，不变更模板、依赖或安全策略：

| 场景 | 实际恢复与数据证据 |
|---|---|
| 同访客双标签冲突 | 保留输入，Tab 到“读取最新数据”、Enter 确认后再保存；原待办与草稿均存在，SQL revision=2 / stateRows=1 / idempotencyRows=2 |
| 已提交但响应丢失 | 真实 PUT 已返回 200 后阻断响应交付；未知提示和草稿保留，键盘重试复用相同 key/body，SQL 仍为 1 / 1 / 1，无重复写入 |
| 数据库不可用时启动 | 仅断开测试自有 JDBC 代理，实际 health 503；页面显示原因和重试入口，无 iframe；恢复连接后 Enter 重试成功，焦点保留，新访客 revision=0 |

每条恢复路径使用真实 Tab / Shift+Tab / Enter，检查焦点、轮廓、视口内可见性与宿主/iframe 无横向溢出；不使用 JS focus 跳过键盘路径。读取草稿/重试后，Shift+Tab 可从沙箱返回宿主重载按钮。初版试图用正向 Tab 绕过页面末尾返回顶部，失败于宿主按钮失焦；改为直接验证向前进入、向后退出沙箱的用户路径，没有增加重试、等待预算或修改产品焦点行为。

axe 4.13 保持现代跨 frame 扫描，WCAG 2 A/AA 与 2.1 A/AA **violations 为 0**；通过 `label/#title`、`button-name/#latest或#retry` 的嵌套 selector 证明确实扫描到 opaque iframe。扫描结果仅附加规则 ID/目标节点，不导出凭据。自动化检查不代表完整 WCAG 认证或读屏器人工验收。

首次额外检查全部 incomplete 时失败：连接成功的 `.status-marker` 字符 `✓` 被 axe 标为 `color-contrast/nonBmp`，需人工复核而非已确定的违规。已核对实际截图、`aria-hidden=true`、相邻的“应用信息已就绪”文字，以及前景 `#d9f47e` / 背景 `#1a1e1b`（对比度 **13.8037:1**）。测试严格锁定这一节点/原因/颜色；其他 incomplete 仍失败，数据库离线页面要求 incomplete=0，没有 exclude 或 disableRules。未把“无违规”写成“没有人工复核项”。

五维自审未发现需修改产品的缺陷。反转新增的 iframe selector 深度判断后，用例实际失败于“必须审计 frame 内 #title”；恢复后完整 Chrome **23/23 通过（3.9 分钟，retries=0，退出码 0）**，新增 8 项 = 沙箱 2 + 错误恢复 6。原四尺寸正常态、故障/重试、安装误配、同机双实例、进程重启和真实升级全部复验。严格 TypeScript、ESLint、Prettier、git diff 检查通过；本轮没有修改生产代码，不重复宣称已重跑平台 Maven 全门禁（留待 EB25）。

已实际查看六张错误态截图，位于 `frontend/test-results/standalone/accessibility-*/` 下的 `conflict-keyboard.png`、`unknown-keyboard.png`、`offline-keyboard.png`；窄屏文案可换行，键盘恢复按钮滚入视口且轮廓可见。复验沿用 EB23d 的完整浏览器命令；只检查新增错误路径时追加 `accessibility.spec.ts`。截图与临时报告不提交仓库。

收尾：原版/升级版 111 个源文件及 JAR hash 与原报告一致；18089/18090 无监听，`db-link-*` / `wrong-installation-*` 临时配置剩余 0；共享 PostgreSQL 仍为 Up 5 days (healthy)。EB23/C-EB10 完成，EB24–EB25/C-EB11 和下载/托管运行保持未完成；仅本地功能分支提交，没有推送、部署或更新原平台交付文档。

## 已核对的官方依据

- [Playwright 无障碍检查](https://playwright.dev/docs/accessibility-testing)、[axe 对比度规则](https://dequeuniversity.com/rules/axe/4.13/color-contrast)、[WCAG 对比度说明](https://www.w3.org/WAI/WCAG21/Understanding/contrast-minimum.html)：扫描显露出的错误 UI、保留人工复核项并核对实际色值；不能用自动检测结果代替完整人工可访问性验收。

- [Playwright 脚本装载](https://playwright.dev/docs/api/class-frame#frame-add-script-tag)、[确认弹窗](https://playwright.dev/docs/dialogs)：真实文档内装载探针，恢复时显式接受确认；没有替换真实文档响应。
- [CSP sandbox](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Content-Security-Policy/sandbox)、[CSP 违规事件](https://developer.mozilla.org/en-US/docs/Web/API/Element/securitypolicyviolation_event)、[Worker error](https://developer.mozilla.org/en-US/docs/Web/API/Worker/error_event)：分别核对 opaque origin、安全策略拒绝及 worker 异步失败观察方式。

- [RFC 6265 §8.5](https://datatracker.ietf.org/doc/html/rfc6265#section-8.5)：Cookie 不按端口隔离，不能把双进程当作浏览器凭据隔离证明。
- [Playwright 同 context 新页面](https://playwright.dev/docs/api/class-browsercontext#browser-context-new-page)、[WebError](https://playwright.dev/docs/api/class-weberror#web-error-error)、[用例总超时](https://playwright.dev/docs/test-timeouts#test-timeout)：在共享存储下观察多个页面，区分 context 错误事件与页面事件，以及基础设施总时长与业务断言的各自预算。

- [Playwright worker fixture](https://playwright.dev/docs/test-fixtures#worker-scoped-fixtures)、[Route.fetch](https://playwright.dev/docs/api/class-route#route-fetch)：自有测试生命周期与真实请求转发；项目使用 1.63.0，无自动重试。
- [Node 24.20 socket.destroy](https://nodejs.org/download/release/v24.20.0/docs/api/net.html#socketdestroyerror)、[PostgreSQL 17 只读事务](https://www.postgresql.org/docs/17/sql-set-transaction.html)：仅中断自有连接与只读证据查询。
- [Spring 6.2.19 TransactionTemplate](https://github.com/spring-projects/spring-framework/blob/v6.2.19/spring-tx/src/main/java/org/springframework/transaction/support/TransactionTemplate.java)、[Boot 3.5 日志级别](https://docs.spring.io/spring-boot/3.5/reference/features/logging.html#features.logging.log-levels)：核对 rollback 失败的原始异常日志来源，使用精确 logger 配置且保留脱敏应用事件。

- [Maven 显式资源 includes](https://maven.apache.org/plugins/maven-resources-plugin/examples/include-exclude.html)、[JAR 默认排除开关](https://maven.apache.org/plugins/maven-jar-plugin/jar-mojo.html#addDefaultExcludes)：两阶段保留清单资源（项目实际 JAR Plugin 3.4.2，开关自 3.4.0 可用）。
- [Java 17 ClassLoader.getResources](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/ClassLoader.html#getResources(java.lang.String))、[Boot 3.5 PropertiesLauncher](https://docs.spring.io/spring-boot/3.5/specification/executable-jar/property-launcher.html)：检测重复 classpath 资源并从正式 Boot JAR 装载，测试入口保持在 test-classes。
- [Maven Wrapper 3.3.4 原始声明](https://github.com/apache/maven-wrapper/tree/maven-wrapper-3.3.4)：模板保留上游 LICENSE/NOTICE。
- [Node 24.20 监听选项](https://nodejs.org/download/release/v24.20.0/docs/api/net.html#serverlistenoptions-callback)：显式回环独占端口预检，只管理自有进程。

- [Node 24.20 child_process](https://nodejs.org/download/release/v24.20.0/docs/api/child_process.html#optionsdetached)：固定 argv、无 shell、自有 POSIX 进程组及保持父子生命周期；实现不调用 unref。
- [Maven 资源目录](https://maven.apache.org/plugins/maven-resources-plugin/examples/resource-directory.html)：用显式资源 profile 接入新暂存目录，禁用过滤以保留 hash 对应字节。
- [Boot 3.5 静态资源](https://docs.spring.io/spring-boot/3.5/reference/web/servlet.html#web.servlet.spring-mvc.static-content)：JAR classpath 静态内容；本项目显式 MVC 路由仅返回已校验的快照，没有全路径 SPA 回退。

- [HTML Channel Messaging](https://html.spec.whatwg.org/multipage/web-messaging.html#channel-messaging)、[MDN postMessage](https://developer.mozilla.org/en-US/docs/Web/API/Window/postMessage)：MessageChannel 生命周期与 opaque origin 的目标限制；仅在验证来源后转移能力端口，不向通道发送宿主凭据。
- [Vue onUnmounted](https://vuejs.org/api/composition-api-lifecycle.html#onunmounted)：组件销毁时清理副作用；本实现使用 onBeforeUnmount 先关闭连接。
- [Playwright Response](https://playwright.dev/docs/api/class-response)、[waitForResponse](https://playwright.dev/docs/api/class-page#page-wait-for-response)：在触发动作前监听响应并及时读取响应体；`headers()` 不包含完整 Cookie 相关头，检查 Set-Cookie 使用 headerValue。

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

## PE01–PE05 / EB24–EB25 · 实际工程下载与交付（2026-10-13）

用户确认“完成工程下载按钮”“一次完成”后，按 `SPEC-project-export.md` 连续实现；没有逐阶段停下要求再次授权，没有扩大到远程部署或托管执行器。下载使用保存后的不可变版本，不读取真实状态，不生成新版本，也不调用模型。

### 实现及规格对照

| 契约 | 实现与验证 |
| --- | --- |
| 确定性、有界 ZIP | `EngineeringZip` 使用 Java 标准库 STORED、固定时间/有序条目、大小和 CRC；路径重复/穿越、目录冲突及体积拒绝测试；写出前后核对 ZIP 精确长度 |
| 先鉴权，当前/历史版本均可下载 | `EngineeringExportService` 调用现有 ArtifactService；真实 PostgreSQL HTTP 测试验证 401/404、不同项目版本混用、V1/V2 来源与内容、项目指针不变 |
| 安全模板、有限并发和失败 | TemplatePolicy/TemplateCatalog 固定白名单；两槽位打包，503 繁忙；422 契约/500 打包错误脱敏，释放槽位；canary 不进入错误或 ZIP |
| 有界二进制客户端 | 9 MiB 实读上限、Content-Length 核对、64 KiB 错误读取、ZIP MIME/签名、30 秒超时、拒绝重定向；API 单测验证超限/截断/异常响应 |
| 工作台真实交互 | `EngineeringDownload.vue` 接入 ArtifactPanel；当前选中版本、禁用理由、重复点击保护、可重试失败、requestId；组件测试覆盖切换/卸载取消、晚到响应和 Blob URL 释放 |
| 下载的是真实工程 | Chrome 键盘操作下载 V2/V1，检查文件名、111 条目、manifest/源码/初始数据和私有状态 canary 排除；不把浏览器 mock 的 ZIP 当成成功证据 |
| 真正可构建和独立运行 | 实际网页 ZIP 解压到新目录；按 README 显式 chmod 后运行全构建，Node/Java 两个入口均从工程外启动；真实 JAR 的 23 项 Chrome 验收 |
| 无平台回归 | 普通工作台 14 项、运行时工作台 20 项、平台完整 Maven 和前端门禁均通过；无平台迁移/依赖变化 |

### 真实下载包与配额

包来源是测试工作台已保存的手工待办 V2，非真实用户项目，也非额外模型调用。固定模板 106 文件，加应用 4 文件与 bundle manifest 共 111 文件；单文件最大 121072 bytes，总源文件 546106 bytes，ZIP 564274 bytes。相对 128 文件 / 2 MiB 单项 / 8 MiB 总源 / 9 MiB ZIP 的预算分别仍余 17 文件、1976080 / 7842502 / 8872910 bytes。TemplateCatalogTest 另将当前模板锁定为 106 文件、最大项低于 256 KiB、总量低于 600 KiB，维护时不能悄悄放宽。

本轮模板只补充解压后的执行位说明，许可证/白名单和固定依赖均保留。正式平台 JAR 内加载模板，导出物不包含平台工程、配置、缓存或测试台资源。首次管理员建库 SQL 与现有专用库权限/安装契约一致，文档已复核；本轮复用先前准备的独立运行 A/B 和测试库，没有再次创建角色、重置密码或重演管理员建库操作。构建测试只清理其明确授权的专用测试表，运行 A/B 数据不被构建重置。

留存的本地证据（均忽略提交，不上传测试会话或私有配置）：

- `.local/standalone/verification-ctQhLN/downloaded-engineering.zip`
- `.local/standalone/verification-ctQhLN/engineering-bundle-t5QA8D/`
- `.local/standalone/verification-ctQhLN/verification.json`
- 来源版本 `210acadc-e37a-4ffd-805b-346dbf139c85`
- ZIP SHA-256 `b81894fa3034a54b9cd0fc77239041b2b19ddcc8ce29618de0b82194ebfc3cda`
- bundleId `69602719f62fc4c21fe6c5f71f1cf9b456325393bd7ae4e5567464d41f72efd7`
- loadedRuntimeId `7fd42cd3ae35dd48371eebe7cf90d1e6b82513e0dae1181201f7054dd5275fea`
- JAR SHA-256 `faab357e072550b6cd1cbd9d80e5f7b4e966afdb1b56026a663271bd63a22dbb`

完整浏览器回归后重新核对 ZIP SHA、全部源码字节、manifest 和 JAR SHA，均与报告相同，不使用旧包为变更后的模板背书。收尾检查 8080/8081/5173/5174/18089/18090 均无监听，自有测试服务已停止；共享 `atoms-demo-postgres` 仍为 Up 5 days (healthy)，没有停止 Docker 或删除数据库卷。

### 命令与结果

平台 `backend/` 运行 `./mvnw -B clean verify`：86 项单元测试 + 54 项真实 PostgreSQL 集成测试通过，无跳过；平台前端 typecheck、lint、format:check、build 及 132 项 Vitest 通过。独立浏览器目录另行执行 ESLint/Prettier，未因默认 glob 未包含该目录而遗漏检查。

平台 `frontend/` 的 `npm run test:e2e:runtime -- --project=chromium`：20 项通过（1.5 分钟），含新增工程下载 2 项。普通 `npm run test:e2e -- --project=chromium`：14 项通过（41.4 秒），普通后端明确用专用测试 profile，关闭生成 Worker。下载按钮在 320/390/768/1024/1440px 检查布局和 axe，已查看 390/1440px 截图，控制台无错误/警告。503 后手动重试使用真实后端下载，原输入和预览不丢失。

根目录验证真实下载 ZIP 的命令形态如下，路径替换为本机已有的隔离配置和实际下载文件；不用于导入、执行未知用户 ZIP：

```sh
node scripts/verify-standalone.mjs \
  --config-dir /absolute/path/standalone-config \
  --output-parent /absolute/path/verification-output \
  --downloaded-zip /absolute/path/current-engineering.zip
```

`downloaded-fixture.mjs` 在执行任何文件前验证 ZIP 条目/CRC、全部源码 hash，以及所有模板字节与当前仓库、应用字节与经过审阅的手工待办夹具一致。只创建全新目录，拒绝归档链接/权限继承；不是生产 ZIP 导入能力，不因文件自报 hash 正确就允许运行任意程序。验证脚本及发布辅助测试共 11 项通过。

下载包内的脚本 24 项、独立前端 68 项及其类型/lint/构建、独立后端 129 项单元 + 60 项 PostgreSQL 集成测试全部通过。冻结依赖安装遵循 ignore-scripts，不跳过检查；按 README 实际 `chmod u+x backend/mvnw`，再完整构建。Node 与直接 Java 均可从工程外启动、响应健康与页面，再停掉自有进程。

下载包对应报告作为 `STANDALONE_VERIFICATION_REPORT`，配上原独立 A/B 配置和已验证升级包报告，执行：

```sh
npx --no-install playwright test --config playwright.standalone.config.ts --project=chromium
```

最终 **23/23 通过（3.9 分钟、retries=0、退出码 0）**。涵盖保存/刷新、真实进程重启、同浏览器双实例、访客隔离、数据库断连、安装 ID 误配、冲突、未知响应重试、升级与旧意图重放、沙箱和键盘/axe。除明确的双实例用例外仅允许独立应用 origin，平台和外网请求均被禁止并断言零越界。正常态新包 320px 截图已查看；它保留下载项目自己的标题，不替换成构建夹具标题。

### 失败、修复和审阅

- 新 ZIP/API/客户端/组件按失败用例后实现，阶段性提交；未删除、跳过旧测试或降低检查阈值。
- 测试服务器最初无法选择主类：新增 BundleFixture 与 FixtureApplication 都有 main。运行时和发布浏览器配置明确指定 `dev.atoms.fixtures.FixtureApplication`，依据当前 Boot Maven 插件的 main-class 参数；没有把测试夹具放入正式产物。
- sandbox 不能绑定测试端口时，按权限流程以原命令重跑；不复用个人 Chrome 资料。浏览器进程设置 loopback NO_PROXY，避免系统代理干扰本机测试。
- 独立只读代码审阅发现：切换版本取消请求后，旧错误体可能晚到并触发 session-invalidated。新增用例先复现回调错误触发 1 次，在统一错误处理前检查 caller abort 后为 0 次；132 项前端完整回归通过。审阅未报告其他 Required/Critical 问题。
- 临时反转二进制读取的超限判断，相关 13 项用例中 8 项实际失败；恢复后完整前端 132 项通过。没有留下变异或提高传输上限。
- 实际 ZIP 首轮完整浏览器为 19 通过 / 4 失败，四个尺寸用例写死旧组装夹具的“独立工程待办”；新包正确显示“手工测试待办”。改成从已验证工程 manifest 读取期望标题，同时精确核对 API 与 H1，不放宽为任意 heading、不改工程数据；重跑 23 项全通过，类型/lint/格式检查通过。

本次按增量实现、TDD、浏览器验证与安全审阅技能补齐了取消竞态、授权和真 ZIP 构建证据；Chrome DevTools MCP 不可用，使用已有隔离 Playwright + Chrome。未新增依赖或发起真实模型调用，没有推送、部署或变更线上计费额度。C-EB11 人工验收留待用户；`workspace-build`、`app-runtime`、`runtime-access` 仍未实现。

新增实现依据：[Java 17 ZipOutputStream](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/zip/ZipOutputStream.html)、[ZipEntry](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/zip/ZipEntry.html)、[AbortSignal.any](https://developer.mozilla.org/en-US/docs/Web/API/AbortSignal/any_static)、[读取取消](https://developer.mozilla.org/en-US/docs/Web/API/ReadableStreamDefaultReader/cancel)、[Blob URL 释放](https://developer.mozilla.org/en-US/docs/Web/API/URL/revokeObjectURL_static)。
