# EMIE 项目执行规范（RTK）

本文件是 EMIE 项目的最高优先级仓库级操作规范。进入本项目工作的人员或自动化代理，在读取代码、修改文件、提交、推送或操作生产环境前，必须先阅读并遵守本文件。

## 1. 基本原则

1. 以生产稳定、数据安全和可回滚为第一优先级。
2. 默认使用中文沟通和记录；代码、命令、接口名及既有英文术语保持原样。
3. 先确认现状和影响范围，再执行修改；不猜测生产状态。
4. 只处理本次明确纳入范围的文件，不覆盖、不删除用户或其他任务的改动。
5. 功能完成不代表任务完成；还必须通过检查、留下记录并明确是否已推送和部署。
6. 遇到密码泄露、破坏性数据库变更、无法备份、生产状态异常或回滚条件不足时，立即停止并说明风险。
7. 每次开发进度、诊断结论、验证结果或本地运行状态变化后，必须在结束当前任务前更新 [`docs/development-handoff.md`](docs/development-handoff.md)，确保其他开发者或 AI 可直接接手。

## 2. 项目基线

- 技术栈：Java 21、Spring Boot 3.2、Maven、MySQL、原生 HTML/CSS/JavaScript。
- 本地构建入口：`./mvnw`。
- 生产运行方式：Docker Compose。
- 本地开发/生产构建分支：`master`；Gitee 发布分支：`master`；GitHub 发布分支：`main`。
- Git 远端：`emie`。
- 生产 Spring profile：`prod`。
- 生产数据库结构策略：`spring.jpa.hibernate.ddl-auto=validate`。
- 应用容器：`emie-app`。
- 演示文稿预览容器：`emie-preview-converter`。

所有远端操作必须显式指定目标分支：Gitee `emie` 使用 `master`，GitHub `github` 使用 `main`；生产发布在本地 `master` 分支完成构建，并校验 Gitee `master` 与本地提交一致。

## 3. 开始工作前

执行并检查：

```bash
git status --short --branch
git fetch emie
git rev-list --left-right --count HEAD...emie/master
```

要求：

- 识别全部已修改、已暂存和未跟踪文件；
- 区分本次任务文件与用户已有文件；
- 发现同一文件存在不明改动时，先保留并核对，不擅自还原；
- 分支分叉时先检查提交差异，不直接覆盖或强制推送。

## 4. 文件修改规范

1. 保持现有项目结构、命名和代码风格。
2. 优先做小而明确的改动，避免把无关重构混入同一次发布。
3. 不修改或提交运行时数据、用户上传、日志、构建产物和本地工具产物。
4. 重点排除：
   - `.env`、`.env.*`、`.server.local.env`；
   - `target/`、`uploads/`、`logs/`、`backups/`；
   - `.sheet-work/`、`outputs/`；
   - IDE、系统缓存和临时文件。
5. 修改 `src/main/resources/static/js/` 下任一前端模块后，应同步更新 `index.html` 中对应静态资源版本参数，避免浏览器继续使用旧缓存。
6. 修改实体字段、表结构或索引时，必须同时提供 `db/migrations/` 下的数据库迁移。
7. 修改 Docker、环境变量、端口、挂载目录或部署流程时，必须同步更新 `docs/deployment.md`。

## 5. 敏感信息规范

禁止在代码、Git 历史、Markdown、终端输出或对话中暴露：

- 服务器和数据库真实密码；
- 飞书或其他平台的 token、secret、cookie；
- SSH 私钥、访问密钥和完整认证配置；
- 含真实凭据的 `.env` 内容。

敏感值必须通过本地忽略文件或生产环境变量注入。示例文件只保留变量名和安全占位符。

如发现已经提交的真实凭据：

1. 不在记录中重复该值；
2. 立即报告涉及范围；
3. 优先轮换凭据；
4. 再决定是否需要清理 Git 历史。

## 6. 本地验证标准

常规 Java、后端或综合改动执行：

```bash
git diff --check
./mvnw clean package
```

前端 JavaScript 改动额外执行：

```bash
for file in src/main/resources/static/js/*.js; do node --input-type=module --check < "$file"; done
```

提交前还需检查：

```bash
git diff --stat
git diff
```

验收要求：

- 构建成功；
- 测试全部通过；
- 无意外文件、调试代码或敏感信息；
- 用户可见变更已更新 `CHANGELOG.md`；
- 发布范围和验证结果已更新 `docs/release-records.md`。
- 当前进度、本地运行状态、工作区风险和下一步已更新 `docs/development-handoff.md`。

若某项检查无法执行或被跳过，必须写明原因和风险，不得将“未执行”描述为“通过”。

## 7. Git 提交与推送

1. 禁止使用 `git add .` 或不加选择地暂存全部文件。
2. 只能逐个暂存已经确认属于本次范围的文件。
3. 提交前必须检查暂存差异：

```bash
git diff --cached --check
git diff --cached --stat
git diff --cached
```

4. 未经用户明确要求，不执行提交、推送、合并、打标签或创建发布。
5. 不使用 `git push --force`，不改写共享分支历史。
6. 推送必须显式指定远端和业务分支：

```bash
git push emie HEAD:master
git push github HEAD:main
```

7. 推送后核对本地与远端 SHA：

```bash
git rev-parse HEAD
git ls-remote emie refs/heads/master
git ls-remote github refs/heads/main
```

8. 将提交 SHA、推送时间和结果写入 `docs/release-records.md`。

## 8. 数据库迁移

生产使用 `ddl-auto=validate`，数据库结构不匹配会导致应用启动失败。因此生产发布顺序必须是：备份、迁移、验证结构、部署应用。

迁移要求：

- 每个迁移文件使用唯一且可排序的名称；
- 不修改已经在生产执行过的迁移文件；
- 新迁移优先设计为可检测是否已执行，并能安全处理重复运行；
- 执行前记录数据库备份文件；
- 执行后验证表、字段、索引和关键数据；
- 删除字段、删除表、类型收窄或批量重写数据必须有专项回滚方案。

无法确认备份有效时，不执行生产迁移。

## 9. 生产部署

未经用户明确要求，不登录生产执行变更，不重启容器，不拉取代码，不运行迁移。

收到部署指令后，严格按照 [`docs/release-runbook.md`](docs/release-runbook.md) 执行。核心要求：

1. 确认计划发布的精确提交 SHA 已推送；
2. 记录生产当前旧 SHA；
3. 检查磁盘、内存、容器和近期错误日志；
4. 备份数据库并验证备份文件；
5. 执行并验证数据库迁移；
6. 使用 `git pull --ff-only` 获取业务分支；
7. 构建并启动应用和预览服务；
8. 检查容器、日志、接口和核心业务；
9. 验证失败时停止继续操作，并按已记录的旧 SHA 回滚；
10. 将全过程写入发布记录。

禁止未经确认执行：

- `docker compose down -v`；
- 删除 `uploads/`、`logs/`、数据库目录或 Docker volume；
- 在生产工作区使用强制重置覆盖不明改动；
- 直接恢复整库覆盖生产最新数据。

## 10. 生产验证标准

容器启动不等于发布成功。至少验证：

- `emie-app` 持续正常运行；
- `emie-preview-converter` 持续正常运行；
- 应用日志无新增严重错误；
- 本机关键接口可访问；
- 登录流程正常；
- 项目列表或工作台正常；
- 本次变更涉及的业务流程正常；
- 生产运行 SHA 与计划发布 SHA 一致。

全部通过后，才能把发布状态标记为“发布成功”。

## 11. Markdown 留痕

相关文档职责：

- [`CHANGELOG.md`](CHANGELOG.md)：产品功能、修复和用户可见变化；
- [`CONTRIBUTING.md`](CONTRIBUTING.md)：分支、提交、验证和协作流程；
- [`SECURITY.md`](SECURITY.md)：敏感信息和安全问题处理要求；
- [`docs/development.md`](docs/development.md)：本地环境、启动、测试和调试；
- [`docs/repository-structure.md`](docs/repository-structure.md)：目录职责与 Git 跟踪边界；
- [`docs/deployment.md`](docs/deployment.md)：固定生产环境与服务结构；
- [`docs/release-runbook.md`](docs/release-runbook.md)：标准推送、部署和回滚步骤；
- [`docs/release-records.md`](docs/release-records.md)：每次真实操作的时间、SHA、备份、迁移、验证和结果。

每次发布记录至少包含：

- 旧提交、新提交、远端提交和生产运行提交；
- 操作开始与结束时间；
- 变更范围；
- 测试与构建结果；
- 数据库备份和迁移结果；
- 容器、接口和业务验证；
- 异常、处理、回滚和最终结论。

记录中不得粘贴任何真实密码或完整敏感配置。

## 12. 完成与汇报

最终汇报必须明确区分：

- 已修改但未提交；
- 已提交但未推送；
- 已推送但未部署；
- 已部署但仍待验证；
- 已验证并发布成功；
- 发布失败或已回滚。

不得用“已完成”模糊代替实际状态。汇报应列出验证结果、提交 SHA、生产状态和仍存在的风险。
