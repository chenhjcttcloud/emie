# EMIE 设备迁移交接文档

> 恢复状态：`2026-07-23` 已在 `/Users/emie/Documents/emie` 完成新设备接管与本地验收；本文其余旧设备路径和 PID 状态作为迁移历史保留。

> 更新时间：`2026-07-23 09:40 +0800`
>
> 适用范围：将当前开发工作站迁移到另一台设备。本文不授权提交、推送、生产数据库迁移或生产部署。

## 1. 迁移结论

- 当前业务代码已位于远端业务分支，迁移代码时以 `project_manager_system` 和提交 `c1ed377f381feeec25fdb3fc666f93b17e0a9ed9` 为基线。
- 迁移盘点前工作区干净，本地与 `emie/project_manager_system` 为 `0 ahead / 0 behind`。
- 本次新增和更新的交接文档尚未提交；若新设备只重新克隆远端仓库，这些本地文档不会自动出现，必须一并复制。
- 暂停前的开发服务使用 Java 21、`dev` profile 和共享测试 MySQL，健康检查为 HTTP 200；`2026-07-23 10:10 +0800` 已按用户要求停止，本机 `8080` 当前无监听。
- 代码以外还必须处理 `.env`、`.server.local.env`、`uploads/`、`data/` 和 `backups/`。其中前两项含敏感信息，只能通过加密介质或密码管理器迁移。
- 工作站迁移不等于生产迁移。生产服务不需要因更换开发设备而搬动或重启。

## 2. 当前可核对基线

| 项目 | 当前状态 |
|---|---|
| 仓库 | `https://gitee.com/Lucascloud/emie` |
| 业务分支 | `project_manager_system` |
| 本地提交 | `c1ed377f381feeec25fdb3fc666f93b17e0a9ed9` |
| 远端同步 | `HEAD...emie/project_manager_system = 0 / 0` |
| Git 跟踪文件 | 225 个 |
| 工作区大小 | 约 172 MB，其中 `target/` 约 97 MB，可重建 |
| 本机系统 | macOS 26.5.2，Apple Silicon (`aarch64`) |
| Java | Eclipse Temurin 21.0.11 |
| Maven | Wrapper 使用 Maven 3.9.16 |
| 应用 | Spring Boot 3.2.5，默认端口 `8080` |
| 最近运行模式 | `dev` profile，连接共享测试 MySQL |
| 当前运行状态 | 已暂停；`emie-dev` 会话已关闭，`8080` 无监听 |
| 最近健康检查 | 停止前 `GET http://127.0.0.1:8080/api/admin/public-config` 返回 HTTP 200 |
| 最近确认的生产代码 | 仓库记录显示为 `efed402`；本次未登录生产核对实际运行 SHA |

PID、`screen` 会话号、本机局域网 IP 和 `/tmp/emie-dev.log` 都是设备临时状态，不应复制为新设备配置。

## 3. 最近工作内容

最新提交 `c1ed377` 主要包含：

- 新增独立的“设计/送审需求”导航、列表和创建流程，不复用 `projects` 表；
- 新增 `DesignRequirement` 实体、分页查询仓库与 `/api/design-requirements` 接口；
- 新增管理员“临时飞书通知”，临时输入标题和正文后向有效用户发送，不保存为模板；
- 调整项目创建表单、工作台列表、项目详情与部分导航展示；
- 本地启动脚本继续固定 `dev` profile，并默认关闭飞书同步消费者。

当前已知未收尾项：

1. `DesignRequirement` 使用新表 `design_requirements`，但 `db/migrations/` 下没有对应迁移。开发环境的 `ddl-auto=update` 会自动建表，生产的 `ddl-auto=validate` 不会；因此 `c1ed377` 不能直接作为生产可部署版本。
2. 当前设计需求接口只有创建和分页列表，没有详情、编辑、状态流转或删除接口；实体虽有 `attachmentsJson`，创建接口目前没有写入附件字段。
3. 未找到针对设计需求和临时广播新流程的专项回归测试；现有全量测试通过不等于这两个流程已经完成浏览器业务验收。
4. `c1ed377` 已在远端，但没有对应生产发布记录。本次没有查询生产服务器，生产是否运行该提交未知；禁止据此假定已经部署。
5. 启动命令在 `java` 和 `-jar` 之间包含 JVM 参数，但 PID 检测仍按连续的 `java -jar` 匹配，因此当前不会生成 `/tmp/emie-dev.pid`。缺少 PID 文件时，`./dev.sh restart` 会空输出退出；直接 `start` 还可能遗留旧 Java 进程，并把旧进程的 HTTP 200 误判为新进程就绪。本次已核对旧 PID 工作目录、只终止该 EMIE 进程，再成功启动新 JAR；接手后应修正脚本，修复前不能只凭脚本输出判断启停结果。

建议接手后的优先级：先补 `design_requirements` 幂等迁移和专项测试，再完成浏览器业务验收；只有收到明确发布指令后，才按发布手册处理生产。

## 4. 迁移前验证结果

`2026-07-23 09:40 +0800` 已完成：

- `git fetch emie` 成功；本地和远端业务分支一致；
- `scripts/mvnw-java21.sh clean package` 成功；
- 105 个主源码文件和 20 个测试源码文件编译成功；
- 65 项测试全部通过，0 失败、0 错误、0 跳过；
- `src/main/resources/static/js/*.js` 全部通过 Node.js 模块语法检查；
- 本机开发服务曾用新构建 JAR 重新启动并通过 HTTP 200；完成验证后已按用户要求停止，当前处于暂停状态。

构建日志中仅有现存的 Java 注解处理、动态测试代理和 `FileThumbnailService` 过时 API 警告，没有导致构建失败。

## 5. 本机数据清单

以下大小为迁移盘点时的近似值，迁移前应重新执行 `du -sh` 核对：

| 路径 | 当前规模 | 是否迁移 | 说明 |
|---|---:|---|---|
| `.git/` | 约 26 MB | 推荐 | 保留完整分支、远端和本地工作区状态；若重新克隆可不复制 |
| `.env` | 约 4 KB | 必须安全迁移 | 共享测试库连接等本机敏感配置，不得提交或写入文档 |
| `.server.local.env` | 约 4 KB | 按需安全迁移 | 生产连接信息；只有新设备需要运维权限时才迁移 |
| `uploads/` | 约 41 MB，17 个文件 | 建议完整迁移 | 本地上传与预览缓存；共享测试库中的文件记录可能引用这些路径 |
| `data/` | 约 96 KB，1 个文件 | 建议迁移 | 默认 H2 profile 的本地数据；当前 `dev` 运行不使用它 |
| `backups/` | 约 1.8 MB，1 个文件 | 建议迁移 | 本地恢复资料，不应提交 Git |
| `logs/` | 约 4 KB，1 个文件 | 可选 | 仅在需要历史诊断时迁移 |
| `target/` | 约 97 MB | 不迁移 | 构建产物，新设备执行 Maven 构建即可恢复 |
| `.idea/`、`.vscode/` | 本机配置 | 可选 | 不影响项目运行 |
| `.workbuddy/`、`.codebuddy/`、`.sheet-work/`、`outputs/` | 工具或临时产物 | 不建议 | 不属于项目运行必需资料 |

注意：当前 `dev` profile 的业务数据位于共享测试 MySQL，不在本机 `data/` 中。迁移工作站通常不需要导出该数据库，但新设备必须具备相同的网络访问条件和有效凭据。数据库备份或恢复属于单独的数据操作，不要把生产库导出混入普通工作区压缩包。

## 6. 推荐迁移方案：完整工作区加密归档

这个方案最适合当前状态，因为它能保留尚未提交的交接文档、Git 元数据和本地运行数据。

### 6.1 最终迁移窗口

先关闭会写入本地文件的开发服务，避免归档期间文件变化：

```bash
cd /Users/jinli/Documents/emie
./dev.sh stop
lsof -nP -iTCP:8080 -sTCP:LISTEN
```

由于当前 PID 匹配存在已知问题，如果 `stop` 后仍有监听进程，先用 `lsof -a -p <PID> -d cwd -Fn` 确认其工作目录确实是本项目，再对该精确 PID 发送 `TERM`；不要使用宽泛的 `pkill`。确认 `8080` 已释放后，在项目父目录创建加密归档。输出位置必须是加密磁盘或其他受控介质：

```bash
cd /Users/jinli/Documents
tar \
  --exclude='emie/target' \
  --exclude='emie/.idea' \
  --exclude='emie/.vscode' \
  --exclude='emie/.workbuddy' \
  --exclude='emie/.codebuddy' \
  --exclude='emie/.sheet-work' \
  --exclude='emie/outputs' \
  --exclude='emie/.DS_Store' \
  -czf - emie \
| openssl enc -aes-256-cbc -salt -pbkdf2 \
  -out /Volumes/<加密迁移盘>/emie-workspace-20260723.tar.gz.enc
```

命令会交互式要求设置加密密码。密码不要写进命令、仓库、聊天或同一块迁移盘。

生成校验值并另行保存：

```bash
shasum -a 256 /Volumes/<加密迁移盘>/emie-workspace-20260723.tar.gz.enc
```

如果旧设备还要继续使用，完成归档后重新启动并检查：

```bash
cd /Users/jinli/Documents/emie
./dev.sh start
curl -fsS http://127.0.0.1:8080/api/admin/public-config >/dev/null
lsof -nP -iTCP:8080 -sTCP:LISTEN
screen -ls
rg -a 'Started DesignPmApplication' /tmp/emie-dev.log | tail -n 1
```

### 6.2 在新设备恢复

先安装 Git、JDK 21 和 OpenSSL，再解密到目标目录：

```bash
mkdir -p /Users/<新用户名>/Documents
openssl enc -d -aes-256-cbc -pbkdf2 \
  -in /Volumes/<加密迁移盘>/emie-workspace-20260723.tar.gz.enc \
| tar -xzf - -C /Users/<新用户名>/Documents
```

恢复后立即检查权限与状态：

```bash
cd /Users/<新用户名>/Documents/emie
chmod +x mvnw dev.sh scripts/*.sh
git status --short --branch
git rev-parse HEAD
```

迁移验证完成后，安全删除迁移介质上的临时明文副本；加密归档是否保留由备份策略决定。

## 7. 备用方案：重新克隆代码，单独迁移本地状态

如果不需要保留 `.git/`，可先在新设备克隆精确业务分支：

```bash
git clone --branch project_manager_system --single-branch \
  https://gitee.com/Lucascloud/emie emie
cd emie
git remote rename origin emie
git checkout c1ed377f381feeec25fdb3fc666f93b17e0a9ed9
```

随后通过加密方式单独复制：

- 本文档及本次更新的本地文档；
- `.env`；
- 按需复制 `.server.local.env`；
- `uploads/`、`data/`、`backups/`；
- 需要保留时复制 `logs/`。

不要复制 `target/`。如果不复制本次本地文档，远端克隆只能恢复到 `c1ed377` 的原始文档状态。

## 8. 新设备环境恢复

### 必需软件

- Git；
- JDK 21，推荐 Eclipse Temurin 21；
- 可访问共享测试 MySQL 的网络环境；
- `screen`，可选但推荐，开发脚本会用它守护进程；
- Node.js，用于前端语法检查；
- Docker，仅在需要生产等价容器验证时安装。

macOS 可让 `scripts/java21-env.sh` 自动通过 `/usr/libexec/java_home` 寻找 JDK 21。Linux 或无法自动识别的环境需要设置：

```bash
export JAVA21_HOME=/path/to/jdk-21
```

先确认配置文件存在，但不要打印内容：

```bash
test -f .env
test -f .server.local.env   # 仅运维需要时
```

如果不迁移旧 `.env`，从安全示例重新创建并通过受控渠道填写凭据：

```bash
cp .env.example .env
chmod 600 .env
```

开发环境必须保持 `APP_FEISHU_SYNC_WORKER_ENABLED=false`，除非正在执行明确的飞书同步联调。

## 9. 新设备验收清单

按顺序执行：

```bash
cd /Users/<新用户名>/Documents/emie
scripts/mvnw-java21.sh --version
git fetch emie
git rev-list --left-right --count HEAD...emie/project_manager_system
git status --short --branch
scripts/mvnw-java21.sh clean package
for file in src/main/resources/static/js/*.js; do
  node --input-type=module --check < "$file"
done
./dev.sh start
curl -fsS http://127.0.0.1:8080/api/admin/public-config >/dev/null
```

验收标准：

- Java 主版本为 21；
- 提交 SHA 与预期一致；
- 除交接文档等已知本地改动外，没有意外修改；
- Maven 构建成功，65 项基线测试不减少且全部通过；
- 全部前端模块语法通过；
- `8080` 健康检查成功；
- `lsof`、`screen -ls` 和最新启动日志指向同一个新进程，不能只读取到旧进程的 HTTP 200；
- 可以用测试账号登录并打开工作台、项目列表和“设计/送审需求”；
- 随机抽查原有上传文件可以访问；
- 不应因开发服务启动而向飞书自动消费共享测试库队列。

如果新设备的测试数量增加，只要新增测试也全部通过即可；如果数量减少，先检查是否漏复制源码或切错分支。

## 10. 生产与安全边界

- 生产部署目录 `/root/emie` 是服务器运行产物目录，不是本次工作站迁移目标。
- 不要把 `.env`、`.server.local.env`、数据库导出、上传文件或备份提交到 Git。
- 不要在新设备上直接启用 `prod` profile 验证连接。
- 不要在缺少数据库备份和 `design_requirements` 迁移时部署 `c1ed377`。
- 不要复制浏览器 cookie、SSH 私钥或飞书 token 到普通压缩包；如确需迁移，应使用各自的安全凭据管理方式。
- 生产操作只有在用户明确授权后，才按 [`release-runbook.md`](release-runbook.md) 执行。

## 11. 接手入口

下一位开发者或 AI 应按以下顺序阅读：

1. [`RTK.md`](../RTK.md)：最高优先级项目操作规范；
2. 本文：设备迁移基线、数据范围和恢复步骤；
3. [`development-handoff.md`](development-handoff.md)：历史开发进度和风险；
4. [`development.md`](development.md)：开发环境与命令；
5. [`repository-structure.md`](repository-structure.md)：Git 跟踪边界；
6. [`deployment.md`](deployment.md) 与 [`release-runbook.md`](release-runbook.md)：仅在明确授权生产工作时阅读执行。
