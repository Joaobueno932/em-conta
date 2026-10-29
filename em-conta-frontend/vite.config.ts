import { fileURLToPath, URL } from 'node:url'
import process from 'node:process'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'

// Backend usado pelo proxy em desenvolvimento/preview. Variável lida só pelo Node (sem prefixo
// VITE_), portanto não vai para o bundle.
const apiTarget = process.env.EM_CONTA_API_PROXY_TARGET ?? 'http://localhost:8080'
const proxy = { '/api': { target: apiTarget } }

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue(), vueDevTools()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: { port: 5173, strictPort: true, proxy },
  preview: { port: 4173, strictPort: true, proxy },
})
