# Inventário do protótipo

Arquivo local `design/referencias/Em Conta — Protótipo.pdf` (≈ 900 MB, ignorado pelo Git), exportado do Figma em 04/11/2026: **420 páginas**, cada uma um frame de 1672 px de largura. O PDF não traz nomes de frame, sumário nem rótulos de página, então cada página é identificada pelo número e pelo conteúdo. O arquivo foi processado localmente, página por página (PyMuPDF), sem envio a serviços externos.

## Como as páginas do Atendimento foram identificadas

1. Busca, em todas as 420 páginas, dos termos próprios do Atendimento (tabela abaixo).
2. Inspeção visual das páginas encontradas, renderizadas na escala do frame (1672 px) — cópias em [`prototipo/`](prototipo/).
3. Fora das páginas 1–18, os termos só aparecem como "WhatsApp" (canal de envio de faturas, propostas e notificações) e no atalho "Abrir conversa no Atendimento" do menu do associado (p. 35).

| Termo | Páginas |
|---|---|
| Converse e acompanhe seus associados | 2, 3, 4, 5, 11, 12, 13 |
| Assumir | 4 |
| Transferir | 5, 11, 12, 13 |
| Encerrar | 5, 11, 12, 13 |
| Dados do associado | 4, 5, 8, 23, 94 |
| Digite uma mensagem | 5, 11, 12 |
| Respostas rápidas | 5, 11, 12, 13 |
| Mensagens rápidas | 3, 12, 13 |
| Nota interna | 5, 11, 12, 13 |
| Mídia | 4, 5, 8 |
| Histórico geral | 3, 6, 7, 8, 12 |
| Mensagens em massa | 9, 14, 15 |
| Dashboard de atendimento | 10, 16, 17, 18 |
| Abrir conversa | 35 |

## Páginas do Atendimento e relacionadas

| Página | Tamanho | Frame (pelo conteúdo) |
|---|---|---|
| 1 | 1672×1059 | Painel geral (ponto de entrada; contador do menu Atendimento) |
| 2 | 1672×941 | AT-01 · Fila "Em andamento" vazia, sem conversa |
| 3 | 1672×941 | AT-02 · "Ver opções" aberto (Histórico geral, Mensagem em massa, Mensagens rápidas, Dashboard, tema, sons) |
| 4 | 1672×941 | AT-03 · Aguardando · conversa selecionada (Assumir) |
| 5 | 1672×941 | AT-04 · Em atendimento · dados do associado abertos |
| 6 | 1672×941 | Histórico geral · sem resultados |
| 7 | 1672×941 | Histórico geral · lista com filtros e paginação |
| 8 | 1672×941 | Detalhe do atendimento (somente leitura, Exportar conversa, Notas/Mídias/Documentos/Áudios) |
| 9 | 1672×941 | Mensagens em massa · etapa 1 (destinatários) |
| 10 | 1672×1013 | Dashboard de atendimento · dados históricos |
| 11 | 1672×941 | AT-05 · Em atendimento · dados do associado fechados |
| 12 | 1672×941 | AT-06 · Conversa + "Ver opções" aberto |
| 13 | 1672×941 | AT-07 · Mensagens rápidas em painel lateral (Pessoais/Compartilhadas) |
| 14 | 1672×941 | Mensagens em massa · etapa 2 (mensagem) |
| 15 | 1672×941 | Mensagens em massa · etapa 1 (variação) |
| 16 | 1672×1375 | Dashboard de atendimento · ao vivo |
| 17 | 1672×1120 | Dashboard de atendimento · monitoramento ao vivo |
| 18 | 1672×1201 | Dashboard de atendimento · comercial / gestão de vendas |
| 35 | 1672×2395 | Menu do associado: "Abrir conversa no Atendimento" (atalho de outro módulo) |

## Todas as páginas

Texto próprio de cada página (sem os itens repetidos do menu e do cabeçalho), para conferência. As páginas 19 a 420 pertencem a outros módulos (associados, contratos, pendências, faturas, oportunidades, usinas, documentos, conta, notificações, ajuda) e ficam fora desta auditoria.

| Página | Tamanho | Primeiras linhas |
|---|---|---|
| 1 | 1672×1059 | Acompanhe sua operação. · Faturado no mês · R$ 284.450,75 · + 12% vs. mês anterior · Recebido |
| 2 | 1672×941 | Converse e acompanhe seus associados. · Disponível · Buscar contato por nome, telefone ou ID do ticket · Em andamento · 0 |
| 3 | 1672×941 | Converse e acompanhe seus associados. · Disponível · Buscar contato por nome, telefone ou ID do ticket · Em andamento · 0 |
| 4 | 1672×941 | Converse e acompanhe seus associados. · Disponível · FA · (67) 99999-0000 · Fernando Almeida |
| 5 | 1672×941 | Converse e acompanhe seus associados. · Disponível · Fernando Almeida · FA · (67) 99999-0000 |
| 6 | 1672×941 | Histórico geral · Consulte o histórico de todos os atendimentos do Em Conta. · Buscar contato · Setor · Nome, telefone ou ID do ticket |
| 7 | 1672×941 | Histórico geral · Consulte o histórico de todos os atendimentos do Em Conta. · Buscar contato · Setor · Nome, telefone ou ID do ticket |
| 8 | 1672×941 | Fernando Almeida · Histórico geral   /   Detalhes do atendimento · FA · Fernando Almeida · Encerrado |
| 9 | 1672×941 | Mensagens em massa · Envie um comunicado para vários contatos de uma só vez. · 1 · Destinatários · Destinatários |
| 10 | 1672×1013 | Dashboard de atendimento · Acompanhe a operação e os resultados da equipe. · Ao vivo · Dados históricos · Comercial |
| 11 | 1672×941 | Converse e acompanhe seus associados. · Disponível · FA · (67) 99999-0000 · Fernando Almeida |
| 12 | 1672×941 | Converse e acompanhe seus associados. · Disponível · FA · (67) 99999-0000 · Fernando Almeida |
| 13 | 1672×941 | Converse e acompanhe seus associados. · Disponível · Fernando Almeida · FA · (67) 99999-0000 |
| 14 | 1672×941 | Mensagens em massa · Envie um comunicado para vários contatos de uma só vez. · Destinatários · 2 · Mensagem |
| 15 | 1672×941 | Mensagens em massa · Envie um comunicado para vários contatos de uma só vez. · Destinatários · Mensagem · Destinatários |
| 16 | 1672×1375 | Dashboard de atendimento · Acompanhe a operação e os resultados da equipe. · Ao vivo · Dados históricos · Comercial |
| 17 | 1672×1120 | Dashboard de atendimento · Acompanhe a operação e os resultados da equipe. · Ao vivo · Dados históricos · Comercial |
| 18 | 1672×1201 | Dashboard de atendimento · Acompanhe a operação e os resultados da equipe. · Ao vivo · Dados históricos · Comercial |
| 19 | 1672×1100 | Consulte e gerencie os cadastros dos clientes da Em Conta. · Associados cadastrados · 640 no total · Buscar por nome, razão social ou CPF/CN |
| 20 | 1672×1100 | Consulte e gerencie os cadastros dos clientes da Em Conta. · Associados cadastrados · 640 no total · Buscar por nome, razão social ou CPF/CN |
| 21 | 1672×1100 | Consulte e gerencie os cadastros dos clientes da Em Conta. · Associados cadastrados · 8 resultados · Buscar por nome, razão social ou CPF/CN |
| 22 | 1672×1100 | Consulte e gerencie os cadastros dos clientes da Em Conta. · Associados cadastrados · 31 resultados · Buscar por nome, razão social ou CPF/C |
| 23 | 1672×2433 | Associados / Todos os associados / Agropecuária Aurora · Agropecuária Aurora · PJ · CNPJ 22.333.***/0001-55 · Nº cliente 282334417 · Criado  |
| 24 | 1672×1288 | Associados / Todos os associados / Agropecuária Aurora · Agropecuária Aurora · PJ · CNPJ 22.333.***/0001-55 · Nº cliente 282334417 · Criado  |
| 25 | 1672×664 | Associados / Todos os associados / Agropecuária Aurora · Agropecuária Aurora · PJ · CNPJ 22.333.***/0001-55 · Nº cliente 282334417 · Criado  |
| 26 | 1672×672 | Associados / Todos os associados / Agropecuária Aurora · Agropecuária Aurora · PJ · CNPJ 22.333.***/0001-55 · Nº cliente 282334417 · Criado  |
| 27 | 1672×1100 | Gerencie os contratos dos associados e acompanhe as assinaturas. · Assinados · Todos · 96 · 128 |
| 28 | 1672×1100 | Gerencie os contratos dos associados e acompanhe as assinaturas. · Todos · Assinados · 128 · 96 |
| 29 | 1672×1100 | Gerencie os contratos dos associados e acompanhe as assinaturas. · Todos · Assinados · 128 · 96 |
| 30 | 1672×1100 | Gerencie os contratos dos associados e acompanhe as assinaturas. · Todos · Assinados · 128 · 96 |
| 31 | 1672×1100 | Associados / Contratos / Novo contrato · Novo contrato · Gere o documento a partir de um modelo interno e envie para assinatura. · 1 · Assoc |
| 32 | 1672×1100 | Associados / Contratos / Novo contrato · Novo contrato · Gere o documento a partir de um modelo interno e envie para assinatura. · Associado |
| 33 | 1672×1100 | Associados / Contratos / Novo contrato · Novo contrato · Gere o documento a partir de um modelo interno e envie para assinatura. · Associado |
| 34 | 1672×1100 | Associados / Contratos / Novo contrato · Novo contrato · Gere o documento a partir de um modelo interno e envie para assinatura. · Associado |
| 35 | 1672×2395 | Copiar link · Enviar proposta · Abrir conversa no Atendimento |
| 36 | 1672×2395 | Vincular geradora · Buscar geradora por nome · Vale Verde · 2,0 MWp · Terenos/MS · Sol Nascente |
| 37 | 1672×2395 | Vincular negócio · Buscar negócio · Expansão — Armazéns · Fase: Proposta · R$ 540,00/mês · Renovação 2027 |
| 38 | 1672×2395 | Vincular produto ou serviço · Buscar produto ou serviço · Monitoramento premium · Relatórios semanais de geração · Gestão de faturas |
| 39 | 1672×1100 | Filtros · Tipo de pessoa · Todos · PF · PJ |
| 40 | 1672×1100 | Filtrar por tipo, situação, responsável e pendências |
| 41 | 1672×1100 | Ver detalhes · Editar cadastro · Novo contrato · Ver contratos |
| 42 | 1672×1100 | Importar associados · Etapa 1 de 3 · Arquivo · Arraste o arquivo aqui ou clique para selecionar · .csv ou .xlsx — até 5 MB · Baixar modelo . |
| 43 | 1672×1100 | Identifique informações incompletas e acompanhe a regularização dos cadastros. · Pendências em aberto · Associados com pendências · 24 · 18 |
| 44 | 1672×1100 | Identifique informações incompletas e acompanhe a regularização dos cadastros. · Pendências em aberto · Associados com pendências · 24 · 18 |
| 45 | 1672×1100 | Identifique informações incompletas e acompanhe a regularização dos cadastros. · Pendências em aberto · Associados com pendências · 24 · 18 |
| 46 | 1672×1100 | Identifique informações incompletas e acompanhe a regularização dos cadastros. · Pendências em aberto · Associados com pendências · 24 · 18 |
| 47 | 1672×1100 | Consulte o que mudou, quem realizou a alteração e quando. · Rastreabilidade das alterações de associados, unidades consumidoras, contratos e |
| 48 | 1672×1100 | Consulte o que mudou, quem realizou a alteração e quando. · Rastreabilidade das alterações de associados, unidades consumidoras, contratos e |
| 49 | 1672×1100 | Consulte o que mudou, quem realizou a alteração e quando. · Rastreabilidade das alterações de associados, unidades consumidoras, contratos e |
| 50 | 1672×1100 | Consulte o que mudou, quem realizou a alteração e quando. · Rastreabilidade das alterações de associados, unidades consumidoras, contratos e |
| 51 | 1672×1100 | Revisar pendência · Telefone não informado · Associado · Maria Eduarda Lopes · PF · CPF ***.***.789-** · Registro afetado |
| 52 | 1672×1100 | Revisar pendência · Telefone não informado · Associado · Maria Eduarda Lopes · PF · CPF ***.***.789-** · Registro afetado |
| 53 | 1672×1100 | Revisar pendência · Telefone não informado · Associado · Maria Eduarda Lopes · PF · CPF ***.***.789-** · Registro afetado |
| 54 | 1672×1100 | Revisar pendência · Telefone não informado · Associado · Maria Eduarda Lopes · PF · CPF ***.***.789-** · Registro afetado |
| 55 | 1672×1100 | Filtros · Associado · Categoria · Documentação · Cadastro |
| 56 | 1672×1100 | Filtrar por associado, categoria, responsável e período |
| 57 | 1672×1100 | Revisar pendência · Ver histórico do associado · Ver cadastro |
| 58 | 1672×1100 | Exportar pendências · Formato · .csv · Texto separado por vírgulas · Situação incluída |
| 59 | 1672×1100 | Consulte e gerencie os cadastros dos clientes da Em Conta. · Associados cadastrados · 2 resultados · maria · Associado |
| 60 | 1672×1100 | Consulte e gerencie os cadastros dos clientes da Em Conta. · Associados cadastrados · 0 resultados · Lúcia Prado · Associado |
| 61 | 1672×1100 | Consulte e gerencie os cadastros dos clientes da Em Conta. · Associados cadastrados · 640 no total · Buscar por nome, razão social ou CPF/CN |
| 62 | 1672×1100 | Consulte e gerencie os cadastros dos clientes da Em Conta. · Associados cadastrados · 640 no total · Buscar por nome, razão social ou CPF/CN |
| 63 | 1672×1100 | Gerencie os contratos dos associados e acompanhe as assinaturas. · Assinados · Todos · 96 · 128 |
| 64 | 1672×1100 | Gerencie os contratos dos associados e acompanhe as assinaturas. · Assinados · Todos · 96 · 128 |
| 65 | 1672×1100 | Associados / Contratos / Aditivo de UC — Ordenha · Aditivo de UC — Ordenha · Agropecuária Aurora · Criado em 16/10/2026 por Pedro Costa · Si |
| 66 | 1672×1100 | Associados / Contratos / Termo de Adesão · Termo de Adesão · Prefeitura de Maracaju · Criado em 13/10/2026 por João Silva · Signatários · 2  |
| 67 | 1672×1100 | Associados / Contratos / Novo contrato · Novo contrato · Gere o documento a partir de um modelo interno e envie para assinatura. · Associado |
| 68 | 1672×1100 | Associados / Contratos / Novo contrato · Novo contrato · Gere o documento a partir de um modelo interno e envie para assinatura. · Associado |
| 69 | 1672×1100 | Associados / Contratos / Novo contrato · Novo contrato · Gere o documento a partir de um modelo interno e envie para assinatura. · Associado |
| 70 | 1672×1100 | Associados / Contratos / Novo contrato · Novo contrato · Gere o documento a partir de um modelo interno e envie para assinatura. · Associado |
| 71 | 1672×1100 | Importar associados · Etapa 2 de 3 · Validação · associados-outubro.xlsx · 124 registros · Validando campos e possíveis duplicidades… |
| 72 | 1672×1100 | Importar associados · Etapa 1 de 3 · Arquivo · Formato não suportado. Envie um arquivo .csv ou .xlsx. · .csv ou .xlsx — até 5 MB · Não foi p |
| 73 | 1672×1100 | Importar associados · Etapa 3 de 3 · Prévia e validação · associados-outubro.xlsx · 124 registros · 118 |
| 74 | 1672×1100 | Importação concluída · 118 associados importados com situação inicial “Em contratação”. Resultado · demonstrativo. · Importados · 118 |
| 75 | 1672×1100 | Identifique informações incompletas e acompanhe a regularização dos cadastros. · Pendências em aberto · Associados com pendências · 24 · 18 |
| 76 | 1672×1100 | Identifique informações incompletas e acompanhe a regularização dos cadastros. · Pendências em aberto · Associados com pendências · 24 · 18 |
| 77 | 1672×1100 | Identifique informações incompletas e acompanhe a regularização dos cadastros. · Pendências em aberto · Associados com pendências · 24 · 18 |
| 78 | 1672×1100 | Identifique informações incompletas e acompanhe a regularização dos cadastros. · Pendências em aberto · Associados com pendências · 23 · 17 |
| 79 | 1672×1100 | Consulte o que mudou, quem realizou a alteração e quando. · Rastreabilidade das alterações de associados, unidades consumidoras, contratos e |
| 80 | 1672×1100 | Consulte o que mudou, quem realizou a alteração e quando. · Rastreabilidade das alterações de associados, unidades consumidoras, contratos e |
| 81 | 1672×1100 | Consulte o que mudou, quem realizou a alteração e quando. · Rastreabilidade das alterações de associados, unidades consumidoras, contratos e |
| 82 | 1672×1100 | Consulte o que mudou, quem realizou a alteração e quando. · Rastreabilidade das alterações de associados, unidades consumidoras, contratos e |
| 83 | 1672×1100 | Revisar pendência · Telefone não informado · Associado · Maria Eduarda Lopes · PF · CPF ***.***.789-** · Registro afetado |
| 84 | 1672×1100 | Descartar correção? · O telefone informado não será salvo e a pendência continuará · em aberto. · Descartar · Continuar editando |
| 85 | 1672×1100 | Revisar pendência · Documento de identificação ausente · Associado · João Pedro Almeida · PF · CPF ***.***.214-** · Registro afetado |
| 86 | 1672×1100 | Revisar pendência · Documento de identificação ausente · Associado · João Pedro Almeida · PF · CPF ***.***.214-** · Registro afetado |
| 87 | 1672×1100 | Exportação pronta · “pendencias-em-aberto-19-10-2026.xlsx” gerado com 24 · registros. Download demonstrativo — nenhum arquivo real é · gerad |
| 88 | 1672×1100 | Filtros · Associado · Usuário ou sistema · Todos · Categoria |
| 89 | 1672×1100 | Período · Hoje · Últimos 7 dias · Este mês · Personalizado |
| 90 | 1672×1100 | Filtrar por associado, usuário, categoria, ação e origem |
| 91 | 1672×1100 | Consulte e gerencie os cadastros dos clientes da Em Conta. · Associados cadastrados · 641 no total · Buscar por nome, razão social ou CPF/CN |
| 92 | 1672×1100 | Acompanhe a base de associados da Em Conta. · Total de associados · Ativos · 640 · 582 |
| 93 | 1672×1565 | Associados / Todos os associados / Agropecuária Aurora · Agropecuária Aurora · PJ · CNPJ 22.333.***/0001-55 · Nº cliente 282334417 · Criado  |
| 94 | 1672×2433 | Associados / Todos os associados / Agropecuária Aurora · Agropecuária Aurora · PJ · CNPJ 22.333.***/0001-55 · Nº cliente 282334417 · Criado  |
| 95 | 1672×1754 | Associados / Todos os associados / Agropecuária Aurora · Agropecuária Aurora · PJ · CNPJ 22.333.***/0001-55 · Nº cliente 282334417 · Criado  |
| 96 | 1672×1136 | Associados / Todos os associados / Agropecuária Aurora · Agropecuária Aurora · PJ · CNPJ 22.333.***/0001-55 · Nº cliente 282334417 · Criado  |
| 97 | 1672×1100 | Associados / Contratos / Aditivo de UC — Ordenha · Aditivo de UC — Ordenha · Agropecuária Aurora · Criado em 16/10/2026 por Pedro Costa · Si |
| 98 | 1672×2395 | Vincular unidade consumidora · Buscar por nº da UC, instalação ou endereço · UC 6***2450 · Secador de grãos · Instalação D62***2450 · Energi |
| 99 | 1672×2395 | Criar atividade · Tipo · Ligação · Título * · Confirmar envio do comprovante de endereço |
| 100 | 1672×2395 | Criar chamado · Título * · Leitura divergente na UC Silo 2 · Categoria · Prioridade |
| 101 | 1672×2395 | Gerar cobrança · Mês de referência · 10/2026 · Envio · E-mail e WhatsApp |
| 102 | 1672×1100 | Exportar associados · Formato · .csv · Texto separado por vírgulas · Registros |
| 103 | 1672×1100 | Exportação pronta · Arquivo “associados-19-10-2026.xlsx” gerado com 8 registros. · Download demonstrativo — nenhum arquivo real é baixado no |
| 104 | 1672×1100 | Descartar alterações? · As informações preenchidas serão perdidas. Nenhum · cadastro foi criado ou alterado. · Continuar editando · Descarta |
| 105 | 1672×1100 | Identifique informações incompletas e acompanhe a regularização dos cadastros. · Pendências em aberto · Associados com pendências · 23 · 17 |
| 106 | 1672×1100 | Identifique informações incompletas e acompanhe a regularização dos cadastros. · Pendências em aberto · Associados com pendências · 23 · 17 |
| 107 | 1672×1100 | Identifique informações incompletas e acompanhe a regularização dos cadastros. · Pendências em aberto · Associados com pendências · 24 · 18 |
| 108 | 1672×1100 | Revisar pendência · Documento de identificação ausente · Associado · João Pedro Almeida · PF · CPF ***.***.214-** · Registro afetado |
| 109 | 1672×1100 | Revisar pendência · Documento de identificação ausente · Associado · João Pedro Almeida · PF · CPF ***.***.214-** · Registro afetado |
| 110 | 1672×1100 | Revisar pendência · Documento de identificação ausente · Associado · João Pedro Almeida · PF · CPF ***.***.214-** · Registro afetado |
| 111 | 1672×1100 | Revisar pendência · Documento de identificação ausente · Associado · João Pedro Almeida · PF · CPF ***.***.214-** · Registro afetado |
| 112 | 1672×1100 | Exportar histórico · Formato · .csv · Texto separado por vírgulas · Escopo |
| 113 | 1672×1100 | Exportação pronta · “historico-alteracoes-19-10-2026.xlsx” gerado com 146 registros. · Download demonstrativo. · Concluir |
| 114 | 1672×1100 | Detalhes da alteração · Telefone atualizado · Evento · EVT-20261019-0842-1187 · Data e hora |
| 115 | 1672×1100 | Detalhes da alteração · Endereço atualizado · Evento · EVT-20261019-0835-1186 · Data e hora |
| 116 | 1672×1100 | Filtros · Situação da assinatura · Aguardando assinatura · Assinatura parcial · Assinado |
| 117 | 1672×1100 | Visualizar documento · Copiar link de assinatura · Consultar signatários · Ver detalhes · Cancelar solicitação |
| 118 | 1672×1100 | Visualizar documento · Consultar signatários · Baixar documento assinado · Ver detalhes |
| 119 | 1672×1100 | Visualizar documento · Consultar signatários · Ver detalhes · Copiar link (indisponível) |
| 120 | 1672×1100 | Revisar pendência · Dados da UC incompletos · Associado · Rafael Augusto Lima · PF · CPF ***.***.537-** · Registro afetado |
| 121 | 1672×1100 | Revisar pendência · Dados da UC incompletos · Associado · Rafael Augusto Lima · PF · CPF ***.***.537-** · Registro afetado |
| 122 | 1672×1100 | Revisar pendência · CPF em conflito com outro cadastro · Associado · Beatriz Souza Martins · PF · Registro afetado |
| 123 | 1672×1100 | Revisar pendência · CPF em conflito com outro cadastro · Associado · Beatriz Souza Martins · PF · Registro afetado |
| 124 | 1672×1100 | Detalhes da alteração · Documento substituído · Evento · EVT-20261019-0821-1185 · Data e hora |
| 125 | 1672×1100 | Detalhes da alteração · Responsável alterado · Evento · EVT-20261017-1028-1172 · Data e hora |
| 126 | 1672×1100 | Detalhes da alteração · Situação alterada para Ativo · Evento · EVT-20261018-1532-1179 · Data e hora |
| 127 | 1672×1100 | Detalhes da alteração · Assinaturas concluídas · Evento · EVT-20261017-1154-1173 · Data e hora |
| 128 | 1672×854 | Associados / Todos os associados / Agropecuária Aurora · Agropecuária Aurora · PJ · CNPJ 22.333.***/0001-55 · Nº cliente 282334417 · Criado  |
| 129 | 1672×987 | Associados / Todos os associados / Agropecuária Aurora · Agropecuária Aurora · PJ · CNPJ 22.333.***/0001-55 · Nº cliente 282334417 · Criado  |
| 130 | 1672×1654 | Associados / Todos os associados / Agropecuária Aurora · Agropecuária Aurora · PJ · CNPJ 22.333.***/0001-55 · Nº cliente 282334417 · Criado  |
| 131 | 1672×817 | Associados / Todos os associados / Agropecuária Aurora · Agropecuária Aurora · PJ · CNPJ 22.333.***/0001-55 · Nº cliente 282334417 · Criado  |
| 132 | 1672×2395 | Captura automática de faturas · Quando ativada, as faturas da concessionária das UCs deste associado · são capturadas mensalmente pela integ |
| 133 | 1672×2395 | Enviar proposta · Proposta · PC-2026-0311 · Expansão — Armazéns · Canal · E-mail para ric***@aurora.com.br |
| 134 | 1672×2395 | Link copiado · Link da página de Agropecuária Aurora copiado. |
| 135 | 1672×2395 | Registrado com sucesso · A alteração foi registrada na linha do tempo do associad |
| 136 | 1672×1100 | Signatários · Aditivo de UC — Ordenha · Aditivo de UC — Ordenha · Agropecuária Aurora · 1 de 2 · Assinatura parcial · Ricardo Alves |
| 137 | 1672×1100 | Aditivo de UC — Ordenha — Agropecuária Aurora · Versão gerada em 16/10/2026 · assinatura parcial · ADITIVO DE INCLUSÃO DE UC · Modelo Aditiv |
| 138 | 1672×1100 | Cancelar solicitação de assinatura · Etapa 1 de 2 · Motivo · Documento · Aditivo de UC — Ordenha · Associado |
| 139 | 1672×1100 | Confirmar cancelamento? · Etapa 2 de 2 · Confirmação · A solicitação de assinatura do Aditivo de UC — Ordenha · (Agropecuária Aurora) será c |
| 140 | 1672×1100 | Revisar pendência · CPF em conflito com outro cadastro · Associado · Beatriz Souza Martins · PF · Registro afetado |
| 141 | 1672×1100 | Detalhes da alteração · Cadastro criado · Evento · EVT-20261016-1705-1160 · Data e hora |
| 142 | 1672×1100 | Detalhes da alteração · Possível duplicidade identificada · Evento · EVT-20261016-1730-1161 · Data e hora |
| 143 | 1672×1100 | Detalhes da alteração · Pendência resolvida · Evento · EVT-20261019-0914-1190 · Data e hora |
| 144 | 1672×1100 | Detalhes da alteração · Telefone atualizado · Evento · EVT-20261019-0914-1189 · Data e hora |
| 145 | 1672×835 | Associados / Todos os associados / Agropecuária Aurora · Agropecuária Aurora · PJ · CNPJ 22.333.***/0001-55 · Nº cliente 282334417 · Criado  |
| 146 | 1672×1100 | Associados / Todos os associados / Larissa Moreira Santos · Larissa Moreira Santos · PF · CPF ***.***.448-** · Nº cliente 282334590 · Criado |
| 147 | 1672×2467 | Associados / Todos os associados / Agropecuária Aurora / Editar cadastro · Editar cadastro · Agropecuária Aurora · As alterações ficam regis |
| 148 | 1672×2428 | Associados / Todos os associados / Novo associado · Novo associado · Cadastre um cliente PF ou PJ. Campos com * são obrigatórios. · Tipo de  |
| 149 | 1672×1100 | Exportar contratos · Formato · .csv · Texto separado por vírgulas · Registros |
| 150 | 1672×1100 | Exportação pronta · Arquivo “contratos-19-10-2026.xlsx” gerado com 128 registros. · Download demonstrativo — nenhum arquivo real é baixado n |
| 151 | 1672×1100 | Link de assinatura copiado · Envie o link pelo canal combinado com o signatário. |
| 152 | 1672×1100 | Download iniciado · Termo de Adesão — Prefeitura de Maracaju (PDF). Demo |
| 153 | 1672×1100 | Detalhes da alteração · Unidade consumidora cadastrada · Evento · EVT-20261018-1648-1180 · Data e hora |
| 154 | 1672×1291 | Filtros de UCs · Status · Faturando · Contratada · Em contratação |
| 155 | 1672×1100 | Termo de Adesão — Prefeitura de Maracaju · Assinaturas concluídas em 15/10/2026 · 2 de 2 · TERMO DE ADESÃO · Modelo Termo de Adesão · versão |
| 156 | 1672×2718 | Associados / Todos os associados / Novo associado · Novo associado · Cadastre um cliente PF ou PJ. Campos com * são obrigatórios. · Tipo de  |
| 157 | 1672×2563 | Associados / Todos os associados / Novo associado · Novo associado · Cadastre um cliente PF ou PJ. Campos com * são obrigatórios. · Corrija  |
| 158 | 1672×2718 | Associados / Todos os associados / Novo associado · Novo associado · Cadastre um cliente PF ou PJ. Campos com * são obrigatórios. · Tipo de  |
| 159 | 1672×2806 | Associados / Todos os associados / Novo associado · Novo associado · Cadastre um cliente PF ou PJ. Campos com * são obrigatórios. · Não foi  |
| 160 | 1672×1179 | Faturas · Cobranças · Links de renegociação · Cobranças · Acompanhe emissão, recebimento e saldo das cobranças dos associados. |
| 161 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Cobranças · Acompanhe emissão, recebimento e saldo das cobranças dos associados. |
| 162 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Cobranças · Acompanhe emissão, recebimento e saldo das cobranças dos associados. |
| 163 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Cobranças · Acompanhe emissão, recebimento e saldo das cobranças dos associados. |
| 164 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Financeiro / Cobranças / Gerar cobrança · Gerar cobrança |
| 165 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Financeiro / Cobranças / Gerar cobrança · Gerar cobrança |
| 166 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Financeiro / Cobranças / Gerar cobrança · Gerar cobrança |
| 167 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Financeiro / Cobranças / Gerar cobrança · Gerar cobrança |
| 168 | 1672×1122 | Faturas · Cobranças · Links de renegociação · Financeiro / Cobranças / Gerar em lote · Gerar cobranças em lote |
| 169 | 1672×1155 | Faturas · Cobranças · Links de renegociação · Financeiro / Cobranças / Gerar em lote · Gerar cobranças em lote |
| 170 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Financeiro / Cobranças / Gerar em lote · Gerar cobranças em lote |
| 171 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Financeiro / Cobranças / Gerar em lote · Gerar cobranças em lote |
| 172 | 1672×1068 | Faturas · Cobranças · Links de renegociação · Financeiro / Cobranças / Gerar cobranças em lote · Gerar cobranças em lote |
| 173 | 1672×1070 | Faturas · Cobranças · Links de renegociação · Financeiro / Cobranças / Gerar cobranças em lote · Gerar cobranças em lote |
| 174 | 1672×790 | Faturas · Cobranças · Links de renegociação · Financeiro / Cobranças / Gerar cobranças em lote · Gerar cobranças em lote |
| 175 | 1672×900 | Faturas · Cobranças · Links de renegociação · Financeiro / Cobranças / Gerar cobranças em lote · Gerar cobranças em lote |
| 176 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Links de renegociação · Renegociação de débitos e cobranças avulsas por link, processadas pela |
| 177 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Links de renegociação · Renegociação de débitos e cobranças avulsas por link, processadas pela |
| 178 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Links de renegociação · Renegociação de débitos e cobranças avulsas por link, processadas pela |
| 179 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Financeiro / Links de renegociação / Renegociar débitos · Renegociar débitos |
| 180 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Financeiro / Links de renegociação / Renegociar débitos · Renegociar débitos |
| 181 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Financeiro / Links de renegociação / Renegociar débitos · Renegociar débitos |
| 182 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Financeiro / Links de renegociação / Renegociar débitos · Renegociar débitos |
| 183 | 1672×1165 | Filtros · Situação financeira · Aguardando pagamento · Vencida · Parcialmente paga |
| 184 | 1672×1165 | Filtrar por situação, associado, UC, responsável e datas |
| 185 | 1672×1165 | Como ler os indicadores · Valor emitido · Soma das cobranças com vencimento no período · selecionado. · Valor recebido |
| 186 | 1672×1165 | Ver detalhes · Baixar fatura (PDF) · Renegociar débitos · Ver associado |
| 187 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Cobranças · Acompanhe emissão, recebimento e saldo das cobranças dos associados. |
| 188 | 1672×1197 | Faturas · Cobranças · Links de renegociação · Cobranças · Acompanhe emissão, recebimento e saldo das cobranças dos associados. |
| 189 | 1672×1258 | Faturas · Cobranças · Links de renegociação · Financeiro / Cobranças / COB-10514 · COB-10514 · Fernanda Oliveira Costa |
| 190 | 1672×1191 | Faturas · Cobranças · Links de renegociação · Financeiro / Cobranças / COB-10518 · COB-10518 · Rafael Augusto Lima |
| 191 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Financeiro / Cobranças / Gerar cobrança · Gerar cobrança |
| 192 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Financeiro / Cobranças / Gerar cobrança · Gerar cobrança |
| 193 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Financeiro / Cobranças / Gerar cobrança · Gerar cobrança |
| 194 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Financeiro / Cobranças / Gerar cobrança · Gerar cobrança |
| 195 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Financeiro / Cobranças / Gerar em lote · Gerar cobranças em lote |
| 196 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Financeiro / Cobranças / Gerar em lote · Gerar cobranças em lote |
| 197 | 1672×906 | Faturas · Cobranças · Links de renegociação · Financeiro / Cobranças / Gerar cobranças em lote · Gerar cobranças em lote |
| 198 | 1672×958 | Faturas · Cobranças · Links de renegociação · j · Financeiro / Cobranças / Gerar cobranças em lote |
| 199 | 1672×1336 | Faturas · Cobranças · Links de renegociação · Financeiro / Links de renegociação / LNK-0042 · Renegociação — João Pedro |
| 200 | 1672×1165 | Faturas · Cobranças · Links de renegociação · Financeiro / Cobranças / COB-10519 · COB-10519 · Beatriz Souza Martins |
| 201 | 1672×1404 | Faturas · Cobranças · Links de renegociação · Financeiro / Links de renegociação / LNK-0041 · Renegociação — Carlos Eduardo |
| 202 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Financeiro / Links de renegociação / Renegociar débitos · Renegociar débitos |
| 203 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Financeiro / Links de renegociação / Renegociar débitos · Renegociar débitos |
| 204 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Financeiro / Links de renegociação / Renegociar débitos · Renegociar débitos |
| 205 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Financeiro / Links de renegociação / Renegociar débitos · Renegociar débitos |
| 206 | 1672×1100 | Filtros · Finalidade · Renegociação · Cobrança avulsa · Situação do link |
| 207 | 1672×1151 | Faturas · Cobranças · Links de renegociação · Financeiro / Cobranças / COB-10519 · COB-10519 · Beatriz Souza Martins |
| 208 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Financeiro / Links de renegociação / Renegociar débitos · Renegociar débitos |
| 209 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Financeiro / Links de renegociação / Renegociar débitos · Renegociar débitos |
| 210 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Financeiro / Links de renegociação / Renegociar débitos · Renegociar débitos |
| 211 | 1672×1165 | Exportar cobranças · Formato · .csv · Texto separado por vírgulas · Registros |
| 212 | 1672×1165 | Baixar PDFs em lote · Escopo · Cobranças selecionadas · 3 cobranças · 3 com documento final · Resultados atuais |
| 213 | 1672×1165 | Disponibilizar fatura ao associado · Envie a fatura COB-10522 e os meios de pagamento para Patrícia · Gomes Lima. Nada é enviado sem esta co |
| 214 | 1672×1165 | Exportar links de renegociação · Formato · .csv · Texto separado por vírgulas · Registros |
| 215 | 1672×1165 | Ajustar juros e multa · Disponível apenas para perfil autorizado (financeiro/administrador). · Valor calculado pela configuração do cliente  |
| 216 | 1672×1165 | Prévia do link de renegociação · Em Conta — Energia Renovável · Renegociação — Fernanda · R$ 484,27 · Pix · vence em 26/10/2026 |
| 217 | 1672×1165 | Compartilhar link de renegociação · Canal · WhatsApp · (67) 9****-0912 · contato principal · E-mail |
| 218 | 1672×1165 | Criar link de renegociação · Renegociação de débitos — preenche o valor com as cobranças · vencidas do associado · Nome * · Associado |
| 219 | 1672×1165 | Descartar alterações? · As informações preenchidas serão perdidas. Nenhuma · cobrança ou link foi criado. · Continuar editando · Descartar |
| 220 | 1672×1165 | Link copiado · Cole o link no canal combinado com o associado. |
| 221 | 1672×1165 | Envio confirmado · Registro adicionado ao histórico (demonstração). |
| 222 | 1672×1165 | Download iniciado · Arquivo demonstrativo — nenhum documento real é baix |
| 223 | 1672×1165 | Juros e multa ajustados · R$ 14,32 → R$ 10,00 · justificativa registrada por João Lu |
| 224 | 1672×1165 | Exportação gerada · Arquivo demonstrativo com todas as páginas do escopo |
| 225 | 1672×1100 | Oportunidades · Entrada, negociação e conversão de oportunidades em associados. · Oportunidades · 50 abertas · 01/10/2026 – 19/10/2026 |
| 226 | 1672×1100 | Oportunidades · Entrada, negociação e conversão de oportunidades em associados. · Oportunidades · 50 abertas · 01/10/2026 – 19/10/2026 |
| 227 | 1672×1100 | Oportunidades · Entrada, negociação e conversão de oportunidades em associados. · Oportunidades · 50 abertas · 01/11/2026 – 19/11/2026 |
| 228 | 1672×1100 | Oportunidades · Entrada, negociação e conversão de oportunidades em associados. · Oportunidades · 50 abertas · 01/10/2026 – 19/10/2026 |
| 229 | 1672×1100 | Oportunidades · Oportunidades em andamento e resultados comerciais. Antes da assinatura, o potencial cliente não aparece em Associados. · Op |
| 230 | 1672×1100 | Oportunidades · Oportunidades em andamento e resultados comerciais. Antes da assinatura, o potencial cliente não aparece em Associados. · Op |
| 231 | 1672×1100 | Oportunidades · Oportunidades em andamento e resultados comerciais. Antes da assinatura, o potencial cliente não aparece em Associados. · Op |
| 232 | 1672×1100 | Oportunidades · Oportunidades em andamento e resultados comerciais. Antes da assinatura, o potencial cliente não aparece em Associados. · Op |
| 233 | 1672×1100 | Larissa Moreira Santos · Oportunidades · OP-0412 · Pessoa física · Origem: WhatsApp · Entrada 19/10/2026 · Mover para · Nova tarefa |
| 234 | 1672×1100 | Larissa Moreira Santos · Em negociação · OP-0412 · Pessoa física · Origem: WhatsApp · Entrada 19/10/2026 · Mover para · Responsável: Pedro C |
| 235 | 1672×1100 | Larissa Moreira Santos · Pendente assinatura · OP-0412 · Pessoa física · Origem: WhatsApp · Entrada 19/10/2026 · Mover para · Nova tarefa |
| 236 | 1672×1100 | Larissa Moreira Santos · Pendente assinatura · OP-0412 · Pessoa física · Origem: WhatsApp · Entrada 19/10/2026 · Mover para · Nova tarefa |
| 237 | 1672×1100 | Nova oportunidade · Nome completo * · Telefone (WhatsApp) * · Larissa Moreira Santos · (67) 9****-7731 |
| 238 | 1672×1100 | Nova oportunidade · Corrija os 2 campos destacados para salvar. · Nome completo * · Telefone (WhatsApp) * · (67) 9999 |
| 239 | 1672×1100 | Nova oportunidade · Possível duplicidade: o telefone (67) 9****-0912 já está vinculado a um cadastro · existente. · Nome completo * · Telefo |
| 240 | 1672×1100 | Atribuir e iniciar negociação · Larissa Moreira Santos · OP-0412 · Responsável comercial · Pedro Costa · Será registrado: autoria (João Luca |
| 241 | 1672×1100 | Oportunidades · Oportunidades em andamento e resultados comerciais. Antes da assinatura, o potencial cliente não aparece em Associados. · Op |
| 242 | 1672×1128 | Oportunidades · Oportunidades em andamento e resultados comerciais. Antes da assinatura, o potencial cliente não aparece em Associados. · Op |
| 243 | 1672×1140 | Oportunidades · Oportunidades em andamento e resultados comerciais. Antes da assinatura, o potencial cliente não aparece em Associados. · Op |
| 244 | 1672×1138 | Oportunidades · Oportunidades em andamento e resultados comerciais. Antes da assinatura, o potencial cliente não aparece em Associados. · Op |
| 245 | 1672×1100 | Larissa Moreira Santos · Pendente assinatura · OP-0412 · Pessoa física · Origem: WhatsApp · Entrada 19/10/2026 · Mover para · Nova tarefa |
| 246 | 1672×1100 | Larissa Moreira Santos · OP-0412 · Pessoa física · Origem: WhatsApp · Entrada 19/10/2026 · Ver associado · Responsável: Pedro Costa · PC |
| 247 | 1672×1100 | Bruno Henrique Teles · Oportunidades · OP-0410 · Pessoa física · Origem: LP NOVO · Entrada 18/10/2026 · Mover para · Nova tarefa |
| 248 | 1672×1100 | Sônia Regina Prado · Em negociação · OP-0399 · Pessoa física · Origem: LP NOVO · Entrada 15/10/2026 · Marcar como perdido · Responsável: Ped |
| 249 | 1672×1100 | Gerar contrato · Conferência antes de gerar (Larissa Moreira Santos · OP-0412) · Dados cadastrais e contato completos · Concluído · Proposta |
| 250 | 1672×1100 | Marcar como perdido · Diego Ramos Vieira · OP-0377 · Em negociação · Motivo * · Optou por outra empresa · Observação |
| 251 | 1672×1100 | Registrar “Não se encaixa” · Sônia Regina Prado · OP-0399 · Critério não atendido * · Consumo médio mínimo de 155 kWh · Motivo * * |
| 252 | 1672×1100 | Nova tarefa · Título * · Confirmar data preferencial de vencimento · Responsável · Prazo |
| 253 | 1672×1100 | Oportunidades · Oportunidades em andamento e resultados comerciais. Antes da assinatura, o potencial cliente não aparece em Associados. · Op |
| 254 | 1672×1100 | Oportunidades · Oportunidades em andamento e resultados comerciais. Antes da assinatura, o potencial cliente não aparece em Associados. · Op |
| 255 | 1672×1100 | Oportunidades · Oportunidades em andamento e resultados comerciais. Antes da assinatura, o potencial cliente não aparece em Associados. · Op |
| 256 | 1672×1100 | Diego Ramos Vieira · Em negociação · OP-0377 · Pessoa física · Origem: WhatsApp · Entrada 05/10/2026 · Mover para · Responsável: Mariana Alv |
| 257 | 1672×1100 | Ver detalhes · Nova tarefa · Mover para · Pendente assinatura · Exige contrato criado → Gerar contrato |
| 258 | 1672×1100 | Não é possível mover para Associados · Diego Ramos Vieira ainda não tem contrato assinado. A conversão em · associado acontece automaticamen |
| 259 | 1672×1100 | Filtros · Etapa ou resultado · Oportunidades · Em negociação · Pendente assinatura |
| 260 | 1672×1100 | Filtrar por etapa, origem, responsável, tarefas e período |
| 261 | 1672×1100 | Descartar alterações? · Os dados preenchidos nesta oportunidade serão perdidos. · Continuar editando · Descartar |
| 262 | 1672×1100 | Tarefa salva · Responsável e prazo registrados no histórico. |
| 263 | 1672×1100 | Envio refeito · Mesmo documento v3 reenviado — nenhum contrato no |
| 264 | 1672×1100 | Usinas · Cadastro e consulta das usinas e de seus vínculos cadastrais com unidades consumidoras. · Buscar por nome ou identificador… · Usina |
| 265 | 1672×1100 | Usinas · Cadastro e consulta das usinas e de seus vínculos cadastrais com unidades consumidoras. · Buscar por nome ou identificador… · Filtr |
| 266 | 1672×1100 | Usinas · Cadastro e consulta das usinas e de seus vínculos cadastrais com unidades consumidoras. · Santa Clara · Usina · Identificador |
| 267 | 1672×1100 | Usinas · Cadastro e consulta das usinas e de seus vínculos cadastrais com unidades consumidoras. · Buscar por nome ou identificador… · Usina |
| 268 | 1672×1214 | Usinas › Fazenda Araras · Fazenda Araras · USN-0001 · Sidrolândia/MS · Responsável: Mariana Alves · Unidades consumidoras (18) · Informações |
| 269 | 1672×1100 | Usinas · Cadastro e consulta das usinas e de seus vínculos cadastrais com unidades consumidoras. · Buscar por nome ou identificador… · Usina |
| 270 | 1672×1603 | Usinas › Fazenda Araras · Fazenda Araras · USN-0001 · Sidrolândia/MS · Responsável: Mariana Alves · Unidades consumidoras (18) · Informações |
| 271 | 1672×1655 | Usinas › Fazenda Araras · Fazenda Araras · USN-0001 · Sidrolândia/MS · Responsável: Mariana Alves · Unidades consumidoras (18) · Informações |
| 272 | 1672×1655 | Usinas › Fazenda Araras · Fazenda Araras · USN-0001 · Sidrolândia/MS · Responsável: Mariana Alves · Unidades consumidoras (18) · Informações |
| 273 | 1672×1100 | Filtros · UF · MS · Cidade · Campo Grande |
| 274 | 1672×1100 | Filtrar por localização, responsável e vínculos |
| 275 | 1672×1100 | Filtros · Concessionária · Energisa MS · Cidade da UC · Sidrolândia |
| 276 | 1672×1100 | Exportar · Formato · .csv · Texto separado por vírgulas · Registros |
| 277 | 1672×1214 | Usinas › Fazenda Araras · Fazenda Araras · USN-0001 · Sidrolândia/MS · Responsável: Mariana Alves · Unidades consumidoras (18) · Informações |
| 278 | 1672×1100 | Usinas › Fazenda Araras · Fazenda Araras · USN-0001 · Sidrolândia/MS · Responsável: Mariana Alves · Unidades consumidoras (18) · Informações |
| 279 | 1672×1100 | Usinas › Fazenda Araras · Fazenda Araras · USN-0001 · Sidrolândia/MS · Responsável: Mariana Alves · Unidades consumidoras (18) · Informações |
| 280 | 1672×1100 | Usinas › Fazenda Araras · Fazenda Araras · USN-0001 · Sidrolândia/MS · Responsável: Mariana Alves · Unidades consumidoras (19) · Informações |
| 281 | 1672×1100 | Ver detalhes · Editar informações · Vincular unidade consumidora |
| 282 | 1672×1100 | Nova usina · Nome da usina * · Responsável interno · San Vito 2 · Mariana Alves |
| 283 | 1672×1100 | Nova usina · Corrija 1 campo obrigatório para salvar. · Nome da usina * · Responsável interno · Mariana Alves |
| 284 | 1672×1100 | Nova usina · Já existe uma usina com nome semelhante: São Miguel (USN-0002 · · Maracaju/MS). Confira para evitar duplicidade (RN-CLI-04). ·  |
| 285 | 1672×1100 | Usinas › San Vito 2 · San Vito 2 · USN-0008 · Rio Brilhante/MS · Responsável: Mariana Alves · Unidades consumidoras (0) · Informações |
| 286 | 1672×1100 | Usinas › Fazenda Araras · Fazenda Araras · USN-0001 · Sidrolândia/MS · Responsável: Mariana Alves · Unidades consumidoras (18) · Informações |
| 287 | 1672×1100 | Usinas › Fazenda Araras · Fazenda Araras · USN-0001 · Sidrolândia/MS · Responsável: Mariana Alves · Unidades consumidoras (19) · Informações |
| 288 | 1672×1100 | Usinas › San Vito 2 · San Vito 2 · USN-0008 · Rio Brilhante/MS · Responsável: Mariana Alves · Unidades consumidoras (0) · Informações |
| 289 | 1672×1100 | Vincular unidade consumidora · Usina: Fazenda Araras (USN-0001) · 6*** · UCs já cadastradas · UC 6***8840 · Larissa Moreira Santos |
| 290 | 1672×1100 | Vincular unidade consumidora · UC 6***8840 · Larissa Moreira Santos · Energisa MS · Campo Grande/MS · Sem vínculo · Vínculo atual |
| 291 | 1672×1100 | Esta UC já tem vínculo com outra usina · UC 6***2190 · Rafael Augusto Lima · Energisa MS · Terenos/MS · Vale Verde · Vinculada a outra usina |
| 292 | 1672×1100 | UC 6***4120 · Unidade consumidora · Energisa MS · Identificação · Número da UC · 6***4120 |
| 293 | 1672×1100 | Descartar alterações? · As alterações não salvas serão perdidas. · Continuar editando · Descartar |
| 294 | 1672×1100 | Exportação gerada · Arquivo demonstrativo — nenhum arquivo real é gerado |
| 295 | 1672×1100 | Docs · Crie e gerencie modelos de contratos. · Buscar modelo por nome… · Modelo · Tipo de contrato |
| 296 | 1672×1100 | Docs · Crie e gerencie modelos de contratos. · Buscar modelo por nome… · Filtros aplicados: · Limpar filtros |
| 297 | 1672×1100 | Docs · Crie e gerencie modelos de contratos. · Locação · Modelo · Tipo de contrato |
| 298 | 1672×1100 | Docs · Crie e gerencie modelos de contratos. · Buscar modelo por nome… · Modelo · Tipo de contrato |
| 299 | 1672×1100 | Termo de Adesão — PF (Indicação) · v1 · rascunho · Adesão · modelo novo · Parágrafo · Times New Roman |
| 300 | 1672×1100 | Termo de Adesão — Pessoa Física · Alterações não salvas · v4 · rascunho · Adesão · baseado na v3 (disponível) · Parágrafo |
| 301 | 1672×1100 | Termo de Adesão — Pessoa Física · v4 · rascunho · Adesão · baseado na v3 (disponível) · Parágrafo · Times New Roman |
| 302 | 1672×1100 | Termo de Adesão — Pessoa Física · Alterações não salvas · v4 · rascunho · Adesão · baseado na v3 (disponível) · Parágrafo |
| 303 | 1672×1100 | Prévia · Termo de Adesão — Pessoa Física · v4 · rascunho · somente leitura · Campos destacados · Dados ilustrativos. A prévia não gera contr |
| 304 | 1672×1100 | Prévia · Termo de Adesão — Pessoa Física · v4 · rascunho · somente leitura · Campos destacados · Dados ilustrativos. A prévia não gera contr |
| 305 | 1672×1100 | Prévia · Termo de Adesão — Pessoa Física · v4 · rascunho · somente leitura · Documento preenchido · Campos destacados · Dados ilustrativos.  |
| 306 | 1672×1100 | Prévia · Termo de Adesão — Pessoa Física · v4 · rascunho · somente leitura · Campos destacados · Dados ilustrativos. A prévia não gera contr |
| 307 | 1672×1100 | Novo modelo · Nome do modelo * · Termo de Adesão — PF (Indicação) · Tipo de contrato · Adesão |
| 308 | 1672×1100 | Novo modelo · Corrija os campos destacados. · Nome do modelo * · Informe um nome para o modelo. · Tipo de contrato |
| 309 | 1672×1100 | Identificação do modelo · Nome do modelo * · Termo de Adesão — Pessoa Física · Tipo de contrato · Adesão |
| 310 | 1672×1100 | Histórico de versões · Termo de Adesão — Pessoa Física · Inclui vencimento preferencial e bloco de UCs · Rascunho · v4 |
| 311 | 1672×1100 | Docs · Crie e gerencie modelos de contratos. · Buscar modelo por nome… · Modelo · Tipo de contrato |
| 312 | 1672×1100 | Docs · Crie e gerencie modelos de contratos. · Seu perfil tem acesso somente leitura aos modelos: é possível visualizar e consultar versões, |
| 313 | 1672×1100 | Docs · Crie e gerencie modelos de contratos. · Buscar modelo por nome… · Modelo · Tipo de contrato |
| 314 | 1672×1100 | Termo de Adesão — Pessoa Física · v4 · rascunho · Adesão · baseado na v3 (disponível) · Parágrafo · Times New Roman |
| 315 | 1672×1100 | Termo de Adesão — Pessoa Física · v4 · rascunho · Adesão · baseado na v3 (disponível) · Parágrafo · Times New Roman |
| 316 | 1672×1100 | Termo de Adesão — Pessoa Física · Falha ao salvar · v4 · rascunho · Adesão · baseado na v3 (disponível) · Parágrafo |
| 317 | 1672×1100 | Termo de Adesão — Pessoa Física · v2 · substituída · Somente leitura · usada em 9 contratos · Parágrafo · Times New Roman |
| 318 | 1672×1100 | Criar nova versão para editar · A v3 já está disponível e foi usada em 14 contratos. Editar cria a v4 em · rascunho. · A v3 continua disponí |
| 319 | 1672×1100 | Disponibilizar v4 para uso · Modelo · Versão · Campos dinâmicos · Termo de Adesão — PF |
| 320 | 1672×1100 | Não é possível disponibilizar a v4 · Validação · Nome do modelo preenchido · Concluído · Conteúdo do documento |
| 321 | 1672×1100 | Substituir “Prazo de vigência” · prazo · Nenhum campo do catálogo corresponde a “prazo”. · Opção: remover o campo e escrever o prazo no text |
| 322 | 1672×1100 | Sair sem salvar? · Há alterações não salvas na v4 (rascunho). Se sair agora, elas serão · perdidas. · Sair sem salvar · Continuar editando |
| 323 | 1672×1100 | Filtros · Tipo de contrato · Adesão · Cancelamento · Versão |
| 324 | 1672×1100 | Filtrar por tipo de contrato e ve |
| 325 | 1672×1100 | Abrir no editor · Visualizar prévia · Histórico de versões · Renomear |
| 326 | 1672×1100 | Selecionar modelo do contrato · Larissa Moreira Santos · OP-0412 · Pessoa física · Modelos com versão disponível · Termo de Adesão — Pessoa  |
| 327 | 1672×1100 | Conferir dados do contrato · Modelo · Versão · Pessoa · Termo de Adesão — PF |
| 328 | 1672×1100 | Contrato gerado · CT-2026-0188 · Gerado com Termo de Adesão — Pessoa Física v3. O conteúdo e a referência desta versão ficam preservados no  |
| 329 | 1672×1100 | Conta, empresa, acessos, preferências e parâmetros autorizados. · Minha conta · Minha conta · Seus dados de acesso e contato · Empresa |
| 330 | 1672×1100 | Conta, empresa, acessos, preferências e parâmetros autorizados. · Minha conta · Minha conta · Seus dados de acesso e contato · Empresa |
| 331 | 1672×1100 | Conta, empresa, acessos, preferências e parâmetros autorizados. · Corrija 1 campo para salvar. · Minha conta · Empresa · Minha conta |
| 332 | 1672×1100 | Conta, empresa, acessos, preferências e parâmetros autorizados. · Não foi possível salvar agora. Seus dados foram mantidos — tente novamente |
| 333 | 1672×1100 | Conta, empresa, acessos, preferências e parâmetros autorizados. · Minha conta · Minha conta · Seus dados de acesso e contato · Empresa |
| 334 | 1672×1100 | Conta, empresa, acessos, preferências e parâmetros autorizados. · Alterações nos dados da empresa valem para documentos futuros. Contratos e |
| 335 | 1672×1100 | Conta, empresa, acessos, preferências e parâmetros autorizados. · Alterações nos dados da empresa valem para documentos futuros. Contratos e |
| 336 | 1672×1100 | Conta, empresa, acessos, preferências e parâmetros autorizados. · Minha conta · Usuários e permissões · Perfis existentes: Comercial, Atendi |
| 337 | 1672×1100 | Sua conta e suas preferências. · Minha conta · Preferências de notificações · Atalhos · Seções administrativas não |
| 338 | 1672×1505 | Conta, empresa, acessos, preferências e parâmetros autorizados. · Canal disponível nesta etapa: notificações dentro do sistema. E-mail, What |
| 339 | 1672×1100 | Conta, empresa, acessos, preferências e parâmetros autorizados. · Somente parâmetros documentados aparecem aqui. Alterações valem para opera |
| 340 | 1672×1100 | Conta, empresa, acessos, preferências e parâmetros autorizados. · Minha conta · Auditoria · Alterações administrativas e de configuração · s |
| 341 | 1672×1100 | Conta, empresa, acessos, preferências e parâmetros autorizados. · Minha conta · Empresa · Usuários e permissões · Preferências de notificaçõ |
| 342 | 1672×1100 | Como podemos ajudar? · Guias rápidos dos módulos disponíveis no Em Conta. · Buscar por assunto, por exemplo “link de renegociação”… · Catego |
| 343 | 1672×1100 | Como podemos ajudar? · Guias rápidos dos módulos disponíveis no Em Conta. · associado · 4 resultados para “associado” · Quando a oportunidad |
| 344 | 1672×1100 | Como podemos ajudar? · Guias rápidos dos módulos disponíveis no Em Conta. · rateio automático · Nenhum artigo encontrado · Não há artigos pa |
| 345 | 1672×1100 | Ajuda › Funil de vendas · Oportunidades, qualificação e conversão em associados. · Quando a oportunidade vira associado? · Funil de vendas · |
| 346 | 1672×1100 | Ajuda › Funil de vendas › Quando a oportunidade vira associado? · Quando a oportunidade vira associado? · Funil de vendas · leitura de 2 min |
| 347 | 1672×1100 | Como podemos ajudar? · Guias rápidos dos módulos disponíveis no Em Conta. · Buscar por assunto, por exemplo “link de renegociação”… · Não fo |
| 348 | 1672×1100 | Entrar · E-mail · Senha · Entrar · Esqueci minha senha (demonstrativo) |
| 349 | 1672×1100 | Sua sessão expirou por inatividade. Entre · novamente para continuar. · Entrar novamente · E-mail · joao.lucas@emconta.com.br |
| 350 | 1672×1100 | Notificações · 3 não lidas · Todas · Não lidas · 3 |
| 351 | 1672×1100 | Notificações · 3 não lidas · Todas · Não lidas · 3 |
| 352 | 1672×1100 | Notificações · 2 não lidas · Todas · Não lidas · 2 |
| 353 | 1672×1100 | Notificações · Todas · Não lidas · 0 · Marcar todas como lidas |
| 354 | 1672×1100 | Notificações · Todas · Não lidas · 0 · Marcar todas como lidas |
| 355 | 1672×1100 | Notificações · Todas · Não lidas · 0 · Marcar todas como lidas |
| 356 | 1672×1100 | Notificações · Todas · Não lidas · 0 · Marcar todas como lidas |
| 357 | 1672×1100 | Notificações · 2 não lidas · Todas · Não lidas · 2 |
| 358 | 1672×1100 | joao.lucas@emconta.com.br · Administrador · Minha conta · Preferências de notificações · Tema |
| 359 | 1672×1100 | Claro · Escuro · Usar tema do sistema · Ativo · joao.lucas@emconta.com.br |
| 360 | 1672×1100 | Claro · Escuro · Usar tema do sistema · Ativo · joao.lucas@emconta.com.br |
| 361 | 1672×1100 | Claro · Escuro · Usar tema do sistema · Ativo · joao.lucas@emconta.com.br |
| 362 | 1672×1100 | Alterar senha · Senha atual * · •••••••• · Nova senha * · •••••••••••• |
| 363 | 1672×1100 | Alterar senha · A confirmação não confere com a nova senha. · Senha atual * · •••••••• · Nova senha * |
| 364 | 1672×1100 | Alterar senha · Senha atual incorreta. Nenhuma alteração foi feita. · Senha atual * · •••••••• · Senha atual incorreta. |
| 365 | 1672×1100 | Convidar usuário · Nome * · Carla Souza · E-mail * · carla.souza@emconta.com.br |
| 366 | 1672×1100 | Editar usuário · Nome * · Pedro Costa · E-mail * · pedro.costa@emconta.com.br |
| 367 | 1672×1100 | Perfil Comercial · permissões · Matriz em validação (RN-POR-03). Itens abaixo são referência demonstrativa, não · regra aprovada. · Consulta |
| 368 | 1672×1100 | Desativar acesso de Rafael Nunes? · O usuário não poderá mais entrar. Registros, autoria e histórico são · preservados — não há exclusão. ·  |
| 369 | 1672×1100 | Editar “Lembrete de assinatura pendente” · Valor atual · Novo valor (horas) * · 24 horas · 48 |
| 370 | 1672×1100 | Alterou contato institucional · Empresa · 20/10/2026 17:05 · Autor · Ana Lima (Gestão) · Origem |
| 371 | 1672×1100 | Descartar alterações? · As alterações não salvas serão perdidas. · Continuar editando · Descartar |
| 372 | 1672×1100 | Senha alterada · Use a nova senha no próximo acesso. |
| 373 | 1672×1100 | Convite registrado · Carla Souza aparece como convite pendente (demonstra |
| 374 | 1672×1100 | Convite reenviado · Ação demonstrativa — nenhum e-mail real foi enviado. |
| 375 | 1672×1100 | Preferências salvas · Valem apenas para os seus avisos. |
| 376 | 1672×1100 | Parâmetro atualizado · Vale para operações futuras. Registrado na auditoria. |
| 377 | 1672×1100 | Acesso desativado · Histórico e autoria preservados. |
| 378 | 1672×1100 | joao.lucas@emconta.com.br · Administrador · Minha conta · Preferências de notificações · Tema |
| 379 | 1672×1100 | Notificações · 3 não lidas · Todas · Não lidas · 3 |
| 380 | 1672×1100 | Falar com o suporte · O canal oficial de suporte (telefone, e-mail, horário e prazo de · resposta) ainda está em definição. Nesta demonstraç |
| 381 | 1672×1059 | Acompanhe sua operação. · Faturado no mês · R$ 284.450,75 · + 12% vs. mês anterior · Recebido |
| 382 | 1672×1100 | Conta, empresa, acessos, preferências e parâmetros autorizados. · Minha conta · Minha conta · Seus dados de acesso e contato · Empresa |
| 383 | 1672×1100 | Oportunidades · Oportunidades em andamento e resultados comerciais. Antes da assinatura, o potencial cliente não aparece em Associados. · Op |
| 384 | 1672×1100 | Termo de Adesão — Pessoa Física · Alterações não salvas · v4 · rascunho · Adesão · baseado na v3 (disponível) · Parágrafo |
| 385 | 1672×1059 | Acompanhe sua operação. · Faturado no mês · Recebido · Novo cliente · Outubro 2026 |
| 386 | 1672×1100 | Usinas · Cadastro e consulta das usinas e de seus vínculos cadastrais com unidades consumidoras. · Buscar por nome ou identificador… · Usina |
| 387 | 820×347 | Como navegar · • Avatar (todas as telas) › Menu do usuário › Minha conta, Preferências, Tema, Configurações, · Ajuda, Sair. · • Minha conta: |
| 388 | 820×378 | Regras e decisões aplicadas · • Variáveis semânticas (coleção “Tema”, modos Claro/Escuro) ligadas a componentes e telas · existentes; página |
| 389 | 820×321 | Decisões pendentes · • Matriz de permissões por perfil (RN-POR-03). · • Política de troca de e-mail de acesso e de complexidade de senha. ·  |
| 390 | 820×347 | Notas técnicas e limitações · • Autorização deve ser validada no servidor; ocultar opções na interface não é barreira de · segurança (RN-POR |
| 391 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Faturas · Faturas da concessionária por UC e competência — base conferida para gerar as cobran |
| 392 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Faturas · Faturas da concessionária por UC e competência — base conferida para gerar as cobran |
| 393 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Faturas · Faturas da concessionária por UC e competência — base conferida para gerar as cobran |
| 394 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Faturas · Faturas da concessionária por UC e competência — base conferida para gerar as cobran |
| 395 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Faturas · Faturas da concessionária por UC e competência — base conferida para gerar as cobran |
| 396 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Faturas · Faturas da concessionária por UC e competência — base conferida para gerar as cobran |
| 397 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Faturas · Faturas da concessionária por UC e competência — base conferida para gerar as cobran |
| 398 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Faturas · Faturas da concessionária por UC e competência — base conferida para gerar as cobran |
| 399 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Financeiro › Faturas › FAT-2609-0471 · FAT-2609-0471 · Fernanda Oliveira Costa |
| 400 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Financeiro › Faturas › FAT-2609-0884 · FAT-2609-0884 · Larissa Moreira Santos |
| 401 | 1672×1100 | Faturas · Cobranças · Links de renegociação · Financeiro › Faturas › FAT-2609-0661 · FAT-2609-0661 · Lucas Andrade Pires |
| 402 | 1672×1113 | Faturas · Cobranças · Links de renegociação · Financeiro › Faturas › FAT-2609-0471 · FAT-2609-0471 · Fernanda Oliveira Costa |
| 403 | 1672×1100 | Importar faturas · Arraste os arquivos ou selecione · Formatos de referência: PDF ou XML da concessionária (sujeito à confirmação do suporte |
| 404 | 1672×1100 | Importando faturas · 3 de 6 arquivos processados · fatura-6xxx1510-10-2026.pdf · PDF · 760 KB · fatura-6xxx7342-10-2026.pdf |
| 405 | 1672×1100 | Resultado da importação · Arquivos · Importados · 6 · 3 |
| 406 | 1672×1100 | UC não localizada · fatura-6xxx9930-10-2026.pdf · PDF · 811 KB · UC 6***9930 não localizada no cadastro · UC no arquivo |
| 407 | 1672×1100 | Possível duplicidade · fatura-6xxx4471-09-2026.pdf · Mesmo conteúdo da FAT-2609-0471 · PDF · 812 KB · Já existe fatura para a UC 6***4471 na |
| 408 | 1672×1100 | Arquivo não processado · scan-conta-outubro.jpg · Arquivo ilegível ou formato inválido · JPG · 2,1 MB · Não foi possível ler o arquivo. Os d |
| 409 | 1672×1100 | Fatura importada · fatura-6xxx1510-10-2026.pdf · UC 6***1510 · Energisa MS · 10/2026 · PDF · 760 KB · FAT-2610-0151 importada e conferida. A |
| 410 | 1672×1100 | Inserir dados manualmente · UC * · Competência * · 6***7342 · Renata Cardoso Silva · 10/2026 |
| 411 | 1672×1100 | Inserir dados manualmente · Corrija 2 campos para continuar. · UC * · Competência * · 6***7342 · Renata Cardoso Silva |
| 412 | 1672×1100 | Revisar inserção manual · UC · 6***7342 · Renata Cardoso Silva · Competência · 10/2026 |
| 413 | 1672×1100 | Filtros · Processamento · Arquivo processado · Inserção manual · Falha no arquivo |
| 414 | 1672×1100 | Filtrar por processamento, conferência, cobrança e usina |
| 415 | 1672×1100 | Ver detalhes · Gerar cobrança Em Conta · Reprocessar · Baixar original |
| 416 | 1672×1100 | Fatura registrada · Inserção manual salva · conferência pendente (compens |
| 417 | 820×316 | Como navegar · • Início: “Financeiro — Faturas” (Financeiro na sidebar abre Faturas). · • Importar › 6 arquivos › processando › lote parcial |
| 418 | 820×371 | Regras aplicadas · • Faturas = contas da concessionária; Cobranças = títulos Em Conta. Valor da conta não é copiado · para a cobrança. · • P |
| 419 | 820×271 | Decisões pendentes · • Formatos e limites oficiais de importação (PDF/XML de referência). · • Política de substituição de faturas e de revis |
| 420 | 820×228 | Limitações e validação · • Auditoria verifica conexões e destinos; a experiência no modo Apresentar precisa de validação · manual. · • Detal |
