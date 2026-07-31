#!/usr/bin/env bash
# 标准发布编排：先将已提交代码推送仓库，再按需调用生产原子发布脚本。
set -Eeuo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd -P)"
branch="$(git branch --show-current)"
[[ "$branch" == "project_manager_system" ]] || { echo '必须在 project_manager_system 分支发布。' >&2; exit 1; }
[[ -z "$(git status --porcelain)" ]] || { echo '工作区有未提交改动，请先提交后再发布。' >&2; exit 1; }

target="${1:---repository}"
case "$target" in
  --repository)
    git push emie "$branch"
    printf 'repository_pushed=%s\n' "$(git rev-parse HEAD)"
    ;;
  --production)
    "$ROOT/scripts/release-production.sh"
    ;;
  --all)
    git push emie "$branch"
    "$ROOT/scripts/release-production.sh"
    ;;
  *) echo '用法：./scripts/publish.sh [--repository|--production|--all]' >&2; exit 2 ;;
esac
