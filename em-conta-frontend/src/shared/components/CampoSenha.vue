<script setup lang="ts">
import { computed, ref } from 'vue'

import IconeTraco from './IconeTraco.vue'

/**
 * Campo de senha com o botão de mostrar/ocultar dentro do campo. O botão tem nome fixo ("Mostrar
 * senha") e o estado vai em `aria-pressed`. `invalido` e `descritoPor` funcionam como no CampoTexto.
 */
const props = defineProps<{
  id: string
  rotulo: string
  autocomplete: 'current-password' | 'new-password'
  obrigatorio?: boolean
  descricao?: string
  erro?: string | null
  invalido?: boolean
  descritoPor?: string
}>()

const valor = defineModel<string>({ required: true })
const visivel = ref(false)

const descritoPor = computed(
  () =>
    [props.descricao || props.erro ? `${props.id}-ajuda` : '', props.descritoPor ?? '']
      .filter(Boolean)
      .join(' ') || undefined,
)
</script>

<template>
  <div class="campo">
    <label :for="id">
      {{ rotulo }}<span v-if="obrigatorio" class="obrigatorio" aria-hidden="true"> *</span>
    </label>
    <div class="campo-senha">
      <!-- Sem maxlength: uma senha colada nunca é cortada em silêncio; o limite é validado e explicado. -->
      <input
        :id="id"
        v-model="valor"
        :type="visivel ? 'text' : 'password'"
        :autocomplete="autocomplete"
        :aria-describedby="descritoPor"
        :aria-invalid="erro || invalido ? 'true' : undefined"
        :aria-required="obrigatorio || undefined"
        spellcheck="false"
        autocapitalize="off"
        required
      />
      <button
        type="button"
        class="alternar"
        aria-label="Mostrar senha"
        :title="visivel ? 'Ocultar senha' : 'Mostrar senha'"
        :aria-pressed="visivel"
        :aria-controls="id"
        @click="visivel = !visivel"
      >
        <IconeTraco :nome="visivel ? 'olho-cortado' : 'olho'" :tamanho="18" />
      </button>
    </div>
    <p v-if="descricao || erro" :id="`${id}-ajuda`" class="ajuda" :class="{ 'ajuda--erro': erro }">
      {{ erro || descricao }}
    </p>
  </div>
</template>

<style scoped>
.campo-senha {
  position: relative;
  display: flex;
}

.campo-senha input {
  flex: 1;
  min-width: 0;
  padding-right: 2.75rem;
}

button.alternar {
  position: absolute;
  top: 50%;
  right: 0.4rem;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 2rem;
  height: 2rem;
  padding: 0;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--cor-texto);
  transform: translateY(-50%);
}

button.alternar:hover {
  background: var(--cor-superficie-2);
}
</style>
