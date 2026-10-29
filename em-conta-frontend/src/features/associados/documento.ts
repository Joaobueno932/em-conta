/**
 * CPF/CNPJ no navegador: só para apresentação e aviso imediato. A validação que vale é a do
 * servidor, com as mesmas regras (CNPJ alfanumérico da Receita Federal: 12 posições 0-9/A-Z e 2
 * dígitos verificadores, módulo 11 com o valor ASCII menos 48).
 *
 * O campo aceita digitação e colagem com ou sem pontuação; o valor enviado é o digitado, e o
 * servidor faz a normalização canônica.
 */

export function normalizarDocumento(valor: string): string {
  return valor.replace(/[\s./-]/g, '').toUpperCase()
}

function digitoCpf(base: string, pesoInicial: number): number {
  let soma = 0
  for (let i = 0; i < base.length; i++) soma += Number(base[i]) * (pesoInicial - i)
  const resto = soma % 11
  return resto < 2 ? 0 : 11 - resto
}

function digitoCnpj(base: string): number {
  let soma = 0
  let peso = 2
  for (let i = base.length - 1; i >= 0; i--) {
    soma += (base.charCodeAt(i) - 48) * peso
    peso = peso === 9 ? 2 : peso + 1
  }
  const resto = soma % 11
  return resto < 2 ? 0 : 11 - resto
}

function repetido(valor: string): boolean {
  return [...valor].every((c) => c === valor[0])
}

export function cpfValido(valor: string): boolean {
  const cpf = normalizarDocumento(valor)
  if (!/^\d{11}$/.test(cpf) || repetido(cpf)) return false
  return (
    digitoCpf(cpf.slice(0, 9), 10) === Number(cpf[9]) &&
    digitoCpf(cpf.slice(0, 10), 11) === Number(cpf[10])
  )
}

export function cnpjValido(valor: string): boolean {
  const cnpj = normalizarDocumento(valor)
  if (!/^[0-9A-Z]{12}\d{2}$/.test(cnpj) || repetido(cnpj)) return false
  return (
    digitoCnpj(cnpj.slice(0, 12)) === Number(cnpj[12]) &&
    digitoCnpj(cnpj.slice(0, 13)) === Number(cnpj[13])
  )
}

/** Máscara de apresentação aplicada só quando o valor está completo; não altera a digitação. */
export function formatarDocumento(tipo: 'PF' | 'PJ', valor: string): string {
  const d = normalizarDocumento(valor)
  if (tipo === 'PF' && d.length === 11) {
    return `${d.slice(0, 3)}.${d.slice(3, 6)}.${d.slice(6, 9)}-${d.slice(9)}`
  }
  if (tipo === 'PJ' && d.length === 14) {
    return `${d.slice(0, 2)}.${d.slice(2, 5)}.${d.slice(5, 8)}/${d.slice(8, 12)}-${d.slice(12)}`
  }
  return valor
}
