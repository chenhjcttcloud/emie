# EMIE 部署信息

生产发布的完整检查、推送、迁移、验证和回滚流程见 [`release-runbook.md`](release-runbook.md)，每次实际操作必须追加到 [`release-records.md`](release-records.md)。本文件只维护相对稳定的环境与服务信息，不记录任何真实密码。

生产应用必须显式启用 `prod` profile，并提供 `DESIGNPM_DB_HOST`、`DESIGNPM_DB_NAME`、`DESIGNPM_DB_USER`、`DESIGNPM_DB_PASSWORD`。仓库不再为这些变量提供可连接数据库的默认值；缺少任意变量时应让部署失败，不得回退到其他数据库。`DESIGNPM_DB_USE_SSL` 默认建议保持为 `true`，只有已确认的受保护内网环境才可按实际情况调整。

## 当前生产部署结构

- 生产部署目录为 `/root/emie`，这是运行产物目录，不是 Git 工作区。
- 应用以已经推送的 `project_manager_system` 精确提交在本地干净工作树构建，再上传 `app.jar`；上传前后必须核对 SHA-256。
- 发布前将现有 `app.jar` 复制为带时间戳的备份，并额外生成、校验数据库压缩备份。
- 服务器保留现有 `docker-compose.yml`、`Dockerfile`、`uploads/`、`logs/` 和 `/root/.lark-cli`，发布时只替换已核验的应用产物并重建 `app` 服务。
- 生产运行提交以同目录的 `release-sha.txt` 记录；应用回滚使用发布前保存的 JAR，不依赖生产目录中的 Git 历史。

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
