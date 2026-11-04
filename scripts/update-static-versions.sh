#!/usr/bin/env bash
# 保留原有入口；按内容更新缓存版本，重复验证不会再次修改源码。
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
exec python3 "$ROOT/scripts/update-static-versions.py" "$@"
