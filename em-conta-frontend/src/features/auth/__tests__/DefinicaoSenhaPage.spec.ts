import { fireEvent, screen } from '@testing-library/vue'
import { afterEach, describe, expect, it } from 'vitest'

import { apiFalsa, csrf, montarApp, problema } from '@/test/api-falsa'
import { jsonResponse } from '@/test/render-with-providers'

const TOKEN = 'A'.repeat(43)

afterEach(() => {
  window.history.replaceState(null, '', '/')
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
})

describe('DefinicaoSenhaPage', () => {
  it('remove o token da barra de endereço e mostra caminho para novo link quando inválido', async () => {
    window.history.replaceState(null, '', `/redefinir-senha#token=${TOKEN}`)
    const { fetchFn } = apiFalsa({
      ...csrf(),
      'POST /api/v1/auth/tokens/validacao': () =>
        problema(400, 'TOKEN_INVALIDO', 'Este link é inválido, expirou ou já foi utilizado.'),
    })

    await montarApp(fetchFn, '/redefinir-senha')

    expect(window.location.hash).toBe('')
    expect(window.location.href).not.toContain(TOKEN)
    expect(await screen.findByRole('alert')).toHaveTextContent('expirou ou já foi utilizado')
    expect(screen.getByRole('link', { name: 'Solicitar um novo link' })).toBeInTheDocument()
    const validacao = fetchFn.mock.calls.find(([url]) => String(url).endsWith('/tokens/validacao'))
    expect(String(validacao?.[0])).not.toContain(TOKEN) // o token vai só no corpo
  })

  it('valida a política localmente sem truncar e só então envia', async () => {
    window.history.replaceState(null, '', `/convite#token=${TOKEN}`)
    const { fetchFn, chamadas } = apiFalsa({
      ...csrf(),
      'POST /api/v1/auth/tokens/validacao': () =>
        jsonResponse({ finalidade: 'CONVITE', nome: 'Maria' }),
      'POST /api/v1/auth/convites/aceitacao': () => new Response(null, { status: 204 }),
    })
    const { router } = await montarApp(fetchFn, '/convite')

    expect(await screen.findByText(/Olá, Maria/)).toBeInTheDocument()
    await fireEvent.update(screen.getByLabelText('Nova senha'), 'curta demais')
    await fireEvent.update(screen.getByLabelText('Repita a nova senha'), 'curta demais')
    await fireEvent.click(screen.getByRole('button', { name: 'Ativar acesso' }))
    expect(await screen.findByText(/pelo menos 15 caracteres/)).toBeInTheDocument()
    expect(chamadas).not.toContain('POST /api/v1/auth/convites/aceitacao')

    const senha = '  frase longa com espaços  '
    await fireEvent.update(screen.getByLabelText('Nova senha'), senha)
    await fireEvent.update(screen.getByLabelText('Repita a nova senha'), senha)
    await fireEvent.click(screen.getByRole('button', { name: 'Ativar acesso' }))

    await screen.findByRole('heading', { name: 'Entrar no Em Conta' })
    expect(router.currentRoute.value.query.motivo).toBe('convite-aceito')
    const envio = fetchFn.mock.calls.find(([url]) => String(url).endsWith('/convites/aceitacao'))
    expect(JSON.parse(String(envio?.[1]?.body))).toEqual({ token: TOKEN, senha })
  })
})
