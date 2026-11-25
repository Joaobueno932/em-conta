<script setup lang="ts">
import { keepPreviousData, useQuery } from '@tanstack/vue-query'
import { computed, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { temPermissao, useSessao } from '@/features/auth/auth-api'
import { describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'
import CabecalhoPagina from '@/shared/components/CabecalhoPagina.vue'
import IconeTraco from '@/shared/components/IconeTraco.vue'
import PaginacaoLista from '@/shared/components/PaginacaoLista.vue'

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
  <main class="pagina-modulo">
    <CabecalhoPagina titulo="Fazendas" subtitulo="Cadastro simples que agrupa usinas.">
      <template #trilha>
        <nav aria-label="Trilha" class="trilha">
          <RouterLink :to="{ name: 'usinas' }">Usinas</RouterLink>
          <span aria-hidden="true">›</span>
          <span aria-current="page">Fazendas</span>
        </nav>
      </template>
      <template #acoes>
        <RouterLink v-if="podeCadastrar" class="botao-primario" :to="{ name: 'fazenda-nova' }">
          <IconeTraco nome="mais-circulo" :tamanho="18" />
          Nova fazenda
        </RouterLink>
      </template>
    </CabecalhoPagina>
    <AlertaMensagem v-if="route.query.salva === '1'" tipo="sucesso">Fazenda salva.</AlertaMensagem>
    <section class="painel lista">
      <form
        class="barra-lista"
        role="search"
        @submit.prevent="
          () => {
            busca = termo.trim()
            pagina = 0
          }
        "
      >
        <div class="busca-lista">
          <label for="busca-fazendas" class="visualmente-oculto">Buscar fazendas</label>
          <IconeTraco nome="busca" :tamanho="18" />
          <input
            id="busca-fazendas"
            v-model="termo"
            type="search"
            maxlength="100"
            placeholder="Buscar por nome ou identificador (FZD-0001)…"
          />
        </div>
        <button type="submit" class="botao-contorno">Buscar</button>
      </form>
      <p v-if="lista.isPending.value" role="status">Carregando fazendas…</p>
      <div v-else-if="lista.isError.value">
        <AlertaMensagem tipo="erro">{{ describeApiError(lista.error.value) }}</AlertaMensagem>
        <button type="button" class="botao-contorno" @click="lista.refetch()">
          Tentar novamente
        </button>
      </div>
      <template v-else-if="lista.data.value">
        <div v-if="lista.data.value.totalItens === 0" class="estado-vazio" role="status">
          <span class="estado-vazio__icone"><IconeTraco nome="busca" :tamanho="24" /></span>
          <strong>{{
            busca ? 'Nenhuma fazenda encontrada.' : 'Nenhuma fazenda cadastrada ainda.'
          }}</strong>
        </div>
        <table v-else class="tabela-dados">
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
              <td>
                <strong>{{ f.nome }}</strong>
              </td>
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
        <PaginacaoLista
          v-if="lista.data.value.totalPaginas > 1"
          :pagina="pagina"
          :total-paginas="lista.data.value.totalPaginas"
          :total-itens="lista.data.value.totalItens"
          :itens-na-pagina="lista.data.value.itens.length"
          :tamanho="20"
          rotulo="fazendas"
          @ir="pagina = $event"
        />
      </template>
    </section>
  </main>
</template>

<style scoped>
.lista {
  padding: 16px 18px;
}

.trilha {
  margin-bottom: 4px;
}
</style>
