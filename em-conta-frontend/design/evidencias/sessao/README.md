# Evidências — telas de sessão

Comparação das telas Entrar, Esqueceu sua senha? e Criar nova senha com as pranchas de estados
"🔐 Sessão — Entrar e sessão expirada", "🔐 Sessão — Esqueceu a senha" e "🔐 Sessão — Nova Senha"
(PNGs fornecidos em 24/11/2026, fora do Git). Cada quadro das pranchas tem 1672 × 1100 px, a mesma
escala do protótipo, então as capturas de desktop usam essa janela.

- `capturas/desktop-*.png`: build de produção (`vite preview`) em 1672 × 1100, Chromium.
- `capturas/celular-*.png`: a mesma build em 390 × 844.
- `comparacao/comparacao-*.png`: quadro da prancha (esquerda) e implementação (direita), a 50%.
- `medidas.json`: retângulo do cartão e do botão principal em cada quadro, medidos nos pixels da
  prancha e da captura.

Ambiente isolado de revisão (API na porta 8092, banco próprio). Dados fictícios: e-mail
`pessoa@exemplo.com.br`, senhas de teste e token de 43 caracteres inventado. Nenhuma credencial
aparece nas capturas.

## Estados

"Real" usa a API do ambiente isolado. "Simulado" responde no navegador (Playwright), porque o
estado depende de um token válido recebido por e-mail e o ambiente de revisão não tem Mailpit ligado.

| Estado | Quadro | Origem | Cartão (prancha → implementação) |
| --- | --- | --- | --- |
| Entrar · inicial | 1 | real | y 339–760 → 339–760 |
| Entrar · sessão expirada | 2 | real: cookie de sessão invalidado, 401 `SESSAO_EXPIRADA` | 291–784 → 301–798 |
| Entrar · preenchido | 3 | real | 339–760 → 339–760 |
| Entrar · em envio | 4 | requisição real retida | 340–760 → 339–760 |
| Entrar · credenciais inválidas | 5 | real: 401 `CREDENCIAIS_INVALIDAS` | 313–786 → 315–784 |
| Entrar · campos vazios | 6 | real (validação local) | 292–807 → 293–806 |
| Entrar · senha visível e foco do teclado | — | real | — |
| Esqueceu · inicial | 1 | real | 350–748 → 350–749 |
| Esqueceu · preenchido | 2 | real | 350–748 → 350–749 |
| Esqueceu · em envio | 3 | requisição real retida | 351–748 → 350–749 |
| Esqueceu · verifique seu e-mail | 4 | real: 202 genérico | 366–733 → 365–734 |
| Esqueceu · campos vazios | 5 | real (validação local) | 314–784 → 315–784 |
| Esqueceu · formato inválido | 6 | real (validação local) | 323–775 → 324–775 |
| Nova senha · inicial | 1 | simulado | 300–798 → 300–799 |
| Nova senha · preenchido | 2 | simulado | 300–798 → 300–799 |
| Nova senha · senhas diferentes | 3 | simulado | 282–816 → 285–814 |
| Nova senha · redefinindo | 4 | simulado | 300–798 → 300–799 |
| Nova senha · link expirado | 5 | real: 400 `TOKEN_INVALIDO` | 415–683 → 400–699 |
| Nova senha · sucesso | 6 | simulado | 416–682 → 417–682 |
| Nova senha · confirmação vazia | 7 | simulado | 264–834 → 265–834 |

Em todos os quadros o cartão ocupa x 616–1055 (440 px) na prancha e na implementação.

## Diferenças que permanecem

- **Textos da política de senha:** a prancha traz "Requisitos (demonstrativo — a definir)" e
  "Mínimo 8 caracteres". A tela mostra a política vigente (`politica-senha.ts`, espelho do
  backend): de 15 a 128 caracteres, espaços permitidos, mas não só espaços.
- **Sessão expirada:** o texto é "Sua sessão expirou ou foi encerrada. Entre novamente para
  continuar.", e não "…expirou por inatividade…". O frontend não distingue inatividade, limite de
  12 h e revogação (troca de senha, desativação). O e-mail da sessão anterior não é preenchido: os
  testes de ponta a ponta exigem que nenhum dado do usuário anterior apareça depois do 401. No
  quadro, o cartão fica 12 px acima do centro; na implementação ele é centralizado como nos demais.
- **Link expirado:** além de "Voltar ao login", mantém o link "Solicitar um novo link" do fluxo
  existente (no convite, a orientação de pedir novo convite ao administrador). Por isso o cartão é
  32 px mais alto. O texto cobre link inválido, expirado ou já usado.
- **Em envio (Entrar):** o botão mostra "Entrando…", como "Enviando…" e "Redefinindo…" nas outras
  telas; no quadro ele continua "Entrar".
- **Cores:** o gradiente do botão vai de `--cor-destaque` a `--cor-destaque-forte` (o da prancha,
  `#2db04e` → `#1c8a3a`, deixa o texto branco abaixo de 3:1 à esquerda). Bordas e mensagens de erro
  usam `--cor-erro` (`#b42318`), e a borda dos campos é a global (`#c9cfcb`); a prancha usa
  `#d93a32` e `#e8ece9`.
- **Curvas do fundo:** redesenhadas em SVG a partir dos pixels das pranchas; no celular, reduzidas à
  metade.
- **Logo:** o mesmo PNG da estrutura global (pendente o SVG oficial).

## Acessibilidade verificada

- Rótulos ligados aos campos; obrigatórios com `*` visual e `aria-required`.
- Erros em texto (por campo e resumo com `role="alert"`), ligados por `aria-describedby`; a borda
  vermelha e o ícone de alerta só reforçam.
- Ordem do Tab no login: E-mail → Senha → Mostrar senha → Esqueci minha senha → Entrar. O botão do
  olho é alcançável pelo teclado, tem nome fixo ("Mostrar senha") e estado em `aria-pressed`.
- Foco visível (contorno de 3 px) nos campos, botões e links; o foco vai para o primeiro campo com
  erro e para o título quando o estado muda.
