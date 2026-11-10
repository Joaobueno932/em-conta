# Atendimento via WhatsApp

Primeira jornada: receber mensagem → localizar ou criar a conversa → fila → assumir → ler e
responder → vincular associado existente → transferir → encerrar e consultar o histórico. Nova
mensagem depois do encerramento abre outro atendimento (`AGUARDANDO`) na mesma conversa.

Fora do escopo desta versão (sem botões na interface): envio em massa, respostas rápidas, chatbot/IA,
CRM, tickets/SLA, automações, dashboards, templates, setor, protocolo, etiquetas, gravação pelo
microfone, transcrição e OCR. Anexos: imagem, PDF, planilha XLSX, vídeo e áudio (ver [Anexos](#anexos));
outros tipos aparecem como "não suportado", com o motivo e o que é aceito.

## Provedor

Integração direta com a **WhatsApp Cloud API** (Meta), sem intermediário. Contrato seguido conforme
a documentação oficial:

| Ponto | Implementação |
|---|---|
| Verificação do webhook | `GET …/webhook?hub.mode=subscribe&hub.verify_token=…&hub.challenge=…` devolve o `challenge` só se o token confere (comparação em tempo constante); senão 403 |
| Autenticidade | `X-Hub-Signature-256: sha256=<HMAC-SHA256 do corpo bruto com o App Secret>`; sem assinatura válida → 401 e nada é gravado |
| Notificações | `entry[].changes[].value` com `metadata.phone_number_id`, `contacts`, `messages`, `statuses` |
| Escopo | depois da assinatura, só mudanças `messages` do `phone_number_id` configurado e, com `WHATSAPP_WABA_ID`, da WABA (`entry[].id`) são gravadas. Tudo no escopo: o corpo bruto, intacto. Lote misto: só as entradas e mudanças do escopo. Nada no escopo (outro número, outra WABA, outro campo, JSON inválido): 200 sem gravar nada, para a Meta não reentregar; o log conta as mudanças descartadas, sem conteúdo |
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
| `WHATSAPP_GRAPH_VERSION` | padrão `v23.0` (disponível até 08/10/2027, conforme o changelog de versões da Graph API) |
| `WHATSAPP_WABA_ID` | opcional; WABA do número. Com ela, eventos de outra WABA também são descartados no webhook |

O número oficial continua no GDASH e **não** deve ser usado aqui.

## Modelo (V6 a V12)

| Tabela | Conteúdo |
|---|---|
| `atd_contato` | contato do WhatsApp (`wa_id` único, nome do perfil) |
| `atd_conversa` | uma por contato; associado vinculado, última entrada (janela de 24 h), `versao` |
| `atd_atendimento` | ciclo `AGUARDANDO → EM_ATENDIMENTO → ENCERRADO`, responsável, `versao`; índice único garante um só ciclo aberto por conversa |
| `atd_mensagem` | entrada/saída; `id_externo`, `correlacao` e `chave_idempotencia` únicos; situação (V7 acrescenta `JANELA_EXPIRADA`), erro, tentativas |
| `atd_mensagem_situacao` | histórico de status de cada mensagem |
| `atd_evento` | assumido, transferido, devolvido, encerrado, associado vinculado/alterado/removido (autor, alvo, motivo) |
| `atd_webhook_evento` | caixa de entrada do webhook (corpo autenticado e recortado ao escopo do canal, hash único, situação, tentativas) |
| `atd_anexo` | V8: anexo da mensagem (categoria — V11 acrescenta `VIDEO` —, MIME, nome higienizado, tamanho, SHA-256, chave opaca do objeto, id da mídia na Meta, situação, erro, tentativas); `atd_mensagem.etapa_falha` diz se a falha foi no UPLOAD ou no ENVIO |
| `atd_objeto_pendente` | V9: diário durável de objetos do armazenamento que podem ter ficado sem dono no recebimento (`GRAVANDO` antes de gravar, `REMOVER` quando excedente), com tentativas, próxima tentativa e erro; `atd_anexo.reserva` guarda o token da reserva vigente |
| `atd_presenca` | presença manual do atendente (Disponível/Ausente; não afeta distribuição) |
| `atd_sinal` | V12: contador de mudanças por conversa, incrementado depois de cada commit que altera a linha do tempo; só sinal, sem conteúdo (ver [Atualização em tempo real](#atualização-em-tempo-real)) |

## Consistência, eventos e repetição

- **Entrada**: o webhook só valida a assinatura e grava o corpo em `atd_webhook_evento`
  (deduplicado pelo hash) e responde 200. Um processador (`@Scheduled`, 2 s, com
  `FOR UPDATE SKIP LOCKED`, seguro com várias réplicas) interpreta cada evento numa transação; além
  do ciclo, ele roda na hora depois que algo entra na fila na mesma instância (evento gravado,
  resposta ou anexo registrado, nova tentativa de download), numa thread própria que agrupa pedidos
  simultâneos.
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
- **Atualização da tela**: a conversa aberta recebe avisos de mudança por SSE e relê na hora (ver
  [Atualização em tempo real](#atualização-em-tempo-real)); a consulta periódica fica como rede de
  segurança (30 s com avisos, 5 s sem eles e nas demais telas).
- **Indicador no balão enviado** (`IndicadorSituacao.vue`, ao lado do horário, com rótulo acessível e
  tooltip; o mesmo na conversa e no detalhe do histórico): relógio para `PENDENTE`, `ENVIANDO` e
  `ACEITA` (aceite é só da requisição, ainda sem confirmação de envio), um tique cinza para `ENVIADA`,
  dois cinza para `ENTREGUE`, dois azuis (`--cor-lida`) para `LIDA`, exclamação vermelha para `FALHA` e
  `JANELA_EXPIRADA` e, para `DESCONHECIDA`, "Envio não confirmado" com ícone próprio. Falha e
  resultado desconhecido abrem a causa num detalhe (clique, toque ou teclado; Esc fecha). Mostra só a
  situação atual: a ordem acima e a mescla da linha do tempo (`maisNovo`) impedem a regressão.
- **Digitando / gravando áudio** (documentação oficial conferida em 09/11/2026: [Typing
  indicators](https://developers.facebook.com/docs/whatsapp/cloud-api/typing-indicators) e o webhook
  `messages`), as quatro direções:

  | Direção | Suporte na Cloud API | No Em Conta |
  |---|---|---|
  | Cliente digitando → Em Conta | não existe evento no webhook (o campo `messages` traz só mensagens, status e erros) | não exibido; nada é simulado |
  | Atendente digitando → WhatsApp do cliente | `typing_indicator` do tipo `text`, **só junto com `status: read` de uma mensagem recebida** (`message_id` obrigatório); some com a resposta ou em 25 s | implementado (abaixo) |
  | Cliente gravando áudio → Em Conta | não existe evento | não exibido; um áudio recebido é só a chegada do arquivo |
  | Atendente gravando áudio → WhatsApp do cliente | não existe tipo de indicador de gravação (só `text`); o Em Conta também não grava pelo microfone | não implementado |

  **"Digitando…" para o cliente** (`POST …/digitando`, atendimento:responder): o compositor pede ao
  digitar texto de fato no campo (não ao preencher por código nem com anexo selecionado), no máximo
  uma vez a cada 20 s; o servidor confere as regras de resposta (responsável, em atendimento, janela de
  24 h) e repete o limite por atendimento; uma resposta registrada libera o próximo pedido. O indicador
  vai junto com a leitura da **última mensagem recebida neste atendimento**, como a Meta exige: o
  cliente passa a ver essa mensagem com os tiques azuis de lida. No Em Conta nada muda (não há estado
  de leitura das mensagens recebidas) e a situação das mensagens enviadas continua vindo só dos status
  da Meta. Sem mensagem recebida no atendimento, nada é pedido. Para não enviar confirmações de
  leitura, desligue com `EMCONTA_ATENDIMENTO_DIGITANDO_HABILITADO=false`. Falha do indicador só vai
  para o log (sem conteúdo).

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
| `POST atendimentos/{id}/digitando` | "digitando…" no WhatsApp do cliente, com a leitura da última recebida (204; até 1 pedido a cada 20 s) |
| `POST atendimentos/{id}/transferencia` | `versao`, `destinoId?`, `motivo?` |
| `POST atendimentos/{id}/encerramento` | `versao` |
| `PUT atendimentos/{id}/associado`, `GET …/associados-sugeridos`, `GET associados?busca=` | vínculo |
| `GET responsaveis` | destinos de transferência |
| `GET historico?busca=&de=&ate=` | histórico geral (na tela, busca, período e página ficam na URL; o detalhe recebe a origem em `volta` e só aceita o próprio histórico) |
| `GET webhook-falhas`, `POST webhook-eventos/{id}/reprocessamento` | eventos com falha |
| `GET`/`POST /api/v1/integracoes/whatsapp/webhook` | webhook da Meta (fora do contrato OpenAPI) |

| `POST atendimentos/{id}/anexos` | multipart `arquivo`, `legenda?`, `chaveIdempotencia` → 202 (atendimento:responder) |
| `GET atendimentos/{id}/anexos/{anexo}/conteudo?somenteEste=&download=` | conteúdo em streaming, com `Range` |
| `POST atendimentos/{id}/anexos/{anexo}/nova-tentativa` | baixar de novo um anexo recebido em FALHA (responsável) |
| `GET atendimentos/{id}/midia?somenteEste=&categoria=&antes=&tamanho=` | área Mídia, por cursor (1–50) |
| `GET atendimentos/{id}/linha-do-tempo/situacoes?ids=&somenteEste=` | situação atual de até 100 mensagens já carregadas |
| `GET atendimentos/{id}/eventos` | avisos de mudança da conversa (SSE, `text/event-stream`; atendimento:consultar) |

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

A interface relê a página recente a cada aviso de mudança (e a cada 30 s, ou 5 s sem avisos) e carrega as anteriores sob demanda ("Carregar
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
(`filename` e legenda), [Video messages](https://developers.facebook.com/docs/whatsapp/cloud-api/messages/video-messages)
(legenda) e [Audio messages](https://developers.facebook.com/docs/whatsapp/cloud-api/messages/audio-messages)
(sem legenda). Formatos e limites conferidos de novo em 09/11/2026 na tabela "Supported media types". Os exemplos atuais da Meta usam a Graph API v26.0; a aplicação usa `WHATSAPP_GRAPH_VERSION`
(padrão v23.0) em todas as chamadas, e a versão deve ser confirmada na homologação.

### Formatos e limites

| Categoria | Formatos (MIME / extensão) | Limite padrão | Limite da Meta |
|---|---|---|---|
| Imagem | `image/jpeg` (.jpg, .jpeg), `image/png` (.png) | 5 MB | 5 MB |
| Documento | `application/pdf` (.pdf), `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` (.xlsx) | 16 MB | 100 MB |
| Vídeo | `video/mp4` (.mp4), `video/3gpp` (.3gp); só vídeo H.264 e áudio AAC, no máximo uma faixa de áudio | 16 MB | 16 MB |
| Áudio | `audio/ogg` com Opus (.ogg, .opus), `audio/mpeg` (.mp3), `audio/mp4` (.m4a), `audio/aac` (.aac), `audio/amr` (.amr) | 16 MB | 16 MB |

Cada arquivo é conferido por MIME declarado, extensão (quando há nome), assinatura dos primeiros bytes e
tamanho lido de fato (o limite vale durante a leitura, não só pelo cabeçalho). Nada é convertido.

- **Planilha** (nos dois sentidos): o pacote ZIP é conferido pelo diretório central, sem descompactar
  nada: exige `[Content_Types].xml` e `xl/workbook.xml`, recusa projeto de macros (`xl/vbaProject.bin`,
  um XLSM renomeado) e pacotes com mais de 10 000 itens.
- **Vídeo enviado**: os cabeçalhos ISO BMFF (`moov → trak → mdia → hdlr/stsd`) são lidos pulando os
  dados de mídia; a faixa de vídeo precisa ser H.264 (`avc1`/`avc3`) e o áudio, se houver, uma única
  faixa AAC (`mp4a`) — a regra da Meta, que de outro modo recusaria o arquivo só depois do upload. O
  motivo da recusa diz o codec encontrado ("este arquivo usa H.265 (HEVC)"). **MP4 fragmentado**
  (`mvex` no `moov` ou `moof` no topo, como o gravado pelo MediaRecorder dos navegadores) é recusado ao
  anexar, antes de qualquer upload: "Este vídeo está em um formato incompatível com o envio pelo
  WhatsApp. Exporte como MP4 padrão e tente novamente." Na homologação, a Meta aceitou a requisição de um
  MP4 fragmentado e o marcou como falho (#131053), enquanto o mesmo conteúdo em MP4 convencional foi
  entregue (`EstruturaVideoTests` usa os dois arquivos). Vídeo recebido vem da própria Meta: valem
  assinatura, MIME, tamanho e hash, sem essa regra.

Word, PowerPoint, XLS, texto e figurinhas recebidos ficam visíveis como "não suportado", com o motivo
e o que a categoria aceita (ex.: "o Em Conta aceita PDF e planilha XLSX"). Os
limites são configuráveis (`emconta.atendimento.anexos.*`) e nunca passam dos da Meta; o teto do
multipart (`EMCONTA_ANEXO_MAXIMO`, padrão 17 MB) precisa acompanhar o maior limite.

O campo `sha256` da Meta não tem codificação documentada; a aplicação aceita hexadecimal ou Base64 e,
se não conferir com o arquivo baixado, o anexo fica em FALHA (não é disponibilizado).

### Recebimento

1. O webhook só valida a assinatura e grava o evento (como antes).
2. O processamento do evento grava a mensagem (`IMAGEM`/`DOCUMENTO`/`AUDIO`/`VIDEO`, legenda em `texto`) e o
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
`MINIO_BUCKET`) e grava `infra/armazenamento.env` (ignorado pelo Git) com `ARMAZENAMENTO_*`. A API
recebe esse arquivo por `--env-file` no `docker run` — o `infra/.env` só alimenta o Compose. O bucket
de trabalho (`emconta-local`) e o de testes e2e (`emconta-e2e`) são separados. O script:

- para antes de qualquer alteração se o bucket tiver acesso anônimo (não é privado), com mensagem
  explicando o que conferir; ele nunca muda a política anônima;
- trata falha ao vincular a política como erro (só "já vinculada" é aceito) e confere em seguida que
  a conta tem exatamente a política `emconta-api-local`;
- com a credencial restrita, confere que lista o bucket de trabalho e que **não** acessa o de testes
  nem a administração do MinIO;
- só depois grava o arquivo, de forma atômica (arquivo temporário + `mv`): falha em qualquer etapa
  mantém o arquivo anterior intacto. Rodar de novo reaproveita a credencial existente. Segredos não
  aparecem na saída nem na linha de comando (vão pela entrada padrão do `docker exec`).

- MinIO/S3 privado (`emconta.armazenamento.*`), cliente S3 mínimo com assinatura SigV4 (sem SDK).
  Chave opaca `atendimento/anexos/<uuid>`; o nome original é só metadado de exibição, higienizado
  (sem diretórios, caracteres de controle, marcas de direção de texto nem reservados; até 120).
- Conteúdo só por `GET …/anexos/{anexo}/conteudo` (atendimento:consultar): o anexo precisa pertencer à
  conversa do atendimento informado (ou ao próprio ciclo, com `somenteEste=true`, usado no histórico);
  qualquer outra combinação é 404. Streaming do armazenamento (sem carregar o arquivo na memória),
  `Range` de uma faixa (206) para áudio, `Content-Type` validado, `X-Content-Type-Options: nosniff`,
  `Content-Disposition` com nome higienizado (RFC 6266), `Cache-Control: private, no-store` e CSP
  restritiva (`sandbox` para imagem, áudio, vídeo e planilha). Planilha sai sempre como download
  (`attachment`), mesmo sem `download=true`: o navegador não a exibe. Vídeo usa `Range` como o áudio.
  Não há URL assinada nem endereço do MinIO no navegador.

### Envio

1. `POST …/anexos` (multipart; CSRF como as demais rotas): valida chave, legenda (imagem, documento e
   vídeo, até 1024), tipo, extensão, tamanho durante a leitura, assinatura e estrutura (planilha e
   codecs do vídeo); confere responsável, situação e
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

### Atualização em tempo real

Antes, a tela dependia só da consulta a cada 5 s. Medido na homologação com a Meta (09/11/2026, 17
eventos reais): a Meta levou de 1,3 a 4,2 s do evento até o webhook (parte dela); do webhook ao
processamento foram de 0,1 a 2,0 s (ciclo de 2 s do processador); do processamento até a tela, de 0 a
5 s (consulta periódica). Como `sent` e `delivered` costumam chegar no mesmo segundo, a tela pulava os
estados intermediários.

- **Processamento imediato**: gravar um evento do webhook (ou registrar resposta, anexo ou nova
  tentativa) acorda o processador da mesma instância depois do commit; o ciclo de 2 s continua para o
  que outras instâncias gravaram.
- **Sinal por conversa** (`AvisosAtendimento`): cada commit que muda algo visível na linha do tempo
  (mensagem recebida, situação de envio, ENVIANDO/ACEITA/FALHA/DESCONHECIDA/JANELA_EXPIRADA, anexo
  em processamento/disponível/falha, assumir, transferir, devolver, encerrar, vínculo) incrementa
  `atd_sinal` numa transação própria, **depois** da confirmação. Rollback não sinaliza. Falha ao
  sinalizar só atrasa a tela até a consulta de segurança.
- **Entrega** (`GET …/eventos`, `NotificadorConversas`): cada instância da API guarda as conexões SSE
  das conversas abertas nela e, a cada 300 ms (e na hora, quando a mudança foi nesta instância), lê
  só os contadores dessas conversas pela chave primária. Contador diferente → evento `mudanca` (só o
  contador, nenhum conteúdo) para quem assina aquela conversa. Ao conectar chega `pronto`. Funciona com
  várias réplicas e com o processamento no worker, porque o sinal está no banco. Cada conexão dura até
  5 min (o navegador reconecta sozinho); um comentário a cada 20 s mantém proxies sem cortar a conexão;
  `X-Accel-Buffering: no` impede o Nginx de acumular o fluxo.
- **Permissão**: a rota exige `atendimento:consultar` e um atendimento existente (404). O aviso não traz
  dados: a tela relê pela linha do tempo e pelo detalhe, com as permissões de sempre. Sessão expirada
  ou revogada: a reconexão é recusada e a consulta periódica recebe 401, que leva ao login.
- **Tela** (`use-eventos-conversa.ts`): a cada `pronto` ou `mudanca` relê a página recente e o
  detalhe; avisos durante uma leitura em andamento viram uma releitura depois dela. Trocar de conversa
  fecha a assinatura anterior (aviso atrasado dela é ignorado). Recusa da conexão: nova tentativa em
  2, 5, 15 e 30 s, e a consulta volta a 5 s até os avisos voltarem.
- **Balão na hora do envio**: o compositor mostra a resposta (texto ou anexo) com relógio assim que a
  pessoa envia. A identidade da operação é a chave de idempotência, que a linha do tempo devolve só nas
  mensagens de quem consulta (`chaveIdempotencia`): quando o registro do servidor chega (pelo aviso ou
  pela resposta do envio, o que vier primeiro), ele substitui o balão local, sem aparecerem os dois.
  Falha sem registro tira o balão e devolve o texto ao campo (mesma chave ao tentar de novo).
- **Ícones**: a mesma regra de antes (relógio para PENDENTE/ENVIANDO/ACEITA, um tique cinza para
  ENVIADA, dois cinza para ENTREGUE, dois azuis para LIDA, exclamação para FALHA, "Envio não
  confirmado" para DESCONHECIDA). Confirmação atrasada não regride: o servidor só avança a situação e a
  tela mantém a versão com `atualizadoEm` mais novo.

As mensagens já carregadas fora da página recente e
ainda sujeitas a mudança (PENDENTE, ENVIANDO, ACEITA, ENVIADA, ENTREGUE, DESCONHECIDA ou anexo em
processamento) são conferidas em lote por `…/linha-do-tempo/situacoes` (até 100 por consulta, uma
consulta por leitura da página recente, sem sobreposição), em **rodízio**: cada lote começa onde o anterior parou e volta
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

## Respostas rápidas (V10)

Catálogo da equipe com textos internos para inserir no campo de resposta. **Não são templates
(modelos de mensagem) aprovados pela Meta**: não passam por aprovação, não liberam envio fora da
janela de 24 h e não enviam nada sozinhos. Escolher uma resposta só preenche o campo; o atendente
revisa, pode editar e envia pelo fluxo normal (mesma validação, responsável, janela, permissão
`atendimento:responder` e chave de idempotência por texto). A mensagem guarda o texto enviado:
editar ou desativar a resposta depois não altera mensagens registradas.

- `atd_resposta_rapida`: título (até 80), atalho, conteúdo (1 a 4096, o limite do envio; quebras de
  linha e Unicode preservados, só os espaços das pontas são removidos), ativa, `versao`, autor e
  datas de criação e alteração.
- **Atalho**: normalizado (sem espaços nas pontas, sem a `/` inicial, minúsculas) e validado com
  `^[a-z0-9][a-z0-9-]{1,29}$`; único no catálogo inteiro, inclusive entre inativas (restrição única
  no banco, collation binária): duplicado → 409 `ATALHO_EM_USO` no campo `atalho`, também sob
  concorrência.
- **Concorrência otimista**: editar, ativar e desativar exigem a `versao` vista; diferente → 409
  `CONFLITO_CONCORRENTE` (a tela pede para recarregar). Pedir a situação que já vale não altera nada.
- **Auditoria**: `atd_resposta_rapida_historico` registra criação, alteração (com os campos que
  mudaram), ativação e desativação, com autor e instante. Nunca guarda o conteúdo, que também não vai
  para logs técnicos.

| Método e caminho (`/api/v1/atendimento/respostas-rapidas`) | Uso |
| --- | --- |
| `GET ?busca&noConteudo&situacao&pagina&tamanho` | Lista em ordem de título; `busca` no título e atalho e, com `noConteudo=true` (padrão), no conteúdo; `situacao` `ATIVAS`/`INATIVAS`/`TODAS`; `tamanho` até 100 |
| `GET /{id}` | Uma resposta |
| `POST` | `titulo`, `atalho`, `conteudo` → 201 |
| `PUT /{id}` | mesmos campos + `versao` |
| `POST /{id}/ativacao`, `/{id}/desativacao` | `versao` |

Permissões em [seguranca.md](seguranca.md#autorização); só o `ADMINISTRADOR` as recebe por enquanto.

**Telas.** O catálogo fica em **Ver opções → Mensagens rápidas** (`/atendimento/respostas-rapidas`):
busca no servidor, filtro por situação, paginação, formulário com prévia e contador, erros junto dos
campos, ativar/desativar; ações sem permissão não aparecem. No compositor, o botão **Respostas
rápidas** (ou digitar `/` no início do campo ou depois de espaço) abre a busca nas ativas por título
e atalho (até 8 resultados) com prévia; ↑/↓ escolhem, Enter insere, Esc fecha e devolve o foco ao
campo. Enquanto a busca do termo atual não respondeu, a lista anterior fica esmaecida (`aria-busy`) e
não pode ser escolhida; um Enter dado nesse intervalo escolhe o primeiro resultado da busca atual
quando ela chegar, e digitar de novo cancela essa escolha. O seletor abre sobre a linha do tempo,
acima da área de resposta, sem encolher a conversa. Links e barras no meio de palavras não abrem nada. Com rascunho, a tela pergunta se substitui
ou insere no cursor (cancelar mantém o rascunho); se o resultado passar de 4096 caracteres, nada é
inserido e o motivo aparece. O botão segue os bloqueios do compositor e fica indisponível com anexo
selecionado.

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

### Homologação com a Meta (conta e número de TESTE)

Ambiente separado, só com o app "Em Conta Teste", a WABA de teste e o número de teste (+1 555 616 8584,
modo de desenvolvimento). O número oficial (GDASH) não é usado nem alterado. Tudo por
`infra/homologacao-meta.sh` (ver o cabeçalho do script):

| Peça | Onde |
|---|---|
| API | `127.0.0.1:8091` (`emconta-api-homolog`), banco `emconta_homolog`, bucket `emconta-homolog` com conta de serviço restrita a ele |
| Frontend | `npx vite preview --port 5175` com `EM_CONTA_API_PROXY_TARGET=http://localhost:8091` (build de produção) |
| Segredos | `~/.emconta/homologacao/` (fora do repositório e do OneDrive; o script recusa outro lugar): `whatsapp.env`, `armazenamento.env`, `admin.txt`, `cacerts` |
| Túnel | Cloudflare Quick Tunnel (imagem oficial fixada por digest) → nginx (`infra/homologacao-webhook.conf`) que só repassa `/api/v1/integracoes/whatsapp/webhook`; raiz e demais rotas dão 404 |

Procedimento:

1. `docker build -t em-conta-backend:homolog .` (redes com inspeção TLS: `--secret id=extra_ca,...`).
2. Uma vez: `WHATSAPP_PHONE_NUMBER_ID=<id> WHATSAPP_WABA_ID=<id> CA_EXTRA=<pem> bash infra/homologacao-meta.sh preparar`
   e preencher à mão, no `whatsapp.env`, o token de acesso e o App Secret (nunca no chat nem em comando).
3. `bash infra/homologacao-meta.sh conferir`: validade e expiração do token, app, WABAs que ele
   alcança, número e app assinado na WABA, sem exibir valores.
4. `subir`; depois `tunel-abrir`, que confere 404 na raiz e 403 no webhook sem token antes de abrir e
   grava a URL em `tunel-url.txt`. Cadastrar na Meta `https://<url>/api/v1/integracoes/whatsapp/webhook`
   com o `WHATSAPP_VERIFY_TOKEN` e assinar só o campo `messages`. A URL muda a cada abertura.
5. **Token temporário** (expira em cerca de 1 h): renovar no painel da Meta, salvar no `whatsapp.env`,
   rodar `conferir` e recriar só a API (`descer` + `subir`); o túnel e o cadastro continuam. Com o
   editor aberto no arquivo, não deixe o valor selecionado ao conversar com assistentes integrados ao
   editor: a seleção vai junto com a mensagem.
6. Destinatário no modo de desenvolvimento: o número precisa estar na lista "Até" do painel no mesmo
   formato do `wa_id` que a Meta devolve. Celular brasileiro chegou com 12 dígitos (sem o nono dígito); com
   13 dígitos na lista, a resposta falhou com #131030. O Em Conta usa o `wa_id` como veio, sem regra de
   nono dígito.
7. Ao terminar: `tunel-fechar`, `descer` e parar o preview 5175. Banco, bucket e arquivos ficam para a
   próxima rodada.

Resultados de 09/11/2026, com a Meta e confirmados no celular de teste:

| Item | Resultado |
|---|---|
| Webhook | Verificação pela Meta (200 com o verify token, 403 sem); eventos de outro número/WABA, assinados localmente contra a 8091: 200 sem gravar nada |
| Texto recebido | Aparece em Aguardando, uma vez |
| Resposta de texto | Aceita → enviada → entregue → lida, com correlação; `read` antes de `delivered` não regride |
| Recebidos do celular | Imagem JPEG, nota de voz OGG/Opus, PDF, vídeo MP4 e planilha XLSX: download na 1ª tentativa, hash igual ao da Meta, armazenamento privado, abertura ou download no Em Conta (vídeo toca no player; planilha só com download) em desktop e celular |
| Enviados pelo Em Conta (sintéticos) | MP4 convencional H.264/AAC: lido; planilha XLSX e PDF: entregues; os três chegaram e abriram no celular |
| MP4 fragmentado (mesmos codecs, gerado pelo MediaRecorder) | **Falha confirmada pela Meta**: aceito e, 1 s depois, `failed` #131053 "Media upload error". O convencional com o mesmo conteúdo passou, então é o formato fragmentado, não o envio de vídeo. Desde então, o Em Conta o recusa ao anexar (ver [Formatos e limites](#formatos-e-limites)) |
| "Digitando…" | Apareceu no WhatsApp antes do texto; a última mensagem recebida (a planilha) ficou com tiques azuis, como a Meta exige |
| Tempo real | Balão com relógio 12 a 60 ms após enviar; webhook → processamento 8 a 14 ms (antes 0,1 a 2 s); processamento → ícone na tela 44 a 140 ms (antes até 5 s). O tempo da Meta até o webhook (1,3 a 4,2 s) não depende do Em Conta |

Resultados de 10/11/2026 (mesmo número e WABA de teste; número oficial e GDASH intocados). Em cada
envio: o que a Meta aceitou, o que chegou pelo webhook e o que foi confirmado no celular.

| Cenário | Meta | Webhook | Celular |
|---|---|---|---|
| Imagem JPEG sintética com legenda | upload e mensagem aceitos | `sent`, `delivered` (repetido pela Meta, sem efeito), `read`; cada um processado 7 a 13 ms após chegar | imagem e legenda chegaram juntas |
| Áudio AAC sintético (3 s) | upload e mensagem aceitos | `sent`, `delivered`, `read` | chegou e tocou |
| Encerramento do ATD-000001 pela tela | — | — | saiu de Em andamento, Meus e Aguardando; no histórico, "Conversa encerrada · Somente leitura", sem compositor nem ações, com as 22 mensagens, as situações (9 lidas, 4 falhas: as três #131030 e o MP4 fragmentado) e os 12 anexos |
| Nova mensagem do celular | — | mensagem recebida | abriu o **ATD-000002** em Aguardando, sem responsável; uma só conversa e um só atendimento aberto; o ATD-000001 ficou igual (situação, versão, encerramento, mensagens, eventos e anexos) |

Achados da rodada:

- O compositor recusava `.aac` no Windows ("Tipo não suportado"): o sistema registra a extensão como
  `audio/vnd.dlna.adts`. Nada chegava ao servidor nem à Meta. Corrigido: apelidos conhecidos dos tipos
  aceitos valem com a extensão certa e o envio leva o tipo canônico.
- Depois de uma reinicialização da máquina, o Docker parado derruba API, túnel e callback; o Quick Tunnel
  volta com outra URL, que precisa ser recadastrada.
- **Troca do verify token** (o anterior ficou exposto): `trocar-verify-token`, `descer` e `subir`
  (token antigo → 403, novo → 200 com o desafio). Salvar no painel com a mesma URL não refez a
  verificação (nenhum GET chegou ao proxy). A verificação foi forçada pela Graph API, com autorização,
  atualizando a assinatura do app de teste (`POST /{app-id}/subscriptions` com a mesma URL, `messages` e o
  token lido do arquivo): a Meta verificou na hora (GET 200) e respondeu `success: true`.

## Testes

- `AtendimentoIntegrationTests`, `TransicoesAtendimentoIntegrationTests`,
  `RespostaEEnvioIntegrationTests`, `LinhaDoTempoIntegrationTests` (base `AtendimentoIntegracao`) e
  `CanalNaoConfiguradoIntegrationTests`: MySQL real (Testcontainers) e um servidor HTTP **controlado, de
  teste** (`GraphApiControlada`) no lugar da Graph API, com webhooks assinados com segredo fictício. O
  relógio da aplicação é controlado pelo teste (`RelogioControlado`) para a janela de 24 h, sem esperas.
- `AnexosIntegrationTests`: MinIO real (mesma imagem do Compose, via Testcontainers) e a Graph
  controlada reproduzindo consulta, download e upload de mídia: recebimento de imagem/PDF/XLSX/vídeo/áudio,
  planilha sem pasta de trabalho ou com macros, vídeo com codec, faixas ou estrutura fora da regra da Meta,
  deduplicação e concorrência, URL expirada, timeout, mídia inexistente, conteúdo/MIME/hash/limite
  inválidos, destino e redirecionamento não autorizados, permissões e relação anexo↔atendimento,
  envio pelo responsável, idempotência, janela, resultado desconhecido, falha do armazenamento,
  limpeza de uploads abandonados, Mídia por ciclo, busca por associado e situações em lote.
- `EventosConversaIntegrationTests`: aviso SSE só depois do commit e só para a conversa alterada, sem
  conteúdo; ação recusada não sinaliza; sessão, permissão e atendimento existente; chave da operação só
  para o autor. `ProcessamentoAtendimentoTests`: pedidos de processamento imediato agrupados, sem perder
  o que chega durante uma execução. `DigitandoIntegrationTests`: formato documentado do indicador
  (leitura da última recebida + `typing_indicator`), intervalo mínimo, resposta liberando o próximo,
  regras de resposta e permissão.
- Frontend: `src/features/atendimento/__tests__` (Vitest; `tempo-real.spec.ts` cobre aviso → releitura,
  ícones fora de ordem, balão local sem duplicar, falha, troca de conversa e reconexão) e
  `e2e/atendimento.spec.ts` (Playwright),
  este contra a API de testes do banco isolado, também com Graph controlada local
  (`e2e/whatsapp-controlado.ts`). Nada disso é envio ou recebimento real.
