import type { components } from '@/shared/api/contract/schema'
import type { HttpClient } from '@/shared/api/http-client'

export type Usuario = components['schemas']['Usuario']
export type PaginaUsuarios = components['schemas']['PaginaUsuarios']
export type Perfil = Usuario['perfis'][number]
export type ConviteCriado = components['schemas']['ConviteCriado']

export const chaveUsuarios = ['usuarios'] as const

export const ROTULO_SITUACAO: Record<Usuario['situacao'], string> = {
  CONVIDADO: 'Convite pendente',
  ATIVO: 'Ativo',
  DESATIVADO: 'Desativado',
}

export const ROTULO_PERFIL: Record<Perfil, string> = {
  ADMINISTRADOR: 'Administrador',
}

export function listarUsuarios(http: HttpClient, pagina: number, tamanho = 20) {
  return http.get<PaginaUsuarios>(`/v1/usuarios?pagina=${pagina}&tamanho=${tamanho}`)
}

export function obterUsuario(http: HttpClient, id: number) {
  return http.get<Usuario>(`/v1/usuarios/${id}`)
}

export function convidarUsuario(http: HttpClient, nome: string, email: string, perfis: Perfil[]) {
  return http.request<ConviteCriado>('POST', '/v1/usuarios', { body: { nome, email, perfis } })
}

export function reenviarConvite(http: HttpClient, id: number) {
  return http.request<components['schemas']['ReenvioConvite']>('POST', `/v1/usuarios/${id}/convite`)
}

export function editarNome(http: HttpClient, id: number, nome: string) {
  return http.request<Usuario>('PATCH', `/v1/usuarios/${id}`, { body: { nome } })
}

export function definirPerfis(http: HttpClient, id: number, perfis: Perfil[]) {
  return http.request<Usuario>('PUT', `/v1/usuarios/${id}/perfis`, { body: { perfis } })
}

export function desativarUsuario(http: HttpClient, id: number) {
  return http.request<Usuario>('POST', `/v1/usuarios/${id}/desativacao`)
}

export function reativarUsuario(http: HttpClient, id: number) {
  return http.request<Usuario>('POST', `/v1/usuarios/${id}/reativacao`)
}

export function rotuloPerfis(perfis: readonly Perfil[]): string {
  return perfis.map((perfil) => ROTULO_PERFIL[perfil]).join(', ') || '—'
}
