<script setup lang="ts">
defineProps<{
  title: string
  message: string
  retrying?: boolean
}>()

const emit = defineEmits<{ retry: [] }>()
</script>

<template>
  <section class="error-notice" role="alert">
    <h2>{{ title }}</h2>
    <p>{{ message }}</p>
    <slot />
    <button type="button" :disabled="retrying" @click="emit('retry')">
      {{ retrying ? 'Verificando…' : 'Tentar novamente' }}
    </button>
  </section>
</template>

<style scoped>
.error-notice {
  border: 1px solid #b42318;
  border-radius: 6px;
  padding: 1rem;
  background: #fef3f2;
  color: #7a271a;
}

.error-notice h2 {
  margin: 0 0 0.5rem;
  font-size: 1.1rem;
}
</style>
