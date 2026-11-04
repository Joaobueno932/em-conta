<script setup lang="ts">
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, nextTick, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'
import IconeTraco from '@/shared/components/IconeTraco.vue'

import { chavesAtendimento, definirPresenca, estadoCanal, obterPresenca } from './atendimento-api'
import ConversaAtendimento from './ConversaAtendimento.vue'
import FilaAtendimento from './FilaAtendimento.vue'
import PainelAssociado from './PainelAssociado.vue'

/**
 * Atendimento via WhatsApp (AT-01..AT-06): fila (340 px), conversa e painel do associado (278 px)
 * ocupando a altura disponível; cada coluna rola por dentro.
 */
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
const fila = ref<InstanceType<typeof FilaAtendimento> | null>(null)
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
const disponivel = computed(() => presenca.data.value?.disponivel)
</script>

<template>
  <main class="atendimento">
    <!-- O título fica no cabeçalho global (meta.tituloCabecalho); aqui só o aviso real do canal. -->
    <p v-if="canal.data.value && !canal.data.value.configurado" class="aviso-canal" role="status">
      <IconeTraco nome="informacao" :tamanho="18" />
      <span>
        <strong>Canal não configurado.</strong> Mensagens não são recebidas nem enviadas até a
        integração com o WhatsApp ser configurada.
      </span>
    </p>
    <AlertaMensagem v-if="alterarPresenca.isError.value" tipo="erro">
      {{ describeApiError(alterarPresenca.error.value) }}
    </AlertaMensagem>

    <div class="colunas" :class="{ 'sem-painel': !selecionado || !painelAberto }">
      <FilaAtendimento ref="fila" :selecionado="selecionado" @selecionar="selecionar">
        <template #topo>
          <!-- Presença manual simples: só informa os colegas, não muda a distribuição. -->
          <div v-if="presenca.data.value" class="presenca">
            <span class="ponto" :class="{ ausente: !disponivel }" aria-hidden="true"></span>
            <label for="presenca" class="visualmente-oculto">Sua presença</label>
            <select
              id="presenca"
              :value="disponivel ? 'DISPONIVEL' : 'AUSENTE'"
              :disabled="alterarPresenca.isPending.value"
              @change="
                alterarPresenca.mutate(($event.target as HTMLSelectElement).value === 'DISPONIVEL')
              "
            >
              <option value="DISPONIVEL">Disponível</option>
              <option value="AUSENTE">Ausente</option>
            </select>
          </div>
        </template>
      </FilaAtendimento>
      <ConversaAtendimento
        v-if="selecionado"
        :id="selecionado"
        ref="conversa"
        :key="selecionado"
        :painel-aberto="painelAberto"
        @fechar="selecionar(null)"
        @alternar-painel="alternarPainel"
      />
      <section v-else class="cartao vazio" aria-labelledby="titulo-vazio">
        <span class="selo-vazio" aria-hidden="true"
          ><IconeTraco nome="conversa" :tamanho="40"
        /></span>
        <h2 id="titulo-vazio">Tudo pronto para atender</h2>
        <p>Selecione uma conversa em Aguardando para iniciar o atendimento.</p>
        <button type="button" class="contorno" @click="fila?.mostrarVisao('AGUARDANDO')">
          Ver fila de espera
        </button>
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
/* Fila, conversa e painel logo abaixo do cabeçalho global, até o fim da janela. */
.atendimento {
  display: flex;
  flex-direction: column;
  gap: 10px;
  height: 100%;
  min-height: 28rem;
  box-sizing: border-box;
  padding: 12px 15px 15px;
}

/* Canal desligado: uma linha compacta acima das colunas, que quebra em telas estreitas. */
.aviso-canal {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  flex-shrink: 0;
  margin: 0;
  padding: 6px 12px;
  border: 1px solid #f0d9a8;
  border-radius: var(--raio-controle);
  background: #fdf6e7;
  color: #6b4a0d;
  font-size: 14px;
  line-height: 1.4;
}

.aviso-canal .icone {
  flex-shrink: 0;
  margin-top: 1px;
  color: var(--cor-alerta);
}

.presenca {
  display: inline-flex;
  gap: 8px;
  align-items: center;
}

.presenca select {
  padding: 2px 4px;
  border-color: transparent;
  background: transparent;
  font-size: 16px;
  font-weight: 500;
}

.ponto {
  width: 9px;
  height: 9px;
  border-radius: 999px;
  background: var(--cor-destaque);
  flex-shrink: 0;
}

.ponto.ausente {
  background: var(--cor-texto-3);
}

.colunas {
  display: grid;
  grid-template-columns: 340px minmax(0, 1fr) 278px;
  gap: 16px;
  flex: 1;
  min-height: 0;
}

.colunas.sem-painel {
  grid-template-columns: 340px minmax(0, 1fr);
}

.colunas > :deep(*) {
  min-height: 0;
}

/* AT-01: estado sem conversa selecionada, centralizado no cartão. */
.vazio {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  margin: 0;
  padding: 2rem;
  text-align: center;
}

.selo-vazio {
  display: grid;
  place-items: center;
  width: 117px;
  height: 117px;
  margin-bottom: 18px;
  border-radius: 999px;
  background: var(--cor-destaque-suave);
  color: var(--cor-destaque);
}

.vazio h2 {
  margin: 0 0 8px;
  font-size: 32px;
  font-weight: 700;
}

.vazio p {
  margin: 0 0 16px;
  color: var(--cor-texto-2);
  font-size: 17px;
}

button.contorno {
  padding: 10px 34px;
  border: 1.5px solid var(--cor-destaque);
  border-radius: var(--raio-controle);
  background: var(--cor-superficie);
  color: var(--cor-destaque-forte);
  font-size: 16px;
  font-weight: 500;
}

button.contorno:hover {
  background: var(--cor-destaque-suave);
}

/* Fila e conversa ocupam a altura que sobra na janela (descontado o aviso do canal, se houver) e
   rolam por dentro, preservando a leitura; o painel desce para a linha de baixo e a página rola
   até ele. */
@media (max-width: 80rem) {
  .colunas,
  .colunas.sem-painel {
    grid-template-columns: 320px minmax(0, 1fr);
    grid-template-rows: max(28rem, 100%) auto;
  }

  .colunas > :nth-child(3) {
    grid-column: 1 / -1;
    margin-bottom: 15px;
  }
}

@media (max-width: 60rem) {
  .atendimento {
    height: auto;
  }

  .colunas > :nth-child(3) {
    margin-bottom: 0;
  }

  .colunas,
  .colunas.sem-painel {
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: minmax(0, 26rem) max(32rem, calc(100dvh - 140px)) auto;
  }
}
</style>
