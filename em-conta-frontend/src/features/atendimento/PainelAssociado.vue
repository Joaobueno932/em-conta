<script setup lang="ts">
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { temPermissao, useSessao } from '@/features/auth/auth-api'
import { codigoDoErro, describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'

import {
  buscarAssociados,
  chavesAtendimento,
  obterDetalhe,
  sugeridos,
  vincularAssociado,
  type AssociadoAtendimento,
} from './atendimento-api'
import MidiaConversa from './MidiaConversa.vue'

/**
 * Painel do associado (AT-06). Telefone igual não vincula sozinho: as sugestões só aparecem para o
 * atendente escolher. Trocar ou remover um vínculo exige motivo e fica no histórico.
 */
const props = defineProps<{ id: number }>()
const emit = defineEmits<{ fechar: []; abrirMensagem: [mensagemId: number] }>()

const http = useHttpClient()
const queryClient = useQueryClient()
const { data: usuario } = useSessao()
const podeVincular = computed(() => temPermissao(usuario.value, 'atendimento:vincular-associado'))

const detalhe = useQuery({
  queryKey: computed(() => chavesAtendimento.detalhe(props.id)),
  queryFn: () => obterDetalhe(http, props.id),
})
const atual = computed(() => detalhe.data.value?.resumo.associado)

const editando = ref(false)
const mostrarEdicao = computed(() => podeVincular.value && (editando.value || !atual.value))

const listaSugeridos = useQuery({
  queryKey: computed(() => chavesAtendimento.sugeridos(props.id)),
  queryFn: () => sugeridos(http, props.id),
  enabled: mostrarEdicao,
})

const termo = ref('')
const busca = ref('')
const resultados = useQuery({
  queryKey: computed(() => chavesAtendimento.buscaAssociados(busca.value)),
  queryFn: () => buscarAssociados(http, busca.value),
  enabled: computed(() => busca.value.length >= 2),
})

const escolhido = ref<AssociadoAtendimento | null>(null)
const motivo = ref('')
const exigeMotivo = computed(() => !!atual.value)

const vincular = useMutation({
  mutationFn: (associadoId: number | undefined) =>
    vincularAssociado(http, props.id, {
      versaoConversa: detalhe.data.value!.versaoConversa,
      associadoId,
      motivo: motivo.value.trim() || undefined,
    }),
  onSuccess: () => {
    editando.value = false
    escolhido.value = null
    motivo.value = ''
  },
  onSettled: () => queryClient.invalidateQueries({ queryKey: chavesAtendimento.todos }),
})
const conflito = computed(() => codigoDoErro(vincular.error.value) === 'CONFLITO_CONCORRENTE')

function confirmar(associadoId?: number) {
  if (exigeMotivo.value && !motivo.value.trim()) return
  vincular.mutate(associadoId)
}
</script>

<template>
  <aside class="painel cartao" aria-labelledby="titulo-associado">
    <header class="topo">
      <h2 id="titulo-associado">Associado</h2>
      <button
        type="button"
        class="link"
        aria-label="Fechar painel do associado"
        @click="emit('fechar')"
      >
        ✕
      </button>
    </header>

    <p v-if="detalhe.isPending.value" role="status">Carregando…</p>
    <template v-else-if="detalhe.data.value">
      <div v-if="atual" class="atual">
        <p>
          <strong>{{ atual.nome }}</strong>
          <span class="ajuda">{{ atual.tipo }} · {{ atual.documentoMascarado }}</span>
        </p>
        <RouterLink :to="{ name: 'associado', params: { id: atual.id } }">Abrir ficha</RouterLink>
        <button
          v-if="podeVincular && !editando"
          type="button"
          class="link"
          @click="editando = true"
        >
          Alterar vínculo
        </button>
      </div>
      <p v-else class="ajuda">Nenhum associado vinculado a esta conversa.</p>

      <div v-if="mostrarEdicao" class="edicao">
        <AlertaMensagem v-if="conflito" tipo="aviso">
          O vínculo foi alterado por outra pessoa. Confira o associado atual e tente de novo.
        </AlertaMensagem>
        <AlertaMensagem v-else-if="vincular.isError.value" tipo="erro">
          {{ describeApiError(vincular.error.value) }}
        </AlertaMensagem>

        <template v-if="listaSugeridos.data.value?.length">
          <h3>Sugestões pelo telefone</h3>
          <p class="ajuda">
            Confira antes de vincular: o mesmo telefone pode pertencer a mais de um associado.
          </p>
          <ul class="opcoes">
            <li v-for="a in listaSugeridos.data.value" :key="`s-${a.id}`">
              <label>
                <input v-model="escolhido" type="radio" name="associado" :value="a" />
                {{ a.nome }} <span class="ajuda">{{ a.documentoMascarado }}</span>
              </label>
            </li>
          </ul>
        </template>

        <form role="search" @submit.prevent="busca = termo.trim()">
          <label for="busca-associado">Buscar associado</label>
          <input
            id="busca-associado"
            v-model="termo"
            type="search"
            maxlength="100"
            placeholder="Nome ou documento"
          />
        </form>
        <ul v-if="resultados.data.value?.length" class="opcoes">
          <li v-for="a in resultados.data.value" :key="`b-${a.id}`">
            <label>
              <input v-model="escolhido" type="radio" name="associado" :value="a" />
              {{ a.nome }} <span class="ajuda">{{ a.documentoMascarado }}</span>
            </label>
          </li>
        </ul>
        <p v-else-if="resultados.isSuccess.value" class="ajuda">Nenhum associado encontrado.</p>

        <div v-if="exigeMotivo" class="campo">
          <label for="motivo-vinculo">Motivo da alteração</label>
          <input id="motivo-vinculo" v-model="motivo" maxlength="500" required />
        </div>
        <div class="acoes">
          <button
            type="button"
            :disabled="!escolhido || (exigeMotivo && !motivo.trim()) || vincular.isPending.value"
            @click="confirmar(escolhido?.id)"
          >
            Vincular associado
          </button>
          <button
            v-if="atual"
            type="button"
            class="secundario"
            :disabled="!motivo.trim() || vincular.isPending.value"
            @click="confirmar(undefined)"
          >
            Remover vínculo
          </button>
          <button v-if="editando" type="button" class="secundario" @click="editando = false">
            Cancelar
          </button>
        </div>
      </div>

      <MidiaConversa :atendimento="id" @abrir-mensagem="(m) => emit('abrirMensagem', m)" />
    </template>
  </aside>
</template>

<style scoped>
.painel {
  margin: 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.topo {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.topo h2 {
  margin: 0;
  font-size: 1.05rem;
}

h3 {
  margin: 0.5rem 0 0.25rem;
  font-size: 0.9rem;
}

.atual p {
  margin: 0 0 0.25rem;
}

.atual .ajuda {
  display: block;
}

.atual a,
.atual button {
  margin-right: 0.75rem;
}

.edicao input[type='search'],
.edicao .campo input {
  width: 100%;
  box-sizing: border-box;
}

.opcoes {
  list-style: none;
  padding: 0;
  margin: 0.25rem 0;
}
</style>
