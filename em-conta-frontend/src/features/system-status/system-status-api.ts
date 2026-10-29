import { useQuery } from '@tanstack/vue-query'

import { useHttpClient } from '@/app/injection'
import type { components } from '@/shared/api/contract/schema'
import { ApiError, type HttpClient } from '@/shared/api/http-client'

export type SystemStatus = components['schemas']['SystemStatus']

const STATUSES = new Set(['UP', 'DOWN'])

/** Confere a forma da resposta: um 200 com outro conteúdo não pode aparecer como sucesso. */
export function isSystemStatus(value: unknown): value is SystemStatus {
  if (typeof value !== 'object' || value === null) return false
  const v = value as Record<string, unknown>
  return (
    typeof v.application === 'string' &&
    typeof v.version === 'string' &&
    typeof v.status === 'string' &&
    STATUSES.has(v.status) &&
    typeof v.database === 'string' &&
    STATUSES.has(v.database) &&
    typeof v.serverTime === 'string'
  )
}

export async function fetchSystemStatus(
  http: HttpClient,
  signal?: AbortSignal,
): Promise<SystemStatus> {
  const body = await http.get<unknown>('/v1/system/status', { signal })
  if (!isSystemStatus(body)) {
    throw new ApiError('invalid-response', 'Resposta de status fora do contrato.', 200)
  }
  return body
}

/** 503 com corpo válido: a API respondeu, mas o banco está indisponível. */
export function degradedStatusFrom(error: unknown): SystemStatus | null {
  return error instanceof ApiError && error.status === 503 && isSystemStatus(error.body)
    ? error.body
    : null
}

export function useSystemStatusQuery() {
  const http = useHttpClient()
  return useQuery({
    queryKey: ['system', 'status'],
    queryFn: ({ signal }) => fetchSystemStatus(http, signal),
  })
}
