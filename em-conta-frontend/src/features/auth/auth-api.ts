import { queryOptions, useQuery } from '@tanstack/vue-query'

import { useHttpClient } from '@/app/injection'
import type { components } from '@/shared/api/contract/schema'
import { ApiError, type HttpClient } from '@/shared/api/http-client'

export type UsuarioAtual = components['schemas']['UsuarioAtual']
export type TokenValido = components['schemas']['TokenValido']
export type FinalidadeToken = TokenValido['finalidade']

export const chaveSessao = ['auth', 'sessao'] as const

/** Sessão atual; `null` quando não há sessão válida. Falhas de rede e 5xx continuam sendo erros. */
export function sessaoQuery(http: HttpClient) {
  return queryOptions({
    queryKey: chaveSessao,
    queryFn: async (): Promise<UsuarioAtual | null> => {
      try {
        return await http.get<UsuarioAtual>('/v1/auth/sessao', { ignorarSessaoInvalida: true })
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) {
          return null
        }
        throw error
      }
    },
    staleTime: 60_000,
    retry: false,
  })
}

export function useSessao() {
  return useQuery(sessaoQuery(useHttpClient()))
}

export function temPermissao(usuario: UsuarioAtual | null | undefined, permissao: string): boolean {
  return usuario?.permissoes.includes(permissao) ?? false
}

export function entrar(http: HttpClient, email: string, senha: string) {
  return http.request<UsuarioAtual>('POST', '/v1/auth/login', {
    body: { email, senha },
    ignorarSessaoInvalida: true,
  })
}

export function sair(http: HttpClient) {
  return http.request<void>('POST', '/v1/auth/logout', { ignorarSessaoInvalida: true })
}

export function solicitarRecuperacao(http: HttpClient, email: string) {
  return http.request<components['schemas']['Mensagem']>('POST', '/v1/auth/senha/recuperacao', {
    body: { email },
  })
}

export function validarToken(http: HttpClient, token: string, finalidade: FinalidadeToken) {
  return http.request<TokenValido>('POST', '/v1/auth/tokens/validacao', {
    body: { token, finalidade },
  })
}

export function redefinirSenha(http: HttpClient, token: string, novaSenha: string) {
  return http.request<void>('POST', '/v1/auth/senha/redefinicao', { body: { token, novaSenha } })
}

export function aceitarConvite(http: HttpClient, token: string, senha: string) {
  return http.request<void>('POST', '/v1/auth/convites/aceitacao', { body: { token, senha } })
}

export function alterarSenha(http: HttpClient, senhaAtual: string, novaSenha: string) {
  return http.request<void>('POST', '/v1/auth/senha/alteracao', {
    body: { senhaAtual, novaSenha },
  })
}
