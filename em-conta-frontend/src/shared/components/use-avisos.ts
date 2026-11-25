import { readonly, ref } from 'vue'

/**
 * Avisos flutuantes de confirmação ("Dados atualizados", "Usina cadastrada"), no canto superior
 * direito da área autenticada. Só confirmam operações que o servidor já concluiu; erros continuam
 * nos banners da própria página, perto do que falhou.
 */
export interface AvisoFlutuante {
  id: number
  titulo: string
  texto?: string
}

const DURACAO_MS = 6000
const avisos = ref<AvisoFlutuante[]>([])
let proximo = 1

export function fecharAviso(id: number) {
  avisos.value = avisos.value.filter((a) => a.id !== id)
}

export function avisar(titulo: string, texto?: string) {
  const id = proximo++
  avisos.value = [...avisos.value, { id, titulo, texto }]
  setTimeout(() => fecharAviso(id), DURACAO_MS)
}

export const avisosAtivos = readonly(avisos)
