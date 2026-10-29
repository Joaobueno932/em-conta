import { expect, test, type Page } from '@playwright/test'

import { ADMIN_EMAIL, ADMIN_SENHA, paginaDoAdministrador } from './apoio'

/**
 * Jornada de usinas e vínculos cadastrais contra o backend real. Cria registros: rode contra um
 * banco isolado de testes (ver README), nunca contra dados reais.
 */
// eslint-disable-next-line playwright/no-skipped-test -- sem credenciais do bootstrap, as jornadas não podem rodar
test.skip(!ADMIN_EMAIL || !ADMIN_SENHA, 'Defina E2E_ADMIN_EMAIL e E2E_ADMIN_SENHA (ver README)')

const marca = () => `E2E${Date.now().toString(36).toUpperCase()}`

function cnpjNumerico() {
  const dv = (base: string) => {
    let soma = 0
    let peso = 2
    for (let i = base.length - 1; i >= 0; i--) {
      soma += Number(base[i]) * peso
      peso = peso === 9 ? 2 : peso + 1
    }
    const resto = soma % 11
    return resto < 2 ? 0 : 11 - resto
  }
  let base = ''
  for (let i = 0; i < 12; i++) base += Math.floor(Math.random() * 10)
  const primeiro = dv(base)
  return `${base}${primeiro}${dv(base + primeiro)}`
}

/** Associado com uma UC, cadastrados pela interface; devolve nome e número da UC. */
async function associadoComUc(page: Page) {
  const nome = `Titular ${marca()}`
  const numero = `000${Date.now()}`
  await page.goto('/associados/novo')
  await page.getByLabel(/Pessoa jurídica/).check()
  await page.getByLabel(/Razão social/).fill(nome)
  await page.getByLabel(/^CNPJ/).fill(cnpjNumerico())
  await page.getByRole('button', { name: 'Salvar associado' }).click()
  await expect(page.getByRole('heading', { name: nome, level: 1 })).toBeVisible()
  await page.getByRole('tab', { name: /Unidades consumidoras/ }).click()
  await page.getByRole('link', { name: 'Cadastrar UC' }).click()
  await page.getByLabel(/Número da UC/).fill(numero)
  await page.getByRole('button', { name: 'Salvar UC' }).click()
  await expect(page.getByRole('cell', { name: numero, exact: true })).toBeVisible()
  return { nome, numero, url: page.url().replace(/\?.*$/, '') }
}

test('cadastra usina, vincula UC existente e confere pelos dois lados e no histórico', async ({
  browser,
}) => {
  const page = await paginaDoAdministrador(browser)
  const titular = await associadoComUc(page)
  const nomeUsina = `Usina ${marca()}`

  // Cadastro da usina com fazenda.
  const nomeFazenda = `Fazenda ${marca()}`
  await page.goto('/fazendas/nova')
  await page.getByLabel(/Nome da fazenda/).fill(nomeFazenda)
  await page.getByRole('button', { name: 'Salvar fazenda' }).click()
  await expect(page.getByRole('cell', { name: nomeFazenda, exact: true })).toBeVisible()

  await page.getByRole('link', { name: 'Usinas', exact: true }).first().click()
  await expect(page.getByRole('heading', { name: 'Usinas', level: 1 })).toBeVisible()
  await page.getByRole('link', { name: 'Nova usina' }).click()
  await page.getByLabel(/Nome da usina/).fill(nomeUsina)
  const fazenda = await page
    .locator('#usina-fazenda option', { hasText: nomeFazenda })
    .getAttribute('value')
  await page.getByLabel('Fazenda (opcional)').selectOption(fazenda as string)
  await page.getByLabel('Cidade').fill('Sidrolândia')
  await page.getByLabel('UF').selectOption('MS')
  await page.getByRole('button', { name: 'Salvar usina' }).click()
  await expect(page.getByRole('heading', { name: nomeUsina, level: 1 })).toBeVisible()
  await expect(page.getByText('Usina cadastrada.')).toBeVisible()
  await expect(page.getByText(/não comprova rateio/).first()).toBeVisible()

  // Vincula a UC existente.
  await page.getByRole('tab', { name: 'Unidades consumidoras (0)' }).click()
  await page.getByRole('button', { name: 'Vincular unidade consumidora' }).click()
  await page.getByRole('searchbox', { name: 'Buscar UC' }).fill(titular.numero)
  await page.getByRole('button', { name: 'Buscar UC' }).click()
  await page.getByRole('radio', { name: new RegExp(titular.numero) }).check()
  await expect(page.getByText(`titular ${titular.nome}`)).toBeVisible()
  await page.getByRole('button', { name: 'Vincular UC' }).click()
  await expect(page.getByText('UC vinculada à usina.')).toBeVisible()
  await expect(page.getByRole('tab', { name: 'Unidades consumidoras (1)' })).toBeVisible()
  const linha = page.getByRole('row', { name: new RegExp(titular.numero) })
  await expect(linha.getByRole('link', { name: titular.nome })).toBeVisible()
  await expect(linha.getByText('Vigente')).toBeVisible()

  // A mesma UC não pode ser vinculada a outra usina.
  const usinaUrl = page.url().replace(/\?.*$/, '')
  await page.goto('/usinas/nova')
  const outraUsina = `Outra ${marca()}`
  await page.getByLabel(/Nome da usina/).fill(outraUsina)
  await page.getByRole('button', { name: 'Salvar usina' }).click()
  await expect(page.getByRole('heading', { name: outraUsina, level: 1 })).toBeVisible()
  await page.getByRole('tab', { name: /Unidades consumidoras/ }).click()
  await page.getByRole('button', { name: 'Vincular unidade consumidora' }).click()
  await page.getByRole('searchbox', { name: 'Buscar UC' }).fill(titular.numero)
  await page.getByRole('button', { name: 'Buscar UC' }).click()
  await expect(page.getByRole('radio', { name: new RegExp(titular.numero) })).toBeDisabled()
  await expect(page.getByText(new RegExp(`Vinculada a outra usina: .*${nomeUsina}`))).toBeVisible()

  // Na ficha do associado: usina da UC, com navegação, e histórico do vínculo.
  await page.goto(`${titular.url}?aba=ucs`)
  const linhaUc = page.getByRole('row', { name: new RegExp(titular.numero) }).first()
  await expect(linhaUc.getByRole('link', { name: nomeUsina })).toBeVisible()
  const vinculos = page.getByRole('region', { name: 'Vínculos com usinas' })
  await expect(vinculos.getByText('Vigente')).toBeVisible()
  await linhaUc.getByRole('link', { name: nomeUsina }).click()
  await expect(page.getByRole('heading', { name: nomeUsina, level: 1 })).toBeVisible()

  // Encerramento com motivo; histórico preservado nos dois lados.
  await page
    .getByRole('button', { name: new RegExp(`Encerrar vínculo da UC ${titular.numero}`) })
    .click()
  await page.getByLabel(/Motivo/).fill('Lançamento de teste encerrado')
  page.once('dialog', (dialogo) => void dialogo.accept())
  await page.getByRole('button', { name: 'Encerrar vínculo' }).click()
  await expect(page.getByText(/Vínculo encerrado/)).toBeVisible()
  await expect(page.getByRole('tab', { name: 'Unidades consumidoras (0)' })).toBeVisible()
  await page.getByRole('button', { name: 'Encerrados' }).click()
  await expect(page.getByText('Motivo: Lançamento de teste encerrado')).toBeVisible()

  await page.getByRole('tab', { name: 'Informações' }).click()
  const historico = page.getByRole('tabpanel')
  await expect(historico.getByText('Vínculo encerrado')).toBeVisible()
  await expect(historico.getByText('UC vinculada')).toBeVisible()
  await expect(historico.getByText('Usina cadastrada')).toBeVisible()

  await page.goto(`${titular.url}?aba=ucs`)
  await expect(
    page
      .getByRole('region', { name: 'Vínculos com usinas' })
      .getByText('Encerrado', { exact: true }),
  ).toBeVisible()

  // Listagem: busca e filtro preservados ao voltar.
  await page.goto('/usinas')
  await page.getByRole('searchbox', { name: 'Buscar usinas' }).fill(nomeUsina)
  await page.getByRole('button', { name: 'Buscar' }).click()
  await page.getByLabel('UF', { exact: true }).selectOption('MS')
  await page.getByRole('link', { name: nomeUsina }).click()
  await page.goBack()
  await expect(page.getByRole('searchbox', { name: 'Buscar usinas' })).toHaveValue(nomeUsina)
  await expect(page.getByLabel('UF', { exact: true })).toHaveValue('MS')
  expect(usinaUrl).toContain('/usinas/')
})

test('edição concorrente da usina mantém o texto digitado', async ({ browser }) => {
  const primeira = await paginaDoAdministrador(browser)
  const segunda = await paginaDoAdministrador(browser)
  const nome = `Concorrente ${marca()}`
  await primeira.goto('/usinas/nova')
  await primeira.getByLabel(/Nome da usina/).fill(nome)
  await primeira.getByRole('button', { name: 'Salvar usina' }).click()
  await expect(primeira.getByRole('heading', { name: nome, level: 1 })).toBeVisible()
  const editar = `${primeira.url().replace(/\?.*$/, '')}/editar`

  await primeira.goto(editar)
  await segunda.goto(editar)
  await expect(segunda.getByLabel(/Nome da usina/)).toHaveValue(nome)
  await primeira.getByLabel('Cidade').fill('Dourados')
  await primeira.getByRole('button', { name: 'Salvar usina' }).click()
  await expect(primeira.getByText('Informações salvas.')).toBeVisible()

  await segunda.getByLabel('Cidade').fill('Maracaju')
  await segunda.getByRole('button', { name: 'Salvar usina' }).click()
  await expect(segunda.getByText(/alterada por outra pessoa/)).toBeVisible()
  await expect(segunda.getByLabel('Cidade')).toHaveValue('Maracaju')
})
