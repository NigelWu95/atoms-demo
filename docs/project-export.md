# 工程下载与独立运行

2026-10-14：主入口已区分“下载预览工程 ZIP”和“导出版本源码 ZIP”。前者绑定实际预览的冻结构建，后者保留当前/历史版本导出。本轮没有推送 GitHub 或更新云端部署；文档中的 GitHub 链接在后续推送后对应新内容。

需要下载与预览一致的工程时：选中当前版本 → **运行应用** → 本机运行预览就绪 → **下载预览工程 ZIP**。文件名为 `atoms-build-<构建 UUID>-engineering.zip`，来源是正在运行的构建，不受随后修改模板或选择其他构建影响。预览未就绪或切回共享预览时不开放该下载，详见[本机运行说明](https://github.com/NigelWu95/atoms-demo/blob/main/docs/app-runtime.md)。

## 版本源码导出与通用启动方式

1. 打开自己的项目，在代码版本区域选中要导出的 Vn。
2. 直接点击“导出版本源码 ZIP”，无需展开菜单。文件名为 `atoms-<项目 UUID>-v<n>-engineering.zip`。此入口用当前模板重新组装，不保证等同于正在运行的构建；未生成、正在加载或会话未连接时不可用。
3. 解压到一个新目录，按包内 README 准备 Java 17、Node 24.20.x、npm 11.19.x 和 PostgreSQL 17。首次构建需要访问依赖仓库。
4. 在工程外初始化运行/测试配置，由数据库管理员分别准备专用数据库与受限角色。不要复用平台数据库或将运行库用作测试库；真实配置不得提交 Git。
5. 按 README 构建并启动，访问配置中的 origin。成品只需 Java、JAR、外部配置和 PostgreSQL，不需要平台服务、模型 API Key 或 Vite。

完成 README 的配置和数据库准备后，在解压目录执行：

```sh
chmod u+x backend/mvnw
node --test scripts/*.test.mjs
STANDALONE_TEST_CONFIG=/absolute/path/test.properties node scripts/build.mjs
node scripts/start.mjs --config /absolute/path/application.properties
```

ZIP 不保留 Unix 可执行位，因此不能省略 `chmod`。占位路径需要替换成自己的绝对路径。运行库与测试库的安装 ID、数据库和角色必须分别配置，完整 SQL 与注意事项见[工程 README](https://github.com/NigelWu95/atoms-demo/blob/main/templates/standalone-v1/README.md)。Linux/macOS 构建脚本已经提供；Windows 可使用相应 Linux 环境。

## 下载到了什么

包含 Vue/TypeScript 外壳、Spring Boot 17 数据服务、PostgreSQL 迁移、构建/启动脚本、必要测试、锁文件、许可证，以及所选版本的 HTML/CSS/JS、manifest 和初始数据。`bundle-manifest.json` 记录来源版本、文件清单与校验摘要。

不包含平台账号、会话、项目对话、PREVIEW/LIVE 真实业务数据、模型 Key、数据库密码或本机缓存。独立应用有自己的访客会话与数据库，停止和重启进程不删除已保存数据，但清除 Cookie 或会话过期后没有账号找回能力。

这是**可独立构建、修改和运行的固定完整工程**，不是模型自动生成任意 Vue SFC、后端接口或数据库设计。生成业务代码仍在受限 iframe 中，通过兼容 SDK 保存每位访客的数据。导出后可以自行修改外壳和后端并重新构建，但不会自动同步回平台。

## 实现思路与边界

导出链路是“所有者鉴权 → 指定不可变代码快照 → 固定模板组装 → 有界 ZIP → 浏览器下载”。平台不扫描工作目录、不执行生成代码、不在下载时安装依赖或启动工程。下载历史版本不会恢复代码、切换当前版本、更新发布或复制业务数据。

固定模板从平台 JAR 的白名单资源加载，生产服务器不必另放模板目录。业务内容只写入规定的应用文件，不进入命令、路径、依赖或 Java 类名。

采用 Java 标准库的 STORED ZIP，文件排序、时间与元数据固定；同一版本和同一模板的输出可重复。牺牲一部分压缩率，避免增加依赖和不可预测的压缩工作。工程上限是 128 文件、单文件 2 MiB、总源文件 8 MiB；最终 ZIP 上限 9 MiB。每个服务实例最多同时进行两次打包，繁忙立即失败，不建立无限等待队列。这不是下载带宽限流或平台总配额。

前端使用同源会话，拒绝重定向，设置 30 秒传输超时并按实际字节计数。成功响应校验 MIME、ZIP 标记与体积；错误响应读取上限为 64 KiB。切换项目/版本或卸载组件会取消旧请求，丢弃晚到响应并释放 Blob URL。取消后的旧错误也不能使新会话失效。

## 接口与失败处理

```http
GET /api/projects/{projectId}/versions/{versionId}/engineering.zip
Accept: application/zip
```

需要现有访客 Cookie。服务端先核对项目所有权、版本归属，成功返回 `application/zip`、准确 Content-Length、attachment 文件名及不缓存响应头。只读 GET 不创建新版本，不要求生成任务或发布状态。

| 情况 | 结果 |
| --- | --- |
| 未登录/会话失效 | 401，沿用平台重新连接流程 |
| 非所有者、项目或版本不存在、版本属于另一项目 | 404，不暴露目标是否存在 |
| 产物违反工程契约或超出配额 | 422 `INVALID_ENGINEERING_BUNDLE` |
| 两个打包槽位均忙 | 503 `EXPORT_BUSY`，稍后手动重试 |
| 内部打包失败 | 500 `EXPORT_FAILED`，不暴露路径、源码或堆栈 |
| 网络/传输超时、响应格式不正确 | 保留当前预览与输入，显示失败及重试入口 |

没有自动重试，也不会下载错误 JSON 冒充 ZIP。成功提示仅代表文件已交给浏览器处理，不保证浏览器完成落盘。有限错误日志通过 requestId 关联，不记录源码、密钥或响应正文。

## 验证与后续方向

已验证真实所有者下载当前/历史版本、越权拒绝、错误重试、取消与资源释放、键盘和窄屏使用。实际从网页下载的 ZIP 解压到新目录，完成冻结依赖安装、前后端检查与构建、独立 JAR 启动、保存/刷新/进程重启恢复；浏览器阻止访问平台服务。详细命令、失败修复与产物摘要见[验证记录](https://github.com/NigelWu95/atoms-demo/blob/main/docs/engineering-bundle-verification.md)。

平台内的专用工作区与固定模板本机构建执行器已由独立的 `workspace-build` 模块实现。它先冻结版本和模板，再构建 JAR；“下载本次构建工程 ZIP”按 buildId 返回原始冻结包，不同于本页按版本与当前模板组装的下载接口。使用方式见[本机构建说明](https://github.com/NigelWu95/atoms-demo/blob/main/README.md#在平台本机构建工程)，实际构建与下载重建结果见[验收记录](https://github.com/NigelWu95/atoms-demo/blob/main/docs/workspace-build-verification.md)。

2026-10-14 本地已接入本机应用进程启停、永久项目运行库绑定和实际 JAR 预览；主下载入口绑定同一个 buildId 和已就绪的预览。维护者须预备专用配置池，平台不自动建库，进程不直接发布公网。见[本机运行说明](https://github.com/NigelWu95/atoms-demo/blob/main/docs/app-runtime.md)。GitHub 授权/同步、真实业务数据导入导出、任意后端生成仍未包含在内。模块关系见[能力地图](https://github.com/NigelWu95/atoms-demo/blob/main/CAPABILITY-MAP.md)。
