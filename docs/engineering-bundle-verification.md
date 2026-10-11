# engineering-bundle · 实施与验证记录

日期：2026-10-11。分支：`feature/standalone-engineering`。依据：[规格](../SPEC-engineering-bundle.md)、[计划](../tasks/plan.md)、[执行清单](../tasks/todo.md)。

## 当前交付边界

EB01/EB02 的纯内存工程组装及边界校验已实现且通过后端完整回归；EB03 的独立后端模板骨架通过 Java 17 编译。已到达 C-EB01 检查点。没有下载 HTTP API、工作台按钮、真实模板装载器、独立数据接口、构建执行器或托管实例，不宣称完整工程已可导出运行。线上服务、原平台源码/迁移及原交付文档没有改动。

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

EB03 模板是未公开的编译骨架：只扫描 `dev.atoms.standalone`，沿用最小所需后端依赖，不依赖平台工程；配置要求独立数据库变量、默认 loopback:8088、独立 schema、显式实例 ID，禁止自动 SQL 初始化和 Flyway clean。配置初始化/Java 入口校验（含本地 HTTP Cookie 切换）仍在 EB04，会话与业务数据接口仍在后续任务，因此没有尝试把骨架称为可用服务。真正导出目录的构建和重启验收仍需 EB18–EB23。

原前端源码未变，本轮尚未重跑其 UI/E2E；不复用旧结果冒充本轮通过。没有推送、合并或部署。

## 已核对的官方依据

- [Java 17 Record](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Record.html)：可变组件需要防御性复制，复制后仍需保持值语义。
- [Java 17 MessageDigest](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/security/MessageDigest.html)：使用平台 SHA-256，避免新增摘要依赖。
- [Jackson 2.21 JsonNode 源码](https://github.com/FasterXML/jackson-databind/blob/2.21/src/main/java/com/fasterxml/jackson/databind/JsonNode.java)：deepCopy 与 properties 契约；新代码没有使用已废弃的 fields 方法。
- [Spring Boot 3.5 Maven Plugin](https://docs.spring.io/spring-boot/3.5/maven-plugin/using.html)：后续模板沿用 3.5.16 parent 的 Java 17、依赖管理和 repackage 配置，不升级平台依赖。
