<script setup lang="ts">
import { useMutation, useQueryClient } from '@tanstack/vue-query'
import { computed, reactive, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { camposDoErro } from '@/shared/api/campos'
import { codigoDoErro, describeApiError } from '@/shared/api/errors'
import { ApiError } from '@/shared/api/http-client'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'
import { useAlteracoesPendentes } from '@/shared/components/use-alteracoes-pendentes'

import { cadastrarAssociado, chaves, type TipoPessoa } from './associados-api'
import CampoTexto from '@/shared/components/CampoTexto.vue'
import { cnpjValido, cpfValido, formatarDocumento } from './documento'

const http = useHttpClient()
const router = useRouter()
const queryClient = useQueryClient()

interface ContatoDigitado {
  chave: number
  nome: string
  email: string
  telefone: string
}

let sequencia = 0
const tipo = ref<TipoPessoa>('PF')
const formulario = reactive({ documento: '', nome: '', nomeFantasia: '' })
const contatos = ref<ContatoDigitado[]>([])
const avisoDocumento = ref('')

const alterado = computed(
  () =>
    Boolean(formulario.documento || formulario.nome || formulario.nomeFantasia) ||
    contatos.value.length > 0,
)
const salvo = ref(false)
useAlteracoesPendentes(computed(() => alterado.value && !salvo.value))

function adicionarContato() {
  contatos.value.push({ chave: ++sequencia, nome: '', email: '', telefone: '' })
}

function removerContato(chave: number) {
  contatos.value = contatos.value.filter((contato) => contato.chave !== chave)
}

/** Aviso imediato; o servidor revalida com as mesmas regras. */
function conferirDocumento() {
  const valor = formulario.documento.trim()
  if (!valor) {
    avisoDocumento.value = ''
    return
  }
  const valido = tipo.value === 'PF' ? cpfValido(valor) : cnpjValido(valor)
  avisoDocumento.value = valido ? '' : tipo.value === 'PF' ? 'CPF inválido.' : 'CNPJ inválido.'
  if (valido) formulario.documento = formatarDocumento(tipo.value, valor)
}

const cadastro = useMutation({
  mutationFn: () =>
    cadastrarAssociado(http, {
      tipo: tipo.value,
      documento: formulario.documento,
      nome: formulario.nome,
      ...(tipo.value === 'PJ' && formulario.nomeFantasia
        ? { nomeFantasia: formulario.nomeFantasia }
        : {}),
      contatos: contatos.value.map(({ nome, email, telefone }) => ({
        nome,
        ...(email.trim() ? { email } : {}),
        ...(telefone.trim() ? { telefone } : {}),
      })),
    }),
  onSuccess: async (associado) => {
    salvo.value = true
    await queryClient.invalidateQueries({ queryKey: chaves.listas })
    await router.push({ name: 'associado', params: { id: associado.id }, query: { criado: '1' } })
  },
})

const erros = computed(() => camposDoErro(cadastro.error.value))
const existenteId = computed(() => {
  const erro = cadastro.error.value
  const id = erro instanceof ApiError ? erro.problem?.associadoExistenteId : undefined
  return typeof id === 'number' ? id : null
})
const erroGeral = computed(() => {
  if (!cadastro.isError.value) return ''
  if (codigoDoErro(cadastro.error.value) === 'DOCUMENTO_JA_CADASTRADO') {
    return 'Já existe um associado com este documento. Nenhum dado foi alterado.'
  }
  return Object.keys(erros.value).length
    ? 'Revise os campos destacados. Os dados digitados foram mantidos.'
    : describeApiError(cadastro.error.value)
})

function erroContato(indice: number, campo: string) {
  return erros.value[`contatos[${indice}].${campo}`]
}

function salvar() {
  if (cadastro.isPending.value) return
  cadastro.mutate()
}
</script>

<template>
  <main class="pagina pagina--larga">
    <nav aria-label="Trilha" class="ajuda trilha">
      <RouterLink :to="{ name: 'associados' }">Todos os associados</RouterLink> / Novo associado
    </nav>
    <h1>Novo associado</h1>
    <p class="subtitulo">
      Cadastre um cliente PF ou PJ. Campos com <span class="obrigatorio">*</span> são obrigatórios.
    </p>

    <form class="cartao" novalidate :aria-busy="cadastro.isPending.value" @submit.prevent="salvar">
      <AlertaMensagem v-if="erroGeral" tipo="erro">
        {{ erroGeral }}
        <RouterLink v-if="existenteId" :to="{ name: 'associado', params: { id: existenteId } }">
          Abrir o cadastro existente
        </RouterLink>
      </AlertaMensagem>

      <fieldset class="tipo">
        <legend>Tipo de pessoa</legend>
        <label class="opcao" :class="{ 'opcao--marcada': tipo === 'PF' }">
          <input v-model="tipo" type="radio" name="tipo" value="PF" @change="conferirDocumento" />
          <span>
            Pessoa física (PF)
            <span class="ajuda">Cliente identificado por CPF</span>
          </span>
        </label>
        <label class="opcao" :class="{ 'opcao--marcada': tipo === 'PJ' }">
          <input v-model="tipo" type="radio" name="tipo" value="PJ" @change="conferirDocumento" />
          <span>
            Pessoa jurídica (PJ)
            <span class="ajuda">Cliente identificado por CNPJ</span>
          </span>
        </label>
      </fieldset>

      <h2>Identificação</h2>
      <div class="grade">
        <CampoTexto
          id="associado-nome"
          v-model="formulario.nome"
          :rotulo="tipo === 'PF' ? 'Nome completo' : 'Razão social'"
          obrigatorio
          :maximo="200"
          :erro="erros.nome"
        />
        <CampoTexto
          id="associado-documento"
          v-model="formulario.documento"
          :rotulo="tipo === 'PF' ? 'CPF' : 'CNPJ'"
          obrigatorio
          :maximo="40"
          :erro="erros.documento ?? avisoDocumento"
          :ajuda="tipo === 'PJ' ? 'Aceita CNPJ numérico ou alfanumérico.' : undefined"
          autocomplete="off"
          @blur="conferirDocumento"
        />
        <CampoTexto
          v-if="tipo === 'PJ'"
          id="associado-fantasia"
          v-model="formulario.nomeFantasia"
          rotulo="Nome fantasia"
          :maximo="200"
          :erro="erros.nomeFantasia"
        />
      </div>

      <h2>Contatos</h2>
      <p class="ajuda">Informe nome e ao menos um meio de contato (e-mail ou telefone).</p>
      <p v-if="erros.contatos" class="ajuda ajuda--erro">{{ erros.contatos }}</p>
      <fieldset v-for="(contato, indice) in contatos" :key="contato.chave" class="contato">
        <legend>Contato {{ indice + 1 }}</legend>
        <div class="grade">
          <CampoTexto
            :id="`contato-${contato.chave}-nome`"
            v-model="contato.nome"
            rotulo="Nome"
            obrigatorio
            :maximo="150"
            :erro="erroContato(indice, 'nome')"
          />
          <CampoTexto
            :id="`contato-${contato.chave}-telefone`"
            v-model="contato.telefone"
            rotulo="Telefone"
            tipo="tel"
            :maximo="30"
            ajuda="Com DDD, ex.: (67) 99812-3344"
            :erro="erroContato(indice, 'telefone')"
          />
          <CampoTexto
            :id="`contato-${contato.chave}-email`"
            v-model="contato.email"
            rotulo="E-mail"
            tipo="email"
            :maximo="254"
            :erro="erroContato(indice, 'email')"
          />
        </div>
        <button type="button" class="secundario" @click="removerContato(contato.chave)">
          Remover contato {{ indice + 1 }}
        </button>
      </fieldset>
      <button type="button" class="secundario" @click="adicionarContato">Adicionar contato</button>

      <AlertaMensagem tipo="info">
        Endereço, dados pessoais, equipe responsável, usina, situação e datas do cliente ainda não
        fazem parte deste cadastro (campos aguardando definição, DP-01 e DP-08). Unidades
        consumidoras são cadastradas na página do associado.
      </AlertaMensagem>

      <div class="acoes acoes--fim">
        <RouterLink class="cancelar" :to="{ name: 'associados' }">Cancelar</RouterLink>
        <button type="submit" :disabled="cadastro.isPending.value">
          {{ cadastro.isPending.value ? 'Salvando…' : 'Salvar associado' }}
        </button>
      </div>
    </form>
  </main>
</template>

<style scoped>
.trilha {
  margin-bottom: 0.5rem;
}

.tipo {
  border: none;
  padding: 0;
  margin: 0 0 1.5rem;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(16rem, 1fr));
  gap: 1rem;
}

.tipo legend {
  margin-bottom: 0.5rem;
}

.opcao {
  display: flex;
  gap: 0.75rem;
  align-items: flex-start;
  padding: 0.75rem 1rem;
  border: 1px solid var(--cor-borda);
  border-radius: var(--raio);
  font-weight: 500;
  cursor: pointer;
}

.opcao .ajuda {
  display: block;
  font-weight: 400;
}

.opcao--marcada {
  background: var(--cor-destaque-suave);
  border-color: var(--cor-destaque-borda);
}

.contato {
  border: 1px solid var(--cor-borda);
  border-radius: var(--raio);
  padding: 1rem;
  margin: 0 0 1rem;
}

.cancelar {
  padding: 0.5rem 1rem;
}
</style>
