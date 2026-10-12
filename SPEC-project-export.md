# project-export · 工程 ZIP 下载规格

状态：用户于 2026-10-12 确认范围与一次完成推进方式；2026-10-13 PE01–PE05 已实现并完成本地验证，未推送/部署。依据：[能力地图](CAPABILITY-MAP.md)、[基础工程规格](SPEC-engineering-bundle.md)；结果见[验收记录](docs/engineering-bundle-verification.md)。

用户本轮明确要求“完成工程下载按钮”，随后确认“一次完成”。下载所需的工程说明、实际 ZIP 构建和平台回归已纳入本轮最终验收；EB24–EB25 按实际证据完成，C-EB11 保留人工评审状态。没有自动合并、推送或部署。

## 1. 目标与界面

项目所有者在工作台下载**当前选中的已保存版本**的完整工程 ZIP，包括历史版本；不依赖版本是否已发布，不把历史下载变成恢复或重新发布。

- 入口位于 `ArtifactPanel` 的版本区域，名称“下载工程 ZIP”；显示目标 Vn，解释 Vue + Spring Boot 17 + PostgreSQL。
- 无已保存版本、版本加载中或会话未就绪时不可下载，给出原因；下载中避免重复请求。
- 明确“包含源码与初始数据，不包含平台账号、对话、线上业务数据和密钥”；不是现有 HTML/CSS/JS 文件的简单另存为。
- 下载后由用户准备 Java/Node/PostgreSQL 和独立配置，按包内 README 构建启动。无需平台服务或模型 API Key。
- 下载失败保留当前预览/版本与输入，页面展示可访问错误提示和重试入口；会话失效沿用平台重新连接流程。
- 切换项目/版本、离开页面后取消或废弃旧请求，不触发错版本的延迟下载。成功文案仅表示已交给浏览器下载，不声称已写入用户磁盘或构建成功。
- 保持现有预览、历史恢复、生成和发布行为；320/390/768/1440px 可用，键盘可操作。

## 2. HTTP 契约

新增只读接口：

```http
GET /api/projects/{projectId}/versions/{versionId}/engineering.zip
Accept: application/zip
```

沿用平台访客 Cookie 身份，不传 ownerId、不新增登录体系。先通过 `ArtifactService.get(owner, projectId, versionId)` 检查项目所有权和版本归属，再组装 ZIP。请求指定不可变版本 ID，不在下载途中重新读取“当前版本”指针。

| 结果 | 契约 |
|---|---|
| 200 | `application/zip`，正确 Content-Length，`Content-Disposition: attachment` |
| 401/403 | 沿用平台会话/安全错误结构，不返回 ZIP |
| 404 | 项目不存在、其他访客项目、版本不存在或版本不属于该项目；沿用现有不可枚举语义 |
| 422 | 来源或工程超出既定格式/配额，`INVALID_ENGINEERING_BUNDLE` |
| 503 | 数据库不可用沿用 `SERVICE_UNAVAILABLE`；打包并发占满用 `EXPORT_BUSY`，允许稍后手动重试 |
| 500 | 打包故障返回脱敏 `EXPORT_FAILED` 和 requestId，不返回半个 ZIP 或内部路径/堆栈 |

文件名固定为 `atoms-<project UUID>-v<版本号>-engineering.zip`，不用项目标题或模型文本生成路径/响应头。成功与错误响应均保留 no-store、nosniff、既有 CSP/X-Frame-Options；不放宽 CORS/CSRF。GET 没有业务写入，可以安全重试，无需新增幂等记录或导出任务表。

## 3. 工程与打包边界

- 复用 `TemplateCatalog` → `EngineeringBundleAssembler` → `EngineeringBundle`。只接受固定 classpath 模板与授权版本快照，不扫描工作目录，不读取环境变量、私有配置或真实应用状态。
- ZIP 内工程直接位于根目录，包括 frontend、backend、application、scripts、README、许可证和 `bundle-manifest.json`。文件路径、字节数与 hash 必须与清单一致。
- 沿用 128 文件、单文件 2 MiB、源码合计 8 MiB 的配额；ZIP 含元数据上限 9 MiB。文件名仍使用现有便携路径约束，拒绝绝对路径、穿越、重复/大小写冲突和目录占位冲突。
- 使用 Java 17 标准 ZIP API，无新增依赖。首版使用 STORED 条目、固定时间和稳定顺序，避免压缩 CPU 开销；相同来源/模板生成相同字节。hash 是一致性标识，不是数字签名。
- 完成全部校验和内存打包后才提交成功响应，避免失败时浏览器获得伪成功文件；单 JVM 最多同时打包 2 个请求，超出立即返回 EXPORT_BUSY，不排无限队列。不引入跨节点配额承诺。
- ZIP 不承诺 Unix 可执行位；包内 README 明确在解压的新工程根执行 `chmod u+x backend/mvnw` 后再运行既有构建命令。必须从真实下载包验收这一流程，不能复用测试工具自动 chmod 过的目录冒充下载验收。
- 不打包 JAR/node_modules/target、数据库快照、平台 Session、PREVIEW/LIVE 数据、用户列表、对话、模型配置或真实配置文件。生成应用仍使用受限运行层，不转写为任意 Vue SFC/后端服务。

## 4. 前端响应与生命周期

JSON API 和 ZIP 请求共用现有会话失败通知、ApiFailure 和错误展示约定；不另起会绕过会话状态的裸请求体系。

ZIP 请求使用同源 credentials、no-store、禁止重定向和有界超时；验证状态、MIME、实际体积及 ZIP 标识，拒绝把 JSON/HTML 错误页保存成 ZIP。文件名只由已确认的项目 UUID/版本号构成。读取响应有体积上限，中断时不触发下载。

使用 Blob URL 交给浏览器，完成触发后适时释放；切换目标/卸载清理请求、临时链接、对象 URL 和计时器。没有自动重试风暴，不借下载修改原版本选择或项目指针。

## 5. 结构、风格与实现依赖

保持 Spring Boot 3.5.16 / Oracle Java 17.0.5、Vue 3.5.43 / TypeScript 5.9.3、Node 24.20.x / npm 11.19.x、PostgreSQL 17 及当前锁文件。

代码归属：后端 `dev.atoms.demo.engineering` 的 ZIP 边界与下载接口；前端 `api` 的二进制请求与 `components` 的工程下载控件，接入 `ArtifactPanel.vue`。对应 Java 单元/HTTP 集成、Vitest 组件/API、`e2e-runtime` 真后端下载测试。沿用小切片和本地原子提交，不改写原 `engineering-bundle` 任务历史。

```java
// Contract sketch: resolve the authorized immutable version before reading templates.
var version = artifacts.get(owner, projectId, versionId);
var source = new BundleSource(version.id(), version.contentHash(), version.artifact());
var bundle = assembler.assemble(source, catalog.load());
```

Java 沿用 records、显式构造注入、参数化数据库访问、脱敏 ApiError；Vue 沿用 `<script setup lang="ts">`、显式状态与卸载清理，禁止 any、v-html 和静默吞错。ZIP 处理不执行生成代码、shell 或包安装。

## 6. 验收标准与验证命令

先反例后实现：未认证/其他访客/同所有者不同项目混用版本、非法路径/超额、打包异常都不能下载；当前与历史版本可下载，源码/来源 ID/初始状态正确，下载不改变项目或发布指针。

单元验证 ZIP 条目、CRC、稳定顺序/时间、配额与清单 hash；真实 PostgreSQL HTTP 测试验证所有权、头部/错误与字节；前端验证空态、忙态、重试、会话失效、过时请求和资源释放。至少一个新增关键条件做反向变异，恢复后重新通过。

真实 Chrome 点击按钮并等待下载事件，检查实际 ZIP 的来源与文件；窄屏、键盘、axe 验收。将受控测试夹具通过真实 HTTP 下载的 ZIP 解压到专用全新目录，按 README 配置/构建/启动；阻断平台后验证保存和刷新。不执行真实用户上传工程，不调用 DeepSeek。

平台回归，在仓库根已选定上述 Java/Node/npm 且隔离测试数据库可用后：

```sh
cd backend
./mvnw -B clean verify
cd ../frontend
npm run typecheck
npm run lint
npm run format:check
npm run test:unit
npm run build
npm run test:e2e -- --project=chromium
npm run test:e2e:runtime
cd ..
node --test scripts/release.test.mjs
git diff --check
```

在真实 ZIP 解压后的新工程根，使用已批准的独立运行/测试配置（实际执行时用真实绝对路径替换占位符）：

```sh
chmod u+x backend/mvnw
node --test scripts/*.test.mjs
STANDALONE_TEST_CONFIG=/absolute/path/test.properties node scripts/build.mjs
node scripts/start.mjs --config /absolute/path/application.properties
```

模板 README 修改后重新组装校验，不使用旧 manifest/hash 宣称新包通过。验收前核对模板预算与源码排除规则；实际完成的基础工程收尾另行登记，未完成检查点保持未勾选。只有上述证据齐全才宣称“工程下载按钮完成”，再更新原功能说明中的对应状态，不宣称线上已更新。

## 7. 安全与操作边界

- 始终：授权先于内容获取；来源显式冻结；所有响应有界；失败不提供伪 ZIP；保留现有测试、安全策略和真实数据；日志不含源码/凭据。
- 先询问：新增依赖/服务、身份或数据库变更、真实数据迁移、托管运行、云端部署、模型调用；本规格中的新导出并发上限随规格一并确认。
- 绝不：开放服务器任意路径下载、执行生成后端、导出平台密钥/状态、关闭 sandbox、覆写用户工程、删库/删卷、未通过即标记完成。

推进方式已确认：按本范围一次完成设计、实现和验证；仍不授权 GitHub 推送、部署或下一阶段托管执行器。

## 官方依据

- [Java 17 ZipOutputStream](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/zip/ZipOutputStream.html)、[ZipEntry](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/zip/ZipEntry.html)：标准 ZIP 条目、STORED 与时间元数据接口。
- [Content-Disposition](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Content-Disposition)、[对象 URL 释放](https://developer.mozilla.org/en-US/docs/Web/API/URL/revokeObjectURL_static)：下载响应与浏览器资源生命周期。
