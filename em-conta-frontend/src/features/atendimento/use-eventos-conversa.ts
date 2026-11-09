import { onScopeDispose, ref, watch, type Ref } from 'vue'

import { eventosConversa } from './atendimento-api'

/** Espera antes de reabrir uma conexão recusada (as que só caem o navegador reabre sozinho). */
export const ESPERAS_RECONEXAO_MS = [2000, 5000, 15000, 30000]

/**
 * Avisos de mudança da conversa aberta por Server-Sent Events (`…/eventos`). O aviso não traz conteúdo:
 * `aoMudar` relê pelas rotas de sempre, com as mesmas permissões. A cada (re)conexão chega `pronto` e a
 * tela relê o que pode ter mudado enquanto estava sem conexão. Trocar de conversa fecha a assinatura
 * anterior antes de abrir a nova (aviso atrasado da anterior é ignorado).
 *
 * `conectado` diz se os avisos estão chegando: sem EventSource (testes, navegador antigo) ou com a
 * conexão fora, fica falso e a consulta periódica volta ao intervalo curto.
 */
export function useEventosConversa(id: Ref<number>, aoMudar: () => void) {
  const conectado = ref(false)
  let fonte: EventSource | null = null
  let espera: ReturnType<typeof setTimeout> | undefined
  let recusas = 0

  function fechar() {
    clearTimeout(espera)
    fonte?.close()
    fonte = null
    conectado.value = false
  }

  function abrir() {
    fechar()
    if (typeof EventSource === 'undefined') return
    const atual = new EventSource(eventosConversa(id.value))
    fonte = atual
    atual.addEventListener('pronto', () => {
      if (fonte !== atual) return
      recusas = 0
      conectado.value = true
      aoMudar()
    })
    atual.addEventListener('mudanca', () => {
      if (fonte === atual) aoMudar()
    })
    atual.addEventListener('error', () => {
      if (fonte !== atual) return
      conectado.value = false
      // CONNECTING: queda ou fim da duração da conexão; o navegador reconecta sozinho. CLOSED: o servidor
      // recusou (sessão expirada, permissão, atendimento inexistente); tenta de novo com espera crescente,
      // enquanto a consulta periódica cuida da tela (e o 401 dela leva ao login).
      if (atual.readyState === EventSource.CLOSED) {
        const ms = ESPERAS_RECONEXAO_MS[Math.min(recusas++, ESPERAS_RECONEXAO_MS.length - 1)]
        espera = setTimeout(abrir, ms)
      }
    })
  }

  watch(id, abrir, { immediate: true })
  onScopeDispose(fechar)
  return { conectado }
}
