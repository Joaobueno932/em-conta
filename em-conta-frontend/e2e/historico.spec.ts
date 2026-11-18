import { readFile } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'

import { expect, test, type Page } from '@playwright/test'

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
 * Histórico geral de ponta a ponta, com o canal no AMBIENTE CONTROLADO de teste (ver
 * whatsapp-controlado.ts): busca por nome, telefone e protocolo, período, paginação, lista vazia, dois
 * ciclos do mesmo contato, exportação da conversa e acesso negado por perfil. Os ciclos são criados pela
 * API (webhook assinado, assumir, responder, nota e encerrar); o histórico é usado pela tela. Cria
 * registros: rode contra o banco isolado de testes (ver README), nunca contra dados reais.
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
const aleatorio = (digitos: number) =>
  String(Math.floor(Math.random() * 10 ** digitos)).padStart(digitos, '0')
const protocolo = (id: number) => `ATD-${String(id).padStart(6, '0')}`
/** Dia do negócio (o período do histórico é em dias de Campo Grande). */
const diaDoNegocio = (deslocamentoDias = 0) =>
  new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Campo_Grande' }).format(
    new Date(Date.now() + deslocamentoDias * 86_400_000),
  )

async function cabecalhos(page: Page) {
  const xsrf = (await page.context().cookies()).find((c) => c.name === 'XSRF-TOKEN')?.value ?? ''
  return { 'X-XSRF-TOKEN': xsrf }
}

async function postar(page: Page, caminho: string, data: unknown, esperado: number) {
  const resposta = await page.request.post(`${BASE}${caminho}`, {
    data,
    headers: await cabecalhos(page),
  })
  expect(resposta.status(), caminho).toBe(esperado)
  return resposta
}

/** Ids dos atendimentos aguardando cuja busca encontra `busca`, quando chegarem `quantidade`. */
async function aguardando(page: Page, busca: string, quantidade: number) {
  let ids: number[] = []
  await expect
    .poll(
      async () => {
        const params = new URLSearchParams({ visao: 'AGUARDANDO', busca, tamanho: '100' })
        const pagina = (await (
          await page.request.get(`${BASE}/atendimentos?${params}`)
        ).json()) as {
          itens: { id: number }[]
        }
        ids = pagina.itens.map((i) => i.id)
        return ids.length
      },
      { timeout: 30_000 },
    )
    .toBe(quantidade)
  return ids
}

/** Assume, opcionalmente responde e anota, e encerra. */
async function fecharCiclo(page: Page, id: number, resposta?: string, nota?: string) {
  await postar(page, `/atendimentos/${id}/assuncao`, undefined, 204)
  if (resposta) {
    await postar(
      page,
      `/atendimentos/${id}/mensagens`,
      { texto: resposta, chaveIdempotencia: randomUUID() },
      202,
    )
  }
  if (nota) {
    await postar(
      page,
      `/atendimentos/${id}/notas`,
      { texto: nota, chaveIdempotencia: randomUUID() },
      201,
    )
  }
  const { versao } = (await (await page.request.get(`${BASE}/atendimentos/${id}`)).json()) as {
    versao: number
  }
  await postar(page, `/atendimentos/${id}/encerramento`, { versao }, 204)
}

async function filtrar(page: Page, busca: string, de = '', ate = '') {
  await page.getByLabel('Buscar contato').fill(busca)
  await page.getByLabel('De', { exact: true }).fill(de)
  await page.getByLabel('Até', { exact: true }).fill(ate)
  await page.getByRole('button', { name: 'Filtrar' }).click()
}

test('histórico geral: busca, filtros, paginação, dois ciclos, exportação e acesso por perfil', async ({
  browser,
}) => {
  test.setTimeout(240_000)
  const admin = await paginaDoAdministrador(browser)
  const { nome: eu } = (await (await admin.request.get('/api/v1/auth/sessao')).json()) as {
    nome: string
  }
  const marca = Date.now().toString(36).toUpperCase()
  const waA = `55679${aleatorio(8)}`
  const nomeA = `Histórico ${marca} Ana`

  // Dois ciclos do mesmo contato: o primeiro com resposta e nota interna.
  expect(
    (await webhookDeTeste.mensagem(admin.request, waA, nomeA, `Primeiro pedido ${marca}`)).status(),
  ).toBe(200)
  const [a1] = await aguardando(admin, waA, 1)
  await fecharCiclo(
    admin,
    a1!,
    `Resposta do primeiro ciclo ${marca}`,
    `Nota do primeiro ciclo ${marca}`,
  )
  expect(
    (await webhookDeTeste.mensagem(admin.request, waA, nomeA, `Segundo pedido ${marca}`)).status(),
  ).toBe(200)
  const [a2] = await aguardando(admin, waA, 1)
  expect(a2).not.toBe(a1)
  await fecharCiclo(admin, a2!, `Resposta do segundo ciclo ${marca}`)
  // Mais 20 contatos encerrados com a mesma marca: 22 resultados, duas páginas de 20.
  for (let i = 0; i < 20; i++) {
    const resposta = await webhookDeTeste.mensagem(
      admin.request,
      `55678${aleatorio(8)}`,
      `Histórico ${marca} Outro ${i}`,
      `Pedido ${i}`,
    )
    expect(resposta.status()).toBe(200)
  }
  for (const id of await aguardando(admin, `Histórico ${marca} Outro`, 20)) {
    await fecharCiclo(admin, id)
  }

  // 1. "Ver opções" abre o Histórico geral.
  await admin.goto('/atendimento')
  await admin.getByRole('button', { name: 'Ver opções' }).click()
  await admin.getByRole('link', { name: 'Histórico geral' }).last().click()
  await expect(admin.getByRole('heading', { name: 'Histórico geral', level: 1 })).toBeVisible()
  const linhas = admin.locator('tbody tr')

  // 2. Busca por nome e paginação.
  await filtrar(admin, `Histórico ${marca}`)
  await expect(admin.getByText('1 – 20 de 22')).toBeVisible()
  await expect(linhas).toHaveCount(20)
  await admin.getByRole('button', { name: 'Próxima página' }).click()
  await expect(admin.getByText('21 – 22 de 22')).toBeVisible()
  await expect(linhas).toHaveCount(2)
  await expect(admin).toHaveURL(/pagina=2/)

  // Telefone como o atendente digita: os dois ciclos do contato, com atendente e quem encerrou.
  const telefone = `(${waA.slice(2, 4)}) ${waA.slice(4, 9)}-${waA.slice(9)}`
  await filtrar(admin, telefone)
  await expect(linhas).toHaveCount(2)
  for (const id of [a1!, a2!]) {
    const linha = linhas.filter({ hasText: protocolo(id) })
    await expect(linha).toHaveCount(1)
    await expect(linha).toContainText(nomeA)
    // Colunas: Contato, Associado, Setor, Atendente, Encerrado por, Etiquetas, Data (sem classificação).
    await expect(linha.locator('td').nth(2)).toHaveText('—')
    await expect(linha.locator('td').nth(3)).toHaveText(eu)
    await expect(linha.locator('td').nth(4)).toHaveText(eu)
    await expect(linha.locator('td').nth(5)).toHaveText('Sem etiqueta')
  }

  // Protocolo + período de hoje (combinados); amanhã, lista vazia; período invertido é avisado.
  await filtrar(admin, protocolo(a1!), diaDoNegocio(), diaDoNegocio())
  await expect(linhas).toHaveCount(1)
  await expect(linhas.first()).toContainText(protocolo(a1!))
  await filtrar(admin, `Histórico ${marca}`, diaDoNegocio(1), diaDoNegocio(1))
  await expect(admin.getByText(/Nenhum resultado correspondente/)).toBeVisible()
  await filtrar(admin, `Histórico ${marca}`, diaDoNegocio(1), diaDoNegocio())
  await expect(admin.getByRole('alert')).toContainText('A data final deve ser igual ou posterior')
  await admin.getByRole('button', { name: 'Limpar' }).click()
  await expect(admin.getByLabel('Buscar contato')).toHaveValue('')

  // 3. O protocolo abre o histórico daquele ciclo, somente leitura.
  await filtrar(admin, telefone)
  await linhas
    .filter({ hasText: protocolo(a1!) })
    .getByRole('link', { name: nomeA })
    .click()
  await expect(admin.getByRole('navigation', { name: 'Trilha' })).toContainText(protocolo(a1!))
  await expect(admin.getByText(`Primeiro pedido ${marca}`)).toBeVisible()
  await expect(admin.getByText(`Resposta do primeiro ciclo ${marca}`)).toBeVisible()
  await expect(
    admin.getByRole('note').filter({ hasText: `Nota do primeiro ciclo ${marca}` }),
  ).toBeVisible()
  await expect(admin.getByText(`Segundo pedido ${marca}`)).toHaveCount(0)
  await expect(admin.getByText('Conversa encerrada · Somente leitura')).toBeVisible()
  await expect(admin.getByLabel('Mensagem')).toHaveCount(0)

  // 4. Exportação: o arquivo traz o ciclo aberto, com a nota, e nada do outro ciclo.
  const [download] = await Promise.all([
    admin.waitForEvent('download'),
    admin.getByRole('link', { name: 'Exportar conversa' }).click(),
  ])
  expect(download.suggestedFilename()).toBe(`conversa-${protocolo(a1!)}.txt`)
  const arquivo = await readFile((await download.path())!, 'utf8')
  expect(arquivo).toContain(`Protocolo: ${protocolo(a1!)}`)
  expect(arquivo).toContain(`Primeiro pedido ${marca}`)
  expect(arquivo).toContain(`Resposta do primeiro ciclo ${marca}`)
  expect(arquivo).toContain(`Nota do primeiro ciclo ${marca}`)
  expect(arquivo).toContain('encerrou o atendimento')
  expect(arquivo).not.toContain(`Segundo pedido ${marca}`)
  // A nota nunca foi ao WhatsApp (só as respostas).
  expect(graph.envios.some((e) => e.texto.includes(`Nota do primeiro ciclo ${marca}`))).toBe(false)
  expect(graph.envios.some((e) => e.texto === `Resposta do primeiro ciclo ${marca}`)).toBe(true)

  // Voltar mantém a consulta; o outro protocolo abre o outro ciclo.
  await admin.getByRole('link', { name: 'Voltar ao histórico geral' }).click()
  await expect(admin.getByLabel('Buscar contato')).toHaveValue(telefone)
  await linhas
    .filter({ hasText: protocolo(a2!) })
    .getByRole('link', { name: nomeA })
    .click()
  await expect(admin.getByRole('navigation', { name: 'Trilha' })).toContainText(protocolo(a2!))
  await expect(admin.getByText(`Segundo pedido ${marca}`)).toBeVisible()
  await expect(admin.getByText(`Primeiro pedido ${marca}`)).toHaveCount(0)
  await expect(admin.getByRole('note')).toHaveCount(0)

  // E de volta à tela de Atendimento.
  await admin.getByRole('link', { name: 'Voltar ao histórico geral' }).click()
  await admin.getByRole('link', { name: 'Voltar ao atendimento' }).click()
  await expect(admin).toHaveURL(/\/atendimento$/)
  await expect(admin.getByRole('button', { name: 'Ver opções' })).toBeVisible()

  // 5. Perfil sem atendimento: nem a tela nem a API abrem, inclusive por link direto.
  const { email, page: semAcesso } = await criarUsuarioAtivo(
    browser,
    admin,
    `Sem Atendimento ${marca}`,
  )
  await entrar(semAcesso, email, SENHA_PADRAO)
  await expect(botaoConta(semAcesso)).toBeVisible()
  await semAcesso.goto(`/atendimento/historico/${a1}`)
  await expect(semAcesso.getByRole('heading', { name: 'Acesso negado' })).toBeVisible()
  await expect(semAcesso.getByText(`Primeiro pedido ${marca}`)).toHaveCount(0)
  for (const caminho of [
    `/historico?busca=${encodeURIComponent(marca)}`,
    `/atendimentos/${a1}`,
    `/atendimentos/${a1}/linha-do-tempo?somenteEste=true`,
    `/atendimentos/${a1}/exportacao`,
  ]) {
    expect((await semAcesso.request.get(`${BASE}${caminho}`)).status(), caminho).toBe(403)
  }
})
