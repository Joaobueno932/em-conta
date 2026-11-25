<script setup lang="ts">
import IconeTraco from './IconeTraco.vue'
import { avisosAtivos, fecharAviso } from './use-avisos'

// Região viva sempre presente: o leitor de tela anuncia cada aviso novo sem tirar o foco.
</script>

<template>
  <div class="avisos" role="status" aria-live="polite">
    <div v-for="aviso in avisosAtivos" :key="aviso.id" class="aviso">
      <IconeTraco class="icone-ok" nome="check-circulo" :tamanho="20" />
      <div class="textos">
        <strong>{{ aviso.titulo }}</strong>
        <span v-if="aviso.texto">{{ aviso.texto }}</span>
      </div>
      <button
        type="button"
        class="fechar"
        aria-label="Fechar aviso"
        title="Fechar aviso"
        @click="fecharAviso(aviso.id)"
      >
        <IconeTraco nome="fechar" :tamanho="16" />
      </button>
    </div>
  </div>
</template>

<style scoped>
/* Prancha "Dados atualizados": 378 px, a 12 px do cabeçalho e 32 px da borda direita. */
.avisos {
  position: fixed;
  top: 85px;
  right: 32px;
  z-index: 50;
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: min(378px, calc(100vw - 32px));
  pointer-events: none;
}

.aviso {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 14px 14px 34px;
  border-radius: 10px;
  background: var(--cor-superficie);
  box-shadow: var(--sombra-flutuante);
  pointer-events: auto;
}

.aviso::before {
  content: '';
  position: absolute;
  left: 16px;
  top: 14px;
  bottom: 14px;
  width: 4px;
  border-radius: 2px;
  background: var(--cor-destaque);
}

.icone-ok {
  color: var(--cor-destaque);
}

.textos {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  font-size: 13px;
  color: var(--cor-texto-2);
}

.textos strong {
  color: var(--cor-texto);
  font-size: 14px;
  font-weight: 600;
}

button.fechar {
  display: inline-flex;
  padding: 2px;
  border: none;
  background: none;
  color: var(--cor-texto-2);
}

@media (max-width: 40rem) {
  .avisos {
    top: 64px;
    right: 16px;
  }
}
</style>
