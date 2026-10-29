<script setup lang="ts">
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, reactive, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { camposDoErro } from '@/shared/api/campos'
import { codigoDoErro, describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'
import { useAlteracoesPendentes } from '@/shared/components/use-alteracoes-pendentes'

import {
  cadastrarUc,
  chaves,
  CONCESSIONARIAS,
  editarUc,
  obterAssociado,
  obterUc,
  type Concessionaria,
} from './associados-api'
import CampoTexto from '@/shared/components/CampoTexto.vue'

/** Cadastro (sem `ucId`) ou edição de UC. Na edição, concessionária e número não mudam. */
const props = defineProps<{ id: string; ucId?: string }>()
const associadoId = computed(() => Number(props.id))
const ucId = computed(() => (props.ucId ? Number(props.ucId) : null))

const http = useHttpClient()
const router = useRouter()
const queryClient = useQueryClient()

const associado = useQuery({
  queryKey: computed(() => chaves.ficha(associadoId.value)),
  queryFn: () => obterAssociado(http, associadoId.value),
})
const existente = useQuery({
  queryKey: computed(() => chaves.uc(associadoId.value, ucId.value ?? 0)),
  queryFn: () => obterUc(http, associadoId.value, ucId.value as number),
  enabled: computed(() => ucId.value !== null),
})

const formulario = reactive({
  concessionaria: 'ENERGISA_MS' as Concessionaria,
  numero: '',
  denominacao: '',
  endereco: '',
})
const original = ref('')
const versao = ref<number | null>(null)

/**
 * Preenche o formulário só na primeira carga ou quando o usuário pede recarga: novas buscas em
 * segundo plano (ex.: ao voltar o foco à janela) não sobrescrevem o que foi digitado.
 */
function carregar() {
  const uc = existente.data.value
  if (!uc || versao.value !== null) return
  Object.assign(formulario, {
    concessionaria: uc.concessionaria,
    numero: uc.numero,
    denominacao: uc.denominacao ?? '',
    endereco: uc.endereco ?? '',
  })
  versao.value = uc.versao
  original.value = JSON.stringify(formulario)
}
watch(() => existente.data.value, carregar, { immediate: true })
if (ucId.value === null) original.value = JSON.stringify(formulario)

const salvo = ref(false)
const alterado = computed(() => !salvo.value && JSON.stringify(formulario) !== original.value)
useAlteracoesPendentes(alterado)

const salvar = useMutation({
  mutationFn: () =>
    ucId.value === null
      ? cadastrarUc(http, associadoId.value, {
          concessionaria: formulario.concessionaria,
          numero: formulario.numero,
          ...(formulario.denominacao.trim() ? { denominacao: formulario.denominacao } : {}),
          ...(formulario.endereco.trim() ? { endereco: formulario.endereco } : {}),
        })
      : editarUc(http, associadoId.value, ucId.value, {
          versao: versao.value as number,
          ...(formulario.denominacao.trim() ? { denominacao: formulario.denominacao } : {}),
          ...(formulario.endereco.trim() ? { endereco: formulario.endereco } : {}),
        }),
  onSuccess: async () => {
    salvo.value = true
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: chaves.ucs(associadoId.value) }),
      queryClient.invalidateQueries({ queryKey: chaves.ficha(associadoId.value) }),
      queryClient.invalidateQueries({ queryKey: chaves.historico(associadoId.value) }),
      queryClient.invalidateQueries({ queryKey: chaves.listas }),
    ])
    await router.push({
      name: 'associado',
      params: { id: associadoId.value },
      query: { aba: 'ucs' },
    })
  },
})

const erros = computed(() => camposDoErro(salvar.error.value))
const codigo = computed(() => codigoDoErro(salvar.error.value))
const erroGeral = computed(() => {
  if (!salvar.isError.value) return ''
  if (codigo.value === 'CONFLITO_CONCORRENTE') return ''
  if (codigo.value === 'UC_JA_CADASTRADA') {
    return 'Esta UC já está cadastrada nesta concessionária, possivelmente em outro associado. A transferência de titularidade ainda não está disponível.'
  }
  return Object.keys(erros.value).length
    ? 'Revise os campos destacados. Os dados digitados foram mantidos.'
    : describeApiError(salvar.error.value)
})

/** Após um conflito, descarta o que foi digitado e carrega a versão atual. */
async function recarregar() {
  await existente.refetch()
  versao.value = null
  carregar()
  salvar.reset()
}
</script>

<template>
  <main class="pagina pagina--larga">
    <nav aria-label="Trilha" class="ajuda">
      <RouterLink :to="{ name: 'associados' }">Todos os associados</RouterLink> /
      <RouterLink :to="{ name: 'associado', params: { id: associadoId }, query: { aba: 'ucs' } }">
        {{ associado.data.value?.nome ?? 'Associado' }}
      </RouterLink>
      / {{ ucId === null ? 'Nova UC' : 'Editar UC' }}
    </nav>
    <h1>{{ ucId === null ? 'Cadastrar unidade consumidora' : 'Editar unidade consumidora' }}</h1>
    <p class="subtitulo">
      A UC fica vinculada a {{ associado.data.value?.nome ?? 'este associado' }}. Campos com
      <span class="obrigatorio">*</span> são obrigatórios.
    </p>

    <p v-if="ucId !== null && existente.isPending.value" role="status">Carregando UC…</p>
    <AlertaMensagem v-else-if="existente.isError.value" tipo="erro">
      {{ describeApiError(existente.error.value) }}
    </AlertaMensagem>
    <form
      v-else
      class="cartao"
      novalidate
      :aria-busy="salvar.isPending.value"
      @submit.prevent="!salvar.isPending.value && salvar.mutate()"
    >
      <AlertaMensagem v-if="codigo === 'CONFLITO_CONCORRENTE'" tipo="aviso">
        Esta UC foi alterada por outra pessoa depois que você abriu a página. Seus dados digitados
        foram mantidos.
        <button type="button" class="link" @click="recarregar">
          Recarregar a versão atual (descarta o que foi digitado)
        </button>
      </AlertaMensagem>
      <AlertaMensagem v-if="erroGeral" tipo="erro">{{ erroGeral }}</AlertaMensagem>

      <div class="grade">
        <div class="campo">
          <label for="uc-concessionaria">
            Concessionária<span class="obrigatorio" aria-hidden="true"> *</span>
          </label>
          <select
            id="uc-concessionaria"
            v-model="formulario.concessionaria"
            :disabled="ucId !== null"
            :aria-invalid="erros.concessionaria ? 'true' : undefined"
            :aria-describedby="erros.concessionaria ? 'uc-concessionaria-erro' : undefined"
          >
            <option v-for="c in CONCESSIONARIAS" :key="c.valor" :value="c.valor">
              {{ c.rotulo }}
            </option>
          </select>
          <p v-if="erros.concessionaria" id="uc-concessionaria-erro" class="ajuda ajuda--erro">
            {{ erros.concessionaria }}
          </p>
        </div>
        <CampoTexto
          id="uc-numero"
          v-model="formulario.numero"
          rotulo="Número da UC"
          obrigatorio
          :somente-leitura="ucId !== null"
          :maximo="30"
          ajuda="Como consta na fatura da concessionária; zeros à esquerda são mantidos."
          :erro="erros.numero"
        />
        <CampoTexto
          id="uc-denominacao"
          v-model="formulario.denominacao"
          rotulo="Denominação"
          :maximo="120"
          ajuda="Ex.: Sede administrativa, Silo 1"
          :erro="erros.denominacao"
        />
        <CampoTexto
          id="uc-endereco"
          v-model="formulario.endereco"
          rotulo="Endereço da UC"
          :maximo="300"
          :erro="erros.endereco"
        />
      </div>
      <div class="acoes acoes--fim">
        <RouterLink :to="{ name: 'associado', params: { id: associadoId }, query: { aba: 'ucs' } }">
          Cancelar
        </RouterLink>
        <button type="submit" :disabled="salvar.isPending.value">
          {{ salvar.isPending.value ? 'Salvando…' : 'Salvar UC' }}
        </button>
      </div>
    </form>
  </main>
</template>
