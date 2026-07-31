#!/usr/bin/env bash
# 本地一键生产发布入口：检查精确提交、构建、增量上传并调用服务器原子切换。
set -Eeuo pipefail

cd "$(dirname "$0")/.."
PROJECT_ROOT="$(pwd -P)"
ENV_FILE="${PRODUCTION_ENV_FILE:-$PROJECT_ROOT/.server.production.local.env}"
REMOTE_HELPER_LOCAL="$PROJECT_ROOT/scripts/release-production-remote.sh"
JAR_PATH="$PROJECT_ROOT/target/design-pm-1.0.0.jar"
PREFLIGHT_ONLY="false"

usage() {
  cat <<'EOF'
用法：
  ./scripts/release-production.sh
  ./scripts/release-production.sh --preflight-only
  PRODUCTION_ENV_FILE=/path/to/env ./scripts/release-production.sh

默认读取未纳入 Git 的 .server.production.local.env，至少包含：
  SERVER_ROLE=production-local
  SERVER_HOST=<生产服务器>
  SERVER_USER=<SSH用户>
  SERVER_PORT=22
  SERVER_PASSWORD=<SSH密码>
  SERVER_SUDO_PASSWORD=<sudo密码，可省略并复用SSH密码>
  SERVER_PUBLIC_URL=https://example.com
EOF
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
elif [[ "${1:-}" == "--preflight-only" ]]; then
  PREFLIGHT_ONLY="true"
elif [[ -n "${1:-}" ]]; then
  usage >&2
  exit 2
fi

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
elif [[ -z "${SERVER_ROLE:-}" || -z "${SERVER_HOST:-}" ||
        -z "${SERVER_USER:-}" || -z "${SERVER_PORT:-}" ||
        -z "${SERVER_PASSWORD:-}" || -z "${SERVER_PUBLIC_URL:-}" ]]; then
  printf "缺少生产服务器配置：%s\n" "$ENV_FILE" >&2
  usage >&2
  exit 1
fi

: "${SERVER_ROLE:?缺少 SERVER_ROLE}"
: "${SERVER_HOST:?缺少 SERVER_HOST}"
: "${SERVER_USER:?缺少 SERVER_USER}"
: "${SERVER_PORT:?缺少 SERVER_PORT}"
: "${SERVER_PASSWORD:?缺少 SERVER_PASSWORD}"
: "${SERVER_PUBLIC_URL:?缺少 SERVER_PUBLIC_URL}"
SERVER_INSECURE_TLS="${SERVER_INSECURE_TLS:-false}"
[[ "$SERVER_ROLE" == "production-local" ]] ||
  { echo "SERVER_ROLE 必须明确为 production-local，已停止以避免连错服务器。" >&2; exit 1; }
[[ "$SERVER_HOST" =~ ^[A-Za-z0-9.-]+$ ]] ||
  { echo "SERVER_HOST 格式不安全。" >&2; exit 1; }
[[ "$SERVER_USER" =~ ^[A-Za-z_][A-Za-z0-9_-]*$ ]] ||
  { echo "SERVER_USER 格式不安全。" >&2; exit 1; }
[[ "$SERVER_PORT" =~ ^[0-9]{1,5}$ ]] ||
  { echo "SERVER_PORT 格式不正确。" >&2; exit 1; }
[[ "$SERVER_PUBLIC_URL" =~ ^https://[^[:space:]]+$ ]] ||
  { echo "SERVER_PUBLIC_URL 必须是 HTTPS 地址。" >&2; exit 1; }
[[ "$SERVER_INSECURE_TLS" == "true" || "$SERVER_INSECURE_TLS" == "false" ]] ||
  { echo "SERVER_INSECURE_TLS 必须是 true 或 false。" >&2; exit 1; }

public_curl_args=(--fail --silent --show-error)
[[ "$SERVER_INSECURE_TLS" == "true" ]] && public_curl_args+=(--insecure)

SERVER_SUDO_PASSWORD="${SERVER_SUDO_PASSWORD:-$SERVER_PASSWORD}"
DEPLOY_DIR="${SERVER_DEPLOY_DIR:-/home/emie/emie-deploy}"
[[ "$DEPLOY_DIR" =~ ^/[A-Za-z0-9._/-]+$ ]] ||
  { echo "SERVER_DEPLOY_DIR 格式不安全。" >&2; exit 1; }
REMOTE_HELPER="$DEPLOY_DIR/.release-tools/release-production-remote.sh"
REMOTE_INCOMING_DIR="$DEPLOY_DIR/incoming"

for command_name in git ssh sshpass rsync curl sha256sum; do
  command -v "$command_name" >/dev/null 2>&1 ||
    { echo "本机缺少命令：$command_name" >&2; exit 1; }
done

ssh_args=(
  -p "$SERVER_PORT"
  -o StrictHostKeyChecking=accept-new
  -o ConnectTimeout=8
  -o ServerAliveInterval=10
  -o ServerAliveCountMax=3
)
export SSHPASS="$SERVER_PASSWORD"
trap 'unset SSHPASS SERVER_PASSWORD SERVER_SUDO_PASSWORD' EXIT

run_ssh() {
  sshpass -e ssh "${ssh_args[@]}" "$SERVER_USER@$SERVER_HOST" "$@"
}

printf "阶段 1/5：核对生产目标与当前服务...\n"
remote_current_sha="$(
  run_ssh \
    "test -f '$DEPLOY_DIR/release-sha.txt'; curl -fsS http://127.0.0.1:8080/api/admin/public-config >/dev/null; tr -d '[:space:]' < '$DEPLOY_DIR/release-sha.txt'"
)"
printf "remote_release=%s\n" "$remote_current_sha"
printf "%s\n" "$SERVER_SUDO_PASSWORD" |
  sshpass -e ssh "${ssh_args[@]}" "$SERVER_USER@$SERVER_HOST" \
    "sudo -S -p '' docker inspect emie-app --format 'sudo_container={{.State.Status}}' >/dev/null"
printf "public_http="
curl "${public_curl_args[@]}" -o /dev/null -w "%{http_code}\n" \
  "${SERVER_PUBLIC_URL%/}/"

if [[ "$PREFLIGHT_ONLY" == "true" ]]; then
  printf "预检通过，未构建、上传或修改生产。\n"
  exit 0
fi

printf "阶段 2/5：核对业务分支和精确提交...\n"
[[ "$(git branch --show-current)" == "project_manager_system" ]] ||
  { echo "当前不是 project_manager_system 分支。" >&2; exit 1; }
[[ -z "$(git status --porcelain)" ]] ||
  { echo "工作区存在未提交改动，禁止生产发布。" >&2; exit 1; }
git fetch emie
target_sha="$(git rev-parse HEAD)"
remote_sha="$(git ls-remote emie refs/heads/project_manager_system | awk '{print $1}')"
[[ "$target_sha" == "$remote_sha" ]] ||
  { echo "本地提交与远端业务分支不一致，禁止生产发布。" >&2; exit 1; }
if [[ "$target_sha" == "$remote_current_sha" ]]; then
  printf "生产已经运行目标提交，无需重复构建、上传或重启。\n"
  exit 0
fi

printf "阶段 3/5：Java 21 完整构建与测试...\n"
scripts/mvnw-java21.sh clean package
[[ -f "$JAR_PATH" ]] || { echo "构建产物不存在：$JAR_PATH" >&2; exit 1; }
jar_sha="$(sha256sum "$JAR_PATH" | sed "s/ .*//")"
target_short="${target_sha:0:7}"
remote_incoming="$REMOTE_INCOMING_DIR/app-$target_sha.jar"

printf "阶段 4/5：增量上传发布产物...\n"
run_ssh "mkdir -p '$REMOTE_INCOMING_DIR' '$DEPLOY_DIR/.release-tools'"
rsync_rsh="sshpass -e ssh -p $SERVER_PORT -o StrictHostKeyChecking=accept-new -o ConnectTimeout=8"
SSHPASS="$SERVER_PASSWORD" rsync -a --partial \
  -e "$rsync_rsh" "$REMOTE_HELPER_LOCAL" \
  "$SERVER_USER@$SERVER_HOST:$REMOTE_HELPER"

# 若服务器已有当前版本化 JAR，先在服务器本地复制作为 rsync 基础，减少后续版本传输量。
run_ssh "
  current_sha=\$(tr -d '[:space:]' < '$DEPLOY_DIR/release-sha.txt' 2>/dev/null || true)
  current_jar='$DEPLOY_DIR/releases/'\"\$current_sha\"'/app.jar'
  if test ! -f '$remote_incoming' && test -f \"\$current_jar\"; then
    cp --reflink=auto \"\$current_jar\" '$remote_incoming'
  fi
"
SSHPASS="$SERVER_PASSWORD" rsync -a --checksum --partial --inplace \
  -e "$rsync_rsh" "$JAR_PATH" \
  "$SERVER_USER@$SERVER_HOST:$remote_incoming"
uploaded_sha="$(run_ssh "sha256sum '$remote_incoming' | sed 's/ .*//'")"
[[ "$uploaded_sha" == "$jar_sha" ]] ||
  { echo "上传产物校验不一致。" >&2; exit 1; }

printf "阶段 5/5：备份数据库并原子切换候选容器...\n"
printf "%s\n" "$SERVER_SUDO_PASSWORD" |
  sshpass -e ssh "${ssh_args[@]}" "$SERVER_USER@$SERVER_HOST" \
    "sudo -S -p '' env DEPLOY_DIR='$DEPLOY_DIR' bash '$REMOTE_HELPER' '$target_sha' '$remote_incoming' '$jar_sha'"

printf "release_sha="
run_ssh "cat '$DEPLOY_DIR/release-sha.txt'"
printf "public_http="
curl "${public_curl_args[@]}" -o /dev/null -w "%{http_code}\n" \
  "${SERVER_PUBLIC_URL%/}/"
printf "生产发布完成：%s\n" "$target_short"
