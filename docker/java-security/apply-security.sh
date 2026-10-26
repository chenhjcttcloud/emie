#!/bin/sh
# CVE-2026-91403 and CVE-2026-114820; https://ops.java/emudhra
set -eu
blocked="$JAVA_HOME/lib/security/blocked.certs"
test "$(head -n 1 "$blocked")" = 'Algorithm=SHA-256'
{ printf 'Algorithm=SHA-256\n'; { tail -n +2 "$blocked"; tail -n +2 /tmp/emie-blocked.certs; } | LC_ALL=C sort -u; } > /tmp/emie-merged.certs
cat /tmp/emie-merged.certs > "$blocked"
# Temurin and Oracle use different aliases: identify the exact root subjects.
keytool -J-Duser.language=en -list -v -cacerts -storepass changeit > /tmp/emie-cacerts.txt
awk '/^Alias name: / {alias=substr($0,13)} /^Owner: CN=(emSign Root CA - G1|emSign Root CA - G2|emSign ECC Root CA - G3|AAA Certificate Services),/ {print alias}' /tmp/emie-cacerts.txt > /tmp/emie-remove-aliases.txt
while IFS= read -r alias; do
    keytool -delete -cacerts -storepass changeit -alias "$alias"
done < /tmp/emie-remove-aliases.txt
keytool -J-Duser.language=en -list -v -cacerts -storepass changeit > /tmp/emie-cacerts-after.txt
if grep -Eq '^Owner: CN=(emSign Root CA - G1|emSign Root CA - G2|emSign ECC Root CA - G3|AAA Certificate Services),' /tmp/emie-cacerts-after.txt; then
    echo 'Unsafe root still present' >&2
    exit 1
fi
rm /tmp/emie-blocked.certs /tmp/emie-merged.certs /tmp/emie-cacerts.txt /tmp/emie-remove-aliases.txt /tmp/emie-cacerts-after.txt
