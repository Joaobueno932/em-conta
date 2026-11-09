<script setup lang="ts">
import { computed, ref, useId } from 'vue'

import IconeTraco, { type NomeIcone } from '@/shared/components/IconeTraco.vue'

import { DETALHE_SITUACAO_MENSAGEM, ROTULO_SITUACAO_MENSAGEM } from './atendimento-api'

/**
 * Situação de uma mensagem enviada, ao lado do horário, como no WhatsApp: relógio enquanto não há
 * confirmação de envio (inclusive "aceita", que só confirma a requisição à Meta), um tique para enviada,
 * dois para entregue e dois azuis para lida. Falha confirmada (exclamação vermelha) e resultado
 * desconhecido ("Envio não confirmado", que nunca é reenviado sozinho) mostram a causa num detalhe que
 * abre por clique, toque ou teclado; o mouse também vê a causa no tooltip.
 *
 * Mostra só a situação atual. Que ela não regride quando um status chega fora de ordem (ex.: `sent`
 * depois de `read`) é garantido pelo servidor e pela mescla da linha do tempo (`maisNovo`).
 */
const props = defineProps<{ situacao?: string | null; erroTitulo?: string | null }>()

interface Visual {
  icone: NomeIcone
  tom: 'neutro' | 'lida' | 'falha' | 'incerto'
  rotulo: string
  detalhe?: string
  texto?: string
}

const APARENCIA: Record<string, Pick<Visual, 'icone' | 'tom'>> = {
  PENDENTE: { icone: 'relogio', tom: 'neutro' },
  ENVIANDO: { icone: 'relogio', tom: 'neutro' },
  ACEITA: { icone: 'relogio', tom: 'neutro' },
  ENVIADA: { icone: 'concluir', tom: 'neutro' },
  ENTREGUE: { icone: 'tique-duplo', tom: 'neutro' },
  LIDA: { icone: 'tique-duplo', tom: 'lida' },
  FALHA: { icone: 'exclamacao', tom: 'falha' },
  JANELA_EXPIRADA: { icone: 'exclamacao', tom: 'falha' },
  DESCONHECIDA: { icone: 'interrogacao', tom: 'incerto' },
}

const visual = computed<Visual | null>(() => {
  const situacao = props.situacao ?? ''
  const aparencia = APARENCIA[situacao]
  if (!aparencia) return null
  const rotulo = ROTULO_SITUACAO_MENSAGEM[situacao]!
  if (situacao === 'FALHA')
    return {
      ...aparencia,
      rotulo,
      detalhe: props.erroTitulo ? `${rotulo}: ${props.erroTitulo}` : rotulo,
    }
  return {
    ...aparencia,
    rotulo,
    detalhe: DETALHE_SITUACAO_MENSAGEM[situacao],
    // "Envio não confirmado" fica escrito: não é falha confirmada e pede ação do atendente.
    texto: situacao === 'DESCONHECIDA' ? rotulo : undefined,
  }
})

const aberto = ref(false)
const idDetalhe = useId()
</script>

<template>
  <template v-if="visual">
    <span
      v-if="!visual.detalhe"
      class="indicador"
      :class="visual.tom"
      role="img"
      :aria-label="visual.rotulo"
      :title="visual.rotulo"
    >
      <IconeTraco :nome="visual.icone" :tamanho="16" />
    </span>
    <span v-else class="com-detalhe">
      <button
        type="button"
        class="indicador"
        :class="visual.tom"
        :title="visual.detalhe"
        :aria-expanded="aberto"
        :aria-controls="idDetalhe"
        @click="aberto = !aberto"
        @keydown.esc="aberto = false"
      >
        <IconeTraco :nome="visual.icone" :tamanho="16" />
        <span v-if="visual.texto">{{ visual.texto }}</span>
        <span v-else class="visualmente-oculto">{{ visual.rotulo }}</span>
        <span class="visualmente-oculto">{{ aberto ? ': ocultar detalhe' : ': ver detalhe' }}</span>
      </button>
      <span v-show="aberto" :id="idDetalhe" class="detalhe" :class="visual.tom">{{
        visual.detalhe
      }}</span>
    </span>
  </template>
</template>

<style scoped>
.indicador {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  vertical-align: -3px;
  color: var(--cor-texto-2);
}

button.indicador {
  margin: 0;
  padding: 0 2px;
  border: 0;
  border-radius: 4px;
  background: none;
  font: inherit;
  cursor: pointer;
}

button.indicador:focus-visible {
  outline: 2px solid var(--cor-foco);
  outline-offset: 1px;
}

.lida {
  color: var(--cor-lida);
}

.falha {
  color: var(--cor-erro);
}

.incerto {
  color: var(--cor-alerta);
}

/* Texto pequeno em âmbar ficaria abaixo de 4,5:1: só o ícone leva a cor de alerta. */
.incerto > span,
.detalhe.incerto {
  color: var(--cor-texto);
}

.com-detalhe {
  display: inline;
}

/* A causa ocupa a linha de baixo, dentro do balão, alinhada ao horário. */
.detalhe {
  display: block;
  margin-top: 2px;
  text-align: right;
  white-space: normal;
  overflow-wrap: anywhere;
}
</style>
