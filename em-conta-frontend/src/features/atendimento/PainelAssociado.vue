<script setup lang="ts">
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { temPermissao, useSessao } from '@/features/auth/auth-api'
import { codigoDoErro, describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'
import IconeTraco from '@/shared/components/IconeTraco.vue'

import {
  buscarAssociados,
  chavesAtendimento,
  formatarData,
  iniciais,
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
const emit = defineEmits<{ fechar: []; abrirMensagem: [mensagemId: number, cursor: string] }>()

// Ao abrir o painel pelo botão da conversa, o foco vai para o fechar (e volta ao botão ao fechar).
const botaoFechar = ref<HTMLButtonElement | null>(null)
defineExpose({ focar: () => botaoFechar.value?.focus() })

const http = useHttpClient()
const queryClient = useQueryClient()
const { data: usuario } = useSessao()
const podeVincular = computed(() => temPermissao(usuario.value, 'atendimento:vincular-associado'))

const detalhe = useQuery({
  queryKey: computed(() => chavesAtendimento.detalhe(props.id)),
  queryFn: () => obterDetalhe(http, props.id),
})
const atual = computed(() => detalhe.data.value?.resumo.associado)

// Seções recolhíveis do painel (AT-04).
const secoes = reactive({ atendimento: true })

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
  <aside id="painel-associado" class="painel cartao" aria-labelledby="titulo-associado">
    <header class="topo">
      <h2 id="titulo-associado">Dados do associado</h2>
      <button
        ref="botaoFechar"
        type="button"
        class="botao-icone sem-borda"
        aria-label="Fechar painel do associado"
        title="Fechar painel"
        @click="emit('fechar')"
      >
        <IconeTraco nome="fechar" />
      </button>
    </header>

    <p v-if="detalhe.isPending.value" role="status">Carregando…</p>
    <div v-else-if="detalhe.isError.value">
      <AlertaMensagem tipo="erro">{{ describeApiError(detalhe.error.value) }}</AlertaMensagem>
      <button type="button" class="secundario" @click="detalhe.refetch()">Tentar de novo</button>
    </div>
    <template v-else-if="detalhe.data.value">
      <div v-if="atual" class="atual">
        <div class="identidade">
          <span class="avatar" aria-hidden="true">{{ iniciais(atual.nome) }}</span>
          <p>
            <strong>{{ atual.nome }}</strong>
            <span class="ajuda">{{ atual.tipo }} · {{ atual.documentoMascarado }}</span>
          </p>
        </div>
        <p class="contato com-icone">
          <IconeTraco nome="telefone" />+{{ detalhe.data.value.resumo.contatoNumero }}
        </p>
        <RouterLink
          :to="{ name: 'associado', params: { id: atual.id } }"
          class="ver-cadastro com-icone"
        >
          <IconeTraco nome="externo" :tamanho="20" />
          Ver cadastro
        </RouterLink>
        <button
          v-if="podeVincular && !editando"
          type="button"
          class="link"
          @click="editando = true"
        >
          Alterar vínculo
        </button>
      </div>
      <div v-else class="sem-vinculo">
        <p class="contato com-icone">
          <IconeTraco nome="telefone" />+{{ detalhe.data.value.resumo.contatoNumero }}
        </p>
        <p class="ajuda">Nenhum associado vinculado a esta conversa.</p>
      </div>

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

      <section class="secao" aria-labelledby="titulo-secao-atendimento">
        <h3 id="titulo-secao-atendimento">
          <button
            type="button"
            class="alternar"
            :aria-expanded="secoes.atendimento"
            aria-controls="secao-atendimento"
            @click="secoes.atendimento = !secoes.atendimento"
          >
            Atendimento
            <IconeTraco :nome="secoes.atendimento ? 'seta-acima' : 'seta-abaixo'" :tamanho="20" />
          </button>
        </h3>
        <dl v-show="secoes.atendimento" id="secao-atendimento" class="dados">
          <div>
            <dt class="com-icone"><IconeTraco nome="documento" />Protocolo</dt>
            <dd>{{ detalhe.data.value.resumo.codigo }}</dd>
          </div>
          <div>
            <dt class="com-icone"><IconeTraco nome="pessoa" />Responsável</dt>
            <dd>{{ detalhe.data.value.resumo.responsavel?.nome ?? '—' }}</dd>
          </div>
          <div>
            <dt class="com-icone"><IconeTraco nome="historico" />Desde</dt>
            <dd>{{ formatarData(detalhe.data.value.resumo.iniciadoEm) }}</dd>
          </div>
        </dl>
      </section>

      <MidiaConversa
        :atendimento="id"
        @abrir-mensagem="(m, cursor) => emit('abrirMensagem', m, cursor)"
      />
    </template>
  </aside>
</template>

<style scoped>
/* AT-04: painel de 278 px que rola por dentro; seções com título de 19 px e divisória. */
.painel {
  margin: 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 14px 16px 16px;
  overflow-y: auto;
}

.topo {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.topo h2 {
  margin: 0;
  font-size: 19px;
  font-weight: 600;
}

.topo .botao-icone {
  width: 40px;
  height: 40px;
  background: var(--cor-superficie-2);
}

h3 {
  margin: 0.5rem 0 0.25rem;
  font-size: 0.9rem;
}

.identidade {
  display: flex;
  align-items: center;
  gap: 12px;
}

.identidade p {
  margin: 0;
  min-width: 0;
}

.identidade strong {
  display: block;
  font-size: 17px;
  font-weight: 500;
  overflow-wrap: anywhere;
}

.identidade .ajuda {
  display: block;
}

.avatar {
  display: grid;
  place-items: center;
  flex-shrink: 0;
  width: 60px;
  height: 60px;
  border-radius: 999px;
  background: var(--cor-destaque-suave);
  color: var(--cor-destaque-forte);
  font-size: 18px;
  font-weight: 500;
}

.contato {
  display: flex;
  align-items: center;
  margin: 12px 0;
  font-size: 16px;
  gap: 12px;
}

.sem-vinculo .contato {
  margin-top: 0;
}

.ver-cadastro {
  justify-content: center;
  width: 100%;
  height: 46px;
  box-sizing: border-box;
  margin-bottom: 8px;
  border: 1px solid var(--cor-borda);
  border-radius: var(--raio-controle);
  color: var(--cor-texto);
  font-size: 16px;
  font-weight: 500;
  text-decoration: none;
}

.ver-cadastro:hover {
  background: var(--cor-superficie-2);
}

.secao h3 {
  margin: 0;
}

button.alternar {
  display: flex;
  width: 100%;
  justify-content: space-between;
  align-items: center;
  padding: 10px 0 12px;
  border: none;
  border-bottom: 1px solid var(--cor-borda);
  border-radius: 0;
  background: none;
  color: var(--cor-texto);
  font-size: 19px;
  font-weight: 600;
}

.dados {
  margin: 12px 0 0;
}

.dados div {
  display: grid;
  grid-template-columns: 8.2rem minmax(0, 1fr);
  align-items: center;
  min-height: 42px;
}

.dados dt {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--cor-texto-2);
  font-size: 15px;
}

.dados dd {
  margin: 0;
  font-size: 15px;
  overflow-wrap: anywhere;
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
