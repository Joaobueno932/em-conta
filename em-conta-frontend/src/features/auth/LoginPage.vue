<script setup lang="ts">
import { useQueryClient } from '@tanstack/vue-query'
import { computed, nextTick, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { destinoPosLogin } from '@/app/guards'
import { codigoDoErro, describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'
import CampoSenha from '@/shared/components/CampoSenha.vue'
import CampoTexto from '@/shared/components/CampoTexto.vue'
import IconeTraco, { type NomeIcone } from '@/shared/components/IconeTraco.vue'

import { chaveSessao, entrar } from './auth-api'
import LayoutSessao from './LayoutSessao.vue'
import { CAMPOS_VAZIOS } from './mensagens-sessao'

const http = useHttpClient()
const route = useRoute()
const router = useRouter()
const queryClient = useQueryClient()

const email = ref('')
const senha = ref('')
const enviando = ref(false)
const tentou = ref(false)
const erro = ref<string | null>(null)
const credenciaisInvalidas = ref(false)

interface Aviso {
  tipo: 'aviso' | 'sucesso' | 'info'
  icone: NomeIcone
  texto: string
}
// O frontend não sabe se a sessão caiu por inatividade, pelo limite de duração ou por revogação
// (troca de senha, desativação): a mensagem cobre os três casos.
const MENSAGENS: Record<string, Aviso> = {
  expirada: {
    tipo: 'aviso',
    icone: 'relogio',
    texto: 'Sua sessão expirou ou foi encerrada. Entre novamente para continuar.',
  },
  saiu: { tipo: 'info', icone: 'informacao', texto: 'Você saiu do sistema.' },
  indisponivel: {
    tipo: 'aviso',
    icone: 'exclamacao',
    texto: 'Não foi possível verificar sua sessão: a API está indisponível. Tente novamente.',
  },
}
const aviso = computed(() =>
  typeof route.query.motivo === 'string' ? MENSAGENS[route.query.motivo] : undefined,
)

const erroEmail = computed(() =>
  tentou.value && email.value.trim() === '' ? 'Informe seu e-mail' : null,
)
const erroSenha = computed(() => (tentou.value && senha.value === '' ? 'Informe sua senha' : null))
const resumo = computed(() => (erroEmail.value || erroSenha.value ? CAMPOS_VAZIOS : erro.value))

async function enviar() {
  tentou.value = true
  erro.value = null
  credenciaisInvalidas.value = false
  if (erroEmail.value || erroSenha.value) {
    await nextTick()
    document.getElementById(erroEmail.value ? 'login-email' : 'login-senha')?.focus()
    return
  }
  enviando.value = true
  try {
    const usuario = await entrar(http, email.value, senha.value)
    // Descarta qualquer dado de uma sessão anterior antes de exibir a nova.
    queryClient.clear()
    queryClient.setQueryData(chaveSessao, usuario)
    senha.value = ''
    await router.replace(destinoPosLogin(router, route.query.redirect))
  } catch (error) {
    // A resposta do servidor é genérica: não diz se o e-mail existe nem qual campo está errado.
    credenciaisInvalidas.value = codigoDoErro(error) === 'CREDENCIAIS_INVALIDAS'
    erro.value = credenciaisInvalidas.value
      ? 'E-mail ou senha incorretos. Confira os dados e tente novamente.'
      : describeApiError(error)
  } finally {
    enviando.value = false
  }
}
</script>

<template>
  <LayoutSessao titulo="Entrar">
    <template #aviso>
      <AlertaMensagem v-if="aviso" :tipo="aviso.tipo">
        <IconeTraco :nome="aviso.icone" :tamanho="16" />
        <span>{{ aviso.texto }}</span>
      </AlertaMensagem>
    </template>

    <form novalidate @submit.prevent="enviar">
      <CampoTexto
        id="login-email"
        v-model="email"
        rotulo="E-mail"
        tipo="email"
        obrigatorio
        autocomplete="username"
        :erro="erroEmail"
        :invalido="credenciaisInvalidas"
        :descrito-por="resumo ? 'login-erro' : undefined"
      />
      <CampoSenha
        id="login-senha"
        v-model="senha"
        rotulo="Senha"
        autocomplete="current-password"
        obrigatorio
        :erro="erroSenha"
        :invalido="credenciaisInvalidas"
        :descrito-por="resumo ? 'login-erro' : undefined"
      />
      <p class="link-lateral">
        <RouterLink :to="{ name: 'recuperar-senha' }">Esqueci minha senha</RouterLink>
      </p>
      <p v-if="resumo" id="login-erro" class="resumo-erro" role="alert">{{ resumo }}</p>
      <button type="submit" class="principal" :disabled="enviando">
        {{ enviando ? 'Entrando…' : 'Entrar' }}
        <IconeTraco v-if="!enviando" nome="seta-direita" :tamanho="16" />
      </button>
    </form>
  </LayoutSessao>
</template>
