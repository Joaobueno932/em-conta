import { expect, request, type Browser, type Page } from '@playwright/test'

/**
 * Apoio aos testes de ponta a ponta. Exigem backend real (profile local) com Mailpit e um
 * administrador criado pelo bootstrap, informado por E2E_ADMIN_EMAIL e E2E_ADMIN_SENHA.
 */
export const ADMIN_EMAIL = process.env.E2E_ADMIN_EMAIL ?? ''
export const ADMIN_SENHA = process.env.E2E_ADMIN_SENHA ?? ''
const MAILPIT = process.env.MAILPIT_URL ?? 'http://localhost:8025'

export const SENHA_PADRAO = 'senha de teste de ponta a ponta'

export function emailUnico(prefixo: string) {
  return `${prefixo}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@e2e.emconta.local`
}

export async function entrar(page: Page, email: string, senha: string) {
  await page.getByLabel('E-mail').fill(email)
  await page.getByLabel('Senha', { exact: true }).fill(senha)
  await page.getByRole('button', { name: 'Entrar' }).click()
}

/** Abre o e-mail mais recente no Mailpit e devolve o caminho do link (`/convite#token=...`). */
export async function linkDoEmail(destinatario: string, caminho: string, quantidade = 1) {
  const api = await request.newContext()
  try {
    let link: string | undefined
    await expect
      .poll(
        async () => {
          const busca = await api.get(
            `${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:"${destinatario}"`)}`,
          )
          const { messages } = (await busca.json()) as { messages: { ID: string }[] }
          if (messages.length < quantidade || !messages[0]) return false
          const mensagem = await api.get(`${MAILPIT}/api/v1/message/${messages[0].ID}`)
          const { Text } = (await mensagem.json()) as { Text: string }
          const encontrado = Text.match(
            new RegExp(`https?://\\S+(${caminho}#token=[A-Za-z0-9_-]{43})`),
          )
          link = encontrado?.[1]
          return Boolean(link)
        },
        { timeout: 15_000 },
      )
      .toBe(true)
    return link as string
  } finally {
    await api.dispose()
  }
}

export async function paginaDoAdministrador(browser: Browser) {
  const contexto = await browser.newContext()
  const page = await contexto.newPage()
  await page.goto('/entrar')
  await entrar(page, ADMIN_EMAIL, ADMIN_SENHA)
  await expect(page.getByRole('button', { name: 'Sair' })).toBeVisible()
  return page
}

/** Convida pela interface do administrador e ativa o acesso abrindo o link do e-mail. */
export async function criarUsuarioAtivo(browser: Browser, admin: Page, nome: string) {
  const email = emailUnico('usuario')
  await admin.goto('/admin/usuarios')
  await admin.getByLabel('Nome').fill(nome)
  await admin.getByLabel('E-mail').fill(email)
  await admin.getByRole('button', { name: 'Enviar convite' }).click()
  await expect(admin.getByText(`Convite enviado para ${email}.`)).toBeVisible()

  const link = await linkDoEmail(email, '/convite')
  const contexto = await browser.newContext()
  const page = await contexto.newPage()
  await page.goto(link)
  await expect(page.getByText(`Olá, ${nome}.`)).toBeVisible()
  await page.getByLabel('Nova senha', { exact: true }).fill(SENHA_PADRAO)
  await page.getByLabel('Repita a nova senha').fill(SENHA_PADRAO)
  await page.getByRole('button', { name: 'Ativar acesso' }).click()
  await expect(page).toHaveURL(/\/entrar\?motivo=convite-aceito/)
  return { email, page, link }
}
