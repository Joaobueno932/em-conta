<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { codigoDoErro, describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'
import CampoSenha from '@/shared/components/CampoSenha.vue'

import { aceitarConvite, redefinirSenha, validarToken, type FinalidadeToken } from './auth-api'
import { problemaNaSenha, SENHA_MAXIMO, SENHA_MINIMO } from './politica-senha'
import { extrairTokenDaUrl } from './token-da-url'

/** Definição de senha por link: aceite de convite ou redefinição após recuperação. */
const props = defineProps<{ finalidade: FinalidadeToken }>()

const http = useHttpClient()
const router = useRouter()

// O token sai da barra de endereço assim que a página abre e fica só na memória.
const token = extrairTokenDaUrl()

type Estado = 'verificando' | 'valido' | 'invalido' | 'indisponivel'
const estado = ref<Estado>(token ? 'verificando' : 'invalido')
const nome = ref('')
const senha = ref('')
const confirmacao = ref('')
const enviando = ref(false)
const erro = ref<string | null>(null)
const mostrarProblemas = ref(false)

const convite = computed(() => props.finalidade === 'CONVITE')
const problemaSenha = computed(() => (mostrarProblemas.value ? problemaNaSenha(senha.value) : null))
const problemaConfirmacao = computed(() =>
  mostrarProblemas.value && confirmacao.value !== senha.value ? 'As senhas não conferem.' : null,
)

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
  mostrarProblemas.value = true
  if (!token || problemaNaSenha(senha.value) || confirmacao.value !== senha.value) return
  enviando.value = true
  erro.value = null
  try {
    if (convite.value) {
      await aceitarConvite(http, token, senha.value)
    } else {
      await redefinirSenha(http, token, senha.value)
    }
    senha.value = ''
    confirmacao.value = ''
    await router.replace({
      name: 'entrar',
      query: { motivo: convite.value ? 'convite-aceito' : 'senha-redefinida' },
    })
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
  <main class="pagina pagina--estreita">
    <h1>{{ convite ? 'Ativar acesso' : 'Definir nova senha' }}</h1>

    <p v-if="estado === 'verificando'" role="status">Verificando o link…</p>

    <template v-else-if="estado === 'invalido'">
      <AlertaMensagem tipo="erro"
        >Este link é inválido, expirou ou já foi utilizado.</AlertaMensagem
      >
      <p v-if="convite">Peça ao administrador do sistema que reenvie o convite.</p>
      <p v-else>
        <RouterLink :to="{ name: 'recuperar-senha' }">Solicitar um novo link</RouterLink>
      </p>
    </template>

    <AlertaMensagem v-else-if="estado === 'indisponivel'" tipo="erro">{{ erro }}</AlertaMensagem>

    <form v-else novalidate @submit.prevent="enviar">
      <p v-if="convite">Olá, {{ nome }}. Defina a senha para ativar o seu acesso ao Em Conta.</p>
      <AlertaMensagem v-if="erro" tipo="erro">{{ erro }}</AlertaMensagem>
      <CampoSenha
        id="nova-senha"
        v-model="senha"
        rotulo="Nova senha"
        autocomplete="new-password"
        :descricao="`De ${SENHA_MINIMO} a ${SENHA_MAXIMO} caracteres. Pode usar espaços, como numa frase.`"
        :erro="problemaSenha"
      />
      <CampoSenha
        id="confirmacao-senha"
        v-model="confirmacao"
        rotulo="Repita a nova senha"
        autocomplete="new-password"
        :erro="problemaConfirmacao"
      />
      <button type="submit" :disabled="enviando">
        {{ enviando ? 'Salvando…' : convite ? 'Ativar acesso' : 'Salvar nova senha' }}
      </button>
    </form>
  </main>
</template>
