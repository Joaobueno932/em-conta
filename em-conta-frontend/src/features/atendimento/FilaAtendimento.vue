<script setup lang="ts">
import { keepPreviousData, useQuery } from '@tanstack/vue-query'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { temPermissao, useSessao } from '@/features/auth/auth-api'
import { describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'
import IconeTraco from '@/shared/components/IconeTraco.vue'

import {
  chavesAtendimento,
  formatarData,
  iniciais,
  INTERVALO_MS,
  listarFila,
  nomeContato,
  type Visao,
} from './atendimento-api'

/** Fila de conversas (AT-01/AT-03): visões, busca no servidor e paginação. */
const props = defineProps<{ selecionado: number | null }>()
const emit = defineEmits<{ selecionar: [id: number] }>()

// "Ver opções" (AT-04/AT-06): menu que abre para cima no rodapé da fila. Nesta versão só tem o
// Histórico geral e as Mensagens rápidas (com permissão); as demais opções do protótipo dependem de entregas futuras.
const opcoes = ref(false)
const { data: usuario } = useSessao()
const podeRespostas = computed(() => temPermissao(usuario.value, 'respostas-rapidas:consultar'))
const rodape = ref<HTMLElement | null>(null)
const botaoOpcoes = ref<HTMLButtonElement | null>(null)
function fecharOpcoes(devolverFoco = false) {
  if (!opcoes.value) return
  opcoes.value = false
  if (devolverFoco) botaoOpcoes.value?.focus()
}
function cliqueFora(evento: MouseEvent) {
  if (opcoes.value && rodape.value && !rodape.value.contains(evento.target as Node)) fecharOpcoes()
}
onMounted(() => document.addEventListener('click', cliqueFora))
onBeforeUnmount(() => document.removeEventListener('click', cliqueFora))

const http = useHttpClient()
const visao = ref<Visao>('AGUARDANDO')
const termo = ref('')
const busca = ref('')
const pagina = ref(0)

const fila = useQuery({
  queryKey: computed(() => chavesAtendimento.fila(visao.value, busca.value, pagina.value)),
  queryFn: () => listarFila(http, visao.value, busca.value, pagina.value),
  placeholderData: keepPreviousData,
  refetchInterval: INTERVALO_MS,
})

const visoes = computed(() => {
  const c = fila.data.value?.contagens
  return [
    { valor: 'AGUARDANDO' as const, rotulo: 'Aguardando', total: c?.aguardando },
    { valor: 'EM_ANDAMENTO' as const, rotulo: 'Em andamento', total: c?.emAndamento },
    { valor: 'MEUS' as const, rotulo: 'Meus atendimentos', total: c?.meus },
    { valor: 'ENCERRADOS' as const, rotulo: 'Encerrados', total: undefined },
  ]
})

function trocar(nova: Visao) {
  visao.value = nova
  pagina.value = 0
}
</script>

<template>
  <section class="fila cartao" aria-labelledby="titulo-fila">
    <h2 id="titulo-fila" class="visualmente-oculto">Fila de atendimentos</h2>
    <form
      class="busca"
      role="search"
      @submit.prevent="
        () => {
          busca = termo.trim()
          pagina = 0
        }
      "
    >
      <label for="busca-fila" class="visualmente-oculto">Buscar contato</label>
      <input
        id="busca-fila"
        v-model="termo"
        type="search"
        maxlength="100"
        placeholder="Buscar contato por nome ou telefone"
      />
    </form>
    <div class="acoes visoes" role="group" aria-label="Visões da fila">
      <button
        v-for="v in visoes"
        :key="v.valor"
        type="button"
        class="chip"
        :aria-pressed="visao === v.valor"
        @click="trocar(v.valor)"
      >
        {{ v.rotulo }}<span v-if="v.total !== undefined" class="contagem">{{ v.total }}</span>
      </button>
    </div>

    <p v-if="fila.isPending.value" role="status">Carregando atendimentos…</p>
    <div v-else-if="fila.isError.value">
      <AlertaMensagem tipo="erro">{{ describeApiError(fila.error.value) }}</AlertaMensagem>
      <button type="button" class="secundario" @click="fila.refetch()">Tentar novamente</button>
    </div>
    <template v-else-if="fila.data.value">
      <p v-if="fila.data.value.totalItens === 0" class="vazio" role="status">
        {{ busca ? 'Nenhum atendimento encontrado.' : 'Nenhum atendimento nesta visão.' }}
      </p>
      <ul v-else class="itens" :aria-busy="fila.isFetching.value">
        <li v-for="a in fila.data.value.itens" :key="a.id">
          <button
            type="button"
            class="item"
            :aria-current="props.selecionado === a.id ? 'true' : undefined"
            @click="emit('selecionar', a.id)"
          >
            <span class="avatar" aria-hidden="true">{{ iniciais(nomeContato(a)) }}</span>
            <span class="nome">
              {{ a.associado?.nome ?? nomeContato(a) }}
              <span v-if="a.responsavel && visao !== 'MEUS'" class="ajuda">{{
                a.responsavel.nome
              }}</span>
            </span>
            <span class="ajuda">Desde {{ formatarData(a.iniciadoEm) }}</span>
          </button>
        </li>
      </ul>
      <nav
        v-if="fila.data.value.totalPaginas > 1"
        class="acoes paginas"
        aria-label="Paginação da fila"
      >
        <button type="button" class="secundario" :disabled="pagina === 0" @click="pagina--">
          Anterior
        </button>
        <span>{{ pagina + 1 }} / {{ fila.data.value.totalPaginas }}</span>
        <button
          type="button"
          class="secundario"
          :disabled="pagina + 1 >= fila.data.value.totalPaginas"
          @click="pagina++"
        >
          Próxima
        </button>
      </nav>
    </template>
    <div ref="rodape" class="rodape" @keydown.esc="fecharOpcoes(true)">
      <button
        ref="botaoOpcoes"
        type="button"
        class="ver-opcoes"
        :aria-expanded="opcoes"
        aria-controls="opcoes-atendimento"
        @click="opcoes = !opcoes"
      >
        Ver opções
        <IconeTraco :nome="opcoes ? 'seta-abaixo' : 'seta-acima'" />
      </button>
      <!-- Depois do botão no DOM (o Tab chega ao item); visualmente abre para cima. -->
      <ul v-if="opcoes" id="opcoes-atendimento" class="menu-opcoes">
        <li>
          <RouterLink :to="{ name: 'atendimento-historico' }" class="com-icone">
            <IconeTraco nome="historico" />
            Histórico geral
          </RouterLink>
        </li>
        <li v-if="podeRespostas">
          <RouterLink :to="{ name: 'atendimento-respostas-rapidas' }" class="com-icone">
            <IconeTraco nome="raio" />
            Mensagens rápidas
          </RouterLink>
        </li>
      </ul>
    </div>
  </section>
</template>

<style scoped>
.fila {
  display: flex;
  flex-direction: column;
  min-width: 0;
  margin: 0;
}

.busca input {
  width: 100%;
  box-sizing: border-box;
}

.visoes {
  margin: 0.75rem 0;
}

.chip {
  border-radius: 999px;
  border-color: var(--cor-borda);
  background: var(--cor-superficie);
  color: var(--cor-texto);
  padding: 0.2rem 0.75rem;
  font-size: 0.85rem;
}

.chip[aria-pressed='true'] {
  background: var(--cor-destaque-suave);
  border-color: var(--cor-destaque-borda);
  color: var(--cor-destaque-forte);
}

.contagem {
  margin-left: 0.4rem;
  font-weight: 600;
}

.itens {
  list-style: none;
  padding: 0;
  margin: 0;
  overflow-y: auto;
  flex: 1;
}

.item {
  width: 100%;
  display: grid;
  grid-template-columns: 2.25rem 1fr auto;
  gap: 0.6rem;
  align-items: center;
  text-align: left;
  background: none;
  border: none;
  border-bottom: 1px solid var(--cor-borda);
  border-radius: 0;
  color: var(--cor-texto);
  padding: 0.6rem 0.5rem;
}

.item[aria-current='true'] {
  background: var(--cor-destaque-suave);
}

.avatar {
  display: grid;
  place-items: center;
  width: 2.25rem;
  height: 2.25rem;
  border-radius: 999px;
  background: var(--cor-destaque-suave);
  color: var(--cor-destaque-forte);
  font-size: 0.8rem;
  font-weight: 600;
}

.nome .ajuda {
  display: block;
}

.vazio {
  color: var(--cor-texto-2);
  text-align: center;
  padding: 1.5rem 0;
}

.paginas {
  justify-content: center;
  margin-top: 0.5rem;
}

.rodape {
  position: relative;
  margin: 0.75rem 0 0;
  border-top: 1px solid var(--cor-borda);
  padding-top: 0.5rem;
}

button.ver-opcoes {
  display: flex;
  width: 100%;
  justify-content: space-between;
  align-items: center;
  border: none;
  background: none;
  color: var(--cor-texto);
  padding: 0.4rem 0;
  font-weight: 500;
}

.menu-opcoes {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 100%;
  z-index: 3;
  list-style: none;
  margin: 0 0 0.25rem;
  padding: 0.4rem;
  background: var(--cor-superficie);
  border: 1px solid var(--cor-borda);
  border-radius: 12px;
  box-shadow: 0 8px 24px rgb(0 0 0 / 8%);
}

.menu-opcoes a {
  display: flex;
  padding: 0.5rem;
  border-radius: 8px;
  color: var(--cor-texto);
  text-decoration: none;
}

.menu-opcoes a:hover {
  background: var(--cor-superficie-2);
}
</style>
