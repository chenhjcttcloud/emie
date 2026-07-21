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
SCREEN_SESSION="emie-dev"

# 切换到项目根目录（兼容 scripts/ 内或根目录调用）
cd "$(dirname "$0")/.."

# 无论启动脚本由哪个终端、IDE 或自动化工具调用，都固定使用 Java 21。
# shellcheck disable=SC1091
source scripts/java21-env.sh

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
    scripts/mvnw-java21.sh package -DskipTests -q || { echo "打包失败"; exit 1; }
  fi

  rm -f "$PID_FILE"
  if command -v screen >/dev/null 2>&1; then
    # screen 独立于 Codex/终端会话，避免当前会话取消时连带终止本地服务。
    screen -S "$SCREEN_SESSION" -X quit >/dev/null 2>&1 || true
    screen -dmS "$SCREEN_SESSION" /bin/bash -lc \
      "exec '$JAVA_HOME/bin/java' -Duser.timezone=Asia/Shanghai -jar '$JAR_PATH' --spring.profiles.active='$PROFILE' --server.port='$PORT' > '$LOG_FILE' 2>&1"
    echo "服务启动中（独立 screen 会话：${SCREEN_SESSION}）..."
  else
    nohup "$JAVA_HOME/bin/java" -Duser.timezone=Asia/Shanghai -jar "$JAR_PATH" \
      --spring.profiles.active=$PROFILE \
      --server.port=$PORT \
      > "$LOG_FILE" 2>&1 &
    echo "服务启动中..."
  fi

  # 等待服务启动
  for i in $(seq 1 20); do
    sleep 1
    if curl -s "http://localhost:$PORT/api/admin/public-config" > /dev/null 2>&1; then
      PID=$(pgrep -f "java -jar $JAR_PATH" | head -n 1 || true)
      if [ -n "$PID" ]; then echo "$PID" > "$PID_FILE"; fi
      echo "服务已就绪 -> http://localhost:$PORT"
      return 0
    fi
  done

  echo "启动超时，查看日志: tail -f $LOG_FILE"
}

stop() {
  if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    if kill -0 "$PID" 2>/dev/null; then
      kill "$PID" 2>/dev/null || true
      for i in $(seq 1 10); do
        kill -0 "$PID" 2>/dev/null || break
        sleep 1
      done
      kill -9 "$PID" 2>/dev/null || true
      rm -f "$PID_FILE"
      screen -S "$SCREEN_SESSION" -X quit >/dev/null 2>&1 || true
      echo "已停止进程 $PID"
      return
    fi
    # PID 文件可能在异常退出后遗留，不能因此漏掉仍占用端口的旧服务。
    rm -f "$PID_FILE"
  fi
  PID=$(ps aux | grep "java -jar" | grep "$APP_NAME" | awk '{print $2}')
  if [ -n "$PID" ]; then
    kill "$PID" 2>/dev/null || true
    sleep 1
    kill -9 "$PID" 2>/dev/null || true
    echo "已停止遗留进程 $PID"
  else
    echo "未找到运行中的服务"
  fi
  screen -S "$SCREEN_SESSION" -X quit >/dev/null 2>&1 || true
}

restart() {
  stop
  sleep 1
  # 兼容旧版脚本未记录 PID 的情况，确保端口释放后再启动新 JAR。
  if command -v lsof >/dev/null 2>&1; then
    LISTENING_PIDS=$(lsof -tiTCP:$PORT -sTCP:LISTEN 2>/dev/null || true)
    if [ -n "$LISTENING_PIDS" ]; then
      echo "清理占用端口 $PORT 的旧进程..."
      kill $LISTENING_PIDS 2>/dev/null || true
      sleep 1
      kill -9 $LISTENING_PIDS 2>/dev/null || true
    fi
  fi
  # 重新打包确保代码最新
  echo "重新打包..."
  scripts/mvnw-java21.sh package -DskipTests -q || { echo "打包失败"; exit 1; }
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
