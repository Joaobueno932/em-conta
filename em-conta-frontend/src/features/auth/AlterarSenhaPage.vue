<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import MinhaContaPage from '@/features/configuracoes/MinhaContaPage.vue'
import { describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'
import CampoSenha from '@/shared/components/CampoSenha.vue'
import IconeTraco from '@/shared/components/IconeTraco.vue'
import ModalPainel from '@/shared/components/ModalPainel.vue'
import { avisar } from '@/shared/components/use-avisos'

import { alterarSenha } from './auth-api'
import { problemaNaSenha, SENHA_MAXIMO, SENHA_MINIMO } from './politica-senha'

/**
 * Alterar senha (prancha "🪟 Configurações… Overlays", quadros 6 a 8): modal sobre Minha conta. A rota
 * /conta/senha continua existindo (menu da conta e links antigos); fechar volta para Minha conta.
 * Os requisitos listados são os da política real (politica-senha.ts, espelho do backend).
 */
const http = useHttpClient()
const router = useRouter()
const atual = ref('')
const nova = ref('')
const confirmacao = ref('')
const enviando = ref(false)
const erro = ref<string | null>(null)
const mostrarProblemas = ref(false)

const problemaNova = computed(() => (mostrarProblemas.value ? problemaNaSenha(nova.value) : null))
const problemaConfirmacao = computed(() =>
  mostrarProblemas.value && confirmacao.value !== nova.value ? 'As senhas não conferem.' : null,
)
const requisitos = computed(() => [
  {
    texto: `De ${SENHA_MINIMO} a ${SENHA_MAXIMO} caracteres (pode usar espaços, como numa frase)`,
    ok: !!nova.value && !problemaNaSenha(nova.value),
  },
  {
    texto: 'Confirmação igual à nova senha',
    ok: !!confirmacao.value && confirmacao.value === nova.value,
  },
])

function fechar() {
  void router.push({ name: 'minha-conta' })
}

async function enviar() {
  mostrarProblemas.value = true
  if (!atual.value || problemaNaSenha(nova.value) || confirmacao.value !== nova.value) return
  enviando.value = true
  erro.value = null
  try {
    await alterarSenha(http, atual.value, nova.value)
    avisar('Senha alterada', 'As sessões abertas em outros dispositivos foram encerradas.')
    fechar()
  } catch (error) {
    erro.value = describeApiError(error)
  } finally {
    enviando.value = false
  }
}
</script>

<template>
  <MinhaContaPage />
  <ModalPainel
    :aberto="true"
    titulo="Alterar senha"
    subtitulo="Esta sessão continuará ativa. As sessões abertas em outros dispositivos serão encerradas."
    :largura="520"
    @fechar="fechar"
  >
    <form id="form-senha" novalidate @submit.prevent="enviar">
      <AlertaMensagem v-if="erro" tipo="erro"
        >{{ erro }} Nenhuma alteração foi feita.</AlertaMensagem
      >
      <CampoSenha
        id="senha-atual"
        v-model="atual"
        rotulo="Senha atual"
        obrigatorio
        autocomplete="current-password"
      />
      <CampoSenha
        id="senha-nova"
        v-model="nova"
        rotulo="Nova senha"
        obrigatorio
        autocomplete="new-password"
        :erro="problemaNova"
      />
      <CampoSenha
        id="senha-confirmacao"
        v-model="confirmacao"
        rotulo="Confirmar nova senha"
        obrigatorio
        autocomplete="new-password"
        :erro="problemaConfirmacao"
      />
      <p class="titulo-requisitos">Requisitos</p>
      <ul class="requisitos">
        <li v-for="r in requisitos" :key="r.texto" :class="{ ok: r.ok }">
          <IconeTraco :nome="r.ok ? 'check-circulo' : 'informacao'" :tamanho="18" />
          <span>{{ r.texto }}</span>
          <span class="situacao">{{ r.ok ? 'Concluído' : 'Pendente' }}</span>
        </li>
      </ul>
    </form>
    <template #rodape>
      <button type="button" class="botao-contorno" @click="fechar">Cancelar</button>
      <button type="submit" form="form-senha" class="botao-primario" :disabled="enviando">
        <IconeTraco nome="chave" :tamanho="18" />
        {{ enviando ? 'Salvando…' : 'Alterar senha' }}
      </button>
    </template>
  </ModalPainel>
</template>

<style scoped>
.titulo-requisitos {
  margin: 4px 0 6px;
  color: var(--cor-texto-2);
  font-size: 12px;
}

.requisitos {
  margin: 0;
  padding: 0;
  list-style: none;
}

.requisitos li {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 36px;
  color: var(--cor-texto-2);
  font-size: 14px;
}

.requisitos li span:first-of-type {
  flex: 1;
}

.requisitos li.ok {
  color: var(--cor-texto);
}

.requisitos li.ok .icone,
.requisitos li.ok .situacao {
  color: var(--cor-destaque-forte);
}

.situacao {
  font-size: 12px;
  font-weight: 500;
}
</style>
