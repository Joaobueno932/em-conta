import { fireEvent, screen, waitFor, within } from '@testing-library/vue'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { apiFalsa, csrf, montarApp, problema, usuarioAtual, type Rotas } from '@/test/api-falsa'
import { jsonResponse } from '@/test/render-with-providers'

const BASE = '/api/v1/atendimento'
// usuarioAtual usa o tamanho do e-mail como id: 'adm@x.br' → 8.
const EU = { id: 8, nome: 'Pessoa adm@x.br' }
const OUTRA = { id: 30, nome: 'Outra Pessoa' }
const ATENDE = [
  'atendimento:consultar',
  'atendimento:assumir',
  'atendimento:responder',
  'atendimento:classificar',
]
const GERENCIA = [
  'atendimento:consultar',
  'setores:cadastrar',
  'setores:editar',
  'setores:alterar-situacao',
  'etiquetas:cadastrar',
  'etiquetas:editar',
  'etiquetas:alterar-situacao',
]

function cadastro(id: number, nome: string, ativo = true, versao = 0) {
  return {
    id,
    nome,
    ativo,
    versao,
    criadoEm: '2026-11-10T12:00:00Z',
    criadoPor: OUTRA,
    atualizadoEm: '2026-11-10T12:00:00Z',
    atualizadoPor: OUTRA,
  }
}

const SETORES = [cadastro(1, 'Financeiro'), cadastro(2, 'Comercial'), cadastro(3, 'Antigo', false)]
const ETIQUETAS = [
  cadastro(10, 'Segunda via'),
  cadastro(11, 'Contrato'),
  cadastro(12, 'Descontinuada', false),
  cadastro(13, 'Outra inativa', false),
]

function resumo(sobrescrever: Record<string, unknown> = {}) {
  return {
    id: 1,
    codigo: 'ATD-000001',
    etiquetas: [],
    situacao: 'EM_ATENDIMENTO',
    contatoNumero: '5567999990000',
    contatoNome: 'Contato Teste',
    iniciadoEm: '2026-10-29T12:00:00Z',
    responsavel: EU,
    ...sobrescrever,
  }
}

function rotas(permissoes: string[], det: Record<string, unknown>, adicionais: Rotas = {}): Rotas {
  return {
    ...csrf(),
    'GET /api/v1/auth/sessao': () => jsonResponse(usuarioAtual('adm@x.br', permissoes)),
    [`GET ${BASE}/canal`]: () => jsonResponse({ canal: 'WHATSAPP', configurado: true }),
    [`GET ${BASE}/presenca`]: () => jsonResponse({ disponivel: true, modo: 'MANUAL' }),
    [`GET ${BASE}/atendimentos`]: () =>
      jsonResponse({
        itens: [det],
        pagina: 0,
        tamanho: 20,
        totalItens: 1,
        totalPaginas: 1,
        contagens: { aguardando: 0, emAndamento: 1, meus: 1 },
      }),
    [`GET ${BASE}/atendimentos/1`]: () =>
      jsonResponse({
        resumo: det,
        versao: 5,
        versaoConversa: 4,
        janelaAbertaAte: new Date(Date.now() + 3_600_000).toISOString(),
        canalConfigurado: true,
      }),
    [`GET ${BASE}/atendimentos/1/linha-do-tempo`]: () =>
      jsonResponse({
        itens: [
          {
            tipo: 'EVENTO',
            id: 4,
            cursor: '1200.0.4',
            ocorridoEm: '2026-10-29T12:20:00Z',
            atendimentoId: 1,
            evento: 'CLASSIFICADO',
            autor: EU,
            texto: 'Setor: Financeiro · Etiquetas: Segunda via',
          },
        ],
      }),
    [`GET ${BASE}/atendimentos/1/associados-sugeridos`]: () => jsonResponse([]),
    [`GET ${BASE}/atendimentos/1/midia`]: () => jsonResponse({ itens: [] }),
    [`GET ${BASE}/setores`]: () => jsonResponse({ itens: SETORES }),
    [`GET ${BASE}/etiquetas`]: () => jsonResponse({ itens: ETIQUETAS }),
    ...adicionais,
  }
}

afterEach(() => {
  vi.restoreAllMocks()
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
})

describe('Setores e etiquetas no atendimento', () => {
  it('o responsável classifica o ciclo no painel com itens ativos e mantém o inativo que já tinha', async () => {
    const corpos: unknown[] = []
    const atual = resumo({
      setor: { id: 1, nome: 'Financeiro', ativo: true },
      etiquetas: [{ id: 12, nome: 'Descontinuada', ativo: false }],
    })
    const { fetchFn } = apiFalsa(
      rotas(ATENDE, atual, {
        [`PUT ${BASE}/atendimentos/1/classificacao`]: (init) => {
          corpos.push(JSON.parse(String(init?.body)))
          return new Response(null, { status: 204 })
        },
      }),
    )
    await montarApp(fetchFn, '/atendimento?id=1')
    const painel = await screen.findByRole('complementary', { name: 'Dados do associado' })
    expect(await within(painel).findByText('Financeiro')).toBeTruthy()
    expect(within(painel).getByText('Descontinuada (inativo)')).toBeTruthy()
    // O evento da linha do tempo traz os nomes gravados na classificação.
    expect(await screen.findByText(/classificou o atendimento/)).toBeTruthy()
    expect(screen.getByText(/Setor: Financeiro · Etiquetas: Segunda via/)).toBeTruthy()

    await fireEvent.click(await within(painel).findByRole('button', { name: 'Classificar' }))
    const setor = (await within(painel).findByLabelText('Setor')) as HTMLSelectElement
    await waitFor(() => expect(setor.options.length).toBe(3))
    // Ativos e o inativo que o ciclo já tem; o outro inativo não é escolha.
    expect([...setor.options].map((o) => o.text.trim())).toEqual([
      'Nenhum',
      'Financeiro',
      'Comercial',
    ])
    expect(within(painel).getByLabelText('Descontinuada (inativo)')).toBeTruthy()
    expect(within(painel).queryByLabelText('Outra inativa (inativo)')).toBeNull()
    expect(
      (within(painel).getByLabelText('Descontinuada (inativo)') as HTMLInputElement).checked,
    ).toBe(true)

    await fireEvent.update(setor, '2')
    await fireEvent.click(within(painel).getByLabelText('Contrato'))
    await fireEvent.click(within(painel).getByRole('button', { name: 'Salvar classificação' }))
    await waitFor(() => expect(corpos).toEqual([{ versao: 5, setorId: 2, etiquetaIds: [12, 11] }]))
  })

  it('sem setor nem etiquetas o painel mostra "—" e "Sem etiqueta"; só o responsável vê Classificar', async () => {
    const { fetchFn } = apiFalsa(rotas(ATENDE, resumo({ responsavel: OUTRA })))
    await montarApp(fetchFn, '/atendimento?id=1')
    const painel = await screen.findByRole('complementary', { name: 'Dados do associado' })
    expect(await within(painel).findByText('Sem etiqueta')).toBeTruthy()
    expect(within(painel).queryByRole('button', { name: 'Classificar' })).toBeNull()
  })

  it('sem atendimento:classificar o responsável também não vê Classificar', async () => {
    const { fetchFn } = apiFalsa(
      rotas(['atendimento:consultar', 'atendimento:responder'], resumo()),
    )
    await montarApp(fetchFn, '/atendimento?id=1')
    const painel = await screen.findByRole('complementary', { name: 'Dados do associado' })
    expect(await within(painel).findByText('Sem etiqueta')).toBeTruthy()
    expect(within(painel).queryByRole('button', { name: 'Classificar' })).toBeNull()
  })

  it('conflito de versão ao classificar avisa e não fecha o formulário', async () => {
    const { fetchFn } = apiFalsa(
      rotas(ATENDE, resumo(), {
        [`PUT ${BASE}/atendimentos/1/classificacao`]: () =>
          problema(409, 'CONFLITO_CONCORRENTE', 'Alterado por outra pessoa.'),
      }),
    )
    await montarApp(fetchFn, '/atendimento?id=1')
    const painel = await screen.findByRole('complementary', { name: 'Dados do associado' })
    await fireEvent.click(await within(painel).findByRole('button', { name: 'Classificar' }))
    await fireEvent.click(
      await within(painel).findByRole('button', { name: 'Salvar classificação' }),
    )
    expect(await within(painel).findByText(/alterado por outra pessoa/)).toBeTruthy()
    expect(within(painel).getByRole('button', { name: 'Salvar classificação' })).toBeTruthy()
  })
})

describe('Cadastros de setores e etiquetas', () => {
  it('"Ver opções" leva aos cadastros só para quem pode gerenciá-los', async () => {
    const leitor = apiFalsa(rotas(['atendimento:consultar'], resumo()))
    await montarApp(leitor.fetchFn, '/atendimento')
    await fireEvent.click(await screen.findByRole('button', { name: 'Ver opções' }))
    expect(screen.getByRole('link', { name: 'Histórico geral' })).toBeTruthy()
    expect(screen.queryByRole('link', { name: 'Setores e etiquetas' })).toBeNull()
  })

  it('gestor cria, renomeia e desativa; nome repetido mostra o erro no campo', async () => {
    const corpos: { metodo: string; url: string; corpo: unknown }[] = []
    const registrar = (metodo: string) => (init?: RequestInit, url?: string) => {
      corpos.push({ metodo, url: url ?? '', corpo: JSON.parse(String(init?.body)) })
      return jsonResponse(cadastro(1, 'Financeiro', false, 1))
    }
    const { fetchFn } = apiFalsa(
      rotas(GERENCIA, resumo(), {
        [`POST ${BASE}/setores`]: () =>
          new Response(
            JSON.stringify({
              status: 409,
              codigo: 'NOME_EM_USO',
              detail: 'Já existe um setor com este nome (ativo ou inativo).',
              campos: { nome: 'Já existe um setor com este nome (ativo ou inativo).' },
            }),
            { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
          ),
        [`PUT ${BASE}/setores/1`]: registrar('PUT'),
        [`POST ${BASE}/setores/1/desativacao`]: registrar('POST'),
      }),
    )
    const { router } = await montarApp(fetchFn, '/atendimento')
    await fireEvent.click(await screen.findByRole('button', { name: 'Ver opções' }))
    await fireEvent.click(screen.getByRole('link', { name: 'Setores e etiquetas' }))
    await waitFor(() =>
      expect(router.currentRoute.value.name).toBe('atendimento-setores-etiquetas'),
    )

    const setores = await screen.findByRole('region', { name: 'Setores' })
    expect(await within(setores).findByText('Financeiro')).toBeTruthy()
    await fireEvent.click(within(setores).getByRole('button', { name: 'Novo setor' }))
    await fireEvent.update(within(setores).getByLabelText(/Nome/), 'financeiro')
    await fireEvent.click(within(setores).getByRole('button', { name: 'Salvar' }))
    expect(await within(setores).findByText(/Já existe um setor com este nome/)).toBeTruthy()
    expect(within(setores).getByLabelText(/Nome/).getAttribute('aria-invalid')).toBe('true')
    await fireEvent.click(within(setores).getByRole('button', { name: 'Cancelar' }))

    await fireEvent.click(within(setores).getByRole('button', { name: 'Renomear Financeiro' }))
    await fireEvent.update(within(setores).getByLabelText(/Nome/), 'Cobrança')
    await fireEvent.click(within(setores).getByRole('button', { name: 'Salvar' }))
    await fireEvent.click(
      await within(setores).findByRole('button', { name: 'Desativar Financeiro' }),
    )
    await waitFor(() =>
      expect(corpos).toEqual([
        { metodo: 'PUT', url: `${BASE}/setores/1`, corpo: { nome: 'Cobrança', versao: 0 } },
        { metodo: 'POST', url: `${BASE}/setores/1/desativacao`, corpo: { versao: 0 } },
      ]),
    )
  })

  it('quem só consulta vê os cadastros sem as ações', async () => {
    const { fetchFn } = apiFalsa(rotas(['atendimento:consultar'], resumo()))
    await montarApp(fetchFn, '/atendimento/setores-etiquetas')
    const etiquetas = await screen.findByRole('region', { name: 'Etiquetas' })
    expect(await within(etiquetas).findByText('Segunda via')).toBeTruthy()
    expect(within(etiquetas).queryByRole('button', { name: 'Nova etiqueta' })).toBeNull()
    expect(within(etiquetas).queryByRole('button', { name: /Renomear|Desativar/ })).toBeNull()
  })
})

describe('Filtros de setor e etiqueta no Histórico geral', () => {
  it('combina setor, etiqueta, busca e período, guarda na URL e mostra as etiquetas de cada ciclo', async () => {
    const encerrado = resumo({
      situacao: 'ENCERRADO',
      encerradoEm: '2026-10-29T13:00:00Z',
      encerradoPor: EU,
      setor: { id: 3, nome: 'Antigo', ativo: false },
      etiquetas: [
        { id: 10, nome: 'Segunda via', ativo: true },
        { id: 11, nome: 'Contrato', ativo: true },
      ],
    })
    const { fetchFn, chamadas } = apiFalsa(
      rotas(['atendimento:consultar'], encerrado, {
        [`GET ${BASE}/historico`]: () =>
          jsonResponse({
            itens: [encerrado],
            pagina: 0,
            tamanho: 20,
            totalItens: 1,
            totalPaginas: 1,
          }),
      }),
    )
    const { router } = await montarApp(fetchFn, '/atendimento/historico')
    const linha = await screen.findByRole('row', { name: /Contato Teste/ })
    expect(within(linha).getByText('Antigo (inativo)')).toBeTruthy()
    expect(within(linha).getByText('Segunda via')).toBeTruthy()
    expect(within(linha).getByText('Contrato')).toBeTruthy()

    const setor = screen.getByLabelText('Setor') as HTMLSelectElement
    await waitFor(() => expect(setor.options.length).toBe(5))
    expect([...setor.options].map((o) => o.text.trim())).toEqual([
      'Todos',
      'Sem setor',
      'Financeiro',
      'Comercial',
      'Antigo (inativo)',
    ])
    // Inativos à parte, depois dos ativos, nos dois filtros.
    const grupo = (s: HTMLSelectElement, texto: string) =>
      [...s.options].find((o) => o.text.trim() === texto)?.parentElement
    expect(grupo(setor, 'Antigo (inativo)')?.getAttribute('label')).toBe('Inativos')
    expect(grupo(setor, 'Financeiro')).toBe(setor)
    const filtroEtiqueta = screen.getByLabelText('Etiquetas') as HTMLSelectElement
    expect(
      [...filtroEtiqueta.querySelectorAll('optgroup[label="Inativos"] option')].map((o) =>
        o.textContent?.trim(),
      ),
    ).toEqual(['Descontinuada (inativo)', 'Outra inativa (inativo)'])
    await fireEvent.update(setor, '3')
    await fireEvent.update(screen.getByLabelText('Etiquetas'), 'SEM')
    await fireEvent.update(screen.getByLabelText('Buscar contato'), 'Contato')
    await fireEvent.update(screen.getByLabelText('De'), '2026-10-01')
    await fireEvent.click(screen.getByRole('button', { name: 'Filtrar' }))
    await waitFor(() =>
      expect(
        chamadas.some(
          (c) =>
            c.includes('/historico?') &&
            c.includes('setor=3') &&
            c.includes('etiqueta=SEM') &&
            c.includes('busca=Contato') &&
            c.includes('de=2026-10-01'),
        ),
      ).toBe(true),
    )
    await waitFor(() =>
      expect(router.currentRoute.value.query).toEqual({
        busca: 'Contato',
        de: '2026-10-01',
        setor: '3',
        etiqueta: 'SEM',
      }),
    )

    await fireEvent.click(screen.getByRole('button', { name: 'Limpar' }))
    await waitFor(() => expect(router.currentRoute.value.query).toEqual({}))
    expect(setor.value).toBe('')
  })

  it('só com o filtro de setor, a lista vazia diz que nada corresponde; filtro da URL é aplicado', async () => {
    const { fetchFn, chamadas } = apiFalsa(
      rotas(['atendimento:consultar'], resumo(), {
        [`GET ${BASE}/historico`]: () =>
          jsonResponse({ itens: [], pagina: 0, tamanho: 20, totalItens: 0, totalPaginas: 0 }),
      }),
    )
    await montarApp(fetchFn, '/atendimento/historico?setor=SEM&etiqueta=x')
    expect(await screen.findByText(/Nenhum resultado correspondente/)).toBeTruthy()
    // Valor inválido na URL é descartado; o válido vai ao servidor.
    expect(chamadas.some((c) => c.includes('setor=SEM') && !c.includes('etiqueta='))).toBe(true)
  })
})
