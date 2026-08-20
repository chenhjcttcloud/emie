# EMIE 部署信息

## 发布授权规则

后续代码修改默认只在本地完成和验证，不自动提交、推送远程仓库或发布生产。测试、容器重建和本地浏览器检查不等于获得发布授权。只有用户对当前变更明确授权后，才允许执行 commit、push、生产迁移或生产发布；没有明确授权时，应报告本地验证结果并保留工作区改动。

生产发布的完整检查、推送、迁移、验证和回滚流程见 [`release-runbook.md`](release-runbook.md)，每次实际操作必须追加到 [`release-records.md`](release-records.md)。本文件只维护相对稳定的环境与服务信息，不记录任何真实密码。

## 固定仓库分支约定

这是固定规则，后续不得混用：

- Gitee（远端 `emie`）只推送 `master`；
- GitHub（远端 `github`）只推送 `main`；
- 生产发布前使用本地 `master` 分支进行构建和验证，再将同一提交推送到 Gitee `master` 和 GitHub `main`。

示例：

```bash
git push emie project_manager_system:master
git push github project_manager_system:main
```

生产应用必须显式启用 `prod` profile，并提供 `DESIGNPM_DB_HOST`、`DESIGNPM_DB_NAME`、`DESIGNPM_DB_USER`、`DESIGNPM_DB_PASSWORD`。仓库不再为这些变量提供可连接数据库的默认值；缺少任意变量时应让部署失败，不得回退到其他数据库。`DESIGNPM_DB_USE_SSL` 默认建议保持为 `true`，只有已确认的受保护内网环境才可按实际情况调整。

应用容器 JVM 固定使用 `Asia/Shanghai`（UTC+8），由 `JAVA_TOOL_OPTIONS=-Duser.timezone=Asia/Shanghai` 和 `TZ=Asia/Shanghai` 注入，确保日志与业务时间一致。

## 当前生产部署结构

- 生产 Java 运行时为 Java 21，应用容器为 `emie-app`，使用 host 网络并监听 8080。
- 生产部署目录为 `/home/emie/emie-deploy`，持久化上传和日志目录位于 `/home/emie/emie-app-data/`；该目录是运行产物目录，不是 Git 工作区。
- 业务代码必须先推送到 `project_manager_system`，再由本地 [`scripts/release-production.sh`](../scripts/release-production.sh) 对精确提交执行 Java 21 完整构建、增量上传和生产切换。
- 发布脚本使用 Maven 增量构建、SSH 连接复用和 rsync 增量上传；仍保留完整测试、JAR SHA-256 校验、数据库备份及候选容器回滚。
- 生产域名必须默认严格校验证书；只有当前环境明确使用自签名证书时，才可在发布命令中临时设置 `SERVER_INSECURE_TLS=true`，发布完成后恢复默认值。
- 新发布使用稳定 Java 21 运行时镜像和只读版本化 JAR 挂载：`releases/<完整提交>/app.jar → /app/app.jar`。不再为每次更新复制约 107MB JAR 并生成新镜像；首次使用新脚本会自动从当前镜像平滑迁移。
- 候选容器会在旧容器仍在线时完成环境变量、持久化目录和 JAR 挂载校验；只有候选容器创建成功后才停止旧容器。正常不可用窗口主要是 Spring Boot 自身约 22 秒的启动时间。
- 每次发布仍必须创建并校验数据库压缩备份。旧容器、旧版本化 JAR 和运行时镜像标签共同构成回滚点；失败时脚本恢复旧容器并立即退出，不会继续执行后续发布步骤。
- 生产运行提交以 `/home/emie/emie-deploy/release-sha.txt` 记录；脚本只在新容器健康检查、JAR 校验和重启次数检查全部通过后更新该文件。

## 本地生产发布配置

生产连接信息只保存在被 Git 忽略的 `.server.production.local.env`，不要复用历史云服务器的 `.server.local.env`。脚本要求显式的环境标识，避免连错服务器：

```dotenv
SERVER_ROLE=production-local
SERVER_HOST=<生产服务器地址>
SERVER_USER=<SSH用户>
SERVER_PORT=22
SERVER_PASSWORD=<SSH密码>
SERVER_SUDO_PASSWORD=<sudo密码；相同时可省略>
SERVER_PUBLIC_URL=https://example.com
SERVER_DEPLOY_DIR=/home/emie/emie-deploy
```

只读预检和正式发布：

```bash
./scripts/release-production.sh --preflight-only
./scripts/release-production.sh
```

正式发布入口会拒绝脏工作区、错误分支、本地与远端提交不一致、非生产标识、当前生产不健康、JAR 校验不一致及生产配置缺失等情况。目标提交已经在生产运行时会直接退出，不重复构建或重启。

## 阿里云生产服务器

以下地址仅为脱敏后的本机回环地址示例，不代表真实生产服务器。实际连接地址只保存在本机忽略文件 `.server.local.env` 中，不写入仓库。

| 配置项 | 值 |
|---|---|
| 地址示例 | `127.0.0.1` |
| SSH 用户 | `root` |
| SSH 端口 | `22` |
| 用途 | EMIE 生产环境服务器 |

服务器密码保存在项目根目录的 `.server.local.env`，该文件已加入 `.gitignore`，不会提交到版本库。

```dotenv
SERVER_HOST=127.0.0.1
SERVER_USER=root
SERVER_PORT=22
SERVER_PASSWORD=<本机忽略文件中的实际密码>
```

## 飞书备份表配置

在系统管理后台的「系统配置 → 飞书多维表格」中填写：

- `tableProjectsBackup`：项目备份表的 `tbl...` Table ID；
- `tableTasksBackup`：子任务备份表的 `tbl...` Table ID；
- `tableScoringBackup`：评分备份表的 `tbl...` Table ID。
- `tableLogsBackup`：操作日志备份表的 `tbl...` Table ID。

填写的是飞书 URL 中 `table=` 后面的 Table ID，不是表名或完整 URL。三个备份表必须与主表位于 `feishu.base.appToken` 指向的同一个 Base 中；当前实现不支持用一套 App Token 同步到另一个 Base。保存后应先用少量数据验证，确认日志没有 `TableIdNotFound` 或字段结构错误，再执行全量同步。

## PDF / PPT 在线预览

`docker-compose.yml` 中的 `preview-converter` 服务负责将 PPT/PPTX 转换为 PDF。端口只绑定到服务器回环地址 `127.0.0.1:3000`，不对公网开放；主应用通过 `APP_PREVIEW_CONVERTER_URL` 调用。

预览相关配置：

| 环境变量 | 默认值 | 说明 |
|---|---:|---|
| `APP_PREVIEW_MAX_SOURCE_BYTES` | `52428800` | 可转换演示文稿最大 50MB |
| `APP_PREVIEW_MAX_CACHE_BYTES` | `2147483648` | 预览 PDF 缓存最大 2GB |
| `APP_PREVIEW_TIMEOUT_SECONDS` | `120` | 单次转换超时时间 |

PDF 原文件直接预览；PPT/PPTX 首次访问时异步转换，结果缓存在 `uploads/preview-cache/`。部署或升级时需同时启动应用和转换服务：

```bash
docker compose up -d --build
```
