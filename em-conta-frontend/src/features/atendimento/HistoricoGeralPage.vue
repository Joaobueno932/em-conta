<script setup lang="ts">
import { keepPreviousData, useQuery } from '@tanstack/vue-query'
import { computed, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'
import IconeTraco from '@/shared/components/IconeTraco.vue'

import {
  chavesAtendimento,
  formatarData,
  historico,
  iniciais,
  nomeContato,
} from './atendimento-api'

/**
 * Histórico geral (HG-02): atendimentos encerrados, com busca e período filtrados no servidor.
 * Setor e etiquetas do protótipo dependem de entregas futuras e não aparecem.
 */
const http = useHttpClient()
const route = useRoute()

const TAMANHO = 20
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

function limpar() {
  termo.value = ''
  de.value = ''
  ate.value = ''
  filtrar()
}

const faixa = computed(() => {
  const dados = lista.data.value
  if (!dados || dados.totalItens === 0) return ''
  const inicio = pagina.value * TAMANHO + 1
  return `${inicio} – ${inicio + dados.itens.length - 1} de ${dados.totalItens}`
})

/** Até 5 números de página em volta da atual. */
const paginas = computed(() => {
  const total = lista.data.value?.totalPaginas ?? 0
  const primeira = Math.max(0, Math.min(pagina.value - 2, total - 5))
  return Array.from({ length: Math.min(5, total) }, (_, k) => primeira + k)
})
</script>

<template>
  <main class="pagina pagina--larga">
    <header class="cabecalho">
      <RouterLink
        :to="{ name: 'atendimento' }"
        class="botao-icone sem-borda"
        aria-label="Voltar ao atendimento"
        title="Voltar ao atendimento"
      >
        <IconeTraco nome="voltar" :tamanho="22" />
      </RouterLink>
      <div>
        <h1>Histórico geral</h1>
        <p class="subtitulo">Consulte o histórico dos atendimentos encerrados. Somente consulta.</p>
      </div>
    </header>

    <section class="cartao">
      <form class="filtros" role="search" @submit.prevent="filtrar">
        <div class="campo">
          <label for="hist-busca">Buscar contato</label>
          <input
            id="hist-busca"
            v-model="termo"
            type="search"
            maxlength="100"
            placeholder="Nome do contato, associado ou número"
          />
        </div>
        <div class="campo">
          <label for="hist-de">De</label>
          <input id="hist-de" v-model="de" type="date" />
        </div>
        <div class="campo">
          <label for="hist-ate">Até</label>
          <input id="hist-ate" v-model="ate" type="date" />
        </div>
        <div class="acoes botoes">
          <button type="submit">Filtrar</button>
          <button type="button" class="secundario" @click="limpar">Limpar</button>
        </div>
      </form>

      <p v-if="lista.isPending.value" role="status">Carregando histórico…</p>
      <template v-else-if="lista.isError.value">
        <AlertaMensagem tipo="erro">{{ describeApiError(lista.error.value) }}</AlertaMensagem>
        <button type="button" class="secundario" @click="lista.refetch()">Tentar de novo</button>
      </template>
      <template v-else-if="lista.data.value">
        <p v-if="lista.data.value.totalItens === 0" class="vazio" role="status">
          Nenhum atendimento encontrado.
        </p>
        <div v-else class="tabela-rolavel" :aria-busy="lista.isFetching.value">
          <table>
            <thead>
              <tr>
                <th scope="col">Contato</th>
                <th scope="col">Associado</th>
                <th scope="col">Atendente</th>
                <th scope="col">Encerrado por</th>
                <th scope="col">Data</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="a in lista.data.value.itens" :key="a.id">
                <td>
                  <span class="contato">
                    <span class="avatar" aria-hidden="true">{{ iniciais(nomeContato(a)) }}</span>
                    <span>
                      <RouterLink :to="{ name: 'atendimento-detalhe', params: { id: a.id } }">{{
                        nomeContato(a)
                      }}</RouterLink>
                      <span class="ajuda codigo">{{ a.codigo }}</span>
                    </span>
                  </span>
                </td>
                <td>{{ a.associado?.nome ?? '—' }}</td>
                <td>{{ a.responsavel?.nome ?? '—' }}</td>
                <td>{{ a.encerradoPor?.nome ?? '—' }}</td>
                <td>{{ a.encerradoEm ? formatarData(a.encerradoEm) : '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <footer v-if="lista.data.value.totalItens > 0" class="rodape">
          <p class="ajuda">{{ faixa }}</p>
          <nav
            v-if="lista.data.value.totalPaginas > 1"
            class="paginacao"
            aria-label="Paginação do histórico"
          >
            <button
              type="button"
              class="botao-icone"
              aria-label="Página anterior"
              :disabled="pagina === 0"
              @click="pagina--"
            >
              <IconeTraco nome="voltar" />
            </button>
            <button
              v-for="n in paginas"
              :key="n"
              type="button"
              class="numero"
              :class="{ secundario: n !== pagina }"
              :aria-current="n === pagina ? 'page' : undefined"
              :aria-label="`Página ${n + 1}`"
              @click="pagina = n"
            >
              {{ n + 1 }}
            </button>
            <button
              type="button"
              class="botao-icone"
              aria-label="Próxima página"
              :disabled="pagina + 1 >= lista.data.value.totalPaginas"
              @click="pagina++"
            >
              <IconeTraco nome="voltar" class="girada" />
            </button>
          </nav>
        </footer>
      </template>
    </section>
  </main>
</template>

<style scoped>
.cabecalho {
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
}

.cabecalho .botao-icone {
  margin-top: 0.15rem;
}

.filtros {
  display: grid;
  grid-template-columns: 2fr 1fr 1fr auto;
  gap: 0.75rem;
  align-items: end;
}

.filtros input {
  width: 100%;
  box-sizing: border-box;
}

.botoes {
  margin-bottom: 1rem;
}

.contato {
  display: inline-flex;
  align-items: center;
  gap: 0.6rem;
}

.avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 2.25rem;
  height: 2.25rem;
  border-radius: 999px;
  background: var(--cor-destaque-suave);
  color: var(--cor-destaque-forte);
  font-size: 0.8rem;
  font-weight: 600;
}

.codigo {
  display: block;
}

.vazio {
  text-align: center;
  color: var(--cor-texto-2);
  padding: 2rem 0;
}

.rodape {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.75rem;
  margin-top: 1rem;
}

.paginacao {
  display: flex;
  gap: 0.35rem;
}

button.numero {
  min-width: 2.25rem;
  padding: 0.35rem 0.5rem;
}

.girada {
  transform: rotate(180deg);
}

@media (max-width: 48rem) {
  .filtros {
    grid-template-columns: 1fr;
  }
}
</style>
