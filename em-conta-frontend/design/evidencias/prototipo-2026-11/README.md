# Protótipo completo (export de 25/11/2026) — inventário e cobertura

Registro da implementação das pranchas do Figma "Em Conta — Protótipo" exportadas em 25/11/2026
(pasta `Em Conta — Protótipo (1)/` na raiz do checkout, fora do Git: 51 PDFs, ~900 MB, um PDF por seção;
e `Em Conta — Protótipo/`, 4 PNGs). Cada PDF é uma página com vários quadros de 1672 × 1100.

Branch `feat/figma-telas` (worktree `C:\Users\joao.pinheiro\worktrees\em-conta-figma`).

## Como as pranchas foram lidas

- O MCP do Figma não tem acesso ao arquivo com a conta conectada; a fonte foi o export local.
- PyMuPDF não instala nesta máquina (o download é interrompido, como o Pillow). Os PDFs foram renderizados
  com **pdf.js no Chromium do Playwright**, em escala 1 (quadros a 1672 px), e cada quadro foi recortado
  pela cor do fundo da seção. Textos com posição e tamanho de fonte vêm do próprio pdf.js.
- Scripts (fora do Git, no scratchpad da sessão): `render.mjs` (seção → quadros + `quadros.json`),
  `recorte.mjs` (recorte ampliado, cor de pixels, transições em linha/coluna), `captura.mjs` (login no
  ambiente de revisão, captura 1672 × 1100 e lado a lado com o quadro), `listar.py` (lista de quadros).
- "🧾 Associados — Detalhes e cadastro" (91 MB, 14915 × 12251 pt, quase só imagem) travou o pdf.js por mais
  de uma hora. A ficha do associado foi conferida nas páginas equivalentes do export anterior
  (`design/referencias/Em Conta — Protótipo.pdf`, p. 23–26, 93–96 e 147–148), extraídas com `pypdf`.

## Medidas da estrutura global (conferidas nos pixels)

| Elemento | Medida |
| --- | --- |
| Menu lateral | 294 px + borda; itens de 38 px com passo de 46; ícones de 24 px (traço 2); texto a x 82, 18 px |
| Contador do Atendimento | círculo de 32 px, `#e3f5e7` / `#1b7f3a`, 15 px |
| Grupos | "PLATAFORMA"/"CONECTIVIDADE" 14 px, `#9aa19c`, espaçado |
| Cabeçalho | 72 px + borda; botão de recolher 44 px; busca 406 × 48 (pílula, borda `#e8ece9`, 15 px); avatar 44 px |
| Título de página | 38 px negrito, linha 46; subtítulo 16 px `#7a817c`; conteúdo a 33 px do menu e 32 px da borda |
| Cartões | branco, borda `#e8ece9`, raio 12 |
| Tabelas | cabeçalho `#f2f4f2` 36 px, 12 px; linhas 37–59 px conforme a tela, divisória `#eef1ef` |
| Banners | informação `#eaf1fd` / ícone `#2f6fd6`; erro `#fdeceb` / ícone `#d93a32` |
| Botão principal | 40 px, gradiente `--cor-destaque` → `--cor-destaque-forte` (texto branco com contraste) |

## Inventário

Situação: **C** concluída e validada · **P** implementada com pendência · **B** bloqueada por dependência
(não existe no backend ou não foi aprovada) · **N** não implementada. "Fora de escopo" = material que não
é tela aprovada (componentes soltos, notas, propostas aguardando validação).

### Telas com funcionalidade existente

| Seção (quadros) | Rota | Antes | O que mudou | Situação |
| --- | --- | --- | --- | --- |
| 🔐 Sessão — Entrar e sessão expirada (6) | `/entrar` | Tela simples | Refeita pela prancha (commit `03f22a9`, trabalho de 24/11) | C |
| 🔐 Sessão — Esqueceu a senha (6) | `/recuperar-senha` | Tela simples | Idem | C |
| 🔐 Sessão — Nova Senha (7) | `/redefinir-senha`, `/convite` | Tela simples | Idem; política real de senha no lugar do texto "a definir" | C |
| Estrutura global (todas) | — | Menu de 50 px, título do Atendimento no cabeçalho | Menu, contador, cabeçalho com busca de associados, avisos flutuantes | C |
| 🏠 Painel (1) | `/` | "Área provisória" com e-mail | Indicadores, Associados recentes, Precisa de atenção, Atendimento — só dados reais | P |
| ⚙️ Configurações (13) | `/configuracoes/conta`, `/admin/usuarios`, `/admin/usuarios/:id`, `/conta/senha` | Páginas soltas | Layout de seções; Minha conta (ver, editar nome, erros, sucesso); Usuários e permissões (tabela, selos, convite em modal); detalhe do usuário; alterar senha | P |
| 👥 Associados — Visão geral e listagem (12) | `/associados` | Lista genérica | Cartão da lista, busca, filtros em painel, chips, ordenação na coluna, esqueleto, vazio, erro, paginação numerada | P |
| Ficha do associado (p. 23–26, 93–96 do export anterior) | `/associados/:id` | Abas simples | Trilha, ícone, resumo, abas sublinhadas, Dados cadastrais em cartões, UCs, Histórico em linha do tempo | P |
| Novo associado / Editar cadastro (p. 147–148) | `/associados/novo`, `/associados/:id/editar` | Formulário genérico | Cartão único com seções, botões do protótipo | P |
| ⚡ Usinas — Listagem (5) | `/usinas` | Lista genérica | Igual à de associados; ícone por linha; nota de rodapé | P |
| ⚡ Usinas — Detalhe da usina (12) | `/usinas/:id` | Abas simples | Cabeçalho com voltar, ícone e ações; Visão geral com cartões, UCs vinculadas e informações principais; "Vincular unidade consumidora" no cabeçalho abre o painel real | P |
| Fazendas e formulários de usina/fazenda/UC (sem prancha própria) | `/fazendas`, `/usinas/nova`… | Genéricos | Mesmo padrão das listagens e formulários | C |
| 🎧 Atendimento — Conversas (24) | `/atendimento` | Título no cabeçalho global | Título e subtítulo na página, presença à direita, "Mais recentes / Mais antigos" na fila (parâmetro `maisAntigos` da API) | P |
| 🗂️ Atendimento — Histórico geral e detalhe (8 de 15) | `/atendimento/historico`, `/atendimento/historico/:id` | Já seguia os frames de 04/11 | Título de 38 px | P |

Pendências destas telas (motivo específico):

- **Painel:** faturamento, recebidos, atraso, gráfico e "Últimas faturas" dependem do Financeiro, que não
  existe no `main`. Os blocos foram montados com associados, usinas e a fila do atendimento; nenhum número é
  ilustrativo. O seletor de mês não aparece (nenhum dado do Painel depende de mês hoje).
- **Configurações:** Empresa, Preferências de notificações, Parâmetros do sistema, Auditoria e Atalhos não
  existem no backend e não ganharam itens. Telefone e foto da conta não existem no cadastro de acesso. A data
  da última troca de senha não é exposta pela API. Só o nome é editável, e só por quem tem `usuarios:editar`.
  A API de usuários não tem busca nem filtro; o "…" de cada linha abre o usuário. Só o perfil Administrador
  existe (a prancha lista Comercial, Atendimento, Operação etc.).
- **Associados (lista e ficha):** Usina (vínculo), Situação do cliente, Pendências, Responsável, Nº do cliente,
  Tags, Origem, endereço da sede, equipe, contratos, faturas, cobranças e importação/exportação não existem no
  cadastro. O submenu do protótipo (Visão geral, Contratos, Pendências, Histórico de alterações) não aparece:
  só "Todos os associados" existe. O aviso "Situação do cliente, contratos… ainda não estão disponíveis" foi
  mantido na ficha (é protegido por teste e informa o usuário).
- **Usinas:** Responsável, Potência, Exportar, concessionárias e "Último vínculo" não existem; no lugar
  entram Fazenda, Vínculos encerrados e Cadastrada em. O menu chama o módulo de "Geradoras" (como o
  protótipo); as páginas continuam tratando de usinas.
- **Atendimento:** a conversa, o painel do associado e os estados AT-02 a AT-07 já tinham sido conferidos nos
  frames de 04/11 (ver `../auditoria-atendimento/`) e não mudaram de estrutura no export novo; não foram
  recapturados porque o ambiente de revisão não tem WhatsApp (o aviso "Canal não configurado" aparece, que é o
  estado real). Etiquetas, Simulador de proposta e Documentos do painel, emoji e áudio continuam fora (não
  existem). A busca da fila não procura por "ID do ticket" (a API busca nome e telefone).

### Telas bloqueadas por dependência (sem backend no `main`)

| Seção (quadros) | Motivo |
| --- | --- |
| 📊 Atendimento — Dashboard (4) | Não há endpoints de métricas (tempo médio, SLA, volume por período, comercial) |
| 🗂️ Atendimento — Mensagens em massa (7 de 15) | Não há envio em massa nem modelos aprovados da Meta |
| ⚠️ Associados — Pendências cadastrais (11) | Pendências não existem no cadastro |
| 🕘 Associados — Histórico de alterações (8) | Só existe o histórico por associado (aba da ficha); não há consulta geral |
| 📄 Associados — Contratos (9) e ✍️ Novo contrato (8) | Módulo `contratos` é só `package-info.java` |
| 💰 Financeiro — Cobranças (10), Gerar cobrança (8), Gerar em lote (6), Geração em lote (6) | Módulo de cobrança não existe |
| 🔗 Financeiro — Links de pagamento (5) e 🤝 Renegociação de débitos (12) | Idem |
| 🧾 Financeiro — Faturas e Detalhe da fatura | A importação de fatura por UC está no worktree `em-conta-faturas` (sem commit, aguardando amostras reais); não está no `main` |
| 📄 Documentos — Listagem de modelos (7), ✍️ Editor Docs (11), 👁️ Prévia (8) | Não há módulo de documentos/modelos |
| 📈 Funil de vendas — Visão geral (4) e 🗂️ Oportunidades (11) | Módulo `comercial` é só `package-info.java` |
| ❓ Ajuda — Central e artigos (6) | Não há conteúdo nem backend de ajuda |
| Notificações (sino do cabeçalho, "Proposta de Fluxo · Notificações") | Não há notificações no backend; o sino não aparece |
| 🌗 Tema — Demonstração (7) | Tema escuro não existe; as pranchas mostram telas de outros módulos no tema escuro |
| 🪟 Overlays dos módulos acima | Seguem a situação do módulo |

**Overlays (11 seções, 168 quadros; 🧾 Financeiro — Faturas tem 8 e Detalhe da fatura 4):**

| Seção (quadros) | Situação |
| --- | --- |
| 🪟 Configurações, notificações e conta (22) | **Alterar senha** (q6–q8) virou modal sobre Minha conta (a URL `/conta/senha` continua) e **Convidar usuário** (q9) ganhou cabeçalho com divisória, perfil em lista, asteriscos e nota — comparados em `comparacao/senha-modal.png` e `convite-modal.png`: C. Editar usuário e Desativar acesso (q10–q11) continuam na página do usuário, com o diálogo de confirmação do sistema: P. Notificações (q1–q5), parâmetros, empresa, suporte e menu da conta com tema (q12–q22): B |
| 🪟 Usinas — Overlays e painéis (11) | Nova usina em modal (q4–q6), exportar (q1) e detalhe de UC em painel lateral (q10): P/B — o cadastro continua em página própria (com aviso de nome duplicado já existente); exportar não existe. Vincular UC (q7–q9) e Descartar alterações (q11) seguem como painel e diálogo já existentes, sem comparação quadro a quadro |
| 🪟 Associados — Overlays (19), Página do associado (10) | Filtros, menu de ações da linha e da ficha, importação e exportação: os filtros existentes ficaram no painel do botão Filtros; o restante (importar, exportar, novo contrato, vincular negócio/produto, criar atividade, cobrança) não existe: B |
| 🪟 Associados — Pendências e histórico (29), Pendências: painéis de revisão (17) | B (módulo de pendências e histórico geral não existem) |
| 🪟 Documentos (14), Financeiro (13 + 12), Funil de vendas (12 + 9) | B |

### Fora de escopo (não são telas aprovadas)

- `Button.pdf`, `Campo — null.pdf`, `Checklist Item.pdf`: componentes isolados.
- `📝 … Notas` (2 seções, 8 quadros): notas de navegação e decisões do protótipo.
- `CF-XX (Proposta) · Perfis e Permissões`, `Justificativa da proposta · … Aguardando supervisor`,
  `Proposta de Fluxo · Notificações - Overlays`: propostas marcadas como "aguardando validação do supervisor".

## Validação

- **Visual:** cada tela acessível foi capturada em 1672 × 1100 contra o quadro correspondente
  (`comparacao/`: quadro à esquerda, implementação à direita). Ambiente isolado de revisão (API 8092, banco
  próprio, dados sintéticos com sufixo "(teste)"); uma usina "Usina Revisão Figma (teste)" e um vínculo foram
  criados por esse ambiente para conferir o detalhe da usina pelo fluxo real.
- **Responsividade:** 1280, 1024 e 390 px (`capturas/`); sem rolagem horizontal da página em 390 px (as tabelas
  rolam por dentro; o menu vira faixa).
- **Funcional:** `npm run verify` (oxlint, eslint, prettier, vue-tsc, 172 testes de unidade, build de
  produção). E2E `associados.spec.ts` e `usinas.spec.ts`: 7/7 contra a API real de revisão.
  `acesso.spec.ts` (precisa do Mailpit) e as jornadas de atendimento (precisam do ambiente e2e com a Graph
  controlada) **não foram executadas**: o ambiente e2e isolado está parado e a credencial do administrador
  desse banco não está nesta máquina. Os specs foram atualizados para a nova interface.

## Achados fora do frontend

- `GET /api/v1/vinculos-usina/ucs-candidatas?busca=Revisão` responde **500**: `unidade_consumidora.numero` é
  `ascii_bin` e o `LIKE` com termo acentuado gera "Illegal mix of collations". A busca sem acento funciona. Não
  corrigido nesta entrega (backend).
