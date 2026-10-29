<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'

import { temPermissao, useSessao } from '@/features/auth/auth-api'
import { AVISO_VINCULO_CADASTRAL, formatarDataNegocio } from '@/features/usinas/usinas-api'
import { describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'

import { useVinculosUsina } from './vinculos-usina'

/** Histórico de vínculos das UCs do associado com usinas: inclusão, vigência e encerramento. */
const props = defineProps<{ associadoId: number }>()
const { data: sessao } = useSessao()
const podeVerUsina = computed(() => temPermissao(sessao.value, 'usinas:consultar'))
const { vinculos } = useVinculosUsina(computed(() => props.associadoId))
</script>

<template>
  <section class="vinculos" aria-labelledby="titulo-vinculos-usina">
    <h3 id="titulo-vinculos-usina">Vínculos com usinas</h3>
    <p class="ajuda">{{ AVISO_VINCULO_CADASTRAL }}</p>
    <p v-if="vinculos.isPending.value" role="status">Carregando vínculos…</p>
    <AlertaMensagem v-else-if="vinculos.isError.value" tipo="erro">
      {{ describeApiError(vinculos.error.value) }}
    </AlertaMensagem>
    <p v-else-if="vinculos.data.value && vinculos.data.value.length === 0" class="ajuda">
      Nenhuma UC deste associado tem ou teve vínculo com usina.
    </p>
    <div v-else-if="vinculos.data.value" class="tabela-rolavel">
      <table>
        <thead>
          <tr>
            <th scope="col">Nº UC</th>
            <th scope="col">Usina</th>
            <th scope="col">Vigência</th>
            <th scope="col">Registro</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="v in vinculos.data.value" :key="v.id">
            <td>{{ v.uc.numero }}</td>
            <td>
              <RouterLink
                v-if="podeVerUsina"
                :to="{ name: 'usina', params: { id: v.usina.id }, query: { aba: 'ucs' } }"
              >
                {{ v.usina.nome }}
              </RouterLink>
              <template v-else>{{ v.usina.nome }}</template>
              <span class="ajuda">{{ v.usina.codigo }}</span>
            </td>
            <td>
              <span class="etiqueta" :class="{ 'etiqueta--vigente': v.vigente }">
                {{ v.vigente ? 'Vigente' : 'Encerrado' }}
              </span>
              <span class="ajuda">
                desde {{ formatarDataNegocio(v.inicio) }}
                <template v-if="v.fim"> até {{ formatarDataNegocio(v.fim) }}</template>
              </span>
              <span v-if="v.motivoEncerramento" class="ajuda"
                >Motivo: {{ v.motivoEncerramento }}</span
              >
            </td>
            <td>
              Vinculado por {{ v.criadoPor.nome }}
              <span v-if="v.encerradoPor" class="ajuda"
                >Encerrado por {{ v.encerradoPor.nome }}</span
              >
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.vinculos {
  margin-top: 1.5rem;
}

td .ajuda {
  display: block;
}

.etiqueta--vigente {
  background: var(--cor-destaque-suave);
  color: var(--cor-destaque-forte);
}
</style>
