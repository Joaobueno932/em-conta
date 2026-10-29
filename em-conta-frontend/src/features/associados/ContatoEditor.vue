<script setup lang="ts">
import { useMutation, useQueryClient } from '@tanstack/vue-query'
import { computed, reactive } from 'vue'

import { useHttpClient } from '@/app/injection'
import { camposDoErro } from '@/shared/api/campos'
import { codigoDoErro, describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'

import { adicionarContato, chaves, editarContato, type Contato } from './associados-api'
import CampoTexto from '@/shared/components/CampoTexto.vue'

/**
 * Inclusão ou edição de um contato. Cada contato é salvo sozinho, com a própria versão: editar um
 * contato nunca apaga ou sobrescreve os demais.
 */
const props = defineProps<{ associadoId: number; contato?: Contato }>()
const emit = defineEmits<{ concluido: []; cancelado: [] }>()

const http = useHttpClient()
const queryClient = useQueryClient()
const prefixo = computed(() => `contato-${props.contato?.id ?? 'novo'}`)

const dados = reactive({
  nome: props.contato?.nome ?? '',
  email: props.contato?.email ?? '',
  telefone: props.contato?.telefone ?? '',
})

const salvar = useMutation({
  mutationFn: () => {
    const corpo = {
      nome: dados.nome,
      ...(dados.email.trim() ? { email: dados.email } : {}),
      ...(dados.telefone.trim() ? { telefone: dados.telefone } : {}),
    }
    return props.contato
      ? editarContato(http, props.associadoId, props.contato.id, {
          ...corpo,
          versao: props.contato.versao,
        })
      : adicionarContato(http, props.associadoId, corpo)
  },
  onSuccess: async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: chaves.ficha(props.associadoId) }),
      queryClient.invalidateQueries({ queryKey: chaves.historico(props.associadoId) }),
    ])
    emit('concluido')
  },
})

const erros = computed(() => camposDoErro(salvar.error.value))
const conflito = computed(() => codigoDoErro(salvar.error.value) === 'CONFLITO_CONCORRENTE')

/** Recarrega a ficha depois de um conflito; o texto digitado continua no formulário. */
async function recarregar() {
  await queryClient.invalidateQueries({ queryKey: chaves.ficha(props.associadoId) })
  emit('cancelado')
}
</script>

<template>
  <form
    class="editor"
    novalidate
    :aria-label="contato ? `Editar contato ${contato.nome}` : 'Novo contato'"
    @submit.prevent="!salvar.isPending.value && salvar.mutate()"
  >
    <AlertaMensagem v-if="conflito" tipo="aviso">
      Este contato foi alterado por outra pessoa. Seus dados digitados foram mantidos; recarregue
      para ver a versão atual.
      <button type="button" class="link" @click="recarregar">Recarregar dados</button>
    </AlertaMensagem>
    <AlertaMensagem v-else-if="salvar.isError.value && !Object.keys(erros).length" tipo="erro">
      {{ describeApiError(salvar.error.value) }}
    </AlertaMensagem>
    <div class="grade">
      <CampoTexto
        :id="`${prefixo}-nome`"
        v-model="dados.nome"
        rotulo="Nome do contato"
        obrigatorio
        :maximo="150"
        :erro="erros.nome"
      />
      <CampoTexto
        :id="`${prefixo}-telefone`"
        v-model="dados.telefone"
        rotulo="Telefone"
        tipo="tel"
        :maximo="30"
        ajuda="Com DDD"
        :erro="erros.telefone"
      />
      <CampoTexto
        :id="`${prefixo}-email`"
        v-model="dados.email"
        rotulo="E-mail"
        tipo="email"
        :maximo="254"
        :erro="erros.email ?? erros.contatos"
      />
    </div>
    <div class="acoes">
      <button type="submit" :disabled="salvar.isPending.value">
        {{ salvar.isPending.value ? 'Salvando…' : 'Salvar contato' }}
      </button>
      <button type="button" class="secundario" @click="emit('cancelado')">Cancelar</button>
    </div>
  </form>
</template>

<style scoped>
.editor {
  border: 1px solid var(--cor-borda);
  border-radius: var(--raio);
  padding: 1rem;
  margin: 0.5rem 0 1rem;
}
</style>
