#!/usr/bin/env bash
# 在生产服务器执行的原子发布脚本。由 release-production.sh 上传并通过 sudo 调用。
set -Eeuo pipefail

APP_CONTAINER="${APP_CONTAINER:-emie-app}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-1Panel-mysql-ZRpM}"
DEPLOY_DIR="${DEPLOY_DIR:-/home/emie/emie-deploy}"
BACKUP_ROOT="${BACKUP_ROOT:-/home/emie/emie-deploy-backups}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8080/api/health/live}"
RUNTIME_IMAGE="${RUNTIME_IMAGE:-emie-app-runtime:java21}"
RELEASE_LOCK="$DEPLOY_DIR/.release.lock"

target_sha="${1:-}"
incoming_jar="${2:-}"
expected_jar_sha="${3:-}"
expected_version="${4:-}"

die() {
  printf "release_error=%s\n" "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "服务器缺少命令：$1"
}

get_env_value() {
  local key="$1" item
  for item in "${runtime_env[@]}"; do
    if [[ "$item" == "$key="* ]]; then
      printf "%s" "${item#*=}"
      return 0
    fi
  done
  return 1
}

[[ "${EUID:-$(id -u)}" -eq 0 ]] || die "必须通过 sudo 执行"
[[ "$target_sha" =~ ^[0-9a-f]{40}$ ]] || die "目标提交必须是 40 位 SHA"
[[ "$expected_jar_sha" =~ ^[0-9a-f]{64}$ ]] || die "JAR 校验值格式错误"
[[ "$expected_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "目标版本号格式错误"
[[ "$incoming_jar" == "$DEPLOY_DIR/incoming/app-$target_sha.jar" ]] ||
  die "JAR 必须使用受控 incoming 路径"

if ! mkdir "$RELEASE_LOCK" 2>/dev/null; then
  die "已有生产发布正在执行，拒绝并发切换"
fi
trap 'rmdir "$RELEASE_LOCK" 2>/dev/null || true' EXIT

for command_name in docker jq curl gzip sha256sum stat sed; do
  require_command "$command_name"
done

started_epoch="$(date +%s)"
target_short="${target_sha:0:7}"
stamp="$(date +%Y%m%d_%H%M%S)"
release_dir="$DEPLOY_DIR/releases/$target_sha"
release_jar="$release_dir/app.jar"
backup_dir="$BACKUP_ROOT/$stamp"
candidate_container="$APP_CONTAINER-candidate-$target_short-$stamp"
old_container="$APP_CONTAINER-old-$stamp"
failed_container="$APP_CONTAINER-failed-$stamp"
release_file="$DEPLOY_DIR/release-sha.txt"
old_sha="$(tr -d "[:space:]" < "$release_file" 2>/dev/null || true)"

docker inspect "$APP_CONTAINER" >/dev/null 2>&1 || die "当前应用容器不存在"
if [[ "${ALLOW_UNHEALTHY_CURRENT:-false}" != "true" ]] && ! curl --fail --silent --show-error "$HEALTH_URL" >/dev/null 2>&1; then
  # 兼容尚未包含 /api/health/live 的旧生产容器。
  curl --fail --silent --show-error http://127.0.0.1:8080/api/admin/public-config >/dev/null ||
    die "当前生产健康检查失败，禁止开始发布"
fi
[[ -f "$incoming_jar" ]] || die "待发布 JAR 不存在"
actual_jar_sha="$(sha256sum "$incoming_jar" | sed "s/ .*//")"
[[ "$actual_jar_sha" == "$expected_jar_sha" ]] || die "上传 JAR 校验不一致"

current_image="$(docker inspect "$APP_CONTAINER" --format "{{.Config.Image}}")"
network_mode="$(docker inspect "$APP_CONTAINER" --format "{{.HostConfig.NetworkMode}}")"
restart_policy="$(docker inspect "$APP_CONTAINER" --format "{{.HostConfig.RestartPolicy.Name}}")"
[[ -n "$network_mode" ]] || die "无法读取当前容器网络模式"
[[ -n "$restart_policy" ]] || die "无法读取当前容器重启策略"

mapfile -t runtime_env < <(
  docker inspect "$APP_CONTAINER" --format "{{range .Config.Env}}{{println .}}{{end}}" |
    sed "/^$/d"
)
[[ "${#runtime_env[@]}" -ge 12 ]] || die "运行环境变量数量异常"
set_env_value() {
  local key="$1" value="$2" env_index
  for env_index in "${!runtime_env[@]}"; do
    if [[ "${runtime_env[$env_index]}" == "$key="* ]]; then
      runtime_env[$env_index]="$key=$value"
      return
    fi
  done
  runtime_env+=("$key=$value")
}
# 双连接池配置必须随候选容器一起传递，不能依赖旧容器是否已经配置过。
primary_jdbc_url="$(get_env_value SPRING_DATASOURCE_URL 2>/dev/null || true)"
db_host="$(get_env_value DESIGNPM_DB_HOST 2>/dev/null || true)"
db_name="$(get_env_value DESIGNPM_DB_NAME 2>/dev/null || true)"
if [[ -z "$primary_jdbc_url" && -n "$db_host" && -n "$db_name" ]]; then
  primary_jdbc_url="jdbc:mysql://$db_host:3306/$db_name?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false"
fi
set_env_value APP_DB_POOL_ISOLATION_ENABLED "${APP_DB_POOL_ISOLATION_ENABLED:-true}"
set_env_value APP_DB_BACKGROUND_POOL_MAX "${APP_DB_BACKGROUND_POOL_MAX:-20}"
set_env_value APP_DB_BACKGROUND_POOL_MIN "${APP_DB_BACKGROUND_POOL_MIN:-3}"
[[ -n "$primary_jdbc_url" ]] && set_env_value APP_DB_BACKGROUND_JDBC_URL "$primary_jdbc_url"
db_user="$(get_env_value DESIGNPM_DB_USER 2>/dev/null || true)"
db_password="$(get_env_value DESIGNPM_DB_PASSWORD 2>/dev/null || true)"
[[ -n "$db_user" ]] && set_env_value APP_DB_BACKGROUND_DB_USER "$db_user"
[[ -n "$db_password" ]] && set_env_value APP_DB_BACKGROUND_DB_PASSWORD "$db_password"
# 生产应用的元空间上限固定为 256MB，避免长期运行和动态代理/类加载增长后触发 OOM。
for env_index in "${!runtime_env[@]}"; do
  if [[ "${runtime_env[$env_index]}" == JAVA_TOOL_OPTIONS=* ]]; then
    java_options="${runtime_env[$env_index]#JAVA_TOOL_OPTIONS=}"
    java_options="$(printf '%s' "$java_options" | sed -E 's/(^| )-XX:MaxMetaspaceSize=[^ ]+//g' | sed -E 's/  +/ /g; s/^ //; s/ $//')"
    runtime_env[$env_index]="JAVA_TOOL_OPTIONS=${java_options} -XX:MaxMetaspaceSize=256m"
  fi
done
version_env_set="false"
for env_index in "${!runtime_env[@]}"; do
  if [[ "${runtime_env[$env_index]}" == APP_VERSION=* ]]; then
    runtime_env[$env_index]="APP_VERSION=$expected_version"
    version_env_set="true"
  fi
done
[[ "$version_env_set" == "true" ]] || runtime_env+=("APP_VERSION=$expected_version")
for required_env in \
  SPRING_PROFILES_ACTIVE DESIGNPM_DB_HOST DESIGNPM_DB_NAME DESIGNPM_DB_USER \
  DESIGNPM_DB_PASSWORD SPRING_DATA_REDIS_HOST SPRING_DATA_REDIS_PORT; do
  get_env_value "$required_env" >/dev/null || die "缺少生产配置：$required_env"
done
[[ "$(get_env_value SPRING_PROFILES_ACTIVE)" == "prod" ]] ||
  die "当前容器不是 prod profile"

mount_args=()
while IFS=$'\t' read -r mount_type source destination writable; do
  [[ -n "$mount_type" ]] || continue
  [[ "$destination" == "/app/app.jar" ]] && continue
  [[ "$mount_type" == "bind" ]] || die "暂不支持复制的挂载类型：$mount_type"
  mount_spec="type=$mount_type,src=$source,dst=$destination"
  [[ "$writable" == "false" ]] && mount_spec="$mount_spec,readonly"
  mount_args+=(--mount "$mount_spec")
done < <(
  docker inspect "$APP_CONTAINER" |
    jq -r '.[0].Mounts[] | [.Type, .Source, .Destination, (.RW|tostring)] | @tsv'
)
[[ "${#mount_args[@]}" -ge 2 ]] || die "持久化挂载数量异常"

mkdir -p "$release_dir" "$backup_dir"
if [[ -f "$release_jar" ]]; then
  existing_release_sha="$(sha256sum "$release_jar" | sed "s/ .*//")"
  [[ "$existing_release_sha" == "$expected_jar_sha" ]] ||
    die "目标版本目录已存在不同 JAR"
else
  /usr/bin/mv "$incoming_jar" "$release_jar"
  chmod 0644 "$release_jar"
fi

# 发布前备份数据库。版本化 JAR 已经是应用回滚点；仅首次迁移时额外导出旧容器内 JAR。
printf "%s\n" "$old_sha" > "$backup_dir/release-sha.txt"
current_jar_source="$(
  docker inspect "$APP_CONTAINER" |
    jq -r '.[0].Mounts[] | select(.Destination == "/app/app.jar") | .Source' |
    head -n 1
)"
if [[ -n "$current_jar_source" && -f "$current_jar_source" ]]; then
  printf "%s\n" "$current_jar_source" > "$backup_dir/rollback-jar-path.txt"
else
  docker cp "$APP_CONTAINER:/app/app.jar" "$backup_dir/app.jar" >/dev/null
fi
docker image tag "$current_image" "emie-app:backup-$stamp"

db_user="$(get_env_value DESIGNPM_DB_USER)"
db_password="$(get_env_value DESIGNPM_DB_PASSWORD)"
db_name="$(get_env_value DESIGNPM_DB_NAME)"
docker exec -e MYSQL_PWD="$db_password" "$MYSQL_CONTAINER" \
  mysqldump --single-transaction --quick --routines --triggers --no-tablespaces \
  -h127.0.0.1 -u"$db_user" "$db_name" |
  gzip -9 > "$backup_dir/designpm.sql.gz"
gzip -t "$backup_dir/designpm.sql.gz"
db_bytes="$(stat -c %s "$backup_dir/designpm.sql.gz")"
[[ "$db_bytes" -gt 1024 ]] || die "数据库备份文件异常"

# 运行时镜像保持稳定，业务版本由只读 JAR 挂载决定，避免每次复制 107MB 生成镜像。
if ! docker image inspect "$RUNTIME_IMAGE" >/dev/null 2>&1; then
  docker image tag "$current_image" "$RUNTIME_IMAGE"
fi

create_args=(
  --name "$candidate_container"
  --network "$network_mode"
  --restart "$restart_policy"
  "${mount_args[@]}"
  --mount "type=bind,src=$release_jar,dst=/app/app.jar,readonly"
)
for item in "${runtime_env[@]}"; do
  [[ -n "$item" ]] || die "运行环境变量中存在空项"
  create_args+=(--env "$item")
done

# 候选容器在旧容器仍在线时完成创建；参数或挂载错误不会造成生产中断。
cleanup_candidate() {
  local failure_status="$?"
  trap - ERR
  set +e
  if docker inspect "$candidate_container" >/dev/null 2>&1; then
    docker rename "$candidate_container" "$failed_container" >/dev/null 2>&1
  fi
  exit "$failure_status"
}
trap cleanup_candidate ERR
docker create "${create_args[@]}" "$RUNTIME_IMAGE" >/dev/null
candidate_jar_source="$(
  docker inspect "$candidate_container" |
    jq -r '.[0].Mounts[] | select(.Destination == "/app/app.jar") | .Source'
)"
if [[ "$candidate_jar_source" != "$release_jar" ]]; then
  printf "release_error=候选容器 JAR 挂载校验失败\n" >&2
  false
fi
trap - ERR

old_stopped="false"
old_renamed="false"
candidate_promoted="false"

rollback() {
  local failure_status="$?"
  trap - ERR
  set +e
  if [[ "$candidate_promoted" == "true" ]] &&
      docker inspect "$APP_CONTAINER" >/dev/null 2>&1; then
    docker logs --tail 120 "$APP_CONTAINER" >&2
    docker stop -t 10 "$APP_CONTAINER" >/dev/null 2>&1
    docker rename "$APP_CONTAINER" "$failed_container" >/dev/null 2>&1
  elif docker inspect "$candidate_container" >/dev/null 2>&1; then
    docker rename "$candidate_container" "$failed_container" >/dev/null 2>&1
  fi
  if [[ "$old_renamed" == "true" ]] &&
      docker inspect "$old_container" >/dev/null 2>&1; then
    docker rename "$old_container" "$APP_CONTAINER" >/dev/null 2>&1
    docker start "$APP_CONTAINER" >/dev/null 2>&1
  elif [[ "$old_stopped" == "true" ]] &&
      docker inspect "$APP_CONTAINER" >/dev/null 2>&1; then
    docker start "$APP_CONTAINER" >/dev/null 2>&1
  fi
  for _ in $(seq 1 45); do
    if curl --fail --silent "$HEALTH_URL" >/dev/null 2>&1; then
      printf "rollback=success\n" >&2
      exit "$failure_status"
    fi
    sleep 1
  done
  printf "rollback=failed\n" >&2
  exit "$failure_status"
}
trap rollback ERR

switch_started_epoch="$(date +%s)"
old_stopped="true"
docker stop -t 30 "$APP_CONTAINER" >/dev/null
old_renamed="true"
docker rename "$APP_CONTAINER" "$old_container"
candidate_promoted="true"
docker rename "$candidate_container" "$APP_CONTAINER"
docker start "$APP_CONTAINER" >/dev/null

healthy="false"
for _ in $(seq 1 45); do
  if curl --fail --silent "$HEALTH_URL" >/dev/null 2>&1; then
    healthy="true"
    break
  fi
  sleep 1
done
if [[ "$healthy" != "true" ]]; then
  printf "release_error=新容器启动超时\n" >&2
  false
fi

container_jar_sha="$(
  docker exec "$APP_CONTAINER" sha256sum /app/app.jar | sed "s/ .*//"
)"
if [[ "$container_jar_sha" != "$expected_jar_sha" ]]; then
  printf "release_error=生产容器 JAR 校验不一致\n" >&2
  false
fi
if [[ "$(docker inspect "$APP_CONTAINER" --format "{{.RestartCount}}")" != "0" ]]; then
  printf "release_error=新容器发生异常重启\n" >&2
  false
fi

release_tmp="$release_file.$target_short"
printf "%s\n" "$target_sha" > "$release_tmp"
/usr/bin/mv -f "$release_tmp" "$release_file"
trap - ERR

finished_epoch="$(date +%s)"
printf "release_status=success\n"
printf "old_sha=%s\n" "$old_sha"
printf "target_sha=%s\n" "$target_sha"
printf "jar_sha=%s\n" "$container_jar_sha"
printf "backup_dir=%s\n" "$backup_dir"
printf "old_container=%s\n" "$old_container"
printf "db_bytes=%s\n" "$db_bytes"
printf "switch_seconds=%s\n" "$((finished_epoch - switch_started_epoch))"
printf "total_remote_seconds=%s\n" "$((finished_epoch - started_epoch))"
