#!/usr/bin/env sh
# Executa o Maven Wrapper dentro de um container com JDK 25, para máquinas sem JDK local.
#
# Uso:   scripts/mvnw-docker.sh verify
# Opções (variáveis de ambiente):
#   EXTRA_CA_FILE     arquivo PEM com CAs adicionais (ex.: proxy corporativo que inspeciona TLS).
#                     É importado só no truststore do container; nada é alterado na máquina.
#   MAVEN_MIRROR_URL  espelho público do Maven Central a usar no container, por exemplo
#                     https://maven-central.storage-download.googleapis.com/maven2
#
# O socket do Docker é montado para que os testes com Testcontainers possam criar o MySQL.
set -eu

IMAGE="maven:3.9.16-eclipse-temurin-25"
PROJECT_DIR=$(cd "$(dirname "$0")/.." && pwd)

# Git Bash no Windows: caminho no formato aceito pelo Docker Desktop e sem conversão automática.
if command -v cygpath >/dev/null 2>&1; then
  PROJECT_DIR=$(cygpath -m "$PROJECT_DIR")
  export MSYS_NO_PATHCONV=1
fi

CA_MOUNT=""
if [ -n "${EXTRA_CA_FILE:-}" ]; then
  CA_PATH="$EXTRA_CA_FILE"
  command -v cygpath >/dev/null 2>&1 && CA_PATH=$(cygpath -m "$CA_PATH")
  CA_MOUNT="-v $CA_PATH:/tmp/extra-ca.pem:ro"
fi

# shellcheck disable=SC2086
exec docker run --rm \
  -v "$PROJECT_DIR:/workspace" \
  -v em-conta-m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  $CA_MOUNT \
  -e MAVEN_MIRROR_URL="${MAVEN_MIRROR_URL:-}" \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -w /workspace \
  "$IMAGE" \
  sh scripts/in-container-mvnw.sh "$@"
