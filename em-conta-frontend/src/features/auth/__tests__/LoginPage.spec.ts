import { cleanup, fireEvent, screen } from '@testing-library/vue'
import { afterEach, describe, expect, it } from 'vitest'

import { apiFalsa, csrf, montarApp, problema, type Rotas } from '@/test/api-falsa'

afterEach(() => {
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
})

async function tentarLogin(respostaLogin: Rotas[string]) {
  const { fetchFn } = apiFalsa({
    ...csrf(),
    'GET /api/v1/auth/sessao': () => problema(401, 'NAO_AUTENTICADO', 'Autenticação necessária.'),
    'POST /api/v1/auth/login': respostaLogin,
  })
  await montarApp(fetchFn, '/entrar')
  await fireEvent.update(screen.getByLabelText('E-mail'), 'pessoa@x.br')
  await fireEvent.update(screen.getByLabelText('Senha'), 'uma senha qualquer bem longa')
  await fireEvent.click(screen.getByRole('button', { name: 'Entrar' }))
  return { alerta: await screen.findByRole('alert'), fetchFn }
}

describe('LoginPage', () => {
  it('credenciais inválidas: mensagem genérica do servidor', async () => {
    const { alerta, fetchFn } = await tentarLogin(() =>
      problema(401, 'CREDENCIAIS_INVALIDAS', 'E-mail ou senha inválidos.'),
    )
    expect(alerta).toHaveTextContent('E-mail ou senha inválidos.')
    const envio = fetchFn.mock.calls.find(([url]) => String(url).endsWith('/auth/login'))
    expect(new Headers(envio?.[1]?.headers).get('X-XSRF-TOKEN')).toBe('token-csrf')
  })

  it('bloqueio temporário: informa a espera', async () => {
    const { alerta } = await tentarLogin(() =>
      problema(429, 'MUITAS_TENTATIVAS', 'Muitas tentativas.', { 'Retry-After': '120' }),
    )
    expect(alerta).toHaveTextContent('Aguarde 2 minutos')
  })

  it('API indisponível não aparece como senha incorreta', async () => {
    for (const resposta of [
      () => {
        throw new TypeError('Failed to fetch')
      },
      () =>
        new Response('<html>502</html>', { status: 502, headers: { 'Content-Type': 'text/html' } }),
    ]) {
      cleanup()
      const { alerta } = await tentarLogin(resposta)
      expect(alerta.textContent).toMatch(/conectar à API|indisponível/)
      expect(alerta.textContent).not.toMatch(/senha/i)
    }
  })
})
