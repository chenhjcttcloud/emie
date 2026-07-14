# EMIE 仓库目录规范

本文档说明各目录的职责、是否进入 Git，以及整理仓库时的边界。

## 1. 标准结构

```text
.
├── src/                         # 应用源码与测试
├── db/                          # 数据库初始化与迁移
├── scripts/                     # 可复用开发和辅助工具
├── docs/                        # 项目说明与可交付文档
│   └── templates/               # 经检查的业务模板
├── .dockerignore                # Docker 构建上下文排除规则
├── .editorconfig                # 编辑器格式规范
├── .env.example                 # 安全的环境变量示例
├── .gitattributes               # Git 文本与二进制属性
├── .gitignore                   # Git 排除规则
├── CHANGELOG.md                 # 产品和工程更新日志
├── CONTRIBUTING.md              # 协作说明
├── Dockerfile                   # 应用镜像构建
├── README.md                    # 仓库入口
├── RTK.md                       # 最高优先级项目执行规范
├── SECURITY.md                  # 安全说明
├── docker-compose.yml           # 生产服务编排
├── mvnw                         # Maven Wrapper 入口
└── pom.xml                      # Maven 项目配置
```

## 2. Git 跟踪目录

| 路径 | 内容 | 维护要求 |
|---|---|---|
| `src/` | 业务代码、配置、静态资源和测试 | 路径与运行时约定保持稳定 |
| `db/` | 初始化和增量迁移 | 生产执行过的迁移不可修改 |
| `scripts/` | 可复用、可说明的工具 | 不包含本机绝对路径和私有依赖软链接 |
| `docs/` | 业务、开发、部署和发布文档 | 变更后检查相对链接 |
| `docs/templates/` | 经检查的可交付模板 | 生成过程产物先留在 `outputs/` |

## 3. 本地目录

以下目录可存在于项目工作区，但不进入 Git：

| 路径 | 用途 |
|---|---|
| `.sheet-work/` | 表格工具临时脚本、依赖和检查结果 |
| `.workbuddy/`、`.codebuddy/` | 本地辅助工具配置和记忆 |
| `.idea/`、`.vscode/` | 本地 IDE 配置 |
| `target/` | Maven 构建产物 |
| `outputs/` | 本地生成文件和检查输出 |
| `uploads/` | 应用上传和预览缓存 |
| `logs/` | 应用日志和归档 |
| `backups/` | 本地部署或数据库备份 |
| `data/` | 本地运行数据 |

忽略不等于可以随意删除。`uploads/`、`backups/`、`data/` 和日志可能包含业务或恢复所需数据，整理目录时只调整 Git 边界，不自动清空。

## 4. 根目录文件规则

根目录只保留以下类型文件：

- 构建入口：`pom.xml`、`mvnw`、`Dockerfile`、`docker-compose.yml`；
- 项目入口：`README.md`、`RTK.md`、`CHANGELOG.md`；
- 协作与安全：`CONTRIBUTING.md`、`SECURITY.md`；
- 仓库配置：`.gitignore`、`.dockerignore`、`.editorconfig`、`.gitattributes`、`.env.example`；
- 必要快捷脚本：`dev.sh`。

临时脚本应放入本地工具目录；需要长期维护的脚本应放入 `scripts/` 并附使用说明。

## 5. 文档归档规则

- 项目总览和入口放在根目录 `README.md`；
- 开发、架构、业务、集成、部署和发布说明放在 `docs/`；
- 可交付模板放在 `docs/templates/`；
- 实际发布记录追加到 `docs/release-records.md`；
- 工具自身说明放在对应工具目录的 `README.md`。

不得把临时截图、检查缓存或一次性输出直接放入 `docs/`。确需长期保留时，应归类、命名并在文档索引中说明用途。

## 6. 整理检查

目录调整后至少检查：

```bash
git status --short --branch
git diff --check
git diff --cached --check
```

还需确认：

- 移动文件内容没有变化；
- Markdown 相对链接有效；
- Docker 构建所需文件未被 `.dockerignore` 排除；
- 本地生成物和敏感文件被正确忽略；
- 业务代码和运行时路径没有因整理被意外修改。
