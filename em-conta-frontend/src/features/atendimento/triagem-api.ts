import type { components } from '@/shared/api/contract/schema'
import type { HttpClient } from '@/shared/api/http-client'

/**
 * Triagem inicial pelo WhatsApp: ao abrir um ciclo, o cliente recebe uma saudação com menu numerado; a
 * opção escolhida define o setor antes de alguém assumir. Aqui fica a configuração (ligada ou não e o
 * setor de cada opção), consultada com `atendimento:consultar` e alterada com `triagem:configurar`.
 */
export type ConfiguracaoTriagem = components['schemas']['ConfiguracaoTriagem']
export type OpcaoTriagem = components['schemas']['OpcaoTriagem']
export type PedidoTriagem = components['schemas']['ConfiguracaoTriagemRequest']

const CAMINHO = '/v1/atendimento/triagem'

export const chavesTriagem = {
  configuracao: ['triagem'] as const,
}

export function obterTriagem(http: HttpClient) {
  return http.get<ConfiguracaoTriagem>(CAMINHO)
}

export function configurarTriagem(http: HttpClient, pedido: PedidoTriagem) {
  return http.request<ConfiguracaoTriagem>('PUT', CAMINHO, { body: pedido })
}
