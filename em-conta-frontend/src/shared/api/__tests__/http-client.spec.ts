import { afterEach, describe, expect, it, vi } from 'vitest'

import { jsonResponse } from '@/test/render-with-providers'

import { describeApiError } from '../errors'
import { ApiError, createHttpClient, readCookie } from '../http-client'

function headersOf(fetchFn: ReturnType<typeof vi.fn<typeof fetch>>, chamada = 0): Headers {
  return new Headers(fetchFn.mock.calls[chamada]?.[1]?.headers)
}

function problema(status: number, codigo: string, detail = codigo, headers = {}) {
  return new Response(JSON.stringify({ status, codigo, detail }), {
    status,
    headers: { 'Content-Type': 'application/problem+json', ...headers },
  })
}

afterEach(() => {
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
})

describe('createHttpClient', () => {
  it('envia o token CSRF do cookie apenas em métodos mutáveis', async () => {
    document.cookie = 'XSRF-TOKEN=abc123; path=/'

    const getFetch = vi.fn<typeof fetch>(async () => jsonResponse({}))
    await createHttpClient('/api', getFetch).get('/v1/x')
    expect(headersOf(getFetch).has('X-XSRF-TOKEN')).toBe(false)

    const postFetch = vi.fn<typeof fetch>(async () => new Response(null, { status: 204 }))
    await createHttpClient('/api', postFetch).request('POST', '/v1/x', { body: { a: 1 } })
    expect(headersOf(postFetch).get('X-XSRF-TOKEN')).toBe('abc123')
    expect(postFetch.mock.calls[0]?.[1]?.credentials).toBe('same-origin')
  })

  it('obtém o cookie CSRF antes da primeira requisição mutável', async () => {
    const fetchFn = vi.fn<typeof fetch>(async (url) => {
      if (String(url).endsWith('/v1/auth/csrf')) {
        document.cookie = 'XSRF-TOKEN=novo; path=/'
        return jsonResponse({ cabecalho: 'X-XSRF-TOKEN' })
      }
      return new Response(null, { status: 204 })
    })
    await createHttpClient('/api', fetchFn).request('POST', '/v1/auth/logout')

    expect(String(fetchFn.mock.calls[0]?.[0])).toBe('/api/v1/auth/csrf')
    expect(headersOf(fetchFn, 1).get('X-XSRF-TOKEN')).toBe('novo')
  })

  it('renova o token e repete uma única vez quando o servidor recusa o CSRF', async () => {
    document.cookie = 'XSRF-TOKEN=antigo; path=/'
    const fetchFn = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(problema(403, 'CSRF_INVALIDO'))
      .mockImplementationOnce(async () => {
        document.cookie = 'XSRF-TOKEN=renovado; path=/'
        return jsonResponse({ cabecalho: 'X-XSRF-TOKEN' })
      })
      .mockResolvedValueOnce(new Response(null, { status: 204 }))

    await createHttpClient('/api', fetchFn).request('POST', '/v1/x')

    expect(fetchFn).toHaveBeenCalledTimes(3)
    expect(headersOf(fetchFn, 2).get('X-XSRF-TOKEN')).toBe('renovado')
  })

  it('avisa sessão inválida em 401 de sessão, mas não em credenciais inválidas', async () => {
    const onSessaoInvalida = vi.fn<(error: ApiError) => void>()
    const expirada = createHttpClient(
      '/api',
      vi.fn<typeof fetch>(async () => problema(401, 'SESSAO_EXPIRADA')),
      { onSessaoInvalida },
    )
    await expect(expirada.get('/v1/usuarios')).rejects.toBeInstanceOf(ApiError)
    expect(onSessaoInvalida).toHaveBeenCalledTimes(1)

    document.cookie = 'XSRF-TOKEN=t; path=/'
    const login = createHttpClient(
      '/api',
      vi.fn<typeof fetch>(async () => problema(401, 'CREDENCIAIS_INVALIDAS')),
      { onSessaoInvalida },
    )
    await expect(login.request('POST', '/v1/auth/login')).rejects.toMatchObject({
      codigo: 'CREDENCIAIS_INVALIDAS',
    })
    expect(onSessaoInvalida).toHaveBeenCalledTimes(1)
  })

  it('distingue 401, 403, 429, 5xx e falha de rede nas mensagens', async () => {
    const erro = async (resposta: () => Response) =>
      createHttpClient(
        '/api',
        vi.fn<typeof fetch>(async () => resposta()),
      )
        .get('/v1/x')
        .catch((e: unknown) => e)

    expect(describeApiError(await erro(() => problema(401, 'NAO_AUTENTICADO', 'Entre.')))).toBe(
      'Entre.',
    )
    expect(
      describeApiError(await erro(() => problema(403, 'ACESSO_NEGADO', 'Sem permissão.'))),
    ).toBe('Sem permissão.')
    expect(
      describeApiError(
        await erro(() => problema(429, 'MUITAS_TENTATIVAS', 'x', { 'Retry-After': '30' })),
      ),
    ).toContain('Aguarde 1 minuto')
    const gateway = await erro(
      () =>
        new Response('<html>504</html>', { status: 504, headers: { 'Content-Type': 'text/html' } }),
    )
    expect(gateway).toMatchObject({ kind: 'http', status: 504, problem: null })
    expect(describeApiError(gateway)).toContain('indisponível no momento (HTTP 504)')

    const rede = await createHttpClient(
      '/api',
      vi.fn<typeof fetch>(async () => {
        throw new TypeError('Failed to fetch')
      }),
    )
      .get('/v1/x')
      .catch((e: unknown) => e)
    expect(rede).toMatchObject({ kind: 'network' })
  })
})

describe('readCookie', () => {
  it('lê o valor decodificado do cookie pedido', () => {
    expect(readCookie('XSRF-TOKEN', 'a=1; XSRF-TOKEN=x%3Dy; b=2')).toBe('x=y')
    expect(readCookie('ausente', 'a=1')).toBeNull()
  })
})
