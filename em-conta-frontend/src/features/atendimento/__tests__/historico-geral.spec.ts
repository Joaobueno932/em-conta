import { fireEvent, screen, waitFor } from '@testing-library/vue'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { apiFalsa, csrf, montarApp, usuarioAtual, type Rotas } from '@/test/api-falsa'
import { jsonResponse } from '@/test/render-with-providers'

const BASE = '/api/v1/atendimento'
const EU = { id: 8, nome: 'Pessoa adm@x.br' }
const OUTRA = { id: 30, nome: 'Outra Pessoa' }

const encerrado = {
  id: 1,
  codigo: 'ATD-000001',
  etiquetas: [],
  situacao: 'ENCERRADO',
  contatoNumero: '5567999990000',
  contatoNome: 'Contato Teste',
  iniciadoEm: '2026-10-29T12:00:00Z',
  encerradoEm: '2026-10-29T13:00:00Z',
  responsavel: OUTRA,
  encerradoPor: OUTRA,
}

const vazia = { itens: [], pagina: 0, tamanho: 20, totalItens: 0, totalPaginas: 0 }

function rotas(permissoes: string[], adicionais: Rotas = {}): Rotas {
  return {
    ...csrf(),
    'GET /api/v1/auth/sessao': () => jsonResponse(usuarioAtual('adm@x.br', permissoes)),
    [`GET ${BASE}/canal`]: () => jsonResponse({ canal: 'WHATSAPP', configurado: true }),
    [`GET ${BASE}/presenca`]: () => jsonResponse({ disponivel: true, modo: 'MANUAL' }),
    [`GET ${BASE}/atendimentos`]: () =>
      jsonResponse({ ...vazia, contagens: { aguardando: 0, emAndamento: 0, meus: 0 } }),
    [`GET ${BASE}/historico`]: () => jsonResponse(vazia),
    [`GET ${BASE}/atendimentos/1`]: () =>
      jsonResponse({
        resumo: encerrado,
        versao: 3,
        versaoConversa: 5,
        canalConfigurado: true,
        primeiraRespostaHumanaEm: '2026-10-29T12:05:00Z',
      }),
    [`GET ${BASE}/atendimentos/1/linha-do-tempo`]: () =>
      jsonResponse({
        itens: [
          {
            tipo: 'MENSAGEM',
            id: 10,
            cursor: '1000.1.10',
            ocorridoEm: '2026-10-29T12:00:00Z',
            atendimentoId: 1,
            direcao: 'ENTRADA',
            tipoMensagem: 'TEXTO',
            texto: 'Preciso da segunda via',
            situacao: 'RECEBIDA',
          },
          {
            tipo: 'NOTA',
            id: 3,
            cursor: '1100.2.3',
            ocorridoEm: '2026-10-29T12:10:00Z',
            atendimentoId: 1,
            texto: 'Cliente pediu retorno amanhã.',
            autor: EU,
          },
          {
            tipo: 'EVENTO',
            id: 4,
            cursor: '1200.0.4',
            ocorridoEm: '2026-10-29T12:20:00Z',
            atendimentoId: 1,
            evento: 'TRANSFERIDO',
            autor: EU,
            usuarioAlvo: OUTRA,
            motivo: 'Financeiro',
          },
        ],
      }),
    [`GET ${BASE}/atendimentos/1/midia`]: () => jsonResponse({ itens: [] }),
    ...adicionais,
  }
}

afterEach(() => {
  vi.restoreAllMocks()
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
})

describe('Histórico geral', () => {
  it('"Ver opções" abre o Histórico geral e o botão de voltar retorna ao Atendimento', async () => {
    const { fetchFn } = apiFalsa(rotas(['atendimento:consultar']))
    const { router } = await montarApp(fetchFn, '/atendimento')
    await fireEvent.click(await screen.findByRole('button', { name: 'Ver opções' }))
    await fireEvent.click(screen.getByRole('link', { name: 'Histórico geral' }))
    expect(await screen.findByRole('heading', { name: 'Histórico geral', level: 1 })).toBeTruthy()
    expect(router.currentRoute.value.name).toBe('atendimento-historico')
    await fireEvent.click(screen.getByRole('link', { name: 'Voltar ao atendimento' }))
    await waitFor(() => expect(router.currentRoute.value.name).toBe('atendimento'))
  })

  it('período invertido avisa junto do campo e não consulta o servidor', async () => {
    const { fetchFn, chamadas } = apiFalsa(rotas(['atendimento:consultar']))
    await montarApp(fetchFn, '/atendimento/historico')
    await screen.findByText('Nenhum atendimento encerrado ainda.')
    await fireEvent.update(screen.getByLabelText('De'), '2026-10-10')
    await fireEvent.update(screen.getByLabelText('Até'), '2026-10-01')
    await fireEvent.click(screen.getByRole('button', { name: 'Filtrar' }))
    expect((await screen.findByRole('alert')).textContent).toContain(
      'A data final deve ser igual ou posterior à inicial.',
    )
    expect(screen.getByLabelText('Até').getAttribute('aria-invalid')).toBe('true')
    expect(chamadas.some((c) => c.includes('de=2026-10-10'))).toBe(false)

    // Corrigido, consulta com o período.
    await fireEvent.update(screen.getByLabelText('Até'), '2026-10-12')
    await fireEvent.click(screen.getByRole('button', { name: 'Filtrar' }))
    await waitFor(() =>
      expect(
        chamadas.some((c) => c.includes('de=2026-10-10') && c.includes('ate=2026-10-12')),
      ).toBe(true),
    )
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('lista vazia diferencia "nada encerrado" de "nada corresponde"; protocolo vai na busca', async () => {
    const { fetchFn, chamadas } = apiFalsa(rotas(['atendimento:consultar']))
    await montarApp(fetchFn, '/atendimento/historico')
    expect(await screen.findByText('Nenhum atendimento encerrado ainda.')).toBeTruthy()
    const busca = screen.getByLabelText('Buscar contato') as HTMLInputElement
    expect(busca.placeholder).toContain('protocolo')
    await fireEvent.update(busca, ' ATD-000999 ')
    await fireEvent.click(screen.getByRole('button', { name: 'Filtrar' }))
    expect(await screen.findByText(/Nenhum resultado correspondente/)).toBeTruthy()
    expect(chamadas.some((c) => c.includes('busca=ATD-000999'))).toBe(true)
  })

  it('lista identifica quem atendeu e quem encerrou e abre o detalhe do protocolo', async () => {
    const { fetchFn } = apiFalsa(
      rotas(['atendimento:consultar'], {
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
    expect(linha.textContent).toContain('ATD-000001')
    expect(linha.textContent?.match(/Outra Pessoa/g)).toHaveLength(2)
    expect(screen.getByText('1 – 1 de 1')).toBeTruthy()
    await fireEvent.click(screen.getByRole('link', { name: 'Contato Teste' }))
    await waitFor(() =>
      expect(router.currentRoute.value.fullPath).toMatch(/^\/atendimento\/historico\/1\?/),
    )
  })

  it('detalhe somente leitura mostra nota e transferência e exporta a conversa do ciclo', async () => {
    const { fetchFn } = apiFalsa(rotas(['atendimento:consultar']))
    await montarApp(fetchFn, '/atendimento/historico/1')
    expect(await screen.findByText('Conversa encerrada · Somente leitura')).toBeTruthy()
    expect(screen.getByRole('note').textContent).toContain('Cliente pediu retorno amanhã.')
    expect(screen.getByText(/transferiu o atendimento/).textContent).toContain('Outra Pessoa')
    const exportar = screen.getByRole('link', { name: 'Exportar conversa' })
    expect(exportar.getAttribute('href')).toBe(`${BASE}/atendimentos/1/exportacao`)
    expect(exportar.getAttribute('download')).toBe('conversa-ATD-000001.txt')
    // Somente leitura: nada de compositor, abas de nota ou ações.
    expect(screen.queryByLabelText('Mensagem')).toBeNull()
    expect(screen.queryByRole('tab', { name: 'Nota interna' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Transferir' })).toBeNull()
  })

  it('ciclo ainda aberto não oferece exportação (o servidor responde 404)', async () => {
    const { fetchFn } = apiFalsa(
      rotas(['atendimento:consultar'], {
        [`GET ${BASE}/atendimentos/1`]: () =>
          jsonResponse({
            resumo: {
              ...encerrado,
              situacao: 'EM_ATENDIMENTO',
              encerradoEm: null,
              encerradoPor: null,
            },
            versao: 3,
            versaoConversa: 5,
            canalConfigurado: true,
          }),
      }),
    )
    await montarApp(fetchFn, '/atendimento/historico/1')
    expect(await screen.findByText('Preciso da segunda via')).toBeTruthy()
    expect(screen.queryByRole('link', { name: 'Exportar conversa' })).toBeNull()
  })

  it('sem atendimento:consultar, o link direto do detalhe leva ao acesso negado sem consultar', async () => {
    const { fetchFn, chamadas } = apiFalsa(rotas(['associados:consultar']))
    const { router } = await montarApp(fetchFn, '/atendimento/historico/1')
    await waitFor(() => expect(router.currentRoute.value.name).toBe('acesso-negado'))
    expect(chamadas.some((c) => c.includes('/atendimentos/1'))).toBe(false)
  })
})
