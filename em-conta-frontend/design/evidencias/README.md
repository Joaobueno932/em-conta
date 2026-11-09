# Evidências visuais — estrutura global e Atendimento

> Auditoria completa do Atendimento (checklist, inventário do protótipo, capturas e comparações):
> [auditoria-atendimento/](auditoria-atendimento/README.md).

> Balão do cliente destacado do fundo branco da conversa: [balao-cliente/](balao-cliente/README.md).

> Indicadores de situação nas mensagens enviadas: [indicadores-situacao/](indicadores-situacao/README.md).

> Atualizado depois: título no cabeçalho global, presença única e só duas visões na fila. Ver
> [atendimento-compacto/](atendimento-compacto/README.md).

Comparação da implementação com os frames aprovados AT-01 a AT-06 do arquivo Figma
"Em Conta — Protótipo" (`IlVXijVBwWrqfmjVX8QdDz`, seção "🎧 Atendimento — Conversas").

- `../referencias/`: exports 2x (3344 × 1882) dos frames, fornecidos em 04/11/2026.
- `antes/`: aplicação antes desta entrega (commit `066acf4`), 1672 × 941.
- `depois/`: aplicação depois desta entrega (build de produção), 1672 × 941.
- `comparacao/`: `AT-0n-lado-a-lado.png` (referência | implementação) e `AT-0n-sobreposicao.png`
  (média das duas imagens; bordas desalinhadas aparecem duplicadas).

Capturas com dados sintéticos da base isolada de testes (API e2e, porta 8090) e Graph controlada.
Nenhum dado foi criado na base de trabalho. Os nomes de contato têm sufixos aleatórios e por isso são
mais longos que os do protótipo.

| Frame                                     | Nó        | Captura                            |
| ----------------------------------------- | --------- | ---------------------------------- |
| AT-01 · Em andamento (vazio)              | `18:441`  | `depois/at01-vazio.png`            |
| AT-02 · Ver opções aberto                 | `18:890`  | `depois/at02-ver-opcoes-vazio.png` |
| AT-03 · Aguardando · Conversa selecionada | `18:1374` | `depois/at03-aguardando.png`       |
| AT-04 · Em atendimento · Dados abertos    | `18:1976` | `depois/at04-em-atendimento.png`   |
| AT-05 · Em atendimento · Dados fechados   | `18:2644` | `depois/at05-painel-fechado.png`   |
| AT-06 · Conversa + Ver opções             | `18:3229` | `depois/at06-ver-opcoes.png`       |

## Validado contra os frames (1672 × 941)

- Menu lateral de 294 px: logo, grupos, ícones de 20 px, itens de 50 px, item ativo
  (`#e3f5e7` / `#c9e7cf`), contador, rodapé com divisória.
- Cabeçalho de 72 px + borda: botão de recolher (42 px), conta com avatar de 44 px.
- Curvas decorativas do cabeçalho.
- Título de 40 px e subtítulo de 18 px.
- Colunas nas posições do frame: fila em x 310–650 (340 px), conversa até 1363, painel 1379–1657
  (278 px), cartões de y 178 a 926, com 16 px entre eles e 15 px nas bordas.
- Fila: topo com presença, busca de 38 px, chips, itens de 57 px com avatar de 40 px, "Ver opções" e
  menu que abre para cima.
- Conversa: avatar de 64 px, nome em link, telefone, ações de 46 px, pílulas de dia e evento, balões
  (entrada com borda, saída em `#e3f5e7`), aba "Mensagem", campo de 48 px e botão de enviar de 60 px.
- Faixa "Assuma o atendimento para responder esta conversa." no lugar do compositor (AT-03).
- Painel: título, avatar de 60 px, "Ver cadastro", seção "Atendimento" recolhível e Mídia.
- Estados vazios: fila ("Você não tem atendimentos") e conversa ("Tudo pronto para atender").
- Tokens de cor medidos nos pixels: fundo `#f4f6f4`, borda `#e8ece9`, texto `#1e2420` / `#7a817c` /
  `#9aa19c`, destaque `#1f9d45` / `#1b7f3a`, avatar `#2f7d4c`, PDF `#e0312f`.
- Fonte Inter, empacotada pelo próprio projeto.

## Adaptações sem frame de referência

- Menu recolhido (`depois/menu-recolhido.png`): só ícones, 84 px, rótulos ocultos visualmente.
- Larguras de 1440, 1280, 1024 e 390 px (`depois/largura-*.png`). Até 80rem o painel desce para
  baixo da conversa, mantendo a rolagem interna. Até 60rem as colunas se empilham. Até 40rem a
  navegação vira uma faixa no topo.
- Estados de outro responsável, encerrado, falha da fila e carregamento (`depois/estado-*.png`).
- Aviso de canal não configurado: compacto, no cabeçalho da página. Só aparece com o WhatsApp
  desligado (API de trabalho), por isso não há captura com dados.
- Menu da conta (Alterar senha, Sair).
- Botões de abrir o painel e fechar a conversa, que não aparecem no frame.

## Diferenças que permanecem

- **Itens futuros não implementados:** Funil, Financeiro, Geradoras, O&M, Gestão de Créditos,
  Documentos, Automações, Integrações, Configurações, Ajuda, busca global, notificações, aba "Nota
  interna", emoji, gravação de áudio, ordenação, etiquetas, simulador, documentos no painel e "Setor".
  Não existem no sistema e não ganharam links nem páginas.
- **Usinas:** o menu usa o rótulo da rota existente, e não "Geradoras".
- **Visões da fila:** há quatro visões reais (Em andamento, Aguardando, Meus atendimentos,
  Encerrados), por isso os chips ocupam duas linhas. O frame mostra duas.
- **Presença:** o seletor fica no topo da fila. O cabeçalho da página só mostra a situação, sem seta.
- **Mídia:** fica aberta por padrão (no frame aparece recolhida), para manter os fluxos existentes.
- **Situação das mensagens:** aparece por extenso ("Aceita pelo WhatsApp", "Entregue"…), além da cor.
  O frame usa só os tiques.
- **Logo:** recorte em PNG do export do frame (`src/shared/assets/logo-em-conta*.png`), exibido sem
  distorção. **Pendente: SVG oficial da logo.**
- **Curvas decorativas:** redesenhadas em SVG a partir da geometria medida nos exports, porque o asset
  vetorial não veio nos arquivos.
