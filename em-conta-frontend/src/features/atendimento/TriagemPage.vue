<script setup lang="ts">
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { temPermissao, useSessao } from '@/features/auth/auth-api'
import { camposDoErro } from '@/shared/api/campos'
import { codigoDoErro, describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'
import IconeTraco from '@/shared/components/IconeTraco.vue'

import { formatarDataHora } from './atendimento-api'
import { chavesClassificacoes, listarCadastro, rotuloItem } from './classificacoes-api'
import {
  chavesTriagem,
  configurarTriagem,
  obterTriagem,
  type ConfiguracaoTriagem,
  type OpcaoTriagem,
} from './triagem-api'

/**
 * Triagem inicial pelo WhatsApp (aberta por "Ver opções"): ligar ou desligar e escolher o setor de cada
 * opção do menu. Duas opções podem ter o mesmo setor; sem setor, o ciclo vai para a fila sem setor. A
 * opção "Outro assunto ou falar com um atendente" vai sempre para a fila sem setor. Quem só consulta vê a
 * configuração sem os controles; o servidor confere a permissão de novo.
 */
const http = useHttpClient()
const queryClient = useQueryClient()
const { data: usuario } = useSessao()
const podeConfigurar = computed(() => temPermissao(usuario.value, 'triagem:configurar'))

const configuracao = useQuery({
  queryKey: chavesTriagem.configuracao,
  queryFn: () => obterTriagem(http),
})
const setores = useQuery({
  queryKey: chavesClassificacoes.lista('setores', 'TODOS'),
  queryFn: () => listarCadastro(http, 'setores', 'TODOS'),
})

// ---------------------------------------------------------------- formulário
const ativa = ref(false)
const destinos = ref<Record<string, number | ''>>({})
function carregar(c: ConfiguracaoTriagem) {
  ativa.value = c.ativa
  destinos.value = Object.fromEntries(
    c.opcoes.filter((o) => o.configuravel).map((o) => [o.opcao, o.setor?.id ?? '']),
  )
}
watch(
  () => configuracao.data.value,
  (c) => c && carregar(c),
  { immediate: true },
)

/** Escolhas de cada opção: setores ativos e o que ela já tem (mesmo que desativado depois). */
function escolhas(opcao: OpcaoTriagem) {
  return (setores.data.value?.itens ?? []).filter((s) => s.ativo || s.id === opcao.setor?.id)
}

const salvar = useMutation({
  mutationFn: (versao: number) =>
    configurarTriagem(http, {
      versao,
      ativa: ativa.value,
      opcoes: Object.entries(destinos.value).map(([opcao, setor]) => ({
        opcao: opcao as 'FINANCEIRO' | 'COBRANCA' | 'COMERCIAL',
        ...(setor === '' ? {} : { setorId: setor }),
      })),
    }),
  onSuccess: (c) => {
    queryClient.setQueryData(chavesTriagem.configuracao, c)
    salvo.value = true
  },
  onError: (e) => {
    if (codigoDoErro(e) === 'CONFLITO_CONCORRENTE') {
      void queryClient.invalidateQueries({ queryKey: chavesTriagem.configuracao })
    }
  },
})
const salvo = ref(false)
const erros = computed(() => camposDoErro(salvar.error.value))
const conflito = computed(() => codigoDoErro(salvar.error.value) === 'CONFLITO_CONCORRENTE')
function enviar() {
  const c = configuracao.data.value
  if (!c) return
  salvo.value = false
  salvar.mutate(c.versao)
}
</script>

<template>
  <main class="pagina pagina--larga">
    <header class="cabecalho">
      <RouterLink
        :to="{ name: 'atendimento' }"
        class="botao-icone sem-borda"
        aria-label="Voltar ao atendimento"
        title="Voltar ao atendimento"
      >
        <IconeTraco nome="voltar" :tamanho="22" />
      </RouterLink>
      <div>
        <h1>Triagem do WhatsApp</h1>
        <p class="subtitulo">
          Ao abrir um novo atendimento, o cliente recebe uma saudação com o menu abaixo. A opção
          escolhida define o setor antes de alguém assumir; sem setor de destino, o atendimento
          segue na fila sem setor. Quem assume antes da resposta encerra a triagem.
        </p>
      </div>
    </header>

    <p v-if="configuracao.isPending.value" role="status">Carregando…</p>
    <template v-else-if="configuracao.isError.value">
      <AlertaMensagem tipo="erro">{{ describeApiError(configuracao.error.value) }}</AlertaMensagem>
      <button type="button" class="secundario" @click="configuracao.refetch()">
        Tentar de novo
      </button>
    </template>
    <div v-else-if="configuracao.data.value" class="colunas">
      <form class="cartao" novalidate aria-labelledby="titulo-destinos" @submit.prevent="enviar">
        <h2 id="titulo-destinos">Destinos</h2>
        <p class="situacao" role="status">
          <span class="etiqueta" :class="{ inativa: !configuracao.data.value.ativa }">{{
            configuracao.data.value.ativa ? 'Ligada' : 'Desligada'
          }}</span>
          <template
            v-if="configuracao.data.value.atualizadoPor && configuracao.data.value.atualizadoEm"
          >
            Alterada por {{ configuracao.data.value.atualizadoPor.nome }} em
            {{ formatarDataHora(configuracao.data.value.atualizadoEm) }}
          </template>
        </p>

        <AlertaMensagem v-if="conflito" tipo="aviso">
          A triagem foi alterada por outra pessoa enquanto você editava. A configuração foi
          recarregada; revise e salve de novo.
        </AlertaMensagem>
        <AlertaMensagem
          v-else-if="salvar.isError.value && Object.keys(erros).length === 0"
          tipo="erro"
        >
          {{ describeApiError(salvar.error.value) }}
        </AlertaMensagem>
        <AlertaMensagem v-else-if="salvo" tipo="sucesso">Triagem salva.</AlertaMensagem>

        <div v-if="podeConfigurar" class="campo campo--marcar">
          <input id="triagem-ativa" v-model="ativa" type="checkbox" />
          <label for="triagem-ativa">Enviar a triagem ao abrir um novo atendimento</label>
        </div>

        <ol class="opcoes">
          <li v-for="o in configuracao.data.value.opcoes" :key="o.opcao" class="opcao">
            <template v-if="o.configuravel && podeConfigurar">
              <label :for="`destino-${o.opcao}`">{{ o.numero }} - {{ o.rotulo }}</label>
              <select
                :id="`destino-${o.opcao}`"
                v-model="destinos[o.opcao]"
                :aria-invalid="!!erros[`opcoes.${o.opcao}`]"
                :aria-describedby="
                  erros[`opcoes.${o.opcao}`] ? `destino-${o.opcao}-erro` : undefined
                "
              >
                <option value="">Fila sem setor</option>
                <option v-for="s in escolhas(o)" :key="s.id" :value="s.id">
                  {{ rotuloItem(s) }}
                </option>
              </select>
              <p
                v-if="erros[`opcoes.${o.opcao}`]"
                :id="`destino-${o.opcao}-erro`"
                class="ajuda ajuda--erro"
              >
                {{ erros[`opcoes.${o.opcao}`] }}
              </p>
            </template>
            <template v-else>
              <span class="rotulo">{{ o.numero }} - {{ o.rotulo }}</span>
              <span class="destino">{{
                o.configuravel && o.setor
                  ? rotuloItem(o.setor)
                  : o.configuravel
                    ? 'Fila sem setor'
                    : 'Fila sem setor (atendimento humano)'
              }}</span>
            </template>
          </li>
        </ol>
        <p class="ajuda">
          Duas opções podem ter o mesmo setor. Se o setor estiver desativado quando o cliente
          responder, ou se a resposta continuar inválida depois de uma nova tentativa, o atendimento
          segue na fila sem setor.
        </p>

        <div v-if="podeConfigurar" class="acoes">
          <button type="submit" :disabled="salvar.isPending.value || setores.isPending.value">
            {{ salvar.isPending.value ? 'Salvando…' : 'Salvar triagem' }}
          </button>
        </div>
      </form>

      <section class="cartao" aria-labelledby="titulo-saudacao">
        <h2 id="titulo-saudacao">Saudação enviada ao cliente</h2>
        <p class="saudacao">{{ configuracao.data.value.saudacao }}</p>
        <p class="ajuda">
          Resposta aceita: o número da opção (por exemplo, "1", "1." ou "opção 1"). Na primeira
          resposta inválida o menu é repetido uma vez.
        </p>
      </section>
    </div>
  </main>
</template>

<style scoped>
.cabecalho {
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
}

.cabecalho .botao-icone {
  margin-top: 0.15rem;
}

.colunas {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 1rem;
  align-items: start;
}

.colunas > * {
  margin: 0;
}

h2 {
  margin-top: 0;
}

.situacao {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem;
  color: var(--cor-texto-2);
}

.etiqueta.inativa {
  font-style: italic;
}

.campo--marcar {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.opcoes {
  list-style: none;
  margin: 0.75rem 0;
  padding: 0;
}

.opcao {
  display: grid;
  gap: 0.25rem;
  padding: 0.6rem 0;
  border-bottom: 1px solid var(--cor-borda);
}

.opcao select {
  width: 100%;
}

.rotulo {
  font-weight: 600;
}

.destino {
  color: var(--cor-texto-2);
}

.saudacao {
  white-space: pre-wrap;
  padding: 9px 17px;
  border-radius: var(--raio-controle);
  background: var(--cor-destaque-suave);
}

@media (max-width: 60rem) {
  .colunas {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
