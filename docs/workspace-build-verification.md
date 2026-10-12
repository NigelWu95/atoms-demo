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

## WB03 / C-WB01 · 冻结归档复验

- 按已保存摘要返回原始 ZIP；逐个源文件校验实际字节。源文件改变时原 ZIP 保持原字节，归档被改写后拒绝读取，不自动重新打包。
- 读取检查普通文件、所有者、私有权限、链接数、祖先/文件无符号链接和大小；打开后仍以 limit + 1 有界读取。目录、硬链接和超限拒绝。
- 新测试先因接口缺失失败，实现后定向 8 项通过。完整平台 `./mvnw -B verify`：**93 单元 + 54 集成通过，0 跳过，61 秒**；Java 17.0.5、本机平台专用测试库。包括原下载、模板 JAR 白名单/体积及生成/发布/恢复回归。
- 未变更模板字节、依赖或平台 V000–V010；没有真实模型请求。进程监督、构建任务与工作台仍未完成。

## WB04 · 本机进程监督

- 新增随平台打包的 `workspace/supervisor.mjs` 与私有 IPC keeper。keeper 作为任务组 leader 保持存活；收到停止或上级 IPC 断开后向自身当前进程组发 TERM，宽限后 KILL。上层从不向缓存 PID/PGID 发破坏性信号，仅在退出后观察组是否消失。
- 工具输出持续排空但不保存正文，只保留饱和至 1 MiB 的计数；工具不继承 keeper IPC。取消不可逆，后续 run 拒绝；父 stdin EOF 转中断。观察失败/退出未知会报告未确认，不当作已取消成功。
- 新模块缺失时测试 RED；首批 7 项通过。新增“启动后立即取消”发现 keeper 尚未收到初始化消息时使用错误的测试宽限：5.1 秒，断言失败；改为启动时给定受限宽限，最终 **8 项通过，3.4 秒**，0 跳过。
- 真实测试覆盖超量输出、非零退出、工具父进程退出但孙进程持有 stdout、取消、超时、拥有者 SIGKILL 导致 stdin EOF、无关进程存活、停止后拒绝再启动。使用临时夹具，不运行生成代码。
- 只读 fresh-context 设计审查：缓存 PID 风险、永久停止状态、初始化/重复停止竞态为有效发现并已处理；执行身份/持久 fence 归 WB05/WB16；固定工具链不得脱离任务组的前提归 WB13 实际流程审计。没有调用外部跨模型 CLI。
- **边界**：进程组消失不证明任意 daemon/setsid 程序退出。本模块只用于平台固定、需审计为不自行脱离任务组的构建流程；不支持任意命令、任意插件或不可信后端，不宣称操作系统级隔离。依据 [Node 24 子进程文档](https://nodejs.org/download/release/v24.20.0/docs/api/child_process.html#optionsdetached)；`killed` 标记不能作为退出证明。
