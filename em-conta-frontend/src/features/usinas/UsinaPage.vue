<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { computed } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { formatarData } from '@/features/associados/associados-api'
import { temPermissao, useSessao } from '@/features/auth/auth-api'
import { describeApiError } from '@/shared/api/errors'
import { ApiError } from '@/shared/api/http-client'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'
import IconeTraco from '@/shared/components/IconeTraco.vue'

import HistoricoUsina from './HistoricoUsina.vue'
import UsinaUcsAba from './UsinaUcsAba.vue'
import {
  AVISO_VINCULO_CADASTRAL,
  chavesUsinas,
  formatarDataNegocio,
  listarVinculos,
  localizacao,
  obterUsina,
} from './usinas-api'

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

// "Vincular unidade consumidora" do cabeçalho: abre a aba de UCs já com o painel de vínculo.
function vincular() {
  void router.replace({ query: { aba: 'ucs', vincular: '1' } })
}

const podeVerVinculos = computed(() => temPermissao(sessao.value, 'vinculos-usina:consultar'))
const podeVincular = computed(() => temPermissao(sessao.value, 'vinculos-usina:criar'))
const podeVerAssociado = computed(() => temPermissao(sessao.value, 'associados:consultar'))

// Visão geral: os cinco primeiros vínculos vigentes (mesma consulta da aba, página 0).
const vigentes = useQuery({
  queryKey: computed(() => [...chavesUsinas.vinculos(id.value), 'VIGENTES', 0]),
  queryFn: () => listarVinculos(http, id.value, 'VIGENTES', 0),
  enabled: computed(() => podeVerVinculos.value && aba.value === 'visao'),
})

const podeEditar = computed(() => temPermissao(sessao.value, 'usinas:editar'))
const podeHistorico = computed(() => temPermissao(sessao.value, 'usinas:consultar-historico'))
const naoEncontrada = computed(
  () => usina.error.value instanceof ApiError && usina.error.value.status === 404,
)
</script>

<template>
  <main class="pagina-modulo">
    <nav aria-label="Trilha" class="trilha">
      <RouterLink :to="{ name: 'usinas' }">Usinas</RouterLink>
      <template v-if="usina.data.value">
        <span aria-hidden="true">›</span>
        <span aria-current="page">{{ usina.data.value.nome }}</span>
      </template>
    </nav>

    <p v-if="usina.isPending.value" role="status">Carregando usina…</p>
    <template v-else-if="usina.isError.value">
      <h1>{{ naoEncontrada ? 'Usina não encontrada' : 'Usina' }}</h1>
      <AlertaMensagem tipo="erro">{{ describeApiError(usina.error.value) }}</AlertaMensagem>
      <button v-if="!naoEncontrada" type="button" class="botao-contorno" @click="usina.refetch()">
        Tentar novamente
      </button>
    </template>
    <template v-else-if="usina.data.value">
      <div class="cabecalho-ficha">
        <RouterLink :to="{ name: 'usinas' }" class="voltar" aria-label="Voltar para Usinas">
          <IconeTraco nome="seta-esquerda" :tamanho="20" />
        </RouterLink>
        <span class="icone-ficha" aria-hidden="true">
          <IconeTraco nome="usina" :tamanho="26" />
        </span>
        <div class="identidade">
          <h1>{{ usina.data.value.nome }}</h1>
          <p class="meta">
            {{ usina.data.value.codigo }} ·
            {{ localizacao(usina.data.value.cidade, usina.data.value.uf) }}
            <template v-if="usina.data.value.fazenda">
              · Fazenda {{ usina.data.value.fazenda.nome }}
            </template>
          </p>
        </div>
        <div class="acoes-ficha">
          <RouterLink
            v-if="podeEditar"
            class="botao-contorno"
            :to="{ name: 'usina-editar', params: { id } }"
          >
            <IconeTraco nome="lapis" :tamanho="18" />
            Editar informações
          </RouterLink>
          <button
            v-if="podeVincular && podeVerVinculos && aba !== 'ucs'"
            type="button"
            class="botao-primario"
            @click="vincular"
          >
            <IconeTraco nome="externo" :tamanho="18" />
            Vincular unidade consumidora
          </button>
        </div>
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
            <li class="painel">
              <span class="rotulo">UCs vinculadas</span>
              <strong>{{ usina.data.value.ucsVinculadas }}</strong>
              <span class="nota">Vínculos cadastrais vigentes no cadastro</span>
            </li>
            <li class="painel">
              <span class="rotulo">Associados distintos</span>
              <strong>{{ usina.data.value.associadosDistintos }}</strong>
              <span class="nota">Uma pessoa pode ter várias UCs</span>
            </li>
            <li class="painel">
              <span class="rotulo">Vínculos encerrados</span>
              <strong>{{ usina.data.value.vinculosEncerrados }}</strong>
              <span class="nota">Mantidos no histórico</span>
            </li>
            <li class="painel">
              <span class="rotulo">Cadastrada em</span>
              <strong>{{ formatarData(usina.data.value.criadoEm) }}</strong>
              <span class="nota">por {{ usina.data.value.criadoPor.nome }}</span>
            </li>
          </ul>
          <AlertaMensagem tipo="info">
            {{ AVISO_VINCULO_CADASTRAL }} Potência, geração, disponibilidade e estimativas ainda não
            fazem parte do cadastro.
          </AlertaMensagem>

          <div class="grade-visao">
            <section
              v-if="podeVerVinculos"
              class="painel bloco"
              aria-labelledby="titulo-vinculadas"
            >
              <div class="topo-bloco">
                <h2 id="titulo-vinculadas">Unidades consumidoras vinculadas</h2>
                <button type="button" class="link" @click="abrir('ucs')">
                  Ver todas ({{ usina.data.value.ucsVinculadas }})
                </button>
              </div>
              <p v-if="vigentes.isPending.value" role="status" class="ajuda">Carregando…</p>
              <div
                v-else-if="!vigentes.data.value?.itens.length"
                class="estado-vazio"
                role="status"
              >
                <span class="estado-vazio__icone"><IconeTraco nome="usina" :tamanho="24" /></span>
                <strong>Nenhuma UC vinculada</strong>
                <p>Os vínculos cadastrais com unidades consumidoras aparecem aqui.</p>
              </div>
              <div v-else class="tabela-rolavel">
                <table class="tabela-dados">
                  <thead>
                    <tr>
                      <th scope="col">Número da UC</th>
                      <th scope="col">Associado</th>
                      <th scope="col">Vínculo cadastral desde</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="v in vigentes.data.value.itens.slice(0, 5)" :key="v.id">
                      <td>
                        <strong>{{ v.uc.numero }}</strong>
                      </td>
                      <td>
                        <RouterLink
                          v-if="podeVerAssociado"
                          :to="{ name: 'associado', params: { id: v.uc.associadoId } }"
                          >{{ v.uc.associadoNome }}</RouterLink
                        >
                        <template v-else>{{ v.uc.associadoNome }}</template>
                        <span class="ajuda tipo">{{ v.uc.associadoTipo }}</span>
                      </td>
                      <td>{{ formatarDataNegocio(v.inicio) }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </section>

            <section class="painel bloco" aria-labelledby="titulo-principais">
              <div class="topo-bloco">
                <h2 id="titulo-principais">Informações principais</h2>
                <button type="button" class="link" @click="abrir('informacoes')">
                  Ver informações
                </button>
              </div>
              <dl class="ficha">
                <div class="ficha__linha">
                  <dt>Identificador</dt>
                  <dd>{{ usina.data.value.codigo }}</dd>
                </div>
                <div class="ficha__linha">
                  <dt>Localização</dt>
                  <dd>{{ localizacao(usina.data.value.cidade, usina.data.value.uf) }}</dd>
                </div>
                <div class="ficha__linha">
                  <dt>Endereço</dt>
                  <dd>{{ usina.data.value.endereco || '—' }}</dd>
                </div>
                <div class="ficha__linha">
                  <dt>Fazenda</dt>
                  <dd>{{ usina.data.value.fazenda?.nome ?? '—' }}</dd>
                </div>
                <div class="ficha__linha">
                  <dt>Cadastrada em</dt>
                  <dd>
                    {{ formatarData(usina.data.value.criadoEm) }} ·
                    {{ usina.data.value.criadoPor.nome }}
                  </dd>
                </div>
              </dl>
            </section>
          </div>
        </template>

        <div v-else-if="aba === 'ucs'" class="painel conteudo">
          <UsinaUcsAba :usina-id="id" :vincular-ao-abrir="route.query.vincular === '1'" />
        </div>

        <template v-else>
          <div class="painel conteudo">
            <h2 class="painel__titulo">Informações</h2>
            <dl class="ficha">
              <div class="ficha__linha">
                <dt>Nome da usina</dt>
                <dd>{{ usina.data.value.nome }}</dd>
              </div>
              <div class="ficha__linha">
                <dt>Identificador (gerado pelo sistema)</dt>
                <dd>{{ usina.data.value.codigo }}</dd>
              </div>
              <div class="ficha__linha">
                <dt>Fazenda</dt>
                <dd>
                  {{
                    usina.data.value.fazenda
                      ? `${usina.data.value.fazenda.codigo} · ${usina.data.value.fazenda.nome}`
                      : '—'
                  }}
                </dd>
              </div>
              <div class="ficha__linha">
                <dt>CEP</dt>
                <dd>{{ usina.data.value.cep || '—' }}</dd>
              </div>
              <div class="ficha__linha">
                <dt>Endereço</dt>
                <dd>{{ usina.data.value.endereco || '—' }}</dd>
              </div>
              <div class="ficha__linha">
                <dt>Cidade / UF</dt>
                <dd>{{ localizacao(usina.data.value.cidade, usina.data.value.uf) }}</dd>
              </div>
              <div class="ficha__linha">
                <dt>Observações</dt>
                <dd>{{ usina.data.value.observacoes || '—' }}</dd>
              </div>
              <div class="ficha__linha">
                <dt>Cadastrada por</dt>
                <dd>{{ usina.data.value.criadoPor.nome }}</dd>
              </div>
              <div class="ficha__linha">
                <dt>Última alteração por</dt>
                <dd>{{ usina.data.value.atualizadoPor.nome }}</dd>
              </div>
            </dl>
          </div>
          <div v-if="podeHistorico" class="painel conteudo">
            <HistoricoUsina :usina-id="id" />
          </div>
        </template>
      </section>
    </template>
  </main>
</template>

<style scoped>
/* Prancha "⚡ Usinas — Detalhe da usina": voltar, ícone de 52 px, nome de 32 px, ações à direita. */
.cabecalho-ficha {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 14px;
}

.voltar {
  display: inline-flex;
  color: var(--cor-texto);
}

.icone-ficha {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 52px;
  height: 52px;
  border-radius: 10px;
  background: var(--cor-destaque);
  color: #fff;
}

.identidade {
  flex: 1;
  min-width: 0;
}

h1 {
  margin: 0;
  font-size: 32px;
  font-weight: 700;
  line-height: 1.2;
}

.meta {
  margin: 0;
  color: var(--cor-texto-2);
  font-size: 15px;
}

.acoes-ficha {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
}

.abas {
  display: flex;
  gap: 26px;
  flex-wrap: wrap;
  border-bottom: 1px solid var(--cor-borda);
  margin: 12px 0 18px;
  padding: 0 12px;
}

.aba {
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
  border-radius: 0;
  color: var(--cor-texto);
  font-size: 16px;
  padding: 6px 0 8px;
}

.aba:hover {
  color: var(--cor-destaque-forte);
}

.aba[aria-selected='true'] {
  border-bottom-color: var(--cor-destaque);
  color: var(--cor-destaque-forte);
  font-weight: 600;
}

.resumo {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(13rem, 1fr));
  gap: 14px;
}

.resumo li {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 16px 18px;
}

.resumo .rotulo {
  color: var(--cor-texto-2);
  font-size: 13px;
}

.resumo strong {
  font-size: 26px;
  font-weight: 700;
}

.resumo .nota {
  color: var(--cor-texto-2);
  font-size: 12px;
}

.grade-visao {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 398px;
  gap: 18px;
  align-items: start;
}

.bloco {
  padding: 20px 18px 14px;
}

.topo-bloco {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.topo-bloco h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}

.tipo {
  display: block;
  font-size: 11px;
}

.bloco .ficha__linha {
  grid-template-columns: 138px minmax(0, 1fr);
  border-bottom: none;
  min-height: 32px;
}

.conteudo {
  padding: 20px;
}

.conteudo + .conteudo {
  margin-top: 18px;
}

@media (max-width: 80rem) {
  .grade-visao {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
