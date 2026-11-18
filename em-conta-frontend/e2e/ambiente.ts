import process from 'node:process'

import { request, type APIRequestContext } from '@playwright/test'

import { ADMIN_EMAIL, ADMIN_SENHA } from './apoio'

/**
 * Organização do banco isolado de testes entre execuções (e da revisão manual que usa o mesmo banco):
 *
 * - A triagem inicial é uma configuração única do banco (a demonstração pode deixá-la ligada). A
 *   preparação global desliga-a para as jornadas, que não esperam a saudação; só `triagem.spec.ts` a
 *   liga, e devolve o que encontrou. No fim da execução, a configuração anterior volta.
 * - Setores e etiquetas criados pelas jornadas têm o prefixo `E2E` e ficam sempre ligados a algum ciclo
 *   de teste (são a evidência dos cenários). No fim de cada jornada, os que ainda estão ativos são
 *   desativados ({@link desativarAtivos}): somem das escolhas novas (painel, triagem, cadastro "Ativos")
 *   e continuam nos ciclos e no filtro do Histórico geral, marcados "(inativo)".
 */
export const PREFIXO_E2E = 'E2E'

const BASE = '/api/v1/atendimento'

export interface ConfiguracaoTriagem {
  ativa: boolean
  versao: number
  opcoes: { opcao: string; configuravel: boolean; setor?: { id: number } }[]
}

async function cabecalhos(api: APIRequestContext) {
  const xsrf = (await api.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')?.value ?? ''
  return { 'X-XSRF-TOKEN': xsrf }
}

export async function lerTriagem(api: APIRequestContext) {
  const resposta = await api.get(`${BASE}/triagem`)
  if (!resposta.ok()) throw new Error(`Triagem: leitura recusada (${resposta.status()})`)
  return (await resposta.json()) as ConfiguracaoTriagem
}

/** Grava a triagem sobre a versão atual; `destinos` ausentes ficam sem setor. */
export async function gravarTriagem(
  api: APIRequestContext,
  ativa: boolean,
  destinos: Record<string, number | undefined>,
) {
  const { versao } = await lerTriagem(api)
  const resposta = await api.put(`${BASE}/triagem`, {
    headers: await cabecalhos(api),
    data: {
      versao,
      ativa,
      opcoes: Object.entries(destinos).map(([opcao, setorId]) => ({
        opcao,
        ...(setorId === undefined ? {} : { setorId }),
      })),
    },
  })
  if (!resposta.ok()) throw new Error(`Triagem: gravação recusada (${resposta.status()})`)
}

/** Destinos de uma configuração lida, para devolvê-la depois com {@link gravarTriagem}. */
export const destinosDe = (c: ConfiguracaoTriagem) =>
  Object.fromEntries(c.opcoes.filter((o) => o.configuravel).map((o) => [o.opcao, o.setor?.id]))

/** Desativa os itens do cadastro com esses nomes que ainda estiverem ativos (vínculos preservados). */
export async function desativarAtivos(
  api: APIRequestContext,
  cadastro: 'setores' | 'etiquetas',
  nomes: string[],
) {
  const resposta = await api.get(`${BASE}/${cadastro}?situacao=ATIVOS`)
  if (!resposta.ok()) return
  const { itens } = (await resposta.json()) as {
    itens: { id: number; nome: string; versao: number }[]
  }
  for (const item of itens.filter((i) => nomes.includes(i.nome))) {
    await api.post(`${BASE}/${cadastro}/${item.id}/desativacao`, {
      headers: await cabecalhos(api),
      data: { versao: item.versao },
    })
  }
}

/**
 * Preparação global (playwright.config.ts): com a API de testes configurada, guarda a triagem atual e a
 * desliga; devolve a finalização, que a restaura.
 */
export default async function prepararAmbiente() {
  if (!ADMIN_EMAIL || !ADMIN_SENHA) return
  const api = await request.newContext({
    baseURL: process.env.EM_CONTA_API_PROXY_TARGET ?? 'http://localhost:8080',
  })
  await api.get('/api/v1/auth/csrf')
  const login = await api.post('/api/v1/auth/login', {
    headers: await cabecalhos(api),
    data: { email: ADMIN_EMAIL, senha: ADMIN_SENHA },
  })
  if (!login.ok())
    throw new Error(`Preparação: login do administrador recusado (${login.status()})`)
  const antes = await lerTriagem(api)
  await gravarTriagem(api, false, destinosDe(antes))
  return async () => {
    await gravarTriagem(api, antes.ativa, destinosDe(antes))
    await api.dispose()
  }
}
