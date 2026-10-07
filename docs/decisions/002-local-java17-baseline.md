# ADR-002 · 本地 Java 17 实施基线

## 状态

已接受，2026-10-07。补充原设计目录的 ADR-001，不改写原始决策历史。

## 背景与决定

用户明确使用已有的 `17.0.5-oracle`，暂不升级 Java。后端固定 Spring Boot 3.5.16、Java release 17、Maven Wrapper 3.9.11。不使用 Java 21 的虚拟线程等 API。Java 17.0.5 是较早补丁，适用于本次用户指定的本地开发；公网发布前重新评估安全补丁，不能把版本兼容等同安全审计。

本机已有 Node 24.20.0 / npm 11.19.0，项目用 `.nvmrc` 选定，不改全局默认。前端的 Vue、Vite 和测试工具版本固定在 package.json/锁文件中。

按用户要求先本地开发；真实模型资源验证和线上部署保留待办。用户自定义模型设置后置，不等于免除生成阶段的服务端 API 依赖。

## 安全边界

- 浏览器通过同源 `/api` 访问后端；不接收数据库或模型凭据。
- 后端只监听回环地址，基础阶段只开放只读健康检查，不引入会话或写接口。
- 数据库管理账号仅用于本地安装。应用和集成测试分别使用非超级用户账号、不同数据库和专用 schema。
- `.env` 是原数据库管理配置；`.env.local` 存独立随机应用/测试密码，均忽略且权限 600。
- 测试不清理业务数据库，不执行 Flyway clean、删库或删卷。基础迁移只建立迁移历史；业务表按后续切片添加。
- npm 安装脚本默认全部禁用。审核 lockfile 和实际构建结果，不为了跑通一键放开脚本或强制修复依赖。

## 参考

- [Spring Boot 3.5 系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)
- [数据库初始化与 Flyway](https://docs.spring.io/spring-boot/3.5/how-to/data-initialization.html)
- [Maven Wrapper](https://maven.apache.org/tools/wrapper/)
- [npm ignore-scripts](https://docs.npmjs.com/cli/v11/using-npm/config#ignore-scripts)
