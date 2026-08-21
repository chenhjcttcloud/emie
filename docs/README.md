# EMIE 文档索引

- [权限系统建设方案](permission-system-plan.md)：统一权限模型、数据范围、条件策略、后台配置与四期实施计划。
- [现有权限盘点与首版矩阵](permission-inventory.md)：当前角色硬编码、页面/接口权限、首版编码和兼容接入范围。
- [权限系统发布与回滚清单](permission-release-checklist.md)：上线顺序、权限验收和应用/配置回滚步骤。

本目录集中保存项目业务、集成、架构、部署和发布文档。运行时数据、临时检查结果和工具输出不放入本目录。

## 阅读顺序与文档状态

日常开发先读 [RTK](../RTK.md)、[协作与贡献说明](../CONTRIBUTING.md) 和 [开发指南](development.md)；涉及发布时再读 [部署信息](deployment.md) 与 [发布操作手册](release-runbook.md)。

- **当前规范**：`业务流程.md`、`development.md`、`deployment.md`、`release-runbook.md`、`notification-system-design.md`、`permission-inventory.md`。
- **实际记录**：`release-records.md` 只追加已经发生的发布；`development-handoff.md` 和 `device-migration-handoff.md` 是交接/历史记录，不作为当前操作规范。
- **方案与规划**：文件名含 `plan`、`roadmap`、`sop`、`standards` 的文档用于设计参考；若与当前代码或部署手册冲突，以代码、迁移脚本和当前规范为准。
- **发布授权**：默认只在本地修改和验证，不自动 commit、push 或发布生产；必须获得用户对当前变更的明确授权。

数据库迁移以 `src/main/resources/db/migration/` 中的 Flyway `V__` 脚本为准；根目录 `db/migrations/` 仅保留历史导入/一次性脚本，新增实体变更不得只写在那里。

## 业务与产品

- [业务流程](业务流程.md)：角色权限、项目状态和主要业务流程。
- [更新日志](../CHANGELOG.md)：已完成及待发布的用户可见变化。

## 开发与仓库

- [开发指南](development.md)：环境变量、本地启动、代码结构、构建和调试。
- [MacBook Air 异地开发指南](mac-remote-development.md)：换设备接手、测试容器、Bug 修复和双远端同步。
- [开发交接状态](development-handoff.md)：当前进度、验证结果、运行状态、工作区风险和下一步。
- [设备迁移交接](device-migration-handoff.md)：当前设备基线、本机数据清单、加密迁移和新设备验收步骤。
- [仓库目录规范](repository-structure.md)：目录职责、Git 跟踪边界和整理检查。
- [协作与贡献说明](../CONTRIBUTING.md)：分支、提交、验证和文档同步规则。
- [安全说明](../SECURITY.md)：敏感信息和安全问题处理要求。

## 飞书集成

- [飞书多维表格同步](feishu-base-sync.md)：当前同步架构和 Base 表结构。
- [飞书集成方案](feishu-integration-plan.md)：SSO、内嵌应用和实施方案。

## 文件与存储

- [文件归档方案](file-archive-plan.md)：文件冷热分层与 NAS 归档设计。

## 运维与发布

- [部署信息](deployment.md)：生产服务器、容器及环境配置说明。
- [发布操作手册](release-runbook.md)：推送、备份、迁移、部署、验证及回滚步骤。
- [生产发布记录](release-records.md)：每次发布的提交、迁移、验证和结果。
- [项目执行规范](../RTK.md)：仓库操作和生产安全边界。

## 项目导入模板

可交付模板及使用说明见 [`templates/project-import/README.md`](templates/project-import/README.md)：

- 历史项目导入模板；
- 生产环境导入模板；
- 填写说明和参考数据预览；
- 模板检查结果。

模板生成工具说明位于 [`../scripts/project-import/README.md`](../scripts/project-import/README.md)。
