# em-conta-frontend

Frontend do **Em Conta**: Vue 3 + TypeScript estrito + Vite, gerado com o `create-vue` oficial.

> Estado: fundação técnica + **acesso de usuários internos** + **cadastro de associados PF/PJ,
> contatos, unidades consumidoras e histórico**. As telas de associados seguem a estrutura, os textos
> e as variáveis de cor (`cor/*`) do protótipo no Figma (*Em Conta — Protótipo*, frames AS-02,
> NA-01/02/03, ED-01, AD-02/05/07 e HA-01), restritas ao que foi implementado; não são uma réplica
> pixel a pixel. Usinas e vínculos seguem UL-01, UV-01, UU-01/03, UI-01/02 e OV-U06/U08/U09/U10/U11
> (vínculo e encerramento em painel na página, não em modal). Fazendas não têm frame e seguem o
> padrão das listagens. As telas de acesso continuam provisórias.

## Requisitos

| Ferramenta | Versão |
|---|---|
| Node.js | 24 LTS (`>=24.12.0 <25`) |
| npm | 11.17.0 (fixado em `packageManager`; lockfile `package-lock.json`) |
| Backend | `em-conta-backend` rodando em `http://localhost:8080` (ver README dele) |

## Início rápido

```powershell
npm ci
npm run dev        # http://localhost:5173  (proxy /api -> http://localhost:8080)
```

Outro endereço de backend: `$env:EM_CONTA_API_PROXY_TARGET = "http://localhost:9090"; npm run dev`
(bash: `EM_CONTA_API_PROXY_TARGET=http://localhost:9090 npm run dev`). A variável é lida só pelo
Vite (Node) e não vai para o bundle.

Rotas: `/entrar`, `/recuperar-senha`, `/redefinir-senha#token=…`, `/convite#token=…` (links dos
e-mails), `/` (início autenticado), `/conta/senha`, `/admin/usuarios` e `/admin/usuarios/:id`
(permissão `usuarios:consultar`), `/associados` (listagem; filtros e página ficam na URL),
`/associados/novo`, `/associados/:id` (abas `?aba=ucs` e `?aba=historico`),
`/associados/:id/editar`, `/associados/:id/ucs/nova`, `/associados/:id/ucs/:ucId/editar`,
`/usinas` (filtros na URL), `/usinas/nova`, `/usinas/:id` (abas `?aba=ucs` e `?aba=informacoes`),
`/usinas/:id/editar`, `/fazendas`, `/fazendas/nova`, `/fazendas/:id/editar`,
`/acesso-negado` e a página técnica `/sistema/status`. Cada rota exige a permissão correspondente
e as ações sem permissão não aparecem; a autorização real é do servidor. Para o
primeiro acesso, crie o administrador pelo bootstrap do backend (README do `em-conta-backend`).

A página técnica (`/sistema/status`) mostra **carregando**, **API conectada** (dados reais de
`GET /api/v1/system/status`), **banco indisponível** (API respondeu 503) ou **API indisponível**
(sem conexão, 5xx do proxy ou resposta fora do contrato), com botão para tentar novamente.

## Comandos

| Comando | O quê |
|---|---|
| `npm run dev` | servidor de desenvolvimento com proxy `/api` |
| `npm run lint` | oxlint + ESLint (sem corrigir) · `npm run lint:fix` corrige |
| `npm run format:check` | Prettier · `npm run format` aplica |
| `npm run type-check` | `vue-tsc --build` |
| `npm run test` | Vitest + Vue Testing Library (estados da página, cliente HTTP, config) |
| `npm run build` | type-check + build de produção em `dist/` |
| `npm run preview` | serve `dist/` em http://localhost:4173, com o mesmo proxy `/api` |
| `npm run test:e2e` | Playwright contra o backend **real** e o Mailpit (ver abaixo) |
| `npm run verify` | lint + format:check + type-check + test + build (comando de CI) |
| `npm run contract:update -- <origem>` | atualiza a cópia do contrato OpenAPI (ver abaixo) |

Primeira execução do Playwright: `npx playwright install chromium`. As jornadas de acesso
(`e2e/acesso.spec.ts`) precisam do backend em 8080 com e-mail no Mailpit e de um administrador
criado pelo bootstrap, informado só por variáveis de ambiente (nunca em arquivo versionado):

```powershell
$env:E2E_ADMIN_EMAIL = "voce@exemplo.com.br"
$env:E2E_ADMIN_SENHA = Read-Host "Senha do administrador"   # não fica no histórico
$env:MAILPIT_URL = "http://localhost:8025"                   # padrão
npm run test:e2e
```

As jornadas de associados e de usinas (`e2e/associados.spec.ts`, `e2e/usinas.spec.ts`) criam
registros: rode-as contra um
**banco isolado de testes**, nunca contra dados reais. Exemplo local, com uma segunda API sobre o
banco `emconta_e2e` do mesmo MySQL do Compose:

```powershell
# uma vez: banco e permissão para o usuário da aplicação (senha root do infra/.env, dentro do container)
docker exec em-conta-mysql-1 sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "CREATE DATABASE IF NOT EXISTS emconta_e2e; GRANT ALL ON emconta_e2e.* TO ''emconta''@''%'';"'
docker run -d --name emconta-api-e2e --network em-conta_default -p 127.0.0.1:8090:8080 `
  -e SPRING_PROFILES_ACTIVE=local,api -e DB_HOST=mysql -e DB_PORT=3306 -e DB_NAME=emconta_e2e `
  -e SPRING_MAIL_HOST=mailpit -e SPRING_MAIL_PORT=1025 em-conta-backend
# Todas as chamadas do e2e chegam pelo mesmo IP (o proxy); para rodar várias vezes seguidas,
# a API de testes eleva o limite de uso de links: acrescente ao docker run acima
#   -e EMCONTA_ACESSO_LIMITES_USOTOKENIP_MAXIMO=1000 -e EMCONTA_ACESSO_LIMITES_USOTOKENIP_JANELA=15m
#   -e EMCONTA_ACESSO_LIMITES_USOTOKENIP_BLOQUEIO=15m
# administrador do banco de testes pelo bootstrap (com -e DB_NAME=emconta_e2e), depois:
$env:EM_CONTA_API_PROXY_TARGET = "http://localhost:8090"
npm run test:e2e
```

As jornadas de atendimento (`e2e/atendimento.spec.ts` e `e2e/anexos.spec.ts`) usam um **ambiente externo controlado, só de
teste** (`e2e/whatsapp-controlado.ts`): um servidor local no lugar da Graph API e webhooks assinados
com segredo fictício. Nada vai para a Meta nem para o número oficial. A API de testes recebe valores
fictícios (escolha os seus; não são credenciais):

```powershell
# acrescente ao docker run da API de testes
#   -e WHATSAPP_ACCESS_TOKEN=e2e-ficticio -e WHATSAPP_PHONE_NUMBER_ID=e2e-numero-ficticio
#   -e WHATSAPP_APP_SECRET=e2e-segredo-ficticio -e WHATSAPP_VERIFY_TOKEN=e2e-ficticio
#   -e WHATSAPP_GRAPH_URL=http://host.docker.internal:8099
# anexos (e2e/anexos.spec.ts): MinIO local com um bucket só dos testes, criado pela API
#   -e ARMAZENAMENTO_ENDPOINT=http://minio:9000 -e ARMAZENAMENTO_BUCKET=emconta-e2e
#   -e ARMAZENAMENTO_CHAVE_ACESSO=<usuário do MinIO local> -e ARMAZENAMENTO_CHAVE_SECRETA=<senha>
#   -e ARMAZENAMENTO_CRIAR_BUCKET=true
$env:E2E_WHATSAPP_APP_SECRET = "e2e-segredo-ficticio"        # o mesmo da API de testes
$env:E2E_WHATSAPP_PHONE_NUMBER_ID = "e2e-numero-ficticio"
# $env:E2E_GRAPH_PORT = "8099"                                # padrão
```

Sem essas variáveis, as jornadas de acesso e de associados são puladas e só o teste da página técnica roda. Os
tokens dos convites e das recuperações são lidos dos e-mails no Mailpit; não existe endpoint que
os revele.
`CI=1 npm run test:e2e` testa o build de produção (preview) em vez do servidor de dev.

## Estrutura

```
public/config.json           configuração pública de runtime (validada antes de iniciar)
src/
  main.ts                    carrega /config.json, valida e monta a aplicação
  app/                       App.vue, providers, rotas, guardas, layout autenticado, estilos provisórios
  config/                    leitura e validação da configuração de runtime
  features/
    auth/                    login, recuperação, definição/troca de senha, acesso negado, sessão
    inicio/                  área autenticada mínima
    usuarios/                administração de usuários
    associados/              listagem, cadastro PF/PJ, ficha, contatos, UCs, histórico e usina de cada UC
    atendimento/             fila, conversa, painel do associado, histórico geral (WhatsApp)
    usinas/                  usinas, fazendas, vínculos UC → usina e histórico
    system-status/           página técnica e consulta ao endpoint de status
  shared/
    api/                     cliente HTTP, erros centrais e contrato OpenAPI (cópia gerada)
    components/              componentes compartilhados (alertas, campo de senha, aviso de descarte)
  test/                      utilitários e setup dos testes
e2e/                         testes Playwright
deploy/nginx/                proxy de mesma origem usado pela imagem Docker
scripts/update-contract.mjs  sincronização do contrato
```

Responsabilidades: **TanStack Query** guarda o estado remoto, inclusive a sessão
(`['auth','sessao']`). **Pinia** está instalado para estado local compartilhado, mas ainda não há
stores porque nada precisou disso. Regras financeiras ficam no backend.

## Sessão e rotas

- A sessão vem sempre do backend (`GET /api/v1/auth/sessao`). Cada rota declara `meta.acesso`:
  `publico`, `visitante` (login e recuperação) ou `autenticado` (padrão, com `meta.permissao`
  opcional).
- As guardas esperam a sessão ser conhecida antes de concluir a navegação, então nada protegido
  aparece antes da verificação. Elas não substituem a autorização do servidor.
- Um 401 de sessão (`NAO_AUTENTICADO`, `SESSAO_EXPIRADA`, `SESSAO_ENCERRADA`) em qualquer chamada
  limpa todo o cache e leva ao login com aviso. Login, logout e troca de usuário também limpam o
  cache, então dados de uma sessão nunca aparecem para outra.
- O destino após o login (`?redirect=`) só é aceito se for uma rota interna autenticada conhecida.
- 401, 403, 429 (com o tempo de espera) e falha de rede ou 5xx têm mensagens distintas.
  Indisponibilidade nunca aparece como "senha incorreta".
- Senhas e tokens ficam só na memória do componente. O token dos links é lido do fragmento
  (`#token=`) e apagado da barra de endereço na abertura da página; `Referrer-Policy: no-referrer`
  vale no HTML e no Nginx.

## Cliente HTTP e sessão

- URLs sempre relativas (`/api/...`, mesma origem), com `credentials: 'same-origin'`. O cookie de
  sessão é `HttpOnly` e gerenciado pelo navegador; **nenhum token vai para localStorage**.
- CSRF: em `POST/PUT/PATCH/DELETE`, o cliente copia o cookie `XSRF-TOKEN` para o cabeçalho
  `X-XSRF-TOKEN` (padrão do Spring Security).
- Erros viram `ApiError` (`network`, `http` com Problem Details, `invalid-response`);
  `describeApiError` centraliza as mensagens. Respostas fora do contrato nunca são tratadas como
  sucesso.

## Configuração de runtime

`/config.json` é público e aceita **somente** `environment` e `apiBasePath` (caminho na mesma
origem). Chaves desconhecidas, URLs absolutas e JSON inválido impedem a inicialização, com
mensagem na tela. Não coloque segredos aqui nem em variáveis `VITE_*`. Na imagem Docker, o arquivo
é gerado de `EM_CONTA_ENVIRONMENT` e `EM_CONTA_API_BASE_PATH`.

## Contrato da API

`src/shared/api/contract/` contém uma **cópia versionada** do OpenAPI do backend
(`openapi.json`), os tipos gerados (`schema.d.ts`) e a origem e versão (`contract-source.json`).
O build não depende do repositório do backend.

Para atualizar depois de uma mudança na API:

```powershell
# de um backend rodando no profile local
npm run contract:update -- http://localhost:8080/v3/api-docs
# ou do arquivo versionado de um checkout/versão do backend
npm run contract:update -- C:\caminho\em-conta-backend\contract\openapi.json
```

O script usa `openapi-typescript@7.13.0` via `npm exec`, isolado e fora das dependências (a
versão estável exige TypeScript 5, e o projeto usa TypeScript 6). Revise o diff, rode
`npm run verify` e faça commit.

## Docker e proxy de mesma origem

```powershell
docker build -t em-conta-frontend .
docker run --rm -p 127.0.0.1:8081:8080 -e API_UPSTREAM=http://host.docker.internal:8080 `
  -e EM_CONTA_ENVIRONMENT=local em-conta-frontend
# http://localhost:8081 → SPA;  http://localhost:8081/api/... → backend
```

`deploy/nginx/default.conf.template` é o exemplo de proxy: serve o SPA e encaminha `/api/` ao
backend (`API_UPSTREAM`), repassando `X-Forwarded-*` para cookies `Secure` atrás de HTTPS. Rede
corporativa com inspeção TLS: `docker build --secret id=extra_ca,src=<pem> ...`.

## CI

Provedor Git ainda não definido, por isso não há arquivo de pipeline. Etapas para qualquer
provedor:

1. `npm ci` (cache de `~/.npm`, chave: hash do `package-lock.json`).
2. `npm run verify`.
3. Integração (opcional, requer o backend): subir MySQL e backend (por exemplo, a imagem do
   backend + `infra/compose.yaml`), `npx playwright install --with-deps chromium` e
   `CI=1 npm run test:e2e`. Publicar `playwright-report/`.
4. Opcional: `docker build`. Publicação de imagem só após definir registry e credenciais.
