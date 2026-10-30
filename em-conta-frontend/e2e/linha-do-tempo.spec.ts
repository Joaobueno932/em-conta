import { expect, test, type Locator, type Page } from '@playwright/test'

import { ADMIN_EMAIL, ADMIN_SENHA, paginaDoAdministrador } from './apoio'
import { NUMERO_TESTE, SEGREDO_TESTE, webhookDeTeste } from './whatsapp-controlado'

/**
 * Posição de leitura da linha do tempo medida no navegador real: uma mensagem visível serve de
 * referência e sua distância até o topo da área de mensagens não pode mudar quando entram mensagens
 * anteriores, novas ou uma rajada maior que uma página (lacuna recuperada). Webhooks assinados de teste;
 * banco isolado de testes.
 */
// eslint-disable-next-line playwright/no-skipped-test -- sem a API de testes configurada, a jornada não pode rodar
test.skip(
  !ADMIN_EMAIL || !ADMIN_SENHA || !SEGREDO_TESTE || !NUMERO_TESTE,
  'Defina E2E_ADMIN_*, E2E_WHATSAPP_APP_SECRET e E2E_WHATSAPP_PHONE_NUMBER_ID (ver README)',
)

const TOLERANCIA_PX = 4

async function distanciaAoTopo(area: Locator, referencia: Locator) {
  const [a, r] = await Promise.all([area.boundingBox(), referencia.boundingBox()])
  return r!.y - a!.y
}

async function enviarLote(
  page: Page,
  waId: string,
  nome: string,
  prefixo: string,
  quantas: number,
) {
  for (let i = 1; i <= quantas; i++) {
    expect(
      (await webhookDeTeste.mensagem(page.request, waId, nome, `${prefixo} ${i}`)).status(),
    ).toBe(200)
  }
}

test('mensagens anteriores, novas e uma rajada maior que a página não movem a leitura', async ({
  browser,
}) => {
  test.setTimeout(120_000)
  const page = await paginaDoAdministrador(browser)
  const marca = Date.now().toString(36).toUpperCase()
  const waId = `55679${String(Math.floor(Math.random() * 1e8)).padStart(8, '0')}`
  const nome = `Contato Rolagem ${marca}`

  await enviarLote(page, waId, nome, 'Antiga', 70)
  await page.getByRole('link', { name: 'Atendimento', exact: true }).click()
  await expect(async () => {
    await page.getByLabel('Buscar contato').fill(nome)
    await page.getByLabel('Buscar contato').press('Enter')
    await page.getByRole('button', { name: new RegExp(nome) }).click({ timeout: 3_000 })
  }).toPass({ timeout: 30_000 })

  const area = page.getByLabel('Mensagens', { exact: true })
  await expect(area.getByText('Antiga 70', { exact: true })).toBeVisible({ timeout: 20_000 })
  // Reabre com tudo já processado: a página recente traz só as 50 últimas (21..70).
  await page.reload()
  await expect(area.getByText('Antiga 70', { exact: true })).toBeVisible({ timeout: 20_000 })
  await expect(area.getByText('Antiga 20', { exact: true })).toHaveCount(0)
  // Lê o começo da página carregada, com o botão de anteriores visível: a referência é a primeira
  // mensagem, medida depois de rolar (clicar no botão não rola mais nada).
  await area.evaluate((el) => (el.scrollTop = 0))
  const referencia = area.getByText('Antiga 21', { exact: true })
  await expect(referencia).toBeInViewport()
  const antes = await distanciaAoTopo(area, referencia)

  // 1. Mensagens anteriores entram acima.
  await area.getByRole('button', { name: 'Carregar mensagens anteriores' }).click()
  await expect(area.getByText('Antiga 1', { exact: true })).toBeAttached()
  expect(Math.abs((await distanciaAoTopo(area, referencia)) - antes)).toBeLessThanOrEqual(
    TOLERANCIA_PX,
  )

  // 2. Mensagens novas entram abaixo, sem tirar quem está lendo.
  await enviarLote(page, waId, nome, 'Nova', 3)
  await expect(area.getByText('Nova 3', { exact: true })).toBeAttached({ timeout: 20_000 })
  expect(Math.abs((await distanciaAoTopo(area, referencia)) - antes)).toBeLessThanOrEqual(
    TOLERANCIA_PX,
  )

  // 3. Rajada maior que uma página entre duas consultas: a lacuna é recuperada por inteiro.
  await enviarLote(page, waId, nome, 'Rajada', 60)
  await expect(area.getByText('Rajada 60', { exact: true })).toBeAttached({ timeout: 30_000 })
  for (const n of [1, 10, 30, 59]) {
    await expect(area.getByText(`Rajada ${n}`, { exact: true })).toBeAttached({ timeout: 20_000 })
  }
  await expect(area.getByText(/^Rajada \d+$/)).toHaveCount(60)
  expect(Math.abs((await distanciaAoTopo(area, referencia)) - antes)).toBeLessThanOrEqual(
    TOLERANCIA_PX,
  )
  await expect(page.getByText('Algumas mensagens recentes ainda não foram carregadas')).toHaveCount(
    0,
  )
})
