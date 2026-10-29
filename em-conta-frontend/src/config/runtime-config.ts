/**
 * Configuração pública carregada em runtime de `/config.json`, antes de montar a aplicação.
 * O arquivo é público: nunca coloque credenciais, tokens ou URLs internas nele.
 */
export interface RuntimeConfig {
  /** Nome do ambiente exibido na página técnica (ex.: local, homologacao, producao). */
  environment: string
  /** Caminho da API na mesma origem do frontend (ex.: /api). */
  apiBasePath: string
}

export class RuntimeConfigError extends Error {
  override name = 'RuntimeConfigError'
}

const ALLOWED_KEYS = new Set<string>(['environment', 'apiBasePath'])
const SAME_ORIGIN_PATH = /^\/(?!\/)[A-Za-z0-9/_-]*$/

export function parseRuntimeConfig(raw: unknown): RuntimeConfig {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) {
    throw new RuntimeConfigError('config.json deve conter um objeto JSON.')
  }

  const unknownKeys = Object.keys(raw).filter((key) => !ALLOWED_KEYS.has(key))
  if (unknownKeys.length > 0) {
    throw new RuntimeConfigError(
      `config.json contém chaves não permitidas: ${unknownKeys.join(', ')}.`,
    )
  }

  const { environment, apiBasePath } = raw as Record<string, unknown>
  if (typeof environment !== 'string' || environment.trim() === '') {
    throw new RuntimeConfigError('"environment" deve ser um texto não vazio.')
  }
  if (typeof apiBasePath !== 'string' || !SAME_ORIGIN_PATH.test(apiBasePath)) {
    throw new RuntimeConfigError('"apiBasePath" deve ser um caminho na mesma origem, como "/api".')
  }

  return { environment: environment.trim(), apiBasePath: apiBasePath.replace(/\/+$/, '') }
}

export async function loadRuntimeConfig(fetchFn: typeof fetch = fetch): Promise<RuntimeConfig> {
  let response: Response
  try {
    response = await fetchFn('/config.json', { cache: 'no-store', credentials: 'omit' })
  } catch {
    throw new RuntimeConfigError('Não foi possível carregar /config.json.')
  }
  if (!response.ok) {
    throw new RuntimeConfigError(
      `Não foi possível carregar /config.json (HTTP ${response.status}).`,
    )
  }

  let body: unknown
  try {
    body = await response.json()
  } catch {
    throw new RuntimeConfigError('/config.json não contém JSON válido.')
  }
  return parseRuntimeConfig(body)
}
