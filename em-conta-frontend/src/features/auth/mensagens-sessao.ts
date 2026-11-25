/** Mensagens comuns às telas de sessão (pranchas "Sessão" do protótipo). */
export const CAMPOS_VAZIOS = 'Campos vazios enviados, preencha todos os campos obrigatórios.'

/**
 * Conferência mínima do formato, só para orientar antes do envio: um "@" com texto dos dois lados e
 * sem espaços no meio. O servidor normaliza o e-mail e a recuperação responde igual para qualquer
 * endereço, então nada aqui revela se a conta existe.
 */
export function pareceEmail(email: string): boolean {
  return /^[^\s@]+@[^\s@]+$/.test(email.trim())
}
