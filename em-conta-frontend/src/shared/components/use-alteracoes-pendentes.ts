import { onBeforeUnmount, onMounted, type Ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'

export const AVISO_DESCARTE = 'Há alterações não salvas. Deseja sair e descartá-las?'

/**
 * Avisa antes de descartar um formulário alterado: ao navegar para outra rota (confirmação) e ao
 * fechar ou recarregar a aba (aviso nativo do navegador). Uma sessão encerrada pelo servidor leva
 * ao login sem perguntar, porque os dados não poderiam ser salvos de qualquer forma.
 */
export function useAlteracoesPendentes(alterado: Ref<boolean>, confirmar = window.confirm) {
  function aoFechar(evento: BeforeUnloadEvent) {
    if (alterado.value) evento.preventDefault()
  }

  onMounted(() => window.addEventListener('beforeunload', aoFechar))
  onBeforeUnmount(() => window.removeEventListener('beforeunload', aoFechar))

  onBeforeRouteLeave((to) => {
    if (!alterado.value || to.name === 'entrar') return true
    return confirmar(AVISO_DESCARTE)
  })
}
