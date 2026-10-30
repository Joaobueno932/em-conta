import { compararCursor, type Item } from './atendimento-api'

/** Situações de mensagem que ainda podem mudar (pelo envio ou pelos webhooks de situação). */
const SITUACOES_ABERTAS = ['PENDENTE', 'ENVIANDO', 'ACEITA', 'ENVIADA', 'ENTREGUE', 'DESCONHECIDA']
/** Situações de anexo que ainda podem mudar (download ou upload em andamento). */
const ANEXOS_ABERTOS = ['RECEBENDO', 'PENDENTE', 'PROCESSANDO', 'FALHA_TEMPORARIA']

/** ENTREGUE ainda pode virar LIDA, e DESCONHECIDA pode ser reconciliada pelo webhook. */
export const sujeitaAMudanca = (i: Item) =>
  i.tipo === 'MENSAGEM' &&
  (SITUACOES_ABERTAS.includes(i.situacao ?? '') || ANEXOS_ABERTOS.includes(i.anexo?.situacao ?? ''))

/** Entre duas versões do mesmo item, fica a mais nova (resposta atrasada não volta o estado). */
export function maisNovo(atual: Item | undefined, novo: Item): Item {
  if (!atual?.atualizadoEm || !novo.atualizadoEm) return novo
  return Date.parse(novo.atualizadoEm) >= Date.parse(atual.atualizadoEm) ? novo : atual
}

/**
 * Próximo lote da conferência de situações, em rodízio: percorre os elegíveis do mais novo para o mais
 * antigo a partir do ponto onde o lote anterior parou e volta ao início quando chega ao fim. Com o
 * conjunto estável, cada elegível entra num lote em no máximo ⌈n / limite⌉ ciclos, mesmo que os mais
 * novos continuem elegíveis para sempre (ENTREGUE, DESCONHECIDA).
 */
export function proximoLote(
  elegiveis: Item[],
  ponteiro: string | null,
  limite: number,
): { lote: Item[]; ponteiro: string | null } {
  if (elegiveis.length === 0) return { lote: [], ponteiro: null }
  const ordenados = [...elegiveis].sort((a, b) => compararCursor(b.cursor, a.cursor))
  let inicio = ponteiro ? ordenados.findIndex((i) => compararCursor(i.cursor, ponteiro) < 0) : 0
  if (inicio < 0) inicio = 0
  const quantos = Math.min(limite, ordenados.length)
  const lote: Item[] = []
  for (let k = 0; k < quantos; k++) lote.push(ordenados[(inicio + k) % ordenados.length]!)
  return { lote, ponteiro: lote[lote.length - 1]!.cursor }
}

/**
 * Intervalo da linha do tempo ainda não carregado: tudo o que está antes de `antes` (cursor de
 * continuação, atualizado a cada página recuperada) e depois de `ate` (o mais novo que já estava
 * carregado quando a lacuna apareceu).
 */
export interface Lacuna {
  antes: string
  ate: string
}

/**
 * Aplica uma página recuperada a uma lacuna: termina quando a página alcança o que já estava carregado
 * (ou o início do histórico); senão a lacuna continua a partir do novo cursor.
 */
export function avancarLacuna(
  lacuna: Lacuna,
  pagina: { itens: Item[]; anterior?: string },
): Lacuna | null {
  const primeiro = pagina.itens[0]
  if (!primeiro || !pagina.anterior || compararCursor(primeiro.cursor, lacuna.ate) <= 0) return null
  return { antes: pagina.anterior, ate: lacuna.ate }
}
