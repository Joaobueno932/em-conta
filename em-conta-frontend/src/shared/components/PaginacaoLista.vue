<script setup lang="ts">
import { computed, useId } from 'vue'

import IconeTraco from './IconeTraco.vue'

/**
 * Rodapé das listagens (pranchas de Usinas e Associados): intervalo exibido, linhas por página e
 * navegação com setas e números (o atual em verde). Página começa em 0, como na API.
 */
const props = defineProps<{
  pagina: number
  totalPaginas: number
  totalItens: number
  itensNaPagina: number
  tamanho: number
  rotulo: string
  tamanhos?: readonly number[]
}>()
const emit = defineEmits<{ ir: [pagina: number]; tamanho: [tamanho: number] }>()
const idTamanho = useId()

const intervalo = computed(() => {
  if (props.totalItens === 0) return ''
  const inicio = props.pagina * props.tamanho + 1
  return `Mostrando ${inicio}–${inicio + props.itensNaPagina - 1} de ${props.totalItens} ${props.rotulo}`
})
const ultima = computed(() => Math.max(props.totalPaginas, 1) - 1)
// Até cinco números em torno da página atual.
const numeros = computed(() => {
  const inicio = Math.max(0, Math.min(props.pagina - 2, ultima.value - 4))
  const fim = Math.min(ultima.value, inicio + 4)
  return Array.from({ length: fim - inicio + 1 }, (_, i) => inicio + i)
})
</script>

<template>
  <nav class="paginacao" aria-label="Paginação">
    <span class="intervalo">{{ intervalo }}</span>
    <div class="controles">
      <template v-if="tamanhos">
        <label :for="idTamanho" class="rotulo-tamanho">Linhas por página</label>
        <select
          :id="idTamanho"
          :value="tamanho"
          @change="emit('tamanho', Number(($event.target as HTMLSelectElement).value))"
        >
          <option v-for="t in tamanhos" :key="t" :value="t">{{ t }}</option>
        </select>
      </template>
      <button
        type="button"
        class="passo"
        aria-label="Página anterior"
        :disabled="pagina === 0"
        @click="emit('ir', pagina - 1)"
      >
        <IconeTraco nome="seta-esquerda" :tamanho="16" />
      </button>
      <button
        v-for="n in numeros"
        :key="n"
        type="button"
        class="passo numero"
        :class="{ atual: n === pagina }"
        :aria-current="n === pagina ? 'page' : undefined"
        :aria-label="`Página ${n + 1}`"
        @click="n !== pagina && emit('ir', n)"
      >
        {{ n + 1 }}
      </button>
      <button
        type="button"
        class="passo"
        aria-label="Próxima página"
        :disabled="pagina >= ultima"
        @click="emit('ir', pagina + 1)"
      >
        <IconeTraco nome="seta-direita" :tamanho="16" />
      </button>
    </div>
  </nav>
</template>

<style scoped>
.paginacao {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-top: 26px;
  color: var(--cor-texto-2);
  font-size: 13px;
}

.controles {
  display: flex;
  align-items: center;
  gap: 8px;
}

.rotulo-tamanho {
  font-size: 13px;
  font-weight: 400;
}

select {
  height: 32px;
  margin-right: 20px;
  padding: 0 8px;
  border-color: var(--cor-borda);
  font-size: 13px;
}

button.passo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 32px;
  height: 32px;
  padding: 0 6px;
  border: 1px solid var(--cor-borda);
  border-radius: 6px;
  background: var(--cor-superficie);
  color: var(--cor-texto);
  font-size: 13px;
}

button.passo:hover:not(:disabled, .atual) {
  background: var(--cor-superficie-2);
}

/* Verde mais escuro que o da prancha: o número branco de 13 px precisa de 4,5:1. */
button.numero.atual {
  border-color: var(--cor-destaque-forte);
  background: var(--cor-destaque-forte);
  color: #fff;
  cursor: default;
}
</style>
