import { fireEvent, screen, waitFor } from '@testing-library/vue'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { presencaMaisNova } from '@/features/atendimento/use-presenca'
import { apiFalsa, csrf, montarApp, usuarioAtual, type Rotas } from '@/test/api-falsa'
import { jsonResponse } from '@/test/render-with-providers'

const TODAS = ['atendimento:consultar', 'atendimento:assumir', 'atendimento:responder']
const EU = { id: 8, nome: 'Pessoa adm@x.br' }
const BASE = '/api/v1/atendimento'

function detalhe(extra: Record<string, unknown> = {}) {
  return {
    resumo: {
      id: 1,
      codigo: 'ATD-000001',
      etiquetas: [],
      situacao: 'EM_ATENDIMENTO',
      contatoNumero: '5567999990000',
      contatoNome: 'Contato Teste',
      iniciadoEm: '2026-10-29T12:00:00Z',
      responsavel: EU,
    },
    versao: 2,
    versaoConversa: 4,
    janelaAbertaAte: new Date(Date.now() + 3_600_000).toISOString(),
    canalConfigurado: true,
    ...extra,
  }
}

function rotas(itens: unknown[], presenca: Record<string, unknown>, adicionais: Rotas = {}): Rotas {
  return {
    ...csrf(),
    'GET /api/v1/auth/sessao': () => jsonResponse(usuarioAtual('adm@x.br', TODAS)),
    [`GET ${BASE}/canal`]: () => jsonResponse({ canal: 'WHATSAPP', configurado: true }),
    [`GET ${BASE}/presenca`]: () => jsonResponse(presenca),
    [`GET ${BASE}/atendimentos`]: () =>
      jsonResponse({
        itens: [],
        pagina: 0,
        tamanho: 20,
        totalItens: 0,
        totalPaginas: 0,
        contagens: { aguardando: 0, emAndamento: 1, meus: 1 },
      }),
    [`GET ${BASE}/atendimentos/1`]: () => jsonResponse(detalhe()),
    [`GET ${BASE}/atendimentos/1/linha-do-tempo`]: () => jsonResponse({ itens }),
    [`GET ${BASE}/atendimentos/1/associados-sugeridos`]: () => jsonResponse([]),
    [`GET ${BASE}/atendimentos/1/midia`]: () => jsonResponse({ itens: [] }),
    ...adicionais,
  }
}

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
})

describe('Presença do atendente', () => {
  it('no automático, começar a responder pede a atividade e a tela passa a Disponível', async () => {
    const pedidos: unknown[] = []
    const { fetchFn } = apiFalsa(
      rotas(
        [],
        { disponivel: false, modo: 'AUTOMATICO' },
        {
          [`POST ${BASE}/presenca/atividade`]: (init) => {
            pedidos.push(JSON.parse(String(init?.body)))
            return jsonResponse({
              disponivel: true,
              modo: 'AUTOMATICO',
              atualizadoEm: '2026-11-11T12:00:00Z',
            })
          },
        },
      ),
    )
    await montarApp(fetchFn, '/atendimento?id=1')
    const presenca = (await screen.findByLabelText('Sua presença')) as HTMLSelectElement
    expect(presenca.value).toBe('AUTOMATICO')
    expect(screen.getByRole('option', { name: 'Automático · Ausente' })).toBeTruthy()
    const campo = (await screen.findByLabelText('Mensagem')) as HTMLTextAreaElement
    await waitFor(() => expect(campo.disabled).toBe(false))
    await fireEvent.update(campo, 'B')
    await fireEvent.update(campo, 'Bo')
    await waitFor(() => expect(pedidos).toEqual([{ atendimentoId: 1 }]))
    expect(await screen.findByRole('option', { name: 'Automático · Disponível' })).toBeTruthy()
  })

  it('escolha manual vale: Ausente é enviado como manual e digitar não pede atividade', async () => {
    const escolhas: unknown[] = []
    let atividades = 0
    const { fetchFn } = apiFalsa(
      rotas(
        [],
        { disponivel: true, modo: 'AUTOMATICO' },
        {
          [`PUT ${BASE}/presenca`]: (init) => {
            escolhas.push(JSON.parse(String(init?.body)))
            return jsonResponse({
              disponivel: false,
              modo: 'MANUAL',
              atualizadoEm: '2026-11-11T12:00:00Z',
            })
          },
          [`POST ${BASE}/presenca/atividade`]: () => {
            atividades++
            return jsonResponse({ disponivel: false, modo: 'MANUAL' })
          },
        },
      ),
    )
    await montarApp(fetchFn, '/atendimento?id=1')
    const presenca = (await screen.findByLabelText('Sua presença')) as HTMLSelectElement
    await fireEvent.update(presenca, 'AUSENTE')
    await waitFor(() => expect(escolhas).toEqual([{ modo: 'MANUAL', disponivel: false }]))
    await waitFor(() => expect(presenca.value).toBe('AUSENTE'))
    const campo = (await screen.findByLabelText('Mensagem')) as HTMLTextAreaElement
    await fireEvent.update(campo, 'Olá')
    await new Promise((r) => setTimeout(r, 30))
    expect(atividades).toBe(0)
    expect(presenca.value).toBe('AUSENTE')
  })

  it('resposta atrasada não volta a presença e outra aba é avisada da mudança', async () => {
    const manual = {
      disponivel: false,
      modo: 'MANUAL' as const,
      atualizadoEm: '2026-11-11T12:00:05Z',
    }
    const atrasada = {
      disponivel: true,
      modo: 'AUTOMATICO' as const,
      atualizadoEm: '2026-11-11T12:00:01Z',
    }
    expect(presencaMaisNova(manual, atrasada)).toBe(manual)
    expect(presencaMaisNova(atrasada, manual)).toBe(manual)

    // Outra aba do mesmo navegador ouve o mesmo canal.
    const mensagens: unknown[] = []
    const outraAba = new BroadcastChannel('emconta-presenca')
    outraAba.onmessage = (e) => mensagens.push(e.data)
    const { fetchFn } = apiFalsa(
      rotas(
        [],
        { disponivel: true, modo: 'MANUAL' },
        {
          [`PUT ${BASE}/presenca`]: () => jsonResponse({ disponivel: true, modo: 'AUTOMATICO' }),
        },
      ),
    )
    await montarApp(fetchFn, '/atendimento?id=1')
    const presenca = (await screen.findByLabelText('Sua presença')) as HTMLSelectElement
    await fireEvent.update(presenca, 'AUTOMATICO')
    await waitFor(() => expect(mensagens).toContain('mudou'))
    outraAba.close()
  })
})

describe('Avisos e notas internas', () => {
  it('aviso automático aparece identificado e a nota interna é salva pela aba, sem ir ao cliente', async () => {
    const notas: { texto: string; chaveIdempotencia: string }[] = []
    let respostas = 0
    const itens = [
      {
        tipo: 'MENSAGEM',
        id: 10,
        cursor: '10000.1.10',
        ocorridoEm: '2026-10-29T12:00:00Z',
        atendimentoId: 1,
        direcao: 'SAIDA',
        tipoMensagem: 'TEXTO',
        texto: 'Olá! Seu atendimento agora está com Pessoa adm@x.br.',
        situacao: 'ENTREGUE',
        origem: 'AVISO_RESPONSAVEL',
        atualizadoEm: '2026-10-29T12:00:00Z',
      },
      {
        tipo: 'NOTA',
        id: 3,
        cursor: '11000.2.3',
        ocorridoEm: '2026-10-29T12:01:00Z',
        atendimentoId: 1,
        texto: 'Cliente pediu retorno amanhã.',
        autor: EU,
      },
    ]
    const { fetchFn } = apiFalsa(
      rotas(
        itens,
        { disponivel: true, modo: 'MANUAL' },
        {
          [`POST ${BASE}/atendimentos/1/notas`]: (init) => {
            notas.push(JSON.parse(String(init?.body)))
            return jsonResponse({ notaId: 4 }, 201)
          },
          [`POST ${BASE}/atendimentos/1/mensagens`]: () => {
            respostas++
            return jsonResponse({ mensagemId: 99 }, 202)
          },
        },
      ),
    )
    await montarApp(fetchFn, '/atendimento?id=1')

    expect(await screen.findByText(/Aviso automático/)).toBeTruthy()
    const nota = screen.getByRole('note')
    expect(nota.textContent).toContain('Nota interna · só a equipe vê')
    expect(nota.textContent).toContain('Cliente pediu retorno amanhã.')
    expect(nota.textContent).toContain('Pessoa adm@x.br')

    const abaNota = screen.getByRole('tab', { name: 'Nota interna' })
    expect(screen.getByRole('tab', { name: 'Mensagem' }).getAttribute('aria-selected')).toBe('true')
    await fireEvent.click(abaNota)
    expect(abaNota.getAttribute('aria-selected')).toBe('true')
    const campoNota = screen.getByLabelText('Nota interna') as HTMLTextAreaElement
    await fireEvent.update(campoNota, '  Conferir a fatura de setembro  ')
    await fireEvent.click(screen.getByRole('button', { name: 'Salvar nota' }))
    await waitFor(() => expect(notas).toHaveLength(1))
    expect(notas[0]!.texto).toBe('Conferir a fatura de setembro')
    await waitFor(() => expect(campoNota.value).toBe(''))
    expect(respostas).toBe(0)

    // O rascunho da mensagem é outro: voltar à aba Mensagem não traz o texto da nota.
    await fireEvent.click(screen.getByRole('tab', { name: 'Mensagem' }))
    expect((screen.getByLabelText('Mensagem') as HTMLTextAreaElement).value).toBe('')
  })

  it('outro atendente não vê as abas; o responsável anota mesmo com a janela de 24 h fechada', async () => {
    const OUTRA = { id: 99, nome: 'Outra Pessoa' }
    const deOutra = detalhe()
    const { fetchFn } = apiFalsa(
      rotas(
        [],
        { disponivel: true, modo: 'MANUAL' },
        {
          [`GET ${BASE}/atendimentos/1`]: () =>
            jsonResponse({ ...deOutra, resumo: { ...deOutra.resumo, responsavel: OUTRA } }),
        },
      ),
    )
    const { unmount } = await montarApp(fetchFn, '/atendimento?id=1')
    expect(await screen.findByText(/só quem é responsável responde/)).toBeTruthy()
    expect(screen.queryByRole('tab', { name: 'Nota interna' })).toBeNull()
    unmount()

    const notas: unknown[] = []
    const { fetchFn: janelaFechada } = apiFalsa(
      rotas(
        [],
        { disponivel: true, modo: 'MANUAL' },
        {
          [`GET ${BASE}/atendimentos/1`]: () =>
            jsonResponse(detalhe({ janelaAbertaAte: '2026-01-01T00:00:00Z' })),
          [`POST ${BASE}/atendimentos/1/notas`]: (init) => {
            notas.push(JSON.parse(String(init?.body)).texto)
            return jsonResponse({ notaId: 5 }, 201)
          },
        },
      ),
    )
    await montarApp(janelaFechada, '/atendimento?id=1')
    expect(await screen.findByText(/Janela de 24 horas encerrada/)).toBeTruthy()
    expect(screen.queryByLabelText('Mensagem')).toBeNull()
    await fireEvent.click(screen.getByRole('tab', { name: 'Nota interna' }))
    await fireEvent.update(screen.getByLabelText('Nota interna'), 'Pedir nova mensagem ao cliente')
    await fireEvent.click(screen.getByRole('button', { name: 'Salvar nota' }))
    await waitFor(() => expect(notas).toEqual(['Pedir nova mensagem ao cliente']))
  })
})
