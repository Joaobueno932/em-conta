import { expect, test } from '@playwright/test'

import { ADMIN_EMAIL, ADMIN_SENHA, paginaDoAdministrador } from './apoio'
import { GraphControlada, NUMERO_TESTE, SEGREDO_TESTE, webhookDeTeste } from './whatsapp-controlado'

/**
 * Respostas rápidas contra o backend real, com o canal no ambiente externo controlado de teste (ver
 * whatsapp-controlado.ts). Cria registros: rode só contra o banco isolado de testes.
 */
// eslint-disable-next-line playwright/no-skipped-test -- sem a API de testes configurada, a jornada não pode rodar
test.skip(
  !ADMIN_EMAIL || !ADMIN_SENHA || !SEGREDO_TESTE || !NUMERO_TESTE,
  'Defina E2E_ADMIN_*, E2E_WHATSAPP_APP_SECRET e E2E_WHATSAPP_PHONE_NUMBER_ID (ver README)',
)

const graph = new GraphControlada()

test.beforeAll(() => graph.iniciar())

test.afterAll(() => graph.parar())

test('cadastra, usa na conversa (revisada antes de enviar) e desativa', async ({ browser }) => {
  test.setTimeout(120_000)
  const page = await paginaDoAdministrador(browser)
  const marca = Date.now().toString(36)
  const titulo = `Prazo de fatura ${marca}`
  const atalho = `prazo-${marca}`
  const conteudo = 'Olá!\nA fatura vence no dia 10. 😊'
  const nome = `Contato Rápidas ${marca}`
  const waId = `55679${String(Math.floor(Math.random() * 1e8)).padStart(8, '0')}`

  // 1. Cadastro pelo catálogo (Ver opções → Mensagens rápidas).
  await page.goto('/atendimento')
  await page.getByRole('button', { name: 'Ver opções' }).click()
  await page.getByRole('link', { name: 'Mensagens rápidas' }).click()
  await expect(page.getByRole('heading', { name: 'Respostas rápidas', level: 1 })).toBeVisible()
  await page.getByRole('button', { name: 'Nova resposta' }).click()
  await page.getByLabel(/^Título/).fill(titulo)
  await page.getByLabel(/^Atalho/).fill(`/${atalho.toUpperCase()}`)
  await page.getByLabel(/^Texto/).fill(conteudo)
  await expect(page.getByLabel('Prévia da mensagem')).toContainText('A fatura vence no dia 10.')
  await page.getByRole('button', { name: 'Salvar' }).click()
  await expect(page.getByRole('heading', { name: 'Nova resposta' })).toHaveCount(0)

  // 2. Encontra no catálogo pela busca no servidor.
  await page.getByLabel('Buscar respostas').fill(marca)
  await page.getByRole('button', { name: 'Buscar' }).click()
  const item = page.getByRole('listitem').filter({ hasText: titulo })
  await expect(item).toHaveCount(1)
  await expect(item).toContainText(`/${atalho}`)

  // 3. Mensagem recebida (webhook assinado de teste) → assumir.
  expect((await webhookDeTeste.mensagem(page.request, waId, nome, 'Quando vence?')).status()).toBe(
    200,
  )
  await page.goto('/atendimento')
  await expect(async () => {
    await page.getByLabel('Buscar contato').fill(nome)
    await page.getByLabel('Buscar contato').press('Enter')
    await page.getByRole('button', { name: new RegExp(nome) }).click({ timeout: 3_000 })
  }).toPass({ timeout: 20_000 })
  await page.getByRole('button', { name: 'Assumir' }).click()
  await expect(page.getByText(/Em atendimento com/)).toBeVisible()
  const conversa = page.url()

  // 4. Seleciona pelo "/" com teclado: Enter insere, não envia.
  const campo = page.getByLabel('Mensagem')
  await campo.click()
  await campo.pressSequentially(`/${atalho}`)
  await expect(page.getByRole('option', { name: new RegExp(titulo) })).toBeVisible()
  await campo.press('Enter')
  await expect(campo).toHaveValue(conteudo)
  expect(graph.envios.filter((e) => e.para === waId)).toHaveLength(0)

  // 5. Edita o texto inserido e envia pelo fluxo normal.
  await campo.press('End')
  await campo.pressSequentially(' Qualquer dúvida, estou aqui.')
  const enviado = `${conteudo} Qualquer dúvida, estou aqui.`
  await page.getByRole('button', { name: 'Enviar' }).click()
  await expect(campo).toHaveValue('')
  await expect
    .poll(() => graph.envios.filter((e) => e.para === waId).length, { timeout: 15_000 })
    .toBe(1)
  expect(graph.envios.find((e) => e.para === waId)).toMatchObject({ texto: enviado })
  await expect(page.getByText(/Aceita pelo WhatsApp/)).toBeVisible({ timeout: 15_000 })

  // 6. Desativa no catálogo: some do seletor; a mensagem enviada continua igual.
  await page.goto('/atendimento/respostas-rapidas')
  await page.getByLabel('Buscar respostas').fill(marca)
  await page.getByRole('button', { name: 'Buscar' }).click()
  await page.getByRole('button', { name: `Desativar ${titulo}` }).click()
  await expect(page.getByRole('listitem').filter({ hasText: titulo })).toHaveCount(0)
  await page.getByRole('button', { name: 'Inativas' }).click()
  await expect(page.getByRole('listitem').filter({ hasText: titulo })).toContainText('Inativa')

  await page.goto(conversa)
  await expect(page.getByText('Qualquer dúvida, estou aqui.')).toBeVisible()
  await page.getByRole('button', { name: 'Respostas rápidas' }).click()
  await page.getByRole('combobox', { name: 'Buscar resposta rápida' }).fill(atalho)
  await expect(page.getByText('Nenhuma resposta ativa encontrada.')).toBeVisible()
  await page.getByRole('combobox', { name: 'Buscar resposta rápida' }).press('Escape')
  await expect(campo).toBeFocused()
})
