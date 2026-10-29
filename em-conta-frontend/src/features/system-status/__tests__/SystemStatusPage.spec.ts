import { screen } from '@testing-library/vue'
import { describe, expect, it, vi } from 'vitest'

import { jsonResponse, renderWithProviders } from '@/test/render-with-providers'

import SystemStatusPage from '../SystemStatusPage.vue'

const status = {
  application: 'em-conta-backend',
  version: '0.1.0-SNAPSHOT',
  status: 'UP',
  database: 'UP',
  serverTime: '2026-10-28T18:00:00Z',
}

describe('SystemStatusPage', () => {
  it('chama o endpoint real pelo caminho relativo /api e mostra carregamento', async () => {
    const fetchFn = vi.fn<typeof fetch>(() => new Promise(() => {}))
    await renderWithProviders(SystemStatusPage, fetchFn)

    expect(screen.getByRole('heading', { name: 'Em Conta' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Verificando a API')
    expect(fetchFn).toHaveBeenCalledWith(
      '/api/v1/system/status',
      expect.objectContaining({ method: 'GET', credentials: 'same-origin' }),
    )
  })

  it('mostra os dados quando a API e o banco estão disponíveis', async () => {
    await renderWithProviders(
      SystemStatusPage,
      vi.fn<typeof fetch>(async () => jsonResponse(status)),
    )

    expect(await screen.findByRole('heading', { name: 'API conectada' })).toBeInTheDocument()
    expect(screen.getByTestId('api-status')).toHaveTextContent('UP')
    expect(screen.getByTestId('database-status')).toHaveTextContent('UP')
    expect(screen.getByText('0.1.0-SNAPSHOT')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('mostra banco indisponível quando a API responde 503 com o status', async () => {
    const body = { ...status, status: 'DOWN', database: 'DOWN' }
    await renderWithProviders(
      SystemStatusPage,
      vi.fn<typeof fetch>(async () => jsonResponse(body, 503)),
    )

    expect(await screen.findByRole('alert')).toHaveTextContent('Banco de dados indisponível')
    expect(screen.queryByRole('heading', { name: 'API conectada' })).not.toBeInTheDocument()
  })

  it('mostra mensagem útil, sem simular sucesso, quando a API não responde', async () => {
    const fetchFn = vi.fn<typeof fetch>(async () => {
      throw new TypeError('Failed to fetch')
    })
    await renderWithProviders(SystemStatusPage, fetchFn)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('API indisponível')
    expect(alert).toHaveTextContent('Verifique se o backend está em execução')
    expect(screen.getByRole('button', { name: 'Tentar novamente' })).toBeEnabled()
    expect(screen.queryByRole('heading', { name: 'API conectada' })).not.toBeInTheDocument()
  })

  it('trata como falha um 200 que não segue o contrato (ex.: proxy servindo HTML)', async () => {
    const html = new Response('<!doctype html>', {
      status: 200,
      headers: { 'Content-Type': 'text/html' },
    })
    await renderWithProviders(
      SystemStatusPage,
      vi.fn<typeof fetch>(async () => html),
    )

    expect(await screen.findByRole('alert')).toHaveTextContent('formato inesperado')
    expect(screen.queryByRole('heading', { name: 'API conectada' })).not.toBeInTheDocument()
  })
})
