<script setup lang="ts">
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { camposDoErro } from '@/shared/api/campos'
import { codigoDoErro, describeApiError } from '@/shared/api/errors'
import { ApiError } from '@/shared/api/http-client'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'

import {
  AVISO_VINCULO_CADASTRAL,
  buscarUcsCandidatas,
  chavesUsinas,
  formatarDataNegocio,
  hojeIso,
  vincularUc,
  type UcCandidata,
} from './usinas-api'
import { invalidarAposVinculo } from './vinculos-cache'

/**
 * Vincular UC existente (OV-U09/U10/U11): buscar, conferir titular e vínculo atual, informar o início
 * e confirmar. UC já vinculada a outra usina não pode ser selecionada; nada é sobrescrito.
 */
const props = defineProps<{ usinaId: number }>()
const emit = defineEmits<{ concluido: []; cancelado: [] }>()

const http = useHttpClient()
const queryClient = useQueryClient()

const termo = ref('')
const buscado = ref('')
const selecionada = ref<UcCandidata | null>(null)
const inicio = ref(hojeIso())
const campoBusca = ref<HTMLInputElement | null>(null)
onMounted(() => campoBusca.value?.focus())

const candidatas = useQuery({
  queryKey: computed(() => chavesUsinas.candidatas(buscado.value)),
  queryFn: () => buscarUcsCandidatas(http, buscado.value),
  enabled: computed(() => buscado.value.trim().length >= 2),
})

function buscar() {
  selecionada.value = null
  buscado.value = termo.value.trim()
}

const vincular = useMutation({
  mutationFn: () =>
    vincularUc(http, props.usinaId, (selecionada.value as UcCandidata).uc.id, inicio.value),
  onSuccess: async (vinculo) => {
    emit('concluido')
    await invalidarAposVinculo(queryClient, props.usinaId, vinculo.uc.associadoId)
  },
})

const erros = computed(() => camposDoErro(vincular.error.value))
const codigo = computed(() => codigoDoErro(vincular.error.value))
const vinculoAtual = computed(() => {
  const erro = vincular.error.value
  if (!(erro instanceof ApiError)) return null
  const p = erro.problem
  return typeof p?.usinaAtualNome === 'string'
    ? { codigo: String(p.usinaAtualCodigo), nome: p.usinaAtualNome, desde: String(p.vigenteDesde) }
    : null
})

function bloqueada(c: UcCandidata) {
  return c.vinculoVigente != null
}

function confirmar() {
  if (!selecionada.value || vincular.isPending.value) return
  vincular.mutate()
}
</script>

<template>
  <section class="painel" aria-labelledby="titulo-vincular">
    <h3 id="titulo-vincular">Vincular unidade consumidora</h3>
    <p class="ajuda">Selecione uma UC já cadastrada na ficha de um associado.</p>

    <form class="acoes" role="search" @submit.prevent="buscar">
      <label for="busca-uc" class="visualmente-oculto">Buscar UC</label>
      <input
        id="busca-uc"
        ref="campoBusca"
        v-model="termo"
        type="search"
        maxlength="100"
        placeholder="Nº da UC, nome do titular ou CPF/CNPJ…"
      />
      <button type="submit" class="secundario" :disabled="termo.trim().length < 2">
        Buscar UC
      </button>
    </form>

    <p v-if="candidatas.isFetching.value && !candidatas.data.value" role="status">Buscando…</p>
    <AlertaMensagem v-else-if="candidatas.isError.value" tipo="erro">
      {{ describeApiError(candidatas.error.value) }}
    </AlertaMensagem>
    <p
      v-else-if="candidatas.data.value && candidatas.data.value.length === 0"
      class="ajuda"
      role="status"
    >
      Nenhuma UC encontrada para “{{ buscado }}”.
    </p>
    <fieldset v-else-if="candidatas.data.value" class="opcoes">
      <legend class="visualmente-oculto">UCs encontradas</legend>
      <label
        v-for="c in candidatas.data.value"
        :key="c.uc.id"
        class="opcao"
        :class="{
          'opcao--marcada': selecionada?.uc.id === c.uc.id,
          'opcao--bloqueada': bloqueada(c),
        }"
      >
        <input
          type="radio"
          name="uc"
          :value="c.uc.id"
          :checked="selecionada?.uc.id === c.uc.id"
          :disabled="bloqueada(c)"
          @change="selecionada = c"
        />
        <span>
          <strong>UC {{ c.uc.numero }}</strong> · {{ c.uc.associadoNome }}
          <span class="ajuda">
            {{ c.uc.concessionariaNome }}
            <template v-if="c.uc.denominacao"> · {{ c.uc.denominacao }}</template>
            · {{ c.uc.associadoTipo }} {{ c.uc.associadoDocumentoMascarado }}
          </span>
          <span v-if="c.vinculoVigente" class="ajuda aviso">
            {{
              c.vinculoVigente.usina.id === usinaId
                ? 'Já vinculada a esta usina'
                : 'Vinculada a outra usina'
            }}: {{ c.vinculoVigente.usina.codigo }} · {{ c.vinculoVigente.usina.nome }} desde
            {{ formatarDataNegocio(c.vinculoVigente.inicio) }}
          </span>
        </span>
      </label>
    </fieldset>

    <form v-if="selecionada" class="conferir" novalidate @submit.prevent="confirmar">
      <h4>Conferir e confirmar</h4>
      <p>
        UC <strong>{{ selecionada.uc.numero }}</strong> ({{ selecionada.uc.concessionariaNome }}),
        titular <strong>{{ selecionada.uc.associadoNome }}</strong
        >.
      </p>
      <div class="campo">
        <label for="vinculo-inicio"
          >Início do vínculo<span class="obrigatorio" aria-hidden="true"> *</span></label
        >
        <input
          id="vinculo-inicio"
          v-model="inicio"
          type="date"
          required
          :max="hojeIso()"
          :aria-invalid="erros.inicio ? 'true' : undefined"
          :aria-describedby="erros.inicio ? 'vinculo-inicio-erro' : 'vinculo-inicio-ajuda'"
        />
        <p id="vinculo-inicio-ajuda" class="ajuda">Pode ser retroativo; não pode ser futuro.</p>
        <p v-if="erros.inicio" id="vinculo-inicio-erro" class="ajuda ajuda--erro">
          {{ erros.inicio }}
        </p>
      </div>
      <AlertaMensagem
        v-if="
          codigo === 'UC_VINCULADA_A_OUTRA_USINA' ||
          codigo === 'VINCULO_JA_EXISTE' ||
          codigo === 'UC_JA_VINCULADA'
        "
        tipo="aviso"
      >
        {{ describeApiError(vincular.error.value) }}
        <template v-if="vinculoAtual">
          Vínculo atual: {{ vinculoAtual.codigo }} · {{ vinculoAtual.nome }}, desde
          {{ formatarDataNegocio(vinculoAtual.desde) }}.
        </template>
        A regra para transferência ou múltiplos vínculos simultâneos não permite sobrescrever.
        <RouterLink
          :to="{
            name: 'associado',
            params: { id: selecionada.uc.associadoId },
            query: { aba: 'ucs' },
          }"
        >
          Ver UC
        </RouterLink>
      </AlertaMensagem>
      <AlertaMensagem v-else-if="vincular.isError.value && !erros.inicio" tipo="erro">
        {{ describeApiError(vincular.error.value) }}
      </AlertaMensagem>
      <p class="ajuda">{{ AVISO_VINCULO_CADASTRAL }}</p>
      <div class="acoes">
        <button type="submit" :disabled="vincular.isPending.value">
          {{ vincular.isPending.value ? 'Vinculando…' : 'Vincular UC' }}
        </button>
        <button type="button" class="secundario" @click="emit('cancelado')">Cancelar</button>
      </div>
    </form>
    <div v-else class="acoes">
      <button type="button" class="secundario" @click="emit('cancelado')">Cancelar</button>
    </div>
  </section>
</template>

<style scoped>
.painel {
  border: 1px solid var(--cor-destaque-borda);
  border-radius: var(--raio);
  padding: 1rem;
  margin: 1rem 0;
}

.painel input[type='search'] {
  flex: 1 1 20rem;
}

.opcoes {
  border: none;
  padding: 0;
  margin: 1rem 0;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.opcao {
  display: flex;
  gap: 0.75rem;
  align-items: flex-start;
  padding: 0.6rem 0.9rem;
  border: 1px solid var(--cor-borda);
  border-radius: var(--raio);
  font-weight: 400;
  cursor: pointer;
}

.opcao .ajuda {
  display: block;
}

.opcao--marcada {
  background: var(--cor-destaque-suave);
  border-color: var(--cor-destaque-borda);
}

.opcao--bloqueada {
  cursor: not-allowed;
  opacity: 0.75;
}

.aviso {
  color: var(--cor-alerta);
}

.conferir {
  border-top: 1px solid var(--cor-borda);
  padding-top: 1rem;
}

input[type='date'] {
  font: inherit;
  padding: 0.5rem 0.75rem;
  border: 1px solid #c9cfcb;
  border-radius: var(--raio);
  max-width: 14rem;
}
</style>
