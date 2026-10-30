<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { computed } from 'vue'
import { RouterLink } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'

import {
  chavesAtendimento,
  formatarDataHora,
  nomeContato,
  obterDetalhe,
  ROTULO_EVENTO,
  ROTULO_SITUACAO_MENSAGEM,
} from './atendimento-api'
import AnexoMensagem from './AnexoMensagem.vue'
import MidiaConversa from './MidiaConversa.vue'
import { useLinhaDoTempo } from './use-linha-do-tempo'

/** Detalhe de um atendimento (HG-03), somente leitura: só as mensagens e eventos deste ciclo. */
const props = defineProps<{ id: string }>()
const http = useHttpClient()
const id = computed(() => Number(props.id))

const detalhe = useQuery({
  queryKey: computed(() => chavesAtendimento.detalhe(id.value)),
  queryFn: () => obterDetalhe(http, id.value),
})
// Somente leitura e só deste ciclo; páginas anteriores sob demanda, sem consulta periódica.
const linha = useLinhaDoTempo(id, true, false)
const recente = linha.recente
const resumo = computed(() => detalhe.data.value?.resumo)

/** "Ver na conversa" na área Mídia: vai até a mensagem, se já estiver carregada. */
function irPara(mensagemId: number) {
  const alvo = document.getElementById(`mensagem-${mensagemId}`)
  alvo?.scrollIntoView({ block: 'center' })
  alvo?.focus()
}
</script>

<template>
  <main class="pagina pagina--larga">
    <p><RouterLink :to="{ name: 'atendimento-historico' }">← Histórico geral</RouterLink></p>
    <p v-if="detalhe.isPending.value" role="status">Carregando atendimento…</p>
    <AlertaMensagem v-else-if="detalhe.isError.value" tipo="erro">
      {{ describeApiError(detalhe.error.value) }}
    </AlertaMensagem>
    <template v-else-if="resumo">
      <h1>Atendimento {{ resumo.codigo }}</h1>
      <dl class="cartao dados">
        <div>
          <dt>Contato</dt>
          <dd>{{ nomeContato(resumo) }} (+{{ resumo.contatoNumero }})</dd>
        </div>
        <div>
          <dt>Associado</dt>
          <dd>{{ resumo.associado?.nome ?? '—' }}</dd>
        </div>
        <div>
          <dt>Atendente</dt>
          <dd>{{ resumo.responsavel?.nome ?? '—' }}</dd>
        </div>
        <div>
          <dt>Início</dt>
          <dd>{{ formatarDataHora(resumo.iniciadoEm) }}</dd>
        </div>
        <div>
          <dt>Encerramento</dt>
          <dd>
            {{ resumo.encerradoEm ? formatarDataHora(resumo.encerradoEm) : 'Em aberto' }}
            <template v-if="resumo.encerradoPor">por {{ resumo.encerradoPor.nome }}</template>
          </dd>
        </div>
      </dl>

      <MidiaConversa :atendimento="id" somente-este @abrir-mensagem="irPara" />

      <h2>Mensagens e eventos</h2>
      <p v-if="recente.isPending.value" role="status">Carregando…</p>
      <AlertaMensagem v-else-if="recente.isError.value" tipo="erro">
        {{ describeApiError(recente.error.value) }}
      </AlertaMensagem>
      <template v-else>
        <div class="anteriores">
          <p v-if="linha.carregandoAnteriores.value" role="status">
            Carregando mensagens anteriores…
          </p>
          <template v-else-if="linha.erroAnteriores.value">
            <AlertaMensagem tipo="erro">
              Não foi possível carregar as mensagens anteriores.
              {{ describeApiError(linha.erroAnteriores.value) }}
            </AlertaMensagem>
            <button type="button" class="secundario" @click="linha.carregarAnteriores()">
              Tentar de novo
            </button>
          </template>
          <button
            v-else-if="linha.temAnteriores.value"
            type="button"
            class="secundario"
            @click="linha.carregarAnteriores()"
          >
            Carregar mensagens anteriores
          </button>
          <p v-else class="ajuda">Início do histórico</p>
        </div>
        <ol class="cartao linha">
          <li
            v-for="item in linha.itens.value"
            :id="item.tipo === 'MENSAGEM' ? `mensagem-${item.id}` : undefined"
            :key="`${item.tipo}-${item.id}`"
            tabindex="-1"
          >
            <span class="ajuda">{{ formatarDataHora(item.ocorridoEm) }}</span>
            <template v-if="item.tipo === 'EVENTO'">
              <em>
                {{ item.autor?.nome }} {{ ROTULO_EVENTO[item.evento ?? ''] ?? item.evento }}
                <template v-if="item.usuarioAlvo">para {{ item.usuarioAlvo.nome }}</template>
                <template v-if="item.associado">{{ item.associado.nome }}</template>
                <template v-if="item.motivo">({{ item.motivo }})</template>
              </em>
            </template>
            <template v-else>
              <strong
                >{{
                  item.direcao === 'ENTRADA' ? 'Contato' : (item.autor?.nome ?? 'Equipe')
                }}:</strong
              >
              <AnexoMensagem
                v-if="item.anexo"
                :atendimento="id"
                :anexo="item.anexo"
                :legenda="item.texto"
                somente-este
              />
              <span class="texto">
                {{
                  item.tipoMensagem === 'NAO_SUPORTADO'
                    ? `[${item.tipoExterno} não suportado]`
                    : item.texto
                }}
              </span>
              <span v-if="item.direcao === 'SAIDA'" class="ajuda">
                {{ ROTULO_SITUACAO_MENSAGEM[item.situacao ?? ''] }}
              </span>
            </template>
          </li>
        </ol>
      </template>
    </template>
  </main>
</template>

<style scoped>
.dados {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(12rem, 1fr));
  gap: 0.75rem;
}

.dados dt {
  color: var(--cor-texto-2);
  font-size: 0.8rem;
}

.dados dd {
  margin: 0;
}

.anteriores {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0.25rem;
  margin-bottom: 0.5rem;
}

.linha {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.linha li {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
}

.texto {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
