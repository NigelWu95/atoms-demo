# ADR-017 · 受控 Python 源码文件工具

## 状态与范围

2026-10-18：用户已批准新增 Python 文件操作运行依赖。仅替换源码工作副本及候选暂存的底层文件访问，不改变 Java 17 / Spring Boot / Vue 主技术栈，不启用真实 Coding Agent 或运行生成后端。

## 背景

本机 Oracle Java 17.0.5 的文件系统提供者返回 `UnixDirectoryStream`，实际测试不支持 `SecureDirectoryStream`。先检查链接再用普通路径打开文件，仍留有父目录替换窗口，不能替代所需的描述符相对操作。

## 决定

- 使用 Python 3.11+ 标准库小工具，随 JAR 的 classpath 资源发布，Java 通过已配置绝对解释器路径执行固定代码。变量数据只经 stdin JSON 传递，不经 shell，也不从工程中寻找/导入工具代码。
- `-I -S -B -X utf8`、环境白名单、固定工作目录；不继承 HOME、模型/SMTP/数据库密钥或 PYTHON 配置。不修改用户环境。
- 从根目录逐段 `O_NOFOLLOW` 打开，以 `dir_fd` 访问文件；检查设备号/inode、所有者、权限、链接数与文件类型。读取前后复核元数据，创建使用排他方式，未知目录不接管。
- 副本在 work-root，候选在 source-root 的项目 staging 目录。暂存区有独立标识；候选读回需匹配平台保存的树摘要。
- 协议 24 MiB、单文件 2 MiB、树 16 MiB、512 文件、240 字节路径、16 层，扫描最多 8192 个目录项；工具默认超时 60 秒。目录句柄随递归层级及时关闭，不随所有目录数量累积。
- 工具只做文件操作，不启动子程序、不执行源码/构建脚本、不访问网络。它与 Java 都不是 OS 沙箱；同系统用户的恶意进程仍有主机权限。Agent 必须确认结束后才封存，不能把边读边校验当作任意并发写入下的原子文件系统快照。

## 替代方案与取舍

- 升级/更换 JDK：用户要求保留现有 Java 17，且不同提供者的目录流能力需要另行验证。
- JNA/JNI：需增加本机 ABI/分发维护，本轮不采用。
- 普通路径复制：简单，但未满足已批准的链接替换防护要求。
- Python 工具增加一个 POSIX 运行依赖和有界序列化开销；无需额外 pip 包。关闭新模块时不会启动它。

## 依据及验证

[Java 17 SecureDirectoryStream](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/file/SecureDirectoryStream.html)、[Python 3.11 os](https://docs.python.org/3.11/library/os.html)、[Python 隔离模式](https://docs.python.org/3.11/using/cmdline.html#cmdoption-I)。本机实测 Python 3.11.2；部署方需配置满足能力并获得安全更新的解释器，本次没有自动安装/升级。

实测结果与历史失败保留在 [源码模块验证记录](../project-source-verification.md)。不得据此宣称任意生成工程可安全执行或整个改造已完成。
