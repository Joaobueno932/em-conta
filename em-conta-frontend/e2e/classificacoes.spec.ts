import { expect, test, type Page } from '@playwright/test'

import { desativarAtivos, PREFIXO_E2E } from './ambiente'
import {
  ADMIN_EMAIL,
  ADMIN_SENHA,
  botaoConta,
  criarUsuarioAtivo,
  entrar,
  paginaDoAdministrador,
  SENHA_PADRAO,
} from './apoio'
import { GraphControlada, NUMERO_TESTE, SEGREDO_TESTE, webhookDeTeste } from './whatsapp-controlado'

/**
 * Setores e etiquetas de ponta a ponta, com o canal no AMBIENTE CONTROLADO de teste (ver
 * whatsapp-controlado.ts): cadastro pela tela, classificação do ciclo pelo painel da conversa, dois
 * ciclos do mesmo contato (o anterior preservado), filtros de setor e etiqueta no Histórico geral com
 * busca, item desativado depois e acesso negado por perfil. Cria registros: rode contra o banco isolado
 * de testes (ver README), nunca contra dados reais. Os itens criados têm o prefixo E2E e ficam ligados aos
 * ciclos da jornada; no fim, os que ainda estão ativos são desativados (ver e2e/ambiente.ts).
 */
// eslint-disable-next-line playwright/no-skipped-test -- sem a API de testes configurada, a jornada não pode rodar
test.skip(
  !ADMIN_EMAIL || !ADMIN_SENHA || !SEGREDO_TESTE || !NUMERO_TESTE,
  'Defina E2E_ADMIN_*, E2E_WHATSAPP_APP_SECRET e E2E_WHATSAPP_PHONE_NUMBER_ID (ver README)',
)

const graph = new GraphControlada()

test.beforeAll(() => graph.iniciar())

/** Itens criados na jornada, desativados no fim mesmo se ela falhar no meio. */
const criados: { setores: string[]; etiquetas: string[] } = { setores: [], etiquetas: [] }

test.afterEach(async ({ browser }) => {
  if (criados.setores.length + criados.etiquetas.length === 0) return
  const admin = await paginaDoAdministrador(browser)
  await desativarAtivos(admin.request, 'setores', criados.setores)
  await desativarAtivos(admin.request, 'etiquetas', criados.etiquetas)
  await admin.context().close()
})

test.afterAll(() => graph.parar())

const BASE = '/api/v1/atendimento'
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

/** Mensagem do contato e o id do ciclo aberto que ela cria (aguardando). */
async function novoCiclo(page: Page, waId: string, nome: string, texto: string) {
  expect((await webhookDeTeste.mensagem(page.request, waId, nome, texto)).status()).toBe(200)
  let id = 0
  await expect
    .poll(
      async () => {
        const params = new URLSearchParams({ visao: 'AGUARDANDO', busca: waId })
        const pagina = (await (
          await page.request.get(`${BASE}/atendimentos?${params}`)
        ).json()) as { itens: { id: number }[] }
        id = pagina.itens[0]?.id ?? 0
        return id
      },
      { timeout: 30_000 },
    )
    .toBeGreaterThan(0)
  return id
}

async function assumir(page: Page, id: number) {
  expect((await enviar(page, 'POST', `/atendimentos/${id}/assuncao`, undefined)).status()).toBe(204)
}

async function encerrar(page: Page, id: number) {
  const resposta = await enviar(page, 'POST', `/atendimentos/${id}/encerramento`, {
    versao: await versao(page, id),
  })
  expect(resposta.status()).toBe(204)
}

async function criarCadastro(page: Page, cadastro: 'setores' | 'etiquetas', nome: string) {
  const resposta = await enviar(page, 'POST', `/${cadastro}`, { nome })
  expect(resposta.status()).toBe(201)
  return ((await resposta.json()) as { id: number }).id
}

test('setores e etiquetas: cadastro, classificação no painel, dois ciclos e filtros do histórico', async ({
  browser,
}) => {
  test.setTimeout(240_000)
  const admin = await paginaDoAdministrador(browser)
  const marca = Date.now().toString(36).toUpperCase()
  const financeiro = `${PREFIXO_E2E} Financeiro ${marca}`
  const comercial = `${PREFIXO_E2E} Comercial ${marca}`
  const segundaVia = `${PREFIXO_E2E} Segunda via ${marca}`
  criados.setores.push(financeiro, comercial)
  criados.etiquetas.push(segundaVia)

  // 1. Cadastro pela tela (Ver opções → Setores e etiquetas); o restante pela API.
  await admin.goto('/atendimento')
  await admin.getByRole('button', { name: 'Ver opções' }).click()
  await admin.getByRole('link', { name: 'Setores e etiquetas' }).click()
  await expect(admin.getByRole('heading', { name: 'Setores e etiquetas', level: 1 })).toBeVisible()
  const setores = admin.getByRole('region', { name: 'Setores' })
  await setores.getByRole('button', { name: 'Novo setor' }).click()
  await setores.getByLabel(/Nome/).fill(`  ${financeiro}  `)
  await setores.getByRole('button', { name: 'Salvar' }).click()
  await expect(setores.getByText(financeiro, { exact: true })).toBeVisible()
  // Mesmo nome em minúsculas: recusado junto do campo.
  await setores.getByRole('button', { name: 'Novo setor' }).click()
  await setores.getByLabel(/Nome/).fill(financeiro.toLowerCase())
  await setores.getByRole('button', { name: 'Salvar' }).click()
  await expect(setores.getByText(/Já existe um setor com este nome/).first()).toBeVisible()
  await setores.getByRole('button', { name: 'Cancelar' }).click()
  const etiquetas = admin.getByRole('region', { name: 'Etiquetas' })
  await etiquetas.getByRole('button', { name: 'Nova etiqueta' }).click()
  await etiquetas.getByLabel(/Nome/).fill(segundaVia)
  await etiquetas.getByRole('button', { name: 'Salvar' }).click()
  await expect(etiquetas.getByText(segundaVia, { exact: true })).toBeVisible()
  const comercialId = await criarCadastro(admin, 'setores', comercial)

  // 2. Primeiro ciclo do contato A, classificado pelo painel da conversa.
  const waA = `55679${aleatorio(8)}`
  const nomeA = `Classificação ${marca} Ana`
  const a1 = await novoCiclo(admin, waA, nomeA, `Primeiro pedido ${marca}`)
  await assumir(admin, a1)
  await admin.goto(`/atendimento?id=${a1}`)
  const painel = admin.getByRole('complementary', { name: 'Dados do associado' })
  await expect(painel.getByText('Sem etiqueta')).toBeVisible()
  await painel.getByRole('button', { name: 'Classificar' }).click()
  await painel.getByLabel('Setor', { exact: true }).selectOption({ label: financeiro })
  await painel.getByLabel(segundaVia).check()
  await painel.getByRole('button', { name: 'Salvar classificação' }).click()
  await expect(painel.getByText(financeiro, { exact: true })).toBeVisible()
  await expect(painel.getByText(segundaVia, { exact: true })).toBeVisible()
  await expect(admin.getByText(/classificou o atendimento/)).toBeVisible()
  await encerrar(admin, a1)

  // 3. Segundo ciclo do mesmo contato: começa sem classificação e recebe outro setor.
  const a2 = await novoCiclo(admin, waA, nomeA, `Segundo pedido ${marca}`)
  const novo = (await (await admin.request.get(`${BASE}/atendimentos/${a2}`)).json()) as {
    resumo: { setor?: unknown; etiquetas: unknown[] }
  }
  expect(novo.resumo.setor ?? null).toBeNull()
  expect(novo.resumo.etiquetas).toEqual([])
  await assumir(admin, a2)
  const classificado = await enviar(admin, 'PUT', `/atendimentos/${a2}/classificacao`, {
    versao: await versao(admin, a2),
    setorId: comercialId,
    etiquetaIds: [],
  })
  expect(classificado.status()).toBe(204)
  await encerrar(admin, a2)

  // Contato B, encerrado sem classificação (como os ciclos antigos).
  const waB = `55679${aleatorio(8)}`
  const b1 = await novoCiclo(admin, waB, `Classificação ${marca} Bruno`, `Pedido ${marca}`)
  await assumir(admin, b1)
  await encerrar(admin, b1)

  // 4. Histórico geral: setor e etiqueta combinados com a busca.
  await admin.goto('/atendimento/historico')
  const linhas = admin.getByRole('row').filter({ hasText: `Classificação ${marca}` })
  async function filtrar(setor: string, etiqueta: string) {
    await admin.getByLabel('Buscar contato').fill(`Classificação ${marca}`)
    await admin.getByLabel('Setor', { exact: true }).selectOption({ label: setor })
    await admin.getByLabel('Etiquetas', { exact: true }).selectOption({ label: etiqueta })
    await admin.getByRole('button', { name: 'Filtrar' }).click()
  }
  await filtrar('Todos', 'Todas')
  await expect(linhas).toHaveCount(3)
  await filtrar(financeiro, 'Todas')
  await expect(linhas).toHaveCount(1)
  await expect(linhas.first()).toContainText(segundaVia)
  await filtrar(comercial, 'Todas')
  await expect(linhas).toHaveCount(1)
  await expect(linhas.first()).toContainText('Sem etiqueta')
  await filtrar('Sem setor', 'Sem etiqueta')
  await expect(linhas).toHaveCount(1)
  await expect(linhas.first()).toContainText('Bruno')
  await filtrar(comercial, segundaVia)
  await expect(admin.getByText(/Nenhum resultado correspondente/)).toBeVisible()
  await expect(admin).toHaveURL(/setor=\d+/)

  // Cada protocolo abre o próprio ciclo com a sua classificação.
  await filtrar(financeiro, segundaVia)
  await linhas.first().getByRole('link').click()
  await expect(admin.getByText(`Primeiro pedido ${marca}`)).toBeVisible()
  await expect(admin.getByText(`Segundo pedido ${marca}`)).toHaveCount(0)
  await expect(admin.getByRole('definition').filter({ hasText: financeiro })).toBeVisible()
  await admin.getByRole('link', { name: 'Voltar ao histórico geral' }).click()
  await expect(admin.getByLabel('Setor', { exact: true })).toHaveValue(/\d+/)

  // 5. Desativado depois, o setor continua no ciclo e no filtro, marcado como inativo.
  await admin.goto('/atendimento/setores-etiquetas')
  await setores.getByRole('button', { name: `Desativar ${financeiro}` }).click()
  await expect(setores.getByText(financeiro, { exact: true })).toHaveCount(0)
  await admin.goto('/atendimento/historico')
  await filtrar(`${financeiro} (inativo)`, 'Todas')
  await expect(linhas).toHaveCount(1)
  await expect(linhas.first()).toContainText(`${financeiro} (inativo)`)

  // 6. Sem perfil de atendimento: nem a tela nem a API, inclusive por link direto.
  const { email, page: semAcesso } = await criarUsuarioAtivo(
    browser,
    admin,
    `Sem Classificação ${marca}`,
  )
  await entrar(semAcesso, email, SENHA_PADRAO)
  await expect(botaoConta(semAcesso)).toBeVisible()
  await semAcesso.goto('/atendimento/setores-etiquetas')
  await expect(semAcesso.getByRole('heading', { name: 'Acesso negado' })).toBeVisible()
  expect((await semAcesso.request.get(`${BASE}/setores`)).status()).toBe(403)
  expect(
    (await enviar(semAcesso, 'POST', '/etiquetas', { nome: `Indevida ${marca}` })).status(),
  ).toBe(403)
  expect(
    (
      await enviar(semAcesso, 'PUT', `/atendimentos/${a1}/classificacao`, {
        versao: 0,
        etiquetaIds: [],
      })
    ).status(),
  ).toBe(403)
})
