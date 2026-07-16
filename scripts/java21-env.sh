#!/bin/bash
# 为 EMIE 统一解析并导出 Java 21 运行环境。供启动和构建脚本 source 使用。

set -euo pipefail

if [[ -n "${JAVA21_HOME:-}" && -x "${JAVA21_HOME}/bin/java" ]]; then
  export JAVA_HOME="$JAVA21_HOME"
elif [[ "$(uname -s)" == "Darwin" && -x /usr/libexec/java_home ]]; then
  JAVA_21_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
  if [[ -n "$JAVA_21_HOME" ]]; then
    export JAVA_HOME="$JAVA_21_HOME"
  fi
fi

if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
  echo "未找到 Java 21：请设置 JAVA21_HOME，或在 macOS 安装 JDK 21。" >&2
  exit 1
fi

export PATH="$JAVA_HOME/bin:$PATH"
JAVA_VERSION=$("$JAVA_HOME/bin/java" -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')
if [[ "$JAVA_VERSION" != "21" ]]; then
  echo "EMIE 必须使用 Java 21，当前 JAVA_HOME 为 Java ${JAVA_VERSION:-未知}。" >&2
  exit 1
fi
