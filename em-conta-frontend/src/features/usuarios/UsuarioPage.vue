<script setup lang="ts">
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { temPermissao, useSessao } from '@/features/auth/auth-api'
import { describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'
import DialogoConfirmacao from '@/shared/components/DialogoConfirmacao.vue'
import IconeTraco from '@/shared/components/IconeTraco.vue'
import SeloSituacao from '@/shared/components/SeloSituacao.vue'

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

const confirmandoDesativacao = ref(false)
function desativar() {
  confirmandoDesativacao.value = false
  situacao.mutate('desativar')
}

const TOM_SITUACAO = { ATIVO: 'sucesso', CONVIDADO: 'aviso', DESATIVADO: 'neutro' } as const
</script>

<template>
  <section class="painel">
    <nav aria-label="Trilha" class="trilha">
      <RouterLink :to="{ name: 'usuarios' }">
        <IconeTraco nome="seta-esquerda" :tamanho="16" />
        Usuários e permissões
      </RouterLink>
    </nav>
    <p v-if="usuario.isPending.value" role="status">Carregando…</p>
    <AlertaMensagem v-else-if="usuario.isError.value" tipo="erro">
      {{ describeApiError(usuario.error.value) }}
    </AlertaMensagem>

    <template v-else-if="usuario.data.value">
      <h2 class="painel__titulo titulo-usuario">{{ usuario.data.value.nome }}</h2>
      <AlertaMensagem v-if="mensagem" :tipo="mensagem.tipo">{{ mensagem.texto }}</AlertaMensagem>
      <dl class="linhas">
        <div class="linha">
          <dt>E-mail</dt>
          <dd>{{ usuario.data.value.email }}</dd>
        </div>
        <div class="linha">
          <dt>Situação</dt>
          <dd>
            <SeloSituacao :tom="TOM_SITUACAO[usuario.data.value.situacao]">
              <span data-testid="situacao">{{ ROTULO_SITUACAO[usuario.data.value.situacao] }}</span>
            </SeloSituacao>
          </dd>
        </div>
        <div class="linha">
          <dt>Perfis</dt>
          <dd>{{ rotuloPerfis(usuario.data.value.perfis) }}</dd>
        </div>
      </dl>

      <form
        v-if="pode('usuarios:editar')"
        class="bloco"
        novalidate
        @submit.prevent="edicao.mutate()"
      >
        <div class="campo">
          <label for="usuario-nome">Nome</label>
          <input id="usuario-nome" v-model="nome" type="text" autocomplete="off" required />
        </div>
        <button type="submit" class="botao-contorno" :disabled="edicao.isPending.value || !nome">
          Salvar nome
        </button>
      </form>

      <form
        v-if="pode('usuarios:atribuir-perfis')"
        class="bloco"
        novalidate
        @submit.prevent="perfis.mutate()"
      >
        <fieldset>
          <legend>Perfis</legend>
          <label><input v-model="administrador" type="checkbox" /> Administrador</label>
        </fieldset>
        <button type="submit" class="botao-contorno" :disabled="perfis.isPending.value">
          Salvar perfis
        </button>
      </form>

      <section class="acoes bloco" aria-label="Ações">
        <button
          v-if="pode('usuarios:convidar') && usuario.data.value.situacao === 'CONVIDADO'"
          type="button"
          class="botao-contorno"
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
          @click="confirmandoDesativacao = true"
        >
          Desativar
        </button>
        <button
          v-if="pode('usuarios:alterar-situacao') && usuario.data.value.situacao === 'DESATIVADO'"
          type="button"
          class="botao-primario"
          :disabled="situacao.isPending.value"
          @click="situacao.mutate('reativar')"
        >
          Reativar
        </button>
      </section>
    </template>
  </section>

  <DialogoConfirmacao
    :aberto="confirmandoDesativacao"
    titulo="Desativar este usuário?"
    confirmar="Desativar"
    @confirmar="desativar"
    @cancelar="confirmandoDesativacao = false"
  >
    <p>As sessões abertas dele serão encerradas. Histórico e autoria são preservados.</p>
  </DialogoConfirmacao>
</template>

<style scoped>
.painel {
  padding: 18px 22px 22px;
}

.trilha a {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--cor-texto-2);
  font-size: 13px;
  text-decoration: none;
}

.trilha a:hover {
  color: var(--cor-destaque-forte);
}

.titulo-usuario {
  margin-top: 10px;
}

.linhas {
  margin: 12px 0 0;
}

.linha {
  display: grid;
  grid-template-columns: 238px minmax(0, 1fr);
  align-items: center;
  min-height: 44px;
  border-bottom: 1px solid var(--cor-divisoria);
}

.linha dt {
  color: var(--cor-texto-2);
  font-size: 13px;
  font-weight: 400;
}

.linha dd {
  margin: 0;
  font-size: 14px;
}

.bloco {
  margin-top: 20px;
}

.bloco .campo {
  max-width: 482px;
}

fieldset {
  margin: 0 0 12px;
  padding: 0;
  border: none;
}

@media (max-width: 40rem) {
  .linha {
    grid-template-columns: minmax(0, 1fr);
    padding: 6px 0;
  }
}
</style>
