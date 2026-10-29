<script setup lang="ts">
import { computed, ref } from 'vue'

import { useHttpClient } from '@/app/injection'
import { describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'
import CampoSenha from '@/shared/components/CampoSenha.vue'

import { alterarSenha } from './auth-api'
import { problemaNaSenha, SENHA_MAXIMO, SENHA_MINIMO } from './politica-senha'

const http = useHttpClient()
const atual = ref('')
const nova = ref('')
const confirmacao = ref('')
const enviando = ref(false)
const erro = ref<string | null>(null)
const sucesso = ref(false)
const mostrarProblemas = ref(false)

const problemaNova = computed(() => (mostrarProblemas.value ? problemaNaSenha(nova.value) : null))
const problemaConfirmacao = computed(() =>
  mostrarProblemas.value && confirmacao.value !== nova.value ? 'As senhas não conferem.' : null,
)

async function enviar() {
  mostrarProblemas.value = true
  sucesso.value = false
  if (!atual.value || problemaNaSenha(nova.value) || confirmacao.value !== nova.value) return
  enviando.value = true
  erro.value = null
  try {
    await alterarSenha(http, atual.value, nova.value)
    atual.value = ''
    nova.value = ''
    confirmacao.value = ''
    mostrarProblemas.value = false
    sucesso.value = true
  } catch (error) {
    erro.value = describeApiError(error)
  } finally {
    enviando.value = false
  }
}
</script>

<template>
  <main class="pagina pagina--estreita">
    <h1>Alterar senha</h1>
    <p>Esta sessão continuará ativa. As sessões abertas em outros dispositivos serão encerradas.</p>
    <AlertaMensagem v-if="sucesso" tipo="sucesso">Senha alterada.</AlertaMensagem>
    <AlertaMensagem v-if="erro" tipo="erro">{{ erro }}</AlertaMensagem>

    <form novalidate @submit.prevent="enviar">
      <CampoSenha
        id="senha-atual"
        v-model="atual"
        rotulo="Senha atual"
        autocomplete="current-password"
      />
      <CampoSenha
        id="senha-nova"
        v-model="nova"
        rotulo="Nova senha"
        autocomplete="new-password"
        :descricao="`De ${SENHA_MINIMO} a ${SENHA_MAXIMO} caracteres. Pode usar espaços, como numa frase.`"
        :erro="problemaNova"
      />
      <CampoSenha
        id="senha-confirmacao"
        v-model="confirmacao"
        rotulo="Repita a nova senha"
        autocomplete="new-password"
        :erro="problemaConfirmacao"
      />
      <button type="submit" :disabled="enviando">
        {{ enviando ? 'Salvando…' : 'Alterar senha' }}
      </button>
    </form>
  </main>
</template>
