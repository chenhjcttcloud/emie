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
git push emie master:master
git push github master:main
```

生产应用必须显式启用 `prod` profile，并提供 `DESIGNPM_DB_HOST`、`DESIGNPM_DB_NAME`、`DESIGNPM_DB_USER`、`DESIGNPM_DB_PASSWORD`。仓库不再为这些变量提供可连接数据库的默认值；缺少任意变量时应让部署失败，不得回退到其他数据库。`DESIGNPM_DB_USE_SSL` 默认建议保持为 `true`，只有已确认的受保护内网环境才可按实际情况调整。

## 全新环境数据库初始化（逃生门）

> 背景：迁移脚本存在基线缺口——47 张实体表中仅 18 张有 Flyway CREATE 脚本（V1 为空 baseline），其余表历史上由 Hibernate `update` 补建。因此在**全新空库**上使用默认配置（`ddl-auto: validate` + Flyway 开启）会启动失败：Flyway V2 迁移即因 `design_requirements` 表不存在而报错（已实测 2026-08-26）。存量环境（schema 齐全）不受影响。

全新环境（新机器 / CI / 新测试库）首次启动必须设置以下变量：

```dotenv
SPRING_FLYWAY_ENABLED=false
SPRING_JPA_DDL_AUTO=update
```

效果：跳过 Flyway，由 Hibernate 按实体自动建全量表结构（含 background 数据源）。已验证（2026-08-26）：全新 MySQL 8.4 空库 + 以上变量启动成功（4.5s）。

之后若需恢复 Flyway 管理，必须补齐迁移基线（专项任务：为缺失的 29 张表补 CREATE 脚本），否则不能重新开启 `validate`。

`ddl-auto` 默认值仍为 `validate`（`${SPRING_JPA_DDL_AUTO:validate}`），安全意图不变：未显式设置变量的环境（存量）保持结构校验。

应用容器 JVM 固定使用 `Asia/Shanghai`（UTC+8），由 `JAVA_TOOL_OPTIONS=-Duser.timezone=Asia/Shanghai` 和 `TZ=Asia/Shanghai` 注入，确保日志与业务时间一致。

## 当前生产部署结构

- 生产 Java 运行时为 Java 21，应用容器为 `emie-app`，使用 host 网络并监听 8080。
- 生产部署目录为 `<DEPLOY_DIR>`，持久化上传和日志目录位于 `<APP_DATA_ROOT>`；该目录是运行产物目录，不是 Git 工作区。
- 业务代码必须先推送本地 `master`（映射到 Gitee `master`、GitHub `main`），再由本地 [`scripts/release-production.sh`](../scripts/release-production.sh) 对精确提交执行 Java 21 完整构建、增量上传和生产切换。
- 发布脚本复用同一源码及工具链下已通过完整测试的 JAR；验证收据不存在或失效时自动完整构建。继续使用 SSH 连接复用和 rsync 增量上传，保留 JAR SHA-256 校验、数据库备份及候选容器回滚。
- 生产域名必须默认严格校验证书；只有当前环境明确使用自签名证书时，才可在发布命令中临时设置 `SERVER_INSECURE_TLS=true`，发布完成后恢复默认值。
- 新发布使用稳定 Java 21 运行时镜像和只读版本化 JAR 挂载：`releases/<完整提交>/app.jar → /app/app.jar`。不再为每次更新复制约 107MB JAR 并生成新镜像；首次使用新脚本会自动从当前镜像平滑迁移。
- 候选容器会在旧容器仍在线时完成环境变量、持久化目录和 JAR 挂载校验；只有候选容器创建成功后才停止旧容器。正常不可用窗口主要是 Spring Boot 自身约 22 秒的启动时间。
- 每次发布仍必须创建并校验数据库压缩备份。旧容器、旧版本化 JAR 和运行时镜像标签共同构成回滚点；失败时脚本恢复旧容器并立即退出，不会继续执行后续发布步骤。
- 生产运行提交以 `<DEPLOY_DIR>/release-sha.txt` 记录；脚本只在新容器健康检查、JAR 校验和重启次数检查全部通过后更新该文件。

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
SERVER_DEPLOY_DIR=<DEPLOY_DIR>
```

只读预检和正式发布：

```bash
./scripts/release-production.sh --preflight-only
./scripts/release-production.sh
```

正式发布入口会拒绝脏工作区、错误分支、本地与远端提交不一致、非生产标识、当前生产不健康、JAR 校验不一致及生产配置缺失等情况。目标提交已经在生产运行时会直接退出，不重复构建或重启。

## 本地验证与构建复用

完成本轮代码和交付文档后，执行一次完整本地验证：

```bash
./scripts/test-update.sh --full
```

它先更新受影响静态资源的缓存版本，再执行 Java 21 `clean package` 完整测试、容器重建/重启、应用/MySQL/Redis 健康、运行 JAR 和静态资源一致性、Chrome 入口及浏览器交互回归。日常 `./scripts/test-update.sh` 保留打包与全部容器/浏览器检查；它的跳过 Java 测试构建不能作为生产发布的完整测试证明。单独运行 `./scripts/test-stack.sh rebuild` 也会校验当前源码与产物，不会仅凭旧 JAR 存在而启动。

`scripts/verified-build.py` 在忽略目录 `.codex-build/` 保存构建收据与私有日志，收据区分打包和完整测试两档。复用时重新校验构建输入、实际 Java/Maven 工具链、构建选项和 JAR SHA-256；构建加锁，源码在构建过程中变化则拒绝签发收据。完整测试产物可供日常打包复用，打包收据不能用于生产发布。固定测试源码时按归档后的 `BUILD_ROOT` 校验，不使用开发工作区的产物。收据不是发布授权。

静态版本脚本按资源内容及已应用版本记录状态，重复检查不再递增相同资源版本；新编辑仍刷新对应引用与入口。仅改文档不会使应用构建失效，但每次标准本地更新仍执行容器与浏览器检查。缓存丢失时安全回退到重新构建/刷新版本。生产上传使用校验后独立保存的临时 JAR 副本，避免后续本地构建覆盖上传源文件。

## 全局业务助手的模型配置

标准原子发布复用当前容器的环境变量，不通过 Compose 重建生产服务。需要为下一次候选容器设置 AI 参数时，在服务器部署目录维护独立私密文件 `<DEPLOY_DIR>/.visual-ai.env`；必须是 root 拥有、权限 `0600` 的普通文件，禁止提交到仓库或打印其中的密钥。

文件仅允许 `VISUAL_AI_ENABLED`、`VISUAL_AI_BASE_URL`、`VISUAL_AI_MODEL`、`VISUAL_AI_API_KEY`、`VISUAL_AI_ENCRYPTION_KEY` 五个变量。变量名与文件名保留历史兼容，现在供全局业务助手按需调用模型，不能恢复已退役的独立批量识图功能。每行严格使用 `KEY=value`，可以有空行和以 `#` 开头的整行注释；不写 `export`、引号或行内注释。发布脚本不执行该文件，按第一个 `=` 分隔变量名与原始值，后续 `=` 保留，拒绝未知或重复变量、CR 和控制字符。无需输出密钥即可核对文件属主和权限。

文件可只包含需要覆盖的变量；缺少文件或未列出的变量沿用当前容器值。配置仅在创建新候选容器时读取，不改变正在运行的旧容器；失败回滚保留旧容器原有环境。仅修改 Compose 或本机 `.env` 不会把新 AI 参数传入这条生产发布流程。

`VISUAL_AI_ENCRYPTION_KEY` 至少32位，并须与数据库中的加密配置一起持久保存。管理员在右下角助手设置中保存的模型配置优先于环境变量；迁移已有密文时须保留对应加密密钥，不能无备份轮换。配置读取只返回是否已配置密钥。发布验收不自动发送业务文件或发起模型对话；服务不可用时检查系统指南/文字检索降级提示。

V95/V96 仅新增 AI 配置、分析缓存和任务图档关联三张表。仍须在发布前完成并校验数据库备份，保持 Flyway 校验；应用回滚时保留这些附加表及迁移历史，不执行 drop/undo，也不以旧数据库整库覆盖发布后的业务修改。

V97 为小觅新增独立模型配置及全局选择表。默认项绑定服务器 Kimi，自定义配置必须分别保存自己的加密密钥；管理员可统一切换，全系统后续提问使用当前配置。备份时继续持久保存原有加密环境配置，回滚时保留新表和迁移历史。页面支持非流式 Chat Completions 兼容格式，具体型号、地域地址、图片能力及思考模式须按服务商文档填写。

V98 仅注册 `admin.feishu_sync.view`，管理员兼容读取同步状态；执行、重试和 Base 切换继续使用独立执行权限，显式拒绝仍优先，不批量改写现有授权。小觅新增模型支持接口地址和 Key 自动读取模型列表，缺少列表协议时保留手动入口；模型列表请求不保存配置、不切换模型、不发起聊天调用。

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

## 2026-10-23 Java 证书安全基线

- 本地测试运行时为 Temurin 21.0.12.1+1，额外合入 OpenJDK 官方 85974eed594aa5a9e13a6a20713156ed8d9cd0ed 的证书阻止列表；Dockerfile 与 Dockerfile.test 在构建时合并列表并保留镜像已有条目。数据及来源见 `docker/java-security/`。
- 生产本次采用官方允许的证书数据修复方案：保留 Temurin 21.0.11+10 和现有业务 JAR，只更新运行时镜像内的 blocked.certs。此项只处理 CVE-2026-91403，不代表已安装其他 JDK 安全更新。
- 已将稳定标签 `emie-app-runtime:java21` 指向包含补丁的新镜像，避免当前发布流程再次复用未修复的运行时；后续运行时替换仍需验证 86 条官方指纹及实际 JVM 阻止行为，不能只看 Java 版本或标签。
- 本次专用镜像：`emie-app-runtime:java21-cve-2026-91403-20261023_183332`。旧容器 `emie-app-security-old-20261023_183332` 保留用于回滚，数据库备份及受保护审计记录位于 `<DEPLOY_DIR>/security-updates/java-cve-2026-91403-20261023_183332/`。

## 2026-10-26 Java 累计更新与根证书修复

当前本地测试及生产均为 Temurin 21.0.13+9。因对应版本 Docker 标签尚不可用，`docker/java-security/install-runtime.sh` 使用 Adoptium 官方发布包和固定 SHA-256 安装整个 JRE，支持 Linux x64/aarch64。`apply-security.sh` 保留 86 条中间证书阻止指纹，并按官方 https://ops.java/emudhra 移除 emSign Root CA - G1、G2、emSign ECC Root CA - G3、AAA Certificate Services 四个根主体（仅删除实际存在的条目，自动适配 Temurin 别名）。对应 CVE-2026-114820，不能仅凭升级到 21.0.13 判断根证书修复完成。

生产安全镜像为 `emie-app-runtime:java21-cve-2026-114820-20261026_093431`，稳定标签 `emie-app-runtime:java21` 同步指向该镜像。旧容器 `emie-app-security-old-20261026_093431` 及旧镜像保留；受保护备份/审计目录为 `<DEPLOY_DIR>/security-updates/java-cve-2026-114820-20261026_093431/`。本次只更新 Java 运行时和证书，业务 JAR、环境、挂载及资源限制保持原样。
