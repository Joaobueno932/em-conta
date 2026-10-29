# Versões do frontend

Consultadas em 28/10/2026 no registro npm. O projeto foi gerado com `create-vue@3.24.0`, e as
versões resolvidas foram fixadas sem `^`/`~` (`.npmrc`: `save-exact=true`), com
`package-lock.json` versionado.

| Componente | Versão | Observação |
|---|---|---|
| Node.js | 24 LTS (testado com 24.19.0; imagem `node:24.22.1-alpine`) | `engines` e `engine-strict` |
| npm | 11.17.0 | `packageManager`; já incluso no Node, sem ferramenta extra |
| Vue | 3.5.44 | |
| Vue Router | 5.4.0 | |
| Pinia | 4.1.0 | |
| TanStack Vue Query | 5.105.0 | |
| Vite | 8.3.5 | |
| TypeScript | 6.0.3 | Linha definida pelo create-vue 3.24. O TS 7 e o Vitest 5 já existem, mas foi mantido o conjunto que o gerador oficial combina; atualizar só após confirmar suporte de vue-tsc e plugins |
| vue-tsc | 3.3.13 | |
| Vitest | 4.1.11 | Linha definida pelo create-vue 3.24 |
| @testing-library/vue | 8.1.0 | com `@testing-library/jest-dom` 7.0.1 |
| Playwright | 1.64.1 | Somente Chromium |
| ESLint / oxlint / Prettier | 10.13.0 / 1.82.0 / 3.9.6 | Configuração do create-vue |
| openapi-typescript | 7.13.0 | Só no `contract:update`, via `npm exec` (pede TS 5 como peer) |
| Nginx (imagem) | 1.30.6-alpine | Linha estável |

**Por que npm e não pnpm**: o npm já vem com o Node, tem lockfile determinístico e reduz uma
ferramenta para quem mantém o projeto sozinho. Para migrar no futuro: `corepack use pnpm@<versão>`
e trocar `npm ci` por `pnpm install --frozen-lockfile` nos scripts e na documentação.
