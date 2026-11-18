<script setup lang="ts">
import IconeTraco from '@/shared/components/IconeTraco.vue'

import AnexoMensagem from './AnexoMensagem.vue'
import { formatarHora, ROTULO_EVENTO, ROTULO_TIPO_EXTERNO, type Item } from './atendimento-api'
import IndicadorSituacao from './IndicadorSituacao.vue'

/**
 * Um item da linha do tempo (AT-04/HG-03): evento em pílula centralizada, nota interna da equipe (no
 * centro, com fundo próprio e o rótulo "só a equipe vê") ou mensagem em balão (entrada
 * à esquerda, saída à direita), com anexo, legenda, horário e, na saída, o indicador da situação do
 * envio ({@link IndicadorSituacao}, com rótulo acessível). Usado na conversa e no detalhe somente
 * leitura do histórico. O aviso automático ao cliente (mudança de responsável) e as mensagens da triagem
 * inicial aparecem como mensagens enviadas, com "Aviso automático" ou "Triagem automática" no lugar do
 * autor; os eventos da triagem não têm autor.
 */
defineProps<{
  item: Item
  atendimento: number
  somenteEste?: boolean
  podeTentarDeNovo?: boolean
  destacada?: boolean
}>()
</script>

<template>
  <p v-if="item.tipo === 'EVENTO'" class="evento">
    <IconeTraco nome="pessoa" :tamanho="16" class="icone-evento" />
    {{ formatarHora(item.ocorridoEm) }} ·
    <template v-if="item.evento === 'ATENDIMENTO_INICIADO'">{{
      ROTULO_EVENTO[item.evento]
    }}</template>
    <template v-else-if="item.evento?.startsWith('TRIAGEM_')"
      >{{ ROTULO_EVENTO[item.evento] ?? item.evento
      }}<template v-if="item.texto"> — {{ item.texto }}</template></template
    >
    <template v-else>
      {{ item.autor?.nome }} {{ ROTULO_EVENTO[item.evento ?? ''] ?? item.evento }}
      <template v-if="item.usuarioAlvo">para {{ item.usuarioAlvo.nome }}</template>
      <template v-if="item.associado">{{ item.associado.nome }}</template>
      <template v-if="item.motivo">({{ item.motivo }})</template>
      <!-- Retrato gravado no evento (ex.: setor e etiquetas da classificação, com os nomes da época). -->
      <template v-if="item.texto">— {{ item.texto }}</template>
    </template>
  </p>
  <div v-else-if="item.tipo === 'NOTA'" :id="`nota-${item.id}`" class="nota" role="note">
    <p class="rotulo-nota com-icone">
      <IconeTraco nome="informacao" :tamanho="16" />
      Nota interna · só a equipe vê
    </p>
    <p class="texto">{{ item.texto }}</p>
    <p class="meta">
      <span v-if="item.autor">{{ item.autor.nome }} · </span>
      <span class="hora">{{ formatarHora(item.ocorridoEm) }}</span>
    </p>
  </div>
  <div
    v-else
    :id="`mensagem-${item.id}`"
    class="mensagem"
    :class="[item.direcao === 'SAIDA' ? 'saida' : 'entrada', { destacada }]"
    tabindex="-1"
  >
    <p v-if="item.tipoMensagem === 'NAO_SUPORTADO'" class="ajuda">
      Mensagem do tipo “{{ ROTULO_TIPO_EXTERNO[item.tipoExterno ?? ''] ?? item.tipoExterno }}” não
      suportada nesta versão.
    </p>
    <template v-else-if="item.anexo">
      <AnexoMensagem
        :atendimento="atendimento"
        :anexo="item.anexo"
        :legenda="item.texto"
        :somente-este="somenteEste"
        :pode-tentar-de-novo="podeTentarDeNovo"
      />
      <p v-if="item.texto" class="texto">{{ item.texto }}</p>
    </template>
    <p v-else class="texto">{{ item.texto }}</p>
    <p class="meta">
      <span v-if="item.origem === 'AVISO_RESPONSAVEL'" class="automatico">Aviso automático · </span>
      <span v-else-if="item.origem === 'TRIAGEM'" class="automatico">Triagem automática · </span>
      <span v-else-if="item.autor">{{ item.autor.nome }} · </span>
      <span class="hora"
        >{{ formatarHora(item.ocorridoEm) }}
        <IndicadorSituacao
          v-if="item.direcao === 'SAIDA'"
          :situacao="item.situacao"
          :erro-titulo="item.erroTitulo"
      /></span>
    </p>
  </div>
</template>

<style scoped>
/* AT-04: eventos em pílula cinza; balões com 17 px de texto, horário pequeno à direita. */
.evento {
  align-self: center;
  max-width: 85%;
  margin: 4px 0;
  padding: 8px 18px;
  border-radius: 8px;
  background: var(--cor-superficie-2);
  color: var(--cor-texto-2);
  font-size: 15px;
  text-align: center;
}

.icone-evento {
  vertical-align: -3px;
  margin-right: 6px;
}

.mensagem {
  max-width: 75%;
  padding: 9px 17px 7px;
  border-radius: var(--raio-controle);
  background: var(--cor-superficie);
  border: 1px solid var(--cor-borda);
  font-size: 17px;
  line-height: 1.35;
  transition: box-shadow 0.3s;
}

/* Entrada (cliente): o tom neutro mais claro da paleta e uma borda um pouco mais marcada, derivada das
   cores da paleta, para o balão não se confundir com o fundo branco da conversa nem com as pílulas de
   evento (sem borda). A paleta não tem creme, por isso o fundo da conversa continua branco. */
.mensagem.entrada {
  background: var(--cor-fundo);
  border-color: color-mix(in srgb, var(--cor-borda) 65%, var(--cor-texto-3));
}

.mensagem.saida {
  align-self: flex-end;
  background: var(--cor-destaque-suave);
  border-color: var(--cor-destaque-suave);
}

/* Mensagem aberta pela área Mídia: destaque discreto por alguns segundos. */
.mensagem.destacada {
  box-shadow: 0 0 0 3px var(--cor-destaque-borda);
}

.mensagem:focus-visible {
  outline: 2px solid var(--cor-foco);
}

.mensagem p {
  margin: 0;
}

.texto {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.meta {
  font-size: 13px;
  color: var(--cor-texto-2);
  margin-top: 4px !important;
  text-align: right;
}

/* Nota interna (só a equipe vê): no centro, com o fundo das notas e o rótulo explícito, para nunca
   parecer mensagem trocada com o cliente. */
.nota {
  align-self: center;
  width: min(75%, 40rem);
  padding: 9px 17px 7px;
  border: 1px dashed var(--cor-nota-borda);
  border-radius: var(--raio-controle);
  background: var(--cor-nota);
  font-size: 16px;
  line-height: 1.35;
}

.nota p {
  margin: 0;
}

.rotulo-nota {
  margin-bottom: 4px !important;
  color: var(--cor-texto);
  font-size: 13px;
  font-weight: 600;
}

.automatico {
  font-style: italic;
}

/* Horário e indicador de situação quebram juntos (o detalhe da causa tem quebra própria). */
.hora {
  white-space: nowrap;
}
</style>
