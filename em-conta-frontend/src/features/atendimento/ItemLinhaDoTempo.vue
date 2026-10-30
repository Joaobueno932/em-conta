<script setup lang="ts">
import AnexoMensagem from './AnexoMensagem.vue'
import {
  formatarHora,
  ROTULO_EVENTO,
  ROTULO_SITUACAO_MENSAGEM,
  SITUACOES_SEM_ENVIO,
  type Item,
} from './atendimento-api'

/**
 * Um item da linha do tempo (AT-04/HG-03): evento em pílula centralizada ou mensagem em balão (entrada
 * à esquerda, saída à direita), com anexo, legenda, horário e situação do envio por extenso (nunca
 * só pela cor). Usado na conversa e no detalhe somente leitura do histórico.
 */
defineProps<{
  item: Item
  atendimento: number
  somenteEste?: boolean
  podeTentarDeNovo?: boolean
  destacada?: boolean
}>()
</script>

<template>
  <p v-if="item.tipo === 'EVENTO'" class="evento">
    {{ formatarHora(item.ocorridoEm) }} ·
    <template v-if="item.evento === 'ATENDIMENTO_INICIADO'">{{
      ROTULO_EVENTO[item.evento]
    }}</template>
    <template v-else>
      {{ item.autor?.nome }} {{ ROTULO_EVENTO[item.evento ?? ''] ?? item.evento }}
      <template v-if="item.usuarioAlvo">para {{ item.usuarioAlvo.nome }}</template>
      <template v-if="item.associado">{{ item.associado.nome }}</template>
      <template v-if="item.motivo">({{ item.motivo }})</template>
    </template>
  </p>
  <div
    v-else
    :id="`mensagem-${item.id}`"
    class="mensagem"
    :class="[item.direcao === 'SAIDA' ? 'saida' : 'entrada', { destacada }]"
    tabindex="-1"
  >
    <p v-if="item.tipoMensagem === 'NAO_SUPORTADO'" class="ajuda">
      Mensagem do tipo “{{ item.tipoExterno }}” não suportada nesta versão.
    </p>
    <template v-else-if="item.anexo">
      <AnexoMensagem
        :atendimento="atendimento"
        :anexo="item.anexo"
        :legenda="item.texto"
        :somente-este="somenteEste"
        :pode-tentar-de-novo="podeTentarDeNovo"
      />
      <p v-if="item.texto" class="texto">{{ item.texto }}</p>
    </template>
    <p v-else class="texto">{{ item.texto }}</p>
    <p class="meta">
      <span v-if="item.autor">{{ item.autor.nome }} · </span>
      {{ formatarHora(item.ocorridoEm) }}
      <span
        v-if="item.direcao === 'SAIDA'"
        :class="{ falha: SITUACOES_SEM_ENVIO.includes(item.situacao ?? '') }"
      >
        · {{ ROTULO_SITUACAO_MENSAGEM[item.situacao ?? ''] }}
        <template v-if="item.situacao === 'FALHA' && item.erroTitulo"
          >: {{ item.erroTitulo }}</template
        >
      </span>
    </p>
  </div>
</template>

<style scoped>
.evento {
  align-self: center;
  margin: 0.25rem 0;
  padding: 0.2rem 0.75rem;
  border-radius: 6px;
  background: var(--cor-superficie-2);
  color: var(--cor-texto-2);
  font-size: 0.8rem;
  text-align: center;
}

.mensagem {
  max-width: 75%;
  padding: 0.6rem 0.85rem;
  border-radius: 10px;
  background: var(--cor-superficie);
  border: 1px solid var(--cor-borda);
  transition: box-shadow 0.3s;
}

.mensagem.saida {
  align-self: flex-end;
  background: var(--cor-destaque-suave);
  border-color: var(--cor-destaque-borda);
}

/* Mensagem aberta pela área Mídia: destaque discreto por alguns segundos. */
.mensagem.destacada {
  box-shadow: 0 0 0 3px var(--cor-destaque-borda);
}

.mensagem:focus-visible {
  outline: 2px solid var(--cor-foco);
}

.mensagem p {
  margin: 0;
}

.texto {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.meta {
  font-size: 0.75rem;
  color: var(--cor-texto-2);
  margin-top: 0.2rem !important;
  text-align: right;
}

.falha {
  color: var(--cor-erro);
}
</style>
