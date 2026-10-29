<script setup lang="ts">
import { keepPreviousData, useQuery } from '@tanstack/vue-query'
import { computed, ref } from 'vue'

import { useHttpClient } from '@/app/injection'
import { formatarDataHora } from '@/features/associados/associados-api'
import { describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'

import {
  chavesUsinas,
  formatarDataNegocio,
  historicoUsina,
  ROTULO_ACAO_USINAS,
  ROTULO_CAMPO_USINAS,
} from './usinas-api'

const props = defineProps<{ usinaId: number }>()
const http = useHttpClient()
const pagina = ref(0)

const historico = useQuery({
  queryKey: computed(() => [...chavesUsinas.historico(props.usinaId), pagina.value]),
  queryFn: () => historicoUsina(http, props.usinaId, pagina.value),
  placeholderData: keepPreviousData,
})

const DATAS = new Set(['inicio', 'fim'])

function valor(campo: string, texto?: string | null) {
  if (!texto) return campo === 'cep' || campo === 'endereco' ? 'não exibido' : 'vazio'
  return DATAS.has(campo) ? formatarDataNegocio(texto) : texto
}
</script>

<template>
  <h2>Histórico de alterações</h2>
  <p class="ajuda">Registros somente leitura, incluindo vínculos criados e encerrados.</p>
  <p v-if="historico.isPending.value" role="status">Carregando histórico…</p>
  <div v-else-if="historico.isError.value">
    <AlertaMensagem tipo="erro">{{ describeApiError(historico.error.value) }}</AlertaMensagem>
    <button type="button" class="secundario" @click="historico.refetch()">Tentar novamente</button>
  </div>
  <template v-else-if="historico.data.value">
    <p v-if="historico.data.value.totalItens === 0" class="ajuda">Nenhum registro.</p>
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
        <tbody>
          <tr v-for="r in historico.data.value.itens" :key="r.id">
            <td>{{ formatarDataHora(r.ocorridoEm) }}</td>
            <td>
              {{ ROTULO_ACAO_USINAS[r.acao] }}
              <span v-if="r.referencia" class="ajuda">{{ r.referencia }}</span>
              <span v-if="r.motivo" class="ajuda">Motivo: {{ r.motivo }}</span>
            </td>
            <td>
              <ul class="alteracoes">
                <li v-for="a in r.alteracoes" :key="a.campo">
                  <strong>{{ ROTULO_CAMPO_USINAS[a.campo] ?? a.campo }}:</strong>
                  <template
                    v-if="
                      a.antes ||
                      (a.campo !== 'cep' && a.campo !== 'endereco' && r.acao.endsWith('ALTERADA'))
                    "
                  >
                    {{ valor(a.campo, a.antes) }} →
                  </template>
                  {{ valor(a.campo, a.depois) }}
                </li>
              </ul>
            </td>
            <td>{{ r.autor.nome }}</td>
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
}
</style>
