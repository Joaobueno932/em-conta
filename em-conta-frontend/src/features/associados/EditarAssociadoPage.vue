<script setup lang="ts">
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, reactive, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { camposDoErro } from '@/shared/api/campos'
import { codigoDoErro, describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'
import { useAlteracoesPendentes } from '@/shared/components/use-alteracoes-pendentes'

import { chaves, editarAssociado, obterAssociado, ROTULO_TIPO } from './associados-api'
import CampoTexto from '@/shared/components/CampoTexto.vue'

/**
 * Edição dos dados cadastrais. Tipo e documento identificam o associado e não são editáveis;
 * contatos são editados um a um na ficha, para que nenhum seja perdido ao salvar esta tela.
 */
const props = defineProps<{ id: string }>()
const id = computed(() => Number(props.id))

const http = useHttpClient()
const router = useRouter()
const queryClient = useQueryClient()

const ficha = useQuery({
  queryKey: computed(() => chaves.ficha(id.value)),
  queryFn: () => obterAssociado(http, id.value),
})

const formulario = reactive({ nome: '', nomeFantasia: '' })
const versao = ref<number | null>(null)
const original = ref('')

/** Só na primeira carga ou em recarga pedida: buscas em segundo plano não apagam a digitação. */
function carregar() {
  const dados = ficha.data.value
  if (!dados || versao.value !== null) return
  formulario.nome = dados.nome
  formulario.nomeFantasia = dados.nomeFantasia ?? ''
  versao.value = dados.versao
  original.value = JSON.stringify(formulario)
}
watch(() => ficha.data.value, carregar, { immediate: true })

const salvo = ref(false)
const alterado = computed(
  () => !salvo.value && versao.value !== null && JSON.stringify(formulario) !== original.value,
)
useAlteracoesPendentes(alterado)

const salvar = useMutation({
  mutationFn: () =>
    editarAssociado(http, id.value, {
      versao: versao.value as number,
      nome: formulario.nome,
      ...(ficha.data.value?.tipo === 'PJ' && formulario.nomeFantasia.trim()
        ? { nomeFantasia: formulario.nomeFantasia }
        : {}),
    }),
  onSuccess: async (atualizado) => {
    salvo.value = true
    queryClient.setQueryData(chaves.ficha(id.value), atualizado)
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: chaves.historico(id.value) }),
      queryClient.invalidateQueries({ queryKey: chaves.listas }),
    ])
    await router.push({ name: 'associado', params: { id: id.value } })
  },
})

const erros = computed(() => camposDoErro(salvar.error.value))
const conflito = computed(() => codigoDoErro(salvar.error.value) === 'CONFLITO_CONCORRENTE')
const erroGeral = computed(() => {
  if (!salvar.isError.value || conflito.value) return ''
  return Object.keys(erros.value).length
    ? 'Revise os campos destacados. Os dados digitados foram mantidos.'
    : describeApiError(salvar.error.value)
})

async function recarregar() {
  await ficha.refetch()
  versao.value = null
  carregar()
  salvar.reset()
}
</script>

<template>
  <main class="pagina pagina--larga">
    <nav aria-label="Trilha" class="ajuda">
      <RouterLink :to="{ name: 'associados' }">Todos os associados</RouterLink> /
      <RouterLink :to="{ name: 'associado', params: { id } }">
        {{ ficha.data.value?.nome ?? 'Associado' }}
      </RouterLink>
      / Editar cadastro
    </nav>
    <h1>Editar cadastro</h1>

    <p v-if="ficha.isPending.value" role="status">Carregando associado…</p>
    <AlertaMensagem v-else-if="ficha.isError.value && versao === null" tipo="erro">
      {{ describeApiError(ficha.error.value) }}
    </AlertaMensagem>
    <form
      v-else-if="ficha.data.value"
      class="cartao"
      novalidate
      :aria-busy="salvar.isPending.value"
      @submit.prevent="!salvar.isPending.value && salvar.mutate()"
    >
      <AlertaMensagem v-if="conflito" tipo="aviso">
        Este cadastro foi alterado por outra pessoa depois que você abriu a página. Nada foi salvo e
        seus dados digitados foram mantidos.
        <button type="button" class="link" @click="recarregar">
          Recarregar a versão atual (descarta o que foi digitado)
        </button>
      </AlertaMensagem>
      <AlertaMensagem v-if="erroGeral" tipo="erro">{{ erroGeral }}</AlertaMensagem>

      <div class="grade">
        <CampoTexto
          id="editar-tipo"
          :model-value="ROTULO_TIPO[ficha.data.value.tipo]"
          rotulo="Tipo de pessoa"
          somente-leitura
        />
        <CampoTexto
          id="editar-documento"
          :model-value="ficha.data.value.documento"
          :rotulo="ficha.data.value.tipo === 'PF' ? 'CPF' : 'CNPJ'"
          somente-leitura
          ajuda="O documento identifica o associado e não pode ser alterado."
        />
        <CampoTexto
          id="editar-nome"
          v-model="formulario.nome"
          :rotulo="ficha.data.value.tipo === 'PF' ? 'Nome completo' : 'Razão social'"
          obrigatorio
          :maximo="200"
          :erro="erros.nome"
        />
        <CampoTexto
          v-if="ficha.data.value.tipo === 'PJ'"
          id="editar-fantasia"
          v-model="formulario.nomeFantasia"
          rotulo="Nome fantasia"
          :maximo="200"
          :erro="erros.nomeFantasia"
        />
      </div>
      <p class="ajuda">Contatos e unidades consumidoras são editados na página do associado.</p>
      <div class="acoes acoes--fim">
        <RouterLink :to="{ name: 'associado', params: { id } }">Cancelar</RouterLink>
        <button type="submit" :disabled="salvar.isPending.value">
          {{ salvar.isPending.value ? 'Salvando…' : 'Salvar alterações' }}
        </button>
      </div>
    </form>
  </main>
</template>
