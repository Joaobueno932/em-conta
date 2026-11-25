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

/**
 * Listagem (prancha "⚡ Usinas — Listagem"). Filtro na URL: ao abrir uma usina e voltar, a lista
 * reaparece igual. Responsável e potência do protótipo não existem no cadastro (a coluna Fazenda,
 * que existe, entra no lugar); exportar também não existe.
 */
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
const temFiltro = computed(() => !!(filtro.value.busca || filtro.value.uf))
const filtrosAtivos = computed(
  () => (filtro.value.uf ? 1 : 0) + (filtro.value.ordenacao !== 'NOME' ? 1 : 0),
)
const ROTULO_ORDENACAO: Record<OrdenacaoUsinas, string> = {
  NOME: 'Nome (A–Z)',
  CADASTRO_RECENTE: 'Cadastro mais recente',
  UCS_VINCULADAS: 'Mais UCs vinculadas',
}
</script>

<template>
  <main class="pagina-modulo">
    <CabecalhoPagina
      titulo="Usinas"
      subtitulo="Cadastro e consulta das usinas e de seus vínculos cadastrais com unidades consumidoras."
    >
      <template #acoes>
        <RouterLink class="botao-contorno" :to="{ name: 'fazendas' }">Fazendas</RouterLink>
        <RouterLink v-if="podeCadastrar" class="botao-primario" :to="{ name: 'usina-nova' }">
          <IconeTraco nome="mais-circulo" :tamanho="18" />
          Nova usina
        </RouterLink>
      </template>
    </CabecalhoPagina>

    <AlertaMensagem v-if="route.query.criada === '1'" tipo="sucesso"
      >Usina cadastrada.</AlertaMensagem
    >

    <section class="painel lista" aria-labelledby="titulo-usinas">
      <h2 id="titulo-usinas" class="visualmente-oculto">Usinas cadastradas</h2>
      <div class="barra-lista">
        <form class="busca-lista" role="search" @submit.prevent="aplicar({ busca: busca.trim() })">
          <label for="busca-usinas" class="visualmente-oculto">Buscar usinas</label>
          <IconeTraco nome="busca" :tamanho="18" />
          <input
            id="busca-usinas"
            v-model="busca"
            type="search"
            maxlength="100"
            placeholder="Buscar por nome, fazenda ou identificador…"
          />
        </form>
        <BotaoFiltros :ativos="filtrosAtivos">
          <div class="campo">
            <label for="filtro-uf">UF</label>
            <select
              id="filtro-uf"
              :value="filtro.uf"
              @change="aplicar({ uf: ($event.target as HTMLSelectElement).value })"
            >
              <option value="">Todas as UFs</option>
              <option v-for="uf in UFS" :key="uf" :value="uf">{{ uf }}</option>
            </select>
          </div>
          <div class="campo">
            <label for="ordenacao-usinas">Ordenar por</label>
            <select
              id="ordenacao-usinas"
              :value="filtro.ordenacao"
              @change="
                aplicar({
                  ordenacao: ($event.target as HTMLSelectElement).value as OrdenacaoUsinas,
                })
              "
            >
              <option v-for="(rotulo, valor) in ROTULO_ORDENACAO" :key="valor" :value="valor">
                {{ rotulo }}
              </option>
            </select>
          </div>
        </BotaoFiltros>
      </div>

      <div v-if="filtrosAtivos" class="filtros-aplicados">
        Filtros aplicados:
        <span v-if="filtro.uf" class="chip-filtro">
          UF: {{ filtro.uf }}
          <button type="button" aria-label="Remover filtro de UF" @click="aplicar({ uf: '' })">
            <IconeTraco nome="fechar" :tamanho="12" />
          </button>
        </span>
        <span v-if="filtro.ordenacao !== 'NOME'" class="chip-filtro">
          {{ ROTULO_ORDENACAO[filtro.ordenacao] }}
          <button
            type="button"
            aria-label="Voltar à ordenação por nome"
            @click="aplicar({ ordenacao: 'NOME' })"
          >
            <IconeTraco nome="fechar" :tamanho="12" />
          </button>
        </span>
        <button
          type="button"
          class="link"
          @click="aplicar({ busca: '', uf: '', ordenacao: 'NOME' })"
        >
          Limpar filtros
        </button>
      </div>

      <div v-if="lista.isPending.value" role="status">
        <span class="visualmente-oculto">Carregando usinas…</span>
        <table class="tabela-dados" aria-hidden="true">
          <tbody>
            <tr v-for="n in 7" :key="n">
              <td v-for="m in 6" :key="m"><span class="esqueleto" /></td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else-if="lista.isError.value" class="estado-vazio estado-vazio--erro">
        <span class="estado-vazio__icone"><IconeTraco nome="alerta" :tamanho="24" /></span>
        <strong>Não foi possível carregar as usinas</strong>
        <AlertaMensagem tipo="erro">{{ describeApiError(lista.error.value) }}</AlertaMensagem>
        <button type="button" class="botao-contorno" @click="lista.refetch()">
          Tentar novamente
        </button>
      </div>
      <template v-else-if="lista.data.value">
        <div v-if="lista.data.value.totalItens === 0" class="estado-vazio" role="status">
          <span class="estado-vazio__icone"><IconeTraco nome="busca" :tamanho="24" /></span>
          <template v-if="temFiltro">
            <strong>Nenhuma usina encontrada</strong>
            <p>Nenhuma usina corresponde à busca ou aos filtros informados.</p>
            <button type="button" class="botao-contorno" @click="aplicar({ busca: '', uf: '' })">
              Limpar busca
            </button>
          </template>
          <template v-else>
            <strong>Nenhuma usina cadastrada ainda</strong>
            <p>As usinas aparecem aqui assim que forem cadastradas.</p>
          </template>
        </div>
        <div v-else class="tabela-rolavel">
          <table class="tabela-dados">
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
                  <span class="usina">
                    <span class="icone-usina" aria-hidden="true">
                      <IconeTraco nome="usina" :tamanho="18" />
                    </span>
                    <RouterLink :to="{ name: 'usina', params: { id: usina.id } }">
                      {{ usina.nome }}
                    </RouterLink>
                  </span>
                </td>
                <td class="texto-secundario">{{ usina.codigo }}</td>
                <td>{{ usina.fazendaNome || '—' }}</td>
                <td>{{ localizacao(usina.cidade, usina.uf) }}</td>
                <td class="numero-forte">{{ usina.ucsVinculadas }}</td>
                <td>{{ usina.associadosDistintos }}</td>
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
          :tamanhos="TAMANHOS"
          rotulo="usinas"
          @ir="aplicar({ pagina: $event })"
          @tamanho="aplicar({ tamanho: $event })"
        />
      </template>
    </section>
    <p class="nota-rodape">
      <IconeTraco nome="informacao" :tamanho="14" />
      {{ AVISO_VINCULO_CADASTRAL }}
    </p>
  </main>
</template>

<style scoped>
.lista {
  padding: 16px 18px;
}

.campo {
  margin: 0;
}

.usina {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}

.icone-usina {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: var(--cor-destaque-suave);
  color: var(--cor-destaque-forte);
}

.texto-secundario {
  color: var(--cor-texto-2);
}

.numero-forte {
  font-weight: 600;
}

.estado-vazio :deep(.alerta) {
  margin: 0 0 8px;
}
</style>
