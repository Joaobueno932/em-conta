# em-conta-backend

API e worker do **Em Conta**: monólito modular em Spring Boot (uma aplicação, módulos por pacote,
verificados com Spring Modulith). Banco MySQL/InnoDB `utf8mb4`, schema exclusivamente via Flyway.

> Estado: fundação técnica + **acesso de usuários internos** (login, sessão, convite, recuperação
> e troca de senha, administração de usuários, perfis e permissões) + **cadastro de associados PF/PJ,
> contatos e unidades consumidoras**, com histórico + **fazendas, usinas e vínculos cadastrais UC →
> usina** + **atendimento via WhatsApp** (Cloud API direta; canal "não configurado" enquanto as
> variáveis não forem preenchidas), com anexos (imagem, PDF e áudio) no MinIO privado. Detalhes em [docs/seguranca.md](docs/seguranca.md),
> [docs/associados.md](docs/associados.md), [docs/usinas.md](docs/usinas.md) e
> [docs/atendimento.md](docs/atendimento.md).

## Requisitos

| Ferramenta | Versão | Observação |
|---|---|---|
| JDK | 25 (LTS) | Opcional: sem JDK local, use `scripts/mvnw-docker.*` (JDK no container) |
| Docker + Compose | 24+ / v2 | Infra local e Testcontainers |
| Maven | — | Não precisa: o projeto usa o Maven Wrapper (Maven 3.9.16) |

## Início rápido

```powershell
# 1. Infraestrutura local (MySQL, RabbitMQ, MinIO, Mailpit)
cd infra
Copy-Item .env.example .env        # bash: cp .env.example .env
docker compose up -d --wait
cd ..

# 2a. Com JDK 25 instalado
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local,api"
#     bash: ./mvnw spring-boot:run -Dspring-boot.run.profiles=local,api

# 2b. Sem JDK local: imagem Docker na rede do Compose
docker build -t em-conta-backend .
docker run --rm --name emconta-api --network em-conta_default -p 127.0.0.1:8080:8080 `
  -e SPRING_PROFILES_ACTIVE=local,api -e DB_HOST=mysql -e DB_PORT=3306 em-conta-backend
```

Pela IDE: execute `br.com.emconta.EmContaApplication` com os profiles `local,api` (ou
`local,worker`). No container, para os e-mails irem ao Mailpit do Compose, acrescente
`-e SPRING_MAIL_HOST=mailpit -e SPRING_MAIL_PORT=1025`.

### Primeiro administrador

Não existe usuário padrão. Crie o primeiro administrador uma única vez (a senha é pedida sem eco):

```powershell
docker run --rm -it --network em-conta_default `
  -e SPRING_PROFILES_ACTIVE=local,bootstrap-admin -e DB_HOST=mysql -e DB_PORT=3306 `
  em-conta-backend --emconta.bootstrap.nome="Seu Nome" --emconta.bootstrap.email=voce@exemplo.com.br
```

Outras formas e regras: [docs/seguranca.md](docs/seguranca.md#primeiro-administrador-bootstrap).
Depois, entre pelo frontend (http://localhost:5173) e convide os demais em **Usuários**. Os
e-mails de convite e de recuperação aparecem no Mailpit: http://localhost:8025.

### Verificação

| URL | Esperado |
|---|---|
| http://localhost:8080/api/v1/system/status | 200, `{"status":"UP","database":"UP",...}` |
| http://localhost:8080/actuator/health | 200, `{"status":"UP"}` (sem detalhes) |
| http://localhost:8080/actuator/health/liveness · /readiness | probes |
| http://localhost:8080/swagger-ui.html | Swagger UI (somente profile `local`) |
| http://localhost:8080/v3/api-docs | OpenAPI JSON (somente profile `local`) |
| http://localhost:8080/api/v1/auth/sessao | 401 `NAO_AUTENTICADO` sem sessão; 200 com o usuário após login |
| http://localhost:8080/api/v1/qualquer-outro | 401 em Problem Details |

### Endpoints de acesso

| Método e caminho | Acesso | Uso |
|---|---|---|
| `GET /api/v1/auth/csrf` | público | emite o cookie `XSRF-TOKEN` |
| `POST /api/v1/auth/login` | público + CSRF | cria a sessão |
| `POST /api/v1/auth/logout` | público + CSRF | encerra a sessão |
| `GET /api/v1/auth/sessao` | sessão | usuário atual e permissões |
| `POST /api/v1/auth/senha/alteracao` | sessão | troca de senha com a senha atual |
| `POST /api/v1/auth/senha/recuperacao` | público + CSRF | pede o link (resposta genérica) |
| `POST /api/v1/auth/senha/redefinicao` | público + CSRF | nova senha pelo link |
| `POST /api/v1/auth/tokens/validacao` | público + CSRF | confere um link sem consumi-lo |
| `POST /api/v1/auth/convites/aceitacao` | público + CSRF | define a senha pelo convite |
| `GET/POST/PATCH/PUT /api/v1/usuarios…`, `GET /api/v1/perfis` | permissões `usuarios:*` | administração (ver docs/seguranca.md) |

### Endpoints de associados

| Método e caminho | Permissão | Uso |
|---|---|---|
| `GET /api/v1/associados?busca=&tipo=&ordenacao=&pagina=&tamanho=` | `associados:consultar` | listagem paginada (máx. 100), documento mascarado |
| `POST /api/v1/associados` | `associados:cadastrar` | cadastro PF/PJ com contatos |
| `GET /api/v1/associados/{id}` | `associados:consultar` | ficha com contatos e `versao` |
| `PUT /api/v1/associados/{id}` | `associados:editar` | nome/razão social e nome fantasia (com `versao`) |
| `POST …/{id}/contatos`, `PUT …/contatos/{contatoId}`, `POST …/contatos/{contatoId}/remocao` | `associados:editar` | contatos, um a um |
| `GET /api/v1/associados/{id}/ucs`, `GET …/ucs/{ucId}` | `ucs:consultar` | UCs do associado |
| `POST /api/v1/associados/{id}/ucs` | `ucs:cadastrar` | cadastro de UC |
| `PUT /api/v1/associados/{id}/ucs/{ucId}` | `ucs:editar` | denominação e endereço (com `versao`) |
| `GET /api/v1/associados/{id}/historico?categoria=&pagina=&tamanho=` | `associados:consultar-historico` | histórico paginado |

### Endpoints de fazendas, usinas e vínculos

| Método e caminho | Permissão | Uso |
|---|---|---|
| `GET /api/v1/usinas?busca=&uf=&fazendaId=&ordenacao=&pagina=&tamanho=` | `usinas:consultar` | listagem (máx. 100) com UCs vinculadas e associados distintos |
| `POST /api/v1/usinas`, `GET`/`PUT /api/v1/usinas/{id}` | `usinas:cadastrar` / `consultar` / `editar` | cadastro e edição (com `versao`; `confirmarNomeRepetido`) |
| `GET`/`POST /api/v1/fazendas`, `GET`/`PUT /api/v1/fazendas/{id}` | idem | fazendas |
| `GET /api/v1/usinas/{id}/historico`, `GET /api/v1/fazendas/{id}/historico` | `usinas:consultar-historico` | histórico paginado |
| `GET /api/v1/usinas/{id}/vinculos?situacao=VIGENTES\|ENCERRADOS\|TODOS` | `vinculos-usina:consultar` | vínculos da usina |
| `GET /api/v1/associados/{id}/vinculos-usina` | `vinculos-usina:consultar` | vínculos das UCs do associado |
| `GET /api/v1/vinculos-usina/ucs-candidatas?busca=` | `vinculos-usina:criar` | busca de UCs existentes com o vínculo vigente |
| `POST /api/v1/usinas/{id}/vinculos` | `vinculos-usina:criar` | vincula UC (`ucId`, `inicio`) |
| `POST /api/v1/usinas/{id}/vinculos/{vinculoId}/encerramento` | `vinculos-usina:encerrar` | encerra (`versao`, `fim`, `motivo`) |

### Endpoints de atendimento

Base `/api/v1/atendimento` (fila, conversa, resposta, anexos e área Mídia, transferência,
encerramento, vínculo com associado, histórico) e o webhook público `/api/v1/integracoes/whatsapp/webhook`. Lista completa,
permissões, variáveis `WHATSAPP_*` e `ARMAZENAMENTO_*` e homologação: [docs/atendimento.md](docs/atendimento.md).

Erros vêm em Problem Details com o campo `codigo` (ex.: `CREDENCIAIS_INVALIDAS`,
`MUITAS_TENTATIVAS`, `SESSAO_EXPIRADA`, `TOKEN_INVALIDO`, `ULTIMO_ADMINISTRADOR`, `CREDENCIAL_ALTERADA`,
`DOCUMENTO_JA_CADASTRADO`, `UC_JA_CADASTRADA`, `CONFLITO_CONCORRENTE`).

## Profiles

Combine **um ambiente** com **uma função de processo**:

| Profile | Tipo | Efeito |
|---|---|---|
| `local` | ambiente | Banco do Compose em `localhost:3310`, credenciais de exemplo, cookie sem `Secure` (HTTP), Swagger liberado |
| `api` | processo | Servidor HTTP na porta 8080, sessão JDBC, cookies |
| `worker` | processo | Sem servidor HTTP (`web-application-type: none`), mantém o processo ativo. Executa apenas a limpeza periódica de contadores e tokens vencidos |
| `bootstrap-admin` | comando | Cria o primeiro administrador e encerra (ver acima) |

Sem `local`, valem os padrões seguros de produção: `DB_USERNAME`/`DB_PASSWORD` obrigatórios, cookie
`Secure`, OpenAPI desligado.

Variáveis de ambiente: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`,
`SERVER_PORT`, `EMCONTA_COOKIE_SECURE`, `EMCONTA_URL_PUBLICA` (obrigatória: URL do frontend
usada nos links por e-mail; https fora do localhost), `EMCONTA_EMAIL_REMETENTE` e SMTP
(`SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`,
`SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE`). No profile `local`, os padrões apontam para o
Mailpit (`localhost:1025`) e para `http://localhost:5173`. Atrás de proxy, ajuste
`SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` ao IP do proxy.

## Comandos de qualidade

```powershell
.\mvnw.cmd verify                 # compila, testes (inclui Testcontainers/MySQL), Spotless check
.\mvnw.cmd spotless:apply         # formata o código (Palantir Java Format)
.\mvnw.cmd test "-Dtest=ModularityTests"   # só a verificação de módulos
```

Sem JDK local (Docker): `.\scripts\mvnw-docker.ps1 verify` ou `sh scripts/mvnw-docker.sh verify`.
Em rede com inspeção TLS ou downloads instáveis, ver [docs/ci.md](docs/ci.md#redes-corporativas).

O que `verify` cobre:

- `ModularityTests`: fronteiras do Spring Modulith e lista dos módulos planejados; gera
  documentação em `target/spring-modulith-docs`.
- `ApiFoundationIntegrationTests` (MySQL 8.4 real via Testcontainers): endpoint técnico, health sem
  detalhes, 401 para rota não liberada, OpenAPI fechado fora do `local`, CSRF ativo (403 sem token,
  aceito com cookie `XSRF-TOKEN` + cabeçalho), migrations aplicadas, tabelas do Spring Session e
  charset `utf8mb4`. O Hibernate em `validate` valida o schema na subida.
- `OpenApiContractTests`: o contrato versionado `contract/openapi.json` confere com a API.
- `WorkerProcessTests`: o worker sobe sem servidor HTTP e sem cadeia de segurança web.
- `acesso/*IntegrationTests` (MySQL e Mailpit reais): login válido e inválido com resposta
  genérica, contas convidadas e desativadas, fixação de sessão, CSRF antes e depois de login e
  logout, 401 sem sessão e com sessão expirada ou revogada, 403 sem permissão, limites por conta e
  por IP (inclusive em paralelo), convite e reenvio com token lido do e-mail no Mailpit, tokens
  expirados, reutilizados, malformados e de outra finalidade, consumo concorrente, recuperação sem
  enumeração, redefinição e troca de senha com encerramento de sessões, mudança de perfis em
  sessão aberta, versão de segurança, escalada, campos não permitidos, e-mail duplicado (inclusive
  concorrente), falha de SMTP, auditoria sem segredos, bootstrap e último administrador (inclusive
  concorrente).
- `associados/AssociadosIntegrationTests` (MySQL real): cadastro PF e PJ (CNPJ alfanumérico),
  documento inválido e duplicado após normalização, cadastros simultâneos do mesmo documento e da
  mesma UC, contatos (validação, sem unicidade, edição e remoção lógica), várias UCs com zeros à
  esquerda e unicidade por concessionária, vínculo inválido e sem troca de titular, edição
  concorrente de associado, contato e UC, paginação e busca, 401/403 por chamada direta à API,
  histórico com autor desativado e dados pessoais mascarados.
- `usinas/UsinasIntegrationTests` (MySQL real): cadastro de fazenda e usina, campos e limites,
  aviso de nome parecido e confirmação, edição concorrente, vínculo de UC existente, UC
  inexistente, vínculo duplicado e com outra usina, vínculos simultâneos da mesma UC, início futuro,
  encerramento (motivo, datas, simultâneo), sobreposição de vigência, consulta pelos dois lados,
  paginação e filtros, histórico com autor desativado.
- `AutorizacaoParcialIntegrationTests`: permissões parciais por chamada direta à API (usinas,
  vínculos, histórico, associados e UCs), com sessão e regras de rota reais.
- `MigracaoV4IntegrationTests`: base em V3 com dados migrada até a versão atual sem perda.
- `acesso/CredenciaisConcorrenciaIntegrationTests`: corridas controladas por latches (troca × redefinição,
  duas trocas, troca × desativação, requisição da mesma sessão durante a troca), rollback sem
  consumo de token nem auditoria, tokens inválidos sem Argon2, uso simultâneo do mesmo token e limite
  por IP dos links públicos (inclusive em paralelo).
- `acesso/AutorizacaoPadraoIntegrationTests`: negação por padrão para rota sem regra, métodos não
  declarados e rotas públicas.
- `associados/DocumentosTests`: CPF, CNPJ numérico e alfanumérico (exemplo oficial) e máscaras.
- `RegrasAcessoTests`: política de senha (caracteres e bytes), normalização de e-mail e validação
  da URL pública.
- `spotless:check`: formatação.

## Contrato OpenAPI

`contract/openapi.json` é a fonte versionada do contrato. Após mudar a API:

```powershell
.\mvnw.cmd test "-Dtest=OpenApiContractTests" "-Dcontract.update=true"
```

Revise o diff e faça commit. O frontend mantém uma cópia própria (ver README do
`em-conta-frontend`, seção *Contrato da API*).

## Estrutura

```
src/main/java/br/com/emconta/
  EmContaApplication.java
  acesso/        usuários, sessão, senhas, convites, permissões, auditoria e segurança HTTP
  associados/    associados PF/PJ, contatos, UCs e histórico (cadastro/ e web/)
  usinas/        fazendas, usinas e vínculos cadastrais UC → usina (cadastro/ e web/)
  atendimento/   atendimento                     (somente descritor)
  comercial/     CRM e propostas                 (somente descritor)
  contratos/     contratos e assinatura          (somente descritor)
  faturamento/   faturamento e inadimplência     (somente descritor)
  conciliacao/   conciliação                     (somente descritor)
  integracoes/   Devex e Meta                    (somente descritor)
  sistema/       GET /api/v1/system/status
  plataforma/    relógio UTC, OpenAPI, Problem Details
src/main/resources/
  application*.yml
  db/migration/  migrations Flyway
contract/        contrato OpenAPI versionado
infra/           Docker Compose local (ver infra/README.md)
scripts/         execução do Maven Wrapper via Docker
docs/            arquitetura, segurança, versões, CI
```

Detalhes: [docs/arquitetura.md](docs/arquitetura.md) · [docs/seguranca.md](docs/seguranca.md) ·
[docs/associados.md](docs/associados.md) · [docs/usinas.md](docs/usinas.md) ·
[docs/atendimento.md](docs/atendimento.md) ·
[docs/versoes.md](docs/versoes.md) · [docs/ci.md](docs/ci.md) · [infra/README.md](infra/README.md)
