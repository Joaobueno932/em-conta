import { flushPromises } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { defineComponent, h, ref } from 'vue'

import { apiFalsa, problema, type Rotas } from '@/test/api-falsa'
import { jsonResponse, renderWithProviders } from '@/test/render-with-providers'

import type { Item } from '../atendimento-api'
import { proximoLote } from '../linha-do-tempo-estado'
import { MAXIMO_PAGINAS_LACUNA, useLinhaDoTempo } from '../use-linha-do-tempo'

const BASE = '/api/v1/atendimento/atendimentos'
const LINHA = (id: number) => `${BASE}/${id}/linha-do-tempo?somenteEste=false&tamanho=50`

function mensagem(id: number, extra: Partial<Item> = {}): Item {
  return {
    tipo: 'MENSAGEM',
    id,
    cursor: `${String(id).padStart(6, '0')}.1.${id}`,
    ocorridoEm: '2026-10-29T12:00:00Z',
    atendimentoId: 1,
    direcao: 'SAIDA',
    tipoMensagem: 'TEXTO',
    texto: `m${id}`,
    situacao: 'ENTREGUE',
    atualizadoEm: '2026-10-29T12:00:00Z',
    ...extra,
  } as Item
}

/** Intervalo de mensagens [de, ate] em ordem cronológica. */
const faixa = (de: number, ate: number, extra: (id: number) => Partial<Item> = () => ({})) =>
  Array.from({ length: ate - de + 1 }, (_, k) => mensagem(de + k, extra(de + k)))

type Linha = ReturnType<typeof useLinhaDoTempo>

/** Hospeda o composable; o teste controla cada ciclo (refetch) e a troca de conversa. */
async function montar(rotas: Rotas, idInicial = 1) {
  const id = ref(idInicial)
  let linha!: Linha
  const Hospede = defineComponent({
    setup() {
      linha = useLinhaDoTempo(id, false, true)
      return () =>
        h(
          'ol',
          linha.itens.value.map((i) => h('li', { 'data-id': i.id }, `${i.texto}:${i.situacao}`)),
        )
    },
  })
  const { fetchFn, chamadas } = apiFalsa(rotas)
  await renderWithProviders(Hospede, fetchFn)
  await flushPromises()
  const ciclo = async () => {
    await linha.recente.refetch()
    await flushPromises()
    await flushPromises()
  }
  return { linha: () => linha, id, chamadas, ciclo }
}

const textos = (l: Linha) => l.itens.value.map((i) => i.id)

describe('rodízio das situações', () => {
  it('percorre todos os elegíveis em ⌈n/limite⌉ lotes, mesmo com os mais novos sempre elegíveis', () => {
    const elegiveis = faixa(1, 230)
    const vistos = new Set<number>()
    let ponteiro: string | null = null
    for (let ciclo = 0; ciclo < 3; ciclo++) {
      const r = proximoLote(elegiveis, ponteiro, 100)
      expect(r.lote.length).toBeLessThanOrEqual(100)
      r.lote.forEach((i) => vistos.add(i.id))
      ponteiro = r.ponteiro
    }
    expect(vistos.size).toBe(230)
  })

  it('mensagem antiga muda no servidor e chega à tela em ciclos limitados, sem regredir', async () => {
    // 230 mensagens carregadas fora da página recente: as mais novas continuam ENTREGUE/DESCONHECIDA.
    const antigas = faixa(1, 230, (id) => ({
      situacao: id === 1 ? 'ACEITA' : id % 2 ? 'DESCONHECIDA' : 'ENTREGUE',
      anexo: id === 2 ? { id: 90, categoria: 'IMAGEM', situacao: 'PROCESSANDO' } : undefined,
    })) as Item[]
    const lotes: number[][] = []
    const { linha, ciclo } = await montar({
      [`GET ${LINHA(1)}`]: () =>
        jsonResponse({
          itens: [mensagem(500, { texto: 'recente' })],
          anterior: mensagem(500).cursor,
        }),
      [`GET ${LINHA(1)}&antes=${mensagem(500).cursor}`]: () => jsonResponse({ itens: antigas }),
      [`GET ${BASE}/1/linha-do-tempo/situacoes`]: (_init, url) => {
        const ids = new URL(url!, 'http://x').searchParams.get('ids')!.split(',').map(Number)
        lotes.push(ids)
        return jsonResponse(
          ids.flatMap((i) => {
            if (i === 1) return [{ id: 1, situacao: 'LIDA', atualizadoEm: '2026-10-29T12:10:00Z' }]
            if (i === 2)
              return [
                {
                  id: 2,
                  situacao: 'ENTREGUE',
                  atualizadoEm: '2026-10-29T12:05:00Z',
                  anexo: { id: 90, categoria: 'IMAGEM', situacao: 'DISPONIVEL' },
                },
              ]
            // Resposta atrasada (mais antiga que a carregada): não pode voltar o estado.
            if (i === 4)
              return [{ id: 4, situacao: 'ACEITA', atualizadoEm: '2026-10-29T11:00:00Z' }]
            return []
          }),
        )
      },
    })
    await linha().carregarAnteriores()
    await flushPromises()
    expect(textos(linha())).toHaveLength(231)

    for (let c = 0; c < 3 && !lotes.flat().includes(1); c++) await ciclo()
    expect(lotes.length).toBeLessThanOrEqual(3)
    expect(lotes.every((l) => l.length <= 100)).toBe(true)
    const porId = new Map(linha().itens.value.map((i) => [i.id, i]))
    expect(porId.get(1)!.situacao).toBe('LIDA')
    expect(porId.get(2)!.anexo!.situacao).toBe('DISPONIVEL')
    expect(porId.get(4)!.situacao).toBe('ENTREGUE')
    expect(textos(linha())).toHaveLength(231)
  })
})

describe('lacunas da linha do tempo', () => {
  /** Página recente: na 1ª consulta termina em `primeira`; depois, em `segunda` (com lacuna). */
  function recente(primeira: number, segunda: number[]) {
    let n = 0
    return () => {
      const fim = n === 0 ? primeira : segunda[Math.min(n - 1, segunda.length - 1)]!
      n++
      return jsonResponse({ itens: [mensagem(fim)], anterior: mensagem(fim).cursor })
    }
  }

  /** Página anterior a `antes`, com `tamanho` itens, terminando no início em 1. */
  function paginaAntes(antes: number, tamanho: number) {
    const de = Math.max(1, antes - tamanho)
    const itens = faixa(de, antes - 1)
    return jsonResponse(de > 1 ? { itens, anterior: itens[0]!.cursor } : { itens })
  }

  it('falha no meio mantém o progresso e a nova tentativa continua do cursor salvo', async () => {
    let falhas = 0
    const pedidos: number[] = []
    const rotas: Rotas = {
      [`GET ${LINHA(1)}`]: recente(100, [160]),
    }
    // Lacuna 101..159 em páginas de 10; a 3ª página falha uma vez.
    for (let antes = 160; antes > 100; antes -= 10) {
      const alvo = antes
      rotas[`GET ${LINHA(1)}&antes=${mensagem(alvo).cursor}`] = () => {
        pedidos.push(alvo)
        if (alvo === 140 && falhas++ === 0) return problema(503, 'INDISPONIVEL', 'Indisponível.')
        return paginaAntes(alvo, 10)
      }
    }
    const { linha, ciclo } = await montar(rotas)
    await ciclo()
    expect(linha().erroLacuna.value).toBeTruthy()
    expect(linha().lacunaPendente.value).toBe(true)
    // O que já veio fica (150..159 e 140..149), sem duplicar.
    expect(textos(linha())).toEqual(expect.arrayContaining([150, 159, 141]))
    await linha().recuperarLacunas()
    await flushPromises()
    expect(pedidos).toEqual([160, 150, 140, 140, 130, 120, 110])
    expect(linha().lacunaPendente.value).toBe(false)
    expect(linha().erroLacuna.value).toBeNull()
    const ids = textos(linha())
    expect(ids).toEqual([...new Set(ids)].sort((a, b) => a - b))
    expect(ids.filter((i) => i >= 100 && i <= 160)).toHaveLength(61)
  })

  it('falha na primeira página intermediária mantém a lacuna inteira', async () => {
    let falhou = false
    const { linha, ciclo } = await montar({
      [`GET ${LINHA(1)}`]: recente(10, [30]),
      [`GET ${LINHA(1)}&antes=${mensagem(30).cursor}`]: () => {
        if (!falhou) {
          falhou = true
          return problema(503, 'INDISPONIVEL', 'Indisponível.')
        }
        return paginaAntes(30, 25)
      },
    })
    await ciclo()
    expect(linha().lacunaPendente.value).toBe(true)
    expect(linha().erroLacuna.value).toBeTruthy()
    await ciclo() // a próxima consulta periódica retoma sozinha
    expect(linha().lacunaPendente.value).toBe(false)
    // A página da lacuna (5..29) junta-se ao que já estava (10 e 30), sem duplicar.
    expect(textos(linha())).toEqual(faixa(5, 30).map((m) => m.id))
  })

  it('lacuna maior que o limite por ciclo continua no ciclo seguinte; novas mensagens viram outra lacuna', async () => {
    const tamanho = 5
    const paginas = MAXIMO_PAGINAS_LACUNA + 5
    const fim = 1000 + paginas * tamanho
    const rotas: Rotas = { [`GET ${LINHA(1)}`]: recente(1000, [fim, fim, fim + 30]) }
    for (let antes = fim; antes > 1000; antes -= tamanho) {
      const alvo = antes
      rotas[`GET ${LINHA(1)}&antes=${mensagem(alvo).cursor}`] = () => paginaAntes(alvo, tamanho)
    }
    for (let antes = fim + 30; antes > fim; antes -= 10) {
      const alvo = antes
      rotas[`GET ${LINHA(1)}&antes=${mensagem(alvo).cursor}`] = () => paginaAntes(alvo, 10)
    }
    const { linha, ciclo, chamadas } = await montar(rotas)
    await ciclo()
    expect(chamadas.filter((c) => c.includes('&antes='))).toHaveLength(MAXIMO_PAGINAS_LACUNA)
    expect(linha().lacunaPendente.value).toBe(true)
    await ciclo() // termina a primeira lacuna
    await ciclo() // chega a página nova com outra lacuna (mensagens durante a recuperação)
    await ciclo()
    expect(linha().lacunaPendente.value).toBe(false)
    const ids = textos(linha())
    expect(ids.filter((i) => i >= 1000)).toEqual(
      Array.from({ length: fim + 30 - 999 }, (_, k) => 1000 + k),
    )
  })

  it('troca de conversa com recuperação pendente não mistura resultados', async () => {
    let liberar!: () => void
    const bloqueio = new Promise<void>((ok) => (liberar = ok))
    const { linha, ciclo, id } = await montar({
      [`GET ${LINHA(1)}`]: recente(10, [30]),
      [`GET ${LINHA(1)}&antes=${mensagem(30).cursor}`]: async () => {
        await bloqueio
        return paginaAntes(30, 25)
      },
      [`GET ${LINHA(2)}`]: () =>
        jsonResponse({ itens: [mensagem(900, { texto: 'outra conversa' })] }),
    })
    await linha().recente.refetch()
    await flushPromises()
    id.value = 2
    await flushPromises()
    liberar()
    await flushPromises()
    await ciclo()
    expect(textos(linha())).toEqual([900])
    expect(linha().lacunaPendente.value).toBe(false)
  })
})
