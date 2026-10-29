<script setup lang="ts">
import { keepPreviousData, useQuery } from '@tanstack/vue-query'
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter, type LocationQuery } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { temPermissao, useSessao } from '@/features/auth/auth-api'
import { describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'

import {
  chaves,
  formatarData,
  listarAssociados,
  ROTULO_TIPO,
  TAMANHOS_PAGINA,
  type FiltroAssociados,
  type Ordenacao,
  type TipoPessoa,
} from './associados-api'

const http = useHttpClient()
const route = useRoute()
const router = useRouter()
const { data: sessao } = useSessao()

/**
 * O filtro vive na URL (`?busca=&tipo=&pagina=`): ao abrir um associado e voltar, a listagem
 * reaparece na mesma página e com os mesmos filtros.
 */
function filtroDaUrl(query: LocationQuery): FiltroAssociados {
  const texto = (valor: unknown) => (typeof valor === 'string' ? valor : '')
  const inteiro = (valor: unknown, padrao: number) => {
    const n = Number(texto(valor))
    return Number.isInteger(n) && n >= 0 ? n : padrao
  }
  const tipo = texto(query.tipo)
  const tamanho = inteiro(query.tamanho, 20)
  return {
    busca: texto(query.busca).slice(0, 100),
    tipo: tipo === 'PF' || tipo === 'PJ' ? tipo : '',
    ordenacao: texto(query.ordenacao) === 'CADASTRO_RECENTE' ? 'CADASTRO_RECENTE' : 'NOME',
    pagina: inteiro(query.pagina, 0),
    tamanho: (TAMANHOS_PAGINA as readonly number[]).includes(tamanho) ? tamanho : 20,
  }
}

const filtro = computed(() => filtroDaUrl(route.query))
const busca = ref(filtro.value.busca)
watch(
  () => filtro.value.busca,
  (valor) => (busca.value = valor),
)

function aplicar(mudancas: Partial<FiltroAssociados>) {
  const novo = { ...filtro.value, pagina: 0, ...mudancas }
  void router.push({
    query: {
      ...(novo.busca ? { busca: novo.busca } : {}),
      ...(novo.tipo ? { tipo: novo.tipo } : {}),
      ...(novo.ordenacao !== 'NOME' ? { ordenacao: novo.ordenacao } : {}),
      ...(novo.pagina ? { pagina: String(novo.pagina) } : {}),
      ...(novo.tamanho !== 20 ? { tamanho: String(novo.tamanho) } : {}),
    },
  })
}

const lista = useQuery({
  queryKey: computed(() => chaves.lista(filtro.value)),
  queryFn: () => listarAssociados(http, filtro.value),
  placeholderData: keepPreviousData,
})

const podeCadastrar = computed(() => temPermissao(sessao.value, 'associados:cadastrar'))
const criado = computed(() => route.query.criado === '1')

const intervalo = computed(() => {
  const dados = lista.data.value
  if (!dados || dados.totalItens === 0) return ''
  const inicio = dados.pagina * dados.tamanho + 1
  const fim = inicio + dados.itens.length - 1
  return `Mostrando ${inicio}–${fim} de ${dados.totalItens} associados`
})
</script>

<template>
  <main class="pagina pagina--larga">
    <div class="topo">
      <div>
        <h1>Todos os associados</h1>
        <p class="subtitulo">Consulte e gerencie os cadastros dos clientes da Em Conta.</p>
      </div>
      <RouterLink v-if="podeCadastrar" class="botao" :to="{ name: 'associado-novo' }">
        Novo associado
      </RouterLink>
    </div>

    <section class="cartao" aria-labelledby="titulo-lista">
      <h2 id="titulo-lista">
        Associados cadastrados
        <span v-if="lista.data.value" class="ajuda total">
          {{ lista.data.value.totalItens }} no total
        </span>
      </h2>

      <form class="filtros" role="search" @submit.prevent="aplicar({ busca: busca.trim() })">
        <label for="busca-associados" class="visualmente-oculto">Buscar associados</label>
        <input
          id="busca-associados"
          v-model="busca"
          type="search"
          maxlength="100"
          placeholder="Buscar por nome, razão social ou CPF/CNPJ…"
        />
        <button type="submit" class="secundario">Buscar</button>
        <label for="filtro-tipo" class="visualmente-oculto">Tipo de pessoa</label>
        <select
          id="filtro-tipo"
          :value="filtro.tipo"
          @change="aplicar({ tipo: ($event.target as HTMLSelectElement).value as TipoPessoa | '' })"
        >
          <option value="">PF e PJ</option>
          <option value="PF">Pessoa física</option>
          <option value="PJ">Pessoa jurídica</option>
        </select>
        <label for="ordenacao" class="visualmente-oculto">Ordenar por</label>
        <select
          id="ordenacao"
          :value="filtro.ordenacao"
          @change="aplicar({ ordenacao: ($event.target as HTMLSelectElement).value as Ordenacao })"
        >
          <option value="NOME">Nome (A–Z)</option>
          <option value="CADASTRO_RECENTE">Cadastro mais recente</option>
        </select>
      </form>

      <p v-if="lista.isPending.value" role="status">Carregando associados…</p>
      <div v-else-if="lista.isError.value">
        <AlertaMensagem tipo="erro">{{ describeApiError(lista.error.value) }}</AlertaMensagem>
        <button type="button" class="secundario" @click="lista.refetch()">Tentar novamente</button>
      </div>
      <template v-else-if="lista.data.value">
        <AlertaMensagem v-if="criado" tipo="sucesso">Associado cadastrado.</AlertaMensagem>
        <p v-if="lista.data.value.totalItens === 0" class="vazio" role="status">
          <template v-if="filtro.busca || filtro.tipo">
            Nenhum associado encontrado para os filtros informados.
            <button type="button" class="link" @click="aplicar({ busca: '', tipo: '' })">
              Limpar filtros
            </button>
          </template>
          <template v-else>Nenhum associado cadastrado ainda.</template>
        </p>
        <div v-else class="tabela-rolavel">
          <table>
            <thead>
              <tr>
                <th scope="col">Associado</th>
                <th scope="col">Tipo</th>
                <th scope="col">CPF/CNPJ</th>
                <th scope="col">UCs</th>
                <th scope="col">Cadastrado em</th>
              </tr>
            </thead>
            <tbody :aria-busy="lista.isFetching.value">
              <tr v-for="associado in lista.data.value.itens" :key="associado.id">
                <td>
                  <RouterLink :to="{ name: 'associado', params: { id: associado.id } }">
                    {{ associado.nome }}
                  </RouterLink>
                  <span v-if="associado.nomeFantasia" class="ajuda">
                    {{ associado.nomeFantasia }}
                  </span>
                </td>
                <td :title="ROTULO_TIPO[associado.tipo]">{{ associado.tipo }}</td>
                <td>{{ associado.documentoMascarado }}</td>
                <td>{{ associado.quantidadeUcs }}</td>
                <td>{{ formatarData(associado.criadoEm) }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <nav v-if="lista.data.value.totalItens > 0" class="acoes paginacao" aria-label="Paginação">
          <span class="ajuda">{{ intervalo }}</span>
          <label for="tamanho-pagina" class="ajuda">Linhas por página</label>
          <select
            id="tamanho-pagina"
            :value="filtro.tamanho"
            @change="aplicar({ tamanho: Number(($event.target as HTMLSelectElement).value) })"
          >
            <option v-for="t in TAMANHOS_PAGINA" :key="t" :value="t">{{ t }}</option>
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

.total {
  font-weight: 400;
  margin-left: 0.5rem;
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

td .ajuda {
  display: block;
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
