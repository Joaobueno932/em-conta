# Auditoria completa do Atendimento

Confronto entre a especificação funcional, o protótipo e a implementação, do frontend ao banco e às integrações. Feito em 06/11/2026 sobre o commit `f19d298` (antes) e esta entrega (depois).

## Fontes

| Fonte | O que define | Onde |
|---|---|---|
| Decisões posteriores do usuário | Atendimento compacto: título no cabeçalho global, sem bloco de título, só "Em andamento" e "Aguardando", presença única, Histórico geral em "Ver opções", máximo de altura | conversas de 04 e 05/11/2026; [`../atendimento-compacto/`](../atendimento-compacto/README.md) |
| `Documentação Em Conta.pdf` (v1.3, 34 páginas) | Regras funcionais: RN-ATE-01 a 10, US-ATE-01 a 06, critério de aceite do domínio, DP-03 | raiz do repositório (fora do Git) |
| `Em Conta — Protótipo.pdf` (420 páginas, ≈ 900 MB) | Composição visual e interações | `design/referencias/` (ignorado pelo Git); ver [inventário](inventario-prototipo.md) |
| `em-conta-backend/docs/atendimento.md` | Desenho técnico implementado e escopo deliberado (fora: envio em massa, setor, etiquetas, tickets/SLA, gravação de áudio…) | repositório |

O PDF do protótipo foi processado localmente, página por página, sem envio a serviços externos. As páginas do Atendimento (1–18, mais o atalho da p. 35) foram renderizadas na escala do frame e estão em [`prototipo/`](prototipo/).

## Resultado

71 itens no checklist:

| Situação | Itens |
|---|---|
| Conforme | 42 |
| Conforme (corrigido) | 13 |
| Parcial | 3 |
| Divergente | 3 |
| Ausente | 2 |
| Fora do escopo | 5 |
| Bloqueado | 1 |
| Não verificado | 2 |

### Bugs confirmados e corrigidos

| Item | Bug | Reprodução | Impacto | Teste |
|---|---|---|---|---|
| R-03 | Resultado de uma busca anterior podia ser inserido como se fosse da busca atual | Abrir Respostas rápidas, digitar outro termo e dar Enter antes de a busca responder (200 ms de espera + requisição) | Média: insere no rascunho uma resposta diferente da procurada (não envia) | `respostas-rapidas.spec.ts`: falha antes, passa depois |
| H-05 | Voltar do detalhe zerava busca, período e página do histórico | Filtrar o histórico, ir à página 2, abrir um atendimento e voltar | Baixa: retrabalho de quem audita vários atendimentos | `atendimento.spec.ts`: falha antes, passa depois; teste negativo do endereço de retorno |
| G-05 | Entre 1281 e 1400 px, com o painel aberto, a conversa ficava com ~314 px | Abrir uma conversa em 1288 × 800 | Média: cabeçalho de 249 px, 218 px para as mensagens | Capturas `antes/` × `depois/` + `medidas.json` |
| R-02 | Em 1280 × 720 o seletor de respostas empurrava a conversa até 30 px | Abrir Respostas rápidas em 1280 × 720 | Média: mensagens somem enquanto se escolhe | Capturas `antes/` × `depois/` |
| H-04 | Detalhe do histórico com linha do tempo de 70vh: a página inteira rolava e a faixa "somente leitura" saía da tela | Abrir um detalhe com muitas mensagens | Baixa | Capturas `antes/` × `depois/` |

Os demais itens corrigidos são divergências visuais (F-04, F-05, F-06, C-03, C-04, C-09, C-11, H-02).

## Checklist

Fonte: Visual = protótipo · Funcional = especificação · Decisão do usuário · Adaptação sem frame · Futura = funcionalidade não autorizada · Técnica = `docs/atendimento.md`.

| ID | Área | Fonte | Referência | Requisito ou comportamento | Implementação | Situação | Evidência | Problema / ação |
|---|---|---|---|---|---|---|---|---|
| G-01 | Estrutura | Visual | p. 2–5 | Menu lateral com logo, grupos e item ativo com contador | AreaAutenticadaLayout.vue | **Conforme** | depois/e01-vazio-1672.png; comparacao/prototipo-p002-vazio.png |  |
| G-02 | Estrutura | Futura | p. 2–5 | Itens Funil, Financeiro, Geradoras, O&M, Créditos, Documentos, Automações, Integrações, Configurações, Ajuda; busca global; notificações | — | **Fora do escopo** | — | Módulos futuros; sem links nem páginas. |
| G-03 | Estrutura | Decisão do usuário | — | Título "Atendimento" compacto no cabeçalho global; sem bloco grande de título e subtítulo | router.ts (meta.tituloCabecalho), AtendimentoPage.vue | **Conforme** | depois/e01-vazio-1672.png; teste unitário "mostra só as visões…" |  |
| G-04 | Estrutura | Decisão do usuário | — | Fila, conversa e painel usam toda a altura abaixo do cabeçalho; cada coluna rola por dentro | AtendimentoPage.vue | **Conforme** | depois/medidas.json (compositor visível em todas as larguras > 960 px) |  |
| G-05 | Estrutura | Adaptação sem frame | — | Larguras intermediárias: 3 colunas só acima de 87,5rem; até lá fila + conversa e painel embaixo | AtendimentoPage.vue, FilaAtendimento.vue | **Conforme (corrigido)** | comparacao/antes-depois-e05-em-atendimento-painel-aberto-1288.png | Em 1281–1400 px a conversa ficava com ~314 px (cabeçalho 249 px, 218 px de mensagens). Agora 2 colunas: 319 px de mensagens. |
| G-06 | Estrutura | Adaptação sem frame | — | Sem rolagem horizontal em nenhum estado e largura (1672, 1440, 1408, 1392, 1288, 1280, 1272, 968, 952, 648, 632, 390) | — | **Conforme** | depois/medidas.json: 0 de 74 capturas com rolagem horizontal |  |
| F-01 | Fila | Visual | p. 2 | Fila vazia e conversa vazia ("Tudo pronto para atender") | FilaAtendimento.vue, AtendimentoPage.vue | **Conforme** | depois/e01-vazio-*.png |  |
| F-02 | Fila | Decisão do usuário | — | Só as visões "Em andamento" e "Aguardando", com contagens do servidor; links antigos `?visao=` tratados | FilaAtendimento.vue | **Conforme** | teste unitário "mostra só as visões Em andamento e Aguardando…"; e2e atendimento |  |
| F-03 | Fila | Decisão do usuário | — | Presença num único seletor no topo da fila | AtendimentoPage.vue | **Conforme** | depois/e01-vazio-1672.png |  |
| F-04 | Fila | Visual | p. 2 | Placeholder da busca legível na fila de 320 px | FilaAtendimento.vue | **Conforme (corrigido)** | depois/e01-vazio-1280.png; antes/e01-vazio-1280.png | Cortava ("…ou telefo"). Agora "Buscar por nome ou telefone". "ID do ticket" do frame não se aplica: não há tickets. |
| F-05 | Fila | Visual | p. 2 | Nomes longos sem altura excessiva | FilaAtendimento.vue | **Conforme (corrigido)** | depois/medidas.json (nomeFilaMaiorAltura ≤ 40 px); comparacao/antes-depois-e04-aguardando-390.png | Até 2 linhas com reticências e nome completo no title (o frame quebra "Prefeitura de Maracaju" em 2). |
| F-06 | Fila | Visual | p. 2 | Avatar com as iniciais do nome exibido | FilaAtendimento.vue | **Conforme (corrigido)** | depois/e05-em-atendimento-painel-aberto-1672.png | Mostrava as iniciais do contato ao lado do nome do associado. |
| F-07 | Fila | Visual | p. 2 | Ordenação "Mais recentes | Mais antigos" | — | **Ausente** | — | Não implementada. A fila segue a ordem do servidor. Decisão pendente: incluir ou não. |
| F-08 | Fila | Técnica do módulo | — | Busca por nome do contato, do associado vinculado ou número | Atendimentos (backend), FilaAtendimento.vue | **Conforme** | BuscaAssociadoIntegrationTests; e2e (busca em todas as jornadas) |  |
| F-09 | Fila | Técnica do módulo | — | Paginação da fila | FilaAtendimento.vue | **Não verificado** | — | Implementada; não exercitada visualmente nesta auditoria. |
| F-10 | Fila | Visual | p. 3, 12 | "Ver opções": Histórico geral e Mensagens rápidas (com permissão) | FilaAtendimento.vue | **Conforme** | depois/e02-ver-opcoes-1672.png; comparacao/prototipo-p003-ver-opcoes.png |  |
| F-11 | Fila | Futura | p. 3, 12 | "Ver opções": Mensagem em massa, Dashboard, tema claro/escuro, notificações sonoras | — | **Fora do escopo** | — | Funcionalidades futuras (p. 9–10, 14–18). |
| C-01 | Conversa | Visual | p. 4 | Aguardando: "Assumir atendimento" e faixa "Assuma o atendimento…" no lugar do compositor | ConversaAtendimento.vue | **Conforme** | comparacao/prototipo-p004-aguardando.png; teste unitário "assumir chama o servidor" |  |
| C-02 | Conversa | Visual | p. 5 | Em atendimento: Transferir, Encerrar, histórico do contato; nome (link quando há associado) e telefone | ConversaAtendimento.vue | **Conforme** | comparacao/prototipo-p005-em-atendimento.png | Botões de abrir o painel e fechar a conversa não estão no frame (adaptação anterior). |
| C-03 | Conversa | Visual | p. 5 | Ações na linha do nome em 1672 px com painel aberto; nomes longos legíveis | ConversaAtendimento.vue | **Conforme (corrigido)** | comparacao/antes-depois-e05-em-atendimento-painel-aberto-1672.png | Nome em até 2 linhas com title; cabeçalho de 134 px com nome longo (119 px com nome curto). |
| C-04 | Conversa | Adaptação sem frame | — | Ações no celular sem ícone sobrando sozinho numa linha | ConversaAtendimento.vue | **Conforme (corrigido)** | comparacao/antes-depois-e05-em-atendimento-painel-aberto-390.png |  |
| C-05 | Conversa | Visual | p. 5 | Linha de metadados "Financeiro · Responsável: …" | ConversaAtendimento.vue | **Divergente** | depois/e11-mensagem-enviada-1672.png | Setor não existe. Mostra protocolo interno, situação e data (intencional). |
| C-06 | Conversa | Visual | p. 5 | Balões de entrada e saída, pílulas de dia e de evento, horário | ItemLinhaDoTempo.vue | **Conforme** | comparacao/prototipo-p005-em-atendimento.png | Situação por extenso além do tique (decisão anterior registrada). |
| C-07 | Conversa | Adaptação sem frame | — | Mensagens extensas, URLs e arquivos com nomes grandes sem corte nem sobreposição | ItemLinhaDoTempo.vue, AnexoMensagem.vue | **Conforme** | depois/e05-em-atendimento-painel-aberto-*.png |  |
| C-08 | Conversa | Visual | p. 5 | Compositor: aba Mensagem, campo, anexar, Respostas rápidas, enviar | ConversaAtendimento.vue | **Conforme** | depois/e11-mensagem-enviada-1672.png |  |
| C-09 | Conversa | Visual | p. 5 | Campo de mensagem sem alça de redimensionar | ConversaAtendimento.vue | **Conforme (corrigido)** | comparacao/antes-depois-e09-rascunho-multilinha-1672.png | Cresce com o texto até 10rem e volta a uma linha ao enviar. |
| C-10 | Conversa | Futura | p. 5 | Aba "Nota interna", emoji, gravação de áudio pelo microfone | — | **Fora do escopo** | — | Fora do escopo documentado (docs/atendimento.md). |
| C-11 | Conversa | Visual | p. 84, 104, 139 (padrão de confirmação) | Confirmar o encerramento num diálogo do sistema | DialogoConfirmacao.vue, ConversaAtendimento.vue | **Conforme (corrigido)** | depois/e12-encerrar-confirmacao-1672.png; teste unitário "encerrar pede confirmação…"; e2e atendimento e anexos | Era window.confirm nativo. Foco inicial em Cancelar, Esc cancela, foco volta ao botão. |
| C-12 | Conversa | Funcional | RN-ATE-04 | Assumir, transferir, devolver à fila e encerrar; só o responsável age; sem exceção para administrador | Atendimentos (backend) | **Conforme** | TransicoesAtendimentoIntegrationTests (5); e2e "transferência entre dois atendentes" |  |
| C-13 | Conversa | Técnica do módulo | — | Concorrência entre atendentes e abas (assumir condicional, versão) | Atendimentos (backend) | **Conforme** | duasPessoasNaoAssumemOMesmoAtendimento; acoesConcorrentesNaoSobrescrevemAVencedora |  |
| C-14 | Conversa | Técnica do módulo | — | Encerrado somente leitura; nova mensagem abre outro ciclo na mesma conversa | ConversaAtendimento.vue, Atendimentos (backend) | **Conforme** | depois/e13-encerrado-1672.png; transferenciaEncerramentoERetornoAbremNovoAtendimento |  |
| C-15 | Conversa | Técnica do módulo | — | Rolagem interna; posição de leitura ao carregar anteriores e ao chegar mensagem nova; lacunas | use-linha-do-tempo.ts, ConversaAtendimento.vue | **Conforme** | e2e linha-do-tempo; LinhaDoTempoIntegrationTests (3); teste unitário "carrega mensagens anteriores…" |  |
| C-16 | Conversa | Técnica do módulo | — | Janela de 24 h no registro e imediatamente antes de enviar | Envio (backend) | **Conforme** | janelaDe24HorasBloqueiaTextoLivre; testes unitários de janela |  |
| C-17 | Conversa | Técnica do módulo | — | Idempotência sequencial e concorrente; falha de rede reutiliza a chave | ConversaAtendimento.vue, backend | **Conforme** | requisicoesSimultaneasIdenticasConvergemParaUmaMensagem; testes unitários de chave |  |
| C-18 | Conversa | Técnica do módulo | — | Resultado desconhecido sem reenvio; situações fora de ordem sem regressão | Envio (backend) | **Conforme** | erroExternoDefinitivoTemporarioETimeoutSemReenvio; reinicioNaoPerdeEventosNemReenviaReservaInterrompida |  |
| C-19 | Conversa | Adaptação sem frame | — | Rascunho preservado | ConversaAtendimento.vue | **Parcial** | — | Preservado durante atualizações e falhas de envio; perdido ao trocar de conversa. Decisão pendente. |
| P-01 | Painel | Visual | p. 5 | Painel aberto: associado, situação, contatos, Ver cadastro; Atendimento recolhível; Mídia | PainelAssociado.vue | **Parcial** | comparacao/prototipo-p005-em-atendimento.png | Etiquetas, Simulador de proposta, Documentos e Setor são futuros. |
| P-02 | Painel | Visual | p. 11 | Painel fechado; foco volta ao botão que o reabre | AtendimentoPage.vue | **Conforme** | comparacao/prototipo-p011-painel-fechado.png; teste unitário "fechar o painel…" |  |
| P-03 | Painel | Funcional | RN-ATE-02 | Vincular, trocar e remover associado (troca e remoção com motivo) | PainelAssociado.vue, backend | **Conforme** | vinculoComAssociadoAmbiguoExigeEscolhaECorrecaoComMotivo; teste unitário "trocar o associado…" | Oportunidade e UC (também em RN-ATE-02) dependem do CRM. |
| M-01 | Mídia | Funcional | RN-ATE-03, US-ATE-04 | Receber e enviar imagem, PDF e áudio com validação de MIME, extensão, conteúdo, tamanho e hash | Midias, Envio (backend), ConversaAtendimento.vue | **Conforme** | AnexosIntegrationTests (16); e2e anexos |  |
| M-02 | Mídia | Técnica do módulo | — | Reservas exclusivas, execução antiga retomada e limpeza de órfãos sem remover válidos | Midias (backend) | **Conforme** | ReservasEObjetosIntegrationTests (9) |  |
| M-03 | Mídia | Técnica do módulo | — | Conteúdo privado, autorizado por ciclo, Range, nosniff, sem URL do armazenamento no navegador | AnexosController | **Conforme** | AnexosIntegrationTests |  |
| M-04 | Mídia | Técnica do módulo | — | "Ver na conversa" para mensagem ainda não carregada; Mídia do ciclo certo no histórico | MidiaConversa.vue, AtendimentoDetalhePage.vue | **Conforme** | e2e anexos "Mídia abre mensagem antiga não carregada…" |  |
| M-05 | Mídia | Visual | p. 8 | Rótulo do item de mídia | MidiaConversa.vue | **Divergente** | depois/e05-em-atendimento-painel-aberto-1672.png | Imagem com legenda e sem nome de arquivo aparece como "Imagem sem nome". Usar a legenda exige um campo novo em ItemMidia (contrato da API). |
| M-06 | Mídia | Adaptação sem frame | — | Arquivo enviado com nome longo e legenda no compositor | ConversaAtendimento.vue | **Conforme** | depois/e10-anexo-selecionado-*.png |  |
| R-01 | Respostas rápidas | Visual | p. 13 | Painel lateral "Mensagens rápidas" (Pessoais/Compartilhadas, busca, "Nova mensagem rápida") | SeletorRespostasRapidas.vue, RespostasRapidasPage.vue | **Divergente** | comparacao/prototipo-p013-respostas-rapidas.png | Implementado como seletor sobre a conversa + catálogo da equipe em "Ver opções". Respostas pessoais não existem: decisão de negócio pendente. |
| R-02 | Respostas rápidas | Adaptação sem frame | — | Seletor sem empurrar a conversa nem esconder o compositor em telas baixas | ConversaAtendimento.vue | **Conforme (corrigido)** | comparacao/antes-depois-e08-respostas-rapidas-1280.png | Em 1280×720 a linha do tempo caía para 30 px. Agora o seletor sobrepõe a linha do tempo, acima da área de resposta. |
| R-03 | Respostas rápidas | Técnica do módulo | — | Resultado de busca anterior não pode ser escolhido como se fosse da busca atual | SeletorRespostasRapidas.vue | **Conforme (corrigido)** | testes unitários "resultado de uma busca anterior…" e "digitar depois do Enter…" (o primeiro falha sem a correção) | Bug. Enter dado antes da resposta agora espera o resultado da busca atual. |
| R-04 | Respostas rápidas | Técnica do módulo | — | Inserir sem enviar; substituir, inserir no cursor ou cancelar; limite de 4096; "/" só no início de palavra | ConversaAtendimento.vue | **Conforme** | testes unitários de Respostas rápidas na conversa; e2e respostas-rapidas |  |
| R-05 | Respostas rápidas | Técnica do módulo | — | Catálogo: busca, paginação, ativas/inativas, atalho único, conflito de edição, permissões | RespostasRapidasPage.vue, backend | **Conforme** | RespostasRapidasIntegrationTests (7); testes unitários do catálogo; depois/e17-*.png |  |
| H-01 | Histórico | Visual | p. 7 | Histórico geral: lista, busca, período, paginação numerada | HistoricoGeralPage.vue | **Parcial** | comparacao/prototipo-p007-historico.png | Setor e Etiquetas são futuros; período em dois campos (De/Até); coluna Associado a mais. |
| H-02 | Histórico | Visual | p. 6–8 | Histórico e detalhe ocupam a área útil (sem margem central larga) | HistoricoGeralPage.vue, AtendimentoDetalhePage.vue, estilos.css | **Conforme (corrigido)** | comparacao/antes-depois-e14-historico-geral-1672.png | Estava centralizado em 76rem e 90rem. |
| H-03 | Histórico | Visual | p. 6 | Estado sem resultados | HistoricoGeralPage.vue | **Conforme** | comparacao/prototipo-p006-historico-vazio.png | Texto "Nenhum atendimento encontrado." |
| H-04 | Histórico | Visual | p. 8 | Detalhe: cartões até o fim da janela, linha do tempo rolando por dentro, faixa "Conversa encerrada · Somente leitura" sempre visível | AtendimentoDetalhePage.vue | **Conforme (corrigido)** | comparacao/antes-depois-e16-historico-detalhe-1672.png; comparacao/prototipo-p008-detalhe.png | A linha do tempo tinha 70vh fixo e a página inteira rolava. |
| H-05 | Histórico | Técnica do módulo | — | Voltar do detalhe preservando busca, período e página | HistoricoGeralPage.vue, AtendimentoDetalhePage.vue | **Conforme (corrigido)** | testes unitários "histórico guarda filtros…" (falha sem a correção) e "o detalhe só volta para endereços do próprio histórico" | Bug. Filtros e página ficam na URL; o detalhe recebe a origem em `volta` e só aceita o próprio histórico. |
| H-06 | Histórico | Futura | p. 8 | Exportar conversa, busca dentro da conversa, Notas | — | **Fora do escopo** | — | Futuros. |
| I-01 | Integração | Técnica do módulo | — | Verificação do webhook e assinatura HMAC do corpo bruto | WebhookWhatsAppController | **Conforme** | verificacaoInicialEAssinatura |  |
| I-02 | Integração | Técnica do módulo | — | Deduplicação, processamento concorrente, retentativas, recuperação após queda | Processador (backend) | **Conforme** | eventosRepetidosEProcessamentoConcorrenteNaoDuplicam; reinicioNaoPerdeEventos… |  |
| I-03 | Integração | Técnica do módulo | — | Canal desabilitado ou incompleto: aviso compacto, envio recusado, webhook recusado | AtendimentoPage.vue, backend | **Conforme** | CanalNaoConfiguradoIntegrationTests; depois/e18-canal-nao-configurado-*.png | A API de trabalho segue com o WhatsApp desligado. |
| I-04 | Integração | Funcional | DP-03 | Homologação real com a Meta (número de teste, webhook publicado) | — | **Bloqueado** | — | Depende do acesso à Meta e do número de teste. Testes usam só a Graph controlada local. |
| S-01 | Segurança | Técnica do módulo | — | Autorização no servidor por rota, negação por padrão, sem bypass de administrador | SecurityConfiguration.java | **Conforme** | permissoesParciaisESessaoRevogada; responsavelSemAPermissaoEspecificaContinuaBloqueado |  |
| S-02 | Segurança | Técnica do módulo | — | CSRF em todas as rotas, exceto o webhook (autenticado por HMAC) | SecurityConfiguration.java | **Conforme** | revisão do código; suíte e2e completa |  |
| S-03 | Segurança | Técnica do módulo | — | 401 e logout limpam todo o cache e a sessão anterior | guards.ts, LoginPage.vue | **Conforme** | revisão do código; testes de acesso |  |
| S-04 | Segurança | Adaptação sem frame | — | Endereço de retorno do detalhe sem redirecionamento aberto | AtendimentoDetalhePage.vue | **Conforme** | teste unitário "o detalhe só volta para endereços do próprio histórico" |  |
| N-01 | Especificação | Funcional | RN-ATE-05/06/08, US-ATE-05 | Chamados com protocolo, origem, assunto, prioridade, 7 situações, solução e reabertura | — | **Ausente** | — | O modelo atual é o ciclo Aguardando → Em atendimento → Encerrado. Decisão de negócio pendente. |
| N-02 | Especificação | Funcional | RN-ATE-07, US-ATE-06 | SLA por categoria e prioridade; fila e SLAs para supervisão | — | **Fora do escopo** | — | Depende de N-01 e do Dashboard (futuro). |
| N-03 | Especificação | Funcional | RN-ATE-09 | Mensagem não recebida pelo canal nunca aparece como entregue; falhas tratadas | Envio (backend), ItemLinhaDoTempo.vue | **Conforme** | respostaExigeResponsavelEAceiteNaoEConfirmacaoDeEntrega |  |
| A-01 | Acessibilidade | Adaptação sem frame | — | Diálogo de confirmação: foco, Esc, nome acessível, fundo inerte | DialogoConfirmacao.vue | **Conforme** | teste unitário "encerrar pede confirmação…" |  |
| A-02 | Acessibilidade | Adaptação sem frame | — | Seletor de respostas: combobox, listbox, aria-busy enquanto desatualizado | SeletorRespostasRapidas.vue | **Conforme** | testes unitários das respostas rápidas |  |
| A-03 | Acessibilidade | Adaptação sem frame | — | Teclado e painéis sem sobreposição na Mídia | MidiaConversa.vue | **Conforme** | e2e anexos |  |
| A-04 | Acessibilidade | Adaptação sem frame | — | Leitor de tela de ponta a ponta (NVDA/VoiceOver) | — | **Não verificado** | — | Não executado nesta auditoria. |

## Verificações executadas

Todas sobre o código desta entrega, contra a API isolada de testes (banco `emconta_e2e`, bucket `emconta-e2e`) e a Graph controlada local. Nenhuma chamada à Meta.

| Verificação | Resultado |
|---|---|
| Backend `mvnw verify` (compilação, testes com MySQL e MinIO reais via Testcontainers, Spotless) | 157 testes, 0 falhas — 61 do Atendimento |
| Frontend lint (oxlint + ESLint), Prettier, vue-tsc | sem erros |
| Frontend Vitest | 107 testes, 0 falhas (eram 102) |
| Frontend build de produção | ok |
| Playwright, suíte completa, modo dev | 19 de 19 |
| Playwright, suíte completa, modo produção (build + preview) | 19 de 19 |
| Bateria visual (`depois/`, 18 estados, 74 capturas, 12 larguras) | 0 rolagens horizontais; compositor visível em todas as capturas acima de 960 px |

Testes com falha simulada (Graph controlada, webhooks assinados com segredo fictício, relógio controlado) não substituem a homologação real com a Meta, que continua pendente.

## Evidências

- [`inventario-prototipo.md`](inventario-prototipo.md): as 420 páginas, a busca de termos e o mapeamento das páginas do Atendimento.
- [`prototipo/`](prototipo/): páginas 2–8 e 11–13 do protótipo, na escala do frame (1672 px).
- [`depois/`](depois/): `e01` a `e18` em 1672, 1440, 1280 e 390 px, mais 1408, 1392, 1288, 1272, 968, 952, 648 e 632 px nos estados principais; `medidas.json` com as medidas de cada captura.
- [`antes/`](antes/): os mesmos estados no commit `f19d298`, só onde houve mudança, e `medidas.json` completo.
- [`comparacao/`](comparacao/): `prototipo-pNNN-*` (protótipo × implementação, mesma escala) e `antes-depois-*`.

Dados sintéticos só da base isolada; nomes, mensagens e arquivos longos de propósito. A imagem recebida nas capturas é um PNG sintético só com a assinatura, por isso aparece como imagem quebrada. Nenhum dado foi criado na base de trabalho.

## Diferenças intencionais em relação aos frames antigos

- Cabeçalho da página, visões da fila e presença seguem as decisões posteriores do usuário (G-03, F-02, F-03), não os frames p. 2–5 e 11–13.
- Situação das mensagens por extenso, além do tique (C-06); protocolo interno no lugar de setor (C-05).

## Pendências visuais concretas

- Entre ~1401 e ~1500 px com o painel aberto, a conversa tem 430–470 px e o cabeçalho ocupa 2–3 linhas (210–263 px). Opções: cabeçalho compacto para cartões estreitos ou fechar o painel por padrão nessas larguras.
- Com o seletor de respostas aberto em 1280 × 720, ele cobre parte do cabeçalho da conversa enquanto está aberto (Esc fecha).
- "Imagem sem nome" na Mídia quando a imagem só tem legenda (M-05; exige campo novo na API).
- Logo ainda em PNG recortado do export (pendência anterior: SVG oficial).
- Os outros módulos ainda usam `window.confirm` (associados, usinas, usuários); o diálogo novo está pronto para reuso.

## Decisões pendentes

1. Chamados (RN-ATE-05 a 08): adotar protocolo, assunto, prioridade, as 7 situações, solução e reabertura sobre o ciclo atual, ou manter o ciclo simples no R1.
2. Respostas rápidas pessoais além das da equipe (p. 13 mostra Pessoais/Compartilhadas) e se viram painel lateral.
3. Ordenação "Mais recentes | Mais antigos" na fila (F-07).
4. Guardar o rascunho por conversa ao trocar de conversa (só em memória, nunca em disco) (C-19).
5. Contrato da integração WhatsApp (DP-03) e acesso à Meta para a homologação (I-04).

## Funcionalidades futuras (registradas, não implementadas)

Mensagens em massa (p. 9, 14, 15), Dashboard de atendimento (p. 10, 16–18), Nota interna, emoji, gravação de áudio, etiquetas, setor, simulador de proposta, documentos no painel, exportar conversa, busca dentro da conversa, notas do atendimento, tema claro/escuro, notificações sonoras, busca global e notificações do cabeçalho.

