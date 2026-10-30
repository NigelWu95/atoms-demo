# 服务器部署配置说明

本文提供可复用的部署约定。实际主机地址、SSH 登录信息、证书指纹、发布目录、数据库清单、备份位置及运行实例标识由维护者在仓库之外保存，不在公开文档中记录。历史运维原始记录已保存在维护者本地私有目录。

## 部署方式

平台可在单台 Linux 服务器上运行，使用 Java 17、PostgreSQL 和持久化工程目录。平台站点可采用仓库提供的 Nginx/systemd 模板；用户应用的独立端口发布使用平台内置转发，不要求另配 Nginx。

启用 Claude Code 完整工程模式还需要受支持的 Claude Code CLI、Node/npm、Maven、Git、Python 及完整 JDK。具体工具版本、目录权限、数据库初始化和启动步骤见[单服务器部署](https://github.com/NigelWu95/atoms-demo/blob/main/deploy/server/README.md)。模板中的目录、服务账号及端口是安装约定，部署时按实际环境配置。

## 私有配置

真实配置保存在源码目录与网站静态目录之外，不随发布包覆盖。仓库中的 `.env.example`、`.env.local.example` 和 `deploy/server/application.properties.example` 只提供键名与占位值。

- 模型 API Key、SMTP 授权码、邮箱验证码 HMAC 密钥及数据库密码均通过私有配置提供。
- 平台数据库、应用测试库和应用运行库使用独立凭据；源码与下载包不携带凭据。
- `PROJECT_DELIVERY_ORIGIN` 填实际工作台 origin；独立端口发布按[发布配置](https://github.com/NigelWu95/atoms-demo/blob/main/docs/server-publication.md)设置。
- 任务额度、并发数和超时按环境明确配置；更新程序应保留既有记账及数据库绑定。

示例中的 `192.0.2.10` 仅为文档地址，不指向实际演示服务。实际工作台与应用链接由维护者单独提供。

## 更新与回退

1. 在开发环境构建发布包，核对源码版本、载荷清单与校验值。
2. 确认任务和应用进程状态，备份数据库、私有配置及服务配置；将准确位置与恢复步骤保存在私有运维记录中。
3. 停止平台服务，切换发布包，应用增量迁移并启动。不得覆盖工程目录、数据库、会话或模型记账。
4. 核对健康、账号、项目数据、生成构建、预览、源码下载和发布访问；停止应用后确认入口关闭，重启后确认数据保留。
5. 回退前验证旧程序与现有 schema 的兼容性。切回程序不等于恢复数据库，不应直接覆盖上线后的业务写入。

同机备份不等于异地灾备；备份归档目录检查不等于恢复演练。实际部署状态和恢复证据由维护者私下维护。

## 验收范围

已进行单服务器部署及完整工程流程的验收，包括模型生成、构建、独立进程运行、持久化、源码下载一致性，以及应用端口访问、停止和重启。各次结果仅覆盖当次版本与样例，不代表任意生成应用均可成功运行。

本地与功能验收可查阅[验收记录](https://github.com/NigelWu95/atoms-demo/blob/main/docs/verification.md)、[完整工程工作台](https://github.com/NigelWu95/atoms-demo/blob/main/docs/project-workbench.md)和[独立端口发布](https://github.com/NigelWu95/atoms-demo/blob/main/docs/server-publication.md)。具体主机参数和操作流水不随这些记录公开。

公网认证默认要求 HTTPS。显式启用 HTTP 演示模式只适用于知情的临时演示，其认证能力受相应配置约束；部署模板说明不替代实际环境验收。
