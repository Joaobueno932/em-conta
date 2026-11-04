<script setup lang="ts">
import { keepPreviousData, useQuery } from '@tanstack/vue-query'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

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
} from './atendimento-api'

/**
 * Fila de conversas (AT-01/AT-03): visões, busca no servidor e paginação. Nesta página só há
 * "Em andamento" (todos os atendimentos em curso, de qualquer responsável) e "Aguardando"; os
 * encerrados ficam no Histórico geral, em "Ver opções".
 */
type VisaoFila = 'EM_ANDAMENTO' | 'AGUARDANDO'
const props = defineProps<{ selecionado: number | null }>()
const emit = defineEmits<{ selecionar: [id: number] }>()

// "Ver opções" (AT-02/AT-06): menu que abre para cima no rodapé da fila. Nesta versão só tem o
// Histórico geral e as Mensagens rápidas (com permissão); as demais opções do protótipo dependem de
// entregas futuras.
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
const route = useRoute()
const router = useRouter()

// Links antigos podem trazer ?visao= com visões que saíram da página (MEUS, ENCERRADOS): abre a
// visão válida mais próxima e tira o parâmetro da URL, mantendo a conversa selecionada.
const pedida = route.query.visao
const visao = ref<VisaoFila>(
  pedida === 'EM_ANDAMENTO' || pedida === 'MEUS' ? 'EM_ANDAMENTO' : 'AGUARDANDO',
)
if (pedida !== undefined) {
  const query = { ...route.query }
  delete query.visao
  void router.replace({ query })
}
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
    { valor: 'EM_ANDAMENTO' as const, rotulo: 'Em andamento', total: c?.emAndamento },
    { valor: 'AGUARDANDO' as const, rotulo: 'Aguardando', total: c?.aguardando },
  ]
})

// Estado vazio por visão (AT-01); com busca, a mensagem fala da busca.
const VAZIO: Record<VisaoFila, [string, string]> = {
  AGUARDANDO: ['Nenhum atendimento aguardando', 'Novas conversas do WhatsApp aparecem aqui.'],
  EM_ANDAMENTO: ['Nenhum atendimento em andamento', 'Assuma uma conversa da fila para começar.'],
}
const vazio = computed<[string, string]>(() =>
  busca.value
    ? ['Nenhum atendimento encontrado', 'Confira o nome ou o telefone buscado.']
    : VAZIO[visao.value],
)

function trocar(nova: VisaoFila) {
  visao.value = nova
  pagina.value = 0
}
defineExpose({ mostrarVisao: trocar })
</script>

<template>
  <section class="fila cartao" aria-labelledby="titulo-fila">
    <h2 id="titulo-fila" class="visualmente-oculto">Fila de atendimentos</h2>
    <div v-if="$slots.topo" class="topo">
      <slot name="topo" />
    </div>
    <div class="filtros">
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
        <IconeTraco nome="busca" :tamanho="16" />
        <input
          id="busca-fila"
          v-model="termo"
          type="search"
          maxlength="100"
          placeholder="Buscar contato por nome ou telefone"
        />
      </form>
      <div class="visoes" role="group" aria-label="Visões da fila">
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
    </div>

    <div class="lista">
      <p v-if="fila.isPending.value" class="estado" role="status">Carregando atendimentos…</p>
      <div v-else-if="fila.isError.value" class="estado">
        <AlertaMensagem tipo="erro">{{ describeApiError(fila.error.value) }}</AlertaMensagem>
        <button type="button" class="secundario" @click="fila.refetch()">Tentar novamente</button>
      </div>
      <template v-else-if="fila.data.value">
        <div v-if="fila.data.value.totalItens === 0" class="vazio" role="status">
          <span class="selo" aria-hidden="true"><IconeTraco nome="conversa" :tamanho="28" /></span>
          <p class="titulo">{{ vazio[0] }}</p>
          <p class="ajuda">{{ vazio[1] }}</p>
        </div>
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
                <span v-if="a.responsavel" class="responsavel">{{ a.responsavel.nome }}</span>
              </span>
              <span class="data">Desde {{ formatarData(a.iniciadoEm) }}</span>
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
    </div>
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
        <IconeTraco :nome="opcoes ? 'seta-abaixo' : 'seta-acima'" :tamanho="20" />
      </button>
      <!-- Depois do botão no DOM (o Tab chega ao item); visualmente abre para cima. -->
      <ul v-if="opcoes" id="opcoes-atendimento" class="menu-opcoes">
        <li>
          <RouterLink :to="{ name: 'atendimento-historico' }" class="com-icone">
            <IconeTraco nome="historico" :tamanho="20" />
            Histórico geral
          </RouterLink>
        </li>
        <li v-if="podeRespostas">
          <RouterLink :to="{ name: 'atendimento-respostas-rapidas' }" class="com-icone">
            <IconeTraco nome="raio" :tamanho="20" />
            Mensagens rápidas
          </RouterLink>
        </li>
      </ul>
    </div>
  </section>
</template>

<style scoped>
/* AT-01/AT-03: cartão de 340 px; topo de 50 px, busca de 38 px, chips de 36 px, itens de 57 px.
   As duas visões cabem numa linha; em larguras menores os chips quebram. */
.fila {
  display: flex;
  flex-direction: column;
  min-width: 0;
  margin: 0;
  padding: 0;
}

.topo {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  height: 49px;
  padding: 0 16px;
  border-bottom: 1px solid var(--cor-borda);
}

.filtros {
  flex-shrink: 0;
  padding: 12px 9px 8px;
}

.busca {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 38px;
  box-sizing: border-box;
  padding: 0 12px 0 16px;
  border: 1px solid var(--cor-borda);
  border-radius: var(--raio);
  background: var(--cor-superficie);
}

.busca:focus-within {
  outline: 3px solid var(--cor-foco);
  outline-offset: 2px;
}

.busca .icone {
  flex-shrink: 0;
  color: var(--cor-texto-2);
}

.busca input {
  flex: 1;
  min-width: 0;
  padding: 0;
  border: none;
  background: transparent;
  font-size: 14px;
}

.busca input:focus-visible {
  outline: none;
}

.visoes {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

/* As duas visões dividem a linha; só quebram se a fila ficar mais estreita que os rótulos. */
.chip {
  flex: 1 1 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 36px;
  padding: 0 8px;
  border: 1px solid var(--cor-borda);
  border-radius: 999px;
  background: var(--cor-superficie);
  color: var(--cor-texto);
  font-size: 14px;
  white-space: nowrap;
}

.chip[aria-pressed='true'] {
  background: var(--cor-destaque-suave);
  border-color: var(--cor-destaque-borda);
  color: var(--cor-destaque-forte);
  font-weight: 500;
}

.contagem {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 22px;
  height: 22px;
  padding: 0 8px;
  box-sizing: border-box;
  border-radius: 999px;
  background: var(--cor-superficie-2);
  color: var(--cor-texto);
  font-size: 13px;
  font-weight: 500;
}

.chip[aria-pressed='true'] .contagem {
  background: var(--cor-destaque-borda);
  color: var(--cor-destaque-forte);
}

.lista {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.itens {
  list-style: none;
  padding: 0 1px;
  margin: 0;
}

.item {
  width: 100%;
  min-height: 57px;
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr) auto;
  gap: 15px;
  align-items: center;
  text-align: left;
  background: none;
  border: none;
  border-bottom: 1px solid var(--cor-borda);
  border-radius: 0;
  color: var(--cor-texto);
  padding: 8px 6px 8px 14px;
}

.item:hover {
  background: var(--cor-superficie-2);
}

.item[aria-current='true'] {
  background: var(--cor-destaque-suave);
  border-bottom-color: transparent;
  border-radius: var(--raio);
}

.avatar {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  border-radius: 999px;
  background: var(--cor-destaque-suave);
  color: var(--cor-destaque-forte);
  font-size: 15px;
  font-weight: 500;
}

.item[aria-current='true'] .avatar {
  background: transparent;
}

.nome {
  font-size: 16px;
  font-weight: 500;
  line-height: 1.25;
  overflow-wrap: break-word;
  min-width: 0;
}

.responsavel {
  display: block;
  color: var(--cor-texto-2);
  font-size: 13px;
  font-weight: 400;
}

.data {
  color: var(--cor-texto-3);
  font-size: 13px;
  white-space: nowrap;
}

.estado {
  margin: 0;
  padding: 16px;
}

/* AT-01: fila vazia com selo, título e orientação. */
.vazio {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 40px 24px;
  text-align: center;
}

.vazio .selo {
  display: grid;
  place-items: center;
  width: 84px;
  height: 84px;
  margin-bottom: 14px;
  border-radius: 999px;
  background: var(--cor-destaque-suave);
  color: var(--cor-destaque);
}

.vazio .titulo {
  margin: 0 0 6px;
  font-size: 20px;
  font-weight: 700;
  line-height: 1.2;
}

.vazio .ajuda {
  max-width: 14rem;
  font-size: 13px;
}

.paginas {
  justify-content: center;
  padding: 8px;
}

.rodape {
  position: relative;
  flex-shrink: 0;
  border-top: 1px solid var(--cor-borda);
}

button.ver-opcoes {
  display: flex;
  width: 100%;
  height: 59px;
  justify-content: space-between;
  align-items: center;
  border: none;
  border-radius: 0 0 var(--raio-cartao) var(--raio-cartao);
  background: none;
  color: var(--cor-texto);
  padding: 0 18px;
  font-size: 17px;
  font-weight: 500;
}

button.ver-opcoes:hover {
  background: var(--cor-superficie-2);
}

/* AT-02/AT-06: menu que abre para cima, sobre a lista. */
.menu-opcoes {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 100%;
  z-index: 3;
  list-style: none;
  margin: 0;
  padding: 12px 10px;
  background: var(--cor-superficie);
  border: 1px solid var(--cor-borda);
  border-radius: var(--raio-cartao) var(--raio-cartao) 0 0;
  box-shadow: 0 -8px 24px rgb(30 36 32 / 8%);
}

.menu-opcoes a {
  display: flex;
  gap: 14px;
  padding: 9px 8px;
  border-radius: 8px;
  color: var(--cor-texto);
  font-size: 16px;
  text-decoration: none;
}

.menu-opcoes a:hover {
  background: var(--cor-superficie-2);
}

/* Fila de 320 px (até 80rem): a data passa para duas linhas e sobra espaço para o nome. */
@media (max-width: 80rem) and (min-width: 60.01rem) {
  .item {
    gap: 10px;
  }

  .chip {
    gap: 6px;
    padding: 0 6px;
    font-size: 13px;
  }

  .data {
    width: 5.5rem;
    white-space: normal;
    text-align: right;
  }
}
</style>
