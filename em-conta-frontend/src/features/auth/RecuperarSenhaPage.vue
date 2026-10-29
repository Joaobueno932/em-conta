<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'

import { solicitarRecuperacao } from './auth-api'

const http = useHttpClient()
const email = ref('')
const enviando = ref(false)
const confirmacao = ref<string | null>(null)
const erro = ref<string | null>(null)

async function enviar() {
  enviando.value = true
  erro.value = null
  confirmacao.value = null
  try {
    confirmacao.value = (await solicitarRecuperacao(http, email.value)).mensagem
  } catch (error) {
    erro.value = describeApiError(error)
  } finally {
    enviando.value = false
  }
}
</script>

<template>
  <main class="pagina pagina--estreita">
    <h1>Recuperar senha</h1>
    <p>
      Informe o e-mail da sua conta. Se ela estiver ativa, você receberá um link para criar uma nova
      senha.
    </p>
    <AlertaMensagem v-if="confirmacao" tipo="sucesso">{{ confirmacao }}</AlertaMensagem>
    <AlertaMensagem v-if="erro" tipo="erro">{{ erro }}</AlertaMensagem>

    <form novalidate @submit.prevent="enviar">
      <div class="campo">
        <label for="recuperacao-email">E-mail</label>
        <input
          id="recuperacao-email"
          v-model="email"
          type="email"
          autocomplete="email"
          inputmode="email"
          required
        />
      </div>
      <div class="acoes">
        <button type="submit" :disabled="enviando || !email">
          {{ enviando ? 'Enviando…' : 'Enviar link' }}
        </button>
        <RouterLink :to="{ name: 'entrar' }">Voltar para o login</RouterLink>
      </div>
    </form>
  </main>
</template>
