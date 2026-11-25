<script setup lang="ts">
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { iniciais } from '@/features/atendimento/atendimento-api'
import { temPermissao, useSessao } from '@/features/auth/auth-api'
import { describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'
import IconeTraco, { type NomeIcone } from '@/shared/components/IconeTraco.vue'
import ModalPainel from '@/shared/components/ModalPainel.vue'
import PaginacaoLista from '@/shared/components/PaginacaoLista.vue'
import SeloSituacao from '@/shared/components/SeloSituacao.vue'
import { avisar } from '@/shared/components/use-avisos'

import {
  chaveUsuarios,
  convidarUsuario,
  listarUsuarios,
  rotuloPerfis,
  ROTULO_PERFIL,
  ROTULO_SITUACAO,
  type Perfil,
  type Usuario,
} from './usuarios-api'

/**
 * Usuários e permissões (prancha "⚙️ Configurações", quadro 8). Busca e filtro do protótipo não
 * aparecem: a API de usuários não filtra. O "…" de cada linha abre o usuário, onde ficam edição do
 * nome, perfis, reenvio de convite, desativação e reativação.
 */
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
const perfisExistentes = Object.values(ROTULO_PERFIL).join(', ')

const SELO: Record<Usuario['situacao'], { tom: 'sucesso' | 'aviso' | 'neutro'; icone: NomeIcone }> =
  {
    ATIVO: { tom: 'sucesso', icone: 'check-circulo' },
    CONVIDADO: { tom: 'aviso', icone: 'relogio' },
    DESATIVADO: { tom: 'neutro', icone: 'proibido' },
  }

// Convite: modal com nome, e-mail e (para quem pode atribuir) o perfil Administrador.
const modalConvite = ref(false)
const nome = ref('')
const email = ref('')
const perfil = ref<Perfil | ''>('')
const resultado = ref<{ tipo: 'sucesso' | 'aviso'; texto: string } | null>(null)

const convite = useMutation({
  mutationFn: () => {
    const perfis: Perfil[] = podeAtribuirPerfis.value && perfil.value ? [perfil.value] : []
    return convidarUsuario(http, nome.value, email.value, perfis)
  },
  onSuccess: (criado) => {
    modalConvite.value = false
    if (criado.conviteEnviado) {
      resultado.value = null
      avisar('Convite enviado', `Convite enviado para ${criado.usuario.email}.`)
    } else {
      resultado.value = {
        tipo: 'aviso',
        texto: `Usuário criado, mas o e-mail para ${criado.usuario.email} não pôde ser enviado. Abra o usuário e use “Reenviar convite”.`,
      }
    }
    void queryClient.invalidateQueries({ queryKey: chaveUsuarios })
  },
})

function abrirConvite() {
  nome.value = ''
  email.value = ''
  perfil.value = ''
  convite.reset()
  modalConvite.value = true
}
</script>

<template>
  <section class="painel" aria-labelledby="titulo-usuarios">
    <div class="topo-painel">
      <div>
        <h2 id="titulo-usuarios" class="painel__titulo">Usuários e permissões</h2>
        <p class="painel__subtitulo">Perfis existentes: {{ perfisExistentes }}</p>
      </div>
      <button v-if="podeConvidar" type="button" class="botao-primario" @click="abrirConvite">
        <IconeTraco nome="mais-circulo" :tamanho="18" />
        Convidar usuário
      </button>
    </div>

    <AlertaMensagem v-if="resultado" :tipo="resultado.tipo">{{ resultado.texto }}</AlertaMensagem>

    <p v-if="lista.isPending.value" role="status" class="estado">Carregando usuários…</p>
    <div v-else-if="lista.isError.value">
      <AlertaMensagem tipo="erro">{{ describeApiError(lista.error.value) }}</AlertaMensagem>
      <button type="button" class="botao-contorno" @click="lista.refetch()">
        Tentar novamente
      </button>
    </div>
    <template v-else-if="lista.data.value">
      <div class="tabela-rolavel">
        <table class="tabela-dados">
          <caption class="visualmente-oculto">
            {{
              lista.data.value.totalItens
            }}
            usuário(s)
          </caption>
          <thead>
            <tr>
              <th scope="col">Nome</th>
              <th scope="col">E-mail</th>
              <th scope="col">Perfil</th>
              <th scope="col">Situação de acesso</th>
              <th scope="col"><span class="visualmente-oculto">Ações</span></th>
            </tr>
          </thead>
          <tbody :aria-busy="lista.isFetching.value">
            <tr v-for="usuario in lista.data.value.itens" :key="usuario.id">
              <td>
                <span class="pessoa">
                  <span class="avatar-iniciais" aria-hidden="true">{{
                    iniciais(usuario.nome)
                  }}</span>
                  <RouterLink :to="{ name: 'usuario', params: { id: usuario.id } }">
                    {{ usuario.nome }}
                  </RouterLink>
                </span>
              </td>
              <td class="texto-secundario">{{ usuario.email }}</td>
              <td>{{ rotuloPerfis(usuario.perfis) }}</td>
              <td>
                <SeloSituacao
                  :tom="SELO[usuario.situacao].tom"
                  :icone="SELO[usuario.situacao].icone"
                >
                  {{ ROTULO_SITUACAO[usuario.situacao] }}
                </SeloSituacao>
              </td>
              <td class="coluna-acoes">
                <RouterLink
                  :to="{ name: 'usuario', params: { id: usuario.id } }"
                  class="botao-icone sem-borda"
                  :aria-label="`Abrir ${usuario.nome}`"
                  :title="`Abrir ${usuario.nome}`"
                >
                  <IconeTraco nome="reticencias" :tamanho="20" :espessura="2.6" />
                </RouterLink>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <PaginacaoLista
        v-if="lista.data.value.totalPaginas > 1"
        :pagina="pagina"
        :total-paginas="lista.data.value.totalPaginas"
        :total-itens="lista.data.value.totalItens"
        :itens-na-pagina="lista.data.value.itens.length"
        :tamanho="20"
        rotulo="usuários"
        @ir="pagina = $event"
      />
    </template>
  </section>
  <AlertaMensagem tipo="info" class="nota">
    Desativar preserva histórico e autoria; não há exclusão de usuários.
  </AlertaMensagem>

  <ModalPainel :aberto="modalConvite" titulo="Convidar usuário" @fechar="modalConvite = false">
    <form id="form-convite" novalidate @submit.prevent="convite.mutate()">
      <AlertaMensagem v-if="convite.isError.value" tipo="erro">
        {{ describeApiError(convite.error.value) }}
      </AlertaMensagem>
      <div class="campo">
        <label for="convite-nome">Nome <span class="obrigatorio" aria-hidden="true">*</span></label>
        <input
          id="convite-nome"
          v-model="nome"
          type="text"
          autocomplete="off"
          required
          aria-required="true"
        />
      </div>
      <div class="campo">
        <label for="convite-email"
          >E-mail <span class="obrigatorio" aria-hidden="true">*</span></label
        >
        <input
          id="convite-email"
          v-model="email"
          type="email"
          autocomplete="off"
          required
          aria-required="true"
        />
      </div>
      <div v-if="podeAtribuirPerfis" class="campo">
        <label for="convite-perfil">Perfil de acesso</label>
        <select id="convite-perfil" v-model="perfil">
          <option value="">Sem perfil</option>
          <option v-for="(rotulo, valor) in ROTULO_PERFIL" :key="valor" :value="valor">
            {{ rotulo }}
          </option>
        </select>
      </div>
      <AlertaMensagem tipo="info">
        O convite fica pendente até o primeiro acesso: a pessoa recebe um e-mail para definir a
        senha.
      </AlertaMensagem>
    </form>
    <template #rodape>
      <button type="button" class="botao-contorno" @click="modalConvite = false">Cancelar</button>
      <button
        type="submit"
        form="form-convite"
        class="botao-primario"
        :disabled="convite.isPending.value || !nome || !email"
      >
        <IconeTraco nome="enviar" :tamanho="18" />
        {{ convite.isPending.value ? 'Enviando…' : 'Enviar convite' }}
      </button>
    </template>
  </ModalPainel>
</template>

<style scoped>
.painel {
  padding: 18px 22px 20px;
}

.topo-painel {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 14px;
}

.estado {
  color: var(--cor-texto-2);
}

.tabela-dados tbody td {
  height: 59px;
}

.pessoa {
  display: inline-flex;
  align-items: center;
  gap: 12px;
}

.avatar-iniciais {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 999px;
  background: var(--cor-destaque-suave);
  color: var(--cor-destaque-forte);
  font-size: 12px;
  font-weight: 500;
}

.texto-secundario {
  color: var(--cor-texto-2);
}

.coluna-acoes {
  width: 48px;
  text-align: right;
}

.coluna-acoes .botao-icone {
  color: var(--cor-texto-2);
}

.nota {
  margin: 16px 0 0;
}

.campo input[type='text'],
.campo input[type='email'] {
  height: 42px;
}
</style>
