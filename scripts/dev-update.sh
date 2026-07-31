#!/usr/bin/env bash
# 本地标准更新：静态缓存版本 -> Java 21 构建 -> 重启测试服务 -> 版本/健康检查。
set -Eeuo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd -P)"

"$ROOT/scripts/update-static-versions.sh" "$@"
"$ROOT/scripts/mvnw-java21.sh" clean package -DskipTests -q
"$ROOT/scripts/dev.sh" restart

served_version="$(curl -fsS http://127.0.0.1:8080/js/bootstrap.js | sed -n 's/.*project-tasks.js?v=\([0-9][0-9]*\).*/\1/p' | head -1)"
[[ -n "$served_version" ]] || { echo '测试服务未返回静态资源版本' >&2; exit 1; }
printf 'test_ready=http://127.0.0.1:8080 bootstrap_project_tasks_v=%s\n' "$served_version"
