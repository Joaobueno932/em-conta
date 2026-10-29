<script setup lang="ts">
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { temPermissao, useSessao } from '@/features/auth/auth-api'
import { describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'

import {
  chaveUsuarios,
  definirPerfis,
  desativarUsuario,
  editarNome,
  obterUsuario,
  reativarUsuario,
  reenviarConvite,
  rotuloPerfis,
  ROTULO_SITUACAO,
  type Perfil,
  type Usuario,
} from './usuarios-api'

const props = defineProps<{ id: string }>()
const idNumerico = computed(() => Number(props.id))

const http = useHttpClient()
const queryClient = useQueryClient()
const { data: sessao } = useSessao()

const usuario = useQuery({
  queryKey: computed(() => [...chaveUsuarios, idNumerico.value]),
  queryFn: () => obterUsuario(http, idNumerico.value),
})

const pode = (permissao: string) => temPermissao(sessao.value, permissao)
const ehVoceMesmo = computed(() => sessao.value?.id === idNumerico.value)

const nome = ref('')
const administrador = ref(false)
watch(
  () => usuario.data.value,
  (dados) => {
    if (dados) {
      nome.value = dados.nome
      administrador.value = dados.perfis.includes('ADMINISTRADOR')
    }
  },
  { immediate: true },
)

const mensagem = ref<{ tipo: 'sucesso' | 'aviso' | 'erro'; texto: string } | null>(null)

function atualizado(dados: Usuario, texto: string) {
  queryClient.setQueryData([...chaveUsuarios, idNumerico.value], dados)
  void queryClient.invalidateQueries({ queryKey: chaveUsuarios })
  mensagem.value = { tipo: 'sucesso', texto }
}

function falhou(error: unknown) {
  mensagem.value = { tipo: 'erro', texto: describeApiError(error) }
}

const edicao = useMutation({
  mutationFn: () => editarNome(http, idNumerico.value, nome.value),
  onSuccess: (dados) => atualizado(dados, 'Nome atualizado.'),
  onError: falhou,
})

const perfis = useMutation({
  mutationFn: () => {
    const novos: Perfil[] = administrador.value ? ['ADMINISTRADOR'] : []
    return definirPerfis(http, idNumerico.value, novos)
  },
  onSuccess: (dados) => atualizado(dados, 'Perfis atualizados.'),
  onError: falhou,
})

const reenvio = useMutation({
  mutationFn: () => reenviarConvite(http, idNumerico.value),
  onSuccess: (r) => {
    mensagem.value = r.conviteEnviado
      ? { tipo: 'sucesso', texto: 'Convite reenviado. O link anterior deixou de valer.' }
      : { tipo: 'aviso', texto: 'Não foi possível enviar o e-mail. Tente novamente mais tarde.' }
  },
  onError: falhou,
})

const situacao = useMutation({
  mutationFn: (acao: 'desativar' | 'reativar') =>
    acao === 'desativar'
      ? desativarUsuario(http, idNumerico.value)
      : reativarUsuario(http, idNumerico.value),
  onSuccess: (dados) =>
    atualizado(
      dados,
      dados.situacao === 'DESATIVADO'
        ? 'Usuário desativado. As sessões dele foram encerradas.'
        : dados.situacao === 'CONVIDADO'
          ? 'Usuário reativado como convite pendente: reenvie o convite para ele definir a senha.'
          : 'Usuário reativado.',
    ),
  onError: falhou,
})

function desativar() {
  if (window.confirm('Desativar este usuário? As sessões abertas dele serão encerradas.')) {
    situacao.mutate('desativar')
  }
}
</script>

<template>
  <main class="pagina">
    <p><RouterLink :to="{ name: 'usuarios' }">← Usuários</RouterLink></p>
    <p v-if="usuario.isPending.value" role="status">Carregando…</p>
    <AlertaMensagem v-else-if="usuario.isError.value" tipo="erro">
      {{ describeApiError(usuario.error.value) }}
    </AlertaMensagem>

    <template v-else-if="usuario.data.value">
      <h1>{{ usuario.data.value.nome }}</h1>
      <AlertaMensagem v-if="mensagem" :tipo="mensagem.tipo">{{ mensagem.texto }}</AlertaMensagem>
      <dl>
        <dt>E-mail</dt>
        <dd>{{ usuario.data.value.email }}</dd>
        <dt>Situação</dt>
        <dd data-testid="situacao">{{ ROTULO_SITUACAO[usuario.data.value.situacao] }}</dd>
        <dt>Perfis</dt>
        <dd>{{ rotuloPerfis(usuario.data.value.perfis) }}</dd>
      </dl>

      <form v-if="pode('usuarios:editar')" novalidate @submit.prevent="edicao.mutate()">
        <div class="campo">
          <label for="usuario-nome">Nome</label>
          <input id="usuario-nome" v-model="nome" type="text" autocomplete="off" required />
        </div>
        <button type="submit" :disabled="edicao.isPending.value || !nome">Salvar nome</button>
      </form>

      <form v-if="pode('usuarios:atribuir-perfis')" novalidate @submit.prevent="perfis.mutate()">
        <fieldset>
          <legend>Perfis</legend>
          <label><input v-model="administrador" type="checkbox" /> Administrador</label>
        </fieldset>
        <button type="submit" :disabled="perfis.isPending.value">Salvar perfis</button>
      </form>

      <section class="acoes" aria-label="Ações">
        <button
          v-if="pode('usuarios:convidar') && usuario.data.value.situacao === 'CONVIDADO'"
          type="button"
          class="secundario"
          :disabled="reenvio.isPending.value"
          @click="reenvio.mutate()"
        >
          Reenviar convite
        </button>
        <button
          v-if="
            pode('usuarios:alterar-situacao') &&
            usuario.data.value.situacao !== 'DESATIVADO' &&
            !ehVoceMesmo
          "
          type="button"
          class="perigo"
          :disabled="situacao.isPending.value"
          @click="desativar"
        >
          Desativar
        </button>
        <button
          v-if="pode('usuarios:alterar-situacao') && usuario.data.value.situacao === 'DESATIVADO'"
          type="button"
          :disabled="situacao.isPending.value"
          @click="situacao.mutate('reativar')"
        >
          Reativar
        </button>
      </section>
    </template>
  </main>
</template>
