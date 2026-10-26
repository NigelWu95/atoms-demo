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
- `ex22-delivery-pd07c-http.log`：14 项定向单测、7 项真实 HTTP/PG 会话检查通过。创建/撤销沿用现有 CSRF 链，匿名和跨 owner 被拒绝，不接受 URL/query 或非规范 UUID，重复撤销成功，实际退出使旧授权失效；管理 OPTIONS 没有 Origin null CORS 许可。本组仅模拟执行门面，不启动应用。

## 预览 HTTP 接入

- `ex22-delivery-pd08a-policy-delete.log`：11 项请求/浏览器策略检查通过，包括无正文 DELETE。
- `ex22-delivery-pd08a-http-admission.log`：15 项单测、14 项真实 HTTP/PG 检查通过。覆盖 HTML、JSON 写入/409、OPTIONS、请求头隔离、慢上传 30 秒截止、断开释放、登录退出和会话不续期。执行返回值受控，尚不是实际 Vue/Java 工程。
- 前两次 HTTP 失败保留：`ex22-delivery-pd08a-http.log` 发现 Tomcat 不会以 null 移除已有 XFO，改为明确 SAMEORIGIN 后备并由 CSP frame-ancestors 限定父页；`ex22-delivery-pd08a-http-fixed.log` 的并发测试中探测请求抢占慢请求名额，改为等待四个请求实际入场后检查，没有修改容量政策。
- `ex22-delivery-pd08a-lifecycle.log`：5 项单测通过。被中断仍未退出的 worker 保持名额；实际完成才释放，不能把超时响应覆盖为成功。

测试 URI 拒绝经过实际 Tomcat/Spring firewall；错误 JSON 没有回显令牌。管理路径仍保留原 CSRF/CORS 语义。浏览器能力与下载工程验收分别留待 PD08b/PD13。

## 固定源码 ZIP

`ex22-delivery-pd09-zip.log` 4 项测试全部通过。JDK ZipInputStream 能读取每个条目，固定排序/1980 时间/UTF-8/STORED；本机 `/usr/bin/unzip` 实际解压后文件字节相同，`backend/mvnw` 有执行位、普通文件没有。清单按固定源码/构建及可选运行身份生成，独立放在 `delivery/`，不修改 `project/` 文件。也覆盖完整 16 MiB 源码树、超限、错误身份/路径/模式以及中途预算到期。

ZIP writer 按 [PKWARE 6.3.10 的本地头、中央目录及 Unix 属性约定](https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT)写有界 classic ZIP。标准 unzip 只在验证中使用，生产代码不调用外部 zip/unzip。没有下载 API、鉴权或独立业务工程验收的完成声明。

## 真实应用预览（进行中）

- `ex22-delivery-pd08b-native.log` 首次编译发现测试夹具用了不可访问的 JdbcSession 实现类型，已改用公开 Session 接口；未进入应用构建。
- `ex22-delivery-pd08b-native-session.log` 原生构建成功：构建 `79815b82-d390-46b6-acbe-832932118b16`，源码 `a3d2b3eb-65cc-4c5f-9ea2-1f899b90ac7c`，树 `29013c47de04b8a3f645291511a8178cce1646598d45ea3d1fb7d28e84625193`，JAR `7cc015cfaf29f07bedd0e913791a05a79fa8ca8274b9ea07daf20e1fd0e1b507`。实际 22 项前端与 15 项 Java 测试通过，原 owned 进程启动成功。
- 首次 Chrome 失败于添加待办：HTML/JS/CSS/初始 API 均为 200、没有 pageerror，但没有 POST。`.local/delivery-form-probe.mjs` 在独立 Chrome 比较证明：只有 allow-scripts 时 submit 事件不触发；增加 allow-forms 后 JS 可处理，保留 form-action none 时原生 POST 计数为 0。已将这一兼容性修正写入规格，仍禁止同源及平台凭据。原失败证据保留在 `/private/tmp/atoms-ex11-contract.APT7Jb/todo-applications-y6dt7dkp/delivery-browser-1247067733290532586`。
- 复用构建时 `ex22-delivery-pd08b-browser-forms.log` 暴露验收夹具仅登记旧整树摘要，拒绝本轮正常产生的新源码；新增两个精确的可移植配置样本摘要，保留旧摘要与拒绝未知源码的断言。`ex22-delivery-pd08b-browser-reviewed.log` 的四项夹具单测已通过；真实浏览器结果另记，不以夹具修复冒充浏览器成功。
