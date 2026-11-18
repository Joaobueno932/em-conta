import { expect, test, type Page } from '@playwright/test'

import { desativarAtivos, destinosDe, gravarTriagem, lerTriagem, PREFIXO_E2E } from './ambiente'
import { ADMIN_EMAIL, ADMIN_SENHA, paginaDoAdministrador } from './apoio'
import { GraphControlada, NUMERO_TESTE, SEGREDO_TESTE, webhookDeTeste } from './whatsapp-controlado'

/**
 * Triagem inicial de ponta a ponta, com o canal no AMBIENTE CONTROLADO de teste (ver
 * whatsapp-controlado.ts): configuração pela tela (Ver opções → Triagem do WhatsApp), saudação enviada à
 * Graph controlada, resposta do cliente definindo o setor na fila Aguardando, assunção antes da resposta,
 * opções 3 e 4 e o setor consultável no Histórico geral depois do encerramento. Liga a triagem no banco
 * isolado de testes e, no fim, devolve a configuração que encontrou e desativa os setores E2E criados,
 * que ficam ligados aos ciclos da jornada (ver e2e/ambiente.ts); nunca contra dados reais.
 */
// eslint-disable-next-line playwright/no-skipped-test -- sem a API de testes configurada, a jornada não pode rodar
test.skip(
  !ADMIN_EMAIL || !ADMIN_SENHA || !SEGREDO_TESTE || !NUMERO_TESTE,
  'Defina E2E_ADMIN_*, E2E_WHATSAPP_APP_SECRET e E2E_WHATSAPP_PHONE_NUMBER_ID (ver README)',
)

const graph = new GraphControlada()

test.beforeAll(() => graph.iniciar())

test.afterAll(() => graph.parar())

const BASE = '/api/v1/atendimento'
const SAUDACAO = 'Para direcionar sua conversa'
const aleatorio = (digitos: number) =>
  String(Math.floor(Math.random() * 10 ** digitos)).padStart(digitos, '0')

async function cabecalhos(page: Page) {
  const xsrf = (await page.context().cookies()).find((c) => c.name === 'XSRF-TOKEN')?.value ?? ''
  return { 'X-XSRF-TOKEN': xsrf }
}

async function enviar(page: Page, metodo: 'POST' | 'PUT', caminho: string, data: unknown) {
  return page.request.fetch(`${BASE}${caminho}`, {
    method: metodo,
    data,
    headers: await cabecalhos(page),
  })
}

async function versao(page: Page, id: number) {
  const { versao } = (await (await page.request.get(`${BASE}/atendimentos/${id}`)).json()) as {
    versao: number
  }
  return versao
}

interface Resumo {
  id: number
  setor?: { id: number; nome: string }
  emTriagem: boolean
}

/** Ciclo aberto (Aguardando) do contato, pela busca da fila. */
async function aguardando(page: Page, waId: string) {
  const params = new URLSearchParams({ visao: 'AGUARDANDO', busca: waId })
  const pagina = (await (await page.request.get(`${BASE}/atendimentos?${params}`)).json()) as {
    itens: Resumo[]
  }
  return pagina.itens[0]
}

/** Primeira mensagem do contato: abre o ciclo, que recebe a saudação. */
async function novoCiclo(page: Page, waId: string, nome: string) {
  expect((await webhookDeTeste.mensagem(page.request, waId, nome, 'Olá')).status()).toBe(200)
  await expect
    .poll(async () => (await aguardando(page, waId))?.id ?? 0, { timeout: 30_000 })
    .toBeGreaterThan(0)
  await expect
    .poll(() => graph.envios.filter((e) => e.para === waId && e.texto.includes(SAUDACAO)).length, {
      timeout: 30_000,
    })
    .toBe(1)
  return (await aguardando(page, waId)).id
}

async function responder(page: Page, waId: string, nome: string, texto: string) {
  expect((await webhookDeTeste.mensagem(page.request, waId, nome, texto)).status()).toBe(200)
}

test('triagem: menu na abertura, setor na fila antes de assumir e consultável no histórico', async ({
  browser,
}) => {
  test.setTimeout(240_000)
  const admin = await paginaDoAdministrador(browser)
  const marca = Date.now().toString(36).toUpperCase()
  const financeiro = `${PREFIXO_E2E} Financeiro ${marca}`
  const comercial = `${PREFIXO_E2E} Comercial ${marca}`
  const antes = await lerTriagem(admin.request)
  const criar = async (nome: string) => {
    const resposta = await enviar(admin, 'POST', '/setores', { nome })
    expect(resposta.status()).toBe(201)
    return ((await resposta.json()) as { id: number }).id
  }
  const financeiroId = await criar(financeiro)
  const comercialId = await criar(comercial)

  try {
    // 1. Configuração pela tela: Financeiro e Cobrança no mesmo setor, Comercial no outro.
    await admin.goto('/atendimento')
    await admin.getByRole('button', { name: 'Ver opções' }).click()
    await admin.getByRole('link', { name: 'Triagem do WhatsApp' }).click()
    await expect(
      admin.getByRole('heading', { name: 'Triagem do WhatsApp', level: 1 }),
    ).toBeVisible()
    await admin.getByLabel('1 - Financeiro').selectOption({ label: financeiro })
    await admin.getByLabel('2 - Cobrança ou segunda via').selectOption({ label: financeiro })
    await admin.getByLabel('3 - Quero conhecer o Em Conta').selectOption({ label: comercial })
    await admin.getByLabel('Enviar a triagem ao abrir um novo atendimento').check()
    await admin.getByRole('button', { name: 'Salvar triagem' }).click()
    await expect(admin.getByText('Triagem salva.')).toBeVisible()
    await expect(admin.getByText('Ligada', { exact: true })).toBeVisible()

    // 2. Contato A: recebe a saudação, aparece "Em triagem" e, ao responder 2, já tem o setor na fila.
    const waA = `55679${aleatorio(8)}`
    const nomeA = `Triagem ${marca} Ana`
    const a = await novoCiclo(admin, waA, nomeA)
    await admin.goto('/atendimento')
    await admin.getByRole('button', { name: /Aguardando/ }).click()
    await admin.getByLabel('Buscar contato').fill(nomeA)
    await admin.getByLabel('Buscar contato').press('Enter')
    const itemA = admin.getByRole('button', { name: new RegExp(nomeA) })
    await expect(itemA).toContainText('Em triagem')
    await responder(admin, waA, nomeA, '2')
    await expect
      .poll(async () => (await aguardando(admin, waA))?.setor?.id, { timeout: 30_000 })
      .toBe(financeiroId)
    await expect(itemA).toContainText(financeiro, { timeout: 15_000 })
    await expect(itemA).not.toContainText('Em triagem')
    expect(
      graph.envios.filter((e) => e.para === waA && e.texto.startsWith('Obrigado!')),
    ).toHaveLength(1)

    // Na conversa: a saudação como "Triagem automática" e o evento com a opção e o setor.
    await itemA.click()
    await expect(admin.getByText(/Triagem automática ·/).first()).toBeVisible()
    await expect(
      admin.getByText(`Opção 2 (Cobrança ou segunda via) · Setor: ${financeiro}`, { exact: false }),
    ).toBeVisible()

    // 3. Contato B: alguém assume antes da resposta; o "3" depois não muda o setor.
    const waB = `55679${aleatorio(8)}`
    const nomeB = `Triagem ${marca} Bruno`
    const b = await novoCiclo(admin, waB, nomeB)
    expect((await enviar(admin, 'POST', `/atendimentos/${b}/assuncao`, undefined)).status()).toBe(
      204,
    )
    await responder(admin, waB, nomeB, '3')
    await expect
      .poll(async () => {
        const det = (await (await admin.request.get(`${BASE}/atendimentos/${b}`)).json()) as {
          resumo: Resumo
        }
        return det.resumo.setor?.id ?? null
      })
      .toBeNull()

    // 4. Contato C: pede atendimento humano (4) e segue na fila sem setor.
    const waC = `55679${aleatorio(8)}`
    const nomeC = `Triagem ${marca} Carla`
    await novoCiclo(admin, waC, nomeC)
    await responder(admin, waC, nomeC, '4')
    await expect
      .poll(async () => (await aguardando(admin, waC))?.emTriagem, { timeout: 30_000 })
      .toBe(false)
    expect((await aguardando(admin, waC)).setor ?? null).toBeNull()

    // Contato D: "Quero conhecer o Em Conta" (3) vai para o setor comercial configurado.
    const waD = `55679${aleatorio(8)}`
    const nomeD = `Triagem ${marca} Davi`
    await novoCiclo(admin, waD, nomeD)
    await responder(admin, waD, nomeD, '3')
    await expect
      .poll(async () => (await aguardando(admin, waD))?.setor?.id, { timeout: 30_000 })
      .toBe(comercialId)

    // 5. A é assumido e encerrado: o setor da triagem continua no Histórico geral.
    expect((await enviar(admin, 'POST', `/atendimentos/${a}/assuncao`, undefined)).status()).toBe(
      204,
    )
    expect(
      (
        await enviar(admin, 'POST', `/atendimentos/${a}/encerramento`, {
          versao: await versao(admin, a),
        })
      ).status(),
    ).toBe(204)
    await admin.goto('/atendimento/historico')
    await admin.getByLabel('Buscar contato').fill(`Triagem ${marca}`)
    await admin.getByLabel('Setor', { exact: true }).selectOption({ label: financeiro })
    await admin.getByRole('button', { name: 'Filtrar' }).click()
    const linhas = admin.getByRole('row').filter({ hasText: `Triagem ${marca}` })
    await expect(linhas).toHaveCount(1)
    await expect(linhas.first()).toContainText(nomeA)
    await expect(linhas.first()).toContainText(financeiro)
  } finally {
    // A triagem é uma configuração única do banco isolado: volta como estava antes da jornada; os
    // setores E2E saem das escolhas novas e continuam nos ciclos da jornada.
    await gravarTriagem(admin.request, antes.ativa, destinosDe(antes))
    await desativarAtivos(admin.request, 'setores', [financeiro, comercial])
  }
})
