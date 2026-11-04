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
    <header class="cabecalho-pagina">
      <div>
        <h1>Atendimento</h1>
        <p class="subtitulo">Converse e acompanhe seus associados.</p>
      </div>
      <div class="situacoes">
        <p
          v-if="canal.data.value && !canal.data.value.configurado"
          class="aviso-canal"
          role="status"
        >
          <IconeTraco nome="informacao" />
          <span>
            <strong>Canal não configurado.</strong> Mensagens não são recebidas nem enviadas até a
            integração com o WhatsApp ser configurada.
          </span>
        </p>
        <!-- Presença manual simples: só informa os colegas, não muda a distribuição. -->
        <p v-if="disponivel !== undefined" class="presenca-atual">
          <span class="ponto" :class="{ ausente: !disponivel }" aria-hidden="true"></span>
          {{ disponivel ? 'Disponível' : 'Ausente' }}
        </p>
      </div>
    </header>
    <AlertaMensagem v-if="alterarPresenca.isError.value" tipo="erro">
      {{ describeApiError(alterarPresenca.error.value) }}
    </AlertaMensagem>

    <div class="colunas" :class="{ 'sem-painel': !selecionado || !painelAberto }">
      <FilaAtendimento ref="fila" :selecionado="selecionado" @selecionar="selecionar">
        <template #topo>
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
/* Frame: conteúdo com 15 px nas bordas; título 12 px mais para dentro; cartões de y 178 a 926. */
.atendimento {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 34rem;
  box-sizing: border-box;
  padding: 0 15px 15px;
}

.cabecalho-pagina {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 1rem;
  flex-shrink: 0;
  padding: 14px 23px 0 12px;
  min-height: 90px;
}

.cabecalho-pagina h1 {
  margin: 0;
  font-size: 40px;
  font-weight: 700;
  line-height: 1.2;
  letter-spacing: -0.01em;
}

.cabecalho-pagina .subtitulo {
  margin: 0;
  color: var(--cor-texto-2);
  font-size: 18px;
}

.situacoes {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 0.5rem;
  padding-top: 26px;
}

.presenca-atual {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 17px;
  font-weight: 500;
}

/* Canal desligado: aviso compacto no cabeçalho, sem tomar a área das conversas. */
.aviso-canal {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  max-width: 30rem;
  margin: -14px 0 0;
  padding: 8px 12px;
  border: 1px solid #f0d9a8;
  border-radius: var(--raio-controle);
  background: #fdf6e7;
  color: #6b4a0d;
  font-size: 14px;
  line-height: 1.4;
}

.aviso-canal .icone {
  flex-shrink: 0;
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

/* Abaixo da largura do frame: o painel desce para baixo e a página rola. */
/* Fila e conversa mantêm altura limitada (rolagem só dentro delas, leitura preservada); o painel
   desce para a linha de baixo e a página rola até ele. */
@media (max-width: 80rem) {
  .atendimento {
    height: auto;
  }

  .colunas,
  .colunas.sem-painel {
    grid-template-columns: 300px minmax(0, 1fr);
    grid-template-rows: max(32rem, calc(100dvh - 210px)) auto;
  }

  .colunas > :nth-child(3) {
    grid-column: 1 / -1;
  }
}

@media (max-width: 60rem) {
  .colunas,
  .colunas.sem-painel {
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: minmax(0, 26rem) max(32rem, calc(100dvh - 140px)) auto;
  }

  .cabecalho-pagina {
    flex-direction: column;
    padding-right: 0;
  }

  .situacoes {
    align-items: flex-start;
    padding-top: 0;
  }

  .aviso-canal {
    margin: 0;
  }
}
</style>
