# 本机构建验证记录

2026-10-13，用户批准 WB01–WB21 一次性实施。当前记录按实际切片追加，不代表整个模块完成；无 GitHub 推送或服务器部署。

## WB01 · 共享快照入口

- 新增 `EngineeringSnapshotService`，原工程下载改用同一授权/组装入口，保留授权错误、打包两路并发及确定性 ZIP。
- 模板摘要采用排序路径和长度前缀的源字节 SHA-256；内容或路径变化会改变摘要。Worker 所用预期模板摘要不符时拒绝，不替换快照。
- RED：`EngineeringSnapshotServiceTest` 因新接口缺失编译失败；实现后定向 `EngineeringSnapshotServiceTest,EngineeringExportTest,EngineeringBundleTest,TemplatePolicyTest` 共 51 项通过。
- 首次 GREEN 执行中，沙箱阻止 Mockito/Byte Buddy 的 JVM attach；旧导出测试同样受影响。经权限批准后原命令成功，未跳过测试或改变 MockMaker。
- `./mvnw -B -Dit.test=EngineeringExportIT verify` 成功，包含所有平台单元测试及原下载 HTTP 2 项。仅使用已有 `atoms_demo_test`，Flyway 仍为 V010，没有新迁移。
- 当前未接通构建任务/进程/UI。代码基于用户指定 Oracle Java 17.0.5，不升级模板或依赖。

## WB02 · 私有工作区

- `WorkspacePaths` 验证绝对专用根、0700、所有者、无符号链接及根目录文件身份；替换根目录后旧对象拒绝继续操作。每次派生路径都重新检查根，不接受浏览器路径。
- `WorkspaceStore` 先复用 ZIP 路径/配额校验，再以 CREATE_NEW 写入全新目录；源文件 0600，目录和 Maven Wrapper 0700。不覆盖旧任务、不自动删除文件。
- RED：新增路径/存储测试因接口不存在失败；GREEN：`WorkspacePathsTest,WorkspaceStoreTest,EngineeringZipTest` 共 6 项通过，包括穿越、重复任务、非私有目录、链接与替换根反例。
- 文件原子新建语义依据 [Java 17 Files](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/file/Files.html#newByteChannel(java.nio.file.Path,java.util.Set,java.nio.file.attribute.FileAttribute...))。本机同用户信任边界未改变，不宣称这些检查可隔离同 OS 用户的恶意程序。
