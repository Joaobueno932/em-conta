import { ApiError } from './http-client'

/**
 * Tradução central de falhas HTTP em mensagens para o usuário. Indisponibilidade (rede, 5xx) nunca é
 * apresentada como erro de senha ou de permissão.
 */
export function describeApiError(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return 'Ocorreu um erro inesperado.'
  }

  switch (error.kind) {
    case 'network':
      return 'Não foi possível conectar à API. Verifique se o backend está em execução e se o proxy /api está configurado.'
    case 'invalid-response':
      return 'A API respondeu em formato inesperado. Verifique se /api está direcionado ao backend.'
    case 'http':
      if (error.status === 429) {
        const minutos = error.retryAfterSeconds ? Math.ceil(error.retryAfterSeconds / 60) : null
        return minutos
          ? `Muitas tentativas. Aguarde ${minutos} ${minutos === 1 ? 'minuto' : 'minutos'} e tente novamente.`
          : 'Muitas tentativas. Aguarde alguns minutos e tente novamente.'
      }
      if (error.status !== null && error.status >= 500) {
        return `A API está indisponível no momento (HTTP ${error.status}). Verifique se o backend está em execução.`
      }
      if (error.problem?.detail) return error.problem.detail
      if (error.status === 401) return 'É necessário entrar no sistema para acessar este recurso.'
      if (error.status === 403) return 'Você não tem permissão para esta ação.'
      return `A requisição não pôde ser concluída (HTTP ${error.status}).`
  }
}

export function codigoDoErro(error: unknown): string | undefined {
  return error instanceof ApiError ? error.codigo : undefined
}
