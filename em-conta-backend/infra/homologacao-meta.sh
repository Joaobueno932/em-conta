#!/usr/bin/env bash
# Ambiente de homologação real do Atendimento com a WhatsApp Cloud API, só com a conta e o número de
# TESTE da Meta. Fica separado da API de trabalho (8080, banco emconta, WhatsApp desligado) e da API dos
# testes automatizados (8090, banco emconta_e2e, Graph controlada), que pode ser apagada pelos testes:
#   API      127.0.0.1:8091 (container emconta-api-homolog)
#   banco    emconta_homolog, no MySQL do Compose
#   bucket   emconta-homolog, no MinIO do Compose, com conta de serviço restrita a ele
#
# Estado local fora do repositório e de pastas sincronizadas (OneDrive etc.), em HOMOLOGACAO_DIR
# (padrão ~/.emconta/homologacao). O script recusa uma pasta dentro de um repositório Git ou do OneDrive.
#   whatsapp.env       token de acesso e App Secret (preenchidos à mão), Phone Number ID e verify token
#   armazenamento.env  credencial restrita ao bucket de homologação (gerada)
#   admin.txt          senha do administrador da base de homologação (gerada; troque-a se for exposta)
#   cacerts            truststore da JVM com a CA da inspeção TLS da rede, quando CA_EXTRA é informado
#
# Nenhum segredo é exibido nem passa pela linha de comando: `conferir` mostra só se cada valor está
# preenchido, o tamanho e o que a Graph API devolve sobre o número (nunca o token).
#
# Uso (a partir de em-conta-backend):
#   docker build -t em-conta-backend:homolog .           # imagem do código atual (ver Dockerfile)
#   WHATSAPP_PHONE_NUMBER_ID=<id> WHATSAPP_WABA_ID=<id> CA_EXTRA=<pem> bash infra/homologacao-meta.sh preparar
#   bash infra/homologacao-meta.sh conferir              # valores preenchidos e, com a WABA, a Graph API
#   bash infra/homologacao-meta.sh subir                 # sobe a API de homologação
#   bash infra/homologacao-meta.sh descer                # remove o container (banco, bucket e arquivos ficam)
#   bash infra/homologacao-meta.sh trocar-verify-token   # novo verify token (atualize também na Meta)
#   bash infra/homologacao-meta.sh tunel-abrir           # túnel HTTPS temporário só para o webhook
#   bash infra/homologacao-meta.sh tunel-fechar          # encerra o túnel e o proxy
#
# Túnel: Cloudflare Quick Tunnel (cloudflared, imagem oficial cloudflare/cloudflared indicada em
# developers.cloudflare.com, fixada por digest) → proxy nginx (infra/homologacao-webhook.conf), que só
# repassa /api/v1/integracoes/whatsapp/webhook à API de homologação. Nenhum dos dois publica porta na
# máquina; o cloudflared fica numa rede própria em que só o proxy existe. O endereço *.trycloudflare.com
# muda a cada abertura: recadastre o callback na Meta sempre que reabrir.
# Variáveis opcionais: HOMOLOGACAO_DIR, IMAGEM (padrão em-conta-backend:homolog), CA_EXTRA (PEM da CA
# corporativa, ver docs/ci.md), ADMIN_EMAIL (padrão admin-homolog@emconta.local).
set -euo pipefail

cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1

DIR="${HOMOLOGACAO_DIR:-$HOME/.emconta/homologacao}"
WHATSAPP="$DIR/whatsapp.env"
ARMAZENAMENTO="$DIR/armazenamento.env"
ADMIN="$DIR/admin.txt"
CACERTS="$DIR/cacerts"
CA_TUNEL="$DIR/ca-tunel.pem"
URL_TUNEL="$DIR/tunel-url.txt"
IMAGEM="${IMAGEM:-em-conta-backend:homolog}"
CONTAINER="emconta-api-homolog"
PORTA=8091
URL_FRONTEND="http://localhost:5175"
BANCO="emconta_homolog"
BUCKET="emconta-homolog"
USUARIO_MINIO="emconta-api-homolog"
MYSQL="em-conta-mysql-1"
MINIO="em-conta-minio-1"
REDE="em-conta_default"
PROXY="emconta-homolog-proxy"
TUNEL="emconta-homolog-tunel"
REDE_TUNEL="emconta-homolog-tunel"
IMAGEM_PROXY="${IMAGEM_PROXY:-nginx:1.30.6-alpine}"
IMAGEM_TUNEL="${IMAGEM_TUNEL:-cloudflare/cloudflared:2026.10.7@sha256:32f933aa6fe1f69c5885b93f3c92cc3f60cca8f0530868bc3bc2cc446114a385}"
OBRIGATORIAS="WHATSAPP_ACCESS_TOKEN WHATSAPP_APP_SECRET WHATSAPP_PHONE_NUMBER_ID WHATSAPP_VERIFY_TOKEN"

falhar() { echo "ERRO: $*" >&2; exit 1; }
aleatorio() { head -c "$1" /dev/urandom | base64 | tr -dc 'A-Za-z0-9'; }
valor() { grep -E "^$1=" "$2" | head -1 | cut -d= -f2- || true; }
caminho_docker() { if command -v cygpath >/dev/null; then cygpath -m "$1"; else echo "$1"; fi; }

# Os arquivos guardam segredos: nunca dentro de um repositório Git nem de uma pasta sincronizada.
conferir_pasta() {
  case "$(caminho_docker "$DIR")" in
    /*|[A-Za-z]:/*) ;;
    *) falhar "HOMOLOGACAO_DIR deve ser um caminho absoluto" ;;
  esac
  [[ "$DIR" != *OneDrive* ]] || falhar "HOMOLOGACAO_DIR não pode ficar no OneDrive: $DIR"
  local pai="$DIR"
  while [ ! -d "$pai" ]; do pai="$(dirname "$pai")"; done
  if git -C "$(caminho_docker "$pai")" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    falhar "HOMOLOGACAO_DIR não pode ficar dentro de um repositório Git: $DIR"
  fi
}

preparar_whatsapp() {
  if [ -n "${WHATSAPP_WABA_ID:-}" ] && [[ ! "$WHATSAPP_WABA_ID" =~ ^[0-9]+$ ]]; then
    falhar "WHATSAPP_WABA_ID deve ter só dígitos"
  fi
  if [ -f "$WHATSAPP" ]; then
    # Arquivo de antes da WABA: acrescenta só essa linha, sem tocar nos demais valores.
    if [ -n "${WHATSAPP_WABA_ID:-}" ] && ! grep -q '^WHATSAPP_WABA_ID=' "$WHATSAPP"; then
      printf '%s\n%s\n' "# WABA do número de teste. Eventos de outra WABA são descartados no webhook." \
        "WHATSAPP_WABA_ID=$WHATSAPP_WABA_ID" >> "$WHATSAPP"
      echo "$WHATSAPP: WHATSAPP_WABA_ID acrescentada; demais valores mantidos."
    else
      echo "$WHATSAPP já existe; mantido como está."
    fi
    return
  fi
  [ -n "${WHATSAPP_PHONE_NUMBER_ID:-}" ] || falhar "informe WHATSAPP_PHONE_NUMBER_ID (número de TESTE)"
  [[ "$WHATSAPP_PHONE_NUMBER_ID" =~ ^[0-9]+$ ]] || falhar "WHATSAPP_PHONE_NUMBER_ID deve ter só dígitos"
  [ -n "${WHATSAPP_WABA_ID:-}" ] || falhar "informe WHATSAPP_WABA_ID (WABA de TESTE)"
  local temporario
  temporario="$(mktemp "$WHATSAPP.XXXXXX")"
  {
    echo "# Homologação real com a WhatsApp Cloud API: conta e número de TESTE. Não versionar."
    echo "# Preencha as duas linhas vazias com o valor logo depois do '=', sem aspas e sem espaços."
    echo "# Lido pelo Docker (--env-file), que não remove aspas: elas virariam parte do valor."
    echo "#"
    echo "# Token de acesso que enxerga a conta de teste (WhatsApp > Configuração da API, ou usuário do sistema)."
    echo "WHATSAPP_ACCESS_TOKEN="
    echo "# App Secret do app de teste (Configurações do app > Básico > Chave secreta do aplicativo)."
    echo "WHATSAPP_APP_SECRET="
    echo "# Phone Number ID do número de teste. Eventos de outro número são ignorados."
    echo "WHATSAPP_PHONE_NUMBER_ID=$WHATSAPP_PHONE_NUMBER_ID"
    echo "# Gerado por infra/homologacao-meta.sh: é o 'Verificar token' do webhook na Meta."
    echo "WHATSAPP_VERIFY_TOKEN=$(aleatorio 48)"
    echo "# WABA do número de teste. Eventos de outra WABA são descartados no webhook."
    echo "WHATSAPP_WABA_ID=$WHATSAPP_WABA_ID"
  } > "$temporario"
  mv -f "$temporario" "$WHATSAPP"
  echo "$WHATSAPP criado: falta preencher WHATSAPP_ACCESS_TOKEN e WHATSAPP_APP_SECRET."
}

preparar_banco() {
  docker exec "$MYSQL" sh -c "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -e \"CREATE DATABASE IF NOT EXISTS $BANCO; GRANT ALL ON $BANCO.* TO 'emconta'@'%';\"" 2>/dev/null \
    || falhar "não foi possível preparar o banco $BANCO"
  echo "banco $BANCO pronto (migrations rodam ao subir a API)."
}

# Mesmo roteiro de infra/armazenamento-local.sh, para o bucket de homologação: bucket privado, conta
# restrita a ele, e confirmação de que a credencial não administra o MinIO nem acessa outros buckets.
preparar_armazenamento() {
  local segredo bucket_trabalho resultado temporario
  bucket_trabalho="$(valor MINIO_BUCKET .env)"
  [ -n "$bucket_trabalho" ] || falhar "MINIO_BUCKET ausente em infra/.env"
  if [ -f "$ARMAZENAMENTO" ] && grep -q '^ARMAZENAMENTO_CHAVE_SECRETA=.' "$ARMAZENAMENTO"; then
    segredo="$(valor ARMAZENAMENTO_CHAVE_SECRETA "$ARMAZENAMENTO")"
  else
    segredo="$(aleatorio 36)"
  fi
  sed "s/BUCKET/$BUCKET/g" minio/politica-api.json \
    | docker exec -i "$MINIO" sh -c 'cat > /tmp/politica-homolog.json'
  resultado="$(printf '%s\n' "$segredo" | docker exec -i \
    -e USUARIO="$USUARIO_MINIO" -e BUCKET="$BUCKET" -e OUTROS="$bucket_trabalho emconta-e2e" \
    "$MINIO" bash -c '
    set -euo pipefail
    read -r SEGREDO
    falhar() { echo "ERRO: $*" >&2; rm -f /tmp/politica-homolog.json; exit 1; }
    mc alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
    mc mb --ignore-existing "local/$BUCKET" >/dev/null
    ANONIMO="$(mc anonymous get "local/$BUCKET")" || falhar "não foi possível consultar o acesso anônimo"
    [[ "$ANONIMO" == *"is \`private\`"* ]] || falhar "o bucket $BUCKET tem acesso anônimo ($ANONIMO)"
    mc admin policy create local "$USUARIO" /tmp/politica-homolog.json >/dev/null \
      || falhar "não foi possível criar a política $USUARIO"
    rm -f /tmp/politica-homolog.json
    mc admin user add local "$USUARIO" "$SEGREDO" >/dev/null || falhar "não foi possível criar a conta $USUARIO"
    SAIDA="$(mc admin policy attach local "$USUARIO" --user "$USUARIO" 2>&1)" \
      || [[ "$SAIDA" == *"already"* ]] || falhar "não foi possível associar a política: $SAIDA"
    [[ "$(mc admin user info local "$USUARIO")" == *"PolicyName: $USUARIO"* ]] \
      || falhar "a política $USUARIO não está associada à conta"
    mc alias set restrita http://127.0.0.1:9000 "$USUARIO" "$SEGREDO" >/dev/null
    mc ls "restrita/$BUCKET" >/dev/null 2>&1 || falhar "a credencial não acessa o bucket $BUCKET"
    ! mc admin info restrita >/dev/null 2>&1 || falhar "a credencial consegue administrar o MinIO"
    for outro in $OUTROS; do
      ! mc ls "restrita/$outro" >/dev/null 2>&1 || falhar "a credencial acessa o bucket $outro"
    done
    mc alias rm restrita >/dev/null
    echo "verificado"
  ')"
  [ "$resultado" = "verificado" ] || falhar "verificação incompleta no MinIO"
  temporario="$(mktemp "$ARMAZENAMENTO.XXXXXX")"
  {
    echo "# Gerado por infra/homologacao-meta.sh. Não versionar."
    echo "ARMAZENAMENTO_ENDPOINT=http://minio:9000"
    echo "ARMAZENAMENTO_BUCKET=$BUCKET"
    echo "ARMAZENAMENTO_CHAVE_ACESSO=$USUARIO_MINIO"
    echo "ARMAZENAMENTO_CHAVE_SECRETA=$segredo"
    echo "ARMAZENAMENTO_CRIAR_BUCKET=false"
  } > "$temporario"
  mv -f "$temporario" "$ARMAZENAMENTO"
  echo "bucket $BUCKET privado; conta $USUARIO_MINIO restrita a ele (sem administração nem outros buckets)."
}

# A rede inspeciona TLS: a JVM da imagem só confia nas CAs públicas. A CA corporativa entra apenas no
# truststore deste container, montado só para leitura; a validação de certificado continua ativa.
preparar_truststore() {
  if [ -z "${CA_EXTRA:-}" ]; then
    [ -f "$CACERTS" ] && echo "CA_EXTRA não informado: truststore existente mantido." \
      || echo "CA_EXTRA não informado: truststore padrão da imagem."
    return
  fi
  [ -f "$CA_EXTRA" ] || falhar "CA_EXTRA não encontrado: $CA_EXTRA"
  cp "$CA_EXTRA" "$DIR/ca-extra.pem"
  docker run --rm --user root --entrypoint bash -v "$(caminho_docker "$DIR"):/saida" "$IMAGEM" -c '
    set -euo pipefail
    cp "$JAVA_HOME/lib/security/cacerts" /saida/cacerts.novo
    cd /tmp && csplit -s -z -f ca- /saida/ca-extra.pem "/-----BEGIN CERTIFICATE-----/" "{*}"
    for f in /tmp/ca-*; do
      keytool -importcert -noprompt -keystore /saida/cacerts.novo -storepass changeit \
        -alias "extra-$(basename "$f")" -file "$f" >/dev/null 2>&1 || { echo "falha ao importar $f" >&2; exit 1; }
    done
    chmod 644 /saida/cacerts.novo' || falhar "não foi possível gerar o truststore"
  rm -f "$DIR/ca-extra.pem"
  mv -f "$DIR/cacerts.novo" "$CACERTS"
  echo "truststore $CACERTS gerado (CAs públicas da imagem + CA corporativa)."
  # O cloudflared (Go) lê SSL_CERT_FILE: CAs públicas do Alpine + a mesma CA corporativa.
  { docker run --rm --entrypoint cat "$IMAGEM_PROXY" /etc/ssl/certs/ca-certificates.crt; cat "$CA_EXTRA"; } \
    > "$CA_TUNEL.novo" || falhar "não foi possível gerar $CA_TUNEL"
  mv -f "$CA_TUNEL.novo" "$CA_TUNEL"
  echo "CAs do túnel em $CA_TUNEL (CAs públicas + CA corporativa)."
}

preparar_admin() {
  local email="${ADMIN_EMAIL:-admin-homolog@emconta.local}"
  if [ -f "$ADMIN" ]; then
    echo "$ADMIN já existe; administrador mantido."
    return
  fi
  aleatorio 32 > "$ADMIN.novo"
  docker run --rm -i --network "$REDE" \
    -e SPRING_PROFILES_ACTIVE=local,bootstrap-admin -e DB_HOST=mysql -e DB_PORT=3306 -e DB_NAME="$BANCO" \
    -e EMCONTA_URL_PUBLICA="$URL_FRONTEND" -e SPRING_MAIL_HOST=mailpit -e SPRING_MAIL_PORT=1025 \
    "$IMAGEM" --emconta.bootstrap.nome="Administrador da homologação" --emconta.bootstrap.email="$email" \
    < "$ADMIN.novo" > "$DIR/bootstrap.log" 2>&1 \
    || { rm -f "$ADMIN.novo"; falhar "bootstrap do administrador falhou (ver $DIR/bootstrap.log)"; }
  mv -f "$ADMIN.novo" "$ADMIN"
  echo "administrador $email criado; senha inicial na 1ª linha de $ADMIN."
}

# Confere o arquivo sem exibir valores e, com o token, pergunta à Graph API se o Phone Number ID é o
# número de teste esperado e pertence à WABA informada.
conferir_arquivo() {
  [ -f "$WHATSAPP" ] || falhar "$WHATSAPP não existe; rode 'preparar' antes"
  local faltando=0 nome conteudo
  # Editores no Windows costumam salvar com CRLF; o Docker manteria o \r no fim de cada valor.
  if grep -q $'\r' "$WHATSAPP"; then
    sed -i 's/\r$//' "$WHATSAPP"
    echo "$WHATSAPP convertido de CRLF para LF."
  fi
  for nome in $OBRIGATORIAS; do
    conteudo="$(valor "$nome" "$WHATSAPP")"
    if [ -z "$conteudo" ]; then
      echo "$nome: vazio"; faltando=1
    elif [[ "$conteudo" =~ [[:space:]\"\'] ]]; then
      echo "$nome: contém espaço ou aspas (remova)"; faltando=1
    else
      echo "$nome: preenchido (${#conteudo} caracteres)"
    fi
  done
  [ "$faltando" = 0 ]
}

conferir() {
  conferir_arquivo || return 1
  local waba
  waba="$(valor WHATSAPP_WABA_ID "$WHATSAPP")"
  [ -n "$waba" ] || { echo "WHATSAPP_WABA_ID ausente: conferência na Graph API pulada."; return 0; }
  python -I - "$(caminho_docker "$WHATSAPP")" "$waba" <<'PY'
import datetime, json, ssl, sys, urllib.error, urllib.parse, urllib.request

sys.stdout.reconfigure(encoding="utf-8")
# Cadeia e nome do host continuam verificados (repositório de certificados do sistema). Só o modo
# estrito do Python 3.13+ sai: CAs de inspeção TLS corporativa costumam não ter Authority Key Identifier.
contexto = ssl.create_default_context()
contexto.verify_flags &= ~ssl.VERIFY_X509_STRICT

valores = {}
for linha in open(sys.argv[1], encoding="utf-8"):
    if "=" in linha and not linha.startswith("#"):
        chave, valor = linha.rstrip("\n").split("=", 1)
        valores[chave] = valor
waba = sys.argv[2]
numero = valores["WHATSAPP_PHONE_NUMBER_ID"]
versao = valores.get("WHATSAPP_GRAPH_VERSION") or "v23.0"

def graph(caminho):
    pedido = urllib.request.Request(
        f"https://graph.facebook.com/{versao}/{caminho}",
        headers={"Authorization": "Bearer " + valores["WHATSAPP_ACCESS_TOKEN"]})
    try:
        with urllib.request.urlopen(pedido, timeout=20, context=contexto) as resposta:
            return json.load(resposta)
    except urllib.error.HTTPError as erro:
        try:
            corpo = json.load(erro).get("error", {})
        except ValueError:
            corpo = {}
        sys.exit(f"Graph API {erro.code} em {caminho.split('?')[0]}: {corpo.get('type')} "
                 f"{corpo.get('code')} {corpo.get('message')}")

token = graph("debug_token?" + urllib.parse.urlencode({"input_token": valores["WHATSAPP_ACCESS_TOKEN"]}))["data"]
expira = token.get("expires_at")
print(f"token: válido {token.get('is_valid')}, app {token.get('application')} ({token.get('app_id')}), expira "
      + (datetime.datetime.fromtimestamp(expira, datetime.timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
         if expira else "nunca"))
alvos = {g.get("scope"): g.get("target_ids") for g in token.get("granular_scopes", [])}
print(f"contas que o token alcança: {alvos}")
dados = graph(f"{numero}?fields=display_phone_number,verified_name,code_verification_status,quality_rating")
print(f"número {dados.get('id')}: {dados.get('display_phone_number')} · {dados.get('verified_name')}")
numeros = graph(f"{waba}/phone_numbers?fields=id,display_phone_number").get("data", [])
print(f"WABA {waba}: {len(numeros)} número(s); configurado pertence a ela: "
      f"{'sim' if any(n['id'] == numero for n in numeros) else 'NÃO'}")
apps = graph(f"{waba}/subscribed_apps").get("data", [])
nomes = [a.get("whatsapp_business_api_data", {}).get("name") for a in apps]
print(f"apps assinados na WABA: {', '.join(n or '?' for n in nomes) or 'nenhum'}")
PY
}

subir() {
  conferir_arquivo >/dev/null || falhar "$WHATSAPP incompleto; rode 'conferir'"
  [ -f "$ARMAZENAMENTO" ] || falhar "$ARMAZENAMENTO ausente; rode 'preparar'"
  if docker inspect "$CONTAINER" >/dev/null 2>&1; then
    falhar "container $CONTAINER já existe; rode 'descer' antes de subir de novo"
  fi
  local opcoes_java="-XX:MaxRAMPercentage=75 -Duser.timezone=UTC" montagem=()
  if [ -f "$CACERTS" ]; then
    montagem=(-v "$(caminho_docker "$CACERTS"):/app/cacerts:ro")
    opcoes_java="$opcoes_java -Djavax.net.ssl.trustStore=/app/cacerts -Djavax.net.ssl.trustStorePassword=changeit"
  fi
  docker run -d --name "$CONTAINER" --network "$REDE" -p "127.0.0.1:$PORTA:8080" \
    --env-file "$(caminho_docker "$WHATSAPP")" --env-file "$(caminho_docker "$ARMAZENAMENTO")" "${montagem[@]}" \
    -e SPRING_PROFILES_ACTIVE=local,api -e DB_HOST=mysql -e DB_PORT=3306 -e DB_NAME="$BANCO" \
    -e SPRING_MAIL_HOST=mailpit -e SPRING_MAIL_PORT=1025 -e EMCONTA_URL_PUBLICA="$URL_FRONTEND" \
    -e JAVA_TOOL_OPTIONS="$opcoes_java" "$IMAGEM" >/dev/null
  for _ in $(seq 1 60); do
    if curl -fsS "http://127.0.0.1:$PORTA/actuator/health" >/dev/null 2>&1; then
      echo "API de homologação em http://127.0.0.1:$PORTA (banco $BANCO, bucket $BUCKET)."
      return
    fi
    sleep 2
  done
  falhar "a API não respondeu; veja 'docker logs $CONTAINER'"
}

trocar_verify_token() {
  [ -f "$WHATSAPP" ] || falhar "$WHATSAPP não existe; rode 'preparar' antes"
  local temporario
  temporario="$(mktemp "$WHATSAPP.XXXXXX")"
  sed "s/^WHATSAPP_VERIFY_TOKEN=.*/WHATSAPP_VERIFY_TOKEN=$(aleatorio 48)/" "$WHATSAPP" > "$temporario"
  grep -q '^WHATSAPP_VERIFY_TOKEN=.' "$temporario" || { rm -f "$temporario"; falhar "linha do verify token ausente"; }
  mv -f "$temporario" "$WHATSAPP"
  echo "novo verify token gravado em $WHATSAPP; reinicie a API ('descer' e 'subir') e atualize-o na Meta."
}

tunel_abrir() {
  docker inspect "$CONTAINER" >/dev/null 2>&1 || falhar "API de homologação parada; rode 'subir'"
  local c
  for c in "$PROXY" "$TUNEL"; do
    ! docker inspect "$c" >/dev/null 2>&1 || falhar "$c já existe; rode 'tunel-fechar' antes"
  done
  docker network inspect "$REDE_TUNEL" >/dev/null 2>&1 || docker network create "$REDE_TUNEL" >/dev/null
  docker run -d --name "$PROXY" --network "$REDE_TUNEL" --read-only --tmpfs /tmp \
    -v "$(caminho_docker "$PWD/homologacao-webhook.conf"):/etc/nginx/nginx.conf:ro" \
    --entrypoint nginx "$IMAGEM_PROXY" -g 'daemon off;' >/dev/null
  # O proxy é o único na rede do túnel que também alcança a rede da API.
  docker network connect "$REDE" "$PROXY"
  # Só abre o túnel com o proxy respondendo: raiz 404 e webhook repassado à API (403 sem token).
  local raiz="" webhook=""
  for _ in $(seq 1 10); do
    raiz="$(docker exec "$PROXY" wget -q -S -O /dev/null http://127.0.0.1:8080/ 2>&1 | grep -oE 'HTTP/1\.[01] [0-9]{3}' | head -1 | cut -d' ' -f2 || true)"
    webhook="$(docker exec "$PROXY" wget -q -S -O /dev/null \
      http://127.0.0.1:8080/api/v1/integracoes/whatsapp/webhook 2>&1 | grep -oE 'HTTP/1\.[01] [0-9]{3}' | head -1 | cut -d' ' -f2 || true)"
    [ "$raiz" = 404 ] && [ "$webhook" = 403 ] && break
    sleep 1
  done
  if [ "$raiz" != 404 ] || [ "$webhook" != 403 ]; then
    docker logs "$PROXY" 2>&1 | tail -3 >&2
    tunel_fechar >/dev/null
    falhar "proxy não respondeu como esperado (raiz $raiz, webhook $webhook); túnel não aberto"
  fi
  local montagem=()
  if [ -f "$CA_TUNEL" ]; then
    montagem=(-v "$(caminho_docker "$CA_TUNEL"):/certs/ca.pem:ro" -e SSL_CERT_FILE=/certs/ca.pem)
  fi
  docker run -d --name "$TUNEL" --network "$REDE_TUNEL" "${montagem[@]}" "$IMAGEM_TUNEL" \
    tunnel --no-autoupdate --url "http://$PROXY:8080" >/dev/null
  local url=""
  for _ in $(seq 1 45); do
    url="$(docker logs "$TUNEL" 2>&1 | grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' | head -1 || true)"
    [ -n "$url" ] && break
    sleep 2
  done
  [ -n "$url" ] || falhar "o túnel não informou o endereço; veja 'docker logs $TUNEL'"
  echo "$url" > "$URL_TUNEL"
  echo "túnel aberto: $url"
  echo "callback: $url/api/v1/integracoes/whatsapp/webhook"
}

tunel_fechar() {
  docker rm -f "$TUNEL" "$PROXY" >/dev/null 2>&1 || true
  docker network rm "$REDE_TUNEL" >/dev/null 2>&1 || true
  rm -f "$URL_TUNEL"
  echo "túnel e proxy encerrados."
}

descer() {
  docker rm -f "$CONTAINER" >/dev/null 2>&1 && echo "container $CONTAINER removido." \
    || echo "container $CONTAINER não existia."
}

conferir_pasta
umask 077
case "${1:-}" in
  preparar)
    mkdir -p "$DIR"
    preparar_whatsapp
    preparar_banco
    preparar_armazenamento
    preparar_truststore
    preparar_admin
    ;;
  conferir) conferir ;;
  subir) subir ;;
  descer) descer ;;
  trocar-verify-token) trocar_verify_token ;;
  tunel-abrir) tunel_abrir ;;
  tunel-fechar) tunel_fechar ;;
  *) falhar "uso: bash infra/homologacao-meta.sh preparar|conferir|subir|descer|trocar-verify-token|tunel-abrir|tunel-fechar" ;;
esac
