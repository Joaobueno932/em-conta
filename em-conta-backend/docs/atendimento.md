# Atendimento via WhatsApp

Primeira jornada: receber mensagem → localizar ou criar a conversa → fila → assumir → ler e
responder → vincular associado existente → transferir → encerrar e consultar o histórico. Nova
mensagem depois do encerramento abre outro atendimento (`AGUARDANDO`) na mesma conversa.

Fora do escopo desta versão (sem botões na interface): envio em massa, respostas rápidas, chatbot/IA,
CRM, tickets/SLA, automações, dashboards, templates, setor, protocolo, etiquetas, gravação pelo
microfone, transcrição e OCR. Anexos: imagem, PDF e áudio (ver [Anexos](#anexos)); outros tipos
aparecem como "não suportado".

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
| Janela de 24 h | resposta livre até o instante exato de 24 h após a última mensagem do contato, inclusive; depois 409 `JANELA_ENCERRADA` (templates estão fora do escopo). Conferida no registro **e** de novo antes de cada tentativa de envio |

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

## Modelo (V6 a V9)

| Tabela | Conteúdo |
|---|---|
| `atd_contato` | contato do WhatsApp (`wa_id` único, nome do perfil) |
| `atd_conversa` | uma por contato; associado vinculado, última entrada (janela de 24 h), `versao` |
| `atd_atendimento` | ciclo `AGUARDANDO → EM_ATENDIMENTO → ENCERRADO`, responsável, `versao`; índice único garante um só ciclo aberto por conversa |
| `atd_mensagem` | entrada/saída; `id_externo`, `correlacao` e `chave_idempotencia` únicos; situação (V7 acrescenta `JANELA_EXPIRADA`), erro, tentativas |
| `atd_mensagem_situacao` | histórico de status de cada mensagem |
| `atd_evento` | assumido, transferido, devolvido, encerrado, associado vinculado/alterado/removido (autor, alvo, motivo) |
| `atd_webhook_evento` | caixa de entrada do webhook (corpo bruto, hash único, situação, tentativas) |
| `atd_anexo` | V8: anexo da mensagem (categoria, MIME, nome higienizado, tamanho, SHA-256, chave opaca do objeto, id da mídia na Meta, situação, erro, tentativas); `atd_mensagem.etapa_falha` diz se a falha foi no UPLOAD ou no ENVIO |
| `atd_objeto_pendente` | V9: diário durável de objetos do armazenamento que podem ter ficado sem dono no recebimento (`GRAVANDO` antes de gravar, `REMOVER` quando excedente), com tentativas, próxima tentativa e erro; `atd_anexo.reserva` guarda o token da reserva vigente |
| `atd_presenca` | presença manual do atendente (Disponível/Ausente; não afeta distribuição) |

## Consistência, eventos e repetição

- **Entrada**: o webhook só valida a assinatura e grava o corpo em `atd_webhook_evento`
  (deduplicado pelo hash) e responde 200. Um processador (`@Scheduled`, 2 s, com
  `FOR UPDATE SKIP LOCKED`, seguro com várias réplicas) interpreta cada evento numa transação.
  Mensagem repetida (mesmo `wamid`) não duplica. Falha → nova tentativa com espera crescente
  (30 s × 2ⁿ); após 5 tentativas fica `FALHA` e pode ser reprocessada
  (`POST …/webhook-eventos/{id}/reprocessamento`). A Meta também reenvia por até 7 dias.
- **Saída (outbox)**: responder grava a mensagem `PENDENTE` com a chave de idempotência enviada pela
  interface. A chave identifica a operação: autor + atendimento + texto normalizado (`strip()`, o mesmo
  texto gravado e enviado). Repetição exata → mesma mensagem (202), sem nova gravação nem reenfileirar,
  inclusive depois de transferência ou encerramento; mesma chave com outro autor, atendimento ou texto →
  409 `CHAVE_IDEMPOTENCIA_REUTILIZADA`, sem revelar a mensagem anterior. A garantia é a restrição única
  `chave_idempotencia`: requisições simultâneas disputam o INSERT; a perdedora relê a vencedora com
  leitura travada (`FOR SHARE`) e aplica a mesma comparação. A interface gera uma chave por texto
  ainda sem confirmação: repetir o mesmo texto após falha de rede reutiliza a chave; texto editado usa
  outra. O processador reserva a mensagem
  (`ENVIANDO`), chama a Graph API **fora de transação** e registra o resultado:
  - janela de 24 h expirada no momento da tentativa (última entrada **atual** do contato, relógio da
    aplicação) → `JANELA_EXPIRADA`, sem chamada à Graph API, sem `erro_codigo` (não é recusa do
    provedor) e sem nova tentativa. Uma mensagem nova do contato antes do processamento renova a janela
    das mensagens ainda pendentes; mensagens já finalizadas não voltam;
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

- Só o responsável atual responde, transfere, devolve à fila e encerra (409 `NAO_E_RESPONSAVEL`), além da
  permissão específica da rota (403). Não há exceção para administrador. Essas ações exigem
  `EM_ATENDIMENTO`: aguardando → 409 `ATENDIMENTO_SEM_RESPONSAVEL` (não se encerra direto da fila);
  encerrado → 409 `ATENDIMENTO_ENCERRADO`; versão antiga → 409 `CONFLITO_CONCORRENTE`. A verificação e a
  gravação acontecem com a linha do atendimento travada (`FOR UPDATE`) na mesma transação; ação recusada
  não altera dados nem grava evento.
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
| `GET atendimentos/{id}` | conversa |
| `GET atendimentos/{id}/linha-do-tempo?somenteEste=&antes=&tamanho=` | página da linha do tempo (ver abaixo) |
| `POST atendimentos/{id}/assuncao` | assumir (204) |
| `POST atendimentos/{id}/mensagens` | responder (`texto`, `chaveIdempotencia`) → 202 |
| `POST atendimentos/{id}/transferencia` | `versao`, `destinoId?`, `motivo?` |
| `POST atendimentos/{id}/encerramento` | `versao` |
| `PUT atendimentos/{id}/associado`, `GET …/associados-sugeridos`, `GET associados?busca=` | vínculo |
| `GET responsaveis` | destinos de transferência |
| `GET historico?busca=&de=&ate=` | histórico geral |
| `GET webhook-falhas`, `POST webhook-eventos/{id}/reprocessamento` | eventos com falha |
| `GET`/`POST /api/v1/integracoes/whatsapp/webhook` | webhook da Meta (fora do contrato OpenAPI) |

| `POST atendimentos/{id}/anexos` | multipart `arquivo`, `legenda?`, `chaveIdempotencia` → 202 (atendimento:responder) |
| `GET atendimentos/{id}/anexos/{anexo}/conteudo?somenteEste=&download=` | conteúdo em streaming, com `Range` |
| `POST atendimentos/{id}/anexos/{anexo}/nova-tentativa` | baixar de novo um anexo recebido em FALHA (responsável) |
| `GET atendimentos/{id}/midia?somenteEste=&categoria=&antes=&tamanho=` | área Mídia, por cursor (1–50) |
| `GET atendimentos/{id}/linha-do-tempo/situacoes?ids=&somenteEste=` | situação atual de até 100 mensagens já carregadas |

### Linha do tempo paginada

Resposta `{ itens, anterior }`: `itens` em ordem cronológica; `anterior` é o cursor para a página
anterior (ausente no início do histórico). Sem `antes`, devolve a página mais recente. `tamanho` 1–100
(padrão 50); cursor inválido → 400 `DADOS_INVALIDOS` (campo `antes`).

Mensagens e eventos entram na mesma ordenação, por (instante de registro, tipo, id): o instante em que
foram gravados, com desempate estável (evento antes de mensagem no mesmo instante, depois o id). O
cursor de cada item é `<microssegundos>.<0|1>.<id>`. Como registros novos têm instante de registro
maior, mensagens que chegam durante a navegação não deslocam as páginas anteriores. O horário exibido
continua sendo `ocorridoEm` (o do WhatsApp, para mensagens recebidas). `somenteEste=true` restringe ao
atendimento selecionado (detalhe do histórico); sem ele, todos os ciclos da conversa.

A interface consulta a página recente a cada 5 s e carrega as anteriores sob demanda ("Carregar
mensagens anteriores", com estados de carregamento, erro com nova tentativa e "Início do histórico").
Páginas já carregadas e itens que saem da página recente continuam na tela; se chegarem mais itens do
que cabem numa página entre duas consultas, a lacuna é buscada por cursor. Ao carregar conteúdo
anterior a posição de leitura é preservada, e a rolagem só desce para mensagens novas quando a pessoa
já está no fim.

## Anexos

Referências oficiais (consultadas em 30/10/2026): [Media](https://developers.facebook.com/docs/whatsapp/cloud-api/reference/media)
(upload `POST /{phone-number-id}/media`, consulta `GET /{media-id}` com `url`, `mime_type`, `sha256`,
`file_size`; URL válida por 5 minutos; download com `Authorization: Bearer`; 404 → consultar de novo),
[Image messages](https://developers.facebook.com/docs/whatsapp/cloud-api/messages/image-messages)
(legenda até 1024 caracteres), [Document messages](https://developers.facebook.com/docs/whatsapp/cloud-api/messages/document-messages)
(`filename` e legenda) e [Audio messages](https://developers.facebook.com/docs/whatsapp/cloud-api/messages/audio-messages)
(sem legenda). Os exemplos atuais da Meta usam a Graph API v26.0; a aplicação usa `WHATSAPP_GRAPH_VERSION`
(padrão v23.0) em todas as chamadas, e a versão deve ser confirmada na homologação.

### Formatos e limites

| Categoria | Formatos (MIME / extensão) | Limite padrão | Limite da Meta |
|---|---|---|---|
| Imagem | `image/jpeg` (.jpg, .jpeg), `image/png` (.png) | 5 MB | 5 MB |
| Documento | `application/pdf` (.pdf) | 16 MB | 100 MB |
| Áudio | `audio/ogg` com Opus (.ogg, .opus), `audio/mpeg` (.mp3), `audio/mp4` (.m4a), `audio/aac` (.aac), `audio/amr` (.amr) | 16 MB | 16 MB |

Cada arquivo é conferido por MIME declarado, extensão (quando há nome), assinatura dos primeiros bytes e
tamanho lido de fato (o limite vale durante a leitura, não só pelo cabeçalho). Nada é convertido.
Documentos do Office, texto, vídeo e figurinhas recebidos ficam visíveis como "não suportado". Os
limites são configuráveis (`emconta.atendimento.anexos.*`) e nunca passam dos da Meta; o teto do
multipart (`EMCONTA_ANEXO_MAXIMO`, padrão 17 MB) precisa acompanhar o maior limite.

O campo `sha256` da Meta não tem codificação documentada; a aplicação aceita hexadecimal ou Base64 e,
se não conferir com o arquivo baixado, o anexo fica em FALHA (não é disponibilizado).

### Recebimento

1. O webhook só valida a assinatura e grava o evento (como antes).
2. O processamento do evento grava a mensagem (`IMAGEM`/`DOCUMENTO`/`AUDIO`, legenda em `texto`) e o
   anexo `PENDENTE` com os metadados (id da mídia, MIME, hash, nome higienizado), na mesma transação.
   Mensagem repetida (mesmo `wamid`) não duplica mensagem nem anexo.
3. `Midias` (mesmo agendador, `SKIP LOCKED`) reserva o anexo e, **fora de transação**, consulta uma
   URL nova pelo id da mídia, baixa para um arquivo temporário com limite, valida e grava no MinIO;
   depois marca `DISPONIVEL`. Falhas temporárias (rede, 5xx, URL expirada, armazenamento) voltam com
   espera crescente (30 s × 2ⁿ) até 5 tentativas; falhas definitivas (tipo divergente, conteúdo
   inválido, hash, limite, destino não autorizado, mídia inexistente) ficam `FALHA` com o motivo.
   Cada reserva tem um token próprio (`atd_anexo.reserva`). Renovação (antes de cada etapa externa),
   conclusão e falha só valem com o token vigente; a recuperação de reservas com mais de 5 min sem
   renovação apaga o token, o que invalida a execução anterior: ela não consegue mais concluir, falhar
   nem mudar tentativas e metadados. Cada etapa externa tem tempo máximo próprio (timeouts da Graph API e
   do armazenamento), bem abaixo dos 5 min, e a renovação entre elas mantém viva a reserva de quem avança. O responsável pode pedir
   "Tentar de novo" de um anexo em FALHA (só baixa de novo; nada é enviado ao contato).

SSRF: o download só vai para HTTPS em host de `WHATSAPP_HOSTS_MIDIA` (padrão `lookaside.fbsbx.com`)
ou para a própria origem configurada da Graph API (que em teste é o servidor controlado);
redirecionamentos não são seguidos, e o token nunca vai para outro destino. URLs temporárias nunca
são gravadas.

### Armazenamento e acesso

**Ambiente local de trabalho:** `bash infra/armazenamento-local.sh` (a partir de `em-conta-backend`)
cria no MinIO do Compose a conta de serviço `emconta-api-local`, com a política
`infra/minio/politica-api.json` (só `GetObject`, `PutObject`, `DeleteObject` e listagem no bucket
`MINIO_BUCKET`), confere que o bucket continua privado e grava `infra/armazenamento.env` (ignorado pelo
Git) com `ARMAZENAMENTO_*`. A API recebe esse arquivo por `--env-file` no `docker run` — o
`infra/.env` só alimenta o Compose. O bucket de trabalho (`emconta-local`) e o de testes e2e
(`emconta-e2e`) são separados; a conta restrita não acessa o de testes nem administra o MinIO. Rodar o
script de novo reaproveita a credencial existente.

- MinIO/S3 privado (`emconta.armazenamento.*`), cliente S3 mínimo com assinatura SigV4 (sem SDK).
  Chave opaca `atendimento/anexos/<uuid>`; o nome original é só metadado de exibição, higienizado
  (sem diretórios, caracteres de controle, marcas de direção de texto nem reservados; até 120).
- Conteúdo só por `GET …/anexos/{anexo}/conteudo` (atendimento:consultar): o anexo precisa pertencer à
  conversa do atendimento informado (ou ao próprio ciclo, com `somenteEste=true`, usado no histórico);
  qualquer outra combinação é 404. Streaming do armazenamento (sem carregar o arquivo na memória),
  `Range` de uma faixa (206) para áudio, `Content-Type` validado, `X-Content-Type-Options: nosniff`,
  `Content-Disposition` com nome higienizado (RFC 6266), `Cache-Control: private, no-store` e CSP
  restritiva (`sandbox` para imagem e áudio). Não há URL assinada nem endereço do MinIO no navegador.

### Envio

1. `POST …/anexos` (multipart; CSRF como as demais rotas): valida chave, legenda (só imagem e PDF,
   até 1024), tipo, extensão, tamanho durante a leitura e assinatura; confere responsável, situação e
   janela antes de gravar qualquer coisa.
2. Idempotência: a chave fica vinculada a autor, atendimento, categoria, SHA-256 do arquivo e legenda
   (sem espaços nas pontas). Repetição exata → a mesma mensagem (sem novo upload nem envio); mesma
   chave em outra operação (inclusive texto) → 409 `CHAVE_IDEMPOTENCIA_REUTILIZADA`. Requisições
   simultâneas disputam a restrição única; a perdedora devolve a vencedora e descarta o próprio objeto.
3. O anexo é registrado como `RECEBENDO`, o objeto é gravado no MinIO e, numa transação com o
   atendimento travado, as regras são conferidas de novo e a mensagem `PENDENTE` é criada. Falha do
   armazenamento → 503 `ARMAZENAMENTO_INDISPONIVEL`, nada registrado.
4. O `Envio` (outbox) revalida a janela na reserva, faz o upload à Meta (`POST /media`, fora de transação; o id
   da mídia fica gravado para não repetir) e envia a mensagem pelo id. Falha no upload: temporária volta
   à fila (até 3), definitiva vira FALHA com `etapaFalha = UPLOAD`; falha no envio, `ENVIO`; resultado
   desconhecido no envio → `DESCONHECIDA`, sem reenvio. Depois de ler o arquivo e do upload, a janela é
   conferida de novo (relógio da aplicação e última entrada atual da conversa) imediatamente antes da
   chamada que envia a mensagem; se expirou, a mensagem fica `JANELA_EXPIRADA` com o motivo "o arquivo
   foi carregado no WhatsApp, mas nenhuma mensagem foi enviada", sem nova tentativa, e o id da mídia
   fica gravado. Resta só o intervalo inevitável entre essa conferência e a requisição. Upload concluído não é entrega: valem ACEITA,
   ENVIADA, ENTREGUE e LIDA pelo webhook.

### Arquivos órfãos e uploads abandonados

- **Recebimento:** antes de gravar o objeto, a execução registra no diário `atd_objeto_pendente` a
  chave e o seu token (`GRAVANDO`), na mesma transação que confere que a reserva ainda é dela. A
  conclusão vincula a chave ao anexo e apaga a linha na mesma transação; se a reserva foi perdida, a
  linha vira `REMOVER` (upsert: volta mesmo se a limpeza já a tiver apagado) e a remoção é tentada na
  hora. Queda entre gravar e concluir: a linha `GRAVANDO` fica e, depois de 6 min, a limpeza confere.
- **Limpeza** (`Midias.limparObjetosPendentes`, no mesmo agendador, 20 por ciclo): reivindica linhas
  vencidas com `SKIP LOCKED`, adia a próxima tentativa (30 s, 1 min, 2 min… até 1 h) antes de soltar a
  trava e remove fora de transação. Só remove se nenhum anexo aponta para a chave e, para `GRAVANDO`,
  se o token não é mais o vigente; um token apagado nunca volta, então aquela execução não consegue
  mais vincular a chave. Objeto vinculado sai do diário sem ser removido. Remover objeto inexistente
  conta como sucesso; falha fica com `erro` e é tentada de novo. Nenhuma varredura do bucket inteiro.
- **Upload do atendente:** a linha `RECEBENDO` é gravada antes do objeto. A limpeza de uploads
  abandonados (mais de `upload-abandonado`, padrão 1 h) primeiro reivindica a linha
  (`RECEBENDO → FALHA`, condicional), o que impede a vinculação a uma mensagem (que passa a recusar com
  503 `UPLOAD_EXPIRADO`); depois remove o objeto e apaga a linha. Falha na remoção: a linha `FALHA` sem
  mensagem volta no ciclo seguinte. Anexos com mensagem nunca entram nessa limpeza.

### Atualização de situações na tela

A página recente da linha do tempo é consultada a cada 5 s; as mensagens já carregadas fora dela e
ainda sujeitas a mudança (PENDENTE, ENVIANDO, ACEITA, ENVIADA, ENTREGUE, DESCONHECIDA ou anexo em
processamento) são conferidas em lote por `…/linha-do-tempo/situacoes` (até 100 por consulta, uma
consulta por ciclo, sem sobreposição), em **rodízio**: cada lote começa onde o anterior parou e volta
ao início no fim, então toda mensagem elegível é conferida em no máximo ⌈n / 100⌉ ciclos, mesmo que as
mais novas continuem elegíveis (ENTREGUE ainda vira LIDA; DESCONHECIDA pode ser reconciliada). A tela
mantém a versão com `atualizadoEm` mais novo, sem recarregar páginas nem mudar a posição de leitura; o
rodízio recomeça ao trocar de conversa.

Lacunas: se entre duas consultas chegarem mais mensagens do que cabem na página recente, o intervalo
que faltou vira uma lacuna explícita (cursor de continuação e o último item já carregado). Ela é
recuperada em até 20 páginas por ciclo; cada página bem-sucedida avança o cursor, então falha de rede
ou o limite por ciclo não perdem o progresso nem o restante. A falha aparece na conversa com "Tentar de
novo", e a próxima consulta periódica também retoma; lacunas de outra conversa são descartadas.

### Busca pelo associado

Fila e histórico também encontram pelo nome do associado vinculado (nome da PF ou razão social da
PJ, campo `nome` do cadastro), sem corte: o módulo de associados fornece o critério como projeção de
ids (`CatalogoAssociados.criterioPorNome`, uma subconsulta só de ids que ele mesmo monta), e o
atendimento a usa em `associado_id in (...)`. O banco resolve a semijunção; não há limite de ids, carga
de associados em memória, junção de linhas (sem duplicar atendimentos) nem exposição de documento.
Total, páginas e ordenação continuam os mesmos da consulta.

## Configurar e homologar

1. Preencher as variáveis acima no `.env` do ambiente (nunca em arquivo versionado) e reiniciar a API.
   `GET /api/v1/atendimento/canal` passa a `configurado: true`.
2. Publicar a URL `https://<host>/api/v1/integracoes/whatsapp/webhook` (HTTPS válido) e cadastrá-la
   no app da Meta com o `WHATSAPP_VERIFY_TOKEN`; assinar o campo `messages`.
3. Do celular de teste, enviar mensagem ao número de teste → aparece em **Aguardando**.
4. Assumir, responder, conferir `Aceita` → `Enviada`/`Entregue`/`Lida` conforme os status da Meta.
5. Conferir a janela de 24 h, a transferência, o encerramento e a nova mensagem após encerrar.

Anexos, na homologação: preencher `ARMAZENAMENTO_*` (bucket privado; credencial restrita ao bucket);
conferir o host das URLs de mídia devolvidas pela Meta e ajustar `WHATSAPP_HOSTS_MIDIA` se for outro;
enviar do celular de teste uma imagem JPEG/PNG, um PDF e um áudio (nota de voz OGG/Opus) e conferir
download, hash e reprodução; responder com imagem com legenda, PDF e áudio e acompanhar
ACEITA → ENTREGUE/LIDA; conferir a versão da Graph API.

A homologação com o canal real **ainda não foi feita**: depende do acesso à Meta e do número de teste.

## Testes

- `AtendimentoIntegrationTests`, `TransicoesAtendimentoIntegrationTests`,
  `RespostaEEnvioIntegrationTests`, `LinhaDoTempoIntegrationTests` (base `AtendimentoIntegracao`) e
  `CanalNaoConfiguradoIntegrationTests`: MySQL real (Testcontainers) e um servidor HTTP **controlado, de
  teste** (`GraphApiControlada`) no lugar da Graph API, com webhooks assinados com segredo fictício. O
  relógio da aplicação é controlado pelo teste (`RelogioControlado`) para a janela de 24 h, sem esperas.
- `AnexosIntegrationTests`: MinIO real (mesma imagem do Compose, via Testcontainers) e a Graph
  controlada reproduzindo consulta, download e upload de mídia: recebimento de imagem/PDF/áudio,
  deduplicação e concorrência, URL expirada, timeout, mídia inexistente, conteúdo/MIME/hash/limite
  inválidos, destino e redirecionamento não autorizados, permissões e relação anexo↔atendimento,
  envio pelo responsável, idempotência, janela, resultado desconhecido, falha do armazenamento,
  limpeza de uploads abandonados, Mídia por ciclo, busca por associado e situações em lote.
- Frontend: `src/features/atendimento/__tests__` (Vitest) e `e2e/atendimento.spec.ts` (Playwright),
  este contra a API de testes do banco isolado, também com Graph controlada local
  (`e2e/whatsapp-controlado.ts`). Nada disso é envio ou recebimento real.
