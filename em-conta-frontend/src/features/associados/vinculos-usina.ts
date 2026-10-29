import { useQuery } from '@tanstack/vue-query'
import { computed, type Ref } from 'vue'

import { useHttpClient } from '@/app/injection'
import { temPermissao, useSessao } from '@/features/auth/auth-api'
import { chavesUsinas, vinculosDoAssociado, type VinculoUsina } from '@/features/usinas/usinas-api'

/**
 * Vínculos cadastrais das UCs do associado com usinas. Só consulta se o usuário tiver
 * `vinculos-usina:consultar`; sem a permissão, nada é pedido nem exibido.
 */
export function useVinculosUsina(associadoId: Ref<number>) {
  const http = useHttpClient()
  const { data: sessao } = useSessao()
  const podeConsultar = computed(() => temPermissao(sessao.value, 'vinculos-usina:consultar'))

  const vinculos = useQuery({
    queryKey: computed(() => chavesUsinas.doAssociado(associadoId.value)),
    queryFn: () => vinculosDoAssociado(http, associadoId.value),
    enabled: podeConsultar,
  })

  const vigentePorUc = computed(() => {
    const mapa = new Map<number, VinculoUsina>()
    for (const v of vinculos.data.value ?? []) if (v.vigente) mapa.set(v.uc.id, v)
    return mapa
  })

  return { podeConsultar, vinculos, vigentePorUc }
}
