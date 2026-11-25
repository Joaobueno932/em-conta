import type { QueryClient } from '@tanstack/vue-query'
import type { RouteLocationRaw, Router } from 'vue-router'

import { chaveSessao, sessaoQuery, type UsuarioAtual } from '@/features/auth/auth-api'
import type { HttpClient } from '@/shared/api/http-client'

/**
 * - `publico`: sem verificação de sessão (status técnico, links de convite e redefinição);
 * - `visitante`: só sem sessão (login, recuperação); com sessão vai para o início;
 * - `autenticado` (padrão): exige sessão e, se indicada, a permissão.
 */
export type NivelAcesso = 'publico' | 'visitante' | 'autenticado'

declare module 'vue-router' {
  interface RouteMeta {
    acesso?: NivelAcesso
    permissao?: string
  }
}

/**
 * Guardas de rota. A navegação só termina depois de a sessão ser conhecida, então conteúdo protegido
 * nunca é renderizado antes da verificação. Isso é só experiência de uso: a autorização real é do
 * servidor, que recusa qualquer chamada sem sessão ou permissão.
 */
export function instalarGuardas(router: Router, queryClient: QueryClient, http: HttpClient) {
  router.beforeEach(async (to) => {
    const acesso = to.meta.acesso ?? 'autenticado'
    if (acesso === 'publico') return true

    let usuario: UsuarioAtual | null = null
    let indisponivel = false
    try {
      usuario = await queryClient.ensureQueryData(sessaoQuery(http))
    } catch {
      indisponivel = true
    }

    if (acesso === 'visitante') {
      return usuario ? { name: 'inicio' } : true
    }
    if (!usuario) {
      return {
        name: 'entrar',
        query: { redirect: to.fullPath, ...(indisponivel ? { motivo: 'indisponivel' } : {}) },
      }
    }
    if (to.meta.permissao && !usuario.permissoes.includes(to.meta.permissao)) {
      return { name: 'acesso-negado' }
    }
    return true
  })
}

/** Reação a um 401 de sessão em qualquer chamada: descarta dados do usuário e leva ao login. */
export function aoInvalidarSessao(router: Router, queryClient: QueryClient) {
  return () => {
    descartarSessao(queryClient)
    const atual = router.currentRoute.value
    if ((atual.meta.acesso ?? 'autenticado') !== 'autenticado') return
    void router.replace({ name: 'entrar', query: { redirect: atual.fullPath, motivo: 'expirada' } })
  }
}

/**
 * Apaga todo o estado remoto em cache (dados de um usuário não podem aparecer para outro) e registra
 * que não há sessão, para que as guardas não confiem em dados anteriores ao 401 ou ao logout.
 */
export function descartarSessao(queryClient: QueryClient) {
  queryClient.clear()
  queryClient.setQueryData(chaveSessao, null)
}

/** Aceita como destino pós-login somente rotas internas e autenticadas conhecidas. */
export function destinoPosLogin(router: Router, bruto: unknown): RouteLocationRaw {
  const inicio = { name: 'inicio' }
  if (
    typeof bruto !== 'string' ||
    !bruto.startsWith('/') ||
    bruto.startsWith('//') ||
    bruto.includes('\\')
  ) {
    return inicio
  }
  const rota = router.resolve(bruto)
  if (
    rota.matched.length === 0 ||
    rota.name === 'nao-encontrada' ||
    (rota.meta.acesso ?? 'autenticado') !== 'autenticado'
  ) {
    return inicio
  }
  return rota.fullPath
}
