# EMIE 代码推送与生产发布手册

本文档用于规范 EMIE 本地代码推送、生产部署、数据库变更、验证和回滚。目标是让每次发布都可检查、可追溯、可恢复。

## 1. 文档职责

| 文档 | 用途 |
|---|---|
| `CHANGELOG.md` | 记录版本包含的产品功能和缺陷修复 |
| `docs/deployment.md` | 记录生产环境结构、服务组成和固定部署信息 |
| `docs/release-runbook.md` | 记录每次发布必须遵循的标准流程 |
| `docs/release-records.md` | 按时间追加实际执行记录、提交号、迁移、验证和结果 |

功能完成不等于生产发布完成。只有代码已推送、生产部署已验证，并在 `docs/release-records.md` 中留下记录，才算完成一次发布。

## 2. 当前发布基线

- 代码托管：Gitee `https://gitee.com/Lucascloud/emie`
- 当前业务分支：`project_manager_system`
- 生产应用：Spring Boot 3.2、Java 17
- 生产数据库：MySQL
- 生产运行方式：Docker Compose
- 应用容器：`emie-app`
- 预览转换容器：`emie-preview-converter`
- 应用端口：`8080`
- 预览服务端口：仅监听 `127.0.0.1:3000`

注意：远端默认分支目前是 `master`，实际业务代码位于 `project_manager_system`。未完成分支治理前，所有推送和部署命令必须显式指定业务分支，禁止依赖默认分支。

## 3. 发布权限和安全边界

1. 不提交 `.env`、`.server.local.env`、数据库密码、服务器密码、令牌或私钥。
2. 不使用 `git add .`；只暂存本次确认过的文件。
3. 不使用强制推送，不改写已经推送的共享分支历史。
4. 不从有未确认改动的生产工作目录构建。
5. 生产数据库变更前必须备份，并记录备份位置。
6. 部署时必须记录旧提交和新提交的完整 SHA。
7. 发现破坏性迁移、无法备份、磁盘不足或健康检查失败时立即停止。

## 4. 发布前检查

### 4.1 规则和工作区

- 确认项目根目录的 `RTK.md` 存在并已阅读。
- 运行 `git status --short --branch`，逐项识别修改和未跟踪文件。
- 确认 `.sheet-work/`、`outputs/`、上传文件、日志和本地备份不会误入提交。
- 确认当前分支是 `project_manager_system`。

### 4.2 同步远端

```bash
git fetch emie
git status --short --branch
git rev-list --left-right --count HEAD...emie/project_manager_system
```

推送前必须确认不存在未处理的远端新提交。若分支已分叉，先检查差异，不直接覆盖远端。

### 4.3 检查提交内容

```bash
git diff --check
git diff --stat
git diff
```

重点检查：

- 是否混入生成文件、临时文件或用户数据；
- 是否包含密码、令牌、内网凭据或真实 `.env`；
- 数据库实体变更是否有对应迁移；
- 配置、Dockerfile 和 Compose 变更是否同步更新文档。

### 4.4 构建和测试

```bash
./mvnw clean package
```

测试必须全部通过。若因明确原因跳过测试，必须在发布记录中写明原因和风险，不能只记录“构建成功”。

## 5. 提交与推送

只暂存明确属于本次发布的文件：

```bash
git add <确认过的文件>
git diff --cached --check
git diff --cached --stat
git diff --cached
git commit -m "<类型>: <清晰说明>"
git push emie HEAD:project_manager_system
```

推送后执行：

```bash
git rev-parse HEAD
git ls-remote emie refs/heads/project_manager_system
```

两个提交号必须一致，并把提交号写入发布记录。

## 6. 生产部署前检查

首次接管生产环境时，需要先只读确认以下信息，并补充到 `docs/deployment.md`：

- 生产项目绝对路径；
- Docker 和 Docker Compose 版本；
- 当前运行提交；
- 数据库备份命令与备份目录；
- 可用磁盘、内存和容器状态；
- 生产 `.env` 的保存位置和权限；
- 对外访问地址及关键业务验收入口。

每次部署前必须记录：

```bash
git rev-parse HEAD
docker compose ps
docker images --digests
df -h
free -h
```

不得在 Markdown 或终端回传中打印任何密码值。

## 7. 数据库备份与迁移

生产使用 `spring.jpa.hibernate.ddl-auto=validate`，因此实体结构变化必须先完成数据库迁移，应用才能正常启动。

标准顺序：

1. 记录数据库名称和迁移脚本文件名；
2. 生成带时间戳的数据库备份；
3. 验证备份文件存在、非空且权限正确；
4. 检查迁移是否已经执行；
5. 执行迁移；
6. 验证表、字段、索引和关键数据；
7. 在发布记录中填写备份位置和迁移结果。

当前迁移脚本位于 `db/migrations/`。新迁移应具备唯一编号，优先设计为可检测、可安全重复执行；禁止修改已经在生产执行过的历史迁移。

若迁移包含删表、删字段、类型收窄、大批量数据重写或不可逆数据变更，必须单独评审回滚方案，不能跟随普通应用发布直接执行。

## 8. 应用部署

生产部署必须基于已经推送并记录的精确提交：

当前生产 `/root/emie` 是产物目录而不是 Git 工作区，因此使用以下流程：

1. 从已经推送的精确提交创建干净本地工作树并执行 `./mvnw clean package`；
2. 记录本地 JAR 的 SHA-256，上传为服务器临时文件并再次核对；
3. 备份服务器现有 `app.jar`，再原子替换为新 JAR；
4. 将目标提交写入服务器 `release-sha.txt`；
5. 在 `/root/emie` 执行 `docker compose build app` 和 `docker compose up -d app`；
6. 不覆盖服务器现有 Compose、环境配置、上传、日志和预览服务。

若生产环境未来改造成 Git 工作区，才使用以下仓库拉取流程：

```bash
git fetch emie
git switch project_manager_system
git pull --ff-only emie project_manager_system
git rev-parse HEAD
docker compose build app
docker compose up -d
```

部署过程中保留 `preview-converter`、`uploads/`、`logs/` 和 `/root/.lark-cli` 挂载，不清理未确认的持久化数据。

禁止使用会删除卷或用户数据的命令，例如未经确认的 `docker compose down -v`。

## 9. 发布后验证

至少完成以下检查：

```bash
docker compose ps
docker compose logs --tail=200 app
curl --fail --silent --show-error http://127.0.0.1:8080/api/admin/public-config
```

还需人工抽查：

- 登录页和登录流程；
- 工作台或项目列表；
- 本次变更涉及的核心功能；
- 数据库读写；
- 文件上传或预览功能（相关版本）；
- `emie-app` 与 `emie-preview-converter` 是否持续正常运行。

验证通过后才把发布状态记为“成功”。仅容器启动不代表业务验证通过。

## 10. 回滚

发布前必须记录 `OLD_SHA`。应用回滚使用上一稳定提交，禁止临时猜测版本：

```bash
git switch --detach <OLD_SHA>
docker compose build app
docker compose up -d app
```

回滚后重新执行容器、日志、接口和核心业务检查，并记录回滚原因与结果。

数据库是否回滚必须单独判断：

- 兼容性新增表、字段通常可保留；
- 删除或改写数据的迁移必须使用事先验证的恢复方案；
- 未确认影响前，不直接恢复整个数据库覆盖生产数据。

## 11. 发布完成标准

同时满足以下条件才可宣布完成：

- 本地测试通过；
- 推送后的远端 SHA 与本地一致；
- 数据库备份和迁移有记录；
- 生产运行 SHA 与计划发布 SHA 一致；
- 容器、日志、接口和核心业务验证通过；
- `docs/release-records.md` 已追加本次记录；
- 若有用户可见变化，`CHANGELOG.md` 已同步更新。

## 12. 当前待补齐项

- [x] 恢复缺失的 `RTK.md`
- [ ] 明确生产项目绝对路径
- [ ] 核验服务器 Docker Compose 和数据库备份能力
- [ ] 从代码配置中移除测试数据库密码默认值并轮换密码
- [x] 为 `.sheet-work/` 和 `outputs/` 制定忽略或归档规则
- [ ] 将数据库迁移改造成可追踪、可检测重复执行的机制
- [ ] 增加专用健康检查端点
- [ ] 建立自动发布前检查和生产回滚脚本
- [ ] 治理远端默认分支和失效的本地跟踪分支
