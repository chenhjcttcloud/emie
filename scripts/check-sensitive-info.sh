#!/usr/bin/env bash
set -euo pipefail

echo "[1/4] 检查已跟踪的敏感文件名"
files="$(git ls-files | rg -i '(^|/)(\.env($|\.)|.*\.(pem|key|p12|pfx|jks|keystore)$|id_rsa|credentials?\.|secrets?\.)' | rg -v '^\.env\.example$' || true)"
if [[ -n "$files" ]]; then
  echo "$files"
  exit 1
fi

echo "[2/4] 检查高置信度密钥格式"
if git grep -IqEi -- '-----BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9_]{20,}|xox[baprs]-[A-Za-z0-9-]{20,}|sk-[A-Za-z0-9]{20,}' -- ':!target/**' ':!logs/**' ':!uploads/**' ':!backups/**' ':!data/**'; then
  echo "发现疑似密钥文件，请人工复核。"
  exit 1
fi

echo "[3/4] 检查具体服务器、邮箱和账号标识"
identity_matches="$(git grep -InE '192\.168\.|10\.[0-9]+\.[0-9]+\.[0-9]+|172\.(1[6-9]|2[0-9]|3[0-1])\.|https?://[^[:space:]`"'"'"']+@|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}' -- ':!target/**' ':!logs/**' ':!uploads/**' ':!backups/**' ':!data/**' ':!src/test/**' | rg -v 'user@example\.com' || true)"
if [[ -n "$identity_matches" ]]; then
  echo "$identity_matches"
  echo "发现疑似服务器地址、邮箱或账号标识，请人工复核。"
  exit 1
fi

echo "[4/4] 检查大文件"
if git ls-files -z | xargs -0 -n1 sh -c 'test "$(wc -c < "$0")" -le 1048576' 2>/dev/null; then
  echo "sensitive_scan=ok"
else
  echo "发现超过 1 MiB 的已跟踪文件，请人工复核。"
  exit 1
fi
