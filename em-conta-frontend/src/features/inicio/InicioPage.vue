<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { computed } from 'vue'
import { RouterLink } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { chaves, formatarData, listarAssociados } from '@/features/associados/associados-api'
import {
  chavesAtendimento,
  formatarHora,
  iniciais,
  INTERVALO_MS,
  listarFila,
} from '@/features/atendimento/atendimento-api'
import { temPermissao, useSessao } from '@/features/auth/auth-api'
import { chavesUsinas, listarUsinas } from '@/features/usinas/usinas-api'
import CabecalhoPagina from '@/shared/components/CabecalhoPagina.vue'
import IconeTraco from '@/shared/components/IconeTraco.vue'

/**
 * Painel (prancha "🏠 Painel"): os mesmos blocos do protótipo — indicadores, lista recente,
 * "Precisa de atenção" e Atendimento — alimentados só pelo que existe hoje (associados, usinas e a
 * fila do atendimento). Faturamento, recebimentos, atraso e faturas ainda não existem no sistema e
 * não aparecem; nenhum número é ilustrativo. Cada bloco só aparece para quem pode consultá-lo.
 */
const http = useHttpClient()
const { data: usuario } = useSessao()
const pode = (p: string) => temPermissao(usuario.value, p)

const verAssociados = computed(() => pode('associados:consultar'))
const verUsinas = computed(() => pode('usinas:consultar'))
const verAtendimento = computed(() => pode('atendimento:consultar'))

const FILTRO_RECENTES = {
  busca: '',
  tipo: '',
  ordenacao: 'CADASTRO_RECENTE',
  pagina: 0,
  tamanho: 20,
} as const
const associados = useQuery({
  queryKey: chaves.lista(FILTRO_RECENTES),
  queryFn: () => listarAssociados(http, FILTRO_RECENTES),
  enabled: verAssociados,
})
const recentes = computed(() => associados.data.value?.itens.slice(0, 5) ?? [])

const FILTRO_USINAS = { busca: '', uf: '', ordenacao: 'NOME', pagina: 0, tamanho: 20 } as const
const usinas = useQuery({
  queryKey: chavesUsinas.lista(FILTRO_USINAS),
  queryFn: () => listarUsinas(http, FILTRO_USINAS),
  enabled: verUsinas,
})

// Mesma consulta do contador do menu (fila "Aguardando"), atualizada no mesmo intervalo.
const aguardando = useQuery({
  queryKey: chavesAtendimento.fila('AGUARDANDO', '', 0),
  queryFn: () => listarFila(http, 'AGUARDANDO', '', 0),
  enabled: verAtendimento,
  refetchInterval: INTERVALO_MS,
})
const emAndamento = useQuery({
  queryKey: chavesAtendimento.fila('EM_ANDAMENTO', '', 0),
  queryFn: () => listarFila(http, 'EM_ANDAMENTO', '', 0),
  enabled: verAtendimento,
  refetchInterval: INTERVALO_MS,
})
const contagens = computed(() => aguardando.data.value?.contagens)
const conversas = computed(() =>
  [...(aguardando.data.value?.itens ?? []), ...(emAndamento.data.value?.itens ?? [])]
    .sort((a, b) => (b.ultimaMensagemEm ?? '').localeCompare(a.ultimaMensagemEm ?? ''))
    .slice(0, 3),
)

/** Hora para hoje; "Ontem" ou a data para os demais dias. */
function quando(instante?: string | null) {
  if (!instante) return ''
  const data = new Date(instante)
  const hoje = new Date()
  const ontem = new Date(hoje.getFullYear(), hoje.getMonth(), hoje.getDate() - 1)
  if (data.toDateString() === hoje.toDateString()) return formatarHora(instante)
  if (data.toDateString() === ontem.toDateString()) return 'Ontem'
  return formatarData(instante)
}

const nomeDe = (c: { associado?: { nome: string }; contatoNome?: string; contatoNumero: string }) =>
  c.associado?.nome ?? c.contatoNome ?? c.contatoNumero
const total = (n?: number | null) => (n == null ? '—' : n.toLocaleString('pt-BR'))
const rotuloAguardando = computed(() =>
  contagens.value?.aguardando === 1 ? 'conversa aguardando' : 'conversas aguardando',
)
</script>

<template>
  <main v-if="usuario" class="pagina-modulo">
    <CabecalhoPagina titulo="Visão geral" subtitulo="Acompanhe sua operação.">
      <template #acoes>
        <RouterLink
          v-if="pode('associados:cadastrar')"
          class="botao-primario"
          :to="{ name: 'associado-novo' }"
        >
          <IconeTraco nome="mais-circulo" :tamanho="20" />
          Novo associado
        </RouterLink>
      </template>
    </CabecalhoPagina>

    <ul class="indicadores" aria-label="Indicadores">
      <li v-if="verAssociados" class="painel indicador">
        <span class="rotulo">Associados cadastrados</span>
        <strong class="valor">{{ total(associados.data.value?.totalItens) }}</strong>
        <span class="nota">Pessoas físicas e jurídicas</span>
      </li>
      <li v-if="verUsinas" class="painel indicador">
        <span class="rotulo">Usinas cadastradas</span>
        <strong class="valor">{{ total(usinas.data.value?.totalItens) }}</strong>
        <span class="nota">Com vínculos cadastrais de UCs</span>
      </li>
      <li v-if="verAtendimento" class="painel indicador">
        <span class="rotulo">Aguardando atendimento</span>
        <strong class="valor">{{ total(contagens?.aguardando) }}</strong>
        <span class="nota">Conversas na fila agora</span>
      </li>
      <li v-if="verAtendimento" class="painel indicador">
        <span class="rotulo">Em atendimento</span>
        <strong class="valor">{{ total(contagens?.emAndamento) }}</strong>
        <span class="nota">Conversas em andamento</span>
      </li>
    </ul>

    <div class="grade-painel">
      <section v-if="verAssociados" class="painel bloco recentes" aria-labelledby="titulo-recentes">
        <div class="topo-bloco">
          <h2 id="titulo-recentes">Associados recentes</h2>
          <RouterLink :to="{ name: 'associados' }" class="ver-todos">Ver todos</RouterLink>
        </div>
        <p v-if="associados.isPending.value" role="status" class="vazio">Carregando…</p>
        <p v-else-if="associados.isError.value" class="vazio">
          Não foi possível carregar os associados.
        </p>
        <p v-else-if="!recentes.length" class="vazio">Nenhum associado cadastrado ainda.</p>
        <div v-else class="tabela-rolavel">
          <table class="tabela-dados">
            <thead>
              <tr>
                <th scope="col">Associado</th>
                <th scope="col">Tipo</th>
                <th scope="col">CPF/CNPJ</th>
                <th scope="col">UCs</th>
                <th scope="col">Cadastrado em</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="a in recentes" :key="a.id">
                <td>
                  <RouterLink :to="{ name: 'associado', params: { id: a.id } }">
                    {{ a.nome }}
                  </RouterLink>
                </td>
                <td class="secundario">{{ a.tipo }}</td>
                <td class="secundario">{{ a.documentoMascarado }}</td>
                <td>{{ a.quantidadeUcs }}</td>
                <td class="secundario">{{ formatarData(a.criadoEm) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <div v-if="verAtendimento" class="coluna-lateral">
        <section class="painel bloco" aria-labelledby="titulo-atencao">
          <div class="topo-bloco">
            <h2 id="titulo-atencao">Precisa de atenção</h2>
          </div>
          <RouterLink :to="{ name: 'atendimento' }" class="item-atencao">
            <span class="icone-atencao"><IconeTraco nome="conversa" :tamanho="20" /></span>
            <span class="textos">
              <strong>{{ total(contagens?.aguardando) }} {{ rotuloAguardando }}</strong>
              <span>Na fila do atendimento, sem responsável</span>
            </span>
            <IconeTraco nome="seta-direita" :tamanho="18" />
          </RouterLink>
        </section>

        <section class="painel bloco" aria-labelledby="titulo-atendimento">
          <div class="topo-bloco">
            <h2 id="titulo-atendimento">Atendimento</h2>
            <RouterLink :to="{ name: 'atendimento' }" class="ver-todos">Ver todas</RouterLink>
          </div>
          <p v-if="aguardando.isPending.value" role="status" class="vazio">Carregando…</p>
          <p v-else-if="!conversas.length" class="vazio">Nenhuma conversa em curso.</p>
          <ul v-else class="conversas">
            <li v-for="c in conversas" :key="c.id">
              <RouterLink
                :to="{ name: 'atendimento', query: { id: String(c.id) } }"
                class="conversa"
              >
                <span class="avatar-iniciais" aria-hidden="true">
                  {{ iniciais(nomeDe(c)) }}
                </span>
                <span class="textos">
                  <span class="linha-nome">
                    <strong>{{ nomeDe(c) }}</strong>
                    <time v-if="c.ultimaMensagemEm" :datetime="c.ultimaMensagemEm">
                      {{ quando(c.ultimaMensagemEm) }}
                    </time>
                  </span>
                  <span class="situacao">
                    {{
                      c.situacao === 'AGUARDANDO'
                        ? 'Aguardando atendimento'
                        : `Com ${c.responsavel?.nome ?? 'responsável'}`
                    }}
                    <span v-if="c.situacao === 'AGUARDANDO'" class="ponto" aria-hidden="true" />
                  </span>
                </span>
              </RouterLink>
            </li>
          </ul>
        </section>
      </div>
    </div>
  </main>
</template>

<style scoped>
/* Prancha: quatro indicadores com 18 px entre eles, depois a lista e a coluna de 398 px. */
.indicadores {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(14rem, 1fr));
  gap: 18px;
  margin: 0 0 18px;
  padding: 0;
  list-style: none;
}

.indicador {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-height: 178px;
  box-sizing: border-box;
  padding: 24px 30px;
}

.indicador .rotulo {
  font-size: 18px;
}

.indicador .valor {
  font-size: 38px;
  font-weight: 700;
  line-height: 1.2;
}

.indicador .nota {
  color: var(--cor-texto-2);
  font-size: 17px;
}

.grade-painel {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 398px;
  gap: 18px;
  align-items: start;
}

.coluna-lateral {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.bloco {
  padding: 22px 22px 16px;
}

.topo-bloco {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.topo-bloco h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}

.ver-todos {
  font-size: 14px;
}

.vazio {
  margin: 8px 0;
  color: var(--cor-texto-2);
  font-size: 14px;
}

.secundario {
  color: var(--cor-texto-2);
}

.tabela-dados tbody td {
  height: 48px;
}

.tabela-dados td a {
  font-weight: 400;
}

.conversas {
  margin: 0;
  padding: 0;
  list-style: none;
}

.item-atencao {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 8px 0;
  color: var(--cor-texto);
  text-decoration: none;
}

.icone-atencao {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  border-radius: 999px;
  background: var(--cor-destaque-suave);
  color: var(--cor-destaque);
}

.textos {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
}

.textos strong {
  font-size: 17px;
  font-weight: 500;
}

.textos > span {
  color: var(--cor-texto-2);
  font-size: 14px;
}

.conversas li + li {
  border-top: 1px solid var(--cor-divisoria);
}

.conversa {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 10px 0;
  color: var(--cor-texto);
  text-decoration: none;
}

.avatar-iniciais {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 50px;
  height: 50px;
  border-radius: 999px;
  background: var(--cor-destaque-suave);
  color: var(--cor-destaque-forte);
  font-size: 17px;
  font-weight: 500;
}

.linha-nome {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}

.linha-nome strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.linha-nome time {
  flex-shrink: 0;
  color: var(--cor-texto-2);
  font-size: 13px;
}

.situacao {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.ponto {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: var(--cor-destaque);
}

@media (max-width: 80rem) {
  .grade-painel {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
