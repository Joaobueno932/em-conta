import { expect, test, type Page } from '@playwright/test'

import {
  abrirUsuarioNaLista,
  ADMIN_EMAIL,
  ADMIN_SENHA,
  criarUsuarioAtivo,
  entrar,
  paginaDoAdministrador,
  SENHA_PADRAO,
} from './apoio'
import { GraphControlada, NUMERO_TESTE, SEGREDO_TESTE, webhookDeTeste } from './whatsapp-controlado'

/**
 * Jornada de atendimento contra o backend real, com o canal num AMBIENTE EXTERNO CONTROLADO de
 * teste (ver whatsapp-controlado.ts): nenhuma mensagem vai para a Meta nem para o número oficial.
 * Cria registros: rode contra o banco isolado de testes (ver README), nunca contra dados reais.
 */
// eslint-disable-next-line playwright/no-skipped-test -- sem a API de testes configurada, a jornada não pode rodar
test.skip(
  !ADMIN_EMAIL || !ADMIN_SENHA || !SEGREDO_TESTE || !NUMERO_TESTE,
  'Defina E2E_ADMIN_*, E2E_WHATSAPP_APP_SECRET e E2E_WHATSAPP_PHONE_NUMBER_ID (ver README)',
)

const graph = new GraphControlada()

test.beforeAll(() => graph.iniciar())

test.afterAll(() => graph.parar())

const aleatorio = (digitos: number) =>
  String(Math.floor(Math.random() * 10 ** digitos)).padStart(digitos, '0')

async function cadastrarPjComTelefone(page: Page, razao: string, telefone: string) {
  await page.goto('/associados/novo')
  await page.getByLabel(/Pessoa jurídica/).check()
  await page.getByLabel(/Razão social/).fill(razao)
  // CNPJ numérico com dígitos verificadores válidos.
  const base = aleatorio(12)
  const dv = (b: string) => {
    let soma = 0
    let peso = 2
    for (let i = b.length - 1; i >= 0; i--) {
      soma += Number(b[i]) * peso
      peso = peso === 9 ? 2 : peso + 1
    }
    return soma % 11 < 2 ? 0 : 11 - (soma % 11)
  }
  const d1 = dv(base)
  await page.getByLabel(/^CNPJ/).fill(`${base}${d1}${dv(`${base}${d1}`)}`)
  await page.getByRole('button', { name: 'Adicionar contato' }).click()
  const contato = page.getByRole('group', { name: 'Contato 1' })
  await contato.getByLabel(/^Nome/).fill('Contato WhatsApp')
  await contato.getByLabel('Telefone').fill(telefone)
  await page.getByRole('button', { name: 'Salvar associado' }).click()
  await expect(page.getByRole('heading', { name: razao, level: 1 })).toBeVisible()
}

async function abrirNaFila(page: Page, busca: string, rotulo: string) {
  await page.getByLabel('Buscar contato').fill(busca)
  await page.getByLabel('Buscar contato').press('Enter')
  await page.getByRole('button', { name: new RegExp(rotulo) }).click({ timeout: 3_000 })
}

test('recebe, assume, responde, vincula associado, devolve, encerra e consulta o histórico', async ({
  browser,
}) => {
  test.setTimeout(120_000)
  const page = await paginaDoAdministrador(browser)
  const marca = Date.now().toString(36).toUpperCase()
  const final = aleatorio(8)
  const waId = `55679${final}`
  const nome = `Contato E2E ${marca}`
  const razao = `Cooperativa Atendimento ${marca}`

  await cadastrarPjComTelefone(page, razao, `(67) 9${final.slice(0, 4)}-${final.slice(4)}`)

  // 1. Mensagem recebida (webhook assinado de teste) → novo atendimento na fila.
  const recebido = await webhookDeTeste.mensagem(
    page.request,
    waId,
    nome,
    'Olá, quero saber da fatura',
  )
  expect(recebido.status()).toBe(200)

  await page.getByRole('link', { name: 'Atendimento', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Atendimento', level: 1 })).toBeVisible()
  await expect(page.getByText('Canal não configurado')).toHaveCount(0)
  // O evento entra na caixa de entrada e é processado em segundo plano: a fila atualiza sozinha.
  await expect(async () => {
    await abrirNaFila(page, nome, nome)
  }).toPass({ timeout: 20_000 })
  await expect(page.getByText('Olá, quero saber da fatura')).toBeVisible()
  await expect(page.getByText('Aguardando atendimento')).toBeVisible()

  // 2. Assumir e responder; o envio sai pelo servidor controlado, fora da transação.
  await page.getByRole('button', { name: 'Assumir' }).click()
  await expect(page.getByText(/Em atendimento com/)).toBeVisible()
  await page.getByLabel('Mensagem').fill('Bom dia! Vou verificar sua fatura.')
  await page.getByRole('button', { name: 'Enviar' }).click()
  await expect(page.getByLabel('Mensagem')).toHaveValue('')
  await expect.poll(() => graph.envios.length, { timeout: 15_000 }).toBe(1)
  expect(graph.envios[0]).toMatchObject({ para: waId, texto: 'Bom dia! Vou verificar sua fatura.' })
  expect(graph.envios[0]!.caminho).toContain(`/${NUMERO_TESTE}/messages`)
  // Aceita pela API não é entregue: só o status do webhook muda para "Entregue".
  await expect(page.getByText(/Aceita pelo WhatsApp/)).toBeVisible({ timeout: 15_000 })
  const entregue = await webhookDeTeste.situacao(
    page.request,
    waId,
    graph.envios[0]!.idExterno,
    'delivered',
  )
  expect(entregue.status()).toBe(200)
  await expect(page.getByText(/· Entregue/)).toBeVisible({ timeout: 15_000 })

  // 3. Vincular o associado sugerido pelo telefone (escolha do atendente, não automática).
  const painel = page.getByRole('complementary', { name: 'Associado' })
  await painel.getByRole('radio', { name: new RegExp(razao) }).check()
  await painel.getByRole('button', { name: 'Vincular associado' }).click()
  await expect(painel.getByRole('link', { name: 'Abrir ficha' })).toBeVisible()
  await expect(page.getByRole('heading', { name: razao, level: 2 })).toBeVisible()

  // 4. Transferir: devolver à fila, com motivo; depois assumir de novo e encerrar.
  await page.getByRole('button', { name: 'Transferir' }).click()
  await page.getByLabel('Transferir para').selectOption({ label: 'Devolver à fila' })
  await page.getByLabel('Motivo (opcional)').fill('Teste de devolução')
  await page.getByRole('button', { name: 'Confirmar transferência' }).click()
  await expect(page.getByText('Aguardando atendimento')).toBeVisible()
  await expect(page.getByText(/devolveu o atendimento à fila/)).toBeVisible()

  await page.getByRole('button', { name: 'Assumir' }).click()
  await expect(page.getByText(/Em atendimento com/)).toBeVisible()
  page.once('dialog', (d) => d.accept())
  await page.getByRole('button', { name: 'Encerrar' }).click()
  await expect(page.getByText('Encerrado', { exact: true })).toBeVisible()
  await expect(page.getByLabel('Mensagem')).toBeDisabled()

  // 5. Histórico geral (pelo menu "Ver opções") e detalhe somente leitura.
  await page.getByRole('button', { name: 'Ver opções' }).click()
  await page.getByRole('link', { name: 'Histórico geral' }).last().click()
  await expect(page.getByRole('heading', { name: 'Histórico geral' })).toBeVisible()
  const linha = page.getByRole('row', { name: new RegExp(razao) })
  await expect(linha).toHaveCount(1)
  await linha.getByRole('link').click()
  const trilha = page.getByRole('navigation', { name: 'Trilha' })
  await expect(trilha).toContainText(/Detalhes do atendimento ATD-\d+/)
  await expect(page.getByRole('heading', { name: razao, level: 1 })).toBeVisible()
  await expect(page.getByText('Bom dia! Vou verificar sua fatura.')).toBeVisible()
  await expect(page.getByText(/encerrou o atendimento/)).toBeVisible()
  await expect(page.getByText('Conversa encerrada · Somente leitura')).toBeVisible()
  const codigoEncerrado = /ATD-\d+/.exec((await trilha.textContent()) ?? '')![0]

  // 6. Nova mensagem depois do encerramento abre outro ciclo na fila, na mesma conversa.
  await webhookDeTeste.mensagem(page.request, waId, nome, 'Esqueci de perguntar uma coisa')
  await page.goto('/atendimento')
  await expect(async () => {
    await abrirNaFila(page, waId, razao)
  }).toPass({ timeout: 20_000 })
  await expect(page.getByText('Aguardando atendimento')).toBeVisible()
  await expect(page.getByText('Esqueci de perguntar uma coisa')).toBeVisible()
  await expect(page.getByText(codigoEncerrado)).toHaveCount(0)
})

test('transferência entre dois atendentes: só o novo responsável responde e encerra', async ({
  browser,
}) => {
  test.setTimeout(120_000)
  const admin = await paginaDoAdministrador(browser)
  const marca = Date.now().toString(36).toUpperCase()
  const nomeAtendente = `Atendente E2E ${marca}`
  const nomeContato = `Contato Transferência ${marca}`
  const waId = `55679${aleatorio(8)}`

  // Segundo atendente: convite real (Mailpit) e perfil Administrador atribuído pela interface.
  const { email, page: atendente } = await criarUsuarioAtivo(browser, admin, nomeAtendente)
  await admin.goto('/admin/usuarios')
  await abrirUsuarioNaLista(admin, nomeAtendente)
  await admin.getByLabel('Administrador').check()
  await admin.getByRole('button', { name: 'Salvar perfis' }).click()
  await expect(admin.getByText(/Administrador/).first()).toBeVisible()
  await entrar(atendente, email, SENHA_PADRAO)
  await expect(atendente.getByRole('button', { name: 'Sair' })).toBeVisible()

  expect(
    (
      await webhookDeTeste.mensagem(
        admin.request,
        waId,
        nomeContato,
        'Preciso falar com o financeiro',
      )
    ).status(),
  ).toBe(200)
  await admin.goto('/atendimento')
  await expect(async () => {
    await abrirNaFila(admin, nomeContato, nomeContato)
  }).toPass({ timeout: 20_000 })
  await admin.getByRole('button', { name: 'Assumir' }).click()
  await expect(admin.getByText(/Em atendimento com/)).toBeVisible()

  await admin.getByRole('button', { name: 'Transferir' }).click()
  await admin.getByLabel('Transferir para').selectOption({ label: nomeAtendente })
  await admin.getByLabel('Motivo (opcional)').fill('Assunto financeiro')
  await admin.getByRole('button', { name: 'Confirmar transferência' }).click()
  await expect(admin.getByText(`Em atendimento com ${nomeAtendente}`)).toBeVisible()
  await expect(admin.getByText(/transferiu o atendimento\s+para/)).toBeVisible()
  // O antigo responsável perde as ações, na tela e no servidor.
  await expect(admin.getByRole('button', { name: 'Transferir' })).toHaveCount(0)
  await expect(admin.getByRole('button', { name: 'Encerrar' })).toHaveCount(0)
  await expect(admin.getByLabel('Mensagem')).toBeDisabled()
  const id = new URL(admin.url()).searchParams.get('id')
  const xsrf = (await admin.context().cookies()).find((c) => c.name === 'XSRF-TOKEN')?.value ?? ''
  const direto = await admin.request.post(`/api/v1/atendimento/atendimentos/${id}/encerramento`, {
    data: { versao: 999 },
    headers: { 'X-XSRF-TOKEN': xsrf },
  })
  expect(direto.status()).toBe(409)
  expect(((await direto.json()) as { codigo: string }).codigo).toBe('NAO_E_RESPONSAVEL')

  // O novo responsável responde e encerra.
  await atendente.goto(`/atendimento?id=${id}`)
  await expect(atendente.getByText(`Em atendimento com ${nomeAtendente}`)).toBeVisible()
  await atendente.getByLabel('Mensagem').fill('Olá! Sou do financeiro.')
  await atendente.getByRole('button', { name: 'Enviar' }).click()
  await expect.poll(() => graph.envios.some((e) => e.para === waId), { timeout: 15_000 }).toBe(true)
  await expect(atendente.getByText(/Aceita pelo WhatsApp/)).toBeVisible({ timeout: 15_000 })
  atendente.once('dialog', (d) => d.accept())
  await atendente.getByRole('button', { name: 'Encerrar' }).click()
  await expect(atendente.getByText('Encerrado', { exact: true })).toBeVisible()
})
