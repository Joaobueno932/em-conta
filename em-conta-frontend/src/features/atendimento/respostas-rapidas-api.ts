import type { components } from '@/shared/api/contract/schema'
import type { HttpClient } from '@/shared/api/http-client'

/**
 * Respostas rápidas: textos internos do catálogo da equipe. Só preenchem o campo de resposta para o
 * atendente revisar e enviar pelo fluxo normal — não são templates da Meta, não enviam nada sozinhos e
 * não liberam envio fora da janela de 24 horas.
 */
export type RespostaRapida = components['schemas']['RespostaRapida']
export type PaginaRespostasRapidas = components['schemas']['PaginaRespostasRapidas']
export type SituacaoFiltro = 'ATIVAS' | 'INATIVAS' | 'TODAS'

export const TAMANHO_MAXIMO_TITULO = 80
export const TAMANHO_MAXIMO_ATALHO = 30
/** Itens no seletor da conversa (busca no servidor, resultados limitados). */
export const TAMANHO_SELETOR = 8

const BASE = '/v1/atendimento/respostas-rapidas'

export const chavesRespostas = {
  todas: ['respostas-rapidas'] as const,
  lista: (busca: string, situacao: SituacaoFiltro, pagina: number) =>
    ['respostas-rapidas', 'lista', busca, situacao, pagina] as const,
  seletor: (busca: string) => ['respostas-rapidas', 'seletor', busca] as const,
}

export function listarRespostas(
  http: HttpClient,
  opcoes: {
    busca?: string
    situacao?: SituacaoFiltro
    pagina?: number
    tamanho?: number
    noConteudo?: boolean
  },
) {
  const params = new URLSearchParams({
    situacao: opcoes.situacao ?? 'TODAS',
    pagina: String(opcoes.pagina ?? 0),
    tamanho: String(opcoes.tamanho ?? 20),
    noConteudo: String(opcoes.noConteudo ?? true),
  })
  if (opcoes.busca?.trim()) params.set('busca', opcoes.busca.trim())
  return http.get<PaginaRespostasRapidas>(`${BASE}?${params}`)
}

export function obterResposta(http: HttpClient, id: number) {
  return http.get<RespostaRapida>(`${BASE}/${id}`)
}

export interface DadosResposta {
  titulo: string
  atalho: string
  conteudo: string
}

export function criarResposta(http: HttpClient, dados: DadosResposta) {
  return http.request<RespostaRapida>('POST', BASE, { body: dados })
}

export function editarResposta(http: HttpClient, id: number, versao: number, dados: DadosResposta) {
  return http.request<RespostaRapida>('PUT', `${BASE}/${id}`, { body: { ...dados, versao } })
}

export function alterarSituacaoResposta(
  http: HttpClient,
  id: number,
  versao: number,
  ativa: boolean,
) {
  return http.request<RespostaRapida>(
    'POST',
    `${BASE}/${id}/${ativa ? 'ativacao' : 'desativacao'}`,
    { body: { versao } },
  )
}

/** Mesma normalização do servidor (só para exibir/prevalidar; o servidor decide). */
export function normalizarAtalho(atalho: string) {
  return atalho.trim().replace(/^\/+/, '').toLowerCase()
}

export const ATALHO_VALIDO = /^[a-z0-9][a-z0-9-]{1,29}$/
