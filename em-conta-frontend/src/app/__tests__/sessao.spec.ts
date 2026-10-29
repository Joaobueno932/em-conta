import { fireEvent, screen, waitFor } from '@testing-library/vue'
import { afterEach, describe, expect, it } from 'vitest'
import { createMemoryHistory } from 'vue-router'

import { chaveSessao } from '@/features/auth/auth-api'
import {
  apiFalsa,
  csrf,
  montarApp,
  PERMISSOES_ADMIN,
  problema,
  usuarioAtual,
} from '@/test/api-falsa'
import { jsonResponse } from '@/test/render-with-providers'

import { destinoPosLogin } from '../guards'
import { createAppRouter } from '../router'

const semSessao = () => problema(401, 'NAO_AUTENTICADO', 'Autenticação necessária.')
const paginaVazia = () =>
  jsonResponse({ itens: [], pagina: 0, tamanho: 20, totalItens: 0, totalPaginas: 0 })

afterEach(() => {
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
})

describe('guardas e ciclo de sessão', () => {
  it('não exibe conteúdo protegido enquanto a sessão carrega e redireciona ao login sem sessão', async () => {
    let liberar: (r: Response) => void = () => {}
    const { fetchFn } = apiFalsa({
      'GET /api/v1/auth/sessao': () => new Promise<Response>((resolve) => (liberar = resolve)),
    })

    const navegacao = montarApp(fetchFn, '/admin/usuarios')
    await waitFor(() => expect(fetchFn).toHaveBeenCalled())
    expect(screen.queryByRole('heading', { name: 'Usuários' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Sair' })).not.toBeInTheDocument()

    liberar(semSessao())
    const { router } = await navegacao

    expect(router.currentRoute.value.name).toBe('entrar')
    expect(router.currentRoute.value.query.redirect).toBe('/admin/usuarios')
    expect(await screen.findByRole('heading', { name: 'Entrar no Em Conta' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Usuários' })).not.toBeInTheDocument()
  })

  it('mostra acesso negado quando falta a permissão da rota', async () => {
    const { fetchFn } = apiFalsa({
      'GET /api/v1/auth/sessao': () => jsonResponse(usuarioAtual('comum@x.br')),
    })
    const { router } = await montarApp(fetchFn, '/admin/usuarios')

    expect(router.currentRoute.value.name).toBe('acesso-negado')
    expect(await screen.findByRole('heading', { name: 'Acesso negado' })).toBeInTheDocument()
    expect(fetchFn.mock.calls.some(([url]) => String(url).includes('/v1/usuarios'))).toBe(false)
  })

  it('sessão expirada durante o uso limpa o cache e leva ao login com aviso', async () => {
    const { fetchFn } = apiFalsa({
      'GET /api/v1/auth/sessao': () => jsonResponse(usuarioAtual('adm@x.br', PERMISSOES_ADMIN)),
      'GET /api/v1/usuarios': () =>
        problema(401, 'SESSAO_EXPIRADA', 'Sua sessão expirou. Entre novamente.'),
    })
    const { router, queryClient } = await montarApp(fetchFn, '/admin/usuarios')

    await waitFor(() => expect(router.currentRoute.value.name).toBe('entrar'))
    expect(router.currentRoute.value.query).toMatchObject({
      motivo: 'expirada',
      redirect: '/admin/usuarios',
    })
    expect(await screen.findByText(/sessão expirou ou foi encerrada/)).toBeInTheDocument()
    expect(queryClient.getQueryData(chaveSessao)).toBeNull()
    expect(screen.queryByText('adm@x.br')).not.toBeInTheDocument()
  })

  it('logout limpa os dados do usuário e o próximo login não vê a sessão anterior', async () => {
    let sessaoAtual: ReturnType<typeof usuarioAtual> | null = usuarioAtual('ana@x.br')
    const { fetchFn, chamadas } = apiFalsa({
      ...csrf(),
      'GET /api/v1/auth/sessao': () => (sessaoAtual ? jsonResponse(sessaoAtual) : semSessao()),
      'POST /api/v1/auth/logout': () => {
        sessaoAtual = null
        return new Response(null, { status: 204 })
      },
      'POST /api/v1/auth/login': () => {
        sessaoAtual = usuarioAtual('bruno@x.br', PERMISSOES_ADMIN)
        return jsonResponse(sessaoAtual)
      },
      'GET /api/v1/usuarios': paginaVazia,
    })
    const { router, queryClient } = await montarApp(fetchFn, '/')

    expect(await screen.findByTestId('usuario-email')).toHaveTextContent('ana@x.br')
    await fireEvent.click(screen.getByRole('button', { name: 'Sair' }))

    await waitFor(() => expect(router.currentRoute.value.name).toBe('entrar'))
    expect(chamadas).toContain('POST /api/v1/auth/logout')
    expect(queryClient.getQueryData(chaveSessao)).toBeNull()
    expect(screen.queryByText('ana@x.br')).not.toBeInTheDocument()

    await fireEvent.update(screen.getByLabelText('E-mail'), 'bruno@x.br')
    await fireEvent.update(screen.getByLabelText('Senha'), 'senha do bruno, longa')
    await fireEvent.click(screen.getByRole('button', { name: 'Entrar' }))

    expect(await screen.findByTestId('usuario-email')).toHaveTextContent('bruno@x.br')
    expect(screen.queryByText('ana@x.br')).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Usuários' })).toBeInTheDocument()
  })

  it('não redireciona em ciclo: o login sem sessão permanece no login', async () => {
    const { fetchFn } = apiFalsa({ 'GET /api/v1/auth/sessao': semSessao })
    const { router } = await montarApp(fetchFn, '/entrar')
    expect(router.currentRoute.value.name).toBe('entrar')
    expect(router.currentRoute.value.query.redirect).toBeUndefined()
  })
})

describe('destinoPosLogin', () => {
  const router = createAppRouter(createMemoryHistory())

  it('aceita somente rotas internas autenticadas conhecidas', () => {
    expect(destinoPosLogin(router, '/admin/usuarios')).toBe('/admin/usuarios')
    expect(destinoPosLogin(router, '/admin/usuarios/7')).toBe('/admin/usuarios/7')
    for (const invalido of [
      '//malicioso.example',
      'https://malicioso.example',
      '/\\malicioso.example',
      '/entrar',
      '/convite',
      '/rota-inexistente',
      undefined,
      ['/admin/usuarios'],
    ]) {
      expect(destinoPosLogin(router, invalido)).toEqual({ name: 'inicio' })
    }
  })
})
