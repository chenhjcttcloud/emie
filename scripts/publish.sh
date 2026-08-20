#!/usr/bin/env bash
# 标准发布编排：将本地业务提交映射推送到 Gitee master 和 GitHub main，再按需调用生产原子发布脚本。
set -Eeuo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd -P)"
branch="$(git branch --show-current)"
[[ "$branch" == "master" ]] || { echo '必须在 master 分支发布。' >&2; exit 1; }
[[ -z "$(git status --porcelain)" ]] || { echo '工作区有未提交改动，请先提交后再发布。' >&2; exit 1; }

target="${1:---repository}"
case "$target" in
  --repository)
    git push emie "$branch:master"
    git push github "$branch:main"
    printf 'repository_pushed=%s gitee=master github=main\n' "$(git rev-parse HEAD)"
    ;;
  --production)
    "$ROOT/scripts/release-production.sh"
    ;;
  --all)
    git push emie "$branch:master"
    git push github "$branch:main"
    "$ROOT/scripts/release-production.sh"
    ;;
  *) echo '用法：./scripts/publish.sh [--repository|--production|--all]' >&2; exit 2 ;;
esac
