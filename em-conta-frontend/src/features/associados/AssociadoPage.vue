<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { computed } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { temPermissao, useSessao } from '@/features/auth/auth-api'
import { describeApiError } from '@/shared/api/errors'
import { ApiError } from '@/shared/api/http-client'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'

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
  <main class="pagina pagina--larga">
    <nav aria-label="Trilha" class="ajuda">
      <RouterLink :to="{ name: 'associados' }">Todos os associados</RouterLink>
      <template v-if="ficha.data.value"> / {{ ficha.data.value.nome }}</template>
    </nav>

    <p v-if="ficha.isPending.value" role="status">Carregando associado…</p>
    <template v-else-if="ficha.isError.value">
      <h1>{{ naoEncontrado ? 'Associado não encontrado' : 'Associado' }}</h1>
      <AlertaMensagem tipo="erro">{{ describeApiError(ficha.error.value) }}</AlertaMensagem>
      <button v-if="!naoEncontrado" type="button" class="secundario" @click="ficha.refetch()">
        Tentar novamente
      </button>
    </template>
    <template v-else-if="ficha.data.value">
      <div class="cabecalho-ficha">
        <div>
          <h1>{{ ficha.data.value.nome }}</h1>
          <p class="ajuda">
            {{ ficha.data.value.tipo }} · {{ ficha.data.value.tipo === 'PF' ? 'CPF' : 'CNPJ' }}
            {{ ficha.data.value.documento }} · Criado em
            {{ formatarData(ficha.data.value.criadoEm) }}
          </p>
        </div>
        <RouterLink
          v-if="podeEditar"
          class="botao-secundario"
          :to="{ name: 'associado-editar', params: { id } }"
        >
          Editar cadastro
        </RouterLink>
      </div>

      <AlertaMensagem v-if="route.query.criado === '1'" tipo="sucesso">
        Associado cadastrado. Cadastro concluído não significa contrato assinado nem cliente ativo.
      </AlertaMensagem>

      <ul class="resumo">
        <li class="cartao">
          <span class="ajuda">Tipo</span>
          <strong>{{ ROTULO_TIPO[ficha.data.value.tipo] }}</strong>
        </li>
        <li class="cartao">
          <span class="ajuda">Unidades consumidoras</span>
          <strong>{{ ficha.data.value.quantidadeUcs }} UC(s)</strong>
        </li>
        <li class="cartao">
          <span class="ajuda">Contatos</span>
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
      <section :id="`painel-${aba}`" role="tabpanel" :aria-labelledby="`aba-${aba}`" class="cartao">
        <DadosCadastraisAba v-if="aba === 'dados'" :associado="ficha.data.value" />
        <UcsAba v-else-if="aba === 'ucs'" :associado-id="id" />
        <HistoricoAba v-else :associado-id="id" />
      </section>
    </template>
  </main>
</template>

<style scoped>
.cabecalho-ficha {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 1rem;
  flex-wrap: wrap;
  margin: 0.5rem 0 1rem;
}

.botao-secundario {
  padding: 0.5rem 1rem;
  border: 1px solid var(--cor-borda);
  border-radius: var(--raio);
  background: var(--cor-superficie);
  color: var(--cor-texto);
  text-decoration: none;
}

.resumo {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(12rem, 1fr));
  gap: 1rem;
}

.resumo li {
  display: flex;
  flex-direction: column;
}

.abas {
  display: flex;
  gap: 0.25rem;
  flex-wrap: wrap;
  border-bottom: 1px solid var(--cor-borda);
  margin: 1rem 0;
}

.aba {
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
  border-radius: 0;
  color: var(--cor-texto);
  padding: 0.5rem 0.75rem;
}

.aba[aria-selected='true'] {
  border-bottom-color: var(--cor-destaque);
  color: var(--cor-destaque-forte);
  font-weight: 600;
}
</style>
