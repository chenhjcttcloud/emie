# EMIE 开发指南

## 1. 环境要求

| 工具 | 要求 | 用途 |
|---|---|---|
| JDK | 21 | 编译和运行 Spring Boot |
| Maven | 使用 `./mvnw` | 构建与测试 |
| MySQL | 可访问测试库 | `dev`、`local` profile 使用专用测试库 |
| Node.js | 可选 | JavaScript 语法检查和模板工具 |
| Docker | 可选 | 容器镜像和生产等价环境 |

## 2. 环境变量

先复制示例文件：

```bash
cp .env.example .env
```

日常开发使用 `dev` profile 连接专用测试 MySQL，以保留飞书配置、测试账号和业务测试数据。连接信息只保存在已被 Git 忽略的 `.env`，不得写入源码。

`dev` 和 `local` profile 均使用以下变量：

| 变量 | 说明 |
|---|---|
| `DESIGNPM_TEST_DB_HOST` | 测试数据库地址 |
| `DESIGNPM_TEST_DB_NAME` | 测试数据库名称 |
| `DESIGNPM_TEST_DB_USER` | 测试数据库用户 |
| `DESIGNPM_TEST_DB_PASSWORD` | 测试数据库密码 |
| `DESIGNPM_TEST_DB_USE_SSL` | 是否启用数据库 TLS，默认建议为 `true` |

生产变量 `DESIGNPM_DB_*` 只用于生产或明确的生产等价验证，不应在日常开发中加载。

`./dev.sh` 会自动加载 `.env`。直接运行 Maven 或 Java 命令时，可手动加载：

```bash
set -a
source .env
set +a
```

为避免共享测试库中的存量队列在本地启动时写入飞书，`dev` profile 默认关闭同步消费者。需要进行明确的同步联调时，再在本机 `.env` 中设置 `APP_FEISHU_SYNC_WORKER_ENABLED=true`；不得把该值写入仓库或生产配置。

`.env` 和 `.server.local.env` 都是本地敏感文件，不得提交或复制到文档。

## 3. 启动与停止

### Java 21 与生产负载控制

生产默认启用 Spring Boot 虚拟线程，减少阻塞式 HTTP 请求占用的平台线程；可通过 `SPRING_THREADS_VIRTUAL_ENABLED=false` 临时关闭。虚拟线程不会扩大数据库或飞书并发。

生产数据库连接池默认收紧为最大 10、最小空闲 2；Tomcat 默认限制为 100 个工作线程、200 个连接和 50 个排队请求，均可通过环境变量调整。飞书同步仍按队列串行消费，文件预览仍保持单线程有界队列。

```bash
./dev.sh start
./dev.sh restart
./dev.sh stop
./dev.sh log
```

根目录 `dev.sh` 是快捷入口，实际逻辑位于 `scripts/dev.sh`。开发脚本使用 `dev` profile，默认端口为 `8080`，日志写入 `/tmp/emie-dev.log`。

`dev` 和 `local` profile 连接显式配置的专用测试 MySQL，并允许 Hibernate更新测试库结构；未指定 profile 时才使用本机 H2 作为安全兜底。`prod` profile 使用 `ddl-auto=validate`，不能依赖应用自动修改生产数据库。

连接共享测试库时可显式启动：

```bash
set -a
source .env
set +a
java -jar target/design-pm-1.0.0.jar --spring.profiles.active=local
```

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
- 核心域：`core-runtime.js`、`core-auth.js`、`core-identity.js`、`core-shell.js`、`core-ui.js` 和门面 `core.js`
- 工作台域：`dashboard-projects.js`、`dashboard-home.js`、`dashboard-lists.js`、`dashboard-scoring.js`、`dashboard-designer.js` 和门面 `dashboard.js`
- 项目域门面：`static/js/projects.js`
- 项目上传：`static/js/project-uploads.js`
- 项目创建表单：`static/js/project-form.js`
- 项目详情与项目级操作：`static/js/project-detail.js`
- 子任务、交付、验收与评分：`static/js/project-tasks.js`
- 项目分享：`static/js/project-sharing.js`
- 系统管理域：`admin-shell.js`、`admin-users.js`、`admin-roles.js`、`admin-catalog.js`、`admin-org.js`、`admin-scoring.js`、`admin-audit.js`、`admin-storage.js`、`admin-workload.js` 和门面 `admin.js`
- 声明式事件：`static/js/event-runtime.js`
- 文件预览与下载：`static/js/files.js`
- 启动入口：`static/js/bootstrap.js`

页面只加载 `bootstrap.js` 这一个 `type="module"` 入口，由它按依赖顺序导入所有业务模块。修改任一前端模块后，同步递增 `bootstrap.js` 内部 import 和 `index.html` 启动入口的 `?v=` 参数。

跨模块共享的可变状态统一保存在 `window.EMIE` 下：通用状态使用 `EMIE.state`，各业务模块分别使用 `dashboardState`、`projectState`、`adminState` 和 `fileState`。模块通过 `EMIE.registerModule()` 声明公共接口，跨模块调用通过 `EMIE.actions` 解析，不向 `window` 暴露业务函数。页面交互使用 `data-emie-on*` 声明式事件，不再使用 HTML 内联 `on*` 属性。

语法检查：

```bash
for file in src/main/resources/static/js/*.js; do node --input-type=module --check < "$file"; done
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

确认 `.env` 中的测试数据库变量完整，并检查网络、数据库用户权限、TLS 配置和数据库名称。

### 端口被占用

检查 `8080` 端口上的现有进程，确认是否为本项目实例。不要直接终止来源不明的进程。
