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
