# project-agent · 离线实施与兼容性记录

日期：2026-10-18～19。规格与计划已批准，模块仍未完成。只使用本机假 Anthropic 服务和公开测试字符串，不调用真实 DeepSeek、不改个人 Claude 配置、不部署、不推送。任务状态以 [清单](../tasks/project-agent/todo.md)为准。

## 已完成底座

- PA01：懒加载默认关闭配置，维护者 UUID、绝对路径与预算校验。先缺类失败，再 5 个配置测试通过。配置关闭不读取密钥、不做文件/进程 I/O。
- PA02：固定参数数组、空继承环境白名单、CLI 2.1.302 精确版本与文件身份校验。同长度、同时间戳内容修改仍被 SHA-256 校验发现。新增 6 个测试先缺类失败，之后共 11 个 Agent 单元测试通过、0 失败/错误/跳过。
- Oracle Java 17.0.5 保持不变。新增 Agent 不接入旧生成器，不改旧 tokens、额度或超时配置。日常服务与远程数据库未变。
- 2026-10-19 默认后端回归：`env PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH ./mvnw -B verify` 在沙箱外完成，224 单元测试 + 170 集成测试通过，0 失败/错误/跳过，BUILD SUCCESS。仅使用专用测试 PG，日志确认无需新增迁移。需要安装 CLI 的独立 tag 不在默认运行范围；上述数字不包含下述 2 个失败用例，也不表示 Agent 模块验收通过。前端未修改，本轮未重复前端测试。

## PA03：原 bare 组合的失败证据

真实可执行文件：`/Users/wubingheng/.nvm/versions/node/v20.15.0/lib/node_modules/@anthropic-ai/claude-code/bin/claude.exe`。先用无认证版本探测验证 2.1.302，再在新临时项目、config/tmp 目录中执行。测试服务固定绑定 `127.0.0.1`，不转发网络请求。正式代码没有用户可配置的 API 地址；loopback 覆盖只存在于 test 源码。

复现命令（backend 目录）：

```sh
./mvnw -B -Pagent-cli-contract \
  -Datoms.agent.test.cli=/Users/wubingheng/.nvm/versions/node/v20.15.0/lib/node_modules/@anthropic-ai/claude-code/bin/claude.exe \
  '-Dtest=Agent*Test' '-Dit.test=AgentCli*ContractIT' verify
```

最近结果：11 单元测试通过；2 个参数化 CLI 测试均因缺少工具而失败，0 错误/跳过。测试没有改低验收期望。

2026-10-19 最后补验：将文件读回与 Edit 工具结果断言放在能力集合断言之前，再以同一 CLI/profile 执行 `test-compile failsafe:integration-test failsafe:verify`（保留 `-Dit.test` 选择器），2 个用例仍仅因缺少 Glob/Grep/Write 失败；之前的读改、正常退出和 128000 参数检查通过。没有为重复验证重跑已通过的完整默认回归。

| 观测项 | 两种参数格式的实际结果 |
| --- | --- |
| 工具白名单参数 | 逗号分隔 / 独立 argv，均为 Read、Glob、Grep、Write、Edit |
| 受控 API 请求数 | 每次 3 次，全部到本机模拟接口 |
| 认证方式 | x-api-key，固定假值 offline-fixture-key |
| 上游模型字段 | deepseek-flash（CLI 参数带 `[1m]`） |
| 上游 max_tokens | 128000 |
| 上游 stream | true |
| 实际工具集合 | **Edit、Read**，没有 Glob、Grep、Write |
| 文件变化 | hello.txt 从 `before\n` 变成 `after\n` |
| 最终结果 | success、is_error=false、退出码 0、permission_denials 为空 |

CLI 确实能读改已有文件，不等于完整工程编辑契约已满足。这里没有新增文件、搜索、恶意越界、真实模型、持续任务、源码版本接受或生成应用运行的验收证据。工具轮次/重试预算也不能由这次正常往返推断为已全面验证。

生产 JAR 中只有 AgentSettings、AgentInvocation、AgentCliProbe 及其内部值类型；`jar tf` 确认未包含 AgentProtocolFixture 或 AgentCliContractIT。默认 Maven verify 按 tag 排除需要安装 CLI 的测试，显式 profile 才运行；缺少 CLI 路径会失败，不会跳过当成功。

## 排查与待确认的调整

1. 首轮完整 verify 在沙箱中发生 Mockito JVM self-attach 和自有子进程检查失败；按权限流程在沙箱外重跑，同一代码的 **224 个单元测试全部通过、0 失败/错误/跳过**，随后暴露上述真实 CLI 工具兼容性失败。没有关闭 Mockito、禁用失败测试或改旧实现。
2. 请求中记录的工具字段仍是 Read 的 file_path/offset/limit/pages/allow_large，以及 Edit 的 file_path/old_string/new_string/replace_all。未看到搜索工具被合并到这些字段的证据。切换相同白名单的参数分隔方式后结果不变。
3. 官方 [bare 说明](https://code.claude.com/docs/en/headless#start-faster-with-bare-mode)描述其受限工具和无个人 OAuth/keychain 行为；[工具参考](https://code.claude.com/docs/en/tools-reference#glob-tool-behavior)说明通常可显式恢复 Glob/Grep，但这不证明当前 bare 组合一定恢复。以上实测优先于假定。
4. `--safe-mode + --restricted + 独立配置目录 + 原文件工具白名单` 已在 2026-10-19 用户要求完整实施后验证，见下节。根据 [CLI 参考](https://code.claude.com/docs/en/cli-reference)，safe-mode 的认证行为与 bare 不同，受管策略仍生效；不能宣称是等价替换或 OS 沙箱。

## PA03：safe-mode 离线通过

正式 argv 改为 `--safe-mode --restricted`，版本探测仍使用无密钥 `--bare --restricted --version`。未升级 CLI，文件工具白名单与 128000 输出目标不变，不开放 Shell/MCP。测试覆盖：两种参数写法的 Read/Edit；Glob/Grep；新增 Vue、Java 和 SQL；目录外读写、软链接读取拒绝；项目和私有配置 CLAUDE.md canary 未进入请求。

红绿证据：新增夹具方法前测试编译失败；补齐夹具后参数单测明确因仍使用 bare 失败；切换正式参数并移除测试中的替换后，执行下面命令通过（2026-10-19 00:59，Java 17.0.5）：

```sh
env PATH=/Users/wubingheng/.nvm/versions/node/v24.20.0/bin:$PATH ./mvnw -B \
  -Pagent-cli-contract \
  -Datoms.agent.test.cli=/Users/wubingheng/.nvm/versions/node/v20.15.0/lib/node_modules/@anthropic-ai/claude-code/bin/claude.exe \
  '-Dit.test=AgentCli*ContractIT' verify
```

结果：224 单元测试、4 CLI 集成测试通过，0 失败/错误/跳过，构建成功。此命令没有运行其余数据库集成测试，其上次完整回归见本文开头。第一次候选验证在沙箱内因禁止绑定 loopback 端口报 SocketException，获准后原命令在沙箱外运行；没有跳过该测试。

这些证据只证明已安装版本在本机测试场景下的文件操作及部分权限边界。没有证明真实 DeepSeek、任意受管策略、个人 keychain 完全不可访问、任务恢复、工程构建或运行。完整隔离/canary 反例仍在 PA30，真实模型仍属 PA-LIVE。PA04 及之后按任务清单继续。
