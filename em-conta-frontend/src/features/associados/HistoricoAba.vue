<script setup lang="ts">
import { keepPreviousData, useQuery } from '@tanstack/vue-query'
import { computed, ref } from 'vue'

import { useHttpClient } from '@/app/injection'
import { describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'

import {
  chaves,
  consultarHistorico,
  formatarDataHora,
  ROTULO_ACAO,
  ROTULO_CAMPO,
  ROTULO_CATEGORIA,
  type CategoriaHistorico,
  type RegistroHistorico,
} from './associados-api'

const props = defineProps<{ associadoId: number }>()
const http = useHttpClient()

const categoria = ref<CategoriaHistorico | ''>('')
const pagina = ref(0)

const historico = useQuery({
  queryKey: computed(() => [...chaves.historico(props.associadoId), categoria.value, pagina.value]),
  queryFn: () => consultarHistorico(http, props.associadoId, pagina.value, categoria.value),
  placeholderData: keepPreviousData,
})

const categorias: { valor: CategoriaHistorico | ''; rotulo: string }[] = [
  { valor: '', rotulo: 'Todos os registros' },
  ...(Object.keys(ROTULO_CATEGORIA) as CategoriaHistorico[]).map((valor) => ({
    valor,
    rotulo: ROTULO_CATEGORIA[valor],
  })),
]

function filtrar(valor: CategoriaHistorico | '') {
  categoria.value = valor
  pagina.value = 0
}

/** Inclusões mostram só o valor registrado; alterações mostram antes → depois. */
const INCLUSOES = new Set<RegistroHistorico['acao']>([
  'ASSOCIADO_CADASTRADO',
  'CONTATO_ADICIONADO',
  'UC_CADASTRADA',
])

function valor(texto: string | undefined, campo: string) {
  if (texto) return texto
  return campo === 'endereco' ? 'não exibido' : 'vazio'
}

function descricao(registro: RegistroHistorico) {
  return [ROTULO_CATEGORIA[registro.categoria], registro.referencia].filter(Boolean).join(' · ')
}
</script>

<template>
  <h2>Histórico de alterações</h2>
  <p class="ajuda">
    Registros somente leitura. Dados pessoais aparecem mascarados. Alterações de contratos e
    documentos entrarão aqui quando esses módulos existirem.
  </p>

  <div class="acoes chips" role="group" aria-label="Filtrar por categoria">
    <button
      v-for="item in categorias"
      :key="item.valor"
      type="button"
      class="chip"
      :aria-pressed="categoria === item.valor"
      @click="filtrar(item.valor)"
    >
      {{ item.rotulo }}
    </button>
  </div>

  <p v-if="historico.isPending.value" role="status">Carregando histórico…</p>
  <div v-else-if="historico.isError.value">
    <AlertaMensagem tipo="erro">{{ describeApiError(historico.error.value) }}</AlertaMensagem>
    <button type="button" class="secundario" @click="historico.refetch()">Tentar novamente</button>
  </div>
  <template v-else-if="historico.data.value">
    <p v-if="historico.data.value.totalItens === 0" class="ajuda" role="status">
      Nenhum registro {{ categoria ? 'nesta categoria' : 'no histórico' }}.
    </p>
    <div v-else class="tabela-rolavel">
      <table>
        <thead>
          <tr>
            <th scope="col">Data e hora</th>
            <th scope="col">Alteração</th>
            <th scope="col">Campos</th>
            <th scope="col">Realizado por</th>
          </tr>
        </thead>
        <tbody :aria-busy="historico.isFetching.value">
          <tr v-for="registro in historico.data.value.itens" :key="registro.id">
            <td>{{ formatarDataHora(registro.ocorridoEm) }}</td>
            <td>
              {{ ROTULO_ACAO[registro.acao] }}
              <span class="ajuda">{{ descricao(registro) }}</span>
            </td>
            <td>
              <ul class="alteracoes">
                <li v-for="alteracao in registro.alteracoes" :key="alteracao.campo">
                  <strong>{{ ROTULO_CAMPO[alteracao.campo] ?? alteracao.campo }}:</strong>
                  <template v-if="INCLUSOES.has(registro.acao)">
                    {{ valor(alteracao.depois, alteracao.campo) }}
                  </template>
                  <template v-else>
                    {{ valor(alteracao.antes, alteracao.campo) }} →
                    {{ valor(alteracao.depois, alteracao.campo) }}
                  </template>
                </li>
              </ul>
            </td>
            <td>{{ registro.autor.nome }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <nav
      v-if="historico.data.value.totalPaginas > 1"
      class="acoes acoes--fim"
      aria-label="Paginação do histórico"
    >
      <button type="button" class="secundario" :disabled="pagina === 0" @click="pagina--">
        Anterior
      </button>
      <span>Página {{ pagina + 1 }} de {{ historico.data.value.totalPaginas }}</span>
      <button
        type="button"
        class="secundario"
        :disabled="pagina + 1 >= historico.data.value.totalPaginas"
        @click="pagina++"
      >
        Próxima
      </button>
    </nav>
  </template>
</template>

<style scoped>
.chips {
  margin: 1rem 0;
}

.chip {
  border-radius: 999px;
  border-color: var(--cor-borda);
  background: var(--cor-superficie);
  color: var(--cor-texto);
  padding: 0.25rem 0.9rem;
  font-size: 0.85rem;
}

.chip[aria-pressed='true'] {
  background: var(--cor-destaque-forte);
  border-color: var(--cor-destaque-forte);
  color: #fff;
}

td .ajuda {
  display: block;
}

.alteracoes {
  list-style: none;
  margin: 0;
  padding: 0;
}

.alteracoes strong {
  margin-right: 0.25rem;
  font-weight: 600;
}
</style>
