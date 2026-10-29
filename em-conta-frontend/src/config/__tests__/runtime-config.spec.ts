import { describe, expect, it, vi } from 'vitest'

import { jsonResponse } from '@/test/render-with-providers'

import { RuntimeConfigError, loadRuntimeConfig, parseRuntimeConfig } from '../runtime-config'

describe('parseRuntimeConfig', () => {
  it('aceita configuração válida e normaliza a barra final', () => {
    expect(parseRuntimeConfig({ environment: 'local', apiBasePath: '/api/' })).toEqual({
      environment: 'local',
      apiBasePath: '/api',
    })
  })

  it.each([
    ['URL absoluta', { environment: 'local', apiBasePath: 'https://api.exemplo.com' }],
    ['URL sem protocolo', { environment: 'local', apiBasePath: '//api.exemplo.com' }],
    ['ambiente vazio', { environment: ' ', apiBasePath: '/api' }],
    ['chave não permitida', { environment: 'local', apiBasePath: '/api', apiKey: 'segredo' }],
    ['não objeto', ['local']],
  ])('rejeita %s', (_, raw) => {
    expect(() => parseRuntimeConfig(raw)).toThrow(RuntimeConfigError)
  })
})

describe('loadRuntimeConfig', () => {
  it('falha antes de iniciar a aplicação quando /config.json não existe', async () => {
    const fetchFn = vi.fn<typeof fetch>(async () => new Response('não encontrado', { status: 404 }))
    await expect(loadRuntimeConfig(fetchFn)).rejects.toThrow('HTTP 404')
  })

  it('carrega e valida /config.json', async () => {
    const fetchFn = vi.fn<typeof fetch>(async () =>
      jsonResponse({ environment: 'local', apiBasePath: '/api' }),
    )
    await expect(loadRuntimeConfig(fetchFn)).resolves.toEqual({
      environment: 'local',
      apiBasePath: '/api',
    })
    expect(fetchFn).toHaveBeenCalledWith(
      '/config.json',
      expect.objectContaining({ cache: 'no-store' }),
    )
  })
})
