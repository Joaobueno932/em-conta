<script setup lang="ts">
import { keepPreviousData, useQuery } from '@tanstack/vue-query'
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { describeApiError } from '@/shared/api/errors'
import { ApiError } from '@/shared/api/http-client'

import { chavesUsinas, listarFazendas } from './usinas-api'

export interface FazendaSelecionada {
  id: number
  codigo: string
  nome: string
}

/**
 * Seleção opcional de fazenda com busca e paginação no servidor. A fazenda escolhida fica sempre
 * visível, mesmo fora dos resultados atuais, e não muda ao trocar a busca ou a página nem quando a
 * busca falha. Cada busca tem a própria chave no TanStack Query: a resposta de uma busca antiga nunca
 * substitui a da busca atual.
 */
const props = defineProps<{ id: string; erro?: string }>()
const selecionada = defineModel<FazendaSelecionada | null>({ required: true })

const TAMANHO = 20
const http = useHttpClient()
const termo = ref('')
const busca = ref('')
const pagina = ref(0)

const resultados = useQuery({
  queryKey: computed(() => [...chavesUsinas.fazendas, 'seletor', busca.value, pagina.value]),
  queryFn: () => listarFazendas(http, busca.value, pagina.value, TAMANHO),
  placeholderData: keepPreviousData,
})

function buscar() {
  busca.value = termo.value.trim()
  pagina.value = 0
}

const semPermissao = computed(
  () => resultados.error.value instanceof ApiError && resultados.error.value.status === 403,
)
const descricao = computed(() =>
  [props.erro ? `${props.id}-erro` : '', `${props.id}-ajuda`].filter(Boolean).join(' '),
)
</script>

<template>
  <fieldset class="seletor" :aria-describedby="descricao">
    <legend>Fazenda (opcional)</legend>

    <p class="selecionada" aria-live="polite">
      <template v-if="selecionada">
        Selecionada: <strong>{{ selecionada.codigo }} · {{ selecionada.nome }}</strong>
        <button type="button" class="link" @click="selecionada = null">
          Remover fazenda<span class="visualmente-oculto"> {{ selecionada.nome }}</span>
        </button>
      </template>
      <template v-else>Nenhuma fazenda selecionada.</template>
    </p>

    <div class="acoes">
      <label :for="`${id}-busca`" class="visualmente-oculto">Buscar fazenda</label>
      <input
        :id="`${id}-busca`"
        v-model="termo"
        type="search"
        maxlength="100"
        placeholder="Nome ou identificador (FZD-0001)…"
        @keydown.enter.prevent="buscar"
      />
      <button type="button" class="secundario" @click="buscar">Buscar fazenda</button>
    </div>

    <p v-if="resultados.isPending.value" role="status">Carregando fazendas…</p>
    <p v-else-if="resultados.isError.value" class="ajuda ajuda--erro" role="alert">
      {{
        semPermissao
          ? 'Você não tem permissão para consultar fazendas.'
          : describeApiError(resultados.error.value)
      }}
      A seleção atual foi mantida.
      <button v-if="!semPermissao" type="button" class="link" @click="resultados.refetch()">
        Tentar novamente
      </button>
    </p>
    <template v-else-if="resultados.data.value">
      <p v-if="resultados.data.value.totalItens === 0" class="ajuda" role="status">
        {{ busca ? `Nenhuma fazenda encontrada para “${busca}”.` : 'Nenhuma fazenda cadastrada.' }}
      </p>
      <ul v-else class="opcoes" :aria-busy="resultados.isFetching.value">
        <li v-for="f in resultados.data.value.itens" :key="f.id">
          <button
            type="button"
            class="opcao"
            :aria-pressed="selecionada?.id === f.id"
            @click="selecionada = { id: f.id, codigo: f.codigo, nome: f.nome }"
          >
            <span class="visualmente-oculto">Selecionar </span>{{ f.codigo }} · {{ f.nome }}
            <span v-if="f.cidade || f.uf" class="ajuda">{{
              [f.cidade, f.uf].filter(Boolean).join('/')
            }}</span>
          </button>
        </li>
      </ul>
      <nav
        v-if="resultados.data.value.totalPaginas > 1"
        class="acoes"
        :aria-label="`Páginas de fazendas`"
      >
        <button type="button" class="secundario" :disabled="pagina === 0" @click="pagina--">
          Anterior
        </button>
        <span>Página {{ pagina + 1 }} de {{ resultados.data.value.totalPaginas }}</span>
        <button
          type="button"
          class="secundario"
          :disabled="pagina + 1 >= resultados.data.value.totalPaginas"
          @click="pagina++"
        >
          Próxima
        </button>
      </nav>
    </template>

    <p :id="`${id}-ajuda`" class="ajuda">
      Não encontrou? <RouterLink :to="{ name: 'fazenda-nova' }">Cadastrar fazenda</RouterLink>
    </p>
    <p v-if="erro" :id="`${id}-erro`" class="ajuda ajuda--erro">{{ erro }}</p>
  </fieldset>
</template>

<style scoped>
.seletor {
  border: 1px solid var(--cor-borda);
  border-radius: var(--raio);
  padding: 0.75rem 1rem;
  margin: 0 0 1rem;
}

.seletor input[type='search'] {
  flex: 1 1 14rem;
}

.selecionada {
  margin: 0 0 0.5rem;
}

.opcoes {
  list-style: none;
  padding: 0;
  margin: 0.5rem 0;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.opcao {
  width: 100%;
  text-align: left;
  background: var(--cor-superficie);
  color: var(--cor-texto);
  border-color: var(--cor-borda);
}

.opcao .ajuda {
  margin-left: 0.5rem;
}

.opcao[aria-pressed='true'] {
  background: var(--cor-destaque-suave);
  border-color: var(--cor-destaque-borda);
  font-weight: 600;
}
</style>
