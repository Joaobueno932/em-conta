import '@testing-library/jest-dom/vitest'

import { cleanup } from '@testing-library/vue'
import { afterEach } from 'vitest'

// Sem `globals: true` no Vitest, a limpeza automática da Testing Library não é registrada.
afterEach(() => cleanup())

// O jsdom ainda não implementa o modo modal do <dialog>: o essencial para os testes é abrir e fechar.
if (!HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function () {
    this.open = true
  }
  HTMLDialogElement.prototype.close = function () {
    this.open = false
    this.dispatchEvent(new Event('close'))
  }
}
