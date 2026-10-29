/**
 * Espelho da política provisória do backend, apenas para orientar antes do envio. A validação que
 * vale é a do servidor. A senha nunca é aparada, truncada ou alterada.
 */
export const SENHA_MINIMO = 15
export const SENHA_MAXIMO = 128
const SENHA_MAXIMO_BYTES = 512

export function problemaNaSenha(senha: string): string | null {
  const caracteres = [...senha].length
  if (caracteres < SENHA_MINIMO) return `A senha deve ter pelo menos ${SENHA_MINIMO} caracteres.`
  if (caracteres > SENHA_MAXIMO || new TextEncoder().encode(senha).length > SENHA_MAXIMO_BYTES) {
    return `A senha deve ter no máximo ${SENHA_MAXIMO} caracteres.`
  }
  if (senha.trim() === '') return 'A senha não pode conter apenas espaços.'
  return null
}
