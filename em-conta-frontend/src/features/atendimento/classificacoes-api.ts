import type { components } from '@/shared/api/contract/schema'
import type { HttpClient } from '@/shared/api/http-client'

/**
 * Cadastros de setores e etiquetas do atendimento. A classificação pertence a cada ciclo (atendimento):
 * um setor opcional e nenhuma ou várias etiquetas, definidos pelo responsável atual. Desativar tira o item
 * das escolhas novas, mas os atendimentos que já o usam continuam com ele e são filtrados no histórico.
 */
export type ItemCadastro = components['schemas']['ItemCadastroClassificacao']
export type ListaCadastro = components['schemas']['ListaCadastroClassificacao']
export type Cadastro = 'setores' | 'etiquetas'
export type SituacaoCadastro = 'ATIVOS' | 'INATIVOS' | 'TODOS'

export const TAMANHO_MAXIMO_NOME: Record<Cadastro, number> = { setores: 60, etiquetas: 40 }
export const MAXIMO_ETIQUETAS = 10

export const ROTULO_CADASTRO: Record<Cadastro, { singular: string; plural: string; novo: string }> =
  {
    setores: { singular: 'setor', plural: 'Setores', novo: 'Novo setor' },
    etiquetas: { singular: 'etiqueta', plural: 'Etiquetas', novo: 'Nova etiqueta' },
  }

const BASE = '/v1/atendimento'

export const chavesClassificacoes = {
  todas: ['classificacoes'] as const,
  lista: (cadastro: Cadastro, situacao: SituacaoCadastro) =>
    ['classificacoes', cadastro, situacao] as const,
}

export function listarCadastro(
  http: HttpClient,
  cadastro: Cadastro,
  situacao: SituacaoCadastro = 'TODOS',
) {
  return http.get<ListaCadastro>(`${BASE}/${cadastro}?situacao=${situacao}`)
}

export function criarItem(http: HttpClient, cadastro: Cadastro, nome: string) {
  return http.request<ItemCadastro>('POST', `${BASE}/${cadastro}`, { body: { nome } })
}

export function renomearItem(
  http: HttpClient,
  cadastro: Cadastro,
  id: number,
  versao: number,
  nome: string,
) {
  return http.request<ItemCadastro>('PUT', `${BASE}/${cadastro}/${id}`, { body: { nome, versao } })
}

export function alterarSituacaoItem(
  http: HttpClient,
  cadastro: Cadastro,
  id: number,
  versao: number,
  ativo: boolean,
) {
  return http.request<ItemCadastro>(
    'POST',
    `${BASE}/${cadastro}/${id}/${ativo ? 'ativacao' : 'desativacao'}`,
    { body: { versao } },
  )
}

/** Ativos primeiro e os inativos à parte (filtros que alcançam itens desativados depois de usados). */
export function separarPorSituacao<T extends { ativo: boolean }>(itens: T[] | undefined) {
  return {
    ativos: (itens ?? []).filter((i) => i.ativo),
    inativos: (itens ?? []).filter((i) => !i.ativo),
  }
}

/** Nome para listas e filtros, marcando o item desativado depois de usado. */
export const rotuloItem = (item: { nome: string; ativo: boolean }) =>
  item.ativo ? item.nome : `${item.nome} (inativo)`
