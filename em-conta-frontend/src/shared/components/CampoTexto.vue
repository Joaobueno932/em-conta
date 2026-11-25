<script setup lang="ts">
import { computed } from 'vue'

/**
 * Campo de texto com rótulo, ajuda e erro ligados ao input (`aria-describedby`, `aria-invalid`).
 * Não aplica máscara durante a digitação: colar com ou sem pontuação funciona igual.
 * `invalido` marca o campo sem mensagem própria; `descritoPor` liga uma mensagem de fora do campo
 * (ex.: "E-mail ou senha incorretos", que vale para os dois campos do login).
 */
const props = withDefaults(
  defineProps<{
    id: string
    rotulo: string
    tipo?: 'text' | 'email' | 'tel'
    obrigatorio?: boolean
    somenteLeitura?: boolean
    maximo?: number
    erro?: string | null
    invalido?: boolean
    descritoPor?: string
    ajuda?: string
    autocomplete?: string
  }>(),
  { tipo: 'text', obrigatorio: false, somenteLeitura: false, autocomplete: 'off' },
)

const valor = defineModel<string>({ required: true })
defineEmits<{ blur: [] }>()

const descricao = computed(
  () =>
    [
      props.erro ? `${props.id}-erro` : '',
      props.ajuda ? `${props.id}-ajuda` : '',
      props.descritoPor ?? '',
    ]
      .filter(Boolean)
      .join(' ') || undefined,
)
</script>

<template>
  <div class="campo">
    <label :for="id">
      {{ rotulo }}<span v-if="obrigatorio" class="obrigatorio" aria-hidden="true"> *</span>
    </label>
    <input
      :id="id"
      v-model="valor"
      :type="tipo"
      :maxlength="maximo"
      :required="obrigatorio"
      :readonly="somenteLeitura"
      :aria-required="obrigatorio || undefined"
      :aria-invalid="erro || invalido ? 'true' : undefined"
      :aria-describedby="descricao"
      :autocomplete="autocomplete"
      @blur="$emit('blur')"
    />
    <p v-if="ajuda" :id="`${id}-ajuda`" class="ajuda">{{ ajuda }}</p>
    <p v-if="erro" :id="`${id}-erro`" class="ajuda ajuda--erro">{{ erro }}</p>
  </div>
</template>
