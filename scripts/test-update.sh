#!/usr/bin/env bash
# 本地测试环境标准更新流程：缓存版本 -> 前端语法检查 -> Java 构建 -> 容器重建 -> 健康检查。
set -Eeuo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd -P)"

echo '[1/8] 更新静态资源版本号'
if [[ $# -gt 0 ]]; then
  "$ROOT/scripts/update-static-versions.sh" "$@"
else
  "$ROOT/scripts/update-static-versions.sh"
fi

echo '[2/8] 检查前端 JavaScript 语法'
if command -v node >/dev/null 2>&1; then
  while IFS= read -r file; do
    node --check "$file" >/dev/null
  done < <(find "$ROOT/src/main/resources/static/js" -type f -name '*.js' -print)
else
  echo '未找到 Node.js，跳过前端语法检查' >&2
fi

echo '[3/8] 构建 Java 应用'
"$ROOT/scripts/mvnw-java21.sh" clean package -DskipTests -q

echo '[4/8] 重建并启动测试容器'
"$ROOT/scripts/test-stack.sh" rebuild >/tmp/emie-test-update.log 2>&1 || {
  cat /tmp/emie-test-update.log >&2
  exit 1
}

echo '[5/8] 等待应用健康'
ready=0
for _ in $(seq 1 30); do
  if curl -fsS http://127.0.0.1:8080/ >/dev/null 2>&1; then ready=1; break; fi
  sleep 2
done
[[ "$ready" == 1 ]] || { cat /tmp/emie-test-update.log >&2; exit 1; }

echo '[6/8] 验证容器、数据库和 Redis'
docker compose -f docker-compose.test.yml ps
health_json="$(curl -fsS --max-time 10 http://127.0.0.1:8080/actuator/health)" || {
  echo '应用、数据库或 Redis 健康检查失败' >&2; exit 1;
}
python3 - "$health_json" <<'PY' || {
import json, sys
payload = json.loads(sys.argv[1])
raise SystemExit(0 if payload.get('status') == 'UP' else 1)
PY
  echo '应用、数据库或 Redis 健康状态不是 UP' >&2; exit 1;
}
if docker compose -f docker-compose.test.yml logs --tail=120 test-app | grep -Eq 'UnknownHostException|Communications link failure|APPLICATION FAILED TO START|SyntaxError'; then
  echo '发现应用启动错误日志，停止本次更新' >&2
  docker compose -f docker-compose.test.yml logs --tail=120 test-app >&2
  exit 1
fi
echo '[7/8] 浏览器级入口冒烟检查'
smoke_html="$(curl -fsS --max-time 10 http://127.0.0.1:8080/)" || {
  echo '首页入口检查失败' >&2; exit 1;
}
grep -Eq '产品管理系统|/js/bootstrap\.js' <<<"$smoke_html" || {
  echo '首页入口内容不完整' >&2; exit 1;
}
for asset in /js/bootstrap.js /js/core-runtime.js /css/app.css; do
  curl -fsS --max-time 10 "http://127.0.0.1:8080${asset}" >/dev/null || {
    echo "静态资源检查失败: ${asset}" >&2; exit 1;
  }
done
CHROME_BIN="${CHROME_BIN:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
if [[ -x "$CHROME_BIN" ]]; then
  smoke_dir="$(mktemp -d /tmp/emie-browser-smoke.XXXXXX)"
  if ! python3 - "$CHROME_BIN" "$smoke_dir" <<'PY'
import os, signal, subprocess, sys, time
chrome, out_dir = sys.argv[1:]
cmd = [chrome, '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
       '--disable-background-networking', '--enable-logging=stderr', '--log-level=0',
       f'--user-data-dir={out_dir}', '--dump-dom', '--virtual-time-budget=5000',
       'http://127.0.0.1:8080/']
with open(out_dir + '/dom.html', 'w') as dom, open(out_dir + '/browser.log', 'w') as log:
    process = subprocess.Popen(cmd, stdout=dom, stderr=log, start_new_session=True)
    deadline = time.monotonic() + 20
    rendered_at = None
    while process.poll() is None:
        try:
            with open(out_dir + '/dom.html', encoding='utf-8') as captured:
                rendered_so_far = captured.read()
        except UnicodeDecodeError:
            rendered_so_far = ''
        app_ready = 'data-app-ready="login"' in rendered_so_far or \
                    'data-app-ready="authenticated"' in rendered_so_far or \
                    'data-app-ready="pending"' in rendered_so_far
        if rendered_at is None and '</html>' in rendered_so_far.lower() and app_ready:
            rendered_at = time.monotonic()
        # Chrome 151 on macOS may finish --dump-dom but keep background processes alive.
        # Allow console logs to flush, then stop the whole isolated process group.
        if rendered_at is not None and time.monotonic() - rendered_at >= 2:
            return_code = 0
            break
        if time.monotonic() >= deadline:
            return_code = 1
            print('浏览器冒烟检查超时且未生成完整 DOM', file=sys.stderr)
            break
        time.sleep(0.25)
    else:
        return_code = process.returncode
    if process.poll() is None:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
    if return_code != 0:
        raise SystemExit(return_code)

with open(out_dir + '/dom.html', encoding='utf-8') as dom:
    rendered = dom.read()
if '</html>' not in rendered.lower() or 'data-app-ready=' not in rendered:
    print('浏览器未完成应用初始化', file=sys.stderr)
    raise SystemExit(1)
PY
  then
    cat "$smoke_dir/browser.log" >&2; exit 1;
  fi
  if grep -Eiq 'Uncaught ([[:alnum:]_]*Error|Exception)|Failed to load module script|SyntaxError' "$smoke_dir/browser.log"; then
    cat "$smoke_dir/browser.log" >&2
    exit 1
  fi
  rm -rf "$smoke_dir"
else
  echo "未找到 Chrome，无法执行浏览器级检查: $CHROME_BIN" >&2
  exit 1
fi
echo '[8/8] 弹窗交互浏览器回归'
if command -v npm >/dev/null 2>&1; then
  (
    cd "$ROOT/scripts/modal-e2e"
    if [[ ! -d node_modules/playwright ]]; then npm ci --silent; fi
    npm test
  )
else
  echo '未找到 npm，无法执行弹窗交互浏览器回归' >&2
  exit 1
fi
printf 'test_update=ok url=http://127.0.0.1:8080\n'
