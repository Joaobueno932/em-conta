<script setup lang="ts">
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'

import {
  chavesAtendimento,
  iniciais,
  INTERVALO_MS,
  listarFila,
} from '@/features/atendimento/atendimento-api'
import { sair, temPermissao, useSessao } from '@/features/auth/auth-api'
import { describeApiError } from '@/shared/api/errors'
import logo from '@/shared/assets/logo-em-conta.png'
import simbolo from '@/shared/assets/logo-em-conta-simbolo.png'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'
import IconeTraco, { type NomeIcone } from '@/shared/components/IconeTraco.vue'

import { descartarSessao } from './guards'
import { useHttpClient } from './injection'

/**
 * Estrutura da área autenticada (frames AT-01 a AT-06): menu lateral com grupos, recolhível, e
 * cabeçalho com a conta. Só aparecem módulos já implementados e permitidos; os itens futuros do
 * protótipo (Funil, Financeiro, O&M, busca global, notificações…) não ganham links nem páginas.
 */
const http = useHttpClient()
const router = useRouter()
const route = useRoute()
const queryClient = useQueryClient()
const { data: usuario } = useSessao()
const pode = (p: string) => !!usuario.value && temPermissao(usuario.value, p)

interface Item {
  nome: string
  rotulo: string
  icone: NomeIcone
  ativo: (caminho: string) => boolean
}
/** Itens do menu com a permissão exigida (sem permissão, o item não aparece). */
const ITENS: (Item & { grupo: 'principal' | 'plataforma' | 'rodape'; permissao?: string })[] = [
  { grupo: 'principal', nome: 'inicio', rotulo: 'Painel', icone: 'casa', ativo: (c) => c === '/' },
  {
    grupo: 'principal',
    nome: 'atendimento',
    rotulo: 'Atendimento',
    icone: 'fone',
    permissao: 'atendimento:consultar',
    ativo: (c) => c.startsWith('/atendimento'),
  },
  {
    grupo: 'plataforma',
    nome: 'associados',
    rotulo: 'Associados',
    icone: 'pessoas',
    permissao: 'associados:consultar',
    // Ativo também na ficha, no cadastro e nas UCs (rotas irmãs, não filhas).
    ativo: (c) => c.startsWith('/associados'),
  },
  {
    grupo: 'plataforma',
    nome: 'usinas',
    rotulo: 'Usinas',
    icone: 'usina',
    permissao: 'usinas:consultar',
    ativo: (c) => /^\/(usinas|fazendas)/.test(c),
  },
  {
    grupo: 'rodape',
    nome: 'usuarios',
    rotulo: 'Usuários',
    icone: 'usuarios',
    permissao: 'usuarios:consultar',
    ativo: (c) => c.startsWith('/admin/usuarios'),
  },
]
const doGrupo = (grupo: string) =>
  computed(() => ITENS.filter((i) => i.grupo === grupo && (!i.permissao || pode(i.permissao))))
const principais = doGrupo('principal')
const plataforma = doGrupo('plataforma')
const rodape = doGrupo('rodape')

// Contador do Atendimento: conversas aguardando (o mesmo dado e a mesma consulta da fila).
const fila = useQuery({
  queryKey: chavesAtendimento.fila('AGUARDANDO', '', 0),
  queryFn: () => listarFila(http, 'AGUARDANDO', '', 0),
  enabled: computed(() => pode('atendimento:consultar')),
  refetchInterval: INTERVALO_MS,
})
const aguardando = computed(() => fila.data.value?.contagens.aguardando ?? 0)

// Menu recolhido: preferência só deste navegador (conveniência; a falta dela não muda nada).
const CHAVE = 'em-conta:menu-recolhido'
const lerPreferencia = () => {
  try {
    return localStorage.getItem(CHAVE) === '1'
  } catch {
    return false
  }
}
const recolhido = ref(lerPreferencia())
watch(recolhido, (v) => {
  try {
    localStorage.setItem(CHAVE, v ? '1' : '0')
  } catch {
    /* sem armazenamento: vale só nesta visita */
  }
})

// Menu da conta (Alterar senha, Sair): fecha com Esc ou clique fora e devolve o foco ao botão.
const menuConta = ref(false)
const areaConta = ref<HTMLElement | null>(null)
const botaoConta = ref<HTMLButtonElement | null>(null)
function fecharConta(devolverFoco = false) {
  if (!menuConta.value) return
  menuConta.value = false
  if (devolverFoco) void nextTick(() => botaoConta.value?.focus())
}
function cliqueFora(evento: MouseEvent) {
  if (menuConta.value && areaConta.value && !areaConta.value.contains(evento.target as Node))
    fecharConta()
}
onMounted(() => document.addEventListener('click', cliqueFora))
onBeforeUnmount(() => document.removeEventListener('click', cliqueFora))
watch(
  () => route.fullPath,
  () => fecharConta(),
)

const saindo = ref(false)
const erroSaida = ref<string | null>(null)

async function encerrar() {
  saindo.value = true
  erroSaida.value = null
  try {
    await sair(http)
    // Nenhum dado da sessão anterior permanece em cache para o próximo usuário.
    descartarSessao(queryClient)
    await router.replace({ name: 'entrar', query: { motivo: 'saiu' } })
  } catch (error) {
    erroSaida.value = describeApiError(error)
  } finally {
    saindo.value = false
  }
}
</script>

<template>
  <div v-if="usuario" class="estrutura" :class="{ recolhido }">
    <aside id="menu-lateral" class="lateral">
      <RouterLink :to="{ name: 'inicio' }" class="marca" aria-label="Em Conta — Painel">
        <img class="logo-completo" :src="logo" alt="" width="220" height="55" />
        <img class="logo-simbolo" :src="simbolo" alt="" width="70" height="48" />
      </RouterLink>
      <nav class="navegacao" aria-label="Principal">
        <ul class="grupo">
          <li v-for="i in principais" :key="i.nome">
            <RouterLink
              :to="{ name: i.nome }"
              class="item"
              :class="{ ativo: i.ativo(route.path) }"
              :aria-current="i.ativo(route.path) ? 'page' : undefined"
              :aria-describedby="
                i.nome === 'atendimento' && aguardando ? 'contagem-aguardando' : undefined
              "
              :title="recolhido ? i.rotulo : undefined"
            >
              <IconeTraco :nome="i.icone" :tamanho="20" />
              <span class="rotulo">{{ i.rotulo }}</span>
              <span
                v-if="i.nome === 'atendimento' && aguardando"
                class="contador"
                aria-hidden="true"
                >{{ aguardando }}</span
              >
            </RouterLink>
          </li>
        </ul>
        <span v-if="aguardando" id="contagem-aguardando" class="visualmente-oculto"
          >Conversas na fila: {{ aguardando }}</span
        >
        <template v-if="plataforma.length">
          <p id="grupo-plataforma" class="titulo-grupo">Plataforma</p>
          <ul class="grupo" aria-labelledby="grupo-plataforma">
            <li v-for="i in plataforma" :key="i.nome">
              <RouterLink
                :to="{ name: i.nome }"
                class="item"
                :class="{ ativo: i.ativo(route.path) }"
                :aria-current="i.ativo(route.path) ? 'page' : undefined"
                :title="recolhido ? i.rotulo : undefined"
              >
                <IconeTraco :nome="i.icone" :tamanho="20" />
                <span class="rotulo">{{ i.rotulo }}</span>
              </RouterLink>
            </li>
          </ul>
        </template>
        <ul v-if="rodape.length" class="grupo grupo--rodape">
          <li v-for="i in rodape" :key="i.nome">
            <RouterLink
              :to="{ name: i.nome }"
              class="item"
              :class="{ ativo: i.ativo(route.path) }"
              :aria-current="i.ativo(route.path) ? 'page' : undefined"
              :title="recolhido ? i.rotulo : undefined"
            >
              <IconeTraco :nome="i.icone" :tamanho="20" />
              <span class="rotulo">{{ i.rotulo }}</span>
            </RouterLink>
          </li>
        </ul>
      </nav>
    </aside>

    <div class="principal">
      <!-- Curvas decorativas do cabeçalho (AT-01..AT-06), redesenhadas a partir dos exports: o asset
           vetorial não veio nos arquivos. Coordenadas do frame (x 1060–1672, y 0–178). -->
      <svg
        class="curvas"
        viewBox="1060 0 612 178"
        width="612"
        height="178"
        aria-hidden="true"
        focusable="false"
      >
        <path
          class="faixa"
          d="M1062 0C1100 0 1130 22 1160 34C1230 58 1330 72 1420 92C1500 110 1580 135 1672 178H1588C1500 152 1420 132 1330 113C1240 95 1170 78 1130 62C1105 52 1088 40 1072 20Z"
        />
        <path class="linha" d="M1452 28C1490 46 1520 56 1545 49C1590 36 1630 38 1672 58" />
        <path class="linha" d="M1458 76C1500 60 1520 104 1560 98C1600 90 1640 90 1672 104" />
        <path class="linha" d="M1265 178C1300 165 1340 140 1385 122" />
        <path class="linha" d="M1410 168C1450 148 1590 128 1672 146" />
      </svg>
      <header class="topo">
        <button
          type="button"
          class="recolher"
          aria-controls="menu-lateral"
          :aria-expanded="!recolhido"
          :aria-label="recolhido ? 'Expandir menu' : 'Recolher menu'"
          :title="recolhido ? 'Expandir menu' : 'Recolher menu'"
          @click="recolhido = !recolhido"
        >
          <IconeTraco :nome="recolhido ? 'expandir' : 'recolher'" :tamanho="20" />
        </button>
        <div ref="areaConta" class="conta" @keydown.esc="fecharConta(true)">
          <button
            ref="botaoConta"
            type="button"
            class="botao-conta"
            aria-haspopup="true"
            :aria-expanded="menuConta"
            aria-controls="menu-conta"
            @click="menuConta = !menuConta"
          >
            <span class="avatar" aria-hidden="true">{{ iniciais(usuario.nome) }}</span>
            <span class="nome">{{ usuario.nome }}</span>
            <span class="visualmente-oculto">: menu da conta</span>
            <IconeTraco :nome="menuConta ? 'seta-acima' : 'seta-abaixo'" :tamanho="18" />
          </button>
          <ul v-if="menuConta" id="menu-conta" class="menu-conta">
            <li>
              <RouterLink :to="{ name: 'alterar-senha' }" class="opcao">
                <IconeTraco nome="cadeado" />
                Alterar senha
              </RouterLink>
            </li>
            <li>
              <button type="button" class="opcao" :disabled="saindo" @click="encerrar">
                <IconeTraco nome="sair" />
                {{ saindo ? 'Saindo…' : 'Sair' }}
              </button>
            </li>
          </ul>
        </div>
      </header>
      <AlertaMensagem v-if="erroSaida" tipo="erro">{{ erroSaida }}</AlertaMensagem>
      <div class="conteudo">
        <RouterView />
      </div>
    </div>
  </div>
</template>

<style scoped>
/* Medidas do frame (1672 × 941): menu de 294 px, cabeçalho de 72 px + borda, conteúdo com 15 px. */
.estrutura {
  display: grid;
  grid-template-columns: var(--largura-lateral) minmax(0, 1fr);
  height: 100vh;
  height: 100dvh;
}

.estrutura.recolhido {
  grid-template-columns: var(--largura-lateral-recolhida) minmax(0, 1fr);
}

.lateral {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow-y: auto;
  background: var(--cor-superficie);
  border-right: 1px solid var(--cor-borda);
  padding: 0 14px 8px 28px;
}

.recolhido .lateral {
  padding: 0 12px 8px;
}

.marca {
  display: flex;
  align-items: center;
  height: 55px;
  margin: 39px 0 31px 6px;
}

.recolhido .marca {
  justify-content: center;
  margin-left: 0;
}

.marca img {
  display: block;
}

.logo-simbolo,
.recolhido .logo-completo {
  display: none !important;
}

.recolhido .logo-simbolo {
  display: block !important;
}

.navegacao {
  display: flex;
  flex: 1;
  flex-direction: column;
}

.grupo {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.grupo--rodape {
  margin-top: auto;
  padding-top: 16px;
  border-top: 1px solid var(--cor-borda);
}

.titulo-grupo {
  margin: 27px 0 12px 13px;
  color: var(--cor-texto-3);
  font-size: 15px;
  font-weight: 500;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  line-height: 1.2;
}

.recolhido .titulo-grupo {
  margin: 20px 0 8px;
  text-align: center;
  font-size: 0;
  border-top: 1px solid var(--cor-borda);
}

.item {
  display: flex;
  align-items: center;
  gap: 16px;
  height: 50px;
  padding: 0 18px;
  border: 1px solid transparent;
  border-radius: var(--raio-controle);
  color: var(--cor-texto);
  font-size: 18px;
  text-decoration: none;
}

.item:hover {
  background: var(--cor-superficie-2);
}

.item.ativo {
  background: var(--cor-destaque-suave);
  border-color: var(--cor-destaque-borda);
  color: var(--cor-destaque-forte);
  font-weight: 600;
}

.item.ativo .icone {
  color: var(--cor-destaque);
}

.item .rotulo {
  flex: 1;
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.contador {
  font-size: 16px;
  font-weight: 600;
  color: var(--cor-destaque-forte);
}

.recolhido .item {
  justify-content: center;
  padding: 0;
  position: relative;
}

/* Rótulo oculto só visualmente: o link mantém o nome acessível. */
.recolhido .item .rotulo {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
}

.recolhido .contador {
  position: absolute;
  top: 4px;
  right: 6px;
  font-size: 12px;
}

.principal {
  position: relative;
  isolation: isolate;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

/* Fica atrás do cabeçalho e do título; os cartões brancos cobrem o restante. */
.curvas {
  position: absolute;
  top: 0;
  right: 0;
  z-index: -1;
  pointer-events: none;
}

.curvas .faixa {
  fill: #ebf5ed;
}

.curvas .linha {
  fill: none;
  stroke: var(--cor-destaque-suave);
  stroke-width: 2;
}

.topo {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-shrink: 0;
  height: 72px;
  padding: 0 32px 0 27px;
  border-bottom: 1px solid var(--cor-borda);
}

button.recolher {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 42px;
  height: 42px;
  padding: 0;
  border: 1px solid var(--cor-borda);
  border-radius: 999px;
  background: var(--cor-superficie);
  color: var(--cor-texto);
}

button.recolher:hover {
  background: var(--cor-superficie-2);
}

.conta {
  position: relative;
}

button.botao-conta {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  padding: 4px 6px 4px 4px;
  border: none;
  border-radius: 999px;
  background: transparent;
  color: var(--cor-texto);
  font-size: 18px;
  font-weight: 600;
}

button.botao-conta:hover {
  background: var(--cor-superficie-2);
}

button.botao-conta .icone {
  color: var(--cor-texto-2);
}

.avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 999px;
  background: var(--cor-avatar);
  color: #fff;
  font-size: 17px;
  font-weight: 500;
}

.menu-conta {
  position: absolute;
  right: 0;
  top: calc(100% + 6px);
  z-index: 10;
  min-width: 13rem;
  list-style: none;
  margin: 0;
  padding: 6px;
  background: var(--cor-superficie);
  border: 1px solid var(--cor-borda);
  border-radius: var(--raio-cartao);
  box-shadow: var(--sombra-menu);
}

.opcao {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 10px 12px;
  border: none;
  border-radius: 8px;
  background: none;
  color: var(--cor-texto);
  font-size: 16px;
  text-align: left;
  text-decoration: none;
}

.opcao:hover {
  background: var(--cor-superficie-2);
}

.conteudo {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

/* Telas menores: o menu ocupa só os ícones (preferência do usuário vale acima de 64rem). */
@media (max-width: 64rem) {
  .estrutura,
  .estrutura.recolhido {
    grid-template-columns: var(--largura-lateral-recolhida) minmax(0, 1fr);
  }

  .lateral {
    padding: 0 12px 8px;
  }

  .marca {
    justify-content: center;
    margin-left: 0;
  }

  .logo-completo {
    display: none !important;
  }

  .logo-simbolo {
    display: block !important;
  }

  .titulo-grupo {
    margin: 20px 0 8px;
    font-size: 0;
    border-top: 1px solid var(--cor-borda);
  }

  .item {
    justify-content: center;
    padding: 0;
    position: relative;
  }

  .item .rotulo {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip: rect(0 0 0 0);
  }

  .contador {
    position: absolute;
    top: 4px;
    right: 6px;
    font-size: 12px;
  }

  button.recolher {
    display: none;
  }

  .topo {
    justify-content: flex-end;
    padding: 0 16px;
  }

  button.botao-conta .nome {
    display: none;
  }
}

/* Celular: a navegação vira uma faixa no topo, para o conteúdo usar a largura toda. */
@media (max-width: 40rem) {
  .estrutura,
  .estrutura.recolhido {
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: auto minmax(0, 1fr);
  }

  .lateral {
    flex-direction: row;
    align-items: center;
    padding: 4px 8px;
    border-right: none;
    border-bottom: 1px solid var(--cor-borda);
    overflow: auto hidden;
  }

  .marca {
    height: 48px;
    margin: 0 8px 0 0;
  }

  .logo-simbolo {
    width: 52px;
    height: auto;
  }

  .navegacao,
  .grupo {
    flex-direction: row;
    align-items: center;
  }

  .navegacao {
    flex: 1;
    gap: 2px;
  }

  .titulo-grupo {
    display: none;
  }

  .grupo--rodape {
    margin: 0 0 0 auto;
    padding: 0;
    border: none;
  }

  .item {
    width: 48px;
    height: 48px;
  }

  .topo {
    height: 56px;
  }

  .curvas {
    display: none;
  }
}
</style>
