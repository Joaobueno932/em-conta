#!/usr/bin/env bash
# Prepara o armazenamento de anexos da API de trabalho no MinIO local (Compose):
#   - confere, antes de mudar qualquer coisa, que o bucket de trabalho ($MINIO_BUCKET do .env) está
#     privado; se tiver acesso anônimo, para com erro (não altera o acesso anônimo por conta própria);
#   - cria (ou atualiza) uma conta de serviço restrita a esse bucket (minio/politica-api.json) e
#     confirma que a política ficou associada;
#   - confirma, com a própria credencial, que ela acessa o bucket e não administra o MinIO nem acessa
#     o bucket de testes;
#   - só então grava ARMAZENAMENTO_* em infra/armazenamento.env (ignorado pelo Git), de forma atômica:
#     em qualquer falha o arquivo existente fica como estava.
#
# Segredos não são exibidos: a senha da conta é gerada aqui e só vai para o MinIO e para o arquivo.
# Rodar de novo é seguro: reaproveita a credencial existente em armazenamento.env.
#
# Uso (a partir de em-conta-backend):  bash infra/armazenamento-local.sh
# Variáveis opcionais (testes do próprio script): MINIO_CONTAINER, ENV_FILE, SAIDA.
set -euo pipefail

cd "$(dirname "$0")"
CONTAINER="${MINIO_CONTAINER:-em-conta-minio-1}"
ENV_FILE="${ENV_FILE:-.env}"
SAIDA="${SAIDA:-armazenamento.env}"
USUARIO="emconta-api-local"
POLITICA="emconta-api-local"
BUCKET_TESTES="emconta-e2e"

falhar() { echo "ERRO: $*" >&2; exit 1; }

valor() { grep -E "^$1=" "$ENV_FILE" | head -1 | cut -d= -f2-; }
[ -f "$ENV_FILE" ] || falhar "arquivo $ENV_FILE não encontrado"
BUCKET="$(valor MINIO_BUCKET)"
[ -n "$BUCKET" ] || falhar "MINIO_BUCKET ausente em $ENV_FILE"
[ "$BUCKET" != "$BUCKET_TESTES" ] || falhar "este script é só para o bucket de trabalho, não o de testes"

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

# Tudo dentro do container: as credenciais do administrador são as variáveis dele; a senha da conta
# chega pela entrada padrão. Qualquer verificação que falhe encerra com erro antes da gravação local.
RESULTADO="$(printf '%s\n' "$SEGREDO" | docker exec -i \
  -e USUARIO="$USUARIO" -e POLITICA="$POLITICA" -e BUCKET="$BUCKET" -e BUCKET_TESTES="$BUCKET_TESTES" \
  "$CONTAINER" bash -c '
  set -euo pipefail
  read -r SEGREDO
  falhar() { echo "ERRO: $*" >&2; rm -f /tmp/politica-api.json; exit 1; }
  mc alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
  mc mb --ignore-existing "local/$BUCKET" >/dev/null

  # 1. Bucket privado, antes de qualquer alteração.
  ANONIMO="$(mc anonymous get "local/$BUCKET")" || falhar "não foi possível consultar o acesso anônimo"
  [[ "$ANONIMO" == *"is \`private\`"* ]] \
    || falhar "o bucket $BUCKET tem acesso anônimo ($ANONIMO). Revise-o; o script não altera isso."

  # 2. Conta restrita e política associada (associar de novo não é erro; outra falha é).
  mc admin policy create local "$POLITICA" /tmp/politica-api.json >/dev/null \
    || falhar "não foi possível criar a política $POLITICA"
  rm -f /tmp/politica-api.json
  mc admin user add local "$USUARIO" "$SEGREDO" >/dev/null || falhar "não foi possível criar a conta $USUARIO"
  SAIDA_ATTACH="$(mc admin policy attach local "$POLITICA" --user "$USUARIO" 2>&1)" \
    || [[ "$SAIDA_ATTACH" == *"already"* ]] \
    || falhar "não foi possível associar a política: $SAIDA_ATTACH"
  INFO="$(mc admin user info local "$USUARIO")" || falhar "não foi possível consultar a conta $USUARIO"
  [[ "$INFO" == *"PolicyName: $POLITICA"* ]] || falhar "a política $POLITICA não está associada à conta"

  # 3. A própria credencial: acessa o bucket; não administra; não acessa o bucket de testes.
  mc alias set restrita http://127.0.0.1:9000 "$USUARIO" "$SEGREDO" >/dev/null
  mc ls "restrita/$BUCKET" >/dev/null 2>&1 || falhar "a credencial não acessa o bucket $BUCKET"
  ! mc admin info restrita >/dev/null 2>&1 || falhar "a credencial consegue administrar o MinIO"
  ! mc ls "restrita/$BUCKET_TESTES" >/dev/null 2>&1 || falhar "a credencial acessa o bucket de testes"
  mc alias rm restrita >/dev/null
  echo "verificado"
')"
[ "$RESULTADO" = "verificado" ] || falhar "verificação incompleta no MinIO"

# Gravação atômica: o arquivo antigo só é trocado por um arquivo completo.
umask 077
TEMPORARIO="$(mktemp "$SAIDA.XXXXXX")"
trap 'rm -f "$TEMPORARIO"' EXIT
{
  echo "# Gerado por infra/armazenamento-local.sh. Não versionar."
  echo "ARMAZENAMENTO_ENDPOINT=http://minio:9000"
  echo "ARMAZENAMENTO_BUCKET=$BUCKET"
  echo "ARMAZENAMENTO_CHAVE_ACESSO=$USUARIO"
  echo "ARMAZENAMENTO_CHAVE_SECRETA=$SEGREDO"
  echo "ARMAZENAMENTO_CRIAR_BUCKET=false"
} > "$TEMPORARIO"
mv -f "$TEMPORARIO" "$SAIDA"
trap - EXIT
echo "bucket $BUCKET privado; conta $USUARIO restrita a ele (sem administração nem bucket de testes)."
echo "credencial gravada em $SAIDA"
