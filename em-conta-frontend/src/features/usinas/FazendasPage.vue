<script setup lang="ts">
import { keepPreviousData, useQuery } from '@tanstack/vue-query'
import { computed, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { temPermissao, useSessao } from '@/features/auth/auth-api'
import { describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'

import { chavesUsinas, listarFazendas, localizacao } from './usinas-api'

/** Fazendas (cadastro simples que agrupa usinas). Sem frame no Figma: segue o padrão das listagens. */
const http = useHttpClient()
const route = useRoute()
const { data: sessao } = useSessao()

const termo = ref('')
const busca = ref('')
const pagina = ref(0)

const lista = useQuery({
  queryKey: computed(() => [...chavesUsinas.fazendas, 'pagina', busca.value, pagina.value]),
  queryFn: () => listarFazendas(http, busca.value, pagina.value, 20),
  placeholderData: keepPreviousData,
})
const podeCadastrar = computed(() => temPermissao(sessao.value, 'usinas:cadastrar'))
const podeEditar = computed(() => temPermissao(sessao.value, 'usinas:editar'))
</script>

<template>
  <main class="pagina pagina--larga">
    <nav aria-label="Trilha" class="ajuda">
      <RouterLink :to="{ name: 'usinas' }">Usinas</RouterLink> / Fazendas
    </nav>
    <div class="topo">
      <h1>Fazendas</h1>
      <RouterLink v-if="podeCadastrar" class="botao" :to="{ name: 'fazenda-nova' }"
        >Nova fazenda</RouterLink
      >
    </div>
    <AlertaMensagem v-if="route.query.salva === '1'" tipo="sucesso">Fazenda salva.</AlertaMensagem>
    <section class="cartao">
      <form
        class="acoes filtros"
        role="search"
        @submit.prevent="
          () => {
            busca = termo.trim()
            pagina = 0
          }
        "
      >
        <label for="busca-fazendas" class="visualmente-oculto">Buscar fazendas</label>
        <input
          id="busca-fazendas"
          v-model="termo"
          type="search"
          maxlength="100"
          placeholder="Buscar por nome ou identificador (FZD-0001)…"
        />
        <button type="submit" class="secundario">Buscar</button>
      </form>
      <p v-if="lista.isPending.value" role="status">Carregando fazendas…</p>
      <div v-else-if="lista.isError.value">
        <AlertaMensagem tipo="erro">{{ describeApiError(lista.error.value) }}</AlertaMensagem>
        <button type="button" class="secundario" @click="lista.refetch()">Tentar novamente</button>
      </div>
      <template v-else-if="lista.data.value">
        <p v-if="lista.data.value.totalItens === 0" class="ajuda" role="status">
          {{ busca ? 'Nenhuma fazenda encontrada.' : 'Nenhuma fazenda cadastrada ainda.' }}
        </p>
        <table v-else>
          <thead>
            <tr>
              <th scope="col">Fazenda</th>
              <th scope="col">Identificador</th>
              <th scope="col">Localização</th>
              <th scope="col">Usinas</th>
              <th v-if="podeEditar" scope="col"><span class="visualmente-oculto">Ações</span></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="f in lista.data.value.itens" :key="f.id">
              <td>{{ f.nome }}</td>
              <td>{{ f.codigo }}</td>
              <td>{{ localizacao(f.cidade, f.uf) }}</td>
              <td>{{ f.usinas }}</td>
              <td v-if="podeEditar">
                <RouterLink :to="{ name: 'fazenda-editar', params: { id: f.id } }">
                  Editar<span class="visualmente-oculto"> fazenda {{ f.nome }}</span>
                </RouterLink>
              </td>
            </tr>
          </tbody>
        </table>
        <nav
          v-if="lista.data.value.totalPaginas > 1"
          class="acoes acoes--fim"
          aria-label="Paginação"
        >
          <button type="button" class="secundario" :disabled="pagina === 0" @click="pagina--">
            Anterior
          </button>
          <span>Página {{ pagina + 1 }} de {{ lista.data.value.totalPaginas }}</span>
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
    </section>
  </main>
</template>

<style scoped>
.topo {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 1rem;
}

.filtros {
  margin-bottom: 1rem;
}

.filtros input {
  flex: 1 1 20rem;
}
</style>
