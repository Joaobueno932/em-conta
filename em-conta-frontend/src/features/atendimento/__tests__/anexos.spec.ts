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
const EU = { id: 8, nome: 'Pessoa adm@x.br' }
const BASE = '/api/v1/atendimento'
const LINHA = `${BASE}/atendimentos/1/linha-do-tempo?somenteEste=false&tamanho=50`

function detalhe(situacao = 'EM_ATENDIMENTO') {
  return {
    resumo: {
      id: 1,
      codigo: 'ATD-000001',
      situacao,
      contatoNumero: '5567999990000',
      contatoNome: 'Contato Teste',
      iniciadoEm: '2026-10-29T12:00:00Z',
      responsavel: EU,
    },
    versao: 2,
    versaoConversa: 4,
    janelaAbertaAte: new Date(Date.now() + 3_600_000).toISOString(),
    canalConfigurado: true,
  }
}

function mensagem(id: number, extra: Record<string, unknown> = {}) {
  return {
    tipo: 'MENSAGEM',
    id,
    cursor: `${id}000.1.${id}`,
    ocorridoEm: '2026-10-29T12:00:00Z',
    atendimentoId: 1,
    direcao: 'ENTRADA',
    tipoMensagem: 'TEXTO',
    texto: `mensagem ${id}`,
    situacao: 'RECEBIDA',
    atualizadoEm: '2026-10-29T12:00:00Z',
    ...extra,
  }
}

const anexo = (id: number, categoria: string, extra: Record<string, unknown> = {}) => ({
  id,
  categoria,
  mimeType:
    categoria === 'IMAGEM'
      ? 'image/png'
      : categoria === 'DOCUMENTO'
        ? 'application/pdf'
        : 'audio/ogg',
  nome: categoria === 'DOCUMENTO' ? 'fatura.pdf' : undefined,
  tamanho: 2048,
  situacao: 'DISPONIVEL',
  ...extra,
})

function rotas(itens: unknown[], adicionais: Rotas = {}, situacao = 'EM_ATENDIMENTO'): Rotas {
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
    [`GET ${BASE}/atendimentos/1`]: () => jsonResponse(detalhe(situacao)),
    [`GET ${BASE}/atendimentos/1/linha-do-tempo`]: () => jsonResponse({ itens }),
    [`GET ${BASE}/atendimentos/1/associados-sugeridos`]: () => jsonResponse([]),
    [`GET ${BASE}/atendimentos/1/midia`]: () => jsonResponse({ itens: [] }),
    ...adicionais,
  }
}

afterEach(() => {
  vi.restoreAllMocks()
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
})

/** Simula a escolha de um arquivo no seletor (jsdom não preenche `files` sozinho). */
async function selecionar(entrada: HTMLInputElement, arquivo: File) {
  Object.defineProperty(entrada, 'files', { value: [arquivo], configurable: true })
  await fireEvent.change(entrada)
}

const arquivo = (nome: string, tipo: string, tamanho = 10) =>
  new File([new Uint8Array(tamanho)], nome, { type: tipo })

describe('Anexos no atendimento', () => {
  it('mostra imagem, PDF e áudio pela rota autenticada, sem reproduzir automaticamente', async () => {
    const { fetchFn } = apiFalsa(
      rotas([
        mensagem(1, {
          tipoMensagem: 'IMAGEM',
          texto: 'Foto do medidor',
          anexo: anexo(11, 'IMAGEM'),
        }),
        mensagem(2, { tipoMensagem: 'DOCUMENTO', texto: undefined, anexo: anexo(12, 'DOCUMENTO') }),
        mensagem(3, { tipoMensagem: 'AUDIO', texto: undefined, anexo: anexo(13, 'AUDIO') }),
      ]),
    )
    await montarApp(fetchFn, '/atendimento?id=1')

    const imagem = (await screen.findByAltText('Foto do medidor')) as HTMLImageElement
    expect(imagem.getAttribute('src')).toBe(`${BASE}/atendimentos/1/anexos/11/conteudo`)
    expect(imagem.getAttribute('loading')).toBe('lazy')
    expect(screen.getByText('fatura.pdf', { selector: '.nome' })).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Baixar fatura.pdf' }).getAttribute('href')).toBe(
      `${BASE}/atendimentos/1/anexos/12/conteudo?download=true`,
    )
    const audio = document.querySelector('audio')!
    expect(audio.getAttribute('preload')).toBe('none')
    expect(audio.hasAttribute('autoplay')).toBe(false)
  })

  it('estados de processamento, falha com nova tentativa e não suportado', async () => {
    const { fetchFn, chamadas } = apiFalsa(
      rotas(
        [
          mensagem(1, {
            tipoMensagem: 'IMAGEM',
            anexo: anexo(11, 'IMAGEM', { situacao: 'PROCESSANDO' }),
          }),
          mensagem(2, {
            tipoMensagem: 'DOCUMENTO',
            anexo: anexo(12, 'DOCUMENTO', {
              situacao: 'FALHA',
              erro: 'Hash do arquivo não confere',
            }),
          }),
          mensagem(3, {
            tipoMensagem: 'DOCUMENTO',
            anexo: anexo(13, 'DOCUMENTO', {
              situacao: 'NAO_SUPORTADO',
              mimeType: 'application/msword',
            }),
          }),
        ],
        {
          [`POST ${BASE}/atendimentos/1/anexos/12/nova-tentativa`]: () =>
            new Response(null, { status: 204 }),
        },
      ),
    )
    await montarApp(fetchFn, '/atendimento?id=1')
    expect(await screen.findByText(/Processando o arquivo/)).toBeTruthy()
    expect(screen.getByText(/Hash do arquivo não confere/)).toBeTruthy()
    expect(screen.getByText(/application\/msword\) não suportado/)).toBeTruthy()
    await fireEvent.click(screen.getByRole('button', { name: 'Tentar de novo' }))
    await waitFor(() =>
      expect(chamadas).toContain(`POST ${BASE}/atendimentos/1/anexos/12/nova-tentativa`),
    )
  })

  it('valida o arquivo antes de enviar e permite remover a seleção', async () => {
    const { fetchFn, chamadas } = apiFalsa(rotas([mensagem(1)]))
    await montarApp(fetchFn, '/atendimento?id=1')
    const entrada = (await waitFor(() => {
      const el = document.querySelector('input[type=file]')
      expect(el).toBeTruthy()
      return el
    })) as HTMLInputElement

    await selecionar(entrada, arquivo('programa.exe', 'application/x-msdownload'))
    expect(await screen.findByText(/Tipo não suportado/)).toBeTruthy()
    await selecionar(entrada, arquivo('foto.pdf', 'image/png'))
    expect(await screen.findByText(/não corresponde ao tipo/)).toBeTruthy()
    await selecionar(entrada, arquivo('foto.png', 'image/png', 6 * 1024 * 1024))
    expect(await screen.findByText(/acima do limite de 5,0 MB/)).toBeTruthy()

    await selecionar(entrada, arquivo('voz.ogg', 'audio/ogg', 2048))
    expect(await screen.findByText('voz.ogg')).toBeTruthy()
    expect(screen.getByText(/Áudio · 2 KB/)).toBeTruthy()
    // Áudio não aceita legenda.
    expect((screen.getByLabelText('Legenda (opcional)') as HTMLTextAreaElement).disabled).toBe(true)
    await fireEvent.click(screen.getByRole('button', { name: /Remover o arquivo voz.ogg/ }))
    expect(screen.queryByText('voz.ogg')).toBeNull()
    expect(chamadas.some((c) => c.includes('/anexos'))).toBe(false)
  })

  it('envia o anexo com legenda; repetir após falha reutiliza a chave e outra legenda usa outra', async () => {
    const envios: { nome: string; legenda: string | null; chave: string }[] = []
    let tentativa = 0
    const { fetchFn } = apiFalsa(
      rotas([mensagem(1)], {
        [`POST ${BASE}/atendimentos/1/anexos`]: (init) => {
          const dados = init?.body as FormData
          envios.push({
            nome: (dados.get('arquivo') as File).name,
            legenda: dados.get('legenda') as string | null,
            chave: dados.get('chaveIdempotencia') as string,
          })
          tentativa++
          return tentativa < 3
            ? problema(503, 'ARMAZENAMENTO_INDISPONIVEL', 'Armazenamento indisponível.')
            : jsonResponse({ mensagemId: 99 }, 202)
        },
      }),
    )
    await montarApp(fetchFn, '/atendimento?id=1')
    const entrada = (await waitFor(() => {
      const el = document.querySelector('input[type=file]')
      expect(el).toBeTruthy()
      return el
    })) as HTMLInputElement
    await selecionar(entrada, arquivo('contrato.pdf', 'application/pdf'))
    const legenda = screen.getByLabelText('Legenda (opcional)') as HTMLTextAreaElement
    await waitFor(() => expect(legenda.disabled).toBe(false))
    await fireEvent.update(legenda, 'Contrato assinado')

    await fireEvent.click(screen.getByRole('button', { name: 'Enviar' }))
    expect(await screen.findByText(/O arquivo foi mantido/)).toBeTruthy()
    await fireEvent.click(screen.getByRole('button', { name: 'Enviar' }))
    await waitFor(() => expect(envios).toHaveLength(2))
    await fireEvent.update(legenda, 'Contrato revisado')
    await fireEvent.click(await screen.findByRole('button', { name: 'Enviar' }))
    await waitFor(() => expect(envios).toHaveLength(3))

    expect(envios[0]).toMatchObject({ nome: 'contrato.pdf', legenda: 'Contrato assinado' })
    expect(envios[1]!.chave).toBe(envios[0]!.chave)
    expect(envios[2]!.chave).not.toBe(envios[0]!.chave)
    await waitFor(() => expect(screen.queryByText('contrato.pdf')).toBeNull())
  })

  it('área Mídia filtra por tipo, pagina e trata erro com nova tentativa', async () => {
    let falhar = true
    const { fetchFn, chamadas } = apiFalsa(
      rotas([mensagem(1)], {
        [`GET ${BASE}/atendimentos/1/midia?somenteEste=false&tamanho=20`]: () => {
          if (falhar) {
            falhar = false
            return problema(503, 'INDISPONIVEL', 'Serviço indisponível.')
          }
          return jsonResponse({
            itens: [
              {
                id: 30,
                mensagemId: 1,
                atendimentoId: 1,
                direcao: 'ENTRADA',
                categoria: 'DOCUMENTO',
                nome: 'boleto.pdf',
                tamanho: 4096,
                situacao: 'DISPONIVEL',
                registradoEm: '2026-10-29T12:00:00Z',
              },
            ],
            anterior: 30,
          })
        },
        [`GET ${BASE}/atendimentos/1/midia?somenteEste=false&tamanho=20&antes=30`]: () =>
          jsonResponse({
            itens: [
              {
                id: 20,
                mensagemId: 1,
                atendimentoId: 1,
                direcao: 'SAIDA',
                categoria: 'IMAGEM',
                situacao: 'DISPONIVEL',
                registradoEm: '2026-10-29T11:00:00Z',
              },
            ],
          }),
        [`GET ${BASE}/atendimentos/1/midia?somenteEste=false&tamanho=20&categoria=AUDIO`]: () =>
          jsonResponse({ itens: [] }),
      }),
    )
    await montarApp(fetchFn, '/atendimento?id=1')
    const midia = await screen.findByRole('region', { name: 'Mídia' }).catch(() => null)
    expect(midia ?? document.querySelector('.midia')).toBeTruthy()
    await fireEvent.click(await screen.findByRole('button', { name: 'Tentar de novo' }))
    expect(await screen.findByText('boleto.pdf')).toBeTruthy()
    await fireEvent.click(screen.getByRole('button', { name: 'Carregar mais' }))
    expect(await screen.findByText('Imagem sem nome')).toBeTruthy()
    await fireEvent.click(screen.getByRole('button', { name: 'Áudios' }))
    expect(await screen.findByText('Nenhum anexo nesta conversa.')).toBeTruthy()
    expect(chamadas).toContain(
      `GET ${BASE}/atendimentos/1/midia?somenteEste=false&tamanho=20&categoria=AUDIO`,
    )
  })

  it('mensagem antiga fora da página recente passa de Aceita para Entregue sem perder páginas', async () => {
    let consultas = 0
    const { fetchFn, chamadas } = apiFalsa(
      rotas(
        [],
        {
          [`POST ${BASE}/atendimentos/1/assuncao`]: () => new Response(null, { status: 204 }),
          [`GET ${LINHA}`]: () => {
            consultas++
            return jsonResponse({
              itens: [mensagem(50, { texto: 'recente' })],
              anterior: '50000.1.50',
            })
          },
          [`GET ${LINHA}&antes=50000.1.50`]: () =>
            jsonResponse({
              itens: [
                mensagem(10, {
                  direcao: 'SAIDA',
                  texto: 'antiga enviada',
                  situacao: 'ACEITA',
                  atualizadoEm: '2026-10-29T12:00:00Z',
                }),
              ],
            }),
          [`GET ${BASE}/atendimentos/1/linha-do-tempo/situacoes?somenteEste=false&ids=10`]: () =>
            jsonResponse([{ id: 10, situacao: 'ENTREGUE', atualizadoEm: '2026-10-29T12:05:00Z' }]),
        },
        'AGUARDANDO',
      ),
    )
    await montarApp(fetchFn, '/atendimento?id=1')
    expect(await screen.findByText('recente')).toBeTruthy()
    await fireEvent.click(screen.getByRole('button', { name: 'Carregar mensagens anteriores' }))
    expect(await screen.findByText('antiga enviada')).toBeTruthy()
    expect(screen.getByText(/Aceita pelo WhatsApp/)).toBeTruthy()

    // Uma atualização (aqui provocada por uma ação) confere as mensagens antigas em lote.
    await fireEvent.click(screen.getByRole('button', { name: 'Assumir' }))
    await waitFor(() => expect(screen.getByText(/· Entregue/)).toBeTruthy())
    expect(screen.getByText('antiga enviada')).toBeTruthy()
    expect(screen.getByText('recente')).toBeTruthy()
    expect(consultas).toBeGreaterThan(1)
    expect(chamadas.filter((c) => c.includes('antes=')).length).toBe(1)
  })
})
