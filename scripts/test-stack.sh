#!/usr/bin/env bash
set -Eeuo pipefail
cd "$(dirname "$0")/.."

COMPOSE_FILE="docker-compose.test.yml"
ENV_FILE=".env"
[[ -f "$ENV_FILE" ]] || { echo "缺少 .env 测试配置" >&2; exit 1; }
set -a
source "$ENV_FILE"
set +a

action="${1:-up}"
case "$action" in
  up)
    [[ -f target/design-pm-1.0.0.jar ]] || {
      echo "未找到构建产物，先执行 Maven 构建..."
      scripts/mvnw-java21.sh package -DskipTests -q
    }
    docker compose -f "$COMPOSE_FILE" up -d --build
    echo "测试容器已启动：http://127.0.0.1:8080"
    ;;
  rebuild)
    scripts/mvnw-java21.sh package -DskipTests -q
    docker compose -f "$COMPOSE_FILE" up -d --build
    echo "测试容器已重新构建并启动：http://127.0.0.1:8080"
    ;;
  down) docker compose -f "$COMPOSE_FILE" down ;;
  restart) docker compose -f "$COMPOSE_FILE" restart ;;
  logs) docker compose -f "$COMPOSE_FILE" logs -f --tail=200 ;;
  ps) docker compose -f "$COMPOSE_FILE" ps ;;
  *) echo "用法：$0 {up|rebuild|down|restart|logs|ps}" >&2; exit 2 ;;
esac
