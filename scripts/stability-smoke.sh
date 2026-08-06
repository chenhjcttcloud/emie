#!/usr/bin/env bash
# 轻量稳定性冒烟：不写业务数据，只验证入口、公开配置和并发错误率。
set -Eeuo pipefail

BASE_URL="${1:-http://127.0.0.1:8080}"
CONCURRENCY="${STABILITY_CONCURRENCY:-20}"
REQUESTS="${STABILITY_REQUESTS:-100}"
BASE_URL="${BASE_URL%/}"

[[ "$CONCURRENCY" =~ ^[1-9][0-9]*$ && "$REQUESTS" =~ ^[1-9][0-9]*$ ]] || {
  echo 'STABILITY_CONCURRENCY 和 STABILITY_REQUESTS 必须是正整数。' >&2
  exit 2
}

for endpoint in / /api/admin/public-config; do
  code="$(curl -ksS -o /dev/null -w '%{http_code}' --max-time 10 "$BASE_URL$endpoint")"
  [[ "$code" =~ ^2[0-9][0-9]$ ]] || { echo "基础检查失败 endpoint=$endpoint http=$code" >&2; exit 1; }
done

tmp_dir="$(mktemp -d /tmp/emie-stability-smoke.XXXXXX)"
trap 'rm -rf "$tmp_dir"' EXIT

seq "$REQUESTS" | xargs -P "$CONCURRENCY" -I{} sh -c \
  'curl -ksS -o /dev/null -w "%{http_code}\n" --max-time 10 "$0/api/admin/public-config"' "$BASE_URL" \
  >"$tmp_dir/statuses"

total="$(wc -l < "$tmp_dir/statuses" | tr -d ' ')"
server_errors="$(awk '$1 ~ /^5/ { count++ } END { print count + 0 }' "$tmp_dir/statuses")"
timeouts="$(awk '$1 == 000 { count++ } END { print count + 0 }' "$tmp_dir/statuses")"
echo "stability_smoke base=$BASE_URL requests=$total concurrency=$CONCURRENCY 5xx=$server_errors timeouts=$timeouts"

if (( server_errors > 0 || timeouts > 0 )); then
  exit 1
fi
