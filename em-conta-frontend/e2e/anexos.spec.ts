import { randomUUID } from 'node:crypto'

import { expect, test } from '@playwright/test'

import { ADMIN_EMAIL, ADMIN_SENHA, paginaDoAdministrador } from './apoio'
import { GraphControlada, NUMERO_TESTE, SEGREDO_TESTE, webhookDeTeste } from './whatsapp-controlado'

/**
 * Anexos de ponta a ponta contra o backend real (MySQL e MinIO do ambiente de testes), com o canal no
 * AMBIENTE EXTERNO CONTROLADO de teste: nenhum arquivo vai para a Meta nem para o número oficial.
 * Arquivos sintéticos e pequenos, só com as assinaturas dos formatos.
 */
// eslint-disable-next-line playwright/no-skipped-test -- sem a API de testes configurada, a jornada não pode rodar
test.skip(
  !ADMIN_EMAIL || !ADMIN_SENHA || !SEGREDO_TESTE || !NUMERO_TESTE,
  'Defina E2E_ADMIN_*, E2E_WHATSAPP_APP_SECRET e E2E_WHATSAPP_PHONE_NUMBER_ID (ver README)',
)

const graph = new GraphControlada()

test.beforeAll(() => graph.iniciar())

test.afterAll(() => graph.parar())

const PNG = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  Buffer.from('imagem de teste e2e'),
])
/** As duas caixas se sobrepõem na tela? */
function sobrepoem(
  a: { x: number; y: number; width: number; height: number },
  b: { x: number; y: number; width: number; height: number },
) {
  return a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height
}

const PDF = Buffer.from('%PDF-1.4\n% documento de teste e2e\n%%EOF\n')

test('recebe anexo, abre e baixa, envia anexo, acompanha a situação, consulta Mídia e o histórico', async ({
  browser,
}) => {
  test.setTimeout(120_000)
  const page = await paginaDoAdministrador(browser)
  const marca = Date.now().toString(36).toUpperCase()
  const waId = `55679${String(Math.floor(Math.random() * 1e8)).padStart(8, '0')}`
  const nome = `Contato Anexo ${marca}`

  // 1. Imagem e PDF recebidos por webhook assinado de teste; o download é feito em segundo plano.
  const idImagem = `e2e-img-${randomUUID()}`
  const idPdf = `e2e-pdf-${randomUUID()}`
  const shaImagem = graph.midia(idImagem, PNG, 'image/png')
  const shaPdf = graph.midia(idPdf, PDF, 'application/pdf')
  expect(
    (
      await webhookDeTeste.midia(page.request, waId, nome, 'image', {
        id: idImagem,
        mime: 'image/png',
        sha256: shaImagem,
        legenda: 'Foto do medidor',
      })
    ).status(),
  ).toBe(200)
  expect(
    (
      await webhookDeTeste.midia(page.request, waId, nome, 'document', {
        id: idPdf,
        mime: 'application/pdf',
        sha256: shaPdf,
        nomeArquivo: 'fatura-outubro.pdf',
      })
    ).status(),
  ).toBe(200)

  await page.getByRole('link', { name: 'Atendimento', exact: true }).click()
  await expect(async () => {
    await page.getByLabel('Buscar contato').fill(nome)
    await page.getByLabel('Buscar contato').press('Enter')
    await page.getByRole('button', { name: new RegExp(nome) }).click({ timeout: 3_000 })
  }).toPass({ timeout: 20_000 })

  // 2. Abrir a imagem e baixar o PDF pela rota autenticada.
  const imagem = page.getByRole('img', { name: 'Foto do medidor' })
  await expect(imagem).toBeVisible({ timeout: 20_000 })
  const src = (await imagem.getAttribute('src'))!
  expect(src).toMatch(/^\/api\/v1\/atendimento\/atendimentos\/\d+\/anexos\/\d+\/conteudo$/)
  const conteudo = await page.request.get(src)
  expect(conteudo.status()).toBe(200)
  expect(conteudo.headers()['content-type']).toBe('image/png')
  expect(conteudo.headers()['x-content-type-options']).toBe('nosniff')
  expect(Buffer.from(await conteudo.body())).toEqual(PNG)
  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('link', { name: 'Baixar fatura-outubro.pdf' }).click(),
  ])
  expect(download.suggestedFilename()).toBe('fatura-outubro.pdf')

  // 3. Assumir e enviar uma imagem com legenda.
  await page.getByRole('button', { name: 'Assumir' }).click()
  await expect(page.getByText(/Em atendimento com/)).toBeVisible()
  await page.locator('input[type=file]').setInputFiles({
    name: 'comprovante.png',
    mimeType: 'image/png',
    buffer: PNG,
  })
  await expect(page.getByText('comprovante.png')).toBeVisible()
  await expect(page.getByText(/Imagem · 1 KB/)).toBeVisible()
  await page.getByLabel('Legenda (opcional)').fill('Segue o comprovante')
  await page.getByRole('button', { name: 'Enviar' }).click()
  await expect(page.getByRole('group', { name: 'Arquivo selecionado' })).toHaveCount(0)
  await expect
    .poll(() => graph.envios.filter((e) => e.para === waId).length, { timeout: 15_000 })
    .toBe(1)
  const envio = graph.envios.find((e) => e.para === waId)!
  expect(envio).toMatchObject({ tipo: 'image', legenda: 'Segue o comprovante' })
  expect(envio.idMidia).toMatch(/^midia-e2e-/)
  expect(graph.uploads.some((u) => u.tipo === 'image/png')).toBe(true)
  // Upload e aceite não são entrega: só o webhook muda para "Entregue".
  await expect(page.getByText(/Aceita pelo WhatsApp/)).toBeVisible({ timeout: 15_000 })
  expect(
    (await webhookDeTeste.situacao(page.request, waId, envio.idExterno, 'delivered')).status(),
  ).toBe(200)
  await expect(page.getByText(/· Entregue/)).toBeVisible({ timeout: 15_000 })

  // 4. Mídia: os três anexos, com filtro por tipo.
  const midia = page.locator('section.midia')
  await expect(midia.getByText('fatura-outubro.pdf')).toBeVisible()
  await expect(midia.getByText('comprovante.png')).toBeVisible()
  await midia.getByRole('button', { name: 'Documentos' }).click()
  await expect(midia.getByText('comprovante.png')).toHaveCount(0)
  await expect(midia.getByText('fatura-outubro.pdf')).toBeVisible()

  // 5. Encerrar e conferir o histórico do ciclo, somente leitura.
  page.once('dialog', (d) => d.accept())
  await page.getByRole('button', { name: 'Encerrar' }).click()
  await expect(page.getByText('Encerrado', { exact: true })).toBeVisible()
  const id = new URL(page.url()).searchParams.get('id')
  await page.goto(`/atendimento/historico/${id}`)
  await expect(page.getByRole('navigation', { name: 'Trilha' })).toContainText(
    /Detalhes do atendimento ATD-\d+/,
  )
  const imagemHistorico = page.getByRole('img', { name: 'Segue o comprovante' })
  await expect(imagemHistorico).toBeVisible()
  expect(await imagemHistorico.getAttribute('src')).toContain('somenteEste=true')
  await expect(page.getByRole('button', { name: 'Enviar' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Anexar arquivo' })).toHaveCount(0)
})

test('Mídia abre mensagem antiga não carregada; teclado e painéis sem sobreposição', async ({
  browser,
}) => {
  test.setTimeout(120_000)
  const page = await paginaDoAdministrador(browser)
  const marca = Date.now().toString(36).toUpperCase()
  const waId = `55679${String(Math.floor(Math.random() * 1e8)).padStart(8, '0')}`
  const nome = `Contato Mídia Antiga ${marca}`
  const idPdf = `e2e-antigo-${randomUUID()}`
  const sha = graph.midia(idPdf, PDF, 'application/pdf')
  expect(
    (
      await webhookDeTeste.midia(page.request, waId, nome, 'document', {
        id: idPdf,
        mime: 'application/pdf',
        sha256: sha,
        nomeArquivo: 'contrato-antigo.pdf',
      })
    ).status(),
  ).toBe(200)
  // Mais mensagens do que cabem na página recente: o PDF fica fora do que a conversa carrega.
  for (let i = 1; i <= 60; i++) {
    await webhookDeTeste.mensagem(page.request, waId, nome, `Depois ${i}`)
  }
  await page.setViewportSize({ width: 1600, height: 900 })
  await page.getByRole('link', { name: 'Atendimento', exact: true }).click()
  await expect(async () => {
    await page.getByLabel('Buscar contato').fill(nome)
    await page.getByLabel('Buscar contato').press('Enter')
    await page.getByRole('button', { name: new RegExp(nome) }).click({ timeout: 3_000 })
  }).toPass({ timeout: 30_000 })
  const area = page.getByLabel('Mensagens', { exact: true })
  await expect(area.getByText('Depois 60', { exact: true })).toBeVisible({ timeout: 30_000 })
  await page.reload()
  await expect(area.getByText('Depois 60', { exact: true })).toBeVisible({ timeout: 20_000 })
  await expect(area.getByText('contrato-antigo.pdf')).toHaveCount(0)

  // Ver na conversa: carrega o contexto, rola até a mensagem, foca e destaca.
  const midia = page.locator('section.midia')
  await expect(midia.getByText('contrato-antigo.pdf')).toBeVisible({ timeout: 20_000 })
  await midia.getByRole('button', { name: 'Ver na conversa' }).first().click()
  const alvo = area.locator('.mensagem', { hasText: 'contrato-antigo.pdf' })
  await expect(alvo).toBeInViewport()
  await expect(alvo).toBeFocused()
  await expect(alvo).toHaveClass(/destacada/)
  await expect(page.getByText('Esta mensagem não está disponível')).toHaveCount(0)

  // Painéis lado a lado sem sobreposição em tela larga; em tela menor o painel vai para baixo.
  const caixa = async (seletor: string) => (await page.locator(seletor).first().boundingBox())!
  expect(sobrepoem(await caixa('section.conversa'), await caixa('#painel-associado'))).toBe(false)
  expect(sobrepoem(await caixa('section.fila'), await caixa('section.conversa'))).toBe(false)
  await page.setViewportSize({ width: 1000, height: 900 })
  const conversa = await caixa('section.conversa')
  const painel = await caixa('#painel-associado')
  expect(sobrepoem(conversa, painel)).toBe(false)
  expect(painel.y).toBeGreaterThanOrEqual(conversa.y + conversa.height)
  // O compositor continua alcançável (sem ação escondida atrás de outro painel).
  const mensagem = page.getByLabel('Mensagem')
  await mensagem.scrollIntoViewIfNeeded()
  await expect(mensagem).toBeInViewport()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  )

  // Teclado: "Ver opções" abre e fecha com Esc devolvendo o foco; o X do painel devolve o foco ao
  // botão que o reabre.
  const verOpcoes = page.getByRole('button', { name: 'Ver opções' })
  await verOpcoes.focus()
  await page.keyboard.press('Enter')
  await expect(verOpcoes).toHaveAttribute('aria-expanded', 'true')
  await page.keyboard.press('Tab')
  await expect(page.getByRole('link', { name: 'Histórico geral' })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(verOpcoes).toBeFocused()
  await expect(verOpcoes).toHaveAttribute('aria-expanded', 'false')
  await page.getByRole('button', { name: 'Fechar painel do associado' }).focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('button', { name: 'Dados do associado' })).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('button', { name: 'Fechar painel do associado' })).toBeFocused()
})
