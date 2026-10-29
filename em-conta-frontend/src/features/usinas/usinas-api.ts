import type { components } from '@/shared/api/contract/schema'
import type { HttpClient } from '@/shared/api/http-client'

export type Usina = components['schemas']['Usina']
export type UsinaResumo = components['schemas']['UsinaResumo']
export type PaginaUsinas = components['schemas']['PaginaUsinas']
export type Fazenda = components['schemas']['Fazenda']
export type FazendaResumo = components['schemas']['FazendaResumo']
export type PaginaFazendas = components['schemas']['PaginaFazendas']
export type VinculoUsina = components['schemas']['VinculoUsina']
export type PaginaVinculos = components['schemas']['PaginaVinculosUsina']
export type UcCandidata = components['schemas']['UcCandidata']
export type RegistroHistoricoUsinas = components['schemas']['RegistroHistoricoUsinas']
export type PaginaHistoricoUsinas = components['schemas']['PaginaHistoricoUsinas']
export type DadosUsina = Omit<components['schemas']['UsinaRequest'], 'confirmarNomeRepetido'>
export type DadosFazenda = Omit<components['schemas']['FazendaRequest'], 'confirmarNomeRepetido'>
export type OrdenacaoUsinas = 'NOME' | 'CADASTRO_RECENTE' | 'UCS_VINCULADAS'
export type SituacaoVinculo = 'VIGENTES' | 'ENCERRADOS' | 'TODOS'

/**
 * Chaves do TanStack Query. Vínculos mudam a usina (contagens), a lista de usinas, os vínculos e o
 * histórico da usina e a ficha do associado (UCs com usina); só esses caches são invalidados.
 */
export const chavesUsinas = {
  listas: ['usinas', 'lista'] as const,
  lista: (filtro: FiltroUsinas) => ['usinas', 'lista', filtro] as const,
  usina: (id: number) => ['usinas', 'usina', id] as const,
  vinculos: (id: number) => ['usinas', 'vinculos', id] as const,
  historico: (id: number) => ['usinas', 'historico', id] as const,
  fazendas: ['usinas', 'fazendas'] as const,
  fazenda: (id: number) => ['usinas', 'fazendas', id] as const,
  candidatas: (busca: string) => ['usinas', 'candidatas', busca] as const,
  doAssociado: (associadoId: number) => ['associados', 'vinculos-usina', associadoId] as const,
}

export interface FiltroUsinas {
  busca: string
  uf: string
  ordenacao: OrdenacaoUsinas
  pagina: number
  tamanho: number
}

export const UFS = [
  'AC',
  'AL',
  'AP',
  'AM',
  'BA',
  'CE',
  'DF',
  'ES',
  'GO',
  'MA',
  'MT',
  'MS',
  'MG',
  'PA',
  'PB',
  'PR',
  'PE',
  'PI',
  'RJ',
  'RN',
  'RS',
  'RO',
  'RR',
  'SC',
  'SP',
  'SE',
  'TO',
] as const

export const ROTULO_ACAO_USINAS: Record<RegistroHistoricoUsinas['acao'], string> = {
  FAZENDA_CADASTRADA: 'Fazenda cadastrada',
  FAZENDA_ALTERADA: 'Fazenda atualizada',
  USINA_CADASTRADA: 'Usina cadastrada',
  USINA_ALTERADA: 'Informações da usina atualizadas',
  VINCULO_CRIADO: 'UC vinculada',
  VINCULO_ENCERRADO: 'Vínculo encerrado',
}

export const ROTULO_CAMPO_USINAS: Record<string, string> = {
  nome: 'Nome',
  fazenda: 'Fazenda',
  cep: 'CEP',
  endereco: 'Endereço',
  cidade: 'Cidade',
  uf: 'UF',
  observacoes: 'Observações',
  inicio: 'Início',
  fim: 'Fim',
}

export const AVISO_VINCULO_CADASTRAL =
  'Vínculo cadastral (RN-CLI-05/06): não comprova rateio efetivado, disponibilidade de energia, ativação nem aptidão de faturamento.'

export function listarUsinas(http: HttpClient, filtro: FiltroUsinas) {
  const params = new URLSearchParams({
    pagina: String(filtro.pagina),
    tamanho: String(filtro.tamanho),
    ordenacao: filtro.ordenacao,
  })
  if (filtro.busca.trim()) params.set('busca', filtro.busca.trim())
  if (filtro.uf) params.set('uf', filtro.uf)
  return http.get<PaginaUsinas>(`/v1/usinas?${params}`)
}

export function obterUsina(http: HttpClient, id: number) {
  return http.get<Usina>(`/v1/usinas/${id}`)
}

export function cadastrarUsina(
  http: HttpClient,
  dados: DadosUsina,
  confirmarNomeRepetido: boolean,
) {
  return http.request<Usina>('POST', '/v1/usinas', { body: { ...dados, confirmarNomeRepetido } })
}

export function editarUsina(
  http: HttpClient,
  id: number,
  dados: DadosUsina & { versao: number },
  confirmarNomeRepetido: boolean,
) {
  return http.request<Usina>('PUT', `/v1/usinas/${id}`, {
    body: { ...dados, confirmarNomeRepetido },
  })
}

export function listarFazendas(http: HttpClient, busca = '', pagina = 0, tamanho = 100) {
  const params = new URLSearchParams({ pagina: String(pagina), tamanho: String(tamanho) })
  if (busca.trim()) params.set('busca', busca.trim())
  return http.get<PaginaFazendas>(`/v1/fazendas?${params}`)
}

export function obterFazenda(http: HttpClient, id: number) {
  return http.get<Fazenda>(`/v1/fazendas/${id}`)
}

export function cadastrarFazenda(
  http: HttpClient,
  dados: DadosFazenda,
  confirmarNomeRepetido: boolean,
) {
  return http.request<Fazenda>('POST', '/v1/fazendas', {
    body: { ...dados, confirmarNomeRepetido },
  })
}

export function editarFazenda(
  http: HttpClient,
  id: number,
  dados: DadosFazenda & { versao: number },
  confirmarNomeRepetido: boolean,
) {
  return http.request<Fazenda>('PUT', `/v1/fazendas/${id}`, {
    body: { ...dados, confirmarNomeRepetido },
  })
}

export function listarVinculos(
  http: HttpClient,
  usinaId: number,
  situacao: SituacaoVinculo,
  pagina: number,
  tamanho = 20,
) {
  const params = new URLSearchParams({ situacao, pagina: String(pagina), tamanho: String(tamanho) })
  return http.get<PaginaVinculos>(`/v1/usinas/${usinaId}/vinculos?${params}`)
}

export function buscarUcsCandidatas(http: HttpClient, busca: string) {
  return http.get<UcCandidata[]>(
    `/v1/vinculos-usina/ucs-candidatas?${new URLSearchParams({ busca: busca.trim() })}`,
  )
}

export function vincularUc(http: HttpClient, usinaId: number, ucId: number, inicio: string) {
  return http.request<VinculoUsina>('POST', `/v1/usinas/${usinaId}/vinculos`, {
    body: { ucId, inicio },
  })
}

export function encerrarVinculo(
  http: HttpClient,
  usinaId: number,
  vinculoId: number,
  dados: { versao: number; fim: string; motivo: string },
) {
  return http.request<VinculoUsina>(
    'POST',
    `/v1/usinas/${usinaId}/vinculos/${vinculoId}/encerramento`,
    { body: dados },
  )
}

export function historicoUsina(http: HttpClient, usinaId: number, pagina: number, tamanho = 20) {
  return http.get<PaginaHistoricoUsinas>(
    `/v1/usinas/${usinaId}/historico?pagina=${pagina}&tamanho=${tamanho}`,
  )
}

export function vinculosDoAssociado(http: HttpClient, associadoId: number) {
  return http.get<VinculoUsina[]>(`/v1/associados/${associadoId}/vinculos-usina`)
}

/** Data de hoje no fuso do navegador, em `AAAA-MM-DD` (o servidor recusa datas futuras). */
export function hojeIso(): string {
  const agora = new Date()
  const mes = String(agora.getMonth() + 1).padStart(2, '0')
  const dia = String(agora.getDate()).padStart(2, '0')
  return `${agora.getFullYear()}-${mes}-${dia}`
}

/** `AAAA-MM-DD` → `DD/MM/AAAA`, sem conversão de fuso (data de negócio). */
export function formatarDataNegocio(data: string | undefined | null): string {
  if (!data) return '—'
  const [ano, mes, dia] = data.split('-')
  return `${dia}/${mes}/${ano}`
}

export function localizacao(cidade?: string | null, uf?: string | null): string {
  return [cidade, uf].filter(Boolean).join('/') || '—'
}
