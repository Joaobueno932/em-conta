import type { QueryClient } from '@tanstack/vue-query'

import { chavesUsinas } from './usinas-api'

/**
 * Depois de criar ou encerrar um vínculo: invalida só a usina (contagens), seus vínculos e
 * histórico, a lista de usinas, a busca de UCs candidatas e os vínculos do associado titular.
 */
export function invalidarAposVinculo(
  queryClient: QueryClient,
  usinaId: number,
  associadoId: number,
) {
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: chavesUsinas.usina(usinaId) }),
    queryClient.invalidateQueries({ queryKey: chavesUsinas.vinculos(usinaId) }),
    queryClient.invalidateQueries({ queryKey: chavesUsinas.historico(usinaId) }),
    queryClient.invalidateQueries({ queryKey: chavesUsinas.listas }),
    queryClient.invalidateQueries({ queryKey: ['usinas', 'candidatas'] }),
    queryClient.invalidateQueries({ queryKey: chavesUsinas.doAssociado(associadoId) }),
  ])
}
