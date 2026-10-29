<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { computed } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { temPermissao, useSessao } from '@/features/auth/auth-api'
import { describeApiError } from '@/shared/api/errors'
import { ApiError } from '@/shared/api/http-client'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'

import HistoricoUsina from './HistoricoUsina.vue'
import UsinaUcsAba from './UsinaUcsAba.vue'
import { AVISO_VINCULO_CADASTRAL, chavesUsinas, localizacao, obterUsina } from './usinas-api'

const props = defineProps<{ id: string }>()
const id = computed(() => Number(props.id))

const http = useHttpClient()
const route = useRoute()
const router = useRouter()
const { data: sessao } = useSessao()

const usina = useQuery({
  queryKey: computed(() => chavesUsinas.usina(id.value)),
  queryFn: () => obterUsina(http, id.value),
})

type Aba = 'visao' | 'ucs' | 'informacoes'
const abas = computed(() => {
  const lista: { id: Aba; rotulo: string }[] = [{ id: 'visao', rotulo: 'Visão geral' }]
  if (temPermissao(sessao.value, 'vinculos-usina:consultar')) {
    lista.push({
      id: 'ucs',
      rotulo: `Unidades consumidoras (${usina.data.value?.ucsVinculadas ?? 0})`,
    })
  }
  lista.push({ id: 'informacoes', rotulo: 'Informações' })
  return lista
})
const aba = computed<Aba>(() => abas.value.find((a) => a.id === route.query.aba)?.id ?? 'visao')

function abrir(nova: Aba) {
  void router.replace({ query: { aba: nova === 'visao' ? undefined : nova } })
}

const podeEditar = computed(() => temPermissao(sessao.value, 'usinas:editar'))
const podeHistorico = computed(() => temPermissao(sessao.value, 'usinas:consultar-historico'))
const naoEncontrada = computed(
  () => usina.error.value instanceof ApiError && usina.error.value.status === 404,
)
</script>

<template>
  <main class="pagina pagina--larga">
    <nav aria-label="Trilha" class="ajuda">
      <RouterLink :to="{ name: 'usinas' }">Usinas</RouterLink>
      <template v-if="usina.data.value"> / {{ usina.data.value.nome }}</template>
    </nav>

    <p v-if="usina.isPending.value" role="status">Carregando usina…</p>
    <template v-else-if="usina.isError.value">
      <h1>{{ naoEncontrada ? 'Usina não encontrada' : 'Usina' }}</h1>
      <AlertaMensagem tipo="erro">{{ describeApiError(usina.error.value) }}</AlertaMensagem>
      <button v-if="!naoEncontrada" type="button" class="secundario" @click="usina.refetch()">
        Tentar novamente
      </button>
    </template>
    <template v-else-if="usina.data.value">
      <div class="cabecalho-ficha">
        <div>
          <h1>{{ usina.data.value.nome }}</h1>
          <p class="ajuda">
            {{ usina.data.value.codigo }} ·
            {{ localizacao(usina.data.value.cidade, usina.data.value.uf) }}
            <template v-if="usina.data.value.fazenda">
              · Fazenda {{ usina.data.value.fazenda.nome }}
            </template>
          </p>
        </div>
        <RouterLink
          v-if="podeEditar"
          class="botao-secundario"
          :to="{ name: 'usina-editar', params: { id } }"
        >
          Editar informações
        </RouterLink>
      </div>

      <AlertaMensagem v-if="route.query.criada === '1'" tipo="sucesso"
        >Usina cadastrada.</AlertaMensagem
      >
      <AlertaMensagem v-if="route.query.salva === '1'" tipo="sucesso"
        >Informações salvas.</AlertaMensagem
      >

      <div class="abas" role="tablist" aria-label="Seções da usina">
        <button
          v-for="item in abas"
          :id="`aba-${item.id}`"
          :key="item.id"
          type="button"
          role="tab"
          class="aba"
          :aria-selected="aba === item.id"
          :aria-controls="`painel-${item.id}`"
          :tabindex="aba === item.id ? 0 : -1"
          @click="abrir(item.id)"
        >
          {{ item.rotulo }}
        </button>
      </div>

      <section :id="`painel-${aba}`" role="tabpanel" :aria-labelledby="`aba-${aba}`">
        <template v-if="aba === 'visao'">
          <ul class="resumo">
            <li class="cartao">
              <span class="ajuda">UCs com vínculo vigente</span>
              <strong>{{ usina.data.value.ucsVinculadas }}</strong>
            </li>
            <li class="cartao">
              <span class="ajuda">Associados distintos</span>
              <strong>{{ usina.data.value.associadosDistintos }}</strong>
            </li>
            <li class="cartao">
              <span class="ajuda">Vínculos encerrados</span>
              <strong>{{ usina.data.value.vinculosEncerrados }}</strong>
            </li>
          </ul>
          <p v-if="usina.data.value.ucsVinculadas === 0" class="ajuda">
            Nenhuma unidade consumidora vinculada a esta usina.
          </p>
          <AlertaMensagem tipo="info">
            {{ AVISO_VINCULO_CADASTRAL }} Potência, geração, disponibilidade e estimativas ainda não
            fazem parte do cadastro.
          </AlertaMensagem>
        </template>

        <div v-else-if="aba === 'ucs'" class="cartao">
          <UsinaUcsAba :usina-id="id" />
        </div>

        <template v-else>
          <div class="cartao">
            <h2>Informações</h2>
            <dl class="dados">
              <div>
                <dt>Nome da usina</dt>
                <dd>{{ usina.data.value.nome }}</dd>
              </div>
              <div>
                <dt>Identificador (gerado pelo sistema)</dt>
                <dd>{{ usina.data.value.codigo }}</dd>
              </div>
              <div>
                <dt>Fazenda</dt>
                <dd>
                  {{
                    usina.data.value.fazenda
                      ? `${usina.data.value.fazenda.codigo} · ${usina.data.value.fazenda.nome}`
                      : '—'
                  }}
                </dd>
              </div>
              <div>
                <dt>CEP</dt>
                <dd>{{ usina.data.value.cep || '—' }}</dd>
              </div>
              <div>
                <dt>Endereço</dt>
                <dd>{{ usina.data.value.endereco || '—' }}</dd>
              </div>
              <div>
                <dt>Cidade / UF</dt>
                <dd>{{ localizacao(usina.data.value.cidade, usina.data.value.uf) }}</dd>
              </div>
              <div>
                <dt>Observações</dt>
                <dd>{{ usina.data.value.observacoes || '—' }}</dd>
              </div>
              <div>
                <dt>Cadastrada por</dt>
                <dd>{{ usina.data.value.criadoPor.nome }}</dd>
              </div>
              <div>
                <dt>Última alteração por</dt>
                <dd>{{ usina.data.value.atualizadoPor.nome }}</dd>
              </div>
            </dl>
          </div>
          <div v-if="podeHistorico" class="cartao">
            <HistoricoUsina :usina-id="id" />
          </div>
        </template>
      </section>
    </template>
  </main>
</template>

<style scoped>
.cabecalho-ficha {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 1rem;
  flex-wrap: wrap;
  margin: 0.5rem 0 1rem;
}

.resumo {
  list-style: none;
  padding: 0;
  margin: 0 0 1rem;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(12rem, 1fr));
  gap: 1rem;
}

.resumo li {
  display: flex;
  flex-direction: column;
}

.abas {
  display: flex;
  gap: 0.25rem;
  flex-wrap: wrap;
  border-bottom: 1px solid var(--cor-borda);
  margin: 1rem 0;
}

.aba {
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
  border-radius: 0;
  color: var(--cor-texto);
  padding: 0.5rem 0.75rem;
}

.aba[aria-selected='true'] {
  border-bottom-color: var(--cor-destaque);
  color: var(--cor-destaque-forte);
  font-weight: 600;
}

.dados {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(16rem, 1fr));
  gap: 0.75rem 1.5rem;
  margin: 0;
}

.dados dt {
  color: var(--cor-texto-2);
  font-size: 0.8rem;
}

.dados dd {
  margin: 0;
}
</style>
