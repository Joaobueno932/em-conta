<script setup lang="ts">
import { useMutation, useQueryClient } from '@tanstack/vue-query'
import { computed, ref } from 'vue'

import { useHttpClient } from '@/app/injection'
import { camposDoErro } from '@/shared/api/campos'
import { codigoDoErro, describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'

import { encerrarVinculo, formatarDataNegocio, hojeIso, type VinculoUsina } from './usinas-api'
import { invalidarAposVinculo } from './vinculos-cache'

/**
 * Encerramento do vínculo com data e motivo obrigatórios. Trocar de usina ou corrigir um lançamento
 * é encerrar (e, se for o caso, vincular de novo); o registro é mantido no histórico.
 */
const props = defineProps<{ vinculo: VinculoUsina }>()
const emit = defineEmits<{ concluido: []; cancelado: [] }>()

const http = useHttpClient()
const queryClient = useQueryClient()

const fim = ref(hojeIso())
const motivo = ref('')
const prefixo = computed(() => `encerrar-${props.vinculo.id}`)

const encerrar = useMutation({
  mutationFn: () =>
    encerrarVinculo(http, props.vinculo.usina.id, props.vinculo.id, {
      versao: props.vinculo.versao,
      fim: fim.value,
      motivo: motivo.value,
    }),
  onSuccess: async () => {
    // Avisa antes de recarregar: a linha (e este painel) some da lista de vigentes ao recarregar.
    emit('concluido')
    await invalidarAposVinculo(queryClient, props.vinculo.usina.id, props.vinculo.uc.associadoId)
  },
})

const erros = computed(() => camposDoErro(encerrar.error.value))
const conflito = computed(() =>
  ['CONFLITO_CONCORRENTE', 'VINCULO_JA_ENCERRADO'].includes(
    codigoDoErro(encerrar.error.value) ?? '',
  ),
)

function confirmar() {
  if (encerrar.isPending.value) return
  if (
    window.confirm(
      `Encerrar o vínculo da UC ${props.vinculo.uc.numero} com ${props.vinculo.usina.nome} em ${formatarDataNegocio(fim.value)}?`,
    )
  ) {
    encerrar.mutate()
  }
}

async function recarregar() {
  await invalidarAposVinculo(queryClient, props.vinculo.usina.id, props.vinculo.uc.associadoId)
  emit('cancelado')
}
</script>

<template>
  <form
    class="painel"
    novalidate
    :aria-label="`Encerrar vínculo da UC ${vinculo.uc.numero}`"
    @submit.prevent="confirmar"
  >
    <AlertaMensagem v-if="conflito" tipo="aviso">
      Este vínculo foi alterado por outra pessoa. O que você digitou foi mantido.
      <button type="button" class="link" @click="recarregar">Recarregar vínculos</button>
    </AlertaMensagem>
    <AlertaMensagem v-else-if="encerrar.isError.value && !Object.keys(erros).length" tipo="erro">
      {{ describeApiError(encerrar.error.value) }}
    </AlertaMensagem>
    <div class="grade">
      <div class="campo">
        <label :for="`${prefixo}-fim`"
          >Data de encerramento<span class="obrigatorio" aria-hidden="true"> *</span></label
        >
        <input
          :id="`${prefixo}-fim`"
          v-model="fim"
          type="date"
          required
          :min="vinculo.inicio"
          :max="hojeIso()"
          :aria-invalid="erros.fim ? 'true' : undefined"
          :aria-describedby="erros.fim ? `${prefixo}-fim-erro` : `${prefixo}-fim-ajuda`"
        />
        <p :id="`${prefixo}-fim-ajuda`" class="ajuda">
          A partir desta data o vínculo deixa de valer (início em
          {{ formatarDataNegocio(vinculo.inicio) }}).
        </p>
        <p v-if="erros.fim" :id="`${prefixo}-fim-erro`" class="ajuda ajuda--erro">
          {{ erros.fim }}
        </p>
      </div>
      <div class="campo">
        <label :for="`${prefixo}-motivo`"
          >Motivo<span class="obrigatorio" aria-hidden="true"> *</span></label
        >
        <textarea
          :id="`${prefixo}-motivo`"
          v-model="motivo"
          rows="2"
          maxlength="500"
          required
          :aria-invalid="erros.motivo ? 'true' : undefined"
          :aria-describedby="erros.motivo ? `${prefixo}-motivo-erro` : undefined"
        />
        <p v-if="erros.motivo" :id="`${prefixo}-motivo-erro`" class="ajuda ajuda--erro">
          {{ erros.motivo }}
        </p>
      </div>
    </div>
    <div class="acoes">
      <button type="submit" class="perigo" :disabled="encerrar.isPending.value">
        {{ encerrar.isPending.value ? 'Encerrando…' : 'Encerrar vínculo' }}
      </button>
      <button type="button" class="secundario" @click="emit('cancelado')">Cancelar</button>
    </div>
  </form>
</template>

<style scoped>
.painel {
  border: 1px solid var(--cor-borda);
  border-radius: var(--raio);
  padding: 1rem;
  margin: 0.5rem 0;
}

input[type='date'] {
  font: inherit;
  padding: 0.5rem 0.75rem;
  border: 1px solid #c9cfcb;
  border-radius: var(--raio);
  max-width: 14rem;
}
</style>
