import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { onScopeDispose } from 'vue'

import { useHttpClient } from '@/app/injection'

import {
  chavesAtendimento,
  definirPresenca,
  obterPresenca,
  registrarAtividade,
  type EscolhaPresenca,
  type Presenca,
} from './atendimento-api'

/**
 * Canal entre abas do mesmo navegador: só avisa que a presença mudou; cada aba relê do servidor. Um só
 * por aba (a mensagem não volta para quem a enviou), compartilhado pela fila e pela conversa: assim a aba
 * não avisa a si mesma nem relê à toa.
 */
const ouvintes = new Set<() => void>()
let canal: BroadcastChannel | null | undefined
function canalDaAba() {
  if (canal === undefined) {
    canal =
      typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel('emconta-presenca')
    if (canal) canal.onmessage = () => ouvintes.forEach((ouvir) => ouvir())
  }
  return canal
}

/** Entre duas versões da presença, fica a mais nova (resposta atrasada não volta o estado). */
export function presencaMaisNova(atual: Presenca | undefined, nova: Presenca): Presenca {
  if (!atual?.atualizadoEm || !nova.atualizadoEm) return nova
  return Date.parse(nova.atualizadoEm) >= Date.parse(atual.atualizadoEm) ? nova : atual
}

/**
 * Presença do próprio atendente, a mesma em toda a tela (fila e conversa) e entre abas. Modo
 * automático: {@link atividade} marca Disponível quando a pessoa começa a responder. Escolha manual: vale
 * até ela voltar ao automático; o servidor ignora atividade nesse modo, então um pedido automático
 * atrasado não a desfaz, e na tela fica sempre a versão com `atualizadoEm` mais novo.
 */
export function usePresenca() {
  const http = useHttpClient()
  const queryClient = useQueryClient()
  const consulta = useQuery({
    queryKey: chavesAtendimento.presenca,
    queryFn: () => obterPresenca(http),
  })

  const reler = () => void queryClient.invalidateQueries({ queryKey: chavesAtendimento.presenca })
  canalDaAba()
  ouvintes.add(reler)
  onScopeDispose(() => ouvintes.delete(reler))

  function aplicar(nova: Presenca) {
    queryClient.setQueryData<Presenca>(chavesAtendimento.presenca, (atual) =>
      presencaMaisNova(atual, nova),
    )
    canalDaAba()?.postMessage('mudou')
  }

  const escolher = useMutation({
    mutationFn: (escolha: EscolhaPresenca) => definirPresenca(http, escolha),
    onSuccess: aplicar,
  })

  let pedindo = false
  /** A pessoa começou a responder nesta conversa: só pede algo se estiver no automático e Ausente. */
  async function atividade(atendimento: number) {
    const atual = consulta.data.value
    if (!atual || atual.modo !== 'AUTOMATICO' || atual.disponivel || pedindo) return
    pedindo = true
    try {
      aplicar(await registrarAtividade(http, atendimento))
    } catch {
      // Sem efeito na conversa; a próxima atividade tenta de novo.
    } finally {
      pedindo = false
    }
  }

  return { consulta, escolher, atividade }
}
