import { expect, test } from '@playwright/test'

test('consome o status real do backend pelo proxy /api na mesma origem', async ({ page }) => {
  const statusResponse = page.waitForResponse((response) =>
    response.url().endsWith('/api/v1/system/status'),
  )
  await page.goto('/sistema/status')
  const response = await statusResponse

  expect(new URL(response.url()).origin).toBe(new URL(page.url()).origin)
  expect(response.status()).toBe(200)
  const body = await response.json()
  expect(body).toMatchObject({ application: 'em-conta-backend', status: 'UP', database: 'UP' })

  await expect(page.getByRole('heading', { name: 'Em Conta' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'API conectada' })).toBeVisible()
  await expect(page.getByTestId('database-status')).toHaveText('UP')
})

test('mostra indisponibilidade quando a chamada à API falha', async ({ page }) => {
  // Interrompe a conexão (não fornece resposta de sucesso) para exercitar o estado de falha.
  await page.route('**/api/v1/system/status', (route) => route.abort('connectionrefused'))
  await page.goto('/sistema/status')

  await expect(page.getByRole('alert')).toContainText('API indisponível')
  await expect(page.getByRole('heading', { name: 'API conectada' })).toHaveCount(0)
})
