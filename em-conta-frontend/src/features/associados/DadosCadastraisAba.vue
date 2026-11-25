<script setup lang="ts">
import { useMutation, useQueryClient } from '@tanstack/vue-query'
import { computed, ref } from 'vue'

import { useHttpClient } from '@/app/injection'
import { temPermissao, useSessao } from '@/features/auth/auth-api'
import { codigoDoErro, describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'
import IconeTraco from '@/shared/components/IconeTraco.vue'

import {
  chaves,
  formatarDataHora,
  formatarTelefone,
  removerContato,
  ROTULO_TIPO,
  type Associado,
  type Contato,
} from './associados-api'
import ContatoEditor from './ContatoEditor.vue'

const props = defineProps<{ associado: Associado }>()

const http = useHttpClient()
const queryClient = useQueryClient()
const { data: sessao } = useSessao()
const podeEditar = computed(() => temPermissao(sessao.value, 'associados:editar'))

/** Contato em edição (`'novo'` para inclusão). */
const editando = ref<number | 'novo' | null>(null)

const remocao = useMutation({
  mutationFn: (contato: Contato) =>
    removerContato(http, props.associado.id, contato.id, contato.versao),
  onSuccess: () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: chaves.ficha(props.associado.id) }),
      queryClient.invalidateQueries({ queryKey: chaves.historico(props.associado.id) }),
    ]),
  onError: (erro) => {
    if (codigoDoErro(erro) === 'CONFLITO_CONCORRENTE') {
      void queryClient.invalidateQueries({ queryKey: chaves.ficha(props.associado.id) })
    }
  },
})

function remover(contato: Contato) {
  if (window.confirm(`Remover o contato ${contato.nome} da ficha? O histórico é mantido.`)) {
    remocao.mutate(contato)
  }
}

const erroRemocao = computed(() => {
  if (!remocao.isError.value) return ''
  return codigoDoErro(remocao.error.value) === 'CONFLITO_CONCORRENTE'
    ? 'O contato foi alterado por outra pessoa. A ficha foi recarregada; confira antes de remover.'
    : describeApiError(remocao.error.value)
})
</script>

<template>
  <p class="introducao">Dados do cadastro. Campos com cadeado são preenchidos pelo sistema.</p>
  <div class="cartoes">
    <section class="painel" aria-labelledby="titulo-identificacao">
      <h2 id="titulo-identificacao" class="painel__titulo">Identificação</h2>
      <dl class="ficha">
        <div class="ficha__linha">
          <dt>Tipo de pessoa</dt>
          <dd>{{ ROTULO_TIPO[associado.tipo] }} ({{ associado.tipo }})</dd>
        </div>
        <div class="ficha__linha">
          <dt>{{ associado.tipo === 'PF' ? 'Nome completo' : 'Razão social' }}</dt>
          <dd>{{ associado.nome }}</dd>
        </div>
        <div v-if="associado.tipo === 'PJ'" class="ficha__linha">
          <dt>Nome fantasia</dt>
          <dd>{{ associado.nomeFantasia || '—' }}</dd>
        </div>
        <div class="ficha__linha">
          <dt>{{ associado.tipo === 'PF' ? 'CPF' : 'CNPJ' }}</dt>
          <dd>{{ associado.documento }}</dd>
        </div>
      </dl>
    </section>

    <section class="painel" aria-labelledby="titulo-registro">
      <h2 id="titulo-registro" class="painel__titulo">Registro</h2>
      <dl class="ficha">
        <div class="ficha__linha">
          <dt>Cadastrado por</dt>
          <dd class="sistema">
            <IconeTraco nome="cadeado" :tamanho="13" />
            {{ associado.criadoPor.nome }} em {{ formatarDataHora(associado.criadoEm) }}
          </dd>
        </div>
        <div class="ficha__linha">
          <dt>Última alteração</dt>
          <dd class="sistema">
            <IconeTraco nome="cadeado" :tamanho="13" />
            {{ associado.atualizadoPor.nome }} em {{ formatarDataHora(associado.atualizadoEm) }}
          </dd>
        </div>
      </dl>
    </section>

    <section class="painel contatos-painel" aria-labelledby="titulo-contatos">
      <div class="titulo-contatos">
        <h2 id="titulo-contatos" class="painel__titulo">Contatos</h2>
        <button
          v-if="podeEditar && editando === null"
          type="button"
          class="botao-contorno"
          @click="editando = 'novo'"
        >
          <IconeTraco nome="mais-circulo" :tamanho="16" />
          Adicionar contato
        </button>
      </div>
      <AlertaMensagem v-if="erroRemocao" tipo="erro">{{ erroRemocao }}</AlertaMensagem>
      <ContatoEditor
        v-if="editando === 'novo'"
        :associado-id="associado.id"
        @concluido="editando = null"
        @cancelado="editando = null"
      />
      <p v-if="associado.contatos.length === 0 && editando !== 'novo'" class="ajuda">
        Nenhum contato cadastrado.
      </p>
      <ul class="contatos">
        <li v-for="contato in associado.contatos" :key="contato.id">
          <ContatoEditor
            v-if="editando === contato.id"
            :associado-id="associado.id"
            :contato="contato"
            @concluido="editando = null"
            @cancelado="editando = null"
          />
          <template v-else>
            <div>
              <strong>{{ contato.nome }}</strong>
              <span class="ajuda">
                {{ formatarTelefone(contato.telefone) }} · {{ contato.email || '—' }}
              </span>
            </div>
            <div v-if="podeEditar && editando === null" class="acoes">
              <button type="button" class="link" @click="editando = contato.id">
                Editar<span class="visualmente-oculto"> contato {{ contato.nome }}</span>
              </button>
              <button
                type="button"
                class="link"
                :disabled="remocao.isPending.value"
                @click="remover(contato)"
              >
                Remover<span class="visualmente-oculto"> contato {{ contato.nome }}</span>
              </button>
            </div>
          </template>
        </li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
.introducao {
  margin: 0 0 16px;
  color: var(--cor-texto-2);
  font-size: 13px;
}

/* Prancha "Dados cadastrais": duas colunas de cartões com 18 px entre eles. */
.cartoes {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
  align-items: start;
}

.cartoes .painel {
  padding: 22px 20px 12px;
}

.contatos-painel {
  grid-column: 1 / -1;
}

.sistema {
  display: flex;
  align-items: center;
  gap: 8px;
}

.sistema .icone {
  color: var(--cor-texto-3);
}

.titulo-contatos {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
}

.contatos {
  list-style: none;
  padding: 0;
  margin: 0;
}

.contatos > li {
  display: flex;
  justify-content: space-between;
  gap: 1rem;
  flex-wrap: wrap;
  padding: 0.75rem 0;
  border-bottom: 1px solid var(--cor-divisoria);
  font-size: 14px;
}

.contatos > li:last-child {
  border-bottom: none;
}

.contatos > li > div:first-child .ajuda {
  display: block;
}

.contatos > li:has(form) {
  display: block;
}

@media (max-width: 60rem) {
  .cartoes {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
