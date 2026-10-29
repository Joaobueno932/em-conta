import { ApiError } from './http-client'

/**
 * Mensagens por campo enviadas pelo backend em Problem Details (`campos`: campo → mensagem). A
 * validação de corpo do Spring envia apenas "inválido"; o domínio envia a mensagem completa.
 */
export function camposDoErro(error: unknown): Record<string, string> {
  if (!(error instanceof ApiError) || error.kind !== 'http') return {}
  const campos = error.problem?.campos
  if (typeof campos !== 'object' || campos === null) return {}
  const resultado: Record<string, string> = {}
  for (const [campo, mensagem] of Object.entries(campos)) {
    if (typeof mensagem === 'string') {
      resultado[campo] = mensagem === 'inválido' ? 'Valor inválido.' : mensagem
    }
  }
  return resultado
}
