<script setup lang="ts">
import { useSessao } from '@/features/auth/auth-api'

const { data: usuario } = useSessao()
</script>

<template>
  <main v-if="usuario" class="pagina">
    <h1>Olá, {{ usuario.nome }}</h1>
    <p>Área autenticada provisória. Os módulos do Em Conta serão adicionados aqui.</p>
    <h2>Seus dados</h2>
    <dl>
      <dt>E-mail</dt>
      <dd data-testid="usuario-email">{{ usuario.email }}</dd>
      <dt>Perfis</dt>
      <dd>{{ usuario.perfis.length ? usuario.perfis.join(', ') : 'Nenhum perfil atribuído' }}</dd>
    </dl>
  </main>
</template>
