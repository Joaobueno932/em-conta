import { fireEvent, screen, waitFor } from '@testing-library/vue'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { apiFalsa, csrf, montarApp, problema, usuarioAtual, type Rotas } from '@/test/api-falsa'
import { jsonResponse } from '@/test/render-with-providers'

const ATENDIMENTO = [
  'atendimento:consultar',
  'atendimento:assumir',
  'atendimento:responder',
  'atendimento:transferir',
  'atendimento:encerrar',
]
const CATALOGO = [
  'respostas-rapidas:consultar',
  'respostas-rapidas:cadastrar',
  'respostas-rapidas:editar',
  'respostas-rapidas:alterar-situacao',
]
const EU = { id: 8, nome: 'Pessoa adm@x.br' }
const BASE = '/api/v1/atendimento'
const RR = `${BASE}/respostas-rapidas`

function resposta(sobrescrever: Record<string, unknown> = {}) {
  return {
    id: 5,
    titulo: 'Segunda via',
    atalho: 'segunda-via',
    conteudo: 'Olá!\nSegue a segunda via 😊',
    ativa: true,
    versao: 0,
    criadoEm: '2026-10-29T12:00:00Z',
    criadoPor: EU,
    atualizadoEm: '2026-10-29T12:00:00Z',
    atualizadoPor: EU,
    ...sobrescrever,
  }
}
const pagina = (itens: unknown[]) =>
  jsonResponse({
    itens,
    pagina: 0,
    tamanho: 20,
    totalItens: itens.length,
    totalPaginas: itens.length ? 1 : 0,
  })

function rotas(
  permissoes: string[],
  adicionais: Rotas = {},
  det: Record<string, unknown> = {},
): Rotas {
  return {
    ...csrf(),
    'GET /api/v1/auth/sessao': () => jsonResponse(usuarioAtual('adm@x.br', permissoes)),
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
    [`GET ${BASE}/atendimentos/1`]: () =>
      jsonResponse({
        resumo: {
          id: 1,
          codigo: 'ATD-000001',
          situacao: 'EM_ATENDIMENTO',
          contatoNumero: '5567999990000',
          iniciadoEm: '2026-10-29T12:00:00Z',
          responsavel: EU,
        },
        versao: 2,
        versaoConversa: 4,
        janelaAbertaAte: new Date(Date.now() + 3_600_000).toISOString(),
        canalConfigurado: true,
        ...det,
      }),
    [`GET ${BASE}/atendimentos/1/linha-do-tempo`]: () => jsonResponse({ itens: [] }),
    [`GET ${BASE}/atendimentos/1/associados-sugeridos`]: () => jsonResponse([]),
    [`GET ${BASE}/atendimentos/1/midia`]: () => jsonResponse({ itens: [] }),
    [`GET ${RR}`]: () => pagina([resposta()]),
    ...adicionais,
  }
}

afterEach(() => {
  vi.restoreAllMocks()
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
})

async function abrirConversa(fetchFn: typeof fetch) {
  await montarApp(fetchFn, '/atendimento?id=1')
  const campo = (await screen.findByLabelText('Mensagem')) as HTMLTextAreaElement
  await waitFor(() => expect(campo.disabled).toBe(false))
  return campo
}

describe('Respostas rápidas na conversa', () => {
  it('botão abre a busca (só ativas, sem conteúdo), mostra prévia e insere sem enviar', async () => {
    const { fetchFn, chamadas } = apiFalsa(rotas([...ATENDIMENTO, 'respostas-rapidas:consultar']))
    const campo = await abrirConversa(fetchFn)

    await fireEvent.click(screen.getByRole('button', { name: 'Respostas rápidas' }))
    const busca = await screen.findByRole('combobox', { name: 'Buscar resposta rápida' })
    expect(document.activeElement).toBe(busca)
    await screen.findByRole('option', { name: /Segunda via/ })
    const consulta = chamadas.find((c) => c.startsWith(`GET ${RR}?`))!
    expect(consulta).toContain('situacao=ATIVAS')
    expect(consulta).toContain('noConteudo=false')
    expect(consulta).toContain('tamanho=8')
    expect(document.querySelector('.previa .balao')?.textContent).toBe(
      'Olá!\nSegue a segunda via 😊',
    )

    await fireEvent.keyDown(busca, { key: 'Enter' })
    expect(campo.value).toBe('Olá!\nSegue a segunda via 😊')
    expect(document.activeElement).toBe(campo)
    expect(chamadas.some((c) => c.startsWith(`POST ${BASE}/atendimentos/1/mensagens`))).toBe(false)
  })

  it('Esc fecha e devolve o foco ao campo, sem mexer no rascunho', async () => {
    const { fetchFn } = apiFalsa(rotas([...ATENDIMENTO, 'respostas-rapidas:consultar']))
    const campo = await abrirConversa(fetchFn)
    await fireEvent.update(campo, 'meu rascunho')

    await fireEvent.click(screen.getByRole('button', { name: 'Respostas rápidas' }))
    const busca = await screen.findByRole('combobox', { name: 'Buscar resposta rápida' })
    await fireEvent.keyDown(busca, { key: 'Escape' })
    await waitFor(() => expect(document.activeElement).toBe(campo))
    expect(screen.queryByRole('dialog', { name: 'Respostas rápidas' })).toBeNull()
    expect(campo.value).toBe('meu rascunho')
  })

  it('com rascunho pergunta: inserir no cursor, substituir ou cancelar', async () => {
    const { fetchFn } = apiFalsa(rotas([...ATENDIMENTO, 'respostas-rapidas:consultar']))
    const campo = await abrirConversa(fetchFn)
    const escolher = async () => {
      await fireEvent.click(screen.getByRole('button', { name: 'Respostas rápidas' }))
      await fireEvent.click(await screen.findByRole('option', { name: /Segunda via/ }))
    }

    await fireEvent.update(campo, 'Oi. Tchau')
    campo.setSelectionRange(4, 4)
    await escolher()
    await fireEvent.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(campo.value).toBe('Oi. Tchau')

    campo.setSelectionRange(4, 4)
    await escolher()
    await fireEvent.click(screen.getByRole('button', { name: 'Inserir no cursor' }))
    expect(campo.value).toBe('Oi. Olá!\nSegue a segunda via 😊Tchau')

    await escolher()
    await fireEvent.click(screen.getByRole('button', { name: 'Substituir rascunho' }))
    expect(campo.value).toBe('Olá!\nSegue a segunda via 😊')
  })

  it('não passa do limite do campo sem avisar', async () => {
    const { fetchFn } = apiFalsa(rotas([...ATENDIMENTO, 'respostas-rapidas:consultar']))
    const campo = await abrirConversa(fetchFn)
    const rascunho = 'x'.repeat(4090)
    await fireEvent.update(campo, rascunho)
    campo.setSelectionRange(4090, 4090)
    await fireEvent.click(screen.getByRole('button', { name: 'Respostas rápidas' }))
    await fireEvent.click(await screen.findByRole('option', { name: /Segunda via/ }))
    await fireEvent.click(screen.getByRole('button', { name: 'Inserir no cursor' }))

    expect((await screen.findByRole('alert')).textContent).toMatch(/máximo 4096/)
    expect(campo.value).toBe(rascunho)
  })

  it('"/" no início de palavra abre sugestões; links e barras no meio não', async () => {
    const { fetchFn, chamadas } = apiFalsa(rotas([...ATENDIMENTO, 'respostas-rapidas:consultar']))
    const campo = await abrirConversa(fetchFn)

    campo.value = 'veja https://site.com/seg'
    campo.setSelectionRange(campo.value.length, campo.value.length)
    await fireEvent.input(campo)
    expect(screen.queryByRole('dialog', { name: 'Respostas rápidas' })).toBeNull()

    campo.value = 'Bom dia /seg'
    campo.setSelectionRange(campo.value.length, campo.value.length)
    await fireEvent.input(campo)
    await screen.findByRole('option', { name: /Segunda via/ })
    expect(campo.getAttribute('role')).toBe('combobox')
    await waitFor(() => expect(chamadas.some((c) => c.includes('busca=seg'))).toBe(true))

    // Enter escolhe (não envia); o "/seg" é trocado e o restante vira rascunho → pergunta.
    await fireEvent.keyDown(campo, { key: 'Enter' })
    await fireEvent.click(screen.getByRole('button', { name: 'Inserir no cursor' }))
    expect(campo.value).toBe('Bom dia Olá!\nSegue a segunda via 😊')
    expect(chamadas.some((c) => c.startsWith(`POST ${BASE}/atendimentos/1/mensagens`))).toBe(false)
  })

  it('o envio depois de inserir é o envio normal, com o texto revisado', async () => {
    let corpo: { texto?: string; chaveIdempotencia?: string } = {}
    const { fetchFn } = apiFalsa(
      rotas([...ATENDIMENTO, 'respostas-rapidas:consultar'], {
        [`POST ${BASE}/atendimentos/1/mensagens`]: (init) => {
          corpo = JSON.parse(String(init?.body))
          return jsonResponse({ mensagemId: 99, situacao: 'PENDENTE' }, 202)
        },
      }),
    )
    const campo = await abrirConversa(fetchFn)
    await fireEvent.click(screen.getByRole('button', { name: 'Respostas rápidas' }))
    await fireEvent.click(await screen.findByRole('option', { name: /Segunda via/ }))
    await fireEvent.update(campo, campo.value + ' Abraço')
    await fireEvent.click(screen.getByRole('button', { name: 'Enviar' }))

    await waitFor(() => expect(corpo.texto).toBe('Olá!\nSegue a segunda via 😊 Abraço'))
    expect(corpo.chaveIdempotencia).toBeTruthy()
  })

  it('sem permissão não há botão de respostas rápidas', async () => {
    const sem = apiFalsa(rotas(ATENDIMENTO))
    await abrirConversa(sem.fetchFn)
    expect(screen.queryByRole('button', { name: 'Respostas rápidas' })).toBeNull()
  })

  it('bloqueios do compositor também valem para as respostas', async () => {
    const { fetchFn } = apiFalsa(
      rotas(
        [...ATENDIMENTO, 'respostas-rapidas:consultar'],
        {},
        {
          janelaAbertaAte: new Date(Date.now() - 1000).toISOString(),
        },
      ),
    )
    await montarApp(fetchFn, '/atendimento?id=1')
    const botao = (await screen.findByRole('button', {
      name: 'Respostas rápidas',
    })) as HTMLButtonElement
    expect(botao.disabled).toBe(true)
  })

  it('com anexo selecionado o botão fica indisponível e explica', async () => {
    const { fetchFn } = apiFalsa(rotas([...ATENDIMENTO, 'respostas-rapidas:consultar']))
    await abrirConversa(fetchFn)
    const entrada = document.querySelector<HTMLInputElement>('input[type=file]')!
    const arquivo = new File(['%PDF-1.4'], 'boleto.pdf', { type: 'application/pdf' })
    await fireEvent.change(entrada, { target: { files: [arquivo] } })

    const botao = screen.getByRole('button', { name: 'Respostas rápidas' }) as HTMLButtonElement
    expect(botao.disabled).toBe(true)
    expect(screen.getByText(/indisponíveis com anexo selecionado/)).toBeTruthy()
  })
})

describe('Catálogo de respostas rápidas', () => {
  it('menu "Ver opções" mostra Mensagens rápidas só com permissão', async () => {
    const { fetchFn } = apiFalsa(rotas([...ATENDIMENTO, 'respostas-rapidas:consultar']))
    await montarApp(fetchFn, '/atendimento')
    await fireEvent.click(await screen.findByRole('button', { name: 'Ver opções' }))
    expect(screen.getByRole('link', { name: 'Mensagens rápidas' }).getAttribute('href')).toBe(
      '/atendimento/respostas-rapidas',
    )
  })

  it('lista, busca no servidor, filtra e mostra só as ações permitidas', async () => {
    const { fetchFn, chamadas } = apiFalsa(
      rotas(['atendimento:consultar', 'respostas-rapidas:consultar']),
    )
    await montarApp(fetchFn, '/atendimento/respostas-rapidas')
    expect(await screen.findByText('Segunda via')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Nova resposta' })).toBeNull()
    expect(screen.queryByRole('button', { name: /Editar/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /Desativar/ })).toBeNull()

    await fireEvent.update(screen.getByLabelText('Buscar respostas'), 'via')
    await fireEvent.click(screen.getByRole('button', { name: 'Buscar' }))
    await waitFor(() => expect(chamadas.some((c) => c.includes('busca=via'))).toBe(true))
    await fireEvent.click(screen.getByRole('button', { name: 'Inativas' }))
    await waitFor(() => expect(chamadas.some((c) => c.includes('situacao=INATIVAS'))).toBe(true))
  })

  it('estado vazio e erro com nova tentativa', async () => {
    let falhar = true
    const { fetchFn } = apiFalsa(
      rotas(CATALOGO, {
        [`GET ${RR}`]: () =>
          falhar ? problema(503, 'INDISPONIVEL', 'Serviço indisponível.') : pagina([]),
      }),
    )
    await montarApp(fetchFn, '/atendimento/respostas-rapidas')
    await screen.findByRole('button', { name: 'Tentar de novo' })
    falhar = false
    await fireEvent.click(screen.getByRole('button', { name: 'Tentar de novo' }))
    expect(await screen.findByText('Nenhuma resposta rápida encontrada.')).toBeTruthy()
  })

  it('cadastra com prévia; erro de campo aparece junto do campo', async () => {
    const corpos: unknown[] = []
    const { fetchFn } = apiFalsa(
      rotas(CATALOGO, {
        [`POST ${RR}`]: (init) => {
          corpos.push(JSON.parse(String(init?.body)))
          return new Response(
            JSON.stringify({
              status: 409,
              codigo: 'ATALHO_EM_USO',
              detail: 'Este atalho já é usado.',
              campos: { atalho: 'Este atalho já é usado por outra resposta (ativa ou inativa).' },
            }),
            { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
          )
        },
      }),
    )
    await montarApp(fetchFn, '/atendimento/respostas-rapidas')
    await fireEvent.click(await screen.findByRole('button', { name: 'Nova resposta' }))
    await fireEvent.update(screen.getByLabelText(/^Título/), 'Boas-vindas')
    await fireEvent.update(screen.getByLabelText(/^Atalho/), '/Ola')
    await fireEvent.update(screen.getByLabelText(/^Texto/), 'Linha 1\nLinha 2')
    expect(document.querySelector('.formulario .balao')?.textContent).toBe('Linha 1\nLinha 2')
    expect(screen.getByText(/digite \/ola/)).toBeTruthy()
    await fireEvent.click(screen.getByRole('button', { name: 'Salvar' }))

    const atalho = screen.getByLabelText(/^Atalho/)
    await waitFor(() => expect(atalho.getAttribute('aria-invalid')).toBe('true'))
    expect(document.getElementById('rr-atalho-erro')?.textContent).toMatch(/já é usado/)
    expect(corpos[0]).toEqual({
      titulo: 'Boas-vindas',
      atalho: '/Ola',
      conteudo: 'Linha 1\nLinha 2',
    })
  })

  it('edição em conflito pede para recarregar', async () => {
    const { fetchFn } = apiFalsa(
      rotas(CATALOGO, {
        [`PUT ${RR}/5`]: () => problema(409, 'CONFLITO_CONCORRENTE', 'Alterada por outra pessoa.'),
        [`GET ${RR}/5`]: () => jsonResponse(resposta({ versao: 1, titulo: 'Segunda via (nova)' })),
      }),
    )
    await montarApp(fetchFn, '/atendimento/respostas-rapidas')
    await fireEvent.click(await screen.findByRole('button', { name: 'Editar Segunda via' }))
    await fireEvent.update(screen.getByLabelText(/^Título/), 'Outro título')
    await fireEvent.click(screen.getByRole('button', { name: 'Salvar' }))
    expect(await screen.findByText(/alterada por outra pessoa enquanto você editava/)).toBeTruthy()

    await fireEvent.click(screen.getByRole('button', { name: 'Recarregar' }))
    await waitFor(() =>
      expect((screen.getByLabelText(/^Título/) as HTMLInputElement).value).toBe(
        'Segunda via (nova)',
      ),
    )
  })

  it('desativar envia a versão vista', async () => {
    let corpo: unknown
    const { fetchFn } = apiFalsa(
      rotas(CATALOGO, {
        [`POST ${RR}/5/desativacao`]: (init) => {
          corpo = JSON.parse(String(init?.body))
          return jsonResponse(resposta({ ativa: false, versao: 1 }))
        },
      }),
    )
    await montarApp(fetchFn, '/atendimento/respostas-rapidas')
    await fireEvent.click(await screen.findByRole('button', { name: 'Desativar Segunda via' }))
    await waitFor(() => expect(corpo).toEqual({ versao: 0 }))
  })

  it('sem respostas-rapidas:consultar a rota leva a acesso negado', async () => {
    const { fetchFn } = apiFalsa(rotas(ATENDIMENTO))
    const { router } = await montarApp(fetchFn, '/atendimento/respostas-rapidas')
    await waitFor(() => expect(router.currentRoute.value.name).toBe('acesso-negado'))
  })
})
