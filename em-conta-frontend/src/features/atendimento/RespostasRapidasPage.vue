<script setup lang="ts">
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, nextTick, ref } from 'vue'
import { RouterLink } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { temPermissao, useSessao } from '@/features/auth/auth-api'
import { camposDoErro } from '@/shared/api/campos'
import { codigoDoErro, describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'
import IconeTraco from '@/shared/components/IconeTraco.vue'

import { formatarDataHora } from './atendimento-api'
import {
  alterarSituacaoResposta,
  chavesRespostas,
  criarResposta,
  editarResposta,
  listarRespostas,
  normalizarAtalho,
  obterResposta,
  TAMANHO_MAXIMO_ATALHO,
  TAMANHO_MAXIMO_TITULO,
  type RespostaRapida,
  type SituacaoFiltro,
} from './respostas-rapidas-api'

/**
 * Catálogo de respostas rápidas (gerenciamento). Ações aparecem só para quem tem a permissão
 * correspondente; o servidor confere de novo. Desativar preserva o cadastro.
 */
const TAMANHO_MAXIMO_CONTEUDO = 4096
const http = useHttpClient()
const queryClient = useQueryClient()
const { data: usuario } = useSessao()
const pode = (p: string) => temPermissao(usuario.value, p)

const termo = ref('')
const busca = ref('')
const situacao = ref<SituacaoFiltro>('ATIVAS')
const pagina = ref(0)
const filtros: { valor: SituacaoFiltro; rotulo: string }[] = [
  { valor: 'ATIVAS', rotulo: 'Ativas' },
  { valor: 'INATIVAS', rotulo: 'Inativas' },
  { valor: 'TODAS', rotulo: 'Todas' },
]

const lista = useQuery({
  queryKey: computed(() => chavesRespostas.lista(busca.value, situacao.value, pagina.value)),
  queryFn: () =>
    listarRespostas(http, { busca: busca.value, situacao: situacao.value, pagina: pagina.value }),
  placeholderData: keepPreviousData,
})

function buscar() {
  busca.value = termo.value.trim()
  pagina.value = 0
}
function filtrar(valor: SituacaoFiltro) {
  situacao.value = valor
  pagina.value = 0
}
const atualizar = () => queryClient.invalidateQueries({ queryKey: chavesRespostas.todas })

// ---------------------------------------------------------------- formulário
const editando = ref<RespostaRapida | null>(null)
const aberto = ref(false)
const form = ref({ titulo: '', atalho: '', conteudo: '' })
const campoTitulo = ref<HTMLInputElement | null>(null)

async function abrir(resposta: RespostaRapida | null) {
  editando.value = resposta
  form.value = resposta
    ? { titulo: resposta.titulo, atalho: resposta.atalho, conteudo: resposta.conteudo }
    : { titulo: '', atalho: '', conteudo: '' }
  salvar.reset()
  aberto.value = true
  await nextTick()
  campoTitulo.value?.focus()
}
function fechar() {
  aberto.value = false
  editando.value = null
  salvar.reset()
}

const salvar = useMutation({
  mutationFn: () =>
    editando.value
      ? editarResposta(http, editando.value.id, editando.value.versao, form.value)
      : criarResposta(http, form.value),
  onSuccess: () => fechar(),
  onSettled: atualizar,
})
const erros = computed(() => camposDoErro(salvar.error.value))
const conflito = computed(() => codigoDoErro(salvar.error.value) === 'CONFLITO_CONCORRENTE')
async function recarregarEdicao() {
  const id = editando.value?.id
  await atualizar()
  if (id !== undefined) await abrir(await obterResposta(http, id))
}

const atalhoNormalizado = computed(() => normalizarAtalho(form.value.atalho))

// ---------------------------------------------------------------- situação
const erroSituacao = ref<unknown>(null)
const situacaoMut = useMutation({
  mutationFn: (r: RespostaRapida) => alterarSituacaoResposta(http, r.id, r.versao, !r.ativa),
  onMutate: () => (erroSituacao.value = null),
  onError: (e) => (erroSituacao.value = e),
  onSettled: atualizar,
})
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
      <div class="titulo">
        <h1>Respostas rápidas</h1>
        <p class="subtitulo">
          Textos internos da equipe para inserir na conversa e revisar antes de enviar. Não são
          modelos aprovados pela Meta e não liberam envio fora da janela de 24 horas.
        </p>
      </div>
      <button
        v-if="pode('respostas-rapidas:cadastrar')"
        type="button"
        class="com-icone"
        @click="abrir(null)"
      >
        Nova resposta
      </button>
    </header>

    <div class="colunas" :class="{ 'com-formulario': aberto }">
      <section class="cartao" aria-labelledby="titulo-catalogo">
        <h2 id="titulo-catalogo" class="visualmente-oculto">Catálogo</h2>
        <form class="busca" role="search" @submit.prevent="buscar">
          <label for="busca-respostas" class="visualmente-oculto">Buscar respostas</label>
          <input
            id="busca-respostas"
            v-model="termo"
            type="search"
            maxlength="100"
            placeholder="Buscar por título, atalho ou texto"
          />
          <button type="submit" class="secundario">Buscar</button>
        </form>
        <div class="filtros" role="group" aria-label="Filtrar por situação">
          <button
            v-for="f in filtros"
            :key="f.valor"
            type="button"
            class="chip"
            :aria-pressed="situacao === f.valor"
            @click="filtrar(f.valor)"
          >
            {{ f.rotulo }}
          </button>
        </div>

        <AlertaMensagem v-if="erroSituacao" tipo="erro">
          {{
            codigoDoErro(erroSituacao) === 'CONFLITO_CONCORRENTE'
              ? 'Esta resposta foi alterada por outra pessoa. A lista foi recarregada; tente de novo.'
              : describeApiError(erroSituacao)
          }}
        </AlertaMensagem>
        <p v-if="lista.isPending.value" role="status">Carregando respostas…</p>
        <template v-else-if="lista.isError.value">
          <AlertaMensagem tipo="erro">{{ describeApiError(lista.error.value) }}</AlertaMensagem>
          <button type="button" class="secundario" @click="lista.refetch()">Tentar de novo</button>
        </template>
        <template v-else-if="lista.data.value">
          <p v-if="lista.data.value.totalItens === 0" class="vazio" role="status">
            Nenhuma resposta rápida encontrada.
          </p>
          <ul v-else class="itens" :aria-busy="lista.isFetching.value">
            <li v-for="r in lista.data.value.itens" :key="r.id" class="item">
              <div class="cabeca">
                <strong>{{ r.titulo }}</strong>
                <code class="atalho">/{{ r.atalho }}</code>
                <span class="etiqueta" :class="{ inativa: !r.ativa }">{{
                  r.ativa ? 'Ativa' : 'Inativa'
                }}</span>
              </div>
              <p class="previa-curta">{{ r.conteudo }}</p>
              <p class="ajuda">
                Alterada por {{ r.atualizadoPor.nome }} em {{ formatarDataHora(r.atualizadoEm) }}
              </p>
              <div class="acoes">
                <button
                  v-if="pode('respostas-rapidas:editar')"
                  type="button"
                  class="secundario"
                  :aria-label="`Editar ${r.titulo}`"
                  @click="abrir(r)"
                >
                  Editar
                </button>
                <button
                  v-if="pode('respostas-rapidas:alterar-situacao')"
                  type="button"
                  class="secundario"
                  :aria-label="`${r.ativa ? 'Desativar' : 'Ativar'} ${r.titulo}`"
                  :disabled="situacaoMut.isPending.value"
                  @click="situacaoMut.mutate(r)"
                >
                  {{ r.ativa ? 'Desativar' : 'Ativar' }}
                </button>
              </div>
            </li>
          </ul>
          <nav
            v-if="lista.data.value.totalPaginas > 1"
            class="acoes paginacao"
            aria-label="Paginação das respostas"
          >
            <button type="button" class="secundario" :disabled="pagina === 0" @click="pagina--">
              Anterior
            </button>
            <span>Página {{ pagina + 1 }} de {{ lista.data.value.totalPaginas }}</span>
            <button
              type="button"
              class="secundario"
              :disabled="pagina + 1 >= lista.data.value.totalPaginas"
              @click="pagina++"
            >
              Próxima
            </button>
          </nav>
        </template>
      </section>

      <section v-if="aberto" class="cartao formulario" aria-labelledby="titulo-form">
        <h2 id="titulo-form">{{ editando ? 'Editar resposta' : 'Nova resposta' }}</h2>
        <AlertaMensagem v-if="conflito" tipo="aviso">
          Esta resposta foi alterada por outra pessoa enquanto você editava. Recarregue para ver a
          versão atual; suas alterações não foram salvas.
          <button type="button" class="link" @click="recarregarEdicao">Recarregar</button>
        </AlertaMensagem>
        <AlertaMensagem
          v-else-if="salvar.isError.value && Object.keys(erros).length === 0"
          tipo="erro"
        >
          {{ describeApiError(salvar.error.value) }}
        </AlertaMensagem>
        <form novalidate @submit.prevent="salvar.mutate()">
          <div class="campo">
            <label for="rr-titulo"
              >Título<span class="obrigatorio" aria-hidden="true"> *</span></label
            >
            <input
              id="rr-titulo"
              ref="campoTitulo"
              v-model="form.titulo"
              type="text"
              required
              :maxlength="TAMANHO_MAXIMO_TITULO"
              :aria-invalid="!!erros.titulo"
              :aria-describedby="erros.titulo ? 'rr-titulo-erro' : undefined"
            />
            <p v-if="erros.titulo" id="rr-titulo-erro" class="ajuda ajuda--erro">
              {{ erros.titulo }}
            </p>
          </div>
          <div class="campo">
            <label for="rr-atalho"
              >Atalho<span class="obrigatorio" aria-hidden="true"> *</span></label
            >
            <div class="com-barra">
              <span aria-hidden="true">/</span>
              <input
                id="rr-atalho"
                v-model="form.atalho"
                type="text"
                required
                :maxlength="TAMANHO_MAXIMO_ATALHO + 1"
                autocapitalize="off"
                spellcheck="false"
                :aria-invalid="!!erros.atalho"
                aria-describedby="rr-atalho-ajuda rr-atalho-erro"
              />
            </div>
            <p id="rr-atalho-ajuda" class="ajuda">
              Letras minúsculas sem acento, números e hífen (2 a {{ TAMANHO_MAXIMO_ATALHO }}). Na
              conversa, digite /{{ atalhoNormalizado || 'atalho' }}.
            </p>
            <p v-if="erros.atalho" id="rr-atalho-erro" class="ajuda ajuda--erro">
              {{ erros.atalho }}
            </p>
          </div>
          <div class="campo">
            <label for="rr-conteudo"
              >Texto<span class="obrigatorio" aria-hidden="true"> *</span></label
            >
            <textarea
              id="rr-conteudo"
              v-model="form.conteudo"
              rows="6"
              required
              :maxlength="TAMANHO_MAXIMO_CONTEUDO"
              :aria-invalid="!!erros.conteudo"
              aria-describedby="rr-conteudo-contador rr-conteudo-erro"
            ></textarea>
            <p id="rr-conteudo-contador" class="ajuda">
              {{ form.conteudo.length }} de {{ TAMANHO_MAXIMO_CONTEUDO }} caracteres
            </p>
            <p v-if="erros.conteudo" id="rr-conteudo-erro" class="ajuda ajuda--erro">
              {{ erros.conteudo }}
            </p>
          </div>
          <div class="previa" aria-label="Prévia da mensagem">
            <p class="ajuda">Prévia</p>
            <p class="balao">{{ form.conteudo || 'O texto aparece aqui.' }}</p>
          </div>
          <div class="acoes">
            <button type="submit" :disabled="salvar.isPending.value">
              {{ salvar.isPending.value ? 'Salvando…' : 'Salvar' }}
            </button>
            <button type="button" class="secundario" @click="fechar">Cancelar</button>
          </div>
        </form>
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

.cabecalho .titulo {
  flex: 1;
}

.cabecalho .botao-icone {
  margin-top: 0.15rem;
}

.colunas {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 1rem;
  align-items: start;
}

.colunas.com-formulario {
  grid-template-columns: minmax(0, 1fr) minmax(0, 28rem);
}

@media (max-width: 60rem) {
  .colunas.com-formulario {
    grid-template-columns: minmax(0, 1fr);
  }
}

.busca {
  display: flex;
  gap: 0.5rem;
}

.busca input {
  flex: 1;
  min-width: 0;
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

.atalho {
  color: var(--cor-destaque-forte);
}

.etiqueta.inativa {
  background: var(--cor-superficie-2);
  color: var(--cor-texto-2);
  font-style: italic;
}

.previa-curta {
  margin: 0.25rem 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.vazio {
  text-align: center;
  color: var(--cor-texto-2);
  padding: 2rem 0;
}

.paginacao {
  margin-top: 1rem;
}

.formulario {
  margin: 0;
}

.formulario input,
.formulario textarea {
  width: 100%;
  box-sizing: border-box;
}

.com-barra {
  display: flex;
  align-items: center;
  gap: 0.25rem;
}

.previa {
  margin-bottom: 1rem;
}

.balao {
  margin: 0.25rem 0 0;
  max-width: 100%;
  padding: 0.6rem 0.85rem;
  border-radius: 10px;
  background: var(--cor-destaque-suave);
  border: 1px solid var(--cor-destaque-borda);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
