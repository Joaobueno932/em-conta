<script setup lang="ts">
import { RouterLink } from 'vue-router'

import IconeTraco from '@/shared/components/IconeTraco.vue'

import CadastroClassificacao from './CadastroClassificacao.vue'

/**
 * Setores e etiquetas do atendimento (aberto por "Ver opções"). Quem atende classifica cada ciclo pelo
 * painel da conversa; aqui ficam os cadastros. A lista acompanha `atendimento:consultar`; incluir,
 * renomear e ativar/desativar têm permissões próprias por cadastro.
 */
</script>

<template>
  <main class="pagina pagina--larga">
    <header class="cabecalho">
      <RouterLink
        :to="{ name: 'atendimento' }"
        class="botao-icone sem-borda"
        aria-label="Voltar ao atendimento"
        title="Voltar ao atendimento"
      >
        <IconeTraco nome="voltar" :tamanho="22" />
      </RouterLink>
      <div>
        <h1>Setores e etiquetas</h1>
        <p class="subtitulo">
          Classificação dos atendimentos: cada ciclo tem um setor (opcional) e até 10 etiquetas,
          definidos por quem atende. Desativar tira o item das escolhas novas; os atendimentos que
          já o usam continuam com ele no Histórico geral.
        </p>
      </div>
    </header>

    <div class="colunas">
      <CadastroClassificacao cadastro="setores" />
      <CadastroClassificacao cadastro="etiquetas" />
    </div>
  </main>
</template>

<style scoped>
.cabecalho {
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
}

.cabecalho .botao-icone {
  margin-top: 0.15rem;
}

.colunas {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 1rem;
  align-items: start;
}

.colunas > * {
  margin: 0;
}

@media (max-width: 60rem) {
  .colunas {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
