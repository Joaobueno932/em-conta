<script setup lang="ts">
import { useMutation, useQueryClient } from '@tanstack/vue-query'
import { computed } from 'vue'

import { useHttpClient } from '@/app/injection'
import { describeApiError } from '@/shared/api/errors'

import {
  chavesAtendimento,
  conteudoAnexo,
  formatarTamanho,
  novaTentativaAnexo,
  ROTULO_CATEGORIA,
  type Anexo,
  type CategoriaAnexo,
} from './atendimento-api'

/**
 * Anexo dentro de uma mensagem: miniatura de imagem, PDF com visualizar/baixar e áudio com controles
 * nativos (sem reprodução automática; nada é carregado antes de pedir). O conteúdo vem só da rota
 * autenticada. "Tentar de novo" só para download recebido que falhou, por quem pode agir.
 */
const props = defineProps<{
  atendimento: number
  anexo: Anexo
  legenda?: string
  somenteEste?: boolean
  podeTentarDeNovo?: boolean
}>()

const http = useHttpClient()
const queryClient = useQueryClient()

const categoria = computed(() => props.anexo.categoria as CategoriaAnexo)
const disponivel = computed(() => props.anexo.situacao === 'DISPONIVEL')
const nome = computed(() => props.anexo.nome ?? `${ROTULO_CATEGORIA[categoria.value]} sem nome`)
const url = (download = false) =>
  conteudoAnexo(props.atendimento, props.anexo.id, { somenteEste: props.somenteEste, download })

const novaTentativa = useMutation({
  mutationFn: () => novaTentativaAnexo(http, props.atendimento, props.anexo.id),
  onSettled: () => queryClient.invalidateQueries({ queryKey: chavesAtendimento.todos }),
})

const estado = computed(() => {
  switch (props.anexo.situacao) {
    case 'RECEBENDO':
    case 'PENDENTE':
    case 'PROCESSANDO':
      return 'Processando o arquivo…'
    case 'FALHA_TEMPORARIA':
      return 'Falha temporária ao baixar o arquivo; nova tentativa automática em instantes.'
    case 'FALHA':
      return `Não foi possível obter o arquivo${props.anexo.erro ? `: ${props.anexo.erro}` : '.'}`
    case 'NAO_SUPORTADO':
      return `Arquivo ${props.anexo.mimeType ? `(${props.anexo.mimeType}) ` : ''}não suportado nesta versão.`
    default:
      return ''
  }
})
</script>

<template>
  <div class="anexo" :class="`anexo--${categoria.toLowerCase()}`">
    <template v-if="disponivel">
      <a
        v-if="categoria === 'IMAGEM'"
        :href="url()"
        target="_blank"
        rel="noopener"
        class="miniatura"
        :aria-label="`Abrir imagem ${nome}`"
      >
        <img :src="url()" :alt="legenda || nome" loading="lazy" />
      </a>
      <div v-else-if="categoria === 'DOCUMENTO'" class="documento">
        <span aria-hidden="true">📄</span>
        <span class="nome">{{ nome }}</span>
        <span class="ajuda">PDF · {{ formatarTamanho(anexo.tamanho) }}</span>
        <span class="acoes">
          <a :href="url()" target="_blank" rel="noopener" :aria-label="`Visualizar ${nome}`"
            >Visualizar</a
          >
          <a :href="url(true)" :download="nome" :aria-label="`Baixar ${nome}`">Baixar</a>
        </span>
      </div>
      <div v-else class="audio">
        <audio controls preload="none" :src="url()" :aria-label="`Áudio ${nome}`"></audio>
        <a :href="url(true)" :download="nome" class="ajuda">Baixar áudio</a>
      </div>
    </template>
    <p
      v-else
      class="estado"
      :class="{ falha: ['FALHA', 'NAO_SUPORTADO'].includes(anexo.situacao) }"
      role="status"
    >
      {{ ROTULO_CATEGORIA[categoria] }}<template v-if="anexo.nome"> “{{ anexo.nome }}”</template>:
      {{ estado }}
      <button
        v-if="anexo.situacao === 'FALHA' && podeTentarDeNovo"
        type="button"
        class="link"
        :disabled="novaTentativa.isPending.value"
        @click="novaTentativa.mutate()"
      >
        Tentar de novo
      </button>
    </p>
    <p v-if="novaTentativa.isError.value" class="estado falha" role="alert">
      {{ describeApiError(novaTentativa.error.value) }}
    </p>
  </div>
</template>

<style scoped>
.anexo {
  margin: 0.25rem 0;
}

.miniatura img {
  display: block;
  max-width: min(16rem, 100%);
  max-height: 14rem;
  border-radius: 6px;
  border: 1px solid var(--cor-borda);
  object-fit: contain;
  background: var(--cor-superficie-2);
}

.documento {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 0.35rem 0.5rem;
}

.documento .nome {
  font-weight: 600;
  overflow-wrap: anywhere;
}

.documento .acoes {
  display: inline-flex;
  gap: 0.75rem;
}

.audio {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
}

.audio audio {
  max-width: 100%;
}

.estado {
  margin: 0;
  font-size: 0.85rem;
  color: var(--cor-texto-2);
}

.estado.falha {
  color: var(--cor-erro);
}
</style>
