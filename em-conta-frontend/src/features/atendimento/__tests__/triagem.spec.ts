import { fireEvent, render, screen, waitFor, within } from '@testing-library/vue'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { apiFalsa, csrf, montarApp, problema, usuarioAtual, type Rotas } from '@/test/api-falsa'
import { jsonResponse } from '@/test/render-with-providers'

import ItemLinhaDoTempo from '../ItemLinhaDoTempo.vue'

const BASE = '/api/v1/atendimento'
const OUTRA = { id: 30, nome: 'Outra Pessoa' }
const CONSULTA = ['atendimento:consultar', 'atendimento:assumir']
const CONFIGURA = [...CONSULTA, 'triagem:configurar']

function setor(id: number, nome: string, ativo = true) {
  return {
    id,
    nome,
    ativo,
    versao: 0,
    criadoEm: '2026-11-10T12:00:00Z',
    criadoPor: OUTRA,
    atualizadoEm: '2026-11-10T12:00:00Z',
    atualizadoPor: OUTRA,
  }
}

const SETORES = [
  setor(1, 'Financeiro'),
  setor(2, 'Comercial'),
  setor(3, 'Antigo', false),
  setor(4, 'Outro inativo', false),
]

const SAUDACAO =
  'Olá! Você está no atendimento do Em Conta. Para direcionar sua conversa, responda com o número de uma das opções:\n\n1 - Financeiro\n2 - Cobrança ou segunda via\n3 - Quero conhecer o Em Conta\n4 - Outro assunto ou falar com um atendente'

function configuracao(sobrescrever: Record<string, unknown> = {}) {
  return {
    ativa: false,
    versao: 7,
    saudacao: SAUDACAO,
    opcoes: [
      {
        opcao: 'FINANCEIRO',
        numero: 1,
        rotulo: 'Financeiro',
        configuravel: true,
        setor: { id: 1, nome: 'Financeiro', ativo: true },
      },
      { opcao: 'COBRANCA', numero: 2, rotulo: 'Cobrança ou segunda via', configuravel: true },
      {
        opcao: 'COMERCIAL',
        numero: 3,
        rotulo: 'Quero conhecer o Em Conta',
        configuravel: true,
        setor: { id: 3, nome: 'Antigo', ativo: false },
      },
      {
        opcao: 'HUMANO',
        numero: 4,
        rotulo: 'Outro assunto ou falar com um atendente',
        configuravel: false,
      },
    ],
    atualizadoEm: '2026-11-15T13:00:00Z',
    atualizadoPor: OUTRA,
    ...sobrescrever,
  }
}

function rotas(permissoes: string[], adicionais: Rotas = {}): Rotas {
  return {
    ...csrf(),
    'GET /api/v1/auth/sessao': () => jsonResponse(usuarioAtual('adm@x.br', permissoes)),
    [`GET ${BASE}/canal`]: () => jsonResponse({ canal: 'WHATSAPP', configurado: true }),
    [`GET ${BASE}/presenca`]: () => jsonResponse({ disponivel: true, modo: 'MANUAL' }),
    [`GET ${BASE}/triagem`]: () => jsonResponse(configuracao()),
    [`GET ${BASE}/setores`]: () => jsonResponse({ itens: SETORES }),
    ...adicionais,
  }
}

afterEach(() => {
  vi.restoreAllMocks()
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
})

describe('Triagem do WhatsApp', () => {
  it('quem configura liga a triagem e aponta duas opções para o mesmo setor', async () => {
    const corpos: unknown[] = []
    const { fetchFn } = apiFalsa(
      rotas(CONFIGURA, {
        [`PUT ${BASE}/triagem`]: (init) => {
          corpos.push(JSON.parse(String(init?.body)))
          return jsonResponse(configuracao({ ativa: true, versao: 8 }))
        },
      }),
    )
    await montarApp(fetchFn, '/atendimento/triagem')
    expect(
      await screen.findByRole('heading', { name: 'Triagem do WhatsApp', level: 1 }),
    ).toBeTruthy()
    expect(await screen.findByText('Desligada')).toBeTruthy()
    expect(screen.getByText(/1 - Financeiro 2 - Cobrança ou segunda via/)).toBeTruthy()

    const cobranca = (await screen.findByLabelText(
      '2 - Cobrança ou segunda via',
    )) as HTMLSelectElement
    await waitFor(() => expect(cobranca.options.length).toBe(3))
    expect([...cobranca.options].map((o) => o.text.trim())).toEqual([
      'Fila sem setor',
      'Financeiro',
      'Comercial',
    ])
    // A opção mantém o setor que já tinha, mesmo desativado depois; outro inativo não é escolha.
    const comercial = screen.getByLabelText('3 - Quero conhecer o Em Conta') as HTMLSelectElement
    expect([...comercial.options].map((o) => o.text.trim())).toContain('Antigo (inativo)')
    expect([...comercial.options].map((o) => o.text.trim())).not.toContain(
      'Outro inativo (inativo)',
    )
    expect(screen.queryByLabelText(/4 - Outro assunto/)).toBeNull()
    expect(screen.getByText('Fila sem setor (atendimento humano)')).toBeTruthy()

    await fireEvent.update(cobranca, '1')
    await fireEvent.update(comercial, '2')
    await fireEvent.click(screen.getByLabelText('Enviar a triagem ao abrir um novo atendimento'))
    await fireEvent.click(screen.getByRole('button', { name: 'Salvar triagem' }))

    await waitFor(() => expect(corpos).toHaveLength(1))
    expect(corpos[0]).toEqual({
      versao: 7,
      ativa: true,
      opcoes: [
        { opcao: 'FINANCEIRO', setorId: 1 },
        { opcao: 'COBRANCA', setorId: 1 },
        { opcao: 'COMERCIAL', setorId: 2 },
      ],
    })
    expect(await screen.findByText('Triagem salva.')).toBeTruthy()
    expect(screen.getByText('Ligada')).toBeTruthy()
  })

  it('mostra o erro do servidor junto da opção e o conflito de versão', async () => {
    let tentativa = 0
    const { fetchFn } = apiFalsa(
      rotas(CONFIGURA, {
        [`PUT ${BASE}/triagem`]: () =>
          ++tentativa === 1
            ? new Response(
                JSON.stringify({
                  status: 400,
                  codigo: 'DADOS_INVALIDOS',
                  detail: 'Dados inválidos.',
                  campos: { 'opcoes.FINANCEIRO': 'Escolha um setor ativo.' },
                }),
                { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
              )
            : problema(409, 'CONFLITO_CONCORRENTE', 'Alterada por outra pessoa.'),
      }),
    )
    await montarApp(fetchFn, '/atendimento/triagem')
    await fireEvent.click(await screen.findByRole('button', { name: 'Salvar triagem' }))
    const erro = await screen.findByText('Escolha um setor ativo.')
    const financeiro = screen.getByLabelText('1 - Financeiro')
    expect(financeiro.getAttribute('aria-invalid')).toBe('true')
    expect(financeiro.getAttribute('aria-describedby')).toBe(erro.id)

    await fireEvent.click(screen.getByRole('button', { name: 'Salvar triagem' }))
    expect(await screen.findByText(/alterada por outra pessoa enquanto você editava/)).toBeTruthy()
  })

  it('quem só consulta vê os destinos sem os controles', async () => {
    const { fetchFn } = apiFalsa(rotas(CONSULTA))
    await montarApp(fetchFn, '/atendimento/triagem')
    expect(await screen.findByText('Antigo (inativo)')).toBeTruthy()
    expect(screen.getAllByText('Fila sem setor')).toHaveLength(1)
    expect(screen.queryByRole('combobox')).toBeNull()
    expect(screen.queryByRole('checkbox')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Salvar triagem' })).toBeNull()
  })

  it('a fila mostra o setor e "Em triagem"; Ver opções leva à configuração só com a permissão', async () => {
    const itens = [
      {
        id: 1,
        codigo: 'ATD-000001',
        situacao: 'AGUARDANDO',
        contatoNumero: '5567999990000',
        contatoNome: 'Ana Triada',
        iniciadoEm: '2026-11-17T12:00:00Z',
        setor: { id: 1, nome: 'Financeiro', ativo: true },
        etiquetas: [],
        emTriagem: false,
      },
      {
        id: 2,
        codigo: 'ATD-000002',
        situacao: 'AGUARDANDO',
        contatoNumero: '5567999990001',
        contatoNome: 'Bruno Esperando',
        iniciadoEm: '2026-11-17T12:05:00Z',
        etiquetas: [],
        emTriagem: true,
      },
    ]
    const fila = {
      [`GET ${BASE}/atendimentos`]: () =>
        jsonResponse({
          itens,
          pagina: 0,
          tamanho: 20,
          totalItens: 2,
          totalPaginas: 1,
          contagens: { aguardando: 2, emAndamento: 0, meus: 0 },
        }),
    }
    const { fetchFn } = apiFalsa(rotas(CONFIGURA, fila))
    await montarApp(fetchFn, '/atendimento')
    await fireEvent.click(await screen.findByRole('button', { name: /Aguardando/ }))
    const ana = await screen.findByRole('button', { name: /Ana Triada/ })
    expect(within(ana).getByText('Financeiro')).toBeTruthy()
    expect(
      within(screen.getByRole('button', { name: /Bruno Esperando/ })).getByText('Em triagem'),
    ).toBeTruthy()

    await fireEvent.click(screen.getByRole('button', { name: 'Ver opções' }))
    expect(screen.getByRole('link', { name: 'Triagem do WhatsApp' })).toBeTruthy()
  })

  it('na linha do tempo, a mensagem e o evento da triagem aparecem como automáticos, sem autor', () => {
    const base = { atendimento: 1 }
    render(ItemLinhaDoTempo, {
      props: {
        ...base,
        item: {
          tipo: 'MENSAGEM',
          id: 5,
          cursor: '1.1.5',
          ocorridoEm: '2026-11-17T12:00:00Z',
          atendimentoId: 1,
          direcao: 'SAIDA',
          tipoMensagem: 'TEXTO',
          texto: 'Olá! Você está no atendimento do Em Conta.',
          situacao: 'ENTREGUE',
          origem: 'TRIAGEM',
        },
      },
    })
    expect(screen.getByText(/Triagem automática ·/)).toBeTruthy()
    render(ItemLinhaDoTempo, {
      props: {
        ...base,
        item: {
          tipo: 'EVENTO',
          id: 6,
          cursor: '1.0.6',
          ocorridoEm: '2026-11-17T12:01:00Z',
          atendimentoId: 1,
          evento: 'TRIAGEM_CLASSIFICADA',
          texto: 'Opção 1 (Financeiro) · Setor: Financeiro',
        },
      },
    })
    expect(
      screen.getByText(
        /Triagem automática classificou o atendimento — Opção 1 \(Financeiro\) · Setor: Financeiro/,
      ),
    ).toBeTruthy()
  })

  it('sem triagem:configurar, Ver opções não mostra a triagem', async () => {
    const { fetchFn } = apiFalsa(
      rotas(CONSULTA, {
        [`GET ${BASE}/atendimentos`]: () =>
          jsonResponse({
            itens: [],
            pagina: 0,
            tamanho: 20,
            totalItens: 0,
            totalPaginas: 0,
            contagens: { aguardando: 0, emAndamento: 0, meus: 0 },
          }),
      }),
    )
    await montarApp(fetchFn, '/atendimento')
    await fireEvent.click(await screen.findByRole('button', { name: 'Ver opções' }))
    expect(screen.getByRole('link', { name: 'Histórico geral' })).toBeTruthy()
    expect(screen.queryByRole('link', { name: 'Triagem do WhatsApp' })).toBeNull()
  })
})
