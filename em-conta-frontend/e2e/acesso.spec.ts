import { expect, test } from '@playwright/test'

import {
  abrirUsuarioNaLista,
  ADMIN_EMAIL,
  ADMIN_SENHA,
  criarUsuarioAtivo,
  entrar,
  linkDoEmail,
  paginaDoAdministrador,
  SENHA_PADRAO,
} from './apoio'

// eslint-disable-next-line playwright/no-skipped-test -- sem credenciais do bootstrap, as jornadas não podem rodar
test.skip(!ADMIN_EMAIL || !ADMIN_SENHA, 'Defina E2E_ADMIN_EMAIL e E2E_ADMIN_SENHA (ver README)')

test('login, sessão, rota protegida e logout', async ({ page, context }) => {
  await page.goto('/admin/usuarios')
  await expect(page).toHaveURL(/\/entrar\?redirect=(%2F|\/)admin(%2F|\/)usuarios/)

  await entrar(page, ADMIN_EMAIL, 'senha errada, porém comprida')
  await expect(page.getByRole('alert')).toHaveText('E-mail ou senha inválidos.')

  await entrar(page, ADMIN_EMAIL, ADMIN_SENHA)
  await expect(page).toHaveURL('/admin/usuarios')
  await expect(page.getByRole('heading', { name: 'Usuários', level: 1 })).toBeVisible()

  const cookies = await context.cookies()
  expect(cookies.find((c) => c.name === 'EMCONTA_SESSION')?.httpOnly).toBe(true)
  // Nada de sessão, token, senha ou CSRF no armazenamento do navegador (no modo dev, o plugin
  // Vue DevTools grava apenas preferências próprias).
  const armazenado = await page.evaluate(() =>
    [localStorage, sessionStorage].flatMap((area) =>
      Object.keys(area).map((chave) => `${chave}=${area.getItem(chave)}`),
    ),
  )
  for (const item of armazenado) {
    expect(item).not.toMatch(/sess|token|senha|csrf|xsrf|auth|EMCONTA/i)
    expect(item).not.toContain(ADMIN_SENHA)
  }

  await page.getByRole('button', { name: 'Sair' }).click()
  await expect(page).toHaveURL(/\/entrar\?motivo=saiu/)
  await page.goto('/admin/usuarios')
  await expect(page).toHaveURL(/\/entrar\?redirect=/)
  expect((await context.cookies()).some((c) => c.name === 'EMCONTA_SESSION')).toBe(false)
})

test('convite, definição de senha, acesso negado e link de uso único', async ({ browser }) => {
  const admin = await paginaDoAdministrador(browser)
  const { email, page, link } = await criarUsuarioAtivo(browser, admin, 'Pessoa Convidada')

  await entrar(page, email, SENHA_PADRAO)
  await expect(page.getByTestId('usuario-email')).toHaveText(email)
  await expect(page.getByRole('link', { name: 'Usuários' })).toHaveCount(0)

  await page.goto('/admin/usuarios')
  await expect(page.getByRole('heading', { name: 'Acesso negado' })).toBeVisible()

  const reuso = await browser.newPage()
  await reuso.goto(link)
  await expect(reuso).not.toHaveURL(/token=/)
  await expect(reuso.getByRole('alert')).toContainText('expirou ou já foi utilizado')
})

test('recuperação e redefinição de senha pelo e-mail', async ({ browser }) => {
  const admin = await paginaDoAdministrador(browser)
  const { email, page } = await criarUsuarioAtivo(browser, admin, 'Pessoa Recupera')

  await page.goto('/recuperar-senha')
  await page.getByLabel('E-mail').fill(email)
  await page.getByRole('button', { name: 'Enviar link' }).click()
  await expect(page.getByRole('status')).toContainText('Se houver uma conta ativa')

  const link = await linkDoEmail(email, '/redefinir-senha')
  await page.goto(link)
  await expect(page).not.toHaveURL(/token=/)
  const nova = 'nova senha escolhida na recuperação'
  await page.getByLabel('Nova senha', { exact: true }).fill(nova)
  await page.getByLabel('Repita a nova senha').fill(nova)
  await page.getByRole('button', { name: 'Salvar nova senha' }).click()
  await expect(page).toHaveURL(/motivo=senha-redefinida/)

  await entrar(page, email, SENHA_PADRAO)
  await expect(page.getByRole('alert')).toHaveText('E-mail ou senha inválidos.')
  await entrar(page, email, nova)
  await expect(page.getByTestId('usuario-email')).toHaveText(email)
})

test('desativação encerra a sessão aberta e a próxima ação leva ao login', async ({ browser }) => {
  const admin = await paginaDoAdministrador(browser)
  const nome = `Pessoa Desativada ${Date.now()}`
  const { email, page } = await criarUsuarioAtivo(browser, admin, nome)
  await entrar(page, email, SENHA_PADRAO)
  await expect(page.getByTestId('usuario-email')).toHaveText(email)

  await admin.goto('/admin/usuarios')
  await abrirUsuarioNaLista(admin, nome)
  admin.once('dialog', (dialogo) => void dialogo.accept())
  await admin.getByRole('button', { name: 'Desativar' }).click()
  await expect(admin.getByTestId('situacao')).toHaveText('Desativado')

  // A pessoa ainda vê a tela, mas a próxima chamada à API é recusada pelo servidor.
  await page.getByRole('link', { name: 'Alterar senha' }).click()
  await page.getByLabel('Senha atual').fill(SENHA_PADRAO)
  await page.getByLabel('Nova senha', { exact: true }).fill('qualquer outra senha longa')
  await page.getByLabel('Repita a nova senha').fill('qualquer outra senha longa')
  await page.getByRole('button', { name: 'Alterar senha' }).click()

  await expect(page).toHaveURL(/\/entrar\?.*motivo=expirada/)
  await expect(page.getByText('Sua sessão expirou ou foi encerrada')).toBeVisible()
  await expect(page.getByText(email)).toHaveCount(0)
  await entrar(page, email, SENHA_PADRAO)
  await expect(page.getByRole('alert')).toHaveText('E-mail ou senha inválidos.')
})
