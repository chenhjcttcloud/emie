# Java certificate security update

CVE-2026-91403 mitigation: merge the OpenJDK certificate blocklist into the bundled Java runtime; preserve existing blocked certificates. This is a certificate-data mitigation, not a claim that all JDK security updates are installed.

Source: https://github.com/openjdk/jdk21u/commit/85974eed594aa5a9e13a6a20713156ed8d9cd0ed (8395142: CA Enhancements).

Generated from `src/java.base/share/data/blockedcertsconverter/blocked.certs.pem` using that commit’s official `make/jdk/src/classes/build/tools/blockedcertsconverter/BlockedCertsConverter.java`:

```sh
java --add-exports java.base/sun.security.util=ALL-UNNAMED BlockedCertsConverter.java < blocked.certs.pem > blocked.certs
```

Use the official converter: it includes alternate certificate encodings as well as the original DER fingerprints. Expected output: Algorithm=SHA-256 and 86 fingerprints.

Official guidance: https://openjdk.org/groups/vulnerability/advisories/2026-10-21

Production currently reuses a separate runtime image. Changing these Dockerfiles alone does not update that image; the production runtime must also incorporate this blocklist before restarting the application.

## 2026-10-26 follow-up

Runtime images install checksum-pinned official Temurin 21.0.13+9 archives (the versioned Docker tag is not yet available) to include October cumulative security fixes. `apply-security.sh` also removes the four root subjects specified by https://ops.java/emudhra (CVE-2026-114820). Alias discovery uses exact certificate subjects because Temurin aliases differ from Oracle aliases. The script fails if a targeted root remains, and keeps the prior intermediate-certificate blocklist. Validate with Oracle's https://cr.openjdk.org/~mr/CheckCerts.java and an ordinary Feishu TLS connection.
