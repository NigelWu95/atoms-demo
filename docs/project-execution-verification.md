# 完整工程执行 · 验证记录

本模块依据[已确认规格](../SPEC-project-execution.md)及[EX 任务清单](../tasks/project-execution/todo.md)实施。仅记录实际运行过的检查，不以计划、假子进程或历史通过数替代完整工程构建/业务验收。当前尚无真实生成应用构建/运行结论，日常开关未启用。

## 2026-10-20 · EX01 配置

使用 Oracle Java 17.0.5；除以下专用测试 schema 外，不访问日常/远程数据库。未改私有 env、个人工具配置、模型/邮箱额度及旧 Agent 默认 4 并发。

| 完成时间（Asia/Shanghai） | 检查 | 结果 |
|---|---|---|
| 14:20:17 | 新 `ExecutionSettingsTest`，实现之前 | 编译失败，16 处均为尚未存在的 `ExecutionSettings` 引用；RED |
| 14:21:58 | 新配置及原 Agent 配置定向单元 | 14 项，0 失败/错误/跳过 |
| 14:24:22 | 全部后端单元 + `ProjectSourceReadGatewayIT` + 打包 | 297 单元、5 集成，0 失败/错误/跳过，BUILD SUCCESS；23 项既有迁移验证通过，无新迁移 |
| 14:26:39 | 暂把允许构建并发上限从 4 改为 5 | 限额反例 1 项按预期失败，变异被捕获；随即恢复 |
| 14:27:49 | 恢复后的配置定向测试及重新打包 | 14 项全通过，BUILD SUCCESS；最终包不含变异代码 |

完整后端命令（工作目录 `backend`）：

```sh
env PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH \
  SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/atoms_demo_test?currentSchema=atoms_verify_20261019_pa32b&connectTimeout=3&socketTimeout=5' \
  ./mvnw -B -Dit.test=ProjectSourceReadGatewayIT verify
```

真实 PG/子进程和 JVM 自附加使用已批准的沙箱外测试权限；不是关闭测试或访问其他数据库。统计只计本次 14:22 之后的 68 个 Surefire 报告，排除目录中 14 份历史报告。集成 5 项来自本次 Failsafe 输出，不将旧 277 项集成结果当作重跑。

定向与变异命令：

```sh
./mvnw -B -Dtest=ExecutionSettingsTest test
./mvnw -B -Dtest=ExecutionSettingsTest,AgentSettingsTest test
./mvnw -B '-Dtest=ExecutionSettingsTest#malformedOrExpandedBudgetsFailClosed' test
./mvnw -B -Dtest=ExecutionSettingsTest,AgentSettingsTest package
```

恢复后的 `ExecutionSettings.java` SHA-256 与完整回归时相同：
`92a921652d52eaa92e7417c57d1e5e75eb68ca9d10df77737586abaf01e34d37`。

测试覆盖默认关闭只读开关、加载无 I/O、不可变维护者集合、规范 UUID、路径/控制字符拒绝、数值上限、配置异常不回显原值以及实际 YAML 别名与 Spring 上下文。该切片不执行工具身份探测、项目授权、构建、运行或数据库绑定，这些分别由后续任务实现。

配置按既有 `Environment` 惰性读取，依据 [Spring Boot 3.5 配置文档](https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html)。目录租约后续依 [Java 17 FileLock](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/channels/FileLock.html)保持同一文件的唯一描述符；该规则尚不代表 EX02 已完成。

## 2026-10-20 · EX02a 根租约

先运行 `./mvnw -B -Dtest=ExecutionRootLeaseTest test`，因缺少待实现类编译失败。实现后 14:32:13，以下命令 20 项全部通过、无跳过，平台重新打包成功：

```sh
./mvnw -B -Dtest=ExecutionRootLeaseTest,ExecutionSettingsTest,ProjectSourcePathsTest,AgentStateDirectoryTest package
```

新测试 4 项包含真实两 JVM 争用、同 JVM 重复申请后仍保持 OS 锁、重复检查不释放锁、关闭后可重开，以及 root UID、未标记/宽泛/重叠/符号链接目录、权限变化、根/标记替换和硬链接反例。测试 JVM 环境清空，限时等待并确认结束；只修改 `@TempDir` 下的合成文件。

生产默认身份读取使用 [Java 17 UnixSystem](https://docs.oracle.com/en/java/javase/17/docs/api/jdk.security.auth/com/sun/security/auth/module/UnixSystem.html) 的原生 UID，不以可覆盖的 `user.name` 判断 root。文件锁是协作锁，标记与私有目录不是同 UID 恶意代码的隔离机制。EX02 的任务目录/容量尚未完成，执行器仍未接入启动流程。

## 2026-10-20 · EX02b 目录能力与容量

`ExecutionPathsTest` 先因缺少实现类产生 12 处编译错误；实现后 14:40:10：

```sh
./mvnw -B -Dtest=ExecutionPathsTest,ExecutionRootLeaseTest,ExecutionSettingsTest,ProjectSourcePathsTest,AgentStateDirectoryTest package
```

25 项通过，0 失败/错误/跳过，BUILD SUCCESS。新增 5 项验证分离的 builds/artifacts/runs 私有目录、不可跨发行者使用的任务句柄、目录/标记替换、关闭后撤销、缺失布局不补建、同 UUID 不覆盖，以及容量拒绝时不分配任务、不删除证据。真实文件只写入临时目录；8 GiB/1 GiB 精确边界通过算术测试，小预算真实文件验证容量流程，不分配 8 GiB 测试文件。

容量扫描不跟随 npm 包符号链接，普通文件按逻辑长度计算；最大深度 64、10 万节点、10 秒扫描预算，超界保守拒绝。该检查不是硬磁盘配额，不能阻止同 UID 代码并发写盘。根/控制目录重新核对身份和权限，未知顶层内容不自动收养或清理；已有任务的恢复决策仍由 EX13/22 实现。EX02 完成，但尚未启用构建。
