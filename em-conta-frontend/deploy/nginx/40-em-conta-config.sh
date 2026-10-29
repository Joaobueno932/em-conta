#!/bin/sh
# Gera /config.json (configuração pública) a partir do ambiente do container.
# Somente valores públicos: nunca coloque credenciais aqui.
set -eu

ENVIRONMENT="${EM_CONTA_ENVIRONMENT:-producao}"
API_BASE_PATH="${EM_CONTA_API_BASE_PATH:-/api}"

case "$ENVIRONMENT" in
  *[!A-Za-z0-9_-]*|"") echo "EM_CONTA_ENVIRONMENT inválido: use letras, números, _ ou -" >&2; exit 1 ;;
esac
case "$API_BASE_PATH" in
  /*) ;;
  *) echo "EM_CONTA_API_BASE_PATH deve começar com / (mesma origem)" >&2; exit 1 ;;
esac

printf '{\n  "environment": "%s",\n  "apiBasePath": "%s"\n}\n' "$ENVIRONMENT" "$API_BASE_PATH" \
  > /usr/share/nginx/html/config.json
