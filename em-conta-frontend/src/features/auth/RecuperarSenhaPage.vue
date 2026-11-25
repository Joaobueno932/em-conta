<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { RouterLink } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { describeApiError } from '@/shared/api/errors'
import CampoTexto from '@/shared/components/CampoTexto.vue'
import IconeTraco from '@/shared/components/IconeTraco.vue'

import { solicitarRecuperacao } from './auth-api'
import LayoutSessao from './LayoutSessao.vue'
import { CAMPOS_VAZIOS, pareceEmail } from './mensagens-sessao'

const http = useHttpClient()
const email = ref('')
const enviando = ref(false)
const tentou = ref(false)
const erro = ref<string | null>(null)
/** E-mail da solicitação aceita. A confirmação é a mesma exista ou não a conta. */
const enviadoPara = ref<string | null>(null)

const erroEmail = computed(() => {
  if (!tentou.value) return null
  if (email.value.trim() === '') return 'Informe seu e-mail'
  return pareceEmail(email.value) ? null : 'Informe um e-mail válido'
})
const resumo = computed(() => {
  if (!erroEmail.value) return erro.value
  return email.value.trim() === '' ? CAMPOS_VAZIOS : 'Formato de e-mail inválido.'
})

async function enviar() {
  tentou.value = true
  erro.value = null
  if (erroEmail.value) {
    document.getElementById('recuperacao-email')?.focus()
    return
  }
  enviando.value = true
  try {
    await solicitarRecuperacao(http, email.value)
    enviadoPara.value = email.value.trim()
  } catch (error) {
    erro.value = describeApiError(error)
  } finally {
    enviando.value = false
  }
}

async function tentarOutroEmail() {
  enviadoPara.value = null
  tentou.value = false
  email.value = ''
  await nextTick()
  document.getElementById('recuperacao-email')?.focus()
}
</script>

<template>
  <LayoutSessao :titulo="enviadoPara ? 'Verifique seu e-mail' : 'Esqueceu sua senha?'">
    <template v-if="enviadoPara">
      <div role="status">
        <p class="descricao">
          Se houver uma conta vinculada ao e-mail {{ enviadoPara }}, você receberá um link para
          redefinir sua senha.
        </p>
        <p class="descricao">Confira também a pasta de spam.</p>
      </div>
      <RouterLink :to="{ name: 'entrar' }" class="principal">
        Voltar ao login
        <IconeTraco nome="seta-direita" :tamanho="16" />
      </RouterLink>
      <p class="link-voltar">
        <button type="button" class="link" @click="tentarOutroEmail">
          <IconeTraco nome="seta-esquerda" :tamanho="14" />
          Tentar outro e-mail
        </button>
      </p>
    </template>

    <form v-else novalidate @submit.prevent="enviar">
      <p class="descricao">
        Insira seu endereço de e-mail para enviarmos instruções para redefinir sua senha
      </p>
      <CampoTexto
        id="recuperacao-email"
        v-model="email"
        rotulo="E-mail"
        tipo="email"
        obrigatorio
        autocomplete="email"
        :erro="erroEmail"
        :descrito-por="resumo ? 'recuperacao-erro' : undefined"
      />
      <p v-if="resumo" id="recuperacao-erro" class="resumo-erro" role="alert">{{ resumo }}</p>
      <button type="submit" class="principal" :disabled="enviando">
        {{ enviando ? 'Enviando…' : 'Enviar' }}
        <IconeTraco v-if="!enviando" nome="seta-direita" :tamanho="16" />
      </button>
      <p class="link-voltar">
        <RouterLink :to="{ name: 'entrar' }">
          <IconeTraco nome="seta-esquerda" :tamanho="14" />
          Voltar ao login
        </RouterLink>
      </p>
    </form>
  </LayoutSessao>
</template>
