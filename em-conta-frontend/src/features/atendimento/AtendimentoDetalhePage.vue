<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { computed, nextTick, ref } from 'vue'
import { RouterLink } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'
import IconeTraco from '@/shared/components/IconeTraco.vue'

import {
  chavesAtendimento,
  comecaDia,
  formatarDataHora,
  iniciais,
  nomeContato,
  obterDetalhe,
  rotuloDia,
} from './atendimento-api'
import ItemLinhaDoTempo from './ItemLinhaDoTempo.vue'
import MidiaConversa from './MidiaConversa.vue'
import { useLinhaDoTempo } from './use-linha-do-tempo'

/**
 * Detalhe de um atendimento (HG-03), somente leitura: só as mensagens, eventos e anexos deste ciclo.
 * Exportar conversa, notas e etiquetas do protótipo dependem de entregas futuras.
 */
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
const titulo = computed(() =>
  resumo.value ? (resumo.value.associado?.nome ?? nomeContato(resumo.value)) : '',
)

// "Ver na conversa" na área Mídia: carrega o contexto da mensagem se preciso e vai até ela.
const aviso = ref('')
const destacada = ref<number | null>(null)
async function irPara(mensagemId: number, cursor: string) {
  aviso.value = 'Localizando a mensagem…'
  try {
    if (!(await linha.carregarAte(cursor, mensagemId))) {
      aviso.value = 'Esta mensagem não está disponível neste atendimento.'
      return
    }
  } catch (erro) {
    aviso.value = `Não foi possível abrir a mensagem. ${describeApiError(erro)}`
    return
  }
  await nextTick()
  const alvo = document.getElementById(`mensagem-${mensagemId}`)
  if (!alvo) {
    aviso.value = 'Esta mensagem não está disponível neste atendimento.'
    return
  }
  aviso.value = ''
  alvo.scrollIntoView({ block: 'center' })
  alvo.focus({ preventScroll: true })
  destacada.value = mensagemId
  window.setTimeout(() => {
    if (destacada.value === mensagemId) destacada.value = null
  }, 3000)
}
</script>

<template>
  <main class="pagina pagina--larga detalhe">
    <p v-if="detalhe.isPending.value" role="status">Carregando atendimento…</p>
    <AlertaMensagem v-else-if="detalhe.isError.value" tipo="erro">
      {{ describeApiError(detalhe.error.value) }}
    </AlertaMensagem>
    <template v-else-if="resumo">
      <header class="cabecalho">
        <RouterLink
          :to="{ name: 'atendimento-historico' }"
          class="botao-icone sem-borda"
          aria-label="Voltar ao histórico geral"
          title="Voltar ao histórico geral"
        >
          <IconeTraco nome="voltar" :tamanho="22" />
        </RouterLink>
        <div>
          <h1>{{ titulo }}</h1>
          <nav aria-label="Trilha" class="trilha">
            <RouterLink :to="{ name: 'atendimento-historico' }">Histórico geral</RouterLink>
            <span aria-hidden="true">/</span>
            <span aria-current="page">Detalhes do atendimento {{ resumo.codigo }}</span>
          </nav>
        </div>
      </header>

      <div class="colunas">
        <section class="cartao conversa" aria-labelledby="titulo-ciclo">
          <header class="topo">
            <span class="avatar" aria-hidden="true">{{ iniciais(titulo) }}</span>
            <h2 id="titulo-ciclo">{{ titulo }}</h2>
            <span class="etiqueta">{{
              resumo.situacao === 'ENCERRADO' ? 'Encerrado' : 'Em aberto'
            }}</span>
          </header>

          <p v-if="aviso" class="ajuda" role="status">{{ aviso }}</p>
          <p v-if="recente.isPending.value" role="status">Carregando mensagens…</p>
          <AlertaMensagem v-else-if="recente.isError.value" tipo="erro">
            {{ describeApiError(recente.error.value) }}
          </AlertaMensagem>
          <div v-else class="linha" aria-label="Mensagens e eventos">
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
            <template v-for="(item, indice) in linha.itens.value" :key="`${item.tipo}-${item.id}`">
              <p v-if="comecaDia(linha.itens.value, indice)" class="dia">
                {{ rotuloDia(item.ocorridoEm) }}
              </p>
              <ItemLinhaDoTempo
                :item="item"
                :atendimento="id"
                somente-este
                :destacada="destacada === item.id"
              />
            </template>
            <template v-if="linha.erroLacuna.value">
              <AlertaMensagem tipo="erro">
                Algumas mensagens ainda não foram carregadas.
                {{ describeApiError(linha.erroLacuna.value) }}
              </AlertaMensagem>
              <button type="button" class="secundario" @click="linha.recuperarLacunas()">
                Tentar de novo
              </button>
            </template>
            <p v-else-if="linha.lacunaPendente.value" class="ajuda" role="status">
              Carregando mensagens intermediárias…
            </p>
          </div>

          <footer class="somente-leitura">
            <IconeTraco nome="cadeado" />
            {{
              resumo.situacao === 'ENCERRADO'
                ? 'Conversa encerrada · Somente leitura'
                : 'Atendimento em aberto · Somente consulta'
            }}
          </footer>
        </section>

        <aside class="cartao dados" aria-labelledby="titulo-dados">
          <h2 id="titulo-dados">Dados do atendimento</h2>
          <h3>Sobre o contato</h3>
          <dl>
            <div>
              <dt>Nome</dt>
              <dd>{{ nomeContato(resumo) }}</dd>
            </div>
            <div>
              <dt>Telefone</dt>
              <dd>+{{ resumo.contatoNumero }}</dd>
            </div>
            <div>
              <dt>Associado</dt>
              <dd>
                <RouterLink
                  v-if="resumo.associado"
                  :to="{ name: 'associado', params: { id: resumo.associado.id } }"
                  >{{ resumo.associado.nome }}</RouterLink
                >
                <template v-else>—</template>
              </dd>
            </div>
          </dl>
          <h3>Sobre o atendimento</h3>
          <dl>
            <div>
              <dt>Protocolo</dt>
              <dd>{{ resumo.codigo }}</dd>
            </div>
            <div>
              <dt>Atendente</dt>
              <dd>{{ resumo.responsavel?.nome ?? '—' }}</dd>
            </div>
            <div>
              <dt>Encerrado por</dt>
              <dd>{{ resumo.encerradoPor?.nome ?? '—' }}</dd>
            </div>
            <div>
              <dt>Início</dt>
              <dd>{{ formatarDataHora(resumo.iniciadoEm) }}</dd>
            </div>
            <div>
              <dt>Encerramento</dt>
              <dd>{{ resumo.encerradoEm ? formatarDataHora(resumo.encerradoEm) : 'Em aberto' }}</dd>
            </div>
          </dl>
          <MidiaConversa :atendimento="id" somente-este @abrir-mensagem="irPara" />
        </aside>
      </div>
    </template>
  </main>
</template>

<style scoped>
.detalhe {
  max-width: 90rem;
}

.cabecalho {
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
  margin-bottom: 1rem;
}

.cabecalho .botao-icone {
  margin-top: 0.15rem;
}

.trilha {
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem;
  color: var(--cor-texto-2);
}

.trilha a {
  color: var(--cor-texto-2);
}

.colunas {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 22rem;
  gap: 1rem;
  align-items: start;
}

@media (max-width: 64rem) {
  .colunas {
    grid-template-columns: minmax(0, 1fr);
  }
}

.conversa {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  margin: 0;
}

.topo {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  border-bottom: 1px solid var(--cor-borda);
  padding-bottom: 0.75rem;
}

.topo h2 {
  margin: 0;
}

.avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 2.75rem;
  height: 2.75rem;
  border-radius: 999px;
  background: var(--cor-destaque-suave);
  color: var(--cor-destaque-forte);
  font-weight: 600;
}

.linha {
  display: flex;
  flex-direction: column;
  gap: 0.6rem;
  max-height: 70vh;
  overflow-y: auto;
  padding: 0.5rem 0.25rem;
}

.anteriores {
  align-self: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.25rem;
}

.anteriores p {
  margin: 0;
}

.dia {
  align-self: center;
  margin: 0.25rem 0;
  padding: 0.2rem 0.75rem;
  border-radius: 6px;
  background: var(--cor-superficie-2);
  color: var(--cor-texto-2);
  font-size: 0.8rem;
}

.somente-leitura {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 0.5rem;
  border-top: 1px solid var(--cor-borda);
  padding-top: 0.75rem;
  color: var(--cor-texto-2);
}

.dados {
  margin: 0;
}

.dados h3 {
  margin: 1rem 0 0.4rem;
  font-size: 0.95rem;
}

.dados dl {
  margin: 0;
}

.dados dl div {
  display: grid;
  grid-template-columns: 7.5rem minmax(0, 1fr);
  gap: 0.5rem;
  padding: 0.2rem 0;
}

.dados dt {
  font-weight: 600;
  font-size: 0.85rem;
}

.dados dd {
  margin: 0;
  overflow-wrap: anywhere;
}
</style>
