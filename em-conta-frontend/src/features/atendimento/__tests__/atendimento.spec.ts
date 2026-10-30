import { fireEvent, screen, waitFor } from '@testing-library/vue'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { apiFalsa, csrf, montarApp, problema, usuarioAtual, type Rotas } from '@/test/api-falsa'
import { jsonResponse } from '@/test/render-with-providers'

const TODAS = [
  'atendimento:consultar',
  'atendimento:assumir',
  'atendimento:responder',
  'atendimento:transferir',
  'atendimento:encerrar',
  'atendimento:vincular-associado',
]

// usuarioAtual usa o tamanho do e-mail como id: 'adm@x.br' → 8.
const EU = { id: 8, nome: 'Pessoa adm@x.br' }
const OUTRA = { id: 30, nome: 'Outra Pessoa' }
const BASE = '/api/v1/atendimento'

function resumo(sobrescrever: Record<string, unknown> = {}) {
  return {
    id: 1,
    codigo: 'ATD-000001',
    situacao: 'AGUARDANDO',
    contatoNumero: '5567999990000',
    contatoNome: 'Contato Teste',
    iniciadoEm: '2026-10-29T12:00:00Z',
    ...sobrescrever,
  }
}

function detalhe(sobrescrever: Record<string, unknown> = {}, extra: Record<string, unknown> = {}) {
  return {
    resumo: resumo(sobrescrever),
    versao: 2,
    versaoConversa: 4,
    janelaAbertaAte: new Date(Date.now() + 3_600_000).toISOString(),
    canalConfigurado: true,
    ...extra,
  }
}

const mensagem = {
  tipo: 'MENSAGEM',
  id: 10,
  cursor: '1000.1.10',
  ocorridoEm: '2026-10-29T12:00:00Z',
  atendimentoId: 1,
  direcao: 'ENTRADA',
  tipoMensagem: 'TEXTO',
  texto: 'Olá, preciso de ajuda',
  situacao: 'RECEBIDA',
}

function rotas(det: unknown, permissoes = TODAS, adicionais: Rotas = {}): Rotas {
  return {
    ...csrf(),
    'GET /api/v1/auth/sessao': () => jsonResponse(usuarioAtual('adm@x.br', permissoes)),
    [`GET ${BASE}/canal`]: () => jsonResponse({ canal: 'WHATSAPP', configurado: true }),
    [`GET ${BASE}/presenca`]: () => jsonResponse({ disponivel: true }),
    [`GET ${BASE}/atendimentos`]: () =>
      jsonResponse({
        itens: [resumo()],
        pagina: 0,
        tamanho: 20,
        totalItens: 1,
        totalPaginas: 1,
        contagens: { aguardando: 1, emAndamento: 0, meus: 0 },
      }),
    [`GET ${BASE}/atendimentos/1`]: () => jsonResponse(det),
    [`GET ${BASE}/atendimentos/1/linha-do-tempo`]: () => jsonResponse({ itens: [mensagem] }),
    [`GET ${BASE}/atendimentos/1/associados-sugeridos`]: () => jsonResponse([]),
    [`GET ${BASE}/atendimentos/1/midia`]: () => jsonResponse({ itens: [] }),
    ...adicionais,
  }
}

afterEach(() => {
  vi.restoreAllMocks()
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
})

describe('Atendimento', () => {
  it('mostra a fila e a conversa; assumir chama o servidor', async () => {
    const { fetchFn, chamadas } = apiFalsa(
      rotas(detalhe(), TODAS, {
        [`POST ${BASE}/atendimentos/1/assuncao`]: () => new Response(null, { status: 204 }),
      }),
    )
    await montarApp(fetchFn, '/atendimento?id=1')

    expect(await screen.findByText('Olá, preciso de ajuda')).toBeTruthy()
    expect(screen.getByRole('button', { name: /Aguardando/ }).textContent).toContain('1')
    // Ainda não é responsável: não responde.
    expect((screen.getByLabelText('Mensagem') as HTMLTextAreaElement).disabled).toBe(true)

    await fireEvent.click(screen.getByRole('button', { name: 'Assumir atendimento' }))
    await waitFor(() => expect(chamadas).toContain(`POST ${BASE}/atendimentos/1/assuncao`))
  })

  it('sem canal configurado avisa e não permite enviar', async () => {
    const { fetchFn, chamadas } = apiFalsa(
      rotas(
        detalhe({ situacao: 'EM_ATENDIMENTO', responsavel: EU }, { canalConfigurado: false }),
        TODAS,
        { [`GET ${BASE}/canal`]: () => jsonResponse({ canal: 'WHATSAPP', configurado: false }) },
      ),
    )
    await montarApp(fetchFn, '/atendimento?id=1')

    expect((await screen.findAllByText(/Canal não configurado/)).length).toBeGreaterThan(0)
    expect((screen.getByLabelText('Mensagem') as HTMLTextAreaElement).disabled).toBe(true)
    expect(chamadas.some((c) => c.startsWith(`POST ${BASE}/atendimentos/1/mensagens`))).toBe(false)
  })

  it('repetir o envio após falha reutiliza a mesma chave de idempotência', async () => {
    const chaves: string[] = []
    let tentativa = 0
    const { fetchFn } = apiFalsa(
      rotas(detalhe({ situacao: 'EM_ATENDIMENTO', responsavel: EU }), TODAS, {
        [`POST ${BASE}/atendimentos/1/mensagens`]: (init) => {
          chaves.push(JSON.parse(String(init?.body)).chaveIdempotencia)
          tentativa++
          return tentativa === 1
            ? problema(503, 'INDISPONIVEL', 'Serviço indisponível.')
            : jsonResponse({ mensagemId: 99, situacao: 'PENDENTE' }, 202)
        },
      }),
    )
    await montarApp(fetchFn, '/atendimento?id=1')

    const campo = (await screen.findByLabelText('Mensagem')) as HTMLTextAreaElement
    await waitFor(() => expect(campo.disabled).toBe(false))
    await fireEvent.update(campo, 'Bom dia!')
    await fireEvent.click(screen.getByRole('button', { name: 'Enviar' }))
    expect(await screen.findByText(/tentar de novo não duplica/)).toBeTruthy()
    expect(campo.value).toBe('Bom dia!')

    await fireEvent.click(screen.getByRole('button', { name: 'Enviar' }))
    await waitFor(() => expect(chaves).toHaveLength(2))
    expect(chaves[0]).toBe(chaves[1])
    await waitFor(() => expect(campo.value).toBe(''))
  })

  it('texto editado depois de uma falha é outra operação, com outra chave', async () => {
    const enviados: { texto: string; chave: string }[] = []
    const { fetchFn } = apiFalsa(
      rotas(detalhe({ situacao: 'EM_ATENDIMENTO', responsavel: EU }), TODAS, {
        [`POST ${BASE}/atendimentos/1/mensagens`]: (init) => {
          const corpo = JSON.parse(String(init?.body))
          enviados.push({ texto: corpo.texto, chave: corpo.chaveIdempotencia })
          return problema(503, 'INDISPONIVEL', 'Serviço indisponível.')
        },
      }),
    )
    await montarApp(fetchFn, '/atendimento?id=1')
    const campo = (await screen.findByLabelText('Mensagem')) as HTMLTextAreaElement
    await waitFor(() => expect(campo.disabled).toBe(false))
    const enviar = async (valor: string, quantos: number) => {
      await fireEvent.update(campo, valor)
      await fireEvent.click(await screen.findByRole('button', { name: 'Enviar' }))
      await waitFor(() => expect(enviados).toHaveLength(quantos))
    }

    await enviar('Bom dia!', 1)
    await enviar('Bom dia! Tudo bem?', 2)
    // Voltar ao texto original repete a primeira operação (que pode ter sido registrada).
    await enviar('  Bom dia!  ', 3)
    expect(enviados.map((e) => e.texto)).toEqual(['Bom dia!', 'Bom dia! Tudo bem?', 'Bom dia!'])
    expect(enviados[1]!.chave).not.toBe(enviados[0]!.chave)
    expect(enviados[2]!.chave).toBe(enviados[0]!.chave)
  })

  it('carrega mensagens anteriores, mantém o que já foi lido e preenche lacunas', async () => {
    const item = (id: number, texto: string) => ({
      ...mensagem,
      id,
      cursor: `${id}000.1.${id}`,
      texto,
    })
    let consultasRecentes = 0
    const LINHA = `${BASE}/atendimentos/1/linha-do-tempo?somenteEste=false&tamanho=50`
    const { fetchFn, chamadas } = apiFalsa(
      rotas(detalhe({ situacao: 'AGUARDANDO' }), TODAS, {
        [`POST ${BASE}/atendimentos/1/assuncao`]: () => new Response(null, { status: 204 }),
        [`GET ${LINHA}`]: () => {
          consultasRecentes++
          // Na segunda consulta chegaram mais itens do que cabem na página: a 40 ficou de fora.
          return jsonResponse(
            consultasRecentes === 1
              ? { itens: [item(30, 'trinta')], anterior: '30000.1.30' }
              : { itens: [item(50, 'cinquenta')], anterior: '50000.1.50' },
          )
        },
        [`GET ${LINHA}&antes=30000.1.30`]: () =>
          jsonResponse({ itens: [item(10, 'dez'), item(20, 'vinte')] }),
        [`GET ${LINHA}&antes=50000.1.50`]: () =>
          jsonResponse({ itens: [item(40, 'quarenta')], anterior: '40000.1.40' }),
        [`GET ${LINHA}&antes=40000.1.40`]: () => jsonResponse({ itens: [item(30, 'trinta')] }),
      }),
    )
    await montarApp(fetchFn, '/atendimento?id=1')
    expect(await screen.findByText('trinta')).toBeTruthy()

    await fireEvent.click(screen.getByRole('button', { name: 'Carregar mensagens anteriores' }))
    expect(await screen.findByText('dez')).toBeTruthy()
    expect(screen.getByText('Início do histórico')).toBeTruthy()

    // Atualização (aqui provocada por uma ação): as páginas lidas continuam e a lacuna é buscada.
    await fireEvent.click(screen.getByRole('button', { name: 'Assumir atendimento' }))
    expect(await screen.findByText('cinquenta')).toBeTruthy()
    expect(await screen.findByText('quarenta')).toBeTruthy()
    const textos = [...document.querySelectorAll('.mensagem .texto')].map((e) => e.textContent)
    expect(textos).toEqual(['dez', 'vinte', 'trinta', 'quarenta', 'cinquenta'])
    expect(chamadas).toContain(`GET ${LINHA}&antes=50000.1.50`)
  })

  it('falha ao carregar anteriores oferece nova tentativa', async () => {
    let tentativas = 0
    const LINHA = `${BASE}/atendimentos/1/linha-do-tempo?somenteEste=false&tamanho=50`
    const { fetchFn } = apiFalsa(
      rotas(detalhe(), TODAS, {
        [`GET ${LINHA}`]: () => jsonResponse({ itens: [mensagem], anterior: mensagem.cursor }),
        [`GET ${LINHA}&antes=${mensagem.cursor}`]: () =>
          ++tentativas === 1
            ? problema(503, 'INDISPONIVEL', 'Serviço indisponível.')
            : jsonResponse({ itens: [{ ...mensagem, id: 9, cursor: '900.1.9', texto: 'antiga' }] }),
      }),
    )
    await montarApp(fetchFn, '/atendimento?id=1')
    await fireEvent.click(
      await screen.findByRole('button', { name: 'Carregar mensagens anteriores' }),
    )
    expect(
      await screen.findByText(/Não foi possível carregar as mensagens anteriores/),
    ).toBeTruthy()
    await fireEvent.click(screen.getByRole('button', { name: 'Tentar de novo' }))
    expect(await screen.findByText('antiga')).toBeTruthy()
    expect(screen.getByText('Início do histórico')).toBeTruthy()
  })

  it('resposta não enviada por janela expirada é explicada', async () => {
    const { fetchFn } = apiFalsa(
      rotas(detalhe({ situacao: 'EM_ATENDIMENTO', responsavel: EU }), TODAS, {
        [`GET ${BASE}/atendimentos/1/linha-do-tempo`]: () =>
          jsonResponse({
            itens: [
              mensagem,
              {
                ...mensagem,
                id: 11,
                cursor: '2000.1.11',
                direcao: 'SAIDA',
                texto: 'Resposta tardia',
                situacao: 'JANELA_EXPIRADA',
              },
            ],
          }),
      }),
    )
    await montarApp(fetchFn, '/atendimento?id=1')
    expect(await screen.findByText(/janela de 24 horas expirou antes do envio/)).toBeTruthy()
  })

  it('janela de 24 horas encerrada bloqueia a resposta', async () => {
    const { fetchFn } = apiFalsa(
      rotas(
        detalhe(
          { situacao: 'EM_ATENDIMENTO', responsavel: EU },
          { janelaAbertaAte: '2026-01-01T00:00:00Z' },
        ),
      ),
    )
    await montarApp(fetchFn, '/atendimento?id=1')
    expect(await screen.findByPlaceholderText(/Janela de 24 horas encerrada/)).toBeTruthy()
  })

  it('atendimento de outra pessoa: sem transferir, encerrar nem responder', async () => {
    const { fetchFn } = apiFalsa(rotas(detalhe({ situacao: 'EM_ATENDIMENTO', responsavel: OUTRA })))
    await montarApp(fetchFn, '/atendimento?id=1')
    expect(await screen.findByText(/Em atendimento com Outra Pessoa/)).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Transferir' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Encerrar' })).toBeNull()
    expect(screen.getByPlaceholderText('Assuma o atendimento para responder.')).toBeTruthy()
  })

  it('"Ver opções" só oferece o Histórico geral', async () => {
    const { fetchFn } = apiFalsa(rotas(detalhe()))
    await montarApp(fetchFn, '/atendimento?id=1')
    const botao = await screen.findByRole('button', { name: 'Ver opções' })
    expect(botao.getAttribute('aria-expanded')).toBe('false')
    await fireEvent.click(botao)
    expect(botao.getAttribute('aria-expanded')).toBe('true')
    const menu = document.getElementById('opcoes-atendimento')!
    expect([...menu.querySelectorAll('a')].map((a) => a.textContent?.trim())).toEqual([
      'Histórico geral',
    ])
    // Esc fecha e devolve o foco ao botão.
    menu.querySelector('a')!.focus()
    await fireEvent.keyDown(menu, { key: 'Escape' })
    expect(document.getElementById('opcoes-atendimento')).toBeNull()
    expect(document.activeElement).toBe(botao)
  })

  it('fechar o painel do associado pelo X devolve o foco ao botão que o reabre', async () => {
    const { fetchFn } = apiFalsa(rotas(detalhe()))
    await montarApp(fetchFn, '/atendimento?id=1')
    // Com a conversa carregada (o botão que reabre o painel já existe).
    const reabrir = await screen.findByRole('button', { name: 'Dados do associado' })
    expect(reabrir.getAttribute('aria-expanded')).toBe('true')
    await fireEvent.click(await screen.findByRole('button', { name: 'Fechar painel do associado' }))
    await waitFor(() => expect(document.activeElement).toBe(reabrir))
    expect(reabrir.getAttribute('aria-expanded')).toBe('false')
    expect(screen.queryByRole('complementary', { name: 'Dados do associado' })).toBeNull()
    await fireEvent.click(reabrir)
    const fechar = await screen.findByRole('button', { name: 'Fechar painel do associado' })
    await waitFor(() => expect(document.activeElement).toBe(fechar))
  })

  it('trocar o associado vinculado exige motivo', async () => {
    const atual = {
      id: 7,
      tipo: 'PF',
      nome: 'Associado Atual',
      documentoMascarado: '***.456.789-**',
    }
    const novo = { id: 8, tipo: 'PF', nome: 'Associado Novo', documentoMascarado: '***.111.222-**' }
    const corpos: unknown[] = []
    const { fetchFn } = apiFalsa(
      rotas(detalhe({ situacao: 'EM_ATENDIMENTO', responsavel: EU, associado: atual }), TODAS, {
        [`GET ${BASE}/associados`]: () => jsonResponse([novo]),
        [`PUT ${BASE}/atendimentos/1/associado`]: (init) => {
          corpos.push(JSON.parse(String(init?.body)))
          return new Response(null, { status: 204 })
        },
      }),
    )
    await montarApp(fetchFn, '/atendimento?id=1')

    await fireEvent.click(await screen.findByRole('button', { name: 'Alterar vínculo' }))
    await fireEvent.update(screen.getByLabelText('Buscar associado'), 'Novo')
    await fireEvent.submit(screen.getByLabelText('Buscar associado'))
    await fireEvent.click(await screen.findByRole('radio', { name: /Associado Novo/ }))
    const vincular = screen.getByRole('button', { name: 'Vincular associado' }) as HTMLButtonElement
    expect(vincular.disabled).toBe(true)

    await fireEvent.update(
      screen.getByLabelText('Motivo da alteração'),
      'Telefone de outro titular',
    )
    expect(vincular.disabled).toBe(false)
    await fireEvent.click(vincular)
    await waitFor(() =>
      expect(corpos).toEqual([
        { versaoConversa: 4, associadoId: 8, motivo: 'Telefone de outro titular' },
      ]),
    )
  })

  it('sem atendimento:consultar a rota leva a acesso negado', async () => {
    const { fetchFn } = apiFalsa(rotas(detalhe(), ['associados:consultar']))
    const { router } = await montarApp(fetchFn, '/atendimento')
    await waitFor(() => expect(router.currentRoute.value.name).toBe('acesso-negado'))
  })
})
