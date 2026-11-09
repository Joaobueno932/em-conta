# Balão do cliente na conversa

O balão das mensagens recebidas era branco, com borda `--cor-borda` (contraste 1,19:1), sobre o fundo
branco da conversa: o limite do balão quase não aparecia.

**Escolha.** A paleta do Em Conta não tem creme, então o fundo da área de mensagens continua branco. O
balão do cliente passou a usar o tom neutro mais claro da paleta (`--cor-fundo`, `#f4f6f4`) e uma borda
derivada das cores da paleta (`--cor-borda` com 35% de `--cor-texto-3`, por volta de `#cdd2ce`,
1,5:1 contra o branco). As pílulas de evento continuam sem borda, o que as distingue do balão.
Mensagens do atendente, fundo da tela e cores de erro não mudaram.

| Contraste no balão do cliente | Antes (branco) | Depois (`#f4f6f4`) | Balão do atendente |
|---|---|---|---|
| Texto (`--cor-texto`) | 15,81:1 | 14,56:1 | 13,91:1 |
| Horário (`--cor-texto-2`) | 3,99:1 | 3,68:1 | 3,51:1 |

O horário em `--cor-texto-2` já ficava abaixo de 4,5:1 antes desta mudança (nos dois balões); a cor
dos horários é de todo o sistema e fica como pendência de acessibilidade.

## Capturas

Conversa sintética da base de testes (1440 × 900 e 390 × 844) e, para o estado de erro, a área de
mensagens de uma conversa real da homologação, sem nome nem telefone do contato.

| Arquivo | Conteúdo |
|---|---|
| `antes-conversa-desktop-1440.png` / `depois-conversa-desktop-1440.png` | tela inteira, antes e depois |
| `depois-mensagens-desktop-1440.png`, `depois-mensagens-celular-390.png` | mensagens dos dois lados |
| `antes-falha-real-desktop-1440.png`, `depois-falha-real-*.png` | resposta recusada pela Meta, com o erro legível |
