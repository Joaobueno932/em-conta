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

import {
  cadastrarUsina,
  chavesUsinas,
  editarUsina,
  obterUsina,
  UFS,
  type DadosUsina,
} from './usinas-api'
import SeletorFazenda, { type FazendaSelecionada } from './SeletorFazenda.vue'

/** Cadastro (sem `id`) ou edição das informações da usina. */
const props = defineProps<{ id?: string }>()
const id = computed(() => (props.id ? Number(props.id) : null))

const http = useHttpClient()
const router = useRouter()
const queryClient = useQueryClient()

const existente = useQuery({
  queryKey: computed(() => chavesUsinas.usina(id.value ?? 0)),
  queryFn: () => obterUsina(http, id.value as number),
  enabled: computed(() => id.value !== null),
})

const formulario = reactive({
  nome: '',
  fazenda: null as FazendaSelecionada | null,
  cep: '',
  endereco: '',
  cidade: '',
  uf: '',
  observacoes: '',
})
const versao = ref<number | null>(null)
const original = ref(JSON.stringify(formulario))

/** Preenche só na primeira carga ou em recarga pedida; buscas em segundo plano não apagam a digitação. */
function carregar() {
  const u = existente.data.value
  if (!u || versao.value !== null) return
  Object.assign(formulario, {
    nome: u.nome,
    fazenda: u.fazenda
      ? { id: u.fazenda.id, codigo: u.fazenda.codigo, nome: u.fazenda.nome }
      : null,
    cep: u.cep ?? '',
    endereco: u.endereco ?? '',
    cidade: u.cidade ?? '',
    uf: u.uf ?? '',
    observacoes: u.observacoes ?? '',
  })
  versao.value = u.versao
  original.value = JSON.stringify(formulario)
}
watch(() => existente.data.value, carregar, { immediate: true })

const salvo = ref(false)
const alterado = computed(() => !salvo.value && JSON.stringify(formulario) !== original.value)
useAlteracoesPendentes(alterado)

function dados(): DadosUsina {
  const texto = (v: string) => (v.trim() ? v : undefined)
  return {
    nome: formulario.nome,
    fazendaId: formulario.fazenda?.id,
    cep: texto(formulario.cep),
    endereco: texto(formulario.endereco),
    cidade: texto(formulario.cidade),
    uf: texto(formulario.uf),
    observacoes: texto(formulario.observacoes),
  }
}

const salvar = useMutation({
  mutationFn: (confirmarNomeRepetido: boolean) =>
    id.value === null
      ? cadastrarUsina(http, dados(), confirmarNomeRepetido)
      : editarUsina(
          http,
          id.value,
          { ...dados(), versao: versao.value as number },
          confirmarNomeRepetido,
        ),
  onSuccess: async (usina) => {
    salvo.value = true
    queryClient.setQueryData(chavesUsinas.usina(usina.id), usina)
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: chavesUsinas.listas }),
      queryClient.invalidateQueries({ queryKey: chavesUsinas.historico(usina.id) }),
    ])
    await router.push({
      name: 'usina',
      params: { id: usina.id },
      query: id.value === null ? { criada: '1' } : { aba: 'informacoes', salva: '1' },
    })
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
const erroGeral = computed(() => {
  if (!salvar.isError.value || codigo.value === 'NOME_POSSIVEL_DUPLICIDADE') return ''
  if (codigo.value === 'CONFLITO_CONCORRENTE') return ''
  return Object.keys(erros.value).length
    ? 'Revise os campos destacados. Os dados digitados foram mantidos.'
    : describeApiError(salvar.error.value)
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
      {{
        id === null
          ? 'Nova usina'
          : (existente.data.value?.nome ?? 'Usina') + ' / Editar informações'
      }}
    </nav>
    <h1>{{ id === null ? 'Nova usina' : 'Editar informações da usina' }}</h1>
    <p class="subtitulo">
      O identificador é gerado pelo sistema. Campos com <span class="obrigatorio">*</span> são
      obrigatórios; os demais campos obrigatórios ainda estão em definição (DP-01).
    </p>

    <p v-if="id !== null && existente.isPending.value" role="status">Carregando usina…</p>
    <AlertaMensagem v-else-if="existente.isError.value" tipo="erro">
      {{ describeApiError(existente.error.value) }}
    </AlertaMensagem>
    <form
      v-else
      class="cartao"
      novalidate
      :aria-busy="salvar.isPending.value"
      @submit.prevent="enviar(false)"
    >
      <AlertaMensagem v-if="semelhantes.length" tipo="aviso">
        Já existe usina com nome igual ou muito parecido:
        <ul>
          <li v-for="s in semelhantes" :key="s.id">
            <RouterLink :to="{ name: 'usina', params: { id: s.id } }"
              >{{ s.codigo }} · {{ s.nome }}</RouterLink
            >
          </li>
        </ul>
        Confira se não é a mesma usina. Nada foi salvo.
        <button
          type="button"
          class="secundario"
          :disabled="salvar.isPending.value"
          @click="enviar(true)"
        >
          É outra usina: salvar mesmo assim
        </button>
      </AlertaMensagem>
      <AlertaMensagem v-if="codigo === 'CONFLITO_CONCORRENTE'" tipo="aviso">
        Esta usina foi alterada por outra pessoa depois que você abriu a página. Nada foi salvo e
        seus dados digitados foram mantidos.
        <button type="button" class="link" @click="recarregar">
          Recarregar a versão atual (descarta o que foi digitado)
        </button>
      </AlertaMensagem>
      <AlertaMensagem v-if="erroGeral" tipo="erro">{{ erroGeral }}</AlertaMensagem>

      <h2>Identificação</h2>
      <div class="grade">
        <CampoTexto
          id="usina-nome"
          v-model="formulario.nome"
          rotulo="Nome da usina"
          obrigatorio
          :maximo="150"
          :erro="erros.nome"
        />
        <SeletorFazenda id="usina-fazenda" v-model="formulario.fazenda" :erro="erros.fazendaId" />
      </div>

      <h2>Localização</h2>
      <div class="grade">
        <CampoTexto
          id="usina-cep"
          v-model="formulario.cep"
          rotulo="CEP"
          :maximo="10"
          ajuda="8 dígitos"
          :erro="erros.cep"
        />
        <CampoTexto
          id="usina-cidade"
          v-model="formulario.cidade"
          rotulo="Cidade"
          :maximo="100"
          :erro="erros.cidade"
        />
        <div class="campo">
          <label for="usina-uf">UF</label>
          <select
            id="usina-uf"
            v-model="formulario.uf"
            :aria-invalid="erros.uf ? 'true' : undefined"
            :aria-describedby="erros.uf ? 'usina-uf-erro' : undefined"
          >
            <option value="">—</option>
            <option v-for="uf in UFS" :key="uf" :value="uf">{{ uf }}</option>
          </select>
          <p v-if="erros.uf" id="usina-uf-erro" class="ajuda ajuda--erro">{{ erros.uf }}</p>
        </div>
        <CampoTexto
          id="usina-endereco"
          v-model="formulario.endereco"
          rotulo="Endereço"
          :maximo="300"
          :erro="erros.endereco"
        />
      </div>
      <CampoTexto
        id="usina-observacoes"
        v-model="formulario.observacoes"
        rotulo="Observações"
        :maximo="500"
        :erro="erros.observacoes"
      />

      <div class="acoes acoes--fim">
        <RouterLink :to="id === null ? { name: 'usinas' } : { name: 'usina', params: { id } }">
          Cancelar
        </RouterLink>
        <button type="submit" :disabled="salvar.isPending.value">
          {{ salvar.isPending.value ? 'Salvando…' : 'Salvar usina' }}
        </button>
      </div>
    </form>
  </main>
</template>
