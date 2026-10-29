<script setup lang="ts">
import { keepPreviousData, useQuery } from '@tanstack/vue-query'
import { computed, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'

import { chavesAtendimento, formatarDataHora, historico, nomeContato } from './atendimento-api'

/** Histórico geral (HG-02): atendimentos encerrados, com busca e período filtrados no servidor. */
const http = useHttpClient()
const route = useRoute()

const inicial = typeof route.query.busca === 'string' ? route.query.busca : ''
const termo = ref(inicial)
const filtros = ref({ busca: inicial, de: '', ate: '' })
const de = ref('')
const ate = ref('')
const pagina = ref(0)

const lista = useQuery({
  queryKey: computed(() =>
    chavesAtendimento.historico(
      filtros.value.busca,
      filtros.value.de,
      filtros.value.ate,
      pagina.value,
    ),
  ),
  queryFn: () =>
    historico(http, filtros.value.busca, filtros.value.de, filtros.value.ate, pagina.value),
  placeholderData: keepPreviousData,
})

function filtrar() {
  filtros.value = { busca: termo.value.trim(), de: de.value, ate: ate.value }
  pagina.value = 0
}
</script>

<template>
  <main class="pagina pagina--larga">
    <p><RouterLink :to="{ name: 'atendimento' }">← Atendimento</RouterLink></p>
    <h1>Histórico geral</h1>
    <p class="subtitulo">Atendimentos encerrados. Somente consulta.</p>

    <form class="cartao filtros" role="search" @submit.prevent="filtrar">
      <div class="campo">
        <label for="hist-busca">Contato (nome ou número)</label>
        <input id="hist-busca" v-model="termo" type="search" maxlength="100" />
      </div>
      <div class="campo">
        <label for="hist-de">De</label>
        <input id="hist-de" v-model="de" type="date" />
      </div>
      <div class="campo">
        <label for="hist-ate">Até</label>
        <input id="hist-ate" v-model="ate" type="date" />
      </div>
      <button type="submit">Filtrar</button>
    </form>

    <p v-if="lista.isPending.value" role="status">Carregando histórico…</p>
    <AlertaMensagem v-else-if="lista.isError.value" tipo="erro">
      {{ describeApiError(lista.error.value) }}
    </AlertaMensagem>
    <template v-else-if="lista.data.value">
      <p v-if="lista.data.value.totalItens === 0" role="status">Nenhum atendimento encontrado.</p>
      <div v-else class="tabela-rolavel cartao">
        <table>
          <thead>
            <tr>
              <th scope="col">Código</th>
              <th scope="col">Contato</th>
              <th scope="col">Associado</th>
              <th scope="col">Atendente</th>
              <th scope="col">Início</th>
              <th scope="col">Encerramento</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="a in lista.data.value.itens" :key="a.id">
              <td>
                <RouterLink :to="{ name: 'atendimento-detalhe', params: { id: a.id } }">{{
                  a.codigo
                }}</RouterLink>
              </td>
              <td>{{ nomeContato(a) }}</td>
              <td>{{ a.associado?.nome ?? '—' }}</td>
              <td>{{ a.encerradoPor?.nome ?? a.responsavel?.nome ?? '—' }}</td>
              <td>{{ formatarDataHora(a.iniciadoEm) }}</td>
              <td>{{ a.encerradoEm ? formatarDataHora(a.encerradoEm) : '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <nav
        v-if="lista.data.value.totalPaginas > 1"
        class="acoes"
        aria-label="Paginação do histórico"
      >
        <button type="button" class="secundario" :disabled="pagina === 0" @click="pagina--">
          Anterior
        </button>
        <span>{{ pagina + 1 }} / {{ lista.data.value.totalPaginas }}</span>
        <button
          type="button"
          class="secundario"
          :disabled="pagina + 1 >= lista.data.value.totalPaginas"
          @click="pagina++"
        >
          Próxima
        </button>
      </nav>
    </template>
  </main>
</template>

<style scoped>
.filtros {
  display: grid;
  grid-template-columns: 2fr 1fr 1fr auto;
  gap: 0.75rem;
  align-items: end;
}

@media (max-width: 48rem) {
  .filtros {
    grid-template-columns: 1fr;
  }
}
</style>
