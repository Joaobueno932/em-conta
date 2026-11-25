<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, useId } from 'vue'

import IconeTraco from './IconeTraco.vue'

/**
 * Botão quadrado de filtros das listagens (ícone de funil, ponto verde quando há filtro ativo) com
 * painel suspenso. Fecha com Esc ou clique fora e devolve o foco ao botão.
 */
defineProps<{ ativos: number }>()
const aberto = ref(false)
const area = ref<HTMLElement | null>(null)
const botao = ref<HTMLButtonElement | null>(null)
const idPainel = useId()

function fechar(devolverFoco = false) {
  if (!aberto.value) return
  aberto.value = false
  if (devolverFoco) void nextTick(() => botao.value?.focus())
}
function cliqueFora(evento: MouseEvent) {
  if (aberto.value && area.value && !area.value.contains(evento.target as Node)) fechar()
}
onMounted(() => document.addEventListener('click', cliqueFora))
onBeforeUnmount(() => document.removeEventListener('click', cliqueFora))
defineExpose({ fechar })
</script>

<template>
  <div ref="area" class="filtros" @keydown.esc="fechar(true)">
    <button
      ref="botao"
      type="button"
      class="botao-quadrado"
      :aria-expanded="aberto"
      :aria-controls="idPainel"
      :aria-label="ativos ? `Filtros (${ativos} ativo${ativos > 1 ? 's' : ''})` : 'Filtros'"
      title="Filtros"
      @click="aberto = !aberto"
    >
      <IconeTraco nome="filtro" :tamanho="20" />
      <span v-if="ativos" class="ponto" aria-hidden="true" />
    </button>
    <div v-show="aberto" :id="idPainel" class="painel-filtros">
      <slot :fechar="fechar" />
    </div>
  </div>
</template>

<style scoped>
.filtros {
  position: relative;
}

.ponto {
  position: absolute;
  top: 8px;
  right: 8px;
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: var(--cor-destaque);
}

.painel-filtros {
  position: absolute;
  top: calc(100% + 6px);
  right: 0;
  z-index: 20;
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: 280px;
  padding: 16px;
  background: var(--cor-superficie);
  border: 1px solid var(--cor-borda);
  border-radius: var(--raio-cartao);
  box-shadow: var(--sombra-menu);
}
</style>
