<script setup lang="ts">
/**
 * Ícone de traço (24×24, cor do texto), no estilo dos ícones do protótipo. Sempre decorativo
 * (`aria-hidden`): quem o usa dá o nome acessível ao botão ou link.
 */
export type NomeIcone =
  | 'fechar'
  | 'historico'
  | 'transferir'
  | 'concluir'
  | 'anexar'
  | 'enviar'
  | 'baixar'
  | 'pessoa'
  | 'voltar'
  | 'seta-acima'
  | 'seta-abaixo'
  | 'seta-direita'
  | 'seta-esquerda'
  | 'olho'
  | 'olho-cortado'
  | 'alerta'
  | 'informacao'
  | 'cadeado'
  | 'documento'
  | 'raio'
  | 'busca'
  | 'casa'
  | 'fone'
  | 'pessoas'
  | 'usina'
  | 'usuarios'
  | 'recolher'
  | 'expandir'
  | 'conversa'
  | 'telefone'
  | 'email'
  | 'externo'
  | 'sair'
  | 'predio'
  | 'relogio'
  | 'tique-duplo'
  | 'exclamacao'
  | 'interrogacao'
  | 'engrenagem'
  | 'filtro'
  | 'mais-circulo'
  | 'lapis'
  | 'chave'
  | 'tabela'
  | 'reticencias'
  | 'check-circulo'
  | 'proibido'
  | 'imagem'
  | 'mapa-pino'

defineProps<{ nome: NomeIcone; tamanho?: number; espessura?: number }>()

const TRACOS: Record<NomeIcone, string[]> = {
  fechar: ['M6 6l12 12', 'M18 6L6 18'],
  historico: ['M3 12a9 9 0 1 0 3-6.7', 'M3 4v5h5', 'M12 7v5l3 2'],
  transferir: ['M15 6l5 5-5 5', 'M20 11h-9a6 6 0 0 0-6 6v1'],
  concluir: ['M5 12.5l4.5 4.5L19 7'],
  anexar: [
    'M20 11.5l-8.2 8.2a5 5 0 0 1-7.1-7.1l8.6-8.6a3.4 3.4 0 0 1 4.8 4.8l-8.6 8.6a1.7 1.7 0 0 1-2.4-2.4l7.9-7.9',
  ],
  enviar: ['M21 3L10 14', 'M21 3l-7 18-4-7-7-4z'],
  baixar: ['M12 4v11', 'M7 10.5l5 5 5-5', 'M5 20h14'],
  pessoa: ['M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8z', 'M4 21a8 8 0 0 1 16 0'],
  voltar: ['M19 12H5', 'M11 6l-6 6 6 6'],
  'seta-acima': ['M6 15l6-6 6 6'],
  'seta-abaixo': ['M6 9l6 6 6-6'],
  'seta-direita': ['M9 6l6 6-6 6'],
  'seta-esquerda': ['M15 6l-6 6 6 6'],
  olho: [
    'M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z',
    'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z',
  ],
  'olho-cortado': [
    'M3 3l18 18',
    'M10.6 5.6a9.6 9.6 0 0 1 1.4-.1c6 0 9.5 6.5 9.5 6.5a17 17 0 0 1-2.9 3.6',
    'M6.6 6.6C3.9 8.3 2.5 12 2.5 12s3.5 6.5 9.5 6.5a9 9 0 0 0 4.5-1.2',
    'M9.9 9.9a3 3 0 0 0 4.2 4.2',
  ],
  alerta: ['M12 3.5L2.5 20h19z', 'M12 10v4.5', 'M12 17.3h.01'],
  informacao: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'M12 11v5', 'M12 8h.01'],
  cadeado: ['M6 11h12v9H6z', 'M8.5 11V8a3.5 3.5 0 0 1 7 0v3'],
  // Casa, fone e pessoas ocupam a caixa inteira (como os ícones do menu nas pranchas).
  casa: [
    'M3 10a2 2 0 0 1 .7-1.5l7-6a2 2 0 0 1 2.6 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z',
    'M9 21v-7a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v7',
  ],
  fone: [
    'M3 14h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a9 9 0 0 1 18 0v7a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2h3',
  ],
  pessoas: [
    'M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z',
    'M2 21v-1.5A4.5 4.5 0 0 1 6.5 15h5a4.5 4.5 0 0 1 4.5 4.5V21',
    'M16 3.1a4 4 0 0 1 0 7.8',
    'M22 21v-1.5a4.5 4.5 0 0 0-3.4-4.4',
  ],
  usina: [
    'M3 13l2-9h14l2 9z',
    'M3.9 8.5h16.2',
    'M9.5 4l-.9 9',
    'M14.5 4l.9 9',
    'M12 13v5',
    'M8 21h8',
    'M12 18v3',
  ],
  usuarios: [
    'M10 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z',
    'M3 21a7 7 0 0 1 11-5.7',
    'M18 15v6',
    'M15 18h6',
  ],
  recolher: ['M6 5v14', 'M17 7l-5 5 5 5'],
  expandir: ['M18 5v14', 'M7 7l5 5-5 5'],
  conversa: ['M20.5 11.5a8.5 8.5 0 0 1-12.4 7.5L3.5 20.5l1.5-4.6a8.5 8.5 0 1 1 15.5-4.4z'],
  telefone: [
    'M5 4h3.5l2 5-2.5 1.5a11 11 0 0 0 5.5 5.5L15 13.5l5 2V19a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2z',
  ],
  email: ['M3.5 6h17v12h-17z', 'M3.5 7l8.5 6 8.5-6'],
  externo: ['M14 4h6v6', 'M20 4l-9 9', 'M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5'],
  sair: ['M15 4h4a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1h-4', 'M10 17l5-5-5-5', 'M15 12H3'],
  predio: ['M4 21V8l8-5 8 5v13', 'M3 21h18', 'M9 21v-6h6v6'],
  busca: ['M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z', 'M20 20l-4-4'],
  raio: ['M13 3L5 13.5h6L10 21l8-10.5h-6z'],
  documento: ['M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z', 'M14 3v5h5'],
  relogio: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'M12 7v5l3 2'],
  'tique-duplo': ['M1.5 12.5l4.5 4.5L15.5 7', 'M10.5 15.5L12 17l9.5-10'],
  exclamacao: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'M12 7.5v5.5', 'M12 16.5h.01'],
  interrogacao: [
    'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z',
    'M9.5 9.5a2.5 2.5 0 1 1 3.4 2.3c-.6.3-.9.8-.9 1.4v.6',
    'M12 17h.01',
  ],
  engrenagem: [
    'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z',
    'M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z',
  ],
  filtro: ['M4 7h16', 'M7 12h10', 'M10 17h4'],
  'mais-circulo': ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'M12 8v8', 'M8 12h8'],
  lapis: ['M4 20h4L19 9a2.8 2.8 0 0 0-4-4L4 16z', 'M13.5 6.5l4 4'],
  chave: [
    'M7.5 19.5a4 4 0 1 0 0-8 4 4 0 0 0 0 8z',
    'M10.3 12.7L19 4',
    'M16 7l2.5 2.5',
    'M14 9l2 2',
  ],
  tabela: ['M4 5h16v14H4z', 'M4 10h16', 'M4 15h16', 'M10 5v14'],
  reticencias: ['M5 12h.01', 'M12 12h.01', 'M19 12h.01'],
  'check-circulo': ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'M8.5 12.5l2.5 2.5 4.5-5'],
  proibido: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'M5.6 5.6l12.8 12.8'],
  imagem: ['M4 5h16v14H4z', 'M4 16l5-5 4 4 2-2 5 5', 'M15 9.5h.01'],
  'mapa-pino': [
    'M12 21s7-6.2 7-11.5a7 7 0 0 0-14 0C5 14.8 12 21 12 21z',
    'M12 12a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5z',
  ],
}
</script>

<template>
  <svg
    class="icone"
    :width="tamanho ?? 18"
    :height="tamanho ?? 18"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    :stroke-width="espessura ?? 1.8"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    focusable="false"
  >
    <path v-for="d in TRACOS[nome]" :key="d" :d="d" />
  </svg>
</template>

<style scoped>
.icone {
  flex-shrink: 0;
  vertical-align: middle;
}
</style>
