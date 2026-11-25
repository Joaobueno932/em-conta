<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'

import logo from '@/shared/assets/logo-em-conta.png'

/**
 * Estrutura das telas de sessão (Entrar, Esqueceu sua senha?, Criar nova senha), conforme as pranchas
 * "Sessão" do protótipo (quadros de 1672 × 1100): fundo com curvas verdes nos cantos e cartão branco
 * centralizado com a logo, divisória e título. O conteúdo de cada tela vai no slot padrão; avisos de
 * sessão (ex.: sessão expirada) vão no slot `aviso`, entre a divisória e o título.
 */
const props = defineProps<{ titulo: string }>()

// Quando o estado muda (e-mail enviado, link expirado, senha alterada), o título muda e recebe o
// foco, para que o leitor de tela anuncie a nova tela.
const tituloEl = ref<HTMLHeadingElement | null>(null)
watch(
  () => props.titulo,
  () => nextTick(() => tituloEl.value?.focus()),
)
</script>

<template>
  <div class="sessao">
    <!-- Curvas decorativas redesenhadas em SVG a partir dos pixels das pranchas (coordenadas do
         quadro 1672 × 1100): o asset vetorial não veio com as referências. -->
    <svg
      class="curvas curvas--topo"
      viewBox="1040 0 632 420"
      width="632"
      height="420"
      aria-hidden="true"
      focusable="false"
    >
      <path
        class="linha"
        d="M1360 -8C1366 -4 1384 6 1396 13C1408 20 1420 27 1432 36C1444 45 1456 55 1468 69C1480 83 1492 98 1504 120C1516 142 1530 179 1540 202C1550 226 1556 244 1564 261C1572 279 1580 294 1588 307C1596 320 1604 330 1612 340C1620 350 1626 357 1636 366C1646 375 1662 388 1672 395C1682 402 1692 408 1696 410"
      />
      <path
        class="linha"
        d="M1478 -8C1482 -2 1496 14 1504 26C1512 38 1520 49 1528 64C1536 79 1544 95 1552 114C1560 133 1568 157 1576 176C1584 195 1592 214 1600 229C1608 244 1616 257 1624 268C1632 279 1640 288 1648 297C1656 306 1664 313 1672 320C1680 327 1692 335 1696 338"
      />
      <path
        class="linha"
        d="M1538 -8C1542 -2 1556 16 1564 30C1572 44 1580 58 1588 75C1596 92 1604 115 1612 134C1620 154 1628 175 1636 192C1644 209 1652 224 1660 237C1668 250 1680 266 1684 272"
      />
      <path
        class="faixa"
        d="M1040 -40C1048 -31 1075 1 1088 15C1101 30 1109 37 1120 47C1131 57 1136 64 1152 73C1168 82 1195 93 1216 100C1237 107 1259 112 1280 116C1301 121 1323 123 1344 127C1365 131 1387 134 1408 138C1429 142 1451 145 1472 150C1493 155 1515 159 1536 166C1557 173 1579 179 1600 190C1621 201 1647 220 1664 233C1681 246 1694 261 1700 266"
      />
    </svg>
    <svg
      class="curvas curvas--base"
      viewBox="0 800 1040 300"
      width="1040"
      height="300"
      aria-hidden="true"
      focusable="false"
    >
      <path
        class="linha"
        d="M-30 892C-25 895 -16 900 0 908C16 917 43 932 64 943C85 954 107 964 128 973C149 983 171 992 192 1000C213 1008 235 1015 256 1021C277 1027 299 1033 320 1037C341 1041 363 1044 384 1046C405 1048 421 1049 448 1049C475 1049 512 1047 544 1046C576 1045 608 1044 640 1044C672 1044 709 1045 736 1045C763 1046 779 1046 800 1047C821 1048 843 1050 864 1053C885 1056 907 1059 928 1064C949 1069 975 1077 992 1085C1009 1093 1024 1106 1030 1110"
      />
      <path
        class="linha"
        d="M-30 939C-25 942 -16 946 0 954C16 962 43 976 64 986C85 996 107 1006 128 1014C149 1023 171 1030 192 1037C213 1044 235 1049 256 1054C277 1059 299 1063 320 1066C341 1069 363 1070 384 1070C405 1071 421 1070 448 1069C475 1069 507 1068 544 1067C581 1067 635 1066 672 1066C709 1067 741 1068 768 1070C795 1072 811 1074 832 1077C853 1080 878 1084 896 1089C914 1095 933 1107 940 1110"
      />
      <path
        class="linha"
        d="M-30 1006C-25 1009 -16 1015 0 1022C16 1029 43 1040 64 1048C85 1056 107 1062 128 1068C149 1074 171 1078 192 1082C213 1086 235 1089 256 1091C277 1093 293 1093 320 1093C347 1093 384 1091 416 1090C448 1089 485 1088 512 1088C539 1088 555 1088 576 1088C597 1088 619 1089 640 1090C661 1091 686 1092 704 1095C722 1098 742 1108 750 1110"
      />
      <path
        class="faixa"
        d="M-40 812C-33 816 -17 823 0 833C17 843 43 859 64 870C85 881 107 890 128 898C149 906 171 913 192 919C213 925 235 930 256 934C277 938 299 941 320 944C341 947 363 949 384 951C405 954 427 956 448 959C469 962 491 965 512 970C533 975 555 980 576 987C597 994 619 1003 640 1014C661 1025 683 1037 704 1051C725 1065 752 1084 768 1097C784 1110 795 1125 800 1130"
      />
    </svg>

    <main class="cartao-sessao">
      <div class="marca">
        <img :src="logo" alt="Em Conta — Gestão de energia" width="220" height="55" />
      </div>
      <slot name="aviso" />
      <h1 ref="tituloEl" tabindex="-1">{{ titulo }}</h1>
      <slot />
    </main>
  </div>
</template>

<style scoped>
/* Medidas dos quadros (1672 × 1100): cartão de 440 px com 36 px nas laterais, campos de 42 px e
   botão de 41 px. */
.sessao {
  position: relative;
  display: grid;
  place-items: center;
  box-sizing: border-box;
  min-height: 100vh;
  min-height: 100dvh;
  padding: 24px 16px;
  overflow: hidden;
  background: var(--cor-fundo);
}

.curvas {
  position: absolute;
  pointer-events: none;
}

.curvas--topo {
  top: 0;
  right: 0;
  transform-origin: top right;
}

.curvas--base {
  bottom: 0;
  left: 0;
  transform-origin: bottom left;
}

.curvas path {
  fill: none;
}

.curvas .faixa {
  stroke: #ebf5ed;
  stroke-width: 41;
}

.curvas .linha {
  stroke: var(--cor-destaque-suave);
  stroke-width: 2;
}

.cartao-sessao {
  position: relative;
  box-sizing: border-box;
  width: 100%;
  max-width: 440px;
  padding: 33px 36px 32px;
  background: var(--cor-superficie);
  border: 1px solid var(--cor-borda);
  border-radius: 16px;
  box-shadow: 0 1px 2px rgb(30 36 32 / 4%);
}

.marca {
  padding-bottom: 14px;
  border-bottom: 1px solid var(--cor-borda);
}

.marca img {
  display: block;
  margin: 0 auto;
}

h1 {
  margin: 17px 0 14px;
  font-size: 22px;
  line-height: 1.2;
  text-align: center;
}

h1:focus {
  outline: none;
}

/* Conteúdo das telas (vem dos slots). */
.cartao-sessao :deep(label) {
  font-size: 14px;
  font-weight: 500;
}

.cartao-sessao :deep(.campo) {
  margin-bottom: 14px;
}

.cartao-sessao :deep(input) {
  box-sizing: border-box;
  height: 42px;
  padding: 0 12px;
  font-size: 14px;
}

/* Campo inválido: borda e ícone de alerta (a mensagem em texto continua sendo a informação). */
.cartao-sessao :deep(input[aria-invalid='true']) {
  padding-right: 40px;
  background: var(--cor-superficie)
    url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%23b42318' stroke-width='1.8' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M12 3.5L2.5 20h19z'/%3E%3Cpath d='M12 10v4.5'/%3E%3Cpath d='M12 17.3h.01'/%3E%3C/svg%3E")
    no-repeat right 12px center / 18px;
}

.cartao-sessao :deep(.campo-senha input) {
  padding-right: 44px;
}

.cartao-sessao :deep(.campo-senha input[aria-invalid='true']) {
  padding-right: 72px;
}

.cartao-sessao :deep(.campo-senha:has(input[aria-invalid='true']) .alternar) {
  right: 36px;
}

.cartao-sessao :deep(.ajuda) {
  font-size: 12px;
}

.cartao-sessao :deep(.descricao) {
  margin: 0 0 14px;
  font-size: 14px;
  line-height: 1.35;
}

.cartao-sessao :deep(.descricao--suave) {
  color: var(--cor-texto-2);
  font-size: 13px;
}

.cartao-sessao :deep(.resumo-erro) {
  max-width: 285px;
  margin: 0 auto 12px;
  color: var(--cor-erro);
  font-size: 15px;
  line-height: 1.2;
  text-align: center;
}

/* Botão principal (e link com a mesma aparência): largura total, gradiente verde e seta. */
.cartao-sessao :deep(.principal) {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  box-sizing: border-box;
  width: 100%;
  height: 41px;
  padding: 0 16px;
  border: none;
  border-radius: var(--raio);
  background: linear-gradient(90deg, var(--cor-destaque), var(--cor-destaque-forte));
  color: #fff;
  font-size: 15px;
  font-weight: 500;
  text-decoration: none;
}

.cartao-sessao :deep(.principal:hover:not(:disabled)) {
  background: var(--cor-destaque-forte);
}

/* Só fica desabilitado durante o envio, e o texto já diz isso ("Entrando…"): sem esmaecer. */
.cartao-sessao :deep(.principal:disabled) {
  opacity: 1;
  cursor: progress;
}

/* Links secundários: "Esqueci minha senha" (à direita) e "Voltar ao login" (centralizado). */
.cartao-sessao :deep(.link-lateral) {
  margin: -8px 0 14px;
  font-size: 13px;
  font-weight: 500;
  text-align: right;
}

.cartao-sessao :deep(.link-voltar) {
  display: flex;
  justify-content: center;
  margin: 12px 0 0;
  font-size: 13px;
  font-weight: 500;
}

.cartao-sessao :deep(.link-voltar > a),
.cartao-sessao :deep(.link-voltar > button) {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 500;
}

.cartao-sessao :deep(.alerta) {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin: 16px 0 0;
  padding: 12px 16px;
  border: none;
  border-radius: var(--raio);
  color: var(--cor-texto);
  font-size: 13px;
  line-height: 1.35;
}

.cartao-sessao :deep(.alerta--aviso) {
  background: #fff4dc;
}

.cartao-sessao :deep(.alerta--aviso .icone) {
  color: var(--cor-alerta);
}

.cartao-sessao :deep(.alerta--sucesso) {
  background: var(--cor-destaque-suave);
}

.cartao-sessao :deep(.alerta--info) {
  background: var(--cor-superficie-2);
}

/* Celular: cartão na largura disponível e curvas pela metade. */
@media (max-width: 30rem) {
  .cartao-sessao {
    padding: 24px 20px 24px;
  }

  .curvas {
    transform: scale(0.5);
  }
}
</style>
