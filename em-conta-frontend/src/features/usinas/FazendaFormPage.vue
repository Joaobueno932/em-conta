<script setup lang="ts">
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, reactive, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { camposDoErro } from '@/shared/api/campos'
import { codigoDoErro, describeApiError } from '@/shared/api/errors'
import { ApiError } from '@/shared/api/http-client'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'
import CampoTexto from '@/shared/components/CampoTexto.vue'
import { useAlteracoesPendentes } from '@/shared/components/use-alteracoes-pendentes'

import { cadastrarFazenda, chavesUsinas, editarFazenda, obterFazenda, UFS } from './usinas-api'

/** Cadastro ou edição de fazenda. Sem frame no Figma: segue o padrão dos formulários existentes. */
const props = defineProps<{ id?: string }>()
const id = computed(() => (props.id ? Number(props.id) : null))

const http = useHttpClient()
const router = useRouter()
const queryClient = useQueryClient()

const existente = useQuery({
  queryKey: computed(() => chavesUsinas.fazenda(id.value ?? 0)),
  queryFn: () => obterFazenda(http, id.value as number),
  enabled: computed(() => id.value !== null),
})

const formulario = reactive({ nome: '', cidade: '', uf: '', observacoes: '' })
const versao = ref<number | null>(null)
const original = ref(JSON.stringify(formulario))

function carregar() {
  const f = existente.data.value
  if (!f || versao.value !== null) return
  Object.assign(formulario, {
    nome: f.nome,
    cidade: f.cidade ?? '',
    uf: f.uf ?? '',
    observacoes: f.observacoes ?? '',
  })
  versao.value = f.versao
  original.value = JSON.stringify(formulario)
}
watch(() => existente.data.value, carregar, { immediate: true })

const salvo = ref(false)
useAlteracoesPendentes(
  computed(() => !salvo.value && JSON.stringify(formulario) !== original.value),
)

function dados() {
  const texto = (v: string) => (v.trim() ? v : undefined)
  return {
    nome: formulario.nome,
    cidade: texto(formulario.cidade),
    uf: texto(formulario.uf),
    observacoes: texto(formulario.observacoes),
  }
}

const salvar = useMutation({
  mutationFn: (confirmar: boolean) =>
    id.value === null
      ? cadastrarFazenda(http, dados(), confirmar)
      : editarFazenda(http, id.value, { ...dados(), versao: versao.value as number }, confirmar),
  onSuccess: async () => {
    salvo.value = true
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: chavesUsinas.fazendas }),
      queryClient.invalidateQueries({ queryKey: chavesUsinas.listas }),
    ])
    await router.push({ name: 'fazendas', query: { salva: '1' } })
  },
})

const erros = computed(() => camposDoErro(salvar.error.value))
const codigo = computed(() => codigoDoErro(salvar.error.value))
const semelhantes = computed(() => {
  const erro = salvar.error.value
  if (!(erro instanceof ApiError) || codigo.value !== 'NOME_POSSIVEL_DUPLICIDADE') return []
  const lista = erro.problem?.existentes
  return Array.isArray(lista) ? (lista as { id: number; codigo: string; nome: string }[]) : []
})

function enviar(confirmar = false) {
  if (!salvar.isPending.value) salvar.mutate(confirmar)
}

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
      <RouterLink :to="{ name: 'usinas' }">Usinas</RouterLink> /
      <RouterLink :to="{ name: 'fazendas' }">Fazendas</RouterLink> /
      {{ id === null ? 'Nova fazenda' : 'Editar fazenda' }}
    </nav>
    <h1>{{ id === null ? 'Nova fazenda' : 'Editar fazenda' }}</h1>
    <p class="subtitulo">
      O identificador é gerado pelo sistema. Campos com <span class="obrigatorio">*</span> são
      obrigatórios.
    </p>
    <p v-if="id !== null && existente.isPending.value" role="status">Carregando fazenda…</p>
    <form
      v-else
      class="cartao"
      novalidate
      :aria-busy="salvar.isPending.value"
      @submit.prevent="enviar(false)"
    >
      <AlertaMensagem v-if="semelhantes.length" tipo="aviso">
        Já existe fazenda com nome igual ou muito parecido:
        <ul>
          <li v-for="s in semelhantes" :key="s.id">{{ s.codigo }} · {{ s.nome }}</li>
        </ul>
        Nada foi salvo.
        <button
          type="button"
          class="secundario"
          :disabled="salvar.isPending.value"
          @click="enviar(true)"
        >
          É outra fazenda: salvar mesmo assim
        </button>
      </AlertaMensagem>
      <AlertaMensagem v-else-if="codigo === 'CONFLITO_CONCORRENTE'" tipo="aviso">
        Esta fazenda foi alterada por outra pessoa. Seus dados digitados foram mantidos.
        <button type="button" class="link" @click="recarregar">
          Recarregar a versão atual (descarta o que foi digitado)
        </button>
      </AlertaMensagem>
      <AlertaMensagem v-else-if="salvar.isError.value && !Object.keys(erros).length" tipo="erro">
        {{ describeApiError(salvar.error.value) }}
      </AlertaMensagem>
      <div class="grade">
        <CampoTexto
          id="fazenda-nome"
          v-model="formulario.nome"
          rotulo="Nome da fazenda"
          obrigatorio
          :maximo="150"
          :erro="erros.nome"
        />
        <CampoTexto
          id="fazenda-cidade"
          v-model="formulario.cidade"
          rotulo="Cidade"
          :maximo="100"
          :erro="erros.cidade"
        />
        <div class="campo">
          <label for="fazenda-uf">UF</label>
          <select
            id="fazenda-uf"
            v-model="formulario.uf"
            :aria-invalid="erros.uf ? 'true' : undefined"
          >
            <option value="">—</option>
            <option v-for="uf in UFS" :key="uf" :value="uf">{{ uf }}</option>
          </select>
        </div>
      </div>
      <CampoTexto
        id="fazenda-observacoes"
        v-model="formulario.observacoes"
        rotulo="Observações"
        :maximo="500"
        :erro="erros.observacoes"
      />
      <div class="acoes acoes--fim">
        <RouterLink :to="{ name: 'fazendas' }">Cancelar</RouterLink>
        <button type="submit" :disabled="salvar.isPending.value">
          {{ salvar.isPending.value ? 'Salvando…' : 'Salvar fazenda' }}
        </button>
      </div>
    </form>
  </main>
</template>
