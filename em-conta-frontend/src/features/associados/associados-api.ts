import type { components } from '@/shared/api/contract/schema'
import type { HttpClient } from '@/shared/api/http-client'

export type Associado = components['schemas']['Associado']
export type AssociadoResumo = components['schemas']['AssociadoResumo']
export type PaginaAssociados = components['schemas']['PaginaAssociados']
export type Contato = components['schemas']['Contato']
export type UnidadeConsumidora = components['schemas']['UnidadeConsumidora']
export type PaginaHistorico = components['schemas']['PaginaHistorico']
export type RegistroHistorico = components['schemas']['RegistroHistorico']
export type NovoAssociado = components['schemas']['NovoAssociadoRequest']
export type DadosContato = components['schemas']['ContatoRequest']
export type TipoPessoa = Associado['tipo']
export type Concessionaria = UnidadeConsumidora['concessionaria']
export type CategoriaHistorico = RegistroHistorico['categoria']
export type Ordenacao = 'NOME' | 'CADASTRO_RECENTE'

/**
 * Chaves do TanStack Query. Cada mutação invalida só o que muda: a ficha do associado, suas UCs,
 * seu histórico e as listagens (que mostram nome e quantidade de UCs).
 */
export const chaves = {
  todos: ['associados'] as const,
  listas: ['associados', 'lista'] as const,
  lista: (filtro: FiltroAssociados) => ['associados', 'lista', filtro] as const,
  ficha: (id: number) => ['associados', 'ficha', id] as const,
  ucs: (id: number) => ['associados', 'ucs', id] as const,
  uc: (id: number, ucId: number) => ['associados', 'ucs', id, ucId] as const,
  historico: (id: number) => ['associados', 'historico', id] as const,
}

export interface FiltroAssociados {
  busca: string
  tipo: TipoPessoa | ''
  ordenacao: Ordenacao
  pagina: number
  tamanho: number
}

export const TAMANHOS_PAGINA = [20, 50, 100] as const

export const ROTULO_TIPO: Record<TipoPessoa, string> = {
  PF: 'Pessoa física',
  PJ: 'Pessoa jurídica',
}

export const CONCESSIONARIAS: { valor: Concessionaria; rotulo: string }[] = [
  { valor: 'ENERGISA_MS', rotulo: 'Energisa MS' },
]

export const ROTULO_ACAO: Record<RegistroHistorico['acao'], string> = {
  ASSOCIADO_CADASTRADO: 'Cadastro criado',
  ASSOCIADO_ALTERADO: 'Dados cadastrais atualizados',
  CONTATO_ADICIONADO: 'Contato adicionado',
  CONTATO_ALTERADO: 'Contato atualizado',
  CONTATO_REMOVIDO: 'Contato removido',
  UC_CADASTRADA: 'Unidade consumidora cadastrada',
  UC_ALTERADA: 'Unidade consumidora atualizada',
}

export const ROTULO_CATEGORIA: Record<CategoriaHistorico, string> = {
  CADASTRO: 'Cadastro',
  CONTATOS: 'Contatos',
  UNIDADES_CONSUMIDORAS: 'Unidades consumidoras',
}

export const ROTULO_CAMPO: Record<string, string> = {
  tipo: 'Tipo',
  documento: 'Documento',
  nome: 'Nome',
  nomeFantasia: 'Nome fantasia',
  email: 'E-mail',
  telefone: 'Telefone',
  concessionaria: 'Concessionária',
  numero: 'Nº da UC',
  denominacao: 'Denominação',
  endereco: 'Endereço',
}

export function listarAssociados(http: HttpClient, filtro: FiltroAssociados) {
  const params = new URLSearchParams({
    pagina: String(filtro.pagina),
    tamanho: String(filtro.tamanho),
    ordenacao: filtro.ordenacao,
  })
  if (filtro.busca.trim()) params.set('busca', filtro.busca.trim())
  if (filtro.tipo) params.set('tipo', filtro.tipo)
  return http.get<PaginaAssociados>(`/v1/associados?${params}`)
}

export function obterAssociado(http: HttpClient, id: number) {
  return http.get<Associado>(`/v1/associados/${id}`)
}

export function cadastrarAssociado(http: HttpClient, dados: NovoAssociado) {
  return http.request<Associado>('POST', '/v1/associados', { body: dados })
}

export function editarAssociado(
  http: HttpClient,
  id: number,
  dados: { versao: number; nome: string; nomeFantasia?: string },
) {
  return http.request<Associado>('PUT', `/v1/associados/${id}`, { body: dados })
}

export function adicionarContato(http: HttpClient, id: number, dados: DadosContato) {
  return http.request<Contato>('POST', `/v1/associados/${id}/contatos`, { body: dados })
}

export function editarContato(
  http: HttpClient,
  id: number,
  contatoId: number,
  dados: DadosContato & { versao: number },
) {
  return http.request<Contato>('PUT', `/v1/associados/${id}/contatos/${contatoId}`, {
    body: dados,
  })
}

export function removerContato(http: HttpClient, id: number, contatoId: number, versao: number) {
  return http.request<void>('POST', `/v1/associados/${id}/contatos/${contatoId}/remocao`, {
    body: { versao },
  })
}

export function listarUcs(http: HttpClient, id: number) {
  return http.get<UnidadeConsumidora[]>(`/v1/associados/${id}/ucs`)
}

export function obterUc(http: HttpClient, id: number, ucId: number) {
  return http.get<UnidadeConsumidora>(`/v1/associados/${id}/ucs/${ucId}`)
}

export function cadastrarUc(
  http: HttpClient,
  id: number,
  dados: components['schemas']['NovaUcRequest'],
) {
  return http.request<UnidadeConsumidora>('POST', `/v1/associados/${id}/ucs`, { body: dados })
}

export function editarUc(
  http: HttpClient,
  id: number,
  ucId: number,
  dados: components['schemas']['EdicaoUcRequest'],
) {
  return http.request<UnidadeConsumidora>('PUT', `/v1/associados/${id}/ucs/${ucId}`, {
    body: dados,
  })
}

export function consultarHistorico(
  http: HttpClient,
  id: number,
  pagina: number,
  categoria: CategoriaHistorico | '',
  tamanho = 20,
) {
  const params = new URLSearchParams({ pagina: String(pagina), tamanho: String(tamanho) })
  if (categoria) params.set('categoria', categoria)
  return http.get<PaginaHistorico>(`/v1/associados/${id}/historico?${params}`)
}

/** Telefone guardado só com dígitos, exibido com DDI/DDD quando reconhecíveis. */
export function formatarTelefone(digitos: string | undefined | null): string {
  if (!digitos) return '—'
  const local = digitos.startsWith('55') && digitos.length >= 12 ? digitos.slice(2) : digitos
  const prefixo = local === digitos ? '' : '+55 '
  if (local.length === 11)
    return `${prefixo}(${local.slice(0, 2)}) ${local.slice(2, 7)}-${local.slice(7)}`
  if (local.length === 10)
    return `${prefixo}(${local.slice(0, 2)}) ${local.slice(2, 6)}-${local.slice(6)}`
  return digitos
}

const formatoData = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short' })
const formatoDataHora = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'short' })

export function formatarData(instante: string): string {
  return formatoData.format(new Date(instante))
}

export function formatarDataHora(instante: string): string {
  return formatoDataHora.format(new Date(instante))
}
