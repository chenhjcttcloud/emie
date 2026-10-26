FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn package -DskipTests -q

FROM eclipse-temurin:21-jre
# Versioned Docker tag is not yet available; install a checksum-pinned official JRE.
COPY docker/java-security/install-runtime.sh /tmp/emie-install-runtime.sh
RUN sh /tmp/emie-install-runtime.sh && rm /tmp/emie-install-runtime.sh
# Apply both eMudhra advisories, including root distrust (CVE-2026-114820).
COPY docker/java-security/blocked.certs /tmp/emie-blocked.certs
COPY docker/java-security/apply-security.sh /tmp/emie-apply-security.sh
RUN sh /tmp/emie-apply-security.sh && rm /tmp/emie-apply-security.sh
WORKDIR /app
COPY --from=builder /build/target/design-pm-1.0.0.jar app.jar

RUN mkdir -p /app/uploads /app/logs/archive

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar", "--spring.profiles.active=prod"]
