# Spec · engineering-bundle：可独立运行的固定工程

状态：2026-10-11 用户已确认规格；[开发计划](tasks/plan.md)待审，未实现。
归属：[已确认能力地图](CAPABILITY-MAP.md)。本文件只定义基础工程模块，不代替下载、执行器、实例管理或平台预览模块的规格。

## 1. 目标与完成定义

把一个已经保存的 `web-static-v1` 版本组装为 Vue + Spring Boot 17 + PostgreSQL 工程。它可以放进全新目录，使用自己的配置与数据库构建启动，且运行时不再连接 Atoms 平台。

主要使用者是导出应用后继续开发或自托管的人，以及后续平台构建执行器。输出的是完整的固定模板工程，不是通用代码生成环境：Vue 提供应用外壳，已有 HTML/CSS/JS 在受限 iframe 内执行，固定 Spring Boot 后端提供访客会话和 JSON 状态服务。

完成必须有真实证据：待办应用操作保存 → 刷新 → 停止并启动该应用 → 同一有效访客恢复数据；第二访客看不到第一人的数据；测试浏览器阻断平台地址后上述流程仍成功。仅生成目录、ZIP 或截图不能算完成。

## 2. 依据与保留的假设

检查的现有实现：

- `backend/.../artifact/ArtifactValidator.java`：只允许 `view.html`、`styles.css`、`main.js`，源码 120 KiB，初始 JSON 状态 256 KiB。
- `backend/.../preview/RuntimeDocumentService.java` 与 `RuntimeDocumentPolicy.java`：服务端净化 HTML、编码内联源码，生成代码仅在 opaque-origin iframe 执行。
- `frontend/src/runtime/{mountFrame,bridge,protocol}.ts` 与 `backend/src/main/resources/runtime/bootstrap.js`：受限 MessageChannel SDK，包含未知保存结果的同键重试。
- `backend/.../appdata/AppStateService.java`：访客隔离、修订号冲突检查、事务幂等与失败不覆盖。

本模块保留上述安全和一致性语义，但将会话、状态和幂等记录放进导出应用自己的数据库。不会复制整个平台后端、生成队列、模型网关或项目管理 API。

已确认取舍：一个工程对应一个应用；保留按访客隔离的数据模型；默认仅携带初始数据；原有应用代码不自动变成 Vue SFC；不会为了独立运行把数据降级到 localStorage。

## 3. 技术栈与交付结构

沿用当前仓库已锁定版本，不因为导出功能升级平台依赖：Java 17（本机 17.0.5-oracle）、Spring Boot 3.5.16、Vue 3.5.43、TypeScript 5.9.3、Vite 8.3.3、Node 24.20.x、npm 11.19.x、Maven Wrapper 3.9.11、PostgreSQL 17。具体前端依赖必须有独立且受版本控制的 lockfile；不能把整个管理平台的依赖复制进去充数。

拟交付目录；本文件中的新增路径和命令是实现契约，当前尚不存在：

```text
generated-app/
  bundle-manifest.json           # 格式、模板、源码来源及白名单文件 hash
  application/
    manifest.json                # 应用标题、摘要、初始状态、状态格式
    view.html
    styles.css
    main.js
  frontend/
    src/                         # Vue 外壳、SDK 宿主、API 客户端
    package.json
    package-lock.json
    vite.config.ts
    ...                          # 类型、lint、测试配置
  backend/
    src/main/java/dev/atoms/standalone/
    src/main/resources/db/migration/
    src/test/
    pom.xml
    mvnw
    .mvn/wrapper/
  scripts/
    build.mjs
    start.mjs
    init-config.mjs
  config/application.properties.example
  README.md
  THIRD-PARTY-NOTICES.md
  .gitignore
```

仓库中模板拟存于 `templates/standalone-v1/`，平台内的纯组装逻辑存于 `backend/.../engineering/`，测试放在对应 test 包；只打包显式允许的模板文件，不把模板测试目录、开发配置和产物缓存自动放入导出物。

Vue 产物最终与应用资源一起进入 Spring Boot JAR，由一个端口提供页面与 API。开发时可用 Vite 同源代理；运行成品只需要 Java 17 和已配置的 PostgreSQL，不要求 Node、Maven、平台进程或模型 API。

首次构建允许访问明确的依赖仓库，不承诺离线安装依赖。运行时不加载 CDN 字体、脚本或第三方 SDK。导出工程可由开发者修改后重新构建；修改后的工程不是平台已经验证过的新版本，也不自动支持重新导入。

## 4. 工程组装契约

本模块提供纯组装边界：输入为调用方已经完成所有者授权的不可变代码版本快照与固定模板版本，输出为不可变文件清单及 manifest。它不自行查找任意磁盘目录、不执行构建命令、不读取环境密钥，也不提供下载 HTTP 端点。

输入再次按现有产物协议验证；非法历史数据明确失败，不能绕过校验或用空白模板替代。应用标题、摘要和代码只写入数据文件，不插值到 shell、Java 源码、POM、npm scripts、路径或依赖名称。

`bundle-manifest.json` 采用 `bundleFormatVersion=1`，至少包含：

| 字段 | 约束 |
|---|---|
| `templateId` / `templateVersion` | 固定 `vue-spring-postgres` 和不可变模板版本；首版 `1.0.0` |
| `sourceVersionId` | 被选定的代码版本 UUID；不是“始终取最新” |
| `sourceContentHash` | 平台保存时的 SHA-256，用于来源追踪，不宣称是修改后源码的 hash |
| `sourceRuntime` | 固定 `web-static-v1` |
| `dataMode` | 固定 `INITIAL_ONLY` |
| `files` | 按路径排序的相对路径、字节数、SHA-256；不把 manifest 自己放进 hash 清单 |
| `bundleId` | 对格式、模板、来源和有序文件清单的规范序列化结果计算 SHA-256 |

同一个快照和同一模板得到相同的文件字节及 bundleId，不依赖当前时间、机器绝对路径或随机生成的配置。运行配置和部署身份在启动准备阶段生成，不进入这个可复现产物。

每个文件必须来自模板白名单或三个固定源码位置。拒绝绝对路径、`..`、反斜杠、空字节、重复/大小写冲突路径和符号链接。最终文件数、单文件大小和总字节数须有测试锁定的有限上限，批准计划时按真实模板大小确定，不能先写无限制实现。

工程默认不包含任何平台对话、访客身份、项目列表、真实 PREVIEW/LIVE 数据、实际 `.env`、Cookie、密钥、数据库密码、Git 元数据、`node_modules`、构建缓存或整个平台源码。

## 5. 兼容运行与浏览器边界

```text
浏览器中的 Vue 应用外壳
  ├─ 同源会话 / CSRF / 状态 API → 独立 Spring Boot → 独立 PostgreSQL
  └─ MessageChannel → sandbox="allow-scripts" 的生成应用 iframe
```

生成代码继续使用 `Atoms.ready`、`sdk.storage.get()`、`sdk.storage.save(value, expectedRevision)`，不接触会话、数据库凭据或宿主 API 客户端。

保留源码净化、JSON 到 HTML 的安全编码、请求大小/深度检查、通道绑定、错误观察、加载超时和卸载清理。生成应用仍不能直接联网、读宿主 DOM/Cookie、导航宿主、开启 worker 或安装依赖；仅受限 document 放行 inline script，不放宽 Vue 外壳 CSP。独立运行不是关闭 sandbox。

外壳至少展示启动中、运行失败、保存中、已保存、保存结果未知、冲突和数据库不可用状态；未知结果只重试原意图，不能刷新后声称上一笔失败。保留移动端可用性、键盘操作和可访问状态播报。

平台内跨来源预览的授权交换和网关由 `runtime-access` 单独设计。本模块不加入 wildcard CORS，也不把平台 Cookie 传给独立应用。端口不同虽然影响同源判断，但 Cookie 不能仅靠端口隔离；部署实例必须使用独立 Cookie 名称和自己的会话存储。

## 6. 独立后端与数据接口

拟实现的接口属于导出应用，不改原平台 API：

| 方法与路径 | 功能及主要响应 |
|---|---|
| `GET /api/health` | 检查独立数据库；UP 200，不可用为脱敏 503 |
| `GET /api/csrf` | 获取本应用 CSRF；只在内存中使用 |
| `POST /api/session` | 仅接受空对象，建立或返回本应用访客，认证时旋转会话与 CSRF |
| `GET /api/app` | 已认证访客读取公开应用描述、运行版本标识和状态格式；不返回凭据 |
| `GET /api/app/document` | 已认证访客读取受限运行文档，成功响应具有专门 CSP |
| `GET /api/state` | 当前访客状态；首次返回 manifest 初始值、revision=0、stateSchemaVersion=1 |
| `PUT /api/state` | CSRF + Idempotency-Key；按加载版本与 expectedRevision 条件保存 |

`PUT` 只接受 `{ loadedRuntimeId, value, expectedRevision, stateSchemaVersion }`。value 必须是 256 KiB 以内 JSON 对象；revision 是 `[0, 9007199254740991)` 整数；schema 固定 1。`loadedRuntimeId` 是构建时对实际应用源码与受控运行层计算的内容标识，不能把原 sourceVersionId 当作手工修改后仍有效的运行标识。

成功返回 `{ value, revision, stateSchemaVersion }`。错误统一为 `{ error: { code, message, retryable }, requestId }`，不泄露 SQL、目录、栈或配置。

- 未认证 401、CSRF 403、非法输入 422、大小超过入口上限 413；未知路由 404，不回落成 Vue HTML。
- 修订号不匹配 `STATE_CONFLICT` 409；旧页面写新代码实例为 `STALE_APP_VERSION` 409。
- 同一访客、方法、路径、幂等键与相同请求体重复调用，返回同一已提交结果，不重复写入。
- 同键异体为 `IDEMPOTENCY_PAYLOAD_MISMATCH` 409。并发提交必须使用数据库唯一约束/锁保证原子性，不能内存先查后写。
- 已成功请求先按身份及请求体校验重放，再判断新意图的运行标识，避免应用升级让未知结果无法确认。
- 已确认拒绝不得改变状态；超时、连接中断、5xx 或无法解码的 2xx 不等于事务失败。

实例使用独立数据库和非超级用户角色，schema 为专用 `standalone_app`，不复用平台 `atoms` schema。新数据库的迁移独立从 V001 起，不挪用或编辑平台 V000–V010 历史；运行与迁移权限只覆盖自己的 schema。

持久化范围：访客、JDBC 会话、每访客一份状态、幂等请求结果和安装身份。有效会话重启后可恢复，访客有效期沿用 7 天；过期、删除 Cookie 或换浏览器后不承诺找回。幂等结果保留期不短于允许重试的会话期，不能靠重启清空。

不同访客默认看到独立状态，不实现共享任务、业务账号、角色权限或跨访客协作。导出/启动默认从初始数据开始，不是迁移当前平台业务数据。

停止、重建、替换 JAR 不删除数据库。再次启动不重写已有状态为初始值。旧代码不一定兼容新业务数据；本模块不承诺数据迁移回滚，后续实例模块也不得以删库方式切换版本。

## 7. 配置、启动与独立目录

模板只携带 `application.properties.example`，真实配置使用明确的外部文件。配置项至少有：数据库 URL/用户名/密码、稳定的独立 `APP_INSTANCE_ID`、监听地址/端口、本应用 origin。实例 ID 决定独立 Cookie 名称，不能每次重启随机变化。

初始化配置命令只创建缺失文件，不覆盖已有配置；生成实例 ID，不复用平台凭据，不打印密码。缺失数据库凭据、占位符未替换、配置文件不存在或实例身份与数据库安装记录不符时退出，不能静默连接平台或使用内置密码。

默认只监听 `127.0.0.1:8088`。回环 HTTP 使用非 Secure Cookie，同时保留 HttpOnly、SameSite 和 CSRF；对外部署默认要求 HTTPS、Secure Cookie 与明确 origin。本模块不继承云端临时 HTTP 例外、不自动开放公网，也不安装软件或更改防火墙。

拟提供的完整命令契约（在导出后的工程根执行）：

```sh
# 先由操作者准备独立 PostgreSQL 数据库、受限角色和 schema。
# 创建外部配置模板，随后填入该数据库的真实连接信息。
node scripts/init-config.mjs --output /absolute/path/application.properties

# 使用锁定依赖完成前后端检查与构建，不执行生成代码中的 shell。
node scripts/build.mjs

# 明确指定配置；启动已有 JAR，不后台静默挂起、不自动下载依赖。
node scripts/start.mjs --config /absolute/path/application.properties

# 成品也必须能不依赖 Node 直接启动。
java -jar backend/target/app.jar --spring.config.location=classpath:/application.yml,file:/absolute/path/application.properties
```

构建入口按顺序执行锁文件安装、前端类型/lint/单元测试/构建和后端 `./mvnw -B clean verify`；任一失败非零退出，不输出“构建成功”。依赖安装首次禁用生命周期脚本；确实需要的构建脚本必须先审查并以锁定版本最小授权，不全局放开。

开发与检查命令契约：

```sh
npm --prefix frontend ci --ignore-scripts
npm --prefix frontend run typecheck
npm --prefix frontend run lint
npm --prefix frontend run test:unit
npm --prefix frontend run build
cd backend
./mvnw -B verify
```

上面的 `verify` 需要外部提供独立测试库配置，不允许默认连运行库。构建脚本与运行脚本必须明确区分运行凭据和测试凭据；最终参数名称在计划中统一，不允许测试失败后跳过集成测试来得到包。

此模块不自动为每个线上用户创建目录或启动进程。模板与固定夹具可在本地受控条件下构建验证；不下载真实用户源码并执行任意命令。平台托管构建由后续受限执行器负责。

## 8. 代码风格与边界示例

沿用 Java records、Spring JDBC、边界校验、事务一致性和脱敏 ApiError；前端使用 Vue `<script setup lang="ts">`、显式类型与返回值解码。命名说明职责，不创建万能的 ShellService 或命令透传端点。

```java
public record BundleSource(UUID versionId, String contentHash, Artifact artifact) {}
public record BundleFile(String path, long size, String sha256) {}

// 调用者先完成所有者授权；组装只处理快照与固定模板，不访问数据库或环境。
public EngineeringBundle assemble(BundleSource source, TemplateSnapshot template) {
    var artifact = validator.parse(json.valueToTree(source.artifact()));
    return renderer.render(template, source.versionId(), source.contentHash(), artifact);
}
```

示例说明接口意图，不是已存在代码；二进制内容和集合必须防御性复制。SDK/桥接协议复用需有一致性测试，不能把平台鉴权、域名或密钥通过复制代码带入模板。模板自己的测试和锁文件必须跟随模板版本管理。

## 9. 威胁与验证策略

资产：平台凭据与项目、导出应用的会话和业务数据、宿主文件系统、构建执行权限。信任边界：历史模型产物 → 工程文件；生成脚本 → Vue 宿主；访客 → 数据库；操作者配置 → 外部数据库。

| 风险 | 要求 | 必须验证 |
|---|---|---|
| 标题/路径注入 | 模型字段只进数据文件；文件路径固定白名单 | `../`、绝对路径、控制字符、shell 字符不产生路径或命令 |
| 把源码插入 HTML | 复用净化与上下文编码，保留 sandbox/CSP | `</script>`、事件属性、导航与联网尝试不能逃出运行边界 |
| 打包泄露 | 模板及版本快照显式白名单，不扫描工作树 | 配置、密钥、真实状态、平台会话、测试夹具不在文件清单 |
| 越权/竞争写 | 独立身份、CSRF、数据库原子修订与幂等 | 跨访客、缺 CSRF、并发同键/异键、同键异体、未知结果重试 |
| 误用平台数据库 | 配置显式且无默认平台地址；安装身份校验 | 缺失/错误配置拒绝，所有集成测试仅使用专用库 |
| 数据随构建丢失 | 数据不放源码目录，不做自动重置 | 重建/重启保留状态与有效会话，第二实例不串数据 |

测试层次：

1. Java 单元：产物校验、确定性清单、hash、文件排除、配置拒绝、错误语义。
2. JUnit + 真实 PostgreSQL 集成：会话、初始值、状态冲突与幂等、隔离、迁移重复启动；不替换成 H2。
3. Vitest：SDK 契约、外壳状态、响应解码、结果未知与重试；新增行为先红后绿。
4. 独立 Chrome：从全新目录的真实构建包启动；实际待办保存与刷新、有效会话跨进程重启、第二访客隔离；320/390/768/1440px、键盘和 axe 检查。
5. 独立性：测试浏览器禁止请求平台 origin；导出应用后端配置中没有平台地址/凭据，确认状态仅写入专用数据库；不为测试停用用户正在使用的平台服务。
6. 回归：原平台前后端测试及 runtime 流程继续通过；修改组装器不能削弱原有产物校验。

测试使用固定、明确标注的产物；不调用 DeepSeek。不得新增跳过测试、降低已有断言或把人工配置失败说成测试通过。构建完成不等于浏览器、数据隔离和重启恢复完成。

## 10. 范围与批准边界

始终：冻结来源版本；保持现有应用行为；明确独立数据库和访客语义；所有运行凭据留在包外；先写失败测试；保留失败记录；只在获批的工作区操作。

先询问：新增未列出的第三方依赖/服务、平台数据库迁移、真实数据导出、改变身份模型、扩大联网能力、开放任意后端/依赖执行、云端部署、付费模型调用或改变数据库技术栈。

绝不：覆盖原用户目录、导出真实密钥、复用平台数据库超级权限、执行模型拼接的 shell、关闭 sandbox 以掩盖不兼容、自动删库/删卷、将当前存储状态冒充初始数据、修改旧交付文档为“已实现”。

退出条件：本模块的工程格式、固定模板与独立运行证据通过评审，才能成为后续 `project-export` 和 `workspace-build` 的稳定输入。当前仅规格获批，不包含已批准计划、实现任务或功能完成声明。

## 11. 已确认项与后续测量

- 已确认数据与身份语义：仅初始数据、独立 PostgreSQL、仍按访客隔离，清除 Cookie 后不提供找回功能。
- 已确认交付运行方式：首次需要准备 Java/Node/PostgreSQL 并构建，之后可仅用 JAR + 数据库运行；本模块不承诺免环境安装的一键桌面程序。
- 模板字节/文件配额与后续容器并发、CPU、内存、超时阈值在对应计划中按实测确定；不在尚未测量时宣称性能保证。

## 官方依据

- [Spring Boot 3.5 外部配置](https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html)：支持明确配置位置；本设计不读取平台相对配置。
- [Spring Boot Servlet 静态资源](https://docs.spring.io/spring-boot/3.5/reference/web/servlet.html)：用于 JAR 内提供 Vue 构建资源，API 错误不参与 SPA 回退。
- [Spring Session JDBC](https://docs.spring.io/spring-session/reference/configuration/jdbc.html)：用于导出应用自己的持久会话。
- [MDN 同源策略](https://developer.mozilla.org/en-US/docs/Web/Security/Defenses/Same-origin_policy)：浏览器来源隔离和 Cookie 作用域不同，不以不同端口作为凭据隔离保证。
- [Docker 安全边界](https://docs.docker.com/engine/security/)：后续执行器需限制挂载、权限和控制接口；本模块不直接操纵容器服务。

以上资料只支持技术选择，不代表新模板或后续运行器已经验证。实现必须补充真实证据。
