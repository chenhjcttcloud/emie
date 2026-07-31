#!/usr/bin/env bash
# 只更新受影响静态资源的缓存版本号。不会改业务逻辑，也不会触碰运行中的服务。
set -Eeuo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd -P)"
BOOTSTRAP="$ROOT/src/main/resources/static/js/bootstrap.js"
INDEX="$ROOT/src/main/resources/static/index.html"

increment_import() {
  local module="$1" file="$2" current next
  current="$(sed -n "s#^import './${module}?v=\([0-9][0-9]*\)';#\1#p" "$BOOTSTRAP" | head -1)"
  [[ -n "$current" ]] || return 0
  next=$((current + 1))
  sed -i '' "s#\./${module}?v=${current}'#./${module}?v=${next}'#" "$BOOTSTRAP"
  printf 'cache_bump=%s v%s->v%s\n' "$module" "$current" "$next"
}

files=("$@")
if [[ ${#files[@]} -eq 0 ]]; then
  while IFS= read -r file; do files+=("$file"); done < <(git diff --name-only --diff-filter=ACMR -- src/main/resources/static/js)
fi

for file in "${files[@]}"; do
  module="$(basename "$file")"
  [[ "$module" == *.js && -f "$ROOT/src/main/resources/static/js/$module" ]] || continue
  [[ "$module" == "bootstrap.js" ]] || increment_import "$module" "$file"
done

# bootstrap 本身是唯一入口；任一模块版本变化后，入口也必须更新，确保浏览器不复用旧 import 图。
current="$(sed -n 's#.*<script type="module" src="/js/bootstrap.js?v=\([0-9][0-9]*\)"></script>.*#\1#p' "$INDEX" | head -1)"
if [[ -n "$current" ]]; then
  next=$((current + 1))
  sed -i '' "s#bootstrap.js?v=${current}#bootstrap.js?v=${next}#" "$INDEX"
  printf 'cache_bump=bootstrap.js v%s->v%s\n' "$current" "$next"
fi
