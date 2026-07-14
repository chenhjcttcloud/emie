# EMIE 协作与贡献说明

本项目是内部业务系统。开始修改前必须先阅读 [RTK.md](RTK.md)，并遵守其中的代码、Git、数据库和生产安全规则。

## 工作流程

1. 确认当前分支和工作区状态；
2. 同步远端业务分支；
3. 明确本次范围并保留其他人的已有改动；
4. 完成小而聚焦的代码或文档修改；
5. 执行与改动范围匹配的检查；
6. 更新 `CHANGELOG.md` 和 `docs/release-records.md`；
7. 逐个暂存确认过的文件；
8. 经确认后再提交、推送和部署。

## 分支

- 当前业务分支：`project_manager_system`
- Git 远端：`emie`

远端默认分支不是当前业务发布分支。拉取和推送必须显式指定 `project_manager_system`。

```bash
git fetch emie
git rev-list --left-right --count HEAD...emie/project_manager_system
```

禁止强制推送或改写共享分支历史。

## 提交范围

提交前检查：

```bash
git status --short --branch
git diff --check
git diff --stat
git diff
```

禁止提交：

- `.env`、`.server.local.env` 和任何真实凭据；
- `target/`、`outputs/` 和本地工具产物；
- `uploads/`、`logs/`、`backups/` 和用户数据；
- IDE、系统缓存和本机绝对路径软链接。

不得使用 `git add .`。应逐个暂存本次确认过的文件，并检查暂存差异：

```bash
git diff --cached --check
git diff --cached --stat
git diff --cached
```

## 验证

Java 或综合改动：

```bash
./mvnw clean package
```

前端 JavaScript 改动：

```bash
node --check src/main/resources/static/js/app.js
```

文档改动还需检查相对链接、文件路径和发布状态是否准确。无法执行的检查必须如实记录，不能标记为通过。

## 数据库变更

实体、字段、表或索引变化必须提供 `db/migrations/` 下的迁移脚本。生产使用 `ddl-auto=validate`，迁移必须在应用部署前执行。

迁移不得包含未评审的破坏性操作。执行生产迁移前必须备份数据库，并在发布记录中填写备份、迁移和验证结果。

## 文档同步

- 用户可见变化：更新 `CHANGELOG.md`；
- 开发或目录变化：更新 `README.md`、`docs/development.md` 或 `docs/repository-structure.md`；
- 部署变化：更新 `docs/deployment.md` 和 `docs/release-runbook.md`；
- 实际推送或部署：更新 `docs/release-records.md`。

## 提交信息

提交信息应简洁说明目的，推荐格式：

```text
feat: 新增功能
fix: 修复问题
docs: 更新文档
refactor: 重构但不改变行为
chore: 工程或仓库维护
```

不要把互不相关的功能、格式化和目录调整混入同一个提交。
