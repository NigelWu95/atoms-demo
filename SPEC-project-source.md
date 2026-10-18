# Spec: project-source · 完整工程源码与不可变版本

状态：用户已于 2026-10-18 确认规格并授权按整体计划连续实施；功能开发中，未完成验收。对应 [完整工程能力地图](FULL-PROJECT-CAPABILITY-MAP.md)。

用户已同意将本模块计划和任务单独存放在 [实施计划](tasks/project-source/plan.md) 与 [任务清单](tasks/project-source/todo.md)，不覆盖原任务记录。

本文只规定源码管理，不表示 Claude Code 已接入、模型已能生成 Java、任意后端已能执行，或服务器已更新。

## 1. 目标

为每个项目保存可持续增量开发的完整工程，包括前端、后端、依赖声明、非敏感配置和数据库迁移。源码不再受旧三文件产物格式约束；版本、权限及任务元数据仍由 PostgreSQL 管理。

核心承诺：正式版本不可变；修改只在任务副本中发生；失败、取消、并发冲突和恢复中断均不得静默覆盖当前版本。

本模块提供可单独测试的内部接口；用户文件浏览和操作入口由 `project-workbench` 实现。不提前暴露未设计完的 HTTP 文件写入、任意目录上传或通用终端接口。

## 2. 技术栈与前提

- 沿用 Java 17、Spring Boot 3.5.16、JDBC、Flyway、PostgreSQL 和现有项目所有权验证。
- 源码存储使用本机 Git，通过参数数组调用经过维护者配置的绝对可执行路径，不经 shell 拼接输入；首期不引入 JGit。
- 本次只读检查确认本机 Git 为 2.39.5；这不是新源码管理能力的运行验证。
- 前端仍为 Vue 3、TypeScript、Vite；此模块不修改前端业务流程。
- 新功能默认关闭。关闭时不创建源码仓库，不影响原生成、导出、预览及运行路径。
- 单个平台写入进程使用专用存储根；不支持多个平台实例共享本地 Git 根并同时写入。

## 3. 存储结构与事实来源

下面是计划中的结构，不是已经创建的文件夹：

```text
<source-root>/<project-uuid>/repository.git/  正式 bare Git 仓库
<source-root>/<project-uuid>/staging/<operation-uuid>/  平台受控临时输入
<agent-work-root>/<task-uuid>/project/        后续 Agent 的普通文件副本
<agent-config-root>/<task-uuid>/             后续 Agent 私有配置及会话
<build-root>/<build-uuid>/                   现有/后续构建工作区
<runtime-private-root>/                     运行数据库配置，不属于源码
```

- 使用项目 UUID，而非用户名或邮箱作为目录键；账号归属变化不要求移动仓库。
- 对外可见版本由 PostgreSQL 的当前版本指针及该版本引用的 Git 对象共同确定；不以目录最新修改时间、Git `HEAD` 或 Agent 会话判断版本。
- 每个正式版本保留平台创建的不可变引用，例如 `refs/atoms/versions/<version-uuid>`。客户端不得直接传入任意 Git 引用、对象 ID 或服务器绝对路径读取内容。
- Agent 获得普通文件副本，不获得指向正式仓库的 Git worktree 元数据，不拥有平台提交版本的接口或凭据。配置分离不构成同系统用户下的文件系统隔离。
- 存储根由维护者配置并校验，不自动在用户任意目录内初始化 Git；发现非平台标记的既有目录时拒绝接管。

### 3.1 数据库增量结构

拟新增以下表，实际 Flyway 编号在实现前重新检查；当前最后一个迁移为 V017。批准本规格代表同意设计这些增量表，不代表同意在远程数据库执行迁移。

| 表 | 主要数据 | 约束 |
|---|---|---|
| `project_sources` | project_id、格式标识、current_source_version_id、source_revision、状态 | 一个项目一条；引用版本必须属于同项目 |
| `source_versions` | id、project_id、version_no、commit_id、tree_sha256、based_on_id、restored_from_id、创建时间、导入来源及模板摘要 | 版本不可变；同项目编号唯一；不保存完整源码 |
| `source_operations` | id、project_id、操作类型、幂等键摘要、请求摘要、预期基线、候选版本 ID、执行标识/未结束标记、状态、结果或错误码 | 同项目幂等键唯一；记录写盘前的持久操作意图；未确认结束的执行不可重启或发布 |

新模式使用独立的源码修订号，不在本模块切换旧 `projects.current_version_id` 或重写历史 `versions.source_json`。源码行仅表示已保存，不等同于构建通过；构建、运行状态由后续模块单独关联。

### 3.2 文件与大小策略

- 允许完整目录树和前端/后端/SQL/非敏感配置文件。首期支持 UTF-8 文本源码及文本静态资源；二进制资源上传另行设计，不静默丢弃无法支持的文件。
- 默认拟定：最多 512 个文件、单文件 2 MiB、整棵树 16 MiB、路径最多 240 个 UTF-8 字节及 16 层。均为新模块待验证的可配置预算，不修改旧三文件产物的限制；超限明确拒绝，不截断或自动减功能。
- 拒绝绝对路径、空路径分量、`.`、`..`、反斜杠、控制字符、大小写折叠冲突，以及符号链接、硬链接、子模块和非普通文件。读取时仍检查真实路径及文件类型，不能只做一次字符串前缀判断。
- 不收录 `.git`、Agent 配置目录、依赖缓存、构建结果、日志及运行数据。拒绝 `.env` 等实际凭据文件、私钥和已知敏感配置；允许经过内容检查的无秘密示例配置。扫描不能证明任意文本绝不含秘密，界面及文档必须保留此边界。
- 保留经过策略允许的文件模式；可执行位只允许初始化模板中明确列出的启动/构建脚本。保存源码不授予这些脚本执行权限。
- Git 不加载用户/系统配置、外部钩子、过滤器、凭据助手、自动签名或网络远端。使用受控配置和对象写入流程；禁止从项目 `.gitattributes` 触发外部程序。
- `tree_sha256` 基于规范化、排序后的路径、文件模式、字节长度和文件内容 SHA-256 计算；序列化必须无歧义。Git 对象 ID 单独记录，不当作整个工程的通用 SHA-256。

## 4. 生命周期与一致性

### 4.1 初始化与旧项目导入

1. 所有者显式请求初始化；校验项目存在、归属及未归档。读取接口不隐式创建工程。
2. 新工程从受控模板初始化；旧项目从指定不可变产物和指定模板摘要导入。保存来源版本、模板标识和摘要，不根据“最新模板”动态重解释历史版本。
3. 旧导入保留三文件兼容运行层及初始数据，不自动转为原生 Vue/Java 业务，也不复制平台 PREVIEW/LIVE 数据。
4. 旧格式的派生 `bundle-manifest.json` 不充当新工程修改后的校验依据。新源码树的身份由源码版本元数据维护，后续交付模块生成独立导出清单。
5. 已初始化项目重复同一请求返回同一结果；不同请求不得无提示清空重建仓库。新工程模板的业务能力扩展由 `project-agent` 规格定义。

### 4.2 修改与提交

1. 验证所有者、源码基线版本及源码修订号；先保存带幂等键的操作意图和候选版本 ID。
2. 从已保存版本物化任务副本。读取所需文件和修改副本与正式仓库分开；任务副本可被丢弃而不丢失已保存版本。
3. 候选内容进入平台独占的 staging；使用不跟随链接的受控读取、预算检查和哈希校验。Agent 退出前的可变目录不得直接作为正式快照。
4. 在数据库事务外写入 Git 对象和候选版本引用，核对完整树与哈希。失败时不改变数据库当前指针。
5. 在短数据库事务内重新检查权限、归档状态和基线，原子写入版本元数据、递增源码修订号、更新指针及完成操作记录。
6. 同一基线只能有一次成功接受；其他候选返回 `SOURCE_BASE_CONFLICT`，不得覆盖或自动强制合并。任务失败不回滚别人的成功提交。

Git 写入与 PostgreSQL 事务不是一个分布式原子事务。持久操作意图和候选引用用于恢复：数据库未发布的对象不可见；数据库已提交但响应丢失时，重试返回原结果。进程崩溃后的恢复必须核对操作意图、对象和数据库事实，不能仅凭退出码或引用存在宣布成功。

执行细化：启动任何 Git 写入前先持久化一次性执行标识及 `execution_pending`；只有该执行的所有自有子进程已确认结束，才可清除标记。重启发现仍未结束时保留待核对状态，即使候选引用完整也不能宣告进程已结束；不依据旧 PID 杀进程、不自动清除阻断。V018 已进入专用测试库后，此增量约束追加为 V019，不修改已应用迁移。

同一幂等键且请求摘要相同返回原操作；不同摘要返回 `SOURCE_IDEMPOTENCY_CONFLICT`。取消在发布事务前可阻止接受；若事务已提交，返回已提交结果，不声称取消成功并删除新版本。

状态区分 `PREPARING`、`CANDIDATE_READY`、`COMMITTED`、`FAILED`、`CANCELLED`、`NEEDS_RECONCILIATION`；未知状态阻止该项目继续写入并提示核查，不当作普通失败重跑。此模块不自动清除不可识别仓库、孤立引用或历史对象。

### 4.3 读取、恢复与备份

- 所有元数据、文件树、文件和差异读取均先校验项目所有权；通过平台版本 UUID 定位源码，不提供任意 SHA/路径访问。
- 列表采用有界分页，单次最多 100 项；文件读取遵守单文件预算；差异超出展示预算时返回明确的截断标志和统计，不用于代替完整内容校验。
- 恢复旧版本通过新建版本引用旧文件树实现，记录 `restored_from_id`，父版本指向恢复前当前版本；不删除后续历史，不移动已有不可变引用。
- 恢复源码不运行 SQL、不删除数据、不声明数据库兼容。能否启动恢复后的代码由执行模块另行验证。
- 缺失仓库、引用或内容校验不一致时返回 `SOURCE_STORAGE_UNAVAILABLE` 并停止写入；不能凭数据库元数据重造源码。
- 备份必须同时覆盖 Git 对象/引用及 PostgreSQL 元数据。首期提供一致性检查要求，不在本模块实现自动远程备份、垃圾回收或灾备编排。

## 5. 模块接口与代码风格

内部接口使用 UUID、不可变 DTO 和显式基线；仅内部适配器持有绝对路径。错误沿用平台异常映射，不向调用方暴露服务器路径或 Git 原始错误中的敏感内容。

```java
public record SourceBase(UUID versionId, long revision) {}

public record SourceVersion(
        UUID id, UUID projectId, long versionNo,
        String commitId, String treeSha256,
        UUID basedOnId, UUID restoredFromId) {}

public interface ProjectSourceService {
    SourceVersion get(UUID ownerId, UUID projectId, UUID versionId);
    SourceOperation initialize(UUID ownerId, UUID projectId,
            SourceInitialization request, String idempotencyKey);
    SourceWorkspace prepare(UUID ownerId, UUID projectId, SourceBase base);
    SourceOperation accept(UUID ownerId, UUID projectId,
            SourceCandidate candidate, SourceBase base, String idempotencyKey);
    SourceOperation restore(UUID ownerId, UUID projectId, UUID targetVersionId,
            SourceBase base, String idempotencyKey);
}
```

以上是拟定的契约示例，不是当前可编译实现。`SourceCandidate` 必须是平台校验后产生的不可伪造内部句柄，不能只凭模型提供的路径或哈希接受版本。初始化与接受返回持久操作状态，不要求 HTTP/调用线程等待磁盘处理完成。

Java 按现有代码采用四空格、构造器注入、窄职责类；避免持有数据库事务执行 Git 或复制大文件。文件树及差异 DTO 只包含相对路径、大小、模式和摘要等必要字段；数据库 SQL 全部使用绑定参数。

## 6. 计划中的代码位置与命令

```text
backend/src/main/java/dev/atoms/demo/source/       领域服务、仓库、路径与 Git 适配器
backend/src/test/java/dev/atoms/demo/source/       单元测试、真实 Git/数据库集成测试
backend/src/main/resources/db/migration/          只新增迁移，不修改历史脚本
templates/                                       复用受控初始化素材
docs/decisions/016-full-project-source-and-coding-agent.md
```

以下命令在实现对应测试后执行；本次未运行测试、启动平台或修改数据库：

```sh
cd /Users/wubingheng/atoms-demo/backend
./mvnw -B -Dtest=ProjectSourcePolicyTest,ProjectSourceServiceTest test
./mvnw -B verify
./mvnw -B spring-boot:run
```

集成测试必须显式连接项目既有的专用测试数据库，不能复用日常或线上用户数据。完整平台回归涉及前端时再执行：

```sh
cd /Users/wubingheng/atoms-demo/frontend
npm run typecheck
npm run lint
npm run format:check
npm run test:unit
npm run build
```

## 7. 测试策略与成功标准

采用现有 JUnit、Surefire/Failsafe，真实 Git 使用临时专用目录；数据库集成使用专用 PostgreSQL。不得只 mock Git/事务来声称崩溃恢复成立。覆盖率不设置未经基线核实的数字门槛，但以下场景全部必须有可重复的断言：

1. 初始化包含 Vue、Java、SQL 和非敏感配置的树；保存一个超过旧 120 KiB 上限且低于新预算的工程并完整读回。
2. 修改一个业务文件形成第二版；其他文件内容及 Git blob 标识保持不变，两个版本均可重建相同字节及允许的文件模式。
3. 权限隔离：其他用户不可枚举、读取、初始化、恢复或接受该项目版本；账号所有权变化后重新授权生效。
4. 同基线并发接受只有一次成功；幂等重试不产生重复版本；同键不同请求被拒绝。
5. 在写入对象前、Git 引用完成而数据库未提交、数据库已提交而响应丢失三个位置注入故障，验证恢复、重试和可见版本正确。
6. 路径穿越、链接、异常文件、大小写冲突、预算超限、已知秘密文件及 Git 钩子/过滤器诱导均被拒绝或不会执行；测试放置无效 canary 密钥，确认日志和快照中不存在该值。
7. 取消与接受竞态给出真实结果；仓库缺失或损坏不会切换到错误版本，也不会触发未授权的自动删除。
8. 恢复生成第三个不可变版本；文件树匹配目标旧版，旧历史及业务数据均未改变。
9. 旧项目显式导入后，历史 `source_json`、旧当前版本指针、现有 PREVIEW/LIVE 数据完全不变；旧生成、预览、下载和恢复回归通过。
10. 功能关闭时不启动 Git、不初始化存储；保存源码的测试不会调用模型、构建、运行用户工程或访问远程 Git。

## 8. 行为边界

- Always：所有者授权、基线校验、有界资源、秘密排除、不可变历史、操作审计及失败恢复；明确区分源码已保存和应用可运行。
- Ask first：新增依赖、改变上述预算或技术栈、开启真实模型调用、运行新生成后端、上线迁移、推送/部署、清理正式仓库或业务数据。
- Never：修改个人 Claude Code 配置、继承整份平台环境给 Agent、读取或提交真实密钥、重写旧版本、绕过失败测试、以文件夹隔离冒充沙箱、静默把失败版本作为已通过交付。

## 9. 已确认范围与后续模块事项

本规格按上述默认文本文件范围和容量预算实施；如需第一阶段即支持图片等二进制资源，需要补充相应校验、预览和导出契约。

Claude Code 本机路径已定位，但其版本、`--bare` 等隔离选项、DeepSeek 协议兼容性尚未联调。这些属于 `project-agent`，不阻塞本模块源码管理设计。新业务后端的执行授权、数据库供给及私有预览路由属于后续模块，不能沿用旧固定模板的安全假设直接开放。

规格批准不替代实现计划批准，也不代表上述测试已经通过。
