import { fireEvent, screen } from '@testing-library/vue'
import { afterEach, describe, expect, it } from 'vitest'

import { apiFalsa, csrf, montarApp, problema } from '@/test/api-falsa'
import { jsonResponse } from '@/test/render-with-providers'

const TOKEN = 'A'.repeat(43)

afterEach(() => {
  window.history.replaceState(null, '', '/')
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
})

async function abrirRedefinicao(rotas: Parameters<typeof apiFalsa>[0] = {}) {
  window.history.replaceState(null, '', `/redefinir-senha#token=${TOKEN}`)
  const api = apiFalsa({
    ...csrf(),
    'POST /api/v1/auth/tokens/validacao': () =>
      jsonResponse({ finalidade: 'REDEFINICAO_SENHA', nome: 'Maria' }),
    ...rotas,
  })
  const app = await montarApp(api.fetchFn, '/redefinir-senha')
  // O título já aparece enquanto o link é verificado; os campos, só depois.
  await screen.findByLabelText(/^Nova senha/)
  return { ...api, ...app }
}

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
    expect(screen.getByRole('heading', { name: 'Link expirado' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Voltar ao login/ })).toHaveAttribute('href', '/entrar')
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
    await montarApp(fetchFn, '/convite')

    expect(await screen.findByText(/Olá, Maria/)).toBeInTheDocument()
    await fireEvent.update(screen.getByLabelText(/^Nova senha/), 'curta demais')
    await fireEvent.update(screen.getByLabelText(/^Confirmar nova senha/), 'curta demais')
    await fireEvent.click(screen.getByRole('button', { name: 'Ativar acesso' }))
    expect(await screen.findByText(/pelo menos 15 caracteres/)).toBeInTheDocument()
    expect(chamadas).not.toContain('POST /api/v1/auth/convites/aceitacao')

    const senha = '  frase longa com espaços  '
    await fireEvent.update(screen.getByLabelText(/^Nova senha/), senha)
    await fireEvent.update(screen.getByLabelText(/^Confirmar nova senha/), senha)
    await fireEvent.click(screen.getByRole('button', { name: 'Ativar acesso' }))

    expect(await screen.findByRole('heading', { name: 'Acesso ativado' })).toHaveFocus()
    expect(screen.getByRole('link', { name: /Ir para o login/ })).toHaveAttribute('href', '/entrar')
    const envio = fetchFn.mock.calls.find(([url]) => String(url).endsWith('/convites/aceitacao'))
    expect(JSON.parse(String(envio?.[1]?.body))).toEqual({ token: TOKEN, senha })
  })

  it('mostra os requisitos da política vigente ligados ao campo', async () => {
    await abrirRedefinicao()
    expect(screen.getByLabelText(/^Nova senha/)).toHaveAccessibleDescription(
      /Requisitos.*De 15 a 128 caracteres/,
    )
  })

  it('confirmação vazia e senhas diferentes bloqueiam o envio', async () => {
    const { chamadas } = await abrirRedefinicao()
    const nova = screen.getByLabelText(/^Nova senha/)
    const confirmar = screen.getByLabelText(/^Confirmar nova senha/)

    await fireEvent.update(nova, 'uma frase longa o bastante')
    await fireEvent.click(screen.getByRole('button', { name: 'Redefinir senha' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Campos vazios enviados, preencha todos os campos obrigatórios.',
    )
    expect(confirmar).toHaveAccessibleDescription(/Insira novamente a nova senha/)
    expect(confirmar).toHaveFocus()

    await fireEvent.update(confirmar, 'outra frase longa o bastante')
    await fireEvent.click(screen.getByRole('button', { name: 'Redefinir senha' }))
    expect(screen.getByRole('alert')).toHaveTextContent('As senhas não coincidem.')
    expect(nova).toHaveAttribute('aria-invalid', 'true')
    expect(confirmar).toHaveAttribute('aria-invalid', 'true')
    expect(chamadas).not.toContain('POST /api/v1/auth/senha/redefinicao')
  })

  it('redefinição concluída mostra o sucesso e o caminho para o login', async () => {
    const { fetchFn } = await abrirRedefinicao({
      'POST /api/v1/auth/senha/redefinicao': () => new Response(null, { status: 204 }),
    })
    const senha = 'nova senha escolhida agora'
    await fireEvent.update(screen.getByLabelText(/^Nova senha/), senha)
    await fireEvent.update(screen.getByLabelText(/^Confirmar nova senha/), senha)
    await fireEvent.click(screen.getByRole('button', { name: 'Redefinir senha' }))

    expect(await screen.findByRole('heading', { name: 'Senha alterada com sucesso' })).toHaveFocus()
    expect(screen.getByRole('status')).toHaveTextContent(
      'Use sua nova senha para acessar sua conta.',
    )
    expect(screen.getByRole('link', { name: /Voltar ao login/ })).toHaveAttribute('href', '/entrar')
    const envio = fetchFn.mock.calls.find(([url]) => String(url).endsWith('/senha/redefinicao'))
    expect(JSON.parse(String(envio?.[1]?.body))).toEqual({ token: TOKEN, novaSenha: senha })
  })

  it('link que expira entre a abertura e o envio leva ao estado de link expirado', async () => {
    await abrirRedefinicao({
      'POST /api/v1/auth/senha/redefinicao': () =>
        problema(400, 'TOKEN_INVALIDO', 'Este link é inválido, expirou ou já foi utilizado.'),
    })
    const senha = 'nova senha escolhida agora'
    await fireEvent.update(screen.getByLabelText(/^Nova senha/), senha)
    await fireEvent.update(screen.getByLabelText(/^Confirmar nova senha/), senha)
    await fireEvent.click(screen.getByRole('button', { name: 'Redefinir senha' }))

    expect(await screen.findByRole('heading', { name: 'Link expirado' })).toBeInTheDocument()
  })
})
