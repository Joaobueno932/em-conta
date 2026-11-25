<script setup lang="ts">
import { nextTick, ref, useId, watch } from 'vue'

import IconeTraco from './IconeTraco.vue'

/**
 * Modal de formulário das pranchas (título, subtítulo, fechar no canto e rodapé com as ações), sobre
 * o <dialog> nativo como o DialogoConfirmacao: o restante da página fica inerte, Esc fecha e o foco
 * volta ao controle que abriu. O primeiro campo do conteúdo recebe o foco ao abrir.
 */
const props = defineProps<{
  aberto: boolean
  titulo: string
  subtitulo?: string
  largura?: number
}>()
const emit = defineEmits<{ fechar: [] }>()

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
      el.querySelector<HTMLElement>('input, select, textarea')?.focus()
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
    class="modal"
    :style="largura ? { width: `min(${largura}px, calc(100vw - 32px))` } : undefined"
    :aria-labelledby="idTitulo"
    @cancel.prevent="emit('fechar')"
  >
    <header class="topo">
      <div>
        <h2 :id="idTitulo">{{ titulo }}</h2>
        <p v-if="subtitulo">{{ subtitulo }}</p>
      </div>
      <button
        type="button"
        class="fechar"
        aria-label="Fechar"
        title="Fechar"
        @click="emit('fechar')"
      >
        <IconeTraco nome="fechar" :tamanho="18" />
      </button>
    </header>
    <div class="corpo"><slot /></div>
    <footer v-if="$slots.rodape" class="rodape"><slot name="rodape" /></footer>
  </dialog>
</template>

<style scoped>
.modal {
  width: min(560px, calc(100vw - 32px));
  max-height: calc(100dvh - 48px);
  box-sizing: border-box;
  padding: 0;
  border: 1px solid var(--cor-borda);
  border-radius: var(--raio-cartao);
  background: var(--cor-superficie);
  color: var(--cor-texto);
  box-shadow: 0 16px 40px rgb(30 36 32 / 18%);
}

.modal::backdrop {
  background: rgb(30 36 32 / 35%);
}

/* Pranchas de overlay: título de 20 px com divisória abaixo, corpo e rodapé a 24 px das bordas. */
.topo {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  padding: 20px 24px 16px;
  border-bottom: 1px solid var(--cor-divisoria);
}

h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}

.topo p {
  margin: 2px 0 0;
  color: var(--cor-texto-2);
  font-size: 13px;
}

button.fechar {
  display: inline-flex;
  padding: 4px;
  border: none;
  border-radius: 999px;
  background: none;
  color: var(--cor-texto-2);
}

button.fechar:hover {
  background: var(--cor-superficie-2);
}

.corpo {
  padding: 18px 24px 4px;
}

.corpo :deep(input[type='text']),
.corpo :deep(input[type='email']),
.corpo :deep(input[type='password']),
.corpo :deep(select) {
  height: 42px;
}

.rodape {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 10px;
  padding: 14px 24px 18px;
  border-top: 1px solid var(--cor-divisoria);
}
</style>
