#!/bin/sh
# Maven Wrapper - downloads and runs Maven
# ---------------------------------------------------------------------------

# Attempt to set MAVEN_HOME
if [ -z "$MAVEN_HOME" ]; then
  # Search for mvn in PATH
  MAVEN_HOME=$(dirname $(which mvn 2>/dev/null) 2>/dev/null)
  if [ -z "$MAVEN_HOME" ]; then
    MAVEN_HOME=""
  fi
fi

# If mvn is found, use it
if [ -n "$MAVEN_HOME" ]; then
  exec mvn "$@"
fi

# Otherwise try to download maven
MVN_VERSION="3.9.6"
MVN_URL="https://dlcdn.apache.org/maven/maven-3/${MVN_VERSION}/binaries/apache-maven-${MVN_VERSION}-bin.tar.gz"
MVN_DIR="$HOME/.m2/wrapper/dists/apache-maven-${MVN_VERSION}"

if [ ! -f "${MVN_DIR}/bin/mvn" ]; then
  echo "Downloading Maven ${MVN_VERSION}..."
  mkdir -p "${MVN_DIR}"
  # Try multiple mirrors
  for url in \
    "https://mirrors.aliyun.com/apache/maven/maven-3/${MVN_VERSION}/binaries/apache-maven-${MVN_VERSION}-bin.tar.gz" \
    "https://dlcdn.apache.org/maven/maven-3/${MVN_VERSION}/binaries/apache-maven-${MVN_VERSION}-bin.tar.gz" \
    "https://archive.apache.org/dist/maven/maven-3/${MVN_VERSION}/binaries/apache-maven-${MVN_VERSION}-bin.tar.gz"; do
    if curl -fsSL "$url" -o /tmp/maven-wrapper.tar.gz 2>/dev/null; then
      if [ "$(wc -c < /tmp/maven-wrapper.tar.gz 2>/dev/null)" -gt 1000000 ]; then
        tar xzf /tmp/maven-wrapper.tar.gz -C "${MVN_DIR}" --strip-components=1 2>/dev/null
        break
      fi
    fi
  done
  rm -f /tmp/maven-wrapper.tar.gz
fi

if [ -f "${MVN_DIR}/bin/mvn" ]; then
  exec "${MVN_DIR}/bin/mvn" "$@"
else
  echo "Error: Maven could not be found or downloaded."
  echo "Please install Maven manually: https://maven.apache.org/download.cgi"
  echo "Then add it to your PATH."
  exit 1
fi
