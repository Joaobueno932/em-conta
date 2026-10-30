import { useQuery } from '@tanstack/vue-query'
import { computed, ref, shallowRef, watch, type Ref } from 'vue'

import { useHttpClient } from '@/app/injection'

import {
  chavesAtendimento,
  compararCursor,
  INTERVALO_MS,
  linhaDoTempo,
  MAXIMO_SITUACOES,
  situacoes,
  type Item,
  type PaginaLinhaDoTempo,
} from './atendimento-api'

/** Limite de páginas buscadas de uma vez para fechar uma lacuna (muitas mensagens entre duas consultas). */
const MAXIMO_PAGINAS_LACUNA = 20

const identidade = (i: Item) => `${i.tipo}-${i.id}`

/** Situações de mensagem que ainda podem mudar (pelo envio ou pelos webhooks de situação). */
const SITUACOES_ABERTAS = ['PENDENTE', 'ENVIANDO', 'ACEITA', 'ENVIADA', 'ENTREGUE', 'DESCONHECIDA']
/** Situações de anexo que ainda podem mudar (download ou upload em andamento). */
const ANEXOS_ABERTOS = ['RECEBENDO', 'PENDENTE', 'PROCESSANDO', 'FALHA_TEMPORARIA']

const sujeitaAMudanca = (i: Item) =>
  i.tipo === 'MENSAGEM' &&
  (SITUACOES_ABERTAS.includes(i.situacao ?? '') || ANEXOS_ABERTOS.includes(i.anexo?.situacao ?? ''))

/** Entre duas versões do mesmo item, fica a mais nova (resposta atrasada não volta o estado). */
function maisNovo(atual: Item | undefined, novo: Item): Item {
  if (!atual?.atualizadoEm || !novo.atualizadoEm) return novo
  return Date.parse(novo.atualizadoEm) >= Date.parse(atual.atualizadoEm) ? novo : atual
}

/**
 * Linha do tempo paginada por cursor. A página mais recente é consultada periodicamente (quando
 * `atualizar`) e traz mensagens novas e situações atualizadas; páginas anteriores são carregadas sob
 * demanda e ficam guardadas. Um item que sai da página recente continua na tela, e se chegarem mais
 * itens do que cabem numa página entre duas consultas, a lacuna é preenchida — nada some nem pula.
 */
export function useLinhaDoTempo(id: Ref<number>, somenteEste: boolean, atualizar: boolean) {
  const http = useHttpClient()
  const guardados = shallowRef(new Map<string, Item>())
  const carregouAnteriores = ref(false)
  const fimDoHistorico = ref(false)
  const carregandoAnteriores = ref(false)
  const erroAnteriores = ref<unknown>(null)

  const recente = useQuery({
    queryKey: computed(() => chavesAtendimento.linha(id.value, somenteEste)),
    // Cada resultado leva o atendimento a que pertence: ao trocar de conversa, a página antiga não se
    // mistura com a nova.
    queryFn: async () => {
      const de = id.value
      return { de, pagina: await linhaDoTempo(http, de, { somenteEste }) }
    },
    refetchInterval: atualizar ? INTERVALO_MS : false,
  })

  function guardar(itens: Item[]) {
    if (itens.length === 0) return
    const mapa = new Map(guardados.value)
    for (const item of itens) mapa.set(identidade(item), maisNovo(mapa.get(identidade(item)), item))
    guardados.value = mapa
  }

  const itens = computed(() => {
    const mapa = new Map(guardados.value)
    // A página recente tem as situações mais novas.
    const atual = recente.data.value
    if (atual?.de === id.value)
      for (const item of atual.pagina.itens)
        mapa.set(identidade(item), maisNovo(mapa.get(identidade(item)), item))
    return [...mapa.values()].sort((a, b) => compararCursor(a.cursor, b.cursor))
  })

  watch(id, () => {
    // Outra conversa: nada do que estava guardado vale.
    guardados.value = new Map()
    carregouAnteriores.value = false
    fimDoHistorico.value = false
    erroAnteriores.value = null
  })

  watch(
    () => recente.data.value,
    async (atual, antes) => {
      if (!atual || !antes || atual === antes || atual.de !== antes.de || atual.de !== id.value)
        return
      const { pagina } = atual
      const ultimoConhecido = [...guardados.value.values(), ...antes.pagina.itens].reduce<
        string | null
      >((maior, i) => (!maior || compararCursor(i.cursor, maior) > 0 ? i.cursor : maior), null)
      // A página anterior sai da janela recente mas continua na tela.
      guardar(antes.pagina.itens)
      const maisAntigo = pagina.itens[0]
      if (!ultimoConhecido || !maisAntigo || !pagina.anterior) return
      if (compararCursor(maisAntigo.cursor, ultimoConhecido) <= 0) return
      await preencherLacuna(pagina, ultimoConhecido)
    },
  )

  // A cada consulta periódica, as mensagens já carregadas fora da página recente e que ainda podem
  // mudar (ex.: "Aceita" esperando "Entregue", anexo processando) são conferidas em lote, até
  // MAXIMO_SITUACOES por vez, das mais recentes para as mais antigas. Páginas e posição não mudam.
  let conferindo = false
  watch(
    () => recente.dataUpdatedAt.value,
    async () => {
      const atual = recente.data.value
      if (!atualizar || conferindo || !atual || atual.de !== id.value) return
      const naPagina = new Set(atual.pagina.itens.map(identidade))
      const ids = [...guardados.value.values()]
        .filter((i) => sujeitaAMudanca(i) && !naPagina.has(identidade(i)))
        .sort((a, b) => compararCursor(b.cursor, a.cursor))
        .slice(0, MAXIMO_SITUACOES)
        .map((i) => i.id)
      if (ids.length === 0) return
      const idAtual = id.value
      conferindo = true
      try {
        const novas = await situacoes(http, idAtual, ids, somenteEste)
        if (id.value !== idAtual) return
        const mapa = new Map(guardados.value)
        for (const s of novas) {
          const chave = `MENSAGEM-${s.id}`
          const existente = mapa.get(chave)
          if (!existente) continue
          mapa.set(
            chave,
            maisNovo(existente, {
              ...existente,
              situacao: s.situacao as Item['situacao'],
              erroCodigo: s.erroCodigo,
              erroTitulo: s.erroTitulo,
              etapaFalha: s.etapaFalha as Item['etapaFalha'],
              atualizadoEm: s.atualizadoEm,
              anexo: s.anexo ?? existente.anexo,
            }),
          )
        }
        guardados.value = mapa
      } catch {
        // a próxima consulta periódica tenta de novo (401 segue o tratamento central de sessão)
      } finally {
        conferindo = false
      }
    },
  )

  async function preencherLacuna(pagina: PaginaLinhaDoTempo, ultimoConhecido: string) {
    const idAtual = id.value
    let cursor = pagina.anterior
    for (let i = 0; cursor && i < MAXIMO_PAGINAS_LACUNA; i++) {
      try {
        const anteriores = await linhaDoTempo(http, idAtual, { somenteEste, antes: cursor })
        if (id.value !== idAtual) return
        guardar(anteriores.itens)
        const primeiro = anteriores.itens[0]
        if (!primeiro || compararCursor(primeiro.cursor, ultimoConhecido) <= 0) return
        cursor = anteriores.anterior
      } catch {
        return // a próxima consulta periódica tenta de novo
      }
    }
  }

  const temAnteriores = computed(() =>
    carregouAnteriores.value
      ? !fimDoHistorico.value
      : recente.data.value?.de === id.value && !!recente.data.value.pagina.anterior,
  )

  async function carregarAnteriores() {
    const maisAntigo = itens.value[0]
    if (!maisAntigo || carregandoAnteriores.value || !temAnteriores.value) return
    const idAtual = id.value
    carregandoAnteriores.value = true
    erroAnteriores.value = null
    try {
      const pagina = await linhaDoTempo(http, idAtual, { somenteEste, antes: maisAntigo.cursor })
      if (id.value !== idAtual) return
      guardar(pagina.itens)
      carregouAnteriores.value = true
      fimDoHistorico.value = !pagina.anterior
    } catch (erro) {
      erroAnteriores.value = erro
    } finally {
      carregandoAnteriores.value = false
    }
  }

  return {
    recente,
    itens,
    temAnteriores,
    carregandoAnteriores,
    erroAnteriores,
    carregarAnteriores,
  }
}
