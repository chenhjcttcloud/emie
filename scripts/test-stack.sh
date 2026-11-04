#!/usr/bin/env bash
set -Eeuo pipefail
cd "$(dirname "$0")/.."

COMPOSE_FILE="docker-compose.test.yml"
ENV_FILE=".env"
[[ -f "$ENV_FILE" ]] || { echo "缺少 .env 测试配置" >&2; exit 1; }
set -a
source "$ENV_FILE"
set +a
ROOT="$(pwd -P)"
BUILD_ROOT="$(bash "$ROOT/scripts/test-build-root.sh")"

build_test_app() {
  if [[ "$BUILD_ROOT" != "$ROOT" ]]; then
    python3 "$ROOT/scripts/verified-build.py" --source-root "$BUILD_ROOT" --mode package \
      --output "$ROOT/target/design-pm-1.0.0.jar"
  else
    python3 "$ROOT/scripts/verified-build.py" --source-root "$BUILD_ROOT" --mode package
  fi
}

action="${1:-up}"
case "$action" in
  up)
    build_test_app
    docker compose -f "$COMPOSE_FILE" up -d --build
    echo "测试容器已启动：http://127.0.0.1:8080"
    ;;
  rebuild)
    build_test_app
    previous_container="$(docker compose -f "$COMPOSE_FILE" ps -q test-app)"
    docker compose -f "$COMPOSE_FILE" up -d --build
    current_container="$(docker compose -f "$COMPOSE_FILE" ps -q test-app)"
    # 仅文档改动或重复验证会命中相同镜像；仍按本地更新约定重启应用。
    if [[ -n "$previous_container" && "$previous_container" == "$current_container" ]]; then
      docker compose -f "$COMPOSE_FILE" restart test-app
    fi
    echo "测试容器已重新构建并启动：http://127.0.0.1:8080"
    ;;
  down) docker compose -f "$COMPOSE_FILE" down ;;
  restart) docker compose -f "$COMPOSE_FILE" restart ;;
  logs) docker compose -f "$COMPOSE_FILE" logs -f --tail=200 ;;
  ps) docker compose -f "$COMPOSE_FILE" ps ;;
  *) echo "用法：$0 {up|rebuild|down|restart|logs|ps}" >&2; exit 2 ;;
esac
