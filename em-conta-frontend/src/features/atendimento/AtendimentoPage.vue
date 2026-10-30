<script setup lang="ts">
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, nextTick, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'

import { chavesAtendimento, definirPresenca, estadoCanal, obterPresenca } from './atendimento-api'
import ConversaAtendimento from './ConversaAtendimento.vue'
import FilaAtendimento from './FilaAtendimento.vue'
import PainelAssociado from './PainelAssociado.vue'

/** Atendimento via WhatsApp (AT-01..AT-06): fila, conversa e painel do associado. */
const http = useHttpClient()
const route = useRoute()
const router = useRouter()
const queryClient = useQueryClient()

// O atendimento aberto fica na URL: recarregar a página ou compartilhar o link mantém a conversa.
const selecionado = computed(() => {
  const id = Number(route.query.id)
  return Number.isInteger(id) && id > 0 ? id : null
})
const painelAberto = ref(true)
const conversa = ref<InstanceType<typeof ConversaAtendimento> | null>(null)
const painel = ref<InstanceType<typeof PainelAssociado> | null>(null)

// Painel do associado: o X fecha e devolve o foco ao botão da conversa que o reabre.
async function fecharPainel() {
  painelAberto.value = false
  await nextTick()
  conversa.value?.focarBotaoPainel()
}
async function alternarPainel() {
  if (painelAberto.value) {
    await fecharPainel()
    return
  }
  painelAberto.value = true
  await nextTick()
  painel.value?.focar()
}

function selecionar(id: number | null) {
  painelAberto.value = true
  void router.replace({ query: id ? { id: String(id) } : {} })
}

const canal = useQuery({ queryKey: chavesAtendimento.canal, queryFn: () => estadoCanal(http) })
const presenca = useQuery({
  queryKey: chavesAtendimento.presenca,
  queryFn: () => obterPresenca(http),
})
const alterarPresenca = useMutation({
  mutationFn: (disponivel: boolean) => definirPresenca(http, disponivel),
  onSuccess: (p) => queryClient.setQueryData(chavesAtendimento.presenca, p),
})
</script>

<template>
  <main class="pagina pagina--larga atendimento">
    <header class="cabecalho-pagina">
      <div>
        <h1>Atendimento</h1>
        <p class="subtitulo">Converse e acompanhe seus associados.</p>
      </div>
      <!-- Presença manual simples: só informa os colegas, não muda a distribuição. -->
      <div v-if="presenca.data.value" class="presenca">
        <span
          class="ponto"
          :class="{ ausente: !presenca.data.value.disponivel }"
          aria-hidden="true"
        ></span>
        <label for="presenca" class="visualmente-oculto">Sua presença</label>
        <select
          id="presenca"
          :value="presenca.data.value.disponivel ? 'DISPONIVEL' : 'AUSENTE'"
          :disabled="alterarPresenca.isPending.value"
          @change="
            alterarPresenca.mutate(($event.target as HTMLSelectElement).value === 'DISPONIVEL')
          "
        >
          <option value="DISPONIVEL">Disponível</option>
          <option value="AUSENTE">Ausente</option>
        </select>
      </div>
    </header>
    <AlertaMensagem v-if="alterarPresenca.isError.value" tipo="erro">
      {{ describeApiError(alterarPresenca.error.value) }}
    </AlertaMensagem>
    <AlertaMensagem v-if="canal.data.value && !canal.data.value.configurado" tipo="aviso">
      Canal não configurado. Mensagens não são recebidas nem enviadas até a integração com o
      WhatsApp ser configurada.
    </AlertaMensagem>

    <div class="colunas" :class="{ 'sem-painel': !selecionado || !painelAberto }">
      <FilaAtendimento :selecionado="selecionado" @selecionar="selecionar" />
      <ConversaAtendimento
        v-if="selecionado"
        :id="selecionado"
        ref="conversa"
        :key="selecionado"
        :painel-aberto="painelAberto"
        @fechar="selecionar(null)"
        @alternar-painel="alternarPainel"
      />
      <section v-else class="cartao vazio">
        <p>Selecione um atendimento na fila.</p>
      </section>
      <PainelAssociado
        v-if="selecionado && painelAberto"
        :id="selecionado"
        ref="painel"
        :key="`p-${selecionado}`"
        @fechar="fecharPainel"
        @abrir-mensagem="(m, cursor) => conversa?.irParaMensagem(m, cursor)"
      />
    </div>
  </main>
</template>

<style scoped>
.atendimento {
  max-width: 96rem;
}

.cabecalho-pagina {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 1rem;
}

.cabecalho-pagina .subtitulo {
  margin: 0 0 1rem;
}

.presenca {
  display: inline-flex;
  gap: 0.4rem;
  align-items: center;
}

.presenca select {
  border-color: transparent;
  background: transparent;
  font-weight: 500;
}

.ponto {
  width: 0.6rem;
  height: 0.6rem;
  border-radius: 999px;
  background: var(--cor-destaque);
}

.ponto.ausente {
  background: var(--cor-texto-3);
}

/* Três colunas só onde cabem; abaixo disso, fila e conversa lado a lado e o painel embaixo. */
.colunas {
  display: grid;
  grid-template-columns: 20rem minmax(0, 1fr) 20rem;
  gap: 1rem;
  align-items: start;
}

.colunas.sem-painel {
  grid-template-columns: 20rem minmax(0, 1fr);
}

.vazio {
  margin: 0;
  color: var(--cor-texto-2);
  text-align: center;
  padding: 3rem 1rem;
}

@media (max-width: 90rem) {
  .colunas {
    grid-template-columns: 18rem minmax(0, 1fr);
  }

  .colunas > :nth-child(3) {
    grid-column: 1 / -1;
  }
}

@media (max-width: 60rem) {
  .colunas,
  .colunas.sem-painel {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
