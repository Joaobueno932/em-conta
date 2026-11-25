<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { codigoDoErro, describeApiError } from '@/shared/api/errors'
import CampoSenha from '@/shared/components/CampoSenha.vue'
import IconeTraco from '@/shared/components/IconeTraco.vue'

import { aceitarConvite, redefinirSenha, validarToken, type FinalidadeToken } from './auth-api'
import LayoutSessao from './LayoutSessao.vue'
import { CAMPOS_VAZIOS } from './mensagens-sessao'
import { problemaNaSenha, SENHA_MAXIMO, SENHA_MINIMO } from './politica-senha'
import { extrairTokenDaUrl } from './token-da-url'

/** Definição de senha por link: aceite de convite ou redefinição após recuperação. */
const props = defineProps<{ finalidade: FinalidadeToken }>()

const http = useHttpClient()

// O token sai da barra de endereço assim que a página abre e fica só na memória.
const token = extrairTokenDaUrl()

type Estado = 'verificando' | 'valido' | 'invalido' | 'indisponivel' | 'concluido'
const estado = ref<Estado>(token ? 'verificando' : 'invalido')
const nome = ref('')
const senha = ref('')
const confirmacao = ref('')
const enviando = ref(false)
const erro = ref<string | null>(null)
const tentou = ref(false)

const convite = computed(() => props.finalidade === 'CONVITE')
const titulo = computed(() => {
  if (estado.value === 'invalido') return 'Link expirado'
  if (estado.value === 'concluido')
    return convite.value ? 'Acesso ativado' : 'Senha alterada com sucesso'
  return convite.value ? 'Ativar acesso' : 'Criar nova senha'
})

const problemaSenha = computed(() => {
  if (!tentou.value) return null
  return senha.value === '' ? 'Insira a nova senha' : problemaNaSenha(senha.value)
})
const problemaConfirmacao = computed(() =>
  tentou.value && confirmacao.value === '' ? 'Insira novamente a nova senha' : null,
)
const divergentes = computed(
  () =>
    tentou.value &&
    senha.value !== '' &&
    confirmacao.value !== '' &&
    senha.value !== confirmacao.value,
)
const resumo = computed(() => {
  if (tentou.value && (senha.value === '' || confirmacao.value === '')) return CAMPOS_VAZIOS
  if (divergentes.value) return 'As senhas não coincidem.'
  return erro.value
})

onMounted(async () => {
  if (!token) return
  try {
    nome.value = (await validarToken(http, token, props.finalidade)).nome
    estado.value = 'valido'
  } catch (error) {
    estado.value = codigoDoErro(error) === 'TOKEN_INVALIDO' ? 'invalido' : 'indisponivel'
    erro.value = estado.value === 'indisponivel' ? describeApiError(error) : null
  }
})

async function enviar() {
  tentou.value = true
  erro.value = null
  if (!token) return
  if (problemaSenha.value || problemaConfirmacao.value || divergentes.value) {
    await nextTick()
    const primeiro = problemaSenha.value || divergentes.value ? 'nova-senha' : 'confirmacao-senha'
    document.getElementById(primeiro)?.focus()
    return
  }
  enviando.value = true
  try {
    if (convite.value) {
      await aceitarConvite(http, token, senha.value)
    } else {
      await redefinirSenha(http, token, senha.value)
    }
    senha.value = ''
    confirmacao.value = ''
    tentou.value = false
    estado.value = 'concluido'
  } catch (error) {
    if (codigoDoErro(error) === 'TOKEN_INVALIDO') {
      estado.value = 'invalido'
    } else {
      erro.value = describeApiError(error)
    }
  } finally {
    enviando.value = false
  }
}
</script>

<template>
  <LayoutSessao :titulo="titulo">
    <p v-if="estado === 'verificando'" class="descricao" role="status">Verificando o link…</p>

    <template v-else-if="estado === 'invalido'">
      <p class="descricao" role="alert">Este link é inválido, expirou ou já foi utilizado.</p>
      <p v-if="convite" class="descricao">
        Peça ao administrador do sistema que reenvie o convite.
      </p>
      <RouterLink :to="{ name: 'entrar' }" class="principal">
        Voltar ao login
        <IconeTraco nome="seta-direita" :tamanho="16" />
      </RouterLink>
      <p v-if="!convite" class="link-voltar">
        <RouterLink :to="{ name: 'recuperar-senha' }">Solicitar um novo link</RouterLink>
      </p>
    </template>

    <p v-else-if="estado === 'indisponivel'" class="resumo-erro" role="alert">{{ erro }}</p>

    <template v-else-if="estado === 'concluido'">
      <p class="descricao descricao--suave" role="status">
        {{
          convite
            ? 'Entre com o e-mail e a senha que você definiu.'
            : 'Use sua nova senha para acessar sua conta.'
        }}
      </p>
      <RouterLink :to="{ name: 'entrar' }" class="principal">
        {{ convite ? 'Ir para o login' : 'Voltar ao login' }}
        <IconeTraco nome="seta-direita" :tamanho="16" />
      </RouterLink>
    </template>

    <form v-else novalidate @submit.prevent="enviar">
      <p class="descricao">
        <template v-if="convite">
          Olá, {{ nome }}. Defina a senha para ativar o seu acesso ao Em Conta.
        </template>
        <template v-else>Defina uma nova senha para acessar sua conta.</template>
      </p>
      <CampoSenha
        id="nova-senha"
        v-model="senha"
        rotulo="Nova senha"
        autocomplete="new-password"
        obrigatorio
        :erro="problemaSenha"
        :invalido="divergentes"
        :descrito-por="resumo ? 'requisitos-senha definicao-erro' : 'requisitos-senha'"
      />
      <CampoSenha
        id="confirmacao-senha"
        v-model="confirmacao"
        rotulo="Confirmar nova senha"
        autocomplete="new-password"
        obrigatorio
        :erro="problemaConfirmacao"
        :invalido="divergentes"
        :descrito-por="resumo ? 'definicao-erro' : undefined"
      />
      <div id="requisitos-senha" class="requisitos">
        <p>Requisitos</p>
        <ul>
          <li>De {{ SENHA_MINIMO }} a {{ SENHA_MAXIMO }} caracteres</li>
          <li>Pode usar espaços, como numa frase, mas não só espaços</li>
        </ul>
      </div>
      <p v-if="resumo" id="definicao-erro" class="resumo-erro" role="alert">{{ resumo }}</p>
      <button type="submit" class="principal" :disabled="enviando">
        <template v-if="enviando">{{ convite ? 'Ativando…' : 'Redefinindo…' }}</template>
        <template v-else>
          {{ convite ? 'Ativar acesso' : 'Redefinir senha' }}
          <IconeTraco nome="seta-direita" :tamanho="16" />
        </template>
      </button>
    </form>
  </LayoutSessao>
</template>

<style scoped>
.requisitos {
  margin: -3px 0 14px;
  color: var(--cor-texto-2);
  font-size: 12px;
  line-height: 1.4;
}

.requisitos p {
  margin: 0 0 6px;
}

.requisitos ul {
  margin: 0;
  padding-left: 18px;
  line-height: 1.5;
}
</style>
