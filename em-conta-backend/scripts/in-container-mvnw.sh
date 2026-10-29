#!/usr/bin/env sh
# Executado DENTRO do container por mvnw-docker.sh / mvnw-docker.ps1. Não use diretamente.
set -eu

if [ -f /tmp/extra-ca.pem ]; then
  csplit -s -z -f /tmp/ca- /tmp/extra-ca.pem "/-----BEGIN CERTIFICATE-----/" "{*}"
  for f in /tmp/ca-*; do
    keytool -importcert -noprompt -cacerts -storepass changeit -alias "extra-$(basename "$f")" -file "$f" >/dev/null 2>&1 || true
  done
fi

if [ -n "${MAVEN_MIRROR_URL:-}" ]; then
  printf '<settings><mirrors><mirror><id>central-mirror</id><mirrorOf>central</mirrorOf><url>%s</url></mirror></mirrors></settings>' \
    "$MAVEN_MIRROR_URL" > /tmp/settings.xml
  export MAVEN_ARGS="-s /tmp/settings.xml ${MAVEN_ARGS:-}"
  export MVNW_REPOURL="$MAVEN_MIRROR_URL"
fi

exec sh ./mvnw "$@"
