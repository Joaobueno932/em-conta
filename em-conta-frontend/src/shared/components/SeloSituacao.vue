<script setup lang="ts">
import IconeTraco, { type NomeIcone } from './IconeTraco.vue'

/**
 * Selo de situação das listagens (pranchas: "Ativo", "Convite pendente", "Desativado"): pílula com
 * ícone e texto. A cor só reforça o texto, que é sempre visível.
 */
defineProps<{ tom: 'sucesso' | 'aviso' | 'neutro' | 'erro' | 'info'; icone?: NomeIcone }>()
</script>

<template>
  <span class="selo" :class="`selo--${tom}`">
    <IconeTraco v-if="icone" :nome="icone" :tamanho="13" :espessura="2" />
    <slot />
  </span>
</template>

<style scoped>
.selo {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 22px;
  padding: 0 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
}

.selo--sucesso {
  background: var(--cor-destaque-suave);
  color: var(--cor-destaque-forte);
}

.selo--aviso {
  background: var(--cor-aviso-fundo);
  color: var(--cor-alerta);
}

.selo--neutro {
  background: var(--cor-superficie-2);
  color: var(--cor-texto-2);
}

.selo--erro {
  background: var(--cor-erro-fundo);
  color: var(--cor-erro);
}

.selo--info {
  background: var(--cor-info-fundo);
  color: #1d4f9c;
}
</style>
