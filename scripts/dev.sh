#!/bin/bash
# EMIE 项目管理系统 - 开发启动脚本
# 使用方法: ./scripts/dev.sh [start|restart|stop|log]
# 或者根目录的 ./dev.sh （推荐）
#
# 特性:
# - Spring Boot DevTools 已集成，Java 代码变更后只需重新编译即可自动重启
# - 前端静态文件（JS/CSS/HTML）修改后刷新浏览器即可，无需重启

APP_NAME="design-pm"
LOG_FILE="/tmp/emie-dev.log"
PID_FILE="/tmp/emie-dev.pid"
PORT=8080
PROFILE="dev"

# 切换到项目根目录（兼容 scripts/ 内或根目录调用）
cd "$(dirname "$0")/.."

# 本地敏感配置只保存在已被 Git 忽略的 .env 中，启动时自动加载但不输出内容。
if [ -f ".env" ]; then
  set -a
  # shellcheck disable=SC1091
  source ".env"
  set +a
fi

JAR_PATH="target/$APP_NAME-1.0.0.jar"

start() {
  for var_name in DESIGNPM_TEST_DB_HOST DESIGNPM_TEST_DB_NAME DESIGNPM_TEST_DB_USER DESIGNPM_TEST_DB_PASSWORD; do
    if [ -z "${!var_name}" ]; then
      echo "缺少测试库配置：$var_name，请先配置本机 .env"
      exit 1
    fi
  done

  if [ -f "$PID_FILE" ] && kill -0 $(cat "$PID_FILE") 2>/dev/null; then
    echo "服务已在运行 PID=$(cat $PID_FILE)"
    exit 1
  fi

  # 如果 jar 不存在则打包
  if [ ! -f "$JAR_PATH" ]; then
    echo "首次启动，正在打包..."
    ./mvnw package -DskipTests -q || { echo "打包失败"; exit 1; }
  fi

  nohup java -jar "$JAR_PATH" \
    --spring.profiles.active=$PROFILE \
    --server.port=$PORT \
    > "$LOG_FILE" 2>&1 &

  PID=$!
  echo $PID > "$PID_FILE"
  echo "服务启动中 (PID=$PID)..."

  # 等待服务启动
  for i in $(seq 1 20); do
    sleep 1
    if curl -s "http://localhost:$PORT/api/admin/public-config" > /dev/null 2>&1; then
      echo "服务已就绪 -> http://localhost:$PORT"
      return 0
    fi
  done

  echo "启动超时，查看日志: tail -f $LOG_FILE"
}

stop() {
  if [ ! -f "$PID_FILE" ]; then
    PID=$(ps aux | grep "java -jar" | grep "$APP_NAME" | awk '{print $2}')
    if [ -n "$PID" ]; then
      kill -9 $PID 2>/dev/null
      echo "已强制停止进程 $PID"
    else
      echo "未找到运行中的服务"
    fi
    return
  fi

  PID=$(cat "$PID_FILE")
  kill -9 $PID 2>/dev/null
  rm -f "$PID_FILE"
  echo "已停止服务 (PID=$PID)"
}

restart() {
  stop
  sleep 1
  # 重新打包确保代码最新
  echo "重新打包..."
  ./mvnw package -DskipTests -q || { echo "打包失败"; exit 1; }
  start
}

log() {
  tail -f "$LOG_FILE"
}

case "${1:-start}" in
  start)   start ;;
  stop)    stop ;;
  restart) restart ;;
  log)     log ;;
  *)
    echo "用法: $0 [start|stop|restart|log]"
    echo "  start   - 启动服务（默认）"
    echo "  stop    - 停止服务"
    echo "  restart - 重新打包并重启"
    echo "  log     - 查看运行日志"
    ;;
esac
