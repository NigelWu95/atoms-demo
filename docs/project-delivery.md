# 完整工程交付（实施中）

规格和进度分别见 [SPEC-project-delivery.md](../SPEC-project-delivery.md)及[任务清单](../tasks/project-delivery/todo.md)。当前仅完成独立构建测试配置和交付设置基础，预览、同快照下载和恢复 API 尚未交付。

## 平台配置

`PROJECT_DELIVERY_ENABLED` 默认 `false`，关闭时不解析 origin、不启动执行资源。开启必须同时显式设置 `PROJECT_DELIVERY_ORIGIN`，例如开发页面实际访问的 `http://127.0.0.1:5174`。该值仅含协议、主机和端口，不附带 `/`、路径、查询、用户信息或 fragment；不是应用进程监听地址。禁止用客户端 Host/转发头推导此配置。

交付设置不自动打开 `PROJECT_EXECUTION_ENABLED`，不新增维护者或数据库绑定；实际操作仍须执行模块授权。授权固定十分钟、最多 32 个/每会话 8 个；HTTP 交换最多 16 个/每授权 4 个；下载最多 2 个/每会话 1 个。超额请求将拒绝，未实现接口不能因设置这些配置而被视为可用。

## 下载工程的独立测试配置

未来快照中的构建脚本支持 `node scripts/build.mjs --test-config /absolute/private/test.properties`。配置为工程外当前用户拥有的私有文件，只向 Maven/Java 测试传递路径，不向 npm 传递数据库凭据。格式和数据库准备要求见[模板说明](../templates/full-project-v1/README.md)。Java 初始化器要求测试用途标记、非特权角色及空 schema，业务测试事务回滚；不创建或清空数据库。

旧源码快照不修改，也不在下载时补丁替换。新脚本、测试辅助类与业务测试修改需要形成正常的新源码版本，再构建与预览。源码一致不代表已有数据相同；当前独立 Java 测试证明不能替代后续 ZIP 的完整构建、运行及浏览器验收。
