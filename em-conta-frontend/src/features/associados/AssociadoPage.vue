<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { computed } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { temPermissao, useSessao } from '@/features/auth/auth-api'
import { describeApiError } from '@/shared/api/errors'
import { ApiError } from '@/shared/api/http-client'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'
import IconeTraco from '@/shared/components/IconeTraco.vue'

import { chaves, formatarData, obterAssociado, ROTULO_TIPO } from './associados-api'
import DadosCadastraisAba from './DadosCadastraisAba.vue'
import HistoricoAba from './HistoricoAba.vue'
import UcsAba from './UcsAba.vue'

const props = defineProps<{ id: string }>()
const id = computed(() => Number(props.id))

const http = useHttpClient()
const route = useRoute()
const router = useRouter()
const { data: sessao } = useSessao()

const ficha = useQuery({
  queryKey: computed(() => chaves.ficha(id.value)),
  queryFn: () => obterAssociado(http, id.value),
})

type Aba = 'dados' | 'ucs' | 'historico'
const abas = computed(() => {
  const lista: { id: Aba; rotulo: string }[] = [{ id: 'dados', rotulo: 'Dados cadastrais' }]
  if (temPermissao(sessao.value, 'ucs:consultar')) {
    lista.push({
      id: 'ucs',
      rotulo: `Unidades consumidoras (${ficha.data.value?.quantidadeUcs ?? 0})`,
    })
  }
  if (temPermissao(sessao.value, 'associados:consultar-historico')) {
    lista.push({ id: 'historico', rotulo: 'Histórico' })
  }
  return lista
})
const aba = computed<Aba>(() => {
  const pedida = route.query.aba
  return abas.value.find((a) => a.id === pedida)?.id ?? 'dados'
})

function abrir(nova: Aba) {
  void router.replace({ query: { ...route.query, aba: nova === 'dados' ? undefined : nova } })
}

const naoEncontrado = computed(
  () => ficha.error.value instanceof ApiError && ficha.error.value.status === 404,
)
const podeEditar = computed(() => temPermissao(sessao.value, 'associados:editar'))
</script>

<template>
  <main class="pagina-modulo">
    <nav aria-label="Trilha" class="trilha">
      <RouterLink :to="{ name: 'associados' }">Associados</RouterLink>
      <span aria-hidden="true">/</span>
      <RouterLink :to="{ name: 'associados' }">Todos os associados</RouterLink>
      <template v-if="ficha.data.value">
        <span aria-hidden="true">/</span>
        <span aria-current="page">{{ ficha.data.value.nome }}</span>
      </template>
    </nav>

    <p v-if="ficha.isPending.value" role="status">Carregando associado…</p>
    <template v-else-if="ficha.isError.value">
      <h1>{{ naoEncontrado ? 'Associado não encontrado' : 'Associado' }}</h1>
      <AlertaMensagem tipo="erro">{{ describeApiError(ficha.error.value) }}</AlertaMensagem>
      <button v-if="!naoEncontrado" type="button" class="botao-contorno" @click="ficha.refetch()">
        Tentar novamente
      </button>
    </template>
    <template v-else-if="ficha.data.value">
      <div class="cabecalho-ficha">
        <span class="icone-ficha" aria-hidden="true">
          <IconeTraco :nome="ficha.data.value.tipo === 'PJ' ? 'predio' : 'pessoa'" :tamanho="26" />
        </span>
        <div class="identidade">
          <h1>{{ ficha.data.value.nome }}</h1>
          <p class="meta">
            {{ ficha.data.value.tipo }} · {{ ficha.data.value.tipo === 'PF' ? 'CPF' : 'CNPJ' }}
            {{ ficha.data.value.documento }} · Criado em
            {{ formatarData(ficha.data.value.criadoEm) }}
          </p>
        </div>
        <RouterLink
          v-if="podeEditar"
          class="botao-contorno"
          :to="{ name: 'associado-editar', params: { id } }"
        >
          <IconeTraco nome="lapis" :tamanho="18" />
          Editar cadastro
        </RouterLink>
      </div>

      <AlertaMensagem v-if="route.query.criado === '1'" tipo="sucesso">
        Associado cadastrado. Cadastro concluído não significa contrato assinado nem cliente ativo.
      </AlertaMensagem>

      <ul class="resumo">
        <li class="painel">
          <span class="rotulo">Tipo de pessoa</span>
          <strong>{{ ROTULO_TIPO[ficha.data.value.tipo] }}</strong>
        </li>
        <li class="painel">
          <span class="rotulo">Unidades consumidoras</span>
          <strong>{{ ficha.data.value.quantidadeUcs }} UC(s)</strong>
        </li>
        <li class="painel">
          <span class="rotulo">Contatos</span>
          <strong>{{ ficha.data.value.contatos.length }}</strong>
        </li>
      </ul>

      <AlertaMensagem tipo="info">
        Situação do cliente, contratos, faturas, cobranças e pendências ainda não estão disponíveis
        nesta versão.
      </AlertaMensagem>

      <div class="abas" role="tablist" aria-label="Seções do associado">
        <button
          v-for="item in abas"
          :id="`aba-${item.id}`"
          :key="item.id"
          type="button"
          role="tab"
          class="aba"
          :aria-selected="aba === item.id"
          :aria-controls="`painel-${item.id}`"
          :tabindex="aba === item.id ? 0 : -1"
          @click="abrir(item.id)"
        >
          {{ item.rotulo }}
        </button>
      </div>
      <section
        :id="`painel-${aba}`"
        role="tabpanel"
        :aria-labelledby="`aba-${aba}`"
        :class="{ painel: aba !== 'dados', conteudo: aba !== 'dados' }"
      >
        <DadosCadastraisAba v-if="aba === 'dados'" :associado="ficha.data.value" />
        <UcsAba v-else-if="aba === 'ucs'" :associado-id="id" />
        <HistoricoAba v-else :associado-id="id" />
      </section>
    </template>
  </main>
</template>

<style scoped>
/* Prancha da ficha (1672 × 1565): trilha verde de 13 px, ícone de 56 px, nome de 32 px. */
.trilha {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
  color: var(--cor-texto-3);
  font-size: 13px;
}

.trilha a {
  color: var(--cor-destaque-forte);
  text-decoration: none;
}

.trilha a:hover {
  text-decoration: underline;
}

.trilha [aria-current] {
  color: var(--cor-texto);
}

.cabecalho-ficha {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 16px;
  margin-bottom: 16px;
}

.icone-ficha {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  border-radius: 10px;
  background: var(--cor-destaque);
  color: #fff;
}

.identidade {
  flex: 1;
  min-width: 0;
}

h1 {
  margin: 0;
  font-size: 32px;
  font-weight: 700;
  line-height: 1.2;
}

.meta {
  margin: 2px 0 0;
  color: var(--cor-texto-2);
  font-size: 15px;
}

.resumo {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(12rem, 1fr));
  gap: 14px;
}

.resumo li {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 14px 16px;
}

.resumo .rotulo {
  color: var(--cor-texto-2);
  font-size: 12px;
}

.resumo strong {
  font-size: 15px;
  font-weight: 600;
}

/* Abas sublinhadas: texto de 16 px, a ativa em verde com traço de 2 px. */
.abas {
  display: flex;
  gap: 26px;
  flex-wrap: wrap;
  border-bottom: 1px solid var(--cor-borda);
  margin: 18px 0 18px;
  padding: 0 12px;
}

.aba {
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
  border-radius: 0;
  color: var(--cor-texto);
  font-size: 16px;
  padding: 8px 0 10px;
}

.aba:hover {
  color: var(--cor-destaque-forte);
}

.aba[aria-selected='true'] {
  border-bottom-color: var(--cor-destaque);
  color: var(--cor-destaque-forte);
  font-weight: 600;
}

.conteudo {
  padding: 20px;
}
</style>
