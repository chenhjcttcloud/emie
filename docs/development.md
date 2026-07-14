# EMIE 开发指南

## 1. 环境要求

| 工具 | 要求 | 用途 |
|---|---|---|
| JDK | 17 | 编译和运行 Spring Boot |
| Maven | 使用 `./mvnw` | 构建与测试 |
| MySQL | 可访问测试库 | `dev`、`local` profile 数据源 |
| Node.js | 可选 | JavaScript 语法检查和模板工具 |
| Docker | 可选 | 容器镜像和生产等价环境 |

## 2. 环境变量

先复制示例文件：

```bash
cp .env.example .env
```

开发/测试数据库变量：

| 变量 | 说明 |
|---|---|
| `DESIGNPM_TEST_DB_HOST` | 测试数据库地址 |
| `DESIGNPM_TEST_DB_NAME` | 测试数据库名称 |
| `DESIGNPM_TEST_DB_USER` | 测试数据库用户 |
| `DESIGNPM_TEST_DB_PASSWORD` | 测试数据库密码 |

生产变量 `DESIGNPM_DB_*` 只用于生产或明确的生产等价验证，不应在日常开发中加载。

将 `.env` 加载到当前 shell：

```bash
set -a
source .env
set +a
```

`.env` 和 `.server.local.env` 都是本地敏感文件，不得提交或复制到文档。

## 3. 启动与停止

```bash
./dev.sh start
./dev.sh restart
./dev.sh stop
./dev.sh log
```

根目录 `dev.sh` 是快捷入口，实际逻辑位于 `scripts/dev.sh`。开发脚本使用 `dev` profile，默认端口为 `8080`，日志写入 `/tmp/emie-dev.log`。

`dev` 和 `local` profile 使用测试 MySQL，并允许 Hibernate 更新测试库结构；`prod` profile 使用 `ddl-auto=validate`，不能依赖应用自动修改生产数据库。

## 4. 代码结构

后端包位于 `src/main/java/com/emie/designpm/`：

| 目录 | 职责 |
|---|---|
| `config/` | Web、认证、调度和缓存配置 |
| `controller/` | HTTP API 控制器 |
| `dto/` | API 数据传输对象 |
| `entity/` | JPA 实体 |
| `repository/` | Spring Data JPA 数据访问 |
| `service/` | 业务逻辑、同步、预览和归档 |
| `util/` | 通用工具 |

前端静态资源位于 `src/main/resources/static/`，应用配置位于 `src/main/resources/application.yml`。

## 5. 前端开发

前端使用原生 HTML、CSS 和 JavaScript：

- 页面入口：`static/index.html`
- 样式：`static/css/app.css`
- 主要逻辑：`static/js/app.js`

修改 `app.js` 后同步递增 `index.html` 中的 `app.js?v=` 参数，避免浏览器缓存旧脚本。

语法检查：

```bash
node --check src/main/resources/static/js/app.js
```

## 6. 构建与测试

完整验证：

```bash
git diff --check
./mvnw clean package
```

仅运行测试：

```bash
./mvnw test
```

测试代码位于 `src/test/java/`。提交前测试必须全部通过；若确实无法执行，应在发布记录中写明原因。

## 7. 数据库开发

- 初始化脚本：`db/init.sql`、`db/init-clean.sql`
- 增量迁移：`db/migrations/`

生产迁移文件使用唯一、可排序的名称。实体结构改变后应先准备迁移，再验证 `prod` profile 的结构兼容性。

不要修改已经在生产执行过的迁移文件，也不要把生产数据库导出文件放入仓库。

## 8. 项目导入模板

生成工具位于 `scripts/project-import/`，可交付模板位于 `docs/templates/project-import/`。生成结果先写入本地 `outputs/`，检查通过后再更新可交付模板。

该工具依赖 Codex 工作区提供的 `@oai/artifact-tool`，具体说明见 `scripts/project-import/README.md`。

## 9. 调试与常见问题

### 应用启动失败并提示数据库结构不一致

确认当前 profile。生产 `prod` profile 不会自动创建缺失字段，需要先执行对应迁移。

### 前端修改后浏览器仍显示旧版本

确认 `index.html` 中静态资源版本已递增，并清理浏览器缓存或强制刷新。

### 开发脚本无法连接数据库

确认测试数据库变量已经加载到当前 shell，并检查网络、数据库用户权限和数据库名称。

### 端口被占用

检查 `8080` 端口上的现有进程，确认是否为本项目实例。不要直接终止来源不明的进程。
