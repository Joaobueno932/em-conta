import type { components } from '@/shared/api/contract/schema'
import type { HttpClient } from '@/shared/api/http-client'

export type Resumo = components['schemas']['AtendimentoResumo']
export type Pagina = components['schemas']['PaginaAtendimentos']
export type Detalhe = components['schemas']['AtendimentoDetalhe']
export type Item = components['schemas']['ItemLinhaDoTempo']
export type AssociadoAtendimento = components['schemas']['AssociadoAtendimento']
export type UsuarioAtendimento = components['schemas']['UsuarioAtendimento']
export type Visao = 'AGUARDANDO' | 'EM_ANDAMENTO' | 'MEUS' | 'ENCERRADOS'

/**
 * Atualização sem recarregar a página. A conversa aberta recebe avisos de mudança por SSE
 * (`use-eventos-conversa.ts`) e relê na hora; com os avisos chegando, a consulta periódica fica em
 * {@link INTERVALO_SEGURANCA_MS}, só como rede de segurança. Sem avisos (conexão fora) e nas demais
 * telas (fila), a consulta é a cada {@link INTERVALO_MS} enquanto a aba está visível; o 401 leva ao
 * login.
 */
export const INTERVALO_MS = 5000
export const INTERVALO_SEGURANCA_MS = 30_000

/** Endereço dos avisos de mudança da conversa (SSE, mesma origem e sessão). */
export const eventosConversa = (atendimento: number) =>
  `/api/v1/atendimento/atendimentos/${atendimento}/eventos`

export const chavesAtendimento = {
  todos: ['atendimento'] as const,
  fila: (visao: Visao, busca: string, pagina: number) =>
    ['atendimento', 'fila', visao, busca, pagina] as const,
  detalhe: (id: number) => ['atendimento', 'detalhe', id] as const,
  linha: (id: number, somenteEste: boolean) => ['atendimento', 'linha', id, somenteEste] as const,
  midia: (id: number, somenteEste: boolean, categoria: string) =>
    ['atendimento', 'midia', id, somenteEste, categoria] as const,
  historico: (busca: string, de: string, ate: string, pagina: number) =>
    ['atendimento', 'historico', busca, de, ate, pagina] as const,
  canal: ['atendimento', 'canal'] as const,
  presenca: ['atendimento', 'presenca'] as const,
  responsaveis: ['atendimento', 'responsaveis'] as const,
  sugeridos: (id: number) => ['atendimento', 'sugeridos', id] as const,
  buscaAssociados: (busca: string) => ['atendimento', 'associados', busca] as const,
}

export const ROTULO_SITUACAO_MENSAGEM: Record<string, string> = {
  RECEBIDA: 'Recebida',
  PENDENTE: 'Aguardando envio',
  ENVIANDO: 'Enviando',
  // Aceite é só da requisição; envio, entrega e leitura chegam depois pelo webhook.
  ACEITA: 'Aceita pela Meta, aguardando confirmação de envio',
  ENVIADA: 'Enviada',
  ENTREGUE: 'Entregue',
  LIDA: 'Lida',
  FALHA: 'Falha no envio',
  DESCONHECIDA: 'Envio não confirmado',
  JANELA_EXPIRADA: 'Não enviada',
}

/** Explicação de cada situação sem envio confirmado, mostrada no detalhe do indicador. */
export const DETALHE_SITUACAO_MENSAGEM: Record<string, string> = {
  DESCONHECIDA: 'Resultado desconhecido: confirme com o cliente antes de reenviar.',
  JANELA_EXPIRADA:
    'Não enviada: a janela de 24 horas expirou antes do envio. Nada foi enviado ao WhatsApp; o contato precisa mandar uma nova mensagem.',
}

export const ROTULO_EVENTO: Record<string, string> = {
  ATENDIMENTO_INICIADO: 'Novo atendimento na fila',
  ASSUMIDO: 'assumiu o atendimento',
  TRANSFERIDO: 'transferiu o atendimento',
  DEVOLVIDO_A_FILA: 'devolveu o atendimento à fila',
  ENCERRADO: 'encerrou o atendimento',
  ASSOCIADO_VINCULADO: 'vinculou o associado',
  ASSOCIADO_ALTERADO: 'alterou o associado vinculado',
  ASSOCIADO_DESVINCULADO: 'removeu o vínculo com o associado',
}

/** Tipos de mensagem do WhatsApp sem suporte nesta versão, pelo nome em português. */
export const ROTULO_TIPO_EXTERNO: Record<string, string> = {
  sticker: 'figurinha',
  location: 'localização',
  contacts: 'contato',
  reaction: 'reação',
  interactive: 'resposta interativa',
  button: 'botão',
  order: 'pedido',
  unsupported: 'não reconhecido pelo WhatsApp',
}

export function listarFila(http: HttpClient, visao: Visao, busca: string, pagina: number) {
  const params = new URLSearchParams({ visao, pagina: String(pagina), tamanho: '20' })
  if (busca.trim()) params.set('busca', busca.trim())
  return http.get<Pagina>(`/v1/atendimento/atendimentos?${params}`)
}

export function obterDetalhe(http: HttpClient, id: number) {
  return http.get<Detalhe>(`/v1/atendimento/atendimentos/${id}`)
}

export type PaginaLinhaDoTempo = components['schemas']['PaginaLinhaDoTempo']

/** Itens por página da linha do tempo (o servidor aceita até 100). */
export const TAMANHO_LINHA = 50

/**
 * Uma página da linha do tempo, em ordem cronológica: as mais recentes ou, com `antes`, as
 * imediatamente anteriores a esse cursor.
 */
export function linhaDoTempo(
  http: HttpClient,
  id: number,
  opcoes: { somenteEste?: boolean; antes?: string } = {},
) {
  const params = new URLSearchParams({
    somenteEste: String(opcoes.somenteEste ?? false),
    tamanho: String(TAMANHO_LINHA),
  })
  if (opcoes.antes) params.set('antes', opcoes.antes)
  return http.get<PaginaLinhaDoTempo>(`/v1/atendimento/atendimentos/${id}/linha-do-tempo?${params}`)
}

/**
 * Compara cursores (`<micros>.<ordem>.<id>`): a ordem da linha do tempo, igual à do servidor.
 * Microssegundos desde 1970 cabem com folga em `number` (abaixo de 2^53).
 */
export function compararCursor(a: string, b: string): number {
  const [ma = 0, oa = 0, ia = 0] = a.split('.').map(Number)
  const [mb = 0, ob = 0, ib = 0] = b.split('.').map(Number)
  return ma - mb || oa - ob || ia - ib
}

export type Anexo = components['schemas']['AnexoAtendimento']
export type ItemMidia = components['schemas']['ItemMidia']
export type PaginaMidia = components['schemas']['PaginaMidia']
export type SituacaoMensagem = components['schemas']['SituacaoMensagem']
export type CategoriaAnexo = 'IMAGEM' | 'DOCUMENTO' | 'AUDIO' | 'VIDEO'

/** Máximo de mensagens por consulta de situações (igual ao servidor). */
export const MAXIMO_SITUACOES = 100

/**
 * Situações atuais de mensagens já carregadas que estão fora da página recente (para atualizar, por
 * exemplo, "Aceita" → "Entregue" sem recarregar o histórico).
 */
export function situacoes(http: HttpClient, id: number, ids: number[], somenteEste = false) {
  const params = new URLSearchParams({ somenteEste: String(somenteEste), ids: ids.join(',') })
  return http.get<SituacaoMensagem[]>(
    `/v1/atendimento/atendimentos/${id}/linha-do-tempo/situacoes?${params}`,
  )
}

export function midia(
  http: HttpClient,
  id: number,
  opcoes: { somenteEste?: boolean; categoria?: CategoriaAnexo; antes?: number } = {},
) {
  const params = new URLSearchParams({
    somenteEste: String(opcoes.somenteEste ?? false),
    tamanho: '20',
  })
  if (opcoes.categoria) params.set('categoria', opcoes.categoria)
  if (opcoes.antes) params.set('antes', String(opcoes.antes))
  return http.get<PaginaMidia>(`/v1/atendimento/atendimentos/${id}/midia?${params}`)
}

/**
 * Endereço do conteúdo de um anexo (rota autenticada pela sessão; nunca o armazenamento direto).
 * `somenteEste` restringe ao ciclo consultado (histórico).
 */
export function conteudoAnexo(
  atendimento: number,
  anexo: number,
  opcoes: { somenteEste?: boolean; download?: boolean } = {},
) {
  const params = new URLSearchParams()
  if (opcoes.somenteEste) params.set('somenteEste', 'true')
  if (opcoes.download) params.set('download', 'true')
  const query = params.size ? `?${params}` : ''
  return `/api/v1/atendimento/atendimentos/${atendimento}/anexos/${anexo}/conteudo${query}`
}

export function enviarAnexo(
  http: HttpClient,
  id: number,
  arquivo: File,
  legenda: string,
  chaveIdempotencia: string,
  mime: string = arquivo.type,
) {
  const dados = new FormData()
  // Tipo deduzido pela extensão quando o sistema não informa nenhum (ver validarArquivo).
  dados.append(
    'arquivo',
    arquivo.type === mime ? arquivo : new File([arquivo], arquivo.name, { type: mime }),
  )
  if (legenda) dados.append('legenda', legenda)
  dados.append('chaveIdempotencia', chaveIdempotencia)
  return http.request<components['schemas']['RespostaRegistrada']>(
    'POST',
    `/v1/atendimento/atendimentos/${id}/anexos`,
    { body: dados },
  )
}

export function novaTentativaAnexo(http: HttpClient, id: number, anexo: number) {
  return http.request<void>(
    'POST',
    `/v1/atendimento/atendimentos/${id}/anexos/${anexo}/nova-tentativa`,
  )
}

export const MIME_XLSX = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'

/**
 * Formatos aceitos (a mesma lista do servidor), com o nome mostrado e a sigla do cartão. O servidor
 * confere de novo MIME, extensão, conteúdo e tamanho; a planilha também pelo pacote e o vídeo enviado
 * pelos codecs (H.264 e AAC, as regras da Meta).
 */
export const FORMATOS_ANEXO: {
  mime: string
  categoria: CategoriaAnexo
  extensoes: string[]
  rotulo: string
  sigla: string
}[] = [
  {
    mime: 'image/jpeg',
    categoria: 'IMAGEM',
    extensoes: ['jpg', 'jpeg'],
    rotulo: 'Imagem JPEG',
    sigla: 'JPG',
  },
  {
    mime: 'image/png',
    categoria: 'IMAGEM',
    extensoes: ['png'],
    rotulo: 'Imagem PNG',
    sigla: 'PNG',
  },
  {
    mime: 'application/pdf',
    categoria: 'DOCUMENTO',
    extensoes: ['pdf'],
    rotulo: 'PDF',
    sigla: 'PDF',
  },
  {
    mime: MIME_XLSX,
    categoria: 'DOCUMENTO',
    extensoes: ['xlsx'],
    rotulo: 'Planilha XLSX',
    sigla: 'XLSX',
  },
  { mime: 'video/mp4', categoria: 'VIDEO', extensoes: ['mp4'], rotulo: 'Vídeo MP4', sigla: 'MP4' },
  { mime: 'video/3gpp', categoria: 'VIDEO', extensoes: ['3gp'], rotulo: 'Vídeo 3GP', sigla: '3GP' },
  {
    mime: 'audio/ogg',
    categoria: 'AUDIO',
    extensoes: ['ogg', 'opus'],
    rotulo: 'Áudio OGG',
    sigla: 'OGG',
  },
  { mime: 'audio/mpeg', categoria: 'AUDIO', extensoes: ['mp3'], rotulo: 'Áudio MP3', sigla: 'MP3' },
  { mime: 'audio/mp4', categoria: 'AUDIO', extensoes: ['m4a'], rotulo: 'Áudio M4A', sigla: 'M4A' },
  { mime: 'audio/aac', categoria: 'AUDIO', extensoes: ['aac'], rotulo: 'Áudio AAC', sigla: 'AAC' },
  { mime: 'audio/amr', categoria: 'AUDIO', extensoes: ['amr'], rotulo: 'Áudio AMR', sigla: 'AMR' },
]

/** Formatos que chegam do WhatsApp mas não são aceitos: identificados pelo nome real, não por "PDF". */
const FORMATOS_NAO_ACEITOS: Record<string, { rotulo: string; sigla: string }> = {
  'application/vnd.ms-excel': { rotulo: 'Planilha XLS', sigla: 'XLS' },
  'application/msword': { rotulo: 'Documento DOC', sigla: 'DOC' },
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document': {
    rotulo: 'Documento DOCX',
    sigla: 'DOCX',
  },
  'application/vnd.ms-powerpoint': { rotulo: 'Apresentação PPT', sigla: 'PPT' },
  'application/vnd.openxmlformats-officedocument.presentationml.presentation': {
    rotulo: 'Apresentação PPTX',
    sigla: 'PPTX',
  },
  'text/plain': { rotulo: 'Texto TXT', sigla: 'TXT' },
  'image/webp': { rotulo: 'Imagem WebP', sigla: 'WEBP' },
}

export const LIMITE_ANEXO: Record<CategoriaAnexo, number> = {
  IMAGEM: 5 * 1024 * 1024,
  DOCUMENTO: 16 * 1024 * 1024,
  AUDIO: 16 * 1024 * 1024,
  VIDEO: 16 * 1024 * 1024,
}

export const TAMANHO_MAXIMO_LEGENDA = 1024

export const ACEITOS = FORMATOS_ANEXO.flatMap((f) => [
  f.mime,
  ...f.extensoes.map((e) => `.${e}`),
]).join(',')

export const ROTULO_CATEGORIA: Record<CategoriaAnexo, string> = {
  IMAGEM: 'Imagem',
  DOCUMENTO: 'Documento',
  AUDIO: 'Áudio',
  VIDEO: 'Vídeo',
}

const extensaoDe = (nome?: string) =>
  nome && nome.includes('.') ? nome.split('.').pop()!.toLowerCase() : ''

/**
 * Identificação do arquivo pelo formato real (MIME; a extensão do nome só quando o MIME falta): "PDF",
 * "Planilha XLSX", "Vídeo MP4", "Documento DOCX"… Sem nenhum dos dois, o nome genérico da categoria.
 */
export function formatoDoAnexo(
  mime: string | undefined,
  categoria: string,
  nome?: string,
): { rotulo: string; sigla: string } {
  const base = mime?.split(';')[0]?.trim().toLowerCase()
  const conhecido =
    FORMATOS_ANEXO.find((f) => f.mime === base) ??
    (base ? undefined : FORMATOS_ANEXO.find((f) => f.extensoes.includes(extensaoDe(nome))))
  if (conhecido) return conhecido
  if (base && FORMATOS_NAO_ACEITOS[base]) return FORMATOS_NAO_ACEITOS[base]
  const rotulo = ROTULO_CATEGORIA[categoria as CategoriaAnexo] ?? 'Arquivo'
  return {
    rotulo,
    sigla: extensaoDe(nome).toUpperCase().slice(0, 4) || rotulo.slice(0, 3).toUpperCase(),
  }
}

/**
 * Validação antecipada (o servidor confere de novo, inclusive o conteúdo). Sem tipo informado pelo
 * sistema (comum em .3gp e .xlsx no Windows), o formato vem da extensão e o envio leva esse tipo; o
 * servidor confere o conteúdo do mesmo jeito.
 */
export function validarArquivo(
  arquivo: File,
):
  | { categoria: CategoriaAnexo; mime: string; erro?: undefined }
  | { erro: string; categoria?: undefined; mime?: undefined } {
  const extensao = extensaoDe(arquivo.name)
  const semTipo = !arquivo.type || arquivo.type === 'application/octet-stream'
  const formato = semTipo
    ? FORMATOS_ANEXO.find((f) => f.extensoes.includes(extensao))
    : FORMATOS_ANEXO.find((f) => f.mime === arquivo.type)
  if (!formato)
    return {
      erro: 'Tipo não suportado. Envie JPEG, PNG, PDF, planilha XLSX, vídeo MP4 ou 3GP, ou áudio (AAC, AMR, MP3, M4A, OGG).',
    }
  if (!formato.extensoes.includes(extensao))
    return { erro: `A extensão “.${extensao}” não corresponde ao tipo do arquivo.` }
  if (arquivo.size === 0) return { erro: 'O arquivo está vazio.' }
  if (arquivo.size > LIMITE_ANEXO[formato.categoria])
    return {
      erro: `Arquivo acima do limite de ${formatarTamanho(LIMITE_ANEXO[formato.categoria])} para ${ROTULO_CATEGORIA[formato.categoria].toLowerCase()}.`,
    }
  return { categoria: formato.categoria, mime: formato.mime }
}

/** O navegador abre o conteúdo (imagem, PDF, áudio e vídeo); planilhas só por download. */
export const exibivelNoNavegador = (mime?: string) =>
  !!mime && (/^(image|audio|video)\//.test(mime) || mime === 'application/pdf')

export function formatarTamanho(bytes: number | undefined) {
  if (bytes === undefined) return ''
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1).replace('.', ',')} MB`
  return `${Math.max(1, Math.round(bytes / 1024))} KB`
}

export function historico(
  http: HttpClient,
  busca: string,
  de: string,
  ate: string,
  pagina: number,
) {
  const params = new URLSearchParams({ pagina: String(pagina), tamanho: '20' })
  if (busca.trim()) params.set('busca', busca.trim())
  if (de) params.set('de', de)
  if (ate) params.set('ate', ate)
  return http.get<Pagina>(`/v1/atendimento/historico?${params}`)
}

export function estadoCanal(http: HttpClient) {
  return http.get<components['schemas']['CanalAtendimento']>('/v1/atendimento/canal')
}

export function obterPresenca(http: HttpClient) {
  return http.get<components['schemas']['PresencaAtendente']>('/v1/atendimento/presenca')
}

export function definirPresenca(http: HttpClient, disponivel: boolean) {
  return http.request<components['schemas']['PresencaAtendente']>(
    'PUT',
    '/v1/atendimento/presenca',
    {
      body: { disponivel },
    },
  )
}

export function assumir(http: HttpClient, id: number) {
  return http.request<void>('POST', `/v1/atendimento/atendimentos/${id}/assuncao`)
}

export function transferir(
  http: HttpClient,
  id: number,
  dados: { versao: number; destinoId?: number; motivo?: string },
) {
  return http.request<void>('POST', `/v1/atendimento/atendimentos/${id}/transferencia`, {
    body: dados,
  })
}

export function encerrar(http: HttpClient, id: number, versao: number) {
  return http.request<void>('POST', `/v1/atendimento/atendimentos/${id}/encerramento`, {
    body: { versao },
  })
}

export function responder(http: HttpClient, id: number, texto: string, chaveIdempotencia: string) {
  return http.request<components['schemas']['RespostaRegistrada']>(
    'POST',
    `/v1/atendimento/atendimentos/${id}/mensagens`,
    { body: { texto, chaveIdempotencia } },
  )
}

/**
 * "Digitando…" no WhatsApp do contato (o servidor confere responsável e janela e limita a um pedido a
 * cada 20 s). A Cloud API exige marcar como lida a última mensagem recebida junto com o indicador.
 */
export function indicarDigitando(http: HttpClient, id: number) {
  return http.request<void>('POST', `/v1/atendimento/atendimentos/${id}/digitando`)
}

/** Intervalo entre pedidos de "digitando…" enquanto a pessoa escreve (o indicador dura até 25 s). */
export const INTERVALO_DIGITANDO_MS = 20_000

export function vincularAssociado(
  http: HttpClient,
  id: number,
  dados: { versaoConversa: number; associadoId?: number; motivo?: string },
) {
  return http.request<void>('PUT', `/v1/atendimento/atendimentos/${id}/associado`, { body: dados })
}

export function sugeridos(http: HttpClient, id: number) {
  return http.get<AssociadoAtendimento[]>(`/v1/atendimento/atendimentos/${id}/associados-sugeridos`)
}

export function buscarAssociados(http: HttpClient, busca: string) {
  return http.get<AssociadoAtendimento[]>(
    `/v1/atendimento/associados?${new URLSearchParams({ busca: busca.trim() })}`,
  )
}

export function responsaveis(http: HttpClient) {
  return http.get<UsuarioAtendimento[]>('/v1/atendimento/responsaveis')
}

/** Nome para exibir: perfil do WhatsApp ou o número. */
export function nomeContato(r: Pick<Resumo, 'contatoNome' | 'contatoNumero'>): string {
  return r.contatoNome || `+${r.contatoNumero}`
}

export function iniciais(nome: string): string {
  return (
    nome
      .replace(/[^\p{L}\s]/gu, '')
      .trim()
      .split(/\s+/)
      .slice(0, 2)
      .map((p) => p[0]?.toUpperCase() ?? '')
      .join('') || '#'
  )
}

const hora = new Intl.DateTimeFormat('pt-BR', { hour: '2-digit', minute: '2-digit' })
const dataHora = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'short' })
const data = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short' })

export const formatarHora = (i: string) => hora.format(new Date(i))
export const formatarDataHora = (i: string) => dataHora.format(new Date(i))
export const formatarData = (i: string) => data.format(new Date(i))

const dia = new Intl.DateTimeFormat('pt-BR', { day: 'numeric', month: 'long' })
const diaComAno = new Intl.DateTimeFormat('pt-BR', {
  day: 'numeric',
  month: 'long',
  year: 'numeric',
})

/** Rótulo do separador de dia da linha do tempo: "Hoje, 13 de outubro", "Ontem, …" ou a data. */
export function rotuloDia(instante: string, agora = new Date()) {
  const data = new Date(instante)
  const ontem = new Date(agora.getFullYear(), agora.getMonth(), agora.getDate() - 1)
  if (data.toDateString() === agora.toDateString()) return `Hoje, ${dia.format(data)}`
  if (data.toDateString() === ontem.toDateString()) return `Ontem, ${dia.format(data)}`
  return data.getFullYear() === agora.getFullYear() ? dia.format(data) : diaComAno.format(data)
}

/** O item na posição `indice` é o primeiro do seu dia (recebe separador antes)? */
export function comecaDia(itens: { ocorridoEm: string }[], indice: number) {
  const atual = itens[indice]
  if (!atual) return false
  return (
    indice === 0 ||
    new Date(itens[indice - 1]!.ocorridoEm).toDateString() !==
      new Date(atual.ocorridoEm).toDateString()
  )
}
