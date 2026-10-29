import { fireEvent, screen, waitFor } from '@testing-library/vue'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { apiFalsa, csrf, montarApp, problema, usuarioAtual } from '@/test/api-falsa'
import { jsonResponse } from '@/test/render-with-providers'

import { cnpjValido, cpfValido, formatarDocumento } from '../documento'

const PERMISSOES_ASSOCIADOS = [
  'associados:consultar',
  'associados:cadastrar',
  'associados:editar',
  'associados:consultar-historico',
  'ucs:consultar',
  'ucs:cadastrar',
  'ucs:editar',
]

const sessao =
  (permissoes = PERMISSOES_ASSOCIADOS) =>
  () =>
    jsonResponse(usuarioAtual('adm@x.br', permissoes))

const autor = { id: 1, nome: 'Pessoa adm@x.br' }

function associado(sobrescrever: Record<string, unknown> = {}) {
  return {
    id: 7,
    tipo: 'PJ',
    documento: '12.ABC.345/01DE-35',
    nome: 'Agropecuária Aurora Ltda.',
    nomeFantasia: 'Aurora',
    contatos: [{ id: 3, nome: 'Ricardo', email: 'ricardo@aurora.com.br', versao: 0 }],
    quantidadeUcs: 0,
    criadoEm: '2026-10-28T12:00:00Z',
    criadoPor: autor,
    atualizadoEm: '2026-10-28T12:00:00Z',
    atualizadoPor: autor,
    versao: 0,
    ...sobrescrever,
  }
}

function pagina(itens: unknown[], total = itens.length) {
  return jsonResponse({ itens, pagina: 0, tamanho: 20, totalItens: total, totalPaginas: 1 })
}

afterEach(() => {
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
  vi.restoreAllMocks()
})

describe('documentos no navegador', () => {
  it('valida CPF e CNPJ, inclusive alfanumérico, sem depender da pontuação', () => {
    expect(cpfValido('529.982.247-25')).toBe(true)
    expect(cpfValido('52998224724')).toBe(false)
    expect(cpfValido('111.111.111-11')).toBe(false)
    expect(cnpjValido('11.222.333/0001-81')).toBe(true)
    expect(cnpjValido('12.abc.345/01de-35')).toBe(true)
    expect(cnpjValido('12ABC34501DE36')).toBe(false)
    expect(formatarDocumento('PJ', '12abc34501de35')).toBe('12.ABC.345/01DE-35')
    expect(formatarDocumento('PF', '5299822')).toBe('5299822')
  })
})

describe('listagem de associados', () => {
  it('lê filtros e página da URL e mantém-nos na consulta', async () => {
    const { fetchFn, chamadas } = apiFalsa({
      'GET /api/v1/auth/sessao': sessao(),
      'GET /api/v1/associados': () =>
        pagina([
          {
            id: 7,
            tipo: 'PJ',
            documentoMascarado: '12.ABC.***/01DE-35',
            nome: 'Agropecuária Aurora Ltda.',
            quantidadeUcs: 2,
            criadoEm: '2026-10-28T12:00:00Z',
          },
        ]),
    })
    await montarApp(fetchFn, '/associados?busca=aurora&tipo=PJ&pagina=2')

    expect(
      await screen.findByRole('link', { name: 'Agropecuária Aurora Ltda.' }),
    ).toBeInTheDocument()
    expect(screen.getByText('12.ABC.***/01DE-35')).toBeInTheDocument()
    const consulta = chamadas.find((c) => c.startsWith('GET /api/v1/associados?'))
    expect(consulta).toContain('busca=aurora')
    expect(consulta).toContain('tipo=PJ')
    expect(consulta).toContain('pagina=2')
    expect(screen.getByRole('searchbox', { name: 'Buscar associados' })).toHaveValue('aurora')
  })

  it('distingue lista vazia, busca sem resultados e erro', async () => {
    const { fetchFn } = apiFalsa({
      'GET /api/v1/auth/sessao': sessao(['associados:consultar']),
      'GET /api/v1/associados': () => pagina([]),
    })
    await montarApp(fetchFn, '/associados?busca=ninguem')
    expect(
      await screen.findByText(/Nenhum associado encontrado para os filtros informados/),
    ).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Novo associado' })).not.toBeInTheDocument()

    const falha = apiFalsa({
      'GET /api/v1/auth/sessao': sessao(['associados:consultar']),
      'GET /api/v1/associados': () => problema(503, 'INDISPONIVEL', 'x'),
    })
    await montarApp(falha.fetchFn, '/associados')
    expect(await screen.findByText(/A API está indisponível no momento/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Tentar novamente' })).toBeInTheDocument()
  })

  it('sessão expirada durante o uso leva ao login e descarta os dados', async () => {
    const { fetchFn } = apiFalsa({
      'GET /api/v1/auth/sessao': sessao(),
      'GET /api/v1/associados': () =>
        problema(401, 'SESSAO_EXPIRADA', 'Sua sessão expirou. Entre novamente.'),
    })
    const { router, queryClient } = await montarApp(fetchFn, '/associados')

    await waitFor(() => expect(router.currentRoute.value.name).toBe('entrar'))
    expect(router.currentRoute.value.query).toMatchObject({ motivo: 'expirada' })
    expect(queryClient.getQueryCache().findAll({ queryKey: ['associados'] })).toHaveLength(0)
  })
})

describe('cadastro de associado', () => {
  it('mostra erros do servidor nos campos certos e mantém o preenchimento', async () => {
    let liberar: (r: Response) => void = () => {}
    const { fetchFn, chamadas } = apiFalsa({
      ...csrf(),
      'GET /api/v1/auth/sessao': sessao(),
      'POST /api/v1/associados': () => new Promise<Response>((resolve) => (liberar = resolve)),
    })
    await montarApp(fetchFn, '/associados/novo')

    await fireEvent.update(await screen.findByLabelText(/Nome completo/), 'Lúcia Prado')
    await fireEvent.update(screen.getByLabelText(/^CPF/), '529.982.247-25')
    await fireEvent.click(screen.getByRole('button', { name: 'Adicionar contato' }))
    await fireEvent.update(screen.getByLabelText(/^Nome/, { selector: '#contato-1-nome' }), 'Lúcia')

    const salvar = screen.getByRole('button', { name: 'Salvar associado' })
    await fireEvent.click(salvar)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Salvando…' })).toBeDisabled())
    // Segundo clique durante o envio não gera nova requisição.
    await fireEvent.click(screen.getByRole('button', { name: 'Salvando…' }))
    expect(chamadas.filter((c) => c === 'POST /api/v1/associados')).toHaveLength(1)

    liberar(
      new Response(
        JSON.stringify({
          status: 400,
          codigo: 'DADOS_INVALIDOS',
          detail: 'Informe um e-mail ou um telefone.',
          campos: { 'contatos[0].email': 'Informe um e-mail ou um telefone.' },
        }),
        { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
      ),
    )

    const email = await screen.findByLabelText('E-mail')
    await waitFor(() => expect(email).toHaveAttribute('aria-invalid', 'true'))
    expect(email).toHaveAccessibleDescription('Informe um e-mail ou um telefone.')
    expect(screen.getByText(/Os dados digitados foram mantidos/)).toBeInTheDocument()
    expect(screen.getByLabelText(/Nome completo/)).toHaveValue('Lúcia Prado')
    expect(screen.getByLabelText(/^CPF/)).toHaveValue('529.982.247-25')
  })

  it('documento duplicado oferece o cadastro existente sem sobrescrever', async () => {
    const { fetchFn } = apiFalsa({
      ...csrf(),
      'GET /api/v1/auth/sessao': sessao(),
      'POST /api/v1/associados': () =>
        new Response(
          JSON.stringify({
            status: 409,
            codigo: 'DOCUMENTO_JA_CADASTRADO',
            detail: 'Já existe um associado com este documento.',
            campos: { documento: 'Já existe um associado com este documento.' },
            associadoExistenteId: 7,
          }),
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        ),
    })
    await montarApp(fetchFn, '/associados/novo')
    await fireEvent.click(await screen.findByLabelText(/Pessoa jurídica/))
    await fireEvent.update(screen.getByLabelText(/Razão social/), 'Aurora')
    await fireEvent.update(screen.getByLabelText(/^CNPJ/), '12ABC34501DE35')
    await fireEvent.click(screen.getByRole('button', { name: 'Salvar associado' }))

    const link = await screen.findByRole('link', { name: 'Abrir o cadastro existente' })
    expect(link).toHaveAttribute('href', '/associados/7')
    expect(screen.getByLabelText(/Razão social/)).toHaveValue('Aurora')
  })

  it('pede confirmação antes de descartar o formulário alterado', async () => {
    const confirmar = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const { fetchFn } = apiFalsa({
      'GET /api/v1/auth/sessao': sessao(),
      'GET /api/v1/associados': () => pagina([]),
    })
    const { router } = await montarApp(fetchFn, '/associados/novo')
    await fireEvent.update(await screen.findByLabelText(/Nome completo/), 'Rascunho')

    await router.push('/associados')
    expect(confirmar).toHaveBeenCalled()
    expect(router.currentRoute.value.name).toBe('associado-novo')
    expect(screen.getByLabelText(/Nome completo/)).toHaveValue('Rascunho')
  })
})

describe('edição de associado', () => {
  it('conflito de versão preserva o que foi digitado e oferece recarga', async () => {
    let versaoServidor = 0
    const { fetchFn } = apiFalsa({
      ...csrf(),
      'GET /api/v1/auth/sessao': sessao(),
      'GET /api/v1/associados/7': () =>
        jsonResponse(associado({ versao: versaoServidor, nome: `Nome v${versaoServidor}` })),
      'PUT /api/v1/associados/7': () => {
        versaoServidor = 1
        return problema(409, 'CONFLITO_CONCORRENTE', 'Os dados foram alterados.')
      },
    })
    await montarApp(fetchFn, '/associados/7/editar')

    const nome = await screen.findByLabelText(/Razão social/)
    await waitFor(() => expect(nome).toHaveValue('Nome v0'))
    await fireEvent.update(nome, 'Minha edição')
    await fireEvent.click(screen.getByRole('button', { name: 'Salvar alterações' }))

    expect(await screen.findByText(/alterado por outra pessoa/)).toBeInTheDocument()
    expect(screen.getByLabelText(/Razão social/)).toHaveValue('Minha edição')
    expect(screen.getByLabelText('CNPJ')).toHaveAttribute('readonly')

    await fireEvent.click(screen.getByRole('button', { name: /Recarregar a versão atual/ }))
    await waitFor(() => expect(screen.getByLabelText(/Razão social/)).toHaveValue('Nome v1'))
  })
})

describe('página do associado', () => {
  it('mostra só as abas e ações permitidas e indica áreas indisponíveis', async () => {
    const { fetchFn } = apiFalsa({
      'GET /api/v1/auth/sessao': sessao(['associados:consultar']),
      'GET /api/v1/associados/7': () => jsonResponse(associado()),
    })
    await montarApp(fetchFn, '/associados/7')

    expect(
      await screen.findByRole('heading', { name: 'Agropecuária Aurora Ltda.', level: 1 }),
    ).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Dados cadastrais' })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: /Unidades consumidoras/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Histórico' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Editar cadastro' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Adicionar contato' })).not.toBeInTheDocument()
    expect(
      screen.getByText(/contratos, faturas, cobranças e pendências ainda não/),
    ).toBeInTheDocument()
    expect(screen.getByText('Ricardo')).toBeInTheDocument()
  })

  it('histórico mostra autor, ação e diferenças', async () => {
    const { fetchFn } = apiFalsa({
      'GET /api/v1/auth/sessao': sessao(),
      'GET /api/v1/associados/7': () => jsonResponse(associado()),
      'GET /api/v1/associados/7/historico': () =>
        jsonResponse({
          itens: [
            {
              id: 1,
              ocorridoEm: '2026-10-28T12:00:00Z',
              acao: 'CONTATO_ALTERADO',
              categoria: 'CONTATOS',
              contatoId: 3,
              referencia: 'Ricardo',
              autor: { id: 9, nome: 'Usuária Desativada' },
              alteracoes: [
                { campo: 'email', antes: 'r***@aurora.com.br', depois: 'r***@novo.com' },
              ],
            },
          ],
          pagina: 0,
          tamanho: 20,
          totalItens: 1,
          totalPaginas: 1,
        }),
    })
    await montarApp(fetchFn, '/associados/7?aba=historico')

    expect(await screen.findByText('Contato atualizado')).toBeInTheDocument()
    expect(screen.getByText('Usuária Desativada')).toBeInTheDocument()
    expect(screen.getByText(/r\*\*\*@aurora\.com\.br → r\*\*\*@novo\.com/)).toBeInTheDocument()
  })
})
