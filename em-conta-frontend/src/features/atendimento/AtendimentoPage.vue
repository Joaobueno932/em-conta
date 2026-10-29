<script setup lang="ts">
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, ref } from 'vue'
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
      <h1>Atendimento</h1>
      <div class="acoes">
        <!-- Presença manual simples: só informa os colegas, não muda a distribuição. -->
        <label v-if="presenca.data.value" class="presenca">
          <input
            type="checkbox"
            :checked="presenca.data.value.disponivel"
            :disabled="alterarPresenca.isPending.value"
            @change="alterarPresenca.mutate(($event.target as HTMLInputElement).checked)"
          />
          {{ presenca.data.value.disponivel ? 'Disponível' : 'Ausente' }}
        </label>
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
        :key="selecionado"
        @fechar="selecionar(null)"
      />
      <section v-else class="cartao vazio">
        <p>Selecione um atendimento na fila.</p>
      </section>
      <PainelAssociado
        v-if="selecionado && painelAberto"
        :id="selecionado"
        :key="`p-${selecionado}`"
        @fechar="painelAberto = false"
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
  align-items: center;
}

.presenca {
  display: inline-flex;
  gap: 0.4rem;
  align-items: center;
}

.colunas {
  display: grid;
  grid-template-columns: 20rem minmax(0, 1fr) 18rem;
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

@media (max-width: 70rem) {
  .colunas,
  .colunas.sem-painel {
    grid-template-columns: 1fr;
  }
}
</style>
