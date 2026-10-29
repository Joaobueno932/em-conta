<script setup lang="ts">
import { RouterLink } from 'vue-router'
</script>

<template>
  <main class="pagina">
    <h1>Acesso negado</h1>
    <p>Você não tem permissão para acessar esta página.</p>
    <p>Se precisar deste acesso, fale com o administrador do sistema.</p>
    <RouterLink :to="{ name: 'inicio' }">Voltar ao início</RouterLink>
  </main>
</template>
