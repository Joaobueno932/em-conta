import { fireEvent, screen, waitFor, within } from '@testing-library/vue'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { apiFalsa, csrf, montarApp, problema, usuarioAtual } from '@/test/api-falsa'
import { jsonResponse } from '@/test/render-with-providers'

const PERMISSOES = ['usinas:consultar', 'usinas:cadastrar', 'usinas:editar']
const autor = { id: 1, nome: 'Pessoa adm@x.br' }

/** 105 fazendas no "servidor", com busca e paginação como o backend. */
const FAZENDAS = Array.from({ length: 105 }, (_, i) => ({
  id: i + 1,
  codigo: `FZD-${String(i + 1).padStart(4, '0')}`,
  nome: `Fazenda ${String(i + 1).padStart(3, '0')}`,
  usinas: 0,
}))

function paginaFazendas(url: string) {
  const params = new URL(url, 'http://x').searchParams
  const busca = (params.get('busca') ?? '').toLowerCase()
  const pagina = Number(params.get('pagina') ?? 0)
  const tamanho = Number(params.get('tamanho') ?? 20)
  const filtradas = FAZENDAS.filter(
    (f) => !busca || f.nome.toLowerCase().includes(busca) || f.codigo.toLowerCase() === busca,
  )
  return jsonResponse({
    itens: filtradas.slice(pagina * tamanho, (pagina + 1) * tamanho),
    pagina,
    tamanho,
    totalItens: filtradas.length,
    totalPaginas: Math.ceil(filtradas.length / tamanho),
  })
}

function usina(fazenda?: { id: number; codigo: string; nome: string }) {
  return {
    id: 5,
    codigo: 'USN-0005',
    nome: 'Usina Teste',
    fazenda,
    ucsVinculadas: 0,
    associadosDistintos: 0,
    vinculosEncerrados: 0,
    criadoEm: '2026-10-29T12:00:00Z',
    criadoPor: autor,
    atualizadoEm: '2026-10-29T12:00:00Z',
    atualizadoPor: autor,
    versao: 0,
  }
}

/** API falsa em que /fazendas responde por `responderFazendas` (com acesso à URL). */
function api(responderFazendas: (url: string) => Response | Promise<Response> = paginaFazendas) {
  const corpos: Record<string, unknown>[] = []
  const urls: string[] = []
  const base = apiFalsa({
    ...csrf(),
    'GET /api/v1/auth/sessao': () => jsonResponse(usuarioAtual('adm@x.br', PERMISSOES)),
    'GET /api/v1/usinas/5': () =>
      jsonResponse(usina({ id: 150, codigo: 'FZD-0150', nome: 'Fazenda Distante' })),
    'POST /api/v1/usinas': (init) => {
      corpos.push(JSON.parse(String(init?.body)))
      return jsonResponse(usina(), 201)
    },
    'PUT /api/v1/usinas/5': (init) => {
      corpos.push(JSON.parse(String(init?.body)))
      return jsonResponse(usina())
    },
  })
  const fetchFn = vi.fn<typeof fetch>(async (input, init) => {
    const url = String(input)
    if (url.startsWith('/api/v1/fazendas?')) {
      urls.push(url)
      return responderFazendas(url)
    }
    return base.fetchFn(input, init)
  })
  return { fetchFn, corpos, urls }
}

async function buscar(termo: string) {
  await fireEvent.update(screen.getByRole('searchbox', { name: 'Buscar fazenda' }), termo)
  await fireEvent.click(screen.getByRole('button', { name: 'Buscar fazenda' }))
}

afterEach(() => {
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
})

describe('seletor de fazendas', () => {
  it('pagina no servidor e seleciona fazenda fora da primeira página', async () => {
    const { fetchFn, corpos, urls } = api()
    const { router } = await montarApp(fetchFn, '/usinas/nova')

    expect(
      await screen.findByRole('button', { name: /FZD-0001 · Fazenda 001/ }),
    ).toBeInTheDocument()
    expect(screen.getByText('Página 1 de 6')).toBeInTheDocument()
    expect(urls[0]).toContain('tamanho=20')

    // Navega até a última página (fazendas 101–105).
    for (let i = 0; i < 5; i++) {
      await fireEvent.click(screen.getByRole('button', { name: 'Próxima' }))
    }
    await fireEvent.click(await screen.findByRole('button', { name: /FZD-0103 · Fazenda 103/ }))
    expect(screen.getByText(/Selecionada:/).textContent).toContain('FZD-0103 · Fazenda 103')

    // Mudar a busca não perde a seleção.
    await buscar('Fazenda 00')
    expect(await screen.findByRole('button', { name: /FZD-0001/ })).toBeInTheDocument()
    expect(screen.getByText(/Selecionada:/).textContent).toContain('FZD-0103')

    await fireEvent.update(screen.getByLabelText(/Nome da usina/), 'Nova usina')
    await fireEvent.click(screen.getByRole('button', { name: 'Salvar usina' }))
    await waitFor(() => expect(router.currentRoute.value.name).toBe('usina'))
    expect(corpos[0]).toMatchObject({ nome: 'Nova usina', fazendaId: 103 })
  })

  it('na edição mantém visível a fazenda vinculada que não está nos resultados e permite limpar', async () => {
    const { fetchFn, corpos } = api()
    const { router } = await montarApp(fetchFn, '/usinas/5/editar')

    await waitFor(() =>
      expect(screen.getByText(/Selecionada:/).textContent).toContain('FZD-0150 · Fazenda Distante'),
    )
    expect(screen.queryByRole('button', { name: /FZD-0150/ })).not.toBeInTheDocument()

    await fireEvent.click(screen.getByRole('button', { name: /Remover fazenda/ }))
    expect(screen.getByText('Nenhuma fazenda selecionada.')).toBeInTheDocument()
    await fireEvent.click(screen.getByRole('button', { name: 'Salvar usina' }))
    await waitFor(() => expect(router.currentRoute.value.name).toBe('usina'))
    expect(corpos[0]).not.toHaveProperty('fazendaId')
  })

  it('busca sem resultados e falha de carregamento mantêm a seleção', async () => {
    let falhar = false
    const { fetchFn } = api((url) => (falhar ? problema(503, 'X', 'x') : paginaFazendas(url)))
    await montarApp(fetchFn, '/usinas/5/editar')
    await waitFor(() => expect(screen.getByText(/Selecionada:/).textContent).toContain('FZD-0150'))

    await buscar('inexistente')
    expect(
      await screen.findByText('Nenhuma fazenda encontrada para “inexistente”.'),
    ).toBeInTheDocument()

    falhar = true
    await buscar('outra')
    const alerta = await screen.findByRole('alert')
    expect(within(alerta).getByText(/A API está indisponível/)).toBeInTheDocument()
    expect(alerta.textContent).toContain('A seleção atual foi mantida.')
    expect(screen.getByText(/Selecionada:/).textContent).toContain('FZD-0150 · Fazenda Distante')
  })

  it('resposta antiga que chega depois não substitui a busca atual', async () => {
    let liberarAntiga: () => void = () => {}
    const { fetchFn } = api((url) => {
      if (url.includes('busca=lenta')) {
        return new Promise<Response>((resolve) => {
          liberarAntiga = () =>
            resolve(
              jsonResponse({
                itens: [{ id: 999, codigo: 'FZD-0999', nome: 'Resultado antigo', usinas: 0 }],
                pagina: 0,
                tamanho: 20,
                totalItens: 1,
                totalPaginas: 1,
              }),
            )
        })
      }
      return paginaFazendas(url)
    })
    await montarApp(fetchFn, '/usinas/nova')
    await screen.findByRole('button', { name: /FZD-0001/ })

    await buscar('lenta')
    await buscar('Fazenda 104')
    expect(
      await screen.findByRole('button', { name: /FZD-0104 · Fazenda 104/ }),
    ).toBeInTheDocument()
    liberarAntiga()
    await new Promise((r) => setTimeout(r, 20))
    expect(screen.queryByText(/Resultado antigo/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /FZD-0104/ })).toBeInTheDocument()
  })

  it('sem permissão de consulta informa e não impede salvar sem fazenda', async () => {
    const { fetchFn } = api(() =>
      problema(403, 'ACESSO_NEGADO', 'Você não tem permissão para esta ação.'),
    )
    await montarApp(fetchFn, '/usinas/nova')
    expect(
      await screen.findByText(/Você não tem permissão para consultar fazendas/),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Tentar novamente' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Salvar usina' })).toBeEnabled()
  })
})
