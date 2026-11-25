<script setup lang="ts">
import { keepPreviousData, useQuery } from '@tanstack/vue-query'
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter, type LocationQuery } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { temPermissao, useSessao } from '@/features/auth/auth-api'
import { describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'
import BotaoFiltros from '@/shared/components/BotaoFiltros.vue'
import CabecalhoPagina from '@/shared/components/CabecalhoPagina.vue'
import IconeTraco from '@/shared/components/IconeTraco.vue'
import PaginacaoLista from '@/shared/components/PaginacaoLista.vue'

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
 * Listagem (prancha "👥 Associados — Visão geral e listagem", quadros 2 a 12). O filtro vive na URL
 * (`?busca=&tipo=&pagina=`): ao abrir um associado e voltar, a listagem reaparece na mesma página e
 * com os mesmos filtros. Usina, situação do cliente, pendências e responsável do protótipo não
 * existem no cadastro e não ganham colunas; importar e exportar também não existem.
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
const filtrosAtivos = computed(() => (filtro.value.tipo ? 1 : 0))
const temFiltro = computed(() => !!(filtro.value.busca || filtro.value.tipo))

// "Associado" no cabeçalho alterna entre nome (A–Z) e cadastro mais recente.
function alternarOrdenacao() {
  aplicar({ ordenacao: filtro.value.ordenacao === 'NOME' ? 'CADASTRO_RECENTE' : 'NOME' })
}
</script>

<template>
  <main class="pagina-modulo">
    <CabecalhoPagina
      titulo="Todos os associados"
      subtitulo="Consulte e gerencie os cadastros dos clientes da Em Conta."
    >
      <template #acoes>
        <RouterLink v-if="podeCadastrar" class="botao-primario" :to="{ name: 'associado-novo' }">
          <IconeTraco nome="mais-circulo" :tamanho="18" />
          Novo associado
        </RouterLink>
      </template>
    </CabecalhoPagina>

    <AlertaMensagem v-if="criado" tipo="sucesso">Associado cadastrado.</AlertaMensagem>

    <section class="painel lista" aria-labelledby="titulo-lista">
      <h2 id="titulo-lista" class="titulo-lista">
        Associados cadastrados
        <span v-if="lista.data.value" class="total">
          {{ lista.data.value.totalItens }} {{ temFiltro ? 'resultados' : 'no total' }}
        </span>
      </h2>

      <div class="barra-lista">
        <form class="busca-lista" role="search" @submit.prevent="aplicar({ busca: busca.trim() })">
          <label for="busca-associados" class="visualmente-oculto">Buscar associados</label>
          <IconeTraco nome="busca" :tamanho="18" />
          <input
            id="busca-associados"
            v-model="busca"
            type="search"
            maxlength="100"
            placeholder="Buscar por nome, razão social ou CPF/CNPJ…"
          />
        </form>
        <BotaoFiltros :ativos="filtrosAtivos">
          <div class="campo">
            <label for="filtro-tipo">Tipo de pessoa</label>
            <select
              id="filtro-tipo"
              :value="filtro.tipo"
              @change="
                aplicar({ tipo: ($event.target as HTMLSelectElement).value as TipoPessoa | '' })
              "
            >
              <option value="">PF e PJ</option>
              <option value="PF">Pessoa física</option>
              <option value="PJ">Pessoa jurídica</option>
            </select>
          </div>
          <div class="campo">
            <label for="ordenacao">Ordenar por</label>
            <select
              id="ordenacao"
              :value="filtro.ordenacao"
              @change="
                aplicar({ ordenacao: ($event.target as HTMLSelectElement).value as Ordenacao })
              "
            >
              <option value="NOME">Nome (A–Z)</option>
              <option value="CADASTRO_RECENTE">Cadastro mais recente</option>
            </select>
          </div>
        </BotaoFiltros>
      </div>

      <div v-if="filtro.tipo" class="filtros-aplicados">
        Filtros aplicados:
        <span class="chip-filtro">
          Tipo: {{ filtro.tipo }}
          <button type="button" aria-label="Remover filtro de tipo" @click="aplicar({ tipo: '' })">
            <IconeTraco nome="fechar" :tamanho="12" />
          </button>
        </span>
        <button type="button" class="link" @click="aplicar({ busca: '', tipo: '' })">
          Limpar filtros
        </button>
      </div>

      <div v-if="lista.isPending.value" role="status">
        <span class="visualmente-oculto">Carregando associados…</span>
        <table class="tabela-dados" aria-hidden="true">
          <tbody>
            <tr v-for="n in 8" :key="n">
              <td v-for="m in 5" :key="m"><span class="esqueleto" /></td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else-if="lista.isError.value" class="estado-vazio estado-vazio--erro">
        <span class="estado-vazio__icone"><IconeTraco nome="alerta" :tamanho="24" /></span>
        <strong>Não foi possível carregar os associados</strong>
        <AlertaMensagem tipo="erro">{{ describeApiError(lista.error.value) }}</AlertaMensagem>
        <button type="button" class="botao-contorno" @click="lista.refetch()">
          Tentar novamente
        </button>
      </div>
      <template v-else-if="lista.data.value">
        <div v-if="lista.data.value.totalItens === 0" class="estado-vazio" role="status">
          <span class="estado-vazio__icone"><IconeTraco nome="busca" :tamanho="24" /></span>
          <template v-if="temFiltro">
            <strong>Nenhum associado encontrado</strong>
            <p>Nenhum associado corresponde à busca ou aos filtros informados.</p>
            <button type="button" class="botao-contorno" @click="aplicar({ busca: '', tipo: '' })">
              Limpar filtros
            </button>
          </template>
          <template v-else>
            <strong>Nenhum associado cadastrado ainda</strong>
            <p>Os cadastros aparecem aqui assim que forem criados.</p>
          </template>
        </div>
        <div v-else class="tabela-rolavel">
          <table class="tabela-dados">
            <thead>
              <tr>
                <th scope="col" :aria-sort="filtro.ordenacao === 'NOME' ? 'ascending' : undefined">
                  <button type="button" class="ordenar" @click="alternarOrdenacao">
                    Associado
                    <IconeTraco
                      :nome="filtro.ordenacao === 'NOME' ? 'seta-abaixo' : 'seta-acima'"
                      :tamanho="12"
                    />
                    <span class="visualmente-oculto">
                      {{
                        filtro.ordenacao === 'NOME'
                          ? '(ordenar por cadastro mais recente)'
                          : '(ordenar por nome)'
                      }}
                    </span>
                  </button>
                </th>
                <th scope="col">Tipo</th>
                <th scope="col">CPF/CNPJ</th>
                <th scope="col">UCs</th>
                <th
                  scope="col"
                  :aria-sort="filtro.ordenacao === 'CADASTRO_RECENTE' ? 'descending' : undefined"
                >
                  Cadastrado em
                </th>
              </tr>
            </thead>
            <tbody :aria-busy="lista.isFetching.value">
              <tr v-for="associado in lista.data.value.itens" :key="associado.id">
                <td>
                  <RouterLink :to="{ name: 'associado', params: { id: associado.id } }">
                    {{ associado.nome }}
                  </RouterLink>
                  <span v-if="associado.nomeFantasia" class="fantasia">
                    {{ associado.nomeFantasia }}
                  </span>
                </td>
                <td class="texto-secundario" :title="ROTULO_TIPO[associado.tipo]">
                  {{ associado.tipo }}
                </td>
                <td class="texto-secundario">{{ associado.documentoMascarado }}</td>
                <td>{{ associado.quantidadeUcs }}</td>
                <td class="texto-secundario">{{ formatarData(associado.criadoEm) }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <PaginacaoLista
          v-if="lista.data.value.totalItens > 0"
          :pagina="filtro.pagina"
          :total-paginas="lista.data.value.totalPaginas"
          :total-itens="lista.data.value.totalItens"
          :itens-na-pagina="lista.data.value.itens.length"
          :tamanho="filtro.tamanho"
          :tamanhos="TAMANHOS_PAGINA"
          rotulo="associados"
          @ir="aplicar({ pagina: $event })"
          @tamanho="aplicar({ tamanho: $event })"
        />
      </template>
    </section>
  </main>
</template>

<style scoped>
.lista {
  padding: 22px 20px 20px;
}

.titulo-lista {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin: 0 0 12px;
  font-size: 18px;
  font-weight: 600;
}

.total {
  color: var(--cor-texto-2);
  font-size: 12px;
  font-weight: 400;
}

.campo {
  margin: 0;
}

.tabela-dados tbody td {
  height: 37px;
}

.tabela-dados td a {
  font-weight: 500;
}

.fantasia {
  display: block;
  color: var(--cor-texto-2);
  font-size: 12px;
}

.texto-secundario {
  color: var(--cor-texto-2);
}

button.ordenar {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 0;
  border: none;
  background: none;
  color: inherit;
  font: inherit;
}

.estado-vazio :deep(.alerta) {
  margin: 0 0 8px;
}
</style>
