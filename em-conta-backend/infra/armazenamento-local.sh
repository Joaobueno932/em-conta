#!/usr/bin/env bash
# Prepara o armazenamento de anexos da API de trabalho no MinIO local (Compose):
#   - cria (ou atualiza) uma conta de serviço restrita ao bucket de trabalho ($MINIO_BUCKET do .env),
#     só com leitura, gravação e remoção de objetos desse bucket (minio/politica-api.json);
#   - confere que o bucket continua privado (sem acesso anônimo);
#   - grava ARMAZENAMENTO_* em infra/armazenamento.env (ignorado pelo Git) para o --env-file da API.
#
# Segredos não são exibidos: a senha da conta é gerada aqui e só vai para o MinIO e para o arquivo.
# Rodar de novo é seguro: reaproveita a credencial existente em armazenamento.env.
#
# Uso (a partir de em-conta-backend):  bash infra/armazenamento-local.sh
set -euo pipefail

cd "$(dirname "$0")"
CONTAINER="${MINIO_CONTAINER:-em-conta-minio-1}"
SAIDA="armazenamento.env"
USUARIO="emconta-api-local"

valor() { grep -E "^$1=" .env | head -1 | cut -d= -f2-; }
BUCKET="$(valor MINIO_BUCKET)"
[ -n "$BUCKET" ] || { echo "MINIO_BUCKET ausente em infra/.env" >&2; exit 1; }
case "$BUCKET" in *e2e*) echo "Este script é só para o bucket de trabalho, não o de testes." >&2; exit 1 ;; esac

if [ -f "$SAIDA" ] && grep -q '^ARMAZENAMENTO_CHAVE_SECRETA=.' "$SAIDA"; then
  SEGREDO="$(grep -E '^ARMAZENAMENTO_CHAVE_SECRETA=' "$SAIDA" | cut -d= -f2-)"
else
  # 36 bytes aleatórios em Base64, só alfanuméricos (sem cortar o pipe, que falharia com pipefail).
  SEGREDO="$(head -c 36 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')"
fi

export MSYS_NO_PATHCONV=1
# Política com o nome do bucket, enviada pela entrada padrão (nada passa pela linha de comando).
sed "s/BUCKET/$BUCKET/g" minio/politica-api.json \
  | docker exec -i "$CONTAINER" sh -c 'cat > /tmp/politica-api.json'
# Credenciais do administrador do MinIO ficam dentro do container (variáveis dele).
printf '%s\n' "$SEGREDO" | docker exec -i -e USUARIO="$USUARIO" -e BUCKET="$BUCKET" "$CONTAINER" bash -c '
  set -e
  read -r SEGREDO
  mc alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
  mc mb --ignore-existing "local/$BUCKET" >/dev/null
  mc admin policy create local emconta-api-local /tmp/politica-api.json >/dev/null
  mc admin user add local "$USUARIO" "$SEGREDO" >/dev/null
  mc admin policy attach local emconta-api-local --user "$USUARIO" >/dev/null 2>&1 || true
  rm -f /tmp/politica-api.json
  mc anonymous get "local/$BUCKET" # deve informar "private"
'

umask 077
{
  echo "# Gerado por infra/armazenamento-local.sh. Não versionar."
  echo "ARMAZENAMENTO_ENDPOINT=http://minio:9000"
  echo "ARMAZENAMENTO_BUCKET=$BUCKET"
  echo "ARMAZENAMENTO_CHAVE_ACESSO=$USUARIO"
  echo "ARMAZENAMENTO_CHAVE_SECRETA=$SEGREDO"
  echo "ARMAZENAMENTO_CRIAR_BUCKET=false"
} > "$SAIDA"
echo "credencial restrita ao bucket $BUCKET gravada em infra/$SAIDA"
