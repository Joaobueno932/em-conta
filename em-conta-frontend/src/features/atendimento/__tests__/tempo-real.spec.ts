import { fireEvent, screen, waitFor } from '@testing-library/vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { apiFalsa, csrf, montarApp, problema, usuarioAtual, type Rotas } from '@/test/api-falsa'
import { jsonResponse } from '@/test/render-with-providers'

const TODAS = [
  'atendimento:consultar',
  'atendimento:assumir',
  'atendimento:responder',
  'atendimento:transferir',
  'atendimento:encerrar',
]
const EU = { id: 8, nome: 'Pessoa adm@x.br' }
const BASE = '/api/v1/atendimento'

/** EventSource falso: guarda as conexões abertas e deixa o teste emitir os eventos do servidor. */
class FonteFalsa {
  static abertas: FonteFalsa[] = []
  static readonly CONNECTING = 0
  static readonly OPEN = 1
  static readonly CLOSED = 2
  readyState = FonteFalsa.CONNECTING
  private ouvintes = new Map<string, (() => void)[]>()
  constructor(readonly url: string) {
    FonteFalsa.abertas.push(this)
  }
  addEventListener(nome: string, ouvinte: () => void) {
    this.ouvintes.set(nome, [...(this.ouvintes.get(nome) ?? []), ouvinte])
  }
  close() {
    this.readyState = FonteFalsa.CLOSED
  }
  emitir(nome: string) {
    if (nome !== 'error') this.readyState = FonteFalsa.OPEN
    this.ouvintes.get(nome)?.forEach((o) => o())
  }
}

function detalhe(id: number) {
  return {
    resumo: {
      id,
      codigo: `ATD-00000${id}`,
      situacao: 'EM_ATENDIMENTO',
      contatoNumero: '5567999990000',
      contatoNome: `Contato ${id}`,
      iniciadoEm: '2026-10-29T12:00:00Z',
      responsavel: EU,
    },
    versao: 2,
    versaoConversa: 4,
    janelaAbertaAte: new Date(Date.now() + 3_600_000).toISOString(),
    canalConfigurado: true,
  }
}

function saida(id: number, texto: string, situacao: string, extra: Record<string, unknown> = {}) {
  return {
    tipo: 'MENSAGEM',
    id,
    cursor: `${id}000.1.${id}`,
    ocorridoEm: '2026-10-29T12:00:00Z',
    atendimentoId: 1,
    direcao: 'SAIDA',
    tipoMensagem: 'TEXTO',
    texto,
    situacao,
    autor: EU,
    atualizadoEm: `2026-10-29T12:0${id % 10}:00Z`,
    ...extra,
  }
}

function rotas(linha: () => unknown[], adicionais: Rotas = {}): Rotas {
  return {
    ...csrf(),
    'GET /api/v1/auth/sessao': () => jsonResponse(usuarioAtual('adm@x.br', TODAS)),
    [`GET ${BASE}/canal`]: () => jsonResponse({ canal: 'WHATSAPP', configurado: true }),
    [`GET ${BASE}/presenca`]: () => jsonResponse({ disponivel: true }),
    [`GET ${BASE}/atendimentos`]: () =>
      jsonResponse({
        itens: [],
        pagina: 0,
        tamanho: 20,
        totalItens: 0,
        totalPaginas: 0,
        contagens: { aguardando: 0, emAndamento: 1, meus: 1 },
      }),
    [`GET ${BASE}/atendimentos/1`]: () => jsonResponse(detalhe(1)),
    [`GET ${BASE}/atendimentos/2`]: () => jsonResponse(detalhe(2)),
    [`GET ${BASE}/atendimentos/1/linha-do-tempo`]: () => jsonResponse({ itens: linha() }),
    [`GET ${BASE}/atendimentos/2/linha-do-tempo`]: () => jsonResponse({ itens: [] }),
    [`GET ${BASE}/atendimentos/1/associados-sugeridos`]: () => jsonResponse([]),
    [`GET ${BASE}/atendimentos/2/associados-sugeridos`]: () => jsonResponse([]),
    [`GET ${BASE}/atendimentos/1/midia`]: () => jsonResponse({ itens: [] }),
    [`GET ${BASE}/atendimentos/2/midia`]: () => jsonResponse({ itens: [] }),
    ...adicionais,
  }
}

const leiturasDaLinha = (chamadas: string[], id = 1) =>
  chamadas.filter((c) => c.startsWith(`GET ${BASE}/atendimentos/${id}/linha-do-tempo?`)).length

beforeEach(() => {
  FonteFalsa.abertas = []
  vi.stubGlobal('EventSource', FonteFalsa)
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
})

describe('Atualização em tempo real da conversa', () => {
  it('cada aviso de mudança relê a conversa na hora e o ícone avança sem esperar a consulta', async () => {
    // Cada situação tem a sua hora de atualização; a "enviada" atrasada traz a hora antiga.
    const HORA: Record<string, string> = {
      ACEITA: '2026-10-29T12:00:00Z',
      ENVIADA: '2026-10-29T12:01:00Z',
      ENTREGUE: '2026-10-29T12:02:00Z',
      LIDA: '2026-10-29T12:03:00Z',
    }
    let situacao = 'ACEITA'
    const { fetchFn, chamadas } = apiFalsa(
      rotas(() => [saida(10, 'Resposta', situacao, { atualizadoEm: HORA[situacao] })]),
    )
    await montarApp(fetchFn, '/atendimento?id=1')
    expect(await screen.findByText('Resposta')).toBeTruthy()
    const fonte = await waitFor(() => {
      expect(FonteFalsa.abertas).toHaveLength(1)
      return FonteFalsa.abertas[0]!
    })
    expect(fonte.url).toBe(`${BASE}/atendimentos/1/eventos`)
    expect(screen.getByRole('img', { name: /Aceita pela Meta/ })).toBeTruthy()

    fonte.emitir('pronto')
    situacao = 'ENVIADA'
    fonte.emitir('mudanca')
    await waitFor(() => expect(screen.getByRole('img', { name: 'Enviada' })).toBeTruthy())
    situacao = 'ENTREGUE'
    fonte.emitir('mudanca')
    await waitFor(() => expect(screen.getByRole('img', { name: 'Entregue' })).toBeTruthy())
    situacao = 'LIDA'
    fonte.emitir('mudanca')
    await waitFor(() => expect(screen.getByRole('img', { name: 'Lida' })).toBeTruthy())
    // Leitura confirmada antes, "enviada" atrasada depois: o ícone não volta.
    const leituras = leiturasDaLinha(chamadas)
    situacao = 'ENVIADA'
    fonte.emitir('mudanca')
    await waitFor(() => expect(leiturasDaLinha(chamadas)).toBeGreaterThan(leituras))
    await new Promise((r) => setTimeout(r, 20))
    expect(screen.getByRole('img', { name: 'Lida' })).toBeTruthy()
    expect(screen.getAllByText('Resposta')).toHaveLength(1)
  })

  it('a resposta aparece na hora com relógio e o registro do servidor substitui o balão, sem duplicar', async () => {
    let liberar: (r: Response) => void = () => {}
    const servidor: unknown[] = []
    let chave = ''
    const { fetchFn } = apiFalsa(
      rotas(() => servidor, {
        [`POST ${BASE}/atendimentos/1/mensagens`]: (init) => {
          chave = JSON.parse(String(init?.body)).chaveIdempotencia
          return new Promise<Response>((r) => (liberar = r))
        },
      }),
    )
    await montarApp(fetchFn, '/atendimento?id=1')
    const campo = (await screen.findByLabelText('Mensagem')) as HTMLTextAreaElement
    await waitFor(() => expect(campo.disabled).toBe(false))
    await fireEvent.update(campo, 'Bom dia!')
    await fireEvent.click(screen.getByRole('button', { name: 'Enviar' }))

    // Antes de qualquer resposta do servidor: balão com relógio e campo livre.
    expect(await screen.findByText('Bom dia!', { selector: '.texto' })).toBeTruthy()
    expect(screen.getByRole('img', { name: 'Aguardando envio' })).toBeTruthy()
    expect(campo.value).toBe('')

    // O registro chega pela linha do tempo antes da resposta do POST (aviso SSE): a chave concilia.
    await waitFor(() => expect(chave).not.toBe(''))
    servidor.push(saida(99, 'Bom dia!', 'PENDENTE', { chaveIdempotencia: chave }))
    FonteFalsa.abertas[0]!.emitir('mudanca')
    await waitFor(() =>
      expect(screen.getAllByText('Bom dia!', { selector: '.texto' })).toHaveLength(1),
    )
    liberar(jsonResponse({ mensagemId: 99 }, 202))
    await new Promise((r) => setTimeout(r, 20))
    expect(screen.getAllByText('Bom dia!', { selector: '.texto' })).toHaveLength(1)
  })

  it('falha sem registro tira o balão e devolve o texto ao campo', async () => {
    const { fetchFn } = apiFalsa(
      rotas(() => [], {
        [`POST ${BASE}/atendimentos/1/mensagens`]: () =>
          problema(503, 'INDISPONIVEL', 'Serviço indisponível.'),
      }),
    )
    await montarApp(fetchFn, '/atendimento?id=1')
    const campo = (await screen.findByLabelText('Mensagem')) as HTMLTextAreaElement
    await waitFor(() => expect(campo.disabled).toBe(false))
    await fireEvent.update(campo, 'Vai falhar')
    await fireEvent.click(screen.getByRole('button', { name: 'Enviar' }))
    expect(await screen.findByText(/tentar de novo não duplica/)).toBeTruthy()
    expect(screen.queryByText('Vai falhar', { selector: '.texto' })).toBeNull()
    expect(campo.value).toBe('Vai falhar')
  })

  it('trocar de conversa fecha a assinatura anterior e ignora avisos atrasados dela', async () => {
    const { fetchFn, chamadas } = apiFalsa(rotas(() => [saida(10, 'Resposta', 'ACEITA')]))
    const { router } = await montarApp(fetchFn, '/atendimento?id=1')
    await screen.findByText('Resposta')
    const primeira = FonteFalsa.abertas[0]!
    await router.push('/atendimento?id=2')
    await waitFor(() => expect(FonteFalsa.abertas).toHaveLength(2))
    expect(primeira.readyState).toBe(FonteFalsa.CLOSED)
    expect(FonteFalsa.abertas[1]!.url).toBe(`${BASE}/atendimentos/2/eventos`)
    const antes = leiturasDaLinha(chamadas, 1)
    primeira.emitir('mudanca')
    await new Promise((r) => setTimeout(r, 20))
    expect(leiturasDaLinha(chamadas, 1)).toBe(antes)
  })

  it('recusa da conexão tenta de novo com espera e a reconexão relê o que mudou', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      const { fetchFn, chamadas } = apiFalsa(rotas(() => [saida(10, 'Resposta', 'ACEITA')]))
      await montarApp(fetchFn, '/atendimento?id=1')
      await screen.findByText('Resposta')
      const primeira = FonteFalsa.abertas[0]!
      primeira.readyState = FonteFalsa.CLOSED
      primeira.emitir('error')
      expect(FonteFalsa.abertas).toHaveLength(1)
      await vi.advanceTimersByTimeAsync(2100)
      expect(FonteFalsa.abertas).toHaveLength(2)
      const antes = leiturasDaLinha(chamadas)
      FonteFalsa.abertas[1]!.emitir('pronto')
      await waitFor(() => expect(leiturasDaLinha(chamadas)).toBeGreaterThan(antes))
    } finally {
      vi.useRealTimers()
    }
  })
})
