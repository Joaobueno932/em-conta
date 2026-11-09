# Indicadores de situação nas mensagens enviadas

Ícone ao lado do horário de cada mensagem enviada, como no WhatsApp, com rótulo acessível e tooltip
(`src/features/atendimento/IndicadorSituacao.vue`).

| Situação | Indicador | Rótulo acessível |
|---|---|---|
| `PENDENTE`, `ENVIANDO` | relógio | Aguardando envio / Enviando |
| `ACEITA` | relógio (ainda sem confirmação de envio) | Aceita pela Meta, aguardando confirmação de envio |
| `ENVIADA` (`sent`) | um tique cinza | Enviada |
| `ENTREGUE` (`delivered`) | dois tiques cinza | Entregue |
| `LIDA` (`read`) | dois tiques azuis (`--cor-lida`, 4,3:1 no balão) | Lida |
| `FALHA`, `JANELA_EXPIRADA` | exclamação vermelha; a causa abre num detalhe | Falha no envio / Não enviada |
| `DESCONHECIDA` | "Envio não confirmado", com ícone âmbar próprio; detalhe pede confirmar com o cliente | Envio não confirmado |

O azul do WhatsApp (`#53bdeb`) ficaria em 1,9:1 no balão verde; o escolhido passa de 3:1. Entregue e
lida diferem só pela cor, como no WhatsApp; o rótulo acessível e o tooltip dizem a situação.

## Capturas

**Observado na Meta** (homologação com o número de teste; só o balão, sem dados do contato):

- `meta-mensagem-lida-*.png`: resposta aceita, entregue e lida (status `sent`, `delivered` e `read`
  recebidos pelo webhook).
- `meta-mensagem-falha-131030-*.png`: resposta recusada pela Meta com #131030, detalhe aberto.

**Simulado só na captura** (`simulado-todas-as-situacoes-*.png`): conversa sintética da base de
testes, com a resposta da linha do tempo interceptada no navegador para incluir uma mensagem por
situação. Nada foi gravado. "Envio não confirmado" nunca ocorreu na Meta.
