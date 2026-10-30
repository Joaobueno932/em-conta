/**
 * Cliente HTTP da aplicação. Todas as chamadas vão para a mesma origem (`/api`), levando o cookie
 * de sessão HttpOnly automaticamente. Nenhum token ou senha é guardado em armazenamento do navegador.
 *
 * CSRF: o backend emite o cookie legível `XSRF-TOKEN`; em métodos mutáveis o valor é repetido no
 * cabeçalho `X-XSRF-TOKEN`. Sem o cookie, o cliente o obtém em `GET /v1/auth/csrf` antes de enviar;
 * se o servidor recusar o token (`CSRF_INVALIDO`, ex.: renovado no login/logout), renova e repete uma vez.
 */

export interface ProblemDetail {
  type?: string
  title?: string
  status?: number
  detail?: string
  instance?: string
  codigo?: string
  [extension: string]: unknown
}

export type ApiErrorKind = 'network' | 'http' | 'invalid-response'

export class ApiError extends Error {
  override name = 'ApiError'

  constructor(
    readonly kind: ApiErrorKind,
    message: string,
    readonly status: number | null = null,
    readonly problem: ProblemDetail | null = null,
    /** Corpo JSON da resposta de erro, quando houver. */
    readonly body: unknown = null,
    /** Segundos indicados em `Retry-After` (respostas 429). */
    readonly retryAfterSeconds: number | null = null,
  ) {
    super(message)
  }

  /** Código estável enviado pelo backend em Problem Details (ex.: `CREDENCIAIS_INVALIDAS`). */
  get codigo(): string | undefined {
    return typeof this.problem?.codigo === 'string' ? this.problem.codigo : undefined
  }
}

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'

export interface RequestOptions {
  body?: unknown
  signal?: AbortSignal
  /** Não aciona `onSessaoInvalida` em 401 (ex.: a própria consulta de sessão e o login). */
  ignorarSessaoInvalida?: boolean
}

export interface HttpClient {
  request<T>(method: HttpMethod, path: string, options?: RequestOptions): Promise<T>
  get<T>(path: string, options?: Omit<RequestOptions, 'body'>): Promise<T>
}

export interface HttpClientOptions {
  /** Chamado quando a API responde 401 por sessão ausente, expirada ou encerrada. */
  onSessaoInvalida?: (error: ApiError) => void
}

const MUTATING_METHODS = new Set<HttpMethod>(['POST', 'PUT', 'PATCH', 'DELETE'])
const CSRF_COOKIE = 'XSRF-TOKEN'
const CSRF_HEADER = 'X-XSRF-TOKEN'
const CODIGOS_SESSAO = new Set(['NAO_AUTENTICADO', 'SESSAO_EXPIRADA', 'SESSAO_ENCERRADA'])

export function readCookie(name: string, cookies: string = document.cookie): string | null {
  for (const part of cookies.split(';')) {
    const [key, ...value] = part.trim().split('=')
    if (key === name) {
      return decodeURIComponent(value.join('='))
    }
  }
  return null
}

function isJson(response: Response): boolean {
  const contentType = response.headers.get('content-type') ?? ''
  return contentType.includes('application/json') || contentType.includes('+json')
}

async function readJson(response: Response): Promise<unknown> {
  try {
    return await response.json()
  } catch {
    return null
  }
}

function retryAfter(response: Response): number | null {
  const valor = Number(response.headers.get('retry-after'))
  return Number.isFinite(valor) && valor > 0 ? valor : null
}

export function createHttpClient(
  basePath: string,
  fetchFn: typeof fetch = (...args) => fetch(...args),
  clientOptions: HttpClientOptions = {},
): HttpClient {
  async function enviar(
    method: HttpMethod,
    path: string,
    options: RequestOptions,
    csrfToken: string | null,
  ): Promise<Response> {
    const headers = new Headers({ Accept: 'application/json, application/problem+json' })
    // FormData (envio de arquivo): o navegador define o multipart com a fronteira.
    const formulario = options.body instanceof FormData
    if (options.body !== undefined && !formulario) {
      headers.set('Content-Type', 'application/json')
    }
    if (csrfToken) {
      headers.set(CSRF_HEADER, csrfToken)
    }
    try {
      return await fetchFn(`${basePath}${path}`, {
        method,
        headers,
        body:
          options.body === undefined
            ? undefined
            : formulario
              ? (options.body as FormData)
              : JSON.stringify(options.body),
        credentials: 'same-origin',
        signal: options.signal,
      })
    } catch (cause) {
      if (cause instanceof DOMException && cause.name === 'AbortError') {
        throw cause
      }
      throw new ApiError('network', 'Falha de rede ao acessar a API.')
    }
  }

  async function obterCsrf(): Promise<string | null> {
    await enviar('GET', '/v1/auth/csrf', {}, null).catch(() => undefined)
    return readCookie(CSRF_COOKIE)
  }

  async function erroDe(response: Response): Promise<ApiError> {
    const body = isJson(response) ? await readJson(response) : null
    const problem =
      (response.headers.get('content-type') ?? '').includes('application/problem+json') &&
      typeof body === 'object' &&
      body !== null
        ? (body as ProblemDetail)
        : null
    return new ApiError(
      'http',
      `A API respondeu HTTP ${response.status}.`,
      response.status,
      problem,
      body,
      retryAfter(response),
    )
  }

  async function request<T>(
    method: HttpMethod,
    path: string,
    options: RequestOptions = {},
  ): Promise<T> {
    const mutavel = MUTATING_METHODS.has(method)
    let csrfToken = mutavel ? (readCookie(CSRF_COOKIE) ?? (await obterCsrf())) : null

    let response = await enviar(method, path, options, csrfToken)
    if (!response.ok) {
      let error = await erroDe(response)
      if (mutavel && error.status === 403 && error.codigo === 'CSRF_INVALIDO') {
        csrfToken = await obterCsrf()
        response = await enviar(method, path, options, csrfToken)
        if (response.ok) {
          return lerSucesso<T>(response)
        }
        error = await erroDe(response)
      }
      if (
        error.status === 401 &&
        CODIGOS_SESSAO.has(error.codigo ?? '') &&
        !options.ignorarSessaoInvalida
      ) {
        clientOptions.onSessaoInvalida?.(error)
      }
      throw error
    }
    return lerSucesso<T>(response)
  }

  async function lerSucesso<T>(response: Response): Promise<T> {
    if (response.status === 204) {
      return undefined as T
    }
    if (!isJson(response)) {
      throw new ApiError('invalid-response', 'A API não respondeu JSON.', response.status)
    }
    const body = await readJson(response)
    if (body === null) {
      throw new ApiError('invalid-response', 'A API respondeu JSON inválido.', response.status)
    }
    return body as T
  }

  return {
    request,
    get: (path, options) => request('GET', path, options),
  }
}
