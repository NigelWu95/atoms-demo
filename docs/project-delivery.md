# 完整工程交付（实施中）

规格和进度分别见 [SPEC-project-delivery.md](../SPEC-project-delivery.md)及[任务清单](../tasks/project-delivery/todo.md)，证据见[验证记录](project-delivery-verification.md)。当前已完成独立构建测试配置、交付设置、原进程 HTTP 交换、会话授权及有界预览 HTTP 接口。真实 Vue 浏览器验收已通过，同快照下载和恢复 API 尚未完成，正式工作台尚未接入。

管理入口为 `POST /api/projects/{projectId}/delivery/runs/{runId}/previews` 和 `DELETE /api/projects/{projectId}/delivery/previews/{previewId}`，沿用登录会话与 CSRF，只接受空对象或无正文。签发结果内的 `iframeUrl` 是短期能力链接，应按秘密处理，不记录进日志或长期存储。

新预览使用独立的精确路径过滤链，不能访问平台会话；原会话从数据库只读检查，不因预览访问续期。Servlet 非阻塞读取/写入与有界线程池共享 30 秒期限；取消后仍未结束的后台工作继续占用名额，实际结束才释放。配置遵循 [Spring Security 多过滤链](https://docs.spring.io/spring-security/reference/6.5/servlet/configuration/java.html#_multiple_httpsecurity_instances)和 [Servlet 异步上下文](https://tomcat.apache.org/tomcat-10.1-doc/servletapi/jakarta/servlet/AsyncContext.html)语义。正式嵌入必须保留 `sandbox="allow-scripts allow-forms" referrerpolicy="no-referrer"`；allow-forms 用于 Vue 的 JS 表单事件，CSP `form-action 'none'` 仍禁止原生表单请求，不加入 allow-same-origin。真实 Chrome 已验证新增、修改、409 冲突和桌面/移动布局；停止或重启后旧授权不可复用。

## 平台配置

`PROJECT_DELIVERY_ENABLED` 默认 `false`，关闭时不解析 origin、不启动执行资源。开启必须同时显式设置 `PROJECT_DELIVERY_ORIGIN`，例如开发页面实际访问的 `http://127.0.0.1:5174`。该值仅含协议、主机和端口，不附带 `/`、路径、查询、用户信息或 fragment；不是应用进程监听地址。禁止用客户端 Host/转发头推导此配置。

交付设置不自动打开 `PROJECT_EXECUTION_ENABLED`，不新增维护者或数据库绑定；实际操作仍须执行模块授权。授权固定十分钟、最多 32 个/每会话 8 个；HTTP 交换最多 16 个/每授权 4 个；下载最多 2 个/每会话 1 个。超额请求将拒绝，未实现接口不能因设置这些配置而被视为可用。

## 下载工程的独立测试配置

未来快照中的构建脚本支持 `node scripts/build.mjs --test-config /absolute/private/test.properties`。配置为工程外当前用户拥有的私有文件，只向 Maven/Java 测试传递路径，不向 npm 传递数据库凭据。格式和数据库准备要求见[模板说明](../templates/full-project-v1/README.md)。Java 初始化器要求测试用途标记、非特权角色及空 schema，业务测试事务回滚；不创建或清空数据库。

旧源码快照不修改，也不在下载时补丁替换。新脚本、测试辅助类与业务测试修改需要形成正常的新源码版本，再构建与预览。源码一致不代表已有数据相同；当前独立 Java 测试证明不能替代后续 ZIP 的完整构建、运行及浏览器验收。
