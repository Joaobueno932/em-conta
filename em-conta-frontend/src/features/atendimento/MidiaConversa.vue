<script setup lang="ts">
import { useInfiniteQuery } from '@tanstack/vue-query'
import { computed, ref } from 'vue'

import { useHttpClient } from '@/app/injection'
import { describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'

import {
  chavesAtendimento,
  conteudoAnexo,
  formatarDataHora,
  formatarTamanho,
  midia,
  ROTULO_CATEGORIA,
  type CategoriaAnexo,
} from './atendimento-api'

/**
 * Área Mídia: anexos da conversa (ou só do ciclo, no histórico), do mais recente, 20 por vez. Só a lista
 * é carregada; nenhum arquivo é baixado ou reproduzido até a pessoa pedir.
 */
const props = defineProps<{ atendimento: number; somenteEste?: boolean }>()
const emit = defineEmits<{ abrirMensagem: [mensagemId: number, cursor: string] }>()

const http = useHttpClient()
const categoria = ref<CategoriaAnexo | ''>('')
const filtros: { valor: CategoriaAnexo | ''; rotulo: string }[] = [
  { valor: '', rotulo: 'Todos' },
  { valor: 'IMAGEM', rotulo: 'Imagens' },
  { valor: 'DOCUMENTO', rotulo: 'Documentos' },
  { valor: 'AUDIO', rotulo: 'Áudios' },
]

const lista = useInfiniteQuery({
  queryKey: computed(() =>
    chavesAtendimento.midia(props.atendimento, !!props.somenteEste, categoria.value),
  ),
  queryFn: ({ pageParam }) =>
    midia(http, props.atendimento, {
      somenteEste: props.somenteEste,
      categoria: categoria.value || undefined,
      antes: pageParam || undefined,
    }),
  initialPageParam: 0,
  getNextPageParam: (ultima) => ultima.anterior ?? undefined,
})

const itens = computed(() => lista.data.value?.pages.flatMap((p) => p.itens) ?? [])
</script>

<template>
  <section class="midia" aria-labelledby="titulo-midia">
    <h3 id="titulo-midia">Mídia</h3>
    <div class="filtros" role="group" aria-label="Filtrar mídia por tipo">
      <button
        v-for="f in filtros"
        :key="f.valor"
        type="button"
        class="chip"
        :aria-pressed="categoria === f.valor"
        @click="categoria = f.valor"
      >
        {{ f.rotulo }}
      </button>
    </div>
    <p v-if="lista.isPending.value" role="status">Carregando mídia…</p>
    <template v-else-if="lista.isError.value">
      <AlertaMensagem tipo="erro">{{ describeApiError(lista.error.value) }}</AlertaMensagem>
      <button type="button" class="secundario" @click="lista.refetch()">Tentar de novo</button>
    </template>
    <p v-else-if="itens.length === 0" class="ajuda">Nenhum anexo nesta conversa.</p>
    <ul v-else class="itens">
      <li v-for="item in itens" :key="item.id">
        <span class="tipo">{{ ROTULO_CATEGORIA[item.categoria as CategoriaAnexo] }}</span>
        <a
          v-if="item.situacao === 'DISPONIVEL'"
          :href="conteudoAnexo(atendimento, item.id, { somenteEste })"
          target="_blank"
          rel="noopener"
          class="nome"
          >{{ item.nome ?? `${ROTULO_CATEGORIA[item.categoria as CategoriaAnexo]} sem nome` }}</a
        >
        <span v-else class="nome">{{ item.nome ?? 'Sem nome' }} (indisponível)</span>
        <span class="ajuda">
          {{ item.direcao === 'ENTRADA' ? 'Recebido' : 'Enviado' }} ·
          {{ formatarDataHora(item.registradoEm) }}
          <template v-if="item.tamanho"> · {{ formatarTamanho(item.tamanho) }}</template>
        </span>
        <button
          type="button"
          class="link"
          @click="emit('abrirMensagem', item.mensagemId, item.cursorMensagem)"
        >
          Ver na conversa
        </button>
      </li>
    </ul>
    <p v-if="lista.isFetchingNextPage.value" role="status">Carregando mais…</p>
    <template v-else-if="lista.isFetchNextPageError.value">
      <AlertaMensagem tipo="erro">Não foi possível carregar mais itens.</AlertaMensagem>
      <button type="button" class="secundario" @click="lista.fetchNextPage()">
        Tentar de novo
      </button>
    </template>
    <button
      v-else-if="lista.hasNextPage.value"
      type="button"
      class="secundario"
      @click="lista.fetchNextPage()"
    >
      Carregar mais
    </button>
  </section>
</template>

<style scoped>
.midia {
  border-top: 1px solid var(--cor-borda);
  padding-top: 0.5rem;
}

h3 {
  margin: 0.5rem 0 0.25rem;
  font-size: 0.9rem;
}

.filtros {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem;
  margin-bottom: 0.5rem;
}

/* Mesmo chip das visões da fila. */
.chip {
  border-radius: 999px;
  border-color: var(--cor-borda);
  background: var(--cor-superficie);
  color: var(--cor-texto);
  padding: 0.2rem 0.6rem;
  font-size: 0.8rem;
}

.chip[aria-pressed='true'] {
  background: var(--cor-destaque-suave);
  border-color: var(--cor-destaque-borda);
  color: var(--cor-destaque-forte);
}

.itens {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.itens li {
  display: flex;
  flex-direction: column;
  gap: 0.1rem;
  min-width: 0;
}

.tipo {
  font-size: 0.75rem;
  color: var(--cor-texto-2);
}

.nome {
  overflow-wrap: anywhere;
}

/* Sem sublinhado contínuo: ele esconderia "_" de nomes de arquivo (volta no foco e ao passar). */
a.nome {
  text-decoration: none;
}

a.nome:hover,
a.nome:focus-visible {
  text-decoration: underline;
}

.itens .link {
  align-self: flex-start;
  font-size: 0.8rem;
}
</style>
