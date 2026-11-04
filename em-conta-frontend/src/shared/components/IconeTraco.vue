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

defineProps<{ nome: NomeIcone; tamanho?: number }>()

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
  informacao: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'M12 11v5', 'M12 8h.01'],
  cadeado: ['M6 11h12v9H6z', 'M8.5 11V8a3.5 3.5 0 0 1 7 0v3'],
  casa: ['M3 10.5L12 3l9 7.5', 'M5 9v11h14V9', 'M10 20v-6h4v6'],
  fone: ['M4 15v-3a8 8 0 0 1 16 0v3', 'M4 14h3v6H5a1 1 0 0 1-1-1z', 'M20 14h-3v6h2a1 1 0 0 0 1-1z'],
  pessoas: [
    'M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z',
    'M2 21a7 7 0 0 1 14 0',
    'M16 3.5a4 4 0 0 1 0 7',
    'M18 14.5a6 6 0 0 1 4 6.5',
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
    stroke-width="1.8"
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
