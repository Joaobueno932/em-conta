<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { computed } from 'vue'
import { RouterLink } from 'vue-router'

import { useHttpClient } from '@/app/injection'
import { temPermissao, useSessao } from '@/features/auth/auth-api'
import { describeApiError } from '@/shared/api/errors'
import AlertaMensagem from '@/shared/components/AlertaMensagem.vue'

import { chaves, formatarData, listarUcs } from './associados-api'
import VinculosUsinaDoAssociado from './VinculosUsinaDoAssociado.vue'
import { useVinculosUsina } from './vinculos-usina'

const props = defineProps<{ associadoId: number }>()

const http = useHttpClient()
const { data: sessao } = useSessao()

const ucs = useQuery({
  queryKey: computed(() => chaves.ucs(props.associadoId)),
  queryFn: () => listarUcs(http, props.associadoId),
})
const podeCadastrar = computed(() => temPermissao(sessao.value, 'ucs:cadastrar'))
const podeEditar = computed(() => temPermissao(sessao.value, 'ucs:editar'))
const podeVerUsina = computed(() => temPermissao(sessao.value, 'usinas:consultar'))
const {
  podeConsultar: podeVerVinculos,
  vinculos,
  vigentePorUc,
} = useVinculosUsina(computed(() => props.associadoId))
</script>

<template>
  <div class="titulo">
    <h2>
      Unidades consumidoras
      <span v-if="ucs.data.value" class="ajuda">{{ ucs.data.value.length }} UC(s)</span>
    </h2>
    <RouterLink
      v-if="podeCadastrar"
      class="botao"
      :to="{ name: 'uc-nova', params: { id: associadoId } }"
    >
      Cadastrar UC
    </RouterLink>
  </div>
  <p class="ajuda">
    Cadastro da UC não indica situação operacional, rateio ou aptidão de faturamento. O vínculo com
    usina é cadastral e é feito pela página da usina. Transferência de titularidade ainda não está
    disponível.
  </p>

  <p v-if="ucs.isPending.value" role="status">Carregando unidades consumidoras…</p>
  <div v-else-if="ucs.isError.value">
    <AlertaMensagem tipo="erro">{{ describeApiError(ucs.error.value) }}</AlertaMensagem>
    <button type="button" class="secundario" @click="ucs.refetch()">Tentar novamente</button>
  </div>
  <p v-else-if="ucs.data.value && ucs.data.value.length === 0" class="vazio">
    Nenhuma unidade consumidora cadastrada para este associado.
  </p>
  <div v-else-if="ucs.data.value" class="tabela-rolavel">
    <table>
      <thead>
        <tr>
          <th scope="col">Nº UC</th>
          <th scope="col">Concessionária</th>
          <th scope="col">Denominação</th>
          <th scope="col">Endereço</th>
          <th scope="col">Cadastrada em</th>
          <th v-if="podeVerVinculos" scope="col">Usina (vínculo cadastral)</th>
          <th v-if="podeEditar" scope="col"><span class="visualmente-oculto">Ações</span></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="uc in ucs.data.value" :key="uc.id">
          <td>
            <strong>{{ uc.numero }}</strong>
          </td>
          <td>{{ uc.concessionariaNome }}</td>
          <td>{{ uc.denominacao || '—' }}</td>
          <td>{{ uc.endereco || '—' }}</td>
          <td>{{ formatarData(uc.criadoEm) }}</td>
          <td v-if="podeVerVinculos">
            <template v-if="vigentePorUc.get(uc.id)">
              <RouterLink
                v-if="podeVerUsina"
                :to="{
                  name: 'usina',
                  params: { id: vigentePorUc.get(uc.id)!.usina.id },
                  query: { aba: 'ucs' },
                }"
              >
                {{ vigentePorUc.get(uc.id)!.usina.nome }}
              </RouterLink>
              <template v-else>{{ vigentePorUc.get(uc.id)!.usina.nome }}</template>
              <span class="ajuda">{{ vigentePorUc.get(uc.id)!.usina.codigo }}</span>
            </template>
            <template v-else-if="vinculos.isPending.value">…</template>
            <template v-else>—</template>
          </td>
          <td v-if="podeEditar">
            <RouterLink :to="{ name: 'uc-editar', params: { id: associadoId, ucId: uc.id } }">
              Editar<span class="visualmente-oculto"> UC {{ uc.numero }}</span>
            </RouterLink>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
  <VinculosUsinaDoAssociado v-if="podeVerVinculos" :associado-id="associadoId" />
</template>

<style scoped>
.titulo {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 1rem;
}

.vazio {
  padding: 1.5rem 0;
  text-align: center;
  color: var(--cor-texto-2);
}
</style>
