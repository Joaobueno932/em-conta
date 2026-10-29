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

export function listarFila(http: HttpClient, visao: Visao, busca: string, pagina: number) {
  const params = new URLSearchParams({ visao, pagina: String(pagina), tamanho: '20' })
  if (busca.trim()) params.set('busca', busca.trim())
  return http.get<Pagina>(`/v1/atendimento/atendimentos?${params}`)
}

export function obterDetalhe(http: HttpClient, id: number) {
  return http.get<Detalhe>(`/v1/atendimento/atendimentos/${id}`)
}

export function linhaDoTempo(http: HttpClient, id: number, somenteEste = false) {
  return http.get<Item[]>(
    `/v1/atendimento/atendimentos/${id}/linha-do-tempo?somenteEste=${somenteEste}`,
  )
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
