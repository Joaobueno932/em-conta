// Atualiza a cópia versionada do contrato OpenAPI do backend e regenera os tipos TypeScript.
//
//   npm run contract:update -- <origem>
//
// <origem> pode ser uma URL (ex.: http://localhost:8080/v3/api-docs com o backend no profile local)
// ou um arquivo openapi.json (ex.: o contract/openapi.json de uma versão do em-conta-backend).
// A origem só é usada neste momento; o build do frontend depende apenas dos arquivos gerados.
import { execFileSync } from 'node:child_process'
import { readFile, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'

const OPENAPI_TYPESCRIPT = 'openapi-typescript@7.13.0'
const contractDir = fileURLToPath(new URL('../src/shared/api/contract/', import.meta.url))
const source = process.argv[2]

if (!source) {
  console.error('Informe a origem do contrato: npm run contract:update -- <url-ou-arquivo>')
  process.exit(1)
}

const raw = /^https?:\/\//.test(source)
  ? await (await fetch(source)).text()
  : await readFile(source, 'utf8')

const spec = JSON.parse(raw)
if (!spec.openapi || !spec.info?.version) {
  console.error('A origem não parece ser um documento OpenAPI válido.')
  process.exit(1)
}

await writeFile(`${contractDir}openapi.json`, `${JSON.stringify(spec, null, 2)}\n`)
await writeFile(
  `${contractDir}contract-source.json`,
  `${JSON.stringify(
    {
      producer: 'em-conta-backend',
      apiTitle: spec.info.title,
      apiVersion: spec.info.version,
      openapi: spec.openapi,
      generator: OPENAPI_TYPESCRIPT,
      updatedAt: new Date().toISOString(),
    },
    null,
    2,
  )}\n`,
)

// Executado isoladamente via npx: o gerador não é dependência do build.
// Usa o npm que executa o script (npm run), sem shell, com caminhos relativos ao contrato.
const npmCli = process.env.npm_execpath
if (!npmCli) {
  console.error('Execute por meio de: npm run contract:update -- <origem>')
  process.exit(1)
}
execFileSync(
  process.execPath,
  [
    npmCli,
    'exec',
    '--yes',
    `--package=${OPENAPI_TYPESCRIPT}`,
    '--',
    'openapi-typescript',
    'openapi.json',
    '--output',
    'schema.d.ts',
  ],
  { cwd: contractDir, stdio: 'inherit' },
)

console.log(
  `Contrato ${spec.info.title} ${spec.info.version} atualizado em src/shared/api/contract/.`,
)
