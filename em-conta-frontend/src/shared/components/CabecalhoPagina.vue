<script setup lang="ts">
/**
 * Título e subtítulo das páginas de módulo (pranchas de 1672 × 1100: título de 38 px, subtítulo de
 * 16 px), com as ações principais à direita. A trilha (breadcrumb), quando há, vai no slot `trilha`.
 */
defineProps<{ titulo: string; subtitulo?: string }>()
</script>

<template>
  <header class="cabecalho-pagina">
    <div class="textos">
      <slot name="trilha" />
      <h1>{{ titulo }}</h1>
      <p v-if="subtitulo">{{ subtitulo }}</p>
    </div>
    <div v-if="$slots.acoes" class="acoes-pagina">
      <slot name="acoes" />
    </div>
  </header>
</template>

<style scoped>
.cabecalho-pagina {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  flex-wrap: wrap;
  gap: 12px 24px;
  margin-bottom: 14px;
}

.textos {
  min-width: 0;
}

h1 {
  margin: 0;
  font-size: 38px;
  font-weight: 700;
  line-height: 46px;
  letter-spacing: -0.01em;
}

p {
  margin: 0;
  color: var(--cor-texto-2);
  font-size: 16px;
  line-height: 24px;
}

.acoes-pagina {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

@media (max-width: 40rem) {
  h1 {
    font-size: 28px;
    line-height: 34px;
  }
}
</style>
