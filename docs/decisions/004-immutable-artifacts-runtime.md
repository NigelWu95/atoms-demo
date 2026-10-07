# ADR-004 · 不可变代码版本与受限运行文档

## 状态

已接受，2026-10-07。按 M3 先实施 T10–T11，不引入 OAuth、模型调用、发布或任意服务器执行。T12–T15 继续按原清单接入 SDK、状态保存和工作台。

## 背景与决定

访客项目必须能够持有真实代码版本，后续 Worker 才能保存生成结果。生成的 HTML/CSS/JS 都是不可信内容；平台会话、私有项目、模型密钥和管理页面 DOM 是需要保护的资产。

- 增量 V004 新建 versions，给 projects 增加 current_version_id。项目行锁序列化版本提交；同时检查 expectedProjectRevision 与 baseVersionId。新版本与指针/revision 更新使用一个短事务。事务中不访问模型或网络。
- `(project_id, version_no)` 唯一；跨项目指针受复合外键约束。content_hash 只用于内容比对，不唯一；相同源码可以产生新版本。UPDATE/DELETE 触发器拒绝修改历史，不对既有表做清空或回写。
- 产物保存在数据库 TEXT JSON 中，不写入模型提供的文件路径。结构严格限定为 manifest 和三个固定文件；源码合计至多 120 KiB UTF-8，initialState 对象至多 256 KiB JSON UTF-8，标题 1–120、摘要 1–2000 个 UTF-16 单元。
- 版本列表只返回分页元信息，详情才返回 artifact。所有路径由会话确定 owner；当前版本不匹配返回 409，历史文档必须显式 HISTORY。
- 采用 MIT 许可的 jsoup 1.23.2（Java 8+，无必要运行时依赖），做真正的 HTML 解析。先验证白名单，不支持内容报错，再规范化输出。原始源码仍保存在快照中。允许基本结构、表单控件、受限 SVG 图形；不允许表单提交、URL 属性、外部媒体、内联事件、foreignObject、脚本标签或子框架。此白名单是首版能力上限，不承诺任意网页兼容。
- 运行文档使用独立 HTTP 端点，CSP `sandbox allow-scripts` 保证直接打开也采用 opaque origin；iframe 外壳还必须设置 `sandbox="allow-scripts"`。不使用 allow-same-origin。
- 只在成功的精确运行文档 GET 上允许同站嵌入。管理页、JSON 和错误仍是 X-Frame-Options DENY / 严格 CSP。Spring 默认的 XFO writer 被等价的条件 writer 替代，其他默认安全头不变。
- CSS/JS 经 JSON 序列化并转义 `< > &` 和行分隔符，再在运行文档内部用 textContent 创建 style/classic script。闭合标签不能逃出可信装配器；不使用 eval、v-html、innerHTML、srcdoc 或服务器执行。
- Runtime 的 script-src 仅为 `'unsafe-inline'`，script-src-attr 为 `'none'`，不开放 self/http/blob/data/unsafe-eval；外部脚本、connect、frame、worker、object、base、form-action 均禁用，图片只允许 data。这只作用于本来就必须执行不可信脚本的沙箱，绝不复用到管理站点。

## 复核发现与取舍

初稿使用随机 nonce，但独立复核指出生成代码可复制 nonce 到外部 script 元素。该发现有效，改为上述无 URL 源的 inline-only 策略，并以真实 Chrome 测试验证拦截外部脚本。nonce 不是沙箱内部可信 SDK 与恶意脚本之间的隔离手段。

这不是通用恶意代码执行环境：CPU/内存耗尽、同框架导航等能力并未全面隔离。公开部署前仍需要独立预览域、资源/请求配额和安全评估。没有宣称完整网络隔离。

本批测试计数器只验证 DOM 交互与文档边界，刷新会重置计数，不是 AI 生成，也不是持久化应用。Atoms.ready / 消息桥在 T12，业务数据在 T13–T14；经典脚本完整语法和产物行为验证随后续验证器接入，当前 JSON/HTML 校验不等于 JS 语义校验。

测试装载器与测试页面仅在 src/test 下，通过独立 test-run 入口加载，并检查专用数据库/角色和回环绑定；不进入正式 JAR，不在正常前端增加假生成入口。测试 Cookie 与本地正式开发会话使用不同名称。

不可变触发器不替代数据库管理员权限控制。将来落实用户数据删除/保留策略时，需要受控的管理迁移路径，不应为此给普通 API 增加历史修改能力。

## 官方实现依据

- [jsoup 1.23.2 发行说明](https://jsoup.org/news/release-1.23.2)、[依赖与 Java 兼容性](https://jsoup.org/download)
- [Cleaner：验证后仍必须规范化](https://jsoup.org/apidocs/org/jsoup/safety/Cleaner)
- [PostgreSQL 17 行锁](https://www.postgresql.org/docs/17/explicit-locking.html#LOCKING-ROWS)
- [Spring 声明式事务代理](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)
- [Spring Security 6.5 响应头](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/headers.html)
- [CSP sandbox 响应头与 opaque origin](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Content-Security-Policy/sandbox)
- [CSP script-src 的 inline、nonce 和外部脚本语义](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Content-Security-Policy/script-src)
- [Spring Boot 3.5 test-run](https://docs.spring.io/spring-boot/3.5/maven-plugin/run.html#run.test-run-goal)
