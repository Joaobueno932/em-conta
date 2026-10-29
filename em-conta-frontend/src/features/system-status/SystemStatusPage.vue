<script setup lang="ts">
import { computed } from 'vue'

import { useRuntimeConfig } from '@/app/injection'
import { describeApiError } from '@/shared/api/errors'
import ErrorNotice from '@/shared/components/ErrorNotice.vue'

import { degradedStatusFrom, useSystemStatusQuery } from './system-status-api'

const config = useRuntimeConfig()
const { data, error, isPending, isError, isFetching, refetch } = useSystemStatusQuery()

const degraded = computed(() => degradedStatusFrom(error.value))
const errorMessage = computed(() => describeApiError(error.value))
</script>

<template>
  <main class="system-status">
    <header>
      <h1>Em Conta</h1>
      <p class="subtitle">
        Página técnica provisória de verificação da fundação · ambiente
        <strong>{{ config.environment }}</strong>
      </p>
    </header>

    <p v-if="isPending" role="status">Verificando a API…</p>

    <ErrorNotice
      v-else-if="isError && degraded"
      title="Banco de dados indisponível"
      message="A API respondeu, mas não conseguiu conectar ao banco de dados."
      :retrying="isFetching"
      @retry="refetch()"
    >
      <p>Versão da API: {{ degraded.version }}</p>
    </ErrorNotice>

    <ErrorNotice
      v-else-if="isError"
      title="API indisponível"
      :message="errorMessage"
      :retrying="isFetching"
      @retry="refetch()"
    />

    <section v-else-if="data" aria-labelledby="status-title">
      <h2 id="status-title">API conectada</h2>
      <dl>
        <dt>Aplicação</dt>
        <dd>{{ data.application }}</dd>
        <dt>Versão</dt>
        <dd>{{ data.version }}</dd>
        <dt>Situação</dt>
        <dd data-testid="api-status">{{ data.status }}</dd>
        <dt>Banco de dados</dt>
        <dd data-testid="database-status">{{ data.database }}</dd>
        <dt>Horário do servidor (UTC)</dt>
        <dd>{{ data.serverTime }}</dd>
      </dl>
    </section>
  </main>
</template>

<style scoped>
.system-status {
  max-width: 40rem;
  margin: 3rem auto;
  padding: 0 1rem;
  font-family: system-ui, sans-serif;
  color: #1d2939;
}

.subtitle {
  color: #475467;
}

dl {
  display: grid;
  grid-template-columns: max-content 1fr;
  gap: 0.25rem 1rem;
}

dt {
  font-weight: 600;
}

dd {
  margin: 0;
}
</style>
