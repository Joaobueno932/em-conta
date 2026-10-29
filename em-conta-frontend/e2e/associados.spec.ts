import { expect, test, type Page } from '@playwright/test'

import { ADMIN_EMAIL, ADMIN_SENHA, paginaDoAdministrador } from './apoio'

/**
 * Jornada de associados e UCs contra o backend real. Cria registros: rode contra um banco isolado
 * de testes (ver README, "Testes de ponta a ponta"), nunca contra dados reais.
 */
// eslint-disable-next-line playwright/no-skipped-test -- sem credenciais do bootstrap, as jornadas não podem rodar
test.skip(!ADMIN_EMAIL || !ADMIN_SENHA, 'Defina E2E_ADMIN_EMAIL e E2E_ADMIN_SENHA (ver README)')

/** CNPJ (alfanumérico ou numérico) com dígitos verificadores válidos, para dados de teste. */
function cnpjDeTeste(alfabeto = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ') {
  const dv = (base: string) => {
    let soma = 0
    let peso = 2
    for (let i = base.length - 1; i >= 0; i--) {
      soma += (base.charCodeAt(i) - 48) * peso
      peso = peso === 9 ? 2 : peso + 1
    }
    const resto = soma % 11
    return resto < 2 ? 0 : 11 - resto
  }
  let base = ''
  for (let i = 0; i < 12; i++) base += alfabeto[Math.floor(Math.random() * alfabeto.length)]
  const primeiro = dv(base)
  return `${base}${primeiro}${dv(base + primeiro)}`
}

const marcador = () => `E2E${Date.now().toString(36).toUpperCase()}`

async function cadastrarPj(page: Page, razao: string, cnpj: string) {
  await page.goto('/associados/novo')
  await page.getByLabel(/Pessoa jurídica/).check()
  await page.getByLabel(/Razão social/).fill(razao)
  await page.getByLabel(/^CNPJ/).fill(cnpj)
  await page.getByLabel('Nome fantasia').fill(`${razao} Fantasia`)
  await page.getByRole('button', { name: 'Adicionar contato' }).click()
  await page.getByRole('group', { name: 'Contato 1' }).getByLabel(/^Nome/).fill('Contato E2E')
  await page
    .getByRole('group', { name: 'Contato 1' })
    .getByLabel('Telefone')
    .fill('(67) 99812-3344')
  await page.getByRole('button', { name: 'Salvar associado' }).click()
  await expect(page.getByRole('heading', { name: razao, level: 1 })).toBeVisible()
}

test('cadastra PJ com CNPJ alfanumérico, UCs, edita e consulta o histórico', async ({
  browser,
}) => {
  const page = await paginaDoAdministrador(browser)
  const razao = `Cooperativa ${marcador()}`
  const cnpj = cnpjDeTeste()

  await page.getByRole('link', { name: 'Associados', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Todos os associados' })).toBeVisible()

  await cadastrarPj(page, razao, cnpj.toLowerCase())
  await expect(page.getByText(/Associado cadastrado/)).toBeVisible()
  const formatado = `${cnpj.slice(0, 2)}.${cnpj.slice(2, 5)}.${cnpj.slice(5, 8)}/${cnpj.slice(8, 12)}-${cnpj.slice(12)}`
  await expect(page.getByText(formatado, { exact: true })).toBeVisible()
  await expect(page.getByText('Contato E2E', { exact: true })).toBeVisible()

  // Duas UCs; zeros à esquerda preservados.
  const numero = `000${Date.now()}`
  await page.getByRole('tab', { name: /Unidades consumidoras/ }).click()
  await expect(page.getByText('Nenhuma unidade consumidora cadastrada')).toBeVisible()
  for (const [n, denominacao] of [
    [numero, 'Sede'],
    [`${numero}9`, 'Silo 1'],
  ] as const) {
    await page.getByRole('link', { name: 'Cadastrar UC' }).click()
    await page.getByLabel(/Número da UC/).fill(n)
    await page.getByLabel('Denominação').fill(denominacao)
    await page.getByRole('button', { name: 'Salvar UC' }).click()
    await expect(page.getByRole('cell', { name: n, exact: true })).toBeVisible()
  }
  await expect(page.getByRole('tab', { name: 'Unidades consumidoras (2)' })).toBeVisible()

  // UC repetida na mesma concessionária: recusada, formulário preservado.
  await page.getByRole('link', { name: 'Cadastrar UC' }).click()
  await page.getByLabel(/Número da UC/).fill(numero)
  await page.getByRole('button', { name: 'Salvar UC' }).click()
  await expect(page.getByRole('alert')).toContainText('já está cadastrada nesta concessionária')
  await expect(page.getByLabel(/Número da UC/)).toHaveAttribute('aria-invalid', 'true')
  await expect(page.getByLabel(/Número da UC/)).toHaveValue(numero)
  page.once('dialog', (dialogo) => void dialogo.accept())
  await page.getByRole('link', { name: 'Cancelar' }).click()

  // Edição de UC: número e concessionária não são editáveis.
  await page.getByRole('link', { name: `Editar UC ${numero}`, exact: true }).click()
  await expect(page.getByLabel(/Número da UC/)).toHaveAttribute('readonly', '')
  await page.getByLabel('Denominação').fill('Sede administrativa')
  await page.getByRole('button', { name: 'Salvar UC' }).click()
  await expect(page.getByRole('cell', { name: 'Sede administrativa' })).toBeVisible()

  // Edição do cadastro.
  await page.getByRole('link', { name: 'Editar cadastro' }).click()
  await page.getByLabel(/Razão social/).fill(`${razao} Ltda.`)
  await page.getByRole('button', { name: 'Salvar alterações' }).click()
  await expect(page.getByRole('heading', { name: `${razao} Ltda.`, level: 1 })).toBeVisible()

  // Histórico com autor e diferenças, telefone mascarado.
  await page.getByRole('tab', { name: 'Histórico' }).click()
  const historico = page.getByRole('tabpanel')
  await expect(historico.getByText('Dados cadastrais atualizados')).toBeVisible()
  await expect(historico.getByText('Unidade consumidora atualizada')).toBeVisible()
  await expect(historico.getByText('Cadastro criado')).toBeVisible()
  await expect(historico.getByText(`${razao} → ${razao} Ltda.`)).toBeVisible()
  await expect(historico.getByText('****3344')).toBeVisible()
  await expect(historico.getByText('99812-3344')).toHaveCount(0)
  await historico.getByRole('button', { name: 'Contatos' }).click()
  await expect(historico.getByText('Contato adicionado')).toBeVisible()
  await expect(historico.getByText('Cadastro criado')).toHaveCount(0)

  // Busca por documento e por nome; filtros preservados ao voltar da ficha.
  await page.getByRole('link', { name: 'Associados', exact: true }).click()
  await page.getByRole('searchbox', { name: 'Buscar associados' }).fill(razao)
  await page.getByRole('button', { name: 'Buscar' }).click()
  await page.getByLabel('Tipo de pessoa').selectOption('PJ')
  await expect(page.getByText('1 no total')).toBeVisible()
  await page.getByRole('link', { name: `${razao} Ltda.` }).click()
  await page.goBack()
  await expect(page.getByRole('searchbox', { name: 'Buscar associados' })).toHaveValue(razao)
  await expect(page.getByLabel('Tipo de pessoa')).toHaveValue('PJ')
  await expect(page.getByRole('link', { name: `${razao} Ltda.` })).toBeVisible()

  await page.getByRole('searchbox', { name: 'Buscar associados' }).fill(cnpj.slice(0, 8))
  await page.getByRole('button', { name: 'Buscar' }).click()
  await expect(page.getByRole('link', { name: `${razao} Ltda.` })).toBeVisible()

  // Nada pessoal fica no armazenamento do navegador.
  const armazenado = await page.evaluate(() =>
    JSON.stringify({ ...localStorage, ...sessionStorage }),
  )
  expect(armazenado).not.toContain(cnpj)
  expect(armazenado).not.toContain(razao)
})

test('documento duplicado e erro de validação preservam o formulário', async ({ browser }) => {
  const page = await paginaDoAdministrador(browser)
  const razao = `Duplicada ${marcador()}`
  const cnpj = cnpjDeTeste('0123456789')
  await cadastrarPj(page, razao, cnpj)

  await page.goto('/associados/novo')
  await page.getByLabel(/Pessoa jurídica/).check()
  await page.getByLabel(/Razão social/).fill('Outra razão')
  await page.getByLabel(/^CNPJ/).fill(cnpj)
  await page.getByRole('button', { name: 'Salvar associado' }).click()
  await expect(page.getByText(/Já existe um associado com este documento/).first()).toBeVisible()
  await expect(page.getByLabel(/Razão social/)).toHaveValue('Outra razão')
  page.once('dialog', (dialogo) => void dialogo.accept())
  await page.getByRole('link', { name: 'Abrir o cadastro existente' }).click()
  await expect(page.getByRole('heading', { name: razao, level: 1 })).toBeVisible()

  await page.goto('/associados/novo')
  await page.getByLabel(/Nome completo/).fill('Pessoa Teste')
  await page.getByLabel(/^CPF/).fill('529.982.247-24')
  await page.getByRole('button', { name: 'Salvar associado' }).click()
  await expect(page.getByLabel(/^CPF/)).toHaveAttribute('aria-invalid', 'true')
  await expect(page.getByText('CPF inválido.')).toBeVisible()
  await expect(page.getByLabel(/Nome completo/)).toHaveValue('Pessoa Teste')
})

test('edição concorrente não sobrescreve e mantém o texto digitado', async ({ browser }) => {
  const primeira = await paginaDoAdministrador(browser)
  const segunda = await paginaDoAdministrador(browser)
  const razao = `Concorrente ${marcador()}`
  await cadastrarPj(primeira, razao, cnpjDeTeste())
  const url = primeira.url().replace(/\?.*$/, '')

  await primeira.goto(`${url}/editar`)
  await segunda.goto(`${url}/editar`)
  await expect(segunda.getByLabel(/Razão social/)).toHaveValue(razao)

  await primeira.getByLabel(/Razão social/).fill(`${razao} A`)
  await primeira.getByRole('button', { name: 'Salvar alterações' }).click()
  await expect(primeira.getByRole('heading', { name: `${razao} A`, level: 1 })).toBeVisible()

  await segunda.getByLabel(/Razão social/).fill(`${razao} B`)
  await segunda.getByRole('button', { name: 'Salvar alterações' }).click()
  await expect(segunda.getByText(/alterado por outra pessoa/)).toBeVisible()
  await expect(segunda.getByLabel(/Razão social/)).toHaveValue(`${razao} B`)
  await segunda.getByRole('button', { name: /Recarregar a versão atual/ }).click()
  await expect(segunda.getByLabel(/Razão social/)).toHaveValue(`${razao} A`)
})

test('sessão encerrada durante o cadastro leva ao login', async ({ browser }) => {
  const page = await paginaDoAdministrador(browser)
  const outraAba = await page.context().newPage()
  await page.goto('/associados/novo')
  await page.getByLabel(/Nome completo/).fill('Rascunho')

  await outraAba.goto('/')
  await outraAba.getByRole('button', { name: 'Sair' }).click()
  await expect(outraAba.getByRole('heading', { name: 'Entrar no Em Conta' })).toBeVisible()

  await page.getByRole('button', { name: 'Salvar associado' }).click()
  await expect(page).toHaveURL(/\/entrar\?/)
  await expect(page.getByRole('heading', { name: 'Entrar no Em Conta' })).toBeVisible()
})
