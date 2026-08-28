# EMIE 设计项目管理系统

EMIE 是面向设计团队的项目管理系统，覆盖渠道定制单、公司常规品、子任务协作、评分、文件管理以及飞书数据同步等业务。

## 主要能力

- 渠道定制单与公司常规品项目全生命周期管理
- 销售、产品企划、设计师、供应链和管理员多角色协作
- 子任务分配、接单、交付、验收和评分
- 项目附件、参考图、公开分享和 PDF/PPT 在线预览
- 产品类目、参考价格、合规项和 IP 选项配置
- 飞书用户与多维表格同步
- 文件归档、恢复和操作日志管理

## 技术栈

- Java 21
- Spring Boot 3.2
- Spring Data JPA
- MySQL
- 原生 HTML、CSS、JavaScript
- Docker Compose

## 快速开始

### 环境要求

- JDK 21
- Git
- 可访问的 MySQL 测试数据库
- Docker 与 Docker Compose（仅容器部署需要）
- Node.js（仅前端语法检查或模板工具需要）

### 配置开发环境

复制环境变量示例并填写测试数据库配置：

```bash
cp .env.example .env
```

`.env` 已被 Git 忽略。`./dev.sh` 会在启动时自动读取该文件；直接运行其他命令时，可以手动加载到当前 shell：

```bash
set -a
source .env
set +a
```

不得把真实密码填写到 `.env.example` 或任何已跟踪文件。

### 启动应用

```bash
./dev.sh start
```

常用命令：

```bash
./dev.sh restart
./dev.sh stop
./dev.sh log
./mvnw clean package
```

默认开发端口为 `8080`，开发脚本使用 `dev` profile。更完整的配置、调试和测试说明见 [开发指南](docs/development.md)。

## 项目目录

```text
.
├── src/
│   ├── main/java/              # Spring Boot 后端代码
│   ├── main/resources/         # 应用配置与前端静态资源
│   └── test/java/              # 自动化测试
├── db/
│   ├── migrations/             # 生产数据库迁移
│   ├── init.sql                # 数据库初始化脚本
│   └── init-clean.sql          # 清理后的初始化脚本
├── scripts/
│   ├── dev.sh                  # 本地开发启动脚本
│   └── project-import/         # 项目导入模板生成工具
├── docs/
│   ├── README.md               # 文档索引
│   └── templates/              # 可交付的业务模板及预览
├── Dockerfile
├── docker-compose.yml
├── pom.xml
├── CHANGELOG.md
├── CONTRIBUTING.md             # 协作与贡献说明
├── RTK.md                      # 项目操作与发布规范
└── SECURITY.md                 # 安全说明
```

以下目录仅保存本地运行或工具产物，不属于代码仓库内容：

- `.sheet-work/`
- `outputs/`
- `target/`
- `uploads/`
- `logs/`
- `backups/`

## 文档入口

- [项目文档索引](docs/README.md)
- [开发指南](docs/development.md)
- [开发交接状态](docs/development-handoff.md)
- [仓库目录规范](docs/repository-structure.md)
- [业务流程](docs/业务流程.md)
- [部署信息](docs/deployment.md)
- [发布操作手册](docs/release-runbook.md)
- [生产发布记录](docs/release-records.md)
- [项目执行规范](RTK.md)
- [协作与贡献说明](CONTRIBUTING.md)
- [安全说明](SECURITY.md)

## 分支与发布

当前本地生产构建分支为 `master`。固定推送规则如下：Gitee 远端 `emie` 推送到 `master`，GitHub 远端 `github` 推送到 `main`；生产发布前使用本地 `master` 完成构建和验证。

任何提交、推送、数据库迁移或生产部署操作都必须遵循 [RTK.md](RTK.md) 和 [发布操作手册](docs/release-runbook.md)。

实际推送和生产部署状态以 [生产发布记录](docs/release-records.md) 为准。
