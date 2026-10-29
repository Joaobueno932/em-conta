# Arquitetura do backend

## Monólito modular

Uma única aplicação Spring Boot, um único artefato e um único banco. Os domínios são **módulos
lógicos**: cada pacote direto de `br.com.emconta` é um módulo do Spring Modulith, descrito no seu
`package-info.java`.

| Módulo | Responsabilidade | Situação |
|---|---|---|
| `acesso` | autenticação, sessão, usuários, convites, autorização | implementado para usuários internos (ver seguranca.md) |
| `associados` | associados PF/PJ, contatos, unidades consumidoras (UCs) e histórico | implementado (ver associados.md) |
| `usinas` | fazendas, usinas e vínculos cadastrais UC → usina (R1) | implementado (ver usinas.md) |
| `atendimento` | atendimento ao cliente | descritor |
| `comercial` | CRM e propostas | descritor |
| `contratos` | contratos, assinatura, cancelamento | descritor |
| `faturamento` | faturamento, pagamentos, inadimplência | descritor |
| `conciliacao` | conciliação de pagamentos | descritor |
| `integracoes` | Devex (boleto/Pix, assinatura) e Meta (WhatsApp) | descritor |
| `sistema` | endpoints técnicos | `GET /api/v1/system/status` |
| `plataforma` | relógio UTC, OpenAPI, Problem Details | implementado |

Os demais módulos funcionais têm apenas o descritor: nenhuma entidade, controller ou service foi
criado sem requisito. `associados` depende de `acesso` somente pela API pública
`br.com.emconta.acesso.Autoria` (usuário autenticado e nomes de autores); não lê tabelas do acesso.
`usinas` depende de `associados` pela API pública `br.com.emconta.associados.UnidadesConsumidoras`
e de `acesso` pela `Autoria`; `associados` não depende de `usinas` (sem ciclo).

### Regras de fronteira

- **API pública de um módulo** = tipos públicos no pacote raiz do módulo
  (ex.: `br.com.emconta.associados.AssociadoConsulta`). Subpacotes (`web`, `internal`, `persistence`
  etc.) são internos e não podem ser usados por outros módulos.
- Classes que não precisam ser públicas ficam package-private (ex.: `SecurityConfiguration`,
  `SystemStatusController`).
- Comunicação entre módulos: chamada a um tipo público do pacote raiz, ou evento de aplicação
  quando o emissor não precisa de resposta.
- Dependências cíclicas entre módulos são proibidas. O `ModularityTests` falha o build se uma
  regra for violada.
- Quando um módulo precisar restringir dependências, use
  `@ApplicationModule(allowedDependencies = {...})` no `package-info.java`.

### Organização interna

Comece simples: `web/` (controllers e DTOs HTTP), classes de domínio e repositórios no próprio
módulo. Portas e adaptadores só onde houver necessidade concreta, tipicamente em `integracoes`
(provedores externos substituíveis). Sem hierarquias genéricas de CRUD ou interfaces com uma única
implementação sem motivo.

## Funções de processo

A mesma imagem roda como:

- **API** (`api`): servidor HTTP, sessão JDBC, segurança web.
- **Worker** (`worker`): sem servidor HTTP. Destino de jobs agendados e consumidores RabbitMQ
  quando forem necessários. Hoje executa só a limpeza horária de contadores de tentativa e
  tokens vencidos (idempotente, também roda na API) e permanece ativo (`spring.main.keep-alive`).
- **Bootstrap** (`bootstrap-admin`): comando que cria o primeiro administrador e encerra.

As migrations rodam na subida de ambos. O Flyway usa lock no banco, então API e worker podem subir
juntos sem conflito (verificado localmente).

## Banco e migrations

- MySQL 8.4 LTS, InnoDB, `utf8mb4` / `utf8mb4_0900_ai_ci` (servidor e tabelas).
- Flyway é o único responsável pelo schema: `spring.jpa.hibernate.ddl-auto=validate` e
  `spring.session.jdbc.initialize-schema=never`.
- `V1__spring_session.sql` cria as tabelas do Spring Session JDBC.
- `V2__acesso_usuarios.sql` cria usuários, perfis, tokens, limites de tentativa e auditoria.
- `V3__associados_ucs.sql` cria associados, contatos, UCs e histórico de cadastro.
- `V4__usinas_fazendas_vinculos.sql` cria fazendas, usinas, vínculos UC → usina e histórico.
- Convenção: `V<n>__<descricao>.sql`, nunca alterar migration já aplicada.
- Unicidade por índice único: CPF/CNPJ global (`associado_documento_uk`) e UC por concessionária
  (`uc_concessionaria_numero_uk`). Violação concorrente vira 409 com código estável.

## Tempo

- Instantes técnicos (criação, auditoria, expiração) usam `Instant`, gravados e trafegados em UTC:
  JDBC com `connectionTimeZone=UTC`, `hibernate.jdbc.time_zone=UTC`, Jackson em UTC, JVM da imagem em
  UTC e bean `Clock` UTC (injetável e substituível em testes).
- **Datas de negócio** (vencimento, competência, data de leitura) usam `LocalDate`/`YearMonth`, sem
  conversão de fuso. O fuso de referência do negócio, se necessário, deverá ser decidido e
  configurado explicitamente; não foi definido nesta etapa.

## Erros HTTP

Problem Details (RFC 9457, `application/problem+json`):

- Exceções do Spring MVC: `ApiExceptionHandler` (estende `ResponseEntityExceptionHandler`).
- Exceções não tratadas: 500 com mensagem genérica; detalhes só no log do servidor.
- 401/403 da camada de segurança: mesmo formato, escrito pelo `SecurityConfiguration`.
- `server.error.include-message/stacktrace=never`.

## Logs

- Nível `INFO`. Não habilitar `DEBUG`/`TRACE` de `org.springframework.security`,
  `org.springframework.web` ou `org.hibernate.SQL` em produção (podem registrar cabeçalhos,
  parâmetros e valores).
- Nunca registrar senhas, cookies, tokens, CPF/CNPJ ou documentos. Ao registrar exceções de
  infraestrutura, preferir o tipo do erro (ex.: o status de banco registra só a classe da exceção,
  pois a mensagem do driver pode conter host e usuário).
- Não há log de corpo de requisição/resposta nem access log com cabeçalhos.

## OpenAPI

springdoc-openapi gera o documento a partir dos controllers. `contract/openapi.json` é a versão
versionada e conferida em teste. Swagger UI e `/v3/api-docs` só ficam ativos e liberados no profile
`local`.
