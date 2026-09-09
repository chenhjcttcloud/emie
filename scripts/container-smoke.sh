#!/usr/bin/env bash
# CI 容器冒烟：构建镜像 -> 起 compose -> 等健康 -> 查静态资源 -> 查启动日志。
# 是 scripts/test-update.sh 里可移植的子集（去掉 Mac 专属的浏览器 / playwright 步骤）。
# 本地也可直接跑；CI 里由 .github/workflows/java21-ci.yml 调用。
set -Eeuo pipefail
cd "$(dirname "$0")/.."

COMPOSE_FILE="docker-compose.test.yml"

cleanup() {
  docker compose -f "$COMPOSE_FILE" logs --tail=200 test-app 2>&1 | tail -80 || true
  # 不加 -v：本地跑时保留测试库数据（见 AGENTS.md）。CI runner 是一次性的，无所谓。
  docker compose -f "$COMPOSE_FILE" down --remove-orphans || true
}
trap cleanup EXIT

echo '[1/5] 构建 Java 应用'
scripts/mvnw-java21.sh -B clean package -DskipTests -q

echo '[2/5] 起测试容器'
docker compose -f "$COMPOSE_FILE" up -d --build

echo '[3/5] 等待应用健康 (最多 120s)'
ready=0
for _ in $(seq 1 60); do
  if curl -fsS --max-time 5 http://127.0.0.1:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
    ready=1; break
  fi
  sleep 2
done
[[ "$ready" == 1 ]] || { echo '健康检查未通过' >&2; exit 1; }

echo '[4/5] 容器状态 + 健康详情'
docker compose -f "$COMPOSE_FILE" ps
curl -fsS --max-time 10 http://127.0.0.1:8080/actuator/health

echo '[5/5] 首页 + 静态资源 + 启动日志'
home="$(curl -fsS --max-time 10 http://127.0.0.1:8080/)"
grep -Eq '产品管理系统|/js/bootstrap\.js' <<<"$home" || { echo '首页内容不完整' >&2; exit 1; }
for asset in /js/bootstrap.js /js/core-runtime.js /css/app.css; do
  curl -fsS --max-time 10 "http://127.0.0.1:8080${asset}" >/dev/null || { echo "静态资源缺失: ${asset}" >&2; exit 1; }
done
if docker compose -f "$COMPOSE_FILE" logs --tail=400 test-app \
    | grep -Eq 'UnknownHostException|Communications link failure|APPLICATION FAILED TO START|BeanCreationException|no bean of type'; then
  echo '发现应用启动错误日志' >&2
  exit 1
fi

echo 'container_smoke=ok'
