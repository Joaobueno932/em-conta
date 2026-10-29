/**
 * Lê o token do fragmento da URL (`#token=...`) e o remove da barra de endereço imediatamente.
 * O fragmento não é enviado ao servidor nem incluído no cabeçalho Referer; o token fica só na memória
 * do componente.
 */
export function extrairTokenDaUrl(
  local: Location = window.location,
  historico: History = window.history,
) {
  const token = new URLSearchParams(local.hash.replace(/^#/, '')).get('token')
  if (local.hash) {
    historico.replaceState(historico.state, '', `${local.pathname}${local.search}`)
  }
  return token && /^[A-Za-z0-9_-]{43}$/.test(token) ? token : null
}
