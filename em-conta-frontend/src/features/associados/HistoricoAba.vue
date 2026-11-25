<script setup lang="ts">
import { keepPreviousData, useQuery } from '@tanstack/vue-query'
import { computed, ref } from 'vue'

import { useHttpClient } from '@/app/injection'
import { describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'
import PaginacaoLista from '@/shared/components/PaginacaoLista.vue'
import IconeTraco from '@/shared/components/IconeTraco.vue'

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
  <div class="topo-historico">
    <h2>Linha do tempo</h2>
    <span class="ajuda">Registros somente leitura · ordem decrescente</span>
  </div>
  <p class="ajuda">
    Dados pessoais aparecem mascarados. Alterações de contratos e documentos entrarão aqui quando
    esses módulos existirem.
  </p>

  <div class="chips" role="group" aria-label="Filtrar por categoria">
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
    <button type="button" class="botao-contorno" @click="historico.refetch()">
      Tentar novamente
    </button>
  </div>
  <template v-else-if="historico.data.value">
    <p v-if="historico.data.value.totalItens === 0" class="ajuda" role="status">
      Nenhum registro {{ categoria ? 'nesta categoria' : 'no histórico' }}.
    </p>
    <ol v-else class="eventos" :aria-busy="historico.isFetching.value">
      <li v-for="registro in historico.data.value.itens" :key="registro.id" class="evento">
        <span class="icone-evento" aria-hidden="true">
          <IconeTraco
            :nome="INCLUSOES.has(registro.acao) ? 'mais-circulo' : 'lapis'"
            :tamanho="14"
          />
        </span>
        <div class="corpo-evento">
          <div class="linha-evento">
            <p class="titulo-evento">
              <span class="autor">{{ registro.autor.nome }}</span>
              <span aria-hidden="true"> · </span>
              <strong class="acao">{{ ROTULO_ACAO[registro.acao] }}</strong>
              <span v-if="descricao(registro)" class="descricao">{{ descricao(registro) }}</span>
            </p>
            <time :datetime="registro.ocorridoEm">{{ formatarDataHora(registro.ocorridoEm) }}</time>
          </div>
          <ul v-if="registro.alteracoes.length" class="alteracoes">
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
        </div>
      </li>
    </ol>
    <PaginacaoLista
      v-if="historico.data.value.totalPaginas > 1"
      :pagina="pagina"
      :total-paginas="historico.data.value.totalPaginas"
      :total-itens="historico.data.value.totalItens"
      :itens-na-pagina="historico.data.value.itens.length"
      :tamanho="historico.data.value.tamanho"
      rotulo="registros"
      @ir="pagina = $event"
    />
  </template>
</template>

<style scoped>
.topo-historico {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 8px;
}

.topo-historico h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}

/* Chips da prancha: pílulas de 26 px, a selecionada em verde cheio. */
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 12px 0 8px;
}

.chip {
  height: 28px;
  padding: 0 12px;
  border: 1px solid var(--cor-borda);
  border-radius: 999px;
  background: var(--cor-superficie);
  color: var(--cor-texto);
  font-size: 13px;
}

.chip[aria-pressed='true'] {
  background: var(--cor-destaque-forte);
  border-color: var(--cor-destaque-forte);
  color: #fff;
}

.eventos {
  margin: 0;
  padding: 0;
  list-style: none;
}

.evento {
  display: flex;
  gap: 14px;
  padding: 14px 0;
}

.icone-evento {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 999px;
  background: var(--cor-superficie-2);
  color: var(--cor-texto-2);
}

.corpo-evento {
  flex: 1;
  min-width: 0;
}

.linha-evento {
  display: flex;
  justify-content: space-between;
  gap: 16px;
}

.titulo-evento {
  margin: 4px 0 0;
  font-size: 14px;
}

.acao {
  font-weight: 500;
}

.descricao {
  display: block;
  color: var(--cor-texto-2);
  font-size: 13px;
}

time {
  flex-shrink: 0;
  margin-top: 4px;
  color: var(--cor-texto-2);
  font-size: 12px;
}

.alteracoes {
  margin: 8px 0 0;
  padding: 10px 14px;
  border: 1px solid var(--cor-borda);
  border-radius: 8px;
  list-style: none;
  font-size: 13px;
}

.alteracoes strong {
  margin-right: 0.25rem;
  font-weight: 600;
}
</style>
