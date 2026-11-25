<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'

import { temPermissao, useSessao } from '@/features/auth/auth-api'
import CabecalhoPagina from '@/shared/components/CabecalhoPagina.vue'
import IconeTraco, { type NomeIcone } from '@/shared/components/IconeTraco.vue'

/**
 * Configurações (prancha "⚙️ Configurações"): menu de seções à esquerda e a seção à direita. Só
 * aparecem as seções que existem no sistema e que o perfil pode ver. Empresa, preferências de
 * notificações, parâmetros, auditoria e atalhos ainda não existem e não ganham itens.
 */
const route = useRoute()
const { data: sessao } = useSessao()

interface Secao {
  nome: string
  rotulo: string
  icone: NomeIcone
  rotas: string[]
  permissao?: string
}
const SECOES: Secao[] = [
  {
    nome: 'minha-conta',
    rotulo: 'Minha conta',
    icone: 'pessoa',
    rotas: ['minha-conta', 'alterar-senha'],
  },
  {
    nome: 'usuarios',
    rotulo: 'Usuários e permissões',
    icone: 'pessoas',
    rotas: ['usuarios', 'usuario'],
    permissao: 'usuarios:consultar',
  },
]
const secoes = computed(() =>
  SECOES.filter((s) => !s.permissao || temPermissao(sessao.value, s.permissao)),
)
const administrativo = computed(() => secoes.value.length > 1)
const ativa = (s: Secao) => s.rotas.includes(String(route.name))
</script>

<template>
  <main class="pagina-modulo">
    <CabecalhoPagina
      titulo="Configurações"
      :subtitulo="
        administrativo ? 'Conta, usuários e permissões autorizados.' : 'Sua conta de acesso.'
      "
    />
    <div class="configuracoes">
      <nav class="secoes" aria-label="Seções de configurações">
        <ul>
          <li v-for="s in secoes" :key="s.nome">
            <RouterLink
              :to="{ name: s.nome }"
              class="secao"
              :class="{ ativa: ativa(s) }"
              :aria-current="ativa(s) ? 'page' : undefined"
            >
              <IconeTraco :nome="s.icone" :tamanho="17" />
              {{ s.rotulo }}
            </RouterLink>
          </li>
        </ul>
        <p v-if="!administrativo" class="nota">
          Seções administrativas não aparecem para o seu perfil.
        </p>
      </nav>
      <div class="secao-atual">
        <RouterView />
      </div>
    </div>
  </main>
</template>

<style scoped>
/* Prancha: menu de seções de 268 px, 21 px até o cartão da seção. */
.configuracoes {
  display: grid;
  grid-template-columns: 268px minmax(0, 1fr);
  gap: 21px;
  align-items: start;
}

.secoes {
  padding: 8px;
  background: var(--cor-superficie);
  border: 1px solid var(--cor-borda);
  border-radius: var(--raio-cartao);
}

.secoes ul {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.secao {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 34px;
  padding: 0 12px;
  border-radius: 8px;
  color: var(--cor-texto);
  font-size: 14px;
  text-decoration: none;
}

.secao .icone {
  color: var(--cor-texto-2);
}

.secao:hover {
  background: var(--cor-superficie-2);
}

.secao.ativa {
  background: var(--cor-destaque-suave);
  color: var(--cor-destaque-forte);
  font-weight: 600;
}

.secao.ativa .icone {
  color: var(--cor-destaque-forte);
}

.nota {
  margin: 8px 0 0;
  padding: 8px 12px;
  border-radius: 8px;
  background: var(--cor-superficie-2);
  color: var(--cor-texto-2);
  font-size: 11px;
}

.secao-atual {
  min-width: 0;
}

@media (max-width: 60rem) {
  .configuracoes {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
