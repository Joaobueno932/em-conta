<script setup lang="ts">
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, nextTick, ref, toRef, watch } from 'vue'
import { RouterLink } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { temPermissao, useSessao } from '@/features/auth/auth-api'
import { codigoDoErro, describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'

import AnexoMensagem from './AnexoMensagem.vue'
import {
  ACEITOS,
  assumir,
  type CategoriaAnexo,
  chavesAtendimento,
  enviarAnexo,
  formatarTamanho,
  ROTULO_CATEGORIA,
  TAMANHO_MAXIMO_LEGENDA,
  validarArquivo,
  encerrar,
  formatarDataHora,
  formatarHora,
  INTERVALO_MS,
  nomeContato,
  obterDetalhe,
  responder,
  responsaveis,
  ROTULO_EVENTO,
  ROTULO_SITUACAO_MENSAGEM,
  SITUACOES_SEM_ENVIO,
  transferir,
} from './atendimento-api'
import { useLinhaDoTempo } from './use-linha-do-tempo'

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
const linha = useLinhaDoTempo(toRef(props, 'id'), false, true)
const recente = linha.recente

const resumo = computed(() => detalhe.data.value?.resumo)
const souResponsavel = computed(
  () => !!resumo.value?.responsavel && resumo.value.responsavel.id === usuario.value?.id,
)
const janelaAberta = computed(() => {
  const ate = detalhe.data.value?.janelaAbertaAte
  // Aberta até o instante exato do limite, inclusive (mesma regra do servidor).
  return !!ate && Date.now() <= new Date(ate).getTime()
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

// Resposta: a chave de idempotência identifica a operação (este atendimento + este texto, já sem
// espaços nas pontas, que é o que o servidor compara). Repetir um texto cujo envio não teve resposta
// reutiliza a chave daquela tentativa e não duplica a mensagem; texto editado é outra operação, com
// outra chave.
const texto = ref('')
const tentativas = new Map<string, string>()
const operacao = (id: number, conteudo: string) => JSON.stringify([id, conteudo])
function chaveDa(id: number, conteudo: string) {
  let chave = tentativas.get(operacao(id, conteudo))
  if (!chave) {
    chave = crypto.randomUUID()
    tentativas.set(operacao(id, conteudo), chave)
  }
  return chave
}
const responderMut = useMutation({
  mutationFn: (conteudo: string) =>
    responder(http, props.id, conteudo, chaveDa(props.id, conteudo)),
  onSuccess: (_, conteudo) => {
    texto.value = ''
    tentativas.delete(operacao(props.id, conteudo))
  },
  onSettled: atualizar,
})
watch(texto, () => {
  if (!responderMut.isPending.value && responderMut.isError.value) responderMut.reset()
})
// Anexo: validação antecipada (tipo, extensão, tamanho); o servidor confere tudo de novo, inclusive o
// conteúdo. A chave identifica a operação (atendimento + arquivo + legenda): repetir após falha de rede
// reutiliza a chave; trocar o arquivo ou a legenda é outra operação.
const arquivo = ref<{ arquivo: File; categoria: CategoriaAnexo } | null>(null)
const erroArquivo = ref('')
const seletor = ref<HTMLInputElement | null>(null)
function selecionarArquivo(evento: Event) {
  const entrada = evento.target as HTMLInputElement
  const escolhido = entrada.files?.[0]
  entrada.value = ''
  if (!escolhido) return
  const resultado = validarArquivo(escolhido)
  if (resultado.erro !== undefined) {
    erroArquivo.value = resultado.erro
    arquivo.value = null
    return
  }
  erroArquivo.value = ''
  arquivo.value = { arquivo: escolhido, categoria: resultado.categoria }
  anexoMut.reset()
}
function removerArquivo() {
  arquivo.value = null
  erroArquivo.value = ''
  anexoMut.reset()
}
const aceitaLegenda = computed(() => arquivo.value?.categoria !== 'AUDIO')
const anexoMut = useMutation({
  mutationFn: (dados: { arquivo: File; legenda: string }) =>
    enviarAnexo(
      http,
      props.id,
      dados.arquivo,
      dados.legenda,
      chaveDa(props.id, operacaoArquivo(dados.arquivo, dados.legenda)),
    ),
  onSuccess: (_, dados) => {
    tentativas.delete(operacao(props.id, operacaoArquivo(dados.arquivo, dados.legenda)))
    arquivo.value = null
    texto.value = ''
  },
  onSettled: atualizar,
})
const operacaoArquivo = (f: File, legenda: string) =>
  `arquivo:${f.name}:${f.size}:${f.lastModified}:${legenda}`

function enviar() {
  if (arquivo.value) {
    const legenda = aceitaLegenda.value ? texto.value.trim() : ''
    if (anexoMut.isPending.value || legenda.length > TAMANHO_MAXIMO_LEGENDA) return
    anexoMut.mutate({ arquivo: arquivo.value.arquivo, legenda })
    return
  }
  const conteudo = texto.value.trim()
  if (!conteudo || responderMut.isPending.value) return
  responderMut.mutate(conteudo)
}
const enviando = computed(() => responderMut.isPending.value || anexoMut.isPending.value)

// "Ver na conversa" (área Mídia): rola até a mensagem, se já estiver carregada.
const avisoMensagem = ref('')
async function irParaMensagem(mensagemId: number) {
  avisoMensagem.value = ''
  await nextTick()
  const alvo = rolagem.value?.querySelector<HTMLElement>(`#mensagem-${mensagemId}`)
  if (!alvo) {
    avisoMensagem.value =
      'Esta mensagem ainda não está carregada. Use “Carregar mensagens anteriores” para chegar até ela.'
    return
  }
  alvo.scrollIntoView({ block: 'center' })
  alvo.focus()
}
defineExpose({ irParaMensagem })

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
const perto = (el: HTMLElement) => el.scrollHeight - el.scrollTop - el.clientHeight < 80

// Item novo no fim: desce só se a pessoa já estava no fim (não tira quem está lendo mensagens antigas).
watch(
  () => linha.itens.value[linha.itens.value.length - 1]?.cursor,
  async (novo, antigo) => {
    const el = rolagem.value
    const descer = !antigo || !el || perto(el)
    await nextTick()
    if (descer && rolagem.value) rolagem.value.scrollTop = rolagem.value.scrollHeight
  },
  { flush: 'pre' },
)

// Conteúdo anterior entra acima: mantém o mesmo trecho visível.
async function carregarAnteriores() {
  const el = rolagem.value
  const doFim = el ? el.scrollHeight - el.scrollTop : 0
  await linha.carregarAnteriores()
  await nextTick()
  if (el) el.scrollTop = el.scrollHeight - doFim
}
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
        <p v-if="recente.isPending.value" role="status">Carregando mensagens…</p>
        <AlertaMensagem v-else-if="recente.isError.value" tipo="erro">
          {{ describeApiError(recente.error.value) }}
        </AlertaMensagem>
        <template v-else>
          <div class="anteriores">
            <p v-if="linha.carregandoAnteriores.value" role="status">
              Carregando mensagens anteriores…
            </p>
            <template v-else-if="linha.erroAnteriores.value">
              <AlertaMensagem tipo="erro">
                Não foi possível carregar as mensagens anteriores.
                {{ describeApiError(linha.erroAnteriores.value) }}
              </AlertaMensagem>
              <button type="button" class="secundario" @click="carregarAnteriores">
                Tentar de novo
              </button>
            </template>
            <button
              v-else-if="linha.temAnteriores.value"
              type="button"
              class="secundario"
              @click="carregarAnteriores"
            >
              Carregar mensagens anteriores
            </button>
            <p v-else class="ajuda">Início do histórico</p>
          </div>
          <template v-for="item in linha.itens.value" :key="`${item.tipo}-${item.id}`">
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
            <div
              v-else
              :id="`mensagem-${item.id}`"
              class="mensagem"
              :class="item.direcao === 'SAIDA' ? 'saida' : 'entrada'"
              tabindex="-1"
            >
              <p v-if="item.tipoMensagem === 'NAO_SUPORTADO'" class="ajuda">
                Mensagem do tipo “{{ item.tipoExterno }}” não suportada nesta versão.
              </p>
              <template v-else-if="item.anexo">
                <AnexoMensagem
                  :atendimento="id"
                  :anexo="item.anexo"
                  :legenda="item.texto"
                  :pode-tentar-de-novo="
                    item.direcao === 'ENTRADA' &&
                    souResponsavel &&
                    resumo.situacao === 'EM_ATENDIMENTO' &&
                    pode('atendimento:responder')
                  "
                />
                <p v-if="item.texto" class="texto">{{ item.texto }}</p>
              </template>
              <p v-else class="texto">{{ item.texto }}</p>
              <p class="meta">
                <span v-if="item.autor">{{ item.autor.nome }} · </span>
                {{ formatarHora(item.ocorridoEm) }}
                <span
                  v-if="item.direcao === 'SAIDA'"
                  :class="{ falha: SITUACOES_SEM_ENVIO.includes(item.situacao ?? '') }"
                >
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
      <p v-if="avisoMensagem" class="ajuda" role="status">{{ avisoMensagem }}</p>
      <div v-if="arquivo" class="selecionado" role="group" aria-label="Arquivo selecionado">
        <span class="nome">{{ arquivo.arquivo.name }}</span>
        <span class="ajuda"
          >{{ ROTULO_CATEGORIA[arquivo.categoria] }} ·
          {{ formatarTamanho(arquivo.arquivo.size) }}</span
        >
        <button
          type="button"
          class="link"
          :disabled="anexoMut.isPending.value"
          :aria-label="`Remover o arquivo ${arquivo.arquivo.name}`"
          @click="removerArquivo"
        >
          Remover
        </button>
      </div>
      <AlertaMensagem v-if="erroArquivo" tipo="erro">{{ erroArquivo }}</AlertaMensagem>
      <form class="composer" @submit.prevent="enviar">
        <input
          ref="seletor"
          type="file"
          class="visualmente-oculto"
          tabindex="-1"
          aria-hidden="true"
          :accept="ACEITOS"
          @change="selecionarArquivo"
        />
        <button
          type="button"
          class="secundario"
          :disabled="!!motivoComposer || enviando"
          @click="seletor?.click()"
        >
          Anexar arquivo
        </button>
        <label for="resposta" class="visualmente-oculto">{{
          arquivo ? 'Legenda (opcional)' : 'Mensagem'
        }}</label>
        <textarea
          id="resposta"
          v-model="texto"
          rows="2"
          :maxlength="arquivo ? TAMANHO_MAXIMO_LEGENDA : 4096"
          :disabled="!!motivoComposer || (!!arquivo && !aceitaLegenda)"
          :placeholder="
            motivoComposer ||
            (arquivo
              ? aceitaLegenda
                ? 'Legenda (opcional)'
                : 'O WhatsApp não aceita legenda em áudio.'
              : 'Digite uma mensagem')
          "
          @keydown.enter.exact.prevent="enviar"
        ></textarea>
        <button
          type="submit"
          :disabled="!!motivoComposer || (!arquivo && !texto.trim()) || enviando"
        >
          {{ enviando ? 'Enviando…' : 'Enviar' }}
        </button>
      </form>
      <p v-if="motivoComposer && detalhe.data.value?.canalConfigurado" class="ajuda">
        {{ motivoComposer }}
      </p>
      <AlertaMensagem v-if="anexoMut.isError.value" tipo="erro">
        {{ describeApiError(anexoMut.error.value) }} O arquivo foi mantido; tentar de novo não
        duplica a mensagem.
      </AlertaMensagem>
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

.anteriores {
  align-self: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.25rem;
  text-align: center;
}

.anteriores p {
  margin: 0;
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

.selecionado {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 0.25rem 0.75rem;
  padding: 0.4rem 0.6rem;
  border: 1px dashed var(--cor-borda);
  border-radius: 8px;
}

.selecionado .nome {
  font-weight: 600;
  overflow-wrap: anywhere;
}

.mensagem:focus-visible {
  outline: 2px solid var(--cor-destaque-borda);
}

.composer textarea {
  flex: 1;
  resize: vertical;
}
</style>
