<script setup lang="ts">
import { keepPreviousData, useQuery } from '@tanstack/vue-query'
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter, type LocationQuery } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { temPermissao, useSessao } from '@/features/auth/auth-api'
import { describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'

import {
  AVISO_VINCULO_CADASTRAL,
  chavesUsinas,
  listarUsinas,
  localizacao,
  UFS,
  type FiltroUsinas,
  type OrdenacaoUsinas,
} from './usinas-api'

const http = useHttpClient()
const route = useRoute()
const router = useRouter()
const { data: sessao } = useSessao()

const TAMANHOS = [20, 50, 100]

/** Filtro na URL: ao abrir uma usina e voltar, a lista reaparece igual. */
function filtroDaUrl(query: LocationQuery): FiltroUsinas {
  const texto = (valor: unknown) => (typeof valor === 'string' ? valor : '')
  const inteiro = (valor: unknown, padrao: number) => {
    const n = Number(texto(valor))
    return Number.isInteger(n) && n >= 0 ? n : padrao
  }
  const ordenacao = texto(query.ordenacao)
  const tamanho = inteiro(query.tamanho, 20)
  const uf = texto(query.uf)
  return {
    busca: texto(query.busca).slice(0, 100),
    uf: (UFS as readonly string[]).includes(uf) ? uf : '',
    ordenacao: (['NOME', 'CADASTRO_RECENTE', 'UCS_VINCULADAS'] as const).includes(
      ordenacao as OrdenacaoUsinas,
    )
      ? (ordenacao as OrdenacaoUsinas)
      : 'NOME',
    pagina: inteiro(query.pagina, 0),
    tamanho: TAMANHOS.includes(tamanho) ? tamanho : 20,
  }
}

const filtro = computed(() => filtroDaUrl(route.query))
const busca = ref(filtro.value.busca)
watch(
  () => filtro.value.busca,
  (valor) => (busca.value = valor),
)

function aplicar(mudancas: Partial<FiltroUsinas>) {
  const novo = { ...filtro.value, pagina: 0, ...mudancas }
  void router.push({
    query: {
      ...(novo.busca ? { busca: novo.busca } : {}),
      ...(novo.uf ? { uf: novo.uf } : {}),
      ...(novo.ordenacao !== 'NOME' ? { ordenacao: novo.ordenacao } : {}),
      ...(novo.pagina ? { pagina: String(novo.pagina) } : {}),
      ...(novo.tamanho !== 20 ? { tamanho: String(novo.tamanho) } : {}),
    },
  })
}

const lista = useQuery({
  queryKey: computed(() => chavesUsinas.lista(filtro.value)),
  queryFn: () => listarUsinas(http, filtro.value),
  placeholderData: keepPreviousData,
})

const podeCadastrar = computed(() => temPermissao(sessao.value, 'usinas:cadastrar'))
const intervalo = computed(() => {
  const dados = lista.data.value
  if (!dados || dados.totalItens === 0) return ''
  const inicio = dados.pagina * dados.tamanho + 1
  return `Mostrando ${inicio}–${inicio + dados.itens.length - 1} de ${dados.totalItens} usinas`
})
</script>

<template>
  <main class="pagina pagina--larga">
    <div class="topo">
      <div>
        <h1>Usinas</h1>
        <p class="subtitulo">
          Cadastro e consulta das usinas e de seus vínculos cadastrais com unidades consumidoras.
        </p>
      </div>
      <div class="acoes">
        <RouterLink class="botao-secundario" :to="{ name: 'fazendas' }">Fazendas</RouterLink>
        <RouterLink v-if="podeCadastrar" class="botao" :to="{ name: 'usina-nova' }">
          Nova usina
        </RouterLink>
      </div>
    </div>

    <section class="cartao" aria-labelledby="titulo-usinas">
      <h2 id="titulo-usinas" class="visualmente-oculto">Usinas cadastradas</h2>
      <form class="filtros" role="search" @submit.prevent="aplicar({ busca: busca.trim() })">
        <label for="busca-usinas" class="visualmente-oculto">Buscar usinas</label>
        <input
          id="busca-usinas"
          v-model="busca"
          type="search"
          maxlength="100"
          placeholder="Buscar por nome, fazenda ou identificador (USN-0001)…"
        />
        <button type="submit" class="secundario">Buscar</button>
        <label for="filtro-uf" class="visualmente-oculto">UF</label>
        <select
          id="filtro-uf"
          :value="filtro.uf"
          @change="aplicar({ uf: ($event.target as HTMLSelectElement).value })"
        >
          <option value="">Todas as UFs</option>
          <option v-for="uf in UFS" :key="uf" :value="uf">{{ uf }}</option>
        </select>
        <label for="ordenacao-usinas" class="visualmente-oculto">Ordenar por</label>
        <select
          id="ordenacao-usinas"
          :value="filtro.ordenacao"
          @change="
            aplicar({ ordenacao: ($event.target as HTMLSelectElement).value as OrdenacaoUsinas })
          "
        >
          <option value="NOME">Nome (A–Z)</option>
          <option value="CADASTRO_RECENTE">Cadastro mais recente</option>
          <option value="UCS_VINCULADAS">Mais UCs vinculadas</option>
        </select>
      </form>

      <p v-if="lista.isPending.value" role="status">Carregando usinas…</p>
      <div v-else-if="lista.isError.value">
        <AlertaMensagem tipo="erro">{{ describeApiError(lista.error.value) }}</AlertaMensagem>
        <button type="button" class="secundario" @click="lista.refetch()">Tentar novamente</button>
      </div>
      <template v-else-if="lista.data.value">
        <AlertaMensagem v-if="route.query.criada === '1'" tipo="sucesso"
          >Usina cadastrada.</AlertaMensagem
        >
        <p v-if="lista.data.value.totalItens === 0" class="vazio" role="status">
          <template v-if="filtro.busca || filtro.uf">
            Nenhuma usina encontrada para os filtros informados.
            <button type="button" class="link" @click="aplicar({ busca: '', uf: '' })">
              Limpar filtros
            </button>
          </template>
          <template v-else>Nenhuma usina cadastrada ainda.</template>
        </p>
        <div v-else class="tabela-rolavel">
          <table>
            <thead>
              <tr>
                <th scope="col">Usina</th>
                <th scope="col">Identificador</th>
                <th scope="col">Fazenda</th>
                <th scope="col">Localização</th>
                <th scope="col">UCs vinculadas</th>
                <th scope="col">Associados distintos</th>
              </tr>
            </thead>
            <tbody :aria-busy="lista.isFetching.value">
              <tr v-for="usina in lista.data.value.itens" :key="usina.id">
                <td>
                  <RouterLink :to="{ name: 'usina', params: { id: usina.id } }">
                    {{ usina.nome }}
                  </RouterLink>
                </td>
                <td>{{ usina.codigo }}</td>
                <td>{{ usina.fazendaNome || '—' }}</td>
                <td>{{ localizacao(usina.cidade, usina.uf) }}</td>
                <td>{{ usina.ucsVinculadas }}</td>
                <td>{{ usina.associadosDistintos }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <nav v-if="lista.data.value.totalItens > 0" class="acoes paginacao" aria-label="Paginação">
          <span class="ajuda">{{ intervalo }}</span>
          <label for="tamanho-usinas" class="ajuda">Linhas por página</label>
          <select
            id="tamanho-usinas"
            :value="filtro.tamanho"
            @change="aplicar({ tamanho: Number(($event.target as HTMLSelectElement).value) })"
          >
            <option v-for="t in TAMANHOS" :key="t" :value="t">{{ t }}</option>
          </select>
          <button
            type="button"
            class="secundario"
            :disabled="filtro.pagina === 0"
            @click="aplicar({ pagina: filtro.pagina - 1 })"
          >
            Anterior
          </button>
          <span
            >Página {{ filtro.pagina + 1 }} de
            {{ Math.max(lista.data.value.totalPaginas, 1) }}</span
          >
          <button
            type="button"
            class="secundario"
            :disabled="filtro.pagina + 1 >= lista.data.value.totalPaginas"
            @click="aplicar({ pagina: filtro.pagina + 1 })"
          >
            Próxima
          </button>
        </nav>
      </template>
    </section>
    <p class="ajuda">{{ AVISO_VINCULO_CADASTRAL }}</p>
  </main>
</template>

<style scoped>
.topo {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 1rem;
  flex-wrap: wrap;
}

.filtros {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-bottom: 1rem;
}

.filtros input {
  flex: 1 1 20rem;
}

.vazio {
  padding: 2rem 0;
  text-align: center;
  color: var(--cor-texto-2);
}

.paginacao {
  justify-content: flex-end;
  margin-top: 1rem;
}

.paginacao > .ajuda:first-child {
  margin-right: auto;
}
</style>
