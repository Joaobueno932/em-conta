import { QueryClient } from '@tanstack/vue-query'
import { render } from '@testing-library/vue'
import { vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'

import App from '@/app/App.vue'
import { aoInvalidarSessao, instalarGuardas } from '@/app/guards'
import { installProviders } from '@/app/providers'
import { createAppRouter } from '@/app/router'
import { createHttpClient } from '@/shared/api/http-client'

import { jsonResponse, testConfig } from './render-with-providers'

type Resposta = Response | Promise<Response>
export type Rotas = Record<string, (init?: RequestInit) => Resposta>

/** Respostas falsas por "MÉTODO /caminho" (a query string é ignorada se não houver rota exata). */
export function apiFalsa(rotas: Rotas) {
  const chamadas: string[] = []
  const fetchFn = vi.fn<typeof fetch>(async (input, init) => {
    const chave = `${init?.method ?? 'GET'} ${String(input)}`
    chamadas.push(chave)
    const responder = rotas[chave] ?? rotas[chave.replace(/\?.*$/, '')]
    return responder ? responder(init) : problema(404, 'NAO_ENCONTRADO', 'Não encontrado.')
  })
  return { fetchFn, chamadas }
}

export function problema(status: number, codigo: string, detail: string, headers = {}): Response {
  return new Response(JSON.stringify({ status, codigo, detail }), {
    status,
    headers: { 'Content-Type': 'application/problem+json', ...headers },
  })
}

/** O backend emite o cookie XSRF-TOKEN; aqui o handler o grava no document.cookie do jsdom. */
export function csrf(valor = 'token-csrf'): Rotas {
  return {
    'GET /api/v1/auth/csrf': () => {
      document.cookie = `XSRF-TOKEN=${valor}; path=/`
      return jsonResponse({ cabecalho: 'X-XSRF-TOKEN' })
    },
  }
}

export function usuarioAtual(email: string, permissoes: string[] = []) {
  return {
    id: email.length,
    nome: `Pessoa ${email}`,
    email,
    perfis: permissoes.length ? ['ADMINISTRADOR'] : [],
    permissoes,
  }
}

export const PERMISSOES_ADMIN = [
  'usuarios:alterar-situacao',
  'usuarios:atribuir-perfis',
  'usuarios:consultar',
  'usuarios:convidar',
  'usuarios:editar',
]

/** Monta a aplicação inteira (App + guardas + reação a sessão inválida) numa rota. */
export async function montarApp(fetchFn: typeof fetch, caminho: string) {
  const router = createAppRouter(createMemoryHistory())
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const httpClient = createHttpClient('/api', fetchFn, {
    onSessaoInvalida: aoInvalidarSessao(router, queryClient),
  })
  instalarGuardas(router, queryClient, httpClient)
  const resultado = render(App, {
    global: {
      plugins: [
        {
          install: (app) =>
            installProviders(app, { config: testConfig, router, queryClient, httpClient }),
        },
      ],
    },
  })
  await router.push(caminho)
  return { ...resultado, router, queryClient }
}
