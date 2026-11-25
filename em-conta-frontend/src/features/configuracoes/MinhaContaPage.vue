<script setup lang="ts">
import { useQueryClient } from '@tanstack/vue-query'
import { computed, nextTick, ref } from 'vue'
import { RouterLink } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { iniciais } from '@/features/atendimento/atendimento-api'
import { chaveSessao, temPermissao, useSessao } from '@/features/auth/auth-api'
import { chaveUsuarios, editarNome, rotuloPerfis } from '@/features/usuarios/usuarios-api'
import { describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'
import IconeTraco from '@/shared/components/IconeTraco.vue'
import { avisar } from '@/shared/components/use-avisos'

/**
 * Minha conta (prancha "⚙️ Configurações", quadros 1 a 5 e 9): dados de acesso do usuário da sessão.
 * Só o nome é editável, e só por quem tem `usuarios:editar` (a API não tem edição da própria conta
 * para os demais perfis). Telefone e foto do protótipo não existem no cadastro de acesso.
 */
const http = useHttpClient()
const queryClient = useQueryClient()
const { data: usuario } = useSessao()

const NOME_MAXIMO = 150
const podeEditar = computed(() => temPermissao(usuario.value, 'usuarios:editar'))
const perfil = computed(() =>
  usuario.value?.perfis.length ? rotuloPerfis(usuario.value.perfis) : 'Sem perfil atribuído',
)

const editando = ref(false)
const nome = ref('')
const salvando = ref(false)
const erroCampo = ref<string | null>(null)
const erroApi = ref<string | null>(null)
const campoNome = ref<HTMLInputElement | null>(null)
const botaoEditar = ref<HTMLButtonElement | null>(null)

function editar() {
  nome.value = usuario.value?.nome ?? ''
  erroCampo.value = null
  erroApi.value = null
  editando.value = true
  void nextTick(() => campoNome.value?.focus())
}

function cancelar() {
  editando.value = false
  erroCampo.value = null
  erroApi.value = null
  void nextTick(() => botaoEditar.value?.focus())
}

async function salvar() {
  if (!usuario.value) return
  const valor = nome.value.trim()
  erroCampo.value = valor ? null : 'Informe o nome.'
  if (erroCampo.value) {
    erroApi.value = null
    void nextTick(() => campoNome.value?.focus())
    return
  }
  salvando.value = true
  erroApi.value = null
  try {
    await editarNome(http, usuario.value.id, valor)
    await queryClient.invalidateQueries({ queryKey: chaveSessao })
    void queryClient.invalidateQueries({ queryKey: chaveUsuarios })
    editando.value = false
    avisar('Dados atualizados', 'Nome salvo.')
    void nextTick(() => botaoEditar.value?.focus())
  } catch (error) {
    erroApi.value = describeApiError(error)
  } finally {
    salvando.value = false
  }
}
</script>

<template>
  <template v-if="usuario">
    <AlertaMensagem v-if="erroCampo" tipo="erro" class="banner"
      >Corrija 1 campo para salvar.</AlertaMensagem
    >
    <AlertaMensagem v-else-if="erroApi" tipo="erro" class="banner">
      Não foi possível salvar agora. Seus dados foram mantidos — tente novamente. ({{ erroApi }})
    </AlertaMensagem>

    <section class="painel" aria-labelledby="titulo-conta">
      <div class="topo-painel">
        <div>
          <h2 id="titulo-conta" class="painel__titulo">Minha conta</h2>
          <p class="painel__subtitulo">Seus dados de acesso</p>
        </div>
        <button
          v-if="podeEditar && !editando"
          ref="botaoEditar"
          type="button"
          class="botao-contorno"
          @click="editar"
        >
          <IconeTraco nome="lapis" :tamanho="18" />
          Editar
        </button>
      </div>

      <div class="identidade">
        <span class="avatar-grande" aria-hidden="true">{{ iniciais(usuario.nome) }}</span>
        <div>
          <strong class="nome">{{ usuario.nome }}</strong>
          <span class="perfil">{{ perfil }}</span>
        </div>
      </div>

      <form v-if="editando" novalidate @submit.prevent="salvar">
        <div class="campo campo-nome">
          <label for="conta-nome">Nome <span class="obrigatorio" aria-hidden="true">*</span></label>
          <input
            id="conta-nome"
            ref="campoNome"
            v-model="nome"
            type="text"
            autocomplete="name"
            :maxlength="NOME_MAXIMO"
            aria-required="true"
            :aria-invalid="!!erroCampo"
            :aria-describedby="erroCampo ? 'conta-nome-erro' : undefined"
          />
          <p v-if="erroCampo" id="conta-nome-erro" class="ajuda ajuda--erro">{{ erroCampo }}</p>
        </div>
        <dl class="linhas">
          <div class="linha">
            <dt>E-mail de acesso</dt>
            <dd>
              <IconeTraco nome="cadeado" :tamanho="13" />
              <span>
                {{ usuario.email }}
                <small>Alterado somente pelo cadastro de acesso</small>
              </span>
            </dd>
          </div>
          <div class="linha">
            <dt>Perfil de acesso</dt>
            <dd>
              <IconeTraco nome="cadeado" :tamanho="13" />
              <span>
                {{ perfil }}
                <small>Você não pode alterar o próprio perfil</small>
              </span>
            </dd>
          </div>
        </dl>
        <div class="acoes acoes--fim">
          <button type="button" class="botao-contorno" :disabled="salvando" @click="cancelar">
            Cancelar
          </button>
          <button type="submit" class="botao-primario" :disabled="salvando">
            <IconeTraco :nome="erroApi ? 'historico' : 'concluir'" :tamanho="18" />
            {{ salvando ? 'Salvando…' : erroApi ? 'Tentar novamente' : 'Salvar alterações' }}
          </button>
        </div>
      </form>

      <dl v-else class="linhas">
        <div class="linha">
          <dt>Nome</dt>
          <dd>{{ usuario.nome }}</dd>
        </div>
        <div class="linha">
          <dt>E-mail de acesso</dt>
          <dd>
            <IconeTraco nome="cadeado" :tamanho="13" />
            <span>
              {{ usuario.email }}
              <small>Alterado somente pelo cadastro de acesso</small>
            </span>
          </dd>
        </div>
        <div class="linha">
          <dt>Perfil de acesso</dt>
          <dd>
            <IconeTraco nome="cadeado" :tamanho="13" />
            <span>
              {{ perfil }}
              <small>Somente leitura — definido por um administrador</small>
            </span>
          </dd>
        </div>
      </dl>
    </section>

    <section class="painel" aria-labelledby="titulo-seguranca">
      <div class="topo-painel">
        <div>
          <h2 id="titulo-seguranca" class="painel__titulo">Segurança</h2>
          <p class="painel__subtitulo">Senha de acesso</p>
        </div>
        <RouterLink :to="{ name: 'alterar-senha' }" class="botao-contorno">
          <IconeTraco nome="chave" :tamanho="18" />
          Alterar senha
        </RouterLink>
      </div>
      <dl class="linhas">
        <div class="linha linha--sem-divisoria">
          <dt>Senha</dt>
          <dd><span aria-label="Senha definida">••••••••••</span></dd>
        </div>
      </dl>
    </section>
  </template>
</template>

<style scoped>
.banner {
  margin: 0 0 16px;
}

.painel {
  padding: 20px 22px 12px;
}

.painel + .painel {
  margin-top: 16px;
}

.topo-painel {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}

.identidade {
  display: flex;
  align-items: center;
  gap: 16px;
  margin: 10px 0 10px;
}

.avatar-grande {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 90px;
  height: 90px;
  border-radius: 999px;
  background: var(--cor-destaque-suave);
  color: var(--cor-destaque-forte);
  font-size: 30px;
  font-weight: 500;
}

.nome {
  display: block;
  font-size: 18px;
  font-weight: 600;
}

.perfil {
  color: var(--cor-texto-2);
  font-size: 13px;
}

.linhas {
  margin: 0;
}

.linha {
  display: grid;
  grid-template-columns: 238px minmax(0, 1fr);
  align-items: center;
  min-height: 41px;
  padding: 3px 0;
  border-bottom: 1px solid var(--cor-divisoria);
}

.linha:last-child,
.linha--sem-divisoria {
  border-bottom: none;
}

.linha dt {
  color: var(--cor-texto-2);
  font-size: 13px;
  font-weight: 400;
}

.linha dd {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin: 0;
  font-size: 14px;
  line-height: 20px;
}

.linha dd .icone {
  margin-top: 4px;
  color: var(--cor-texto-3);
}

.linha small {
  display: block;
  color: var(--cor-texto-3);
  font-size: 11px;
  line-height: 14px;
}

.campo-nome {
  max-width: 482px;
  margin-bottom: 8px;
}

.campo-nome input {
  height: 42px;
  border-color: var(--cor-borda);
}

.acoes {
  margin-top: 16px;
}

@media (max-width: 40rem) {
  .linha {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
