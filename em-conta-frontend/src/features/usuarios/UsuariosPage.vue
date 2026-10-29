<script setup lang="ts">
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { temPermissao, useSessao } from '@/features/auth/auth-api'
import { describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'

import {
  chaveUsuarios,
  convidarUsuario,
  listarUsuarios,
  rotuloPerfis,
  ROTULO_SITUACAO,
  type Perfil,
} from './usuarios-api'

const http = useHttpClient()
const queryClient = useQueryClient()
const { data: sessao } = useSessao()

const pagina = ref(0)
const lista = useQuery({
  queryKey: computed(() => [...chaveUsuarios, 'pagina', pagina.value]),
  queryFn: () => listarUsuarios(http, pagina.value),
  placeholderData: keepPreviousData,
})

const podeConvidar = computed(() => temPermissao(sessao.value, 'usuarios:convidar'))
const podeAtribuirPerfis = computed(() => temPermissao(sessao.value, 'usuarios:atribuir-perfis'))

const nome = ref('')
const email = ref('')
const administrador = ref(false)
const resultado = ref<{ tipo: 'sucesso' | 'aviso'; texto: string } | null>(null)

const convite = useMutation({
  mutationFn: () => {
    const perfis: Perfil[] =
      podeAtribuirPerfis.value && administrador.value ? ['ADMINISTRADOR'] : []
    return convidarUsuario(http, nome.value, email.value, perfis)
  },
  onSuccess: (criado) => {
    resultado.value = criado.conviteEnviado
      ? { tipo: 'sucesso', texto: `Convite enviado para ${criado.usuario.email}.` }
      : {
          tipo: 'aviso',
          texto: `Usuário criado, mas o e-mail para ${criado.usuario.email} não pôde ser enviado. Abra o usuário e use “Reenviar convite”.`,
        }
    nome.value = ''
    email.value = ''
    administrador.value = false
    void queryClient.invalidateQueries({ queryKey: chaveUsuarios })
  },
})
</script>

<template>
  <main class="pagina">
    <h1>Usuários</h1>

    <section v-if="podeConvidar" aria-labelledby="titulo-convite">
      <h2 id="titulo-convite">Convidar usuário</h2>
      <AlertaMensagem v-if="resultado" :tipo="resultado.tipo">{{ resultado.texto }}</AlertaMensagem>
      <AlertaMensagem v-if="convite.isError.value" tipo="erro">
        {{ describeApiError(convite.error.value) }}
      </AlertaMensagem>
      <form
        novalidate
        @submit.prevent="
          () => {
            resultado = null
            convite.mutate()
          }
        "
      >
        <div class="campo">
          <label for="convite-nome">Nome</label>
          <input id="convite-nome" v-model="nome" type="text" autocomplete="off" required />
        </div>
        <div class="campo">
          <label for="convite-email">E-mail</label>
          <input id="convite-email" v-model="email" type="email" autocomplete="off" required />
        </div>
        <div v-if="podeAtribuirPerfis" class="campo">
          <label><input v-model="administrador" type="checkbox" /> Perfil Administrador</label>
        </div>
        <button type="submit" :disabled="convite.isPending.value || !nome || !email">
          {{ convite.isPending.value ? 'Enviando…' : 'Enviar convite' }}
        </button>
      </form>
    </section>

    <section aria-labelledby="titulo-lista">
      <h2 id="titulo-lista">Cadastrados</h2>
      <p v-if="lista.isPending.value" role="status">Carregando usuários…</p>
      <AlertaMensagem v-else-if="lista.isError.value" tipo="erro">
        {{ describeApiError(lista.error.value) }}
      </AlertaMensagem>
      <template v-else-if="lista.data.value">
        <table>
          <caption class="ajuda">
            {{
              lista.data.value.totalItens
            }}
            usuário(s)
          </caption>
          <thead>
            <tr>
              <th scope="col">Nome</th>
              <th scope="col">E-mail</th>
              <th scope="col">Situação</th>
              <th scope="col">Perfis</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="usuario in lista.data.value.itens" :key="usuario.id">
              <td>
                <RouterLink :to="{ name: 'usuario', params: { id: usuario.id } }">
                  {{ usuario.nome }}
                </RouterLink>
              </td>
              <td>{{ usuario.email }}</td>
              <td>{{ ROTULO_SITUACAO[usuario.situacao] }}</td>
              <td>{{ rotuloPerfis(usuario.perfis) }}</td>
            </tr>
          </tbody>
        </table>
        <nav class="acoes" aria-label="Paginação">
          <button
            type="button"
            class="secundario"
            :disabled="pagina === 0"
            @click="pagina = pagina - 1"
          >
            Anterior
          </button>
          <span>Página {{ pagina + 1 }} de {{ Math.max(lista.data.value.totalPaginas, 1) }}</span>
          <button
            type="button"
            class="secundario"
            :disabled="pagina + 1 >= lista.data.value.totalPaginas"
            @click="pagina = pagina + 1"
          >
            Próxima
          </button>
        </nav>
      </template>
    </section>
  </main>
</template>
