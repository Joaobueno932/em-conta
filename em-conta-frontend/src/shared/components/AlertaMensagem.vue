<script setup lang="ts">
import { computed } from 'vue'

import IconeTraco, { type NomeIcone } from './IconeTraco.vue'

type Tipo = 'erro' | 'aviso' | 'sucesso' | 'info'
const props = defineProps<{ tipo: Tipo }>()

// Erros interrompem o leitor de tela; os demais são anunciados sem interromper.
const papel = computed(() => (props.tipo === 'erro' ? 'alert' : 'status'))

// Banners das pranchas: fundo suave, sem borda, ícone colorido à esquerda (a cor só reforça o texto).
const ICONES: Record<Tipo, NomeIcone> = {
  erro: 'alerta',
  aviso: 'alerta',
  sucesso: 'check-circulo',
  info: 'informacao',
}
</script>

<template>
  <div class="alerta" :class="`alerta--${tipo}`" :role="papel">
    <IconeTraco class="alerta__icone" :nome="ICONES[tipo]" :tamanho="18" />
    <div class="alerta__texto"><slot /></div>
  </div>
</template>

<style scoped>
.alerta {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  border-radius: 8px;
  padding: 8px 16px;
  margin: 1rem 0;
  color: var(--cor-texto);
  font-size: 14px;
  line-height: 20px;
}

.alerta__icone {
  margin-top: 1px;
}

.alerta__texto {
  flex: 1;
  min-width: 0;
}

.alerta--erro {
  background: var(--cor-erro-fundo);
}

.alerta--erro .alerta__icone {
  color: var(--cor-erro-icone);
}

.alerta--aviso {
  background: var(--cor-aviso-fundo);
}

.alerta--aviso .alerta__icone {
  color: var(--cor-alerta);
}

.alerta--sucesso {
  background: var(--cor-destaque-suave);
}

.alerta--sucesso .alerta__icone {
  color: var(--cor-destaque-forte);
}

.alerta--info {
  background: var(--cor-info-fundo);
}

.alerta--info .alerta__icone {
  color: var(--cor-info);
}
</style>
