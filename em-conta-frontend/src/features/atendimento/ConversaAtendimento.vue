<script setup lang="ts">
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, nextTick, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { temPermissao, useSessao } from '@/features/auth/auth-api'
import { codigoDoErro, describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'

import {
  assumir,
  chavesAtendimento,
  encerrar,
  formatarDataHora,
  formatarHora,
  INTERVALO_MS,
  linhaDoTempo,
  nomeContato,
  obterDetalhe,
  responder,
  responsaveis,
  ROTULO_EVENTO,
  ROTULO_SITUACAO_MENSAGEM,
  transferir,
} from './atendimento-api'

/**
 * Conversa (AT-02/AT-04/AT-05): linha do tempo, assumir, responder, transferir e encerrar. Só o
 * responsável responde; o servidor confere tudo de novo (permissão, responsável, janela de 24 h).
 */
const props = defineProps<{ id: number }>()
const emit = defineEmits<{ fechar: [] }>()

const http = useHttpClient()
const queryClient = useQueryClient()
const { data: usuario } = useSessao()

const detalhe = useQuery({
  queryKey: computed(() => chavesAtendimento.detalhe(props.id)),
  queryFn: () => obterDetalhe(http, props.id),
  refetchInterval: INTERVALO_MS,
})
const linha = useQuery({
  queryKey: computed(() => chavesAtendimento.linha(props.id, false)),
  queryFn: () => linhaDoTempo(http, props.id),
  refetchInterval: INTERVALO_MS,
})

const resumo = computed(() => detalhe.data.value?.resumo)
const souResponsavel = computed(
  () => !!resumo.value?.responsavel && resumo.value.responsavel.id === usuario.value?.id,
)
const janelaAberta = computed(() => {
  const ate = detalhe.data.value?.janelaAbertaAte
  return !!ate && new Date(ate).getTime() > Date.now()
})
const pode = (p: string) => temPermissao(usuario.value, p)

async function atualizar() {
  await queryClient.invalidateQueries({ queryKey: chavesAtendimento.todos })
}

const erroAcao = ref<unknown>(null)
const conflito = computed(() =>
  ['CONFLITO_CONCORRENTE', 'ATENDIMENTO_JA_ASSUMIDO', 'ATENDIMENTO_ENCERRADO'].includes(
    codigoDoErro(erroAcao.value) ?? '',
  ),
)

const assumirMut = useMutation({
  mutationFn: () => assumir(http, props.id),
  onMutate: () => (erroAcao.value = null),
  onError: (e) => (erroAcao.value = e),
  onSettled: atualizar,
})

const encerrarMut = useMutation({
  mutationFn: () => encerrar(http, props.id, detalhe.data.value!.versao),
  onMutate: () => (erroAcao.value = null),
  onError: (e) => (erroAcao.value = e),
  onSettled: atualizar,
})

function confirmarEncerramento() {
  if (
    window.confirm(
      'Encerrar este atendimento? Uma nova mensagem do contato abre outro atendimento.',
    )
  ) {
    encerrarMut.mutate()
  }
}

// Transferência direta, com motivo opcional; sem destino devolve à fila.
const transferindo = ref(false)
const destino = ref<number | ''>('')
const motivoTransferencia = ref('')
const listaResponsaveis = useQuery({
  queryKey: chavesAtendimento.responsaveis,
  queryFn: () => responsaveis(http),
  enabled: transferindo,
})
const outrosResponsaveis = computed(
  () => listaResponsaveis.data.value?.filter((u) => u.id !== usuario.value?.id) ?? [],
)
const transferirMut = useMutation({
  mutationFn: () =>
    transferir(http, props.id, {
      versao: detalhe.data.value!.versao,
      destinoId: destino.value === '' ? undefined : destino.value,
      motivo: motivoTransferencia.value.trim() || undefined,
    }),
  onMutate: () => (erroAcao.value = null),
  onError: (e) => (erroAcao.value = e),
  onSuccess: () => {
    transferindo.value = false
    destino.value = ''
    motivoTransferencia.value = ''
  },
  onSettled: atualizar,
})

// Resposta: a chave de idempotência só muda depois de um envio registrado, então repetir após uma
// falha de rede não duplica a mensagem.
const texto = ref('')
const chave = ref(crypto.randomUUID())
const responderMut = useMutation({
  mutationFn: () => responder(http, props.id, texto.value.trim(), chave.value),
  onSuccess: () => {
    texto.value = ''
    chave.value = crypto.randomUUID()
  },
  onSettled: atualizar,
})
watch(texto, (novo, antigo) => {
  // Texto diferente é outra mensagem.
  if (!responderMut.isPending.value && novo !== antigo && responderMut.isError.value) {
    chave.value = crypto.randomUUID()
    responderMut.reset()
  }
})
function enviar() {
  if (!texto.value.trim() || responderMut.isPending.value) return
  responderMut.mutate()
}

const motivoComposer = computed(() => {
  if (!detalhe.data.value) return ''
  if (!detalhe.data.value.canalConfigurado)
    return 'Canal não configurado. Não é possível enviar mensagens.'
  if (resumo.value?.situacao === 'ENCERRADO') return 'Atendimento encerrado.'
  if (!souResponsavel.value) return 'Assuma o atendimento para responder.'
  if (!janelaAberta.value)
    return 'Janela de 24 horas encerrada: o contato precisa enviar uma nova mensagem.'
  if (!pode('atendimento:responder')) return 'Você não tem permissão para responder.'
  return ''
})

const opcoes = ref(false)
const rolagem = ref<HTMLElement | null>(null)
watch(
  () => linha.data.value?.length,
  async () => {
    await nextTick()
    if (rolagem.value) rolagem.value.scrollTop = rolagem.value.scrollHeight
  },
)
</script>

<template>
  <section class="conversa cartao" aria-labelledby="titulo-conversa">
    <p v-if="detalhe.isPending.value" role="status">Carregando conversa…</p>
    <AlertaMensagem v-else-if="detalhe.isError.value" tipo="erro">
      {{ describeApiError(detalhe.error.value) }}
    </AlertaMensagem>
    <template v-else-if="resumo">
      <header class="topo">
        <div>
          <h2 id="titulo-conversa">{{ resumo.associado?.nome ?? nomeContato(resumo) }}</h2>
          <p class="ajuda">
            {{ resumo.codigo }} · +{{ resumo.contatoNumero }} ·
            <span v-if="resumo.situacao === 'AGUARDANDO'">Aguardando atendimento</span>
            <span v-else-if="resumo.situacao === 'EM_ATENDIMENTO'">
              Em atendimento com {{ resumo.responsavel?.nome }}
            </span>
            <span v-else>Encerrado</span>
          </p>
        </div>
        <div class="acoes">
          <button
            v-if="resumo.situacao === 'AGUARDANDO' && pode('atendimento:assumir')"
            type="button"
            :disabled="assumirMut.isPending.value"
            @click="assumirMut.mutate()"
          >
            Assumir
          </button>
          <template v-if="resumo.situacao === 'EM_ATENDIMENTO' && souResponsavel">
            <button
              v-if="pode('atendimento:transferir')"
              type="button"
              class="secundario"
              :aria-expanded="transferindo"
              @click="transferindo = !transferindo"
            >
              Transferir
            </button>
            <button
              v-if="pode('atendimento:encerrar')"
              type="button"
              :disabled="encerrarMut.isPending.value"
              @click="confirmarEncerramento"
            >
              Encerrar
            </button>
          </template>
          <div class="menu">
            <button
              type="button"
              class="secundario"
              :aria-expanded="opcoes"
              @click="opcoes = !opcoes"
            >
              Ver opções
            </button>
            <ul v-if="opcoes" class="menu-itens">
              <li>
                <RouterLink
                  :to="{ name: 'atendimento-historico', query: { busca: resumo.contatoNumero } }"
                >
                  Histórico geral
                </RouterLink>
              </li>
            </ul>
          </div>
          <button
            type="button"
            class="link fechar"
            aria-label="Fechar conversa"
            @click="emit('fechar')"
          >
            ✕
          </button>
        </div>
      </header>

      <AlertaMensagem v-if="conflito" tipo="aviso">
        O atendimento foi alterado por outra pessoa e a tela foi atualizada.
      </AlertaMensagem>
      <AlertaMensagem v-else-if="erroAcao" tipo="erro">{{
        describeApiError(erroAcao)
      }}</AlertaMensagem>

      <form v-if="transferindo" class="transferencia" @submit.prevent="transferirMut.mutate()">
        <div class="campo">
          <label for="destino">Transferir para</label>
          <select id="destino" v-model="destino">
            <option value="">Devolver à fila</option>
            <option v-for="u in outrosResponsaveis" :key="u.id" :value="u.id">{{ u.nome }}</option>
          </select>
        </div>
        <div class="campo">
          <label for="motivo-transferencia">Motivo (opcional)</label>
          <input id="motivo-transferencia" v-model="motivoTransferencia" maxlength="500" />
        </div>
        <div class="acoes">
          <button type="submit" :disabled="transferirMut.isPending.value">
            Confirmar transferência
          </button>
          <button type="button" class="secundario" @click="transferindo = false">Cancelar</button>
        </div>
      </form>

      <div ref="rolagem" class="linha" aria-live="polite" aria-label="Mensagens">
        <p v-if="linha.isPending.value" role="status">Carregando mensagens…</p>
        <AlertaMensagem v-else-if="linha.isError.value" tipo="erro">
          {{ describeApiError(linha.error.value) }}
        </AlertaMensagem>
        <template v-else>
          <template v-for="item in linha.data.value" :key="`${item.tipo}-${item.id}`">
            <p v-if="item.tipo === 'EVENTO'" class="evento">
              {{ formatarDataHora(item.ocorridoEm) }} —
              <template v-if="item.evento === 'ATENDIMENTO_INICIADO'">{{
                ROTULO_EVENTO[item.evento]
              }}</template>
              <template v-else>
                {{ item.autor?.nome }} {{ ROTULO_EVENTO[item.evento ?? ''] ?? item.evento }}
                <template v-if="item.usuarioAlvo">para {{ item.usuarioAlvo.nome }}</template>
                <template v-if="item.associado">{{ item.associado.nome }}</template>
                <template v-if="item.motivo">({{ item.motivo }})</template>
              </template>
            </p>
            <div v-else class="mensagem" :class="item.direcao === 'SAIDA' ? 'saida' : 'entrada'">
              <p v-if="item.tipoMensagem === 'NAO_SUPORTADO'" class="ajuda">
                Mensagem do tipo “{{ item.tipoExterno }}” não suportada nesta versão.
              </p>
              <p v-else class="texto">{{ item.texto }}</p>
              <p class="meta">
                <span v-if="item.autor">{{ item.autor.nome }} · </span>
                {{ formatarHora(item.ocorridoEm) }}
                <span v-if="item.direcao === 'SAIDA'" :class="{ falha: item.situacao === 'FALHA' }">
                  · {{ ROTULO_SITUACAO_MENSAGEM[item.situacao ?? ''] }}
                  <template v-if="item.situacao === 'FALHA' && item.erroTitulo"
                    >: {{ item.erroTitulo }}</template
                  >
                </span>
              </p>
            </div>
          </template>
        </template>
      </div>

      <AlertaMensagem v-if="!detalhe.data.value?.canalConfigurado" tipo="aviso">
        Canal não configurado
      </AlertaMensagem>
      <form class="composer" @submit.prevent="enviar">
        <label for="resposta" class="visualmente-oculto">Mensagem</label>
        <textarea
          id="resposta"
          v-model="texto"
          rows="2"
          maxlength="4096"
          :disabled="!!motivoComposer"
          :placeholder="motivoComposer || 'Digite uma mensagem'"
          @keydown.enter.exact.prevent="enviar"
        ></textarea>
        <button
          type="submit"
          :disabled="!!motivoComposer || !texto.trim() || responderMut.isPending.value"
        >
          {{ responderMut.isPending.value ? 'Enviando…' : 'Enviar' }}
        </button>
      </form>
      <p v-if="motivoComposer && detalhe.data.value?.canalConfigurado" class="ajuda">
        {{ motivoComposer }}
      </p>
      <AlertaMensagem v-if="responderMut.isError.value" tipo="erro">
        {{ describeApiError(responderMut.error.value) }} O texto foi mantido; tentar de novo não
        duplica a mensagem.
      </AlertaMensagem>
    </template>
  </section>
</template>

<style scoped>
.conversa {
  display: flex;
  flex-direction: column;
  min-width: 0;
  margin: 0;
  gap: 0.5rem;
}

.topo {
  display: flex;
  justify-content: space-between;
  gap: 1rem;
  border-bottom: 1px solid var(--cor-borda);
  padding-bottom: 0.5rem;
}

.topo h2 {
  margin: 0;
  font-size: 1.1rem;
}

.topo p {
  margin: 0.2rem 0 0;
}

.menu {
  position: relative;
}

.menu-itens {
  position: absolute;
  right: 0;
  top: 100%;
  z-index: 2;
  list-style: none;
  margin: 0.25rem 0 0;
  padding: 0.5rem 0.75rem;
  background: var(--cor-superficie);
  border: 1px solid var(--cor-borda);
  border-radius: 8px;
  white-space: nowrap;
}

.fechar {
  font-size: 1.1rem;
}

.transferencia {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(12rem, 1fr));
  gap: 0.75rem;
  align-items: end;
}

.transferencia select,
.transferencia input {
  width: 100%;
  box-sizing: border-box;
}

.transferencia .acoes {
  grid-column: 1 / -1;
}

.linha {
  flex: 1;
  min-height: 18rem;
  max-height: 60vh;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  padding: 0.5rem;
  background: var(--cor-superficie-2);
  border-radius: 8px;
}

.evento {
  align-self: center;
  margin: 0;
  font-size: 0.8rem;
  color: var(--cor-texto-2);
  text-align: center;
}

.mensagem {
  max-width: 75%;
  padding: 0.5rem 0.75rem;
  border-radius: 10px;
  background: var(--cor-superficie);
  border: 1px solid var(--cor-borda);
}

.mensagem.saida {
  align-self: flex-end;
  background: var(--cor-destaque-suave);
  border-color: var(--cor-destaque-borda);
}

.mensagem p {
  margin: 0;
}

.texto {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.meta {
  font-size: 0.75rem;
  color: var(--cor-texto-2);
  margin-top: 0.2rem !important;
}

.falha {
  color: var(--cor-erro);
}

.composer {
  display: flex;
  gap: 0.5rem;
  align-items: flex-end;
}

.composer textarea {
  flex: 1;
  resize: vertical;
}
</style>
