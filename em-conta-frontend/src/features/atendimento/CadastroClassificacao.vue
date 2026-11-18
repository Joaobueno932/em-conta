<script setup lang="ts">
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, nextTick, ref } from 'vue'

import { useHttpClient } from '@/app/injection'
import { temPermissao, useSessao } from '@/features/auth/auth-api'
import { camposDoErro } from '@/shared/api/campos'
import { codigoDoErro, describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'

import { chavesAtendimento, formatarDataHora } from './atendimento-api'
import {
  alterarSituacaoItem,
  chavesClassificacoes,
  criarItem,
  listarCadastro,
  renomearItem,
  ROTULO_CADASTRO,
  TAMANHO_MAXIMO_NOME,
  type Cadastro,
  type ItemCadastro,
  type SituacaoCadastro,
} from './classificacoes-api'

/**
 * Um cadastro (setores ou etiquetas): lista por situação, inclusão, renomeação e ativação. Ações
 * aparecem só para quem tem a permissão correspondente; o servidor confere de novo. Desativar preserva o
 * item nos atendimentos que já o usam; renomear vale para todos eles.
 */
const props = defineProps<{ cadastro: Cadastro }>()

const http = useHttpClient()
const queryClient = useQueryClient()
const { data: usuario } = useSessao()
const pode = (acao: string) => temPermissao(usuario.value, `${props.cadastro}:${acao}`)
const rotulo = computed(() => ROTULO_CADASTRO[props.cadastro])
const maximo = computed(() => TAMANHO_MAXIMO_NOME[props.cadastro])

const situacao = ref<SituacaoCadastro>('ATIVOS')
const filtros: { valor: SituacaoCadastro; rotulo: string }[] = [
  { valor: 'ATIVOS', rotulo: 'Ativos' },
  { valor: 'INATIVOS', rotulo: 'Inativos' },
  { valor: 'TODOS', rotulo: 'Todos' },
]

const lista = useQuery({
  queryKey: computed(() => chavesClassificacoes.lista(props.cadastro, situacao.value)),
  queryFn: () => listarCadastro(http, props.cadastro, situacao.value),
})
// Renomear ou desativar muda o que a fila, o painel e o histórico mostram.
const atualizar = () =>
  Promise.all([
    queryClient.invalidateQueries({ queryKey: chavesClassificacoes.todas }),
    queryClient.invalidateQueries({ queryKey: chavesAtendimento.todos }),
  ])

// ---------------------------------------------------------------- formulário (novo ou renomear)
const editando = ref<ItemCadastro | null>(null)
const aberto = ref(false)
const nome = ref('')
const campoNome = ref<HTMLInputElement | null>(null)

async function abrir(item: ItemCadastro | null) {
  editando.value = item
  nome.value = item?.nome ?? ''
  salvar.reset()
  aberto.value = true
  await nextTick()
  campoNome.value?.focus()
}
function fechar() {
  aberto.value = false
  editando.value = null
  salvar.reset()
}

const salvar = useMutation({
  mutationFn: () =>
    editando.value
      ? renomearItem(http, props.cadastro, editando.value.id, editando.value.versao, nome.value)
      : criarItem(http, props.cadastro, nome.value),
  onSuccess: () => fechar(),
  onSettled: atualizar,
})
const erros = computed(() => camposDoErro(salvar.error.value))
const conflito = computed(() => codigoDoErro(salvar.error.value) === 'CONFLITO_CONCORRENTE')

// ---------------------------------------------------------------- situação
const erroSituacao = ref<unknown>(null)
const situacaoMut = useMutation({
  mutationFn: (item: ItemCadastro) =>
    alterarSituacaoItem(http, props.cadastro, item.id, item.versao, !item.ativo),
  onMutate: () => (erroSituacao.value = null),
  onError: (e) => (erroSituacao.value = e),
  onSettled: atualizar,
})
</script>

<template>
  <section class="cartao" :aria-labelledby="`titulo-${cadastro}`">
    <header class="topo">
      <h2 :id="`titulo-${cadastro}`">{{ rotulo.plural }}</h2>
      <button
        v-if="pode('cadastrar') && !aberto"
        type="button"
        class="secundario"
        @click="abrir(null)"
      >
        {{ rotulo.novo }}
      </button>
    </header>

    <form
      v-if="aberto"
      class="formulario"
      novalidate
      :aria-label="editando ? `Renomear ${rotulo.singular}` : rotulo.novo"
      @submit.prevent="salvar.mutate()"
    >
      <AlertaMensagem v-if="conflito" tipo="aviso">
        Este item foi alterado por outra pessoa enquanto você editava. A lista foi recarregada;
        abra-o de novo para ver a versão atual.
      </AlertaMensagem>
      <AlertaMensagem v-else-if="salvar.isError.value && !erros.nome" tipo="erro">
        {{ describeApiError(salvar.error.value) }}
      </AlertaMensagem>
      <div class="campo">
        <label :for="`nome-${cadastro}`"
          >Nome<span class="obrigatorio" aria-hidden="true"> *</span></label
        >
        <input
          :id="`nome-${cadastro}`"
          ref="campoNome"
          v-model="nome"
          type="text"
          required
          :maxlength="maximo"
          :aria-invalid="!!erros.nome"
          :aria-describedby="`nome-${cadastro}-ajuda${erros.nome ? ` nome-${cadastro}-erro` : ''}`"
        />
        <p :id="`nome-${cadastro}-ajuda`" class="ajuda">
          De 2 a {{ maximo }} caracteres; não pode repetir o nome de outro item, ativo ou inativo.
          <template v-if="editando"
            >O novo nome vale para os atendimentos que já usam este item.</template
          >
        </p>
        <p v-if="erros.nome" :id="`nome-${cadastro}-erro`" class="ajuda ajuda--erro">
          {{ erros.nome }}
        </p>
      </div>
      <div class="acoes">
        <button type="submit" :disabled="salvar.isPending.value">
          {{ salvar.isPending.value ? 'Salvando…' : 'Salvar' }}
        </button>
        <button type="button" class="secundario" @click="fechar">Cancelar</button>
      </div>
    </form>

    <div
      class="filtros"
      role="group"
      :aria-label="`Filtrar ${rotulo.plural.toLowerCase()} por situação`"
    >
      <button
        v-for="f in filtros"
        :key="f.valor"
        type="button"
        class="chip"
        :aria-pressed="situacao === f.valor"
        @click="situacao = f.valor"
      >
        {{ f.rotulo }}
      </button>
    </div>

    <AlertaMensagem v-if="erroSituacao" tipo="erro">
      {{
        codigoDoErro(erroSituacao) === 'CONFLITO_CONCORRENTE'
          ? 'Este item foi alterado por outra pessoa. A lista foi recarregada; tente de novo.'
          : describeApiError(erroSituacao)
      }}
    </AlertaMensagem>
    <p v-if="lista.isPending.value" role="status">Carregando…</p>
    <template v-else-if="lista.isError.value">
      <AlertaMensagem tipo="erro">{{ describeApiError(lista.error.value) }}</AlertaMensagem>
      <button type="button" class="secundario" @click="lista.refetch()">Tentar de novo</button>
    </template>
    <template v-else-if="lista.data.value">
      <p v-if="lista.data.value.itens.length === 0" class="vazio" role="status">
        Nenhum item nesta situação.
      </p>
      <ul v-else class="itens" :aria-busy="lista.isFetching.value">
        <li v-for="i in lista.data.value.itens" :key="i.id" class="item">
          <div class="cabeca">
            <strong>{{ i.nome }}</strong>
            <span class="etiqueta" :class="{ inativa: !i.ativo }">{{
              i.ativo ? 'Ativo' : 'Inativo'
            }}</span>
          </div>
          <p class="ajuda">
            Alterado por {{ i.atualizadoPor.nome }} em {{ formatarDataHora(i.atualizadoEm) }}
          </p>
          <div class="acoes">
            <button
              v-if="pode('editar')"
              type="button"
              class="secundario"
              :aria-label="`Renomear ${i.nome}`"
              @click="abrir(i)"
            >
              Renomear
            </button>
            <button
              v-if="pode('alterar-situacao')"
              type="button"
              class="secundario"
              :aria-label="`${i.ativo ? 'Desativar' : 'Ativar'} ${i.nome}`"
              :disabled="situacaoMut.isPending.value"
              @click="situacaoMut.mutate(i)"
            >
              {{ i.ativo ? 'Desativar' : 'Ativar' }}
            </button>
          </div>
        </li>
      </ul>
    </template>
  </section>
</template>

<style scoped>
.topo {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 0.5rem;
}

.topo h2 {
  margin: 0;
}

.formulario {
  margin-top: 0.75rem;
}

.formulario input {
  width: 100%;
  box-sizing: border-box;
}

.filtros {
  display: flex;
  flex-wrap: wrap;
  gap: 0.35rem;
  margin: 0.75rem 0;
}

.chip {
  border-radius: 999px;
  border-color: var(--cor-borda);
  background: var(--cor-superficie);
  color: var(--cor-texto);
  padding: 0.2rem 0.75rem;
  font-size: 0.85rem;
}

.chip[aria-pressed='true'] {
  background: var(--cor-destaque-suave);
  border-color: var(--cor-destaque-borda);
  color: var(--cor-destaque-forte);
}

.itens {
  list-style: none;
  margin: 0;
  padding: 0;
}

.item {
  padding: 0.75rem 0;
  border-bottom: 1px solid var(--cor-borda);
}

.cabeca {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem;
}

.etiqueta.inativa {
  font-style: italic;
}

.vazio {
  text-align: center;
  color: var(--cor-texto-2);
  padding: 2rem 0;
}
</style>
