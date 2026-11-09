<script setup lang="ts">
import { useMutation, useQueryClient } from '@tanstack/vue-query'
import { computed, ref } from 'vue'

import { useHttpClient } from '@/app/injection'
import { describeApiError } from '@/shared/api/errors'
import IconeTraco from '@/shared/components/IconeTraco.vue'

import {
  chavesAtendimento,
  conteudoAnexo,
  exibivelNoNavegador,
  formatarTamanho,
  formatoDoAnexo,
  novaTentativaAnexo,
  type Anexo,
  type CategoriaAnexo,
} from './atendimento-api'

/**
 * Anexo dentro de uma mensagem: miniatura de imagem; documento identificado pelo formato real (PDF com
 * visualizar e baixar, planilha só com baixar); vídeo e áudio com controles nativos, sem reprodução
 * automática (do vídeo só os metadados são carregados, para mostrar duração e quadro). Vídeo que o
 * navegador não reproduz (3GP, por exemplo) fica com o download. O conteúdo vem só da rota
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
const formato = computed(() =>
  formatoDoAnexo(props.anexo.mimeType, props.anexo.categoria, props.anexo.nome),
)
const disponivel = computed(() => props.anexo.situacao === 'DISPONIVEL')
const nome = computed(() => props.anexo.nome ?? `${formato.value.rotulo} sem nome`)
const visualizavel = computed(() => exibivelNoNavegador(props.anexo.mimeType))
const url = (download = false) =>
  conteudoAnexo(props.atendimento, props.anexo.id, { somenteEste: props.somenteEste, download })

// Vídeo: reprodução só se o navegador declara suporte ao tipo; erro ao carregar também cai no download.
const falhaVideo = ref(false)
const reproduzVideo = computed(() => {
  if (falhaVideo.value || typeof document === 'undefined') return false
  const tipo = props.anexo.mimeType ?? ''
  return document.createElement('video').canPlayType(tipo) !== ''
})

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
      // O servidor grava o motivo com o que é aceito; sem ele, o tipo informado.
      return (
        props.anexo.erro ??
        `Formato ${props.anexo.mimeType ? `(${props.anexo.mimeType}) ` : ''}não suportado nesta versão.`
      )
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
      <div v-else-if="categoria === 'VIDEO'" class="video">
        <video
          v-if="reproduzVideo"
          controls
          playsinline
          preload="metadata"
          :src="url()"
          :aria-label="`Vídeo ${nome}`"
          @error="falhaVideo = true"
        ></video>
        <p v-else class="ajuda" role="note">
          Este navegador não reproduz {{ formato.rotulo }}; baixe o arquivo para assistir.
        </p>
        <a
          :href="url(true)"
          :download="nome"
          class="ajuda com-icone"
          :aria-label="`Baixar ${formato.rotulo.toLowerCase()} ${nome}`"
        >
          <IconeTraco nome="baixar" :tamanho="14" />
          Baixar vídeo · {{ formatarTamanho(anexo.tamanho) }}
        </a>
      </div>
      <div v-else-if="categoria === 'DOCUMENTO'" class="documento">
        <span class="selo" :class="`selo--${formato.sigla.toLowerCase()}`" aria-hidden="true">{{
          formato.sigla
        }}</span>
        <span class="descricao">
          <a
            v-if="visualizavel"
            :href="url()"
            target="_blank"
            rel="noopener"
            class="nome"
            :aria-label="`Visualizar ${nome}`"
            title="Visualizar"
            >{{ nome }}</a
          >
          <a
            v-else
            :href="url(true)"
            :download="nome"
            class="nome"
            :aria-label="`Baixar ${formato.rotulo.toLowerCase()} ${nome}`"
            title="Baixar"
            >{{ nome }}</a
          >
          <span class="ajuda">{{ formato.rotulo }} · {{ formatarTamanho(anexo.tamanho) }}</span>
        </span>
        <a
          :href="url(true)"
          :download="nome"
          class="botao-icone sem-borda"
          :aria-label="`Baixar ${nome}`"
          title="Baixar"
        >
          <IconeTraco nome="baixar" />
        </a>
      </div>
      <div v-else class="audio">
        <audio controls preload="none" :src="url()" :aria-label="`Áudio ${nome}`"></audio>
        <a
          :href="url(true)"
          :download="nome"
          class="ajuda com-icone"
          :aria-label="`Baixar ${nome}`"
        >
          <IconeTraco nome="baixar" :tamanho="14" />
          Baixar áudio
        </a>
      </div>
    </template>
    <p
      v-else
      class="estado"
      :class="{ falha: ['FALHA', 'NAO_SUPORTADO'].includes(anexo.situacao) }"
      role="status"
    >
      {{ formato.rotulo }}<template v-if="anexo.nome"> “{{ anexo.nome }}”</template>:
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

/* Cartão de documento (AT-04/HG-03): selo com a sigla do formato, nome e tipo, ação de baixar. */
.documento {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  min-width: min(16rem, 100%);
  padding: 0.5rem 0.6rem;
  border: 1px solid var(--cor-borda);
  border-radius: 8px;
  background: var(--cor-superficie);
}

.selo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 2rem;
  height: 2.25rem;
  padding: 0 0.15rem;
  border-radius: 4px;
  background: var(--cor-texto);
  color: #fff;
  font-size: 0.6rem;
  font-weight: 700;
}

.selo--pdf {
  background: var(--cor-pdf);
}

.selo--xlsx {
  background: var(--cor-planilha);
}

.descricao {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
}

.documento .nome {
  color: var(--cor-texto);
  font-weight: 600;
  font-size: 0.9rem;
  text-decoration: none;
  overflow-wrap: anywhere;
}

.documento .nome:hover {
  text-decoration: underline;
}

.audio,
.video {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
}

.audio audio {
  max-width: 100%;
}

.video video {
  display: block;
  width: min(20rem, 100%);
  max-height: 16rem;
  border-radius: 6px;
  background: #000;
}

.video .ajuda {
  margin: 0;
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
