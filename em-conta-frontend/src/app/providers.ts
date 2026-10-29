import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { createPinia } from 'pinia'
import type { App } from 'vue'
import type { Router } from 'vue-router'

import type { RuntimeConfig } from '@/config/runtime-config'
import { createHttpClient, type HttpClient } from '@/shared/api/http-client'

import { httpClientKey, runtimeConfigKey } from './injection'

export function createAppQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: 1,
        refetchOnWindowFocus: false,
      },
    },
  })
}

export interface ProvidersOptions {
  config: RuntimeConfig
  router: Router
  queryClient?: QueryClient
  httpClient?: HttpClient
}

/**
 * Providers globais. TanStack Query guarda o estado remoto; Pinia fica disponível para estado
 * local compartilhado quando ele realmente existir (ainda não há stores).
 */
export function installProviders(app: App, options: ProvidersOptions): void {
  app.provide(runtimeConfigKey, options.config)
  app.provide(httpClientKey, options.httpClient ?? createHttpClient(options.config.apiBasePath))
  app.use(createPinia())
  app.use(VueQueryPlugin, { queryClient: options.queryClient ?? createAppQueryClient() })
  app.use(options.router)
}
