<script setup lang="ts">
import { keepPreviousData, useQuery } from '@tanstack/vue-query'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import { useHttpClient } from '@/app/injection'
import { describeApiError } from '@/shared/api/errors'

import {
  chavesRespostas,
  listarRespostas,
  TAMANHO_SELETOR,
  type RespostaRapida,
} from './respostas-rapidas-api'

/**
 * Seletor de respostas rápidas do compositor (AT-07). Busca no servidor só ativas, por título e
 * atalho, com poucos resultados, e mostra a prévia do item ativo. Escolher só devolve a resposta: quem
 * usa decide onde inserir; nada é enviado daqui.
 *
 * Dois modos: com campo de busca próprio (aberto pelo botão) ou guiado pelo texto digitado depois de
 * "/" no campo de mensagem (`termo`), em que o foco fica no campo e quem usa repassa as teclas por
 * `teclar`.
 */
const props = defineProps<{ termo?: string; id: string }>()
const emit = defineEmits<{ escolher: [resposta: RespostaRapida]; fechar: [] }>()

const http = useHttpClient()
const proprio = computed(() => props.termo === undefined)
const digitado = ref('')
const busca = ref((props.termo ?? '').trim())
let espera: number | undefined
watch(
  () => (proprio.value ? digitado.value : (props.termo ?? '')).trim(),
  (valor) => {
    window.clearTimeout(espera)
    espera = window.setTimeout(() => (busca.value = valor), 200)
  },
)
onBeforeUnmount(() => window.clearTimeout(espera))

const consulta = useQuery({
  queryKey: computed(() => chavesRespostas.seletor(busca.value)),
  queryFn: () =>
    listarRespostas(http, {
      busca: busca.value,
      situacao: 'ATIVAS',
      tamanho: TAMANHO_SELETOR,
      noConteudo: false,
    }),
  placeholderData: keepPreviousData,
})
const itens = computed(() => consulta.data.value?.itens ?? [])
const ativo = ref(0)
watch(itens, () => (ativo.value = 0))
const atual = computed(() => itens.value[ativo.value])
const opcaoId = (i: number) => `${props.id}-opcao-${i}`

/** Trata a tecla; devolve true quando ela foi usada pelo seletor. */
function teclar(evento: KeyboardEvent): boolean {
  if (evento.key === 'ArrowDown' || evento.key === 'ArrowUp') {
    if (itens.value.length === 0) return true
    const passo = evento.key === 'ArrowDown' ? 1 : -1
    ativo.value = (ativo.value + passo + itens.value.length) % itens.value.length
    return true
  }
  if (evento.key === 'Enter' || (evento.key === 'Tab' && !proprio.value)) {
    if (!atual.value) return evento.key === 'Enter'
    emit('escolher', atual.value)
    return true
  }
  if (evento.key === 'Escape') {
    emit('fechar')
    return true
  }
  return false
}
function teclarNaBusca(evento: KeyboardEvent) {
  if (teclar(evento)) evento.preventDefault()
}
defineExpose({
  teclar,
  opcaoAtiva: computed(() => (atual.value ? opcaoId(ativo.value) : undefined)),
})

const campo = ref<HTMLInputElement | null>(null)
onMounted(() => campo.value?.focus())
</script>

<template>
  <div class="seletor" role="dialog" aria-label="Respostas rápidas" @keydown.esc.stop>
    <div v-if="proprio" class="campo">
      <label :for="`${id}-busca`" class="visualmente-oculto">Buscar resposta rápida</label>
      <input
        :id="`${id}-busca`"
        ref="campo"
        v-model="digitado"
        type="search"
        role="combobox"
        aria-autocomplete="list"
        aria-expanded="true"
        :aria-controls="`${id}-lista`"
        :aria-activedescendant="atual ? opcaoId(ativo) : undefined"
        maxlength="80"
        placeholder="Buscar por título ou atalho"
        autocomplete="off"
        @keydown="teclarNaBusca"
      />
    </div>
    <p v-if="consulta.isPending.value" class="ajuda" role="status">Carregando respostas…</p>
    <div v-else-if="consulta.isError.value" class="ajuda" role="alert">
      {{ describeApiError(consulta.error.value) }}
      <button type="button" class="link" @click="consulta.refetch()">Tentar de novo</button>
    </div>
    <p v-else-if="itens.length === 0" class="ajuda" role="status">
      Nenhuma resposta ativa encontrada.
    </p>
    <div v-else class="corpo">
      <ul :id="`${id}-lista`" role="listbox" aria-label="Respostas encontradas" class="opcoes">
        <li
          v-for="(r, i) in itens"
          :id="opcaoId(i)"
          :key="r.id"
          role="option"
          :aria-selected="i === ativo"
          @mousedown.prevent
          @mouseenter="ativo = i"
          @click="emit('escolher', r)"
        >
          <strong>{{ r.titulo }}</strong>
          <code>/{{ r.atalho }}</code>
        </li>
      </ul>
      <div v-if="atual" class="previa" aria-live="polite">
        <p class="ajuda">Prévia</p>
        <p class="balao">{{ atual.conteudo }}</p>
      </div>
    </div>
    <p class="ajuda dica">↑ ↓ para escolher · Enter insere (não envia) · Esc fecha</p>
  </div>
</template>

<style scoped>
.seletor {
  border: 1px solid var(--cor-borda);
  border-radius: 10px;
  background: var(--cor-superficie);
  padding: 0.6rem;
  box-shadow: 0 6px 20px rgb(0 0 0 / 10%);
}

.seletor .campo {
  margin-bottom: 0.5rem;
}

.seletor input {
  width: 100%;
  box-sizing: border-box;
}

.corpo {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.2fr);
  gap: 0.75rem;
}

@media (max-width: 40rem) {
  .corpo {
    grid-template-columns: minmax(0, 1fr);
  }
}

.opcoes {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 14rem;
  overflow-y: auto;
}

.opcoes li {
  display: flex;
  flex-direction: column;
  padding: 0.4rem 0.5rem;
  border-radius: 6px;
  cursor: pointer;
}

.opcoes li[aria-selected='true'] {
  background: var(--cor-destaque-suave);
  outline: 1px solid var(--cor-destaque-borda);
}

.opcoes code {
  color: var(--cor-destaque-forte);
  font-size: 0.85rem;
}

.previa p {
  margin: 0;
}

.balao {
  margin-top: 0.25rem !important;
  max-height: 14rem;
  overflow-y: auto;
  padding: 0.6rem 0.85rem;
  border-radius: 10px;
  background: var(--cor-destaque-suave);
  border: 1px solid var(--cor-destaque-borda);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.dica {
  margin: 0.5rem 0 0;
  font-size: 0.8rem;
}
</style>
