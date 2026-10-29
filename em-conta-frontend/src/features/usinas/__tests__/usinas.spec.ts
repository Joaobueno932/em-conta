import { fireEvent, screen, waitFor, within } from '@testing-library/vue'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { apiFalsa, csrf, montarApp, problema, usuarioAtual } from '@/test/api-falsa'
import { jsonResponse } from '@/test/render-with-providers'

const TODAS = [
  'usinas:consultar',
  'usinas:cadastrar',
  'usinas:editar',
  'usinas:consultar-historico',
  'vinculos-usina:consultar',
  'vinculos-usina:criar',
  'vinculos-usina:encerrar',
  'associados:consultar',
  'ucs:consultar',
]

const sessao =
  (permissoes = TODAS) =>
  () =>
    jsonResponse(usuarioAtual('adm@x.br', permissoes))

const autor = { id: 1, nome: 'Pessoa adm@x.br' }

function usina(sobrescrever: Record<string, unknown> = {}) {
  return {
    id: 5,
    codigo: 'USN-0005',
    nome: 'Usina Teste',
    cidade: 'Sidrolândia',
    uf: 'MS',
    ucsVinculadas: 0,
    associadosDistintos: 0,
    vinculosEncerrados: 0,
    criadoEm: '2026-10-29T12:00:00Z',
    criadoPor: autor,
    atualizadoEm: '2026-10-29T12:00:00Z',
    atualizadoPor: autor,
    versao: 0,
    ...sobrescrever,
  }
}

const uc = {
  id: 9,
  numero: '0001234',
  concessionariaNome: 'Energisa MS',
  associadoId: 7,
  associadoNome: 'Titular Teste',
  associadoTipo: 'PJ',
  associadoDocumentoMascarado: '11.222.***/0001-81',
}

function vinculo(sobrescrever: Record<string, unknown> = {}) {
  return {
    id: 3,
    usina: { id: 5, codigo: 'USN-0005', nome: 'Usina Teste' },
    uc,
    inicio: '2026-10-01',
    vigente: true,
    criadoEm: '2026-10-29T12:00:00Z',
    criadoPor: autor,
    versao: 0,
    ...sobrescrever,
  }
}

const paginaVazia = () =>
  jsonResponse({ itens: [], pagina: 0, tamanho: 20, totalItens: 0, totalPaginas: 0 })

function corpoProblema(status: number, corpo: Record<string, unknown>) {
  return new Response(JSON.stringify({ status, ...corpo }), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  })
}

afterEach(() => {
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
  vi.restoreAllMocks()
})

describe('listagem de usinas', () => {
  it('usa filtros da URL e mostra só dados cadastrais', async () => {
    const { fetchFn, chamadas } = apiFalsa({
      'GET /api/v1/auth/sessao': sessao(['usinas:consultar']),
      'GET /api/v1/usinas': () =>
        jsonResponse({
          itens: [
            {
              id: 5,
              codigo: 'USN-0005',
              nome: 'Usina Teste',
              uf: 'MS',
              cidade: 'Terenos',
              ucsVinculadas: 2,
              associadosDistintos: 1,
              criadoEm: '2026-10-29T12:00:00Z',
            },
          ],
          pagina: 0,
          tamanho: 20,
          totalItens: 1,
          totalPaginas: 1,
        }),
    })
    await montarApp(fetchFn, '/usinas?busca=teste&uf=MS')

    expect(await screen.findByRole('link', { name: 'Usina Teste' })).toBeInTheDocument()
    expect(screen.getByText('Terenos/MS')).toBeInTheDocument()
    expect(screen.queryByText(/kWp/)).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Nova usina' })).not.toBeInTheDocument()
    const consulta = chamadas.find((c) => c.startsWith('GET /api/v1/usinas?'))
    expect(consulta).toContain('busca=teste')
    expect(consulta).toContain('uf=MS')
  })

  it('distingue vazio, sem resultados e erro; sessão expirada leva ao login', async () => {
    const vazia = apiFalsa({
      'GET /api/v1/auth/sessao': sessao(),
      'GET /api/v1/usinas': paginaVazia,
    })
    await montarApp(vazia.fetchFn, '/usinas')
    expect(await screen.findByText('Nenhuma usina cadastrada ainda.')).toBeInTheDocument()

    const falha = apiFalsa({
      'GET /api/v1/auth/sessao': sessao(),
      'GET /api/v1/usinas': () => problema(503, 'X', 'x'),
    })
    await montarApp(falha.fetchFn, '/usinas?busca=x')
    expect(await screen.findByText(/A API está indisponível/)).toBeInTheDocument()

    const expirada = apiFalsa({
      'GET /api/v1/auth/sessao': sessao(),
      'GET /api/v1/usinas': () => problema(401, 'SESSAO_EXPIRADA', 'Sua sessão expirou.'),
    })
    const { router } = await montarApp(expirada.fetchFn, '/usinas')
    await waitFor(() => expect(router.currentRoute.value.name).toBe('entrar'))
  })
})

describe('cadastro de usina', () => {
  it('aviso de nome parecido permite confirmar e mantém o preenchimento', async () => {
    const corpos: unknown[] = []
    const { fetchFn } = apiFalsa({
      ...csrf(),
      'GET /api/v1/auth/sessao': sessao(),
      'GET /api/v1/fazendas': paginaVazia,
      'POST /api/v1/usinas': (init) => {
        const corpo = JSON.parse(String(init?.body))
        corpos.push(corpo)
        if (!corpo.confirmarNomeRepetido) {
          return corpoProblema(409, {
            codigo: 'NOME_POSSIVEL_DUPLICIDADE',
            detail: 'Já existe cadastro com nome igual ou muito parecido.',
            campos: { nome: 'Já existe cadastro com nome igual ou muito parecido.' },
            existentes: [{ id: 2, codigo: 'USN-0002', nome: 'São Miguel' }],
          })
        }
        return jsonResponse(usina({ nome: 'Sao Miguel' }), 201)
      },
      'GET /api/v1/usinas/5': () => jsonResponse(usina({ nome: 'Sao Miguel' })),
    })
    const { router } = await montarApp(fetchFn, '/usinas/nova')

    await fireEvent.update(await screen.findByLabelText(/Nome da usina/), 'Sao Miguel')
    await fireEvent.update(screen.getByLabelText('Cidade'), 'Maracaju')
    await fireEvent.click(screen.getByRole('button', { name: 'Salvar usina' }))

    expect(await screen.findByRole('link', { name: 'USN-0002 · São Miguel' })).toBeInTheDocument()
    expect(screen.getByLabelText(/Nome da usina/)).toHaveValue('Sao Miguel')
    expect(screen.getByLabelText('Cidade')).toHaveValue('Maracaju')

    await fireEvent.click(screen.getByRole('button', { name: /salvar mesmo assim/ }))
    await waitFor(() => expect(router.currentRoute.value.name).toBe('usina'))
    expect(corpos).toHaveLength(2)
    expect(corpos[1]).toMatchObject({
      nome: 'Sao Miguel',
      cidade: 'Maracaju',
      confirmarNomeRepetido: true,
    })
  })

  it('conflito de versão preserva o texto e oferece recarga', async () => {
    const { fetchFn } = apiFalsa({
      ...csrf(),
      'GET /api/v1/auth/sessao': sessao(),
      'GET /api/v1/fazendas': paginaVazia,
      'GET /api/v1/usinas/5': () => jsonResponse(usina()),
      'PUT /api/v1/usinas/5': () => problema(409, 'CONFLITO_CONCORRENTE', 'Alterado.'),
    })
    await montarApp(fetchFn, '/usinas/5/editar')
    const nome = await screen.findByLabelText(/Nome da usina/)
    await waitFor(() => expect(nome).toHaveValue('Usina Teste'))
    await fireEvent.update(nome, 'Minha edição')
    await fireEvent.click(screen.getByRole('button', { name: 'Salvar usina' }))

    expect(await screen.findByText(/alterada por outra pessoa/)).toBeInTheDocument()
    expect(screen.getByLabelText(/Nome da usina/)).toHaveValue('Minha edição')
    expect(screen.getByRole('button', { name: /Recarregar a versão atual/ })).toBeInTheDocument()
  })
})

describe('detalhe da usina e vínculos', () => {
  it('sem permissão de vínculos não mostra a aba de UCs nem ações', async () => {
    const { fetchFn, chamadas } = apiFalsa({
      'GET /api/v1/auth/sessao': sessao(['usinas:consultar']),
      'GET /api/v1/usinas/5': () => jsonResponse(usina({ ucsVinculadas: 3 })),
    })
    await montarApp(fetchFn, '/usinas/5?aba=ucs')

    expect(
      await screen.findByRole('heading', { name: 'Usina Teste', level: 1 }),
    ).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Visão geral' })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    expect(screen.queryByRole('tab', { name: /Unidades consumidoras/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Editar informações' })).not.toBeInTheDocument()
    expect(chamadas.some((c) => c.includes('/vinculos'))).toBe(false)
    expect(screen.getByText(/não comprova rateio/)).toBeInTheDocument()
  })

  it('vincula UC existente, bloqueia UC de outra usina e atualiza os dados', async () => {
    let vinculada = false
    const corpos: unknown[] = []
    const { fetchFn, chamadas } = apiFalsa({
      ...csrf(),
      'GET /api/v1/auth/sessao': sessao(),
      'GET /api/v1/usinas/5': () => jsonResponse(usina({ ucsVinculadas: vinculada ? 1 : 0 })),
      'GET /api/v1/usinas/5/vinculos': () =>
        jsonResponse({
          itens: vinculada ? [vinculo()] : [],
          pagina: 0,
          tamanho: 20,
          totalItens: vinculada ? 1 : 0,
          totalPaginas: vinculada ? 1 : 0,
        }),
      'GET /api/v1/vinculos-usina/ucs-candidatas': () =>
        jsonResponse([
          { uc },
          {
            uc: { ...uc, id: 10, numero: '0009999', associadoNome: 'Outro Titular' },
            vinculoVigente: {
              vinculoId: 1,
              usina: { id: 8, codigo: 'USN-0008', nome: 'Outra Usina' },
              inicio: '2026-09-01',
            },
          },
        ]),
      'POST /api/v1/usinas/5/vinculos': (init) => {
        corpos.push(JSON.parse(String(init?.body)))
        vinculada = true
        return jsonResponse(vinculo(), 201)
      },
    })
    await montarApp(fetchFn, '/usinas/5?aba=ucs')

    expect(
      await screen.findByText('Nenhuma unidade consumidora vinculada a esta usina.'),
    ).toBeInTheDocument()
    await fireEvent.click(screen.getByRole('button', { name: 'Vincular unidade consumidora' }))
    await fireEvent.update(screen.getByRole('searchbox', { name: 'Buscar UC' }), 'Titular')
    await fireEvent.click(screen.getByRole('button', { name: 'Buscar UC' }))

    const outra = await screen.findByRole('radio', { name: /0009999/ })
    expect(outra).toBeDisabled()
    expect(screen.getByText(/Vinculada a outra usina/)).toBeInTheDocument()

    await fireEvent.click(screen.getByRole('radio', { name: /0001234/ }))
    expect(screen.getByLabelText(/Início do vínculo/)).toHaveValue(
      new Date().toLocaleDateString('sv-SE'),
    )
    await fireEvent.update(screen.getByLabelText(/Início do vínculo/), '2026-10-01')
    await fireEvent.click(screen.getByRole('button', { name: 'Vincular UC' }))

    expect(await screen.findByText('UC vinculada à usina.')).toBeInTheDocument()
    expect(corpos[0]).toEqual({ ucId: 9, inicio: '2026-10-01' })
    const tabela = await screen.findByRole('table')
    expect(within(tabela).getByText('0001234')).toBeInTheDocument()
    expect(within(tabela).getByRole('link', { name: 'Titular Teste' })).toBeInTheDocument()
    await waitFor(() =>
      expect(screen.getByRole('tab', { name: 'Unidades consumidoras (1)' })).toBeInTheDocument(),
    )
    expect(
      chamadas.filter((c) => c.startsWith('GET /api/v1/usinas/5/vinculos')).length,
    ).toBeGreaterThan(1)
  })

  it('encerramento exige confirmação e mostra erro do motivo no campo', async () => {
    const confirmar = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const { fetchFn } = apiFalsa({
      ...csrf(),
      'GET /api/v1/auth/sessao': sessao(),
      'GET /api/v1/usinas/5': () => jsonResponse(usina({ ucsVinculadas: 1 })),
      'GET /api/v1/usinas/5/vinculos': () =>
        jsonResponse({
          itens: [vinculo()],
          pagina: 0,
          tamanho: 20,
          totalItens: 1,
          totalPaginas: 1,
        }),
      'POST /api/v1/usinas/5/vinculos/3/encerramento': () =>
        corpoProblema(400, {
          codigo: 'DADOS_INVALIDOS',
          detail: 'Informe o motivo do encerramento (mínimo 5 caracteres).',
          campos: { motivo: 'Informe o motivo do encerramento (mínimo 5 caracteres).' },
        }),
    })
    await montarApp(fetchFn, '/usinas/5?aba=ucs')

    await fireEvent.click(await screen.findByRole('button', { name: /^Encerrar/ }))
    await fireEvent.update(screen.getByLabelText(/Motivo/), 'abc')
    await fireEvent.click(screen.getByRole('button', { name: 'Encerrar vínculo' }))

    expect(confirmar).toHaveBeenCalled()
    const motivo = screen.getByLabelText(/Motivo/)
    await waitFor(() => expect(motivo).toHaveAttribute('aria-invalid', 'true'))
    expect(motivo).toHaveValue('abc')
  })
})

describe('ficha do associado', () => {
  const ficha = {
    id: 7,
    tipo: 'PJ',
    documento: '11.222.333/0001-81',
    nome: 'Titular Teste',
    contatos: [],
    quantidadeUcs: 1,
    criadoEm: '2026-10-29T12:00:00Z',
    criadoPor: autor,
    atualizadoEm: '2026-10-29T12:00:00Z',
    atualizadoPor: autor,
    versao: 0,
  }
  const ucsDoAssociado = () =>
    jsonResponse([
      {
        id: 9,
        associadoId: 7,
        concessionaria: 'ENERGISA_MS',
        concessionariaNome: 'Energisa MS',
        numero: '0001234',
        criadoEm: '2026-10-29T12:00:00Z',
        atualizadoEm: '2026-10-29T12:00:00Z',
        versao: 0,
      },
    ])

  it('mostra a usina da UC e o histórico do vínculo, sem link sem permissão de usinas', async () => {
    const { fetchFn } = apiFalsa({
      'GET /api/v1/auth/sessao': sessao([
        'associados:consultar',
        'ucs:consultar',
        'vinculos-usina:consultar',
      ]),
      'GET /api/v1/associados/7': () => jsonResponse(ficha),
      'GET /api/v1/associados/7/ucs': ucsDoAssociado,
      'GET /api/v1/associados/7/vinculos-usina': () =>
        jsonResponse([
          vinculo(),
          vinculo({
            id: 2,
            usina: { id: 4, codigo: 'USN-0004', nome: 'Usina Antiga' },
            vigente: false,
            inicio: '2026-01-01',
            fim: '2026-10-01',
            motivoEncerramento: 'Troca de usina',
            encerradoPor: autor,
          }),
        ]),
    })
    await montarApp(fetchFn, '/associados/7?aba=ucs')

    expect(
      await screen.findByRole('columnheader', { name: 'Usina (vínculo cadastral)' }),
    ).toBeInTheDocument()
    expect((await screen.findAllByText('Usina Teste')).length).toBeGreaterThan(0)
    expect(screen.queryByRole('link', { name: 'Usina Teste' })).not.toBeInTheDocument()
    expect(screen.getByText('Motivo: Troca de usina')).toBeInTheDocument()
  })

  it('sem permissão de vínculos não consulta nem mostra usinas', async () => {
    const { fetchFn, chamadas } = apiFalsa({
      'GET /api/v1/auth/sessao': sessao(['associados:consultar', 'ucs:consultar']),
      'GET /api/v1/associados/7': () => jsonResponse(ficha),
      'GET /api/v1/associados/7/ucs': ucsDoAssociado,
    })
    await montarApp(fetchFn, '/associados/7?aba=ucs')

    expect(await screen.findByText('0001234')).toBeInTheDocument()
    expect(screen.queryByText(/Usina \(vínculo cadastral\)/)).not.toBeInTheDocument()
    expect(screen.queryByText('Vínculos com usinas')).not.toBeInTheDocument()
    expect(chamadas.some((c) => c.includes('vinculos-usina'))).toBe(false)
  })
})
