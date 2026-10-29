<script setup lang="ts">
import { ref } from 'vue'

defineProps<{
  id: string
  rotulo: string
  autocomplete: 'current-password' | 'new-password'
  descricao?: string
  erro?: string | null
}>()

const valor = defineModel<string>({ required: true })
const visivel = ref(false)
</script>

<template>
  <div class="campo">
    <label :for="id">{{ rotulo }}</label>
    <div class="campo-senha">
      <!-- Sem maxlength: uma senha colada nunca é cortada em silêncio; o limite é validado e explicado. -->
      <input
        :id="id"
        v-model="valor"
        :type="visivel ? 'text' : 'password'"
        :autocomplete="autocomplete"
        :aria-describedby="descricao || erro ? `${id}-ajuda` : undefined"
        :aria-invalid="erro ? 'true' : undefined"
        spellcheck="false"
        autocapitalize="off"
        required
      />
      <button
        type="button"
        class="secundario"
        :aria-pressed="visivel"
        :aria-controls="id"
        @click="visivel = !visivel"
      >
        {{ visivel ? 'Ocultar' : 'Mostrar' }}
      </button>
    </div>
    <p v-if="descricao || erro" :id="`${id}-ajuda`" class="ajuda" :class="{ 'ajuda--erro': erro }">
      {{ erro || descricao }}
    </p>
  </div>
</template>

<style scoped>
.campo-senha {
  display: flex;
  gap: 0.5rem;
}

.campo-senha input {
  flex: 1;
}
</style>
