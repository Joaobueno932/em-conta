import { cleanup, fireEvent, screen } from '@testing-library/vue'
import { afterEach, describe, expect, it } from 'vitest'

import { apiFalsa, csrf, montarApp, problema, type Rotas } from '@/test/api-falsa'

afterEach(() => {
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
})

const semSessao = () => problema(401, 'NAO_AUTENTICADO', 'Autenticação necessária.')

async function tentarLogin(respostaLogin: Rotas[string]) {
  const { fetchFn } = apiFalsa({
    ...csrf(),
    'GET /api/v1/auth/sessao': semSessao,
    'POST /api/v1/auth/login': respostaLogin,
  })
  await montarApp(fetchFn, '/entrar')
  await fireEvent.update(screen.getByLabelText(/^E-mail/), 'pessoa@x.br')
  await fireEvent.update(screen.getByLabelText(/^Senha/), 'uma senha qualquer bem longa')
  await fireEvent.click(screen.getByRole('button', { name: 'Entrar' }))
  return { alerta: await screen.findByRole('alert'), fetchFn }
}

describe('LoginPage', () => {
  it('credenciais inválidas: mensagem genérica e os dois campos marcados', async () => {
    const { alerta, fetchFn } = await tentarLogin(() =>
      problema(401, 'CREDENCIAIS_INVALIDAS', 'E-mail ou senha inválidos.'),
    )
    expect(alerta).toHaveTextContent(
      'E-mail ou senha incorretos. Confira os dados e tente novamente.',
    )
    for (const campo of [screen.getByLabelText(/^E-mail/), screen.getByLabelText(/^Senha/)]) {
      expect(campo).toHaveAttribute('aria-invalid', 'true')
      expect(campo).toHaveAccessibleDescription(/E-mail ou senha incorretos/)
    }
    const envio = fetchFn.mock.calls.find(([url]) => String(url).endsWith('/auth/login'))
    expect(new Headers(envio?.[1]?.headers).get('X-XSRF-TOKEN')).toBe('token-csrf')
  })

  it('bloqueio temporário: informa a espera', async () => {
    const { alerta } = await tentarLogin(() =>
      problema(429, 'MUITAS_TENTATIVAS', 'Muitas tentativas.', { 'Retry-After': '120' }),
    )
    expect(alerta).toHaveTextContent('Aguarde 2 minutos')
    expect(screen.getByLabelText(/^E-mail/)).not.toHaveAttribute('aria-invalid')
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

  it('campos vazios: mensagens por campo, foco no primeiro e nada é enviado', async () => {
    const { fetchFn, chamadas } = apiFalsa({ ...csrf(), 'GET /api/v1/auth/sessao': semSessao })
    await montarApp(fetchFn, '/entrar')

    await fireEvent.click(screen.getByRole('button', { name: 'Entrar' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Campos vazios enviados, preencha todos os campos obrigatórios.',
    )
    expect(screen.getByLabelText(/^E-mail/)).toHaveAccessibleDescription(/Informe seu e-mail/)
    expect(screen.getByLabelText(/^Senha/)).toHaveAccessibleDescription(/Informe sua senha/)
    expect(screen.getByLabelText(/^E-mail/)).toHaveFocus()
    expect(chamadas).not.toContain('POST /api/v1/auth/login')

    await fireEvent.update(screen.getByLabelText(/^E-mail/), 'pessoa@x.br')
    expect(screen.queryByText('Informe seu e-mail')).not.toBeInTheDocument()
    expect(screen.getByText('Informe sua senha')).toBeInTheDocument()
  })

  it('mostrar senha alterna o tipo do campo e o estado do botão', async () => {
    const { fetchFn } = apiFalsa({ 'GET /api/v1/auth/sessao': semSessao })
    await montarApp(fetchFn, '/entrar')
    const senha = screen.getByLabelText(/^Senha/)
    const botao = screen.getByRole('button', { name: 'Mostrar senha' })

    expect(senha).toHaveAttribute('type', 'password')
    expect(botao).toHaveAttribute('aria-pressed', 'false')
    await fireEvent.click(botao)
    expect(senha).toHaveAttribute('type', 'text')
    expect(botao).toHaveAttribute('aria-pressed', 'true')
  })

  it('sessão expirada: aviso acima do título, sem preencher dados da sessão anterior', async () => {
    const { fetchFn } = apiFalsa({ 'GET /api/v1/auth/sessao': semSessao })
    await montarApp(fetchFn, '/entrar?motivo=expirada&redirect=%2Fassociados')

    expect(screen.getByRole('status')).toHaveTextContent(
      'Sua sessão expirou ou foi encerrada. Entre novamente para continuar.',
    )
    expect(screen.getByRole('heading', { name: 'Entrar' })).toBeInTheDocument()
    expect(screen.getByLabelText(/^E-mail/)).toHaveValue('')
    expect(screen.getByRole('link', { name: 'Esqueci minha senha' })).toHaveAttribute(
      'href',
      '/recuperar-senha',
    )
  })
})
