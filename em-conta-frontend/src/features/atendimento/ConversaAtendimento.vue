<script setup lang="ts">
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, nextTick, ref, toRef, watch } from 'vue'
import { RouterLink } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { temPermissao, useSessao } from '@/features/auth/auth-api'
import { codigoDoErro, describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'
import IconeTraco from '@/shared/components/IconeTraco.vue'

import {
  ACEITOS,
  assumir,
  formatarData,
  type CategoriaAnexo,
  chavesAtendimento,
  enviarAnexo,
  formatarTamanho,
  ROTULO_CATEGORIA,
  TAMANHO_MAXIMO_LEGENDA,
  validarArquivo,
  encerrar,
  iniciais,
  INTERVALO_MS,
  comecaDia,
  nomeContato,
  rotuloDia,
  obterDetalhe,
  responder,
  responsaveis,
  transferir,
} from './atendimento-api'
import ItemLinhaDoTempo from './ItemLinhaDoTempo.vue'
import type { RespostaRapida } from './respostas-rapidas-api'
import SeletorRespostasRapidas from './SeletorRespostasRapidas.vue'
import { useLinhaDoTempo } from './use-linha-do-tempo'

/**
 * Conversa (AT-02/AT-04/AT-05): linha do tempo, assumir, responder, transferir e encerrar. Só o
 * responsável responde; o servidor confere tudo de novo (permissão, responsável, janela de 24 h).
 */
const props = defineProps<{ id: number; painelAberto?: boolean }>()
const emit = defineEmits<{ fechar: []; alternarPainel: [] }>()

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

// Respostas rápidas (AT-07): pelo botão ou digitando "/" no início de uma palavra. Escolher só preenche
// o campo para revisão; o envio continua o mesmo (mesma chave de idempotência por texto). Com rascunho,
// pergunta se substitui ou insere no cursor; nunca passa do limite do campo sem avisar.
const TAMANHO_MAXIMO_MENSAGEM = 4096
const campoTexto = ref<HTMLTextAreaElement | null>(null)
const seletorRespostas = ref<InstanceType<typeof SeletorRespostasRapidas> | null>(null)
const podeRespostas = computed(() => pode('respostas-rapidas:consultar'))
const respostasIndisponiveis = computed(
  () => !!motivoComposer.value || enviando.value || !!arquivo.value,
)
/** Seletor aberto; `barra` é o termo depois de "/" quando aberto pela digitação. */
const respostas = ref<{ inicio: number; fim: number; barra?: string } | null>(null)
const escolha = ref<{ resposta: RespostaRapida; inicio: number; fim: number } | null>(null)
const erroResposta = ref('')
const pelaBarra = computed(() => respostas.value?.barra !== undefined)

function abrirRespostas() {
  if (respostasIndisponiveis.value) return
  const el = campoTexto.value
  const inicio = el?.selectionStart ?? texto.value.length
  respostas.value = { inicio, fim: el?.selectionEnd ?? inicio }
  erroResposta.value = ''
}
async function focarCampo(cursor?: number) {
  await nextTick()
  const el = campoTexto.value
  if (!el) return
  el.focus()
  if (cursor !== undefined) el.setSelectionRange(cursor, cursor)
}
function fecharRespostas() {
  respostas.value = null
  void focarCampo()
}
// "/" só abre no início do campo ou depois de espaço/quebra de linha, e só com caracteres de atalho
// até o cursor: links (https://…) e barras no meio de palavras não abrem nada.
function verificarBarra() {
  const el = campoTexto.value
  if (!el || !podeRespostas.value || respostasIndisponiveis.value) return
  if (respostas.value && !pelaBarra.value) return
  const cursor = el.selectionStart
  const achado =
    cursor === el.selectionEnd
      ? /(^|\s)\/([a-z0-9-]{0,30})$/i.exec(texto.value.slice(0, cursor))
      : null
  if (!achado) {
    fecharBarra()
    return
  }
  const termo = achado[2] ?? ''
  respostas.value = { inicio: cursor - termo.length - 1, fim: cursor, barra: termo }
  erroResposta.value = ''
}
function fecharBarra() {
  if (pelaBarra.value) respostas.value = null
}
function teclarNoCampo(evento: KeyboardEvent) {
  if (pelaBarra.value && seletorRespostas.value?.teclar(evento)) {
    evento.preventDefault()
    return
  }
  const modificador = evento.shiftKey || evento.ctrlKey || evento.altKey || evento.metaKey
  if (evento.key === 'Enter' && !modificador && !evento.isComposing) {
    evento.preventDefault()
    enviar()
  }
}

function escolherResposta(resposta: RespostaRapida) {
  const aberto = respostas.value
  if (!aberto) return
  respostas.value = null
  const resto = texto.value.slice(0, aberto.inicio) + texto.value.slice(aberto.fim)
  if (resto.trim()) {
    escolha.value = { resposta, inicio: aberto.inicio, fim: aberto.fim }
    return
  }
  aplicarResposta(resposta.conteudo, resposta.conteudo.length)
}
function decidir(modo: 'substituir' | 'inserir') {
  const e = escolha.value
  if (!e) return
  const conteudo = e.resposta.conteudo
  if (modo === 'substituir') aplicarResposta(conteudo, conteudo.length)
  else
    aplicarResposta(
      texto.value.slice(0, e.inicio) + conteudo + texto.value.slice(e.fim),
      e.inicio + conteudo.length,
    )
}
function cancelarEscolha() {
  escolha.value = null
  void focarCampo()
}
function aplicarResposta(novo: string, cursor: number) {
  escolha.value = null
  if (novo.length > TAMANHO_MAXIMO_MENSAGEM) {
    erroResposta.value = `Com esta resposta a mensagem ficaria com ${novo.length} caracteres (máximo ${TAMANHO_MAXIMO_MENSAGEM}). Nada foi inserido; encurte o rascunho ou substitua o texto.`
    void focarCampo()
    return
  }
  erroResposta.value = ''
  texto.value = novo
  void focarCampo(cursor)
}
watch(arquivo, (a) => {
  if (a) {
    respostas.value = null
    escolha.value = null
  }
})

// "Ver na conversa" (área Mídia): carrega o contexto da mensagem se preciso, rola até ela, foca e
// destaca por alguns segundos.
const avisoMensagem = ref('')
const destacada = ref<number | null>(null)
async function irParaMensagem(mensagemId: number, cursor: string) {
  avisoMensagem.value = 'Localizando a mensagem…'
  try {
    const carregada = await linha.carregarAte(cursor, mensagemId)
    if (!carregada) {
      avisoMensagem.value = 'Esta mensagem não está disponível nesta conversa.'
      return
    }
  } catch (erro) {
    avisoMensagem.value = `Não foi possível abrir a mensagem. ${describeApiError(erro)}`
    return
  }
  await nextTick()
  const alvo = rolagem.value?.querySelector<HTMLElement>(`#mensagem-${mensagemId}`)
  if (!alvo) {
    avisoMensagem.value = 'Esta mensagem não está disponível nesta conversa.'
    return
  }
  avisoMensagem.value = ''
  alvo.scrollIntoView({ block: 'center' })
  alvo.focus({ preventScroll: true })
  destacada.value = mensagemId
  window.setTimeout(() => {
    if (destacada.value === mensagemId) destacada.value = null
  }, 3000)
}

/** Devolve o foco ao botão que reabre o painel do associado (ao fechar o painel pelo X). */
function focarBotaoPainel() {
  botaoPainel.value?.focus()
}
defineExpose({ irParaMensagem, focarBotaoPainel })

const motivoComposer = computed(() => {
  if (!detalhe.data.value) return ''
  if (!detalhe.data.value.canalConfigurado)
    return 'Canal não configurado. Não é possível enviar mensagens.'
  if (resumo.value?.situacao === 'ENCERRADO') return 'Atendimento encerrado.'
  if (!souResponsavel.value)
    return resumo.value?.responsavel
      ? 'Este atendimento está com outra pessoa: só quem é responsável responde.'
      : 'Assuma o atendimento para responder esta conversa.'
  if (!janelaAberta.value)
    return 'Janela de 24 horas encerrada: o contato precisa enviar uma nova mensagem.'
  if (!pode('atendimento:responder')) return 'Você não tem permissão para responder.'
  return ''
})

const botaoPainel = ref<HTMLButtonElement | null>(null)

// Separador de dia (AT-04: "Hoje, 13 de outubro") antes do primeiro item de cada dia.
const novoDia = (indice: number) => comecaDia(linha.itens.value, indice)
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
    <div v-if="detalhe.isPending.value" class="estado-conversa" role="status">
      Carregando conversa…
    </div>
    <div v-else-if="detalhe.isError.value" class="estado-conversa">
      <AlertaMensagem tipo="erro">{{ describeApiError(detalhe.error.value) }}</AlertaMensagem>
      <button type="button" class="secundario" @click="detalhe.refetch()">Tentar de novo</button>
    </div>
    <template v-else-if="resumo">
      <header class="topo">
        <span class="avatar" aria-hidden="true">{{
          iniciais(resumo.associado?.nome ?? nomeContato(resumo))
        }}</span>
        <div class="identificacao">
          <h2 id="titulo-conversa" class="com-icone">
            <IconeTraco nome="pessoa" :tamanho="18" />
            <RouterLink
              v-if="resumo.associado"
              :to="{ name: 'associado', params: { id: resumo.associado.id } }"
              >{{ resumo.associado.nome }}</RouterLink
            >
            <span v-else>{{ nomeContato(resumo) }}</span>
          </h2>
          <p class="telefone com-icone">
            <IconeTraco nome="telefone" :tamanho="17" />
            +{{ resumo.contatoNumero }}
          </p>
        </div>
        <div class="acoes">
          <button
            v-if="resumo.situacao === 'AGUARDANDO' && pode('atendimento:assumir')"
            type="button"
            class="com-icone assumir"
            :disabled="assumirMut.isPending.value"
            @click="assumirMut.mutate()"
          >
            <IconeTraco nome="concluir" :tamanho="22" />
            Assumir atendimento
          </button>
          <template v-if="resumo.situacao === 'EM_ATENDIMENTO' && souResponsavel">
            <button
              v-if="pode('atendimento:transferir')"
              type="button"
              class="secundario com-icone"
              :aria-expanded="transferindo"
              @click="transferindo = !transferindo"
            >
              <IconeTraco nome="transferir" />
              Transferir
            </button>
            <button
              v-if="pode('atendimento:encerrar')"
              type="button"
              class="secundario com-icone"
              :disabled="encerrarMut.isPending.value"
              @click="confirmarEncerramento"
            >
              <IconeTraco nome="concluir" />
              Encerrar
            </button>
          </template>
          <RouterLink
            class="botao-icone"
            :to="{ name: 'atendimento-historico', query: { busca: resumo.contatoNumero } }"
            aria-label="Histórico do contato"
            title="Histórico do contato"
          >
            <IconeTraco nome="historico" />
          </RouterLink>
          <button
            ref="botaoPainel"
            type="button"
            class="botao-icone painel"
            aria-label="Dados do associado"
            title="Dados do associado"
            :aria-expanded="painelAberto"
            aria-controls="painel-associado"
            @click="emit('alternarPainel')"
          >
            <IconeTraco nome="pessoa" />
          </button>
          <button
            type="button"
            class="botao-icone sem-borda"
            aria-label="Fechar conversa"
            title="Fechar conversa"
            @click="emit('fechar')"
          >
            <IconeTraco nome="fechar" />
          </button>
        </div>
        <p class="meta">
          {{ resumo.codigo }} ·
          <span v-if="resumo.situacao === 'AGUARDANDO'">Aguardando atendimento</span>
          <span v-else-if="resumo.situacao === 'EM_ATENDIMENTO'"
            >Em atendimento com {{ resumo.responsavel?.nome }}</span
          >
          <span v-else>Encerrado</span>
          · Desde {{ formatarData(resumo.iniciadoEm) }}
        </p>
      </header>

      <AlertaMensagem v-if="conflito" tipo="aviso" class="faixa">
        O atendimento foi alterado por outra pessoa e a tela foi atualizada.
      </AlertaMensagem>
      <AlertaMensagem v-else-if="erroAcao" tipo="erro" class="faixa">{{
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
          <template v-for="(item, indice) in linha.itens.value" :key="`${item.tipo}-${item.id}`">
            <p v-if="novoDia(indice)" class="dia">{{ rotuloDia(item.ocorridoEm) }}</p>
            <ItemLinhaDoTempo
              :item="item"
              :atendimento="id"
              :destacada="destacada === item.id"
              :pode-tentar-de-novo="
                item.direcao === 'ENTRADA' &&
                souResponsavel &&
                resumo.situacao === 'EM_ATENDIMENTO' &&
                pode('atendimento:responder')
              "
            />
          </template>
        </template>
      </div>

      <div v-if="linha.erroLacuna.value" class="lacuna faixa">
        <AlertaMensagem tipo="erro">
          Algumas mensagens recentes ainda não foram carregadas.
          {{ describeApiError(linha.erroLacuna.value) }}
        </AlertaMensagem>
        <button
          type="button"
          class="secundario"
          :disabled="linha.recuperandoLacunas.value"
          @click="linha.recuperarLacunas()"
        >
          Tentar de novo
        </button>
      </div>
      <p v-else-if="linha.lacunaPendente.value" class="ajuda faixa" role="status">
        Carregando mensagens recentes…
      </p>
      <p v-if="avisoMensagem" class="ajuda faixa" role="status">{{ avisoMensagem }}</p>

      <!-- AT-03: sem poder responder, a faixa explica o motivo no lugar do compositor. -->
      <p v-if="motivoComposer" class="aviso-compositor" role="status">
        <IconeTraco nome="informacao" :tamanho="22" />
        {{ motivoComposer }}
      </p>
      <div v-else class="area-resposta">
        <p class="aba" aria-hidden="true">Mensagem</p>
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
          <label for="resposta" class="visualmente-oculto">{{
            arquivo ? 'Legenda (opcional)' : 'Mensagem'
          }}</label>
          <SeletorRespostasRapidas
            v-if="respostas"
            id="seletor-respostas"
            ref="seletorRespostas"
            :termo="respostas.barra"
            @escolher="escolherResposta"
            @fechar="fecharRespostas"
          />
          <div v-if="escolha" class="escolha" role="group" aria-labelledby="escolha-titulo">
            <p id="escolha-titulo">
              O campo já tem um rascunho. Como usar “{{ escolha.resposta.titulo }}”?
            </p>
            <div class="acoes">
              <button type="button" @click="decidir('substituir')">Substituir rascunho</button>
              <button type="button" class="secundario" @click="decidir('inserir')">
                Inserir no cursor
              </button>
              <button type="button" class="link" @click="cancelarEscolha">Cancelar</button>
            </div>
          </div>
          <p v-if="erroResposta" class="ajuda ajuda--erro" role="alert">{{ erroResposta }}</p>
          <textarea
            id="resposta"
            ref="campoTexto"
            v-model="texto"
            rows="1"
            :maxlength="arquivo ? TAMANHO_MAXIMO_LEGENDA : 4096"
            :disabled="!!motivoComposer || (!!arquivo && !aceitaLegenda)"
            :placeholder="
              motivoComposer ||
              (arquivo
                ? aceitaLegenda
                  ? 'Legenda (opcional)'
                  : 'O WhatsApp não aceita legenda em áudio.'
                : 'Digite uma mensagem…')
            "
            :role="pelaBarra ? 'combobox' : undefined"
            :aria-expanded="pelaBarra ? 'true' : undefined"
            :aria-controls="pelaBarra ? 'seletor-respostas-lista' : undefined"
            :aria-activedescendant="pelaBarra ? seletorRespostas?.opcaoAtiva : undefined"
            @keydown="teclarNoCampo"
            @input="verificarBarra"
            @click="verificarBarra"
            @blur="fecharBarra"
          ></textarea>
          <div class="ferramentas">
            <button
              type="button"
              class="botao-icone sem-borda"
              aria-label="Anexar arquivo"
              title="Anexar arquivo (imagem, PDF ou áudio)"
              :disabled="!!motivoComposer || enviando"
              @click="seletor?.click()"
            >
              <IconeTraco nome="anexar" :tamanho="22" />
            </button>
            <button
              v-if="podeRespostas"
              type="button"
              class="ferramenta com-icone"
              :aria-expanded="!!respostas && !pelaBarra"
              :aria-describedby="arquivo ? 'respostas-indisponivel' : undefined"
              :disabled="respostasIndisponiveis"
              title="Inserir uma resposta rápida (ou digite / no campo)"
              @click="respostas && !pelaBarra ? fecharRespostas() : abrirRespostas()"
            >
              <IconeTraco nome="raio" :tamanho="22" />
              Respostas rápidas
            </button>
            <span v-if="podeRespostas && arquivo" id="respostas-indisponivel" class="ajuda">
              Respostas rápidas ficam indisponíveis com anexo selecionado.
            </span>
            <span class="espaco"></span>
            <button
              type="submit"
              class="botao-icone enviar"
              :aria-label="enviando ? 'Enviando…' : 'Enviar'"
              :title="enviando ? 'Enviando…' : 'Enviar'"
              :disabled="!!motivoComposer || (!arquivo && !texto.trim()) || enviando"
            >
              <IconeTraco nome="enviar" :tamanho="26" />
            </button>
          </div>
        </form>
        <AlertaMensagem v-if="anexoMut.isError.value" tipo="erro">
          {{ describeApiError(anexoMut.error.value) }} O arquivo foi mantido; tentar de novo não
          duplica a mensagem.
        </AlertaMensagem>
        <AlertaMensagem v-if="responderMut.isError.value" tipo="erro">
          {{ describeApiError(responderMut.error.value) }} O texto foi mantido; tentar de novo não
          duplica a mensagem.
        </AlertaMensagem>
      </div>
    </template>
  </section>
</template>

<style scoped>
/* AT-03/AT-04/AT-05: cartão da conversa em toda a altura; só a linha do tempo rola. */
.conversa {
  display: flex;
  flex-direction: column;
  min-width: 0;
  margin: 0;
  padding: 0;
  overflow: hidden;
}

.estado-conversa {
  padding: 24px;
}

.faixa {
  margin: 8px 16px 0;
}

/* Cabeçalho (112 px): avatar de 64 px, nome em link, telefone; ações à direita; meta embaixo. */
.topo {
  display: grid;
  grid-template-columns: 64px minmax(11rem, 1fr) auto;
  align-items: center;
  column-gap: 14px;
  flex-shrink: 0;
  padding: 10px 16px 13px 9px;
  border-bottom: 1px solid var(--cor-borda);
}

.avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 64px;
  height: 64px;
  border-radius: 999px;
  background: var(--cor-destaque-suave);
  color: var(--cor-destaque-forte);
  font-size: 22px;
  font-weight: 500;
}

.identificacao {
  min-width: 0;
}

.topo h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 500;
  line-height: 1.3;
  overflow-wrap: anywhere;
}

.topo h2,
.telefone {
  display: flex;
  align-items: center;
  gap: 8px;
}

.topo h2 .icone,
.telefone .icone {
  flex-shrink: 0;
}

.topo h2 a {
  color: var(--cor-destaque);
  text-underline-offset: 3px;
}

.telefone {
  margin: 2px 0 0;
  color: var(--cor-texto-2);
  font-size: 17px;
}

.telefone .icone {
  color: var(--cor-destaque);
}

.meta {
  grid-column: 1 / -1;
  margin: 8px 0 0 7px;
  color: var(--cor-texto-2);
  font-size: 15px;
}

.topo .acoes {
  justify-content: flex-end;
  flex-wrap: nowrap;
  gap: 8px;
}

.topo .acoes button:not(.botao-icone) {
  height: 46px;
  padding: 0 16px;
  white-space: nowrap;
  border-radius: var(--raio-controle);
  font-size: 17px;
}

.topo .acoes .botao-icone {
  width: 44px;
  height: 44px;
}

/* Abrir o painel e fechar a conversa não aparecem no frame: ficam discretos, sem borda. */
.topo .acoes .botao-icone.sem-borda,
.topo .acoes .botao-icone.painel {
  width: 36px;
  border-color: transparent;
}

/* AT-03: assumir é a ação principal do estado aguardando. */
.topo .acoes button.assumir {
  height: 46px;
  min-width: 250px;
  justify-content: center;
  gap: 12px;
  border-color: var(--cor-destaque);
  background: linear-gradient(180deg, #24a94c 0%, var(--cor-destaque) 100%);
  font-size: 20px;
  font-weight: 500;
  box-shadow: 0 2px 6px rgb(31 157 69 / 25%);
}

.transferencia {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(12rem, 1fr));
  gap: 0.75rem;
  align-items: end;
  padding: 12px 16px 0;
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
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px 16px 16px;
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

/* Separador de dia (pílula centralizada, como os eventos). */
.dia {
  align-self: center;
  margin: 4px 0;
  padding: 8px 20px;
  border-radius: 8px;
  background: var(--cor-superficie-2);
  color: var(--cor-texto-2);
  font-size: 15px;
}

/* Área de resposta (AT-04): aba "Mensagem", campo de 48 px e ferramentas abaixo. */
.area-resposta {
  flex-shrink: 0;
  border-top: 1px solid var(--cor-borda);
  padding: 0 16px 11px;
}

.aba {
  display: inline-block;
  margin: 0 0 12px 12px;
  padding: 13px 2px 9px;
  border-bottom: 3px solid var(--cor-destaque);
  color: var(--cor-destaque);
  font-size: 17px;
  font-weight: 500;
}

.selecionado {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 0.25rem 0.75rem;
  margin-bottom: 8px;
  padding: 0.4rem 0.6rem;
  border: 1px dashed var(--cor-borda);
  border-radius: 8px;
}

.selecionado .nome {
  font-weight: 600;
  overflow-wrap: anywhere;
}

/* AT-03: faixa no lugar da resposta quando ela não é possível. */
.aviso-compositor {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  gap: 10px;
  min-height: 56px;
  margin: 0;
  padding: 8px 16px;
  box-sizing: border-box;
  background: var(--cor-destaque-suave);
  color: var(--cor-destaque-forte);
  font-size: 17px;
  text-align: center;
}

.aviso-compositor .icone {
  flex-shrink: 0;
  color: var(--cor-destaque);
}

.composer {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.composer textarea {
  width: 100%;
  min-height: 48px;
  max-height: 10rem;
  box-sizing: border-box;
  padding: 12px 16px;
  border-color: var(--cor-borda);
  border-radius: var(--raio-controle);
  font-size: 17px;
  line-height: 1.35;
  resize: vertical;
}

.ferramentas {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 12px;
}

.ferramentas .espaco {
  flex: 1;
}

.ferramentas > .botao-icone:first-child {
  width: 36px;
  height: 36px;
}

button.ferramenta {
  height: 36px;
  padding: 0 6px;
  border: none;
  background: none;
  color: var(--cor-texto-2);
  font-size: 15px;
  gap: 10px;
}

button.ferramenta .icone {
  color: var(--cor-texto);
}

button.ferramenta:hover:not(:disabled) {
  color: var(--cor-texto);
}

button.botao-icone.enviar {
  width: 60px;
  height: 60px;
  border-color: var(--cor-destaque);
  background: var(--cor-destaque);
  color: #fff;
}

button.botao-icone.enviar:hover:not(:disabled) {
  background: var(--cor-destaque-forte);
}

.escolha {
  padding: 0.6rem 0.75rem;
  border: 1px solid var(--cor-borda);
  border-radius: 8px;
}

.escolha p {
  margin: 0 0 0.5rem;
}

@media (max-width: 60rem) {
  .topo {
    grid-template-columns: 48px minmax(0, 1fr);
  }

  .avatar {
    width: 48px;
    height: 48px;
    font-size: 18px;
  }

  .topo .acoes {
    grid-column: 1 / -1;
    justify-content: flex-start;
    flex-wrap: wrap;
    margin-top: 8px;
  }

  .topo .acoes button.assumir {
    min-width: 0;
  }

  .linha {
    min-height: 24rem;
  }
}
</style>
