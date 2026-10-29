import { inject, type InjectionKey } from 'vue'

import type { RuntimeConfig } from '@/config/runtime-config'
import type { HttpClient } from '@/shared/api/http-client'

export const runtimeConfigKey: InjectionKey<RuntimeConfig> = Symbol('runtimeConfig')
export const httpClientKey: InjectionKey<HttpClient> = Symbol('httpClient')

function required<T>(key: InjectionKey<T>, name: string): T {
  const value = inject(key)
  if (value === undefined) {
    throw new Error(`${name} não foi fornecido. Verifique installProviders().`)
  }
  return value
}

export const useRuntimeConfig = () => required(runtimeConfigKey, 'RuntimeConfig')
export const useHttpClient = () => required(httpClientKey, 'HttpClient')
