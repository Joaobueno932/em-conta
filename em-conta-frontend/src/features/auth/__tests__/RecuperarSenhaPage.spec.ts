import { fireEvent, screen } from '@testing-library/vue'
import { afterEach, describe, expect, it } from 'vitest'

import { apiFalsa, csrf, montarApp, problema } from '@/test/api-falsa'
import { jsonResponse } from '@/test/render-with-providers'

const semSessao = () => problema(401, 'NAO_AUTENTICADO', 'Autenticação necessária.')
const MENSAGEM_GENERICA =
  'Se houver uma conta ativa com este e-mail, enviaremos as instruções em instantes.'

afterEach(() => {
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
})

async function abrir(resposta = () => jsonResponse({ mensagem: MENSAGEM_GENERICA }, 202)) {
  const api = apiFalsa({
    ...csrf(),
    'GET /api/v1/auth/sessao': semSessao,
    'POST /api/v1/auth/senha/recuperacao': resposta,
  })
  await montarApp(api.fetchFn, '/recuperar-senha')
  await screen.findByRole('heading', { name: 'Esqueceu sua senha?' })
  return api
}

describe('RecuperarSenhaPage', () => {
  it('e-mail vazio ou fora do formato é apontado sem enviar', async () => {
    const { chamadas } = await abrir()
    const campo = screen.getByLabelText(/^E-mail/)

    await fireEvent.click(screen.getByRole('button', { name: 'Enviar' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Campos vazios enviados, preencha todos os campos obrigatórios.',
    )
    expect(campo).toHaveAccessibleDescription(/Informe seu e-mail/)
    expect(campo).toHaveFocus()

    await fireEvent.update(campo, 'pessoa#exemplo.com.br')
    await fireEvent.click(screen.getByRole('button', { name: 'Enviar' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Formato de e-mail inválido.')
    expect(campo).toHaveAttribute('aria-invalid', 'true')
    expect(campo).toHaveAccessibleDescription(/Informe um e-mail válido/)
    expect(chamadas).not.toContain('POST /api/v1/auth/senha/recuperacao')
  })

  it('confirmação genérica: não revela se a conta existe e permite tentar outro e-mail', async () => {
    const { fetchFn } = await abrir()
    await fireEvent.update(screen.getByLabelText(/^E-mail/), ' pessoa@exemplo.com.br ')
    await fireEvent.click(screen.getByRole('button', { name: 'Enviar' }))

    expect(await screen.findByRole('heading', { name: 'Verifique seu e-mail' })).toHaveFocus()
    expect(screen.getByRole('status')).toHaveTextContent(
      'Se houver uma conta vinculada ao e-mail pessoa@exemplo.com.br, você receberá um link para redefinir sua senha.',
    )
    expect(screen.getByRole('link', { name: /Voltar ao login/ })).toHaveAttribute('href', '/entrar')
    const envio = fetchFn.mock.calls.find(([url]) => String(url).endsWith('/senha/recuperacao'))
    expect(JSON.parse(String(envio?.[1]?.body))).toEqual({ email: 'pessoa@exemplo.com.br' }) // o campo de e-mail descarta os espaços das pontas

    await fireEvent.click(screen.getByRole('button', { name: /Tentar outro e-mail/ }))
    expect(await screen.findByRole('heading', { name: 'Esqueceu sua senha?' })).toBeInTheDocument()
    expect(screen.getByLabelText(/^E-mail/)).toHaveValue('')
  })

  it('limite de tentativas aparece como erro, sem confirmação', async () => {
    await abrir(() => problema(429, 'MUITAS_TENTATIVAS', 'Muitas tentativas.'))
    await fireEvent.update(screen.getByLabelText(/^E-mail/), 'pessoa@exemplo.com.br')
    await fireEvent.click(screen.getByRole('button', { name: 'Enviar' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Muitas tentativas')
    expect(screen.queryByRole('heading', { name: 'Verifique seu e-mail' })).not.toBeInTheDocument()
  })
})
