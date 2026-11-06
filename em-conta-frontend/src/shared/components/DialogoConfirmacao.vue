<script setup lang="ts">
import { nextTick, ref, useId, watch } from 'vue'

/**
 * Confirmação no padrão visual do sistema (protótipo: "Descartar alterações?", "Confirmar
 * cancelamento?"), sobre o <dialog> nativo: o restante da página fica inerte, Esc cancela e o foco
 * volta ao controle que abriu. O primeiro foco fica em Cancelar, a escolha que não altera nada.
 */
const props = withDefaults(
  defineProps<{ aberto: boolean; titulo: string; confirmar: string; cancelar?: string }>(),
  { cancelar: 'Cancelar' },
)
const emit = defineEmits<{ confirmar: []; cancelar: [] }>()

const idTitulo = useId()
const dialogo = ref<HTMLDialogElement | null>(null)
let origem: HTMLElement | null = null

watch(
  () => props.aberto,
  async (aberto) => {
    await nextTick()
    const el = dialogo.value
    if (!el) return
    if (aberto && !el.open) {
      origem = document.activeElement instanceof HTMLElement ? document.activeElement : null
      el.showModal()
    } else if (!aberto && el.open) {
      el.close()
      origem?.focus()
      origem = null
    }
  },
  { immediate: true },
)
</script>

<template>
  <dialog
    ref="dialogo"
    class="dialogo"
    :aria-labelledby="idTitulo"
    @cancel.prevent="emit('cancelar')"
  >
    <h2 :id="idTitulo">{{ titulo }}</h2>
    <div class="texto"><slot /></div>
    <div class="botoes">
      <button type="button" class="secundario" @click="emit('cancelar')">{{ cancelar }}</button>
      <button type="button" @click="emit('confirmar')">{{ confirmar }}</button>
    </div>
  </dialog>
</template>

<style scoped>
.dialogo {
  width: min(28rem, calc(100vw - 32px));
  box-sizing: border-box;
  padding: 24px;
  border: 1px solid var(--cor-borda);
  border-radius: var(--raio);
  background: var(--cor-superficie);
  color: var(--cor-texto);
  box-shadow: 0 12px 32px rgb(0 0 0 / 16%);
}

.dialogo::backdrop {
  background: rgb(30 36 32 / 35%);
}

h2 {
  margin: 0 0 8px;
  font-size: 20px;
  font-weight: 600;
}

.texto {
  color: var(--cor-texto-2);
  font-size: 16px;
  line-height: 1.45;
}

.texto :deep(p) {
  margin: 0;
}

.botoes {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 20px;
}
</style>
