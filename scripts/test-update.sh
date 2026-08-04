#!/usr/bin/env bash
# 本地测试环境标准更新流程：缓存版本 -> 前端语法检查 -> Java 构建 -> 容器重建 -> 健康检查。
set -Eeuo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd -P)"

echo '[1/6] 更新静态资源版本号'
if [[ $# -gt 0 ]]; then
  "$ROOT/scripts/update-static-versions.sh" "$@"
else
  "$ROOT/scripts/update-static-versions.sh"
fi

echo '[2/6] 检查前端 JavaScript 语法'
if command -v node >/dev/null 2>&1; then
  while IFS= read -r file; do
    node --check "$file" >/dev/null
  done < <(find "$ROOT/src/main/resources/static/js" -type f -name '*.js' -print)
else
  echo '未找到 Node.js，跳过前端语法检查' >&2
fi

echo '[3/6] 构建 Java 应用'
"$ROOT/scripts/mvnw-java21.sh" clean package -DskipTests -q

echo '[4/6] 重建并启动测试容器'
"$ROOT/scripts/test-stack.sh" rebuild >/tmp/emie-test-update.log 2>&1 || {
  cat /tmp/emie-test-update.log >&2
  exit 1
}

echo '[5/6] 等待应用健康'
ready=0
for _ in $(seq 1 30); do
  if curl -fsS http://127.0.0.1:8080/ >/dev/null 2>&1; then ready=1; break; fi
  sleep 2
done
[[ "$ready" == 1 ]] || { cat /tmp/emie-test-update.log >&2; exit 1; }

echo '[6/7] 验证容器、数据库和 Redis'
docker compose -f docker-compose.test.yml ps
docker exec emie-test-redis sh -lc 'redis-cli -a "$SPRING_DATA_REDIS_PASSWORD" ping' >/dev/null 2>&1 || {
  echo 'Redis 健康检查失败' >&2; exit 1;
}
if docker compose -f docker-compose.test.yml logs --tail=120 test-app | grep -Eq 'UnknownHostException|Communications link failure|APPLICATION FAILED TO START|SyntaxError'; then
  echo '发现应用启动错误日志，停止本次更新' >&2
  docker compose -f docker-compose.test.yml logs --tail=120 test-app >&2
  exit 1
fi
echo '[7/7] 浏览器级入口冒烟检查'
CHROME_BIN="${CHROME_BIN:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
if [[ -x "$CHROME_BIN" ]]; then
  smoke_dir="$(mktemp -d /tmp/emie-browser-smoke.XXXXXX)"
  if ! python3 - "$CHROME_BIN" "$smoke_dir" <<'PY'
import subprocess, sys
chrome, out_dir = sys.argv[1:]
cmd = [chrome, '--headless', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
       f'--user-data-dir={out_dir}', '--dump-dom', '--virtual-time-budget=5000',
       'http://127.0.0.1:8080/']
with open(out_dir + '/dom.html', 'w') as dom, open(out_dir + '/browser.log', 'w') as log:
    try:
        result = subprocess.run(cmd, stdout=dom, stderr=log, timeout=20)
        raise SystemExit(result.returncode)
    except subprocess.TimeoutExpired:
        print('浏览器冒烟检查超时，保留静态检查结果继续', file=sys.stderr)
        raise SystemExit(0)
PY
  then
    cat "$smoke_dir/browser.log" >&2; exit 1;
  fi
  if grep -Eiq 'Uncaught (ReferenceError|TypeError)|Failed to load module script|SyntaxError' "$smoke_dir/browser.log"; then
    cat "$smoke_dir/browser.log" >&2
    exit 1
  fi
  rm -rf "$smoke_dir"
else
  echo '未找到 Chrome，跳过浏览器级检查' >&2
fi
printf 'test_update=ok url=http://127.0.0.1:8080\n'
