import process from 'node:process'
import { defineConfig, devices } from '@playwright/test'

/**
 * Teste de integração real: o frontend (Vite dev ou preview) chama o backend pelo proxy /api.
 * Pré-requisito: backend em execução (por padrão em http://localhost:8080, ver README).
 * https://playwright.dev/docs/test-configuration
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 30 * 1000,
  expect: { timeout: 10000 },
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: process.env.CI ? 'http://localhost:4173' : 'http://localhost:5173',
    trace: 'on-first-retry',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    // No CI testa o build de produção (preview), com o mesmo proxy /api.
    command: process.env.CI ? 'npm run build-only && npm run preview' : 'npm run dev',
    port: process.env.CI ? 4173 : 5173,
    reuseExistingServer: !process.env.CI,
    timeout: 120 * 1000,
  },
})
