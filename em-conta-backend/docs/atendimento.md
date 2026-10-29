# Atendimento via WhatsApp

Primeira jornada: receber mensagem → localizar ou criar a conversa → fila → assumir → ler e
responder → vincular associado existente → transferir → encerrar e consultar o histórico. Nova
mensagem depois do encerramento abre outro atendimento (`AGUARDANDO`) na mesma conversa.

Fora do escopo desta versão (sem botões na interface): envio em massa, respostas rápidas, chatbot/IA,
CRM, tickets/SLA, automações, dashboards, templates, setor, protocolo, etiquetas e **anexos**
(US-ATE-04 parcial: mensagens que não são texto aparecem como "não suportada"; a área "Mídia" do
painel informa que anexos ainda não estão disponíveis).

## Provedor

Integração direta com a **WhatsApp Cloud API** (Meta), sem intermediário. Contrato seguido conforme
a documentação oficial:

| Ponto | Implementação |
|---|---|
| Verificação do webhook | `GET …/webhook?hub.mode=subscribe&hub.verify_token=…&hub.challenge=…` devolve o `challenge` só se o token confere (comparação em tempo constante); senão 403 |
| Autenticidade | `X-Hub-Signature-256: sha256=<HMAC-SHA256 do corpo bruto com o App Secret>`; sem assinatura válida → 401 e nada é gravado |
| Notificações | `entry[].changes[].value` com `metadata.phone_number_id`, `contacts`, `messages`, `statuses`; eventos de outro `phone_number_id` são ignorados (contados) |
| Envio | `POST {graph-url}/{versão}/{phone-number-id}/messages` (texto), `Authorization: Bearer`; resposta `messages[].id` + `message_status: accepted` |
| Correlação | `biz_opaque_callback_data` = correlação interna da mensagem, para casar status mesmo sem o `wamid` |
| Janela de 24 h | resposta livre só até 24 h após a última mensagem do contato; depois 409 `JANELA_ENCERRADA` (templates estão fora do escopo) |

### Variáveis de ambiente

Documentadas em `infra/.env.example`, **sem valores**. Sem as quatro primeiras o canal fica
"não configurado": a interface mostra **Canal não configurado**, o envio é recusado (503
`CANAL_NAO_CONFIGURADO`) e o webhook recusa verificação (403) e eventos (503). Não existe envio
fictício.

| Variável | Uso |
|---|---|
| `WHATSAPP_ACCESS_TOKEN` | token de acesso (usuário de sistema) para a Graph API |
| `WHATSAPP_PHONE_NUMBER_ID` | identificador do número de teste/homologação na Meta |
| `WHATSAPP_APP_SECRET` | segredo do app, valida `X-Hub-Signature-256` |
| `WHATSAPP_VERIFY_TOKEN` | token escolhido por nós e informado na Meta ao cadastrar o webhook |
| `WHATSAPP_GRAPH_URL` | padrão `https://graph.facebook.com` (só se troca em teste controlado) |
| `WHATSAPP_GRAPH_VERSION` | padrão `v23.0` |

O número oficial continua no GDASH e **não** deve ser usado aqui.

## Modelo (V6)

| Tabela | Conteúdo |
|---|---|
| `atd_contato` | contato do WhatsApp (`wa_id` único, nome do perfil) |
| `atd_conversa` | uma por contato; associado vinculado, última entrada (janela de 24 h), `versao` |
| `atd_atendimento` | ciclo `AGUARDANDO → EM_ATENDIMENTO → ENCERRADO`, responsável, `versao`; índice único garante um só ciclo aberto por conversa |
| `atd_mensagem` | entrada/saída; `id_externo`, `correlacao` e `chave_idempotencia` únicos; situação, erro, tentativas |
| `atd_mensagem_situacao` | histórico de status de cada mensagem |
| `atd_evento` | assumido, transferido, devolvido, encerrado, associado vinculado/alterado/removido (autor, alvo, motivo) |
| `atd_webhook_evento` | caixa de entrada do webhook (corpo bruto, hash único, situação, tentativas) |
| `atd_presenca` | presença manual do atendente (Disponível/Ausente; não afeta distribuição) |

## Consistência, eventos e repetição

- **Entrada**: o webhook só valida a assinatura e grava o corpo em `atd_webhook_evento`
  (deduplicado pelo hash) e responde 200. Um processador (`@Scheduled`, 2 s, com
  `FOR UPDATE SKIP LOCKED`, seguro com várias réplicas) interpreta cada evento numa transação.
  Mensagem repetida (mesmo `wamid`) não duplica. Falha → nova tentativa com espera crescente
  (30 s × 2ⁿ); após 5 tentativas fica `FALHA` e pode ser reprocessada
  (`POST …/webhook-eventos/{id}/reprocessamento`). A Meta também reenvia por até 7 dias.
- **Saída (outbox)**: responder grava a mensagem `PENDENTE` com a chave de idempotência enviada pela
  interface (repetir o clique após falha de rede não duplica). O processador reserva a mensagem
  (`ENVIANDO`), chama a Graph API **fora de transação** e registra o resultado:
  - aceita → `ACEITA` (não é "entregue");
  - erro temporário (131000, 130429, 131056, 4, 80007, 131016, 2, HTTP 5xx, falha de conexão) →
    nova tentativa, até 3;
  - erro definitivo (ex.: 131026) → `FALHA` com código e título;
  - tempo esgotado ou conexão interrompida depois do envio → `DESCONHECIDA`, **sem reenvio
    automático** (a mensagem pode ter saído); a interface pede para confirmar com o contato;
  - reserva `ENVIANDO` com mais de 2 min (queda da aplicação) → `DESCONHECIDA`.
- **Status**: `sent`/`delivered`/`read`/`failed` chegam pelo webhook, casados por `wamid` ou pela
  correlação. Fora de ordem não regride (ordem ACEITA < ENVIADA < ENTREGUE < LIDA; `failed` só vale
  antes de ENTREGUE). Todo status fica em `atd_mensagem_situacao`.
- **Concorrência**: assumir é um `UPDATE` condicional (dois atendentes ao mesmo tempo → um recebe 409
  `ATENDIMENTO_JA_ASSUMIDO`); transferir, encerrar e vincular usam `versao` (409
  `CONFLITO_CONCORRENTE`).
- **Atualização da tela**: consulta periódica a cada 5 s (TanStack Query). Escolhido em vez de SSE por
  funcionar igual com várias réplicas e com a sessão revogada (o 401 leva ao login).

## Regras

- Só o responsável responde (409 `NAO_E_RESPONSAVEL`), transfere e encerra.
- Transferência direta, motivo opcional; sem destino devolve à fila. O destino precisa estar ativo e
  ter `atendimento:responder`.
- Vincular associado é escolha do atendente: o telefone só **sugere** (com ou sem DDI 55); vários
  associados com o mesmo telefone aparecem todos. Trocar ou remover exige motivo.
- Texto até 4096 caracteres.

## Endpoints

Base `/api/v1/atendimento`; permissões em [seguranca.md](seguranca.md#autorização).

| Método e caminho | Uso |
|---|---|
| `GET canal` | canal configurado ou não |
| `GET`/`PUT presenca` | presença manual |
| `GET atendimentos?visao=AGUARDANDO\|EM_ANDAMENTO\|MEUS\|ENCERRADOS&busca=&pagina=&tamanho=` | fila com contagens |
| `GET atendimentos/{id}`, `GET atendimentos/{id}/linha-do-tempo?somenteEste=` | conversa |
| `POST atendimentos/{id}/assuncao` | assumir (204) |
| `POST atendimentos/{id}/mensagens` | responder (`texto`, `chaveIdempotencia`) → 202 |
| `POST atendimentos/{id}/transferencia` | `versao`, `destinoId?`, `motivo?` |
| `POST atendimentos/{id}/encerramento` | `versao` |
| `PUT atendimentos/{id}/associado`, `GET …/associados-sugeridos`, `GET associados?busca=` | vínculo |
| `GET responsaveis` | destinos de transferência |
| `GET historico?busca=&de=&ate=` | histórico geral |
| `GET webhook-falhas`, `POST webhook-eventos/{id}/reprocessamento` | eventos com falha |
| `GET`/`POST /api/v1/integracoes/whatsapp/webhook` | webhook da Meta (fora do contrato OpenAPI) |

## Configurar e homologar

1. Preencher as variáveis acima no `.env` do ambiente (nunca em arquivo versionado) e reiniciar a API.
   `GET /api/v1/atendimento/canal` passa a `configurado: true`.
2. Publicar a URL `https://<host>/api/v1/integracoes/whatsapp/webhook` (HTTPS válido) e cadastrá-la
   no app da Meta com o `WHATSAPP_VERIFY_TOKEN`; assinar o campo `messages`.
3. Do celular de teste, enviar mensagem ao número de teste → aparece em **Aguardando**.
4. Assumir, responder, conferir `Aceita` → `Enviada`/`Entregue`/`Lida` conforme os status da Meta.
5. Conferir a janela de 24 h, a transferência, o encerramento e a nova mensagem após encerrar.

A homologação com o canal real **ainda não foi feita**: depende do acesso à Meta e do número de teste.

## Testes

- `AtendimentoIntegrationTests` / `CanalNaoConfiguradoIntegrationTests`: MySQL real (Testcontainers)
  e um servidor HTTP **controlado, de teste** (`GraphApiControlada`) no lugar da Graph API, com
  webhooks assinados com segredo fictício.
- Frontend: `src/features/atendimento/__tests__` (Vitest) e `e2e/atendimento.spec.ts` (Playwright),
  este contra a API de testes do banco isolado, também com Graph controlada local
  (`e2e/whatsapp-controlado.ts`). Nada disso é envio ou recebimento real.
