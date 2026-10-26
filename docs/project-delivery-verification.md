# project-delivery 验证记录（实施中）

2026-10-27，按[已确认规格](../SPEC-project-delivery.md)推进。此文档只记录已有证据，不把内部单测或 HTTP 夹具视为真实 Vue/下载工程验收。总体进度见[任务清单](../tasks/project-delivery/todo.md)。当前没有 push、部署、真实模型或邮件调用。

## 独立构建配置

| 检查 | 结果 / 证据（仓库本机 `.local/`） |
|---|---|
| 构建参数、私有文件拒绝、npm 不获得测试配置 | `delivery-pd01-green.log`，14 个脚本测试通过；先有两项失败证明缺少入口 |
| 模板源码政策和实际 Boot JAR 资源 | `ex22-delivery-pd01-template-fixed.log`，5 个单测、2 个打包检查通过；新增测试值不在既有白名单导致的前次失败保留，修正占位值，未放宽源码政策 |
| 模板 Java 私有配置边界与原 MVC 行为 | `delivery-pd02-green-host.log`，8 项通过；沙箱 JVM 附加失败日志保留，使用批准的项目隔离 JDK 在主机测试 |
| 完整模板目录、POM、依赖策略一致 | `ex22-delivery-pd02-template.log` 通过；当前目录 37 个文件，新增测试辅助类和其测试；旧快照未修改 |
| worker 显式传递测试配置路径 | `ex22-delivery-pd03-worker.log`，10 项通过 |
| 新目录独立 Java 测试 | `delivery-pd03-native.log`，16 项通过，包括 A/B 测试库只读身份检查、伪造业务用途拒绝和 B_test 待办 CRUD/冲突等六项业务测试 |
| 测试完成后的空 schema | `delivery-pd03-rollback.log`，六项待办测试及 `@AfterAll` 空 schema 再检查通过 |

独立 Java 目录：`.local/delivery-pd03-native-3cilnunr/project/backend`。由模板清单和受控待办资源复制，使用目录自己的 `mvnw`，未使用平台 worker 或平台 classpath 构建。复验命令（仅已批准的私有配置路径，不包含凭据）：

```sh
# 在上述 backend 目录，JAVA_HOME 为已批准的项目隔离 Java 17.0.14
./mvnw -B -o \
  -Dfull-project.test-config=/Users/wubingheng/atoms-demo/.local/project-execution-acceptance.x5cvui/atoms_exec_b_test_20261020.properties \
  -Ddelivery.test-config-directory=/Users/wubingheng/atoms-demo/.local/project-execution-acceptance.x5cvui \
  test
```

额外数据库契约测试资源为 `backend/src/test/resources/projectdelivery/PrivateTestDatabaseContractTest.java`，只复制进验收目录，不进入用户模板。测试没有创建数据库/角色、启动业务应用或执行业务库迁移。这里只验证独立 Java/数据库测试配置，**不是 ZIP 自身完整 `scripts/build.mjs`、Vue 浏览器或重启持久化验收**。

## 预览传输基础

- `ex22-delivery-pd04-green.log`：交付设置及原执行设置共 12 项通过。默认关闭、独立别名、origin 限制和无副作用配置检查。
- `ex22-delivery-pd05a-green.log`：5 项请求策略测试，多组路径穿越/编码/查询/凭据头/JSON 编码/体积及不可变性检查。
- `ex22-delivery-pd05b-green.log`：请求及真实 loopback HTTP 夹具共 11 项通过；`ex22-delivery-pd05b-bounds.log` 加入大静态文件和派发前过期后，7 项传输检查通过。覆盖 409 和 JS 原字节、跳转拒绝、上游策略头隔离、MIME、正文上限、读取期限、写结果不确定、HEAD/204。
- `ex22-delivery-pd06a-green.log`：健康 HTTP 和原进程生命周期共 13 项通过，已验证目标不可重绑、停止即失效。此前 RED 编译因未等上一次退出而发生输出冲突，日志保留且不计作有效 RED；后续 PD06b 全新编译串行确认。
- `ex22-delivery-pd06-second.log`：全新编译后 30 项相关单测、3 项实际 owner/PG 集成通过。先前清理编译后的字段名称错误已修正，失败输出保留。预览请求响应中的 run/execution 与原句柄一致，过期预算、串用身份和停止后的访问被拒绝。
- `ex22-delivery-pd06-postcheck-fixed.log`：新增响应后期限耗尽后，7 项传输单测、3 项实际 owner/PG 集成通过。前次错误分类失败的日志保留；修正为沿用执行模块的预算错误协议，读取超时不返回缓存字节，已派发写入返回 `DELIVERY_WRITE_UNCERTAIN`。这里的 `/api/echo` 是故障注入夹具，不作为业务 CRUD 的证明。

这些 HTTP 夹具的临时端口、合成响应和进程停止记录，只证明传输及生命周期约束。真实 Vue/Java 应用、完整 ZIP/恢复、A 下载到 B 启动及兼容性回归仍待后续任务，不能据此宣布模块完成。

## 会话授权

- `ex22-delivery-pd07a-green.log`：9 项注册表/设置测试通过，验证授权期限和容量、撤销后仍在执行的请求不提前释放名额。
- `ex22-delivery-pd07b-project-green.log`：4 项注册表单测、3 项实际 owner/PG 集成通过；管理操作可核验项目权限，无需重新取得活跃进程。
- `ex22-delivery-pd07b-session-green.log`：14 项会话服务/注册表/设置单测通过，原会话失效或配置变化后旧令牌不可恢复，原运行句柄不会换成新进程，写响应后的失权明确返回不确定且不重试。这里模拟 SessionAccess/执行门面；真实登录会话、CSRF 及浏览器验证仍待后续记录。
