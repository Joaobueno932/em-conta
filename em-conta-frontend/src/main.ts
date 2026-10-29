import { createApp } from 'vue'

import './app/estilos.css'
import App from './app/App.vue'
import { aoInvalidarSessao, instalarGuardas } from './app/guards'
import { createAppQueryClient, installProviders } from './app/providers'
import { createAppRouter } from './app/router'
import { loadRuntimeConfig } from './config/runtime-config'
import { createHttpClient } from './shared/api/http-client'

async function bootstrap(root: HTMLElement): Promise<void> {
  try {
    const config = await loadRuntimeConfig()
    const router = createAppRouter()
    const queryClient = createAppQueryClient()
    const httpClient = createHttpClient(config.apiBasePath, undefined, {
      onSessaoInvalida: aoInvalidarSessao(router, queryClient),
    })
    instalarGuardas(router, queryClient, httpClient)

    const app = createApp(App)
    installProviders(app, { config, router, queryClient, httpClient })
    app.mount(root)
  } catch (error) {
    // A aplicação não inicia com configuração inválida.
    const message = error instanceof Error ? error.message : String(error)
    const notice = document.createElement('p')
    notice.setAttribute('role', 'alert')
    notice.textContent = `Não foi possível iniciar o Em Conta: ${message}`
    root.replaceChildren(notice)
    console.error(error)
  }
}

const root = document.getElementById('app')
if (root) {
  void bootstrap(root)
}
