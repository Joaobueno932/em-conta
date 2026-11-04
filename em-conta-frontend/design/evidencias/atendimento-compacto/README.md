# Evidências — Atendimento compacto

Atualização do protótipo: o Atendimento usa praticamente toda a área abaixo do cabeçalho global.

- `antes/`: commit `5ce29af` (título grande, subtítulo, presença duplicada, quatro visões na fila).
- `depois/`: esta entrega.

Arquivos `<estado>-<largura>.png`, nos tamanhos 1672×941, 1440×900, 1280×720 e 390×844:

| Estado                    | O que mostra                                                    |
| ------------------------- | --------------------------------------------------------------- |
| `vazio`                   | Sem conversa selecionada                                        |
| `conversa-painel-aberto`  | Conversa assumida, com painel do associado                      |
| `conversa-painel-fechado` | Mesma conversa, painel fechado                                  |
| `canal-nao-configurado`   | Aviso compacto do canal acima das colunas, com a conversa aberta |

Capturas com dados sintéticos da base isolada de testes (API e2e, porta 8090) e Graph controlada.
Nenhum dado foi criado na base de trabalho. O estado `canal-nao-configurado` foi simulado só na
captura, trocando a resposta de `GET /api/v1/atendimento/canal` por `configurado: false`, porque a
API isolada tem o canal de teste configurado. O texto e o componente são os mesmos que aparecem na
API de trabalho com o WhatsApp desligado.

## O que mudou

- O título "Atendimento" fica no cabeçalho global (`meta.tituloCabecalho` da rota), ao lado do
  botão de recolher o menu. O bloco grande com título e subtítulo saiu.
- Fila, conversa e painel começam 12 px abaixo do cabeçalho e vão até 15 px do fim da janela.
- Um único seletor de presença, no topo da fila.
- A fila mostra só "Em andamento" e "Aguardando", com as contagens do servidor, numa linha.
  "Em andamento" mantém o significado (todos os atendimentos em curso). Os encerrados ficam no
  Histórico geral, em "Ver opções". Links antigos com `?visao=MEUS` abrem "Em andamento"; com
  `?visao=ENCERRADOS` ou valor desconhecido, abrem "Aguardando". Nos dois casos o parâmetro sai da
  URL e a conversa selecionada continua aberta.
- Até 1280 px: a fila tem 320 px, a data do item fica em duas linhas e as ações da conversa descem
  para uma linha própria quando o cartão fica estreito (antes saíam do cartão). Fila e conversa
  ocupam a altura que sobra (descontado o aviso do canal) e o painel desce para baixo delas.
