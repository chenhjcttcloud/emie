#!/bin/bash
# EMIE 项目管理系统 - 开发启动脚本（根目录快捷入口）
# 实际逻辑在 scripts/dev.sh
# 使用方法: ./dev.sh [start|restart|stop|log]

cd "$(dirname "$0")"
exec scripts/dev.sh "$@"
