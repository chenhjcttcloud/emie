#!/bin/bash
# 统一 Java 21 Maven 入口；避免终端默认 JDK 回退到其他版本。
set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck disable=SC1091
source scripts/java21-env.sh
exec ./mvnw "$@"
