<script setup lang="ts">
import { useQueryClient } from '@tanstack/vue-query'
import { ref } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'

import { sair, temPermissao, useSessao } from '@/features/auth/auth-api'
import { describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'

import { descartarSessao } from './guards'
import { useHttpClient } from './injection'

const http = useHttpClient()
const router = useRouter()
const route = useRoute()
const queryClient = useQueryClient()
const { data: usuario } = useSessao()

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
  <!-- Estrutura do protótipo (menu lateral + cabeçalho); só aparecem módulos já implementados. -->
  <div v-if="usuario" class="estrutura">
    <aside class="lateral">
      <p class="marca">
        <strong>Em Conta</strong>
        <span>Gestão de energia</span>
      </p>
      <nav aria-label="Principal">
        <ul>
          <li>
            <RouterLink :to="{ name: 'inicio' }" exact-active-class="ativo">Início</RouterLink>
          </li>
          <li v-if="temPermissao(usuario, 'associados:consultar')">
            <!-- Ativo também na ficha, no cadastro e nas UCs (rotas irmãs, não filhas). -->
            <RouterLink
              :to="{ name: 'associados' }"
              :class="{ ativo: route.path.startsWith('/associados') }"
            >
              Associados
            </RouterLink>
          </li>
          <li v-if="temPermissao(usuario, 'usinas:consultar')">
            <RouterLink
              :to="{ name: 'usinas' }"
              :class="{ ativo: /^\/(usinas|fazendas)/.test(route.path) }"
            >
              Usinas
            </RouterLink>
          </li>
          <li v-if="temPermissao(usuario, 'usuarios:consultar')">
            <RouterLink :to="{ name: 'usuarios' }" active-class="ativo">Usuários</RouterLink>
          </li>
          <li>
            <RouterLink :to="{ name: 'alterar-senha' }" active-class="ativo">
              Alterar senha
            </RouterLink>
          </li>
        </ul>
      </nav>
    </aside>
    <div class="conteudo">
      <header class="cabecalho">
        <span class="usuario">{{ usuario.nome }}</span>
        <button type="button" class="secundario" :disabled="saindo" @click="encerrar">
          {{ saindo ? 'Saindo…' : 'Sair' }}
        </button>
      </header>
      <AlertaMensagem v-if="erroSaida" tipo="erro">{{ erroSaida }}</AlertaMensagem>
      <RouterView />
    </div>
  </div>
</template>

<style scoped>
.estrutura {
  display: grid;
  grid-template-columns: 15rem 1fr;
  min-height: 100vh;
}

.lateral {
  background: var(--cor-superficie);
  border-right: 1px solid var(--cor-borda);
  padding: 1.5rem 1rem;
}

.marca {
  display: flex;
  flex-direction: column;
  margin: 0 0 2rem 0.5rem;
}

.marca strong {
  color: var(--cor-destaque-forte);
  font-size: 1.5rem;
  line-height: 1.1;
}

.marca span {
  color: var(--cor-texto-2);
  font-size: 0.8rem;
}

.lateral ul {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.lateral a {
  display: block;
  padding: 0.5rem 0.75rem;
  border-radius: var(--raio);
  color: var(--cor-texto);
  text-decoration: none;
}

.lateral a.ativo {
  background: var(--cor-destaque-suave);
  border: 1px solid var(--cor-destaque-borda);
  color: var(--cor-destaque-forte);
  font-weight: 600;
}

.conteudo {
  min-width: 0;
}

.cabecalho {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 1rem;
  padding: 0.75rem 1.5rem;
}

.usuario {
  font-weight: 600;
}

@media (max-width: 48rem) {
  .estrutura {
    grid-template-columns: 1fr;
  }

  .lateral {
    border-right: none;
    border-bottom: 1px solid var(--cor-borda);
    padding: 1rem;
  }

  .marca {
    margin-bottom: 0.75rem;
  }

  .lateral ul {
    flex-direction: row;
    flex-wrap: wrap;
  }
}
</style>
