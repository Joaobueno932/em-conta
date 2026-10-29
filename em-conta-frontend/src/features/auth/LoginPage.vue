<script setup lang="ts">
import { useQueryClient } from '@tanstack/vue-query'
import { computed, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { destinoPosLogin } from '@/app/guards'
import { describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'
import CampoSenha from '@/shared/components/CampoSenha.vue'

import { chaveSessao, entrar } from './auth-api'

const http = useHttpClient()
const route = useRoute()
const router = useRouter()
const queryClient = useQueryClient()

const email = ref('')
const senha = ref('')
const enviando = ref(false)
const erro = ref<string | null>(null)

const MENSAGENS: Record<string, string> = {
  expirada: 'Sua sessão expirou ou foi encerrada. Entre novamente.',
  saiu: 'Você saiu do sistema.',
  indisponivel: 'Não foi possível verificar sua sessão: a API está indisponível. Tente novamente.',
  'senha-redefinida': 'Senha redefinida. Entre com a nova senha.',
  'convite-aceito': 'Acesso ativado. Entre com o e-mail e a senha que você definiu.',
}
const aviso = computed(() =>
  typeof route.query.motivo === 'string' ? MENSAGENS[route.query.motivo] : undefined,
)

async function enviar() {
  enviando.value = true
  erro.value = null
  try {
    const usuario = await entrar(http, email.value, senha.value)
    // Descarta qualquer dado de uma sessão anterior antes de exibir a nova.
    queryClient.clear()
    queryClient.setQueryData(chaveSessao, usuario)
    senha.value = ''
    await router.replace(destinoPosLogin(router, route.query.redirect))
  } catch (error) {
    erro.value = describeApiError(error)
  } finally {
    enviando.value = false
  }
}
</script>

<template>
  <main class="pagina pagina--estreita">
    <h1>Entrar no Em Conta</h1>
    <AlertaMensagem v-if="aviso" tipo="info">{{ aviso }}</AlertaMensagem>
    <AlertaMensagem v-if="erro" tipo="erro">{{ erro }}</AlertaMensagem>

    <form novalidate @submit.prevent="enviar">
      <div class="campo">
        <label for="login-email">E-mail</label>
        <input
          id="login-email"
          v-model="email"
          type="email"
          autocomplete="username"
          inputmode="email"
          required
        />
      </div>
      <CampoSenha id="login-senha" v-model="senha" rotulo="Senha" autocomplete="current-password" />
      <div class="acoes">
        <button type="submit" :disabled="enviando || !email || !senha">
          {{ enviando ? 'Entrando…' : 'Entrar' }}
        </button>
        <RouterLink :to="{ name: 'recuperar-senha' }">Esqueci minha senha</RouterLink>
      </div>
    </form>
  </main>
</template>
