# engineering-bundle · 实施与验证记录

日期：2026-10-11。分支：`feature/standalone-engineering`。依据：[规格](../SPEC-engineering-bundle.md)、[计划](../tasks/plan.md)、[执行清单](../tasks/todo.md)。

## 当前交付边界

EB01/EB02 的纯内存工程组装及边界校验已实现且通过后端完整回归；EB03 的独立后端模板骨架通过 Java 17 编译；EB04 的配置初始化与 Java 启动校验已完成。下一步 EB05 专用测试库引导。没有下载 HTTP API、工作台按钮、真实模板装载器、独立数据接口、构建执行器或托管实例，不宣称完整工程已可导出运行。线上服务、原平台源码/迁移及原交付文档没有改动。

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

## 已核对的官方依据

- [Java 17 Record](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Record.html)：可变组件需要防御性复制，复制后仍需保持值语义。
- [Java 17 MessageDigest](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/security/MessageDigest.html)：使用平台 SHA-256，避免新增摘要依赖。
- [Jackson 2.21 JsonNode 源码](https://github.com/FasterXML/jackson-databind/blob/2.21/src/main/java/com/fasterxml/jackson/databind/JsonNode.java)：deepCopy 与 properties 契约；新代码没有使用已废弃的 fields 方法。
- [Spring Boot 3.5 Maven Plugin](https://docs.spring.io/spring-boot/3.5/maven-plugin/using.html)：后续模板沿用 3.5.16 parent 的 Java 17、依赖管理和 repackage 配置，不升级平台依赖。
- [Node 24.20 文件打开标志](https://nodejs.org/download/release/v24.20.0/docs/api/fs.html#file-system-flags)：使用排他创建而非先检查存在再覆盖；不承诺网络文件系统的排他语义。
- [Spring Boot 3.5 SpringApplication](https://docs.spring.io/spring-boot/3.5/api/java/org/springframework/boot/SpringApplication.html)：自定义 Environment 与禁止命令行属性重新覆盖；本实现的预检发生在其 run 之前。
- [Spring Framework StandardEnvironment](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/core/env/StandardEnvironment.html)：默认系统属性/环境属性源可以显式移除。
- [Java 17 Properties.load](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Properties.html#load(java.io.InputStream))：遵循标准 ISO-8859-1 和 Unicode 转义，避免重复解析产生不同配置值。
