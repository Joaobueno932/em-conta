<script setup lang="ts">
import { keepPreviousData, useQuery } from '@tanstack/vue-query'
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { temPermissao, useSessao } from '@/features/auth/auth-api'
import { describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'

import EncerrarVinculoPainel from './EncerrarVinculoPainel.vue'
import {
  AVISO_VINCULO_CADASTRAL,
  chavesUsinas,
  formatarDataNegocio,
  listarVinculos,
  type SituacaoVinculo,
} from './usinas-api'
import VincularUcPainel from './VincularUcPainel.vue'

const props = defineProps<{ usinaId: number }>()

const http = useHttpClient()
const { data: sessao } = useSessao()

const situacao = ref<SituacaoVinculo>('VIGENTES')
const pagina = ref(0)
const vinculando = ref(false)
const encerrando = ref<number | null>(null)
const mensagem = ref('')

const vinculos = useQuery({
  queryKey: computed(() => [...chavesUsinas.vinculos(props.usinaId), situacao.value, pagina.value]),
  queryFn: () => listarVinculos(http, props.usinaId, situacao.value, pagina.value),
  placeholderData: keepPreviousData,
})

const podeCriar = computed(() => temPermissao(sessao.value, 'vinculos-usina:criar'))
const podeEncerrar = computed(() => temPermissao(sessao.value, 'vinculos-usina:encerrar'))
const podeVerAssociado = computed(() => temPermissao(sessao.value, 'associados:consultar'))

function filtrar(valor: SituacaoVinculo) {
  situacao.value = valor
  pagina.value = 0
}

function aoVincular() {
  vinculando.value = false
  mensagem.value = 'UC vinculada à usina.'
  filtrar('VIGENTES')
}

function aoEncerrar() {
  encerrando.value = null
  mensagem.value = 'Vínculo encerrado. O registro continua no histórico.'
}
</script>

<template>
  <div class="titulo">
    <h2>Unidades consumidoras</h2>
    <button
      v-if="podeCriar && !vinculando"
      type="button"
      @click="
        () => {
          mensagem = ''
          vinculando = true
        }
      "
    >
      Vincular unidade consumidora
    </button>
  </div>
  <p class="ajuda">{{ AVISO_VINCULO_CADASTRAL }}</p>
  <AlertaMensagem v-if="mensagem" tipo="sucesso">{{ mensagem }}</AlertaMensagem>

  <VincularUcPainel
    v-if="vinculando"
    :usina-id="usinaId"
    @concluido="aoVincular"
    @cancelado="vinculando = false"
  />

  <div class="acoes chips" role="group" aria-label="Situação do vínculo">
    <button
      v-for="item in [
        { valor: 'VIGENTES', rotulo: 'Vigentes' },
        { valor: 'ENCERRADOS', rotulo: 'Encerrados' },
        { valor: 'TODOS', rotulo: 'Todos' },
      ] as const"
      :key="item.valor"
      type="button"
      class="chip"
      :aria-pressed="situacao === item.valor"
      @click="filtrar(item.valor)"
    >
      {{ item.rotulo }}
    </button>
  </div>

  <p v-if="vinculos.isPending.value" role="status">Carregando vínculos…</p>
  <div v-else-if="vinculos.isError.value">
    <AlertaMensagem tipo="erro">{{ describeApiError(vinculos.error.value) }}</AlertaMensagem>
    <button type="button" class="secundario" @click="vinculos.refetch()">Tentar novamente</button>
  </div>
  <template v-else-if="vinculos.data.value">
    <p v-if="vinculos.data.value.totalItens === 0" class="vazio" role="status">
      {{
        situacao === 'ENCERRADOS'
          ? 'Nenhum vínculo encerrado.'
          : 'Nenhuma unidade consumidora vinculada a esta usina.'
      }}
    </p>
    <div v-else class="tabela-rolavel">
      <table>
        <thead>
          <tr>
            <th scope="col">Nº UC</th>
            <th scope="col">Titular</th>
            <th scope="col">Concessionária</th>
            <th scope="col">Vigência</th>
            <th scope="col">Registrado por</th>
            <th scope="col"><span class="visualmente-oculto">Ações</span></th>
          </tr>
        </thead>
        <tbody :aria-busy="vinculos.isFetching.value">
          <template v-for="v in vinculos.data.value.itens" :key="v.id">
            <tr>
              <td>
                <strong>{{ v.uc.numero }}</strong>
                <span v-if="v.uc.denominacao" class="ajuda">{{ v.uc.denominacao }}</span>
              </td>
              <td>
                <RouterLink
                  v-if="podeVerAssociado"
                  :to="{
                    name: 'associado',
                    params: { id: v.uc.associadoId },
                    query: { aba: 'ucs' },
                  }"
                >
                  {{ v.uc.associadoNome }}
                </RouterLink>
                <template v-else>{{ v.uc.associadoNome }}</template>
                <span class="ajuda"
                  >{{ v.uc.associadoTipo }} {{ v.uc.associadoDocumentoMascarado }}</span
                >
              </td>
              <td>{{ v.uc.concessionariaNome }}</td>
              <td>
                <span class="etiqueta" :class="{ 'etiqueta--vigente': v.vigente }">
                  {{ v.vigente ? 'Vigente' : 'Encerrado' }}
                </span>
                <span class="ajuda">
                  desde {{ formatarDataNegocio(v.inicio) }}
                  <template v-if="v.fim"> até {{ formatarDataNegocio(v.fim) }}</template>
                </span>
                <span v-if="v.motivoEncerramento" class="ajuda"
                  >Motivo: {{ v.motivoEncerramento }}</span
                >
              </td>
              <td>
                {{ v.criadoPor.nome }}
                <span v-if="v.encerradoPor" class="ajuda"
                  >encerrado por {{ v.encerradoPor.nome }}</span
                >
              </td>
              <td>
                <button
                  v-if="v.vigente && podeEncerrar && encerrando !== v.id"
                  type="button"
                  class="link"
                  @click="
                    () => {
                      mensagem = ''
                      encerrando = v.id
                    }
                  "
                >
                  Encerrar<span class="visualmente-oculto"> vínculo da UC {{ v.uc.numero }}</span>
                </button>
              </td>
            </tr>
            <tr v-if="encerrando === v.id">
              <td colspan="6">
                <EncerrarVinculoPainel
                  :vinculo="v"
                  @concluido="aoEncerrar"
                  @cancelado="encerrando = null"
                />
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>
    <nav
      v-if="vinculos.data.value.totalPaginas > 1"
      class="acoes acoes--fim"
      aria-label="Paginação dos vínculos"
    >
      <button type="button" class="secundario" :disabled="pagina === 0" @click="pagina--">
        Anterior
      </button>
      <span>Página {{ pagina + 1 }} de {{ vinculos.data.value.totalPaginas }}</span>
      <button
        type="button"
        class="secundario"
        :disabled="pagina + 1 >= vinculos.data.value.totalPaginas"
        @click="pagina++"
      >
        Próxima
      </button>
    </nav>
  </template>
</template>

<style scoped>
.titulo {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 1rem;
}

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

.etiqueta--vigente {
  background: var(--cor-destaque-suave);
  color: var(--cor-destaque-forte);
}

.vazio {
  padding: 1.5rem 0;
  text-align: center;
  color: var(--cor-texto-2);
}
</style>
