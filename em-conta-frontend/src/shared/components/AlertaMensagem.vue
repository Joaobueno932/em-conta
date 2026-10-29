<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ tipo: 'erro' | 'aviso' | 'sucesso' | 'info' }>()

// Erros interrompem o leitor de tela; os demais são anunciados sem interromper.
const papel = computed(() => (props.tipo === 'erro' ? 'alert' : 'status'))
</script>

<template>
  <div class="alerta" :class="`alerta--${tipo}`" :role="papel">
    <slot />
  </div>
</template>

<style scoped>
.alerta {
  border: 1px solid;
  border-radius: 6px;
  padding: 0.75rem 1rem;
  margin: 1rem 0;
}

.alerta--erro {
  border-color: #b42318;
  background: #fef3f2;
  color: #7a271a;
}

.alerta--aviso {
  border-color: #b54708;
  background: #fffaeb;
  color: #7a2e0e;
}

.alerta--sucesso {
  border-color: #067647;
  background: #ecfdf3;
  color: #054f31;
}

.alerta--info {
  border-color: #175cd3;
  background: #eff8ff;
  color: #194185;
}
</style>
