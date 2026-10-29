import { QueryClient } from '@tanstack/vue-query'
import { render } from '@testing-library/vue'
import type { Component } from 'vue'
import { createMemoryHistory } from 'vue-router'

import { installProviders } from '@/app/providers'
import { createAppRouter } from '@/app/router'
import type { RuntimeConfig } from '@/config/runtime-config'
import { createHttpClient } from '@/shared/api/http-client'

export const testConfig: RuntimeConfig = { environment: 'teste', apiBasePath: '/api' }

/** Renderiza com os mesmos providers da aplicação, trocando apenas o fetch e sem novas tentativas. */
export async function renderWithProviders(component: Component, fetchFn: typeof fetch) {
  const router = createAppRouter(createMemoryHistory())
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const httpClient = createHttpClient(testConfig.apiBasePath, fetchFn)

  const result = render(component, {
    global: {
      plugins: [
        {
          install: (app) =>
            installProviders(app, { config: testConfig, router, queryClient, httpClient }),
        },
      ],
    },
  })
  await router.isReady()
  return result
}

export function jsonResponse(
  body: unknown,
  status = 200,
  contentType = 'application/json',
): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': contentType } })
}
