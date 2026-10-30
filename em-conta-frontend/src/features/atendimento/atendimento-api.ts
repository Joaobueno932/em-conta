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
 * Atualização sem recarregar a página: consulta periódica (polling) a cada {@link INTERVALO_MS}
 * enquanto a aba está visível. Funciona com várias réplicas e com sessão revogada (o 401 leva ao
 * login); não é SSE.
 */
export const INTERVALO_MS = 5000

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
  ACEITA: 'Aceita pelo WhatsApp',
  ENVIADA: 'Enviada',
  ENTREGUE: 'Entregue',
  LIDA: 'Lida',
  FALHA: 'Falha no envio',
  DESCONHECIDA: 'Resultado desconhecido — confirme com o cliente antes de reenviar',
  JANELA_EXPIRADA:
    'Não enviada: a janela de 24 horas expirou antes do envio. Nada foi enviado ao WhatsApp; o contato precisa mandar uma nova mensagem',
}

/** Situações de saída que terminaram sem envio confirmado pelo canal. */
export const SITUACOES_SEM_ENVIO = ['FALHA', 'JANELA_EXPIRADA']

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
export type CategoriaAnexo = 'IMAGEM' | 'DOCUMENTO' | 'AUDIO'

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
) {
  const dados = new FormData()
  dados.append('arquivo', arquivo)
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

/** Formatos aceitos (a mesma lista do servidor) e limites padrão da aplicação. */
export const FORMATOS_ANEXO: { mime: string; categoria: CategoriaAnexo; extensoes: string[] }[] = [
  { mime: 'image/jpeg', categoria: 'IMAGEM', extensoes: ['jpg', 'jpeg'] },
  { mime: 'image/png', categoria: 'IMAGEM', extensoes: ['png'] },
  { mime: 'application/pdf', categoria: 'DOCUMENTO', extensoes: ['pdf'] },
  { mime: 'audio/ogg', categoria: 'AUDIO', extensoes: ['ogg', 'opus'] },
  { mime: 'audio/mpeg', categoria: 'AUDIO', extensoes: ['mp3'] },
  { mime: 'audio/mp4', categoria: 'AUDIO', extensoes: ['m4a'] },
  { mime: 'audio/aac', categoria: 'AUDIO', extensoes: ['aac'] },
  { mime: 'audio/amr', categoria: 'AUDIO', extensoes: ['amr'] },
]

export const LIMITE_ANEXO: Record<CategoriaAnexo, number> = {
  IMAGEM: 5 * 1024 * 1024,
  DOCUMENTO: 16 * 1024 * 1024,
  AUDIO: 16 * 1024 * 1024,
}

export const TAMANHO_MAXIMO_LEGENDA = 1024

export const ACEITOS = FORMATOS_ANEXO.flatMap((f) => [
  f.mime,
  ...f.extensoes.map((e) => `.${e}`),
]).join(',')

export const ROTULO_CATEGORIA: Record<CategoriaAnexo, string> = {
  IMAGEM: 'Imagem',
  DOCUMENTO: 'PDF',
  AUDIO: 'Áudio',
}

/** Validação antecipada (o servidor confere de novo, inclusive o conteúdo). */
export function validarArquivo(
  arquivo: File,
): { categoria: CategoriaAnexo; erro?: undefined } | { erro: string; categoria?: undefined } {
  const extensao = arquivo.name.includes('.') ? arquivo.name.split('.').pop()!.toLowerCase() : ''
  const formato = FORMATOS_ANEXO.find((f) => f.mime === arquivo.type)
  if (!formato)
    return { erro: 'Tipo não suportado. Envie JPEG, PNG, PDF ou áudio (AAC, AMR, MP3, M4A, OGG).' }
  if (!formato.extensoes.includes(extensao))
    return { erro: `A extensão “.${extensao}” não corresponde ao tipo do arquivo.` }
  if (arquivo.size === 0) return { erro: 'O arquivo está vazio.' }
  if (arquivo.size > LIMITE_ANEXO[formato.categoria])
    return {
      erro: `Arquivo acima do limite de ${formatarTamanho(LIMITE_ANEXO[formato.categoria])} para ${ROTULO_CATEGORIA[formato.categoria].toLowerCase()}.`,
    }
  return { categoria: formato.categoria }
}

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
