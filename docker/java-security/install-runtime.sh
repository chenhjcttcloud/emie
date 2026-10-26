#!/bin/sh
# Official Temurin 21.0.13+9 packages; checksums from the Adoptium release API.
set -eu
case "$(uname -m)" in
  aarch64|arm64) arch=aarch64; checksum=b1734db7ade49c3f6acc6ae06233b8c2dd90e3e2f00f67000ae1c972aaa64d8c ;;
  x86_64) arch=x64; checksum=a535a1df9c6284d9618872984e026ae0352d1f7e9e0605425c0e6114f47351dd ;;
  *) echo 'Unsupported Java runtime architecture' >&2; exit 1 ;;
esac
curl --fail --location --retry 3 --connect-timeout 20 --max-time 300 "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jre_${arch}_linux_hotspot_21.0.13_9.tar.gz" -o /tmp/emie-java-runtime.tar.gz
printf '%s  %s\n' "$checksum" /tmp/emie-java-runtime.tar.gz | sha256sum -c -
mkdir /tmp/emie-new-java
tar -xzf /tmp/emie-java-runtime.tar.gz -C /tmp/emie-new-java --strip-components=1
/tmp/emie-new-java/bin/java -version 2>&1 | grep -F '21.0.13+9'
# Replace the complete installation, leaving no outdated modules behind.
find "$JAVA_HOME" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
cp -a /tmp/emie-new-java/. "$JAVA_HOME/"
rm -rf /tmp/emie-new-java /tmp/emie-java-runtime.tar.gz
