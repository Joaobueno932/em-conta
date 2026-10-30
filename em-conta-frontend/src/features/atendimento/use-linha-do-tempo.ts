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
} from './atendimento-api'
import {
  avancarLacuna,
  maisNovo,
  proximoLote,
  sujeitaAMudanca,
  type Lacuna,
} from './linha-do-tempo-estado'

/** Páginas recuperadas por ciclo ao fechar lacunas; o restante continua no próximo ciclo. */
export const MAXIMO_PAGINAS_LACUNA = 20

const identidade = (i: Item) => `${i.tipo}-${i.id}`

/**
 * Linha do tempo paginada por cursor. A página mais recente é consultada periodicamente (quando
 * `atualizar`) e traz mensagens novas e situações atualizadas; páginas anteriores são carregadas sob
 * demanda e ficam guardadas. Um item que sai da página recente continua na tela.
 *
 * Se chegarem mais itens do que cabem numa página entre duas consultas, o intervalo que faltou vira uma
 * lacuna explícita (com cursor de continuação) e é recuperado em até {@link MAXIMO_PAGINAS_LACUNA}
 * páginas por ciclo; falha de rede mantém a lacuna e o progresso, mostra o erro e tenta de novo.
 */
export function useLinhaDoTempo(id: Ref<number>, somenteEste: boolean, atualizar: boolean) {
  const http = useHttpClient()
  const guardados = shallowRef(new Map<string, Item>())
  const carregouAnteriores = ref(false)
  const fimDoHistorico = ref(false)
  const carregandoAnteriores = ref(false)
  const erroAnteriores = ref<unknown>(null)
  const lacunas = ref<Lacuna[]>([])
  const erroLacuna = ref<unknown>(null)
  const recuperandoLacunas = ref(false)

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

  // Estado do rodízio da conferência de situações (por conversa).
  let ponteiroSituacoes: string | null = null
  let conferindo = false

  watch(id, () => {
    // Outra conversa: nada do que estava guardado ou pendente vale.
    guardados.value = new Map()
    carregouAnteriores.value = false
    fimDoHistorico.value = false
    erroAnteriores.value = null
    lacunas.value = []
    erroLacuna.value = null
    ponteiroSituacoes = null
  })

  watch(
    () => recente.data.value,
    (atual, antes) => {
      if (!atual || !antes || atual === antes || atual.de !== antes.de || atual.de !== id.value)
        return
      const { pagina } = atual
      const ultimoConhecido = [...guardados.value.values(), ...antes.pagina.itens].reduce<
        string | null
      >((maior, i) => (!maior || compararCursor(i.cursor, maior) > 0 ? i.cursor : maior), null)
      // A página anterior sai da janela recente mas continua na tela.
      guardar(antes.pagina.itens)
      const maisAntigo = pagina.itens[0]
      if (
        ultimoConhecido &&
        maisAntigo &&
        pagina.anterior &&
        compararCursor(maisAntigo.cursor, ultimoConhecido) > 0
      ) {
        lacunas.value = [...lacunas.value, { antes: pagina.anterior, ate: ultimoConhecido }]
      }
    },
  )

  // A cada consulta periódica: continua lacunas pendentes e confere um lote de situações.
  watch(
    () => recente.dataUpdatedAt.value,
    () => {
      if (lacunas.value.length > 0) void recuperarLacunas()
      if (atualizar) void conferirSituacoes()
    },
  )

  /**
   * Recupera as lacunas pendentes, no máximo {@link MAXIMO_PAGINAS_LACUNA} páginas por chamada (uma
   * recuperação por vez). Cada página bem-sucedida avança o cursor da lacuna, então uma falha no meio
   * não perde o que já foi carregado nem deixa o intervalo restante esquecido.
   */
  async function recuperarLacunas() {
    if (recuperandoLacunas.value) return
    const idAtual = id.value
    recuperandoLacunas.value = true
    try {
      for (
        let paginas = 0;
        paginas < MAXIMO_PAGINAS_LACUNA && lacunas.value.length > 0;
        paginas++
      ) {
        const lacuna = lacunas.value[0]!
        const pagina = await linhaDoTempo(http, idAtual, { somenteEste, antes: lacuna.antes })
        if (id.value !== idAtual) return
        guardar(pagina.itens)
        const restante = avancarLacuna(lacuna, pagina)
        lacunas.value = restante ? [restante, ...lacunas.value.slice(1)] : lacunas.value.slice(1)
      }
      erroLacuna.value = null
    } catch (erro) {
      if (id.value === idAtual) erroLacuna.value = erro
    } finally {
      recuperandoLacunas.value = false
    }
  }

  /**
   * Confere, em rodízio, até {@link MAXIMO_SITUACOES} mensagens já carregadas fora da página recente e
   * ainda sujeitas a mudança (uma requisição por ciclo, sem sobreposição). Respostas atrasadas não
   * voltam o estado ({@link maisNovo}); páginas e posição de leitura não mudam.
   */
  async function conferirSituacoes() {
    const atual = recente.data.value
    if (conferindo || !atual || atual.de !== id.value) return
    const naPagina = new Set(atual.pagina.itens.map(identidade))
    const elegiveis = [...guardados.value.values()].filter(
      (i) => sujeitaAMudanca(i) && !naPagina.has(identidade(i)),
    )
    const { lote, ponteiro } = proximoLote(elegiveis, ponteiroSituacoes, MAXIMO_SITUACOES)
    if (lote.length === 0) return
    const idAtual = id.value
    conferindo = true
    try {
      const novas = await situacoes(
        http,
        idAtual,
        lote.map((i) => i.id),
        somenteEste,
      )
      if (id.value !== idAtual) return
      ponteiroSituacoes = ponteiro
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
      // o mesmo lote é tentado no próximo ciclo (401 segue o tratamento central de sessão)
    } finally {
      conferindo = false
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
    /** Há mensagens recentes ainda não carregadas (entre o que já estava na tela e a página nova). */
    lacunaPendente: computed(() => lacunas.value.length > 0),
    recuperandoLacunas,
    erroLacuna,
    recuperarLacunas,
  }
}
