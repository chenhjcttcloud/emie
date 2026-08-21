# MacBook Air 异地开发与修复指南

适用于周末或异地使用另一台 MacBook Air 继续开发、排查和修复 EMIE 问题。

## 仓库基线

- 主开发分支：`master`
- Gitee 发布分支：`emie/master`
- GitHub 镜像分支：`github/main`

接手时先执行 `git fetch emie`、`git fetch github`，比较 `HEAD`、`emie/master` 和 `github/main`。三方不一致时先确认未推送工作，不要直接覆盖远端分支。

## 环境与首次接手

安装 Git、JDK 21、Docker Desktop、Node.js 和 npm。Apple Silicon Mac 无需修改项目架构配置。

```bash
mkdir -p ~/Documents
cd ~/Documents
git clone https://gitee.com/Lucascloud/emie.git emie
cd emie
git remote rename origin emie
git remote add github git@github.com:chenhjcttcloud/emie.git
git fetch github
```

未提交代码、上传文件和本地配置应使用加密方式迁移。不得提交 `.env`、`.server.local.env`、`.server.production.local.env`、`uploads/`、`logs/`、`backups/` 或 `target/`。

## 本地测试容器

在 `.env` 配置 `DESIGNPM_TEST_DB_NAME`、`DESIGNPM_TEST_DB_USER`、`DESIGNPM_TEST_DB_PASSWORD`、`DESIGNPM_TEST_DB_ROOT_PASSWORD` 和 `SPRING_DATA_REDIS_PASSWORD`，然后运行 `./scripts/test-update.sh`。

检查命令：`docker compose -f docker-compose.test.yml ps`、`docker compose -f docker-compose.test.yml logs --tail=150 test-app`。成功标准是三个测试容器正常运行，首页和 `/actuator/health` 返回 200/UP，并且前端冒烟与弹窗回归通过。

不要使用 `docker compose down -v`，否则会删除本地测试数据库和 Redis 持久数据。

## 修复 Bug

建议流程：创建 `codex/<主题>` 分支，修改前后执行 `git status --short`、`git diff --check`、`./mvnw test`，前端改动再执行 `./scripts/test-update.sh`。

完成后只暂存确认过的文件，提交后同步两个远端：`git push emie HEAD:master` 和 `git push github HEAD:main`。生产发布需要明确授权；仅在 MacBook Air 上修复或测试，不会自动发布生产。

## 常见故障

容器反复重启时查看 `docker logs --tail=200 emie-test-app`。如果提示 Flyway 校验和不一致，通常是复用了旧测试数据库卷，而历史迁移脚本发生了变化；优先保持迁移脚本与数据库状态一致，不要直接删除数据卷。

8080 被占用时执行 `lsof -nP -iTCP:8080 -sTCP:LISTEN`，确认进程属于本项目后再停止，不要使用宽泛的 `pkill`。

本地飞书默认不向真实用户扩散通知。只有明确联调时，才在本机 `.env` 配置测试开关和接收人；真实凭据不得写入仓库或文档。
