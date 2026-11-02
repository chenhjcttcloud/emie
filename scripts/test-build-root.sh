#!/usr/bin/env bash
# 本地可固定运行已提交版本；开发工作区中的未发布修改保持原样。
set -Eeuo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd -P)"
if [[ -f .env ]]; then
  set -a
  source .env
  set +a
fi

ref="${EMIE_TEST_SOURCE_REF:-}"
if [[ -z "$ref" ]]; then
  printf '%s\n' "$ROOT"
  exit 0
fi
[[ "$ref" =~ ^[0-9a-f]{40}$ ]] || { echo 'EMIE_TEST_SOURCE_REF 必须是完整提交 SHA' >&2; exit 1; }
[[ "$(git rev-parse --verify "${ref}^{commit}")" == "$ref" ]] || exit 1

snapshot="$ROOT/.codex-build/test-sources/$ref"
if [[ ! -f "$snapshot/.emie-test-source-ref" ]]; then
  mkdir -p "$snapshot"
  git archive "$ref" | tar -xf - -C "$snapshot"
  printf '%s\n' "$ref" > "$snapshot/.emie-test-source-ref"
fi
[[ "$(cat "$snapshot/.emie-test-source-ref")" == "$ref" ]] || exit 1

# 依据 Git 中的对象校验缓存源码，避免标记正确但缓存文件已被编辑。
python3 - "$ROOT" "$snapshot" "$ref" <<'PY'
import hashlib
import os
from pathlib import Path
import subprocess
import sys

root, directory, ref = sys.argv[1:]
snapshot = Path(directory)
expected = set()
for entry in subprocess.check_output(['git', '-C', root, 'ls-tree', '-r', '-z', ref]).split(b'\0'):
    if not entry:
        continue
    metadata, name = entry.split(b'\t', 1)
    mode, kind, oid = metadata.split()
    if kind != b'blob':
        continue
    relative = os.fsdecode(name)
    expected.add(relative)
    path = snapshot / relative
    content = os.fsencode(os.readlink(path)) if mode == b'120000' else path.read_bytes()
    digest = hashlib.sha1(b'blob ' + str(len(content)).encode() + b'\0' + content).hexdigest()
    if digest != oid.decode():
        raise SystemExit('固定版本源码缓存已变更，请重新导出: ' + relative)
for path in (snapshot / 'src').rglob('*'):
    if path.is_file() and path.relative_to(snapshot).as_posix() not in expected:
        raise SystemExit('固定版本缓存中存在额外源码: ' + str(path.relative_to(snapshot)))
PY

# 版本对应的浏览器回归复用本机依赖，不复制凭据或运行数据。
if [[ -d "$ROOT/scripts/modal-e2e/node_modules" && ! -e "$snapshot/scripts/modal-e2e/node_modules" ]]; then
  ln -s "$ROOT/scripts/modal-e2e/node_modules" "$snapshot/scripts/modal-e2e/node_modules"
fi
printf '%s\n' "$snapshot"
